package app.reflex.e2e

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Condition
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.regex.Pattern

/**
 * The device as a user (and the test script) sees it: the app is found by its accessibility identifiers
 * (Compose test tags exposed as resource ids, docs/health/E2E.md) and contract literals; system UI is driven,
 * never bypassed. Every wait is a condition with a timeout; there are no sleeps.
 */
object HealthDevice {
    const val PACKAGE = "com.beta.health"
    private const val ACTIVITY = "app.reflex.mobile.MainActivity"
    const val TIMEOUT = 10_000L
    private val PERMISSION_ALLOW = By.res("com.android.permissioncontroller:id/permission_allow_button")
    private val PERMISSION_DENY = By.res("com.android.permissioncontroller:id/permission_deny_button")

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice get() = UiDevice.getInstance(instrumentation)

    // Shell (the test APK's UiAutomation runs commands as the shell user) -------------------------------------------

    fun shell(command: String): String {
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readText() }.trim()
    }

    /**
     * A fresh install as far as the app can tell: data cleared (which also kills it and drops its alarms), the
     * notification permission back to "never asked", notifications dismissed. Exact alarms stay granted: the one
     * documented exception (docs/health/E2E.md), since API 37 denies them by default.
     */
    fun freshInstall() {
        shell("pm clear $PACKAGE")
        shell("pm revoke $PACKAGE android.permission.POST_NOTIFICATIONS")
        shell("pm clear-permission-flags $PACKAGE android.permission.POST_NOTIFICATIONS user-set user-fixed")
        shell("appops set $PACKAGE SCHEDULE_EXACT_ALARM allow")
        shell("cmd notification cancel-all")
        launch()
    }

    /** Starts the app (a new task) and waits for Today or whatever screen Core shows. */
    fun launch() {
        val context = instrumentation.context
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(PACKAGE, ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        assertTrue("app didn't start", device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), TIMEOUT))
        waitFor("tab.today")
    }

    /** Terminates the app like the system does: Home, then kill the background process (never force-stop). */
    fun terminate() {
        device.pressHome()
        assertTrue("app still in front", device.wait(Until.gone(By.pkg(PACKAGE).depth(0)), TIMEOUT))
        assertTrue("app process didn't die", until {
            shell("am kill $PACKAGE")
            shell("pidof $PACKAGE").isEmpty()
        })
    }

    /** Waits (UI Automator's own polling) until [condition] holds. */
    fun until(timeout: Long = TIMEOUT, condition: () -> Boolean): Boolean =
        device.wait(Condition<UiDevice, Boolean> { condition() }, timeout) == true

    // Finding things --------------------------------------------------------------------------------------------

    fun id(id: String): BySelector = By.res(id)

    fun find(id: String): UiObject2? = device.findObject(By.res(id))

    fun waitFor(id: String, timeout: Long = TIMEOUT): UiObject2 =
        device.wait(Until.findObject(By.res(id)), timeout) ?: fail("no element '$id' within ${timeout}ms (${screenSummary()})") as Nothing

    fun waitGone(id: String, timeout: Long = TIMEOUT) {
        assertTrue("'$id' still shown", device.wait(Until.gone(By.res(id)), timeout))
    }

    fun waitForText(id: String, expected: String, timeout: Long = TIMEOUT) {
        var last: String? = null
        if (until(timeout) { find(id)?.let(::text).also { last = it } == expected }) return
        assertEquals("text of '$id'", expected, last)
    }

    /** The element's text, or its descendants' texts joined with " " when it is a container. */
    fun text(element: UiObject2): String = runCatching {
        element.text?.takeIf { it.isNotEmpty() } ?: element.children.map(::text).filter { it.isNotEmpty() }.joinToString(" ")
    }.getOrDefault("")

    /**
     * Scrolls the screen's scrolling column until [id] is on screen (down first, then up). Compose replaces nodes
     * when it recomposes, so every lookup tolerates a node going stale and looks again.
     */
    fun scrollTo(id: String): UiObject2 {
        // The screen may still be changing (navigation, a dialog closing): give the element a moment to appear.
        device.wait(Until.hasObject(By.res(id)), 1_500L)
        onScreen(id)?.let { return it }
        for (direction in listOf(Direction.DOWN, Direction.UP)) {
            repeat(12) {
                val moved = runCatching {
                    device.findObject(By.scrollable(true).pkg(PACKAGE))?.scroll(direction, 0.6f) ?: false
                }.getOrDefault(true)
                onScreen(id)?.let { return it }
                if (!moved) return@repeat
            }
        }
        return onScreen(id) ?: waitFor(id)
    }

    private fun onScreen(id: String): UiObject2? = runCatching { find(id)?.takeIf(::isOnScreen) }.getOrNull()

    fun isOnScreen(element: UiObject2): Boolean {
        val bounds = element.visibleBounds
        return bounds.width() > 0 && bounds.height() > 0 && bounds.bottom <= device.displayHeight && bounds.top >= 0
    }

    fun tap(id: String) {
        repeat(2) {
            try {
                scrollTo(id).click()
                return
            } catch (_: StaleObjectException) {
                // Recomposed between finding and clicking: find it again.
            }
        }
        scrollTo(id).click()
    }

    /** Replaces a text field's value through accessibility (no keyboard involved). */
    fun type(id: String, value: String) {
        val field = scrollTo(id)
        field.text = value
        assertTrue("'$id' didn't take '$value'", until { find(id)?.text == value })
    }

    private fun screenSummary(): String =
        device.findObjects(By.pkg(PACKAGE).res(Pattern.compile("[a-zA-Z]+\\..*"))).take(30).joinToString { it.resourceName }

    // System UI -------------------------------------------------------------------------------------------------

    fun answerPermission(allow: Boolean) {
        val button = device.wait(Until.findObject(if (allow) PERMISSION_ALLOW else PERMISSION_DENY), TIMEOUT)
            ?: device.wait(Until.findObject(By.text(Pattern.compile(if (allow) "Allow" else "Don.t allow", Pattern.CASE_INSENSITIVE))), TIMEOUT)
            ?: fail("no notification permission dialog") as Nothing
        // The permission controller animates its dialog in; a tap that lands mid-animation is dropped. Tap once the
        // button has stopped moving, and again if the dialog is still there (the final check below stays strict).
        var target = button
        repeat(3) {
            awaitStableBounds(target)
            runCatching { target.click() }
            if (device.wait(Until.gone(PERMISSION_ALLOW), 3_000)) return
            target = device.findObject(if (allow) PERMISSION_ALLOW else PERMISSION_DENY) ?: return@repeat
        }
        assertTrue("permission dialog still shown", device.wait(Until.gone(PERMISSION_ALLOW), TIMEOUT))
    }

    /** Waits (up to 2 s) until [obj]'s on-screen bounds stop changing between two reads. */
    private fun awaitStableBounds(obj: UiObject2) {
        var previous = runCatching { obj.visibleBounds }.getOrNull()
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            device.waitForIdle(250)
            val current = runCatching { obj.visibleBounds }.getOrNull() ?: return
            if (current == previous) return
            previous = current
        }
    }

    // Editor ----------------------------------------------------------------------------------------------------

    /** From an open editor: fills it in, adds [time] (if any) through the time picker, and saves. */
    fun fillAndSaveMedication(name: String, dose: String, time: String?) {
        type("editor.name", name)
        if (dose.isNotEmpty()) type("editor.dose", dose)
        if (time != null) {
            tap("editor.addTime")
            type("editor.timePicker.hour", time.substring(0, 2))
            type("editor.timePicker.minute", time.substring(3, 5))
            waitFor("editor.timePicker.confirm").click()
            scrollTo("editor.time.$time")
        }
        waitFor("editor.save").click()
        waitGone("editor.save")
    }

    // Time ------------------------------------------------------------------------------------------------------

    private val HH_MM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /** A dose time [minutes] from now (the scripts refuse to run near midnight, so it stays today). */
    fun timeIn(minutes: Long): String = LocalTime.now().plusMinutes(minutes).format(HH_MM)

    /** E4: the next minute boundary plus [minutes]. */
    fun nextMinutePlus(minutes: Long): LocalDateTime =
        LocalDateTime.now().withSecond(0).withNano(0).plusMinutes(1 + minutes)

    fun today(): String = java.time.LocalDate.now().toString()

    // Screenshots (review only; pulled by tools/e2e-android.sh, never committed) ---------------------------------

    const val SCREENSHOT_DIR = "/data/local/tmp/health-beta-screens"

    fun screenshot(name: String) {
        device.waitForIdle()
        shell("mkdir -p $SCREENSHOT_DIR")
        shell("screencap -p $SCREENSHOT_DIR/$name.png")
    }
}

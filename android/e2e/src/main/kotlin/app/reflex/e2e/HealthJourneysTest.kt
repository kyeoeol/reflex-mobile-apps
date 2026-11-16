package app.reflex.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import app.reflex.e2e.HealthDevice.answerPermission
import app.reflex.e2e.HealthDevice.device
import app.reflex.e2e.HealthDevice.fillAndSaveMedication
import app.reflex.e2e.HealthDevice.find
import app.reflex.e2e.HealthDevice.freshInstall
import app.reflex.e2e.HealthDevice.launch
import app.reflex.e2e.HealthDevice.scrollTo
import app.reflex.e2e.HealthDevice.tap
import app.reflex.e2e.HealthDevice.terminate
import app.reflex.e2e.HealthDevice.text
import app.reflex.e2e.HealthDevice.waitFor
import app.reflex.e2e.HealthDevice.waitForText
import app.reflex.e2e.HealthDevice.waitGone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.util.regex.Pattern

/**
 * The journeys of docs/health/E2E.md on the real app. Methods run in name order; each group starts from a fresh
 * install (E1+E2+E3+E5, E4, E6, E8+E7, E9) and later journeys of a group continue where the previous one ended.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class HealthJourneysTest {
    private companion object {
        const val NAME = "Vitamin D"
        const val DOSE = "1 capsule"
        /** Shared by E1, E2, E3, E5 (one group, one fresh install). */
        var groupTime: String = ""
        var groupDoseId: String = ""
    }

    @Test
    fun j01_E1_freshInstallAddMedicationAllowReminders() {
        freshInstall()
        waitForText("today.message", "No medications yet")
        tap("today.addMedication")
        groupTime = HealthDevice.timeIn(5)
        fillAndSaveMedication(NAME, DOSE, groupTime)
        answerPermission(allow = true)
        groupDoseId = "${medicationIdOnToday()}@${HealthDevice.today()}T$groupTime"
        // Upcoming dose, no reminder banner (exact alarms are granted).
        val row = dose("today")
        waitForText("${row}.status", "Upcoming")
        waitForText("today.progress", "0 of 1 taken")
        assertNull("no reminder banner", find("today.banner"))
    }

    @Test
    fun j02_E2_takeFromMenuThenUndo() {
        tap(dose("today"))
        waitFor("doseMenu.take").click()
        waitForText("today.progress", "1 of 1 taken")
        waitForText("today.message", "All done for today")
        waitForText("${dose("today")}.status", "Taken")
        tap(dose("today"))
        waitFor("doseMenu.undo").click()
        waitForText("today.progress", "0 of 1 taken")
        waitGone("today.message")
    }

    @Test
    fun j03_E3_remindersReadBackFromTheScheduler() {
        tap("today.openReminders")
        waitForText("reminders.status", "Reminders are on")
        val item = scrollTo("reminders.item.dose:${HealthDevice.today()}T$groupTime")
        val shown = text(item)
        assertTrue("reminder shows its title: $shown", shown.contains("Time for $NAME"))
        assertTrue("reminder shows its time: $shown", shown.contains(groupTime))
        device.pressBack()
        waitFor("today.openReminders")
    }

    @Test
    fun j04_E5_dataSurvivesTerminateAndRelaunch() {
        // Leave a log behind too: take the dose with the inline button.
        tap("${dose("today")}.take")
        waitForText("today.progress", "1 of 1 taken")
        terminate()
        launch()
        waitForText("today.progress", "1 of 1 taken")
        waitForText("${dose("today")}.status", "Taken")
        tap("tab.medications")
        assertTrue("medication listed", text(scrollTo("medications.item.${groupDoseId.substringBefore('@')}")).contains(NAME))
        tap("tab.today")
    }

    @Test
    fun j05_E4_answerTheSystemNotificationWithTaken() {
        freshInstall()
        tap("today.addMedication")
        val fireAt = HealthDevice.nextMinutePlus(2)
        val time = "%02d:%02d".format(fireAt.hour, fireAt.minute)
        fillAndSaveMedication(NAME, DOSE, time)
        answerPermission(allow = true)
        val doseId = "${medicationIdOnToday()}@${HealthDevice.today()}T$time"
        waitForText("today.dose.$doseId.status", "Upcoming")
        // Terminate before it fires: the alarm must start the app's process on its own.
        terminate()
        val started = System.currentTimeMillis()
        device.openNotification()
        val title = device.wait(Until.findObject(By.text("Time for $NAME")), 240_000L)
        assertTrue("no notification 'Time for $NAME' within 240 s", title != null)
        android.util.Log.i("HealthE2E", "E4 notification after ${(System.currentTimeMillis() - started) / 1000} s")
        notificationAction("Taken").click()
        assertTrue("notification still shown", device.wait(Until.gone(By.text("Time for $NAME")), 15_000L))
        device.pressHome()
        launch()
        waitForText("today.progress", "1 of 1 taken")
        waitForText("today.dose.$doseId.status", "Taken")
    }

    @Test
    fun j06_E6_deniedRemindersStillLogDoses() {
        freshInstall()
        tap("today.addMedication")
        val time = HealthDevice.timeIn(5)
        fillAndSaveMedication(NAME, DOSE, time)
        answerPermission(allow = false)
        waitForText("today.banner", "Reminders are off")
        waitForText("today.banner.action", "Open Settings")
        val doseId = "${medicationIdOnToday()}@${HealthDevice.today()}T$time"
        tap("today.dose.$doseId")
        waitFor("doseMenu.take").click()
        waitForText("today.progress", "1 of 1 taken")
    }

    @Test
    fun j07_E8_historyDayPager() {
        freshInstall()
        tap("today.addMedication")
        val time = HealthDevice.timeIn(5)
        fillAndSaveMedication(NAME, DOSE, time)
        answerPermission(allow = true)
        val doseId = "${medicationIdOnToday()}@${HealthDevice.today()}T$time"
        tap("tab.history")
        scrollTo("history.dose.$doseId")
        waitForText("history.summary", "0 of 1 taken")
        assertFalse("next is disabled on today", waitFor("history.next").isEnabled)
        tap("history.previous")
        waitForText("history.message", "Nothing recorded on this day")
        assertTrue("next is enabled before today", waitFor("history.next").isEnabled)
        tap("history.next")
        scrollTo("history.dose.$doseId")
        assertFalse("next is disabled on today again", waitFor("history.next").isEnabled)
        tap("tab.today")
    }

    @Test
    fun j08_E7_archiveRestoreDelete() {
        val id = medicationIdOnToday()
        tap("tab.medications")
        tap("medications.item.$id")
        waitForText("detail.name", NAME)
        tap("detail.archive")
        scrollTo("detail.restore")
        device.pressBack()
        assertTrue("archived", text(scrollTo("medications.section.archived")).contains(NAME))
        tap("medications.item.$id")
        tap("detail.restore")
        scrollTo("detail.archive")
        tap("detail.delete")
        waitFor("deleteDialog.confirm").click()
        // The detail of a deleted medication leaves the stack on its own.
        waitFor("medications.empty")
        assertNull(find("medications.item.$id"))
        tap("tab.today")
        waitForText("today.message", "No medications yet")
    }

    @Test
    fun j09_E9_darkAndLargestTextAndReviewScreens() {
        freshInstall()
        try {
            // Review screenshots of every screen, light then dark (scratchpad only, never committed).
            tap("today.addMedication")
            fillAndSaveMedication(NAME, DOSE, HealthDevice.timeIn(5))
            answerPermission(allow = true)
            val id = medicationIdOnToday()
            for (mode in listOf("light", "dark")) {
                setNight(mode == "dark")
                reviewTour(mode, id)
            }
            // Largest font scale in dark mode: the main screens render and their buttons are hittable.
            HealthDevice.shell("settings put system font_scale 2.0")
            waitFor("tab.today")
            tap("tab.today")
            assertHittable("today.openReminders")
            HealthDevice.screenshot("dark-xl-today")
            tap("tab.medications")
            assertHittable("medications.add")
            tap("medications.item.$id")
            for (button in listOf("detail.edit", "detail.archive", "detail.delete")) assertHittable(button)
            HealthDevice.screenshot("dark-xl-detail")
            tap("detail.edit")
            for (button in listOf("editor.name", "editor.addTime", "editor.startDate", "editor.notes", "editor.save")) assertHittable(button)
            scrollTo("editor.name")
            HealthDevice.screenshot("dark-xl-editor")
            scrollTo("editor.weekday.7")
            HealthDevice.screenshot("dark-xl-editor-2")
            setNight(false)
            HealthDevice.screenshot("light-xl-editor")
            waitFor("editor.cancel").click()
            waitFor("detail.name")
        } finally {
            HealthDevice.shell("settings put system font_scale 1.0")
            HealthDevice.shell("cmd uimode night no")
        }
    }

    // Helpers -----------------------------------------------------------------------------------------------------

    private fun reviewTour(mode: String, id: String) {
        tap("tab.today")
        waitFor("today.progress")
        HealthDevice.screenshot("$mode-today")
        tap(firstDoseRow())
        waitFor("doseMenu.take")
        HealthDevice.screenshot("$mode-today-dose-menu")
        device.pressBack()
        waitGone("doseMenu.take")
        tap("today.openReminders")
        waitFor("reminders.status")
        HealthDevice.screenshot("$mode-reminders")
        device.pressBack()
        tap("tab.medications")
        waitFor("medications.item.$id")
        HealthDevice.screenshot("$mode-medications")
        tap("medications.item.$id")
        waitFor("detail.name")
        HealthDevice.screenshot("$mode-detail")
        tap("detail.delete")
        waitFor("deleteDialog.cancel")
        HealthDevice.screenshot("$mode-delete-dialog")
        waitFor("deleteDialog.cancel").click()
        waitGone("deleteDialog.cancel")
        tap("detail.edit")
        waitFor("editor.name")
        HealthDevice.screenshot("$mode-editor-edit")
        waitFor("editor.cancel").click()
        device.pressBack()
        waitFor("medications.add").click()
        waitFor("editor.save").click()
        scrollTo("editor.error.name")
        HealthDevice.screenshot("$mode-editor-errors")
        tap("editor.addTime")
        waitFor("editor.timePicker.hour")
        HealthDevice.screenshot("$mode-editor-time-picker")
        waitFor("editor.timePicker.cancel").click()
        waitFor("editor.cancel").click()
        tap("tab.history")
        waitFor("history.date")
        HealthDevice.screenshot("$mode-history")
        tap("history.previous")
        waitFor("history.message")
        HealthDevice.screenshot("$mode-history-empty")
        tap("history.next")
        tap("tab.today")
    }

    private fun setNight(on: Boolean) {
        HealthDevice.shell("cmd uimode night ${if (on) "yes" else "no"}")
        waitFor("tab.today")
    }

    private fun assertHittable(id: String) {
        val element = scrollTo(id)
        assertTrue("'$id' enabled", element.isEnabled)
        assertTrue("'$id' on screen", HealthDevice.isOnScreen(element))
    }

    /** The resource id of today's dose row for this group's medication. */
    private fun dose(prefix: String) = "$prefix.dose.$groupDoseId"

    /** The only medication's id, read from the Medications tab (ids are random UUIDs). */
    private fun medicationIdOnToday(): String {
        tap("tab.medications")
        val item = device.wait(Until.findObject(By.res(Pattern.compile("medications\\.item\\..+"))), HealthDevice.TIMEOUT)
        val id = item.resourceName.removePrefix("medications.item.")
        tap("tab.today")
        return id
    }

    private fun firstDoseRow(): String =
        device.wait(Until.findObject(By.res(Pattern.compile("today\\.dose\\.[^.]+"))), HealthDevice.TIMEOUT).resourceName

    /** The action button of the notification shown in the shade; expands the notification when needed. */
    private fun notificationAction(title: String): androidx.test.uiautomator.UiObject2 {
        val button = By.text(Pattern.compile(title, Pattern.CASE_INSENSITIVE)).pkg("com.android.systemui")
        device.findObject(button)?.let { return it }
        device.findObject(By.res("android:id/expand_button"))?.click()
        return device.wait(Until.findObject(button), HealthDevice.TIMEOUT) ?: error("no '$title' action on the notification")
    }
}

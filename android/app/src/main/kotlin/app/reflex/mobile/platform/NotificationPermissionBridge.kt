package app.reflex.mobile.platform

import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lets the scheduler (Core's edge, Application scope) ask for a runtime permission through whichever Activity is
 * on screen. Every Activity [attach]es in `onCreate` (registering its result launcher there, so a result survives
 * recreation); a request suspends until the launcher returns. Core's scheduler is serialized, so a request must
 * never hang: with no Activity in the foreground it returns right away, and an Activity that finishes without a
 * result resumes it too. All calls happen on Main.
 */
class NotificationPermissionBridge {
    private var launcher: ActivityResultLauncher<String>? = null
    private var pending: CompletableDeferred<Unit>? = null

    fun attach(activity: ComponentActivity) {
        val registered = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }
        launcher = registered
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (launcher === registered) launcher = null
                // Recreated (rotation, dark mode): the new Activity's launcher receives the result. Finished: no result.
                if (activity.isFinishing) finish()
            }
        })
    }

    suspend fun request(permission: String) = withContext(Dispatchers.Main) {
        val launcher = launcher
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (launcher == null || !foreground || pending != null) return@withContext
        val request = CompletableDeferred<Unit>()
        pending = request
        val launched = runCatching { launcher.launch(permission) }.isSuccess
        if (!launched) finish()
        request.await()
    }

    private fun finish() {
        pending?.complete(Unit)
        pending = null
    }
}

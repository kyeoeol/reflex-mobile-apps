package app.reflex.mobile.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.reflex.core.foundation.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import java.time.Instant
import java.time.ZoneId

/**
 * Core's `Clock` on the device clock. [changes] emits on minute ticks, time set, time zone changes (runtime
 * receivers) and every time the app comes to the foreground (process lifecycle `ON_START`, which also covers
 * coming back from Settings with a changed permission).
 */
class SystemClock(private val context: Context) : Clock {
    override fun now(): Instant = Instant.now()

    /** Read fresh on every call: the platform resets the default time zone on `TIMEZONE_CHANGED`. */
    override fun zone(): ZoneId = ZoneId.systemDefault()

    override val changes: Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val foreground = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                trySend(Unit)
            }
        }
        lifecycle.addObserver(foreground)
        awaitClose {
            context.unregisterReceiver(receiver)
            lifecycle.removeObserver(foreground)
        }
    }.flowOn(Dispatchers.Main.immediate).conflate()
}

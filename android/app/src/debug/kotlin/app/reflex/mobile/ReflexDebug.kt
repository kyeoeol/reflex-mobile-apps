package app.reflex.mobile

import app.reflex.core.app.Core
import app.reflex.core.introspection.AppRootIntrospectionNode
import app.reflex.core.introspection.registerIntrospection
import app.reflex.host.ReflexHost
import app.reflex.host.ReflexSocketServer
import kotlinx.coroutines.Dispatchers

/**
 * DEBUG builds only (src/debug): serves the Reflex protocol on 127.0.0.1:7777, talking to the same
 * ViewModels the screen is bound to. On the host: `adb forward tcp:7777 tcp:7777`, then `./reflex cli android --live`.
 * Release builds get the no-op in src/release, and don't link reflex-host at all.
 */
object ReflexDebug {
    private var server: ReflexSocketServer? = null

    fun start(core: Core) {
        registerIntrospection()
        val host = ReflexHost("android", ReflexHost.Mode.Live { AppRootIntrospectionNode(core.app) }, Dispatchers.Main.immediate)
        server = ReflexSocketServer(host).also { runCatching { it.start() } }
    }
}

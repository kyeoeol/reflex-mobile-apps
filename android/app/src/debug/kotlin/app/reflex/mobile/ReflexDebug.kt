package app.reflex.mobile

import app.reflex.core.app.Core
import app.reflex.core.introspection.AppRootIntrospectionNode
import app.reflex.core.introspection.registerIntrospection
import app.reflex.host.LineWriter
import app.reflex.host.ReflexHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * DEBUG builds only (src/debug): serves the Reflex protocol on 127.0.0.1:7777, talking to the same
 * ViewModels the screen is bound to. On the host: `adb forward tcp:7777 tcp:7777`, then `./reflex cli android --live`.
 * Release builds get the no-op in src/release, and don't link reflex-host at all.
 *
 * Requests are handled on Main (the core thread), but Android forbids socket I/O there
 * (`NetworkOnMainThreadException`), so replies and events are queued and written by one writer thread per client,
 * in order. (reflex-host's `ReflexSocketServer` writes replies on the dispatcher thread, which only works on the JVM.)
 */
object ReflexDebug {
    private const val PORT = 7777
    private var server: ServerSocket? = null

    fun start(core: Core) {
        registerIntrospection()
        val host = ReflexHost("android", ReflexHost.Mode.Live { AppRootIntrospectionNode(core.app) }, Dispatchers.Main.immediate)
        // Reusable address: a relaunch right after the previous process died must still get the port.
        val socket = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 4)
            }
        }.getOrNull() ?: return
        server = socket
        thread(name = "reflex-socket", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(name = "reflex-client", isDaemon = true) { serve(host, client) }
            }
        }
    }

    private fun serve(host: ReflexHost, client: Socket) {
        val outgoing = LinkedBlockingQueue<Outgoing>()
        val out = LineWriter(client.getOutputStream().bufferedWriter(Charsets.UTF_8))
        val writer = thread(name = "reflex-client-writer", isDaemon = true) {
            runCatching {
                while (true) {
                    val next = outgoing.take() as? Outgoing.Line ?: break
                    out.write(next.text)
                }
            }
            runCatching { client.close() }
        }
        val token = host.events.addWriter { outgoing.put(Outgoing.Line(it)) }
        try {
            outgoing.put(Outgoing.Line(host.readyEvent))
            client.getInputStream().bufferedReader(Charsets.UTF_8).lineSequence().forEach { line ->
                runBlocking(host.dispatcher) { host.handle(line) { outgoing.put(Outgoing.Line(it)) } }
            }
        } catch (_: Exception) {
        } finally {
            host.events.removeWriter(token)
            outgoing.put(Outgoing.Close)
            writer.join()
        }
    }

    private sealed interface Outgoing {
        class Line(val text: String) : Outgoing
        data object Close : Outgoing
    }
}

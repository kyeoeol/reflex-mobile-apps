package app.reflex.host

import kotlinx.coroutines.runBlocking
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/** Serialized line output. */
class LineWriter(private val out: BufferedWriter) {
    @Synchronized
    fun write(line: String) {
        out.write(line)
        out.write("\n")
        out.flush()
    }

    companion object {
        val stdout = LineWriter(BufferedWriter(OutputStreamWriter(System.out, Charsets.UTF_8)))
    }
}

/** Headless transport: NDJSON on stdin/stdout. Each request is handled on the core thread, in order. */
object StdioTransport {
    fun run(host: ReflexHost) {
        val out = LineWriter.stdout
        host.events.addWriter(out::write)
        out.write(host.readyEvent)
        System.`in`.bufferedReader(Charsets.UTF_8).lineSequence().forEach { line ->
            runBlocking(host.dispatcher) { host.handle(line, out::write) }
        }
    }
}

/**
 * DEBUG-build transport: the same protocol over TCP on 127.0.0.1, talking to the ViewModels the screen is
 * bound to. From the host machine: `adb forward tcp:7777 tcp:7777`, then `./reflex cli android --live`.
 * Only ever wired in through `debugImplementation`.
 *
 * Requests are handled on the host's dispatcher (Main in the app), but Android forbids socket I/O there
 * (`NetworkOnMainThreadException`), so every reply and event is queued and written by one writer thread per
 * client, in order. The address is reusable so a relaunch right after the previous process died gets the port.
 */
class ReflexSocketServer(private val host: ReflexHost, private val port: Int = 7777) {
    private var server: ServerSocket? = null

    fun start() {
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4)
        }
        server = socket
        thread(name = "reflex-socket", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(name = "reflex-client", isDaemon = true) { serve(client) }
            }
        }
    }

    fun stop() { server?.close() }

    private fun serve(client: Socket) {
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
        val send: (String) -> Unit = { outgoing.put(Outgoing.Line(it)) }
        val token = host.events.addWriter(send)
        try {
            send(host.readyEvent)
            client.getInputStream().bufferedReader(Charsets.UTF_8).lineSequence().forEach { line ->
                runBlocking(host.dispatcher) { host.handle(line, send) }
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

package app.reflex.host

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler

/**
 * Zero network, enforced. Installed by the headless CLI only (never by the app).
 *
 * - `http`/`https` URL connections (HttpURLConnection, URL.openStream) fail immediately.
 * - Anything consulting the default ProxySelector (e.g. OkHttp) is pointed at a closed local port.
 * Every attempt emits a `network_violation` event, which fails the behavior test even if the app recovered.
 * `FixtureTransport` never touches the network, so tripping the guard means something bypassed the edge.
 */
object NetworkGuard {
    @Volatile private var sink: EventSink? = null
    private var installed = false

    @Synchronized
    fun install(events: EventSink) {
        sink = events
        if (installed) return
        installed = true
        URL.setURLStreamHandlerFactory { protocol ->
            if (protocol == "http" || protocol == "https") BlockingHandler else null
        }
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
                sink?.networkViolation("CONNECT", uri.toString())
                return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9)))
            }
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
        })
    }

    private object BlockingHandler : URLStreamHandler() {
        override fun openConnection(url: URL): URLConnection {
            sink?.networkViolation("GET", url.toString())
            throw IOException("Network access is disabled in behavior sessions: $url")
        }
    }
}

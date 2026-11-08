package app.reflex.core.foundation

// Everything Core needs from the outside world is declared here as an interface and passed to `Core(...)`.
// The app passes real implementations; the CLI passes fixtures. Same edges as ios/.../Foundation/Platform.swift.
// Add a new edge here (never import android.* into Core); see the reflex-add-edge skill.

interface Storage {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

interface DeviceCapabilities {
    val biometricsAvailable: Boolean
}

interface FeatureFlags {
    fun isEnabled(flag: String): Boolean
}

/** The network edge. Core's [GraphQLClient] is real; only this transport is swapped. */
fun interface GraphQLTransport {
    /** Returns the raw response body (`{"data": ..., "errors": [...]}`). */
    suspend fun execute(request: GraphQLRequest): String
}

/** [variables] is a JSON-encoded object. */
data class GraphQLRequest(val operationName: String, val query: String, val variables: String)

sealed class GraphQLTransportException(message: String) : Exception(message) {
    class Http(val status: Int) : GraphQLTransportException("HTTP $status")
    class Network(message: String) : GraphQLTransportException(message)
}

/** `Storage` without a device: used by the CLI (seeded from a fixture's `storage`). */
class InMemoryStorage(initial: Map<String, String> = emptyMap()) : Storage {
    private val values = initial.toMutableMap()
    override fun read(key: String): String? = values[key]
    override fun write(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

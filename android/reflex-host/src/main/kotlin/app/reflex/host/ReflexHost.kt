package app.reflex.host

import app.reflex.introspection.IntrospectionNode
import app.reflex.introspection.InvalidPayload
import app.reflex.introspection.JsonValue
import app.reflex.introspection.Resolution
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Speaks the Reflex NDJSON protocol (version 1), byte for byte like iOS `ReflexHost`.
 * [handle] must be called on [dispatcher]'s thread (the single thread every ViewModel runs on).
 */
class ReflexHost(
    val platform: String,
    private val mode: Mode,
    val dispatcher: CoroutineDispatcher,
    /** Pass when the root was built before the host (live mode) and already emits into a sink. */
    val events: EventSink = EventSink(),
) {
    sealed interface Mode {
        /** Headless: every `session.create` builds a fresh Core from fixtures. */
        class Fixtures(val root: File, val factory: (SessionContext) -> HostedSession) : Mode
        /** A running app: exactly one session (`"live"`), the app itself. `session.create` is rejected. */
        class Live(val root: () -> IntrospectionNode) : Mode
    }

    private val sessions = mutableMapOf<String, HostedSession>()
    private var nextSession = 1

    init {
        if (mode is Mode.Live) sessions[LIVE_SESSION] = HostedSession(mode.root)
    }

    val readyEvent: String
        get() = JsonValue.obj("event" to JsonValue.Str("ready"), "platform" to JsonValue.Str(platform), "protocol" to JsonValue.Num(PROTOCOL_VERSION)).serialize()

    fun handle(line: String, reply: (String) -> Unit) {
        if (line.isBlank()) return
        val request = try {
            JsonValue.parse(line).objectValue ?: throw HostException("BAD_REQUEST", "request must be a JSON object")
        } catch (e: HostException) {
            reply(failure(JsonValue.Null, e)); return
        } catch (e: Exception) {
            reply(failure(JsonValue.Null, HostException("BAD_REQUEST", e.message ?: "invalid JSON"))); return
        }
        val id = request["id"] ?: JsonValue.Null
        val response = try {
            respond(request, id).serialize()
        } catch (e: HostException) {
            failure(id, e)
        } catch (e: FixtureException) {
            failure(id, HostException(e.code, e.message ?: ""))
        } catch (e: Exception) {
            failure(id, HostException("INTERNAL", e.toString()))
        }
        reply(response)
    }

    private fun respond(request: Map<String, JsonValue>, id: JsonValue): JsonValue {
        val command = request["cmd"]?.stringValue.orEmpty()
        if (command == "session.create") return createSession(request, id)

        val sessionId = if (mode is Mode.Live) LIVE_SESSION else request["session"]?.stringValue ?: throw HostException("BAD_REQUEST", "Missing 'session'")
        val session = sessions[sessionId] ?: throw HostException("UNKNOWN_SESSION", "No session '$sessionId'")
        events.activeSession = sessionId
        val resolution = Resolution.resolve(session.root())

        return when (command) {
            "ls" -> ok(id, "node" to resolution.snapshot())
            "tree" -> ok(id, "tree" to resolution.tree())
            "do" -> {
                val title = request["action"]?.stringValue ?: throw HostException("BAD_REQUEST", "'do' needs an 'action'")
                val available = resolution.action(title) ?: throw HostException(
                    "ACTION_NOT_AVAILABLE", "Action '$title' is not available on ${resolution.focused.name}",
                    mapOf("available" to JsonValue.Arr(resolution.available.map { JsonValue.Str(it.action.title) })),
                )
                try {
                    available.action.perform(request["payload"])
                } catch (e: InvalidPayload) {
                    throw HostException(
                        "INVALID_PAYLOAD", "Invalid payload for $title: ${e.message}",
                        available.action.inputSchema?.let { mapOf("schema" to it) } ?: emptyMap(),
                    )
                }
                ok(id)
            }
            "session.close" -> {
                if (mode is Mode.Live) throw HostException("UNSUPPORTED", "The live session can't be closed")
                sessions.remove(sessionId)?.close()
                ok(id)
            }
            else -> throw HostException("BAD_REQUEST", "Unknown command '$command'")
        }
    }

    private fun createSession(request: Map<String, JsonValue>, id: JsonValue): JsonValue {
        val fixturesMode = mode as? Mode.Fixtures
            ?: throw HostException("UNSUPPORTED", "A live app has a single session ('live'); session.create is not supported")
        val names = request["fixtures"]?.arrayValue.orEmpty().mapNotNull { it.stringValue }
        val fixtures = FixtureSet.load(names, fixturesMode.root)
        val sessionId = "s${nextSession++}"
        events.activeSession = sessionId
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val context = SessionContext(sessionId, fixtures, request["deeplink"]?.stringValue, SessionEvents(sessionId, events), scope)
        val session = try { fixturesMode.factory(context) } catch (e: Exception) { scope.cancel(); throw e }
        sessions[sessionId] = HostedSession(session.root) { session.close(); scope.cancel() }
        return ok(id, "session" to JsonValue.Str(sessionId))
    }

    private fun ok(id: JsonValue, vararg extra: Pair<String, JsonValue>): JsonValue =
        JsonValue.Obj(linkedMapOf("id" to id, "ok" to JsonValue.Bool(true), *extra))

    private fun failure(id: JsonValue, e: HostException): String {
        val body = linkedMapOf<String, JsonValue>("code" to JsonValue.Str(e.code), "message" to JsonValue.Str(e.message ?: ""))
        body.putAll(e.extra)
        return JsonValue.obj("id" to id, "ok" to JsonValue.Bool(false), "error" to JsonValue.Obj(body)).serialize()
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        const val LIVE_SESSION = "live"
    }
}

/** A protocol-level error with a stable code. Session factories may throw it (e.g. `BAD_REQUEST`). */
class HostException(val code: String, message: String, val extra: Map<String, JsonValue> = emptyMap()) : Exception(message)

/** What a factory returns: how to get the root node, and how to tear the session down. */
class HostedSession(val root: () -> IntrospectionNode, private val onClose: () -> Unit = {}) {
    fun close() = onClose()
}

/** What a session factory receives. [scope] runs on the host's single core thread and is cancelled on close. */
class SessionContext(
    val id: String,
    val fixtures: FixtureSet,
    val deeplink: String?,
    val events: SessionEvents,
    val scope: CoroutineScope,
)

class SessionEvents(val sessionId: String, private val sink: EventSink) {
    /** A request reached the fixture edge but no fixture matched. Fails the behavior test. */
    fun fixtureMiss(operation: String, variables: Map<String, JsonValue>) = sink.emit(
        "event" to JsonValue.Str("fixture_miss"), "session" to JsonValue.Str(sessionId),
        "operation" to JsonValue.Str(operation), "variables" to JsonValue.Obj(variables),
    )

    fun log(level: String, message: String) = sink.emit(
        "event" to JsonValue.Str("log"), "session" to JsonValue.Str(sessionId),
        "level" to JsonValue.Str(level), "message" to JsonValue.Str(message),
    )
}

/** Thread-safe event fan-out to every connected client. */
class EventSink {
    private val writers = ConcurrentHashMap<UUID, (String) -> Unit>()

    /** The session that most recently received a command; network violations are attributed to it. */
    @Volatile var activeSession: String? = null

    fun addWriter(writer: (String) -> Unit): UUID = UUID.randomUUID().also { writers[it] = writer }
    fun removeWriter(token: UUID) { writers.remove(token) }

    fun emit(vararg entries: Pair<String, JsonValue>) {
        val line = JsonValue.obj(*entries).serialize()
        writers.values.forEach { it(line) }
    }

    fun networkViolation(method: String, url: String) = emit(
        "event" to JsonValue.Str("network_violation"), "session" to JsonValue.of(activeSession),
        "request" to JsonValue.obj("method" to JsonValue.Str(method), "url" to JsonValue.Str(url)),
    )
}

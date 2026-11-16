package app.reflex.cli

import app.reflex.core.app.Core
import app.reflex.core.features.health.HealthCoding
import app.reflex.core.features.health.HealthData
import app.reflex.core.foundation.Clock
import app.reflex.core.foundation.DeviceCapabilities
import app.reflex.core.foundation.FeatureFlags
import app.reflex.core.foundation.GraphQLRequest
import app.reflex.core.foundation.GraphQLTransport
import app.reflex.core.foundation.GraphQLTransportException
import app.reflex.core.foundation.HealthRepository
import app.reflex.core.foundation.InMemoryStorage
import app.reflex.core.foundation.NotificationPermission
import app.reflex.core.foundation.Reminder
import app.reflex.core.foundation.ReminderScheduler
import app.reflex.core.introspection.AppRootIntrospectionNode
import app.reflex.core.introspection.registerIntrospection
import app.reflex.host.EventSink
import app.reflex.host.FixtureSet
import app.reflex.host.GraphQLFixture
import app.reflex.host.GraphQLFixtureMatcher
import app.reflex.host.HostException
import app.reflex.host.HostedSession
import app.reflex.host.NetworkGuard
import app.reflex.host.ReflexHost
import app.reflex.host.ReflexSocketServer
import app.reflex.host.SessionContext
import app.reflex.host.SessionEvents
import app.reflex.host.StdioTransport
import app.reflex.introspection.JsonValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import kotlin.system.exitProcess

// Usage: java -jar cli.jar --fixtures-dir <dir>                         NDJSON on stdin/stdout (headless sessions)
//        java -jar cli.jar --fixtures-dir <dir> --serve <port> [--fixture <name>]... [--deeplink <url>]
//               one fixture-built Core over the live socket protocol (`./reflex serve android`), to exercise
//               the live layer without an emulator.
// Humans and agents use `./reflex cli android` (which renders ls/do/tree) instead of talking to this directly.

fun main(args: Array<String>) {
    val flag = args.indexOf("--fixtures-dir")
    if (flag < 0 || flag + 1 >= args.size) {
        System.err.println("usage: cli --fixtures-dir <dir>")
        exitProcess(64)
    }
    registerIntrospection()
    // Every ViewModel runs on this one thread (the CLI's stand-in for Dispatchers.Main).
    val core = Executors.newSingleThreadExecutor { Thread(it, "core").apply { isDaemon = true } }.asCoroutineDispatcher()
    val fixturesRoot = File(args[flag + 1])
    fun values(name: String) = args.indices.filter { args[it] == name && it + 1 < args.size }.map { args[it + 1] }

    values("--serve").firstOrNull()?.toIntOrNull()?.let { port ->
        val events = EventSink()
        NetworkGuard.install(events)
        val session = runBlocking(core) {
            makeSession(SessionContext(
                ReflexHost.LIVE_SESSION, FixtureSet.load(values("--fixture"), fixturesRoot), values("--deeplink").firstOrNull(),
                SessionEvents(ReflexHost.LIVE_SESSION, events), CoroutineScope(SupervisorJob() + core),
            ))
        }
        ReflexSocketServer(ReflexHost("android", ReflexHost.Mode.Live(session.root), core, events), port).start()
        System.err.println("Serving Android core on 127.0.0.1:$port (live protocol)")
        Thread.currentThread().join()
    }

    val host = ReflexHost("android", ReflexHost.Mode.Fixtures(fixturesRoot, ::makeSession), core)
    NetworkGuard.install(host.events)
    StdioTransport.run(host)
    exitProcess(0)
}

/** One session = one fresh Core built from fixtures. Nothing is shared between sessions. */
fun makeSession(context: SessionContext): HostedSession {
    val fixtures = context.fixtures
    val storage = fixtures.storage.mapNotNull { (k, v) -> v.stringValue?.let { k to it } }.toMap()
    val core = Core(
        storage = InMemoryStorage(storage),
        transport = FixtureTransport(GraphQLFixtureMatcher(fixtures.graphql), context.events),
        device = FixtureDevice(fixtures.device),
        flags = FixtureFlags(fixtures.flags),
        clock = FixtureClock(fixtures.device),
        reminderScheduler = FixtureReminderScheduler(fixtures.device),
        healthRepository = InMemoryHealthRepository(storage[InMemoryHealthRepository.KEY]),
        scope = context.scope,
    )
    context.deeplink?.let { link ->
        if (!core.app.open(link)) throw HostException("BAD_REQUEST", "Deep link '$link' doesn't route anywhere")
    }
    return HostedSession({ AppRootIntrospectionNode(core.app) })
}

// Fixture-backed implementations of Core's platform interfaces (core/.../foundation/Platform.kt).
// When Core gains a new edge, add its fixture adapter here and in ios/AppCLI (same fixture keys).

/** The network edge in headless mode. Never touches the network, so it never trips the guard. */
class FixtureTransport(private val matcher: GraphQLFixtureMatcher, private val events: SessionEvents) : GraphQLTransport {
    override suspend fun execute(request: GraphQLRequest): String {
        val variables = runCatching { JsonValue.parse(request.variables).objectValue }.getOrNull().orEmpty()
        return when (val outcome = matcher.match(request.operationName, variables)) {
            is GraphQLFixture.Outcome.Response -> outcome.body.serialize()
            is GraphQLFixture.Outcome.HttpError -> throw GraphQLTransportException.Http(outcome.status)
            GraphQLFixture.Outcome.NetworkError -> throw GraphQLTransportException.Network("Fixture network error")
            null -> {
                events.fixtureMiss(request.operationName, variables)
                throw GraphQLTransportException.Network("No fixture for ${request.operationName}")
            }
        }
    }
}

/** Fixture key `device`. Missing keys default to false. */
class FixtureDevice(private val values: Map<String, JsonValue>) : DeviceCapabilities {
    override val biometricsAvailable: Boolean get() = values["biometricsAvailable"]?.boolValue ?: false
}

/** Fixture key `flags`. Missing flags are disabled. */
class FixtureFlags(private val values: Map<String, JsonValue>) : FeatureFlags {
    override fun isEnabled(flag: String): Boolean = values[flag]?.boolValue ?: false
}

/**
 * Fixture keys `device.now` (local `yyyy-MM-ddTHH:mm:ss` in `device.timeZone`, resolved like every local time:
 * gap → shifted forward, overlap → earlier offset) and `device.timeZone` (IANA id). Defaults:
 * `2026-01-01T00:00:00`, `UTC`. Fixed: [changes] never emits.
 */
class FixtureClock(values: Map<String, JsonValue>) : Clock {
    private val zone: ZoneId = (values["timeZone"]?.stringValue ?: "UTC").let { id ->
        runCatching { ZoneId.of(id) }.getOrElse { throw HostException("INVALID_FIXTURE", "device.timeZone '$id' is not an IANA time zone") }
    }
    private val now: Instant = (values["now"]?.stringValue ?: "2026-01-01T00:00:00").let { text ->
        val local = runCatching { LocalDateTime.parse(text) }
            .getOrElse { throw HostException("INVALID_FIXTURE", "device.now '$text' is not yyyy-MM-ddTHH:mm:ss") }
        ZonedDateTime.of(local, zone).toInstant()
    }

    override fun now(): Instant = now
    override fun zone(): ZoneId = zone
    override val changes: Flow<Unit> = emptyFlow()
}

/**
 * Fixture keys `device.notificationPermission` (default `notDetermined`), `device.notificationRequestResult`
 * (what the user answers when asked; default `denied`) and `device.remindersMayBeLate` (default false).
 * Pending reminders live in memory, in the order Core handed them over.
 */
class FixtureReminderScheduler(values: Map<String, JsonValue>) : ReminderScheduler {
    private var permission = permission(values, "notificationPermission", NotificationPermission.NotDetermined)
    private val requestResult = permission(values, "notificationRequestResult", NotificationPermission.Denied)
    private val mayBeLate = values["remindersMayBeLate"]?.boolValue ?: false
    private var pending: List<Reminder> = emptyList()

    override suspend fun permission(): NotificationPermission = permission

    override suspend fun requestPermission(): NotificationPermission {
        if (permission == NotificationPermission.NotDetermined) permission = requestResult
        return permission
    }

    override suspend fun mayBeLate(): Boolean = mayBeLate
    override suspend fun replaceAll(reminders: List<Reminder>) { pending = reminders }
    override suspend fun pending(): List<Reminder> = pending
    override suspend fun openSettings() {}

    private companion object {
        fun permission(values: Map<String, JsonValue>, key: String, default: NotificationPermission): NotificationPermission {
            val code = values[key]?.stringValue ?: return default
            return NotificationPermission.entries.firstOrNull { it.code == code }
                ?: throw HostException("INVALID_FIXTURE", "device.$key '$code' must be notDetermined, denied or authorized")
        }
    }
}

/** Fixture key `storage["health.data"]` (the JSON in docs/health/CONTRACT.md). Unparseable → `load` throws. */
class InMemoryHealthRepository(private val stored: String?) : HealthRepository {
    private var saved: HealthData? = null

    override suspend fun load(): HealthData = saved ?: stored?.let(HealthCoding::decode) ?: HealthData()

    override suspend fun save(data: HealthData) { saved = data }

    companion object {
        const val KEY = "health.data"
    }
}

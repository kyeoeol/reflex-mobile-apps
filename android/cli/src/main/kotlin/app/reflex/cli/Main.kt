package app.reflex.cli

import app.reflex.core.app.AppRoot
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
import app.reflex.host.DeviceControl
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
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
    val world = FixtureWorld(fixtures.device)
    val core = Core(
        storage = InMemoryStorage(storage),
        transport = FixtureTransport(GraphQLFixtureMatcher(fixtures.graphql), context.events),
        device = FixtureDevice(world),
        flags = FixtureFlags(fixtures.flags),
        clock = FixtureClock(world),
        reminderScheduler = FixtureReminderScheduler(world),
        healthRepository = InMemoryHealthRepository(storage[InMemoryHealthRepository.KEY], world),
        scope = context.scope,
    )
    context.deeplink?.let { link ->
        if (!core.app.open(link)) throw HostException("BAD_REQUEST", "Deep link '$link' doesn't route anywhere")
    }
    return HostedSession({ AppRootIntrospectionNode(core.app) }, FixtureDeviceControl(world, core.app))
}

// Fixture-backed implementations of Core's platform interfaces (core/.../foundation/Platform.kt).
// When Core gains a new edge, add its fixture adapter here and in ios/AppCLI (same fixture keys).

/**
 * The session's `device` values (fixture keys, defaults in docs/health/CONTRACT.md §2), mutable mid-session through
 * `device.set`. Every adapter below reads the current values, so a change takes effect immediately.
 */
class FixtureWorld(values: Map<String, JsonValue>) {
    private val values = LinkedHashMap(values)
    var zone: ZoneId = zone(values["timeZone"] ?: JsonValue.Str(DEFAULT_TIME_ZONE))
        private set
    var now: Instant = now(values["now"] ?: JsonValue.Str(DEFAULT_NOW), zone)
        private set
    var permission: NotificationPermission = permission(values["notificationPermission"], "notificationPermission", NotificationPermission.NotDetermined)
    var requestResult: NotificationPermission = permission(values["notificationRequestResult"], "notificationRequestResult", NotificationPermission.Denied)
        private set
    var mayBeLate: Boolean = bool(values["remindersMayBeLate"], "remindersMayBeLate")
        private set
    var saveFails: Boolean = bool(values["saveFails"], "saveFails")
        private set

    /** Yields once after every [set] (what the real clock does on time and time zone changes). */
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun bool(key: String): Boolean = values[key]?.boolValue ?: false

    /**
     * Merges [update] like the fixture's `device` section: `now` (in the new `timeZone` if both are given) moves the
     * clock; `timeZone` alone keeps the instant and changes the zone; `notificationPermission` is what the user set in
     * Settings. Validates everything before changing anything; bad values → `BAD_REQUEST`.
     */
    fun set(update: Map<String, JsonValue>) {
        val fail = { e: HostException -> HostException("BAD_REQUEST", e.message ?: "invalid device values") }
        try {
            val newZone = update["timeZone"]?.let(::zone) ?: zone
            val newNow = update["now"]?.let { now(it, newZone) } ?: now
            val newPermission = update["notificationPermission"]?.let { permission(it, "notificationPermission", permission) } ?: permission
            val newRequestResult = update["notificationRequestResult"]?.let { permission(it, "notificationRequestResult", requestResult) } ?: requestResult
            val newMayBeLate = update["remindersMayBeLate"]?.let { bool(it, "remindersMayBeLate") } ?: mayBeLate
            val newSaveFails = update["saveFails"]?.let { bool(it, "saveFails") } ?: saveFails
            zone = newZone
            now = newNow
            permission = newPermission
            requestResult = newRequestResult
            mayBeLate = newMayBeLate
            saveFails = newSaveFails
            values.putAll(update)
        } catch (e: HostException) {
            throw fail(e)
        }
        changes.tryEmit(Unit)
    }

    private companion object {
        const val DEFAULT_NOW = "2026-01-01T00:00:00"
        const val DEFAULT_TIME_ZONE = "UTC"
        val NOW = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}""")

        fun zone(value: JsonValue): ZoneId {
            val id = value.stringValue ?: throw invalid("device.timeZone must be an IANA time zone id")
            return runCatching { ZoneId.of(id) }.getOrElse { throw invalid("device.timeZone '$id' is not an IANA time zone") }
        }

        /** Local `yyyy-MM-ddTHH:mm:ss` in [zone], resolved like every local time (gap → shifted, overlap → earlier). */
        fun now(value: JsonValue, zone: ZoneId): Instant {
            val text = value.stringValue?.takeIf { NOW.matches(it) }
                ?: throw invalid("device.now must be a local date-time yyyy-MM-ddTHH:mm:ss")
            val local = runCatching { LocalDateTime.parse(text) }.getOrElse { throw invalid("device.now '$text' is not a real date-time") }
            return ZonedDateTime.of(local, zone).toInstant()
        }

        fun permission(value: JsonValue?, key: String, default: NotificationPermission): NotificationPermission {
            value ?: return default
            val code = value.stringValue
            return NotificationPermission.entries.firstOrNull { it.code == code }
                ?: throw invalid("device.$key must be notDetermined, denied or authorized")
        }

        fun bool(value: JsonValue?, key: String): Boolean {
            value ?: return false
            return value.boolValue ?: throw invalid("device.$key must be true or false")
        }

        fun invalid(message: String) = HostException("INVALID_FIXTURE", message)
    }
}

/** `device.set` and `device.event` (host protocol, headless only) for one session. */
class FixtureDeviceControl(private val world: FixtureWorld, private val app: AppRoot) : DeviceControl {
    override suspend fun set(values: Map<String, JsonValue>) = world.set(values)

    override suspend fun event(type: String, payload: Map<String, JsonValue>) {
        when (type) {
            "notificationResponse" -> {
                val slot = payload["slot"]?.stringValue ?: throw HostException("BAD_REQUEST", "notificationResponse needs a 'slot' string")
                val action = payload["action"]?.stringValue ?: throw HostException("BAD_REQUEST", "notificationResponse needs an 'action' string")
                if (!app.handleReminderResponse(slot, action)) {
                    throw HostException("BAD_REQUEST", "notificationResponse needs a slot yyyy-MM-ddTHH:mm and an action open, taken, skipped or snooze")
                }
            }
            "openURL" -> {
                val url = payload["url"]?.stringValue ?: throw HostException("BAD_REQUEST", "openURL needs a 'url' string")
                if (!app.open(url)) throw HostException("BAD_REQUEST", "Link '$url' doesn't route anywhere")
            }
            else -> throw HostException("BAD_REQUEST", "Unknown device event '$type' (known: notificationResponse, openURL)")
        }
    }
}

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
class FixtureDevice(private val world: FixtureWorld) : DeviceCapabilities {
    override val biometricsAvailable: Boolean get() = world.bool("biometricsAvailable")
}

/** Fixture key `flags`. Missing flags are disabled. */
class FixtureFlags(private val values: Map<String, JsonValue>) : FeatureFlags {
    override fun isEnabled(flag: String): Boolean = values[flag]?.boolValue ?: false
}

/** Fixture keys `device.now` / `device.timeZone`; [changes] yields once after every `device.set`. */
class FixtureClock(private val world: FixtureWorld) : Clock {
    override fun now(): Instant = world.now
    override fun zone(): ZoneId = world.zone
    override val changes: Flow<Unit> = world.changes
}

/**
 * Fixture keys `device.notificationPermission` (default `notDetermined`), `device.notificationRequestResult`
 * (what the user answers when asked; default `denied`) and `device.remindersMayBeLate` (default false).
 * Pending reminders live in memory, in the order Core handed them over.
 */
class FixtureReminderScheduler(private val world: FixtureWorld) : ReminderScheduler {
    private var pending: List<Reminder> = emptyList()

    override suspend fun permission(): NotificationPermission = world.permission

    override suspend fun requestPermission(): NotificationPermission {
        if (world.permission == NotificationPermission.NotDetermined) world.permission = world.requestResult
        return world.permission
    }

    override suspend fun mayBeLate(): Boolean = world.mayBeLate
    override suspend fun replaceAll(reminders: List<Reminder>, openSlots: Set<String>) { pending = reminders }
    override suspend fun pending(): List<Reminder> = pending
    override suspend fun openSettings() {}
}

/**
 * Fixture key `storage["health.data"]` (the JSON in docs/health/CONTRACT.md). Unparseable or invalid → `load`
 * throws; `device.saveFails` → every `save` throws.
 */
class InMemoryHealthRepository(private val stored: String?, private val world: FixtureWorld) : HealthRepository {
    private var saved: HealthData? = null

    override suspend fun load(): HealthData = saved ?: HealthCoding.decode(stored)

    override suspend fun save(data: HealthData) {
        if (world.saveFails) throw IOException("device.saveFails")
        saved = data
    }

    companion object {
        const val KEY = "health.data"
    }
}

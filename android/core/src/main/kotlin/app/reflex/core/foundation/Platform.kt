package app.reflex.core.foundation

import app.reflex.core.features.health.HealthData
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId

// Everything Core needs from the outside world is declared here as an interface and passed to `Core(...)`.
// The app passes real implementations; the CLI passes fixtures. Same edges as ios/.../Foundation/Platform.swift.
// Add a new edge here (never import android.* into Core); see the reflex-add-edge skill.

/** Persistence. Suspending because the real store does blocking I/O, which must not run on Main. */
interface Storage {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String?)
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
    override suspend fun read(key: String): String? = values[key]
    override suspend fun write(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/**
 * Time. A cheap synchronous read like [FeatureFlags]; [changes] emits on minute ticks, significant time changes,
 * time zone changes and app foreground (in the CLI: once after every `device.set`). Same edge as iOS `Clock`.
 */
interface Clock {
    fun now(): Instant
    fun zone(): ZoneId
    val changes: Flow<Unit>
}

enum class NotificationPermission(val code: String) {
    NotDetermined("notDetermined"),
    Denied("denied"),
    Authorized("authorized"),
}

/**
 * One local notification Core wants pending. [id] is stable (`dose:<slot>`, `followup:<slot>`, `snooze:<slot>`,
 * `refresh`), so the platform scheduler reconciles by diffing ids. [localFireTime] is the resolved local wall time
 * `yyyy-MM-ddTHH:mm` (for floating OS triggers). [kind]: `dose`, `followUp`, `snooze`, `refresh`. [slot] is null
 * for `refresh`.
 */
data class Reminder(
    val id: String,
    val kind: String,
    val fireAt: Instant,
    val localFireTime: String,
    val title: String,
    val body: String,
    val slot: String?,
)

/** Local notifications. Core computes the complete desired set; the platform only reconciles it. */
interface ReminderScheduler {
    suspend fun permission(): NotificationPermission
    /** Asks the user when the permission is not determined; returns the resulting permission. */
    suspend fun requestPermission(): NotificationPermission
    /** True when reminders can't fire exactly (Android without exact alarms: a 10-minute window). */
    suspend fun mayBeLate(): Boolean
    /**
     * pending := [reminders]. Items whose id is new or whose fireAt/title/body changed are (re)scheduled, items no
     * longer present are cancelled; delivered notifications whose slot isn't in [openSlots] are removed (open slot =
     * at least one unlogged dose and now < T + 120 min). Core never runs two calls at once, and calls in order.
     */
    suspend fun replaceAll(reminders: List<Reminder>, openSlots: Set<String>)
    suspend fun pending(): List<Reminder>
    suspend fun openSettings()
}

/**
 * Persistence of the Health data. App: Room (on Dispatchers.IO, one `@Transaction` replace per save).
 * CLI: in memory, seeded from the fixture's `storage["health.data"]`.
 */
interface HealthRepository {
    /** Nothing stored → empty data. Throws when the store can't be read. */
    suspend fun load(): HealthData
    /** A full snapshot; Core serializes saves (one at a time, in order). */
    suspend fun save(data: HealthData)
}

package app.reflex.cli

import app.reflex.core.app.Core
import app.reflex.core.foundation.DeviceCapabilities
import app.reflex.core.foundation.FeatureFlags
import app.reflex.core.foundation.GraphQLRequest
import app.reflex.core.foundation.GraphQLTransport
import app.reflex.core.foundation.GraphQLTransportException
import app.reflex.core.foundation.InMemoryStorage
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
import kotlinx.coroutines.runBlocking
import java.io.File
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
    val core = Core(
        storage = InMemoryStorage(fixtures.storage.mapNotNull { (k, v) -> v.stringValue?.let { k to it } }.toMap()),
        transport = FixtureTransport(GraphQLFixtureMatcher(fixtures.graphql), context.events),
        device = FixtureDevice(fixtures.device),
        flags = FixtureFlags(fixtures.flags),
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

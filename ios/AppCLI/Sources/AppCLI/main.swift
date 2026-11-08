import AppCore
import Foundation
import IntrospectionKit
import ReflexHost

// Usage: AppCLI --fixtures-dir <dir>                                  NDJSON on stdin/stdout (headless sessions)
//        AppCLI --fixtures-dir <dir> --serve <port> [--fixture <name>]... [--deeplink <url>]
//               one fixture-built Core over the live socket protocol (`./reflex serve ios`), to exercise
//               the live layer without a simulator.
// Humans and agents use `./reflex cli ios` (which renders ls/do/tree) instead of talking to this directly.

let arguments = CommandLine.arguments
guard let flag = arguments.firstIndex(of: "--fixtures-dir"), flag + 1 < arguments.count else {
    FileHandle.standardError.write(Data("usage: AppCLI --fixtures-dir <dir>\n".utf8))
    exit(64)
}
let fixturesRoot = URL(fileURLWithPath: arguments[flag + 1])

func values(of flag: String) -> [String] {
    arguments.indices.filter { arguments[$0] == flag && $0 + 1 < arguments.count }.map { arguments[$0 + 1] }
}

MainActor.assumeIsolated {
    if let port = values(of: "--serve").first.flatMap(UInt16.init) {
        do {
            let events = EventSink()
            NetworkGuard.install(reportingTo: events)
            let context = SessionContext(id: ReflexHost.liveSessionID,
                                         fixtures: try FixtureSet.load(names: values(of: "--fixture"), root: fixturesRoot),
                                         deeplink: values(of: "--deeplink").first,
                                         events: SessionEvents(sessionID: ReflexHost.liveSessionID, sink: events))
            let host = ReflexHost(platform: "ios", mode: .live(root: try makeSession(context)), events: events)
            let server = try ReflexSocketServer(host: host, port: port)
            server.start()
            FileHandle.standardError.write(Data("Serving iOS core on 127.0.0.1:\(port) (live protocol)\n".utf8))
            while true { withExtendedLifetime(server) { _ = RunLoop.main.run(mode: .default, before: .distantFuture) } }
        } catch {
            FileHandle.standardError.write(Data("error: \(error)\n".utf8))
            exit(1)
        }
    }
    let host = ReflexHost(platform: "ios", mode: .fixtures(root: fixturesRoot, factory: makeSession))
    NetworkGuard.install(reportingTo: host.events)
    StdioTransport.run(host)
}

/// One session = one fresh Core built from fixtures. Nothing is shared between sessions.
@MainActor
func makeSession(_ context: SessionContext) throws -> any IntrospectionProviding {
    let fixtures = context.fixtures
    let core = Core(
        storage: InMemoryStorage(fixtures.storage.reduce(into: [:]) { $0[$1.key] = $1.value.stringValue }),
        transport: FixtureTransport(matcher: GraphQLFixtureMatcher(fixtures.graphql), events: context.events),
        device: FixtureDevice(fixtures.device),
        flags: FixtureFlags(fixtures.flags)
    )
    if let deeplink = context.deeplink {
        guard let url = URL(string: deeplink), core.app.open(url) else {
            throw HostError("BAD_REQUEST", "Deep link '\(deeplink)' doesn't route anywhere")
        }
    }
    return core
}

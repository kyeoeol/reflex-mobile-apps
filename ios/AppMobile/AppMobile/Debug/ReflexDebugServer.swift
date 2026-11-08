#if DEBUG
import AppCore
import ReflexHost

/// DEBUG builds only: serves the Reflex protocol on 127.0.0.1:7777, talking to the same ViewModels the
/// screen is bound to. The simulator shares the Mac's network: `./reflex cli ios --live`.
/// Views only observe state, so the simulator updates as commands arrive. Compiled out of release builds.
@MainActor
enum ReflexDebugServer {
    private static var server: ReflexSocketServer?

    static func start(core: Core) {
        let host = ReflexHost(platform: "ios", mode: .live(root: core))
        server = try? ReflexSocketServer(host: host)
        server?.start()
    }
}
#endif

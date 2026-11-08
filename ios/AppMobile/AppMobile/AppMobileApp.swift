import AppCore
import SwiftUI

/// Build-time configuration. Placeholder values: set them when the product is decided.
enum AppConfig {
    static let graphQLEndpoint = URL(string: "https://example.invalid/graphql")!
}

/// The app is just a host: it constructs Core with real platform implementations.
@main
struct AppMobileApp: App {
    @State private var core: Core

    init() {
        let core = Core(
            storage: KeychainStorage(service: Bundle.main.bundleIdentifier ?? "app.reflex.mobile"),
            transport: URLSessionTransport(endpoint: AppConfig.graphQLEndpoint),
            device: SystemDeviceCapabilities(),
            flags: LocalFeatureFlags()
        )
        #if DEBUG
        ReflexDebugServer.start(core: core)
        #endif
        _core = State(initialValue: core)
    }

    var body: some Scene {
        WindowGroup {
            RootView(app: core.app)
        }
    }
}

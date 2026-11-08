import Foundation
import IntrospectionKit

/// The entry point every host constructs. The app and the CLI are just hosts:
///
///     // Mobile
///     Core(storage: KeychainStorage(), transport: URLSessionTransport(endpoint: …),
///          device: SystemDeviceCapabilities(), flags: LocalFeatureFlags())
///     // CLI: same core, no platform/UI
///     Core(storage: InMemoryStorage(…), transport: FixtureTransport(…), device: FixtureDevice(…), flags: FixtureFlags(…))
///
/// Plain dependency inversion: no DI framework.
@MainActor
public final class Core {
    public let app: AppRoot

    public init(storage: Storage, transport: any GraphQLTransport, device: DeviceCapabilities, flags: FeatureFlags) {
        let services = Services(client: GraphQLClient(transport: transport), storage: storage, device: device, flags: flags)
        app = AppRoot(services: services)
    }
}

/// Shared dependencies handed to feature services and ViewModel factories by `AppRoot`.
@MainActor
public struct Services {
    public let client: GraphQLClient
    public let storage: Storage
    public let device: DeviceCapabilities
    public let flags: FeatureFlags
}

extension Core: IntrospectionProviding {
    public var introspectionNode: any IntrospectionNode { app.introspectionNode }
}

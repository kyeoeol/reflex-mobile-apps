import Foundation
import IntrospectionKit

/// The entry point every host constructs. The app and the CLI are just hosts:
///
///     // Mobile
///     Core(storage: KeychainStorage(), transport: URLSessionTransport(endpoint: …),
///          device: SystemDeviceCapabilities(), flags: LocalFeatureFlags(),
///          clock: SystemClock(), reminders: NotificationReminderScheduler(), health: SwiftDataHealthRepository(…))
///     // CLI: same core, no platform/UI
///     Core(storage: InMemoryStorage(…), transport: FixtureTransport(…), device: FixtureDevice(…), flags: FixtureFlags(…),
///          clock: FixtureClock(…), reminders: FixtureReminderScheduler(…), health: FixtureHealthRepository(…))
///
/// Plain dependency inversion: no DI framework.
@MainActor
public final class Core {
    public let app: AppRoot

    public init(storage: any Storage, transport: any GraphQLTransport, device: DeviceCapabilities, flags: FeatureFlags,
                clock: any Clock, reminders: any ReminderScheduler, health: any HealthRepository) {
        let services = Services(client: GraphQLClient(transport: transport), storage: storage, device: device, flags: flags,
                                clock: clock, reminderScheduler: reminders, healthRepository: health)
        app = AppRoot(services: services)
    }
}

/// Shared dependencies handed to feature services and ViewModel factories by `AppRoot`.
@MainActor
public struct Services {
    public let client: GraphQLClient
    public let storage: any Storage
    public let device: DeviceCapabilities
    public let flags: FeatureFlags
    public let clock: any Clock
    public let reminderScheduler: any ReminderScheduler
    public let healthRepository: any HealthRepository
}

extension Core: IntrospectionProviding {
    public var introspectionNode: any IntrospectionNode { app.introspectionNode }
}

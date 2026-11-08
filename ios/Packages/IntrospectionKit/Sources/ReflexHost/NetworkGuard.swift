import Foundation
import ObjectiveC
import os

/// Zero network, enforced. Installed by the headless CLI only (never by the app).
///
/// Every HTTP(S) request in the process is intercepted, fails, and emits a `network_violation` event,
/// which fails the behavior test even if the app recovered. `FixtureTransport` never touches the
/// network, so tripping the guard means something bypassed the edge.
public enum NetworkGuard {
    /// The sink is read from URL loading threads, so it lives behind a lock.
    private static let state = OSAllocatedUnfairLock<(sink: EventSink?, installed: Bool)>(initialState: (nil, false))

    fileprivate static var sink: EventSink? { state.withLock { $0.sink } }

    public static func install(reportingTo sink: EventSink) {
        let alreadyInstalled = state.withLock { state in
            defer { state = (sink, true) }
            return state.installed
        }
        guard !alreadyInstalled else { return }
        URLProtocol.registerClass(BlockingURLProtocol.self)       // URLSession.shared and legacy APIs
        swizzle("defaultSessionConfiguration", with: #selector(URLSessionConfiguration.reflex_guardedDefault))
        swizzle("ephemeralSessionConfiguration", with: #selector(URLSessionConfiguration.reflex_guardedEphemeral))
    }

    private static func swizzle(_ original: String, with replacement: Selector) {
        let cls: AnyClass = URLSessionConfiguration.self
        guard let a = class_getClassMethod(cls, NSSelectorFromString(original)),
              let b = class_getClassMethod(cls, replacement) else { return }
        method_exchangeImplementations(a, b)
    }
}

extension URLSessionConfiguration {
    // After swizzling, calling these names invokes the original implementations.
    @objc dynamic class func reflex_guardedDefault() -> URLSessionConfiguration {
        guarded(reflex_guardedDefault())
    }
    @objc dynamic class func reflex_guardedEphemeral() -> URLSessionConfiguration {
        guarded(reflex_guardedEphemeral())
    }
    private static func guarded(_ configuration: URLSessionConfiguration) -> URLSessionConfiguration {
        configuration.protocolClasses = [BlockingURLProtocol.self] + (configuration.protocolClasses ?? [])
        return configuration
    }
}

final class BlockingURLProtocol: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool {
        ["http", "https"].contains(request.url?.scheme?.lowercased() ?? "")
    }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        NetworkGuard.sink?.networkViolation(method: request.httpMethod ?? "GET", url: request.url?.absoluteString ?? "")
        client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet))
    }
    override func stopLoading() {}
}

import AppCore
import Foundation
import LocalAuthentication
import Security

// Real implementations of Core's platform protocols (AppCore/Foundation/Platform.swift).
// Their fixture counterparts live in ios/AppCLI (FixtureAdapters.swift). Keep each pair in sync.

@MainActor
final class KeychainStorage: Storage {
    private let service: String

    init(service: String) { self.service = service }

    func read(_ key: String) -> String? {
        var query = baseQuery(key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func write(_ key: String, _ value: String?) {
        SecItemDelete(baseQuery(key) as CFDictionary)
        guard let value else { return }
        var query = baseQuery(key)
        query[kSecValueData as String] = Data(value.utf8)
        SecItemAdd(query as CFDictionary, nil)
    }

    private func baseQuery(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: key]
    }
}

struct URLSessionTransport: GraphQLTransport {
    let endpoint: URL
    var session: URLSession = .shared

    func execute(_ request: GraphQLRequest) async throws -> Data {
        var urlRequest = URLRequest(url: endpoint)
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let variables = (try? JSONSerialization.jsonObject(with: request.variables)) ?? [String: Any]()
        urlRequest.httpBody = try JSONSerialization.data(withJSONObject: [
            "operationName": request.operationName, "query": request.query, "variables": variables,
        ])
        let data: Data
        let response: URLResponse
        do { (data, response) = try await session.data(for: urlRequest) } catch {
            throw GraphQLTransportError.network(error.localizedDescription)
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw GraphQLTransportError.http(status: status) }
        return data
    }
}

@MainActor
final class SystemDeviceCapabilities: DeviceCapabilities {
    var biometricsAvailable: Bool { LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil) }
}

/// Placeholder: swap for remote config when the product needs it.
@MainActor
final class LocalFeatureFlags: FeatureFlags {
    private let enabled: Set<String>
    init(enabled: Set<String> = []) { self.enabled = enabled }
    func isEnabled(_ flag: String) -> Bool { enabled.contains(flag) }
}

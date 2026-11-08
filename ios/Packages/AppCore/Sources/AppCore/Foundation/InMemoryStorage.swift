/// `Storage` without a device: used by the CLI (seeded from a fixture's `storage`) and handy for previews.
public actor InMemoryStorage: Storage {
    private var values: [String: String]

    public init(_ values: [String: String] = [:]) { self.values = values }

    public func read(_ key: String) -> String? { values[key] }
    public func write(_ key: String, _ value: String?) { values[key] = value }
}

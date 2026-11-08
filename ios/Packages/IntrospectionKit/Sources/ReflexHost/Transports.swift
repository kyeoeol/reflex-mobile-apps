import Foundation
import IntrospectionKit
#if canImport(Network)
import Network
#endif

/// Serialized line output to a file handle (stdout by default).
public final class LineWriter: @unchecked Sendable {
    public static let stdout = LineWriter(FileHandle.standardOutput)
    private let handle: FileHandle
    private let lock = NSLock()

    public init(_ handle: FileHandle) { self.handle = handle }

    public func write(_ line: String) {
        lock.lock(); defer { lock.unlock() }
        handle.write(Data((line + "\n").utf8))
    }
}

/// Headless transport: NDJSON requests on stdin, responses and events on stdout.
/// stdin is read on a background thread; every request is handled on the main actor, in order.
public enum StdioTransport {
    @MainActor
    public static func run(_ host: ReflexHost) -> Never {
        let out = LineWriter.stdout
        host.events.addWriter { out.write($0) }
        out.write(host.readyEvent)
        Thread.detachNewThread { readLines(into: host, replyingTo: out) }
        // Not dispatchMain(): it exits the main pthread, and MainActor work must run on the main thread.
        while true { RunLoop.main.run() }
    }

    /// Runs on a background thread; hops to the main actor for every request.
    private nonisolated static func readLines(into host: ReflexHost, replyingTo out: LineWriter) {
        while let line = readLine(strippingNewline: true) {
            DispatchQueue.main.async {
                MainActor.assumeIsolated { host.handle(line) { out.write($0) } }
            }
        }
        DispatchQueue.main.async { exit(0) }
    }
}

#if canImport(Network)
/// DEBUG-build transport: the same protocol over TCP on 127.0.0.1, talking to the ViewModels the screen
/// is bound to. The iOS simulator shares the host's network, so `reflex cli ios --live` connects directly.
/// Never start this in a release build.
@MainActor
public final class ReflexSocketServer {
    private let host: ReflexHost
    private let listener: NWListener
    private var connections: [ObjectIdentifier: NWConnection] = [:]

    public init(host: ReflexHost, port: UInt16 = 7777) throws {
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!)
        self.listener = try NWListener(using: parameters)
        self.host = host
    }

    public func start() {
        listener.newConnectionHandler = { [weak self] connection in
            MainActor.assumeIsolated { self?.accept(connection) }
        }
        listener.start(queue: .main)
    }

    private func accept(_ connection: NWConnection) {
        let key = ObjectIdentifier(connection)
        connections[key] = connection
        let send: @Sendable (String) -> Void = { line in connection.send(content: Data((line + "\n").utf8), completion: .idempotent) }
        let token = host.events.addWriter(send)
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled:
                MainActor.assumeIsolated {
                    self?.host.events.removeWriter(token)
                    self?.connections[key] = nil
                }
            default: break
            }
        }
        connection.start(queue: .main)
        send(host.readyEvent)
        receive(on: connection, buffer: Data(), send: send)
    }

    private func receive(on connection: NWConnection, buffer: Data, send: @escaping @Sendable (String) -> Void) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, isComplete, error in
            MainActor.assumeIsolated {
                guard let self else { return }
                var pending = buffer + (data ?? Data())
                while let newline = pending.firstIndex(of: 0x0A) {
                    let line = String(decoding: pending[pending.startIndex..<newline], as: UTF8.self)
                    pending = Data(pending[pending.index(after: newline)...])
                    self.host.handle(line) { send($0) }
                }
                if isComplete || error != nil { connection.cancel(); return }
                self.receive(on: connection, buffer: pending, send: send)
            }
        }
    }
}
#endif

// swift-tools-version: 6.0
import PackageDescription

// IntrospectionKit: the contract between ViewModels and anything outside the app process.
//   - IntrospectionKit: node protocol, JSONValue, input schemas, focus resolution. Core depends on this.
//   - ReflexHost: NDJSON protocol host, fixtures, stdio/socket transports, network guard.
//     Used only by the CLI and by the Mobile app's DEBUG-only socket server. Core never imports it.
// No third-party dependencies, by design.
let package = Package(
    name: "IntrospectionKit",
    platforms: [.macOS(.v14), .iOS(.v17)],
    products: [
        .library(name: "IntrospectionKit", targets: ["IntrospectionKit"]),
        .library(name: "ReflexHost", targets: ["ReflexHost"]),
    ],
    targets: [
        .target(name: "IntrospectionKit"),
        .target(name: "ReflexHost", dependencies: ["IntrospectionKit"]),
        .testTarget(name: "IntrospectionKitTests", dependencies: ["IntrospectionKit", "ReflexHost"]),
    ]
)

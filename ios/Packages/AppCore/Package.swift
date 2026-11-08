// swift-tools-version: 6.0
import PackageDescription

// AppCore: everything except the views. No UIKit/SwiftUI/AppKit imports, no device-dependent code.
// It must build and run headlessly on macOS (`swift build` here is the litmus test).
// Platform needs are declared as protocols in Foundation/Platform.swift and injected by the host.
let package = Package(
    name: "AppCore",
    platforms: [.macOS(.v14), .iOS(.v17)],
    products: [.library(name: "AppCore", targets: ["AppCore"])],
    dependencies: [.package(path: "../IntrospectionKit")],
    targets: [
        .target(name: "AppCore", dependencies: [.product(name: "IntrospectionKit", package: "IntrospectionKit")]),
    ]
)

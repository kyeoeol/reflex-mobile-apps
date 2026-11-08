// swift-tools-version: 6.0
import PackageDescription

// AppCLI: the headless iOS host. Constructs Core with fixtures and speaks NDJSON over stdio.
// Driven by `./reflex cli ios` and by the Bun behavior tests; never shipped.
let package = Package(
    name: "AppCLI",
    platforms: [.macOS(.v14)],
    dependencies: [
        .package(path: "../Packages/AppCore"),
        .package(path: "../Packages/IntrospectionKit"),
    ],
    targets: [
        .executableTarget(name: "AppCLI", dependencies: [
            .product(name: "AppCore", package: "AppCore"),
            .product(name: "IntrospectionKit", package: "IntrospectionKit"),
            .product(name: "ReflexHost", package: "IntrospectionKit"),
        ]),
    ]
)

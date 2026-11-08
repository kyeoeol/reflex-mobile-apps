// ReflexHost: NDJSON protocol host, fixtures, stdio/socket transports, network guard.
// Used by the CLI and (debugImplementation only) by the app's debug socket server. Core never depends on it.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":introspection-kit"))
    api(libs.kotlinx.coroutines.core)
}

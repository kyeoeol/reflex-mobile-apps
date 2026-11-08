// Core: everything except the views. Pure JVM: no android.* / androidx.* imports, ever.
// If it doesn't build here, it isn't Core.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":introspection-kit"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
}

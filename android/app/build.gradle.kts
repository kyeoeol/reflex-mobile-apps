// Mobile: Compose views + real platform integrations. Views read `state` and call `send(action)`; nothing else.
// The debug socket server is wired in through `debugImplementation` only, so release builds don't contain it.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "app.reflex.mobile"
    compileSdk = 37

    defaultConfig {
        // Non-store beta identifier (public repo: no personal/team identifiers or signing config here).
        applicationId = "com.beta.health"
        minSdk = 37
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "GRAPHQL_ENDPOINT", "\"https://example.invalid/graphql\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Room schemas are exported and committed: every schema change needs a migration (no destructive fallback).
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    debugImplementation(project(":reflex-host"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.lifecycle.process)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    debugImplementation(libs.compose.ui.tooling)
}

// Mobile: Compose views + real platform integrations. Views read `state` and call `send(action)`; nothing else.
// The debug socket server is wired in through `debugImplementation` only, so release builds don't contain it.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.reflex.mobile"
    compileSdk = 37

    defaultConfig {
        // Placeholder identifier: change it when the product is decided.
        applicationId = "app.reflex.mobile"
        minSdk = 26
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
    debugImplementation(libs.compose.ui.tooling)
}

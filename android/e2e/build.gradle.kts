// End-to-end UI tests (docs/health/E2E.md): a self-instrumenting test APK that drives the installed debug app only
// through UI Automator (resource ids from Compose test tags, contract literals) and the real system UI (permission
// dialog, notification shade). Being its own process, a journey can send the app Home, kill it and relaunch it.
// Run through tools/e2e-android.sh (`./reflex e2e android`).
plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "app.reflex.e2e"
    compileSdk = 37

    defaultConfig {
        minSdk = 37
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
    // Instrument this APK itself, not the app: the app under test runs (and is killed) as a separate process.
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.uiautomator)
}

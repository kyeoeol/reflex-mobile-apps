pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "reflex-android"

// Headless modules: pure JVM. They (and the behavior tests) never need the Android SDK or an emulator.
include(":introspection-kit", ":reflex-host", ":core", ":cli")

// The Compose app needs the Android SDK. Skip it when there is none, or when REFLEX_SKIP_APP=1
// (headless CI jobs: behavior tests never need it).
val skipApp = System.getenv("REFLEX_SKIP_APP") == "1"
val androidSdkAvailable = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
if (!skipApp && androidSdkAvailable && file("app").isDirectory) include(":app")

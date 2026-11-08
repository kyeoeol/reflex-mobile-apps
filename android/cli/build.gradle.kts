// The headless Android host: constructs Core with fixtures and speaks NDJSON over stdio.
// `./gradlew :cli:fatJar` → cli/build/libs/cli.jar (what the Bun harness launches).
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("app.reflex.cli.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":reflex-host"))
}

tasks.register<Jar>("fatJar") {
    archiveFileName.set("cli.jar")
    manifest { attributes["Main-Class"] = "app.reflex.cli.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    val runtime = configurations.runtimeClasspath
    dependsOn(runtime)
    from({ runtime.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class", "module-info.class")
}

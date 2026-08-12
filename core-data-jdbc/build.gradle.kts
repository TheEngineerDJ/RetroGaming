plugins {
    alias(libs.plugins.kotlin.jvm)
}

// JVM SQLite binding.
//
// Exists so the schema, the repositories and the whole pipeline can be tested
// against a real SQLite engine without an Android device. The Android build
// supplies its own binding for the same interface.
dependencies {
    api(project(":core-data"))
    implementation(libs.sqlite.jdbc)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

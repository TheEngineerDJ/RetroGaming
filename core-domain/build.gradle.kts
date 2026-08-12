plugins {
    id("retrovault.kotlin-jvm")
}

// core-domain is the innermost layer.
//
// It must never depend on Android, Compose, SQLite, filesystem APIs, network
// clients, or coroutines infrastructure. Everything here is pure, synchronous
// and deterministic so that every identity rule is testable without a device.
dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

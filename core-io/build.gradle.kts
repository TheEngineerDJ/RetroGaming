plugins {
    id("retrovault.kotlin-jvm")
}

// Streaming content inspection: hashing and archive reading.
//
// Uses only java.io, java.util.zip and java.security, all of which exist on
// both the JVM and Android, so the Android adapter reuses this code rather
// than reimplementing identity-critical logic.
dependencies {
    api(project(":core-domain"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

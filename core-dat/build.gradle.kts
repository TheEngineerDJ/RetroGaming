plugins {
    id("retrovault.kotlin-jvm")
}

// Streaming DAT ingestion.
//
// The parser is infrastructure and the parsed data is evidence
// (Constitution section 146). It depends on the domain for value types and on
// nothing else: no Android, no XML library, no network.
dependencies {
    api(project(":core-domain"))
    api(project(":core-application"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

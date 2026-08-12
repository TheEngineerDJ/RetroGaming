plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Use cases and the ports they depend on.
//
// Depends on the domain and on coroutines. It must never depend on an
// infrastructure module: adapters implement these interfaces, not the other
// way round (ENGINEERING_SPEC.md section 7).
dependencies {
    api(project(":core-domain"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

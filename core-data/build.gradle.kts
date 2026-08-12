plugins {
    alias(libs.plugins.kotlin.jvm)
}

// SQLite persistence.
//
// Depends on a small driver abstraction it owns, never on a concrete SQLite
// binding, so the same schema and repositories run under JDBC on the JVM and
// under android.database on a device (ARCHITECTURE.md section 10).
dependencies {
    api(project(":core-domain"))
    api(project(":core-application"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core-data-jdbc"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

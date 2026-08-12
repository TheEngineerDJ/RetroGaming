plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Local-filesystem implementations of the application ports.
//
// Serves two purposes: it is the desktop/CI host for the pipeline, and it lets
// the end-to-end tests exercise the real scan, plan, validate and rename flow
// against real files without an Android device.
dependencies {
    api(project(":core-application"))
    implementation(project(":core-io"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core-dat"))
    testImplementation(project(":core-data"))
    testImplementation(project(":core-data-jdbc"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

plugins {
    `kotlin-dsl`
}

// Build conventions shared by every pure-JVM module.
//
// The Kotlin Gradle plugin is declared as an explicit dependency here rather
// than being reached for from the root build script. That is the difference
// between a build that works and one that fails with "Unresolved reference:
// org.jetbrains.kotlin": a convention plugin compiled against a declared
// dependency resolves its types the same way on every machine.
dependencies {
    implementation(libs.kotlin.gradle.plugin)
}

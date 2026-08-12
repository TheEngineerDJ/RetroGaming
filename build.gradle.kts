// Root project intentionally declares no plugins and configures no subprojects.
//
// Shared JVM configuration lives in the `retrovault.kotlin-jvm` convention
// plugin under `buildSrc`, which declares the Kotlin Gradle plugin as an
// explicit dependency and therefore resolves its types identically on every
// machine.
//
// Two failures pushed the configuration here rather than leaving it in a
// `subprojects` block:
//
//  1. Declaring the Kotlin JVM plugin at the root while Android modules apply
//     the Kotlin Android plugin loads the Kotlin Gradle plugin twice, which
//     Gradle rejects as a classpath conflict. It only appears once an Android
//     SDK is present, because that is when the Android modules are included.
//  2. Removing that root declaration then leaves a `subprojects` block that
//     cannot resolve `org.jetbrains.kotlin.gradle.dsl.*` at all, because those
//     types were only on the classpath by virtue of the declaration.
//
// buildSrc resolves both: its output is on every project's buildscript
// classpath, so there is one Kotlin Gradle plugin, loaded once, with its types
// available where they are used.

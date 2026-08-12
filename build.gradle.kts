// Intentionally empty.
//
// Shared JVM configuration lives in the `retrovault.kotlin-jvm` convention
// plugin under `buildSrc`, which declares the Kotlin Gradle plugin explicitly.
// Configuring subprojects from here would require this script to resolve
// another plugin's types, which only works when that plugin happens to be on
// the root buildscript classpath - and fails with "Unresolved reference:
// org.jetbrains.kotlin" when it is not.
//
// Nothing Android-specific is declared here either. Android modules apply
// their own versioned plugins, so a contributor without an Android SDK never
// downloads the Android Gradle plugin at all.

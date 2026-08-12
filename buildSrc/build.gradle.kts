import java.util.Properties

plugins {
    `kotlin-dsl`
}

/**
 * Whether an Android SDK is available.
 *
 * This must agree with the identical check in the root `settings.gradle.kts`,
 * which decides whether the Android modules are part of the build at all. The
 * two are deliberately kept in step: the Android Gradle plugin is only needed
 * on the classpath when there are Android modules to apply it to.
 */
val androidSdkAvailable: Boolean = run {
    val rootDirectory = projectDir.parentFile
    val fromEnv = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    val fromLocalProperties = File(rootDirectory, "local.properties")
        .takeIf { it.exists() }
        ?.let { propertiesFile ->
            // `java` alone would resolve to Gradle's java extension here,
            // not the package, so the type is imported above.
            Properties().apply { propertiesFile.inputStream().use(::load) }.getProperty("sdk.dir")
        }
    val sdkPath = fromLocalProperties ?: fromEnv
    !sdkPath.isNullOrBlank() && File(sdkPath).isDirectory
}

// Build conventions, and the single classpath every Gradle plugin in this
// build is loaded from.
//
// Plugins are declared here rather than resolved per module because they have
// to share one classloader:
//
//  - The Kotlin Gradle plugin must be loaded exactly once. Declaring it at the
//    root while an Android module resolves its own copy loads it twice, which
//    Gradle rejects as a classpath conflict.
//  - The Kotlin *Android* plugin references Android Gradle plugin types
//    (`com.android.build.gradle.api.BaseVariant`). With Kotlin loaded from
//    buildSrc and Android resolved into a module's own buildscript, Kotlin
//    sits in the parent classloader and cannot see Android in the child, so
//    applying it fails with `NoClassDefFoundError`.
//
// The Android plugins are added only when an Android SDK is present. Adding
// them unconditionally would make the Android Gradle plugin a hard requirement
// for everyone, including contributors who cannot reach Google's Maven
// repository - and would fail their build before a single JVM module compiled.
dependencies {
    implementation(libs.kotlin.gradle.plugin)

    if (androidSdkAvailable) {
        implementation(libs.compose.compiler.gradle.plugin)
        implementation(libs.android.gradle.plugin)
    }
}

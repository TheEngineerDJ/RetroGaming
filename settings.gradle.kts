pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "retrovault"

// ---------------------------------------------------------------------------
// Platform-independent core. Always built, always testable on a plain JVM.
// Dependency direction: UI -> Application -> Domain <- Infrastructure
// ---------------------------------------------------------------------------
include(":core-domain")
include(":core-application")
include(":core-dat")
include(":core-data")
include(":core-data-jdbc")

// ---------------------------------------------------------------------------
// Android modules require the Android SDK. They are included only when an SDK
// is actually available so that `./gradlew test` remains runnable on a plain
// JDK (CI, headless build agents, contributors without the SDK installed).
//
// This is a build-tooling decision only. It does not change layering: the
// Android modules are infrastructure/presentation and hold no domain rules.
// ---------------------------------------------------------------------------
val androidSdkAvailable: Boolean = run {
    val fromEnv = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    val fromLocalProperties = file("local.properties")
        .takeIf { it.exists() }
        ?.let { propertiesFile ->
            java.util.Properties().apply { propertiesFile.inputStream().use(::load) }.getProperty("sdk.dir")
        }
    val sdkPath = fromLocalProperties ?: fromEnv
    !sdkPath.isNullOrBlank() && file(sdkPath).isDirectory
}

if (androidSdkAvailable) {
    include(":platform-android")
    include(":app")
} else {
    logger.lifecycle(
        "RetroVault: Android SDK not found - skipping :platform-android and :app. " +
            "Core modules (domain, application, dat, data) still build and test."
    )
}

dependencyResolutionManagement {
    repositories {
        // Consulted only for Android artifacts, and only when an Android SDK
        // is present (see build.gradle.kts). The content filter keeps a build
        // that cannot reach this host from failing on unrelated dependencies.
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

    // Reuse the project's version catalog so the Kotlin version used to compile
    // the convention plugin cannot drift from the one used to compile the code.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"

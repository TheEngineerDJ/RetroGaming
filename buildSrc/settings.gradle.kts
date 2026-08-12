dependencyResolutionManagement {
    repositories {
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

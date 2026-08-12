// Every plugin is applied without a version. buildSrc puts the Kotlin and
// Android Gradle plugins on one shared buildscript classpath; naming a version
// here would resolve a second copy into this module's own classloader, where
// the two plugins can no longer see each other.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.retrovault.platform.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Android infrastructure only: Storage Access Framework and the platform
// SQLite binding. No identity, naming or rename rules live here
// (ROM_INTELLIGENCE.md section 16).
dependencies {
    api(project(":core-application"))
    api(project(":core-data"))
    implementation(project(":core-io"))
    implementation(project(":core-dat"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}

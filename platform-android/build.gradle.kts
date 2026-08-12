plugins {
    alias(libs.plugins.android.library)
    // Applied without a version: buildSrc already puts the Kotlin Gradle
    // plugin on every buildscript classpath. Re-declaring a version here
    // loads it a second time and Gradle rejects that as a classpath conflict.
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

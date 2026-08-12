plugins {
    alias(libs.plugins.android.application)
    // See platform-android: the Kotlin plugin comes from buildSrc, so no
    // version is declared here. The Compose compiler plugin is a separate
    // artifact and is still resolved through the catalog.
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.retrovault.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.retrovault.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

// Presentation only.
//
// Compose renders application state and collects user intent. It contains no
// matching, naming or rename rules (ENGINEERING_SPEC.md section 4).
dependencies {
    implementation(project(":core-application"))
    implementation(project(":core-data"))
    implementation(project(":core-dat"))
    implementation(project(":platform-android"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
}

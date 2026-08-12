import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Shared configuration for every platform-independent module.
 *
 * Android modules deliberately do not use this: they configure themselves, so
 * no Android concern can leak into the core through a shared convention.
 */
plugins {
    kotlin("jvm")
}

kotlin {
    compilerOptions {
        // JVM 17 bytecode keeps the core modules consumable by the Android
        // build without requiring a matching local toolchain.
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // Lets a module assert its own dependency boundaries from a test, so the
    // rule in ENGINEERING_SPEC.md section 1 is enforced by the build rather
    // than by review.
    systemProperty(
        "retrovault.moduleSourceDir",
        layout.projectDirectory.dir("src/main/kotlin").asFile.absolutePath,
    )

    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

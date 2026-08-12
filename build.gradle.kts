plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

// Shared configuration for every pure-JVM module.
//
// Android modules configure themselves; they are deliberately excluded here so
// that no Android concern leaks into the platform-independent core.
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
            compilerOptions {
                // JVM 17 bytecode keeps the core modules consumable by the
                // Android build without requiring a matching local toolchain.
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                allWarningsAsErrors.set(true)
                freeCompilerArgs.add("-Xjvm-default=all")
            }
        }

        tasks.withType<JavaCompile>().configureEach {
            options.release.set(17)
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            // Lets a module assert its own dependency boundaries from a test,
            // so the rule in ENGINEERING_SPEC.md section 1 is enforced by the
            // build rather than by review.
            systemProperty(
                "retrovault.moduleSourceDir",
                layout.projectDirectory.dir("src/main/kotlin").asFile.absolutePath,
            )
            testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}

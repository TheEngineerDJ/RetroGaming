plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

// Shared configuration for every pure-JVM module.
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
            compilerOptions {
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

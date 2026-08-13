package com.retrovault.domain

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The dependency rule, enforced.
 *
 * ARCHITECTURE.md section 4 forbids `Domain → Android`, `Domain → Compose`,
 * `Domain → SQLite`, `Domain → HTTP client` and `Domain → filesystem`, and adds
 * that no shortcut is justified merely because it is faster to implement.
 * TESTING_SPEC.md section 12 makes "core domain depends on Android/UI" a
 * release blocker.
 *
 * A rule that only exists in a document gets broken during a late-evening fix,
 * so it is checked here instead. This test reads the module's own sources and
 * fails on any forbidden import.
 */
class ArchitectureBoundaryTest {

    private val forbiddenPrefixes = listOf(
        "android." to "Android framework",
        "androidx." to "AndroidX",
        "kotlinx.coroutines" to "coroutines (the domain must stay synchronous and pure)",
        "java.io." to "filesystem I/O",
        "java.nio.file" to "filesystem I/O",
        "java.sql" to "SQL",
        "java.net" to "network access",
        "javax.xml" to "XML parsing (an infrastructure concern)",
        "org.xmlpull" to "XML parsing (an infrastructure concern)",
        "java.security" to "platform cryptography (hashing belongs in infrastructure)",
    )

    private val sourceDirectory: File
        get() {
            val path = System.getProperty("retrovault.moduleSourceDir")
                ?: fail("retrovault.moduleSourceDir is not set; the build must provide it")
            return File(path)
        }

    @Test
    fun `the domain imports nothing from an outer layer`() {
        val sources = sourceDirectory.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty(), "No domain sources were found at $sourceDirectory")

        val violations = mutableListOf<String>()
        sources.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("import ")) return@forEachIndexed
                val imported = trimmed.removePrefix("import ").substringBefore(" as ")
                forbiddenPrefixes.forEach { (prefix, description) ->
                    if (imported.startsWith(prefix)) {
                        violations += "${file.name}:${index + 1} imports $imported ($description)"
                    }
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "The domain must not depend on any outer layer:\n" + violations.joinToString("\n"),
        )
    }

    @Test
    fun `the domain does not reach the filesystem or the clock through the platform`() {
        val sources = sourceDirectory.walkTopDown().filter { it.extension == "kt" }.toList()
        val violations = mutableListOf<String>()

        sources.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                // System.currentTimeMillis in the domain would make results
                // depend on wall-clock time and stop them being reproducible
                // (ENGINEERING_SPEC.md section 11). Time is injected instead.
                if (line.contains("System.currentTimeMillis") || line.contains("System.nanoTime")) {
                    violations += "${file.name}:${index + 1} reads the system clock directly"
                }
                if (line.contains("java.io.File(") || line.contains("Files.")) {
                    violations += "${file.name}:${index + 1} touches the filesystem"
                }
            }
        }

        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    @Test
    fun `a forbidden dependency cannot hide behind a fully qualified name`() {
        // The check above reads import lines. Writing the same dependency as a
        // fully qualified name in the body would satisfy the compiler and slip
        // past it, so the body is checked too. Comments are excluded: a rule is
        // allowed to explain what it forbids.
        val path = System.getProperty("retrovault.moduleSourceDir")
            ?: fail("retrovault.moduleSourceDir is not set; the build must provide it")
        val violations = mutableListOf<String>()

        File(path).walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("import ") || code.startsWith("package ") ||
                    code.startsWith("*") || code.startsWith("/*")
                ) {
                    return@forEachIndexed
                }
                forbiddenPrefixes.forEach { (prefix, description) ->
                    if (code.contains(prefix)) {
                        violations += "${file.name}:${index + 1} names $prefix inline ($description)"
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }
}

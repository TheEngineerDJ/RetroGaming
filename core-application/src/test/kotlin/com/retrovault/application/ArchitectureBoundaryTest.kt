package com.retrovault.application

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The application layer's boundary.
 *
 * ENGINEERING_SPEC.md section 7: the application depends on interfaces, not on
 * implementations. It may use coroutines, but an Android, SQLite, XML or
 * filesystem type crossing this boundary would mean a port is leaking an
 * infrastructure concern into the use cases.
 */
class ArchitectureBoundaryTest {

    private val forbiddenPrefixes = listOf(
        "android." to "Android framework",
        "androidx." to "AndroidX",
        "java.io." to "filesystem or stream I/O (ports must not expose streams)",
        "java.nio.file" to "filesystem access",
        "java.sql" to "SQL",
        "java.net" to "network access",
        "javax.xml" to "XML parsing",
        "com.retrovault.data" to "a persistence implementation",
        "com.retrovault.dat" to "a parser implementation",
        "com.retrovault.io" to "an I/O implementation",
        "com.retrovault.platform" to "a platform implementation",
        "com.retrovault.app" to "the presentation layer",
    )

    @Test
    fun `the application layer depends on ports, never on implementations`() {
        val path = System.getProperty("retrovault.moduleSourceDir")
            ?: fail("retrovault.moduleSourceDir is not set; the build must provide it")
        val sources = File(path).walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty(), "No application sources were found at $path")

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
            "The application layer must not depend on an implementation:\n" +
                violations.joinToString("\n"),
        )
    }
}

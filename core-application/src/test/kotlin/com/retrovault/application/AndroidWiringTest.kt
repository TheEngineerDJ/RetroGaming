package com.retrovault.application

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Android layer, checked without an Android SDK.
 *
 * `settings.gradle.kts` excludes `:app` and `:platform-android` when no SDK is
 * present, which is how the core stays buildable on a plain JDK. The cost is
 * that on those machines - CI among them - nothing compiles the Android
 * sources, so a port that gains a method or a type that gets renamed breaks
 * them silently and stays broken until someone opens the project with an SDK
 * installed.
 *
 * This reads the Android sources as text and checks the two things that go
 * wrong in that gap:
 *
 * 1. every `com.retrovault.*` symbol they import still exists, and
 * 2. the composition root passes the arguments that are *optional* on a use
 *    case, because those are exactly the ones a compiler cannot miss for you.
 *
 * It is not a substitute for compiling. It catches the drift that would
 * otherwise be discovered on a device.
 */
class AndroidWiringTest {

    private val repoRoot: File
        get() {
            val path = System.getProperty("retrovault.repoRoot")
                ?: fail("retrovault.repoRoot is not set; the build must provide it")
            return File(path)
        }

    private val androidSources: List<File>
        get() = listOf("app/src/main/kotlin", "platform-android/src/main/kotlin")
            .map { repoRoot.resolve(it) }
            .flatMap { directory ->
                if (directory.isDirectory) {
                    directory.walkTopDown().filter { it.extension == "kt" }.toList()
                } else {
                    emptyList()
                }
            }

    private val coreSources: List<File>
        get() = listOf(
            "core-domain/src/main/kotlin",
            "core-application/src/main/kotlin",
            "core-data/src/main/kotlin",
            "core-dat/src/main/kotlin",
            "core-io/src/main/kotlin",
        )
            .map { repoRoot.resolve(it) }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { file -> file.extension == "kt" }.toList() }

    /**
     * Every top-level name declared anywhere in the project, qualified.
     *
     * The Android sources are included alongside the core ones because they
     * import each other. What matters is that no import points at nothing.
     */
    private fun declaredCoreSymbols(): Set<String> {
        val declaration = Regex(
            "^\\s*(?:(?:public|internal|abstract|open|sealed|data|value|enum|annotation|fun|inline)\\s+)*" +
                "(?:class|interface|object|enum class|annotation class)\\s+([A-Za-z_][A-Za-z0-9_]*)",
        )
        val topLevelCallable = Regex(
            "^(?:public\\s+|internal\\s+|inline\\s+|suspend\\s+|operator\\s+)*" +
                "(?:fun|val|var)\\s+(?:<[^>]*>\\s*)?(?:[A-Za-z0-9_.<>?, ]+\\.)?([A-Za-z_][A-Za-z0-9_]*)",
        )
        val symbols = mutableSetOf<String>()
        (coreSources + androidSources).forEach { file ->
            val packageName = file.useLines { lines ->
                lines.firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim()
            } ?: return@forEach
            file.readLines().forEach { line ->
                declaration.find(line)?.groupValues?.get(1)?.let { symbols += "$packageName.$it" }
                topLevelCallable.find(line)?.groupValues?.get(1)?.let { symbols += "$packageName.$it" }
            }
        }
        return symbols
    }

    @Test
    fun `every core symbol the Android layer imports still exists`() {
        val sources = androidSources
        assertTrue(sources.isNotEmpty(), "No Android sources were found under $repoRoot")

        val declared = declaredCoreSymbols()
        val missing = mutableListOf<String>()

        sources.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("import com.retrovault.")) return@forEachIndexed
                val imported = trimmed.removePrefix("import ").substringBefore(" as ").trim()
                // A nested name (`MatchSignal.HashExact`) resolves through its
                // outer declaration, so the check walks back to the longest
                // prefix that is a known top-level symbol.
                val resolves = generateSequence(imported) { candidate ->
                    candidate.substringBeforeLast('.', "").takeIf { it.isNotEmpty() }
                }.any { it in declared }
                if (!resolves) missing += "${file.name}:${index + 1} imports $imported, which no core module declares"
            }
        }

        assertTrue(
            missing.isEmpty(),
            "The Android layer references core symbols that no longer exist:\n" + missing.joinToString("\n"),
        )
    }

    /**
     * Compose DSL functions that are members, not top-level declarations.
     *
     * `LazyListScope.items` is a top-level extension and imports fine;
     * `LazyListScope.item` is an interface member and cannot be imported at
     * all. The pair looks symmetric, so importing both is the natural mistake -
     * and it costs a device build to find, because nothing here compiles
     * Compose. Each of these resolves on its own from the receiver; naming it
     * in an import is always wrong.
     */
    @Test
    fun `no screen imports a Compose function that is a member rather than a declaration`() {
        val memberOnly = setOf(
            "androidx.compose.foundation.lazy.item",
            "androidx.compose.foundation.lazy.stickyHeader",
            "androidx.compose.foundation.lazy.grid.item",
            "androidx.compose.foundation.pager.item",
        )
        val offenders = mutableListOf<String>()

        androidSources.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val imported = line.trim().removePrefix("import ").substringBefore(" as ").trim()
                if (line.trim().startsWith("import ") && imported in memberOnly) {
                    offenders += "${file.name}:${index + 1} imports $imported, which is a scope member"
                }
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "These resolve from their receiver and cannot be imported:\n" + offenders.joinToString("\n"),
        )
    }

    /**
     * The optional wiring the compiler cannot check for you.
     *
     * [ScanLocationUseCase] takes corrections and the entity graph as nullable
     * parameters so a caller written before they existed still compiles. That
     * is the right default for the port and a trap for the composition root: a
     * container that omits them produces a scan which ignores every user
     * correction and projects nothing into the graph, and every JVM test still
     * passes because the tests wire them.
     */
    @Test
    fun `the Android composition root wires corrections and the entity graph into a scan`() {
        val container = repoRoot.resolve("app/src/main/kotlin/com/retrovault/app/RetroVaultContainer.kt")
        assertTrue(container.isFile, "The composition root is missing at $container")
        val scanCall = argumentsOf(container.readText(), "ScanLocationUseCase(")

        assertTrue(scanCall.isNotBlank(), "The container no longer constructs ScanLocationUseCase")
        listOf("applyCorrections", "corrections", "entities").forEach { parameter ->
            assertTrue(
                scanCall.contains("$parameter ="),
                "The container's scan does not pass '$parameter', so that behaviour is silently absent " +
                    "on a device even though every JVM test passes",
            )
        }
    }

    /**
     * The knowledge layer has a way in.
     *
     * Every use case below existed, was tested, and was reachable by nobody:
     * the app could scan and rename and nothing else. A capability wired into
     * the container but referenced by no screen is not a feature, and this is
     * the check that says so.
     */
    @Test
    fun `the screens reach the corrections, history and undo the core provides`() {
        val ui = androidSources.filter { it.path.contains("/app/") }.joinToString("\n") { it.readText() }
        assertTrue(ui.isNotBlank(), "No app sources were found")

        listOf(
            "reviewObservation" to "correcting a file",
            "browseLibrary" to "browsing the entity graph",
            "renameHistory" to "reading the rename history",
            "undoRenames" to "putting a rename batch back",
        ).forEach { (capability, description) ->
            assertTrue(
                ui.contains("container.$capability"),
                "Nothing in the UI reaches '$capability', so $description is built but unreachable",
            )
        }
    }

    /**
     * The one promise the UI made and could not keep.
     *
     * The reconciliation notice told the user to consult a history screen that
     * did not exist. For a product whose premise is trustworthiness, pointing
     * at absent evidence right after mutating files is the worst kind of bug,
     * so the claim and the screen are checked together.
     */
    @Test
    fun `a message that points the user at history is backed by a history screen`() {
        val ui = androidSources.filter { it.path.contains("/app/") }
        val mentionsHistory = ui.any { file ->
            file.readText().contains("See history", ignoreCase = true)
        }
        val hasHistoryScreen = ui.any { it.name == "HistorySheet.kt" } &&
            ui.any { it.readText().contains("HistorySheet(") }

        assertTrue(
            !mentionsHistory || hasHistoryScreen,
            "The UI tells the user to see history, so a history screen has to exist",
        )
    }

    /**
     * The argument list of one call, to its matching close parenthesis.
     *
     * Nested calls mean the first `)` is usually not the end of the list.
     */
    private fun argumentsOf(source: String, call: String): String {
        val start = source.indexOf(call).takeIf { it >= 0 }?.plus(call.length) ?: return ""
        var depth = 1
        for (index in start until source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return source.substring(start, index)
            }
        }
        return ""
    }

    /** Each port the app depends on has exactly one Android implementation. */
    @Test
    fun `every port the app needs is bound on Android`() {
        val platform = androidSources.filter { it.path.contains("platform-android") }.joinToString("\n") {
            it.readText()
        }
        assertTrue(platform.isNotBlank(), "No platform-android sources were found")

        listOf("DirectoryWalker", "ContentSource", "RenameExecutor", "SqlDatabase", "DatByteSource")
            .forEach { port ->
                assertTrue(
                    Regex(":\\s*$port\\b").containsMatchIn(platform) ||
                        Regex(",\\s*$port\\b").containsMatchIn(platform),
                    "No Android class implements $port",
                )
            }
    }

    /**
     * A read failure must never be reported as a file that is not there.
     *
     * The validator and the reconciler both derive "storage did not answer"
     * from a failed `stat`, so a binding that returns a successful
     * `ArtifactState(exists = false)` when it could not read turns a permission
     * problem into "your file is gone". The JVM binding gets this right; this
     * keeps the Android one honest.
     */
    @Test
    fun `the SAF binding reports an unreadable file as a failure, not as an absence`() {
        val saf = repoRoot
            .resolve("platform-android/src/main/kotlin/com/retrovault/platform/android/SafStorage.kt")
        assertTrue(saf.isFile, "SafStorage.kt is missing at $saf")
        val stat = saf.readText().substringAfter("override suspend fun stat(").substringBefore("override suspend fun listNames")

        assertTrue(stat.isNotBlank(), "SafContentSource no longer implements stat")
        assertTrue(
            stat.contains("cursor == null") && stat.contains("Outcome.failure"),
            "A provider that returns no cursor must produce a failure, not an observed absence",
        )
        assertTrue(
            !Regex("catch \\(failure: IllegalArgumentException\\) \\{\\s*Outcome\\.success").containsMatchIn(stat),
            "An unaddressable URI is a failure to look, not proof that a file is absent",
        )
    }
}

package com.retrovault.domain

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The constitution the source cites is the constitution that exists.
 *
 * Source comments justify decisions by constitutional section number, which is
 * only worth anything if the numbers resolve. Two failures have already
 * happened here and this test exists to stop both recurring:
 *
 * - the repository carried `CONSTITUTION.md` and `Constitution.md` at the same
 *   time, which cannot both exist in a working tree on a case-insensitive
 *   filesystem;
 * - the two files numbered their sections independently, so `section 23` named
 *   a different rule depending on which document the reader opened.
 *
 * Scanning the whole repository from one module is deliberate. The citations are
 * spread across every module and the authority is a single file, so a per-module
 * check would leave gaps exactly where a stale citation would hide.
 */
class ConstitutionCitationTest {

    private val repoRoot: File
        get() = File(
            System.getProperty("retrovault.repoRoot")
                ?: fail("retrovault.repoRoot is not set; the build must provide it"),
        )

    private val citation = Regex("""Constitution section (\d+)""")

    /**
     * A section heading, e.g. `# 166. Matching Pipeline` or `## 323A. …`.
     *
     * The trailing whitespace matters: it is what separates a section heading
     * from a sub-heading like `## 7.1 Truth Before Completeness`, which numbers
     * a point within section 7 rather than declaring a section of its own.
     *
     * The letter suffix is part of the label. `166A` is a section inserted after
     * 166 without renumbering anything, so it is a different section from 166
     * and a citation of one must not be satisfied by the other.
     */
    private val sectionHeading = Regex("""^#{1,3} (\d+[A-Z]?)\.\s""", RegexOption.MULTILINE)

    private fun constitutionFiles(): List<File> =
        repoRoot.listFiles().orEmpty().filter { it.name.equals("CONSTITUTION.md", ignoreCase = true) }

    private fun sourceFiles(): List<File> = repoRoot.walkTopDown()
        .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
        .filter { it.extension == "kt" || it.extension == "kts" }
        .toList()

    @Test
    fun `exactly one constitution file exists`() {
        val found = constitutionFiles().map { it.name }

        assertEquals(
            listOf("CONSTITUTION.md"),
            found,
            "A second constitution differing only in filename case cannot coexist on Windows or macOS",
        )
    }

    @Test
    fun `every section the source cites exists in the constitution`() {
        val constitution = File(repoRoot, "CONSTITUTION.md").readText()
        val declared = sectionHeading.findAll(constitution).map { it.groupValues[1] }.toSet()
        assertTrue(declared.size > 100, "The constitution should declare its sections as headings")

        val dangling = mutableListOf<String>()
        sourceFiles().forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                citation.findAll(line).forEach { match ->
                    val section = match.groupValues[1]
                    if (section !in declared) {
                        dangling += "${file.name}:${index + 1} cites section $section, which does not exist"
                    }
                }
            }
        }

        assertTrue(dangling.isEmpty(), dangling.joinToString("\n"))
    }

    @Test
    fun `no two sections share a label`() {
        val constitution = File(repoRoot, "CONSTITUTION.md").readText()
        val labels = sectionHeading.findAll(constitution).map { it.groupValues[1] }.toList()

        val duplicated = labels.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

        assertTrue(
            duplicated.isEmpty(),
            "A label used twice makes every citation of it ambiguous, which is the exact failure " +
                "the two-file split produced: $duplicated",
        )
    }

    @Test
    fun `the constitution keeps the two numbering ranges apart`() {
        val constitution = File(repoRoot, "CONSTITUTION.md").readText()
        val numbers = sectionHeading.findAll(constitution)
            .map { it.groupValues[1].takeWhile(Char::isDigit).toInt() }
            .toList()

        assertTrue(numbers.any { it in 1..288 }, "Part I keeps the body's original numbering")
        assertTrue(numbers.any { it >= 300 }, "Part II is offset so the two ranges cannot collide")
        assertTrue(
            numbers.none { it in 289..299 },
            "289 to 299 is the gap that keeps the two ranges apart; using it invites a future collision",
        )
    }

    @Test
    fun `no source file contains a control character`() {
        // A stray NUL makes a file read as binary to grep, diff and review
        // tooling, which is how one went unnoticed here for two commits.
        val offenders = sourceFiles().filter { file ->
            file.readText().any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }
        }

        assertTrue(
            offenders.isEmpty(),
            "Control characters in source: ${offenders.map { it.name }}",
        )
    }
}

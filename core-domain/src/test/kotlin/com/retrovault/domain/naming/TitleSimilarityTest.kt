package com.retrovault.domain.naming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * False-positive prevention.
 *
 * TESTING_SPEC.md section 5: the corpus must include adversarial examples
 * designed to trigger false positives.
 */
class TitleSimilarityTest {

    private fun compare(left: String, right: String): TitleComparison =
        TitleSimilarity.compare(TitleNormalizer.normalize(left), TitleNormalizer.normalize(right))

    @Test
    fun `identical titles are exact`() {
        assertIs<TitleComparison.Exact>(compare("Super Mario World", "Super Mario World"))
    }

    @Test
    fun `sequel numbering is a conflict, not a small difference`() {
        val result = compare("Super Mario Bros.", "Super Mario Bros. 2")
        assertIs<TitleComparison.Conflicting>(result)
        assertTrue(result.reason.contains("numbering"), "The reason must be explainable: ${result.reason}")
    }

    @Test
    fun `roman and arabic numbering of different sequels still conflicts`() {
        assertIs<TitleComparison.Conflicting>(compare("Final Fantasy IV", "Final Fantasy VI"))
    }

    @Test
    fun `roman and arabic numbering of the same sequel is exact`() {
        assertIs<TitleComparison.Exact>(compare("Final Fantasy IV", "Final Fantasy 4"))
    }

    @Test
    fun `a subtitle that adds words is not similar enough`() {
        assertIs<TitleComparison.Unrelated>(compare("Sonic 3", "Sonic 3 and Knuckles Collection Deluxe"))
    }

    @Test
    fun `word order variation stays similar`() {
        val result = compare("Legend of Zelda, The - Link's Awakening", "The Legend of Zelda - Links Awakening")
        assertIs<TitleComparison.Exact>(result)
    }

    @Test
    fun `unrelated titles are unrelated`() {
        assertIs<TitleComparison.Unrelated>(compare("Super Metroid", "Chrono Trigger"))
    }

    @Test
    fun `blank titles never match`() {
        assertIs<TitleComparison.Unrelated>(compare("", "Super Metroid"))
        assertIs<TitleComparison.Unrelated>(compare("Super Metroid", ""))
    }

    @Test
    fun `similarity is symmetric and deterministic`() {
        val forward = compare("Street Fighter II Turbo", "Street Fighter II")
        val backward = compare("Street Fighter II", "Street Fighter II Turbo")
        assertEquals(forward, backward)
        assertEquals(forward, compare("Street Fighter II Turbo", "Street Fighter II"))
    }

    @Test
    fun `a single character typo is still recognised`() {
        // Token overlap scores this at zero: no word matches. Edit distance is
        // what carries it, which is why both measures are combined.
        val comparison = TitleSimilarity.compare(
            TitleNormalizer.normalize("Castlevania"),
            TitleNormalizer.normalize("Castlevanla"),
        )

        assertIs<TitleComparison.Similar>(comparison)
    }

    @Test
    fun `edit distance never overrides a numbering conflict`() {
        // "Final Fantasy VII" and "Final Fantasy VIII" are one character apart
        // and are different games.
        val comparison = TitleSimilarity.compare(
            TitleNormalizer.normalize("Final Fantasy VII"),
            TitleNormalizer.normalize("Final Fantasy VIII"),
        )

        assertIs<TitleComparison.Conflicting>(comparison)
    }

    @Test
    fun `edit distance gives up rather than scoring a distant pair`() {
        val comparison = TitleSimilarity.compare(
            TitleNormalizer.normalize("Castlevania"),
            TitleNormalizer.normalize("Contra"),
        )

        assertEquals(TitleComparison.Unrelated, comparison)
    }

    @Test
    fun `the bounded edit distance agrees with the exact one inside its budget`() {
        assertEquals(0, TitleSimilarity.levenshtein("abcdef", "abcdef", 3))
        assertEquals(1, TitleSimilarity.levenshtein("abcdef", "abcdff", 3))
        assertEquals(3, TitleSimilarity.levenshtein("abcdef", "abc", 3))
    }

    @Test
    fun `the bounded edit distance stops once the budget is exceeded`() {
        // It reports "further than the budget" rather than the true distance,
        // which is all the caller needs and is what keeps the fallback cheap.
        assertTrue(TitleSimilarity.levenshtein("abcdefgh", "zzzzzzzz", 2) > 2)
    }
}

package com.retrovault.domain.naming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TitleNormalizerTest {

    @Test
    fun `diacritic tables stay in step`() {
        assertEquals(
            TitleNormalizer.DIACRITIC_SOURCE.length,
            TitleNormalizer.DIACRITIC_TARGET.length,
            "Every source character needs exactly one replacement",
        )
    }

    @Test
    fun `normalization is idempotent`() {
        val samples = listOf(
            "Legend of Zelda, The - A Link to the Past",
            "Pokémon Red",
            "Sonic & Knuckles",
            "  Double   Dragon  II  ",
            "Final Fantasy VI",
            "F-Zero",
            "",
            "!!!",
            "スーパーマリオ",
        )
        samples.forEach { sample ->
            val once = TitleNormalizer.normalize(sample)
            val twice = TitleNormalizer.normalize(once.key)
            assertEquals(once, twice, "normalize(normalize(x)) must equal normalize(x) for '$sample'")
        }
    }

    @Test
    fun `article position does not change identity`() {
        assertEquals(
            TitleNormalizer.normalize("The Legend of Zelda"),
            TitleNormalizer.normalize("Legend of Zelda, The"),
        )
    }

    @Test
    fun `diacritics fold to ascii`() {
        assertEquals(
            TitleNormalizer.normalize("Pokemon Red"),
            TitleNormalizer.normalize("Pokémon Red"),
        )
    }

    @Test
    fun `ampersand and plus expand to words`() {
        assertEquals("sonic and knuckles", TitleNormalizer.normalize("Sonic & Knuckles").key)
        assertEquals("mario plus luigi", TitleNormalizer.normalize("Mario + Luigi").key)
    }

    @Test
    fun `roman numerals become digits so numbering can be compared`() {
        assertEquals(
            TitleNormalizer.normalize("Final Fantasy 4"),
            TitleNormalizer.normalize("Final Fantasy IV"),
        )
    }

    @Test
    fun `non latin scripts survive normalization`() {
        val normalized = TitleNormalizer.normalize("スーパーマリオ")
        assertTrue(normalized.key.isNotBlank(), "A Japanese title must not normalize to an empty key")
    }

    @Test
    fun `punctuation collapses to single spaces`() {
        assertEquals("super mario world", TitleNormalizer.normalize("Super  Mario -- World!").key)
    }

    // ------------------------------------------------------------------
    // Comparison variants: stripping noise without risking the real title
    // ------------------------------------------------------------------

    @Test
    fun `the unstripped form is always among the variants`() {
        // The safety property of the whole approach: an over-eager strip can
        // only fail to help, never cause a wrong match, because the original
        // reading is still there to be scored.
        val variants = TitleNormalizer.comparisonVariants("Spider-Man")

        assertTrue(TitleNormalizer.normalize("Spider-Man") in variants, variants.toString())
    }

    @Test
    fun `a trailing scene tag is offered as an alternative reading`() {
        val variants = TitleNormalizer.comparisonVariants("Red Hot Rumble-memorypsp")

        assertTrue(TitleNormalizer.normalize("Red Hot Rumble") in variants, variants.toString())
    }

    @Test
    fun `a hyphen used as punctuation is not treated as a tag`() {
        val variants = TitleNormalizer.comparisonVariants("Ratchet - Deadlocked")

        assertEquals(listOf(TitleNormalizer.normalize("Ratchet - Deadlocked")), variants)
    }

    @Test
    fun `an over-long trailing segment is not treated as a tag`() {
        val title = "Some Game-" + "a".repeat(40)

        assertEquals(listOf(TitleNormalizer.normalize(title)), TitleNormalizer.comparisonVariants(title))
    }

    @Test
    fun `a site watermark is removed from the front`() {
        val variants = TitleNormalizer.comparisonVariants("www.example.com - Super Mario World")

        assertTrue(TitleNormalizer.normalize("Super Mario World") in variants, variants.toString())
    }

    @Test
    fun `variants never include a blank key`() {
        assertTrue(TitleNormalizer.comparisonVariants("---").none { it.isBlank })
    }

    @Test
    fun `variant production is deterministic`() {
        val title = "www.roms.to - Red Hot Rumble-memorypsp"

        assertEquals(TitleNormalizer.comparisonVariants(title), TitleNormalizer.comparisonVariants(title))
    }
}

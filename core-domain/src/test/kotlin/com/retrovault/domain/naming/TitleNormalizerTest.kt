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
}

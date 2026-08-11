package com.retrovault.domain.naming

import com.retrovault.domain.identity.RegionCode
import com.retrovault.domain.identity.ReleaseFlag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilenameTokenizerTest {

    @Test
    fun `no-intro style name splits into title and typed tokens`() {
        val parsed = FilenameTokenizer.tokenize("Super Mario World (USA) (Rev A).sfc")
        assertEquals("Super Mario World", parsed.titleText)
        assertEquals("sfc", parsed.extension)
        assertEquals(listOf(RegionCode("USA")), parsed.regions)
        assertEquals("A", parsed.revision)
    }

    @Test
    fun `compound region tokens are parsed as several regions`() {
        val parsed = FilenameTokenizer.tokenize("Some Game (USA, Europe).sfc")
        assertEquals(listOf(RegionCode("USA"), RegionCode("EUROPE")), parsed.regions)
    }

    @Test
    fun `language lists are distinguished from regions`() {
        val parsed = FilenameTokenizer.tokenize("Some Game (Europe) (En,Fr,De).sfc")
        assertEquals(listOf(RegionCode("EUROPE")), parsed.regions)
        assertEquals(listOf("en", "fr", "de"), parsed.languages.map { it.code })
    }

    @Test
    fun `dump status and release flags are classified, not deleted`() {
        val parsed = FilenameTokenizer.tokenize("Some Game (USA) (Proto) [b].sfc")
        assertTrue(ReleaseFlag.PROTOTYPE in parsed.flags)
        assertTrue(parsed.tokens.any { it.tokenClass == TokenClass.DUMP_STATUS })
    }

    @Test
    fun `unknown tokens are preserved rather than discarded`() {
        val parsed = FilenameTokenizer.tokenize("Some Game (USA) (Weird Marker).sfc")
        val unknown = parsed.unknownTokens.map { it.text }
        assertEquals(listOf("Weird Marker"), unknown)
    }

    @Test
    fun `scene style names are split on dots`() {
        val parsed = FilenameTokenizer.tokenize("Super.Mario.World.USA.SNES-Group.sfc")
        assertEquals("Super Mario World", parsed.titleText)
        assertEquals(listOf(RegionCode("USA")), parsed.regions)
    }

    @Test
    fun `a version suffix does not turn a plain name into a scene name`() {
        val parsed = FilenameTokenizer.tokenize("Some Game v1.2.sfc")
        assertEquals("Some Game v1.2", parsed.titleText)
    }

    @Test
    fun `known noise suffixes are stripped from the title`() {
        val parsed = FilenameTokenizer.tokenize("Some Game (USA)-memorypsp.iso")
        assertEquals("Some Game", parsed.titleText)
        assertTrue(parsed.tokens.any { it.tokenClass == TokenClass.MEMORY_REQUIREMENT })
    }

    @Test
    fun `an unrecognised hyphen segment stays part of the title`() {
        val parsed = FilenameTokenizer.tokenize("Spider-Man.sfc")
        assertEquals("Spider-Man", parsed.titleText)
    }

    @Test
    fun `disc numbers are extracted`() {
        assertEquals(2, FilenameTokenizer.tokenize("Some Game (USA) (Disc 2).bin").discNumber)
        assertEquals(1, FilenameTokenizer.tokenize("Some Game (USA) (CD1).bin").discNumber)
    }

    @Test
    fun `unbalanced brackets are treated as text, not as an error`() {
        val parsed = FilenameTokenizer.tokenize("Broken (USA.sfc")
        assertEquals("Broken (USA", parsed.titleText)
    }

    @Test
    fun `a filename with no extension yields no extension`() {
        val parsed = FilenameTokenizer.tokenize("README")
        assertNull(parsed.extension)
        assertEquals("README", parsed.titleText)
    }

    @Test
    fun `a long trailing segment is not mistaken for an extension`() {
        val parsed = FilenameTokenizer.tokenize("Some Game.superlongextension")
        assertNull(parsed.extension)
    }

    @Test
    fun `tokenization is deterministic`() {
        val name = "Legend of Zelda, The (USA) (Rev A) (Beta) [!].sfc"
        assertEquals(FilenameTokenizer.tokenize(name), FilenameTokenizer.tokenize(name))
    }

    @Test
    fun `the original filename is always preserved as evidence`() {
        val name = "Weird.Name.With.Stuff.USA.sfc"
        assertEquals(name, FilenameTokenizer.tokenize(name).original)
    }
}

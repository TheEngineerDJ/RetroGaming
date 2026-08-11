package com.retrovault.domain.naming

import com.retrovault.domain.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CanonicalFilenameGeneratorTest {

    private fun generate(
        setName: String,
        extension: String? = "sfc",
        profile: NamingProfile = NamingProfiles.NO_INTRO_V1,
    ): FilenameValidation =
        CanonicalFilenameGenerator.generate(Fixtures.record(setName), profile, extension)

    private fun name(setName: String, extension: String? = "sfc"): String {
        val result = generate(setName, extension)
        assertIs<FilenameValidation.Valid>(result)
        return result.name
    }

    @Test
    fun `title and region are projected in no-intro order`() {
        assertEquals("Super Mario World (USA).sfc", name("Super Mario World (USA)"))
    }

    @Test
    fun `revision and disc tokens are preserved`() {
        assertEquals(
            "Some Game (USA) (Rev A) (Disc 2).sfc",
            name("Some Game (USA) (Rev A) (Disc 2)"),
        )
    }

    @Test
    fun `several regions are written in canonical order`() {
        assertEquals("Some Game (USA, Europe).sfc", name("Some Game (Europe, USA)"))
    }

    @Test
    fun `release flags are projected in a fixed order`() {
        assertEquals("Some Game (USA) (Proto) (Beta).sfc", name("Some Game (USA) (Beta) (Proto)"))
    }

    @Test
    fun `the container extension is preserved, never taken from the catalogue`() {
        // An archive keeps its own extension: renaming a .zip must not claim it
        // is a .sfc.
        assertEquals("Super Mario World (USA).zip", name("Super Mario World (USA)", extension = "zip"))
    }

    @Test
    fun `a file with no extension gets a name with no extension`() {
        assertEquals("Super Mario World (USA)", name("Super Mario World (USA)", extension = null))
    }

    @Test
    fun `generation is deterministic`() {
        val record = Fixtures.record("Some Game (USA) (Rev A)")
        val first = CanonicalFilenameGenerator.generate(record, NamingProfiles.NO_INTRO_V1, "sfc")
        val second = CanonicalFilenameGenerator.generate(record, NamingProfiles.NO_INTRO_V1, "sfc")
        assertEquals(first, second)
    }

    @Test
    fun `generation is idempotent when fed back through the tokenizer`() {
        val record = Fixtures.record("Legend of Zelda, The (USA) (Rev A)")
        val generated = name("Legend of Zelda, The (USA) (Rev A)")
        // Re-deriving a record from the generated name must produce the same
        // name again: normalize(normalize(x)) == normalize(x) at the file level.
        val rederived = Fixtures.record(generated.removeSuffix(".sfc"))
        val second = CanonicalFilenameGenerator.generate(rederived, NamingProfiles.NO_INTRO_V1, "sfc")
        assertIs<FilenameValidation.Valid>(second)
        assertEquals(generated, second.name)
        assertEquals(record.normalizedTitle, rederived.normalizedTitle)
    }

    @Test
    fun `a different profile projects the same identity differently`() {
        val record = Fixtures.record("Some Game (USA) (Rev A)")
        val full = CanonicalFilenameGenerator.generate(record, NamingProfiles.NO_INTRO_V1, "sfc")
        val minimal = CanonicalFilenameGenerator.generate(record, NamingProfiles.MINIMAL_V1, "sfc")
        assertIs<FilenameValidation.Valid>(full)
        assertIs<FilenameValidation.Valid>(minimal)
        assertEquals("Some Game (USA) (Rev A).sfc", full.name)
        assertEquals("Some Game (USA).sfc", minimal.name)
    }

    @Test
    fun `a hostile catalogue title cannot produce a traversal`() {
        val result = generate("../../etc/passwd (USA)")
        assertIs<FilenameValidation.Valid>(result)
        assertEquals(false, result.name.contains('/'), "Generated name must contain no separator")
    }

    @Test
    fun `a catalogue title with no usable characters is rejected`() {
        val result = generate("///")
        assertIs<FilenameValidation.Invalid>(result)
        assertEquals(InvalidNameReason.EMPTY, result.reason)
    }

    @Test
    fun `an over-long title is truncated rather than rejected`() {
        val result = generate("${"A".repeat(400)} (USA)")
        assertIs<FilenameValidation.Valid>(result)
        assertEquals(true, result.name.endsWith(" (USA).sfc"))
        assertEquals(
            true,
            result.name.toByteArray(Charsets.UTF_8).size <= FilenameSanitizer.MAX_FILENAME_BYTES,
        )
    }
}

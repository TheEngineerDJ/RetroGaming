package com.retrovault.domain.naming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SECURITY_SPEC.md section 2: a destination is never constructed from
 * unvalidated DAT input, and traversal, empty and invalid components are
 * rejected outright.
 */
class FilenameSanitizerTest {

    private fun reason(name: String): InvalidNameReason {
        val result = FilenameSanitizer.validate(name)
        assertIs<FilenameValidation.Invalid>(result, "'$name' should have been rejected")
        return result.reason
    }

    @Test
    fun `a plain name is valid`() {
        assertIs<FilenameValidation.Valid>(FilenameSanitizer.validate("Super Mario World (USA).sfc"))
    }

    @Test
    fun `path traversal is rejected`() {
        assertEquals(InvalidNameReason.PATH_TRAVERSAL, reason(".."))
        assertEquals(InvalidNameReason.PATH_SEPARATOR, reason("../../etc/passwd"))
        assertEquals(InvalidNameReason.PATH_SEPARATOR, reason("..\\windows\\system32"))
    }

    @Test
    fun `empty names are rejected`() {
        assertEquals(InvalidNameReason.EMPTY, reason(""))
    }

    @Test
    fun `illegal characters are rejected`() {
        assertEquals(InvalidNameReason.ILLEGAL_CHARACTER, reason("game:1.sfc"))
        assertEquals(InvalidNameReason.ILLEGAL_CHARACTER, reason("game?.sfc"))
        assertEquals(InvalidNameReason.ILLEGAL_CHARACTER, reason("game|pipe.sfc"))
    }

    @Test
    fun `control characters are rejected`() {
        assertEquals(InvalidNameReason.CONTROL_CHARACTER, reason("game\u0007bell.sfc"))
        assertEquals(InvalidNameReason.CONTROL_CHARACTER, reason("game\nnewline.sfc"))
    }

    @Test
    fun `reserved device names are rejected`() {
        assertEquals(InvalidNameReason.RESERVED_DEVICE_NAME, reason("CON.sfc"))
        assertEquals(InvalidNameReason.RESERVED_DEVICE_NAME, reason("com1.bin"))
    }

    @Test
    fun `trailing dots and spaces are rejected`() {
        assertEquals(InvalidNameReason.TRAILING_DOT_OR_SPACE, reason("game."))
        assertEquals(InvalidNameReason.TRAILING_DOT_OR_SPACE, reason("game "))
        assertEquals(InvalidNameReason.TRAILING_DOT_OR_SPACE, reason(" game.sfc"))
    }

    @Test
    fun `over-long names are rejected`() {
        val long = "a".repeat(FilenameSanitizer.MAX_FILENAME_BYTES + 1) + ".sfc"
        assertEquals(InvalidNameReason.TOO_LONG, reason(long))
    }

    @Test
    fun `multibyte length is measured in bytes`() {
        // Each of these is three UTF-8 bytes, so 90 characters exceed 255 bytes.
        val name = "あ".repeat(90) + ".sfc"
        assertEquals(InvalidNameReason.TOO_LONG, reason(name))
    }

    @Test
    fun `sanitizing replaces illegal characters and trims`() {
        assertEquals("a_b_c", FilenameSanitizer.sanitize("a/b:c "))
        assertEquals("game", FilenameSanitizer.sanitize("game."))
    }

    @Test
    fun `sanitizing a traversal attempt does not produce a traversal`() {
        val sanitized = FilenameSanitizer.sanitize("../../etc/passwd")
        assertTrue(!sanitized.contains('/'), "sanitize must not leave a path separator: '$sanitized'")
        assertIs<FilenameValidation.Valid>(FilenameSanitizer.validate(sanitized))
    }

    @Test
    fun `truncation preserves the identity suffix`() {
        val stem = "a".repeat(400)
        val suffix = " (USA) (Rev A).sfc"
        val truncated = FilenameSanitizer.truncateStem(stem, suffix)
        val full = truncated + suffix
        assertTrue(full.toByteArray(Charsets.UTF_8).size <= FilenameSanitizer.MAX_FILENAME_BYTES)
        assertTrue(full.endsWith(suffix), "Identity tokens must survive truncation")
    }

    @Test
    fun `truncation is idempotent`() {
        val stem = "a".repeat(400)
        val suffix = " (USA).sfc"
        val once = FilenameSanitizer.truncateStem(stem, suffix)
        assertEquals(once, FilenameSanitizer.truncateStem(once, suffix))
    }

    @Test
    fun `truncation never splits a surrogate pair`() {
        // Cutting between the halves of an astral character leaves an unpaired
        // surrogate, which encodes to a replacement character and silently
        // corrupts the name.
        val emoji = "\uD83C\uDFAE" // game controller, four UTF-8 bytes
        val stem = emoji.repeat(80)

        val truncated = FilenameSanitizer.truncateStem(stem, " (USA).sfc")

        assertTrue(truncated.isNotEmpty())
        assertFalse(truncated.last().isHighSurrogate(), "The trailing half of a pair must not be left behind")
        assertEquals(
            truncated,
            String(truncated.toByteArray(Charsets.UTF_8), Charsets.UTF_8),
            "A truncated name must survive a UTF-8 round trip unchanged",
        )
    }

    @Test
    fun `a truncated multibyte stem still fits the byte budget`() {
        val stem = "\uD83C\uDFAE".repeat(80)
        val suffix = " (USA).sfc"

        val name = FilenameSanitizer.truncateStem(stem, suffix) + suffix

        assertTrue(name.toByteArray(Charsets.UTF_8).size <= FilenameSanitizer.MAX_FILENAME_BYTES)
    }
}

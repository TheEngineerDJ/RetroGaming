package com.retrovault.domain.observation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Recognising a prefix a dumping tool wrote in front of the software.
 *
 * Constitution section 200: representation may differ without identity
 * differing. These rules decide whether RetroVault looks past a header at all,
 * and the cost of each mistake is asymmetric - a header wrongly detected shifts
 * every following byte and matches nothing, a header wrongly missed costs the
 * fallback the code always had. Neither can produce a *wrong* identity, because
 * identity still rests on a cryptographic hash of whatever the payload is.
 */
class RomHeaderTest {

    private fun prefixOf(text: String, vararg trailing: Int): ByteArray =
        text.map { it.code.toByte() }.toByteArray() +
            trailing.map { it.toByte() }.toByteArray() +
            ByteArray(128)

    // ------------------------------------------------------------------
    // Magic-number headers
    // ------------------------------------------------------------------

    @Test
    fun `an iNES header is recognised by its magic`() {
        val detected = RomHeaderDetector.detect("nes", size = 40_976, prefix = prefixOf("NES", 0x1A))

        assertEquals(RomHeaderKind.INES, detected?.kind)
        assertEquals(16L, detected?.length)
    }

    @Test
    fun `an nes file without the magic carries no header`() {
        // A headerless NES dump is an ordinary file. Assuming 16 bytes of
        // header would corrupt every hash computed from it.
        assertNull(RomHeaderDetector.detect("nes", size = 40_960, prefix = prefixOf("SOME", 0x00)))
    }

    @Test
    fun `a Famicom Disk System header is recognised`() {
        assertEquals(
            RomHeaderKind.FDS,
            RomHeaderDetector.detect("fds", size = 65_516, prefix = prefixOf("FDS", 0x1A))?.kind,
        )
    }

    @Test
    fun `a Lynx header is recognised`() {
        assertEquals(
            RomHeaderKind.LYNX,
            RomHeaderDetector.detect("lnx", size = 131_136, prefix = prefixOf("LYNX"))?.kind,
        )
    }

    @Test
    fun `an Atari 7800 header is recognised at its offset`() {
        // The magic sits after a version byte, not at byte zero.
        val prefix = byteArrayOf(3) + prefixOf("ATARI7800")

        assertEquals(RomHeaderKind.A7800, RomHeaderDetector.detect("a78", 49_280, prefix)?.kind)
    }

    @Test
    fun `a file that is only a header has no payload to identify`() {
        assertNull(RomHeaderDetector.detect("nes", size = 16, prefix = prefixOf("NES", 0x1A)))
    }

    // ------------------------------------------------------------------
    // Size-detected headers
    // ------------------------------------------------------------------

    @Test
    fun `a SNES copier header is recognised from the size alone`() {
        // Every SNES dump is a whole number of kilobytes, so 512 bytes over a
        // kilobyte boundary is a copier header. No read is needed.
        listOf("smc", "sfc", "swc", "fig").forEach { extension ->
            assertEquals(
                RomHeaderKind.SNES_COPIER,
                RomHeaderDetector.detect(extension, size = 524_288 + 512)?.kind,
                extension,
            )
        }
    }

    @Test
    fun `a headerless SNES dump is left alone`() {
        assertNull(RomHeaderDetector.detect("sfc", size = 524_288))
        assertNull(RomHeaderDetector.detect("sfc", size = 1_048_576))
    }

    @Test
    fun `a SNES file that is not 512 over a kilobyte boundary carries no header`() {
        assertNull(RomHeaderDetector.detect("sfc", size = 524_288 + 256))
    }

    // ------------------------------------------------------------------
    // What needs reading
    // ------------------------------------------------------------------

    @Test
    fun `only magic-number formats need bytes read`() {
        // The SNES case is the common one and costs no extra read.
        assertTrue(RomHeaderDetector.needsPrefix("nes"))
        assertTrue(RomHeaderDetector.needsPrefix("a78"))
        assertFalse(RomHeaderDetector.needsPrefix("sfc"))
        assertFalse(RomHeaderDetector.needsPrefix("iso"))
    }

    @Test
    fun `an extension that cannot carry a header is never read for one`() {
        assertFalse(RomHeaderDetector.mayCarryHeader("iso"))
        assertFalse(RomHeaderDetector.mayCarryHeader("zip"))
        assertFalse(RomHeaderDetector.mayCarryHeader(null))
        assertTrue(RomHeaderDetector.mayCarryHeader("SFC"), "Extensions are matched case-insensitively")
    }

    @Test
    fun `a magic header is not claimed when the bytes could not be read`() {
        // An empty prefix means the caller could not look. Reporting a header
        // anyway would be inventing a fact about the file.
        assertNull(RomHeaderDetector.detect("nes", size = 40_976, prefix = ByteArray(0)))
    }

    @Test
    fun `an interleaved dump is not claimed as a skippable header`() {
        // A Mega Drive SMD dump is also 512 bytes longer than its payload, but
        // recovering the payload is a transform rather than an offset. Claiming
        // it here would promise an identification RetroVault cannot make.
        assertFalse(RomHeaderDetector.mayCarryHeader("smd"))
        assertNull(RomHeaderDetector.detect("smd", size = 524_288 + 512))
    }

    // ------------------------------------------------------------------
    // What the observation does with it
    // ------------------------------------------------------------------

    @Test
    fun `a headered observation is looked up by the size of its payload`() {
        val observation = Fixtures.observation(size = 524_288 + 512, header = DetectedHeader(RomHeaderKind.SNES_COPIER))

        assertEquals(
            524_288L,
            observation.identityBearingSize(),
            "Size filtering runs before any hash, so a headered file is excluded before it is read",
        )
        assertEquals(512L, observation.contentRef.byteOffset)
    }

    @Test
    fun `an observation with no header is addressed from byte zero`() {
        val observation = Fixtures.observation(size = 524_288)

        assertEquals(524_288L, observation.identityBearingSize())
        assertEquals(0L, observation.contentRef.byteOffset)
    }

    private object Fixtures {
        fun observation(size: Long, header: DetectedHeader? = null) = FileObservation(
            id = com.retrovault.domain.identity.ObservationId("observation"),
            sessionId = com.retrovault.domain.identity.ScanSessionId("session"),
            storageRef = com.retrovault.domain.identity.StorageRef("file:///game.sfc"),
            parentRef = com.retrovault.domain.identity.StorageRef("file:///"),
            filename = "game.sfc",
            relativePath = "game.sfc",
            size = size,
            lastModifiedEpochMillis = null,
            container = com.retrovault.domain.identity.ContainerKind.RAW,
            header = header,
            observedAtEpochMillis = 1,
        )
    }
}

package com.retrovault.domain.identity

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Media recognition.
 *
 * Constitution section 23: the architecture must avoid a universal
 * "one file = one hash = one game" assumption. This vocabulary is where that
 * starts - and its most important property is restraint, because a wrong medium
 * on the evidence trail is worse than no medium at all.
 */
class MediaVocabularyTest {

    @Test
    fun `a psp umd image is optical disc media, not a generic rom`() {
        assertEquals(MediaType.OPTICAL_DISC, MediaTypeVocabulary.forFilename("Some Game (USA).iso"))
        assertEquals(MediaType.OPTICAL_DISC, MediaTypeVocabulary.forFilename("Some Game (USA).cso"))
        assertEquals(MediaType.OPTICAL_DISC, MediaTypeVocabulary.forFilename("Some Game (USA).pbp"))
    }

    @Test
    fun `disc images of every generation read as optical media`() {
        listOf("game.cue", "game.chd", "game.gdi", "game.rvz", "game.wbfs", "game.nrg")
            .forEach { name ->
                assertEquals(MediaType.OPTICAL_DISC, MediaTypeVocabulary.forFilename(name), name)
            }
    }

    @Test
    fun `cartridge dumps read as cartridge media`() {
        listOf("game.sfc", "game.nes", "game.gba", "game.md", "game.3ds", "game.xci")
            .forEach { name ->
                assertEquals(MediaType.CARTRIDGE, MediaTypeVocabulary.forFilename(name), name)
            }
    }

    @Test
    fun `an extension used by more than one medium is left unknown`() {
        // `.bin` is a Mega Drive cartridge dump and a CD track. Guessing would
        // put a wrong medium on the evidence trail; unknown costs nothing,
        // because an unknown medium only ever widens what is considered.
        assertEquals(MediaType.UNKNOWN, MediaTypeVocabulary.forFilename("game.bin"))
        assertEquals(MediaType.UNKNOWN, MediaTypeVocabulary.forFilename("game.img"))
        assertEquals(MediaType.UNKNOWN, MediaTypeVocabulary.forFilename("game.rom"))
    }

    @Test
    fun `an unrecognised or absent extension is unknown, never a guess`() {
        assertEquals(MediaType.UNKNOWN, MediaTypeVocabulary.forFilename("README"))
        assertEquals(MediaType.UNKNOWN, MediaTypeVocabulary.forFilename("game.qqq"))
        assertEquals(MediaType.UNKNOWN, MediaTypeVocabulary.forExtension(null))
    }

    @Test
    fun `recognition ignores letter case`() {
        assertEquals(MediaType.OPTICAL_DISC, MediaTypeVocabulary.forFilename("GAME.ISO"))
        assertEquals(MediaType.CARTRIDGE, MediaTypeVocabulary.forExtension(".SFC"))
    }

    @Test
    fun `the backfill table agrees with live classification`() {
        // The migration reproduces this mapping in SQL. If the two drift, an
        // upgraded catalogue ends up with media types a re-import would not
        // produce, and coverage silently becomes wrong.
        MediaTypeVocabulary.knownExtensions().forEach { (extension, media) ->
            assertEquals(media, MediaTypeVocabulary.forFilename("game.$extension"), extension)
        }
    }

    @Test
    fun `dataset provenance is read from what the dat says about itself`() {
        assertEquals(
            DatasetKind.REDUMP,
            DatasetKindVocabulary.infer("redump", "Sony - PlayStation Portable", null, null),
        )
        assertEquals(
            DatasetKind.NO_INTRO,
            DatasetKindVocabulary.infer("no_intro", "Nintendo - Super Nintendo", null, null),
        )
        assertEquals(
            DatasetKind.NO_INTRO,
            DatasetKindVocabulary.infer(null, null, "No-Intro", null),
        )
        assertEquals(DatasetKind.TOSEC, DatasetKindVocabulary.infer(null, "TOSEC - Amiga", null, null))
        assertEquals(DatasetKind.MAME, DatasetKindVocabulary.infer(null, "MAME 0.264", null, null))
    }

    @Test
    fun `an unrecognised dataset is unknown rather than misattributed`() {
        assertEquals(DatasetKind.UNKNOWN, DatasetKindVocabulary.infer("custom", "My Set", null, null))
        assertEquals(DatasetKind.UNKNOWN, DatasetKindVocabulary.infer(null, null, null, null))
    }

    @Test
    fun `typical media is advisory and never claimed as coverage`() {
        // Reputation is not evidence: these sets exist to explain a result, and
        // nothing consults them to decide what to search.
        assertEquals(setOf(MediaType.OPTICAL_DISC), DatasetKind.REDUMP.typicalMedia)
        assertEquals(emptySet(), DatasetKind.UNKNOWN.typicalMedia)
    }
}

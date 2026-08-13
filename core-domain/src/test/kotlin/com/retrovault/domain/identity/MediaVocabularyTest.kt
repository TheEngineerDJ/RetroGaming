package com.retrovault.domain.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Media recognition.
 *
 * Constitution section 323: the architecture must avoid a universal
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

    @Test
    fun `an ambiguous extension is absent from the table the backfill uses`() {
        // The SQL backfill has no notion of ambiguity; it simply applies this
        // table. An extension present in both tables would classify one way
        // live and the other way on upgrade, leaving a catalogue whose stored
        // media disagree with what a re-import would produce.
        val exported = MediaTypeVocabulary.knownExtensions()

        listOf("bin", "img", "rom", "dat", "raw", "ima").forEach { extension ->
            assertEquals(
                MediaType.UNKNOWN,
                MediaTypeVocabulary.forExtension(extension),
                extension,
            )
            assertTrue(extension !in exported, "'$extension' would be backfilled despite being ambiguous")
        }
    }

    @Test
    fun `no extension contains a sql wildcard`() {
        // The backfill builds `LIKE '%.<extension>'`. An extension containing
        // `%` or `_` would silently over-match and mislabel unrelated records.
        val offenders = MediaTypeVocabulary.knownExtensions().keys
            .filter { it.contains('%') || it.contains('_') || it.contains('\'') }

        assertTrue(offenders.isEmpty(), "Extensions unsafe in a LIKE pattern: $offenders")
    }

    @Test
    fun `provenance matching does not fire on a word that merely contains a project name`() {
        // "Gamemaster" contains "mame". Misattributed provenance is shown to
        // the user as a fact about where their data came from.
        assertEquals(
            DatasetKind.UNKNOWN,
            DatasetKindVocabulary.infer(null, "Gamemaster Collection", null, null),
        )
        assertEquals(
            DatasetKind.UNKNOWN,
            DatasetKindVocabulary.infer(null, "Redumping Notes For Myself", null, null),
        )
        assertEquals(DatasetKind.MAME, DatasetKindVocabulary.infer(null, "MAME 0.264", null, null))
        assertEquals(DatasetKind.MAME, DatasetKindVocabulary.infer(null, "Arcade (MAME)", null, null))
    }

    @Test
    fun `provenance is read from the description as well as the header name`() {
        assertEquals(
            DatasetKind.REDUMP,
            DatasetKindVocabulary.infer(
                provider = "custom",
                setName = "Sony - PlayStation Portable",
                author = null,
                description = "Sony - PlayStation Portable - Redump",
            ),
        )
    }
}

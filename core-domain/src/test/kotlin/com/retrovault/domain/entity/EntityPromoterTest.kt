package com.retrovault.domain.entity

import com.retrovault.domain.Fixtures
import com.retrovault.domain.identity.MediaType
import com.retrovault.domain.identity.RegionCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Projecting catalogue evidence into canonical entities.
 *
 * Constitution section 31 forbids collapsing every release of a work into one
 * record, and section 43 reserves canonical merges for human or high-confidence
 * evidence. Both mean the promoter's job is to under-merge rather than to
 * guess: two things that might be one entity stay two until someone says
 * otherwise, and that is cheap to undo. Wrongly merging is not.
 */
class EntityPromoterTest {

    private val sha1 = Fixtures.sha1("1111")

    @Test
    fun `one record projects into the whole chain with its structural edges`() {
        val record = Fixtures.record("Super Mario World (USA)", hashes = Fixtures.digests(sha1))

        val promoted = EntityPromoter.promote(record)

        assertEquals("Super Mario World", promoted.work.canonicalTitle)
        assertEquals(record.platform, promoted.platform.name)
        assertEquals(listOf(RegionCode("USA")), promoted.release.regions)
        assertEquals(promoted.work.id, promoted.release.workId)
        assertEquals(promoted.platform.id, promoted.release.platformId)
        assertEquals(promoted.release.id, promoted.artifact.releaseId)
        assertEquals(
            setOf(RelationshipType.RELEASE_OF, RelationshipType.RUNS_ON, RelationshipType.IMAGE_OF),
            promoted.relationships.mapTo(mutableSetOf()) { it.type },
        )
        assertTrue(promoted.relationships.all { it.type.isStructural })
    }

    @Test
    fun `promotion is idempotent, so a rescan produces the same entities`() {
        val record = Fixtures.record("Super Mario World (USA)", hashes = Fixtures.digests(sha1))

        val first = EntityPromoter.promote(record)
        val second = EntityPromoter.promote(record)

        assertEquals(first, second)
    }

    @Test
    fun `two datasets describing one release converge on one release and one artifact`() {
        // The whole point of a canonical entity: No-Intro and Redump describing
        // the same bytes must not produce two parallel graphs.
        val noIntro = Fixtures.record(
            "Some Game (USA)",
            hashes = Fixtures.digests(sha1),
            source = Fixtures.source(provider = "no_intro"),
        )
        val redump = Fixtures.record(
            "Some Game (USA)",
            hashes = Fixtures.digests(sha1),
            source = Fixtures.source(provider = "redump"),
        )

        assertEquals(
            EntityPromoter.promote(noIntro).release.id,
            EntityPromoter.promote(redump).release.id,
        )
        assertEquals(
            EntityPromoter.promote(noIntro).artifact.id,
            EntityPromoter.promote(redump).artifact.id,
        )
    }

    @Test
    fun `regional releases of one work stay separate releases of the same work`() {
        // Constitution section 31: do not force all releases into one canonical
        // record. They share a work; they are not the same publication.
        val usa = EntityPromoter.promote(Fixtures.record("Some Game (USA)"))
        val europe = EntityPromoter.promote(Fixtures.record("Some Game (Europe)"))

        assertEquals(usa.work.id, europe.work.id)
        assertNotEquals(usa.release.id, europe.release.id)
    }

    @Test
    fun `a revision is a different release`() {
        val original = EntityPromoter.promote(Fixtures.record("Some Game (USA)"))
        val revised = EntityPromoter.promote(Fixtures.record("Some Game (USA) (Rev A)"))

        assertEquals(original.work.id, revised.work.id)
        assertNotEquals(original.release.id, revised.release.id)
    }

    @Test
    fun `the same title on two platforms stays two works until someone says otherwise`() {
        // Deliberate under-merge. Section 32 requires a port to be an explicit,
        // evidenced relationship, so deriving one work automatically would be
        // exactly the inference that section forbids.
        val cartridge = EntityPromoter.promote(Fixtures.record("Some Game (USA)"))
        val disc = EntityPromoter.promote(
            Fixtures.record(
                "Some Game (USA)",
                romName = "Some Game (USA).iso",
                source = Fixtures.source(provider = "redump", platform = Fixtures.psp),
            ),
        )

        assertNotEquals(cartridge.work.id, disc.work.id)
        assertNotEquals(cartridge.platform.id, disc.platform.id)
    }

    @Test
    fun `two representations of one release are two artifacts`() {
        // A disc preserved as `.cue` and as `.chd` is one release with two
        // digital images (Constitution section 38).
        val cue = Fixtures.record(
            "Some Game (USA)",
            romName = "Some Game (USA).cue",
            hashes = Fixtures.digests(Fixtures.sha1("aaaa")),
            source = Fixtures.source(provider = "redump", platform = Fixtures.psp),
        )
        val chd = Fixtures.record(
            "Some Game (USA)",
            romName = "Some Game (USA).chd",
            hashes = Fixtures.digests(Fixtures.sha1("bbbb")),
            source = Fixtures.source(provider = "redump", platform = Fixtures.psp),
        )

        assertEquals(EntityPromoter.promote(cue).release.id, EntityPromoter.promote(chd).release.id)
        assertNotEquals(EntityPromoter.promote(cue).artifact.id, EntityPromoter.promote(chd).artifact.id)
    }

    @Test
    fun `a hashless record does not collide with another hashless record`() {
        val first = Fixtures.record("Some Game (USA)", romName = "a.sfc", id = "first")
        val second = Fixtures.record("Some Game (USA)", romName = "b.sfc", id = "second")

        assertNotEquals(EntityPromoter.promote(first).artifact.id, EntityPromoter.promote(second).artifact.id)
    }

    @Test
    fun `everything promoted is derived, never confirmed`() {
        // Section 43: automation proposes. Only a person establishes.
        val promoted = EntityPromoter.promote(Fixtures.record("Some Game (USA)"))

        assertTrue(promoted.entities.all { it.provenance == EntityProvenance.DERIVED })
        assertTrue(promoted.relationships.all { it.provenance == EntityProvenance.DERIVED })
        assertFalse(promoted.entities.any { it.provenance.isConfirmed })
    }

    @Test
    fun `the dataset's own set name survives as an alias`() {
        // Section 43: maintain canonical identity while preserving aliases.
        val promoted = EntityPromoter.promote(Fixtures.record("Some Game (USA) (Rev A)"))

        assertTrue("Some Game (USA) (Rev A)" in promoted.work.aliases)
        assertEquals("Some Game", promoted.work.canonicalTitle)
    }

    @Test
    fun `media type carries through to the artifact`() {
        val promoted = EntityPromoter.promote(
            Fixtures.record("Some Game (USA)", romName = "Some Game (USA).iso"),
        )

        assertEquals(MediaType.OPTICAL_DISC, promoted.artifact.mediaType)
    }
}

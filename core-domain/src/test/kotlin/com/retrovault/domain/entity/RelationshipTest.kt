package com.retrovault.domain.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The controlled relationship vocabulary.
 *
 * Constitution section 40: relationships are first-class knowledge and the
 * vocabulary stays controlled. A typed edge is what stops free-form text
 * becoming the canonical graph structure.
 */
class RelationshipTest {

    private val work = EntityRef(EntityKind.WORK, "work:snes:some game")
    private val release = EntityRef(EntityKind.RELEASE, "release:snes|some game")
    private val other = EntityRef(EntityKind.RELEASE, "release:psp|some game")
    private val platform = EntityRef(EntityKind.PLATFORM, "platform:snes")
    private val artifact = EntityRef(EntityKind.ARTIFACT, "artifact:sha1:aa")

    @Test
    fun `an edge must connect the kinds its type declares`() {
        // A typed vocabulary that did not check its endpoints would be a naming
        // convention, not a constraint.
        val wrongWayRound = runCatching {
            EntityRelationship(work, RelationshipType.RELEASE_OF, release)
        }
        val wrongTarget = runCatching {
            EntityRelationship(release, RelationshipType.RUNS_ON, work)
        }

        assertTrue(wrongWayRound.isFailure)
        assertTrue(wrongTarget.isFailure)
    }

    @Test
    fun `the structural edges connect the constitution's chain`() {
        // Platform -> Work -> Release -> Artifact (Constitution section 305).
        EntityRelationship(release, RelationshipType.RELEASE_OF, work)
        EntityRelationship(release, RelationshipType.RUNS_ON, platform)
        EntityRelationship(artifact, RelationshipType.IMAGE_OF, release)

        assertEquals(
            setOf(
                RelationshipType.RELEASE_OF,
                RelationshipType.RUNS_ON,
                RelationshipType.IMAGE_OF,
            ),
            RelationshipType.entries.filterTo(mutableSetOf()) { it.isStructural },
        )
    }

    @Test
    fun `derivation relations exist but are never structural`() {
        // Section 32 requires port, remake, remaster and compilation to be
        // explicit. A release stands perfectly well without one, so asserting
        // one is a historical claim rather than part of the hierarchy.
        val port = EntityRelationship(release, RelationshipType.PORT_OF, other)

        assertTrue(RelationshipType.entries.any { it == RelationshipType.PORT_OF })
        assertFalse(port.type.isStructural)
    }

    @Test
    fun `an entity cannot relate to itself`() {
        assertTrue(
            runCatching { EntityRelationship(release, RelationshipType.PORT_OF, release) }.isFailure,
        )
    }

    @Test
    fun `an edge is keyed by its endpoints and its type`() {
        // Asserting the same relationship twice is one fact, not two.
        val first = EntityRelationship(release, RelationshipType.RELEASE_OF, work)
        val again = EntityRelationship(
            release,
            RelationshipType.RELEASE_OF,
            work,
            provenance = EntityProvenance.CONFIRMED,
            note = "checked the box art",
        )

        assertEquals(first.key(), again.key())
    }

    @Test
    fun `a reference describes itself without a database lookup`() {
        assertEquals("RELEASE:release:snes|some game", release.describe())
        assertTrue(runCatching { EntityRef(EntityKind.WORK, " ") }.isFailure)
    }
}

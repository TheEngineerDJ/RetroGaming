package com.retrovault.domain.rename

import com.retrovault.domain.Fixtures
import com.retrovault.domain.TestCatalogDriver
import com.retrovault.domain.catalog.DumpRecord
import com.retrovault.domain.identity.HashDigests
import com.retrovault.domain.identity.PlanEntryId
import com.retrovault.domain.identity.RenamePlanId
import com.retrovault.domain.identity.StorageRef
import com.retrovault.domain.naming.NamingProfiles
import com.retrovault.domain.observation.FileObservation
import com.retrovault.domain.policy.AutomationPolicy
import com.retrovault.domain.resolution.ResolutionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Planning rules.
 *
 * CLAUDE_CODE.md: never auto-rename an unresolved artifact, and ambiguity
 * must remain visible.
 */
class RenamePlanTest {

    private val crc = Fixtures.crc("aabbccdd")
    private val sha1 = Fixtures.sha1("1111")

    private fun plan(
        entries: List<Pair<FileObservation, List<DumpRecord>>>,
        policy: AutomationPolicy = AutomationPolicy(),
        confirmed: Set<com.retrovault.domain.identity.ObservationId> = emptySet(),
        content: Map<String?, HashDigests> = mapOf(null to Fixtures.digests(crc, sha1)),
    ): RenamePlan {
        val resolved = entries.map { (observation, records) ->
            observation to TestCatalogDriver(records, content).resolve(observation)
        }
        return RenamePlanBuilder.build(
            id = RenamePlanId("plan-1"),
            sessionId = Fixtures.sessionId,
            profile = NamingProfiles.NO_INTRO_V1,
            policy = policy,
            resolved = resolved,
            confirmations = confirmed,
            createdAtEpochMillis = 1_700_000_000_000L,
            entryIdFactory = { PlanEntryId("entry-${it.filename}") },
        )
    }

    @Test
    fun `an exact match is planned for rename`() {
        val record = Fixtures.record("Super Mario World (USA)", hashes = Fixtures.digests(crc, sha1))
        val observation = Fixtures.observation("smw_scrubbed.sfc")

        val entry = plan(listOf(observation to listOf(record))).entries.single()

        assertEquals(PlannedAction.RENAME, entry.action)
        assertEquals("Super Mario World (USA).sfc", entry.proposedName)
    }

    @Test
    fun `an already canonical file is skipped, making a rerun a no-op`() {
        val record = Fixtures.record("Super Mario World (USA)", hashes = Fixtures.digests(crc, sha1))
        val observation = Fixtures.observation("Super Mario World (USA).sfc")

        val entry = plan(listOf(observation to listOf(record))).entries.single()

        assertEquals(PlannedAction.SKIP_ALREADY_CANONICAL, entry.action)
    }

    @Test
    fun `planning twice produces the same plan`() {
        val record = Fixtures.record("Super Mario World (USA)", hashes = Fixtures.digests(crc, sha1))
        val observation = Fixtures.observation("messy.sfc")

        val first = plan(listOf(observation to listOf(record)))
        val second = plan(listOf(observation to listOf(record)))

        assertEquals(
            first.entries.map { it.proposedName to it.action },
            second.entries.map { it.proposedName to it.action },
        )
    }

    @Test
    fun `an ambiguous identity is never planned for rename`() {
        val japan = Fixtures.record("Some Game (Japan)", hashes = Fixtures.digests(crc, sha1))
        val usa = Fixtures.record("Some Game (USA)", hashes = Fixtures.digests(crc, sha1))
        val observation = Fixtures.observation("some game.sfc")

        val entry = plan(listOf(observation to listOf(japan, usa))).entries.single()

        assertEquals(ResolutionState.AMBIGUOUS, entry.resolution.state)
        assertEquals(PlannedAction.SKIP_UNRESOLVED, entry.action)
        assertNull(entry.proposedName)
    }

    @Test
    fun `an unmatched file is never planned for rename`() {
        val observation = Fixtures.observation("mystery.sfc", size = 42)

        val entry = plan(listOf(observation to emptyList())).entries.single()

        assertEquals(PlannedAction.SKIP_UNRESOLVED, entry.action)
        assertTrue(entry.issues.any { it is PlanIssue.NotRenamed })
    }

    @Test
    fun `a fuzzy match waits for confirmation`() {
        val record = Fixtures.record("Super Mario World (USA)", size = 524_288, hashes = HashDigests.EMPTY)
        val observation = Fixtures.observation("Super Mario World.sfc", size = 500_000)

        val entry = plan(
            listOf(observation to listOf(record)),
            content = emptyMap(),
        ).entries.single()

        assertEquals(ResolutionState.FUZZY_MATCH, entry.resolution.state)
        assertEquals(PlannedAction.SKIP_REQUIRES_REVIEW, entry.action)
    }

    @Test
    fun `a confirmed fuzzy match becomes actionable`() {
        val record = Fixtures.record("Super Mario World (USA)", size = 524_288, hashes = HashDigests.EMPTY)
        val observation = Fixtures.observation("Super Mario World.sfc", size = 500_000)

        val entry = plan(
            listOf(observation to listOf(record)),
            confirmed = setOf(observation.id),
            content = emptyMap(),
        ).entries.single()

        assertEquals(PlannedAction.RENAME, entry.action)
        assertTrue(entry.userConfirmed)
    }

    @Test
    fun `confirmation cannot make a forbidden identity actionable`() {
        val japan = Fixtures.record("Some Game (Japan)", hashes = Fixtures.digests(crc, sha1))
        val usa = Fixtures.record("Some Game (USA)", hashes = Fixtures.digests(crc, sha1))
        val observation = Fixtures.observation("some game.sfc")

        val entry = plan(
            listOf(observation to listOf(japan, usa)),
            confirmed = setOf(observation.id),
        ).entries.single()

        assertEquals(
            PlannedAction.SKIP_UNRESOLVED,
            entry.action,
            "A user cannot confirm an identity RetroVault never established",
        )
    }

    @Test
    fun `a structural match needs review unless the policy allows it`() {
        val record = Fixtures.record("Some Game (USA)", hashes = Fixtures.digests(crc))
        val observation = Fixtures.observation("messy.sfc")
        val content = mapOf<String?, HashDigests>(null to Fixtures.digests(crc))

        val cautious = plan(listOf(observation to listOf(record)), content = content).entries.single()
        val permissive = plan(
            listOf(observation to listOf(record)),
            policy = AutomationPolicy(allowStructuralAutomation = true),
            content = content,
        ).entries.single()

        assertEquals(ResolutionState.STRUCTURAL_MATCH, cautious.resolution.state)
        assertEquals(PlannedAction.SKIP_REQUIRES_REVIEW, cautious.action)
        assertEquals(PlannedAction.RENAME, permissive.action)
    }

    @Test
    fun `a plan can drop excluded entries so it can be revalidated`() {
        val record = Fixtures.record("Super Mario World (USA)", hashes = Fixtures.digests(crc, sha1))
        val first = Fixtures.observation("a.sfc", id = "obs-a")
        val second = Fixtures.observation("b.sfc", id = "obs-b")

        val full = plan(listOf(first to listOf(record), second to listOf(record)))
        val reduced = full.without(setOf(PlanEntryId("entry-a.sfc")))

        assertEquals(2, full.entries.size)
        assertEquals(1, reduced.entries.size)
    }

    @Test
    fun `the plan counts what will and will not change`() {
        val record = Fixtures.record("Super Mario World (USA)", hashes = Fixtures.digests(crc, sha1))
        val renamed = Fixtures.observation("messy.sfc", id = "obs-a")
        val canonical = Fixtures.observation("Super Mario World (USA).sfc", id = "obs-b")

        val result = plan(listOf(renamed to listOf(record), canonical to listOf(record)))

        assertEquals(1, result.renameCount)
        assertEquals(1, result.skippedCount)
    }

    @Test
    fun `a zip is renamed to a canonical name that keeps the zip extension`() {
        val record = Fixtures.record(
            "Super Mario World (USA)",
            romName = "Super Mario World (USA).sfc",
            hashes = Fixtures.digests(crc, sha1),
        )
        val observation = Fixtures.observation(
            filename = "smw.zip",
            container = com.retrovault.domain.identity.ContainerKind.ZIP,
            archiveEntries = listOf(Fixtures.zipEntry("smw.sfc", hashes = Fixtures.digests(crc))),
        )

        val entry = plan(
            listOf(observation to listOf(record)),
            content = mapOf("smw.sfc" to Fixtures.digests(crc, sha1)),
        ).entries.single()

        assertEquals("Super Mario World (USA).zip", entry.proposedName)
    }

    @Test
    fun `every planned entry keeps the storage location it came from`() {
        val record = Fixtures.record("Super Mario World (USA)", hashes = Fixtures.digests(crc, sha1))
        val observation = Fixtures.observation("messy.sfc", directory = "content://tree/roms/snes")

        val entry = plan(listOf(observation to listOf(record))).entries.single()

        assertEquals(StorageRef("content://tree/roms/snes"), entry.directoryRef)
    }
}

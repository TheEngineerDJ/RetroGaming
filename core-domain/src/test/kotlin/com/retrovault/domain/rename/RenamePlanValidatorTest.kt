package com.retrovault.domain.rename

import com.retrovault.domain.Fixtures
import com.retrovault.domain.identity.PlanEntryId
import com.retrovault.domain.identity.RenamePlanId
import com.retrovault.domain.identity.StorageRef
import com.retrovault.domain.naming.NamingProfiles
import com.retrovault.domain.observation.FileObservation
import com.retrovault.domain.policy.AutomationDecision
import com.retrovault.domain.policy.AutomationPolicy
import com.retrovault.domain.resolution.ArtifactResolution
import com.retrovault.domain.resolution.Candidate
import com.retrovault.domain.resolution.ConfidenceLevel
import com.retrovault.domain.resolution.ResolutionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whole-batch validation.
 *
 * Constitution section 159: no partial batch executes when the system can
 * safely prevent it. Every blocking issue therefore refuses the entire batch.
 */
class RenamePlanValidatorTest {

    private val validator = RenamePlanValidator()
    private val directory = StorageRef("content://tree/roms")
    private val now = 1_700_000_100_000L

    private fun resolution(observation: FileObservation, setName: String): ArtifactResolution {
        val candidate = Candidate(record = Fixtures.record(setName), score = 100)
        return ArtifactResolution(
            observationId = observation.id,
            state = ResolutionState.EXACT_HASH,
            confidence = ConfidenceLevel.EXACT,
            selected = candidate,
            candidates = listOf(candidate),
            pipelineEvidence = emptyList(),
            hashesComputed = emptySet(),
            consultedSources = emptyList(),
            resolverVersion = "test",
            tokenizerVersion = "test",
            normalizerVersion = "test",
        )
    }

    private fun entry(
        filename: String,
        proposed: String,
        setName: String = "Some Game (USA)",
        action: PlannedAction = PlannedAction.RENAME,
        automation: AutomationDecision = AutomationDecision.AUTOMATIC,
        size: Long = 524_288,
        directoryRef: StorageRef = directory,
    ): RenamePlanEntry {
        val observation = Fixtures.observation(
            filename,
            size = size,
            id = "obs-${directoryRef.value}-$filename",
            directory = directoryRef.value,
        )
        return RenamePlanEntry(
            id = PlanEntryId("entry-${directoryRef.value}-$filename"),
            observation = observation,
            resolution = resolution(observation, setName),
            currentName = filename,
            proposedName = proposed,
            action = action,
            automation = automation,
            userConfirmed = false,
        )
    }

    private fun plan(vararg entries: RenamePlanEntry) = RenamePlan(
        id = RenamePlanId("plan-1"),
        sessionId = Fixtures.sessionId,
        profile = NamingProfiles.NO_INTRO_V1,
        policy = AutomationPolicy(),
        entries = entries.toList(),
        createdAtEpochMillis = 1_700_000_000_000L,
    )

    private fun states(vararg entries: RenamePlanEntry, writable: Boolean = true) =
        entries.associate { entry ->
            entry.storageRef to ArtifactState(
                storageRef = entry.storageRef,
                exists = true,
                filename = entry.observation.filename,
                size = entry.observation.size,
                writable = writable,
            )
        }

    private fun snapshot(vararg names: String) =
        mapOf(directory to DirectorySnapshot(directory, names.toSet()))

    @Test
    fun `a clean batch is executable`() {
        val one = entry("a.sfc", "Some Game (USA).sfc")
        val result = validator.validate(plan(one), snapshot("a.sfc"), states(one), now)

        assertEquals(PlanVerdict.EXECUTABLE, result.verdict)
        assertEquals(1, result.executable.size)
    }

    @Test
    fun `two sources resolving to one destination block the whole batch`() {
        val one = entry("a.sfc", "Some Game (USA).sfc")
        val two = entry("b.sfc", "Some Game (USA).sfc")

        val result = validator.validate(plan(one, two), snapshot("a.sfc", "b.sfc"), states(one, two), now)

        assertEquals(PlanVerdict.BLOCKED, result.verdict)
        assertTrue(result.blockingIssues.any { it is PlanIssue.DuplicateDestination })
        assertTrue(result.executable.isEmpty(), "A blocked batch executes nothing at all")
    }

    @Test
    fun `duplicate detection is case insensitive`() {
        val one = entry("a.sfc", "Some Game (USA).sfc")
        val two = entry("b.sfc", "SOME GAME (USA).SFC")

        val result = validator.validate(plan(one, two), snapshot("a.sfc", "b.sfc"), states(one, two), now)

        assertEquals(PlanVerdict.BLOCKED, result.verdict)
        assertTrue(result.blockingIssues.any { it is PlanIssue.DuplicateDestination })
    }

    @Test
    fun `an occupied destination blocks the batch`() {
        val one = entry("a.sfc", "Some Game (USA).sfc")

        val result = validator.validate(
            plan(one),
            snapshot("a.sfc", "Some Game (USA).sfc"),
            states(one),
            now,
        )

        assertEquals(PlanVerdict.BLOCKED, result.verdict)
        assertTrue(result.blockingIssues.any { it is PlanIssue.DestinationOccupied })
    }

    @Test
    fun `an occupied destination is detected case insensitively`() {
        val one = entry("a.sfc", "Some Game (USA).sfc")

        val result = validator.validate(
            plan(one),
            snapshot("a.sfc", "SOME GAME (usa).SFC"),
            states(one),
            now,
        )

        assertEquals(PlanVerdict.BLOCKED, result.verdict)
    }

    @Test
    fun `a case-only rename is a warning, not a collision with itself`() {
        val one = entry("some game (usa).sfc", "Some Game (USA).sfc")

        val result = validator.validate(plan(one), snapshot("some game (usa).sfc"), states(one), now)

        assertEquals(PlanVerdict.EXECUTABLE, result.verdict)
        assertTrue(result.warnings.any { it is PlanIssue.CaseOnlyRename })
    }

    @Test
    fun `an invalid destination name blocks the batch`() {
        val one = entry("a.sfc", "../escape.sfc")

        val result = validator.validate(plan(one), snapshot("a.sfc"), states(one), now)

        assertEquals(PlanVerdict.BLOCKED, result.verdict)
        assertTrue(result.blockingIssues.any { it is PlanIssue.InvalidDestinationName })
    }

    @Test
    fun `a file that changed size since the scan blocks the batch`() {
        val one = entry("a.sfc", "Some Game (USA).sfc")
        val stale = mapOf(
            one.storageRef to ArtifactState(one.storageRef, exists = true, filename = "a.sfc", size = 1, writable = true),
        )

        val result = validator.validate(plan(one), snapshot("a.sfc"), stale, now)

        assertEquals(PlanVerdict.BLOCKED, result.verdict)
        assertTrue(result.blockingIssues.any { it is PlanIssue.StaleObservation })
    }

    @Test
    fun `a file renamed by someone else since the scan blocks the batch`() {
        val one = entry("a.sfc", "Some Game (USA).sfc")
        val stale = mapOf(
            one.storageRef to ArtifactState(
                one.storageRef,
                exists = true,
                filename = "renamed-elsewhere.sfc",
                size = one.observation.size,
                writable = true,
            ),
        )

        val result = validator.validate(plan(one), snapshot("a.sfc"), stale, now)

        assertEquals(PlanVerdict.BLOCKED, result.verdict)
        assertTrue(result.blockingIssues.any { it is PlanIssue.StaleObservation })
    }

    @Test
    fun `a deleted file blocks the batch`() {
        val one = entry("a.sfc", "Some Game (USA).sfc")
        val gone = mapOf(
            one.storageRef to ArtifactState(one.storageRef, exists = false, filename = null, size = null, writable = false),
        )

        val result = validator.validate(plan(one), snapshot(), gone, now)

        assertEquals(PlanVerdict.BLOCKED, result.verdict)
        assertTrue(result.blockingIssues.any { it is PlanIssue.StaleObservation })
    }

    @Test
    fun `a read-only file blocks the batch`() {
        val one = entry("a.sfc", "Some Game (USA).sfc")

        val result = validator.validate(
            plan(one),
            snapshot("a.sfc"),
            states(one, writable = false),
            now,
        )

        assertEquals(PlanVerdict.BLOCKED, result.verdict)
        assertTrue(result.blockingIssues.any { it is PlanIssue.PermissionDenied })
    }

    @Test
    fun `an unreadable folder blocks the batch`() {
        val one = entry("a.sfc", "Some Game (USA).sfc")

        val result = validator.validate(plan(one), emptyMap(), states(one), now)

        assertEquals(PlanVerdict.BLOCKED, result.verdict)
        assertTrue(result.blockingIssues.any { it is PlanIssue.UnsupportedOperation })
    }

    @Test
    fun `a rename that policy never authorised is refused even if the plan asks for it`() {
        // Defence in depth: a persisted plan could be replayed after the policy
        // changed, or be corrupted. The validator re-checks rather than trusting.
        val one = entry(
            "a.sfc",
            "Some Game (USA).sfc",
            automation = AutomationDecision.FORBIDDEN,
        )

        val result = validator.validate(plan(one), snapshot("a.sfc"), states(one), now)

        assertEquals(PlanVerdict.BLOCKED, result.verdict)
        assertTrue(result.blockingIssues.any { it is PlanIssue.UnsafeAutomation })
    }

    @Test
    fun `an unconfirmed review-required rename is refused`() {
        val one = entry(
            "a.sfc",
            "Some Game (USA).sfc",
            automation = AutomationDecision.REQUIRES_REVIEW,
        )

        val result = validator.validate(plan(one), snapshot("a.sfc"), states(one), now)

        assertEquals(PlanVerdict.BLOCKED, result.verdict)
        assertTrue(result.blockingIssues.any { it is PlanIssue.UnsafeAutomation })
    }

    @Test
    fun `a plan with nothing to rename reports nothing to do`() {
        val skipped = entry(
            "Some Game (USA).sfc",
            "Some Game (USA).sfc",
            action = PlannedAction.SKIP_ALREADY_CANONICAL,
        )

        val result = validator.validate(plan(skipped), snapshot("Some Game (USA).sfc"), states(skipped), now)

        assertEquals(PlanVerdict.NOTHING_TO_DO, result.verdict)
    }

    @Test
    fun `excluding the offending entry makes the rest executable again`() {
        val one = entry("a.sfc", "Some Game (USA).sfc")
        val two = entry("b.sfc", "Some Game (USA).sfc")
        val full = plan(one, two)

        val blocked = validator.validate(full, snapshot("a.sfc", "b.sfc"), states(one, two), now)
        val reduced = validator.validate(
            full.without(setOf(two.id)),
            snapshot("a.sfc", "b.sfc"),
            states(one, two),
            now,
        )

        assertEquals(PlanVerdict.BLOCKED, blocked.verdict)
        assertEquals(PlanVerdict.EXECUTABLE, reduced.verdict)
    }

    @Test
    fun `validation does not mutate the plan it was given`() {
        val one = entry("a.sfc", "../escape.sfc")
        val original = plan(one)

        validator.validate(original, snapshot("a.sfc"), states(one), now)

        assertEquals(PlannedAction.RENAME, original.entries.single().action)
        assertTrue(original.entries.single().issues.isEmpty())
    }

    @Test
    fun `validation is deterministic`() {
        val one = entry("a.sfc", "Some Game (USA).sfc")
        val two = entry("b.sfc", "Some Game (USA).sfc")

        val first = validator.validate(plan(one, two), snapshot("a.sfc", "b.sfc"), states(one, two), now)
        val second = validator.validate(plan(one, two), snapshot("a.sfc", "b.sfc"), states(one, two), now)

        assertEquals(first.verdict, second.verdict)
        assertEquals(
            first.issues.map { it.message },
            second.issues.map { it.message },
        )
    }

    // ------------------------------------------------------------------
    // Ordering: a name held by another file in the same batch
    // ------------------------------------------------------------------

    @Test
    fun `a destination held by a file that is itself moving is not a collision`() {
        // The ordinary case for a folder being brought onto a new convention:
        // every file shifts along by one name. Reading the occupant as a
        // collision would block the batch that the feature exists to perform.
        val first = entry("a.sfc", "b.sfc")
        val second = entry("b.sfc", "c.sfc")
        val plan = plan(first, second)

        val validation = validator.validate(
            plan,
            snapshot("a.sfc", "b.sfc"),
            states(first, second),
            now,
        )

        assertEquals(PlanVerdict.EXECUTABLE, validation.verdict, validation.issues.map { it.message }.toString())
    }

    @Test
    fun `renames are ordered so a name is freed before it is taken`() {
        val first = entry("a.sfc", "b.sfc")
        val second = entry("b.sfc", "c.sfc")

        val validation = validator.validate(
            plan(first, second),
            snapshot("a.sfc", "b.sfc"),
            states(first, second),
            now,
        )

        assertEquals(
            listOf("b.sfc", "a.sfc"),
            validation.executable.map { it.currentName },
            "'b.sfc' must vacate its name before 'a.sfc' can take it",
        )
    }

    @Test
    fun `a longer chain is ordered from the far end`() {
        val a = entry("a.sfc", "b.sfc")
        val b = entry("b.sfc", "c.sfc")
        val c = entry("c.sfc", "d.sfc")

        val validation = validator.validate(
            plan(a, b, c),
            snapshot("a.sfc", "b.sfc", "c.sfc"),
            states(a, b, c),
            now,
        )

        assertEquals(listOf("c.sfc", "b.sfc", "a.sfc"), validation.executable.map { it.currentName })
    }

    @Test
    fun `two files that want each other's names are refused, not attempted`() {
        val first = entry("a.sfc", "b.sfc")
        val second = entry("b.sfc", "a.sfc")

        val validation = validator.validate(
            plan(first, second),
            snapshot("a.sfc", "b.sfc"),
            states(first, second),
            now,
        )

        assertEquals(PlanVerdict.BLOCKED, validation.verdict)
        val cycle = validation.issues.filterIsInstance<PlanIssue.RenameCycle>().first()
        assertEquals(setOf("a.sfc", "b.sfc"), cycle.names.toSet())
        assertTrue(validation.executable.isEmpty())
    }

    @Test
    fun `a destination held by a file that is staying put is still a collision`() {
        val moving = entry("a.sfc", "keeper.sfc")
        val staying = entry("keeper.sfc", "keeper.sfc", action = PlannedAction.SKIP_ALREADY_CANONICAL)

        val validation = validator.validate(
            plan(moving, staying),
            snapshot("a.sfc", "keeper.sfc"),
            states(moving, staying),
            now,
        )

        assertEquals(PlanVerdict.BLOCKED, validation.verdict)
        assertTrue(validation.issues.any { it is PlanIssue.DestinationOccupied })
    }

    @Test
    fun `an unrelated deadlock is reported separately from another folder's`() {
        val otherDirectory = StorageRef("content://tree/other")
        val a = entry("a.sfc", "b.sfc")
        val b = entry("b.sfc", "a.sfc")
        val c = entry("c.sfc", "d.sfc", directoryRef = otherDirectory)
        val d = entry("d.sfc", "c.sfc", directoryRef = otherDirectory)

        val validation = validator.validate(
            plan(a, b, c, d),
            snapshot("a.sfc", "b.sfc") + mapOf(
                otherDirectory to DirectorySnapshot(otherDirectory, setOf("c.sfc", "d.sfc")),
            ),
            states(a, b, c, d),
            now,
        )

        val cycles = validation.issues.filterIsInstance<PlanIssue.RenameCycle>().distinct()
        assertEquals(2, cycles.size, "Two folders' swaps are two problems: ${cycles.map { it.names }}")
    }

    @Test
    fun `a file whose state could not be read is not reported as missing`() {
        // Both outcomes block the batch, so this changes no decision. It changes
        // what the user is told, and "the file no longer exists" is not true of
        // a file storage refused to describe.
        val entry = entry("a.sfc", "Some Game (USA).sfc")
        val unreadable = mapOf(
            entry.storageRef to ArtifactState(
                storageRef = entry.storageRef,
                exists = false,
                filename = null,
                size = null,
                writable = false,
                readable = false,
            ),
        )

        val validation = validator.validate(plan(entry), snapshot("a.sfc"), unreadable, now)

        assertEquals(PlanVerdict.BLOCKED, validation.verdict)
        val issue = validation.blockingIssues.single()
        assertTrue(issue is PlanIssue.UnsupportedOperation, "Got ${issue::class.simpleName}")
        assertTrue(
            issue.message.contains("could not be read"),
            "The message must describe the read failure: ${issue.message}",
        )
    }

    @Test
    fun `a genuinely absent file is still reported as stale`() {
        val entry = entry("a.sfc", "Some Game (USA).sfc")
        val absent = mapOf(
            entry.storageRef to ArtifactState(
                storageRef = entry.storageRef,
                exists = false,
                filename = null,
                size = null,
                writable = false,
            ),
        )

        val validation = validator.validate(plan(entry), snapshot("a.sfc"), absent, now)

        assertTrue(validation.blockingIssues.single() is PlanIssue.StaleObservation)
    }
}

package com.retrovault.domain.rename

import com.retrovault.domain.identity.HashValue
import com.retrovault.domain.identity.PlanEntryId
import com.retrovault.domain.identity.RenameBatchId
import com.retrovault.domain.identity.RenameOperationId
import com.retrovault.domain.identity.RenamePlanId
import com.retrovault.domain.identity.ScanSessionId
import com.retrovault.domain.identity.StorageRef
import com.retrovault.domain.resolution.ConfidenceLevel
import com.retrovault.domain.resolution.ResolutionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Putting a rename batch back.
 *
 * Reversing is the most destructive thing RetroVault can be asked to do: it
 * renames files the user may have touched since, using a record of what
 * happened rather than a fresh identification. Every rule that decides whether
 * a step is safe is therefore checked here, without a filesystem.
 */
class RenameUndoTest {

    private val directory = StorageRef("file:///roms")

    private fun operation(
        id: String,
        from: String,
        to: String,
        state: RenameOperationState = RenameOperationState.COMPLETED,
        size: Long = 4096,
    ) = RenameOperation(
        id = RenameOperationId(id),
        batchId = RenameBatchId("batch"),
        planEntryId = PlanEntryId("entry-$id"),
        sourceRef = StorageRef("file:///roms/$id"),
        directoryRef = directory,
        sourceName = from,
        destinationName = to,
        resolutionState = ResolutionState.EXACT_HASH,
        confidence = ConfidenceLevel.EXACT,
        identityDescription = "some game [USA]",
        namingProfileVersionedId = "no-intro@v1",
        preconditionSize = size,
        preconditionHash = null as HashValue?,
        state = state,
        plannedAtEpochMillis = 1,
    )

    private fun batch(vararg operations: RenameOperation, dryRun: Boolean = false) = RenameBatch(
        id = RenameBatchId("batch"),
        planId = RenamePlanId("plan"),
        sessionId = ScanSessionId("session"),
        namingProfileVersionedId = "no-intro@v1",
        policyVersion = "automation-policy-v1",
        dryRun = dryRun,
        createdAtEpochMillis = 1,
        operations = operations.toList(),
    )

    private fun state(
        operation: RenameOperation,
        name: String? = operation.destinationName,
        size: Long? = operation.preconditionSize,
        exists: Boolean = true,
        readable: Boolean = true,
    ) = operation.sourceRef to ArtifactState(
        storageRef = operation.sourceRef,
        exists = exists,
        filename = name,
        size = size,
        writable = true,
        readable = readable,
    )

    private fun folder(vararg names: String) =
        mapOf(directory to DirectorySnapshot(directory, names.toSet()))

    // ------------------------------------------------------------------
    // What may be reversed at all
    // ------------------------------------------------------------------

    @Test
    fun `only renames that actually happened can be put back`() {
        val batch = batch(
            operation("a", "old-a.sfc", "New A.sfc", RenameOperationState.COMPLETED),
            operation("b", "old-b.sfc", "New B.sfc", RenameOperationState.FAILED),
            operation("c", "old-c.sfc", "New C.sfc", RenameOperationState.SKIPPED),
            operation("d", "old-d.sfc", "New D.sfc", RenameOperationState.RECONCILED_NOT_APPLIED),
            operation("e", "old-e.sfc", "New E.sfc", RenameOperationState.RECONCILED_COMPLETED),
        )

        val reversible = RenameUndoPlanner.reversible(batch).map { it.id.value }

        assertEquals(
            listOf("a", "e"),
            reversible,
            "Reversing a rename that never happened would rename a file nothing had touched",
        )
    }

    @Test
    fun `an interrupted rename nobody could explain is never reversed`() {
        // RECONCILED_UNKNOWN means the filesystem stopped explaining what
        // happened. Acting on that is exactly what the reconciler refused to do.
        val batch = batch(operation("a", "old.sfc", "New.sfc", RenameOperationState.RECONCILED_UNKNOWN))

        assertTrue(RenameUndoPlanner.reversible(batch).isEmpty())
    }

    @Test
    fun `a dry run has nothing to put back`() {
        val batch = batch(operation("a", "old.sfc", "New.sfc"), dryRun = true)

        assertTrue(RenameUndoPlanner.reversible(batch).isEmpty(), "A dry run never touched the filesystem")
    }

    // ------------------------------------------------------------------
    // Safety
    // ------------------------------------------------------------------

    @Test
    fun `a batch is put back in the reverse of the order it ran`() {
        // The forward order guaranteed no rename ran while another held its
        // name. Running it backwards restores that guarantee.
        val first = operation("a", "one.sfc", "two.sfc")
        val second = operation("b", "two.sfc", "three.sfc")
        val plan = RenameUndoPlanner.plan(
            batch(first, second),
            folder("three.sfc", "two.sfc"),
            mapOf(state(first, name = "two.sfc"), state(second, name = "three.sfc")),
        )

        assertTrue(plan.isExecutable, plan.issues.toString())
        assertEquals(
            listOf("b", "a"),
            plan.steps.map { it.operationId.value },
            "Reversing 'two -> three' first is what frees 'two' for 'one -> two'",
        )
    }

    @Test
    fun `a file that is no longer there is not put back`() {
        val operation = operation("a", "old.sfc", "New.sfc")
        val plan = RenameUndoPlanner.plan(
            batch(operation),
            folder(),
            mapOf(state(operation, exists = false, name = null, size = null)),
        )

        assertFalse(plan.isExecutable)
        assertEquals(UndoRefusal.RENAMED_FILE_MISSING, plan.issues.single().refusal)
    }

    @Test
    fun `a file something else has since renamed is not put back`() {
        val operation = operation("a", "old.sfc", "New.sfc")
        val plan = RenameUndoPlanner.plan(
            batch(operation),
            folder("Something Else.sfc"),
            mapOf(state(operation, name = "Something Else.sfc")),
        )

        assertFalse(plan.isExecutable)
        assertEquals(UndoRefusal.RENAMED_FILE_MISSING, plan.issues.single().refusal)
    }

    @Test
    fun `a file whose contents changed is not put back`() {
        // Undoing would give a different file the old name, which is a claim
        // about identity that nothing checked.
        val operation = operation("a", "old.sfc", "New.sfc", size = 4096)
        val plan = RenameUndoPlanner.plan(
            batch(operation),
            folder("New.sfc"),
            mapOf(state(operation, size = 8192)),
        )

        assertFalse(plan.isExecutable)
        assertEquals(UndoRefusal.CONTENT_CHANGED, plan.issues.single().refusal)
    }

    @Test
    fun `an original name something else now occupies is not overwritten`() {
        val operation = operation("a", "old.sfc", "New.sfc")
        val plan = RenameUndoPlanner.plan(
            batch(operation),
            folder("New.sfc", "old.sfc"),
            mapOf(state(operation)),
        )

        assertFalse(plan.isExecutable)
        assertEquals(UndoRefusal.ORIGINAL_NAME_TAKEN, plan.issues.single().refusal)
    }

    @Test
    fun `an occupant that this reversal itself frees is not a collision`() {
        val first = operation("a", "one.sfc", "two.sfc")
        val second = operation("b", "two.sfc", "three.sfc")
        val plan = RenameUndoPlanner.plan(
            batch(first, second),
            folder("two.sfc", "three.sfc"),
            mapOf(state(first, name = "two.sfc"), state(second, name = "three.sfc")),
        )

        assertTrue(plan.isExecutable, plan.issues.toString())
    }

    @Test
    fun `an unreadable file blocks the reversal rather than being assumed gone`() {
        val operation = operation("a", "old.sfc", "New.sfc")
        val plan = RenameUndoPlanner.plan(
            batch(operation),
            folder("New.sfc"),
            mapOf(state(operation, readable = false, exists = false, name = null, size = null)),
        )

        assertEquals(UndoRefusal.FILE_UNREADABLE, plan.issues.single().refusal)
    }

    @Test
    fun `an unreadable folder blocks the reversal`() {
        val operation = operation("a", "old.sfc", "New.sfc")
        val plan = RenameUndoPlanner.plan(batch(operation), emptyMap(), mapOf(state(operation)))

        assertEquals(UndoRefusal.FOLDER_UNREADABLE, plan.issues.single().refusal)
    }

    @Test
    fun `one unsafe step blocks the whole reversal`() {
        // All-or-nothing, for the same reason the forward plan is: a half
        // reversed batch leaves a library in a state nothing describes.
        val safe = operation("a", "safe-old.sfc", "Safe New.sfc")
        val unsafe = operation("b", "unsafe-old.sfc", "Unsafe New.sfc")
        val plan = RenameUndoPlanner.plan(
            batch(safe, unsafe),
            folder("Safe New.sfc", "Unsafe New.sfc", "unsafe-old.sfc"),
            mapOf(state(safe), state(unsafe)),
        )

        assertFalse(plan.isExecutable)
        assertTrue(plan.executable.isEmpty(), "Nothing runs while anything is unsafe")
        assertEquals(2, plan.steps.size, "The safe step is still shown, so the user can exclude the other")
    }

    @Test
    fun `a case-only reversal is staged through a third name`() {
        val operation = operation("a", "Game.sfc", "game.sfc")
        val plan = RenameUndoPlanner.plan(
            batch(operation),
            folder("game.sfc"),
            mapOf(state(operation, name = "game.sfc")),
        )

        assertTrue(plan.isExecutable, plan.issues.toString())
        val step = plan.steps.single()
        assertTrue(step.requiresStaging, "On FAT and exFAT the two names are the same file")
        assertTrue(step.intermediateName!!.endsWith(RenameStaging.SUFFIX))
    }

    @Test
    fun `excluding a step revalidates the rest`() {
        val first = operation("a", "one.sfc", "two.sfc")
        val second = operation("b", "two.sfc", "three.sfc")
        val states = mapOf(state(first, name = "two.sfc"), state(second, name = "three.sfc"))
        val folder = folder("two.sfc", "three.sfc")

        val whole = RenameUndoPlanner.plan(batch(first, second), folder, states)
        assertTrue(whole.isExecutable)

        // 'a' currently holds 'two.sfc' and was going to vacate it. Excluding
        // it means 'b' would be put back onto a name that is still occupied -
        // so the step that was safe only because of its neighbour stops being
        // safe once the neighbour is gone.
        val reduced = RenameUndoPlanner.validate(
            whole.without(setOf(RenameOperationId("a"))),
            folder,
            states,
        )

        assertFalse(reduced.isExecutable)
        assertEquals(UndoRefusal.ORIGINAL_NAME_TAKEN, reduced.issues.single().refusal)
        assertEquals(RenameOperationId("b"), reduced.issues.single().operationId)
    }

    @Test
    fun `a batch with nothing reversible reports nothing to do rather than a problem`() {
        val plan = RenameUndoPlanner.plan(
            batch(operation("a", "old.sfc", "New.sfc", RenameOperationState.FAILED)),
            folder(),
            emptyMap(),
        )

        assertTrue(plan.hasNothingToDo)
        assertFalse(plan.isExecutable)
    }
}

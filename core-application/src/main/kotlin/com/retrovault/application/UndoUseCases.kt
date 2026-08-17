package com.retrovault.application

import com.retrovault.domain.identity.RenameBatchId
import com.retrovault.domain.identity.RenameOperationId
import com.retrovault.domain.identity.StorageRef
import com.retrovault.domain.rename.ArtifactState
import com.retrovault.domain.rename.DirectorySnapshot
import com.retrovault.domain.rename.RenameBatch
import com.retrovault.domain.rename.RenameFailure
import com.retrovault.domain.rename.RenameOperation
import com.retrovault.domain.rename.RenameOperationState
import com.retrovault.domain.rename.RenameUndoPlanner
import com.retrovault.domain.rename.UndoPlan
import com.retrovault.domain.rename.UndoStep

/** What reversing a batch did. */
data class UndoResult(
    val batchId: RenameBatchId,
    val plan: UndoPlan,
    val restored: Int,
    val failed: Int,
    /** Set when the whole reversal was refused before anything was touched. */
    val refused: RetroVaultFailure? = null,
) {
    val isFullySuccessful: Boolean get() = refused == null && failed == 0 && restored == plan.steps.size
}

/**
 * Puts a rename batch back.
 *
 * Constitution section 170 requires a rename to preserve enough information to
 * reverse it. Storing that information is only half of the requirement - this
 * is the half that makes it true, and until it existed the journal was a
 * promise the product could not keep.
 *
 * The order of operations mirrors [ExecuteRenamePlanUseCase] exactly, and for
 * the same reason: re-read the world, refuse the whole reversal if anything is
 * unsafe, and record the intent to move a file *before* moving it, so an
 * interrupted reversal is as reconstructable as an interrupted rename.
 */
class UndoRenameBatchUseCase(
    private val journal: RenameJournalRepository,
    private val contentSource: ContentSource,
    private val executor: RenameExecutor,
    private val clock: Clock,
) {

    /** What reversing this batch would do, without doing any of it. */
    suspend fun preview(batchId: RenameBatchId): Outcome<UndoPlan> {
        val batch = when (val found = journal.findBatch(batchId)) {
            is Outcome.Success -> found.value
            is Outcome.Failure -> return found
        }
        return Outcome.success(planFor(batch, emptySet()))
    }

    /**
     * @param excluded operations the user chose not to reverse. Excluding one
     * revalidates the rest, because a step that was safe only because another
     * freed its name is no longer safe once that other step is gone.
     */
    suspend fun undo(
        batchId: RenameBatchId,
        excluded: Set<RenameOperationId> = emptySet(),
    ): Outcome<UndoResult> {
        val batch = when (val found = journal.findBatch(batchId)) {
            is Outcome.Success -> found.value
            is Outcome.Failure -> return found
        }
        // A dry run never touched the filesystem, so there is nothing to put
        // back. Reporting that as "blocked" would suggest an obstacle; it is
        // simply an empty reversal.
        if (batch.dryRun) {
            return Outcome.success(UndoResult(batchId, UndoPlan(emptyList(), emptyList()), 0, 0))
        }

        val plan = planFor(batch, excluded)
        if (!plan.isExecutable) {
            // Nothing is touched. A partially reversed batch would leave the
            // library in a state neither the user nor the journal describes.
            return Outcome.success(
                UndoResult(
                    batchId = batchId,
                    plan = plan,
                    restored = 0,
                    failed = 0,
                    refused = if (plan.hasNothingToDo) null else RetroVaultFailure.PlanBlocked(plan.issues.size),
                ),
            )
        }

        val byId = batch.operations.associateBy { it.id }
        var restored = 0
        var failed = 0
        plan.executable.forEach { step ->
            val operation = byId[step.operationId] ?: return@forEach
            // Persisted before the filesystem is touched, exactly as the
            // forward path does it, so a crash mid-reversal is reconstructable
            // from the journal rather than guessed at from the folder.
            journal.updateOperation(operation.markExecuting(clock.nowEpochMillis()))
            if (restore(step)) {
                journal.updateOperation(reversedRecord(operation))
                restored++
            } else {
                journal.updateOperation(
                    operation.markFailed(
                        RenameFailure.ProviderRejected("the file could not be put back"),
                        clock.nowEpochMillis(),
                    ),
                )
                failed++
            }
        }

        return Outcome.success(UndoResult(batchId, plan, restored, failed))
    }

    /**
     * Marks an operation as having been undone.
     *
     * Recorded as `RECONCILED_NOT_APPLIED`: after a successful reversal the
     * file carries the name it had before the batch, which is precisely what
     * that state means. Constitution section 69 forbids rewriting history, so
     * the operation keeps its original source and destination names and only
     * its outcome changes - the record still says what RetroVault did and when.
     */
    private fun reversedRecord(operation: RenameOperation): RenameOperation = operation.copy(
        state = RenameOperationState.RECONCILED_NOT_APPLIED,
        finishedAtEpochMillis = clock.nowEpochMillis(),
        failure = null,
    )

    private suspend fun restore(step: UndoStep): Boolean {
        val intermediate = step.intermediateName
        if (intermediate != null) {
            // Case-only reversal, through a third name, because on FAT and
            // exFAT the two names are the same file.
            val staged = executor.rename(step.storageRef, intermediate)
            val stagedRef = (staged as? Outcome.Success)?.value ?: return false
            return executor.rename(stagedRef, step.originalName) is Outcome.Success
        }
        return executor.rename(step.storageRef, step.originalName) is Outcome.Success
    }

    private suspend fun planFor(batch: RenameBatch, excluded: Set<RenameOperationId>): UndoPlan {
        val candidates = RenameUndoPlanner.reversible(batch).filterNot { it.id in excluded }
        val directories = mutableMapOf<StorageRef, DirectorySnapshot>()
        val states = mutableMapOf<StorageRef, ArtifactState>()

        candidates.forEach { operation ->
            if (!directories.containsKey(operation.directoryRef)) {
                (contentSource.listNames(operation.directoryRef) as? Outcome.Success)
                    ?.let { directories[operation.directoryRef] = it.value }
            }
            if (!states.containsKey(operation.currentRef)) {
                states[operation.currentRef] = readState(operation.currentRef)
            }
        }

        val reduced = batch.copy(operations = candidates)
        return RenameUndoPlanner.plan(reduced, directories, states)
    }

    /**
     * A read that failed is recorded as unreadable rather than as absence.
     *
     * The same distinction the forward validator draws: "the folder refused to
     * answer" and "the file is gone" call for different words to the user and
     * must not be collapsed.
     */
    private suspend fun readState(ref: StorageRef): ArtifactState =
        when (val outcome = contentSource.stat(ref)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> ArtifactState(
                storageRef = ref,
                exists = false,
                filename = null,
                size = null,
                writable = false,
                readable = false,
            )
        }
}

/** One line of the history a user can read. */
data class RenameHistoryEntry(
    val batch: RenameBatch,
    /** True when at least one operation in it can still be put back. */
    val undoable: Boolean,
) {
    val restoredCount: Int
        get() = batch.operations.count { it.state == RenameOperationState.RECONCILED_NOT_APPLIED }
}

/**
 * The rename history, read back.
 *
 * Constitution section 233 requires an audit trail and section 170 requires
 * renames to be reversible; both were satisfied in storage and neither was
 * reachable. This is what a history screen reads.
 */
class ListRenameHistoryUseCase(private val journal: RenameJournalRepository) {
    suspend fun recent(limit: Int = DEFAULT_LIMIT): Outcome<List<RenameHistoryEntry>> =
        when (val found = journal.findRecentBatches(limit)) {
            is Outcome.Success -> Outcome.success(
                found.value.map { batch ->
                    RenameHistoryEntry(
                        batch = batch,
                        undoable = RenameUndoPlanner.reversible(batch).isNotEmpty(),
                    )
                },
            )

            is Outcome.Failure -> found
        }

    companion object {
        const val DEFAULT_LIMIT: Int = 50
    }
}

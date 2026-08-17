package com.retrovault.domain.rename

import com.retrovault.domain.identity.RenameOperationId
import com.retrovault.domain.identity.StorageRef

/**
 * Why one rename cannot be reversed.
 *
 * Each is a refusal to act on a file whose current state does not match what
 * the journal says was left behind. Constitution section 170 makes the journal
 * enough information to reverse a rename; it is not permission to reverse one
 * whose subject has since changed.
 */
enum class UndoRefusal {
    /** The operation never took effect, so there is nothing to reverse. */
    NEVER_APPLIED,

    /** The file is not where the journal says the rename left it. */
    RENAMED_FILE_MISSING,

    /** A file of that size is not what the rename left; the content changed since. */
    CONTENT_CHANGED,

    /** Something already occupies the name the file would go back to. */
    ORIGINAL_NAME_TAKEN,

    /** The folder could not be read, so nothing about it can be checked. */
    FOLDER_UNREADABLE,

    /** The file's current state could not be read. */
    FILE_UNREADABLE,

    /** Reversing this would need another reversal that is itself blocked. */
    DEADLOCKED,
}

/** One rename to be put back, with everything needed to check it is safe. */
data class UndoStep(
    val operationId: RenameOperationId,
    val storageRef: StorageRef,
    val directoryRef: StorageRef,
    /** The name the file carries now - the rename's destination. */
    val currentName: String,
    /** The name it is going back to - the rename's source. */
    val originalName: String,
    val expectedSize: Long,
) {
    /**
     * Reversing `game.sfc` to `Game.sfc` is a no-op on FAT and exFAT, so the
     * same staging the forward rename used applies here.
     */
    val requiresStaging: Boolean get() = RenameStaging.requiresStaging(currentName, originalName)

    val intermediateName: String? get() = if (requiresStaging) RenameStaging.nameFor(originalName) else null
}

data class UndoIssue(
    val operationId: RenameOperationId,
    val refusal: UndoRefusal,
    val message: String,
)

/**
 * The verdict on reversing a whole batch.
 *
 * All-or-nothing for the same reason the forward plan is
 * ([RenamePlanValidator]): a half-reversed batch leaves a library in a state
 * neither the user nor the journal describes. When one step is unsafe the user
 * excludes it and revalidates.
 */
data class UndoPlan(
    val steps: List<UndoStep>,
    val issues: List<UndoIssue>,
) {
    val isExecutable: Boolean get() = issues.isEmpty() && steps.isNotEmpty()

    val hasNothingToDo: Boolean get() = issues.isEmpty() && steps.isEmpty()

    /** Steps in the order they may be run. Empty unless the whole plan is safe. */
    val executable: List<UndoStep> get() = if (isExecutable) steps else emptyList()

    fun without(excluded: Set<RenameOperationId>): List<UndoStep> =
        steps.filterNot { it.operationId in excluded }
}

/**
 * Turns a completed batch back into the state that preceded it.
 *
 * Constitution section 170: the journal exists so a rename can be reversed, and
 * a journal nothing reads is only a promise. This is the reader.
 *
 * Pure, and deliberately so. Reversing a batch is the most destructive thing
 * RetroVault can be asked to do - it renames files the user may have since
 * touched - so every rule that decides whether a step is safe is testable
 * without a filesystem.
 */
object RenameUndoPlanner {
    const val VERSION: String = "rename-undo-v1"

    /**
     * Which operations of a batch can be reversed at all.
     *
     * Only ones that actually took effect. A `FAILED`, `SKIPPED` or
     * `RECONCILED_NOT_APPLIED` operation changed nothing, so reversing it would
     * rename a file that was never renamed. `RECONCILED_UNKNOWN` is excluded
     * too: RetroVault could not establish what happened, and acting on an
     * unknown is exactly what the reconciler refused to do.
     */
    fun reversible(batch: RenameBatch): List<RenameOperation> =
        batch.operations.filter { it.state.succeeded && !batch.dryRun }

    /**
     * Builds the reversal, in an order that can actually be run.
     *
     * The forward batch was ordered so no rename ran while another held its
     * name. Running that order backwards restores the same property, because
     * every name a step needs was freed by a step that came after it going
     * forward. The order is therefore reversed rather than recomputed - the
     * forward ordering was already proved acyclic, and recomputing it would
     * risk two implementations disagreeing.
     */
    fun plan(
        batch: RenameBatch,
        directories: Map<StorageRef, DirectorySnapshot>,
        states: Map<StorageRef, ArtifactState>,
    ): UndoPlan {
        val steps = reversible(batch).reversed().map { operation ->
            UndoStep(
                operationId = operation.id,
                storageRef = operation.currentRef,
                directoryRef = operation.directoryRef,
                currentName = operation.destinationName,
                originalName = operation.sourceName,
                expectedSize = operation.preconditionSize,
            )
        }
        return validate(steps, directories, states)
    }

    /**
     * Checks every step against what storage says right now.
     *
     * @param states keyed by the ref the file is *addressed* by. A provider may
     * hand back a new ref after a rename, so the journal's `sourceRef` is what
     * the caller must have used to read state.
     */
    fun validate(
        steps: List<UndoStep>,
        directories: Map<StorageRef, DirectorySnapshot>,
        states: Map<StorageRef, ArtifactState>,
    ): UndoPlan {
        // Names this reversal frees. A step whose original name is currently
        // held by another step in the same reversal is an ordering constraint,
        // not a collision - the same distinction the forward validator draws.
        val vacated = steps
            .filterNot { it.currentName.equals(it.originalName, ignoreCase = true) }
            .associateBy { key(it.directoryRef, it.currentName) }

        val issues = mutableListOf<UndoIssue>()
        steps.forEach { step ->
            val state = states[step.storageRef]
            when {
                state == null || !state.readable -> issues += UndoIssue(
                    step.operationId,
                    UndoRefusal.FILE_UNREADABLE,
                    "'${step.currentName}' could not be read, so putting it back cannot be checked as safe.",
                )

                !state.exists -> issues += UndoIssue(
                    step.operationId,
                    UndoRefusal.RENAMED_FILE_MISSING,
                    "'${step.currentName}' is no longer there, so there is nothing to put back.",
                )

                state.filename != null && state.filename != step.currentName -> issues += UndoIssue(
                    step.operationId,
                    UndoRefusal.RENAMED_FILE_MISSING,
                    "That file is now named '${state.filename}', not '${step.currentName}'. Something " +
                        "else renamed it after RetroVault did.",
                )

                state.size != null && state.size != step.expectedSize -> issues += UndoIssue(
                    step.operationId,
                    UndoRefusal.CONTENT_CHANGED,
                    "'${step.currentName}' is ${state.size} bytes but was ${step.expectedSize} bytes when " +
                        "RetroVault renamed it. Undoing would give a different file the old name.",
                )
            }

            val directory = directories[step.directoryRef]
            if (directory == null) {
                issues += UndoIssue(
                    step.operationId,
                    UndoRefusal.FOLDER_UNREADABLE,
                    "The folder holding '${step.currentName}' could not be listed.",
                )
                return@forEach
            }
            val caseOnly = step.currentName.equals(step.originalName, ignoreCase = true)
            val occupantMovesAway = vacated[key(step.directoryRef, step.originalName)]
                ?.let { it.operationId != step.operationId } == true
            if (!caseOnly && directory.containsIgnoringCase(step.originalName) && !occupantMovesAway) {
                issues += UndoIssue(
                    step.operationId,
                    UndoRefusal.ORIGINAL_NAME_TAKEN,
                    "Something else is called '${step.originalName}' now, so putting this file back " +
                        "would overwrite it.",
                )
            }
        }

        return UndoPlan(steps, issues)
    }

    private fun key(directory: StorageRef, name: String): String =
        directory.value + SEPARATOR + name.lowercase()

    private const val SEPARATOR = '\u001F'
}

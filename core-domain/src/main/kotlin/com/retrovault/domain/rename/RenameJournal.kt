package com.retrovault.domain.rename

import com.retrovault.domain.identity.HashValue
import com.retrovault.domain.identity.PlanEntryId
import com.retrovault.domain.identity.RenameBatchId
import com.retrovault.domain.identity.RenameOperationId
import com.retrovault.domain.identity.RenamePlanId
import com.retrovault.domain.identity.ScanSessionId
import com.retrovault.domain.identity.StorageRef
import com.retrovault.domain.naming.FilenameSanitizer
import com.retrovault.domain.resolution.ConfidenceLevel
import com.retrovault.domain.resolution.ResolutionState

/**
 * Lifecycle of one journalled rename.
 *
 * DATABASE.md section 11: `PLANNED -> VALIDATED -> EXECUTING -> COMPLETED`,
 * with explicit failure states. `EXECUTING` is persisted *before* the mutation,
 * which is what makes an interrupted batch reconstructable.
 */
enum class RenameOperationState {
    PLANNED,
    VALIDATED,
    EXECUTING,
    COMPLETED,
    FAILED,
    SKIPPED,

    /** Interrupted, then confirmed to have taken effect. */
    RECONCILED_COMPLETED,

    /** Interrupted, then confirmed never to have taken effect. */
    RECONCILED_NOT_APPLIED,

    /** Interrupted, and the filesystem no longer explains what happened. */
    RECONCILED_UNKNOWN,
    ;

    val isTerminal: Boolean
        get() = this in setOf(
            COMPLETED,
            FAILED,
            SKIPPED,
            RECONCILED_COMPLETED,
            RECONCILED_NOT_APPLIED,
            RECONCILED_UNKNOWN,
        )

    val succeeded: Boolean get() = this == COMPLETED || this == RECONCILED_COMPLETED
}

/** Typed rename failures (ENGINEERING_SPEC.md section 8). */
sealed interface RenameFailure {
    val code: String
    val message: String

    data object PermissionDenied : RenameFailure {
        override val code: String get() = "permission_denied"
        override val message: String get() = "The storage provider refused access to this file."
    }

    data object DestinationExists : RenameFailure {
        override val code: String get() = "destination_exists"
        override val message: String get() = "A file with the destination name already exists."
    }

    data object SourceMissing : RenameFailure {
        override val code: String get() = "source_missing"
        override val message: String get() = "The file no longer exists at its recorded location."
    }

    data class ProviderRejected(val detail: String) : RenameFailure {
        override val code: String get() = "provider_rejected"
        override val message: String get() = "The storage provider rejected the rename: $detail"
    }

    data object Cancelled : RenameFailure {
        override val code: String get() = "cancelled"
        override val message: String get() = "The batch was cancelled before this file was renamed."
    }

    data class Unexpected(val detail: String) : RenameFailure {
        override val code: String get() = "unexpected"
        override val message: String get() = "The rename failed unexpectedly: $detail"
    }

    companion object {
        fun fromCode(code: String, detail: String?): RenameFailure = when (code) {
            "permission_denied" -> PermissionDenied
            "destination_exists" -> DestinationExists
            "source_missing" -> SourceMissing
            "cancelled" -> Cancelled
            "provider_rejected" -> ProviderRejected(detail.orEmpty())
            else -> Unexpected(detail.orEmpty())
        }
    }
}

/**
 * One durable rename intent.
 *
 * Constitution section 170: the journal keeps only the metadata needed for
 * recovery and audit, never a copy of file contents.
 */
data class RenameOperation(
    val id: RenameOperationId,
    val batchId: RenameBatchId,
    val planEntryId: PlanEntryId,
    val sourceRef: StorageRef,
    val directoryRef: StorageRef,
    val sourceName: String,
    val destinationName: String,
    /**
     * A staging name used when the rename cannot be done in one step.
     *
     * `game.sfc` to `Game.sfc` is a no-op on FAT and exFAT, where the two names
     * are the same file, so the rename has to pass through a third name. It is
     * journalled because it is a filesystem state the user could otherwise be
     * left in: if the process dies between the two steps, the file exists under
     * a name nothing else in the journal mentions.
     */
    val intermediateName: String? = null,
    /** Why RetroVault believed the rename was correct, kept for audit. */
    val resolutionState: ResolutionState,
    val confidence: ConfidenceLevel,
    val identityDescription: String,
    val namingProfileVersionedId: String,
    /** Preconditions captured at validation, used to detect staleness and to reconcile. */
    val preconditionSize: Long,
    val preconditionHash: HashValue?,
    val state: RenameOperationState,
    val failure: RenameFailure? = null,
    val plannedAtEpochMillis: Long,
    val startedAtEpochMillis: Long? = null,
    val finishedAtEpochMillis: Long? = null,
) {
    fun markExecuting(atEpochMillis: Long): RenameOperation =
        copy(state = RenameOperationState.EXECUTING, startedAtEpochMillis = atEpochMillis)

    fun markCompleted(atEpochMillis: Long): RenameOperation =
        copy(state = RenameOperationState.COMPLETED, finishedAtEpochMillis = atEpochMillis, failure = null)

    fun markFailed(failure: RenameFailure, atEpochMillis: Long): RenameOperation =
        copy(state = RenameOperationState.FAILED, finishedAtEpochMillis = atEpochMillis, failure = failure)

    fun markSkipped(failure: RenameFailure, atEpochMillis: Long): RenameOperation =
        copy(state = RenameOperationState.SKIPPED, finishedAtEpochMillis = atEpochMillis, failure = failure)
}

/**
 * Staging names for renames that cannot be done in one step.
 *
 * Deterministic on purpose: the same operation always stages through the same
 * name, so a journal entry written before a crash still describes the file that
 * is on disk afterwards.
 */
object RenameStaging {
    const val SUFFIX: String = ".rvtmp"

    /** True when [source] and [destination] differ only by letter case. */
    fun requiresStaging(source: String, destination: String): Boolean =
        source != destination && source.equals(destination, ignoreCase = true)

    fun nameFor(destination: String): String =
        FilenameSanitizer.truncateStem(destination, SUFFIX) + SUFFIX
}

/** A batch of journalled operations sharing one validated plan. */
data class RenameBatch(
    val id: RenameBatchId,
    val planId: RenamePlanId,
    val sessionId: ScanSessionId,
    val namingProfileVersionedId: String,
    val policyVersion: String,
    val dryRun: Boolean,
    val createdAtEpochMillis: Long,
    val operations: List<RenameOperation>,
) {
    val completed: List<RenameOperation> get() = operations.filter { it.state.succeeded }
    val failed: List<RenameOperation> get() = operations.filter { it.state == RenameOperationState.FAILED }
    val unfinished: List<RenameOperation> get() = operations.filterNot { it.state.isTerminal }

    /**
     * Honest reporting (Constitution section 245): "100 planned, 98 completed,
     * 2 failed", never "complete" because the request was submitted.
     */
    fun summary(): BatchSummary = BatchSummary(
        planned = operations.size,
        completed = completed.size,
        failed = failed.size,
        skipped = operations.count { it.state == RenameOperationState.SKIPPED },
        unfinished = unfinished.size,
    )
}

data class BatchSummary(
    val planned: Int,
    val completed: Int,
    val failed: Int,
    val skipped: Int,
    val unfinished: Int,
) {
    val isFullySuccessful: Boolean get() = completed == planned && failed == 0 && unfinished == 0
}

/** What the filesystem shows about a source and destination pair. */
data class ReconciliationEvidence(
    val sourceExists: Boolean,
    val sourceSize: Long?,
    val destinationExists: Boolean,
    val destinationSize: Long?,
    /** Whether the operation's staging name is present on disk. */
    val intermediateExists: Boolean = false,
)

/**
 * Decides what actually happened to an interrupted operation.
 *
 * DATABASE.md section 21: the journal provides reconciliation after an
 * interrupted execution, and filesystem and database state must be recoverable
 * independently. This function is pure so the decision table is testable.
 */
object RenameReconciler {
    fun reconcile(
        operation: RenameOperation,
        evidence: ReconciliationEvidence,
        atEpochMillis: Long,
    ): RenameOperation {
        if (operation.state.isTerminal) return operation

        val destinationLooksRight =
            evidence.destinationExists && evidence.destinationSize == operation.preconditionSize
        val sourceLooksRight =
            evidence.sourceExists && evidence.sourceSize == operation.preconditionSize

        return when {
            // A file sitting under the staging name is the one interruption
            // the user cannot diagnose alone: the name appears nowhere else.
            // It is reported by name rather than guessed at.
            evidence.intermediateExists && operation.intermediateName != null -> operation.copy(
                state = RenameOperationState.RECONCILED_UNKNOWN,
                finishedAtEpochMillis = atEpochMillis,
                failure = RenameFailure.Unexpected(
                    "The rename was interrupted part-way. The file is currently named " +
                        "'${operation.intermediateName}' and should be renamed to " +
                        "'${operation.destinationName}'.",
                ),
            )

            destinationLooksRight && !evidence.sourceExists -> operation.copy(
                state = RenameOperationState.RECONCILED_COMPLETED,
                finishedAtEpochMillis = atEpochMillis,
                failure = null,
            )

            sourceLooksRight && !evidence.destinationExists -> operation.copy(
                state = RenameOperationState.RECONCILED_NOT_APPLIED,
                finishedAtEpochMillis = atEpochMillis,
                failure = RenameFailure.Cancelled,
            )

            else -> operation.copy(
                // Both present, both absent, or sizes that no longer agree.
                // RetroVault says so rather than guessing.
                state = RenameOperationState.RECONCILED_UNKNOWN,
                finishedAtEpochMillis = atEpochMillis,
                failure = RenameFailure.Unexpected(
                    "Could not determine whether the rename took effect: " +
                        "source ${present(evidence.sourceExists)}, " +
                        "destination ${present(evidence.destinationExists)}.",
                ),
            )
        }
    }

    private fun present(exists: Boolean): String = if (exists) "present" else "absent"
}

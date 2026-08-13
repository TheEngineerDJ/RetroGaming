package com.retrovault.application

import com.retrovault.domain.identity.ObservationId
import com.retrovault.domain.identity.PlanEntryId
import com.retrovault.domain.identity.RenameBatchId
import com.retrovault.domain.identity.RenameOperationId
import com.retrovault.domain.identity.RenamePlanId
import com.retrovault.domain.identity.ScanSessionId
import com.retrovault.domain.identity.StorageRef
import com.retrovault.domain.naming.NamingProfile
import com.retrovault.domain.naming.NamingProfiles
import com.retrovault.domain.policy.AutomationPolicy
import com.retrovault.domain.rename.ArtifactState
import com.retrovault.domain.rename.DirectorySnapshot
import com.retrovault.domain.rename.PlanVerdict
import com.retrovault.domain.rename.PlannedAction
import com.retrovault.domain.rename.ReconciliationEvidence
import com.retrovault.domain.rename.RenameBatch
import com.retrovault.domain.rename.RenameFailure
import com.retrovault.domain.rename.RenameOperation
import com.retrovault.domain.rename.RenameOperationState
import com.retrovault.domain.rename.RenamePlan
import com.retrovault.domain.rename.RenamePlanBuilder
import com.retrovault.domain.rename.RenamePlanEntry
import com.retrovault.domain.rename.RenamePlanValidation
import com.retrovault.domain.rename.RenamePlanValidator
import com.retrovault.domain.rename.RenameReconciler
import com.retrovault.domain.rename.RenameStaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Builds a plan from a session's resolutions (ENGINEERING_SPEC.md section 5). */
class GenerateRenamePlanUseCase(
    private val observations: ObservationRepository,
    private val clock: Clock,
    private val ids: IdGenerator,
) {
    suspend fun generate(
        sessionId: ScanSessionId,
        profile: NamingProfile = NamingProfiles.NO_INTRO_V1,
        policy: AutomationPolicy = AutomationPolicy(),
        confirmations: Set<ObservationId> = emptySet(),
    ): Outcome<RenamePlan> {
        val resolved = when (val stored = observations.findBySession(sessionId)) {
            is Outcome.Success -> stored.value
            is Outcome.Failure -> return stored
        }
        val plan = RenamePlanBuilder.build(
            id = RenamePlanId(ids.next("plan")),
            sessionId = sessionId,
            profile = profile,
            policy = policy,
            resolved = resolved.map { it.observation to it.resolution },
            confirmations = confirmations,
            createdAtEpochMillis = clock.nowEpochMillis(),
            entryIdFactory = { PlanEntryId(ids.next("entry")) },
        )
        return Outcome.success(plan)
    }
}

/**
 * Re-reads the filesystem and validates the whole batch.
 *
 * The freshly read state is what makes stale-scan detection real: the plan is
 * checked against the filesystem as it is now, not as it was during the scan
 * (Constitution section 243).
 */
class ValidateRenamePlanUseCase(
    private val contentSource: ContentSource,
    private val clock: Clock,
    private val validator: RenamePlanValidator = RenamePlanValidator(),
) {
    suspend fun validate(plan: RenamePlan): RenamePlanValidation {
        val renameEntries = plan.entries.filter { it.action == PlannedAction.RENAME }

        val states = mutableMapOf<StorageRef, ArtifactState>()
        renameEntries.forEach { entry ->
            if (!states.containsKey(entry.storageRef)) {
                states[entry.storageRef] = readState(entry)
            }
        }

        val directories = mutableMapOf<StorageRef, DirectorySnapshot>()
        renameEntries.map { it.directoryRef }.distinct().forEach { ref ->
            (contentSource.listNames(ref) as? Outcome.Success)?.let { directories[ref] = it.value }
        }

        return validator.validate(plan, directories, states, clock.nowEpochMillis())
    }

    private suspend fun readState(entry: RenamePlanEntry): ArtifactState =
        when (val outcome = contentSource.stat(entry.storageRef)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> ArtifactState(
                storageRef = entry.storageRef,
                exists = false,
                filename = null,
                size = null,
                writable = false,
                readable = false,
            )
        }
}

/** What a dry run shows (ROM_INTELLIGENCE.md section 12, UX_SPEC.md section 8). */
data class RenamePreviewRow(
    val entryId: PlanEntryId,
    val currentName: String,
    val proposedName: String?,
    val action: PlannedAction,
    val matchType: String,
    val confidence: String,
    /**
     * Whether the identity was checked against the bytes or read from the name.
     *
     * Carried separately from [confidence] because they answer different
     * questions: confidence is how sure RetroVault is, basis is what that
     * certainty rests on. A user deciding whether to accept a rename needs
     * both (Constitution section 306).
     */
    val identityBasis: String,
    val verified: Boolean,
    val identity: String?,
    val reasons: List<String>,
    val warnings: List<String>,
)

data class RenamePreview(
    val validation: RenamePlanValidation,
    val rows: List<RenamePreviewRow>,
) {
    val executable: Boolean get() = validation.verdict == PlanVerdict.EXECUTABLE
}

/**
 * Produces the preview.
 *
 * Constitution section 169: the preview is part of the trust model, not a UI
 * nicety. It performs no mutation, which is enforced by this class having no
 * access to [RenameExecutor] at all.
 */
class PreviewRenamePlanUseCase(private val validate: ValidateRenamePlanUseCase) {
    suspend fun preview(plan: RenamePlan): RenamePreview {
        val validation = validate.validate(plan)
        val rows = validation.plan.entries.map { entry ->
            RenamePreviewRow(
                entryId = entry.id,
                currentName = entry.currentName,
                proposedName = entry.proposedName,
                action = entry.action,
                matchType = entry.resolution.state.name,
                confidence = entry.resolution.confidence.name,
                identityBasis = entry.resolution.identityBasis.name,
                verified = entry.resolution.isVerified,
                identity = entry.resolution.selected?.record?.canonicalIdentityKey?.describe(),
                reasons = entry.resolution.explanation.map { it.description },
                warnings = entry.issues.map { it.message },
            )
        }
        return RenamePreview(validation, rows)
    }
}

/** The honest outcome of an execution attempt (Constitution section 245). */
data class RenameExecutionResult(
    val batch: RenameBatch,
    val refused: RetroVaultFailure? = null,
) {
    val summary get() = batch.summary()
}

/**
 * Executes a validated plan.
 *
 * The order is fixed and is the safety property of this class:
 *
 * 1. validate the whole batch again;
 * 2. refuse entirely if anything blocks;
 * 3. persist every operation as PLANNED then VALIDATED;
 * 4. for each file, persist EXECUTING *before* touching the filesystem;
 * 5. rename;
 * 6. persist the result.
 *
 * Step 4 is what makes an interrupted batch reconstructable: a crash between
 * the write and the rename leaves a journal entry that reconciliation can
 * resolve against the filesystem (DATABASE.md section 21).
 */
class ExecuteRenamePlanUseCase(
    private val validate: ValidateRenamePlanUseCase,
    private val executor: RenameExecutor,
    private val journal: RenameJournalRepository,
    private val clock: Clock,
    private val ids: IdGenerator,
) {
    suspend fun execute(plan: RenamePlan, dryRun: Boolean = false): Outcome<RenameExecutionResult> {
        val validation = validate.validate(plan)
        val batchId = RenameBatchId(ids.next("batch"))
        val operations = validation.executable.map { entry -> toOperation(batchId, entry) }
        val batch = RenameBatch(
            id = batchId,
            planId = plan.id,
            sessionId = plan.sessionId,
            namingProfileVersionedId = plan.profile.versionedId,
            policyVersion = AutomationPolicy.VERSION,
            dryRun = dryRun,
            createdAtEpochMillis = clock.nowEpochMillis(),
            operations = operations,
        )

        if (validation.verdict != PlanVerdict.EXECUTABLE) {
            return Outcome.success(
                RenameExecutionResult(
                    batch = batch.copy(operations = emptyList()),
                    refused = RetroVaultFailure.PlanBlocked(validation.blockingIssues.size),
                ),
            )
        }

        if (dryRun) {
            // A dry run writes nothing at all: no filesystem mutation and no
            // journal, so it can be run freely and repeatedly.
            return Outcome.success(RenameExecutionResult(batch))
        }

        when (val created = journal.createBatch(batch.copy(operations = operations.map { it.validated() }))) {
            is Outcome.Failure -> return created
            is Outcome.Success -> Unit
        }

        val completed = mutableListOf<RenameOperation>()
        var cancelled = false
        try {
            for (operation in operations) {
                val executing = operation.markExecuting(clock.nowEpochMillis())
                journal.updateOperation(executing)

                val result = performRename(executing)
                val finished = when (result) {
                    is Outcome.Success -> executing.markCompleted(clock.nowEpochMillis())
                    is Outcome.Failure -> executing.markFailed(
                        toRenameFailure(result.failure),
                        clock.nowEpochMillis(),
                    )
                }
                journal.updateOperation(finished)
                completed += finished

                if (finished.state == RenameOperationState.FAILED) {
                    // Stop on first failure. Continuing would turn one provider
                    // problem into a half-renamed library.
                    completed += operations.drop(completed.size).map {
                        it.markSkipped(RenameFailure.Cancelled, clock.nowEpochMillis())
                    }
                    break
                }
            }
        } catch (cancellation: CancellationException) {
            cancelled = true
            throw cancellation
        } finally {
            if (cancelled) {
                withContext(NonCancellable) {
                    operations.drop(completed.size).forEach { pending ->
                        journal.updateOperation(
                            pending.markSkipped(RenameFailure.Cancelled, clock.nowEpochMillis()),
                        )
                    }
                }
            }
        }

        val remaining = operations.drop(completed.size)
        return Outcome.success(RenameExecutionResult(batch.copy(operations = completed + remaining)))
    }

    /**
     * Performs one journalled rename, staging through a temporary name when a
     * single step cannot express the change.
     *
     * A case-only rename is a no-op on FAT and exFAT, where `game.sfc` and
     * `Game.sfc` name the same file, so the provider either refuses it or
     * reports success without changing anything. Going through a third name
     * makes both steps real renames. The staging name is already in the journal
     * before this runs, so an interruption between the steps is recoverable.
     */
    private suspend fun performRename(operation: RenameOperation): Outcome<StorageRef> {
        val staging = operation.intermediateName
            ?: return executor.rename(operation.sourceRef, operation.destinationName)

        return when (val staged = executor.rename(operation.sourceRef, staging)) {
            is Outcome.Failure -> staged
            is Outcome.Success -> executor.rename(staged.value, operation.destinationName)
        }
    }

    private fun toOperation(batchId: RenameBatchId, entry: RenamePlanEntry): RenameOperation =
        RenameOperation(
            id = RenameOperationId(ids.next("operation")),
            batchId = batchId,
            planEntryId = entry.id,
            sourceRef = entry.storageRef,
            directoryRef = entry.directoryRef,
            sourceName = entry.currentName,
            destinationName = requireNotNull(entry.proposedName) {
                "Validation approved an entry with no destination name"
            },
            intermediateName = entry.proposedName
                ?.takeIf { RenameStaging.requiresStaging(entry.currentName, it) }
                ?.let(RenameStaging::nameFor),
            resolutionState = entry.resolution.state,
            confidence = entry.resolution.confidence,
            identityDescription = entry.resolution.selected
                ?.record
                ?.canonicalIdentityKey
                ?.describe()
                .orEmpty(),
            namingProfileVersionedId = entry.resolution.resolverVersion,
            preconditionSize = entry.observation.size,
            preconditionHash = entry.observation.identityBearingHashes()
                .asList()
                .lastOrNull(),
            state = RenameOperationState.PLANNED,
            plannedAtEpochMillis = clock.nowEpochMillis(),
        )

    private fun RenameOperation.validated(): RenameOperation =
        copy(state = RenameOperationState.VALIDATED)

    private fun toRenameFailure(failure: RetroVaultFailure): RenameFailure = when (failure) {
        is RetroVaultFailure.PermissionDenied -> RenameFailure.PermissionDenied
        is RetroVaultFailure.FileNotFound -> RenameFailure.SourceMissing
        is RetroVaultFailure.Cancelled -> RenameFailure.Cancelled
        is RetroVaultFailure.RenameFailed -> RenameFailure.ProviderRejected(failure.detail)
        else -> RenameFailure.Unexpected(failure.message)
    }
}

/**
 * Works out what an interrupted batch actually did.
 *
 * Run at startup. Until this has run, the journal may claim an operation is
 * executing that finished, or finished that never started, and both would
 * mislead the user.
 */
class ReconcileInterruptedRenamesUseCase(
    private val journal: RenameJournalRepository,
    private val contentSource: ContentSource,
    private val clock: Clock,
) {
    suspend fun reconcile(): Outcome<List<RenameOperation>> {
        val batches = when (val found = journal.findUnfinishedBatches()) {
            is Outcome.Success -> found.value
            is Outcome.Failure -> return found
        }

        val reconciled = mutableListOf<RenameOperation>()
        batches.forEach { batch ->
            batch.unfinished.forEach { operation ->
                val evidence = gatherEvidence(operation)
                val updated = RenameReconciler.reconcile(operation, evidence, clock.nowEpochMillis())
                journal.updateOperation(updated)
                reconciled += updated
            }
        }
        return Outcome.success(reconciled)
    }

    private suspend fun gatherEvidence(operation: RenameOperation): ReconciliationEvidence {
        val listing = contentSource.listNames(operation.directoryRef)
        val stat = contentSource.stat(operation.sourceRef)
        val names = (listing as? Outcome.Success)?.value
        val source = (stat as? Outcome.Success)?.value
        val sourceExists = source?.exists == true && source.filename == operation.sourceName
        val destinationExists = names?.containsIgnoringCase(operation.destinationName) == true
        return ReconciliationEvidence(
            sourceExists = sourceExists,
            sourceSize = source?.size?.takeIf { sourceExists },
            destinationExists = destinationExists,
            // The provider gives names, not sizes, for a directory listing. When
            // the source is gone and the destination is present, the rename is
            // the only explanation the journal has, so the recorded precondition
            // size is what the destination is checked against.
            destinationSize = if (destinationExists && !sourceExists) operation.preconditionSize else null,
            intermediateExists = operation.intermediateName
                ?.let { names?.containsIgnoringCase(it) } == true,
            // A provider that refuses to answer produces the same nulls as an
            // empty folder. Recording which one happened is what stops a
            // permission failure being read as proof that a file is gone.
            storageReadable = listing is Outcome.Success && stat is Outcome.Success,
        )
    }
}

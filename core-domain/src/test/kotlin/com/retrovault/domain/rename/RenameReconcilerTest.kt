package com.retrovault.domain.rename

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
import kotlin.test.assertTrue

/**
 * Recovery after an interrupted batch.
 *
 * DATABASE.md section 21: the journal must let RetroVault work out what
 * actually happened, and say so honestly when it cannot.
 */
class RenameReconcilerTest {

    private val size = 524_288L
    private val now = 1_700_000_200_000L

    private fun operation(state: RenameOperationState = RenameOperationState.EXECUTING) = RenameOperation(
        id = RenameOperationId("op-1"),
        batchId = RenameBatchId("batch-1"),
        planEntryId = PlanEntryId("entry-1"),
        sourceRef = StorageRef("content://tree/roms/a.sfc"),
        directoryRef = StorageRef("content://tree/roms"),
        sourceName = "a.sfc",
        destinationName = "Some Game (USA).sfc",
        resolutionState = ResolutionState.EXACT_HASH,
        confidence = ConfidenceLevel.EXACT,
        identityDescription = "some game [USA]",
        namingProfileVersionedId = "no-intro@v1",
        preconditionSize = size,
        preconditionHash = null,
        state = state,
        plannedAtEpochMillis = 1_700_000_000_000L,
    )

    @Test
    fun `destination present and source gone means it completed`() {
        val result = RenameReconciler.reconcile(
            operation(),
            ReconciliationEvidence(
                sourceExists = false,
                sourceSize = null,
                destinationExists = true,
                destinationSize = size,
            ),
            now,
        )

        assertEquals(RenameOperationState.RECONCILED_COMPLETED, result.state)
        assertEquals(null, result.failure)
        assertEquals(now, result.finishedAtEpochMillis)
    }

    @Test
    fun `source still present and destination absent means it never happened`() {
        val result = RenameReconciler.reconcile(
            operation(),
            ReconciliationEvidence(
                sourceExists = true,
                sourceSize = size,
                destinationExists = false,
                destinationSize = null,
            ),
            now,
        )

        assertEquals(RenameOperationState.RECONCILED_NOT_APPLIED, result.state)
        assertEquals(RenameFailure.Cancelled, result.failure)
    }

    @Test
    fun `both present is reported as unknown rather than guessed`() {
        val result = RenameReconciler.reconcile(
            operation(),
            ReconciliationEvidence(
                sourceExists = true,
                sourceSize = size,
                destinationExists = true,
                destinationSize = size,
            ),
            now,
        )

        assertEquals(RenameOperationState.RECONCILED_UNKNOWN, result.state)
        assertTrue(result.failure is RenameFailure.Unexpected)
    }

    @Test
    fun `neither present is reported as unknown`() {
        val result = RenameReconciler.reconcile(
            operation(),
            ReconciliationEvidence(
                sourceExists = false,
                sourceSize = null,
                destinationExists = false,
                destinationSize = null,
            ),
            now,
        )

        assertEquals(RenameOperationState.RECONCILED_UNKNOWN, result.state)
    }

    @Test
    fun `a destination of the wrong size is not accepted as success`() {
        val result = RenameReconciler.reconcile(
            operation(),
            ReconciliationEvidence(
                sourceExists = false,
                sourceSize = null,
                destinationExists = true,
                destinationSize = 999,
            ),
            now,
        )

        assertEquals(RenameOperationState.RECONCILED_UNKNOWN, result.state)
    }

    @Test
    fun `an already terminal operation is left alone`() {
        val completed = operation(RenameOperationState.COMPLETED)

        val result = RenameReconciler.reconcile(
            completed,
            ReconciliationEvidence(true, size, false, null),
            now,
        )

        assertEquals(completed, result)
    }

    @Test
    fun `batch reporting is honest about partial execution`() {
        val batch = RenameBatch(
            id = RenameBatchId("batch-1"),
            planId = RenamePlanId("plan-1"),
            sessionId = ScanSessionId("session-1"),
            namingProfileVersionedId = "no-intro@v1",
            policyVersion = "automation-policy-v1",
            dryRun = false,
            createdAtEpochMillis = 1_700_000_000_000L,
            operations = listOf(
                operation().markCompleted(now),
                operation().markFailed(RenameFailure.PermissionDenied, now),
                operation(),
            ),
        )

        val summary = batch.summary()

        assertEquals(3, summary.planned)
        assertEquals(1, summary.completed)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.unfinished)
        assertEquals(false, summary.isFullySuccessful)
    }

    @Test
    fun `failure codes round-trip through persistence`() {
        val failures = listOf(
            RenameFailure.PermissionDenied,
            RenameFailure.DestinationExists,
            RenameFailure.SourceMissing,
            RenameFailure.Cancelled,
            RenameFailure.ProviderRejected("provider said no"),
        )
        failures.forEach { failure ->
            val detail = (failure as? RenameFailure.ProviderRejected)?.detail
            assertEquals(failure, RenameFailure.fromCode(failure.code, detail))
        }
    }
}

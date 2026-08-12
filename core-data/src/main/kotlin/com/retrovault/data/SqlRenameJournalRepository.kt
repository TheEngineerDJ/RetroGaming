package com.retrovault.data

import com.retrovault.application.Outcome
import com.retrovault.application.RenameJournalRepository
import com.retrovault.application.RetroVaultFailure
import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.HashValue
import com.retrovault.domain.identity.PlanEntryId
import com.retrovault.domain.identity.RenameBatchId
import com.retrovault.domain.identity.RenameOperationId
import com.retrovault.domain.identity.RenamePlanId
import com.retrovault.domain.identity.ScanSessionId
import com.retrovault.domain.identity.StorageRef
import com.retrovault.domain.rename.RenameBatch
import com.retrovault.domain.rename.RenameFailure
import com.retrovault.domain.rename.RenameOperation
import com.retrovault.domain.rename.RenameOperationState
import com.retrovault.domain.resolution.ConfidenceLevel
import com.retrovault.domain.resolution.ResolutionState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The durable rename journal.
 *
 * Constitution section 170: only the metadata needed for recovery and audit is
 * retained - never a copy of file contents. DATABASE.md section 21: this is
 * what allows filesystem state and database state to be reconciled
 * independently after an interruption.
 */
class SqlRenameJournalRepository(
    private val database: SqlDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RenameJournalRepository {

    override suspend fun createBatch(batch: RenameBatch): Outcome<Unit> = write {
        database.transaction {
            database.execute(
                "INSERT OR REPLACE INTO rename_batch (id, plan_id, session_id, naming_profile, " +
                    "policy_version, dry_run, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                listOf(
                    batch.id.value,
                    batch.planId.value,
                    batch.sessionId.value,
                    batch.namingProfileVersionedId,
                    batch.policyVersion,
                    if (batch.dryRun) 1L else 0L,
                    batch.createdAtEpochMillis,
                ),
            )
            batch.operations.forEach(::upsertOperation)
        }
    }

    override suspend fun updateOperation(operation: RenameOperation): Outcome<Unit> = write {
        upsertOperation(operation)
    }

    override suspend fun findBatch(id: RenameBatchId): Outcome<RenameBatch> = withContext(dispatcher) {
        try {
            val batch = loadBatch(id)
            batch?.let { Outcome.success(it) }
                ?: Outcome.failure(RetroVaultFailure.PersistenceFailure("No rename batch ${id.value}"))
        } catch (failure: SqlFailure) {
            Outcome.failure(RetroVaultFailure.PersistenceFailure(failure.message ?: "SQL failure"))
        }
    }

    override suspend fun findUnfinishedBatches(): Outcome<List<RenameBatch>> = withContext(dispatcher) {
        try {
            val ids = database.query(
                "SELECT DISTINCT batch_id FROM rename_operation WHERE state IN (?, ?, ?)",
                listOf(
                    RenameOperationState.PLANNED.name,
                    RenameOperationState.VALIDATED.name,
                    RenameOperationState.EXECUTING.name,
                ),
            ) { row -> RenameBatchId(row.getString(0)) }
            Outcome.success(ids.mapNotNull { loadBatch(it) })
        } catch (failure: SqlFailure) {
            Outcome.failure(RetroVaultFailure.PersistenceFailure(failure.message ?: "SQL failure"))
        }
    }

    private fun upsertOperation(operation: RenameOperation) {
        database.execute(
            "INSERT OR REPLACE INTO rename_operation (id, batch_id, plan_entry_id, source_ref, " +
                "directory_ref, source_name, destination_name, intermediate_name, resolution_state, confidence, " +
                "identity_description, naming_profile, precondition_size, precondition_hash_algorithm, " +
                "precondition_hash_digest, state, failure_code, failure_detail, planned_at, started_at, " +
                "finished_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(
                operation.id.value,
                operation.batchId.value,
                operation.planEntryId.value,
                operation.sourceRef.value,
                operation.directoryRef.value,
                operation.sourceName,
                operation.destinationName,
                operation.intermediateName,
                operation.resolutionState.name,
                operation.confidence.name,
                operation.identityDescription,
                operation.namingProfileVersionedId,
                operation.preconditionSize,
                operation.preconditionHash?.algorithm?.name,
                operation.preconditionHash?.hex,
                operation.state.name,
                operation.failure?.code,
                (operation.failure as? RenameFailure.ProviderRejected)?.detail
                    ?: (operation.failure as? RenameFailure.Unexpected)?.detail,
                operation.plannedAtEpochMillis,
                operation.startedAtEpochMillis,
                operation.finishedAtEpochMillis,
            ),
        )
    }

    private fun loadBatch(id: RenameBatchId): RenameBatch? {
        val header = database.queryOne(
            "SELECT id, plan_id, session_id, naming_profile, policy_version, dry_run, created_at " +
                "FROM rename_batch WHERE id = ?",
            listOf(id.value),
        ) { row ->
            RenameBatch(
                id = RenameBatchId(row.getString(0)),
                planId = RenamePlanId(row.getString(1)),
                sessionId = ScanSessionId(row.getString(2)),
                namingProfileVersionedId = row.getString(3),
                policyVersion = row.getString(4),
                dryRun = row.getBoolean(5),
                createdAtEpochMillis = row.getLong(6),
                operations = emptyList(),
            )
        } ?: return null

        val operations = database.query(
            "SELECT id, batch_id, plan_entry_id, source_ref, directory_ref, source_name, " +
                "destination_name, intermediate_name, resolution_state, confidence, identity_description, " +
                "naming_profile, " +
                "precondition_size, precondition_hash_algorithm, precondition_hash_digest, state, " +
                "failure_code, failure_detail, planned_at, started_at, finished_at " +
                "FROM rename_operation WHERE batch_id = ? ORDER BY planned_at, id",
            listOf(id.value),
        ) { row -> row.toOperation() }

        return header.copy(operations = operations)
    }

    private fun SqlRow.toOperation(): RenameOperation {
        val algorithm = getStringOrNull(13)?.let { name ->
            runCatching { HashAlgorithm.valueOf(name) }.getOrNull()
        }
        val digest = getStringOrNull(14)
        return RenameOperation(
            id = RenameOperationId(getString(0)),
            batchId = RenameBatchId(getString(1)),
            planEntryId = PlanEntryId(getString(2)),
            sourceRef = StorageRef(getString(3)),
            directoryRef = StorageRef(getString(4)),
            sourceName = getString(5),
            destinationName = getString(6),
            intermediateName = getStringOrNull(7),
            resolutionState = runCatching { ResolutionState.valueOf(getString(8)) }
                .getOrDefault(ResolutionState.NO_MATCH),
            confidence = runCatching { ConfidenceLevel.valueOf(getString(9)) }
                .getOrDefault(ConfidenceLevel.UNKNOWN),
            identityDescription = getString(10),
            namingProfileVersionedId = getString(11),
            preconditionSize = getLong(12),
            preconditionHash = if (algorithm != null && digest != null) {
                HashValue.parse(algorithm, digest)
            } else {
                null
            },
            state = runCatching { RenameOperationState.valueOf(getString(15)) }
                .getOrDefault(RenameOperationState.RECONCILED_UNKNOWN),
            failure = getStringOrNull(16)?.let { code -> RenameFailure.fromCode(code, getStringOrNull(17)) },
            plannedAtEpochMillis = getLong(18),
            startedAtEpochMillis = getLongOrNull(19),
            finishedAtEpochMillis = getLongOrNull(20),
        )
    }

    private suspend fun write(body: () -> Unit): Outcome<Unit> = withContext(dispatcher) {
        try {
            Outcome.success(body())
        } catch (failure: SqlFailure) {
            Outcome.failure(RetroVaultFailure.PersistenceFailure(failure.message ?: "SQL failure"))
        }
    }
}

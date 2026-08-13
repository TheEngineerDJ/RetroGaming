package com.retrovault.data

import com.retrovault.application.CorrectionStore
import com.retrovault.application.Outcome
import com.retrovault.application.RetroVaultFailure
import com.retrovault.domain.correction.CorrectedIdentity
import com.retrovault.domain.correction.CorrectionScope
import com.retrovault.domain.correction.CorrectionSet
import com.retrovault.domain.correction.CorrectionState
import com.retrovault.domain.correction.IdentityCorrection
import com.retrovault.domain.identity.CorrectionId
import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.ReleaseId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Durable user corrections over SQLite.
 *
 * Append-only. Constitution section 69 requires a correction to preserve the
 * previous claim and forbids silently rewriting history; section 70 requires
 * earlier knowledge to stay reconstructable. So superseding a correction
 * inserts a row and marks the old one - the question "what did I say before,
 * and why" stays answerable forever.
 *
 * Nothing here deletes. Withdrawing marks a row withdrawn, which is a different
 * fact from never having corrected at all.
 */
class SqlCorrectionStore(
    private val database: SqlDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CorrectionStore {

    override suspend fun record(correction: IdentityCorrection): Outcome<IdentityCorrection> = write {
        database.transaction {
            // Supersede rather than replace. The previous assertion is part of
            // the record of how this user's knowledge changed.
            database.execute(
                "UPDATE identity_correction SET state = ?, superseded_by = ? " +
                    "WHERE scope_algorithm = ? AND scope_digest = ? AND state = ?",
                listOf(
                    CorrectionState.SUPERSEDED.name,
                    correction.id.value,
                    correction.scope.algorithm.name,
                    correction.scope.digest,
                    CorrectionState.ACTIVE.name,
                ),
            )
            insert(correction)
        }
        correction
    }

    override suspend fun withdraw(scope: CorrectionScope): Outcome<Unit> = write {
        database.execute(
            "UPDATE identity_correction SET state = ?, superseded_by = NULL " +
                "WHERE scope_algorithm = ? AND scope_digest = ? AND state = ?",
            listOf(
                CorrectionState.WITHDRAWN.name,
                scope.algorithm.name,
                scope.digest,
                CorrectionState.ACTIVE.name,
            ),
        )
    }

    override suspend fun history(scope: CorrectionScope): Outcome<List<IdentityCorrection>> =
        withContext(dispatcher) {
            try {
                Outcome.success(
                    database.query(
                        SELECT_COLUMNS +
                            "FROM identity_correction WHERE scope_algorithm = ? AND scope_digest = ? " +
                            "ORDER BY recorded_at DESC, id DESC",
                        listOf(scope.algorithm.name, scope.digest),
                    ) { row -> row.toCorrection() }.filterNotNull(),
                )
            } catch (failure: SqlFailure) {
                Outcome.failure(RetroVaultFailure.PersistenceFailure(failure.message ?: "SQL failure"))
            }
        }

    override suspend fun active(): CorrectionSet = withContext(dispatcher) {
        CorrectionSet(
            database.query(
                SELECT_COLUMNS + "FROM identity_correction WHERE state = ? ORDER BY recorded_at, id",
                listOf(CorrectionState.ACTIVE.name),
            ) { row -> row.toCorrection() }.filterNotNull(),
        )
    }

    private fun insert(correction: IdentityCorrection) {
        val release = (correction.corrected as? CorrectedIdentity.IsRelease)?.releaseId?.value
        database.execute(
            "INSERT INTO identity_correction (id, scope_algorithm, scope_digest, scope_size, " +
                "previous_identity, corrected_kind, corrected_release_id, reason, recorded_at, " +
                "state, superseded_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(
                correction.id.value,
                correction.scope.algorithm.name,
                correction.scope.digest,
                correction.scope.size,
                correction.previousIdentityDescription,
                if (release == null) KIND_NOT_THIS else KIND_IS_RELEASE,
                release,
                correction.reason,
                correction.recordedAtEpochMillis,
                correction.state.name,
                correction.supersededBy?.value,
            ),
        )
    }

    /**
     * @return `null` for a row this build cannot read.
     *
     * A correction naming a kind of identity a later version introduced is
     * skipped rather than allowed to fail the whole query. Losing one row from a
     * listing is recoverable; refusing to scan because of it is not.
     */
    private fun SqlRow.toCorrection(): IdentityCorrection? {
        val algorithm = runCatching { HashAlgorithm.valueOf(getString(1)) }.getOrNull() ?: return null
        val state = runCatching { CorrectionState.valueOf(getString(9)) }.getOrNull() ?: return null
        val corrected = when (getString(5)) {
            KIND_IS_RELEASE -> getStringOrNull(6)?.let { CorrectedIdentity.IsRelease(ReleaseId(it)) }
            KIND_NOT_THIS -> CorrectedIdentity.NotThis
            else -> null
        } ?: return null

        return runCatching {
            IdentityCorrection(
                id = CorrectionId(getString(0)),
                scope = CorrectionScope(algorithm, getString(2), getLongOrNull(3)),
                previousIdentityDescription = getStringOrNull(4),
                corrected = corrected,
                reason = getStringOrNull(7),
                recordedAtEpochMillis = getLong(8),
                state = state,
                supersededBy = getStringOrNull(10)?.let(::CorrectionId),
            )
        }.getOrNull()
    }

    private suspend fun <T> write(body: () -> T): Outcome<T> = withContext(dispatcher) {
        try {
            Outcome.success(body())
        } catch (failure: SqlFailure) {
            Outcome.failure(RetroVaultFailure.PersistenceFailure(failure.message ?: "SQL failure"))
        }
    }

    private companion object {
        const val KIND_IS_RELEASE = "IS_RELEASE"
        const val KIND_NOT_THIS = "NOT_THIS"

        const val SELECT_COLUMNS =
            "SELECT id, scope_algorithm, scope_digest, scope_size, previous_identity, corrected_kind, " +
                "corrected_release_id, reason, recorded_at, state, superseded_by "
    }
}

package com.retrovault.application

import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.StorageRef

/**
 * Expected failures, as types.
 *
 * ENGINEERING_SPEC.md section 8: expected failures are typed and unexpected
 * exceptions are not swallowed. Constitution section 250 goes further -
 * failure is itself data, and becomes a structured state rather than a generic
 * exception shown to a user.
 */
sealed interface RetroVaultFailure {
    /** Wording suitable for a user, explaining what failed and what is still safe. */
    val message: String

    /** Stable label for persistence and diagnostics. */
    val code: String

    data class PermissionDenied(val ref: StorageRef?) : RetroVaultFailure {
        override val code: String get() = "permission_denied"
        override val message: String
            get() = "RetroVault does not have permission to read this location. Nothing was changed."
    }

    data class UnsupportedStorage(val detail: String) : RetroVaultFailure {
        override val code: String get() = "unsupported_storage"
        override val message: String get() = "This storage location is not supported: $detail"
    }

    data class FileNotFound(val ref: StorageRef) : RetroVaultFailure {
        override val code: String get() = "file_not_found"
        override val message: String get() = "The file is no longer at its recorded location."
    }

    data class InvalidDat(val detail: String, val characterOffset: Long? = null) : RetroVaultFailure {
        override val code: String get() = "invalid_dat"
        override val message: String
            get() = "The DAT file could not be read completely: $detail" +
                (characterOffset?.let { " (at character $it)" } ?: "")
    }

    data class HashReadFailure(
        val ref: StorageRef,
        val algorithms: Set<HashAlgorithm>,
        val detail: String,
    ) : RetroVaultFailure {
        override val code: String get() = "hash_read_failure"
        override val message: String
            get() = "This file could not be read for identification: $detail. Other files are unaffected."
    }

    data class ArchiveUnreadable(val ref: StorageRef, val detail: String) : RetroVaultFailure {
        override val code: String get() = "archive_unreadable"
        override val message: String get() = "This archive could not be inspected: $detail"
    }

    data class RenameFailed(val ref: StorageRef, val detail: String) : RetroVaultFailure {
        override val code: String get() = "rename_failed"
        override val message: String get() = "The rename did not take effect: $detail"
    }

    data class PlanBlocked(val issueCount: Int) : RetroVaultFailure {
        override val code: String get() = "plan_blocked"
        override val message: String
            get() = "$issueCount problem(s) must be resolved before anything can be renamed. " +
                "No files have been changed."
    }

    /**
     * A correction could not be recorded.
     *
     * Typed rather than generic because the only refusal today is one the user
     * can act on: hash the file first (ENGINEERING_SPEC.md section 8).
     */
    data class CorrectionRefused(
        val refusal: CorrectionRefusal,
        override val message: String,
    ) : RetroVaultFailure {
        override val code: String get() = "correction_refused_${refusal.name.lowercase()}"
    }

    data class PersistenceFailure(val detail: String) : RetroVaultFailure {
        override val code: String get() = "persistence_failure"
        override val message: String get() = "Local data could not be saved: $detail"
    }

    data object Cancelled : RetroVaultFailure {
        override val code: String get() = "cancelled"
        override val message: String get() = "The operation was cancelled. Nothing was left half-done."
    }
}

/** A result that either succeeded or failed for a stated, typed reason. */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>

    data class Failure(val failure: RetroVaultFailure) : Outcome<Nothing>

    fun valueOrNull(): T? = (this as? Success)?.value

    companion object {
        fun <T> success(value: T): Outcome<T> = Success(value)

        fun failure(failure: RetroVaultFailure): Outcome<Nothing> = Failure(failure)
    }
}

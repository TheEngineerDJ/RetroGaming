package com.retrovault.platform.android

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.retrovault.application.Outcome
import com.retrovault.application.RetroVaultFailure
import com.retrovault.domain.identity.StorageRef

/**
 * Holding on to the folder the user picked.
 *
 * A grant returned by the folder picker lasts only as long as the process
 * unless it is explicitly persisted. RetroVault needs it to outlive the
 * process: a rename batch interrupted by the system is reconciled at the *next*
 * launch (DATABASE.md section 21), and reconciliation has to be able to read
 * the same folder. Without a persisted grant, an interrupted batch could never
 * be settled - which is the one thing the journal exists to guarantee.
 *
 * Every method reports what happened. A silently swallowed failure here is the
 * worst case of all: the app keeps working for the rest of the session and then
 * cannot explain, tomorrow, why the folder it was told about is unreadable.
 */
class SafPermissions(context: Context) {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    /**
     * Persists read and write access to a picked folder tree.
     *
     * Write access is taken because renaming is the mutation this app performs.
     * The system only offers a persistable grant for the exact URI the picker
     * returned, so this must be given the tree URI rather than a document URI
     * derived from it.
     */
    fun persistTree(treeUri: Uri): Outcome<Unit> = persist(
        treeUri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )

    /**
     * Whether a persisted grant covering [ref] is currently held.
     *
     * Compared by prefix because the scan addresses documents *inside* the tree
     * the grant was taken on, and those URIs are not equal to it. Providers can
     * revoke a grant at any time - an SD card is unmounted, the provider's app
     * is cleared - so this answers "can RetroVault still read what it was told
     * about", which is a different question from whether it once could.
     */
    fun holdsPersistedAccess(ref: StorageRef): Boolean {
        val target = ref.value
        return resolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && target.startsWith(permission.uri.toString())
        }
    }

    private fun persist(uri: Uri, flags: Int): Outcome<Unit> = try {
        resolver.takePersistableUriPermission(uri, flags)
        Outcome.success(Unit)
    } catch (failure: SecurityException) {
        // The grant was not offered as persistable. The picker's intent has to
        // ask for that, so this means the caller used a picker that cannot
        // produce a lasting grant - a defect in wiring, not in the user's
        // choice, and it must not pass unreported.
        Outcome.failure(
            RetroVaultFailure.PermissionDenied(StorageRef(uri.toString())),
        )
    } catch (failure: IllegalArgumentException) {
        Outcome.failure(
            RetroVaultFailure.UnsupportedStorage(
                failure.message ?: "this storage provider cannot grant lasting access",
            ),
        )
    }
}

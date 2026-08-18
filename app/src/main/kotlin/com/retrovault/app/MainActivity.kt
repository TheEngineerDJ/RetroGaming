package com.retrovault.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.retrovault.app.ui.RetroVaultApp
import com.retrovault.app.ui.theme.RetroVaultTheme
import com.retrovault.application.Outcome
import com.retrovault.domain.identity.StorageRef

class MainActivity : ComponentActivity() {

    private val container by lazy { RetroVaultContainer(this) }

    private val viewModel: ScannerViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ScannerViewModel(container) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RetroVaultTheme {
                RetroVaultApp(
                    viewModel = viewModel,
                    onPersistFolderPermission = ::persistFolderPermission,
                )
            }
        }
    }

    /**
     * Keeps access to the chosen folder across restarts.
     *
     * Without this the grant lasts only for the process, and a rename batch
     * interrupted today could not be reconciled tomorrow. A failure is reported
     * rather than swallowed: the app would otherwise work for the rest of the
     * session and then be inexplicably unable to read the folder it was told
     * about.
     *
     * @return true when access was persisted, so the caller can decline to
     * proceed with a folder RetroVault cannot keep.
     */
    private fun persistFolderPermission(uri: Uri): Boolean {
        when (val outcome = container.permissions.persistTree(uri)) {
            is Outcome.Success -> Unit
            is Outcome.Failure -> {
                viewModel.reportStorageProblem(
                    "RetroVault could not keep lasting access to that folder: " +
                        "${outcome.failure.message} Pick it again, or choose a different folder.",
                )
                return false
            }
        }
        // Taking the grant can succeed while the system quietly declines to
        // record it - the per-app table of persisted grants is finite. Reading
        // it back is the only way to know the grant will still be there after a
        // restart, which is the whole reason for taking it.
        if (!container.permissions.holdsPersistedAccess(StorageRef(uri.toString()))) {
            viewModel.reportStorageProblem(
                "Android did not keep RetroVault's access to that folder. Scanning will work now, " +
                    "but an interrupted rename may not be recoverable after the app closes.",
            )
        }
        return true
    }
}

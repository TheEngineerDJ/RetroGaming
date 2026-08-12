package com.retrovault.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.material3.MaterialTheme
import com.retrovault.app.ui.RetroVaultApp

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
            MaterialTheme {
                RetroVaultApp(
                    viewModel = viewModel,
                    onPersistFolderPermission = ::persistFolderPermission,
                    onPersistDocumentPermission = ::persistDocumentPermission,
                )
            }
        }
    }

    /**
     * Keeps access to the chosen folder across restarts.
     *
     * Without this the grant lasts only for the process, and a scan started
     * yesterday could not be reconciled today.
     */
    private fun persistFolderPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    private fun persistDocumentPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

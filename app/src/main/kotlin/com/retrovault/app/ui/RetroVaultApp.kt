package com.retrovault.app.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retrovault.app.Destination
import com.retrovault.app.ScannerViewModel
import com.retrovault.domain.identity.StorageRef

/**
 * RetroVault, as a product rather than a workflow.
 *
 * The library leads. Constitution section 137 asks the first release to prove
 * RetroVault can represent entities, connect them, search them and explain
 * evidence - so what a user opens into is what it knows, not the machinery
 * that produced it. Setup stays one tap away and stops being the first thing
 * they see the moment it is done.
 *
 * Four destinations and no deeper stack than a detail view. A file is reviewed
 * over the top of wherever the user was, because reviewing is a decision about
 * one file rather than a place to be.
 */
@Composable
fun RetroVaultApp(
    viewModel: ScannerViewModel,
    onPersistFolderPermission: (Uri) -> Boolean,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        // Access is persisted before the folder is accepted. A folder
        // RetroVault cannot keep access to would scan once and then leave any
        // interrupted rename unrecoverable, so it is not adopted at all.
        if (uri != null && onPersistFolderPermission(uri)) {
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                uri,
                DocumentsContract.getTreeDocumentId(uri),
            )
            viewModel.onFolderSelected(
                StorageRef(documentUri.toString()),
                uri.lastPathSegment ?: "Selected folder",
            )
        }
    }

    // No persistable grant is taken for the DAT. `ACTION_OPEN_DOCUMENT` does
    // not offer one, so asking would always fail, and it is not needed: the DAT
    // is read once into the local catalogue at import and never opened again.
    val datPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.onDatSelected(
                StorageRef(uri.toString()),
                uri.lastPathSegment?.substringAfterLast('/') ?: "DAT file",
            )
        }
    }

    val review = state.review

    Scaffold(
        bottomBar = {
            // Hidden while reviewing: the sheet is a decision about one file
            // and moving away mid-decision loses it.
            if (review == null) {
                RetroVaultNavigationBar(
                    current = state.destination,
                    attentionCount = state.needingAttention,
                    onSelect = viewModel::navigate,
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (review != null) {
                ReviewSheet(
                    subject = review,
                    busy = state.reviewBusy,
                    onCorrectTo = viewModel::correctTo,
                    onReject = viewModel::rejectIdentity,
                    onWithdraw = viewModel::withdrawCorrection,
                    onClose = viewModel::closeReview,
                )
                return@Box
            }

            when (state.destination) {
                Destination.LIBRARY -> LibraryScreen(
                    state = state,
                    onQueryChange = viewModel::onLibrarySearchChange,
                    onOpenWork = viewModel::openWork,
                    onCloseWork = viewModel::closeWork,
                    onShowFiles = viewModel::showFiles,
                    onGoToSetup = { viewModel.navigate(Destination.SETUP) },
                )

                Destination.FILES -> FilesScreen(
                    state = state,
                    onFilter = viewModel::filterBy,
                    onReview = viewModel::openReview,
                    onToggleConfirm = viewModel::toggleConfirmation,
                    onBuildPreview = viewModel::buildPreview,
                    onDryRun = { viewModel.executeRenames(dryRun = true) },
                    onExecute = { viewModel.executeRenames(dryRun = false) },
                    onScan = viewModel::startScan,
                    onCancel = viewModel::cancelScan,
                )

                Destination.ACTIVITY -> ActivityScreen(
                    history = state.history,
                    notices = state.notices,
                    onUndo = viewModel::undoBatch,
                )

                Destination.SETUP -> SetupScreen(
                    state = state,
                    onPickFolder = { folderPicker.launch(null) },
                    onPickDat = { datPicker.launch(arrayOf("*/*")) },
                    onScan = viewModel::startScan,
                    onCancel = viewModel::cancelScan,
                )
            }
        }
    }
}

@Composable
private fun RetroVaultNavigationBar(
    current: Destination,
    attentionCount: Int,
    onSelect: (Destination) -> Unit,
) {
    NavigationBar {
        Destination.entries.forEach { destination ->
            NavigationBarItem(
                selected = destination == current,
                onClick = { onSelect(destination) },
                icon = { Icon(destination.icon, contentDescription = null) },
                // The count rides in the label rather than in a badge. A badge
                // is a number with no name attached; the label already names
                // the destination, so putting the count there means a screen
                // reader announces "Files, 3 need attention" instead of "3".
                label = {
                    Text(
                        if (destination == Destination.FILES && attentionCount > 0) {
                            "Files ($attentionCount)"
                        } else {
                            destination.title
                        },
                    )
                },
            )
        }
    }
}

private val Destination.title: String
    get() = when (this) {
        Destination.LIBRARY -> "Library"
        Destination.FILES -> "Files"
        Destination.ACTIVITY -> "Activity"
        Destination.SETUP -> "Setup"
    }

private val Destination.icon: ImageVector
    get() = when (this) {
        Destination.LIBRARY -> Icons.Filled.Home
        Destination.FILES -> Icons.Filled.List
        Destination.ACTIVITY -> Icons.Filled.CheckCircle
        Destination.SETUP -> Icons.Filled.Settings
    }

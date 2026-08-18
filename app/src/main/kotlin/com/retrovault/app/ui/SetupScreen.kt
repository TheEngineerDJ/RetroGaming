package com.retrovault.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.retrovault.app.ScannerUiState
import com.retrovault.app.WorkflowPhase
import com.retrovault.app.ui.theme.StatusTint

/**
 * Where the folder and the datasets are chosen.
 *
 * Secondary by design. It leads on first run through the card on the library
 * screen and afterwards is somewhere a user visits deliberately, because
 * configuration that stays in the way is what makes an app feel like a tool
 * for its own setup.
 *
 * The privacy statements are not decoration. Constitution section 161 and
 * section 165: RetroVault reads only the folder the user picks and identifies
 * offline, and a user cannot take that on trust unless they are told.
 */
@Composable
fun SetupScreen(
    state: ScannerUiState,
    onPickFolder: () -> Unit,
    onPickDat: () -> Unit,
    onScan: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Setup", style = MaterialTheme.typography.headlineMedium)

        // The most recent thing RetroVault said, where the action that caused
        // it was taken. The full list lives on Activity; a user importing a DAT
        // should not have to go looking to find out whether it worked.
        state.notices.lastOrNull()?.let { latest ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = latest,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Where your games are", style = MaterialTheme.typography.titleMedium)
                Text(
                    state.rootDisplayName?.let { "Folder: $it" }
                        ?: "No folder chosen. RetroVault reads only the folder you pick.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.rootDisplayName == null) "Choose folder" else "Choose a different folder")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Reference data", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.importedDatSets.isEmpty()) {
                        "No dataset imported. Identification works offline, against the DAT files you " +
                            "import — RetroVault never sends your library anywhere."
                    } else {
                        "Imported: ${state.importedDatSets.joinToString(", ")}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onPickDat, modifier = Modifier.fillMaxWidth()) {
                    Text("Import a DAT file")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Scan", style = MaterialTheme.typography.titleMedium)
                if (state.phase == WorkflowPhase.SCANNING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(state.currentActivity, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text("Stop scanning")
                    }
                } else {
                    Text(
                        if (state.isSetUp) {
                            "Scanning reads every file in your folder and identifies what it can. " +
                                "Nothing is renamed until you ask."
                        } else {
                            "Choose a folder and import a dataset first."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onScan, enabled = state.canScan, modifier = Modifier.fillMaxWidth()) {
                        Text("Start scan")
                    }
                }
                if (state.summary.hashingSkippedBySizeFilter > 0) {
                    Text(
                        "${state.summary.hashingSkippedBySizeFilter} file(s) had a size no catalogue " +
                            "record matches, so they were identified by name only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusTint.settled,
                    )
                }
            }
        }
    }
}

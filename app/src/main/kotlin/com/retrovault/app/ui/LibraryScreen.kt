package com.retrovault.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.retrovault.app.ScannerUiState
import com.retrovault.app.ui.theme.StatusTint
import com.retrovault.application.LibraryStatus
import com.retrovault.application.MatchKind
import com.retrovault.application.WorkDetail
import com.retrovault.application.WorkSummary
import com.retrovault.domain.entity.EntityProvenance
import com.retrovault.domain.identity.WorkId

/**
 * What RetroVault knows, as the first thing a user sees.
 *
 * The screen answers four questions in order: is anything set up, what needs
 * attention, what is in the library, and what does one title actually consist
 * of. Constitution section 137 asks the first release to prove exactly that
 * chain, and section 277 forbids hiding the trust state anywhere along it - so
 * a title says whether a person established it or automation proposed it, and a
 * search result says how it matched.
 */
@Composable
fun LibraryScreen(
    state: ScannerUiState,
    onQueryChange: (String) -> Unit,
    onOpenWork: (WorkId) -> Unit,
    onCloseWork: () -> Unit,
    onShowFiles: (LibraryStatus?) -> Unit,
    onGoToSetup: () -> Unit,
) {
    val detail = state.openWork
    if (detail != null) {
        WorkDetailScreen(detail = detail, onBack = onCloseWork)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Your library", style = MaterialTheme.typography.headlineMedium)
        }

        if (!state.isSetUp) {
            item { GettingStartedCard(state = state, onGoToSetup = onGoToSetup) }
        }

        if (state.hasScanned) {
            item { AttentionCard(state = state, onShowFiles = onShowFiles) }
        }

        item {
            OutlinedTextField(
                value = state.librarySearch,
                onValueChange = onQueryChange,
                label = { Text("Search your titles") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (state.libraryWorks.isEmpty()) {
            item {
                // An empty library is a statement about what has been scanned,
                // never about what exists (section 169 in miniature).
                Text(
                    text = when {
                        state.librarySearch.isNotBlank() ->
                            "No title in your library matches that. A file can be on disk and " +
                                "unidentified, which means it is not here yet."

                        state.isSetUp ->
                            "Nothing identified yet. Scanning your folder adds every title " +
                                "RetroVault can match to your datasets."

                        else -> "Once you choose a folder and import a dataset, what RetroVault " +
                            "identifies will appear here."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        items(state.libraryWorks, key = { it.id.value }) { work ->
            WorkCard(work = work, onOpen = { onOpenWork(work.id) })
        }
    }
}

/**
 * The first-run path, and only the first-run path.
 *
 * Disappears the moment a folder and a dataset both exist, because setup that
 * stays prominent after it is finished is what makes an app feel like a tool
 * for its own configuration.
 */
@Composable
private fun GettingStartedCard(state: ScannerUiState, onGoToSetup: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Get started", style = MaterialTheme.typography.titleMedium)
            Text(
                "RetroVault identifies your games by their contents, offline, against datasets " +
                    "you import. It needs two things before it can start.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (state.rootDisplayName != null) {
                    "✓ Folder: ${state.rootDisplayName}"
                } else {
                    "1. Choose the folder your games are in"
                },
            )
            Text(
                text = if (state.importedDatSets.isNotEmpty()) {
                    "✓ Datasets: ${state.importedDatSets.joinToString(", ")}"
                } else {
                    "2. Import a DAT so there is something to match against"
                },
            )
            Button(onClick = onGoToSetup) { Text("Set up RetroVault") }
        }
    }
}

/**
 * What the last scan found, as piles the user can act on.
 *
 * Every pile is offered, including the ones nothing can be done about.
 * Constitution section 277: the interface must never hide the trust state, and
 * a summary that showed only the good news would be doing exactly that.
 */
@Composable
private fun AttentionCard(state: ScannerUiState, onShowFiles: (LibraryStatus?) -> Unit) {
    val counts = state.statusCounts
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Your last scan", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (counts.needingAttention > 0) {
                    "${counts.needingAttention} of ${counts.total} file(s) need you to look at them."
                } else {
                    "${counts.total} file(s) scanned. Nothing is waiting on you."
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            counts.present.forEach { status ->
                StatusRow(
                    label = status.label,
                    count = counts[status],
                    attention = status.isActionable,
                    onClick = { onShowFiles(status) },
                )
            }
            if (counts.errors > 0) {
                StatusRow(
                    label = "Could not be read",
                    count = counts.errors,
                    attention = true,
                    onClick = { onShowFiles(null) },
                )
            }

            TextButton(onClick = { onShowFiles(null) }) { Text("See every file") }
        }
    }
}

@Composable
private fun StatusRow(label: String, count: Int, attention: Boolean, onClick: () -> Unit) {
    // The count is never the only signal: the label names the pile, and the
    // tint only makes the ones needing action easier to find.
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = if (attention) StatusTint.attention else StatusTint.settled)
            Text("$count", color = if (attention) StatusTint.attention else StatusTint.settled)
        }
    }
}

@Composable
private fun WorkCard(work: WorkSummary, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(work.title, style = MaterialTheme.typography.titleMedium)
            Text(
                "${work.releaseCount} release(s) · ${work.provenance.describe()}",
                style = MaterialTheme.typography.bodySmall,
            )
            // An alias hit explains itself, so a user is never left wondering
            // why a result appeared (section 213).
            if (work.matchKind == MatchKind.ALIAS) {
                Text(
                    "matched on '${work.matchedOn}'",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusTint.settled,
                )
            }
            OutlinedButton(onClick = onOpen) { Text("Open") }
        }
    }
}

/** One title, opened: its releases, the files known for each, and where it came from. */
@Composable
private fun WorkDetailScreen(detail: WorkDetail, onBack: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onBack) { Text("← Back to library") }
                Text(detail.title, style = MaterialTheme.typography.headlineSmall)
                Text(detail.provenance.describe(), style = MaterialTheme.typography.bodySmall)
                if (detail.aliases.isNotEmpty()) {
                    // Includes names this title used to carry, which is what
                    // keeps historical identity findable (section 43).
                    Text("Also known as: ${detail.aliases.joinToString(", ")}")
                }
            }
        }

        item { Text("Releases", style = MaterialTheme.typography.titleMedium) }

        items(detail.releases, key = { it.id }) { release ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(release.label, style = MaterialTheme.typography.titleSmall)
                    Text(release.platform, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${release.artifactCount} file(s) known · ${release.provenance.describe()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // The digest is the one thing a user can carry to another
                    // tool and check for themselves (section 264).
                    release.artifactDigests.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = StatusTint.settled)
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Where this came from", style = MaterialTheme.typography.titleMedium)
                    if (detail.sources.isEmpty()) {
                        Text("No imported dataset currently describes this.")
                    }
                    detail.sources.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
                    // Counts datasets rather than claiming corroboration:
                    // several sources are not independent confirmation (§46).
                    Text(
                        "${detail.independentSourceCount} dataset(s) contribute to this title.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/**
 * Automation proposes; people establish (Constitution section 43).
 *
 * Said in words rather than left as an enum name, because the distinction is
 * the trust model and a user should not have to learn a vocabulary to read it.
 */
internal fun EntityProvenance.describe(): String = when (this) {
    EntityProvenance.DERIVED -> "from imported data"
    EntityProvenance.CONFIRMED -> "confirmed by you"
}

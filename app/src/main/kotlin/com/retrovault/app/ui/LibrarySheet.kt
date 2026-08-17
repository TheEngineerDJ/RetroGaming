package com.retrovault.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.retrovault.application.MatchKind
import com.retrovault.application.WorkDetail
import com.retrovault.application.WorkSummary
import com.retrovault.domain.entity.EntityProvenance
import com.retrovault.domain.identity.WorkId

/**
 * Browsing what RetroVault knows, rather than what it just scanned.
 *
 * Constitution section 137 requires the MVP to prove RetroVault can represent
 * entities, connect them and search them. This is where those three become
 * something a person can do.
 *
 * Two rules shape what is shown. A result says *why* it matched (section 213),
 * because a title found through an old name is not the same kind of answer as
 * one typed exactly. And an entity says whether a person established it or
 * automation proposed it (section 43), because the difference is the whole
 * trust model.
 */
@Composable
fun LibrarySheet(
    query: String,
    works: List<WorkSummary>,
    detail: WorkDetail?,
    onQueryChange: (String) -> Unit,
    onOpenWork: (WorkId) -> Unit,
    onCloseWork: () -> Unit,
    onClose: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your library", fontWeight = FontWeight.Bold)
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }

            if (detail != null) {
                item { WorkDetailCard(detail = detail, onBack = onCloseWork) }
                return@LazyColumn
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text("Search titles") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (works.isEmpty()) {
                item {
                    // Section 169 in miniature: an empty library is a statement
                    // about what has been scanned, not about what exists.
                    Text(
                        if (query.isBlank()) {
                            "Nothing here yet. Scanning a folder adds what RetroVault identifies."
                        } else {
                            "No title in your library matches that. It may still be on disk unidentified."
                        },
                    )
                }
            }

            items(works, key = { it.id.value }) { work ->
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(work.title, fontWeight = FontWeight.Bold)
                    Text("${work.releaseCount} release(s) · ${work.provenance.describe()}")
                    // An alias hit explains itself, so a user is never left
                    // wondering why a result appeared.
                    if (work.matchKind == MatchKind.ALIAS) {
                        Text("    also known as '${work.matchedOn}'")
                    }
                    TextButton(onClick = { onOpenWork(work.id) }) { Text("Open") }
                }
            }
        }
    }
}

@Composable
private fun WorkDetailCard(detail: WorkDetail, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(detail.title, fontWeight = FontWeight.Bold)
        Text(detail.provenance.describe())
        if (detail.aliases.isNotEmpty()) {
            // Includes names this title used to carry, which is what keeps
            // historical identity findable.
            Text("Also known as: ${detail.aliases.joinToString(", ")}")
        }

        Text("Releases", fontWeight = FontWeight.Bold)
        detail.releases.forEach { release ->
            Column(modifier = Modifier.padding(vertical = 2.dp)) {
                Text("· ${release.label} — ${release.platform}")
                Text("    ${release.artifactCount} file(s) known · ${release.provenance.describe()}")
                release.artifactDigests.forEach { Text("    $it") }
            }
        }

        Text("Where this came from", fontWeight = FontWeight.Bold)
        if (detail.sources.isEmpty()) {
            Text("No imported dataset currently describes this.")
        }
        detail.sources.forEach { Text("· $it") }
        // Counts datasets rather than claiming corroboration: several sources
        // are not automatically independent confirmation (section 46).
        Text("${detail.independentSourceCount} dataset(s) contribute to this title.")

        TextButton(onClick = onBack) { Text("Back to titles") }
    }
}

/**
 * Automation proposes; people establish (Constitution section 43).
 *
 * Said in words rather than left as an enum name, because the distinction is
 * the trust model and a user should not have to learn a vocabulary to read it.
 */
private fun EntityProvenance.describe(): String = when (this) {
    EntityProvenance.DERIVED -> "from imported data"
    EntityProvenance.CONFIRMED -> "confirmed by you"
}

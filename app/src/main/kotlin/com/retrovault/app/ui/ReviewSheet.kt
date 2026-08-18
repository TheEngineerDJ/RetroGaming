package com.retrovault.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.retrovault.application.CandidateChoice
import com.retrovault.application.ReviewSubject
import com.retrovault.domain.correction.CorrectionState
import com.retrovault.domain.identity.DumpRecordId

/**
 * Choosing what a file actually is.
 *
 * Constitution section 218 requires that disagreeing with RetroVault demand no
 * expertise, and section 44 requires the disagreement to stay visible. Both
 * shape this screen: every candidate is listed with the evidence for and
 * against it, the one automatic matching picked is marked rather than
 * privileged, and the correction history is shown underneath so a user can see
 * what they said before and undo it.
 *
 * Nothing here decides anything. The candidates, their evidence and the history
 * all arrive already decided from the application layer.
 */
@Composable
fun ReviewSheet(
    subject: ReviewSubject,
    busy: Boolean,
    onCorrectTo: (DumpRecordId, String?) -> Unit,
    onReject: (String?) -> Unit,
    onWithdraw: () -> Unit,
    onClose: () -> Unit,
) {
    var reason by remember(subject.observationId) { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("What is this file?", fontWeight = FontWeight.Bold)
                    Text(subject.filename)
                    Text("RetroVault concluded: ${subject.resolutionState} (${subject.identityBasis})")
                    if (subject.corrected) {
                        Text("You have already corrected this file. Your answer is being used.")
                    }
                }
            }

            if (subject.candidates.isEmpty()) {
                item {
                    // Section 169: no match is a statement about the catalogue,
                    // not about the artifact.
                    Text(
                        "RetroVault found no candidate identities for this file. That does not mean the " +
                            "file is unknown - it means no imported dataset lists it. Importing a dataset " +
                            "that covers this platform may give you something to choose from.",
                    )
                }
            }

            items(subject.candidates, key = { it.recordId.value }) { candidate ->
                CandidateCard(
                    candidate = candidate,
                    enabled = !busy,
                    onChoose = { onCorrectTo(candidate.recordId, reason.ifBlank { null }) },
                )
            }

            item {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Why? (optional, kept with the correction)") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onReject(reason.ifBlank { null }) },
                        enabled = !busy,
                    ) {
                        Text("None of these")
                    }
                    if (subject.corrected) {
                        OutlinedButton(onClick = onWithdraw, enabled = !busy) {
                            Text("Undo my correction")
                        }
                    }
                    TextButton(onClick = onClose, enabled = !busy) { Text("Close") }
                }
            }

            if (subject.history.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Text("What you have said about this file", fontWeight = FontWeight.Bold)
                }
                // Superseded and withdrawn entries included: a history that
                // shows only the current answer is not a history (section 70).
                items(subject.history, key = { it.correction.id.value }) { entry ->
                    Column(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text("· ${entry.describedAs} — ${entry.correction.state.describe()}")
                        entry.correction.previousIdentityDescription?.let {
                            Text("    RetroVault had said: $it")
                        }
                        entry.correction.reason?.takeIf { it.isNotBlank() }?.let { Text("    Reason: $it") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(candidate: CandidateChoice, enabled: Boolean, onChoose: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = if (candidate.selected) "${candidate.label}  (RetroVault's answer)" else candidate.label,
                fontWeight = FontWeight.Bold,
            )
            Text("from ${candidate.provider}: ${candidate.setName}")
            candidate.supporting.forEach { Text("    + $it") }
            // Shown, never hidden: what argues against an identity is part of
            // deciding whether to accept it.
            candidate.contradicting.forEach { Text("    − $it") }
            Button(onClick = onChoose, enabled = enabled) { Text("This is the one") }
        }
    }
}

private fun CorrectionState.describe(): String = when (this) {
    CorrectionState.ACTIVE -> "in use now"
    CorrectionState.SUPERSEDED -> "replaced by a later answer"
    CorrectionState.WITHDRAWN -> "withdrawn"
}

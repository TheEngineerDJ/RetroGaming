package com.retrovault.domain.rename

import com.retrovault.domain.identity.PlanEntryId
import com.retrovault.domain.identity.StorageRef
import com.retrovault.domain.naming.FilenameSanitizer
import com.retrovault.domain.naming.FilenameValidation
import com.retrovault.domain.naming.InvalidNameReason
import com.retrovault.domain.policy.AutomationDecision

/**
 * What the filesystem currently contains in one directory.
 *
 * Supplied by infrastructure; the validator itself performs no I/O so that
 * every collision rule can be tested without a filesystem.
 */
data class DirectorySnapshot(
    val directoryRef: StorageRef,
    /** Every name currently present, exactly as the provider reports it. */
    val existingNames: Set<String>,
) {
    private val lowercased: Set<String> = existingNames.mapTo(mutableSetOf()) { it.lowercase() }

    fun containsIgnoringCase(name: String): Boolean = name.lowercase() in lowercased
}

/** The current state of one file, re-read immediately before execution. */
data class ArtifactState(
    val storageRef: StorageRef,
    val exists: Boolean,
    val filename: String?,
    val size: Long?,
    val writable: Boolean,
    /**
     * Whether storage answered at all.
     *
     * `false` means [exists] and the rest are defaults, not observations. Both
     * outcomes block the batch, so this changes no decision - it changes what
     * the user is told, and "the file no longer exists" is not a true thing to
     * say about a file whose folder refused to answer (UX_SPEC.md section 13).
     */
    val readable: Boolean = true,
)

enum class PlanVerdict {
    /** Every planned rename is safe. Execution may proceed. */
    EXECUTABLE,

    /** At least one blocking issue. Nothing will be executed. */
    BLOCKED,

    /** Nothing to do; the library is already canonical or nothing is resolved. */
    NOTHING_TO_DO,
}

/** The whole-batch verdict, plus every reason behind it. */
data class RenamePlanValidation(
    val plan: RenamePlan,
    val verdict: PlanVerdict,
    val issues: List<PlanIssue>,
    val executable: List<RenamePlanEntry>,
    val validatedAtEpochMillis: Long,
) {
    val blockingIssues: List<PlanIssue> get() = issues.filter { it.severity == IssueSeverity.BLOCKING }
    val warnings: List<PlanIssue> get() = issues.filter { it.severity == IssueSeverity.WARNING }
}

/**
 * Validates the entire batch before anything is written.
 *
 * Constitution section 159 and ROM_INTELLIGENCE.md section 11: the batch is
 * resolved, duplicate destinations and collisions are detected, filesystem
 * constraints and permissions are checked, and no partial execution happens
 * merely because the first entries would have succeeded.
 *
 * The verdict is deliberately all-or-nothing. When one entry is unsafe the user
 * excludes it and revalidates ([RenamePlan.without]); the alternative - quietly
 * dropping the offending row and renaming the rest - is how libraries get half
 * mutated.
 */
class RenamePlanValidator {

    fun validate(
        plan: RenamePlan,
        directories: Map<StorageRef, DirectorySnapshot>,
        states: Map<StorageRef, ArtifactState>,
        nowEpochMillis: Long,
    ): RenamePlanValidation {
        val perEntryIssues = mutableMapOf<RenamePlanEntry, MutableList<PlanIssue>>()
        val renameEntries = plan.entries.filter { it.action == PlannedAction.RENAME }

        fun issuesFor(entry: RenamePlanEntry) = perEntryIssues.getOrPut(entry) { mutableListOf() }

        // Names this batch will free up. A destination that is currently taken
        // by another file in the same batch is not a collision, it is an
        // ordering constraint - and treating it as a collision would block the
        // most ordinary case there is: a folder renamed to a new convention,
        // where several files shift along by one name.
        val vacated = renameEntries
            .filter { it.proposedName != null && !it.proposedName.equals(it.currentName, ignoreCase = true) }
            .associateBy { nameKey(it.directoryRef, it.currentName) }

        renameEntries.forEach { entry ->
            validateEntry(entry, directories, states, vacated, issuesFor(entry))
        }
        detectDuplicateDestinations(renameEntries).forEach { (entry, issue) ->
            issuesFor(entry).add(issue)
        }

        val ordering = order(renameEntries)
        ordering.cyclic.forEach { cycle ->
            val issue = PlanIssue.RenameCycle(
                entryIds = cycle.map { it.id },
                names = cycle.map { it.currentName },
            )
            cycle.forEach { entry -> issuesFor(entry).add(issue) }
        }

        val validatedEntries = plan.entries.map { entry ->
            entry.withIssues(perEntryIssues[entry].orEmpty())
        }
        val validatedPlan = plan.copy(entries = validatedEntries)
        val allIssues = validatedEntries.flatMap { it.issues }
        val blocking = allIssues.filter { it.severity == IssueSeverity.BLOCKING }
        // Ordered, so the executor can run the list front to back and never
        // find a destination still held by a file it has not moved yet.
        val byId = validatedEntries.associateBy { it.id }
        val executable = ordering.ordered.mapNotNull { byId[it.id] }
            .filter { it.action == PlannedAction.RENAME }

        val verdict = when {
            blocking.isNotEmpty() -> PlanVerdict.BLOCKED
            executable.isEmpty() -> PlanVerdict.NOTHING_TO_DO
            else -> PlanVerdict.EXECUTABLE
        }

        return RenamePlanValidation(
            plan = validatedPlan,
            verdict = verdict,
            issues = allIssues,
            executable = if (verdict == PlanVerdict.EXECUTABLE) executable else emptyList(),
            validatedAtEpochMillis = nowEpochMillis,
        )
    }

    private fun validateEntry(
        entry: RenamePlanEntry,
        directories: Map<StorageRef, DirectorySnapshot>,
        states: Map<StorageRef, ArtifactState>,
        vacated: Map<String, RenamePlanEntry>,
        issues: MutableList<PlanIssue>,
    ) {
        // Re-check the safety rule the planner already applied. A plan can be
        // persisted, reloaded and replayed; this is the last chance to catch a
        // rename that policy never authorised.
        if (entry.automation == AutomationDecision.FORBIDDEN ||
            (entry.automation == AutomationDecision.REQUIRES_REVIEW && !entry.userConfirmed)
        ) {
            issues.add(PlanIssue.UnsafeAutomation(entry.id, entry.resolution.state))
            return
        }

        val proposed = entry.proposedName
        if (proposed == null) {
            issues.add(
                PlanIssue.InvalidDestinationName(
                    entry.id,
                    InvalidNameReason.EMPTY,
                    "No canonical name was produced for this file.",
                ),
            )
            return
        }

        when (val validation = FilenameSanitizer.validate(proposed)) {
            is FilenameValidation.Invalid ->
                issues.add(PlanIssue.InvalidDestinationName(entry.id, validation.reason, validation.message))

            is FilenameValidation.Valid -> Unit
        }

        val state = states[entry.storageRef]
        when {
            state != null && !state.readable ->
                issues.add(
                    PlanIssue.UnsupportedOperation(
                        entry.id,
                        "its current state could not be read, so the rename cannot be verified as safe",
                    ),
                )

            state == null || !state.exists ->
                issues.add(PlanIssue.StaleObservation(entry.id, "the file no longer exists"))

            state.filename != entry.observation.filename ->
                issues.add(
                    PlanIssue.StaleObservation(
                        entry.id,
                        "it is now named '${state.filename}' but was scanned as '${entry.observation.filename}'",
                    ),
                )

            state.size != entry.observation.size ->
                issues.add(
                    PlanIssue.StaleObservation(
                        entry.id,
                        "it is now ${state.size} bytes but was scanned at ${entry.observation.size} bytes",
                    ),
                )

            !state.writable -> issues.add(PlanIssue.PermissionDenied(entry.id))
        }

        val directory = directories[entry.directoryRef]
        if (directory == null) {
            issues.add(PlanIssue.UnsupportedOperation(entry.id, "the containing folder could not be read"))
            return
        }

        val caseOnly = proposed.equals(entry.currentName, ignoreCase = true) && proposed != entry.currentName
        val occupantMovesAway = vacated[nameKey(entry.directoryRef, proposed)]?.let { it.id != entry.id } == true
        if (caseOnly) {
            issues.add(PlanIssue.CaseOnlyRename(entry.id, proposed))
        } else if (directory.containsIgnoringCase(proposed) && !occupantMovesAway) {
            // Case-insensitive on purpose: on FAT and exFAT, `game.sfc` and
            // `GAME.SFC` are the same file (Constitution section 244).
            issues.add(PlanIssue.DestinationOccupied(entry.id, proposed))
        }
    }

    private data class Ordering(
        val ordered: List<RenamePlanEntry>,
        /** Groups that cannot be sequenced at all, one list per deadlock. */
        val cyclic: List<List<RenamePlanEntry>>,
    )

    /**
     * Sequences renames so no entry runs while another still holds its name.
     *
     * Each pass takes every entry whose destination is free of the entries not
     * yet scheduled. When a pass can take nothing, whatever remains is waiting
     * on itself - a swap - and is reported rather than attempted.
     *
     * An entry that only changes letter case is never considered to be blocking
     * itself; the executor performs that one through a temporary name.
     */
    private fun order(entries: List<RenamePlanEntry>): Ordering {
        val ordered = mutableListOf<RenamePlanEntry>()
        var remaining = entries
        while (remaining.isNotEmpty()) {
            val held = remaining.associateBy { nameKey(it.directoryRef, it.currentName) }
            val (ready, waiting) = remaining.partition { entry ->
                val destination = entry.proposedName ?: return@partition true
                val occupant = held[nameKey(entry.directoryRef, destination)]
                occupant == null || occupant.id == entry.id
            }
            if (ready.isEmpty()) return Ordering(ordered, deadlocks(waiting))
            ordered += ready
            remaining = waiting
        }
        return Ordering(ordered, emptyList())
    }

    /**
     * Splits the entries that could not be scheduled into independent groups.
     *
     * Two unrelated swaps in two folders are two separate problems, and the
     * user should be able to exclude one without being told about the other.
     */
    private fun deadlocks(waiting: List<RenamePlanEntry>): List<List<RenamePlanEntry>> {
        val byCurrent = waiting.associateBy { nameKey(it.directoryRef, it.currentName) }
        val groups = mutableListOf<List<RenamePlanEntry>>()
        val assigned = mutableSetOf<PlanEntryId>()
        waiting.forEach { start ->
            if (start.id in assigned) return@forEach
            val group = mutableListOf<RenamePlanEntry>()
            var current: RenamePlanEntry? = start
            while (current != null && assigned.add(current.id)) {
                group += current
                val destination = current.proposedName ?: break
                current = byCurrent[nameKey(current.directoryRef, destination)]
            }
            groups += group
        }
        return groups
    }

    /**
     * Keys a name within its directory.
     *
     * The separator is an ASCII unit separator rather than a space because both
     * halves can contain spaces: a directory ref is a URI or a path, and a
     * filename is arbitrary user data. With a space, directory `a` holding
     * `b c` and directory `a b` holding `c` would produce the same key, and two
     * unrelated files would look like a collision. A unit separator cannot
     * appear in either half - [FilenameSanitizer] rejects control characters in
     * a destination name.
     */
    private fun nameKey(directory: StorageRef, name: String): String =
        directory.value + SEPARATOR + name.lowercase()

    private companion object {
        const val SEPARATOR = '\u001F'
    }

    private fun detectDuplicateDestinations(
        entries: List<RenamePlanEntry>,
    ): List<Pair<RenamePlanEntry, PlanIssue>> {
        val grouped = entries
            .filter { it.proposedName != null }
            .groupBy { it.directoryRef.value to it.proposedName!!.lowercase() }
        return grouped.values
            .filter { it.size > 1 }
            .flatMap { colliding ->
                val issue = PlanIssue.DuplicateDestination(
                    destination = colliding.first().proposedName!!,
                    entryIds = colliding.map { it.id },
                )
                colliding.map { it to issue }
            }
    }
}

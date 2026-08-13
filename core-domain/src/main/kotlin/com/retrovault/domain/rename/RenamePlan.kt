package com.retrovault.domain.rename

import com.retrovault.domain.identity.ObservationId
import com.retrovault.domain.identity.PlanEntryId
import com.retrovault.domain.identity.RenamePlanId
import com.retrovault.domain.identity.ScanSessionId
import com.retrovault.domain.identity.StorageRef
import com.retrovault.domain.naming.CanonicalFilenameGenerator
import com.retrovault.domain.naming.FilenameValidation
import com.retrovault.domain.naming.InvalidNameReason
import com.retrovault.domain.naming.NamingProfile
import com.retrovault.domain.observation.FileObservation
import com.retrovault.domain.policy.AutomationDecision
import com.retrovault.domain.policy.AutomationPolicy
import com.retrovault.domain.resolution.ArtifactResolution
import com.retrovault.domain.resolution.ResolutionState

/** What the planner intends to do with one file. */
enum class PlannedAction {
    RENAME,

    /** The file already carries its canonical name. Running again changes nothing. */
    SKIP_ALREADY_CANONICAL,

    /** Identified, but not confidently enough to act without confirmation. */
    SKIP_REQUIRES_REVIEW,

    /** Not identified. Never renamed. */
    SKIP_UNRESOLVED,

    /** Would have been renamed, but validation refused it. */
    BLOCKED,
    ;

    val mutatesFilesystem: Boolean get() = this == RENAME
}

enum class IssueSeverity {
    /** Refuses execution of the whole batch. */
    BLOCKING,

    /** Execution may proceed; the user must be told. */
    WARNING,

    /** Explains why an entry is not being renamed. */
    INFORMATIONAL,
}

/**
 * A concrete reason a plan cannot proceed as written.
 *
 * ENGINEERING_SPEC.md section 8 requires expected failures to be typed, and
 * UX_SPEC.md section 13 requires errors to explain what failed, what remains
 * safe and whether user action is required.
 */
sealed interface PlanIssue {
    val severity: IssueSeverity
    val message: String
    val entryIds: List<PlanEntryId>

    /** Two or more sources resolve to the same destination in one directory. */
    data class DuplicateDestination(
        val destination: String,
        override val entryIds: List<PlanEntryId>,
    ) : PlanIssue {
        override val severity: IssueSeverity get() = IssueSeverity.BLOCKING
        override val message: String
            get() = "${entryIds.size} files would be renamed to '$destination' in the same folder."
    }

    /** The destination name is already taken by a different file. */
    data class DestinationOccupied(
        val entryId: PlanEntryId,
        val destination: String,
    ) : PlanIssue {
        override val severity: IssueSeverity get() = IssueSeverity.BLOCKING
        override val entryIds: List<PlanEntryId> get() = listOf(entryId)
        override val message: String
            get() = "'$destination' already exists in this folder and is a different file."
    }

    /**
     * Two or more files want each other's names.
     *
     * `a.sfc` to `b.sfc` and `b.sfc` to `a.sfc` cannot be ordered: whichever
     * runs first overwrites or collides with the other. Resolving it needs a
     * temporary name, which would leave the library in a state no journal entry
     * describes if the process died in between. RetroVault refuses instead and
     * asks the user to exclude one side.
     */
    data class RenameCycle(
        override val entryIds: List<PlanEntryId>,
        val names: List<String>,
    ) : PlanIssue {
        override val severity: IssueSeverity get() = IssueSeverity.BLOCKING
        override val message: String
            get() = "${names.joinToString(", ")} would have to swap names. " +
                "Exclude one of them and try again."
    }

    data class InvalidDestinationName(
        val entryId: PlanEntryId,
        val reason: InvalidNameReason,
        override val message: String,
    ) : PlanIssue {
        override val severity: IssueSeverity get() = IssueSeverity.BLOCKING
        override val entryIds: List<PlanEntryId> get() = listOf(entryId)
    }

    /**
     * The file changed since it was scanned.
     *
     * Constitution section 243: a scan result must never be assumed current
     * indefinitely, and identity is revalidated before any mutation.
     */
    data class StaleObservation(
        val entryId: PlanEntryId,
        val detail: String,
    ) : PlanIssue {
        override val severity: IssueSeverity get() = IssueSeverity.BLOCKING
        override val entryIds: List<PlanEntryId> get() = listOf(entryId)
        override val message: String get() = "The file changed since it was scanned: $detail"
    }

    data class PermissionDenied(val entryId: PlanEntryId) : PlanIssue {
        override val severity: IssueSeverity get() = IssueSeverity.BLOCKING
        override val entryIds: List<PlanEntryId> get() = listOf(entryId)
        override val message: String get() = "RetroVault does not have permission to rename this file."
    }

    data class UnsupportedOperation(
        val entryId: PlanEntryId,
        val detail: String,
    ) : PlanIssue {
        override val severity: IssueSeverity get() = IssueSeverity.BLOCKING
        override val entryIds: List<PlanEntryId> get() = listOf(entryId)
        override val message: String get() = "This storage location cannot rename the file: $detail"
    }

    /**
     * A rename was planned for an identity that policy forbids acting on.
     *
     * This should be impossible: the planner never emits it. It is checked
     * again here because it is the one mistake that would let RetroVault
     * rename a file it cannot explain (CLAUDE_CODE.md, safety rules).
     */
    data class UnsafeAutomation(
        val entryId: PlanEntryId,
        val state: ResolutionState,
    ) : PlanIssue {
        override val severity: IssueSeverity get() = IssueSeverity.BLOCKING
        override val entryIds: List<PlanEntryId> get() = listOf(entryId)
        override val message: String
            get() = "Refusing to rename a file whose identity is $state."
    }

    /** Differs from the current name only by letter case. */
    data class CaseOnlyRename(
        val entryId: PlanEntryId,
        val destination: String,
    ) : PlanIssue {
        override val severity: IssueSeverity get() = IssueSeverity.WARNING
        override val entryIds: List<PlanEntryId> get() = listOf(entryId)
        override val message: String
            get() = "'$destination' differs only by letter case; some storage volumes cannot perform this rename."
    }

    data class NotRenamed(
        val entryId: PlanEntryId,
        val action: PlannedAction,
        override val message: String,
    ) : PlanIssue {
        override val severity: IssueSeverity get() = IssueSeverity.INFORMATIONAL
        override val entryIds: List<PlanEntryId> get() = listOf(entryId)
    }
}

/** One row of the preview, carrying everything needed to explain it. */
data class RenamePlanEntry(
    val id: PlanEntryId,
    val observation: FileObservation,
    val resolution: ArtifactResolution,
    val currentName: String,
    /** `null` when no canonical name could be produced. */
    val proposedName: String?,
    val action: PlannedAction,
    val automation: AutomationDecision,
    /** Set when the user explicitly accepted a candidate for this file. */
    val userConfirmed: Boolean,
    val issues: List<PlanIssue> = emptyList(),
) {
    val directoryRef: StorageRef get() = observation.parentRef

    val storageRef: StorageRef get() = observation.storageRef

    fun withIssues(added: List<PlanIssue>): RenamePlanEntry {
        if (added.isEmpty()) return this
        val blocking = added.any { it.severity == IssueSeverity.BLOCKING }
        return copy(
            issues = issues + added,
            action = if (blocking) PlannedAction.BLOCKED else action,
        )
    }
}

/**
 * A complete, previewable batch.
 *
 * Constitution section 160: the system never renames files as it discovers
 * them. Identification produces a plan; the plan is validated as a whole; only
 * then may anything be written.
 */
data class RenamePlan(
    val id: RenamePlanId,
    val sessionId: ScanSessionId,
    val profile: NamingProfile,
    val policy: AutomationPolicy,
    val entries: List<RenamePlanEntry>,
    val createdAtEpochMillis: Long,
) {
    val renameCount: Int get() = entries.count { it.action == PlannedAction.RENAME }

    val skippedCount: Int get() = entries.count { !it.action.mutatesFilesystem }

    /** Removes entries, e.g. after the user excludes a file, so the batch can be revalidated. */
    fun without(excluded: Set<PlanEntryId>): RenamePlan =
        copy(entries = entries.filterNot { it.id in excluded })
}

/**
 * Turns resolutions into a plan.
 *
 * The planner is where "never auto-rename an unresolved artifact" is enforced
 * for the first time. The validator enforces it again, and the executor refuses
 * anything the validator did not approve.
 */
object RenamePlanBuilder {
    const val VERSION: String = "rename-planner-v1"

    /**
     * @param confirmations entries the user explicitly accepted. A confirmation
     * upgrades a `REQUIRES_REVIEW` resolution into an actionable one; it can
     * never upgrade a `FORBIDDEN` one.
     */
    @Suppress("LongParameterList")
    fun build(
        id: RenamePlanId,
        sessionId: ScanSessionId,
        profile: NamingProfile,
        policy: AutomationPolicy,
        resolved: List<Pair<FileObservation, ArtifactResolution>>,
        confirmations: Set<ObservationId> = emptySet(),
        createdAtEpochMillis: Long,
        entryIdFactory: (FileObservation) -> PlanEntryId,
    ): RenamePlan {
        val entries = resolved.map { (observation, resolution) ->
            buildEntry(entryIdFactory(observation), observation, resolution, profile, policy, confirmations)
        }
        return RenamePlan(id, sessionId, profile, policy, entries, createdAtEpochMillis)
    }

    private fun buildEntry(
        entryId: PlanEntryId,
        observation: FileObservation,
        resolution: ArtifactResolution,
        profile: NamingProfile,
        policy: AutomationPolicy,
        confirmations: Set<ObservationId>,
    ): RenamePlanEntry {
        val automation = policy.decide(resolution)
        val confirmed = observation.id in confirmations
        val selected = resolution.selected

        if (selected == null) {
            return RenamePlanEntry(
                id = entryId,
                observation = observation,
                resolution = resolution,
                currentName = observation.filename,
                proposedName = null,
                action = PlannedAction.SKIP_UNRESOLVED,
                automation = automation,
                userConfirmed = confirmed,
                issues = listOf(
                    PlanIssue.NotRenamed(
                        entryId,
                        PlannedAction.SKIP_UNRESOLVED,
                        unresolvedMessage(resolution.state),
                    ),
                ),
            )
        }

        val generated = CanonicalFilenameGenerator.generate(
            record = selected.record,
            profile = profile,
            containerExtension = observation.extension,
        )
        val proposed = when (generated) {
            is FilenameValidation.Valid -> generated.name
            is FilenameValidation.Invalid -> return RenamePlanEntry(
                id = entryId,
                observation = observation,
                resolution = resolution,
                currentName = observation.filename,
                proposedName = null,
                action = PlannedAction.BLOCKED,
                automation = automation,
                userConfirmed = confirmed,
                issues = listOf(
                    PlanIssue.InvalidDestinationName(entryId, generated.reason, generated.message),
                ),
            )
        }

        val action = when {
            proposed == observation.filename -> PlannedAction.SKIP_ALREADY_CANONICAL
            automation == AutomationDecision.FORBIDDEN -> PlannedAction.SKIP_UNRESOLVED
            automation == AutomationDecision.REQUIRES_REVIEW && !confirmed -> PlannedAction.SKIP_REQUIRES_REVIEW
            else -> PlannedAction.RENAME
        }

        val issues = when (action) {
            PlannedAction.SKIP_ALREADY_CANONICAL -> listOf(
                PlanIssue.NotRenamed(
                    entryId,
                    action,
                    "Already named '${observation.filename}'. Nothing to change.",
                ),
            )

            PlannedAction.SKIP_REQUIRES_REVIEW -> listOf(
                PlanIssue.NotRenamed(
                    entryId,
                    action,
                    "This is a ${resolution.confidence} match. Confirm the identity before renaming.",
                ),
            )

            PlannedAction.SKIP_UNRESOLVED -> listOf(
                PlanIssue.NotRenamed(entryId, action, unresolvedMessage(resolution.state)),
            )

            else -> emptyList()
        }

        return RenamePlanEntry(
            id = entryId,
            observation = observation,
            resolution = resolution,
            currentName = observation.filename,
            proposedName = proposed,
            action = action,
            automation = automation,
            userConfirmed = confirmed,
            issues = issues,
        )
    }

    private fun unresolvedMessage(state: ResolutionState): String = when (state) {
        ResolutionState.AMBIGUOUS ->
            "Several releases match equally well. RetroVault will not choose one for you."

        ResolutionState.CONFLICT ->
            "Strong evidence contradicts the best candidate, so the identity is unresolved."

        ResolutionState.NO_MATCH ->
            "The imported datasets cover this kind of file but do not list this one. That may mean a " +
                "modified dump, or a release nobody has catalogued yet. It is left untouched."

        ResolutionState.OUT_OF_CATALOGUE_SCOPE ->
            "No imported dataset covers this kind of media, so RetroVault has nothing to compare this " +
                "file against. Import a dataset for this platform and scan again. This is not a " +
                "statement that the file is unknown."

        ResolutionState.UNSUPPORTED ->
            "This file cannot be identified by the current pipeline."

        else -> "The identity is not established well enough to rename this file."
    }
}

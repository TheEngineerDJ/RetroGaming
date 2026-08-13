package com.retrovault.domain.correction

import com.retrovault.domain.identity.CorrectionId
import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.HashDigests
import com.retrovault.domain.identity.HashValue
import com.retrovault.domain.identity.ReleaseId
import com.retrovault.domain.observation.FileObservation

/**
 * What a correction is about.
 *
 * Keyed by content, never by filename and never by observation id. A filename
 * is representation, not identity, so a correction keyed on one would follow
 * the wrong file the moment anything was renamed - including by RetroVault
 * itself. An observation id is worse: it is minted per scan, so a correction
 * keyed on one would silently stop applying the next time the user scanned.
 *
 * Content keying is what makes a correction *durable*: the same bytes get the
 * same answer next month, wherever they have moved to and whatever they are
 * called.
 */
data class CorrectionScope(
    val algorithm: HashAlgorithm,
    val digest: String,
    /**
     * Byte size at the time of correction, where it was known.
     *
     * Corroboration only. A hash match already settles identity; size is
     * recorded so a stored correction can be explained to the user without
     * re-reading the file.
     */
    val size: Long? = null,
) {
    init {
        require(digest.isNotBlank()) { "A correction scope must carry a digest" }
        require(size == null || size >= 0) { "A correction scope size must not be negative" }
    }

    fun key(): String = "${algorithm.name}:$digest"

    companion object {
        /**
         * The scope a correction for [observation] would have.
         *
         * Returns `null` when no cryptographic hash is available. Refusing is
         * deliberate: CRC32 is a discriminator rather than content proof
         * (Constitution section 148), and a correction keyed on a 32-bit value
         * would eventually attach a user's assertion to bytes they never saw.
         * A correction that cannot be made durable is not made at all.
         */
        fun forObservation(observation: FileObservation): CorrectionScope? =
            fromHashes(observation.identityBearingHashes(), observation.identityBearingSize())

        fun fromHashes(hashes: HashDigests, size: Long?): CorrectionScope? {
            val strongest = listOf(HashAlgorithm.SHA1, HashAlgorithm.MD5)
                .firstNotNullOfOrNull { algorithm -> hashes[algorithm] }
                ?: return null
            return CorrectionScope(strongest.algorithm, strongest.hex, size)
        }

        fun forHash(hash: HashValue, size: Long? = null): CorrectionScope? =
            if (hash.algorithm.isCryptographicIdentityEvidence) {
                CorrectionScope(hash.algorithm, hash.hex, size)
            } else {
                null
            }
    }
}

/** What a correction asserts the artifact actually is. */
sealed interface CorrectedIdentity {
    /** The user says these bytes are this catalogued release. */
    data class IsRelease(val releaseId: ReleaseId) : CorrectedIdentity

    /**
     * The user says RetroVault's answer is wrong and offers no replacement.
     *
     * A first-class outcome. "That is not Chrono Trigger" is useful knowledge
     * even when the user cannot say what it is, and forcing them to supply a
     * replacement would push them into guessing (Constitution section 218:
     * separate "I observed this" from "I conclude this").
     */
    data object NotThis : CorrectedIdentity
}

/** Where a correction stands now. History is kept; nothing is rewritten. */
enum class CorrectionState {
    /** The current answer for this content. */
    ACTIVE,

    /** Replaced by a later correction for the same content. */
    SUPERSEDED,

    /** The user took it back. Automatic identification applies again. */
    WITHDRAWN,
    ;

    val appliesToResolution: Boolean get() = this == ACTIVE
}

/**
 * One durable user correction.
 *
 * Constitution section 69 requires a correction to be a first-class event
 * preserving the previous claim, the corrected claim, a reason, the timestamp
 * and a review state, and never to silently rewrite history. DOMAIN_MODEL.md
 * section 37 invariant 13 requires it to outrank automatic suggestions for that
 * user's collection.
 *
 * Both together mean a correction is append-only: superseding one writes a new
 * row and marks the old [CorrectionState.SUPERSEDED], so "what did RetroVault
 * think before I fixed it" stays answerable (section 70).
 */
data class IdentityCorrection(
    val id: CorrectionId,
    val scope: CorrectionScope,
    /**
     * What automatic identification had concluded, as a description.
     *
     * Kept as text rather than as a release reference because the record it
     * named may be gone by the time anyone reads this - a dataset can be
     * re-imported or removed, and the point of section 69 is that the previous
     * claim survives regardless.
     */
    val previousIdentityDescription: String?,
    val corrected: CorrectedIdentity,
    /** Why, in the user's words. Never parsed, only shown. */
    val reason: String?,
    val recordedAtEpochMillis: Long,
    val state: CorrectionState = CorrectionState.ACTIVE,
    /** The correction that replaced this one, when it was superseded. */
    val supersededBy: CorrectionId? = null,
) {
    init {
        require(state != CorrectionState.SUPERSEDED || supersededBy != null) {
            "A superseded correction must name what superseded it"
        }
        require(state == CorrectionState.SUPERSEDED || supersededBy == null) {
            "Only a superseded correction names a successor"
        }
    }

    fun supersededBy(successor: CorrectionId): IdentityCorrection =
        copy(state = CorrectionState.SUPERSEDED, supersededBy = successor)

    fun withdrawn(): IdentityCorrection = copy(state = CorrectionState.WITHDRAWN, supersededBy = null)
}

/**
 * The active corrections a scan should apply, indexed by content.
 *
 * Loaded once per scan rather than queried per file: corrections are authored
 * by hand, so there are tens or hundreds of them, not millions. The lookup is
 * by content key, so a correction finds its file no matter what the file is
 * called or where it has moved to.
 */
class CorrectionSet(corrections: List<IdentityCorrection>) {

    private val byScope: Map<String, IdentityCorrection> =
        corrections.filter { it.state.appliesToResolution }.associateBy { it.scope.key() }

    val size: Int get() = byScope.size

    val isEmpty: Boolean get() = byScope.isEmpty()

    /**
     * The correction for these bytes, if the user has made one.
     *
     * Every cryptographic hash known for the artifact is tried, because the
     * user may have corrected it when only MD5 was available and a later scan
     * may have computed SHA1.
     */
    fun forHashes(hashes: HashDigests): IdentityCorrection? =
        listOf(HashAlgorithm.SHA1, HashAlgorithm.MD5)
            .mapNotNull { algorithm -> hashes[algorithm] }
            .firstNotNullOfOrNull { hash -> byScope["${hash.algorithm.name}:${hash.hex}"] }

    fun forObservation(observation: FileObservation): IdentityCorrection? =
        forHashes(observation.identityBearingHashes())

    companion object {
        val EMPTY = CorrectionSet(emptyList())
    }
}

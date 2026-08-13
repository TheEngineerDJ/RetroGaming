package com.retrovault.domain.catalog

import com.retrovault.domain.identity.MediaType

/**
 * What one imported dataset actually indexes.
 *
 * Measured from the records the dataset contributed, never assumed from its
 * name. A DAT is evidence about the releases it describes; what it *covers* is
 * evidence about the DAT, and both have to be observed rather than inferred
 * from a provider string (Constitution section 196).
 */
data class DatasetCoverage(
    val source: DatSourceRef,
    /** Media types this dataset has at least one matchable record for. */
    val mediaTypes: Set<MediaType>,
    val recordCount: Int,
) {
    init {
        require(recordCount >= 0) { "Dataset record count must not be negative" }
    }

    /**
     * Whether this dataset might say something about [media].
     *
     * Answers "might", not "does", and every uncertainty resolves to `true`.
     * The only thing this is used for is deciding whether to stop before
     * reading a file, so a false `true` costs a wasted lookup while a false
     * `false` suppresses a real match.
     *
     * A dataset containing *any* record of unrecognised medium may cover
     * anything: RetroVault's failure to classify an extension is not evidence
     * that the dataset excludes that medium. Requiring every record to be
     * unrecognised before granting that benefit - as an earlier version did -
     * would let a dataset of `.sfc` and `.bin` entries declare a `.iso` out of
     * scope, on the strength of extensions it never understood.
     */
    fun covers(media: MediaType): Boolean = when {
        mediaTypes.isEmpty() -> true
        media == MediaType.UNKNOWN -> true
        media in mediaTypes -> true
        MediaType.UNKNOWN in mediaTypes -> true
        else -> false
    }
}

/**
 * The union of everything currently importable into an identification.
 *
 * [UNMEASURED] is the honest default for a caller that did not look. It is not
 * "nothing is covered" - it means RetroVault has no basis for a scope judgement
 * and must fall back to reporting plain absence of a match.
 */
data class CatalogueCoverage(val datasets: List<DatasetCoverage>, val measured: Boolean = true) {

    val isEmpty: Boolean get() = datasets.isEmpty()

    val mediaTypes: Set<MediaType> get() = datasets.flatMapTo(mutableSetOf()) { it.mediaTypes }

    /** Media actually catalogued, with the unrecognised bucket removed. */
    val recognisedMediaTypes: Set<MediaType> get() = mediaTypes - MediaType.UNKNOWN

    companion object {
        val UNMEASURED = CatalogueCoverage(datasets = emptyList(), measured = false)
    }
}

/**
 * Why the catalogue had nothing to say about a file.
 *
 * The distinction this type exists to draw is the one the Constitution insists
 * on in section 168 and section 174: absence of a record is a statement about
 * the catalogue, not about the artifact. "No dataset covers PSP discs" and
 * "the PSP dataset does not list this disc" are different facts, and only the
 * second is evidence about the file.
 */
sealed interface CoverageAssessment {
    /** At least one dataset indexes this medium, so absence of a match is meaningful. */
    data object Covered : CoverageAssessment

    /** Nothing has been imported. The catalogue cannot speak at all. */
    data object NoDatasets : CoverageAssessment

    /**
     * Datasets exist, but none of them index this medium.
     *
     * The canonical case: a library of PSP UMD images scanned against a
     * cartridge-only No-Intro set.
     */
    data class MediaNotCovered(
        val observed: MediaType,
        val available: Set<MediaType>,
    ) : CoverageAssessment

    /** True when the catalogue has no standing to call a file unidentified. */
    val isOutOfScope: Boolean get() = this !is Covered
}

/**
 * Decides whether the imported datasets could plausibly describe a file.
 *
 * Pure and side-effect free so the rule is testable without a database, and
 * deliberately generous: it only ever reports out-of-scope when the evidence
 * for it is positive - datasets exist, their media are known, and the observed
 * medium is not among them. Every uncertain case reads as [CoverageAssessment.Covered]
 * so that a scope judgement can never be the reason a real match is missed.
 */
object DatasetCompatibility {
    const val VERSION: String = "dataset-compatibility-v1"

    fun assess(observed: MediaType, coverage: CatalogueCoverage): CoverageAssessment = when {
        !coverage.measured -> CoverageAssessment.Covered
        coverage.isEmpty -> CoverageAssessment.NoDatasets
        observed == MediaType.UNKNOWN -> CoverageAssessment.Covered
        coverage.datasets.any { it.covers(observed) } -> CoverageAssessment.Covered
        else -> CoverageAssessment.MediaNotCovered(observed, coverage.recognisedMediaTypes)
    }
}

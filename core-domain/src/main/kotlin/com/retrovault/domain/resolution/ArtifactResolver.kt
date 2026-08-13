package com.retrovault.domain.resolution

import com.retrovault.domain.catalog.CanonicalIdentityKey
import com.retrovault.domain.catalog.CatalogueCoverage
import com.retrovault.domain.catalog.CoverageAssessment
import com.retrovault.domain.catalog.DatasetCompatibility
import com.retrovault.domain.catalog.DumpRecord
import com.retrovault.domain.evidence.Evidence
import com.retrovault.domain.evidence.EvidenceStrength
import com.retrovault.domain.evidence.MatchSignal
import com.retrovault.domain.identity.ContainerKind
import com.retrovault.domain.identity.DatSourceId
import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.HashDigests
import com.retrovault.domain.identity.HashValue
import com.retrovault.domain.identity.MediaType
import com.retrovault.domain.identity.RegionCode
import com.retrovault.domain.identity.RegionVocabulary
import com.retrovault.domain.naming.FilenameTokenizer
import com.retrovault.domain.naming.NormalizedTitle
import com.retrovault.domain.naming.ParsedFilename
import com.retrovault.domain.naming.TitleComparison
import com.retrovault.domain.naming.TitleNormalizer
import com.retrovault.domain.naming.TitleSimilarity
import com.retrovault.domain.observation.ArtifactContentRef
import com.retrovault.domain.observation.FileObservation

/**
 * Something the resolver needs before it can continue.
 *
 * The resolver performs no I/O. It states what evidence it needs and the
 * application layer fetches it, which is what keeps every escalation rule
 * unit-testable without a filesystem, a database or Android
 * (ARCHITECTURE.md section 19).
 */
sealed interface EvidenceRequest {
    data class CatalogLookupBySize(val size: Long) : EvidenceRequest

    data class ComputeHashes(
        val ref: ArtifactContentRef,
        val algorithms: Set<HashAlgorithm>,
    ) : EvidenceRequest

    data class CatalogLookupByHash(val hash: HashValue) : EvidenceRequest

    data class CatalogLookupByTitle(val normalizedTitle: NormalizedTitle) : EvidenceRequest
}

/** The answer to an [EvidenceRequest], including its typed failures. */
sealed interface EvidenceResponse {
    data class CatalogRecords(val records: List<DumpRecord>) : EvidenceResponse

    data class HashesComputed(val hashes: HashDigests) : EvidenceResponse

    data class HashesUnavailable(
        val algorithms: Set<HashAlgorithm>,
        val reason: String,
    ) : EvidenceResponse

    data class CatalogUnavailable(val reason: String) : EvidenceResponse
}

/** Where the resolver currently is. */
sealed interface ResolutionStage {
    data class AwaitingEvidence(
        val request: EvidenceRequest,
        val session: ResolutionSession,
    ) : ResolutionStage

    data class Complete(val resolution: ArtifactResolution) : ResolutionStage
}

/** Tunable thresholds. Versioned with the resolver so results stay explainable. */
data class ResolverConfig(
    val similarityThreshold: Int = TitleSimilarity.SIMILARITY_THRESHOLD,
    val ambiguityMargin: Int = TitleSimilarity.AMBIGUITY_MARGIN,
    /**
     * Whether to compute MD5 and SHA1 in the same pass as CRC32.
     *
     * The identification ladder escalates from CRC32 to a cryptographic hash
     * whenever the catalogue offers one, and No-Intro and Redump records
     * essentially always do. Asking for CRC32 alone therefore means reading the
     * file twice; over a storage provider that is the dominant cost of a scan,
     * far outweighing the extra digests computed for the minority of files that
     * never escalate (Constitution section 249).
     *
     * The evidence produced is identical either way: this changes when bytes
     * are read, never what is concluded from them.
     */
    val computeStrongHashesUpFront: Boolean = true,
)

internal enum class Phase {
    SIZE_LOOKUP,
    CRC_COMPUTE,
    CRC_LOOKUP,
    STRONG_COMPUTE,
    STRONG_LOOKUP,
    TITLE_LOOKUP,
}

/**
 * Immutable resolver state.
 *
 * Opaque to callers on purpose: the application drives the machine, it does not
 * reach inside it.
 */
class ResolutionSession private constructor(
    internal val observation: FileObservation,
    internal val coverage: CatalogueCoverage,
    internal val phase: Phase,
    internal val pool: List<DumpRecord>,
    internal val hashes: HashDigests,
    internal val computed: Set<HashAlgorithm>,
    internal val pipelineEvidence: List<Evidence>,
    internal val consultedSources: List<DatSourceId>,
    internal val strictContradictions: List<Evidence>,
    internal val strictCandidates: List<DumpRecord>,
) {
    internal fun next(
        phase: Phase = this.phase,
        pool: List<DumpRecord> = this.pool,
        hashes: HashDigests = this.hashes,
        computed: Set<HashAlgorithm> = this.computed,
        pipelineEvidence: List<Evidence> = this.pipelineEvidence,
        consultedSources: List<DatSourceId> = this.consultedSources,
        strictContradictions: List<Evidence> = this.strictContradictions,
        strictCandidates: List<DumpRecord> = this.strictCandidates,
    ): ResolutionSession = ResolutionSession(
        observation = observation,
        coverage = coverage,
        phase = phase,
        pool = pool,
        hashes = hashes,
        computed = computed,
        pipelineEvidence = pipelineEvidence,
        consultedSources = consultedSources,
        strictContradictions = strictContradictions,
        strictCandidates = strictCandidates,
    )

    internal companion object {
        fun initial(
            observation: FileObservation,
            coverage: CatalogueCoverage,
        ): ResolutionSession = ResolutionSession(
            observation = observation,
            coverage = coverage,
            phase = Phase.SIZE_LOOKUP,
            pool = emptyList(),
            hashes = observation.identityBearingHashes(),
            computed = emptySet(),
            pipelineEvidence = emptyList(),
            consultedSources = emptyList(),
            strictContradictions = emptyList(),
            strictCandidates = emptyList(),
        )
    }
}

/**
 * The deterministic identification ladder.
 *
 * Implements the product invariant of Constitution section 166 and the evidence
 * ordering of ARCHITECTURE.md section 8: size, then CRC32, then a cryptographic
 * hash, then normalized metadata, then bounded textual fallback. A weaker
 * signal never overrides a stronger contradictory one.
 *
 * The governing rule throughout is TESTING_SPEC.md section 1: a missed match is
 * acceptable, a wrong match presented as certain is not.
 */
class ArtifactResolver(private val config: ResolverConfig = ResolverConfig()) {

    /**
     * Starts resolving one observation.
     *
     * @param coverage what the imported datasets are known to describe. The
     * default is [CatalogueCoverage.UNMEASURED], which means "the caller did not
     * look": the resolver then makes no claim about scope and reports plain
     * absence of a match, exactly as it did before coverage existed. A caller
     * that supplies measured coverage gets the stronger distinction between
     * "not listed" and "not covered".
     */
    fun begin(
        observation: FileObservation,
        coverage: CatalogueCoverage = CatalogueCoverage.UNMEASURED,
    ): ResolutionStage {
        val session = ResolutionSession.initial(observation, coverage)
        unsupportedReason(observation)?.let { evidence ->
            return complete(session, ResolutionState.UNSUPPORTED, pipelineEvidence = listOf(evidence))
        }
        // Checked before any bytes are read. Hashing a 1.5 GB UMD image against
        // a cartridge-only catalogue cannot produce a match, and the honest
        // answer - "no dataset covers optical discs" - is already available
        // (Constitution section 24: avoid unnecessary work before avoiding
        // unnecessary I/O).
        outOfScopeEvidence(session)?.let { evidence ->
            return complete(
                session,
                ResolutionState.OUT_OF_CATALOGUE_SCOPE,
                pipelineEvidence = listOf(evidence),
            )
        }
        val size = observation.identityBearingSize()
            ?: return complete(
                session,
                ResolutionState.UNSUPPORTED,
                pipelineEvidence = listOf(
                    Evidence.informational(
                        MatchSignal.Unsupported("no identity-bearing content"),
                        "The file exposes no single artifact to identify.",
                    ),
                ),
            )
        return ResolutionStage.AwaitingEvidence(EvidenceRequest.CatalogLookupBySize(size), session)
    }

    /**
     * Feeds one answer back into the machine.
     *
     * A response that does not match the outstanding request is a programming
     * error in the driver, not a user-facing failure, so it throws.
     */
    fun advance(stage: ResolutionStage.AwaitingEvidence, response: EvidenceResponse): ResolutionStage {
        val session = stage.session
        return when (session.phase) {
            Phase.SIZE_LOOKUP -> onSizeLookup(session, response)
            Phase.CRC_COMPUTE -> onCrcComputed(session, response)
            Phase.CRC_LOOKUP -> onCrcLookup(session, response)
            Phase.STRONG_COMPUTE -> onStrongComputed(session, response)
            Phase.STRONG_LOOKUP -> onStrongLookup(session, response)
            Phase.TITLE_LOOKUP -> onTitleLookup(session, response)
        }
    }

    // -----------------------------------------------------------------------
    // Stage 1: size filtering (Constitution section 151)
    // -----------------------------------------------------------------------

    private fun onSizeLookup(session: ResolutionSession, response: EvidenceResponse): ResolutionStage {
        val records = when (response) {
            is EvidenceResponse.CatalogRecords -> response.records
            is EvidenceResponse.CatalogUnavailable ->
                return startTitleFallback(
                    session.next(
                        pipelineEvidence = session.pipelineEvidence +
                            Evidence.informational(
                                MatchSignal.Unsupported(response.reason),
                                "The catalogue could not be queried by size: ${response.reason}.",
                            ),
                    ),
                )

            else -> throw IllegalStateException("Expected catalogue records for a size lookup, got $response")
        }

        val withSources = session.next(consultedSources = mergeSources(session, records))
        if (records.isEmpty()) {
            // Size filtering is an optimisation, never proof of absence. The
            // user must be able to see that it caused the fallback.
            return startTitleFallback(
                withSources.next(
                    pipelineEvidence = withSources.pipelineEvidence +
                        Evidence.informational(
                            MatchSignal.SizeAbsentFromCatalog,
                            "No catalogue record has this exact size, so cryptographic matching was skipped " +
                                "and identification fell back to filename evidence. This does not prove the " +
                                "file is unknown: headers, padding, trimming or a different dump scope can " +
                                "all change size.",
                        ),
                ),
            )
        }

        return requestCrc(withSources.next(pool = records))
    }

    // -----------------------------------------------------------------------
    // Stage 2: CRC32 as a fast discriminator, never as proof
    // -----------------------------------------------------------------------

    private fun requestCrc(session: ResolutionSession): ResolutionStage {
        session.hashes[HashAlgorithm.CRC32]?.let { existing ->
            // ZIP inspection already produced a CRC32 for the entry; re-reading
            // the archive to recompute it would be wasted I/O.
            return ResolutionStage.AwaitingEvidence(
                EvidenceRequest.CatalogLookupByHash(existing),
                session.next(phase = Phase.CRC_LOOKUP),
            )
        }
        val ref = session.observation.identityBearingRef()
            ?: return startTitleFallback(session)
        val requested = if (config.computeStrongHashesUpFront) {
            setOf(HashAlgorithm.CRC32, HashAlgorithm.MD5, HashAlgorithm.SHA1)
        } else {
            setOf(HashAlgorithm.CRC32)
        }
        return ResolutionStage.AwaitingEvidence(
            EvidenceRequest.ComputeHashes(ref, requested),
            session.next(phase = Phase.CRC_COMPUTE),
        )
    }

    private fun onCrcComputed(session: ResolutionSession, response: EvidenceResponse): ResolutionStage =
        when (response) {
            is EvidenceResponse.HashesComputed -> {
                val crc = response.hashes[HashAlgorithm.CRC32]
                val updated = session.next(
                    hashes = merge(session.hashes, response.hashes),
                    computed = session.computed + response.hashes.algorithms,
                )
                if (crc == null) {
                    startTitleFallback(updated)
                } else {
                    ResolutionStage.AwaitingEvidence(
                        EvidenceRequest.CatalogLookupByHash(crc),
                        updated.next(phase = Phase.CRC_LOOKUP),
                    )
                }
            }

            is EvidenceResponse.HashesUnavailable -> startTitleFallback(
                session.next(
                    pipelineEvidence = session.pipelineEvidence + hashUnavailable(response),
                ),
            )

            else -> throw IllegalStateException("Expected a hash result, got $response")
        }

    private fun onCrcLookup(session: ResolutionSession, response: EvidenceResponse): ResolutionStage {
        val records = catalogRecordsOrFallback(session, response) ?: return startTitleFallback(session)
        val observedSize = session.observation.identityBearingSize()
        val withSources = session.next(consultedSources = mergeSources(session, records))

        if (records.isEmpty()) {
            return startTitleFallback(withSources)
        }

        val sizeAgreeing = records.filter { it.size == null || it.size == observedSize }
        if (sizeAgreeing.isEmpty()) {
            // CRC32 collided with a record of a different length. That is
            // exactly the case CRC32 is too weak to settle, so it produces a
            // contradiction rather than a match.
            val expected = records.first().size ?: -1
            return startTitleFallback(
                withSources.next(
                    strictContradictions = withSources.strictContradictions + Evidence.contradicting(
                        MatchSignal.SizeMismatch(observed = observedSize ?: -1, expected = expected),
                        EvidenceStrength.STRONG,
                        "A catalogue record shares this CRC32 but has a different size " +
                            "($expected bytes vs ${observedSize ?: -1}), so the content is not the same.",
                    ),
                ),
            )
        }

        val strongAlgorithms = buildSet {
            if (sizeAgreeing.any { it.sha1 != null }) add(HashAlgorithm.SHA1)
            if (sizeAgreeing.any { it.md5 != null }) add(HashAlgorithm.MD5)
        }
        val pooled = withSources.next(pool = sizeAgreeing, strictCandidates = sizeAgreeing)

        if (strongAlgorithms.isEmpty()) {
            // Nothing stronger exists to escalate to. CRC32 + size is the
            // strongest available agreement: strong, but not content proof.
            return finalizeStructural(pooled)
        }

        val alreadyKnown = strongAlgorithms.filter { pooled.hashes.contains(it) }.toSet()
        return if (alreadyKnown.containsAll(strongAlgorithms)) {
            onStrongHashesAvailable(pooled.next(phase = Phase.STRONG_LOOKUP), strongAlgorithms)
        } else {
            val ref = pooled.observation.identityBearingRef() ?: return startTitleFallback(pooled)
            ResolutionStage.AwaitingEvidence(
                EvidenceRequest.ComputeHashes(ref, strongAlgorithms),
                pooled.next(phase = Phase.STRONG_COMPUTE),
            )
        }
    }

    // -----------------------------------------------------------------------
    // Stage 3: cryptographic escalation
    // -----------------------------------------------------------------------

    private fun onStrongComputed(session: ResolutionSession, response: EvidenceResponse): ResolutionStage =
        when (response) {
            is EvidenceResponse.HashesComputed -> {
                val updated = session.next(
                    hashes = merge(session.hashes, response.hashes),
                    computed = session.computed + response.hashes.algorithms,
                    phase = Phase.STRONG_LOOKUP,
                )
                onStrongHashesAvailable(updated, response.hashes.algorithms)
            }

            is EvidenceResponse.HashesUnavailable ->
                // The strong hash could not be read. CRC32 + size still agrees,
                // but the file must not be presented as exactly identified.
                finalizeStructural(
                    session.next(
                        pipelineEvidence = session.pipelineEvidence + hashUnavailable(response),
                    ),
                )

            else -> throw IllegalStateException("Expected a hash result, got $response")
        }

    private fun onStrongHashesAvailable(
        session: ResolutionSession,
        algorithms: Set<HashAlgorithm>,
    ): ResolutionStage {
        val lookupHash = listOf(HashAlgorithm.SHA1, HashAlgorithm.MD5)
            .firstOrNull { it in algorithms }
            ?.let { session.hashes[it] }
            ?: return finalizeStructural(session)
        return ResolutionStage.AwaitingEvidence(
            EvidenceRequest.CatalogLookupByHash(lookupHash),
            session.next(phase = Phase.STRONG_LOOKUP),
        )
    }

    private fun onStrongLookup(session: ResolutionSession, response: EvidenceResponse): ResolutionStage {
        val records = catalogRecordsOrFallback(session, response) ?: return finalizeStructural(session)
        val observation = session.observation
        val observedSize = observation.identityBearingSize()
        val withSources = session.next(consultedSources = mergeSources(session, records))

        val agreeing = records.filter { record ->
            HashAlgorithm.entries
                .filter { it.isCryptographicIdentityEvidence }
                .all { algorithm ->
                    val observed = session.hashes[algorithm]
                    val expected = record.hashes[algorithm]
                    observed == null || expected == null || observed == expected
                }
        }

        if (agreeing.isEmpty()) {
            // CRC32 pointed at a record whose cryptographic hash disagrees.
            // Stronger evidence wins, and the result stays unresolved.
            return complete(
                withSources,
                ResolutionState.CONFLICT,
                candidates = withSources.strictCandidates.map { record ->
                    Candidate(
                        record = record,
                        supporting = listOf(crcEvidence(record)),
                        contradicting = listOf(
                            Evidence.contradicting(
                                MatchSignal.HashMismatch(
                                    algorithm = HashAlgorithm.SHA1,
                                    observed = session.hashes[HashAlgorithm.SHA1]?.hex ?: "unavailable",
                                    expected = record.sha1?.hex ?: "unavailable",
                                ),
                                EvidenceStrength.DECISIVE,
                                "CRC32 and size agree with this record but its cryptographic hash does not, " +
                                    "so the file is not this dump.",
                                source = record.source,
                            ),
                        ),
                        score = 0,
                    )
                },
            )
        }

        val sizeAgreeing = agreeing.filter { it.size == null || it.size == observedSize }
        if (sizeAgreeing.isEmpty()) {
            return complete(
                withSources,
                ResolutionState.CONFLICT,
                candidates = agreeing.map { record ->
                    Candidate(
                        record = record,
                        contradicting = listOf(
                            Evidence.contradicting(
                                MatchSignal.SizeMismatch(observedSize ?: -1, record.size ?: -1),
                                EvidenceStrength.STRONG,
                                "The cryptographic hash matches this record but the catalogued size does not.",
                                source = record.source,
                            ),
                        ),
                    )
                },
            )
        }

        return finalizeExact(withSources.next(pool = sizeAgreeing))
    }

    // -----------------------------------------------------------------------
    // Terminal strict outcomes
    // -----------------------------------------------------------------------

    private fun finalizeExact(session: ResolutionSession): ResolutionStage {
        val groups = groupByIdentity(session.pool)
        val verifiedAlgorithms = HashAlgorithm.entries.filter { algorithm ->
            algorithm.isCryptographicIdentityEvidence &&
                session.hashes.contains(algorithm) &&
                session.pool.any { it.hashes[algorithm] == session.hashes[algorithm] }
        }

        if (groups.size > 1) {
            // Identical bytes described as different releases by the catalogue.
            // Rare, and precisely the case where guessing would be harmful.
            val algorithm = verifiedAlgorithms.lastOrNull() ?: HashAlgorithm.SHA1
            return complete(
                session,
                ResolutionState.AMBIGUOUS,
                candidates = groups.map { (_, records) ->
                    buildExactCandidate(session, records, verifiedAlgorithms).copy(
                        contradicting = listOf(
                            Evidence.contradicting(
                                MatchSignal.SharedHashAcrossIdentities(algorithm, groups.size),
                                EvidenceStrength.STRONG,
                                "${groups.size} different catalogue identities share this " +
                                    "${algorithm.canonicalName}. RetroVault cannot tell them apart from " +
                                    "content alone.",
                            ),
                        ),
                    )
                },
            )
        }

        val records = groups.values.first()
        val candidate = buildExactCandidate(session, records, verifiedAlgorithms)
        val state = if (verifiedAlgorithms.size >= 2) {
            ResolutionState.EXACT_MULTI_HASH
        } else {
            ResolutionState.EXACT_HASH
        }
        return complete(session, state, candidates = listOf(candidate), selected = candidate)
    }

    private fun buildExactCandidate(
        session: ResolutionSession,
        records: List<DumpRecord>,
        verifiedAlgorithms: List<HashAlgorithm>,
    ): Candidate {
        val primary = pickPrimary(records, session.observation.extension)
        val supporting = buildList {
            verifiedAlgorithms.forEach { algorithm ->
                add(
                    Evidence.supporting(
                        MatchSignal.HashExact(algorithm),
                        EvidenceStrength.DECISIVE,
                        "${algorithm.canonicalName} matches the catalogued dump exactly.",
                        source = primary.source,
                    ),
                )
            }
            add(
                Evidence.supporting(
                    MatchSignal.SizeExact,
                    EvidenceStrength.MODERATE,
                    "File size matches the catalogued size (${primary.size} bytes).",
                    source = primary.source,
                ),
            )
            val sourceCount = records.map { it.source.id }.distinct().size
            if (sourceCount > 1) {
                add(
                    Evidence.supporting(
                        MatchSignal.CorroboratedByIndependentSources(sourceCount),
                        EvidenceStrength.MODERATE,
                        "$sourceCount independent datasets describe this same release.",
                    ),
                )
            }
        }
        return Candidate(
            record = primary,
            supporting = supporting,
            score = 100,
            corroborating = records.filter { it.id != primary.id },
        )
    }

    private fun finalizeStructural(session: ResolutionSession): ResolutionStage {
        val pool = session.pool
        if (pool.isEmpty()) return startTitleFallback(session)
        val groups = groupByIdentity(pool)
        val candidates = groups.map { (_, records) ->
            val primary = pickPrimary(records, session.observation.extension)
            Candidate(
                record = primary,
                supporting = listOf(
                    crcEvidence(primary),
                    Evidence.supporting(
                        MatchSignal.SizeExact,
                        EvidenceStrength.MODERATE,
                        "File size matches the catalogued size (${primary.size} bytes).",
                        source = primary.source,
                    ),
                ),
                contradicting = listOf(
                    Evidence.informational(
                        MatchSignal.CatalogHasNoCryptographicHash,
                        "This catalogue record carries no MD5 or SHA1, so identity rests on CRC32 and size. " +
                            "A CRC32 collision cannot be ruled out.",
                    ),
                ),
                score = 80,
                corroborating = records.filter { it.id != primary.id },
            )
        }
        return if (candidates.size == 1) {
            complete(
                session,
                ResolutionState.STRUCTURAL_MATCH,
                candidates = candidates,
                selected = candidates.single(),
            )
        } else {
            complete(session, ResolutionState.AMBIGUOUS, candidates = candidates)
        }
    }

    // -----------------------------------------------------------------------
    // Stage 4: bounded textual fallback (ROM_INTELLIGENCE.md section 6/7)
    // -----------------------------------------------------------------------

    private fun startTitleFallback(session: ResolutionSession): ResolutionStage {
        val parsed = FilenameTokenizer.tokenize(session.observation.identityBearingName())
        val normalized = parsed.normalizedTitle
        if (normalized.isBlank) {
            return complete(session, ResolutionState.NO_MATCH)
        }
        return ResolutionStage.AwaitingEvidence(
            EvidenceRequest.CatalogLookupByTitle(normalized),
            session.next(phase = Phase.TITLE_LOOKUP),
        )
    }

    private fun onTitleLookup(session: ResolutionSession, response: EvidenceResponse): ResolutionStage {
        val records = when (response) {
            is EvidenceResponse.CatalogRecords -> response.records
            is EvidenceResponse.CatalogUnavailable -> return complete(session, ResolutionState.NO_MATCH)
            else -> throw IllegalStateException("Expected catalogue records for a title lookup, got $response")
        }
        val observation = session.observation
        val parsed = FilenameTokenizer.tokenize(observation.identityBearingName())
        val observedSize = observation.identityBearingSize()
        val withSources = session.next(consultedSources = mergeSources(session, records))

        val scored = records.mapNotNull { record ->
            scoreTitleCandidate(parsed, record, observedSize, observation.mediaType)
        }
        if (scored.isEmpty()) {
            return complete(withSources, ResolutionState.NO_MATCH)
        }

        // Excluded candidates stay in the result so the user can see what was
        // rejected and why (UX_SPEC.md section 6); they are simply never chosen.
        val allRanked = rank(scored)
        val eligible = scored.filterNot { it.isExcluded }
        if (eligible.isEmpty()) {
            return complete(withSources, ResolutionState.NO_MATCH, candidates = allRanked)
        }

        val byIdentity = eligible.groupBy { it.identityKey }
        val ranked = rank(eligible)
        val best = ranked.first()

        if (byIdentity.size > 1) {
            val runnerUp = ranked.first { it.identityKey != best.identityKey }
            if (best.score - runnerUp.score < config.ambiguityMargin) {
                return complete(withSources, ResolutionState.AMBIGUOUS, candidates = allRanked)
            }
        }

        val track = pickTrack(best, eligible, observation.extension)
            ?: return complete(
                withSources,
                ResolutionState.AMBIGUOUS,
                candidates = allRanked,
                pipelineEvidence = listOf(
                    Evidence.informational(
                        MatchSignal.ArchiveHasMultipleArtifacts(
                            eligible.count { it.identityKey == best.identityKey },
                        ),
                        "This release is made up of several files and the extension does not say " +
                            "which one this is, so RetroVault will not guess.",
                    ),
                ),
            )

        val state = if (qualifiesAsStrongMetadata(track, parsed, observedSize)) {
            ResolutionState.STRONG_METADATA_MATCH
        } else {
            ResolutionState.FUZZY_MATCH
        }
        val merged = mergeCorroborating(track, eligible)
        return complete(
            withSources,
            state,
            candidates = allRanked,
            selected = merged,
        )
    }

    /**
     * Turns one record into a scored candidate, or drops it entirely.
     *
     * Dropping happens only when the titles actively conflict, e.g. differing
     * sequence numbers. Everything else is expressed as evidence so the user
     * can see why a candidate was rejected.
     */
    private fun scoreTitleCandidate(
        parsed: ParsedFilename,
        record: DumpRecord,
        observedSize: Long?,
        observedMedia: MediaType,
    ): Candidate? {
        // Each variant is a different guess at where the title ends and the
        // noise begins. Scoring all of them and keeping the best means an
        // over-eager strip can only fail to help, never mislead.
        val comparison = TitleNormalizer.comparisonVariants(parsed.titleText)
            .map { variant -> TitleSimilarity.compare(variant, record.normalizedTitle) }
            .reduceOrNull(::strongerComparison)
            ?: TitleSimilarity.compare(parsed.normalizedTitle, record.normalizedTitle)
        val supporting = mutableListOf<Evidence>()
        val contradicting = mutableListOf<Evidence>()

        val baseScore = when (comparison) {
            is TitleComparison.Exact -> {
                supporting += Evidence.supporting(
                    MatchSignal.TitleExact,
                    EvidenceStrength.WEAK,
                    "The filename title matches the catalogued title exactly. Filename evidence alone " +
                        "cannot establish content identity.",
                    source = record.source,
                )
                100
            }

            is TitleComparison.Similar -> {
                if (comparison.score < config.similarityThreshold) return null
                supporting += Evidence.supporting(
                    MatchSignal.TitleSimilar(comparison.score),
                    EvidenceStrength.WEAK,
                    "The filename title is ${comparison.score}% similar to the catalogued title.",
                    source = record.source,
                )
                comparison.score
            }

            is TitleComparison.Conflicting -> return null
            is TitleComparison.Unrelated -> return null
        }

        compareRegions(parsed.regions, record.regions, record, supporting, contradicting)
        var penalty = 0
        penalty += compareRevision(parsed.revision, record, contradicting)
        penalty += compareDisc(parsed.discNumber, record, contradicting)
        penalty += compareMedia(observedMedia, record, contradicting)

        val catalogueSize = record.size
        if (observedSize != null && catalogueSize != null) {
            if (catalogueSize == observedSize) {
                supporting += Evidence.supporting(
                    MatchSignal.SizeExact,
                    EvidenceStrength.MODERATE,
                    "File size matches the catalogued size exactly.",
                    source = record.source,
                )
            } else if (catalogueSize > 0) {
                contradicting += Evidence.contradicting(
                    MatchSignal.SizeMismatch(observedSize, catalogueSize),
                    EvidenceStrength.STRONG,
                    "The file is $observedSize bytes but the catalogued dump is $catalogueSize bytes, " +
                        "so this file is not that dump. It may be a modified, trimmed or headered copy.",
                    source = record.source,
                )
            }
        }

        return Candidate(
            record = record,
            supporting = supporting,
            contradicting = contradicting,
            score = (baseScore - penalty).coerceIn(0, 100),
        )
    }

    private fun compareRegions(
        observed: List<RegionCode>,
        expected: List<RegionCode>,
        record: DumpRecord,
        supporting: MutableList<Evidence>,
        contradicting: MutableList<Evidence>,
    ) {
        if (observed.isEmpty() || expected.isEmpty()) return
        val shared = observed.filter { it in expected }
        if (shared.isNotEmpty()) {
            supporting += Evidence.supporting(
                MatchSignal.RegionAgreement(RegionVocabulary.sort(shared)),
                EvidenceStrength.WEAK,
                "Region token ${shared.joinToString(", ") { it.displayToken }} agrees with the catalogue.",
                source = record.source,
            )
        } else {
            contradicting += Evidence.contradicting(
                MatchSignal.RegionConflict(observed, expected),
                EvidenceStrength.MODERATE,
                "The filename says ${observed.joinToString(", ") { it.displayToken }} but this record is " +
                    "${expected.joinToString(", ") { it.displayToken }}. Region is part of identity, " +
                    "not a label.",
                source = record.source,
            )
        }
    }

    /**
     * Weighs a medium disagreement between the file and the catalogue record.
     *
     * Deliberately a penalty rather than an exclusion. One disc exists as
     * `.cue`+`.bin`, `.chd` and `.iso`, and Constitution section 200 holds that
     * a difference of representation does not make something a different
     * release. What it does mean is that a cartridge record should never
     * outrank a disc record when the file on disk is a disc image, which is
     * what the penalty achieves.
     *
     * @return the score penalty this comparison incurs.
     */
    private fun compareMedia(
        observed: MediaType,
        record: DumpRecord,
        contradicting: MutableList<Evidence>,
    ): Int {
        if (observed == MediaType.UNKNOWN || record.mediaType == MediaType.UNKNOWN) return 0
        if (observed == record.mediaType) return 0
        contradicting += Evidence.contradicting(
            MatchSignal.MediaTypeMismatch(observed, record.mediaType),
            EvidenceStrength.MODERATE,
            "This file looks like ${observed.describe} media but the catalogued dump is " +
                "${record.mediaType.describe} media. That can happen when one release is preserved in " +
                "several forms, so it weakens this candidate rather than ruling it out.",
            source = record.source,
        )
        return MEDIA_MISMATCH_PENALTY
    }

    /** @return the score penalty this comparison incurs. */
    private fun compareRevision(
        observed: String?,
        record: DumpRecord,
        contradicting: MutableList<Evidence>,
    ): Int {
        val expected = record.revision
        if (observed == null) return 0
        if (expected == null) {
            contradicting += Evidence.informational(
                MatchSignal.IdentityTokenUnmatched("revision", observed),
                "The filename says revision $observed; this record does not state a revision.",
            )
            return UNMATCHED_TOKEN_PENALTY
        }
        if (!observed.equals(expected, ignoreCase = true)) {
            contradicting += Evidence.contradicting(
                MatchSignal.RevisionConflict(observed, expected),
                EvidenceStrength.MODERATE,
                "The filename says revision $observed but this record is revision $expected.",
                source = record.source,
            )
        }
        return 0
    }

    /** @return the score penalty this comparison incurs. */
    private fun compareDisc(
        observed: Int?,
        record: DumpRecord,
        contradicting: MutableList<Evidence>,
    ): Int {
        val expected = record.discNumber
        if (observed == null) return 0
        if (expected == null) {
            contradicting += Evidence.informational(
                MatchSignal.IdentityTokenUnmatched("disc", observed.toString()),
                "The filename says disc $observed; this record does not state a disc number.",
            )
            return UNMATCHED_TOKEN_PENALTY
        }
        if (observed != expected) {
            contradicting += Evidence.contradicting(
                MatchSignal.DiscNumberConflict(observed, expected),
                EvidenceStrength.MODERATE,
                "The filename says disc $observed but this record is disc $expected.",
                source = record.source,
            )
        }
        return 0
    }

    /**
     * A textual match may only be called "strong metadata" when the catalogue
     * offers no content evidence to contradict. If the record has hashes and we
     * still arrived here, the bytes demonstrably differ from the catalogue and
     * the match stays fuzzy.
     */
    private fun qualifiesAsStrongMetadata(
        candidate: Candidate,
        parsed: ParsedFilename,
        observedSize: Long?,
    ): Boolean =
        candidate.record.hashes.isEmpty &&
            candidate.record.size != null &&
            candidate.record.size == observedSize &&
            parsed.normalizedTitle.key == candidate.record.normalizedTitle.key

    /**
     * Keeps the more useful of two comparisons.
     *
     * A conflict is never overridden: if one variant says the sequence numbers
     * differ, stripping more text off the name must not be allowed to hide that.
     */
    private fun strongerComparison(left: TitleComparison, right: TitleComparison): TitleComparison = when {
        left is TitleComparison.Conflicting -> left
        right is TitleComparison.Conflicting -> right
        left is TitleComparison.Exact || right is TitleComparison.Exact -> TitleComparison.Exact
        left is TitleComparison.Similar && right is TitleComparison.Similar ->
            if (left.score >= right.score) left else right
        left is TitleComparison.Similar -> left
        right is TitleComparison.Similar -> right
        else -> TitleComparison.Unrelated
    }

    private fun mergeCorroborating(best: Candidate, all: List<Candidate>): Candidate {
        val sameIdentity = all.filter { it.identityKey == best.identityKey && it.record.id != best.record.id }
        return best.copy(corroborating = sameIdentity.map { it.record })
    }

    /**
     * Picks which file of a multi-file release a local file corresponds to.
     *
     * A Redump disc release is several `<rom>` entries - one per track, plus a
     * cue sheet - that all share a title. A textual match identifies the
     * release but says nothing about which track is in front of us, so the
     * extension has to settle it. When it cannot, the title is unusable:
     * renaming a `.bin` to the cue sheet's name would be worse than leaving it
     * alone.
     *
     * @return the single applicable record, or `null` when the release cannot
     * be narrowed to one file.
     */
    private fun pickTrack(candidate: Candidate, all: List<Candidate>, extension: String?): Candidate? {
        val siblings = all.filter { it.identityKey == candidate.identityKey }
        val tracks = siblings.distinctBy { it.record.romName }
        if (tracks.size <= 1) return candidate
        if (extension == null) return null
        val byExtension = tracks.filter { it.record.romExtension.equals(extension, ignoreCase = true) }
        return byExtension.singleOrNull()
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    /**
     * Explains why the catalogue has no standing to judge this file, if it has none.
     *
     * Returning `null` means the datasets do cover this kind of artifact, so a
     * later absence of a match is a real (if weak) fact about the file rather
     * than a gap in what was imported.
     */
    private fun outOfScopeEvidence(session: ResolutionSession): Evidence? {
        val observed = session.observation.mediaType
        return when (val assessment = DatasetCompatibility.assess(observed, session.coverage)) {
            is CoverageAssessment.Covered -> null

            is CoverageAssessment.NoDatasets -> Evidence.informational(
                MatchSignal.NoDatasetsImported,
                "No dataset has been imported, so RetroVault has nothing to identify this file against. " +
                    "This says nothing about the file itself.",
            )

            is CoverageAssessment.MediaNotCovered -> Evidence.informational(
                MatchSignal.MediaNotCovered(assessment.observed, assessment.available),
                "This looks like ${article(observed)} ${observed.describe} image, and no imported dataset " +
                    "catalogues ${observed.describe} media" +
                    describeAvailable(assessment.available) +
                    ". RetroVault cannot say what this file is until a dataset covering " +
                    "${observed.describe} media is imported - it is uncatalogued here, not unidentifiable.",
            )
        }
    }

    private fun describeAvailable(available: Set<MediaType>): String =
        if (available.isEmpty()) {
            ""
        } else {
            " (the imported datasets cover " +
                available.sortedBy { it.name }.joinToString(", ") { it.describe } + " media)"
        }

    private fun article(media: MediaType): String =
        if (media.describe.first() in "aeiou") "an" else "a"

    private fun unsupportedReason(observation: FileObservation): Evidence? = when {
        observation.container == ContainerKind.UNSUPPORTED_ARCHIVE -> Evidence.informational(
            MatchSignal.Unsupported("unsupported archive format"),
            "This archive format is not inspected by RetroVault, so its contents cannot be identified.",
        )

        observation.container == ContainerKind.ZIP && observation.candidateArchiveEntries.size > 1 ->
            Evidence.informational(
                MatchSignal.ArchiveHasMultipleArtifacts(observation.candidateArchiveEntries.size),
                "This archive contains ${observation.candidateArchiveEntries.size} artifacts. An archive is a " +
                    "container, not an identity, so RetroVault will not rename it from one of its contents.",
            )

        observation.container == ContainerKind.ZIP && observation.candidateArchiveEntries.isEmpty() ->
            Evidence.informational(
                MatchSignal.Unsupported("archive contains no inspectable artifact"),
                "This archive contains nothing that can be identified.",
            )

        else -> null
    }

    private fun crcEvidence(record: DumpRecord): Evidence = Evidence.supporting(
        MatchSignal.HashExact(HashAlgorithm.CRC32),
        EvidenceStrength.STRONG,
        "CRC32 matches the catalogued dump.",
        source = record.source,
    )

    private fun hashUnavailable(response: EvidenceResponse.HashesUnavailable): Evidence =
        Evidence.informational(
            MatchSignal.HashUnavailable(
                response.algorithms.firstOrNull() ?: HashAlgorithm.CRC32,
                response.reason,
            ),
            "Hashing failed (${response.reason}), so content-level identification was not possible.",
        )

    private fun catalogRecordsOrFallback(
        session: ResolutionSession,
        response: EvidenceResponse,
    ): List<DumpRecord>? = when (response) {
        is EvidenceResponse.CatalogRecords -> response.records
        is EvidenceResponse.CatalogUnavailable -> null
        else -> throw IllegalStateException(
            "Expected catalogue records in phase ${session.phase}, got $response",
        )
    }

    private fun mergeSources(session: ResolutionSession, records: List<DumpRecord>): List<DatSourceId> =
        (session.consultedSources + records.map { it.source.id }).distinct()

    private fun merge(existing: HashDigests, added: HashDigests): HashDigests =
        added.asList().fold(existing) { accumulator, hash -> accumulator.with(hash) }

    /** Stable grouping and ordering so identical inputs always rank identically. */
    private fun groupByIdentity(records: List<DumpRecord>): Map<CanonicalIdentityKey, List<DumpRecord>> =
        records.groupBy { it.canonicalIdentityKey }
            .toSortedMap(compareBy { it.describe() })

    private fun pickPrimary(records: List<DumpRecord>, observedExtension: String?): DumpRecord =
        records.sortedWith(
            compareBy(
                { if (observedExtension != null && it.romExtension.equals(observedExtension, true)) 0 else 1 },
                { it.source.provider },
                { it.source.setName },
                { it.setName },
                { it.romName },
                { it.id.value },
            ),
        ).first()

    private fun rank(candidates: List<Candidate>): List<Candidate> =
        candidates.sortedWith(
            compareByDescending<Candidate> { it.score }
                .thenBy { it.record.source.provider }
                .thenBy { it.record.setName }
                .thenBy { it.record.romName }
                .thenBy { it.record.id.value },
        )

    private fun complete(
        session: ResolutionSession,
        state: ResolutionState,
        candidates: List<Candidate> = emptyList(),
        selected: Candidate? = null,
        pipelineEvidence: List<Evidence> = emptyList(),
    ): ResolutionStage.Complete = ResolutionStage.Complete(
        ArtifactResolution.terminal(
            observationId = session.observation.id,
            state = state,
            candidates = candidates,
            pipelineEvidence = session.pipelineEvidence + session.strictContradictions + pipelineEvidence,
            hashesComputed = session.computed,
            consultedSources = session.consultedSources,
            selected = selected,
            resolverVersion = VERSION,
            tokenizerVersion = FilenameTokenizer.VERSION,
            normalizerVersion = TitleNormalizer.VERSION,
        ),
    )

    companion object {
        /**
         * Constitution section 227: any algorithm that materially affects
         * canonical output carries a version, and results record it.
         */
        const val VERSION: String = "artifact-resolver-v2"

        /**
         * Score cost of an identity token the catalogue record does not state.
         * Larger than [ResolverConfig.ambiguityMargin] so it can break a tie,
         * small enough that it never outranks stronger evidence.
         */
        private const val UNMATCHED_TOKEN_PENALTY = 10

        /**
         * Score cost of a medium disagreement between the file and the record.
         *
         * Heavier than an unstated token because the medium is a stronger
         * discriminator than a missing revision, and still short of exclusion
         * because one release can be preserved in several forms.
         */
        private const val MEDIA_MISMATCH_PENALTY = 20
    }
}

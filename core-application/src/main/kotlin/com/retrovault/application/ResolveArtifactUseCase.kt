package com.retrovault.application

import com.retrovault.domain.observation.FileObservation
import com.retrovault.domain.resolution.ArtifactResolution
import com.retrovault.domain.resolution.ArtifactResolver
import com.retrovault.domain.resolution.EvidenceRequest
import com.retrovault.domain.resolution.EvidenceResponse
import com.retrovault.domain.resolution.ResolutionStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Drives the domain resolver, performing the I/O it asks for.
 *
 * All identity logic lives in [ArtifactResolver]; this class only fetches
 * evidence. That split is what keeps the escalation rules testable without a
 * filesystem and keeps I/O concerns out of the domain
 * (ROM_INTELLIGENCE.md section 21).
 */
class ResolveArtifactUseCase(
    private val catalog: DumpCatalog,
    private val contentSource: ContentSource,
    private val resolver: ArtifactResolver = ArtifactResolver(),
) {
    /**
     * The number of evidence rounds one artifact may take.
     *
     * The ladder is finite by construction; this is a guard against a future
     * change introducing a cycle, not an expected limit.
     */
    private val maxRounds = 16

    suspend fun resolve(observation: FileObservation): ArtifactResolution {
        var stage = resolver.begin(observation)
        var rounds = 0
        while (stage is ResolutionStage.AwaitingEvidence) {
            currentCoroutineContext().ensureActive()
            check(rounds++ < maxRounds) {
                "Resolution did not terminate for ${observation.filename}"
            }
            stage = resolver.advance(stage, respond(stage.request))
        }
        return (stage as ResolutionStage.Complete).resolution
    }

    private suspend fun respond(request: EvidenceRequest): EvidenceResponse = when (request) {
        is EvidenceRequest.CatalogLookupBySize ->
            catalogRecords { catalog.findBySize(request.size) }

        is EvidenceRequest.CatalogLookupByHash ->
            catalogRecords { catalog.findByHash(request.hash) }

        is EvidenceRequest.CatalogLookupByTitle ->
            catalogRecords { catalog.findByNormalizedTitle(request.normalizedTitle) }

        is EvidenceRequest.ComputeHashes ->
            when (val outcome = contentSource.computeHashes(request.ref, request.algorithms)) {
                is Outcome.Success -> EvidenceResponse.HashesComputed(outcome.value)
                is Outcome.Failure -> EvidenceResponse.HashesUnavailable(
                    algorithms = request.algorithms,
                    reason = outcome.failure.message,
                )
            }
    }

    /**
     * A catalogue that cannot answer produces "unavailable", never an empty
     * result: "the index is down" and "nothing matches" must not look the same
     * to the resolver, or a database problem would silently become a library of
     * unmatched files.
     */
    private suspend fun catalogRecords(
        query: suspend () -> List<com.retrovault.domain.catalog.DumpRecord>,
    ): EvidenceResponse = try {
        EvidenceResponse.CatalogRecords(query())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        EvidenceResponse.CatalogUnavailable(failure.message ?: failure::class.simpleName.orEmpty())
    }
}

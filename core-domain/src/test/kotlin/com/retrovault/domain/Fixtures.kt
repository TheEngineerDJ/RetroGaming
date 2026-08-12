package com.retrovault.domain

import com.retrovault.domain.catalog.DatSourceRef
import com.retrovault.domain.catalog.DumpRecord
import com.retrovault.domain.identity.ContainerKind
import com.retrovault.domain.identity.DatSourceId
import com.retrovault.domain.identity.DumpRecordId
import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.HashDigests
import com.retrovault.domain.identity.HashValue
import com.retrovault.domain.identity.ObservationId
import com.retrovault.domain.identity.PlatformName
import com.retrovault.domain.identity.ScanSessionId
import com.retrovault.domain.identity.StorageRef
import com.retrovault.domain.observation.ArchiveEntryObservation
import com.retrovault.domain.observation.FileObservation
import com.retrovault.domain.resolution.ArtifactResolution
import com.retrovault.domain.resolution.ArtifactResolver
import com.retrovault.domain.resolution.EvidenceRequest
import com.retrovault.domain.resolution.EvidenceResponse
import com.retrovault.domain.resolution.ResolutionStage

/**
 * Shared synthetic fixtures.
 *
 * TESTING_SPEC.md section 4: no copyrighted DAT data is committed. Every record
 * here is invented, and every hash is a made-up hex string.
 */
object Fixtures {

    const val SESSION = "session-1"
    val sessionId = ScanSessionId(SESSION)

    val snes = PlatformName("Nintendo - Super Nintendo Entertainment System")

    fun source(
        provider: String = "no_intro",
        version: String = "2026-01-01",
        platform: PlatformName = snes,
    ): DatSourceRef = DatSourceRef(
        id = DatSourceId("$provider:${platform.value}:$version"),
        provider = provider,
        setName = platform.value,
        version = version,
        platform = platform,
        importedAtEpochMillis = 1_700_000_000_000L,
    )

    fun crc(value: String): HashValue = HashValue.of(HashAlgorithm.CRC32, value)
    fun md5(value: String): HashValue = HashValue.of(HashAlgorithm.MD5, value.padStart(32, 'a'))
    fun sha1(value: String): HashValue = HashValue.of(HashAlgorithm.SHA1, value.padStart(40, 'b'))

    fun digests(vararg hashes: HashValue): HashDigests = HashDigests.of(*hashes)

    @Suppress("LongParameterList")
    fun record(
        setName: String,
        romName: String = "$setName.sfc",
        size: Long? = 524_288,
        hashes: HashDigests = HashDigests.EMPTY,
        source: DatSourceRef = source(),
        id: String = "${source.provider}:$setName:$romName",
    ): DumpRecord = DumpRecord.derive(
        id = DumpRecordId(id),
        source = source,
        setName = setName,
        romName = romName,
        size = size,
        hashes = hashes,
    )

    @Suppress("LongParameterList")
    fun observation(
        filename: String,
        size: Long = 524_288,
        container: ContainerKind = ContainerKind.RAW,
        hashes: HashDigests = HashDigests.EMPTY,
        archiveEntries: List<ArchiveEntryObservation> = emptyList(),
        id: String = "observation:$filename",
        directory: String = "content://tree/roms",
    ): FileObservation = FileObservation(
        id = ObservationId(id),
        sessionId = sessionId,
        storageRef = StorageRef("$directory/$filename"),
        parentRef = StorageRef(directory),
        filename = filename,
        relativePath = filename,
        size = size,
        lastModifiedEpochMillis = 1_700_000_000_000L,
        container = container,
        hashes = hashes,
        archiveEntries = archiveEntries,
        observedAtEpochMillis = 1_700_000_000_000L,
    )

    fun zipEntry(
        path: String,
        size: Long = 524_288,
        hashes: HashDigests = HashDigests.EMPTY,
        nested: Boolean = false,
    ): ArchiveEntryObservation = ArchiveEntryObservation(path, size, hashes, nested)
}

/**
 * Drives [ArtifactResolver] to completion against an in-memory catalogue.
 *
 * This is also the executable specification of the catalogue port contract that
 * the real repository must satisfy.
 */
class TestCatalogDriver(
    private val records: List<DumpRecord>,
    /** Hashes the "storage" would return, keyed by entry path (`null` = whole file). */
    private val content: Map<String?, HashDigests> = emptyMap(),
    /** Algorithms that fail to read, to exercise typed hashing failures. */
    private val unreadable: Set<HashAlgorithm> = emptySet(),
    private val catalogUnavailableFor: Set<Class<out EvidenceRequest>> = emptySet(),
    private val resolver: ArtifactResolver = ArtifactResolver(),
) {
    /** Every request the resolver made, in order. Lets tests assert on escalation. */
    val requests: MutableList<EvidenceRequest> = mutableListOf()

    fun resolve(observation: com.retrovault.domain.observation.FileObservation): ArtifactResolution {
        var stage = resolver.begin(observation)
        var guard = 0
        while (stage is ResolutionStage.AwaitingEvidence) {
            check(guard++ < MAX_STEPS) { "Resolver did not terminate; requests=$requests" }
            requests.add(stage.request)
            stage = resolver.advance(stage, respond(stage.request))
        }
        return (stage as ResolutionStage.Complete).resolution
    }

    private fun respond(request: EvidenceRequest): EvidenceResponse {
        if (request.javaClass in catalogUnavailableFor) {
            return EvidenceResponse.CatalogUnavailable("catalogue offline")
        }
        return when (request) {
            // Contract: an exact size match, plus every record the dataset
            // states no size for. An unknown size cannot rule a record out,
            // and excluding those would make them unreachable through the
            // whole ladder.
            is EvidenceRequest.CatalogLookupBySize ->
                EvidenceResponse.CatalogRecords(
                    records.filter { it.size == null || it.size == request.size },
                )

            is EvidenceRequest.CatalogLookupByHash ->
                EvidenceResponse.CatalogRecords(
                    records.filter { it.hashes[request.hash.algorithm] == request.hash },
                )

            is EvidenceRequest.CatalogLookupByTitle -> {
                // Contract: an indexed lookup returns records whose normalized
                // title shares at least one token with the query. Scoring and
                // rejection are the resolver's job, not the index's.
                val queryTokens = request.normalizedTitle.tokens().toSet()
                EvidenceResponse.CatalogRecords(
                    records.filter { record ->
                        record.normalizedTitle.tokens().any { it in queryTokens }
                    },
                )
            }

            is EvidenceRequest.ComputeHashes -> {
                val failing = request.algorithms.filter { it in unreadable }.toSet()
                if (failing.isNotEmpty()) {
                    EvidenceResponse.HashesUnavailable(failing, "simulated read failure")
                } else {
                    val available = content[request.ref.archiveEntryPath] ?: HashDigests.EMPTY
                    val requested = request.algorithms.mapNotNull { available[it] }
                    EvidenceResponse.HashesComputed(HashDigests.of(*requested.toTypedArray()))
                }
            }
        }
    }

    private companion object {
        const val MAX_STEPS = 12
    }
}

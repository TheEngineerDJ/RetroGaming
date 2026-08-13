package com.retrovault.application

import com.retrovault.domain.catalog.CatalogueCoverage
import com.retrovault.domain.identity.ContainerKind
import com.retrovault.domain.identity.ObservationId
import com.retrovault.domain.identity.ScanSessionId
import com.retrovault.domain.observation.FileObservation
import com.retrovault.domain.resolution.ArtifactResolver
import com.retrovault.domain.resolution.ConfidenceLevel
import com.retrovault.domain.resolution.ResolutionState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Progressive scan output (Constitution section 152, UX_SPEC.md section 4). */
sealed interface ScanEvent {
    data class SessionStarted(val session: ScanSessionRecord) : ScanEvent

    data class FileDiscovered(val file: DiscoveredFile, val summary: ScanSummary) : ScanEvent

    data class FileResolved(val resolved: ResolvedObservation, val summary: ScanSummary) : ScanEvent

    /** One file failed. The scan continues (ARCHITECTURE.md section 14). */
    data class FileFailed(
        val relativePath: String,
        val failure: RetroVaultFailure,
        val summary: ScanSummary,
    ) : ScanEvent

    data class SessionFinished(
        val summary: ScanSummary,
        val cancelled: Boolean,
        val persistenceFailure: RetroVaultFailure? = null,
    ) : ScanEvent
}

data class ScanConfig(
    /**
     * Concurrent hashing workers.
     *
     * Bounded because a pathological collection must not create unbounded
     * coroutine, memory or file-descriptor pressure
     * (SECURITY_SPEC.md section 5, Constitution section 249).
     */
    val concurrency: Int = 4,
    /** Observations are committed in batches rather than one transaction per file. */
    val persistBatchSize: Int = 64,
    val namingProfileVersionedId: String = "no-intro@v1",
    /** Extensions never treated as game artifacts. */
    val ignoredExtensions: Set<String> = setOf(
        "txt", "nfo", "md", "jpg", "jpeg", "png", "gif", "bmp", "xml", "json",
        "db", "ini", "cfg", "log", "sav", "srm", "state", "dat",
    ),
) {
    init {
        require(concurrency in 1..32) { "Scan concurrency must be between 1 and 32" }
        require(persistBatchSize >= 1) { "Persist batch size must be at least 1" }
    }
}

/**
 * Walks a location, identifies what it finds, and reports as it goes.
 *
 * Constitution section 152: results appear progressively, the whole library is
 * never held in memory, and nothing waits for the scan to finish.
 *
 * Constitution section 160 is equally important in what this does *not* do: it
 * never renames anything. Scanning produces observations and resolutions;
 * mutation is a separate, validated, user-authorised step.
 */
class ScanLocationUseCase(
    private val walker: DirectoryWalker,
    private val contentSource: ContentSource,
    private val resolveArtifact: ResolveArtifactUseCase,
    private val catalog: DumpCatalog,
    private val observations: ObservationRepository,
    private val sessions: ScanSessionRepository,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val config: ScanConfig = ScanConfig(),
) {

    fun scan(root: StorageLocation): Flow<ScanEvent> = channelFlow {
        val sessionId = ScanSessionId(ids.next("session"))
        val session = ScanSessionRecord(
            id = sessionId,
            rootRef = root.ref,
            rootDisplayName = root.displayName,
            startedAtEpochMillis = clock.nowEpochMillis(),
            finishedAtEpochMillis = null,
            datSourceIds = catalog.sources().map { it.id },
            namingProfileVersionedId = config.namingProfileVersionedId,
            resolverVersion = ArtifactResolver.VERSION,
        )
        sessions.start(session)
        send(ScanEvent.SessionStarted(session))

        // Read once for the whole scan. Coverage is a property of the imported
        // datasets, identical for every file, and it is what lets an unmatched
        // artifact be reported as uncatalogued rather than unidentifiable
        // (Constitution section 174).
        val coverage = catalog.coverage()

        val tally = Tally()
        val buffer = PersistBuffer(observations, config.persistBatchSize)
        var cancelled = true

        try {
            runWorkers(root, sessionId, tally, buffer, coverage)
            cancelled = false
        } finally {
            // The session must be closed out even when the collector walked
            // away mid-scan, so persisted state never claims a scan is running.
            val flushFailure = withContext(NonCancellable) {
                val failure = buffer.flush()
                sessions.finish(sessionId, tally.snapshot(), cancelled)
                failure
            }
            withContext(NonCancellable) {
                send(ScanEvent.SessionFinished(tally.snapshot(), cancelled, flushFailure))
            }
        }
    }

    @Suppress("LongParameterList")
    private suspend fun ProducerScope<ScanEvent>.runWorkers(
        root: StorageLocation,
        sessionId: ScanSessionId,
        tally: Tally,
        buffer: PersistBuffer,
        coverage: CatalogueCoverage,
    ) {
        // Capacity gives the walker a little room to run ahead without letting
        // discovery outpace hashing without bound.
        val work = Channel<DiscoveredFile>(capacity = config.concurrency * 2)

        val producer = launch {
            walker.walk(root).collect { event ->
                when (event) {
                    is WalkEvent.FileFound -> {
                        if (isIgnored(event.file.name)) return@collect
                        tally.discovered()
                        send(ScanEvent.FileDiscovered(event.file, tally.snapshot()))
                        work.send(event.file)
                    }

                    is WalkEvent.DirectoryEntered -> Unit

                    is WalkEvent.Failed -> {
                        tally.failed()
                        send(ScanEvent.FileFailed(event.relativePath, event.failure, tally.snapshot()))
                    }
                }
            }
            work.close()
        }

        val workers = List(config.concurrency) {
            launch {
                for (file in work) {
                    process(file, sessionId, tally, buffer, coverage)
                }
            }
        }

        producer.join()
        workers.joinAll()
    }

    @Suppress("LongParameterList")
    private suspend fun ProducerScope<ScanEvent>.process(
        file: DiscoveredFile,
        sessionId: ScanSessionId,
        tally: Tally,
        buffer: PersistBuffer,
        coverage: CatalogueCoverage,
    ) {
        val container = containerFor(file.name)
        val entries = if (container == ContainerKind.ZIP) {
            when (val inspection = contentSource.inspectArchive(file.ref)) {
                is Outcome.Success -> inspection.value
                is Outcome.Failure -> {
                    tally.failed()
                    send(ScanEvent.FileFailed(file.relativePath, inspection.failure, tally.snapshot()))
                    return
                }
            }
        } else {
            emptyList()
        }

        val observation = FileObservation(
            id = ObservationId(ids.next("observation")),
            sessionId = sessionId,
            storageRef = file.ref,
            parentRef = file.parentRef,
            filename = file.name,
            relativePath = file.relativePath,
            size = file.size,
            lastModifiedEpochMillis = file.lastModifiedEpochMillis,
            container = container,
            archiveEntries = entries,
            observedAtEpochMillis = clock.nowEpochMillis(),
        )

        val resolution = resolveArtifact.resolve(observation, coverage)
        val resolved = ResolvedObservation(observation, resolution)
        tally.resolved(resolution)
        buffer.add(resolved)
        send(ScanEvent.FileResolved(resolved, tally.snapshot()))
    }

    private fun containerFor(filename: String): ContainerKind {
        val extension = filename.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return when (extension) {
            "zip" -> ContainerKind.ZIP
            in unsupportedArchiveExtensions -> ContainerKind.UNSUPPORTED_ARCHIVE
            else -> ContainerKind.RAW
        }
    }

    private fun isIgnored(filename: String): Boolean {
        if (filename.startsWith(".")) return true
        val extension = filename.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return extension in config.ignoredExtensions
    }

    private companion object {
        val unsupportedArchiveExtensions = setOf(
            "7z", "rar", "gz", "bz2", "xz", "tar", "tgz", "lzh", "lha", "arj", "cab",
        )
    }
}

/**
 * Running counts.
 *
 * Several workers report concurrently, so updates are serialized. The snapshot
 * is a value, so an emitted event can never be mutated after the fact.
 */
private class Tally {
    private var summary = ScanSummary()

    @Synchronized
    fun discovered() {
        summary = summary.copy(discovered = summary.discovered + 1)
    }

    @Synchronized
    fun failed() {
        summary = summary.copy(failed = summary.failed + 1)
    }

    @Synchronized
    fun resolved(resolution: com.retrovault.domain.resolution.ArtifactResolution) {
        val hashesComputed = summary.hashesComputed + resolution.hashesComputed.size
        val skipped = summary.hashingSkippedBySizeFilter + if (
            resolution.pipelineEvidence.any {
                it.signal == com.retrovault.domain.evidence.MatchSignal.SizeAbsentFromCatalog
            }
        ) {
            1
        } else {
            0
        }
        summary = summary.copy(
            processed = summary.processed + 1,
            exact = summary.exact + if (resolution.confidence == ConfidenceLevel.EXACT) 1 else 0,
            strong = summary.strong + if (resolution.confidence == ConfidenceLevel.STRONG) 1 else 0,
            reviewRequired = summary.reviewRequired +
                if (resolution.confidence == ConfidenceLevel.PROBABLE) 1 else 0,
            ambiguous = summary.ambiguous +
                if (resolution.state == ResolutionState.AMBIGUOUS ||
                    resolution.state == ResolutionState.CONFLICT
                ) {
                    1
                } else {
                    0
                },
            unmatched = summary.unmatched +
                if (resolution.state == ResolutionState.NO_MATCH ||
                    resolution.state == ResolutionState.UNSUPPORTED
                ) {
                    1
                } else {
                    0
                },
            outOfCatalogueScope = summary.outOfCatalogueScope +
                if (resolution.state == ResolutionState.OUT_OF_CATALOGUE_SCOPE) 1 else 0,
            hashesComputed = hashesComputed,
            hashingSkippedBySizeFilter = skipped,
        )
    }

    @Synchronized
    fun snapshot(): ScanSummary = summary
}

/** Buffers observations so persistence happens in bounded batches. */
private class PersistBuffer(
    private val repository: ObservationRepository,
    private val batchSize: Int,
) {
    private val mutex = Mutex()
    private val pending = mutableListOf<ResolvedObservation>()

    suspend fun add(resolved: ResolvedObservation) {
        val batch = mutex.withLock {
            pending.add(resolved)
            if (pending.size >= batchSize) pending.toList().also { pending.clear() } else null
        }
        batch?.let { repository.saveAll(it) }
    }

    /** @return the failure if the final flush could not be persisted. */
    suspend fun flush(): RetroVaultFailure? {
        val batch = mutex.withLock { pending.toList().also { pending.clear() } }
        if (batch.isEmpty()) return null
        return (repository.saveAll(batch) as? Outcome.Failure)?.failure
    }
}

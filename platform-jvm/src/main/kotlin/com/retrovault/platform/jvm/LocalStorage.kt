package com.retrovault.platform.jvm

import com.retrovault.application.ContentSource
import com.retrovault.application.DirectoryWalker
import com.retrovault.application.DiscoveredFile
import com.retrovault.application.Outcome
import com.retrovault.application.RenameExecutor
import com.retrovault.application.RetroVaultFailure
import com.retrovault.application.StorageLocation
import com.retrovault.application.WalkEvent
import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.HashDigests
import com.retrovault.domain.identity.StorageRef
import com.retrovault.domain.observation.ArchiveEntryObservation
import com.retrovault.domain.observation.ArtifactContentRef
import com.retrovault.domain.rename.ArtifactState
import com.retrovault.domain.rename.DirectorySnapshot
import com.retrovault.io.ArchiveInspection
import com.retrovault.io.CancellationSignal
import com.retrovault.io.ContentFailure
import com.retrovault.io.HashOutcome
import com.retrovault.io.StreamingHasher
import com.retrovault.io.ZipStreamInspector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** A [StorageRef] on this host is an absolute filesystem path. */
internal fun StorageRef.toPath(): Path = Paths.get(value)

internal fun Path.toStorageRef(): StorageRef = StorageRef(toAbsolutePath().normalize().toString())

/**
 * Recursive local traversal.
 *
 * Streams rather than collecting: a directory tree is emitted as it is walked,
 * so a large library produces results immediately
 * (ROM_INTELLIGENCE.md section 15).
 *
 * Symbolic links are not followed. A link loop would otherwise make traversal
 * non-terminating, and a link pointing outside the chosen folder would silently
 * widen the scope the user authorised.
 */
class LocalDirectoryWalker(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxDepth: Int = 32,
) : DirectoryWalker {

    override fun walk(root: StorageLocation): Flow<WalkEvent> = flow {
        val rootPath = root.ref.toPath()
        if (!rootPath.isDirectory()) {
            emit(
                WalkEvent.Failed(
                    relativePath = root.displayName,
                    failure = RetroVaultFailure.UnsupportedStorage("'${root.ref.value}' is not a folder"),
                ),
            )
            return@flow
        }
        walkDirectory(rootPath, rootPath, depth = 0)
    }
        // A modest buffer lets traversal run slightly ahead of the consumer
        // without letting an unbounded queue build up.
        .buffer(capacity = 64)
        .flowOn(dispatcher)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<WalkEvent>.walkDirectory(
        root: Path,
        directory: Path,
        depth: Int,
    ) {
        if (depth > maxDepth) {
            emit(
                WalkEvent.Failed(
                    relativePath = root.relativize(directory).toString(),
                    failure = RetroVaultFailure.UnsupportedStorage("folders nested deeper than $maxDepth"),
                ),
            )
            return
        }

        val children = try {
            Files.newDirectoryStream(directory).use { stream -> stream.sortedBy { it.name } }
        } catch (failure: AccessDeniedException) {
            emit(
                WalkEvent.Failed(
                    relativePath = root.relativize(directory).toString(),
                    failure = RetroVaultFailure.PermissionDenied(directory.toStorageRef()),
                ),
            )
            return
        } catch (failure: IOException) {
            emit(
                WalkEvent.Failed(
                    relativePath = root.relativize(directory).toString(),
                    failure = RetroVaultFailure.UnsupportedStorage(failure.message ?: "unreadable folder"),
                ),
            )
            return
        }

        for (child in children) {
            currentCoroutineContext().ensureActive()
            when {
                Files.isSymbolicLink(child) -> continue

                child.isDirectory() -> {
                    emit(WalkEvent.DirectoryEntered(child.toStorageRef(), root.relativize(child).toString()))
                    walkDirectory(root, child, depth + 1)
                }

                child.isRegularFile() -> emit(
                    WalkEvent.FileFound(
                        DiscoveredFile(
                            ref = child.toStorageRef(),
                            parentRef = directory.toStorageRef(),
                            name = child.name,
                            relativePath = root.relativize(child).toString(),
                            size = child.fileSize(),
                            lastModifiedEpochMillis = child.getLastModifiedTime().toMillis(),
                        ),
                    ),
                )
            }
        }
    }
}

/** Reads local files and archives through the shared streaming implementations. */
class LocalContentSource(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val inspector: ZipStreamInspector = ZipStreamInspector(),
) : ContentSource {

    override suspend fun computeHashes(
        ref: ArtifactContentRef,
        algorithms: Set<HashAlgorithm>,
    ): Outcome<HashDigests> = withContext(dispatcher) {
        val path = ref.storageRef.toPath()
        val cancellation = coroutineCancellation()
        val outcome = try {
            openStream(path).use { stream ->
                if (ref.archiveEntryPath == null) {
                    StreamingHasher.hash(stream, algorithms, cancellation)
                } else {
                    inspector.hashEntry(stream, ref.archiveEntryPath!!, algorithms, cancellation)
                }
            }
        } catch (failure: NoSuchFileException) {
            return@withContext Outcome.failure(RetroVaultFailure.FileNotFound(ref.storageRef))
        } catch (failure: AccessDeniedException) {
            return@withContext Outcome.failure(RetroVaultFailure.PermissionDenied(ref.storageRef))
        } catch (failure: IOException) {
            return@withContext Outcome.failure(
                RetroVaultFailure.HashReadFailure(
                    ref.storageRef,
                    algorithms,
                    failure.message ?: "I/O error",
                ),
            )
        }

        when (outcome) {
            is HashOutcome.Computed -> Outcome.success(outcome.digests)
            is HashOutcome.Failed -> Outcome.failure(
                toFailure(ref.storageRef, algorithms, outcome.failure),
            )
        }
    }

    override suspend fun inspectArchive(ref: StorageRef): Outcome<List<ArchiveEntryObservation>> =
        withContext(dispatcher) {
            val cancellation = coroutineCancellation()
            val inspection = try {
                openStream(ref.toPath()).use { stream ->
                    inspector.inspect(stream, setOf(HashAlgorithm.CRC32), cancellation)
                }
            } catch (failure: NoSuchFileException) {
                return@withContext Outcome.failure(RetroVaultFailure.FileNotFound(ref))
            } catch (failure: AccessDeniedException) {
                return@withContext Outcome.failure(RetroVaultFailure.PermissionDenied(ref))
            } catch (failure: IOException) {
                return@withContext Outcome.failure(
                    RetroVaultFailure.ArchiveUnreadable(ref, failure.message ?: "I/O error"),
                )
            }

            when (inspection) {
                is ArchiveInspection.Inspected -> Outcome.success(inspection.entries)
                is ArchiveInspection.Failed -> Outcome.failure(
                    RetroVaultFailure.ArchiveUnreadable(ref, inspection.failure.message),
                )
            }
        }

    override suspend fun stat(ref: StorageRef): Outcome<ArtifactState> = withContext(dispatcher) {
        val path = ref.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return@withContext Outcome.success(
                ArtifactState(ref, exists = false, filename = null, size = null, writable = false),
            )
        }
        try {
            Outcome.success(
                ArtifactState(
                    storageRef = ref,
                    exists = true,
                    filename = path.name,
                    size = path.fileSize(),
                    writable = Files.isWritable(path) && Files.isWritable(path.parent),
                ),
            )
        } catch (failure: IOException) {
            Outcome.failure(RetroVaultFailure.UnsupportedStorage(failure.message ?: "unreadable file"))
        }
    }

    override suspend fun listNames(directory: StorageRef): Outcome<DirectorySnapshot> =
        withContext(dispatcher) {
            try {
                val names = Files.newDirectoryStream(directory.toPath()).use { stream ->
                    stream.mapTo(mutableSetOf()) { it.name }
                }
                Outcome.success(DirectorySnapshot(directory, names))
            } catch (failure: NoSuchFileException) {
                Outcome.failure(RetroVaultFailure.FileNotFound(directory))
            } catch (failure: AccessDeniedException) {
                Outcome.failure(RetroVaultFailure.PermissionDenied(directory))
            } catch (failure: IOException) {
                Outcome.failure(RetroVaultFailure.UnsupportedStorage(failure.message ?: "unreadable folder"))
            }
        }

    private fun openStream(path: Path): InputStream = Files.newInputStream(path).buffered()

    private fun toFailure(
        ref: StorageRef,
        algorithms: Set<HashAlgorithm>,
        failure: ContentFailure,
    ): RetroVaultFailure = when (failure) {
        is ContentFailure.PermissionDenied -> RetroVaultFailure.PermissionDenied(ref)
        is ContentFailure.NotFound -> RetroVaultFailure.FileNotFound(ref)
        else -> RetroVaultFailure.HashReadFailure(ref, algorithms, failure.message)
    }
}

/**
 * Renames a file in place.
 *
 * Never moves across directories, never creates directories and never
 * overwrites: the destination is checked first and the copy option that would
 * replace an existing file is deliberately not used
 * (SECURITY_SPEC.md section 2).
 */
class LocalRenameExecutor(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RenameExecutor {

    override suspend fun rename(ref: StorageRef, newName: String): Outcome<StorageRef> =
        withContext(dispatcher) {
            val source = ref.toPath()
            if (newName.contains('/') || newName.contains('\\') || newName == "." || newName == "..") {
                return@withContext Outcome.failure(
                    RetroVaultFailure.RenameFailed(ref, "'$newName' is not a plain filename"),
                )
            }
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                return@withContext Outcome.failure(RetroVaultFailure.FileNotFound(ref))
            }

            val destination = source.resolveSibling(newName)
            val caseOnly = source.name.equals(newName, ignoreCase = true) && source.name != newName
            if (!caseOnly && Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                return@withContext Outcome.failure(
                    RetroVaultFailure.RenameFailed(ref, "'$newName' already exists"),
                )
            }

            try {
                Files.move(source, destination)
                Outcome.success(destination.toStorageRef())
            } catch (failure: AccessDeniedException) {
                Outcome.failure(RetroVaultFailure.PermissionDenied(ref))
            } catch (failure: NoSuchFileException) {
                Outcome.failure(RetroVaultFailure.FileNotFound(ref))
            } catch (failure: IOException) {
                Outcome.failure(RetroVaultFailure.RenameFailed(ref, failure.message ?: "I/O error"))
            }
        }
}

/** Bridges coroutine cancellation into the synchronous streaming code. */
private suspend fun coroutineCancellation(): CancellationSignal {
    val context = currentCoroutineContext()
    return CancellationSignal {
        if (!context.isActive) throw kotlinx.coroutines.CancellationException("scan cancelled")
    }
}

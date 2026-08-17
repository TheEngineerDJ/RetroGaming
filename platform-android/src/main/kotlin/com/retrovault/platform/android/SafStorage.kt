package com.retrovault.platform.android

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
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
import kotlinx.coroutines.CancellationException
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
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * A [StorageRef] on Android is a document URI, as a string.
 *
 * The domain never parses it; only this module does.
 */
internal fun StorageRef.toUri(): Uri = Uri.parse(value)

internal fun Uri.toStorageRef(): StorageRef = StorageRef(toString())

/**
 * Recursive traversal over `DocumentsContract` cursors.
 *
 * Constitution section 161 and ROM_INTELLIGENCE.md section 6: cursor traversal
 * is the preferred strategy because the obvious alternative - a `DocumentFile`
 * per entry - issues several IPC round trips per file and makes scanning a
 * large library unacceptably slow. Here one query returns a whole directory.
 *
 * Provider behaviour varies, so an unreadable directory becomes an event
 * rather than an exception and the scan continues elsewhere.
 */
class SafDirectoryWalker(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxDepth: Int = 32,
) : DirectoryWalker {

    private val resolver: ContentResolver get() = context.contentResolver

    override fun walk(root: StorageLocation): Flow<WalkEvent> = flow {
        val treeUri = root.ref.toUri()
        val rootDocumentId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (failure: IllegalArgumentException) {
            emit(
                WalkEvent.Failed(
                    root.displayName,
                    RetroVaultFailure.UnsupportedStorage("this is not a folder RetroVault can read"),
                ),
            )
            return@flow
        }
        // A provider may expose the same document under two parents, and some
        // expose a folder inside itself. Without this guard the walk would
        // recurse until the depth limit and report the same files repeatedly,
        // producing a rename plan with duplicate entries for one file.
        walkDirectory(treeUri, rootDocumentId, relativePath = "", depth = 0, visited = hashSetOf(rootDocumentId))
    }
        .buffer(capacity = 64)
        .flowOn(dispatcher)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<WalkEvent>.walkDirectory(
        treeUri: Uri,
        documentId: String,
        relativePath: String,
        depth: Int,
        visited: MutableSet<String>,
    ) {
        if (depth > maxDepth) {
            emit(
                WalkEvent.Failed(
                    relativePath,
                    RetroVaultFailure.UnsupportedStorage("folders nested deeper than $maxDepth"),
                ),
            )
            return
        }

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val cursor = try {
            resolver.query(childrenUri, PROJECTION, null, null, null)
        } catch (failure: SecurityException) {
            emit(WalkEvent.Failed(relativePath, RetroVaultFailure.PermissionDenied(childrenUri.toStorageRef())))
            return
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            emit(
                WalkEvent.Failed(
                    relativePath,
                    RetroVaultFailure.UnsupportedStorage(failure.message ?: "the folder could not be read"),
                ),
            )
            return
        }

        if (cursor == null) {
            emit(
                WalkEvent.Failed(
                    relativePath,
                    RetroVaultFailure.UnsupportedStorage("the storage provider returned nothing"),
                ),
            )
            return
        }

        // Directories are collected first and descended into after the cursor
        // closes, so a deep tree never holds one open cursor per level.
        val directories = mutableListOf<Pair<String, String>>()
        cursor.use { rows ->
            // DOCUMENT_ID, DISPLAY_NAME and MIME_TYPE are required of every
            // DocumentsProvider, and without MIME_TYPE a folder cannot be told
            // from a file, so their absence is fatal for this directory.
            // SIZE and LAST_MODIFIED are optional in the contract and several
            // third-party providers omit them. They are corroborating
            // metadata, not identity, so an absent column reads as unknown
            // rather than abandoning an otherwise readable folder.
            val idColumn = rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = rows.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedColumn = rows.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (rows.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val childId = rows.getString(idColumn) ?: continue
                val name = rows.getString(nameColumn) ?: continue
                val mimeType = rows.getString(mimeColumn)
                val childPath = if (relativePath.isEmpty()) name else "$relativePath/$name"

                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    directories += childId to childPath
                    continue
                }

                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                emit(
                    WalkEvent.FileFound(
                        DiscoveredFile(
                            ref = childUri.toStorageRef(),
                            parentRef = DocumentsContract
                                .buildDocumentUriUsingTree(treeUri, documentId)
                                .toStorageRef(),
                            name = name,
                            relativePath = childPath,
                            size = rows.longOrNull(sizeColumn) ?: sizeOf(childUri),
                            lastModifiedEpochMillis = rows.longOrNull(modifiedColumn),
                        ),
                    ),
                )
            }
        }

        for ((childId, childPath) in directories) {
            currentCoroutineContext().ensureActive()
            if (!visited.add(childId)) continue
            emit(
                WalkEvent.DirectoryEntered(
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, childId).toStorageRef(),
                    childPath,
                ),
            )
            walkDirectory(treeUri, childId, childPath, depth + 1, visited)
        }
    }

    /**
     * The size of one document, asked for directly.
     *
     * `COLUMN_SIZE` is optional in the DocumentsContract and some providers
     * omit it from a directory listing. Substituting zero would be a
     * fabrication with real consequences: size filtering is the first
     * identification stage, a size that matches no catalogue record skips
     * cryptographic matching entirely (Constitution section 151), and the user
     * would then be told "no catalogue record has this exact size" about a size
     * nothing ever measured.
     *
     * So the cheap path is tried first - one cursor for a whole directory - and
     * only a file the listing could not size costs an extra query. On providers
     * that report sizes, which is the common case, nothing extra happens at all.
     *
     * A provider that will not answer even then leaves the size genuinely
     * unknown; zero is returned because the port cannot yet express that, and
     * the resolver's fallback treats it as a size no record matches.
     */
    private fun sizeOf(documentUri: Uri): Long = try {
        resolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_SIZE),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) 0L else cursor.longOrNull(0) ?: 0L
        }
    } catch (failure: SecurityException) {
        0L
    } catch (failure: IllegalArgumentException) {
        0L
    }

    private fun Cursor.longOrNull(column: Int): Long? =
        if (column < 0 || isNull(column)) null else getLong(column)

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

/** Reads document content through the resolver, using the shared streaming code. */
class SafContentSource(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ContentSource {

    // An implementation detail, not a constructor parameter. Exposing it would
    // put a core-io type in this class's public signature, and core-io is an
    // `implementation` dependency - so every caller would need it on their own
    // compile classpath just to invoke the constructor.
    private val inspector = ZipStreamInspector()

    private val resolver: ContentResolver get() = context.contentResolver

    override suspend fun computeHashes(
        ref: ArtifactContentRef,
        algorithms: Set<HashAlgorithm>,
    ): Outcome<HashDigests> = withContext(dispatcher) {
        val cancellation = coroutineCancellation()
        val outcome = try {
            openStream(ref.storageRef).use { stream ->
                val entryPath = ref.archiveEntryPath
                if (entryPath == null) {
                    // A header is skipped before hashing so the digest is of
                    // the dump the catalogue records, not of the file that
                    // happens to contain it (Constitution section 200).
                    stream.skipFully(ref.byteOffset)
                    StreamingHasher.hash(stream, algorithms, cancellation)
                } else {
                    inspector.hashEntry(stream, entryPath, algorithms, cancellation)
                }
            }
        } catch (failure: FileNotFoundException) {
            return@withContext Outcome.failure(RetroVaultFailure.FileNotFound(ref.storageRef))
        } catch (failure: SecurityException) {
            return@withContext Outcome.failure(RetroVaultFailure.PermissionDenied(ref.storageRef))
        } catch (failure: IOException) {
            return@withContext Outcome.failure(
                RetroVaultFailure.HashReadFailure(ref.storageRef, algorithms, failure.message ?: "I/O error"),
            )
        }

        when (outcome) {
            is HashOutcome.Computed -> Outcome.success(outcome.digests)
            is HashOutcome.Failed -> Outcome.failure(toFailure(ref.storageRef, algorithms, outcome.failure))
        }
    }

    override suspend fun inspectArchive(ref: StorageRef): Outcome<List<ArchiveEntryObservation>> =
        withContext(dispatcher) {
            val cancellation = coroutineCancellation()
            val inspection = try {
                openStream(ref).use { stream ->
                    inspector.inspect(stream, setOf(HashAlgorithm.CRC32), cancellation)
                }
            } catch (failure: FileNotFoundException) {
                return@withContext Outcome.failure(RetroVaultFailure.FileNotFound(ref))
            } catch (failure: SecurityException) {
                return@withContext Outcome.failure(RetroVaultFailure.PermissionDenied(ref))
            } catch (failure: IOException) {
                return@withContext Outcome.failure(
                    RetroVaultFailure.ArchiveUnreadable(ref, failure.message ?: "I/O error"),
                )
            }

            when (inspection) {
                is ArchiveInspection.Inspected -> Outcome.success(inspection.entries)
                is ArchiveInspection.Failed ->
                    Outcome.failure(RetroVaultFailure.ArchiveUnreadable(ref, inspection.failure.message))
            }
        }

    override suspend fun readPrefix(ref: StorageRef, byteCount: Int): Outcome<ByteArray> =
        withContext(dispatcher) {
            try {
                openStream(ref).use { stream ->
                    val buffer = ByteArray(byteCount)
                    var read = 0
                    while (read < byteCount) {
                        val count = stream.read(buffer, read, byteCount - read)
                        if (count < 0) break
                        read += count
                    }
                    Outcome.success(buffer.copyOf(read))
                }
            } catch (failure: FileNotFoundException) {
                Outcome.failure(RetroVaultFailure.FileNotFound(ref))
            } catch (failure: SecurityException) {
                Outcome.failure(RetroVaultFailure.PermissionDenied(ref))
            } catch (failure: IOException) {
                Outcome.failure(
                    RetroVaultFailure.UnsupportedStorage(failure.message ?: "the file could not be read"),
                )
            }
        }

    override suspend fun stat(ref: StorageRef): Outcome<ArtifactState> = withContext(dispatcher) {
        val uri = ref.toUri()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        try {
            resolver.query(uri, projection, null, null, null).use { cursor ->
                // A null cursor is the provider declining to answer, which is
                // not the same fact as an empty one. Reporting it as absence
                // would let a provider outage read as "your file is gone" -
                // exactly what ArtifactState.readable exists to prevent - and
                // the validator and reconciler both derive that from a failed
                // stat, so the failure has to be reported as a failure.
                if (cursor == null) {
                    return@withContext Outcome.failure(
                        RetroVaultFailure.UnsupportedStorage("the storage provider did not answer for this file"),
                    )
                }
                // An empty cursor *is* an answer: the provider looked and the
                // document is not there.
                if (!cursor.moveToFirst()) {
                    return@withContext Outcome.success(
                        ArtifactState(ref, exists = false, filename = null, size = null, writable = false),
                    )
                }
                // Column positions are read from the returned cursor rather
                // than assumed from the projection: a provider may return
                // fewer columns than were asked for.
                val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val flagsColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                // FLAGS is optional. When it is absent, rename support is
                // unknown rather than denied: treating unknown as "cannot
                // rename" would make the app inert on such a provider, while
                // treating it as "may rename" costs at most one typed,
                // journalled rename failure that the user can see and retry.
                val flags = if (flagsColumn < 0) {
                    DocumentsContract.Document.FLAG_SUPPORTS_RENAME.toLong()
                } else if (cursor.isNull(flagsColumn)) {
                    0L
                } else {
                    cursor.getLong(flagsColumn)
                }
                Outcome.success(
                    ArtifactState(
                        storageRef = ref,
                        exists = true,
                        filename = if (nameColumn < 0) null else cursor.getString(nameColumn),
                        size = if (sizeColumn < 0 || cursor.isNull(sizeColumn)) null else cursor.getLong(sizeColumn),
                        // Rename support is a per-document capability, not a
                        // property of the volume, so it is read per file.
                        writable = flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME.toLong() != 0L,
                    ),
                )
            }
        } catch (failure: SecurityException) {
            Outcome.failure(RetroVaultFailure.PermissionDenied(ref))
        } catch (failure: IllegalArgumentException) {
            // The URI could not be addressed - a stale document id, or a
            // provider that is no longer installed. RetroVault failed to look;
            // it did not observe an absence.
            Outcome.failure(
                RetroVaultFailure.UnsupportedStorage(failure.message ?: "this file could not be addressed"),
            )
        }
    }

    override suspend fun listNames(directory: StorageRef): Outcome<DirectorySnapshot> =
        withContext(dispatcher) {
            val uri = directory.toUri()
            val childrenUri = try {
                DocumentsContract.buildChildDocumentsUriUsingTree(
                    uri,
                    DocumentsContract.getDocumentId(uri),
                )
            } catch (failure: IllegalArgumentException) {
                return@withContext Outcome.failure(
                    RetroVaultFailure.UnsupportedStorage("the folder could not be addressed"),
                )
            }
            try {
                val names = mutableSetOf<String>()
                resolver.query(
                    childrenUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null,
                ).use { cursor ->
                    if (cursor == null) {
                        return@withContext Outcome.failure(
                            RetroVaultFailure.UnsupportedStorage("the folder could not be listed"),
                        )
                    }
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.let(names::add)
                    }
                }
                Outcome.success(DirectorySnapshot(directory, names))
            } catch (failure: SecurityException) {
                Outcome.failure(RetroVaultFailure.PermissionDenied(directory))
            } catch (failure: IllegalArgumentException) {
                Outcome.failure(
                    RetroVaultFailure.UnsupportedStorage(failure.message ?: "the folder could not be listed"),
                )
            }
        }

    private fun openStream(ref: StorageRef): InputStream =
        resolver.openInputStream(ref.toUri())?.buffered()
            ?: throw FileNotFoundException("the provider returned no stream for ${ref.value}")

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
 * Renames a document in place.
 *
 * `DocumentsContract.renameDocument` is the only mutation this application
 * performs. It cannot move a file, and providers that do not support renaming
 * report that through the document flags, which validation checks first.
 */
class SafRenameExecutor(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RenameExecutor {

    override suspend fun rename(ref: StorageRef, newName: String): Outcome<StorageRef> =
        withContext(dispatcher) {
            if (newName.contains('/') || newName.contains('\\') || newName == "." || newName == "..") {
                return@withContext Outcome.failure(
                    RetroVaultFailure.RenameFailed(ref, "'$newName' is not a plain filename"),
                )
            }
            try {
                val renamed = DocumentsContract.renameDocument(
                    context.contentResolver,
                    ref.toUri(),
                    newName,
                )
                // Some providers return null on success and keep the original
                // URI valid. Treating null as failure would report a completed
                // rename as failed, so the original ref is carried forward and
                // reconciliation settles any doubt.
                Outcome.success(renamed?.toStorageRef() ?: ref)
            } catch (failure: FileNotFoundException) {
                Outcome.failure(RetroVaultFailure.FileNotFound(ref))
            } catch (failure: SecurityException) {
                Outcome.failure(RetroVaultFailure.PermissionDenied(ref))
            } catch (failure: UnsupportedOperationException) {
                Outcome.failure(
                    RetroVaultFailure.RenameFailed(ref, "this storage location does not support renaming"),
                )
            } catch (failure: IllegalStateException) {
                Outcome.failure(
                    RetroVaultFailure.RenameFailed(ref, failure.message ?: "the provider rejected the rename"),
                )
            } catch (failure: IllegalArgumentException) {
                // `renameDocument` rejects a URI it cannot parse this way. It
                // must become a typed, journalled failure like every other
                // rename outcome: an uncaught throw here would escape mid-batch
                // with operations already marked EXECUTING.
                Outcome.failure(
                    RetroVaultFailure.RenameFailed(ref, failure.message ?: "the file could not be addressed"),
                )
            } catch (failure: IOException) {
                Outcome.failure(RetroVaultFailure.RenameFailed(ref, failure.message ?: "I/O error"))
            }
        }
}

/**
 * Skips exactly [count] bytes, or throws.
 *
 * `InputStream.skip` may skip fewer bytes than asked without explanation, and a
 * provider stream is more likely to do so than a local file. A short skip would
 * hash from the wrong offset and produce a digest matching nothing, with no
 * visible cause.
 */
private fun InputStream.skipFully(count: Long) {
    var remaining = count
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
            continue
        }
        if (read() < 0) throw IOException("the file ended before its header did")
        remaining--
    }
}

/** Bridges coroutine cancellation into the synchronous streaming code. */
private suspend fun coroutineCancellation(): CancellationSignal {
    val context = currentCoroutineContext()
    return CancellationSignal {
        if (!context.isActive) throw CancellationException("scan cancelled")
    }
}

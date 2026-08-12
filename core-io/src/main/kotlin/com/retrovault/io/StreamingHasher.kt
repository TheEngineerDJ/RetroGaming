package com.retrovault.io

import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.HashDigests
import com.retrovault.domain.identity.HashValue
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Cooperative cancellation for long reads.
 *
 * The I/O layer must not depend on coroutines, but it must still stop promptly
 * when a scan is cancelled (ENGINEERING_SPEC.md section 9). The application
 * supplies an implementation backed by the coroutine context.
 */
fun interface CancellationSignal {
    /** Throws if the work should stop. */
    fun check()

    companion object {
        val NONE = CancellationSignal { }
    }
}

/** Why content could not be read. */
sealed interface ContentFailure {
    val message: String

    data object PermissionDenied : ContentFailure {
        override val message: String get() = "The storage provider refused access."
    }

    data object NotFound : ContentFailure {
        override val message: String get() = "The file no longer exists."
    }

    data class ReadFailed(val detail: String) : ContentFailure {
        override val message: String get() = "The file could not be read: $detail"
    }

    data class UnsupportedAlgorithm(val algorithm: HashAlgorithm) : ContentFailure {
        override val message: String get() = "${algorithm.canonicalName} is not available on this platform."
    }
}

/** A read either produced digests or failed for a stated reason. */
sealed interface HashOutcome {
    data class Computed(val digests: HashDigests) : HashOutcome

    data class Failed(val failure: ContentFailure) : HashOutcome
}

/**
 * Computes several digests in a single streaming pass.
 *
 * Constitution section 149: large files are never loaded into memory, multiple
 * hashes avoid repeated reads, and a failed hash produces an explicit error
 * state rather than a silently partial digest.
 */
object StreamingHasher {

    /** 64 KiB balances syscall overhead against bounded memory per worker. */
    const val BUFFER_SIZE: Int = 64 * 1024

    /**
     * Reads [input] to the end, computing every requested digest.
     *
     * The stream is not closed here; the caller owns it.
     */
    fun hash(
        input: InputStream,
        algorithms: Set<HashAlgorithm>,
        cancellation: CancellationSignal = CancellationSignal.NONE,
    ): HashOutcome {
        if (algorithms.isEmpty()) return HashOutcome.Computed(HashDigests.EMPTY)

        val crc = if (HashAlgorithm.CRC32 in algorithms) CRC32() else null
        val digests = mutableMapOf<HashAlgorithm, MessageDigest>()
        for (algorithm in algorithms) {
            val javaName = when (algorithm) {
                HashAlgorithm.MD5 -> "MD5"
                HashAlgorithm.SHA1 -> "SHA-1"
                HashAlgorithm.CRC32 -> continue
            }
            digests[algorithm] = runCatching { MessageDigest.getInstance(javaName) }
                .getOrElse { return HashOutcome.Failed(ContentFailure.UnsupportedAlgorithm(algorithm)) }
        }

        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                cancellation.check()
                val read = input.read(buffer)
                if (read < 0) break
                crc?.update(buffer, 0, read)
                digests.values.forEach { it.update(buffer, 0, read) }
            }
        } catch (failure: IOException) {
            return HashOutcome.Failed(ContentFailure.ReadFailed(failure.message ?: "I/O error"))
        } catch (failure: SecurityException) {
            return HashOutcome.Failed(ContentFailure.PermissionDenied)
        }

        val results = buildList {
            crc?.let { add(HashValue.of(HashAlgorithm.CRC32, "%08x".format(it.value))) }
            digests.forEach { (algorithm, digest) ->
                add(HashValue.of(algorithm, digest.digest().toHex()))
            }
        }
        return HashOutcome.Computed(HashDigests.of(*results.toTypedArray()))
    }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }

    private const val HEX = "0123456789abcdef"
}

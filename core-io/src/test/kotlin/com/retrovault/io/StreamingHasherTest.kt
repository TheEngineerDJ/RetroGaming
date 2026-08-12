package com.retrovault.io

import com.retrovault.domain.identity.HashAlgorithm
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StreamingHasherTest {

    private val all = setOf(HashAlgorithm.CRC32, HashAlgorithm.MD5, HashAlgorithm.SHA1)

    private fun hash(bytes: ByteArray, algorithms: Set<HashAlgorithm> = all): HashOutcome =
        StreamingHasher.hash(ByteArrayInputStream(bytes), algorithms)

    @Test
    fun `known vectors are computed correctly`() {
        val outcome = hash("abc".toByteArray())

        val computed = assertIs<HashOutcome.Computed>(outcome)
        assertEquals("352441c2", computed.digests[HashAlgorithm.CRC32]?.hex)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", computed.digests[HashAlgorithm.MD5]?.hex)
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", computed.digests[HashAlgorithm.SHA1]?.hex)
    }

    @Test
    fun `an empty stream hashes to the empty-input digests`() {
        val computed = assertIs<HashOutcome.Computed>(hash(ByteArray(0)))

        assertEquals("00000000", computed.digests[HashAlgorithm.CRC32]?.hex)
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", computed.digests[HashAlgorithm.MD5]?.hex)
    }

    @Test
    fun `crc32 values are zero padded to eight characters`() {
        // A payload whose CRC32 starts with a zero nibble would otherwise be
        // written short and never match a catalogued value.
        val computed = assertIs<HashOutcome.Computed>(hash(ByteArray(0), setOf(HashAlgorithm.CRC32)))
        assertEquals(8, computed.digests[HashAlgorithm.CRC32]?.hex?.length)
    }

    @Test
    fun `only requested algorithms are computed`() {
        val computed = assertIs<HashOutcome.Computed>(hash("abc".toByteArray(), setOf(HashAlgorithm.CRC32)))

        assertEquals(setOf(HashAlgorithm.CRC32), computed.digests.algorithms)
    }

    @Test
    fun `requesting nothing computes nothing`() {
        val computed = assertIs<HashOutcome.Computed>(hash("abc".toByteArray(), emptySet()))
        assertTrue(computed.digests.isEmpty)
    }

    @Test
    fun `data larger than the buffer hashes identically to one chunk`() {
        val payload = ByteArray(StreamingHasher.BUFFER_SIZE * 3 + 17) { (it % 251).toByte() }

        val streamed = assertIs<HashOutcome.Computed>(hash(payload))
        val singleShot = assertIs<HashOutcome.Computed>(
            StreamingHasher.hash(ByteArrayInputStream(payload), all),
        )

        assertEquals(singleShot.digests, streamed.digests)
    }

    @Test
    fun `a read failure produces a typed failure, not a partial digest`() {
        val failing = object : InputStream() {
            private var served = 0
            override fun read(): Int = throw IOException("device disconnected")
            override fun read(destination: ByteArray, offset: Int, length: Int): Int {
                if (served++ == 0) {
                    destination[offset] = 1
                    return 1
                }
                throw IOException("device disconnected")
            }
        }

        val outcome = StreamingHasher.hash(failing, all)

        val failed = assertIs<HashOutcome.Failed>(outcome)
        assertIs<ContentFailure.ReadFailed>(failed.failure)
    }

    @Test
    fun `permission failures are reported distinctly`() {
        val denied = object : InputStream() {
            override fun read(): Int = throw SecurityException("denied")
            override fun read(destination: ByteArray, offset: Int, length: Int): Int =
                throw SecurityException("denied")
        }

        val failed = assertIs<HashOutcome.Failed>(StreamingHasher.hash(denied, all))
        assertEquals(ContentFailure.PermissionDenied, failed.failure)
    }

    @Test
    fun `cancellation stops the read`() {
        val payload = ByteArray(StreamingHasher.BUFFER_SIZE * 10)
        var checks = 0
        val cancelAfterTwo = CancellationSignal {
            if (checks++ >= 2) throw IllegalStateException("cancelled")
        }

        val failure = runCatching {
            StreamingHasher.hash(ByteArrayInputStream(payload), all, cancelAfterTwo)
        }

        assertTrue(failure.isFailure, "Cancellation must propagate, not be swallowed")
        assertTrue(checks <= 4, "Cancellation must be checked between chunks, not only at the end")
    }
}

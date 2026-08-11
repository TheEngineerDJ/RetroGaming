package com.retrovault.domain.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class HashValueTest {

    @Test
    fun `case and zero padding are normalized so lookup is deterministic`() {
        val padded = HashValue.parse(HashAlgorithm.CRC32, "AB12CD")
        val explicit = HashValue.parse(HashAlgorithm.CRC32, "00ab12cd")
        assertEquals(explicit, padded, "DATs write CRC32 inconsistently; both forms are the same value")
        assertEquals("00ab12cd", padded?.hex)
    }

    @Test
    fun `a 0x prefix is accepted`() {
        assertEquals(
            HashValue.parse(HashAlgorithm.CRC32, "deadbeef"),
            HashValue.parse(HashAlgorithm.CRC32, "0xDEADBEEF"),
        )
    }

    @Test
    fun `malformed digests degrade to no evidence rather than throwing`() {
        assertNull(HashValue.parse(HashAlgorithm.CRC32, ""))
        assertNull(HashValue.parse(HashAlgorithm.CRC32, "   "))
        assertNull(HashValue.parse(HashAlgorithm.CRC32, null))
        assertNull(HashValue.parse(HashAlgorithm.CRC32, "zzzz"))
        assertNull(HashValue.parse(HashAlgorithm.CRC32, "deadbeefdeadbeef"), "too long for CRC32")
        assertNull(HashValue.parse(HashAlgorithm.SHA1, "abc-def"))
    }

    @Test
    fun `the same hex under different algorithms is not the same value`() {
        val text = "a".repeat(32)
        assertNotEquals(
            HashValue.parse(HashAlgorithm.MD5, text),
            HashValue.parse(HashAlgorithm.SHA1, text),
        )
    }

    @Test
    fun `only cryptographic hashes count as content identity evidence`() {
        assertEquals(false, HashAlgorithm.CRC32.isCryptographicIdentityEvidence)
        assertEquals(true, HashAlgorithm.MD5.isCryptographicIdentityEvidence)
        assertEquals(true, HashAlgorithm.SHA1.isCryptographicIdentityEvidence)
    }

    @Test
    fun `digest collections keep one value per algorithm`() {
        val first = HashValue.of(HashAlgorithm.CRC32, "11111111")
        val second = HashValue.of(HashAlgorithm.CRC32, "22222222")
        val digests = HashDigests.EMPTY.with(first).with(second)
        assertEquals(second, digests[HashAlgorithm.CRC32])
        assertEquals(setOf(HashAlgorithm.CRC32), digests.algorithms)
    }

    @Test
    fun `digest listing follows the escalation order`() {
        val digests = HashDigests.of(
            HashValue.of(HashAlgorithm.SHA1, "b".repeat(40)),
            HashValue.of(HashAlgorithm.CRC32, "deadbeef"),
        )
        assertEquals(
            listOf(HashAlgorithm.CRC32, HashAlgorithm.SHA1),
            digests.asList().map { it.algorithm },
        )
    }
}

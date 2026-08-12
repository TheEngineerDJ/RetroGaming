package com.retrovault.domain.catalog

import com.retrovault.domain.Fixtures
import com.retrovault.domain.identity.DumpRecordId
import com.retrovault.domain.identity.DumpStatus
import com.retrovault.domain.identity.HashDigests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a catalogue record is allowed to claim about itself.
 *
 * Constitution section 199 keeps imperfect artifacts as evidence; TESTING_SPEC
 * section 1 forbids presenting a wrong match as certain. Both apply here: a
 * broken dump is stored, and is not matchable.
 */
class DumpRecordTest {

    private fun record(status: DumpStatus = DumpStatus.GOOD, size: Long? = 524_288) = DumpRecord.derive(
        id = DumpRecordId("record-1"),
        source = Fixtures.source(),
        setName = "Some Game (USA)",
        romName = "Some Game (USA).sfc",
        size = size,
        hashes = HashDigests.of(Fixtures.crc("aabbccdd")),
        status = status,
    )

    @Test
    fun `a nodump or baddump record is never matchable`() {
        assertFalse(record(DumpStatus.BAD_DUMP).isMatchable)
        assertFalse(record(DumpStatus.NO_DUMP).isMatchable)
    }

    @Test
    fun `an ordinary or verified record is matchable`() {
        assertTrue(record(DumpStatus.GOOD).isMatchable)
        assertTrue(record(DumpStatus.VERIFIED).isMatchable)
    }

    @Test
    fun `an unrecognised status stays usable rather than being discarded`() {
        // "unknown" is a valid escape state (Constitution section 238): a DAT
        // inventing a new flag must not make every record in it unmatchable.
        assertTrue(record(DumpStatus.UNKNOWN).isMatchable)
    }

    @Test
    fun `an unknown size is modelled as unknown, not as zero`() {
        val sizeless = record(size = null)

        assertNull(sizeless.size)
        assertFalse(sizeless.hasKnownSize)
        assertTrue(record().hasKnownSize)
    }

    @Test
    fun `a negative size is refused outright`() {
        val failure = runCatching { record(size = -1) }

        assertTrue(failure.isFailure)
    }

    @Test
    fun `an unknown size does not change the identity key`() {
        // Size is corroboration, never identity. Two readings of the same
        // release must group together whether or not a size was stated.
        assertEquals(record().canonicalIdentityKey, record(size = null).canonicalIdentityKey)
    }
}

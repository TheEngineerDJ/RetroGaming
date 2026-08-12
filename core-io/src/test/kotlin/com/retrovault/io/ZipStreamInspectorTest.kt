package com.retrovault.io

import com.retrovault.domain.identity.ContainerKind
import com.retrovault.domain.identity.HashAlgorithm
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ZipStreamInspectorTest {

    private val inspector = ZipStreamInspector()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content)
                out.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    private fun inspect(
        bytes: ByteArray,
        algorithms: Set<HashAlgorithm> = setOf(HashAlgorithm.CRC32),
        limits: ArchiveLimits = ArchiveLimits(),
    ) = ZipStreamInspector(limits).inspect(ByteArrayInputStream(bytes), algorithms)

    @Test
    fun `entries are listed with their uncompressed size and crc32`() {
        val archive = zip("game.sfc" to "abc".toByteArray())

        val inspected = assertIs<ArchiveInspection.Inspected>(inspect(archive))
        val entry = inspected.entries.single()

        assertEquals("game.sfc", entry.entryPath)
        assertEquals(3, entry.uncompressedSize)
        assertEquals("352441c2", entry.hashes[HashAlgorithm.CRC32]?.hex)
    }

    @Test
    fun `crc32 is computed from bytes rather than trusted from metadata`() {
        // A streamed ZIP writes its CRC after the data, so a value read from the
        // header before decompression would be zero. Computing it is also what
        // stops a crafted archive from declaring whatever CRC it likes.
        val archive = zip("game.sfc" to ByteArray(1000) { 7 })

        val inspected = assertIs<ArchiveInspection.Inspected>(inspect(archive))

        assertEquals(1000, inspected.entries.single().uncompressedSize)
        assertTrue(inspected.entries.single().hashes.contains(HashAlgorithm.CRC32))
    }

    @Test
    fun `several entries are all reported`() {
        val archive = zip(
            "a.sfc" to "one".toByteArray(),
            "b.sfc" to "two".toByteArray(),
            "readme.txt" to "hello".toByteArray(),
        )

        val inspected = assertIs<ArchiveInspection.Inspected>(inspect(archive))

        assertEquals(listOf("a.sfc", "b.sfc", "readme.txt"), inspected.entries.map { it.entryPath })
    }

    @Test
    fun `directory entries are not artifacts`() {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { out ->
            out.putNextEntry(ZipEntry("folder/"))
            out.closeEntry()
            out.putNextEntry(ZipEntry("folder/game.sfc"))
            out.write("abc".toByteArray())
            out.closeEntry()
        }

        val inspected = assertIs<ArchiveInspection.Inspected>(inspect(buffer.toByteArray()))

        assertEquals(listOf("folder/game.sfc"), inspected.entries.map { it.entryPath })
    }

    @Test
    fun `a nested archive is flagged and not descended into`() {
        val inner = zip("inner.sfc" to "abc".toByteArray())
        val archive = zip("outer.zip" to inner)

        val inspected = assertIs<ArchiveInspection.Inspected>(inspect(archive))

        assertTrue(inspected.entries.single().isNestedArchive)
    }

    @Test
    fun `a traversal path is refused and reported`() {
        val archive = zip("../../etc/passwd" to "x".toByteArray(), "game.sfc" to "abc".toByteArray())

        val inspected = assertIs<ArchiveInspection.Inspected>(inspect(archive))

        assertEquals(listOf("game.sfc"), inspected.entries.map { it.entryPath })
        assertTrue(inspected.warnings.any { it.message.contains("escapes") })
    }

    @Test
    fun `an absolute path is refused`() {
        val archive = zip("/etc/shadow" to "x".toByteArray())

        val inspected = assertIs<ArchiveInspection.Inspected>(inspect(archive))

        assertTrue(inspected.entries.isEmpty())
    }

    @Test
    fun `the entry count is bounded`() {
        val entries = (1..50).map { "file$it.sfc" to "x".toByteArray() }.toTypedArray()
        val archive = zip(*entries)

        val inspected = assertIs<ArchiveInspection.Inspected>(
            inspect(archive, limits = ArchiveLimits(maxEntries = 10)),
        )

        assertEquals(10, inspected.entries.size)
        assertTrue(inspected.truncated)
    }

    @Test
    fun `a decompression bomb stops at the size budget`() {
        // 8 MB of zeroes compresses to a few kilobytes.
        val archive = zip("bomb.bin" to ByteArray(8 * 1024 * 1024))

        val inspected = assertIs<ArchiveInspection.Inspected>(
            inspect(archive, limits = ArchiveLimits(maxEntryUncompressedBytes = 64 * 1024)),
        )

        assertTrue(inspected.truncated, "Inspection must stop rather than expand the whole entry")
        assertTrue(inspected.warnings.any { it.message.contains("budget") })
    }

    @Test
    fun `a long entry name is skipped`() {
        val archive = zip("${"a".repeat(600)}.sfc" to "x".toByteArray())

        val inspected = assertIs<ArchiveInspection.Inspected>(inspect(archive))

        assertTrue(inspected.entries.isEmpty())
    }

    @Test
    fun `a file that is not a zip fails as data`() {
        val outcome = inspect("this is not a zip archive at all".toByteArray())

        val failed = assertIs<ArchiveInspection.Failed>(outcome)
        assertIs<ContentFailure.ReadFailed>(failed.failure)
    }

    @Test
    fun `a truncated archive reports what it managed to read`() {
        val archive = zip("a.sfc" to "abc".toByteArray(), "b.sfc" to ByteArray(4096) { 3 })
        val damaged = archive.copyOfRange(0, archive.size / 2)

        val outcome = inspect(damaged)

        // Either outcome is acceptable; what matters is that it never throws
        // and never claims entries it did not read.
        when (outcome) {
            is ArchiveInspection.Inspected -> assertTrue(outcome.entries.size <= 2)
            is ArchiveInspection.Failed -> assertIs<ContentFailure.ReadFailed>(outcome.failure)
        }
    }

    @Test
    fun `an empty archive yields no entries`() {
        val inspected = assertIs<ArchiveInspection.Inspected>(inspect(zip()))
        assertTrue(inspected.entries.isEmpty())
    }

    @Test
    fun `a specific entry can be hashed on escalation`() {
        val archive = zip("a.sfc" to "one".toByteArray(), "b.sfc" to "abc".toByteArray())

        val outcome = inspector.hashEntry(
            ByteArrayInputStream(archive),
            "b.sfc",
            setOf(HashAlgorithm.SHA1),
        )

        val computed = assertIs<HashOutcome.Computed>(outcome)
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", computed.digests[HashAlgorithm.SHA1]?.hex)
    }

    @Test
    fun `hashing a missing entry reports not found`() {
        val archive = zip("a.sfc" to "one".toByteArray())

        val failed = assertIs<HashOutcome.Failed>(
            inspector.hashEntry(ByteArrayInputStream(archive), "nope.sfc", setOf(HashAlgorithm.SHA1)),
        )

        assertEquals(ContentFailure.NotFound, failed.failure)
    }

    @Test
    fun `container detection distinguishes supported and unsupported archives`() {
        assertEquals(ContainerKind.ZIP, ContainerDetector.detect("game.zip"))
        assertEquals(ContainerKind.ZIP, ContainerDetector.detect("GAME.ZIP"))
        assertEquals(ContainerKind.UNSUPPORTED_ARCHIVE, ContainerDetector.detect("game.7z"))
        assertEquals(ContainerKind.UNSUPPORTED_ARCHIVE, ContainerDetector.detect("game.rar"))
        assertEquals(ContainerKind.RAW, ContainerDetector.detect("game.sfc"))
        assertEquals(ContainerKind.RAW, ContainerDetector.detect("game"))
    }

    @Test
    fun `inspection is deterministic`() {
        val archive = zip("a.sfc" to "one".toByteArray(), "b.sfc" to "two".toByteArray())

        val first = assertIs<ArchiveInspection.Inspected>(inspect(archive))
        val second = assertIs<ArchiveInspection.Inspected>(inspect(archive))

        assertEquals(first.entries, second.entries)
    }
}

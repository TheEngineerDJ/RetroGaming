package com.retrovault.data

import com.retrovault.application.Outcome
import com.retrovault.data.jdbc.JdbcSqlDatabase
import com.retrovault.domain.catalog.DatSourceRef
import com.retrovault.domain.catalog.DumpRecord
import com.retrovault.domain.correction.CorrectedIdentity
import com.retrovault.domain.correction.CorrectionScope
import com.retrovault.domain.correction.CorrectionState
import com.retrovault.domain.correction.IdentityCorrection
import com.retrovault.domain.entity.EntityProvenance
import com.retrovault.domain.entity.EntityPromoter
import com.retrovault.domain.entity.EntityRelationship
import com.retrovault.domain.entity.RelationshipType
import com.retrovault.domain.identity.CorrectionId
import com.retrovault.domain.identity.DatSourceId
import com.retrovault.domain.identity.DumpRecordId
import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.HashDigests
import com.retrovault.domain.identity.HashValue
import com.retrovault.domain.identity.PlatformName
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The canonical entity graph and durable corrections, persisted.
 *
 * Two properties matter more than the rest. Promotion must be idempotent, or a
 * rescan doubles the graph. And a derived write must never overwrite something
 * a person confirmed, because Constitution section 43 reserves canonical merges
 * for human evidence and an automatic pass that quietly undid one would make
 * the whole correction model untrustworthy.
 */
class EntityAndCorrectionPersistenceTest {

    private lateinit var database: JdbcSqlDatabase
    private lateinit var catalog: SqlDumpCatalog
    private lateinit var graph: SqlEntityGraph
    private lateinit var corrections: SqlCorrectionStore

    private val source = DatSourceRef(
        id = DatSourceId("no_intro:Test:1"),
        provider = "no_intro",
        setName = "Test Console",
        version = "1",
        platform = PlatformName("Test Console"),
        importedAtEpochMillis = 1,
    )

    private val redump = source.copy(id = DatSourceId("redump:Test:1"), provider = "redump")

    @BeforeTest
    fun setUp() {
        database = JdbcSqlDatabase.inMemory()
        Schema.migrate(database)
        catalog = SqlDumpCatalog(database)
        graph = SqlEntityGraph(database)
        corrections = SqlCorrectionStore(database)
    }

    @AfterTest
    fun tearDown() = database.close()

    private fun record(
        setName: String,
        sha1: String = "a".repeat(40),
        id: String = "record-$setName",
        from: DatSourceRef = source,
    ): DumpRecord = DumpRecord.derive(
        id = DumpRecordId(id),
        source = from,
        setName = setName,
        romName = "$setName.sfc",
        size = 4096,
        hashes = HashDigests.of(
            HashValue.of(HashAlgorithm.CRC32, "aabbccdd"),
            HashValue.of(HashAlgorithm.SHA1, sha1),
        ),
    )

    private suspend fun importReady(vararg records: DumpRecord, from: DatSourceRef = source) {
        assertIs<Outcome.Success<*>>(catalog.beginImport(from))
        assertIs<Outcome.Success<*>>(catalog.writeBatch(from.id, records.toList()))
        assertIs<Outcome.Success<*>>(catalog.commitImport(from.id))
    }

    private fun countOf(table: String): Int =
        database.query("SELECT COUNT(*) FROM $table") { it.getInt(0) }.first()

    // ------------------------------------------------------------------
    // The entity graph
    // ------------------------------------------------------------------

    @Test
    fun `promoting the same identity twice writes one graph, not two`() = runTest {
        val promoted = EntityPromoter.promote(record("Some Game (USA)"))

        assertIs<Outcome.Success<*>>(graph.save(promoted))
        assertIs<Outcome.Success<*>>(graph.save(promoted))

        assertEquals(1, countOf("platform_entity"))
        assertEquals(1, countOf("work_entity"))
        assertEquals(1, countOf("release_entity"))
        assertEquals(1, countOf("artifact_entity"))
        assertEquals(3, countOf("entity_relationship"))
    }

    @Test
    fun `a release round-trips with everything that distinguishes it`() = runTest {
        val promoted = EntityPromoter.promote(record("Some Game (USA, Europe) (Rev A)"))
        graph.save(promoted)

        val loaded = (graph.findRelease(promoted.release.id) as Outcome.Success).value

        assertNotNull(loaded)
        assertEquals(promoted.release.workId, loaded.workId)
        assertEquals(promoted.release.platformId, loaded.platformId)
        assertEquals(promoted.release.regions, loaded.regions)
        assertEquals("A", loaded.revision)
    }

    @Test
    fun `two datasets describing one release share its entities`() = runTest {
        graph.save(EntityPromoter.promote(record("Some Game (USA)", id = "a")))
        graph.save(EntityPromoter.promote(record("Some Game (USA)", id = "b", from = redump)))

        assertEquals(1, countOf("release_entity"))
        assertEquals(1, countOf("artifact_entity"), "Same bytes, one digital image")
    }

    @Test
    fun `a derived write never demotes an entity the user confirmed`() = runTest {
        // Constitution section 43: automation proposes, people establish. An
        // automatic pass that overwrote a confirmed entity would make every
        // user decision provisional.
        val promoted = EntityPromoter.promote(record("Some Game (USA)"))
        graph.save(promoted)
        database.execute(
            "UPDATE work_entity SET provenance = 'CONFIRMED', canonical_title = 'The Real Title'",
        )

        graph.save(promoted)

        val stored = database.query("SELECT canonical_title, provenance FROM work_entity") {
            it.getString(0) to it.getString(1)
        }.single()
        assertEquals("The Real Title" to "CONFIRMED", stored)
    }

    @Test
    fun `a confirmed relationship survives a later derived pass`() = runTest {
        val promoted = EntityPromoter.promote(record("Some Game (USA)"))
        graph.save(promoted)
        val edge = promoted.relationships.first { it.type == RelationshipType.RELEASE_OF }
        graph.relate(edge.copy(provenance = EntityProvenance.CONFIRMED, note = "verified by hand"))

        graph.save(promoted)

        val loaded = (graph.relationshipsFrom(promoted.release.entityRef) as Outcome.Success).value
            .single { it.type == RelationshipType.RELEASE_OF }
        assertEquals(EntityProvenance.CONFIRMED, loaded.provenance)
        assertEquals("verified by hand", loaded.note)
    }

    @Test
    fun `a user-asserted derivation relation round-trips`() = runTest {
        // Section 32: port, remake, remaster and compilation must be explicit.
        // Nothing derives one automatically, so this is the only way one exists.
        val cartridge = EntityPromoter.promote(record("Some Game (USA)", id = "cart"))
        val disc = EntityPromoter.promote(
            record("Some Game (USA) (Rev A)", sha1 = "b".repeat(40), id = "disc"),
        )
        graph.save(cartridge)
        graph.save(disc)

        val port = EntityRelationship(
            from = disc.release.entityRef,
            type = RelationshipType.PORT_OF,
            to = cartridge.release.entityRef,
            provenance = EntityProvenance.CONFIRMED,
        )
        assertIs<Outcome.Success<*>>(graph.relate(port))

        val edges = (graph.relationshipsFrom(disc.release.entityRef) as Outcome.Success).value
        assertTrue(edges.any { it.type == RelationshipType.PORT_OF && it.to == cartridge.release.entityRef })
    }

    @Test
    fun `a release resolves back to the catalogue records that describe it`() = runTest {
        val noIntro = record("Some Game (USA)", id = "a")
        val fromRedump = record("Some Game (USA)", id = "b", from = redump)
        importReady(noIntro)
        importReady(fromRedump, from = redump)
        val promoted = EntityPromoter.promote(noIntro)
        graph.save(promoted)

        val found = graph.recordsForRelease(promoted.release.id)

        assertEquals(
            listOf("a", "b"),
            found.map { it.id.value }.sorted(),
            "Both datasets describe this release, so a correction naming it finds both",
        )
    }

    @Test
    fun `an unmatchable record is not offered as a release's description`() = runTest {
        importReady(
            DumpRecord.derive(
                id = DumpRecordId("nodump"),
                source = source,
                setName = "Some Game (USA)",
                romName = "Some Game (USA).sfc",
                size = 4096,
                hashes = HashDigests.of(HashValue.of(HashAlgorithm.SHA1, "c".repeat(40))),
                status = com.retrovault.domain.identity.DumpStatus.NO_DUMP,
            ),
        )
        val promoted = EntityPromoter.promote(record("Some Game (USA)", sha1 = "c".repeat(40)))
        graph.save(promoted)

        assertTrue(graph.recordsForRelease(promoted.release.id).isEmpty())
    }

    @Test
    fun `deleting a work cascades to its releases and artifacts`() = runTest {
        val promoted = EntityPromoter.promote(record("Some Game (USA)"))
        graph.save(promoted)

        database.execute("DELETE FROM work_entity")

        assertEquals(0, countOf("release_entity"))
        assertEquals(0, countOf("artifact_entity"))
        assertEquals(0, countOf("artifact_hash"))
    }

    // ------------------------------------------------------------------
    // Durable corrections
    // ------------------------------------------------------------------

    private val scope = CorrectionScope(HashAlgorithm.SHA1, "a".repeat(40), size = 4096)

    private fun correction(id: String, corrected: CorrectedIdentity, at: Long) = IdentityCorrection(
        id = CorrectionId(id),
        scope = scope,
        previousIdentityDescription = "chrono trigger [USA]",
        corrected = corrected,
        reason = "I dumped this myself",
        recordedAtEpochMillis = at,
    )

    @Test
    fun `a correction round-trips with everything section 69 requires`() = runTest {
        val recorded = correction("c1", CorrectedIdentity.NotThis, at = 10)
        assertIs<Outcome.Success<*>>(corrections.record(recorded))

        val loaded = (corrections.history(scope) as Outcome.Success).value.single()

        assertEquals(recorded.id, loaded.id)
        assertEquals(recorded.scope, loaded.scope)
        assertEquals("chrono trigger [USA]", loaded.previousIdentityDescription)
        assertEquals(CorrectedIdentity.NotThis, loaded.corrected)
        assertEquals("I dumped this myself", loaded.reason)
        assertEquals(10L, loaded.recordedAtEpochMillis)
        assertEquals(CorrectionState.ACTIVE, loaded.state)
    }

    @Test
    fun `a release correction round-trips`() = runTest {
        val release = EntityPromoter.promote(record("Some Game (USA)")).release.id
        corrections.record(correction("c1", CorrectedIdentity.IsRelease(release), at = 10))

        val loaded = (corrections.history(scope) as Outcome.Success).value.single()

        assertEquals(CorrectedIdentity.IsRelease(release), loaded.corrected)
    }

    @Test
    fun `correcting again supersedes rather than rewrites`() = runTest {
        // Constitution section 69: never silently rewrite history. Section 70:
        // earlier knowledge stays reconstructable.
        corrections.record(correction("c1", CorrectedIdentity.NotThis, at = 10))
        corrections.record(correction("c2", CorrectedIdentity.NotThis, at = 20))

        val history = (corrections.history(scope) as Outcome.Success).value

        assertEquals(listOf("c2", "c1"), history.map { it.id.value })
        assertEquals(CorrectionState.ACTIVE, history.first().state)
        assertEquals(CorrectionState.SUPERSEDED, history.last().state)
        assertEquals(CorrectionId("c2"), history.last().supersededBy)
        assertEquals(1, corrections.active().size)
    }

    @Test
    fun `withdrawing leaves the record behind`() = runTest {
        corrections.record(correction("c1", CorrectedIdentity.NotThis, at = 10))

        assertIs<Outcome.Success<*>>(corrections.withdraw(scope))

        assertEquals(1, (corrections.history(scope) as Outcome.Success).value.size)
        assertEquals(CorrectionState.WITHDRAWN, (corrections.history(scope) as Outcome.Success).value.single().state)
        assertTrue(corrections.active().isEmpty, "A withdrawn correction must stop applying")
    }

    @Test
    fun `an active correction is found by the content it was made against`() = runTest {
        corrections.record(correction("c1", CorrectedIdentity.NotThis, at = 10))

        val found = corrections.active().forHashes(
            HashDigests.of(HashValue.of(HashAlgorithm.SHA1, "a".repeat(40))),
        )

        assertNotNull(found)
        assertNull(
            corrections.active().forHashes(HashDigests.of(HashValue.of(HashAlgorithm.SHA1, "f".repeat(40)))),
            "Different bytes must not inherit someone else's correction",
        )
    }

    @Test
    fun `corrections for different content do not interfere`() = runTest {
        corrections.record(correction("c1", CorrectedIdentity.NotThis, at = 10))
        corrections.record(
            correction("c2", CorrectedIdentity.NotThis, at = 20)
                .copy(scope = CorrectionScope(HashAlgorithm.SHA1, "b".repeat(40))),
        )

        assertEquals(2, corrections.active().size)
        assertEquals(1, (corrections.history(scope) as Outcome.Success).value.size)
    }
}

package com.retrovault.data

import com.retrovault.application.Clock
import com.retrovault.application.EntityPage
import com.retrovault.application.MatchKind
import com.retrovault.application.Outcome
import com.retrovault.data.jdbc.JdbcSqlDatabase
import com.retrovault.domain.catalog.DatSourceRef
import com.retrovault.domain.catalog.DumpRecord
import com.retrovault.domain.correction.CorrectedIdentity
import com.retrovault.domain.correction.CorrectionScope
import com.retrovault.domain.correction.CorrectionState
import com.retrovault.domain.correction.IdentityCorrection
import com.retrovault.domain.entity.EntityKind
import com.retrovault.domain.entity.EntityProvenance
import com.retrovault.domain.entity.EntityPromoter
import com.retrovault.domain.entity.EntityRef
import com.retrovault.domain.entity.EntityRelationship
import com.retrovault.domain.entity.PromotedIdentity
import com.retrovault.domain.entity.RelationshipType
import com.retrovault.domain.identity.CorrectionId
import com.retrovault.domain.identity.DatSourceId
import com.retrovault.domain.identity.DumpRecordId
import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.HashDigests
import com.retrovault.domain.identity.HashValue
import com.retrovault.domain.identity.PlatformId
import com.retrovault.domain.identity.PlatformName
import com.retrovault.domain.identity.WorkId
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading the canonical entity graph.
 *
 * The properties under test are the ones a browse surface will rely on and
 * cannot check for itself: that a truncated page says it was truncated, that a
 * user's search text is never treated as a wildcard, that both directions of an
 * edge are reachable, and that provenance queries hide nothing - not the
 * dataset an entity came from, not a correction that has since been superseded
 * or withdrawn (Constitution section 44, section 70 and section 196).
 */
class EntityQueryTest {

    private lateinit var database: JdbcSqlDatabase
    private lateinit var catalog: SqlDumpCatalog
    private lateinit var graph: SqlEntityGraph
    private lateinit var corrections: SqlCorrectionStore
    private lateinit var queries: SqlEntityQueries

    private var now = 1_000L

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
        graph = SqlEntityGraph(database, Clock { now })
        corrections = SqlCorrectionStore(database)
        queries = SqlEntityQueries(database, corrections)
    }

    @AfterTest
    fun tearDown() = database.close()

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun record(
        setName: String,
        sha1: String = "a".repeat(40),
        id: String = "record-$setName",
        from: DatSourceRef = source,
        platform: String = "Test Console",
    ): DumpRecord = DumpRecord.derive(
        id = DumpRecordId(id),
        source = from.copy(platform = PlatformName(platform)),
        setName = setName,
        romName = "$setName.sfc",
        size = 4096,
        hashes = HashDigests.of(
            HashValue.of(HashAlgorithm.CRC32, "aabbccdd"),
            HashValue.of(HashAlgorithm.SHA1, sha1),
        ),
    )

    private suspend fun promote(
        setName: String,
        sha1: String = "a".repeat(40),
        id: String = "record-$setName",
        from: DatSourceRef = source,
        platform: String = "Test Console",
    ): PromotedIdentity {
        val promoted = EntityPromoter.promote(record(setName, sha1, id, from, platform))
        assertIs<Outcome.Success<*>>(graph.save(promoted))
        return promoted
    }

    private suspend fun importReady(vararg records: DumpRecord, from: DatSourceRef = source) {
        assertIs<Outcome.Success<*>>(catalog.beginImport(from))
        assertIs<Outcome.Success<*>>(catalog.writeBatch(from.id, records.toList()))
        assertIs<Outcome.Success<*>>(catalog.commitImport(from.id))
    }

    // ------------------------------------------------------------------
    // Platforms
    // ------------------------------------------------------------------

    @Test
    fun `platforms are listed and found by id`() = runTest {
        val promoted = promote("Some Game (USA)")

        val listed = queries.platforms()
        assertEquals(1, listed.size)
        assertFalse(listed.hasMore)
        assertEquals(promoted.platform.id, queries.findPlatform(promoted.platform.id)?.id)
    }

    @Test
    fun `a missing platform is absent rather than invented`() = runTest {
        assertNull(queries.findPlatform(PlatformId("nothing-here")))
    }

    @Test
    fun `a platform search matches its aliases as well as its name`() = runTest {
        val promoted = promote("Some Game (USA)")
        database.execute(
            "UPDATE platform_entity SET aliases = ? WHERE id = ?",
            listOf("SNES", promoted.platform.id.value),
        )

        assertEquals(1, queries.platforms(query = "snes").size, "Section 43 makes aliases search aids")
        assertEquals(1, queries.platforms(query = "TEST con").size)
        assertTrue(queries.platforms(query = "megadrive").isEmpty)
    }

    @Test
    fun `a search term is never treated as a wildcard`() = runTest {
        // A user typing "%" is searching for a title, not writing SQL. Letting
        // it through would silently widen their search to everything and make
        // an empty library look full.
        promote("Some Game (USA)")

        assertTrue(queries.platforms(query = "%").isEmpty)
        assertTrue(queries.platforms(query = "_est Console").isEmpty)
        assertEquals(1, queries.platforms(query = "Test Console").size)
    }

    // ------------------------------------------------------------------
    // Works
    // ------------------------------------------------------------------

    @Test
    fun `works are searched by written title, normalized title and alias`() = runTest {
        val promoted = promote("The Legend of Zelda (USA)")
        database.execute(
            "UPDATE work_entity SET aliases = ? WHERE id = ?",
            listOf("Zeruda no Densetsu", promoted.work.id.value),
        )

        assertEquals(1, queries.works(query = "legend of zelda").size)
        assertEquals(1, queries.works(query = "Legend of Zelda, The").size, "Normalized titles match too")
        assertEquals(1, queries.works(query = "zeruda").size)
        assertTrue(queries.works(query = "metroid").isEmpty)
    }

    @Test
    fun `an exact title outranks a longer title that merely contains it`() = runTest {
        // Section 212: identity relevance outranks raw text. Alphabetical order
        // would put "A Link to the Past" first purely because "A" sorts early.
        promote("Zelda (USA)", id = "a")
        promote("A Link to the Past - Zelda (USA)", sha1 = "b".repeat(40), id = "b")

        val results = queries.works(query = "Zelda")

        assertEquals("Zelda", results.items.first().entity.canonicalTitle)
        assertEquals(MatchKind.EXACT, results.items.first().matchKind)
        assertEquals(MatchKind.PARTIAL, results.items.last().matchKind)
    }

    @Test
    fun `a result says why it matched`() = runTest {
        // Section 213: a result found through an alias must be distinguishable
        // from one whose title the user typed exactly.
        val promoted = promote("The Legend of Zelda (USA)")
        database.execute(
            "UPDATE work_entity SET aliases = ? WHERE id = ?",
            listOf("Zeruda no Densetsu", promoted.work.id.value),
        )

        val byAlias = queries.works(query = "Zeruda no Densetsu").items.single()
        assertEquals(MatchKind.ALIAS, byAlias.matchKind)
        assertEquals("Zeruda no Densetsu", byAlias.matchedOn, "The user sees which name they hit")

        val byNormalized = queries.works(query = "Legend of Zelda, The").items.single()
        assertEquals(MatchKind.NORMALIZED, byNormalized.matchKind)

        val byExact = queries.works(query = "The Legend of Zelda").items.single()
        assertEquals(MatchKind.EXACT, byExact.matchKind)
    }

    @Test
    fun `an exact alias outranks a partial title hit`() = runTest {
        val alias = promote("Something Else (USA)", id = "a")
        database.execute(
            "UPDATE work_entity SET aliases = ? WHERE id = ?",
            listOf("Metroid", alias.work.id.value),
        )
        promote("Super Metroid Deluxe (USA)", sha1 = "b".repeat(40), id = "b")

        val results = queries.works(query = "Metroid")

        assertEquals(MatchKind.ALIAS, results.items.first().matchKind)
        assertEquals("Something Else", results.items.first().entity.canonicalTitle)
    }

    @Test
    fun `ranking is deterministic when two results match the same way`() = runTest {
        promote("Metroid Two (USA)", id = "a")
        promote("Metroid One (USA)", sha1 = "b".repeat(40), id = "b")

        val first = queries.works(query = "Metroid").items.map { it.entity.canonicalTitle }
        val second = queries.works(query = "Metroid").items.map { it.entity.canonicalTitle }

        assertEquals(first, second)
        assertEquals(listOf("Metroid One", "Metroid Two"), first, "Equal matches fall back to title order")
    }

    @Test
    fun `a platform search reports how it matched`() = runTest {
        val promoted = promote("Some Game (USA)")
        database.execute(
            "UPDATE platform_entity SET aliases = ? WHERE id = ?",
            listOf("SNES", promoted.platform.id.value),
        )

        assertEquals(MatchKind.ALIAS, queries.platforms(query = "SNES").items.single().matchKind)
        assertEquals(MatchKind.EXACT, queries.platforms(query = "Test Console").items.single().matchKind)
        assertEquals(MatchKind.PARTIAL, queries.platforms(query = "Test Con").items.single().matchKind)
    }

    @Test
    fun `works can be narrowed to one platform`() = runTest {
        val console = promote("Shared Title (USA)", id = "a", platform = "Test Console")
        promote("Other Title (USA)", sha1 = "b".repeat(40), id = "b", platform = "Other Console")

        val all = queries.works()
        val narrowed = queries.works(platformId = console.platform.id)

        assertEquals(2, all.size)
        assertEquals(listOf(console.work.id), narrowed.items.map { it.entity.id })
    }

    @Test
    fun `a work search and a platform filter apply together`() = runTest {
        promote("Shared Title (USA)", id = "a", platform = "Test Console")
        val other = promote("Shared Title (Europe)", sha1 = "b".repeat(40), id = "b", platform = "Other Console")

        val narrowed = queries.works(query = "shared", platformId = other.platform.id)

        assertEquals(listOf(other.work.id), narrowed.items.map { it.entity.id })
    }

    @Test
    fun `a missing work is absent rather than invented`() = runTest {
        assertNull(queries.findWork(WorkId("nothing-here")))
    }

    // ------------------------------------------------------------------
    // Bounds
    // ------------------------------------------------------------------

    @Test
    fun `a truncated page says that it was truncated`() = runTest {
        repeat(5) { index ->
            promote("Game $index (USA)", sha1 = "$index".repeat(40), id = "record-$index")
        }

        val page = queries.works(limit = 2)

        assertEquals(2, page.size)
        assertTrue(page.hasMore, "A page that cannot say it was cut misleads the user about their library")
        assertFalse(queries.works(limit = 5).hasMore)
    }

    @Test
    fun `a caller cannot raise the bound above the maximum`() = runTest {
        promote("Some Game (USA)")

        // Section 249: bounded means bounded. An out-of-range limit is clamped
        // rather than honoured or rejected, so a browse can never ask for the
        // whole catalogue at once.
        assertEquals(1, queries.works(limit = Int.MAX_VALUE).size)
        assertEquals(1, queries.works(limit = 0).size)
    }

    // ------------------------------------------------------------------
    // Releases and artifacts
    // ------------------------------------------------------------------

    @Test
    fun `every published form of a work is reachable from it`() = runTest {
        val usa = promote("Some Game (USA)", id = "a")
        val europe = promote("Some Game (Europe)", sha1 = "b".repeat(40), id = "b")
        assertEquals(usa.work.id, europe.work.id, "The fixture needs one work with two releases")

        val releases = queries.releasesOfWork(usa.work.id)

        assertEquals(
            setOf(usa.release.id, europe.release.id),
            releases.items.map { it.id }.toSet(),
        )
    }

    @Test
    fun `releases can be browsed by platform`() = runTest {
        val console = promote("Some Game (USA)", id = "a", platform = "Test Console")
        promote("Other Game (USA)", sha1 = "b".repeat(40), id = "b", platform = "Other Console")

        val onConsole = queries.releasesOnPlatform(console.platform.id)

        assertEquals(listOf(console.release.id), onConsole.items.map { it.id })
    }

    @Test
    fun `a release round-trips with what distinguishes it`() = runTest {
        val promoted = promote("Some Game (USA, Europe) (Rev A)")

        val release = assertNotNull(queries.findRelease(promoted.release.id))

        assertEquals(promoted.release.workId, release.workId)
        assertEquals(promoted.release.platformId, release.platformId)
        assertEquals(promoted.release.regions, release.regions)
        assertEquals("A", release.revision)
    }

    @Test
    fun `an artifact carries the hashes that identify it`() = runTest {
        val promoted = promote("Some Game (USA)")

        val artifacts = queries.artifactsOfRelease(promoted.release.id)

        val artifact = artifacts.single()
        assertEquals(promoted.artifact.id, artifact.id)
        assertEquals(4096L, artifact.size)
        assertEquals("a".repeat(40), artifact.hashes[HashAlgorithm.SHA1]?.hex)
        assertEquals(artifact.hashes, assertNotNull(queries.findArtifact(artifact.id)).hashes)
    }

    // ------------------------------------------------------------------
    // Relationships
    // ------------------------------------------------------------------

    @Test
    fun `the graph can be walked in both directions`() = runTest {
        val promoted = promote("Some Game (USA)")

        val fromRelease = queries.relationships(promoted.release.entityRef)
        val fromWork = queries.relationships(promoted.work.entityRef)

        assertTrue(fromRelease.any { it.type == RelationshipType.RELEASE_OF })
        assertTrue(fromRelease.any { it.type == RelationshipType.RUNS_ON })
        assertTrue(
            fromRelease.any { it.type == RelationshipType.IMAGE_OF && it.from == promoted.artifact.entityRef },
            "An incoming edge is how a caller reaches a release's artifacts",
        )
        assertTrue(
            fromWork.any { it.type == RelationshipType.RELEASE_OF && it.to == promoted.work.entityRef },
            "A graph that could only be walked downwards would leave half of it unreachable",
        )
    }

    @Test
    fun `an edge of a type this build cannot read is skipped, not guessed at`() = runTest {
        val promoted = promote("Some Game (USA)")
        database.execute(
            "UPDATE entity_relationship SET type = 'INVENTED_BY_A_LATER_VERSION' " +
                "WHERE type = ? AND from_id = ?",
            listOf(RelationshipType.RELEASE_OF.name, promoted.release.id.value),
        )

        val edges = queries.relationships(promoted.release.entityRef)

        assertTrue(edges.none { it.type == RelationshipType.RELEASE_OF })
        assertTrue(edges.any { it.type == RelationshipType.RUNS_ON }, "The rest of the graph still reads")
    }

    // ------------------------------------------------------------------
    // Provenance
    // ------------------------------------------------------------------

    @Test
    fun `provenance names every dataset that contributes to an entity`() = runTest {
        val noIntro = record("Some Game (USA)", id = "a")
        val fromRedump = record("Some Game (USA)", id = "b", from = redump)
        importReady(noIntro)
        importReady(fromRedump, from = redump)
        val promoted = EntityPromoter.promote(noIntro)
        graph.save(promoted)

        val report = assertNotNull(queries.provenanceOf(promoted.release.entityRef))

        assertEquals(
            listOf("no_intro:Test:1", "redump:Test:1"),
            report.contributingSources.map { it.id.value }.sorted(),
            "Section 196: a dataset must never become an invisible authority",
        )
        assertEquals(2, report.independentSourceCount)
    }

    @Test
    fun `provenance distinguishes what was derived from what a person established`() = runTest {
        val promoted = promote("Some Game (USA)")

        val derived = assertNotNull(queries.provenanceOf(promoted.work.entityRef))
        assertEquals(EntityProvenance.DERIVED, derived.provenance)
        assertFalse(derived.isUserEstablished)

        database.execute("UPDATE work_entity SET provenance = 'CONFIRMED'")

        assertTrue(assertNotNull(queries.provenanceOf(promoted.work.entityRef)).isUserEstablished)
    }

    @Test
    fun `provenance carries the edges that argue for an entity`() = runTest {
        val promoted = promote("Some Game (USA)")

        val report = assertNotNull(queries.provenanceOf(promoted.release.entityRef))

        assertEquals(
            queries.relationships(promoted.release.entityRef).size,
            report.relationships.size,
        )
    }

    @Test
    fun `provenance of something that does not exist is absent`() = runTest {
        assertNull(queries.provenanceOf(EntityRef(EntityKind.WORK, "nothing-here")))
    }

    // ------------------------------------------------------------------
    // Correction history
    // ------------------------------------------------------------------

    private val scope = CorrectionScope(HashAlgorithm.SHA1, "a".repeat(40), size = 4096)

    private fun correction(id: String, corrected: CorrectedIdentity, at: Long) = IdentityCorrection(
        id = CorrectionId(id),
        scope = scope,
        previousIdentityDescription = "some game [USA]",
        corrected = corrected,
        reason = "I dumped this myself",
        recordedAtEpochMillis = at,
    )

    @Test
    fun `an artifact carries its whole correction history, superseded entries included`() = runTest {
        // Section 70: a history that shows only the current answer is not a
        // history. The superseded entry is how a user finds out what they told
        // RetroVault last month and why the answer changed.
        val promoted = promote("Some Game (USA)")
        corrections.record(correction("c1", CorrectedIdentity.NotThis, at = 10))
        corrections.record(
            correction("c2", CorrectedIdentity.IsRelease(promoted.release.id), at = 20),
        )

        val report = assertNotNull(queries.provenanceOf(promoted.artifact.entityRef))

        assertEquals(listOf("c2", "c1"), report.corrections.map { it.id.value })
        assertEquals(CorrectionState.ACTIVE, report.corrections.first().state)
        assertEquals(CorrectionState.SUPERSEDED, report.corrections.last().state)
        assertTrue(report.hasActiveCorrection)
    }

    @Test
    fun `the identity a correction overrode stays visible`() = runTest {
        // Section 44: the losing claim is preserved, not flattened away. A user
        // looking at a corrected file can see what RetroVault thought before
        // they disagreed, and why they disagreed.
        val promoted = promote("Some Game (USA)")
        corrections.record(correction("c1", CorrectedIdentity.NotThis, at = 10))

        val entry = assertNotNull(queries.provenanceOf(promoted.artifact.entityRef)).corrections.single()

        assertEquals("some game [USA]", entry.previousIdentityDescription)
        assertEquals("I dumped this myself", entry.reason)
    }

    @Test
    fun `a withdrawn correction stays visible and stops applying`() = runTest {
        val promoted = promote("Some Game (USA)")
        corrections.record(correction("c1", CorrectedIdentity.NotThis, at = 10))
        assertIs<Outcome.Success<*>>(corrections.withdraw(scope))

        val report = assertNotNull(queries.provenanceOf(promoted.artifact.entityRef))

        assertEquals(CorrectionState.WITHDRAWN, report.corrections.single().state)
        assertFalse(report.hasActiveCorrection, "A withdrawn correction no longer overrides anything")
    }

    @Test
    fun `a correction against other content does not surface on this artifact`() = runTest {
        val promoted = promote("Some Game (USA)")
        corrections.record(
            correction("c1", CorrectedIdentity.NotThis, at = 10)
                .copy(scope = CorrectionScope(HashAlgorithm.SHA1, "f".repeat(40))),
        )

        val report = assertNotNull(queries.provenanceOf(promoted.artifact.entityRef))

        assertTrue(report.corrections.isEmpty(), "Corrections are keyed by content, not by neighbourhood")
    }

    @Test
    fun `only artifacts carry corrections, because only content can be corrected`() = runTest {
        val promoted = promote("Some Game (USA)")
        corrections.record(correction("c1", CorrectedIdentity.NotThis, at = 10))

        assertTrue(assertNotNull(queries.provenanceOf(promoted.release.entityRef)).corrections.isEmpty())
        assertTrue(assertNotNull(queries.provenanceOf(promoted.work.entityRef)).corrections.isEmpty())
    }

    // ------------------------------------------------------------------
    // Historical identity (section 41, section 70, section 37 invariant 12)
    // ------------------------------------------------------------------

    @Test
    fun `a name an entity used to carry is kept as an alias`() = runTest {
        // The minimum needed to preserve historical identity without inventing
        // a temporal system: the outgoing name survives as an alias, so a user
        // searching for what their library used to be called still finds it.
        val promoted = promote("Some Game (USA)")
        database.execute(
            "UPDATE work_entity SET canonical_title = 'Some Game Deluxe' WHERE id = ?",
            listOf(promoted.work.id.value),
        )

        graph.save(promoted)

        val work = assertNotNull(queries.findWork(promoted.work.id))
        assertEquals(promoted.work.canonicalTitle, work.canonicalTitle)
        assertContains(work.aliases, "Some Game Deluxe")
        assertEquals(1, queries.works(query = "Deluxe").size, "The old name is still findable")
    }

    @Test
    fun `a current name is never also listed as one of its own aliases`() = runTest {
        val promoted = promote("Some Game (USA)")

        graph.save(promoted)

        assertFalse(
            assertNotNull(queries.findWork(promoted.work.id))
                .aliases.contains(promoted.work.canonicalTitle),
        )
    }

    @Test
    fun `timestamps record when an entity was first seen and last changed`() = runTest {
        now = 5_000
        val promoted = promote("Some Game (USA)")

        val first = assertNotNull(queries.provenanceOf(promoted.work.entityRef)).timestamps
        assertEquals(5_000L, first.firstSeenAtEpochMillis)
        assertEquals(5_000L, first.lastUpdatedAtEpochMillis)

        now = 9_000
        database.execute("UPDATE work_entity SET canonical_title = 'Renamed'")
        graph.save(promoted)

        val second = assertNotNull(queries.provenanceOf(promoted.work.entityRef)).timestamps
        assertEquals(5_000L, second.firstSeenAtEpochMillis, "First sighting never moves")
        assertEquals(9_000L, second.lastUpdatedAtEpochMillis)
    }

    @Test
    fun `a row that predates timestamps reports unknown rather than the epoch`() = runTest {
        val promoted = promote("Some Game (USA)")
        database.execute("UPDATE work_entity SET first_seen_at = 0, last_updated_at = 0")

        val timestamps = assertNotNull(queries.provenanceOf(promoted.work.entityRef)).timestamps

        assertNull(timestamps.firstSeenAtEpochMillis)
        assertNull(timestamps.lastUpdatedAtEpochMillis, "1 January 1970 is a claim; unknown is the truth")
    }

    @Test
    fun `a confirmed entity keeps its name and its timestamp against a derived pass`() = runTest {
        now = 5_000
        val promoted = promote("Some Game (USA)")
        database.execute(
            "UPDATE work_entity SET provenance = 'CONFIRMED', canonical_title = 'The Real Title'",
        )

        now = 9_000
        graph.save(promoted)

        val work = assertNotNull(queries.findWork(promoted.work.id))
        assertEquals("The Real Title", work.canonicalTitle)
        assertEquals(
            5_000L,
            assertNotNull(queries.provenanceOf(promoted.work.entityRef)).timestamps.lastUpdatedAtEpochMillis,
            "A skipped write is not an update",
        )
    }

    // ------------------------------------------------------------------
    // Browsing the library
    // ------------------------------------------------------------------

    @Test
    fun `browsing lists works with the number of releases each actually has`() = runTest {
        promote("Some Game (USA)", id = "a")
        promote("Some Game (Europe)", sha1 = "b".repeat(40), id = "b")
        promote("Other Game (USA)", sha1 = "c".repeat(40), id = "c")

        val browse = com.retrovault.application.BrowseLibraryUseCase(queries)
        val works = assertIs<Outcome.Success<List<com.retrovault.application.WorkSummary>>>(
            browse.works(),
        ).value

        assertEquals(2, works.size)
        assertEquals(2, works.single { it.title == "Some Game" }.releaseCount)
        assertEquals(1, works.single { it.title == "Other Game" }.releaseCount)
    }

    @Test
    fun `opening a work shows its releases, digests and where they came from`() = runTest {
        val noIntro = record("Some Game (USA)", id = "a")
        val fromRedump = record("Some Game (USA)", id = "b", from = redump)
        importReady(noIntro)
        importReady(fromRedump, from = redump)
        val promoted = EntityPromoter.promote(noIntro)
        graph.save(promoted)

        val browse = com.retrovault.application.BrowseLibraryUseCase(queries)
        val detail = assertIs<Outcome.Success<com.retrovault.application.WorkDetail>>(
            browse.work(promoted.work.id),
        ).value

        assertEquals("Some Game", detail.title)
        val release = detail.releases.single()
        assertEquals(1, release.artifactCount)
        assertTrue(
            release.artifactDigests.single().startsWith("SHA1 "),
            "A digest is the one thing a user can take elsewhere and check",
        )
        assertEquals(2, detail.independentSourceCount, "Section 196: every contributing dataset is named")
        assertEquals(setOf("no_intro: Test Console", "redump: Test Console"), detail.sources.toSet())
    }

    @Test
    fun `a release with no recorded region says so rather than inventing one`() = runTest {
        val promoted = promote("Some Game")

        val browse = com.retrovault.application.BrowseLibraryUseCase(queries)
        val detail = assertIs<Outcome.Success<com.retrovault.application.WorkDetail>>(
            browse.work(promoted.work.id),
        ).value

        assertEquals("no region recorded", detail.releases.single().label)
    }

    @Test
    fun `opening a work that is gone fails rather than showing an empty one`() = runTest {
        val browse = com.retrovault.application.BrowseLibraryUseCase(queries)

        assertIs<Outcome.Failure>(browse.work(WorkId("nothing-here")))
    }

    // ------------------------------------------------------------------
    // Paging arithmetic
    // ------------------------------------------------------------------

    @Test
    fun `a page reports truncation from what was actually fetched`() {
        assertEquals(EntityPage(listOf(1, 2), hasMore = true), EntityPage.of(listOf(1, 2, 3), limit = 2))
        assertEquals(EntityPage(listOf(1, 2), hasMore = false), EntityPage.of(listOf(1, 2), limit = 2))
        assertTrue(EntityPage.empty<Int>().isEmpty)
    }

    @Test
    fun `a confirmed relationship keeps its provenance in a read`() = runTest {
        val promoted = promote("Some Game (USA)")
        val edge = promoted.relationships.first { it.type == RelationshipType.RELEASE_OF }
        assertIs<Outcome.Success<*>>(
            graph.relate(
                EntityRelationship(
                    from = edge.from,
                    type = edge.type,
                    to = edge.to,
                    provenance = EntityProvenance.CONFIRMED,
                    note = "verified by hand",
                ),
            ),
        )

        val loaded = queries.relationships(promoted.release.entityRef)
            .single { it.type == RelationshipType.RELEASE_OF }

        assertEquals(EntityProvenance.CONFIRMED, loaded.provenance)
        assertEquals("verified by hand", loaded.note)
    }
}

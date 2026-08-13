package com.retrovault.domain.resolution

import com.retrovault.domain.Fixtures
import com.retrovault.domain.catalog.CatalogueCoverage
import com.retrovault.domain.TestCatalogDriver
import com.retrovault.domain.evidence.MatchSignal
import com.retrovault.domain.identity.ContainerKind
import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.HashDigests
import com.retrovault.domain.identity.MediaType
import com.retrovault.domain.policy.AutomationDecision
import com.retrovault.domain.policy.AutomationPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The identification ladder.
 *
 * Every test here exists to defend one rule: RetroVault may miss a match, but
 * it may never present a wrong match as certain.
 */
class ArtifactResolverTest {

    private val goodCrc = Fixtures.crc("aabbccdd")
    private val goodSha1 = Fixtures.sha1("1111")
    private val goodMd5 = Fixtures.md5("2222")

    // ------------------------------------------------------------------
    // Exact identification
    // ------------------------------------------------------------------

    @Test
    fun `an exact sha1 match resolves exactly`() {
        val record = Fixtures.record(
            setName = "Super Mario World (USA)",
            hashes = Fixtures.digests(goodCrc, goodSha1),
        )
        val observation = Fixtures.observation("totally-wrong-name.sfc")
        val driver = TestCatalogDriver(
            records = listOf(record),
            content = mapOf(null to Fixtures.digests(goodCrc, goodSha1)),
        )

        val resolution = driver.resolve(observation)

        assertEquals(ResolutionState.EXACT_HASH, resolution.state)
        assertEquals(ConfidenceLevel.EXACT, resolution.confidence)
        assertEquals(record.id, resolution.selected?.record?.id)
        assertTrue(
            resolution.selected!!.supporting.any {
                it.signal == MatchSignal.HashExact(HashAlgorithm.SHA1)
            },
            "The reason must name the hash that decided it",
        )
    }

    @Test
    fun `a misleading filename never overrides content evidence`() {
        val actual = Fixtures.record(
            setName = "Chrono Trigger (USA)",
            hashes = Fixtures.digests(goodCrc, goodSha1),
        )
        val decoy = Fixtures.record(
            setName = "Super Metroid (USA)",
            size = 999_999,
            hashes = Fixtures.digests(Fixtures.crc("99999999"), Fixtures.sha1("9999")),
        )
        val observation = Fixtures.observation("Super Metroid (USA).sfc")
        val driver = TestCatalogDriver(
            records = listOf(actual, decoy),
            content = mapOf(null to Fixtures.digests(goodCrc, goodSha1)),
        )

        val resolution = driver.resolve(observation)

        assertEquals(ResolutionState.EXACT_HASH, resolution.state)
        assertEquals(
            "Chrono Trigger",
            resolution.selected?.record?.canonicalTitle,
            "Bytes outrank a filename that says something else",
        )
    }

    @Test
    fun `matching both md5 and sha1 is recorded as a multi-hash match`() {
        val record = Fixtures.record(
            setName = "Super Mario World (USA)",
            hashes = Fixtures.digests(goodCrc, goodMd5, goodSha1),
        )
        val driver = TestCatalogDriver(
            records = listOf(record),
            content = mapOf(null to Fixtures.digests(goodCrc, goodMd5, goodSha1)),
        )

        val resolution = driver.resolve(Fixtures.observation("whatever.sfc"))

        assertEquals(ResolutionState.EXACT_MULTI_HASH, resolution.state)
        assertEquals(ConfidenceLevel.EXACT, resolution.confidence)
    }

    @Test
    fun `the same dump in two datasets is corroboration, not ambiguity`() {
        val noIntro = Fixtures.record(
            setName = "Super Mario World (USA)",
            hashes = Fixtures.digests(goodCrc, goodSha1),
            source = Fixtures.source(provider = "no_intro"),
        )
        val redump = Fixtures.record(
            setName = "Super Mario World (USA)",
            hashes = Fixtures.digests(goodCrc, goodSha1),
            source = Fixtures.source(provider = "redump"),
        )
        val driver = TestCatalogDriver(
            records = listOf(noIntro, redump),
            content = mapOf(null to Fixtures.digests(goodCrc, goodSha1)),
        )

        val resolution = driver.resolve(Fixtures.observation("mario.sfc"))

        assertEquals(ResolutionState.EXACT_HASH, resolution.state)
        assertEquals(2, resolution.selected?.independentSourceCount)
        assertTrue(
            resolution.selected!!.supporting.any {
                it.signal is MatchSignal.CorroboratedByIndependentSources
            },
        )
    }

    // ------------------------------------------------------------------
    // Ambiguity and conflict
    // ------------------------------------------------------------------

    @Test
    fun `identical bytes described as two different releases stay ambiguous`() {
        val japan = Fixtures.record(
            setName = "Some Game (Japan)",
            hashes = Fixtures.digests(goodCrc, goodSha1),
        )
        val usa = Fixtures.record(
            setName = "Some Game (USA)",
            hashes = Fixtures.digests(goodCrc, goodSha1),
        )
        val driver = TestCatalogDriver(
            records = listOf(japan, usa),
            content = mapOf(null to Fixtures.digests(goodCrc, goodSha1)),
        )

        val resolution = driver.resolve(Fixtures.observation("some game.sfc"))

        assertEquals(ResolutionState.AMBIGUOUS, resolution.state)
        assertNull(resolution.selected, "Ambiguity must never carry a selected identity")
        assertEquals(2, resolution.candidates.size)
        assertTrue(
            resolution.candidates.all { candidate ->
                candidate.contradicting.any { it.signal is MatchSignal.SharedHashAcrossIdentities }
            },
        )
    }

    @Test
    fun `an ambiguous crc32 escalates to a stronger hash and resolves`() {
        val wanted = Fixtures.record(
            setName = "Game A (USA)",
            hashes = Fixtures.digests(goodCrc, Fixtures.sha1("aaaa")),
        )
        val collision = Fixtures.record(
            setName = "Game B (Japan)",
            hashes = Fixtures.digests(goodCrc, Fixtures.sha1("cccc")),
        )
        val driver = TestCatalogDriver(
            records = listOf(wanted, collision),
            content = mapOf(null to Fixtures.digests(goodCrc, Fixtures.sha1("aaaa"))),
        )

        val resolution = driver.resolve(Fixtures.observation("unknown.sfc"))

        assertEquals(ResolutionState.EXACT_HASH, resolution.state)
        assertEquals(wanted.id, resolution.selected?.record?.id)
        assertTrue(
            driver.requests.any {
                it is EvidenceRequest.ComputeHashes && HashAlgorithm.SHA1 in it.algorithms
            },
            "A CRC32 collision must trigger escalation, not a coin toss",
        )
    }

    @Test
    fun `crc32 agreeing while sha1 disagrees is a conflict, never a match`() {
        val record = Fixtures.record(
            setName = "Game A (USA)",
            hashes = Fixtures.digests(goodCrc, Fixtures.sha1("aaaa")),
        )
        val driver = TestCatalogDriver(
            records = listOf(record),
            content = mapOf(null to Fixtures.digests(goodCrc, Fixtures.sha1("ffff"))),
        )

        val resolution = driver.resolve(Fixtures.observation("Game A (USA).sfc"))

        assertEquals(ResolutionState.CONFLICT, resolution.state)
        assertNull(resolution.selected)
        assertTrue(
            resolution.candidates.single().contradicting.any { it.signal is MatchSignal.HashMismatch },
        )
    }

    @Test
    fun `a crc32 collision with a different size never becomes a strict match`() {
        val record = Fixtures.record(
            setName = "Game A (USA)",
            size = 1_048_576,
            hashes = Fixtures.digests(goodCrc),
        )
        val driver = TestCatalogDriver(
            records = listOf(record),
            content = mapOf(null to Fixtures.digests(goodCrc)),
        )

        val resolution = driver.resolve(Fixtures.observation("Game A (USA).sfc", size = 524_288))

        // Constitution section 200: differing bytes do not immediately mean a
        // different release, so the record survives as a reviewable candidate.
        // What must never happen is presenting it as content-level identity.
        assertFalse(resolution.state.isExact)
        assertEquals(ResolutionState.FUZZY_MATCH, resolution.state)
        assertTrue(
            resolution.selected!!.contradicting.any { it.signal is MatchSignal.SizeMismatch },
            "The size disagreement must be stated, not hidden",
        )
        assertEquals(
            AutomationDecision.REQUIRES_REVIEW,
            AutomationPolicy().decide(resolution),
            "A file whose bytes disagree with the catalogue is never renamed automatically",
        )
    }

    // ------------------------------------------------------------------
    // Structural matching when the catalogue has no strong hash
    // ------------------------------------------------------------------

    @Test
    fun `crc32 plus size against a hash-poor record is strong, not exact`() {
        val record = Fixtures.record(
            setName = "Some Game (Europe)",
            hashes = Fixtures.digests(goodCrc),
        )
        val driver = TestCatalogDriver(
            records = listOf(record),
            content = mapOf(null to Fixtures.digests(goodCrc)),
        )

        val resolution = driver.resolve(Fixtures.observation("scrambled.sfc"))

        assertEquals(ResolutionState.STRUCTURAL_MATCH, resolution.state)
        assertEquals(ConfidenceLevel.STRONG, resolution.confidence)
        assertTrue(
            resolution.selected!!.contradicting.any {
                it.signal == MatchSignal.CatalogHasNoCryptographicHash
            },
            "The user must be told that only CRC32 was available",
        )
    }

    // ------------------------------------------------------------------
    // Size filtering and fallback
    // ------------------------------------------------------------------

    @Test
    fun `a size absent from the catalogue is reported, not treated as proof`() {
        val record = Fixtures.record(
            setName = "Super Mario World (USA)",
            size = 524_288,
            hashes = Fixtures.digests(goodCrc, goodSha1),
        )
        val driver = TestCatalogDriver(records = listOf(record))

        // A trimmed copy: same game, fewer bytes.
        val resolution = driver.resolve(Fixtures.observation("Super Mario World (USA).sfc", size = 500_000))

        assertTrue(
            resolution.pipelineEvidence.any { it.signal == MatchSignal.SizeAbsentFromCatalog },
            "Constitution section 151: size filtering must be visible",
        )
        assertFalse(
            driver.requests.any { it is EvidenceRequest.ComputeHashes },
            "Hashing must be skipped when no catalogued size matches",
        )
        assertEquals(ResolutionState.FUZZY_MATCH, resolution.state)
        assertEquals(ConfidenceLevel.PROBABLE, resolution.confidence)
    }

    @Test
    fun `a fuzzy match records the size disagreement that kept it fuzzy`() {
        val record = Fixtures.record(
            setName = "Super Mario World (USA)",
            size = 524_288,
            hashes = Fixtures.digests(goodCrc, goodSha1),
        )
        val driver = TestCatalogDriver(records = listOf(record))

        val resolution = driver.resolve(Fixtures.observation("Super Mario World (USA).sfc", size = 500_000))

        assertEquals(ResolutionState.FUZZY_MATCH, resolution.state)
        assertTrue(resolution.selected!!.contradicting.any { it.signal is MatchSignal.SizeMismatch })
    }

    @Test
    fun `a scene-style filename can be identified by fallback`() {
        val record = Fixtures.record(
            setName = "Super Mario World (USA)",
            size = 524_288,
            hashes = HashDigests.EMPTY,
        )
        val driver = TestCatalogDriver(records = listOf(record))

        val resolution = driver.resolve(
            Fixtures.observation("Super.Mario.World.USA.SNES-Group.sfc", size = 524_288),
        )

        assertEquals(ResolutionState.STRONG_METADATA_MATCH, resolution.state)
        assertEquals(record.id, resolution.selected?.record?.id)
    }

    @Test
    fun `a scrubbed filename with no tokens still finds its title`() {
        val record = Fixtures.record(
            setName = "Super Mario World (USA)",
            size = 524_288,
            hashes = HashDigests.EMPTY,
        )
        val driver = TestCatalogDriver(records = listOf(record))

        val resolution = driver.resolve(Fixtures.observation("super mario world.sfc", size = 524_288))

        assertEquals(ResolutionState.STRONG_METADATA_MATCH, resolution.state)
    }

    @Test
    fun `region variants are not collapsed by the fallback`() {
        val usa = Fixtures.record("Some Game (USA)", size = 524_288)
        val europe = Fixtures.record("Some Game (Europe)", size = 524_288)
        val driver = TestCatalogDriver(records = listOf(usa, europe))

        val resolution = driver.resolve(Fixtures.observation("Some Game (Europe).sfc", size = 524_288))

        assertEquals(europe.id, resolution.selected?.record?.id, "The region token must decide")
        assertTrue(
            resolution.candidates.any { candidate ->
                candidate.record.id == usa.id &&
                    candidate.contradicting.any { it.signal is MatchSignal.RegionConflict }
            },
            "The rejected region variant stays visible with its reason",
        )
    }

    @Test
    fun `region variants with no region token in the filename stay ambiguous`() {
        val usa = Fixtures.record("Some Game (USA)", size = 524_288)
        val europe = Fixtures.record("Some Game (Europe)", size = 524_288)
        val driver = TestCatalogDriver(records = listOf(usa, europe))

        val resolution = driver.resolve(Fixtures.observation("Some Game.sfc", size = 524_288))

        assertEquals(ResolutionState.AMBIGUOUS, resolution.state)
        assertNull(resolution.selected)
    }

    @Test
    fun `revisions are not collapsed by the fallback`() {
        val revA = Fixtures.record("Some Game (USA) (Rev A)", size = 524_288)
        val original = Fixtures.record("Some Game (USA)", size = 524_288)
        val driver = TestCatalogDriver(records = listOf(revA, original))

        val resolution = driver.resolve(Fixtures.observation("Some Game (USA) (Rev A).sfc", size = 524_288))

        assertEquals(revA.id, resolution.selected?.record?.id)
    }

    @Test
    fun `a sequel is never proposed for its predecessor`() {
        val sequel = Fixtures.record("Super Mario Bros. 2 (USA)", size = 524_288)
        val driver = TestCatalogDriver(records = listOf(sequel))

        val resolution = driver.resolve(Fixtures.observation("Super Mario Bros. (USA).sfc", size = 524_288))

        assertEquals(ResolutionState.NO_MATCH, resolution.state)
        assertNull(resolution.selected)
    }

    @Test
    fun `nothing plausible produces no match`() {
        val driver = TestCatalogDriver(records = listOf(Fixtures.record("Chrono Trigger (USA)")))

        val resolution = driver.resolve(Fixtures.observation("holiday-photos.sfc", size = 1))

        assertEquals(ResolutionState.NO_MATCH, resolution.state)
        assertEquals(ConfidenceLevel.UNKNOWN, resolution.confidence)
    }

    @Test
    fun `an empty catalogue never matches anything`() {
        val driver = TestCatalogDriver(records = emptyList())
        val resolution = driver.resolve(Fixtures.observation("Super Mario World (USA).sfc"))

        assertNull(resolution.selected)
        assertEquals(
            ResolutionState.OUT_OF_CATALOGUE_SCOPE,
            resolution.state,
            "With nothing imported the catalogue has no standing to call a file unmatched",
        )
        assertTrue(
            resolution.explanation.any { it.signal == MatchSignal.NoDatasetsImported },
            "The reason must name the empty catalogue, not the file",
        )
    }

    @Test
    fun `a caller that did not measure coverage still reports a plain absence of a match`() {
        // Backwards-compatible honesty: without measured coverage the resolver
        // makes no claim about scope, so it must not invent one.
        val driver = TestCatalogDriver(
            records = emptyList(),
            coverage = CatalogueCoverage.UNMEASURED,
        )

        val resolution = driver.resolve(Fixtures.observation("Super Mario World (USA).sfc"))

        assertEquals(ResolutionState.NO_MATCH, resolution.state)
    }

    // ------------------------------------------------------------------
    // Archives
    // ------------------------------------------------------------------

    @Test
    fun `a zip holding one rom is identified from the contained entry`() {
        val record = Fixtures.record(
            setName = "Super Mario World (USA)",
            romName = "Super Mario World (USA).sfc",
            hashes = Fixtures.digests(goodCrc, goodSha1),
        )
        val observation = Fixtures.observation(
            filename = "smw.zip",
            size = 300_000,
            container = ContainerKind.ZIP,
            archiveEntries = listOf(
                Fixtures.zipEntry(
                    "Super Mario World (USA).sfc",
                    size = 524_288,
                    hashes = Fixtures.digests(goodCrc),
                ),
            ),
        )
        val driver = TestCatalogDriver(
            records = listOf(record),
            content = mapOf("Super Mario World (USA).sfc" to Fixtures.digests(goodCrc, goodSha1)),
        )

        val resolution = driver.resolve(observation)

        assertEquals(ResolutionState.EXACT_HASH, resolution.state)
        assertEquals(record.id, resolution.selected?.record?.id)
    }

    @Test
    fun `a crc32 already known from the zip directory is not recomputed`() {
        val record = Fixtures.record(setName = "Some Game (USA)", hashes = Fixtures.digests(goodCrc))
        val observation = Fixtures.observation(
            filename = "game.zip",
            container = ContainerKind.ZIP,
            archiveEntries = listOf(
                Fixtures.zipEntry("game.sfc", hashes = Fixtures.digests(goodCrc)),
            ),
        )
        val driver = TestCatalogDriver(records = listOf(record))

        driver.resolve(observation)

        assertFalse(
            driver.requests.any {
                it is EvidenceRequest.ComputeHashes && it.algorithms == setOf(HashAlgorithm.CRC32)
            },
            "The ZIP central directory already provided CRC32",
        )
    }

    @Test
    fun `a zip holding several roms has no single identity`() {
        val observation = Fixtures.observation(
            filename = "collection.zip",
            container = ContainerKind.ZIP,
            archiveEntries = listOf(
                Fixtures.zipEntry("a.sfc"),
                Fixtures.zipEntry("b.sfc"),
            ),
        )
        val driver = TestCatalogDriver(records = listOf(Fixtures.record("Some Game (USA)")))

        val resolution = driver.resolve(observation)

        assertEquals(ResolutionState.UNSUPPORTED, resolution.state)
        assertNull(resolution.selected)
        assertTrue(
            resolution.pipelineEvidence.any { it.signal is MatchSignal.ArchiveHasMultipleArtifacts },
        )
    }

    @Test
    fun `an unsupported archive format is reported as unsupported`() {
        val observation = Fixtures.observation(
            filename = "game.7z",
            container = ContainerKind.UNSUPPORTED_ARCHIVE,
        )
        val driver = TestCatalogDriver(records = listOf(Fixtures.record("Some Game (USA)")))

        assertEquals(ResolutionState.UNSUPPORTED, driver.resolve(observation).state)
    }

    @Test
    fun `an empty archive is reported as unsupported`() {
        val observation = Fixtures.observation(
            filename = "empty.zip",
            container = ContainerKind.ZIP,
            archiveEntries = emptyList(),
        )
        val driver = TestCatalogDriver(records = emptyList())

        assertEquals(ResolutionState.UNSUPPORTED, driver.resolve(observation).state)
    }

    // ------------------------------------------------------------------
    // Failure handling
    // ------------------------------------------------------------------

    @Test
    fun `an unreadable file falls back instead of failing the scan`() {
        val record = Fixtures.record(setName = "Some Game (USA)", hashes = Fixtures.digests(goodCrc, goodSha1))
        val driver = TestCatalogDriver(
            records = listOf(record),
            unreadable = setOf(HashAlgorithm.CRC32),
        )

        val resolution = driver.resolve(Fixtures.observation("Some Game (USA).sfc"))

        assertTrue(resolution.pipelineEvidence.any { it.signal is MatchSignal.HashUnavailable })
        assertFalse(resolution.state.isExact, "A file that could not be read is never exactly identified")
    }

    @Test
    fun `a strong hash that cannot be read degrades to structural, not exact`() {
        val record = Fixtures.record(setName = "Some Game (USA)", hashes = Fixtures.digests(goodCrc, goodSha1))
        val driver = TestCatalogDriver(
            records = listOf(record),
            content = mapOf(null to Fixtures.digests(goodCrc)),
            unreadable = setOf(HashAlgorithm.SHA1),
            // Two-pass mode: CRC32 is read first and succeeds, then the
            // escalation to SHA1 fails. This is the only arrangement in which
            // the resolver holds a CRC32 it cannot corroborate.
            resolver = ArtifactResolver(ResolverConfig(computeStrongHashesUpFront = false)),
        )

        val resolution = driver.resolve(Fixtures.observation("Some Game (USA).sfc"))

        assertEquals(ResolutionState.STRUCTURAL_MATCH, resolution.state)
        assertEquals(ConfidenceLevel.STRONG, resolution.confidence)
    }

    @Test
    fun `strong hashes are computed in the same pass as crc32 by default`() {
        val record = Fixtures.record(setName = "Some Game (USA)", hashes = Fixtures.digests(goodCrc, goodSha1))
        val driver = TestCatalogDriver(
            records = listOf(record),
            content = mapOf(null to Fixtures.digests(goodCrc, goodSha1)),
        )

        val resolution = driver.resolve(Fixtures.observation("Some Game (USA).sfc"))

        val reads = driver.requests.filterIsInstance<EvidenceRequest.ComputeHashes>()
        assertEquals(1, reads.size, "The file must be read once, not once per algorithm: $reads")
        assertTrue(HashAlgorithm.SHA1 in reads.single().algorithms)
        assertEquals(ResolutionState.EXACT_HASH, resolution.state)
    }

    @Test
    fun `one pass and two pass hashing reach the same conclusion`() {
        val record = Fixtures.record(setName = "Some Game (USA)", hashes = Fixtures.digests(goodCrc, goodSha1))
        val content = mapOf<String?, HashDigests>(null to Fixtures.digests(goodCrc, goodSha1))
        val observation = Fixtures.observation("Some Game (USA).sfc")

        val onePass = TestCatalogDriver(listOf(record), content).resolve(observation)
        val twoPass = TestCatalogDriver(
            records = listOf(record),
            content = content,
            resolver = ArtifactResolver(ResolverConfig(computeStrongHashesUpFront = false)),
        ).resolve(observation)

        assertEquals(twoPass.state, onePass.state)
        assertEquals(twoPass.confidence, onePass.confidence)
        assertEquals(twoPass.selected?.record?.id, onePass.selected?.record?.id)
    }

    @Test
    fun `an unavailable catalogue never invents a match`() {
        val driver = TestCatalogDriver(
            records = listOf(Fixtures.record("Some Game (USA)")),
            catalogUnavailableFor = setOf(
                EvidenceRequest.CatalogLookupBySize::class.java,
                EvidenceRequest.CatalogLookupByTitle::class.java,
            ),
        )

        val resolution = driver.resolve(Fixtures.observation("Some Game (USA).sfc"))

        assertNull(resolution.selected)
    }

    // ------------------------------------------------------------------
    // Determinism and provenance
    // ------------------------------------------------------------------

    @Test
    fun `the same inputs always produce the same resolution`() {
        val records = listOf(
            Fixtures.record("Some Game (USA)", hashes = Fixtures.digests(goodCrc, goodSha1)),
            Fixtures.record("Some Game (Europe)", hashes = Fixtures.digests(Fixtures.crc("12121212"))),
        )
        val observation = Fixtures.observation("some game.sfc")
        val content = mapOf<String?, HashDigests>(null to Fixtures.digests(goodCrc, goodSha1))

        val first = TestCatalogDriver(records, content).resolve(observation)
        val second = TestCatalogDriver(records, content).resolve(observation)

        assertEquals(first.state, second.state)
        assertEquals(first.selected?.record?.id, second.selected?.record?.id)
        assertEquals(
            first.explanation.map { it.signal.id },
            second.explanation.map { it.signal.id },
        )
    }

    @Test
    fun `every resolution records the algorithm versions that produced it`() {
        val driver = TestCatalogDriver(records = emptyList())
        val resolution = driver.resolve(Fixtures.observation("anything.sfc"))

        assertEquals(ArtifactResolver.VERSION, resolution.resolverVersion)
        assertNotNull(resolution.tokenizerVersion)
        assertNotNull(resolution.normalizerVersion)
    }

    @Test
    fun `a resolution may never carry a selection its state forbids`() {
        val failure = runCatching {
            ArtifactResolution(
                observationId = Fixtures.observation("x.sfc").id,
                state = ResolutionState.AMBIGUOUS,
                confidence = ConfidenceLevel.AMBIGUOUS,
                selected = Candidate(record = Fixtures.record("Some Game (USA)")),
                candidates = emptyList(),
                pipelineEvidence = emptyList(),
                hashesComputed = emptySet(),
                consultedSources = emptyList(),
                resolverVersion = "v",
                tokenizerVersion = "v",
                normalizerVersion = "v",
            )
        }
        assertTrue(failure.isFailure, "The invariant must be enforced by the type, not by convention")
    }

    // ------------------------------------------------------------------
    // Multi-file releases: a title alone cannot say which track this is
    // ------------------------------------------------------------------

    @Test
    fun `a multi-track release matched only by title stays ambiguous`() {
        // The release is identified, but the file is not: renaming a .bin to
        // the cue sheet's name is worse than leaving it alone.
        val cue = Fixtures.record(
            setName = "Some Game (USA)",
            romName = "Some Game (USA).cue",
            size = 524_288,
            id = "cue",
        )
        val bin = Fixtures.record(
            setName = "Some Game (USA)",
            romName = "Some Game (USA) (Track 1).bin",
            size = 524_288,
            id = "bin",
        )
        val driver = TestCatalogDriver(records = listOf(cue, bin))

        val resolution = driver.resolve(Fixtures.observation("Some Game (USA).iso", size = 524_288))

        assertEquals(ResolutionState.AMBIGUOUS, resolution.state)
        assertNull(resolution.selected)
    }

    @Test
    fun `the on-disk extension picks the track when exactly one matches`() {
        val cue = Fixtures.record(
            setName = "Some Game (USA)",
            romName = "Some Game (USA).cue",
            size = 524_288,
            id = "cue",
        )
        val bin = Fixtures.record(
            setName = "Some Game (USA)",
            romName = "Some Game (USA) (Track 1).bin",
            size = 524_288,
            id = "bin",
        )
        val driver = TestCatalogDriver(records = listOf(cue, bin))

        val resolution = driver.resolve(Fixtures.observation("Some Game (USA).cue", size = 524_288))

        assertEquals("cue", resolution.selected?.record?.id?.value)
    }

    @Test
    fun `a single-file release is unaffected by the multi-track guard`() {
        val record = Fixtures.record("Some Game (USA)", size = 524_288)
        val driver = TestCatalogDriver(records = listOf(record))

        val resolution = driver.resolve(Fixtures.observation("Some Game (USA).sfc", size = 524_288))

        assertEquals(record.id, resolution.selected?.record?.id)
    }

    // ------------------------------------------------------------------
    // Noisy filenames
    // ------------------------------------------------------------------

    @Test
    fun `a site watermark does not prevent a title match`() {
        val record = Fixtures.record("Super Mario World (USA)", size = 524_288)
        val driver = TestCatalogDriver(records = listOf(record))

        val resolution = driver.resolve(
            Fixtures.observation("www.example.com - Super Mario World (USA).sfc", size = 524_288),
        )

        assertEquals(record.id, resolution.selected?.record?.id)
    }

    @Test
    fun `a trailing scene tag does not prevent a title match`() {
        val record = Fixtures.record("Red Hot Rumble (USA)", size = 524_288)
        val driver = TestCatalogDriver(records = listOf(record))

        val resolution = driver.resolve(
            Fixtures.observation("Red Hot Rumble (USA)-memorypsp.sfc", size = 524_288),
        )

        assertEquals(record.id, resolution.selected?.record?.id)
    }

    @Test
    fun `stripping a tag never rescues a sequel mismatch`() {
        // The stripped reading must not be allowed to override a numbering
        // conflict found in the unstripped one.
        val sequel = Fixtures.record("Some Game 2 (USA)", size = 524_288)
        val driver = TestCatalogDriver(records = listOf(sequel))

        val resolution = driver.resolve(Fixtures.observation("Some Game (USA)-group.sfc", size = 524_288))

        assertNull(resolution.selected)
    }

    // ------------------------------------------------------------------
    // Records the catalogue states no size for
    // ------------------------------------------------------------------

    @Test
    fun `a record with no stated size can still be matched exactly on its hash`() {
        val record = Fixtures.record(
            setName = "Arcade Thing (USA)",
            romName = "arcadething",
            size = null,
            hashes = Fixtures.digests(goodCrc, goodSha1),
        )
        val driver = TestCatalogDriver(
            records = listOf(record),
            content = mapOf(null to Fixtures.digests(goodCrc, goodSha1)),
        )

        val resolution = driver.resolve(Fixtures.observation("arcadething", size = 524_288))

        assertEquals(ResolutionState.EXACT_HASH, resolution.state)
        assertEquals(record.id, resolution.selected?.record?.id)
    }

    @Test
    fun `an unknown catalogue size produces no size evidence in either direction`() {
        val record = Fixtures.record(
            setName = "Arcade Thing (USA)",
            romName = "arcadething",
            size = null,
            hashes = Fixtures.digests(goodCrc, goodSha1),
        )
        val driver = TestCatalogDriver(
            records = listOf(record),
            content = mapOf(null to Fixtures.digests(goodCrc, goodSha1)),
        )

        val resolution = driver.resolve(Fixtures.observation("arcadething", size = 999_999))

        assertNull(
            resolution.selected?.contradicting?.firstOrNull { it.signal is MatchSignal.SizeMismatch },
            "An absent size cannot contradict anything",
        )
        assertEquals(ResolutionState.EXACT_HASH, resolution.state)
    }

    // ------------------------------------------------------------------
    // Optical media: the PSP UMD case
    // ------------------------------------------------------------------

    private val redump = Fixtures.source(provider = "redump", platform = Fixtures.psp)
    private val noIntroSnes = Fixtures.source(provider = "no_intro", platform = Fixtures.snes)

    private fun pspRecord(
        setName: String = "Some PSP Game (USA)",
        hashes: HashDigests = Fixtures.digests(goodCrc, goodSha1),
        size: Long? = 1_500_000_000L,
    ) = Fixtures.record(
        setName = setName,
        romName = "$setName.iso",
        size = size,
        hashes = hashes,
        source = redump,
        id = "redump:$setName",
    )

    @Test
    fun `a psp iso is treated as optical disc media`() {
        val observation = Fixtures.observation("Some PSP Game (USA).iso", size = 1_500_000_000L)

        assertEquals(MediaType.OPTICAL_DISC, observation.mediaType)
    }

    @Test
    fun `a psp iso matched against a redump dataset verifies exactly`() {
        val record = pspRecord()
        val driver = TestCatalogDriver(
            records = listOf(record),
            content = mapOf(null to Fixtures.digests(goodCrc, goodSha1)),
        )

        val resolution = driver.resolve(
            Fixtures.observation("umd-rip-whatever.iso", size = 1_500_000_000L),
        )

        assertEquals(ResolutionState.EXACT_HASH, resolution.state)
        assertEquals(IdentityBasis.VERIFIED_CONTENT, resolution.identityBasis)
        assertTrue(resolution.isVerified)
        assertEquals(record.id, resolution.selected?.record?.id)
        assertEquals(MediaType.OPTICAL_DISC, resolution.selected?.record?.mediaType)
    }

    @Test
    fun `a psp library scanned against a cartridge dataset is uncatalogued, not unidentified`() {
        // The case this whole mechanism exists for. Reporting NO_MATCH here
        // tells the user their files are unrecognisable; the truth is that the
        // right dataset has not been imported, and the remedy is different.
        val cartridgeOnly = Fixtures.record(
            setName = "Super Mario World (USA)",
            hashes = Fixtures.digests(goodCrc, goodSha1),
            source = noIntroSnes,
        )
        val driver = TestCatalogDriver(records = listOf(cartridgeOnly))

        val resolution = driver.resolve(
            Fixtures.observation("Some PSP Game (USA).iso", size = 1_500_000_000L),
        )

        assertEquals(ResolutionState.OUT_OF_CATALOGUE_SCOPE, resolution.state)
        assertNull(resolution.selected)
        assertEquals(IdentityBasis.NONE, resolution.identityBasis)
        val signal = resolution.explanation.map { it.signal }.filterIsInstance<MatchSignal.MediaNotCovered>()
        assertEquals(MediaType.OPTICAL_DISC, signal.single().observed)
        assertEquals(setOf(MediaType.CARTRIDGE), signal.single().available)
    }

    @Test
    fun `an out of scope file is never hashed`() {
        // Constitution section 324: avoid unnecessary work before avoiding
        // unnecessary I/O. Reading 1.5 GB to prove a cartridge dataset does not
        // list a disc is the definition of unnecessary.
        val driver = TestCatalogDriver(
            records = listOf(
                Fixtures.record("Super Mario World (USA)", source = noIntroSnes),
            ),
        )

        driver.resolve(Fixtures.observation("Some PSP Game (USA).iso", size = 1_500_000_000L))

        assertTrue(
            driver.requests.none { it is EvidenceRequest.ComputeHashes },
            "No bytes should be read for a file no dataset covers: ${driver.requests}",
        )
    }

    @Test
    fun `a disc dataset that simply does not list this disc reports a plain absence`() {
        // Distinct from out-of-scope: the catalogue does cover discs and still
        // does not describe this one, which is weak evidence about the file.
        val other = pspRecord(setName = "Another PSP Game (USA)")
        val driver = TestCatalogDriver(
            records = listOf(other),
            content = mapOf(null to Fixtures.digests(Fixtures.crc("11223344"))),
        )

        val resolution = driver.resolve(
            Fixtures.observation("Unlisted PSP Game (USA).iso", size = 900_000_000L),
        )

        assertEquals(ResolutionState.NO_MATCH, resolution.state)
        assertTrue(
            resolution.explanation.none { it.signal is MatchSignal.MediaNotCovered },
            "The medium is covered, so nothing should claim otherwise",
        )
    }

    @Test
    fun `a mixed catalogue covers a psp library once a disc dataset is imported`() {
        val driver = TestCatalogDriver(
            records = listOf(
                Fixtures.record("Super Mario World (USA)", source = noIntroSnes),
                pspRecord(),
            ),
            content = mapOf(null to Fixtures.digests(goodCrc, goodSha1)),
        )

        val resolution = driver.resolve(
            Fixtures.observation("Some PSP Game (USA).iso", size = 1_500_000_000L),
        )

        assertEquals(ResolutionState.EXACT_HASH, resolution.state)
    }

    @Test
    fun `a cartridge record never outranks a disc record for a disc image`() {
        // Both titles match. The medium is what separates them, and it must
        // rank rather than exclude - the same release can exist in both forms.
        val disc = pspRecord(setName = "Some Game (USA)", hashes = HashDigests.EMPTY, size = 700_000_000L)
        val cartridge = Fixtures.record(
            setName = "Some Game (USA)",
            size = 700_000_000L,
            source = noIntroSnes,
            id = "no_intro:cart",
        )
        val driver = TestCatalogDriver(records = listOf(disc, cartridge))

        val resolution = driver.resolve(Fixtures.observation("Some Game (USA).iso", size = 700_000_000L))

        assertEquals(disc.id, resolution.selected?.record?.id)
        val rejected = resolution.candidates.single { it.record.id == cartridge.id }
        assertTrue(
            rejected.contradicting.any { it.signal is MatchSignal.MediaTypeMismatch },
            "The rejected candidate must carry the reason it lost",
        )
        assertFalse(
            rejected.isExcluded,
            "A medium disagreement weakens a candidate; it does not rule it out",
        )
    }

    @Test
    fun `a disc preserved in another form is still proposed`() {
        // The user has a `.chd`; Redump lists the `.cue`. Both are optical
        // media, so nothing here should even register as a disagreement.
        val record = Fixtures.record(
            setName = "Some Game (USA)",
            romName = "Some Game (USA).cue",
            size = 700_000_000L,
            source = redump,
        )
        val driver = TestCatalogDriver(records = listOf(record))

        val resolution = driver.resolve(Fixtures.observation("Some Game (USA).chd", size = 700_000_000L))

        assertEquals(record.id, resolution.selected?.record?.id)
        assertTrue(
            resolution.selected!!.contradicting.none { it.signal is MatchSignal.MediaTypeMismatch },
        )
    }

    // ------------------------------------------------------------------
    // Verified against inferred
    // ------------------------------------------------------------------

    @Test
    fun `a hash match is verified and a filename match is only inferred`() {
        val hashed = Fixtures.record(
            setName = "Some PSP Game (USA)",
            romName = "Some PSP Game (USA).iso",
            size = 1_500_000_000L,
            hashes = Fixtures.digests(goodCrc, goodSha1),
            source = redump,
        )
        val verified = TestCatalogDriver(
            records = listOf(hashed),
            content = mapOf(null to Fixtures.digests(goodCrc, goodSha1)),
        ).resolve(Fixtures.observation("anything.iso", size = 1_500_000_000L))

        val nameOnly = Fixtures.record(
            setName = "Some PSP Game (USA)",
            romName = "Some PSP Game (USA).iso",
            size = 1_500_000_000L,
            hashes = HashDigests.EMPTY,
            source = redump,
        )
        val inferred = TestCatalogDriver(records = listOf(nameOnly))
            .resolve(Fixtures.observation("Some PSP Game (USA).iso", size = 1_500_000_000L))

        assertTrue(verified.isVerified)
        assertEquals(IdentityBasis.VERIFIED_CONTENT, verified.identityBasis)

        assertFalse(inferred.isVerified, "A filename is not evidence about the bytes")
        assertEquals(IdentityBasis.INFERRED, inferred.identityBasis)
        assertNotNull(inferred.selected, "Inferred identity is still a usable result")
    }

    @Test
    fun `every state reports an identity basis consistent with its selection rule`() {
        ResolutionState.entries.forEach { state ->
            if (state.canCarrySelection) {
                assertTrue(
                    state.identityBasis != IdentityBasis.NONE,
                    "$state may carry an identity, so it must say what that identity rests on",
                )
            } else {
                assertEquals(
                    IdentityBasis.NONE,
                    state.identityBasis,
                    "$state carries no identity, so nothing may rest on it",
                )
            }
        }
    }

    @Test
    fun `an inferred identity is never renamed without confirmation`() {
        val record = Fixtures.record(
            setName = "Some PSP Game (USA)",
            romName = "Some PSP Game (USA).iso",
            size = 1_500_000_000L,
            hashes = HashDigests.EMPTY,
            source = redump,
        )
        val resolution = TestCatalogDriver(records = listOf(record))
            .resolve(Fixtures.observation("Some PSP Game (USA).iso", size = 1_500_000_000L))

        assertEquals(AutomationDecision.REQUIRES_REVIEW, AutomationPolicy().decide(resolution))
    }

    @Test
    fun `an out of scope file is never renamed at all`() {
        val driver = TestCatalogDriver(
            records = listOf(Fixtures.record("Super Mario World (USA)", source = noIntroSnes)),
        )
        val resolution = driver.resolve(Fixtures.observation("Some PSP Game (USA).iso"))

        assertEquals(AutomationDecision.FORBIDDEN, AutomationPolicy().decide(resolution))
    }

    @Test
    fun `an exact state is never claimed without an algorithm that verified it`() {
        // A catalogue answering a SHA1 lookup with a record carrying no SHA1
        // would otherwise reach EXACT_HASH with nothing verified. CRC32 and size
        // still agree, which is exactly what a structural match is - so the
        // result degrades rather than overstating.
        val hashless = Fixtures.record(
            setName = "Some Game (USA)",
            hashes = Fixtures.digests(goodCrc),
        )
        val driver = TestCatalogDriver(
            records = listOf(hashless),
            content = mapOf(null to Fixtures.digests(goodCrc, goodSha1)),
            answerEveryHashLookupWith = listOf(hashless),
        )

        val resolution = driver.resolve(Fixtures.observation("Some Game (USA).sfc"))

        assertFalse(
            resolution.state.isExact,
            "EXACT was claimed with no verified hash: ${resolution.state}",
        )
        assertFalse(resolution.isVerified)
        assertEquals(ResolutionState.STRUCTURAL_MATCH, resolution.state)
    }
}

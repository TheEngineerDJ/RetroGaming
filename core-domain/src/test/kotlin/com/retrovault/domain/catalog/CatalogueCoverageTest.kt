package com.retrovault.domain.catalog

import com.retrovault.domain.Fixtures
import com.retrovault.domain.identity.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Whether the imported datasets could plausibly describe a file.
 *
 * The rule this defends is Constitution section 174: absence of a record is a
 * statement about the catalogue, not about the artifact. Every uncertain case
 * must read as "covered", so that a scope judgement can never be the reason a
 * real match is missed.
 */
class CatalogueCoverageTest {

    private fun dataset(vararg media: MediaType, count: Int = 100) = DatasetCoverage(
        source = Fixtures.source(),
        mediaTypes = media.toSet(),
        recordCount = count,
    )

    private fun coverage(vararg datasets: DatasetCoverage) = CatalogueCoverage(datasets.toList())

    @Test
    fun `a disc image against a cartridge-only catalogue is out of scope`() {
        val assessment = DatasetCompatibility.assess(
            MediaType.OPTICAL_DISC,
            coverage(dataset(MediaType.CARTRIDGE)),
        )

        val notCovered = assertIs<CoverageAssessment.MediaNotCovered>(assessment)
        assertEquals(MediaType.OPTICAL_DISC, notCovered.observed)
        assertEquals(setOf(MediaType.CARTRIDGE), notCovered.available)
        assertTrue(assessment.isOutOfScope)
    }

    @Test
    fun `a disc image against a disc catalogue is in scope`() {
        val assessment = DatasetCompatibility.assess(
            MediaType.OPTICAL_DISC,
            coverage(dataset(MediaType.OPTICAL_DISC)),
        )

        assertEquals(CoverageAssessment.Covered, assessment)
    }

    @Test
    fun `one covering dataset among several is enough`() {
        val assessment = DatasetCompatibility.assess(
            MediaType.OPTICAL_DISC,
            coverage(dataset(MediaType.CARTRIDGE), dataset(MediaType.OPTICAL_DISC)),
        )

        assertEquals(CoverageAssessment.Covered, assessment)
    }

    @Test
    fun `an empty catalogue has no standing rather than no match`() {
        assertEquals(
            CoverageAssessment.NoDatasets,
            DatasetCompatibility.assess(MediaType.OPTICAL_DISC, CatalogueCoverage(emptyList())),
        )
    }

    @Test
    fun `unmeasured coverage makes no claim at all`() {
        // The caller did not look, so the resolver must not invent a scope
        // judgement from silence.
        assertEquals(
            CoverageAssessment.Covered,
            DatasetCompatibility.assess(MediaType.OPTICAL_DISC, CatalogueCoverage.UNMEASURED),
        )
    }

    @Test
    fun `a file of unknown medium is never declared out of scope`() {
        // RetroVault failing to recognise an extension must not become a
        // refusal to look.
        assertEquals(
            CoverageAssessment.Covered,
            DatasetCompatibility.assess(MediaType.UNKNOWN, coverage(dataset(MediaType.CARTRIDGE))),
        )
    }

    @Test
    fun `a dataset whose media are all unrecognised is treated as covering everything`() {
        // A DAT of `.bin` entries says nothing about its medium. Excluding it
        // would turn our own ignorance into a refusal to search it.
        assertEquals(
            CoverageAssessment.Covered,
            DatasetCompatibility.assess(MediaType.OPTICAL_DISC, coverage(dataset(MediaType.UNKNOWN))),
        )
    }

    @Test
    fun `a dataset with no records at all is treated as covering everything`() {
        assertTrue(dataset(count = 0).covers(MediaType.OPTICAL_DISC))
    }

    @Test
    fun `the unrecognised bucket is not reported back to the user as a medium`() {
        val assessment = DatasetCompatibility.assess(
            MediaType.OPTICAL_DISC,
            coverage(DatasetCoverage(Fixtures.source(), setOf(MediaType.CARTRIDGE), 10)),
        )

        assertEquals(
            setOf(MediaType.CARTRIDGE),
            assertIs<CoverageAssessment.MediaNotCovered>(assessment).available,
        )
    }

    @Test
    fun `a negative record count is refused`() {
        val failure = runCatching { DatasetCoverage(Fixtures.source(), emptySet(), -1) }

        assertTrue(failure.isFailure)
    }

    @Test
    fun `a dataset holding any unrecognised record may cover anything`() {
        // A dataset of `.sfc` and `.bin` entries must not declare a `.iso` out
        // of scope on the strength of extensions RetroVault never understood.
        val mixed = dataset(MediaType.CARTRIDGE, MediaType.UNKNOWN)

        assertTrue(mixed.covers(MediaType.OPTICAL_DISC))
        assertEquals(CoverageAssessment.Covered, DatasetCompatibility.assess(MediaType.OPTICAL_DISC, coverage(mixed)))
    }

    @Test
    fun `only a wholly recognised catalogue can put a file out of scope`() {
        val recognised = coverage(dataset(MediaType.CARTRIDGE), dataset(MediaType.FLOPPY_DISK))

        assertTrue(DatasetCompatibility.assess(MediaType.OPTICAL_DISC, recognised).isOutOfScope)

        val withUnknown = coverage(
            dataset(MediaType.CARTRIDGE),
            dataset(MediaType.FLOPPY_DISK, MediaType.UNKNOWN),
        )
        assertFalse(DatasetCompatibility.assess(MediaType.OPTICAL_DISC, withUnknown).isOutOfScope)
    }

    @Test
    fun `a dataset with nothing searchable in it makes no claim either way`() {
        // Its records are all nodump placeholders, so it indexes nothing. That
        // is not grounds to assert what it does *not* cover, so the artifact
        // gets the humbler answer - "not listed" - rather than advice to import
        // a dataset that is already imported.
        val indexesNothing = DatasetCoverage(Fixtures.source(), emptySet(), recordCount = 0)

        assertEquals(
            CoverageAssessment.Covered,
            DatasetCompatibility.assess(MediaType.OPTICAL_DISC, coverage(indexesNothing)),
        )
    }
}

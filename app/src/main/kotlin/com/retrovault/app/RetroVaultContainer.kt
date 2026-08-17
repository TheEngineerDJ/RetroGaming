package com.retrovault.app

import android.content.Context
import com.retrovault.application.ApplyCorrectionsUseCase
import com.retrovault.application.Clock
import com.retrovault.application.ExecuteRenamePlanUseCase
import com.retrovault.application.GenerateRenamePlanUseCase
import com.retrovault.application.IdGenerator
import com.retrovault.application.ImportDatUseCase
import com.retrovault.application.ListRenameHistoryUseCase
import com.retrovault.application.PreviewRenamePlanUseCase
import com.retrovault.application.ReconcileInterruptedRenamesUseCase
import com.retrovault.application.RecordCorrectionUseCase
import com.retrovault.application.ResolveArtifactUseCase
import com.retrovault.application.ReviewObservationUseCase
import com.retrovault.application.ScanLocationUseCase
import com.retrovault.application.UndoRenameBatchUseCase
import com.retrovault.application.ValidateRenamePlanUseCase
import com.retrovault.dat.LogiqxDatReader
import com.retrovault.data.SqlCorrectionStore
import com.retrovault.data.SqlDumpCatalog
import com.retrovault.data.SqlEntityGraph
import com.retrovault.data.SqlEntityQueries
import com.retrovault.data.SqlObservationRepository
import com.retrovault.data.SqlRenameJournalRepository
import com.retrovault.data.SqlScanSessionRepository
import com.retrovault.domain.policy.AutomationPolicy
import com.retrovault.platform.android.AndroidDatByteSource
import com.retrovault.platform.android.AndroidSqlDatabase
import com.retrovault.platform.android.SafContentSource
import com.retrovault.platform.android.SafDirectoryWalker
import com.retrovault.platform.android.SafPermissions
import com.retrovault.platform.android.SafRenameExecutor
import java.util.UUID

/**
 * The composition root.
 *
 * Every dependency is constructed here and injected through constructors
 * (ENGINEERING_SPEC.md section 3). There is no service locator and no global
 * mutable state, so the wiring is visible in one place and each use case stays
 * independently testable.
 */
class RetroVaultContainer(context: Context) {

    private val applicationContext = context.applicationContext

    private val database = AndroidSqlDatabase.open(applicationContext)

    private val clock = Clock { System.currentTimeMillis() }
    private val ids = IdGenerator { prefix -> "$prefix-${UUID.randomUUID()}" }

    private val catalog = SqlDumpCatalog(database)
    private val observations = SqlObservationRepository(database)
    private val sessions = SqlScanSessionRepository(database)
    private val journal = SqlRenameJournalRepository(database)
    private val entities = SqlEntityGraph(database, clock)
    private val corrections = SqlCorrectionStore(database)

    private val contentSource = SafContentSource(applicationContext)
    private val walker = SafDirectoryWalker(applicationContext)
    private val renameExecutor = SafRenameExecutor(applicationContext)

    /** Persisted Storage Access Framework grants (SECURITY_SPEC.md section 4). */
    val permissions = SafPermissions(applicationContext)

    /** Reading the canonical entity graph a scan projects into. */
    val entityQueries = SqlEntityQueries(database, corrections)

    private val recordCorrection = RecordCorrectionUseCase(corrections, clock, ids)

    /** Reviewing one file and recording what the user decides about it. */
    val reviewObservation = ReviewObservationUseCase(observations, recordCorrection)

    val importDat = ImportDatUseCase(
        reader = LogiqxDatReader(AndroidDatByteSource(applicationContext)),
        writer = catalog,
        clock = clock,
    )

    /**
     * The corrections and entity-graph arguments are optional on the use case
     * so that a caller written before they existed still compiles. That makes
     * omitting them silent, and omitting them here would mean a device scan
     * quietly ignored every correction the user had made and projected nothing
     * into the graph - behaviour no test would catch, because the tests wire
     * them.
     */
    val scanLocation = ScanLocationUseCase(
        walker = walker,
        contentSource = contentSource,
        resolveArtifact = ResolveArtifactUseCase(catalog, contentSource),
        applyCorrections = ApplyCorrectionsUseCase(entities),
        corrections = corrections,
        entities = entities,
        catalog = catalog,
        observations = observations,
        sessions = sessions,
        clock = clock,
        ids = ids,
    )

    /**
     * The one authority on what may be renamed without asking.
     *
     * Held here rather than defaulted at each call site so the planner and the
     * screen cannot disagree about which files need review. A screen that
     * decided that for itself would be re-implementing a domain rule in the
     * presentation layer (ENGINEERING_SPEC.md section 18).
     */
    val automationPolicy = AutomationPolicy()

    val generatePlan = GenerateRenamePlanUseCase(observations, clock, ids)

    private val validatePlan = ValidateRenamePlanUseCase(contentSource, clock)

    val previewPlan = PreviewRenamePlanUseCase(validatePlan)

    val executePlan = ExecuteRenamePlanUseCase(validatePlan, renameExecutor, journal, clock, ids)

    /**
     * Runs at startup so an interrupted batch is settled before the user is
     * shown anything (DATABASE.md section 21).
     */
    val reconcileInterruptedRenames =
        ReconcileInterruptedRenamesUseCase(journal, contentSource, clock)

    /** The audit trail, read back (Constitution section 233). */
    val renameHistory = ListRenameHistoryUseCase(journal)

    /** Putting a batch back, which is the other half of section 170. */
    val undoRenames = UndoRenameBatchUseCase(journal, contentSource, renameExecutor, clock)
}

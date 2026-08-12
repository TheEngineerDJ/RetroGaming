package com.retrovault.app

import android.content.Context
import com.retrovault.application.Clock
import com.retrovault.application.ExecuteRenamePlanUseCase
import com.retrovault.application.GenerateRenamePlanUseCase
import com.retrovault.application.IdGenerator
import com.retrovault.application.ImportDatUseCase
import com.retrovault.application.PreviewRenamePlanUseCase
import com.retrovault.application.ReconcileInterruptedRenamesUseCase
import com.retrovault.application.ResolveArtifactUseCase
import com.retrovault.application.ScanLocationUseCase
import com.retrovault.application.ValidateRenamePlanUseCase
import com.retrovault.dat.LogiqxDatReader
import com.retrovault.data.SqlDumpCatalog
import com.retrovault.data.SqlObservationRepository
import com.retrovault.data.SqlRenameJournalRepository
import com.retrovault.data.SqlScanSessionRepository
import com.retrovault.platform.android.AndroidDatByteSource
import com.retrovault.platform.android.AndroidSqlDatabase
import com.retrovault.platform.android.SafContentSource
import com.retrovault.platform.android.SafDirectoryWalker
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

    private val contentSource = SafContentSource(applicationContext)
    private val walker = SafDirectoryWalker(applicationContext)
    private val renameExecutor = SafRenameExecutor(applicationContext)

    val importDat = ImportDatUseCase(
        reader = LogiqxDatReader(AndroidDatByteSource(applicationContext)),
        writer = catalog,
        clock = clock,
    )

    val scanLocation = ScanLocationUseCase(
        walker = walker,
        contentSource = contentSource,
        resolveArtifact = ResolveArtifactUseCase(catalog, contentSource),
        catalog = catalog,
        observations = observations,
        sessions = sessions,
        clock = clock,
        ids = ids,
    )

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

    val renameJournal = journal
}

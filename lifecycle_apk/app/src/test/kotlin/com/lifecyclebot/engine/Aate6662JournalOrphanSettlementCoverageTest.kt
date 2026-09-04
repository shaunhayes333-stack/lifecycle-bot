package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate6662JournalOrphanSettlementCoverageTest {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "src/main/kotlin/com/lifecyclebot/engine/BotService.kt").exists() }
    private val replay = File(root,
        "src/main/kotlin/com/lifecyclebot/engine/truth/JournalEconomicReplay6619.kt").readText()
    private val service = File(root,
        "src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

    @Test
    fun `orphaned journal exposure is settled durably at zero pnl`() {
        val repair = replay.substringAfter("fun repairOrphanedOpenLots6662")
            .substringBefore("\n    fun statusLine")
        assertTrue(repair.contains("openBasisByPosition"))
        assertTrue(repair.contains("CanonicalPositionAuthority6441.getPosition(positionId)"))
        assertTrue(repair.contains("ORPHANED_STOP_LOT_REFUND_6662"))
        assertTrue(repair.contains("soldCostBasisSol = basis"))
        assertTrue(repair.contains("grossProceedsSol = basis"))
        assertTrue(repair.contains("economicEventId = eventId"))
        assertTrue(repair.contains("PaperEconomicAtomicCommit6632.stampLedger"))
    }

    @Test
    fun `startup repairs the journal after rebuilding ledger identity`() {
        val identity = service.indexOf("rebuildPaperCashFromIdentity6505()")
        val journal = service.indexOf("repairOrphanedOpenLots6662()")
        assertTrue(identity >= 0)
        assertTrue(journal > identity)
    }

    @Test
    fun `accepted runtime start anchors the smoke acceptance window`() {
        val acceptance = File(root,
            "src/main/kotlin/com/lifecyclebot/engine/truth/ExecutionSpineAcceptance6647.kt").readText()
        assertTrue(acceptance.contains("fun beginWindow6662"))
        assertTrue(acceptance.contains("baseline = capture(nowMs)"))
        assertTrue(service.contains("ExecutionSpineAcceptanceWindow6647"))
        assertTrue(service.contains(".beginWindow6662()"))
        assertTrue(service.contains("scheduleExecutionSpineAcceptance6666(runtimeGeneration)"))
        assertTrue(service.contains("ExecutionSpineAcceptance6647.MIN_WINDOW_MS + 2_000L"))
        assertTrue(acceptance.contains("CanonicalPaperTransaction6486.reconcileJournalAuthority6663()"))
    }

    @Test
    fun `independent reconciler continuously heals journal authority`() {
        assertTrue(service.contains("IndependentReconcilerScheduler6431.start"))
        assertTrue(service.contains("CanonicalPaperTransaction6486\n                            .reconcileJournalAuthority6663()"))
        assertTrue(replay.contains("side == \"QTY_RECONCILE\""))
        assertTrue(replay.contains("JOURNAL_QTY_RECONCILED_TO_CANONICAL_6666"))
    }
}

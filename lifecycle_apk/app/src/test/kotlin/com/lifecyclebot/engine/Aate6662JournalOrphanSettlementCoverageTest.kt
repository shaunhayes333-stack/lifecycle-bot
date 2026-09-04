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
}

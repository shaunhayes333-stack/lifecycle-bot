package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PaperAccountLedger6430
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate6663RuntimeReconciliationTest {
    @Test fun journalIdentityReplacesDriftedLedgerScalarsAtomically() {
        PaperAccountLedger6430.initialize(10.0)
        assertTrue(PaperAccountLedger6430.reconcileFromJournal6663(
            cashSol = 7.5, openCostSol = 2.0, realizedSol = -0.25, feesSol = 0.25,
        ))
        val s = PaperAccountLedger6430.snapshotAtomic6643()
        assertEquals(7.5, s.cashSol, 1e-9)
        assertEquals(2.0, s.openCostBasisSol, 1e-9)
        assertEquals(-0.25, s.realizedPnlSol, 1e-9)
        assertEquals(0.25, s.feesSol, 1e-9)
        assertFalse(PaperAccountLedger6430.reconcileFromJournal6663(7.5, 9.0, -0.25, 0.25))
    }

    @Test fun runtimeStartsReconciliationOffMainAndSealsActualSpecialistSize() {
        val bot = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val loop = bot.substringAfter("private suspend fun botLoop()")
        assertTrue(loop.contains("reconcileJournalAuthority6663()"))
        assertTrue(bot.contains("resolvedSizeSol6558 = actualInitialSizeForAuth6649"))
        assertTrue(bot.contains("recordDeskStage(cyclePrimaryLane, \"SIZED_EXECUTABLE\", ticketStampIntent6658.attemptId)"))
        val fdg = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(fdg.contains("causalEventId = \"\""))
    }
}

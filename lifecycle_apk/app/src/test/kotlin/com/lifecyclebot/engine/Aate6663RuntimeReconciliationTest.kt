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
        // V5.0.7835/7853 — the FDG size is carried to TradeAuthorizer and sealed
        // by SpecialistPreauthSeal7834; SIZED_EXECUTABLE is stamped on the
        // sealed ticket's own attempt (V5.0.7807).
        assertTrue(bot.contains("preResolvedSizeSol = actualInitialSizeForAuth6649"))
        val seal = File("src/main/kotlin/com/lifecyclebot/engine/SpecialistPreauthSeal7834.kt").readText()
        assertTrue(seal.contains("val size = decision.sizeSol"))
        assertTrue(seal.contains("resolvedSizeSol6558 = size,"))
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(gate.contains("ToolkitSignalSheet.recordDeskStage(lane, \"SIZED_EXECUTABLE\", ticket.attemptId)"))
        val fdg = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(fdg.contains("causalEventId = \"\""))
    }
}

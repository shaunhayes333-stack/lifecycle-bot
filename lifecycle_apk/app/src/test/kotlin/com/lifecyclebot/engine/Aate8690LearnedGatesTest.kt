package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.FdgGate8365
import com.lifecyclebot.engine.truth.GateOutcome8418
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8690 — the two gates whose refusals went on to win (8680 forward record: BIG_LOSER_8130 n81 +17.9 %, the consensus
 * FUSED_LIVE_NEGATIVE veto n20 +40.4 %) are learned: each stands aside on LIVE only once its own graded refusals prove it
 * refuses winners, on the bar every evidence door is retired on. Neither record is long enough yet, so both still bind.
 */
class Aate8690LearnedGatesTest {
    @Test fun theConsensusVetoIsFiledByItsObjectionsNotItsNumbers() {
        val a = FdgGate8365.gateKey8392("BRAIN_CONSENSUS_VETO:FUSED_LIVE_NEGATIVE=pWin=0.18 E=-9.1 n=12")
        val b = FdgGate8365.gateKey8392("BRAIN_CONSENSUS_VETO:FUSED_LIVE_NEGATIVE=pWin=0.27 E=-3.4 n=40")
        assertEquals("BRAIN_CONSENSUS_VETO_FUSED_LIVE_NEGATIVE", a)
        assertEquals("one record however the forecast moves", a, b)
        val two = FdgGate8365.gateKey8392("BRAIN_CONSENSUS_VETO:REALISED_LIVE_NEGATIVE=n=9 WR=0.11 E=-6.0+FUSED_LIVE_NEGATIVE=pWin=0.2 E=-8 n=9")
        assertEquals("objection order does not split the record", "BRAIN_CONSENSUS_VETO_FUSED_LIVE_NEGATIVE_REALISED_LIVE_NEGATIVE", two)
        assertEquals("a signed number's '+' is not an objection", "BRAIN_CONSENSUS_VETO_META_POLICY_NEGATIVE_PROVEN_DEAD_CONTEXT",
            FdgGate8365.gateKey8392("BRAIN_CONSENSUS_VETO:PROVEN_DEAD_CONTEXT=MOONSHOT|S40+META_POLICY_NEGATIVE=conv=0.61 E=+2.0 n=30"))
        // every other gate keeps its key
        assertEquals("BIG_LOSER_8130", FdgGate8365.gateKey8392("BIG_LOSER_8130"))
        assertEquals("ADMISSION8136_STRUCTURAL_RISK_8136", FdgGate8365.gateKey8392("CANDIDATE_ADMISSION_8136_STRUCTURAL_RISK_8136"))
    }

    @Test fun aRecordBelowTheAuditBarNeverRetiresAGate() {
        // 8680's BIG_LOSER record: n81 mean +17.9 % — strong, but under the 100 graded refusals the audit requires
        val st = GateOutcome8418.Stat8418().apply { repeat(81) { i -> add(if (i % 3 == 0) 40.0 else 6.0, false) } }
        assertFalse(GateOutcome8418.refusesWinners8418(st))
        assertFalse(GateOutcome8418.refusesWinners8418(null))
    }

    @Test fun bothGatesConsultTheirOwnRecord() {
        val lockout = src("engine/RebuyLockout8019.kt")
        assertTrue(lockout.contains("GateOutcome8418.selectionGateRetired8696(\"BIG_LOSER_8130\")"))
        assertTrue(lockout.indexOf("if (bigLossRetiredByRecord8690())") < lockout.indexOf("return \"BIG_LOSER_8130\""))
        val fdg = src("engine/FinalDecisionGate.kt")
        assertTrue(fdg.contains("GateOutcome8418.selectionGateRetired8696(consensusVetoReason8690(objections), lane)"))
        assertTrue(fdg.contains("if (consensusVetoRetired8690(mode == TradeMode.LIVE, report.objections, specialistLane ?: modeTag))"))
    }

    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
}

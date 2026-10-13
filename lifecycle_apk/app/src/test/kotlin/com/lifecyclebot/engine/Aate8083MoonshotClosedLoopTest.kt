package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalTradeFinalizedBus6450
import com.lifecyclebot.engine.truth.MoonshotSignalLearner6415
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8083 — MOONSHOT closed loop, LIVE only. */
class Aate8083MoonshotClosedLoopTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    private fun ev(pid: String, mint: String, ret: Double, mode: String = "live", lane: String = "MOONSHOT", dq: String = "LIVE_FINALIZED") =
        CanonicalTradeFinalizedBus6450.Event(
            positionId = pid, mint = mint,
            outcome = if (ret > 0) CanonicalTradeFinalizedBus6450.Outcome.WIN else CanonicalTradeFinalizedBus6450.Outcome.LOSS,
            netRealizedPnlSol = ret * 0.1, grossRealizedPnlSol = ret * 0.1, returnFraction = ret, netReturnPct = ret * 100,
            feesSol = 0.0, entryLane = lane, entryStrategyPid = "", entryTactic = "", exitReason = "T", holdingTimeMs = 1000,
            dataQuality = dq, priceIntegrity = "", mode = mode, settledAtMs = System.currentTimeMillis(),
        )

    @Test fun ordinaryOutcomesTeachAndConsistentLosersAreDownWeightedWithZeroWins() {
        // the 6415 defects: -15% taught nothing; zero wins kept 1.0
        assertTrue(MoonshotSignalLearner6415.weightOf8083(8, -0.15, -0.15) < 1.0)
        assertTrue(MoonshotSignalLearner6415.weightOf8083(8, -0.40, -0.15) < MoonshotSignalLearner6415.weightOf8083(8, -0.15, -0.15))
        assertTrue(MoonshotSignalLearner6415.weightOf8083(8, 0.30, 0.0) > 1.0)
        assertEquals(1.0, MoonshotSignalLearner6415.weightOf8083(2, -0.9, 0.0), 0.0)        // below min sample
        val w = MoonshotSignalLearner6415.weightOf8083(10_000, -1.0, 5.0)
        assertTrue(w >= 0.25 && w <= 2.0)
    }

    @Test fun liveVerifiedMoonshotClosesTrainOnceAndPaperDuplicateUnverifiedDoNot() {
        MoonshotSignalLearner6415.resetForTest()
        assertEquals("NOT_LIVE", MoonshotSignalLearner6415.eligible8083(ev("p", "m", -0.1, mode = "paper")))
        assertEquals("NOT_MOONSHOT", MoonshotSignalLearner6415.eligible8083(ev("p", "m", -0.1, lane = "QUALITY")))
        assertEquals("UNVERIFIED_FINALITY", MoonshotSignalLearner6415.eligible8083(ev("p", "m", -0.1, dq = "")))
        for (i in 0 until 6) {
            MoonshotSignalLearner6415.bindEntry8083("MINT8083_$i", setOf("SIG_LOSER_8083"))
            MoonshotSignalLearner6415.onFinalizedForTest(ev("POS8083_$i", "MINT8083_$i", -0.15))
            MoonshotSignalLearner6415.onFinalizedForTest(ev("POS8083_$i", "MINT8083_$i", -0.15))    // duplicate finality
        }
        MoonshotSignalLearner6415.bindEntry8083("MINT8083_P", setOf("SIG_LOSER_8083"))
        MoonshotSignalLearner6415.onFinalizedForTest(ev("POS8083_P", "MINT8083_P", 3.0, mode = "paper"))  // paper never trains
        assertTrue(MoonshotSignalLearner6415.statusLine().contains("n=6 "))
        assertTrue(MoonshotSignalLearner6415.signalWeight("SIG_LOSER_8083") < 1.0)
        MoonshotSignalLearner6415.resetForTest()
    }

    @Test fun oneRewardEmitterPersistedAndNegativeSignalsUseReciprocal() {
        val l = src("engine/truth/MoonshotSignalLearner6415.kt")
        assertTrue(l.contains("CanonicalTradeFinalizedBus6450.subscribe"))
        assertTrue(l.contains("LearningPersistence.save(KEY"))
        assertTrue(l.contains("MoonshotAdaptiveGate.recordOutcome(e.netReturnPct)"))
        assertFalse(l.contains("WIN_THRESHOLD_PCT"))
        val h = src("engine/truth/EarlyMoonshotHunter6415.kt")
        assertTrue(h.contains("MoonshotSignalLearner6415.bindEntry8083(mint, verdict.signalsFired)"))
        assertTrue(h.contains("if (s.weight < 0.0) (1.0 / learned0).coerceIn(0.25, 2.0) else learned0"))
        assertFalse(h.substringAfter("fun onTradeClosed(").substringBefore("fun statusLine").contains("recordOutcome("))
        assertFalse(src("v3/scoring/MoonshotTraderAI.kt").contains("MoonshotAdaptiveGate.recordOutcome(pnlPct)"))
    }
}

class Aate8083LiveCapitalTest {
    private fun src(p: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$p").readText()
    @Test fun liveThroughputAndSlotsReadTheWalletNotThePaperAccount() {
        val t = src("engine/truth/ExitThroughputAuthority6727.kt")
        val live = t.substringAfter("if (m == \"live\") {").substringBefore("} else {")
        assertFalse(live.contains("CanonicalCapitalAuthority6450.snapshot()"))
        assertTrue(live.contains("LiveConcentrationDoctrine7697.liveCashSol8083()"))
        assertTrue(live.contains("LiveConcentrationDoctrine7697.liveOpenCostSol8083()"))
        val d = src("engine/truth/LiveConcentrationDoctrine7697.kt")
        assertFalse(d.contains("CanonicalCapitalAuthority6450.snapshot().openMarketValueSol"))
        assertTrue(d.contains("val deployed = liveOpenCostSol8083()"))
    }
}

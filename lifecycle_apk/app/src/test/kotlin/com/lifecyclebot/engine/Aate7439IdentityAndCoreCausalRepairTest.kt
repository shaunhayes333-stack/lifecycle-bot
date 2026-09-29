package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7439IdentityAndCoreCausalRepairTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun core_is_real_ensemble_fallback_not_strongest_lane_clone() {
        val s = src("engine/ToolkitSignalSheet.kt")
        assertTrue(s.contains("coreVoters7439.size >= 2"))
        assertTrue(s.contains("coreStrongest7439.conviction < 75.0"))
        assertTrue(s.contains("coreStrongest7439.conviction - coreSecond7439.conviction <= 10.0"))
        assertTrue(s.contains("LaneEntryContract6342.isLaneIdentityEligible7252(ts, \"CORE\")"))
        assertFalse(s.contains("deskHypotheses[\"CORE\"] = strongest.copy("))
        assertFalse(s.contains("never an independent duplicate scanner or position owner"))
    }

    @Test fun canonical_history_keeps_cashgen_separate_from_treasury() {
        val s = src("engine/TradeHistoryStore.kt")
        assertTrue(s.contains("upper.contains(\"CASHGEN\") || upper.contains(\"CASHGENERATION\") -> \"CASHGEN\""))
        assertFalse(s.contains("upper.contains(\"CASHGEN\") || upper.contains(\"CASHGENERATION\") -> \"TREASURY\""))
    }

    @Test fun sniper_exit_and_expectancy_learning_use_project_sniper_identity() {
        val sniper = src("v3/scoring/ProjectSniperAI.kt")
        val tuner = src("engine/learning/LaneExitTuner.kt")
        assertFalse(sniper.contains("\"PRESALE_SNIPE\""))
        assertTrue(sniper.contains("ScoreExpectancyTracker.shouldReject(\"PROJECT_SNIPER\""))
        assertTrue(sniper.contains("LaneExitTuner.getTpMult(\"PROJECT_SNIPER\")"))
        assertTrue(tuner.contains("CanonicalLaneIdentity6506.canonical(lane)"))
        assertFalse(tuner.contains("-> \"PRESALE_SNIPE\""))
    }

    @Test fun express_outcomes_train_express_layer_not_shitcoin() {
        val s = src("v3/scoring/EducationSubLayerAI.kt")
        assertTrue(s.contains("\"SHITCOIN_EXPRESS\", \"SHITCOINEXPRESS\", \"EXPRESS\" -> \"ShitCoinExpress\""))
        assertFalse(s.contains("\"SHITCOIN\", \"SHITCOIN_EXPRESS\", \"SHITCOINEXPRESS\" -> \"ShitCoinTraderAI\""))
    }

    @Test fun tactic_memory_sweep_has_bounded_production_cadence() {
        val s = src("engine/learning/TacticSwitcher.kt")
        assertTrue(s.contains("memorySweepCloseCadence7439.incrementAndGet() % 16L == 0L"))
        assertTrue(s.contains("sweepAllBuckets()"))
        assertTrue(s.contains("TACTIC_MEMORY_SWEEP_7439"))
    }
}

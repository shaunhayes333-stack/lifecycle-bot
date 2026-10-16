package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7439IdentityAndCoreCausalRepairTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun core_is_real_ensemble_fallback_not_strongest_lane_clone() {
        // V5.0.7542 / V5.0.7796 — the CORE ensemble (>=2 voters, strongest < 75,
        // gap <= 10) is decided by CORE's native brain; the toolkit only consumes it.
        val brain = src("engine/SpecialistBrainBridge7542.kt")
        assertTrue(brain.contains("val ensemble=a!=null&&b!=null&&av<75&&(av-bv<=10||av<65)"))
        val s = src("engine/ToolkitSignalSheet.kt")
        assertTrue(s.contains("nativeBrains7542.opinions[\"CORE\"]?.takeIf { it.authoritative && it.eligible }"))
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

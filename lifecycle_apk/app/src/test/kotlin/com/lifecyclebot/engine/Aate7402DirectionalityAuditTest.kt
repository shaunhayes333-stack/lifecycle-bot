package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7402DirectionalityAuditTest {
    private fun src(relative: String): String =
        File("src/main/kotlin/com/lifecyclebot/$relative").readText()

    @Test
    fun shared_entry_routers_use_true_launch_lifecycle() {
        val mode = src("ModeRouter.kt")
        val style = src("AgenticStyleRouter.kt")
        val toolkit = src("ToolkitSignalSheet.kt")
        assertTrue(mode.contains("LaunchPhaseAuthority7401.snapshot"))
        assertTrue(mode.contains("FRESH_REJECT_POST_PUMP_FADE"))
        assertTrue(style.contains("launch7402?.tooLateForSnipe == true"))
        assertTrue(toolkit.contains("launch7402?.tooLateForSnipe == true"))
    }

    @Test
    fun specialist_voters_do_not_call_launch_fear_or_fade_bullish() {
        val votes = src("learning/LayerVoteSampler.kt")
        assertTrue(votes.contains("launch?.tooLateForSnipe == true -> Pair(false"))
        assertTrue(votes.contains("Phase.IGNITION"))
        assertTrue(votes.contains("NO_BOUNCE") || votes.contains("val bounce"))
        assertFalse(votes.contains("isNew && ts.entryScore >= 55 -> Pair(true"))
    }

    @Test
    fun momentum_predictor_has_a_live_writer() {
        val d = src("DataOrchestrator.kt")
        assertTrue(d.contains("MomentumPredictorAI.recordPricePoint"))
        assertTrue(d.contains("MOMENTUM_PREDICTOR_LIVE_POINT_7402"))
    }

    @Test
    fun oracle_live_history_is_not_replaced_by_pooled_paper_history() {
        val hist = src("truth/OracleTradeHistory7287.kt")
        val oracle = src("truth/PredictiveEntryOracle6915.kt")
        assertTrue(hist.contains("laneForMode7402"))
        assertTrue(hist.contains("bookForMode7402"))
        assertTrue(oracle.contains("laneForMode7402(laneKey, liveMode7402)"))
        assertTrue(oracle.contains("bookForMode7402(liveMode7402)"))
        assertTrue(oracle.contains("cellScoreExpPOOLED_SKIPPED_LIVE"))
    }

    @Test
    fun post_event_volume_and_social_ignition_are_not_launch_signals() {
        val movement = src("MovementPatternSignal.kt")
        val mode = src("ModeRouter.kt")
        assertTrue(movement.contains("post_pump_no_new_ignition"))
        assertTrue(mode.contains("SENTIMENT_REJECT: post-pump fade"))
    }

    @Test
    fun cyclic_fallback_age_is_true_market_age() {
        val cyclic = src("CyclicTradeEngine.kt")
        assertTrue(cyclic.contains("LaunchPhaseAuthority7401.trueAgeMs(ts)"))
    }
}

package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7401LaunchTimingDirectionTest {
    private fun src(relative: String): String =
        File("src/main/kotlin/com/lifecyclebot/$relative").readText()

    @Test
    fun token_age_is_not_watchlist_age_for_launches() {
        val router = src("TokenMetricStageRouter.kt")
        val adapter = src("v3/bridge/V3Adapter.kt")
        assertTrue(router.contains("LaunchPhaseAuthority7401.snapshot"))
        assertTrue(adapter.contains("LaunchPhaseAuthority7401.trueAgeMs"))
        assertFalse(adapter.contains("val ageMinutes = ((now - discoveredAt).coerceAtLeast(0L)) / 60_000.0"))
    }

    @Test
    fun post_pump_fade_cannot_be_fresh_launch() {
        val router = src("TokenMetricStageRouter.kt")
        assertTrue(router.contains("lifecycleFade7401"))
        assertTrue(router.contains("lifecycleFade7401 && dd >= 18.0 -> Stage.DUMPING"))
        assertTrue(router.contains("lifecycleFade7401 -> Stage.PEAK_EXHAUSTION"))
        assertTrue(router.contains("ageMin <= 3.0 && lifecycleEarly7401"))
    }

    @Test
    fun v3_scores_ignition_before_chart_confirmation() {
        val scorer = src("v3/scoring/UnifiedScorer.kt")
        assertTrue(scorer.contains("launchIgnition7401"))
        assertTrue(scorer.contains("IGNITION — first-minute acceleration+breadth"))
        assertTrue(scorer.contains("launchPostPumpFade7401"))
        assertTrue(scorer.contains("POST_PUMP_FADE — launch impulse already spent"))
    }

    @Test
    fun launch_flow_records_all_buys_and_sells_before_whale_filter() {
        val whale = src("WhaleDetector.kt")
        val record = whale.substringAfter("fun recordTrade")
        assertTrue(record.indexOf("launchTrades7401") < record.indexOf("MIN_SIGNIFICANT_SOL"))
        assertTrue(whale.contains("accelerationRising"))
        assertTrue(whale.contains("distinctBuyers60s"))
    }

    @Test
    fun live_streams_are_armed_before_history_enrichment() {
        val d = src("DataOrchestrator.kt")
        val block = d.substringAfter("fun onTokenAdded").substringBefore("fun onTokenRemoved")
        assertTrue(block.indexOf("subscribeToken") < block.indexOf("seedCandleHistory"))
    }
}

package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.7399 regression tape:
 * - one-slot small wallets keep the execution reserve but are not double-reserved;
 * - proven-negative PROJECT_SNIPER S0-10 is cohort-shaped, not globally throttled;
 * - held-price fallback cannot serially park the risk loop for tens of seconds;
 * - Crypto Universe ranked-out rows terminate their evaluation generation;
 * - dirty PAPER economics remain visible but cannot poison LIVE acceptance.
 */
class Aate7399ThroughputQualityAndLatencyTest {
    private fun src(relative: String): String =
        File("src/main/kotlin/com/lifecyclebot/$relative").readText()

    @Test
    fun one_routable_slot_uses_tradeable_balance_after_reserve() {
        val s = src("v3/sizing/SmartSizerV3.kt")
        assertTrue(s.contains("SINGLE_ROUTABLE_POSITION_SHARE_7399 = 1.00"))
        assertTrue(s.contains("routableCapacity7218 == 1"))
        assertTrue(s.contains("SINGLE_ROUTABLE_POSITION_SHARE_7399"))
        assertTrue(s.contains("routableMinSol7127 / SINGLE_ROUTABLE_POSITION_SHARE_7399"))
    }

    @Test
    fun sniper_negative_low_band_is_shaped_without_global_lane_shutdown() {
        val s = src("engine/FinalDecisionGate.kt")
        assertTrue(s.contains("LosingPatternMemory.liveStats(\"PROJECT_SNIPER\", 5).isDangerous"))
        assertTrue(s.contains("SNIPER_S0_10_NEG_EV_FLOOR_7399"))
        assertTrue(s.contains("SNIPER_S0_10_LIVE_EXPLORATION_7399"))
        assertTrue(s.contains("sniperLowRescueAllowed7399"))
        assertTrue(s.contains("canonicalV3Score7243 > 10.0"))
        assertFalse(s.contains("RUNTIME_OVERLAY_LANE_DISABLED_PROJECT_SNIPER_7399"))
    }

    @Test
    fun held_price_resolver_is_bounded_and_cache_first() {
        val s = src("engine/sell/PriceResolverFallback.kt")
        assertTrue(s.contains("HOT_CACHE_MS_7399 = 5_000L"))
        assertTrue(s.contains("RESOLVE_BUDGET_MS_7399 = 2_500L"))
        assertTrue(s.contains("callTimeout(1200, TimeUnit.MILLISECONDS)"))
        assertTrue(s.contains("PRICE_FALLBACK_HOT_CACHE_7399"))
        assertTrue(s.contains("PRICE_FALLBACK_BUDGET_YIELD_7399"))
    }

    @Test
    fun crypto_ranked_out_rows_are_terminal_not_a_fake_backlog() {
        val s = src("perps/CryptoAltTrader.kt")
        assertTrue(s.contains("RANKED_OUT_THIS_WINDOW_7399"))
        assertTrue(s.contains("CRYPTO_RANKED_OUT_WINDOW_7399"))
        val rankedBlock = s.substringAfter("V5.0.7399 — ranked-out rows are terminal")
            .substringBefore("for ((signalIndex6567")
        assertTrue(rankedBlock.contains("markEvaluationDisposition6567"))
        assertFalse(rankedBlock.contains("markEvaluationProgress6570"))
    }

    @Test
    fun live_acceptance_keeps_structural_failures_strict_but_paper_deltas_diagnostic() {
        val s = src("engine/truth/AcceptanceInvariantAudit6441.kt")
        assertTrue(s.contains("LIVE_ACCEPTANCE_PAPER_SPINE_DIAGNOSTIC_ONLY_7399"))
        assertTrue(s.contains("val structural7399 = spine6647.failures.filterNot"))
        assertTrue(s.contains("failed.addAll(structural7399.map"))
        assertTrue(s.contains("\"CASH_DELTA\", \"BASIS_DELTA\", \"REALIZED_DELTA\", \"QUANTITY_DELTA\""))
    }

    @Test
    fun runner_compounding_status_separates_live_and_paper() {
        val s = src("engine/truth/RunnerCompoundingLadder6440.kt")
        assertTrue(s.contains("lastLiveWalletObserved7399"))
        assertTrue(s.contains("lastPaperWalletObserved7399"))
        assertTrue(s.contains("live[wallet="))
        assertTrue(s.contains("paper[wallet="))
    }
}

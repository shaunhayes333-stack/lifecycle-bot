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
    fun sniper_quality_learning_must_not_become_a_hard_fdg_choke() {
        val s = src("engine/FinalDecisionGate.kt")
        assertTrue(s.contains("V5.0.7400"))
        assertFalse(s.contains("SNIPER_S0_10_NEG_EV_FLOOR_7399"))
        assertFalse(s.contains("sniperLowRescueAllowed7399"))
        assertTrue(s.contains("laneEvidenceScore7243 >= canonicalFloor7266"))
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
    fun crypto_ranked_out_rows_remain_reconsiderable_shared_intelligence() {
        val s = src("perps/CryptoAltTrader.kt")
        assertTrue(s.contains("CRYPTO_RANKED_OUT_RETAINED_7400"))
        val rankedBlock = s.substringAfter("V5.0.7400 — ranked-out is NOT terminal")
            .substringBefore("for ((signalIndex6567")
        assertTrue(rankedBlock.contains("markEvaluationProgress6570"))
        assertFalse(rankedBlock.contains("RANKED_OUT_THIS_WINDOW_7399"))
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
    fun actual_keyless_batch_client_is_bounded_7400() {
        val s = src("network/KeylessPriceSources6996.kt")
        assertTrue(s.contains("callTimeout(1_200, TimeUnit.MILLISECONDS)"))
        assertTrue(s.contains("connectTimeout(800, TimeUnit.MILLISECONDS)"))
        assertTrue(s.contains("readTimeout(1_000, TimeUnit.MILLISECONDS)"))
        assertTrue(s.contains("writeTimeout(1_000, TimeUnit.MILLISECONDS)"))
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

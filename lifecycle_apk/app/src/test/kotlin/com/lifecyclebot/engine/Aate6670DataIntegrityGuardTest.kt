package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6670 source-level acceptance locks for:
 *   §DECIMAL_SKEW_ROOT_AUTHORITY   — buy/sell decimals converge through MintDecimalsAuthority6392
 *   §RUNNER_BYPASS_PROVENANCE_GUARD — runner-bypass disabled on non-AUTHORITATIVE marks
 *
 * Operator dumps (build 5.0.6669) surfaced:
 *   QTY_DECIMAL_SKEW_6309: 7GCihg buyQty=97.49 sellQty=9.826e+10
 *   QTY_DECIMAL_SKEW_6309: kZbqhb buyQty=2073   sellQty=53.36
 *   skewLearningQuarantine=74
 *   phantom PnL +604,752,538% on WBTC (stale mark) with runner-bypass suppressing evict
 */
class Aate6670DataIntegrityGuardTest {

    private val executor = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()

    @Test
    fun `getTokenDecimals consults MintDecimalsAuthority6392 before falling to the heuristic`() {
        assertTrue(
            "Executor.getTokenDecimals must consult MintDecimalsAuthority6392 when reflection misses",
            executor.contains("V5.0.6670 §DECIMAL_SKEW_ROOT_AUTHORITY"),
        )
        assertTrue(
            "Authority lookup must come BEFORE the inferUiScaleFromTrade heuristic",
            executor.indexOf("MintDecimalsAuthority6392.get(ts.mint)") in
                executor.indexOf("private fun getTokenDecimals")..executor.indexOf("private fun rawTokenAmountToUiAmount"),
        )
        assertTrue(
            "Authoritative hit must seed walletDecimalsByMint6311 and tokenMap.decimals so BUY/SELL converge",
            executor.contains("walletDecimalsByMint6311.putIfAbsent(ts.mint, authority6670)") &&
                executor.contains("ts.tokenMap.decimals = authority6670"),
        )
    }

    @Test
    fun `every path that reads verified decimals seeds MintDecimalsAuthority6392`() {
        val getFn = executor.substring(
            executor.indexOf("private fun getTokenDecimals"),
            executor.indexOf("walletDecimalsByMint6311 = java.util.concurrent.ConcurrentHashMap"),
        )
        assertTrue(
            "wallet-cached decimals must seed the authority so SELL/MARK converge",
            getFn.contains("V5.0.6670 §DECIMAL_SEED_AUTHORITY_ON_WALLET_HIT") &&
                getFn.split("MintDecimalsAuthority6392.resolveAndCache(ts.mint").size >= 3,
        )
    }

    @Test
    fun `runner bypass is gated on AUTHORITATIVE mark provenance in every branch`() {
        assertTrue(
            "primary stale-feed evict branch must guard runner-bypass",
            executor.contains("V5.0.6670 §RUNNER_BYPASS_PROVENANCE_GUARD"),
        )
        // The three runner-bypass branches (dead-feed evict, max-hold #1,
        // max-hold #2) each must consult MarketDataProvenance6471 and
        // require AUTHORITATIVE before applying the bypass.
        val guardCount = executor.split("MarketDataProvenance6471.Provenance.AUTHORITATIVE").size - 1
        assertTrue(
            "MarketDataProvenance6471.Provenance.AUTHORITATIVE must appear at least 4 times " +
                "(1 entry gate + 3 runner-bypass branches). saw=$guardCount",
            guardCount >= 4,
        )
        // Each branch must publish its distrust label so the operator can
        // see runner-bypass distrust firing.
        listOf(
            "RUNNER_BYPASS_UNTRUSTED_MARK_6670",
            "RUNNER_BYPASS_UNTRUSTED_MARK_6670|max_hold_branch",
            "RUNNER_BYPASS_UNTRUSTED_MARK_6670|max_hold_branch2",
        ).forEach { label ->
            assertTrue("Executor must publish $label", executor.contains(label))
        }
    }

    @Test
    fun `preflightExecutableOpen converges caller lane to sealed intent canonicalLane`() {
        assertTrue(
            "preflightExecutableOpen must consult activeExecutionIntent6519 before dispatching to the open-gate",
            executor.contains("V5.0.6671 §PREFLIGHT_LANE_INTENT_CONVERGENCE"),
        )
        assertTrue(
            "convergedLane assignment must use the sealed intent's canonicalLane when it disagrees with the caller",
            executor.contains("intentLane.equals(lane, true)") &&
                executor.contains("PREFLIGHT_LANE_CONVERGED_TO_INTENT_6671"),
        )
        assertTrue(
            "canOpen call must consume convergedLane (not the raw caller lane) so requestedLane matches intent canonicalLane",
            executor.contains("lane = convergedLane,"),
        )
    }
}

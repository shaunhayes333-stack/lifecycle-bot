package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AssetClass
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.7819 — LoopSupervisor: the blocking causes behind 15-37s bot cycles and
 * 117 supervisor worker timeouts in the 5.0.7813 paper snapshot.
 */
class Aate7819LoopSupervisorTest {

    private fun src(rel: String): String = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private fun pos(
        id: String,
        lane: String,
        cls: AssetClass,
        mint: String = "MintX",
    ) = CanonicalPositionAuthority6441.Position(
        positionId = id, mode = "paper", mint = mint, symbol = "SYM", lane = lane, runId = "run",
        openedAtMs = 1L, entryCostSol = 0.1,
        remainingQtyRaw = BigInteger.valueOf(1_000L), originalQtyRaw = BigInteger.valueOf(1_000L),
        soldCostBasisSol = 0.0, realizedPnlSol = 0.0, realizedProceedsSol = 0.0, feesSol = 0.0,
        tokenDecimals = 6, lifecycle = CanonicalPositionAuthority6441.Lifecycle.OPEN,
        lastMutationMs = 1L, quarantineReason = "", assetClass = cls,
    )

    private fun probationEntry(addedBy: String, source: String) = GlobalTradeRegistry.ProbationEntry(
        mint = "Mint7819xxxxxxxxxxxxxxxxxxxxxxxxxxxx", symbol = "PUMP", addedAt = 0L,
        addedBy = addedBy, source = source, initialMcap = 3_000.0, initialLiquidity = 3_000.0,
        initialConfidence = 10, isEstimatedLiquidity = false, isSingleSource = true,
    )

    // ── probation ping-pong ─────────────────────────────────────────────────
    @Test fun demote_wrappers_unwrap_to_the_creating_scanner() {
        assertEquals("PUMP_PORTAL_WS", GlobalTradeRegistry.normalizeProbationSource7819("DEMOTED_DEMOTED_pump_portal_ws+PROBATION"))
        assertEquals("PUMP_PORTAL_WS", GlobalTradeRegistry.normalizeProbationSource7819("SOURCE_BALANCE_DIVERT:PUMP_PORTAL_WS"))
        val keys = GlobalTradeRegistry.probationSourceKeys7819(
            "PROBATION_DEMOTE:SOURCE_BALANCE_PUMP_DOMINANCE_post_intake|PUMP_PORTAL_WS,PUMP_FUN_NEW",
            "DEMOTED_PUMP_PORTAL_WS+PROBATION",
        )
        assertTrue("the creating scanner is not a second scanner", "PUMP_PORTAL_WS" in keys)
        assertTrue("PUMP_FUN_NEW" in keys)
        assertFalse("a genuinely different scanner still confirms", "DEX_BOOSTED" in keys)
    }

    @Test fun demoted_row_waits_for_evidence_until_the_floor() {
        val demoted = probationEntry("DEMOTED_PUMP_PORTAL_WS", "PROBATION_DEMOTE:X|PUMP_PORTAL_WS")
        val fresh = probationEntry("PUMP_PORTAL_WS", "PUMP_PORTAL_WS")
        assertTrue(GlobalTradeRegistry.demotedAwaitingEvidence7819(demoted, 120_000L))
        assertFalse(GlobalTradeRegistry.demotedAwaitingEvidence7819(demoted, GlobalTradeRegistry.DEMOTED_REPROMOTE_FLOOR_MS_7819))
        assertFalse("ordinary probation rows keep the 90s timeout promotion",
            GlobalTradeRegistry.demotedAwaitingEvidence7819(fresh, 120_000L))
    }

    @Test fun registry_uses_the_unwrapped_source_test_on_both_probation_doors() {
        val reg = src("engine/GlobalTradeRegistry.kt")
        assertTrue(reg.contains("val priorSources7475 = probationSourceKeys7819(existingProbation.source, existingProbation.addedBy)"))
        assertTrue(reg.contains("incoming7819 !in probationSourceKeys7819(existing.source, existing.addedBy)"))
        assertFalse("same-source re-sighting must not count as a scanner", reg.contains("existing.additionalScanners.add(addedBy)"))
        assertTrue(reg.contains("elapsed >= PROBATION_MAX_TIME_MS && !demotedAwaitingEvidence7819(entry, elapsed)"))
        assertTrue(reg.contains("TIMEOUT_AUTO_PROMOTE"))
    }

    // ── off-chain markets out of Solana exit/mark paths ─────────────────────
    @Test fun off_chain_market_rows_are_trader_owned() {
        val c = CanonicalPositionAuthority6441
        assertTrue(c.isTraderOwnedOffChainMarket7819(pos("STOCK_1", "STOCK_SPOT", AssetClass.STOCK, "AAPL")))
        assertTrue(c.isTraderOwnedOffChainMarket7819(pos("FX_1", "FOREX", AssetClass.FOREX, "EURUSD")))
        assertTrue("legacy defaulted class is caught by the positionId prefix",
            c.isTraderOwnedOffChainMarket7819(pos("STOCK_2", "STOCK_SPOT", AssetClass.SOLANA_TOKEN, "TSLA")))
        assertFalse(c.isTraderOwnedOffChainMarket7819(pos("p1", "SHITCOIN", AssetClass.SOLANA_TOKEN)))
        assertFalse("crypto alt keeps its held-hot mark + risk clock path",
            c.isTraderOwnedOffChainMarket7819(pos("ALT_1", "CRYPTO_ALT", AssetClass.CRYPTO_ALT, "base|0xabc")))
    }

    @Test fun solana_exit_feed_risk_clock_and_held_hot_skip_off_chain_rows() {
        val bot = src("engine/BotService.kt")
        val snap = bot.substringAfter("private fun canonicalExitTokenSnapshot6512()").substringBefore("val tokenByMint")
        assertTrue(snap.contains("solanaExitScope7819("))
        assertTrue(bot.contains("isTraderOwnedOffChainMarket7819(it)"))
        val clock = src("engine/truth/CanonicalRiskClock6454.kt")
        assertTrue(clock.contains("openPositionCount = riskScope7819?.size ?: -1"))
        assertTrue(clock.contains("val ordered7213 = riskScope7819.sortedBy { it.positionId }"))
        assertTrue("latch pruning still sees the full set",
            clock.contains("open7213.mapTo(HashSet(open7213.size)) { it.positionId }"))
        val held = src("engine/truth/HeldHotMarkAuthority7419.kt")
        assertTrue(held.contains("!CanonicalPositionAuthority6441.isTraderOwnedOffChainMarket7819(it)"))
    }

    // ── supervisor worker: rugcheck wall clock ──────────────────────────────
    @Test fun rugcheck_has_a_whole_call_deadline_inside_the_worker_budget() {
        val s = src("engine/TokenSafetyChecker.kt")
        assertTrue(s.contains(".callTimeout(RUGCHECK_ATTEMPT_CAP_MS_7819, TimeUnit.MILLISECONDS)"))
        assertTrue(s.contains("if (attempt > 0 && rugcheckBudgetSpent7819(startedAt7819))"))
        val cap = Regex("RUGCHECK_ATTEMPT_CAP_MS_7819: Long = ([0-9_]+)L").find(s)!!.groupValues[1].replace("_", "").toLong()
        val total = Regex("RUGCHECK_TOTAL_BUDGET_MS_7819: Long = ([0-9_]+)L").find(s)!!.groupValues[1].replace("_", "").toLong()
        val worker = Regex("SUPERVISOR_WORKER_TIMEOUT_MS: Long = ([0-9_]+)L").find(src("engine/BotService.kt"))!!
            .groupValues[1].replace("_", "").toLong()
        assertTrue("one attempt fits the total", cap <= total)
        assertTrue("rugcheck alone can no longer exhaust the supervisor worker", total + 500L < worker)
    }
}

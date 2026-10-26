package com.lifecyclebot.engine

import com.lifecyclebot.engine.learning.TacticSwitcher
import com.lifecyclebot.engine.truth.CostLedger7962
import com.lifecyclebot.engine.truth.EntryEconomics8329
import com.lifecyclebot.engine.truth.NetEdge8030
import com.lifecyclebot.engine.truth.TradePlan7739
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8329 — entry economics bind inside Entry8114Mode8326: the lane's realised closes refuse in mode (and re-open on
 * provable evidence), the pre-broadcast chase check per tactic, the route-minimum promotion reason, and the pre-trade
 * cost truth (max of the model and the lane's measured median at 2+ trips).
 */
class Aate8329EntryEconomicsTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @After fun reset() { EntryEconomics8329.resetForTests8329() }

    // ── 1. realised lane loss binds in mode ──

    @Test fun laneShutIsTheRealisedGovernorRule() {
        assertTrue(EntryEconomics8329.laneShut8329(3.0, -5.0))
        assertTrue(EntryEconomics8329.laneShut8329(4.0, -0.1))
        assertFalse("two closes do not shut", EntryEconomics8329.laneShut8329(2.0, -50.0))
        assertFalse("a non-negative mean does not shut", EntryEconomics8329.laneShut8329(5.0, 0.0))
        assertFalse(EntryEconomics8329.laneShut8329(Double.NaN, -5.0))
    }

    @Test fun realisedRefusalBindsOverThe8114AdmitButNotOverHardSafety() {
        val s=src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(s.contains("EntryEconomics8329.laneRealisedRefusal8329(l, nowMs)"))
        assertTrue(s.contains("val final = core ?: hold"))
        assertTrue(s.contains("EvidenceHold8336.refusal8336(ts, l, false, nowMs, candidateVersion8397 = candidateVersion)"))
        assertFalse(s.contains("Entry8114Mode8326"))
        assertFalse(com.lifecyclebot.engine.truth.FdgGate8365.hardSafety8392("LIVE_PROVEN_SETUP_ONLY_8059"))
    }

    @Test fun shutLaneReopensOnAWinAfterTheShutOrALastFiveMeanAtOrAboveZero() {
        val since = 1_000L
        val losses = listOf(Triple(900L, -0.01, -20.0), Triple(800L, -0.02, -30.0), Triple(700L, -0.01, -10.0))
        assertFalse("losses only: stays shut", EntryEconomics8329.reopens8329(since, losses))
        assertFalse("a win BEFORE the shut does not re-open", EntryEconomics8329.reopens8329(since, losses + Triple(600L, 0.05, 2.0)))
        assertTrue("the next live close after the shut is a win", EntryEconomics8329.reopens8329(since, losses + Triple(1_100L, 0.001, 2.0)))
        val recovered = listOf(Triple(950L, -0.001, 10.0), Triple(940L, -0.001, 10.0), Triple(930L, -0.001, 10.0),
            Triple(920L, -0.001, 5.0), Triple(910L, -0.001, -30.0), Triple(100L, -0.1, -90.0), Triple(90L, -0.1, -90.0))
        assertTrue("the mean of the last five closes is >= 0 even while the all-time mean is not", EntryEconomics8329.reopens8329(since, recovered))
        assertTrue("re-arms only on a NEW losing close", EntryEconomics8329.rearms8329(2_000L, 2_500L, -0.01))
        assertFalse(EntryEconomics8329.rearms8329(2_000L, 1_500L, -0.01))
        assertFalse(EntryEconomics8329.rearms8329(2_000L, 2_500L, 0.01))
    }

    @Test fun modeDecisionConsultsTheRealisedLaneRead() {
        val s=src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(s.contains("EntryEconomics8329.laneRealisedRefusal8329(l, nowMs)"))
        assertTrue(s.contains("val final = core ?: hold"))
        assertTrue(s.contains("EvidenceHold8336.refusal8336(ts, l, false, nowMs, candidateVersion8397 = candidateVersion)"))
        assertFalse(s.contains("Entry8114Mode8326"))
        assertFalse(com.lifecyclebot.engine.truth.FdgGate8365.hardSafety8392("LIVE_PROVEN_SETUP_ONLY_8059"))
    }

    // ── 2. pre-broadcast chase check ──

    @Test fun chaseCapIsPerTactic() {
        assertEquals(8.0, EntryEconomics8329.chaseCapPct8329(TradePlan7739.Setup.LAUNCH_EARLY, null, false), 1e-9)
        assertEquals(8.0, EntryEconomics8329.chaseCapPct8329(TradePlan7739.Setup.BASE_BREAKOUT, TacticSwitcher.Tactic.PULLBACK, false), 1e-9)
        assertEquals(2.5, EntryEconomics8329.chaseCapPct8329(TradePlan7739.Setup.PULLBACK_RECLAIM, null, false), 1e-9)
        assertEquals(2.5, EntryEconomics8329.chaseCapPct8329(null, TacticSwitcher.Tactic.REACCUMULATION, false), 1e-9)
        assertEquals(2.5, EntryEconomics8329.chaseCapPct8329(null, TacticSwitcher.Tactic.PULLBACK, false), 1e-9)
        assertEquals(8.0, EntryEconomics8329.chaseCapPct8329(null, TacticSwitcher.Tactic.BREAKOUT, false), 1e-9)
        assertEquals(5.0, EntryEconomics8329.chaseCapPct8329(null, TacticSwitcher.Tactic.MOMENTUM, false), 1e-9)
        assertEquals(4.0, EntryEconomics8329.chaseCapPct8329(null, null, false), 1e-9)
        assertTrue("an exhausted rally has no cap: any move up refuses", EntryEconomics8329.chaseCapPct8329(TradePlan7739.Setup.LAUNCH_EARLY, null, true).isNaN())
        assertEquals(8.0, EntryEconomics8329.CAP_LAUNCH_PCT_8329, 1e-9)
        assertEquals(5.0, EntryEconomics8329.CAP_MOMENTUM_PCT_8329, 1e-9)
        assertEquals(4.0, EntryEconomics8329.CAP_DEFAULT_PCT_8329, 1e-9)
        assertEquals(2.5, EntryEconomics8329.CAP_PULLBACK_PCT_8329, 1e-9)
        assertEquals(5, EntryEconomics8329.REOPEN_LAST_N_8329)
    }

    @Test fun consumedEdgeDecision() {
        assertNull("a cheaper quote never refuses", EntryEconomics8329.consumed8329(-3.0, 2.5, 20.0, 5.0))
        assertNull("no move", EntryEconomics8329.consumed8329(0.0, 2.5, 20.0, 5.0))
        assertEquals("OVER_CAP", EntryEconomics8329.consumed8329(13.5, 8.0, 40.0, 5.0))
        assertEquals("EXHAUSTED_RALLY", EntryEconomics8329.consumed8329(0.5, Double.NaN, 40.0, 5.0))
        assertNull("inside the cap with the remaining move still 1.5x the round trip", EntryEconomics8329.consumed8329(6.0, 8.0, 30.0, 10.0))
        assertEquals("inside the cap but the remaining move no longer clears 1.5x the round trip", "EDGE_CONSUMED",
            EntryEconomics8329.consumed8329(6.0, 8.0, 20.0, 10.0))
        assertNull("an unknown expected move or cost is not a refusal here (the admission economics already ran)",
            EntryEconomics8329.consumed8329(6.0, 8.0, Double.NaN, 10.0))
    }

    @Test fun quotePriceAndMoveAreUnitSafe() {
        // 0.03 SOL at $150 buys 1,000 UI tokens (6 decimals): $0.0045 a token.
        assertEquals(0.0045, EntryEconomics8329.quotePxUsd8329(0.03, 1_000_000_000L, 6, 150.0), 1e-12)
        assertTrue(EntryEconomics8329.quotePxUsd8329(0.03, 1_000_000_000L, null, 150.0).isNaN())
        assertTrue(EntryEconomics8329.quotePxUsd8329(0.03, 0L, 6, 150.0).isNaN())
        assertTrue(EntryEconomics8329.quotePxUsd8329(0.03, 1_000_000_000L, 6, 0.0).isNaN())
        assertEquals("the quote's own impact is not a market move", 10.0, EntryEconomics8329.quoteMovePct8329(1.0, 1.12, 2.0)!!, 1e-9)
        assertEquals(12.0, EntryEconomics8329.quoteMovePct8329(1.0, 1.12, Double.NaN)!!, 1e-9)
        assertNull("prices that cannot be one token are not compared", EntryEconomics8329.quoteMovePct8329(1.0, 10.0, 0.0))
    }

    @Test fun chaseCheckSitsImmediatelyBeforeBroadcastOnBothLiveBuyRoutes() {
        val exec = src("engine/Executor.kt")
        val jup = exec.indexOf("EntryEconomics8329.jupiterQuoteRefusal8329(ts, intent8198.canonicalLane, effectiveSol, jq7325)")
        assertTrue("the Jupiter quote is checked after the SecurityGuard quote check", jup > 0)
        val guard = exec.lastIndexOf("val qGuard = security.validateQuote(jq7325, isBuy = true, inputSol = sol)", jup)
        val build = exec.indexOf("buildBuyTxOrRaydium8089(ts, wallet, sol, tradeKey, executionQuote7688, c, submission8397)", jup)
        assertTrue("after the quote guard, before the swap is built / signed", guard in 1 until jup && build > jup)
        val pump = exec.indexOf("EntryEconomics8329.markRefusal8329(ts, layerTag, pumpBuyPlan.solAmount)")
        val pumpSend = exec.indexOf("val pumpFirstResult: Pair<String, Double>? = if (pumpBuyPlan == null) null else tryPumpPortalBuy(")
        assertTrue("the PumpPortal route is checked on the curve mark before its send", pump in 1 until pumpSend)
        val econ = src("engine/truth/EntryEconomics8329.kt")
        assertTrue("both routes share one check", econ.contains("preBroadcastRefusal8329(ts, lane.orEmpty(), sizeSol, px, quote.priceImpactPct, \"JUPITER\")") &&
            econ.contains("preBroadcastRefusal8329(ts, lane.orEmpty(), sizeSol, Double.NaN, 0.0, \"PUMPPORTAL\")"))
        assertTrue("the decision price and time come from the admitted decision", econ.contains("ForwardReturnLabeler7731.decisionPx8094(ts.mint)"))
        assertTrue("the remaining move is judged at the pre-trade cost truth", econ.contains("NetEdge8030.preTradeCostPct8329(lane, sizeSol, liq, ts.mint, ts.lastMcap)"))
        assertTrue("a consumed entry is graded against what happened next", econ.contains("ForwardReturnLabeler7731.observe(ts, \"\$MISSED_LANE_PREFIX_8329\$lane\", false, label, nowMs)"))
        assertTrue("the structural re-entry re-arms it", econ.contains("StructureTracker7962.hlReclaim7962(ts.mint, nowMs)"))
        assertTrue("EntryChase7961's learned penalty is untouched", src("engine/truth/EntryChase7961.kt").contains("private const val MIN_LIVE_N = 8"))
        assertEquals("ENTRY_CHASE_CONSUMED_8329", EntryEconomics8329.CHASE_CONSUMED_8329)
    }

    // ── 3. route-minimum promotion needs a reason ──

    @Test fun promotionReasonIsOneOfThree() {
        assertEquals("SETUP_RECORD_POSITIVE", EntryEconomics8329.promotionReason8329(0.001, 0.0287, 12.0, 3.0, false))
        assertNull("eleven labels are not a record", EntryEconomics8329.promotionReason8329(0.001, 0.0287, 11.0, 3.0, false))
        assertNull("a positive mean on 12 labels is required, not merely non-negative", EntryEconomics8329.promotionReason8329(0.001, 0.0287, 20.0, 0.0, false))
        assertEquals("EARLY_RUNNER", EntryEconomics8329.promotionReason8329(0.0, 0.0287, 0.0, Double.NaN, true))
        assertEquals("KELLY_GE_25PCT_OF_MIN", EntryEconomics8329.promotionReason8329(0.0072, 0.0287, 0.0, Double.NaN, false))
        assertNull("Kelly ~0, no record, no early runner: unsupported", EntryEconomics8329.promotionReason8329(0.0001, 0.0287, 0.0, Double.NaN, false))
        assertEquals(12, EntryEconomics8329.SETUP_RECORD_MIN_N_8329)
        assertEquals(0.25, EntryEconomics8329.KELLY_SHARE_OF_MIN_8329, 1e-12)
        assertEquals("ROUTE_MIN_PROMOTION_UNSUPPORTED_8329", EntryEconomics8329.PROMOTION_UNSUPPORTED_8329)
    }

    @Test fun promotionReasonIsAskedOnBothPromotionPaths() {
        val mp = src("engine/truth/MinPromotion8188.kt")
        // superseded by V5.0.8370 — owner restored 8114 behaviour ("Small stakes trade"): asked on both paths, as a label only.
        assertTrue(mp.contains("if (r == null) SmallStake8370.telemetry8370(EntryEconomics8329.promotionReasonRefusal8329(mint, lane, requestedSol, promotedSol))"))
        assertTrue("the promotion is costed at the pre-trade cost truth", mp.contains("NetEdge8030.preTradeCostPct8329(l, s, liq, mint, mcap)"))
        val rp = src("engine/truth/LiveRiskPolicy7807.kt")
        assertTrue(rp.contains("EntryEconomics8329.promotionReasonRefusal8329(i.mint8329, i.lane, size, executableFloor8269)"))
        assertTrue(rp.contains("NetEdge8030.preTradeCostPct8329(effectiveLane7811, s, liquidityUsd, mint, mcap8153)"))
        val econ = src("engine/truth/EntryEconomics8329.kt")
        // superseded by V5.0.8343 — a grab-lane admit (RunnerGrabLane8343) is the early runner too; the LAUNCH_EARLY plan test is unchanged
        assertTrue("an early runner is a LAUNCH_EARLY plan inside the launch window", econ.contains("plan.setup != TradePlan7739.Setup.LAUNCH_EARLY") &&
            econ.contains("StrategyMove8153.fresh8153(age)") && econ.contains("val early = ts != null && (earlyRunner8329(ts, nowMs) || com.lifecyclebot.engine.RunnerGrabLane8343.admitted8343(mint, lane, nowMs))"))
        assertTrue("a pure caller with no mint still gets the Kelly test", econ.contains("if (!(promotedSol > 0.0) || !(requestedSol < promotedSol)) return null"))
    }

    // ── 4. cost truth ──

    @Test fun measuredMedianAtTwoTripsLiftsTheModelNeverLowersIt() {
        val rows = listOf(doubleArrayOf(14.0, 0.03), doubleArrayOf(15.6, 0.03))
        assertNull("one trip is not a median", CostLedger7962.measuredMedianFrom8329(rows.take(1), 2))
        assertEquals(14.8, CostLedger7962.measuredMedianFrom8329(rows, 2)!!, 1e-9)
        assertEquals("never negative", 0.0, CostLedger7962.measuredMedianFrom8329(listOf(doubleArrayOf(-2.0, 0.03), doubleArrayOf(-1.0, 0.03)), 2)!!, 1e-9)
        assertNull("the 8-trade rule is unchanged for labels and exits", CostLedger7962.measuredFrom7962(rows, 0.03))
        val ne = src("engine/truth/NetEdge8030.kt")
        assertTrue(ne.contains("val measured = (try { CostLedger7962.measuredMedianPct8329(lane, 2) } catch (_: Throwable) { null }) ?: return model"))
        assertTrue(ne.contains("return maxOf(model, measured)"))
        assertTrue("the 8114 core's economic refusal reads the cost truth", ne.contains("val cost = preTradeCostPct8329(lane, entryTicket8151(), liquidity8037(ts), ts.mint, ts.lastMcap)"))
        assertTrue("exits keep the model (wins are not capped)", ne.contains("val rt = costPct8030(lane, sizeSol, liquidityUsd, mint, mcapUsd)"))
        assertTrue(NetEdge8030.entryClears8030(15.0, 10.0))
        assertFalse(NetEdge8030.entryClears8030(14.0, 10.0))
    }

    // ── 5. report ──

    @Test fun reportLinesAreNamed() {
        val line = EntryEconomics8329.statusLine8329()
        assertTrue(line, line.startsWith("chase8329[") && line.contains(" promo8329[") && line.contains(" laneShut8329["))
    }

    // ── 6. the mode stays on; nothing label-based became binding ──

    @Test fun modeStaysOnByDefaultAndLabelRefusalsStayBypassed() {
        val s=src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(s.contains("EntryEconomics8329.laneRealisedRefusal8329(l, nowMs)"))
        assertTrue(s.contains("val final = core ?: hold"))
        assertTrue(s.contains("EvidenceHold8336.refusal8336(ts, l, false, nowMs, candidateVersion8397 = candidateVersion)"))
        assertFalse(s.contains("Entry8114Mode8326"))
        assertFalse(com.lifecyclebot.engine.truth.FdgGate8365.hardSafety8392("LIVE_PROVEN_SETUP_ONLY_8059"))
    }
}

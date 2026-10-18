package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LiveRiskPolicy7807
import com.lifecyclebot.engine.truth.LiveSizingTruth8136
import com.lifecyclebot.engine.truth.SizingNetEdge8153
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8153 — cost-aware entry and sizing: net EV at the size, net Kelly, envelope, pre-broadcast recheck, seal. */
class Aate8153SizingTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private fun inputs(
        lane: String = "QUALITY", equity: Double = 1.0, upstream: Double = 0.18, execMin: Double = 0.025,
        stop: Double? = 10.0, first: Double? = 30.0, target: Double? = 40.0, liq: Double = 200_000.0,
        kelly: Double = 0.0, ceiling: Double = 0.0,
        gross: Double = Double.NaN, rec: SizingNetEdge8153.EdgeRecord8153? = null, cost: ((Double) -> Double)? = null,
        tradeable: Double = Double.NaN, laneDeployed: Double = Double.NaN,
    ) = LiveRiskPolicy7807.Inputs(
        lane = lane, equitySol = equity, upstreamSol = upstream, execMinSol = execMin,
        planStopPct = stop, planFirstTargetPct = first, planTargetPct = target,
        solUsd = 200.0, liquidityUsd = liq, liveCloses = 0, liveMeanNetPct = 0.0,
        liveTotalNetSol = 0.0, liveWrPct = 0.0, drawdownFrac = 0.0, laneDailyLossSol = 0.0,
        governorLossMult = 1.0, partialProviderEvidence = false, oracleUnproven = false, laneOpenLive = 0,
        edgeKellyFrac8057 = kelly, compoundingCeilingSol8094 = ceiling,
        setupExpectedGrossPct8153 = gross, edgeRecord8153 = rec, costAtSizePct8153 = cost,
        tradeableSol8153 = tradeable, laneDeployedSol8153 = laneDeployed,
    )

    /** 8127's proven SHITCOIN candidate: 0.2281 SOL equity, route minimum 0.02892, quarter-Kelly share 0.20. */
    private fun proven(rec: SizingNetEdge8153.EdgeRecord8153?, cost: (Double) -> Double) =
        inputs(lane = "SHITCOIN", equity = 0.2281, upstream = 0.02892, execMin = 0.02892, kelly = 0.20,
            ceiling = 0.2281 * 0.25, rec = rec, cost = cost)

    @Test fun grossFivePercentOnASevenAndAHalfPercentRoundTripIsNotPositiveAndSizesZero() {
        assertTrue(SizingNetEdge8153.netNonPositive8153(5.0, 7.5))
        assertEquals(-2.5, SizingNetEdge8153.netEdgePct8153(5.0, 7.5), 1e-12)
        assertFalse(SizingNetEdge8153.netNonPositive8153(12.0, 7.5))
        assertFalse(SizingNetEdge8153.netNonPositive8153(Double.NaN, 7.5))   // unmeasured: the target test alone judges
        // The plan names +30% (it clears 2x the 7.5% trip), the setup itself expects +5% gross: refused, size zero.
        val d = LiveRiskPolicy7807.decide(inputs(gross = 5.0, cost = { 7.5 }))
        assertFalse(d.open)
        assertEquals(0.0, d.sizeSol, 0.0)
        assertEquals(SizingNetEdge8153.NET_EV_REFUSAL_8153, d.reason)
        assertEquals(7.5, d.costPct, 1e-12)
        // Before 8153 the same candidate opened on the target test alone.
        assertTrue(LiveRiskPolicy7807.decide(inputs(cost = { 7.5 })).open)
        // A setup that does clear the trip at the size still opens.
        val ok = LiveRiskPolicy7807.decide(inputs(gross = 20.0, cost = { 7.5 }))
        assertTrue(ok.reason, ok.open)
        assertEquals(30.0, ok.firstTargetPct8153, 1e-12)
        // The realistic cost never prices cheaper than the 7766 model, and a faulty cost reader falls back to it.
        val base = inputs()
        val model = LiveRiskPolicy7807.roundTripCostPct(0.05, 200.0, 200_000.0)
        assertEquals(model, LiveRiskPolicy7807.costAtSizePct8153(base.copy(costAtSizePct8153 = { 0.1 }), 0.05), 1e-12)
        assertEquals(9.0, LiveRiskPolicy7807.costAtSizePct8153(base.copy(costAtSizePct8153 = { 9.0 }), 0.05), 1e-12)
        assertEquals(model, LiveRiskPolicy7807.costAtSizePct8153(base.copy(costAtSizePct8153 = { throw IllegalStateException() }), 0.05), 1e-12)
    }

    @Test fun kellyIsSizedOnTheNetEdgeAtTheSealedSize() {
        // +8% net per label at a 1.5% label cost, sd 20%, n 100: SE 2, edge 6% -> quarter-Kelly 0.375 (capped 0.30).
        val rec = SizingNetEdge8153.EdgeRecord8153(meanNetPct = 8.0, variancePct2 = 400.0, n = 100.0, labelCostPct = 1.5)
        assertEquals(0.20, SizingNetEdge8153.netKellyFrac8153(0.20, rec, 1.5), 1e-12)       // never above the proof
        assertEquals(0.20, SizingNetEdge8153.netKellyFrac8153(0.20, rec, 1.0), 1e-12)       // a cheaper fill is not credited
        assertEquals(0.1875, SizingNetEdge8153.netKellyFrac8153(0.20, rec, 4.5), 1e-12)     // 3 points dearer: edge 3%
        assertEquals(0.0, SizingNetEdge8153.netKellyFrac8153(0.20, rec, 7.5), 0.0)          // 6 points dearer: no edge
        assertEquals(0.20, SizingNetEdge8153.netKellyFrac8153(0.20, null, 7.5), 1e-12)      // only the share known
        assertEquals(0.0, SizingNetEdge8153.netKellyFrac8153(0.0, rec, 1.5), 0.0)
        // In the policy: the same proven share compounds without the record, and is consumed by the cost with it.
        val gross = LiveRiskPolicy7807.decide(proven(null) { 7.5 })
        assertTrue(gross.labels.contains("PROVEN_EDGE_COMPOUNDING_SIZE_8057"))
        assertEquals(0.2281 * 0.20, gross.sizeSol, 1e-9)
        val net = LiveRiskPolicy7807.decide(proven(rec) { 7.5 })
        assertTrue(net.labels.contains("KELLY_EDGE_CONSUMED_BY_COST_AT_SIZE_8153"))
        assertFalse(net.labels.contains("PROVEN_EDGE_COMPOUNDING_SIZE_8057"))
        assertTrue("${net.sizeSol} vs ${gross.sizeSol}", net.sizeSol < gross.sizeSol)
        // Proven-edge fractional Kelly is preserved when the edge survives the cost at the size.
        val cheap = LiveRiskPolicy7807.decide(proven(rec) { 3.0 })
        assertTrue(cheap.labels.toString(), cheap.labels.contains("PROVEN_EDGE_COMPOUNDING_SIZE_8057"))
        assertTrue(cheap.sizeSol > 0.02892)
        assertTrue(cheap.sizeSol <= 0.2281 * 0.20 + 1e-9)
    }

    @Test fun aQuoteThatWorsensTheSealedEconomicsIsRefusedBeforeBroadcast() {
        val q = SizingQuoteRecheck8153
        val seal = SizingQuoteRecheck8153.SealedEconomics8153(sizeSol = 0.05, expectedGrossPct = 12.0, firstTargetPct = 30.0, costPct = 4.0,
            impactOneWayPct = 0.1, priceUsd = 1.0, atMs = 1_000L)
        val now = 2_000L
        val lam = 50_000_000L
        assertNull(q.recheck8153(seal, 0.05, lam, 0.1, 1.0, now))                               // as sealed: send
        assertEquals(1.6, q.impactBudgetPct8153(0.1), 1e-12)
        assertEquals(3.0, q.impactBudgetPct8153(2.0), 1e-12)
        // Impact 1.5% (inside the budget) adds 2.8 points of round trip, and the price ran 6% since the seal: 12-6-6.8 < 0.
        assertEquals("PRE_BROADCAST_NET_EV_NONPOSITIVE_8153", q.recheck8153(seal, 0.05, lam, 1.5, 1.06, now))
        assertEquals("PRE_BROADCAST_IMPACT_BEYOND_SEALED_BUDGET_8153", q.recheck8153(seal, 0.05, lam, 2.0, 1.0, now))
        // Unknown depth at the seal: the generic 3% envelope, and the whole fresh impact is extra round trip (12-8 > 0).
        val noDepth = seal.copy(impactOneWayPct = Double.NaN)
        assertNull(q.recheck8153(noDepth, 0.05, lam, 2.0, 1.0, now))
        assertEquals("PRE_BROADCAST_IMPACT_BEYOND_SEALED_BUDGET_8153", q.recheck8153(noDepth, 0.05, lam, 3.5, 1.0, now))
        assertEquals("PRE_BROADCAST_NET_EV_NONPOSITIVE_8153", q.recheck8153(noDepth, 0.05, lam, 2.9, 1.03, now))  // 12 - 3 - 9.8 < 0
        // No measured expectation: the plan's remaining move must still clear the cost-pass ratio.
        val planOnly = seal.copy(expectedGrossPct = Double.NaN)
        assertNull(q.recheck8153(planOnly, 0.05, lam, 0.1, 1.0, now))
        assertEquals("PRE_BROADCAST_COST_CONSUMES_MOVE_8153", q.recheck8153(planOnly, 0.05, lam, 0.1, 1.25, now))
        // No opinion on a stale seal or another ticket; a price that is not one market is not read as drift.
        assertNull(q.recheck8153(seal, 0.05, lam, 2.0, 1.0, 1_000L + SizingQuoteRecheck8153.SEAL_TTL_MS_8153 + 1L))
        assertNull(q.recheck8153(seal, 0.04, 40_000_000L, 2.0, 1.0, now))
        assertNull(q.recheck8153(null, 0.05, lam, 2.0, 1.0, now))
        assertEquals(0.0, q.adverseDriftPct8153(1.0, 0.9), 0.0)
        assertEquals(0.0, q.adverseDriftPct8153(1.0, 5.0), 0.0)
        assertEquals(10.0, q.adverseDriftPct8153(1.0, 1.1), 1e-9)
        // Runtime path: the FDG decision's economics bind to the sealed size, and the final quote is judged on them.
        val t = System.currentTimeMillis()
        q.noteDecision8153("MINT_8153_Q", seal.copy(atMs = t))
        q.bindSeal8153("MINT_8153_Q", 0.05, t)
        assertEquals("PRE_BROADCAST_IMPACT_BEYOND_SEALED_BUDGET_8153", q.preBroadcastRefusal8153("MINT_8153_Q", 0.05, lam, 2.0, 1.0))
        assertNull(q.preBroadcastRefusal8153("MINT_8153_UNSEALED", 0.05, lam, 2.0, 1.0))
        // Wired at the pre-broadcast point on the quote already in hand (no extra quote round trip).
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("SizingQuoteRecheck8153.preBroadcastQuoteRefusal8167(ts, sol, executionQuote7688, intent8198 = intent8198)"))
        assertTrue(ex.indexOf("SizingQuoteRecheck8153.preBroadcastQuoteRefusal8167(") < ex.indexOf("useJito = c.jitoEnabled && !executionQuote7688.isUltra"))
        assertTrue(src("engine/WalletCapacitySeal7868.kt").contains("SizingQuoteRecheck8153.bindSeal8153(mint, sizeSol, nowMs, candidateVersion, lane)"))
        assertTrue(src("engine/truth/LiveRiskPolicy7807.kt").contains("if (stage == \"FDG\") noteSealedEconomics8153(mint, i, d)"))
    }

    @Test fun aSafeMaximumBelowTheMinimumIsRejectedNotPromoted() {
        val r = SizingNetEdge8153
        assertEquals("ROUTE_MIN_EXCEEDS_TRADEABLE_8153", r.promotionRefusal8153(0.0414, 0.03, Double.POSITIVE_INFINITY, 30.0, 3.0, Double.NaN))
        assertEquals("ROUTE_MIN_EXCEEDS_LANE_ENVELOPE_8153", r.promotionRefusal8153(0.0414, Double.NaN, 0.02, 30.0, 3.0, Double.NaN))
        assertEquals("ROUTE_MIN_COST_CONSUMES_MOVE_8153", r.promotionRefusal8153(0.0414, 1.0, 1.0, 30.0, 16.0, Double.NaN))
        assertEquals("ROUTE_MIN_NET_EV_NONPOSITIVE_8153", r.promotionRefusal8153(0.0414, 1.0, 1.0, 30.0, 7.5, 5.0))
        assertNull(r.promotionRefusal8153(0.0414, 1.0, 1.0, 30.0, 3.0, 12.0))
        // The 7840 shape (risk-safe minimum 0.0414 over a 0.02274 risk size) is still promoted when it is payable...
        fun floor(tradeable: Double = Double.NaN, deployed: Double = Double.NaN) = LiveRiskPolicy7807.decide(inputs(
            lane = "MOONSHOT", equity = 0.2113, upstream = 0.02274, execMin = 0.0414, stop = 8.0, first = 30.0, target = 40.0,
            liq = 100_000.0, tradeable = tradeable, laneDeployed = deployed))
        // superseded by V5.0.8188 — payable is not enough either: the 7840 shape breaks its original loss budget at the stop.
        assertEquals(com.lifecyclebot.engine.truth.MinPromotion8188.REFUSED_LOSS_BUDGET_8188, floor().reason)
        // ...but never forced up to the minimum the wallet cannot pay or the lane's envelope cannot hold.
        val unpayable = floor(tradeable = 0.03)
        assertFalse(unpayable.open)
        assertEquals(0.0, unpayable.sizeSol, 0.0)
        assertEquals("ROUTE_MIN_EXCEEDS_TRADEABLE_8153", unpayable.reason)
        val laneFull = floor(deployed = 0.09)
        assertFalse(laneFull.open)
        assertEquals("ROUTE_MIN_EXCEEDS_LANE_ENVELOPE_8153", laneFull.reason)
        assertTrue(laneFull.labels.contains("LANE_PORTFOLIO_ENVELOPE_CAP_8153"))
    }

    @Test fun laneAllocationRespectsThePortfolioEnvelopeWhateverCashIsIdle() {
        val r = SizingNetEdge8153
        assertEquals(0.05, r.laneEnvelopeRoomSol8153(1.0, 10, 20, 0.45), 1e-12)
        assertEquals(0.0, r.laneEnvelopeRoomSol8153(1.0, 10, 20, 0.70), 0.0)
        assertEquals(Double.POSITIVE_INFINITY, r.laneEnvelopeRoomSol8153(1.0, 10, 20, Double.NaN), 0.0)
        assertEquals(0.03, r.envelopeCapSol8153(0.25, Double.POSITIVE_INFINITY, 0.03, Double.NaN), 1e-12)
        // QUALITY on 1 SOL already holds 0.45 SOL; 5 SOL — or 50 — of idle cash does not lift it past half the book.
        val free = LiveRiskPolicy7807.decide(inputs(tradeable = 5.0))
        val idle = LiveRiskPolicy7807.decide(inputs(tradeable = 5.0, laneDeployed = 0.45))
        val idler = LiveRiskPolicy7807.decide(inputs(tradeable = 50.0, laneDeployed = 0.45))
        assertTrue(free.sizeSol > 0.05)
        assertTrue(idle.reason, idle.open)
        assertEquals(0.05, idle.sizeSol, 1e-9)
        assertEquals(idle.sizeSol, idler.sizeSol, 1e-12)
        assertTrue(idle.labels.contains("LANE_PORTFOLIO_ENVELOPE_CAP_8153"))
        assertEquals("LANE_PORTFOLIO_ENVELOPE", LiveSizingTruth8136.bindingConstraintOf8136(inputs(tradeable = 5.0, laneDeployed = 0.45), idle))
        // Tradeable SOL (wallet - reserve - other sealed reservations) binds too.
        val thin = LiveRiskPolicy7807.decide(inputs(tradeable = 0.04))
        assertEquals(0.04, thin.sizeSol, 1e-12)
        assertTrue(thin.labels.contains("TRADEABLE_CASH_CAP_8153"))
        assertTrue(src("engine/truth/LiveRiskPolicy7807.kt").contains("laneDeployedSol8153 = laneDeployedSol8153(effectiveLane7811)"))
    }

    @Test fun theSealIsImmutableThroughExecution() {
        assertTrue(WalletCapacitySeal7868.sealHonours8153(0.05, 0.05))
        assertTrue(WalletCapacitySeal7868.sealHonours8153(0.05, 0.050000001))   // one lamport of rounding
        assertFalse(WalletCapacitySeal7868.sealHonours8153(0.05, 0.051))
        assertFalse(WalletCapacitySeal7868.sealHonours8153(0.05, 0.049))
        val now = 1_000_000L
        val seal = WalletCapacitySeal7868.Seal(sizeSol = 0.05, walletSol = 1.0, solUsd = 200.0, atMs = now - 1_000L)
        // A larger size is never honoured under the seal's capacity, whatever the wallet holds.
        assertEquals("SEALED_SIZE_EXCEEDS_CURRENT_CAP_7835",
            WalletCapacitySeal7868.decide(seal, 0.06, 1.0, 200.0, 0.01, "SEALED_SIZE_EXCEEDS_CURRENT_CAP_7835", now))
        assertNull(WalletCapacitySeal7868.decide(seal, 0.05, 1.0, 200.0, 0.01, "SEALED_SIZE_EXCEEDS_CURRENT_CAP_7835", now))
        assertEquals("SEALED_SIZE_EXCEEDS_CURRENT_CAP_7835", SealedExecutionSize7835.boundsRefusal(0.06, 0.05, 0.03))
        // A swap that would spend more lamports than the ticket is refused at the last moment.
        assertEquals("PRE_BROADCAST_SIZE_INFLATED_AFTER_SEAL_8153",
            SizingQuoteRecheck8153.recheck8153(null, 0.05, 50_000_002L, 0.0, 0.0, now))
        assertNull(SizingQuoteRecheck8153.recheck8153(null, 0.05, 50_000_000L, 0.0, 0.0, now))
        // The executor consumes the sealed size as-is (no downstream re-size).
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("LIVE_FINAL_SIZE_CONSUMED_SEALED_7850"))
        assertTrue(ex.contains("kotlin.math.abs(requestedSol - intent.resolvedSize) > 1e-9") ||
            src("engine/SealedExecutionSize7835.kt").contains("kotlin.math.abs(requestedSol - intent.resolvedSize) > 1e-9"))
    }

    @Test fun theResolverDoesNotSizeAFinalisedLiveDecisionFromTheStopTwice() {
        // A request at or below the policy's fresh decision on the mint is that decision.
        assertTrue(LiveRiskPolicy7807.finalisedBy8153(50_000_000L, 1_000L, 30_000_000L, 2_000L))
        assertTrue(LiveRiskPolicy7807.finalisedBy8153(50_000_000L, 1_000L, 50_000_001L, 2_000L))
        assertFalse(LiveRiskPolicy7807.finalisedBy8153(50_000_000L, 1_000L, 50_000_002L, 2_000L))   // larger: not it
        assertFalse(LiveRiskPolicy7807.finalisedBy8153(50_000_000L, 1_000L, 30_000_000L, 1_000L + LiveRiskPolicy7807.FINALISED_TTL_MS_8153 + 1L))
        assertFalse(LiveRiskPolicy7807.finalisedBy8153(0L, 1_000L, 30_000_000L, 2_000L))
        assertFalse(LiveRiskPolicy7807.policyFinalised8153("", 0.03))
        val osr = src("engine/truth/OrderSizeResolver6441.kt")
        assertTrue(osr.contains("val manualCap7715 = if (policyFinal8153) Double.POSITIVE_INFINITY else try {"))
        assertTrue(osr.contains("FieldManual7715.riskCapSol(laneName, paperMode, authoritativeCash)"))   // paper / undecided keep it
        assertTrue(osr.contains("val policyFinal8153 = !paperMode &&"))
    }
}

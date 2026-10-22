package com.lifecyclebot.engine

import com.lifecyclebot.engine.CostTrim8151
import com.lifecyclebot.engine.WalletManager
import com.lifecyclebot.engine.truth.CurveReserves7951
import com.lifecyclebot.engine.truth.FieldManual7715
import com.lifecyclebot.engine.truth.NetEdge8030
import com.lifecyclebot.network.PumpCurveKeys7269
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8337 FIX 1 / 5d / 10c — a pump curve's impact is priced on its VIRTUAL reserve (30 + real SOL), never the real
 * SOL alone: a fresh curve read liq=$3 -> impact 50%/side -> cost 105%, which blocked the +40% spike sells, delayed the
 * break-even lock to ~+112%, widened every stop to -25% and zeroed Kelly. The route fee follows the route a pump mint
 * takes; a relaxed integer floor rounds up.
 */
class Aate8337CostTruthTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private val solUsd = 150.0
    private val ticketSol = NetEdge8030.DEFAULT_TICKET_SOL

    @Test fun activeCurveDepthIsTheVirtualReserveAndEverythingElsePassesThrough() {
        CurveReserves7951.resetForTest7951()
        val now = System.currentTimeMillis()
        val curve = "Curve8337FreshAAAAAAAAAAAAAAAAAAAAAAAAApump"
        CurveReserves7951.noteAccount7951(curve, 30.02, 1_072_000_000.0, 0.02, now)   // $3 of real SOL at $150
        val depth = FieldManual7715.depthUsdForImpact8337(curve, 3.0, solUsd, now)
        assertEquals(2.0 * 30.02 * solUsd, depth, 1e-9)
        // unknown liquidity on an active curve still prices against the virtual reserve
        assertEquals(2.0 * 30.02 * solUsd, FieldManual7715.depthUsdForImpact8337(curve, 0.0, solUsd, now), 1e-9)
        // no SOL price: the figure given is the figure used
        assertEquals(3.0, FieldManual7715.depthUsdForImpact8337(curve, 3.0, 0.0, now), 0.0)
        assertEquals(3.0, FieldManual7715.depthUsdForImpact8337(curve, 3.0, Double.NaN, now), 0.0)
        // non-curve / unknown / blank mints pass the pool figure through unchanged
        assertEquals(1234.5, FieldManual7715.depthUsdForImpact8337("NonCurve8337BBBBBBBBBBBBBBBBBBBBBBBBBBBB", 1234.5, solUsd, now), 0.0)
        assertEquals(1234.5, FieldManual7715.depthUsdForImpact8337("", 1234.5, solUsd, now), 0.0)
        // a stale curve reading is not an active curve
        val stale = "Curve8337StaleCCCCCCCCCCCCCCCCCCCCCCCCCCpump"
        CurveReserves7951.noteAccount7951(stale, 35.0, 920_000_000.0, 5.0, now - 2L * 60L * 60_000L)
        assertEquals(750.0, FieldManual7715.depthUsdForImpact8337(stale, 750.0, solUsd, now), 0.0)
        // a graduated curve trades on its pool, not its old reserve
        val grad = "Curve8337GraduatedDDDDDDDDDDDDDDDDDDDDDDpump"
        CurveReserves7951.noteAccount7951(grad, 115.0, 280_000_000.0, 85.0, now)
        PumpCurveKeys7269.markGraduated7392(grad)
        assertEquals(25_000.0, FieldManual7715.depthUsdForImpact8337(grad, 25_000.0, solUsd, now), 0.0)
        CurveReserves7951.resetForTest7951()
    }

    @Test fun aThreeDollarCurveReadsATenthOfAPercentImpactPerSideNotFifty() {
        CurveReserves7951.resetForTest7951()
        val now = System.currentTimeMillis()
        val curve = "Curve8337ImpactEEEEEEEEEEEEEEEEEEEEEEEEpump"
        CurveReserves7951.noteAccount7951(curve, 30.02, 1_072_000_000.0, 0.02, now)
        val sizeUsd = ticketSol * solUsd
        val perSideOnCurve = FieldManual7715.impactRoundTripPct(sizeUsd, FieldManual7715.depthUsdForImpact8337(curve, 3.0, solUsd, now)) / 2.0
        val perSideOnRealSol = FieldManual7715.impactRoundTripPct(sizeUsd, 3.0) / 2.0
        assertTrue("curve impact/side=$perSideOnCurve", perSideOnCurve > 0.05 && perSideOnCurve < 0.15)
        assertEquals(50.0, perSideOnRealSol, 1e-9)

        // the live round trip: costPct8030 on the same $3-real-SOL curve is fees + slippage + ~0.2 impact, not 105%
        val prior = WalletManager.lastKnownSolPrice
        WalletManager.lastKnownSolPrice = solUsd
        try {
            val onCurve = NetEdge8030.costPct8030("TEST8337", ticketSol, 3.0, curve, 4_500.0)
            val onRealSolOnly = NetEdge8030.costPct8030("TEST8337", ticketSol, 3.0, "NonCurve8337FFFFFFFFFFFFFFFFFFFFFFFFFFFF", 4_500.0)
            assertTrue("curve cost=$onCurve", onCurve.isFinite() && onCurve < 20.0)
            assertTrue("real-sol cost=$onRealSolOnly", onRealSolOnly >= 100.0)
        } finally {
            WalletManager.lastKnownSolPrice = prior
            CurveReserves7951.resetForTest7951()
        }
    }

    @Test fun costPct8030PricesImpactOnTheCurveDepthAndFeesOnTheRealFigure() {
        val n = src("engine/truth/NetEdge8030.kt")
        assertTrue(n.contains("val impactLiq8337 = FieldManual7715.depthUsdForImpact8337(mint, if (depthKnown) liquidityUsd else 0.0, solUsd)"))
        assertTrue(n.contains("FieldManual7715.impactRoundTripPct(if (impactLiq8337 > 0.0) sizeUsd else 0.0, impactLiq8337)"))
        assertTrue(n.contains("FieldManual7715.roundTripCostPct7766(size, if (impactLiq8337 > 0.0) sizeUsd else 0.0, impactLiq8337)"))
        assertTrue(n.contains("lastBreakdown8169 = breakdown8169(lane, size, impactLiq8337, components8151.isFinite(),"))
        // the venue / route / fee components keep the real figure
        assertTrue(n.contains("componentsPct8151(size, mint, if (depthKnown) liquidityUsd else 0.0, mcapUsd)"))
        assertFalse(n.contains("impactRoundTripPct(if (depthKnown) sizeUsd else 0.0, if (depthKnown) liquidityUsd else 0.0)"))
        val fm = src("engine/truth/FieldManual7715.kt")
        assertTrue(fm.contains("fun depthUsdForImpact8337(mint: String, liqUsd: Double, solUsd: Double, nowMs: Long = System.currentTimeMillis()): Double"))
        assertTrue(fm.contains("CurveReserves7951.realSolIfActive8046(mint, nowMs)"))
        assertTrue(fm.contains("2.0 * (30.0 + real.coerceAtLeast(0.0)) * solUsd else liqUsd"))
    }

    @Test fun everyCostCallSitePassesTheMintOrTheCurveDepth() {
        // 1c — the sizer's impact cap reads the curve depth for the candidate mint (Inputs.mint8329)
        val lrp = src("engine/truth/LiveRiskPolicy7807.kt")
        assertTrue(lrp.contains("val impactLiq8337 = FieldManual7715.depthUsdForImpact8337(i.mint8329, i.liquidityUsd, i.solUsd)"))
        assertTrue(lrp.contains("(impactLiq8337 * 0.5 * LIQ_MAX_ONE_WAY_IMPACT_PCT_7807 / 100.0) / i.solUsd else Double.POSITIVE_INFINITY"))
        assertFalse(lrp.contains("(i.liquidityUsd * 0.5 * LIQ_MAX_ONE_WAY_IMPACT_PCT_7807 / 100.0) / i.solUsd"))
        assertTrue(lrp.contains("mint8329 = mint,"))
        // 1d — plan cost, noise floor and the runner sell cost
        val bs = src("engine/BotService.kt")
        assertTrue(bs.contains("ts.position.tradingMode.ifBlank { \"UNKNOWN\" }, ts.position.costSol, com.lifecyclebot.engine.truth.LiquidityTruth8037.hydrate8037(ts), ts.mint, ts.lastMcap))"))
        val ed = src("engine/ExitDoctrine8169.kt")
        assertTrue(ed.contains("com.lifecyclebot.engine.truth.LiquidityTruth8037.hydrate8037(ts), ts.mint, ts.lastMcap)"))
        val rel = src("engine/RunnerExitLive8136.kt")
        assertTrue(rel.contains("com.lifecyclebot.engine.truth.FieldManual7715.depthUsdForImpact8337(ts.mint, com.lifecyclebot.engine.truth.LiquidityTruth8037.hydrate8037(ts), solUsd),"))
        // 1e — the partial doors price the 8037 liquidity for ts.mint, never the raw tick field
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("NetEdge8030.costPct8030(pos.tradingMode, proceeds, com.lifecyclebot.engine.truth.LiquidityTruth8037.hydrate8037(ts), ts.mint, ts.lastMcap) / 2.0"))
        assertTrue(ex.contains("NetEdge8030.exitVariablePct8271(pos.tradingMode, full, com.lifecyclebot.engine.truth.LiquidityTruth8037.hydrate8037(ts), ts.mint, ts.lastMcap)"))
        assertFalse(ex.contains("NetEdge8030.costPct8030(pos.tradingMode, proceeds, ts.lastLiquidityUsd)"))
        assertFalse(ex.contains("NetEdge8030.exitVariablePct8271(pos.tradingMode, full, ts.lastLiquidityUsd, ts.mint)"))
    }

    @Test fun routeFeeFollowsTheRouteAPumpMintTakes() {
        assertTrue(CostTrim8151.routeFeePctPerSide8151("PUMPPORTAL") > 0.0)
        assertEquals(0.0, CostTrim8151.routeFeePctPerSide8151("UNKNOWN_ROUTE"), 0.0)
        val n = src("engine/truth/NetEdge8030.kt")
        assertTrue(n.contains("CostTrim8151.routeFeePctPerSide8151(if (com.lifecyclebot.network.PumpFunDirectApi.isPumpFunMint(mint)) \"PUMPPORTAL\" else \"JUPITER\")"))
        assertFalse(n.contains("routeFeePctPerSide8151(if (curve) \"PUMPPORTAL\" else \"JUPITER\")"))
    }

    @Test fun aRelaxedIntegerFloorRoundsUp() {
        assertEquals(6, kotlin.math.ceil(7 * 0.85).toInt())
        assertEquals(5, (7 * 0.85).toInt())
        assertEquals(7, LiveLayerGateRelaxer.relaxFloor(7, "SHITCOIN", false))
        val r = src("engine/LiveLayerGateRelaxer.kt")
        assertTrue(r.contains("return kotlin.math.ceil(originalFloor * floorMultiplier(traderTag)).toInt()"))
        assertFalse(r.contains("return (originalFloor * floorMultiplier(traderTag)).toInt()"))
    }
}

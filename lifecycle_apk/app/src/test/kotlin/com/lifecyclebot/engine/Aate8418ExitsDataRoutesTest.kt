package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.DiscoveryCapture8400
import com.lifecyclebot.engine.truth.MintSupply8418
import com.lifecyclebot.v3.scoring.MoonshotTraderAI
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8418 — exits, data truth, routing and sizing regressions from the 8365..8417 review (behaviour, not text). */
class Aate8418ExitsDataRoutesTest {

    // ── 1. market cap: no derivation from depth, no stored history as the current cap ──

    @Test fun aCurveWithDepthButNoVerifiedSupplyHasAnUnknownCap() {
        MintSupply8418.resetForTest8418()
        val ts = TokenState(mint = "Cap8418NoSupply1111111111111111111111111pump", symbol = "C").apply { lastLiquidityUsd = 5_000.0 }
        val how = DiscoveryCapture8400.captureMcap8400(ts, "TEST", solUsd = 150.0)
        assertNotEquals("CURVE_FROM_LIQUIDITY", how)
        assertFalse("no cap derived from a depth", ts.lastMcap.isFinite() && ts.lastMcap > 0.0)
    }

    @Test fun aRowWithItsOwnCapKeepsIt() {
        val ts = TokenState(mint = "Cap8418Row11111111111111111111111111111pump", symbol = "R").apply { lastMcap = 42_000.0 }
        assertEquals("ROW", DiscoveryCapture8400.captureMcap8400(ts, "TEST", solUsd = 150.0))
        assertEquals(42_000.0, ts.lastMcap, 0.0)
    }

    // ── 2. MOONSHOT RugCheck: confirmed rug and the danger band refuse ──

    @Test fun moonshotRefusesConfirmedRugAndTheDangerBand() {
        assertEquals("rugcheck_0_confirmed_rug", MoonshotTraderAI.rugcheckRefusal8418(0))
        for (s in 2..14) assertNotNull("safety $s (risk ${100 - s})", MoonshotTraderAI.rugcheckRefusal8418(s))
        assertEquals("rugcheck_2_below_min_15", MoonshotTraderAI.rugcheckRefusal8418(2))
        assertNull("pending", MoonshotTraderAI.rugcheckRefusal8418(1))
        assertNull("unfetched", MoonshotTraderAI.rugcheckRefusal8418(-1))
        for (s in 15..100) assertNull("safety $s", MoonshotTraderAI.rugcheckRefusal8418(s))
    }

    // ── (c) RugCheck raw score: a high raw sum reads risky, never neutral ──

    private fun j(s: String) = JSONObject(s)

    @Test fun aHighRawRugcheckSumReadsRiskyNotNeutral() {
        assertEquals(95, RugcheckScore8399.safety8399(j("""{"score":500}""")))       // good
        val r5000 = RugcheckScore8399.safety8399(j("""{"score":5000}"""))
        assertTrue("raw 5000 (danger) was a neutral 50; now $r5000", r5000 in 2..14)
        assertNotNull(MoonshotTraderAI.rugcheckRefusal8418(r5000))
        assertEquals(2, RugcheckScore8399.safety8399(j("""{"score":20000}""")))
        val r3000 = RugcheckScore8399.safety8399(j("""{"score":3000}"""))
        assertTrue("raw 3000 elevated: $r3000", r3000 in 40..70)
        // monotone: more raw risk never reads safer
        var prev = 101
        for (raw in listOf(0, 500, 999, 1_000, 2_000, 2_500, 4_999, 5_000, 9_000, 50_000)) {
            val s = RugcheckScore8399.safety8399(j("""{"score":$raw}"""))
            assertTrue("raw $raw -> $s after $prev", s <= prev); prev = s
        }
        // a raw sum never reaches the confirmed-rug ceiling on its own
        assertNotEquals(0, RugcheckScore8399.safety8399(j("""{"score":1000000}""")))
        assertEquals(-1, RugcheckScore8399.safety8399(j("""{"score":-5}""")))
    }

    // ── 3. holds: runner lanes keep the trail, every named lane has its own documented hold ──

    @Test fun runnerLanesAreNotForceSoldByAShortClock() {
        val express = HoldingLogicLayer.getHoldParams("EXPRESS")
        assertTrue("EXPRESS max hold ${express.maxHoldTimeMs}", express.maxHoldTimeMs >= 4L * 3_600_000L)
        assertTrue("EXPRESS trail ${express.trailingStopPct}", express.trailingStopPct >= 35.0)
        assertTrue(HoldingLogicLayer.getHoldParams("MANIPULATED").maxHoldTimeMs >= 4L * 3_600_000L)
        // a green runner is owned by the trail, never by a clock; a red or flat one still has its clock
        assertFalse(LaneHolds8418.clockBinds8418("EXPRESS", "EXPRESS", 12.0))
        assertFalse(LaneHolds8418.clockBinds8418("PUMP_SNIPER", "MOONSHOT", 0.5))
        assertTrue(LaneHolds8418.clockBinds8418("EXPRESS", "EXPRESS", -3.0))
        assertTrue(LaneHolds8418.clockBinds8418("EXPRESS", "EXPRESS", Double.NaN))
        assertTrue("non-runner desk keeps its clock", LaneHolds8418.clockBinds8418("TREASURY", "TREASURY", 4.0))
        assertEquals(35.0, LaneHolds8418.trailFor8418("MOONSHOT", "MOONSHOT", 15.0), 0.0)
        assertEquals(3.0, LaneHolds8418.trailFor8418("MARKET_MAKER", "TREASURY", 3.0), 0.0)
    }

    @Test fun everyNamedLaneHasItsOwnDocumentedHold() {
        for (lane in listOf("EXPRESS", "MANIPULATED", "TREASURY", "CASHGEN", "SHITCOIN", "PROJECT_SNIPER", "DIP_HUNTER", "QUALITY", "BLUECHIP", "CORE")) {
            val h = LaneHolds8418.holdFor8418(lane)
            assertNotNull(lane, h)
            assertTrue(lane, h!!.why.isNotBlank())
            val p = HoldingLogicLayer.getHoldParams(lane)
            assertEquals(lane, p.mode); assertEquals(lane, h.maxHoldMs, p.maxHoldTimeMs.coerceAtMost(h.maxHoldMs))
        }
        // TREASURY / CASHGEN hold the 60-minute graded window, not MARKET_MAKER's 30 minutes
        assertEquals(60L * 60_000L, HoldingLogicLayer.getHoldParams("TREASURY").maxHoldTimeMs)
        assertEquals(60L * 60_000L, HoldingLogicLayer.getHoldParams("CASHGEN").maxHoldTimeMs)
        assertNull(LaneHolds8418.holdFor8418("UNKNOWN_DESK"))
        assertEquals(4L * 3_600_000L, HoldingLogicLayer.getHoldParams("UNKNOWN_DESK").maxHoldTimeMs)
    }

    @Test fun anEnsembleOwnerCannotReLaneARunnerOutOfRunnerManagement() {
        assertFalse(HeldPositionPivotArbiter.switchAllowed8384("CORE", "MOONSHOT", "MARKET_MAKER", 12.0, 14.0, true))
        assertFalse(HeldPositionPivotArbiter.switchAllowed8384("TREASURY", "EXPRESS", "STANDARD", 12.0, 14.0, true))
        assertFalse(HeldPositionPivotArbiter.switchAllowed8384("MOONSHOT", "MOONSHOT", "MARKET_MAKER", 12.0, 14.0, true))
        // runner -> runner technique, and non-runner tightening, still allowed
        assertTrue(HeldPositionPivotArbiter.switchAllowed8384("CORE", "MOONSHOT", "PRESALE_SNIPE", 12.0, 14.0, true))
        assertTrue(HeldPositionPivotArbiter.switchAllowed8384("CASHGEN", "STANDARD", "MARKET_MAKER", -2.0, 1.0, false))
    }

    // ── (e) ensemble styles: no runner-only style on a non-runner desk ──

    @Test fun nonRunnerDesksNeverRunARunnerOnlyStyle() {
        for (desk in listOf("TREASURY", "CASHGEN", "CYCLIC")) {
            assertFalse(desk, AgenticStyleRouter.toolkitStyleFitsLane8080(setOf("MOONSHOT"), desk))
            assertFalse(desk, AgenticStyleRouter.toolkitStyleFitsLane8080(setOf("MOONSHOT", "SHITCOIN"), desk))
            assertTrue(desk, AgenticStyleRouter.toolkitStyleFitsLane8080(setOf("QUALITY"), desk))
            assertTrue(desk, AgenticStyleRouter.toolkitStyleFitsLane8080(setOf("MOONSHOT", "TREASURY"), desk))
        }
        assertTrue(AgenticStyleRouter.toolkitStyleFitsLane8080(setOf("MOONSHOT"), "CORE"))
        assertFalse(AgenticStyleRouter.toolkitStyleFitsLane8080(setOf("CASHGEN"), "MOONSHOT"))
    }

    // ── 4 / (f). launch window: the loss hold is off; floor exits always release by code and by state ──

    @Test fun theLaunchWindowLossHoldIsOffUntilTheOwnerConfirmsIt() {
        assertFalse(LaunchWindow8379.lossHoldEnabled8418)
        assertTrue(LaunchWindow8379.statusLine8379().contains("lossHold=OFF_UNCONFIRMED_8418"))
        assertTrue(LaunchWindow8379.statusLine8379().contains("wouldHold="))
    }

    @Test fun floorAndRugExitsAreReleasedByCodeAtAnyPnl() {
        for (r in listOf("THIN_LIQ_EARLY_RUG_BACKSTOP_-10", "LIQUIDITY_BELOW_EXIT_SAFE_FLOOR", "VENUE_NOT_EXIT_SAFE",
                "CURVE_DEPTH_UNVERIFIED", "LIQUIDITY_DRAIN_EXIT_8153_40PCT", "NO_LIQUIDITY_EXIT", "LIQ_HALVED_MARK_INVALIDATED_6310",
                "UNSELLABLE", "LP_PULL")) {
            assertTrue(r, LaunchWindow8379.floorExitCode8418(r))
            assertEquals(r, "FLOOR_EXIT_CODE", LaunchWindow8379.releaseCause8379(r, -12.0, false))
            assertEquals(r, "FLOOR_EXIT_CODE", LaunchWindow8379.releaseCause8379(r, Double.NaN, false))
        }
        assertFalse(LaunchWindow8379.floorExitCode8418("RAPID_FLUID_STOP"))
        assertFalse("a code is the leading code, not any substring", LaunchWindow8379.floorExitCode8418("STRICT_SL_-8_NOT_RUG"))
        // the pool's own state releases it, whatever the reason text says
        assertEquals("POOL_FLOOR", LaunchWindow8379.releaseCause8379("RAPID_FLUID_STOP", -12.0, false, poolFloorBreached = true))
        assertNull(LaunchWindow8379.releaseCause8379("RAPID_FLUID_STOP", -12.0, false, poolFloorBreached = false))
    }

    @Test fun thePoolFloorsAreTheOwnersFiveHundredAndCurveRules() {
        assertTrue(LaunchWindow8379.floorBreached8418(400.0, activeCurve = false, curveExitSafe = false))
        assertFalse(LaunchWindow8379.floorBreached8418(600.0, activeCurve = false, curveExitSafe = false))
        assertFalse("unknown depth is not a breach", LaunchWindow8379.floorBreached8418(Double.NaN, activeCurve = false, curveExitSafe = false))
        assertFalse("unknown depth is not a breach", LaunchWindow8379.floorBreached8418(0.0, activeCurve = false, curveExitSafe = false))
        assertTrue("8320: a curve no longer exit-safe", LaunchWindow8379.floorBreached8418(4_000.0, activeCurve = true, curveExitSafe = false))
        assertFalse("8320: an exit-safe curve under $500 is not breached", LaunchWindow8379.floorBreached8418(300.0, activeCurve = true, curveExitSafe = true))
    }

    // ── 5. routes and costs ──

    @Test fun aMeasuredJupiterStallRoutesAwayOnlyToALandingPumpPortal() {
        val k = Costs8369
        assertFalse("Jupiter failing, PumpPortal landing", k.jupiterFirstForBuy8369("PUMP_CURVE", true, false, jupiterLandFailing = true, pumpLandsOk = true))
        assertTrue("Jupiter failing, PumpPortal unmeasured / failing", k.jupiterFirstForBuy8369("PUMP_CURVE", true, false, jupiterLandFailing = true, pumpLandsOk = false))
        assertTrue("Jupiter landing", k.jupiterFirstForBuy8369("PUMP_CURVE", true, false, jupiterLandFailing = false, pumpLandsOk = true))
        assertFalse("no route", k.jupiterFirstForBuy8369("PUMP_CURVE", false, false, jupiterLandFailing = false, pumpLandsOk = false))
        assertTrue("graduated pool", k.jupiterFirstForBuy8369("PUMPSWAP", null, false, jupiterLandFailing = true, pumpLandsOk = true))
    }

    @Test fun anUnroutedCurveMintIsPricedAtPumpPortal() {
        val k = Costs8369
        assertEquals("PUMPPORTAL", k.plannedBuyRoute8369(true, "PUMP_CURVE", "JUPITER", null, jupiterQuotes = false))
        assertEquals("PUMPPORTAL", k.plannedBuyRoute8369(true, "UNKNOWN", null, null, jupiterQuotes = false))
        assertEquals("JUPITER", k.plannedBuyRoute8369(true, "PUMP_CURVE", null, null, jupiterQuotes = true))
        assertEquals("JUPITER", k.plannedBuyRoute8369(true, "PUMP_CURVE", null, null, jupiterQuotes = null))
        assertEquals("JUPITER", k.plannedBuyRoute8369(false, "UNKNOWN", null, null, jupiterQuotes = false))
        assertEquals("JUPITER", k.plannedBuyRoute8369(true, "PUMPSWAP", null, null, jupiterQuotes = false))
    }

    @Test fun theSellLegIsPricedOnTheRouteExitsActuallyTake() {
        val k = Costs8369
        assertEquals(0.5, k.sellLegFee8418(0.0, 0.5, pumpShare = null, jupiterAdmitted = true, jupiterQuotes = null), 1e-12)
        assertEquals(0.5, k.sellLegFee8418(0.0, 0.5, pumpShare = 0.0, jupiterAdmitted = false, jupiterQuotes = true), 1e-12)
        assertEquals(0.5, k.sellLegFee8418(0.0, 0.5, pumpShare = 0.0, jupiterAdmitted = true, jupiterQuotes = false), 1e-12)
        assertEquals(0.2, k.sellLegFee8418(0.0, 0.5, pumpShare = 0.4, jupiterAdmitted = true, jupiterQuotes = true), 1e-12)
        assertEquals(0.0, k.sellLegFee8418(0.0, 0.5, pumpShare = 0.0, jupiterAdmitted = true, jupiterQuotes = true), 1e-12)
    }

    @Test fun protectiveSellsIntoAFallingPricePayTheLandingTip() {
        val c = CostTrim8151
        for (r in listOf("RUNNER_GREEN_FLOOR_8363_120PK_40PCT", "LIFECYCLE_GREEN_FLOOR_8363_80PK_30PCT", "PROFIT_LOCK_GAPPED",
                "TRAILING_STOP", "PEAK_GIVEBACK_LOCK", "RUNNER_STRUCTURE_BREAK_7967_1M_x", "RUNNER_PEAK_CAPTURE_7980_x", "BREAKEVEN_LOCK")) {
            assertTrue(r, c.sellProtective8378(r, urgent = false, previousSellUnlanded = false))
        }
        assertFalse(c.fallingPriceExit8418("SPIKE_CAPTURE_T1")); assertFalse(c.fallingPriceExit8418("PARTIAL_TAKE_PROFIT_50"))
        for (r in listOf("TAKE_PROFIT", "DUST_BUSTER", "ORPHAN_SELL")) {
            assertFalse(r, c.sellProtective8378(r, urgent = false, previousSellUnlanded = false))
        }
    }
}

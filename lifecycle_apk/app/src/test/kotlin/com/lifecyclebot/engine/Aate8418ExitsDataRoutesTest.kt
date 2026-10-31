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
        // superseded by V5.0.8471 — owner: RugCheck's number is low = bad token, high = good; a raw-only `score` has no stated
        // range under that reading and is UNKNOWN (-1), never neutral and never a confirmed rug (was 500 -> 95, 5000 -> 2..14
        // refused by MOONSHOT, 20000 -> 2, 3000 -> 40..70). The report's findings and our chain proofs decide such a coin.
        assertEquals(-1, RugcheckScore8399.safety8399(j("""{"score":500}""")))
        assertEquals(-1, RugcheckScore8399.safety8399(j("""{"score":5000}""")))
        assertEquals(-1, RugcheckScore8399.safety8399(j("""{"score":20000}""")))
        assertEquals(-1, RugcheckScore8399.safety8399(j("""{"score":3000}""")))
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
        // superseded by V5.0.8468 (strategy audit fix 1) — a fast contract lane runs its own specialist clock and trail until the
        // position is promoted (+50 % verified peak); the runner trail and the green-runner clock exemption take over then
        // (was: EXPRESS / MANIPULATED >= 4 h and EXPRESS trail >= 35 from entry; clockBinds8418("EXPRESS", "EXPRESS", 12.0) false).
        val express = HoldingLogicLayer.getHoldParams("EXPRESS")
        assertEquals(30L * 60_000L, express.maxHoldTimeMs)
        assertEquals(10.0, express.trailingStopPct, 0.0)
        assertEquals(15L * 60_000L, HoldingLogicLayer.getHoldParams("MANIPULATED").maxHoldTimeMs)
        // a green PROMOTED runner is owned by the trail, never by a clock; an unpromoted one, or a red / flat one, has its clock
        assertTrue(LaneHolds8418.clockBinds8418("EXPRESS", "EXPRESS", 12.0, 20.0))
        assertFalse(LaneHolds8418.clockBinds8418("EXPRESS", "EXPRESS", 12.0, 60.0))
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
        // superseded by V5.0.8468 (strategy audit fix 2) — TREASURY / CASHGEN run CashGenerationAI's own 45-minute clock and are
        // graded on it (was the 60-minute graded window: assertEquals(60L * 60_000L, ...)).
        assertEquals(45L * 60_000L, HoldingLogicLayer.getHoldParams("TREASURY").maxHoldTimeMs)
        assertEquals(45L * 60_000L, HoldingLogicLayer.getHoldParams("CASHGEN").maxHoldTimeMs)
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
        assertTrue("Aggregate failures never prove this mint has no Jupiter route", k.jupiterFirstForBuy8369("PUMP_CURVE", true, false, jupiterLandFailing = true, pumpLandsOk = true))
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

    // ── 7. curve reserves: an ACCOUNT read wins a same-ms tie against a frame ──

    @Test fun anAccountReadWinsATieAgainstAFrame() {
        val c = com.lifecyclebot.engine.truth.CurveReserves7951
        assertTrue(c.accepts8418(1_001L, 1_000L, newAccount = false, storedAccount = true))
        assertTrue("ACCOUNT over FRAME at the same ms", c.accepts8418(1_000L, 1_000L, newAccount = true, storedAccount = false))
        assertFalse("FRAME never replaces at the same ms", c.accepts8418(1_000L, 1_000L, newAccount = false, storedAccount = false))
        assertFalse(c.accepts8418(1_000L, 1_000L, newAccount = false, storedAccount = true))
        assertFalse(c.accepts8418(1_000L, 1_000L, newAccount = true, storedAccount = true))
        assertFalse(c.accepts8418(999L, 1_000L, newAccount = true, storedAccount = false))
        c.resetForTest7951()
        val m = "Tie8418Mint11111111111111111111111111111pump"
        c.noteFrame7951(m, 34.0, 9.4e8, 5_000L)
        assertEquals("FRAME", c.acceptedObservation8385(m)!!.source)
        c.noteAccount7951(m, 34.0, 9.4e8, 4.0, 5_000L)
        assertEquals("ACCOUNT", c.acceptedObservation8385(m)!!.source)
        c.noteFrame7951(m, 35.0, 9.3e8, 5_000L)
        assertEquals("a later frame at the same ms does not erase the account read", "ACCOUNT", c.acceptedObservation8385(m)!!.source)
    }

    // ── 8. reconciliation: a colliding pass is coalesced into one follow-up, never dropped ──

    @Test fun aCollidingReconciliationIsCoalescedIntoOneFollowUp() {
        val pub = com.lifecyclebot.engine.truth.ReconciliationPublication8372()
        val lease = pub.claim("k1")!!
        assertNull("second caller loses the lease", pub.claim("k2"))
        assertTrue(pub.isBusy())
        pub.notePending8418()
        pub.notePending8418()
        assertTrue(pub.finish(lease, "k1", coherent = true))
        assertFalse(pub.isBusy())
        assertTrue("one follow-up is owed", pub.takePending8418())
        assertFalse("exactly one", pub.takePending8418())
        val next = pub.claim("k2")
        assertNotNull("the follow-up can claim the newer key", next)
    }

    // ── 9. a pending bot buy gets a protective owner at once, on its own row ──

    @Test fun aPendingBotBuyGetsItsProtectiveOwnerImmediately() {
        val cpa = com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
        val m = "PEND8418Mint${System.nanoTime()}"
        val pid = "LIVE:$m:p8418"
        assertEquals(com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.MutateResult.APPLIED, cpa.openPosition(
            idempotencyKey = "OPEN8418:$pid", positionId = pid, mint = m, symbol = "P", lane = "SHITCOIN", runId = "r8418",
            entryCostSol = 0.03, openedQtyRaw = java.math.BigInteger.ZERO, tokenDecimals = 6, feesSol = 0.0,
            paperMode = false, modeOverride = "live", entryPriceUsd = 0.001, entryPriceSource = "TEST_8418"))
        assertEquals(pid, cpa.pendingEntryLivePositions8418()[m])
        assertFalse(m in cpa.protectiveInventoryMints7807("live"))
        val n = LiveCanonicalRecovery6686.protectUnadoptedBotHoldings7807(
            mapOf(m to com.lifecyclebot.engine.truth.CanonicalTokenAmount(java.math.BigInteger.valueOf(9_000_000L), 6)))
        assertEquals(1, n)
        assertTrue("protected immediately", m in cpa.protectiveInventoryMints7807("live"))
        val row = cpa.getPosition(pid)!!
        assertEquals("on the buy's own row", com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.Lifecycle.QUARANTINED, row.lifecycle)
        assertTrue(row.quarantineReason.startsWith("BASIS_UNCERTAIN_7807"))
        assertEquals("keeps its own buy cost", 0.03, row.entryCostSol, 1e-12)
        // the verified fill still promotes it
        assertEquals(com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.MutateResult.APPLIED, cpa.promotePendingToOpen(positionId = pid, actualQtyRaw = java.math.BigInteger.valueOf(9_000_000L),
            actualEntryCostSol = 0.03, actualFeesSol = 0.0, tokenDecimals = 6, paperMode = false,
            actualEntryPriceUsd = 0.001, actualEntryPriceSource = "TX_8418"))
        assertEquals(com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.Lifecycle.OPEN, cpa.getPosition(pid)!!.lifecycle)
    }

    // ── 10. an erroring shadow position closes after N consecutive failures ──

    @Test fun aShadowPositionThatAlwaysThrowsClosesAsAnError() {
        val e = com.lifecyclebot.engine.truth.EvidenceShadow8344
        val k = "ERR8418|${System.nanoTime()}"
        for (i in 1 until e.MAX_TICK_FAILURES_8418) assertFalse("failure $i", e.noteTickFailure8418(k))
        assertTrue("closes at ${e.MAX_TICK_FAILURES_8418}", e.noteTickFailure8418(k))
        val k2 = "ERR8418b|${System.nanoTime()}"
        assertFalse(e.noteTickFailure8418(k2)); assertFalse(e.noteTickFailure8418(k2))
        e.noteTickOk8418(k2)
        assertFalse("a good tick restarts the streak", e.noteTickFailure8418(k2))
    }

    // ── 6. off-watch marks: on-curve mints are curve-read before DexScreener ──

    @Test fun onCurveMintsGoToTheCurveReadFirst() {
        val fr = com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
        val (on, off) = fr.splitOnCurve8418(listOf("a", "bpump", "c", "dpump")) { it.endsWith("pump") }
        assertEquals(listOf("bpump", "dpump"), on)
        assertEquals(listOf("a", "c"), off)
        val (on2, off2) = fr.splitOnCurve8418(listOf("x", "y")) { m -> if (m == "x") throw IllegalStateException() else true }
        assertEquals("a throwing probe is not on a curve", listOf("y"), on2); assertEquals(listOf("x"), off2)
    }

    // ── 11. stops: non-runner desks cap at 25 % unless their own evidence chose wider; runners keep 40 % ──

    @Test fun nonRunnerStopsCapAtTwentyFivePercent() {
        val fm = com.lifecyclebot.engine.truth.FieldManual7715
        assertEquals(25.0, fm.stopPct8392("TREASURY", 30.0), 1e-9)
        assertEquals(25.0, fm.stopPct8392("QUALITY", 60.0), 1e-9)
        assertEquals(7.5, fm.stopPct8392("TREASURY", 5.0), 1e-9)          // 1.5 x a 5 % trip
        assertEquals(40.0, fm.stopPct8392("MOONSHOT", 60.0), 1e-9)       // runner mandate keeps the 40 % cap
        assertEquals(25.0, fm.nonRunnerCap8418(null), 0.0)
        assertEquals(25.0, fm.nonRunnerCap8418(18.0), 0.0)
        assertEquals(32.0, fm.nonRunnerCap8418(32.0), 0.0)               // the lane's own learned stop
        assertEquals(40.0, fm.nonRunnerCap8418(70.0), 0.0)
        assertEquals(25.0, fm.nonRunnerCap8418(Double.NaN), 0.0)
    }

    @Test fun theRestoredBuyFeeFeedsTheFeeEaterGuardAgain() {
        repeat(50) { BuyAppFee8418.noteLiveFee8418(1.0) }
        BuyAppFee8418.noteLiveFee8418(0.0); BuyAppFee8418.noteLiveFee8418(Double.NaN)
        val (skip, why) = LiveLaneGovernor.feeEaterGuard(0.5)
        assertTrue(why, skip)
        assertTrue(why.startsWith("SIZE_BELOW_FEE_ECONOMIC_FLOOR_6247"))
    }

    // ── 12. TREASURY / CASHGEN established-token floor ──

    @Test fun treasuryFloorIsOneMillionAgain() {
        assertEquals(1_000_000.0, TreasuryScannerFeed.MIN_TREASURY_MCAP, 0.0)
        assertFalse(TreasuryScannerFeed.publishCandidate(TreasuryScannerFeed.TreasuryCandidate(
            "Trs8418Low".padEnd(44, 'A'), "LOW", 500_000.0, 80_000.0, 200_000.0, "TEST")))
    }

    // ── (a) sweep merge age: chain birth first, never inflated by one provider ──

    @Test fun theMergedAgeIsNeverInflatedByTheMerge() {
        val ms = com.lifecyclebot.engine.market.MarketSweep7297
        assertEquals("chain-proven birth wins", 2.0, ms.mergedAge8418(0.5, listOf(0.5, 40.0), 2.0), 0.0)
        assertEquals("one provider's far older pool age is not taken", 0.5, ms.mergedAge8418(0.5, listOf(0.5, 40.0), null), 0.0)
        assertEquals("a corroborating older age (poll lag) is", 0.6, ms.mergedAge8418(0.5, listOf(0.5, 0.6), null), 1e-12)
        assertEquals("a younger age never rejuvenates", 4.0, ms.mergedAge8418(4.0, listOf(0.01, 4.0), null), 0.0)
        assertEquals("no anchor: the youngest witness", 3.0, ms.mergedAge8418(0.0, listOf(3.0, 9.0), null), 0.0)
        assertEquals("unknown stays unknown", 0.0, ms.mergedAge8418(0.0, emptyList(), null), 0.0)
    }

    // ── (b) Helius candle jobs follow the keyless-first gate ──

    @Test fun bulkCoinsGetNoHeliusCandleJob() {
        val h = com.lifecyclebot.engine.truth.HeliusSwapCandles7819
        assertFalse(h.budgetAdmits8284(0, 0, 0))
        assertTrue(h.budgetAdmits8284(h.PRIORITY_HELD_8284, 0, 0))
        assertTrue(h.budgetAdmits8284(h.PRIORITY_HANDOFF_8284, 0, 0))
        val g = com.lifecyclebot.engine.truth.HeliusCandleGate8418
        assertEquals(com.lifecyclebot.engine.truth.HeliusCandleGate8418.Decision8418.FREE_GRACE, g.decide8418(h.PRIORITY_NEAR_DECISION_8284, false, 1_000L, 1_000L + g.FREE_GRACE_MS_8418 - 1))
        assertEquals(com.lifecyclebot.engine.truth.HeliusCandleGate8418.Decision8418.COVERED_BY_FREE, g.decide8418(h.PRIORITY_HELD_8284, true, 0L, 0L))
    }

    // ── ea9f1f0ac — DNA readers: paper never sizes live; a blank lane never disables the veto ──

    @Test fun dnaReadersKeepModesApartAndVetoEveryLaneOnABlankTag() {
        val d = LiveWinDNAStore
        fun row(mode: String) = LiveWinDNAStore.WinDNA("m", "S", "MOONSHOT", "src", "p", "SETUP", "UNKNOWN", 50, 1.0, 1.0, 1.0,
            5, 50.0, 30.0, 40.0, "X", mode, 1L, "pid", "eid")
        assertTrue(d.modeOk8418(row("LIVE"), "LIVE"))
        assertFalse("a paper settlement never sizes a live trade", d.modeOk8418(row("PAPER"), "LIVE"))
        assertTrue(d.modeOk8418(row("PAPER"), null))
        assertEquals("LIVE", d.mode8418(false)); assertEquals("PAPER", d.mode8418(true))
        assertNull("blank tag: the veto reads every lane", d.vetoLane8418(""))
        assertEquals("MOONSHOT", d.vetoLane8418("MOONSHOT"))
        assertNull(d.vetoLane8418("NETWORK_SIGNAL"))
    }

    // ── 7ce191799 — a quiet decay alone never makes an execution quorum member healthy ──

    @Test fun quietDecayIsNotEvidenceForTheLiveQuorum() {
        val a = ApiHealthMonitor
        assertTrue(a.quorumHealthyOf8418(true, 0.6, lastSuccessMs = 0L, lastQuietDecayMs = 0L))
        assertFalse("decayed, no success since", a.quorumHealthyOf8418(true, 0.6, lastSuccessMs = 100L, lastQuietDecayMs = 200L))
        assertTrue("a real success after the decay", a.quorumHealthyOf8418(true, 0.6, lastSuccessMs = 300L, lastQuietDecayMs = 200L))
        assertFalse(a.quorumHealthyOf8418(false, 1.0, 0L, 0L))
        assertFalse(a.quorumHealthyOf8418(true, 0.3, 300L, 0L))
    }

    // ── df436b883 — the GeckoTerminal budget wait never blocks the main thread ──

    @Test fun geckoBudgetNeverSleepsOnTheMainThread() {
        val g = com.lifecyclebot.network.GeckoHostBudget8382
        assertTrue(g.waitMayBlock8418(0L, onMainThread = true))
        assertTrue(g.waitMayBlock8418(800L, onMainThread = false))
        assertFalse(g.waitMayBlock8418(800L, onMainThread = true))
        assertFalse(g.waitMayBlock8418(-1L, onMainThread = false))
    }

    // ── 89209931c — a held (pinned) tape is never released ──

    @Test fun pinnedHeldTapesAreNeverReleased() {
        val drop = com.lifecyclebot.network.releasableSubs7948(listOf("held", "a", "b"), setOf("held")) { it == "a" }
        assertEquals(listOf("b"), drop)
    }
}

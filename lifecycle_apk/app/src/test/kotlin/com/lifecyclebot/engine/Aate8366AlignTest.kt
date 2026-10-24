package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.FieldManual7715
import com.lifecyclebot.engine.truth.ScalpExit8351
import com.lifecyclebot.engine.truth.ShadowAlign8366
import com.lifecyclebot.engine.truth.EvidenceShadow8344
import com.lifecyclebot.engine.truth.ShadowPersist8355
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8366 — the shadow books are a faithful model of live: live's own exit rules (fluid-stop level, green floor, 8363
 * scalp stop and warmup, lifecycle hold), the route's measured landing rate as a deterministic fill probability, exits priced
 * after the live sell latency, the lane's measured cost once measured; paper never sizes live; live vs shadow alignment.
 */
class Aate8366AlignTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    // ── exits: the shadow reads live's pure functions, so the same path gives the same decision ──

    @Test fun runnerStopLevelIsLiveFluidStopPlusTheGreenFloor() {
        val cost = 7.0
        // a +15 % peak on a runner lane: live floors at net break-even (7.5) through the green floor; the old shadow had no floor
        assertNull("pre-8366 shadow: no floor under the lane's give-back arm", EvidenceShadow8344.giveBackFloor8344(15.0, 20.0, cost))
        val live = maxOf(FieldManual7715.runnerAwareStop7935(-15.0, 15.0, cost, true) { -15.0 }, GreenFloor8363.floor8363(15.0, cost))
        assertEquals(live, ShadowAlign8366.liveStopLevel8366(true, 15.0, 15.0, cost), 1e-9)
        assertEquals(7.5, ShadowAlign8366.liveStopLevel8366(true, 15.0, 15.0, cost), 1e-9)
        // under the arm nothing is floored: the lane stop
        assertEquals(-15.0, ShadowAlign8366.liveStopLevel8366(true, 15.0, 10.0, cost), 1e-9)
        // +80 % peak: the green floor keeps half the peak
        assertEquals(40.0, ShadowAlign8366.liveStopLevel8366(true, 15.0, 80.0, cost), 1e-9)
        // another lane: the cost-armed stop locks net break-even once the peak cleared it by 1.5
        assertEquals(FieldManual7715.costArmedStop7928(-15.0, 9.5, cost) { -15.0 }, ShadowAlign8366.liveStopLevel8366(false, 15.0, 9.5, cost), 1e-9)
        assertEquals(7.5, ShadowAlign8366.liveStopLevel8366(false, 15.0, 9.5, cost), 1e-9)
        assertEquals(-15.0, ShadowAlign8366.liveStopLevel8366(false, 15.0, 8.0, cost), 1e-9)
    }

    @Test fun replayedRunnerPathExitsWhereLiveDoes() {
        // one path, ticked: +12, +18 (peak), +10, +7 — live's green floor (max(net break-even 7.5, half the +18 peak) = 9) exits at
        // +7; the 8344 rule (no floor under the lane's +20 arm) rode it to the stop
        val cost = 7.0; val stop = 15.0; val arm = 20.0
        var peak = 0.0
        var shadowExitAt = -1; var liveExitAt = -1; var oldExitAt = -1
        val path = listOf(12.0, 18.0, 10.0, 7.0, -16.0)
        for ((i, g) in path.withIndex()) {
            peak = maxOf(peak, g)
            val level = ShadowAlign8366.liveStopLevel8366(true, stop, peak, cost)
            if (shadowExitAt < 0 && (g <= -stop || (level > -stop && g <= level))) shadowExitAt = i
            val liveLevel = maxOf(FieldManual7715.runnerAwareStop7935(-stop, peak, cost, true) { -stop }, GreenFloor8363.floor8363(peak, cost).takeIf { it.isFinite() } ?: -stop)
            if (liveExitAt < 0 && g <= liveLevel) liveExitAt = i
            val oldFloor = EvidenceShadow8344.giveBackFloor8344(peak, arm, cost)
            if (oldExitAt < 0 && (g <= -stop || (oldFloor != null && g <= oldFloor))) oldExitAt = i
        }
        assertEquals(3, liveExitAt)
        assertEquals(liveExitAt, shadowExitAt)
        assertEquals("the pre-8366 shadow rode it to the stop", 4, oldExitAt)
    }

    @Test fun scalpStopHasLiveWarmupAndNoiseFloor() {
        // -9 % at 10 s: the pre-8366 shadow (decide8351, 0.6 x 15 %) stopped; live's 8363 stop is not armed in the first 60 s
        assertNotNull(ScalpExit8351.decide8351(-9.0, 0.0, 10_000L, 0, 15.0, 7.0))
        assertNull(ScalpStop8363.decide8363(-9.0, 0.0, 10_000L, 0, 15.0, 7.0, 2.0, 2.9))
        // after the warmup the stop is max(0.6 x 15, 2 x 4.1 + 2.9, 6) = 11.1: -9 holds, -11.2 stops
        assertNull(ScalpStop8363.decide8363(-9.0, 0.0, 70_000L, 0, 15.0, 7.0, 4.1, 2.9))
        assertEquals("SCALP_HARD_STOP_8351", ScalpStop8363.decide8363(-11.2, 0.0, 70_000L, 0, 15.0, 7.0, 4.1, 2.9)?.second)
        // the ladder and the clock are decide8351's
        assertEquals("SCALP_TAKE_PROFIT_8351_T1", ScalpStop8363.decide8363(16.0, 16.0, 20_000L, 0, 15.0, 7.0, 4.1, 2.9)?.second)
        assertEquals("SCALP_TIME_8351", ScalpStop8363.decide8363(1.0, 2.0, ScalpExit8351.CLOCK_MS_8351, 0, 15.0, 7.0, 4.1, 2.9)?.second)
    }

    @Test fun lifecycleHoldMirrorsLiveOrderAndConstants() {
        assertTrue(ShadowAlign8366.holdPromotes8366(100.0, 100.0))
        assertFalse(ShadowAlign8366.holdPromotes8366(99.0, 120.0))
        assertFalse(ShadowAlign8366.holdPromotes8366(120.0, 99.0))
        assertEquals("HOLD_FLOOR_8366", ShadowAlign8366.holdExit8366(-41.0, 120.0, 0, 60_000L, 1_000L, 7.0)?.first)
        val take = ShadowAlign8366.holdExit8366(110.0, 110.0, 0, 60_000L, 0L, 7.0)
        assertEquals("HOLD_TAKE1_8366", take?.first)
        assertEquals(LifecycleHold8065.cumulativeTakeDue8079(110.0, 0)!!.second, take!!.second, 1e-9)
        assertEquals(1, ShadowAlign8366.takeOf8366(take.first))
        assertEquals(-1, ShadowAlign8366.takeOf8366("HOLD_FLOOR_8366"))
        // a +150 % peak back at +60 %: the green floor (75 %) fires, as live's onMark8065 does
        assertTrue(GreenFloor8363.breached8363(60.0, 150.0, 7.0))
        assertEquals("HOLD_GREEN_FLOOR_8366", ShadowAlign8366.holdExit8366(60.0, 150.0, 1, 60_000L, 1_000L, 7.0)?.first)
        // a +500 % peak at +150 %: the profitable reversal (45 % of the peak multiple) comes before the floor
        assertTrue(LifecycleHold8065.profitablePeakReversal8079(500.0, 150.0, true))
        assertEquals("HOLD_PEAK_GIVEBACK_8366", ShadowAlign8366.holdExit8366(150.0, 500.0, 1, 60_000L, 1_000L, 7.0)?.first)
        // dead money: 72 h, flat, no high for 48 h
        assertEquals("HOLD_DEAD_MONEY_8366", ShadowAlign8366.holdExit8366(5.0, 10.0, 1, 73L * 3_600_000L, 49L * 3_600_000L, 7.0)?.first)
        assertNull(ShadowAlign8366.holdExit8366(130.0, 130.0, 1, 60_000L, 0L, 7.0))
        assertEquals(LifecycleHold8065.PROMOTE_PCT, 100.0, 0.0)
    }

    // ── entry landing ──

    @Test fun landingProbabilityIsTheMeasuredRouteRateDrawnDeterministically() {
        assertEquals(ShadowAlign8366.ROUTE_PUMPPORTAL_8366, ShadowAlign8366.buyRoute8366(pumpMint = true, pumpRoutedAway = false))
        assertEquals(ShadowAlign8366.ROUTE_JUPITER_8366, ShadowAlign8366.buyRoute8366(pumpMint = true, pumpRoutedAway = true))
        assertEquals(ShadowAlign8366.ROUTE_JUPITER_8366, ShadowAlign8366.buyRoute8366(pumpMint = false, pumpRoutedAway = false))
        // 5.0.8341: BUY|PUMPPORTAL landed 3 / expired 9 / failed 5; BUY|JUPITER 18 / 18
        assertEquals(3.0 / 17.0, ShadowAlign8366.landRate8366(3, 9, 5), 1e-9)
        assertEquals(1.0, ShadowAlign8366.landRate8366(18, 0, 0), 1e-9)
        assertEquals("unmeasured (under 3 resolved) is never guessed", 1.0, ShadowAlign8366.landRate8366(0, 1, 1), 1e-9)
        assertEquals(5.0 / 14.0, ShadowAlign8366.failedShare8366(9, 5), 1e-9)
        assertEquals(0.0, ShadowAlign8366.failedShare8366(0, 0), 1e-9)
        // deterministic and in [0, 1)
        val k = "mintA|SHITCOIN|NONE|1700000000000|NOW"
        assertEquals(ShadowAlign8366.draw8366(k), ShadowAlign8366.draw8366(k), 0.0)
        var landed = 0
        val n = 4_000
        for (i in 0 until n) {
            val d = ShadowAlign8366.draw8366("mint$i|SHITCOIN|NONE|${1_700_000_000_000L + i}|NOW")
            assertTrue(d >= 0.0 && d < 1.0)
            if (ShadowAlign8366.lands8366("mint$i|SHITCOIN|NONE|${1_700_000_000_000L + i}|NOW", 3.0 / 17.0)) landed++
        }
        val rate = landed.toDouble() / n
        assertTrue("applied at the measured rate: $rate", rate > 0.14 && rate < 0.21)
        assertTrue(ShadowAlign8366.lands8366(k, 1.0))
        assertFalse(ShadowAlign8366.lands8366(k, 0.0))
        // an unlanded entry books only the failed-tx fee: 0.0003 SOL x 5/14 failed of a 0.03 ticket = 0.357 %
        assertEquals(0.3571, ShadowAlign8366.unlandedFeePct8366(5.0 / 14.0, 0.0003, 0.03), 1e-3)
        assertEquals(0.0, ShadowAlign8366.unlandedFeePct8366(0.0, 0.0003, 0.03), 1e-12)
    }

    // ── sell latency, cost ──

    @Test fun exitIsPricedAfterTheLiveSellLatency() {
        val lat = ShadowAlign8366.sellLatencyMs8366(false)
        assertTrue(lat >= 500L)
        assertFalse(ShadowAlign8366.latencyElapsed8366(1_000L, 16_000L, 16_999L))
        assertTrue(ShadowAlign8366.latencyElapsed8366(1_000L, 16_000L, 17_000L))
        assertFalse("no trigger, no fill", ShadowAlign8366.latencyElapsed8366(0L, 16_000L, 99_000L))
        // a stop triggered at -15 % whose mark 16 s later is -25 % books -25 %, not the trigger's -15 %
        val marks = listOf(0L to -15.0, 5_000L to -19.0, 16_500L to -25.0, 30_000L to -30.0)
        val fill = marks.first { ShadowAlign8366.latencyElapsed8366(1L, 16_000L, it.first + 1L) }.second
        assertEquals(-25.0, fill, 0.0)
    }

    @Test fun measuredLaneCostOnceMeasuredElseModelNeverUnderTheFixedLegs() {
        assertEquals(14.83, ShadowAlign8366.shadowCostPct8366(14.83, 3.7, 1.0), 1e-9)
        assertEquals("measured below the model is what live pays", 2.0, ShadowAlign8366.shadowCostPct8366(2.0, 7.5, 1.0), 1e-9)
        assertEquals(3.7, ShadowAlign8366.shadowCostPct8366(null, 3.7, 1.0), 1e-9)
        assertEquals(1.04, ShadowAlign8366.shadowCostPct8366(null, 0.5, 1.04), 1e-9)
        // the priority fee + tips of one round trip as a share of the 0.03 route-minimum ticket
        assertEquals(1.04, ShadowAlign8366.fixedPct8366(0.000312, 0.03), 1e-9)
        assertEquals(0.0, ShadowAlign8366.fixedPct8366(Double.NaN), 0.0)
    }

    // ── paper never sizes live ──

    @Test fun smartSizerLiveFallbackIsNeutral() {
        assertTrue(LivePerf8366.thinLive8366(0))
        assertTrue(LivePerf8366.thinLive8366(9))
        assertFalse(LivePerf8366.thinLive8366(10))
        assertEquals(1.0, LivePerf8366.thinLiveMult8366(0), 0.0)
        assertEquals(1.0, LivePerf8366.thinLiveMult8366(9), 0.0)
        val sizer = src("engine/SmartSizer.kt")
        assertFalse(sizer.contains("useFluidFallback"))
        assertFalse(sizer.contains("LIVE perfMult using paper-trained fallback"))
        assertTrue(sizer.contains("if (LivePerf8366.thinLive8366(perf.totalTrades)) {"))
    }

    // ── alignment ──

    @Test fun alignmentPairsSameLaneSameMintWithin60s() {
        ShadowAlign8366.resetForTests8366()
        ShadowAlign8366.noteShadow8366("EVIDENCE", "SHITCOIN", "mintA", 1_000_000L, 10.0, 1_100_000L)
        ShadowAlign8366.noteLive8366("t1", "SHITCOIN", "mintA", 1_030_000L, -5.0, 1_100_000L)
        assertEquals(1, ShadowAlign8366.summary8366().pairs)
        assertEquals(-15.0, ShadowAlign8366.summary8366().meanGapPts, 1e-9)
        ShadowAlign8366.noteLive8366("t1", "SHITCOIN", "mintA", 1_030_000L, -5.0, 1_100_000L)   // the same trade again: once
        ShadowAlign8366.noteShadow8366("EVIDENCE", "SHITCOIN", "mintB", 1_000_000L, 4.0, 1_100_000L)
        ShadowAlign8366.noteLive8366("t2", "SHITCOIN", "mintB", 1_061_000L, 1.0, 1_100_000L)   // 61 s apart: no pair
        ShadowAlign8366.noteLive8366("t3", "MOONSHOT", "mintB", 1_000_000L, 1.0, 1_100_000L)   // another lane: no pair
        assertEquals(1, ShadowAlign8366.summary8366().pairs)
        ShadowAlign8366.noteLive8366("t4", "SHITCOIN", "mintC", 2_000_000L, 3.0, 2_100_000L)
        ShadowAlign8366.noteShadow8366("EXPLORE", "SHITCOIN", "mintC", 2_010_000L, 1.0, 2_100_000L)   // shadow after live pairs too
        val s = ShadowAlign8366.summary8366()
        assertEquals(2, s.pairs)
        assertEquals(-15.0, s.worst, 1e-9)
        assertTrue(ShadowAlign8366.line8366(s).startsWith("align8366[pairs=2 meanGapPts=-6.5 p50=-6.5 worst=-15.0 lane{"))
        ShadowAlign8366.resetForTests8366()
    }

    @Test fun alignmentSummaryIsPure() {
        assertEquals(1, ShadowAlign8366.pairIndex8366("L", "m", 1_000L,
            listOf(Triple("L", "m", 100_000L), Triple("L", "m", 20_000L), Triple("X", "m", 1_000L)), listOf(false, false, false)))
        assertEquals(-1, ShadowAlign8366.pairIndex8366("L", "m", 1_000L, listOf(Triple("L", "m", 1_000L)), listOf(true)))
        val s = ShadowAlign8366.summarize8366(listOf("A" to -15.0, "A" to -5.0, "B" to 3.0))
        assertEquals(3, s.pairs)
        assertEquals(-17.0 / 3.0, s.meanGapPts, 1e-9)
        assertEquals(-5.0, s.p50, 1e-9)
        assertEquals(-15.0, s.worst, 1e-9)
        assertEquals(2, s.byLane["A"]!!.first)
        assertEquals("align8366[pairs=3 meanGapPts=-5.7 p50=-5.0 worst=-15.0 lane{A n2/-10.0,B n1/+3.0}]", ShadowAlign8366.line8366(s))
        assertEquals("align8366[pairs=0 meanGapPts=- p50=- worst=- lane{-}]", ShadowAlign8366.line8366(ShadowAlign8366.summarize8366(emptyList())))
    }

    // ── persistence: a pre-8366 record still decodes, the hold survives a restart ──

    @Test fun holdStatePersistsAndOldRecordsDecode() {
        val leg = ShadowPersist8355.LegRec8355("NOW", "RUNNER", true, false, 1.0, 1L, 0.4, 24.0, 1, 130.0, -3.0,
            hold8366 = true, taken8366 = 1, peakAt8366 = 99L)
        val t = ShadowPersist8355.TicketRec8355("m", "s", "MOONSHOT", "S", "MOMENTUM", "B70", "MC", 1.0, 1L, 7.0, 15.0, 20.0,
            true, true, 2.3, 5L, listOf(leg), kind8360 = "NATIVE", markSrc8360 = "TICK")
        assertEquals(t, ShadowPersist8355.decodeTicket8355(ShadowPersist8355.encodeTicket8355(t)))
        val old = ShadowPersist8355.encodeTicket8355(t.copy(legs = listOf(leg.copy(hold8366 = false, taken8366 = 0, peakAt8366 = 0L))))
            .replace(",0,0,0|", "|")   // strip the three appended leg fields: an 11-field pre-8366 leg
        assertEquals(false, ShadowPersist8355.decodeTicket8355(old)?.legs?.single()?.hold8366)
        val pos = ShadowPersist8355.PosRec8355("m", "SHITCOIN", 1.0, 1L, 7.0, 15.0, 20.0, 1_000L, 1.0, 0.0, 0, 120.0, 2.2, 5L,
            hold8366 = true, taken8366 = 0, peakAt8366 = 4L)
        assertEquals(pos, ShadowPersist8355.decodePos8355(ShadowPersist8355.encodePos8355(pos)))
        val oldPos = ShadowPersist8355.encodePos8355(pos).split('|').take(16).joinToString("|")
        assertEquals(false, ShadowPersist8355.decodePos8355(oldPos)?.hold8366)
    }

    // ── wiring ──

    @Test fun bothBooksAreWiredToTheLiveRules() {
        val ex = src("engine/truth/ExploreShadow8347.kt")
        assertTrue(ex.contains("ScalpStop8363.decide8363(g, maxOf(leg.peak, g), nowMs - leg.entryAtMs, leg.tiers, t.stopPct, t.costPct, noise, chase)"))
        assertTrue(ex.contains("ShadowAlign8366.liveStopLevel8366(t.runner, t.stopPct, leg.peak, t.costPct)"))
        assertTrue(ex.contains("ShadowAlign8366.holdExit8366(g, leg.peak, leg.taken8366, nowMs - leg.entryAtMs, nowMs - leg.peakAt8366, t.costPct)"))
        assertTrue(ex.contains("t.legs.filter { it.entry == Entry8347.NOW && it.active }.forEach { landed8366(t, it, nowMs) }"))
        assertTrue(ex.contains("val cost = try { ShadowAlign8366.laneCostPct8366(lane, liq, ts.mint, ts.lastMcap) }"))
        assertTrue(ex.contains("ShadowAlign8366.latencyElapsed8366(leg.exitAt8366, ShadowAlign8366.sellLatencyMs8366(false), nowMs)"))
        assertTrue(ex.contains("ShadowAlign8366.noteShadow8366(\"EXPLORE\", t.lane, t.mint, leg.entryAtMs, net, nowMs)"))
        assertFalse("the old inline scalp stop is gone", ex.contains("g <= -(t.stopPct * 0.6).coerceAtLeast(4.0)"))
        val ev = src("engine/truth/EvidenceShadow8344.kt")
        assertTrue(ev.contains("ShadowAlign8366.liveStopLevel8366(runner, p.stopPct, p.peakPct, p.costPct)"))
        assertTrue(ev.contains("ShadowAlign8366.unlandedEntryFeePct8366(\"\$mint|\$lane|\$nowMs|EVIDENCE\", mint, nowMs)"))
        assertTrue(ev.contains("val cost = try { ShadowAlign8366.laneCostPct8366(lane, liq, mint, ts.lastMcap) }"))
        assertTrue(ev.contains("ShadowAlign8366.noteShadow8366(\"EVIDENCE\", p.lane, p.mint, p.atMs, net, nowMs)"))
        assertTrue(src("engine/truth/CostLedger7962.kt").contains("ShadowAlign8366.noteLive8366(e.tradeId, e.lane, e.mint, e.atMs - e.holdingTimeMs, e.realizedReturnPct)"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("ShadowAlign8366.statusLine8366()"))
        assertTrue(src("engine/CostTrim8151.kt").contains("fun failedLegFeeSol8366(): Double"))
        assertTrue("one landing read per entry", src("engine/truth/ShadowAlign8366.kt").contains("val land = landFor8366(mint, nowMs)"))
        assertEquals(com.lifecyclebot.engine.truth.ExploreShadow8347.TICKET_SOL_8347, ShadowAlign8366.TICKET_SOL_8366, 0.0)
        assertEquals(60_000L, ShadowAlign8366.PAIR_WINDOW_MS_8366)
        assertEquals(1.0, LivePerf8366.NEUTRAL_8366, 0.0)
        assertFalse("FinalDecisionGate.evaluate is untouched", src("engine/FinalDecisionGate.kt").contains("8366"))
    }
}

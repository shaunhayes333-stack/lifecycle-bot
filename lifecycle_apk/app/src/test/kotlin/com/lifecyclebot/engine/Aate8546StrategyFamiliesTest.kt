package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.FlowRead8546
import com.lifecyclebot.engine.truth.MemePaperTrader8544
import com.lifecyclebot.engine.truth.MemeRegime8546
import com.lifecyclebot.engine.truth.MemeStrategies8546
import com.lifecyclebot.engine.truth.MemeStrategies8546.Family8546
import com.lifecyclebot.engine.truth.TradeTape8100
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8546 — owner: "take everything you suggested, improve, upgrade or expand on the ideas and implement them". A meme
 * market regime switch, an order-flow / proven-wallet reader, five event strategy families paper-traded in the book, and
 * LIVE money for a family only once its paper record proves it.
 */
class Aate8546StrategyFamiliesTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private fun bar(i: Int, close: Double, hi: Double = close, lo: Double = close) =
        Candle(ts = i * 60_000L, priceUsd = close, marketCap = 0.0, volumeH1 = 0.0, volume24h = 0.0, highUsd = hi, lowUsd = lo)

    private fun pullbackBars(lastClose: Double = 1.16, lowAt9: Double = 1.07): List<Candle> = listOf(
        bar(0, 1.00), bar(1, 1.02), bar(2, 1.05), bar(3, 1.20), bar(4, 1.40, hi = 1.45),
        bar(5, 1.30), bar(6, 1.15), bar(7, 1.05, hi = 1.07, lo = 1.03), bar(8, 1.06), bar(9, 1.10, lo = lowAt9), bar(10, lastClose))

    @Test fun pullbackReclaimNeedsImpulsePullbackHigherLowAndReclaimUnderThePeak() {
        assertNotNull(MemeStrategies8546.pullbackReclaim8546(pullbackBars()))
        assertNull("at the high is not a pullback entry", MemeStrategies8546.pullbackReclaim8546(pullbackBars(lastClose = 1.40)))
        assertNull("the higher low broke", MemeStrategies8546.pullbackReclaim8546(pullbackBars(lowAt9 = 1.02)))
        assertNull("no impulse", MemeStrategies8546.pullbackReclaim8546((0..12).map { bar(it, 1.0 + it * 0.001) }))
    }

    @Test fun baseBreakoutNeedsATightBaseAndAnUnextendedBreak() {
        val base = (0..11).map { bar(it, 1.00 + (it % 6) * 0.01) }
        assertNotNull(MemeStrategies8546.baseBreakout8546(base + bar(12, 1.10)))
        assertNull("extended", MemeStrategies8546.baseBreakout8546(base + bar(12, 1.30)))
        assertNull("no base", MemeStrategies8546.baseBreakout8546((0..11).map { bar(it, if (it % 2 == 0) 1.0 else 1.3) } + bar(12, 1.40)))
    }

    @Test fun anEarlyCurveReclaimUsesExecuted15sEventsBeforeNineOneMinuteCandlesExist() {
        val now = 1_000_000L
        val prices = listOf(1.00, 1.04, 1.16, 1.33, 1.40, 1.35, 1.20, 1.10,
            1.02, 1.00, 1.04, 1.05, 1.12, 1.15, 1.18, 1.20)
        val prints = prices.mapIndexed { i, px ->
            TradeTape8100.Print8128(now - 90_000L + i * 6_000L, px, 0.35, i % 5 != 0,
                source = "helius", pool = "same_curve")
        }
        val why = MemeStrategies8546.earlyFlowReclaim8650(prints, now)
        assertNotNull("early real-swap reclaim before M1 history is ready", why)
        assertTrue(why!!.contains("15sBars="))
        assertNull("price-only curve marks never count as executed buys",
            MemeStrategies8546.earlyFlowReclaim8650(prints.map { it.copy(priceOnly = true) }, now))
        assertNull("a 20-second-stale signal is not an entry now",
            MemeStrategies8546.earlyFlowReclaim8650(prints, now + 20_000L))
        assertNull("a return to the peak is an adverse chase",
            MemeStrategies8546.earlyFlowReclaim8650(prints.dropLast(1) + prints.last().copy(priceSol = 1.42), now))
        assertNull("old buys cannot fund a sell-dominated recovery leg", MemeStrategies8546.earlyFlowReclaim8650(
            prints.mapIndexed { i, p -> if (i >= 11) p.copy(isBuy = false) else p }, now))
        val flow = FlowRead8546.of8546(prints, now)
        assertTrue(MemeStrategies8546.flowConfirms8546(Family8546.EARLY_FLOW_RECLAIM, flow))
        assertFalse(MemeStrategies8546.flowConfirms8546(Family8546.EARLY_FLOW_RECLAIM,
            flow.copy(buySol2m = 0.1, sellSol2m = 4.0)))
        assertFalse("young run-up cannot automatically turn into a LIVE buy without paper proof",
            MemeStrategies8546.provenRecord8546(5, 200.0, 1.0, 10.0))
    }

    @Test fun earlyLaunchPaperGetsATapeFirstLookWithoutInventingLiquidityOrBuyFlow() {
        val proof = "PUMPFUN_BONDING_CURVE_EXECUTABLE"
        val pump = "Abcdefghijkmnpqrstuvwxyz123456789pump"
        assertTrue("real swaps and executable curve allow $2k-market-cap discovery below the old $3k-liquidity scan floor",
            MemeStrategies8546.earlyTapeLiquidityEligible8663(pump, 744.0, proof, 10))
        assertTrue(MemeStrategies8546.earlyTapeLiquidityEligible8663("OtherSolanaMint", 2_000.0, "DEX_ROUTABLE", 12))
        assertFalse("price-only updates cannot supply volume", MemeStrategies8546.earlyTapeLiquidityEligible8663(pump, 744.0, proof, 0))
        assertFalse("non-pump tokens cannot inherit the pump curve route", MemeStrategies8546.earlyTapeLiquidityEligible8663("OtherSolanaMint", 744.0, proof, 20))
        assertFalse("an unknown route is not a paper fill", MemeStrategies8546.earlyTapeLiquidityEligible8663(pump, 744.0, "LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP", 20))
        assertFalse("too little usable depth stays out", MemeStrategies8546.earlyTapeLiquidityEligible8663(pump, 100.0, proof, 20))
        val s = src("engine/truth/MemeStrategies8546.kt")
        assertTrue(s.contains("TradeTape8100.activeMints8293(nowMs, 120_000L)"))
        assertTrue(s.contains("if (evaluate(ts, true)) { done++; tapeDone++ }"))
        assertTrue("the rest of the round-robin still runs", s.contains("while (done < PER_TICK_8546 && visited < all.size)"))
    }

    @Test fun aDueHandoffGetsTheBoundedRescueClaimAndTheGenericPrimaryLearnedFloorIsAdvisory() {
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("val dueReceiver8663 = !ExecutionAuthorityPolicy6533.isTrunkLane(l)"))
        assertTrue(bot.contains("if (!ownBrain8429 && !dueReceiver8663)"))
        assertTrue(bot.contains("SpecialistOwnership7951.handOffAllowed7960(l, ts.mint, primaryLane)"))
        val learned = bot.substringAfter("if (learnedFloorDelta6491 > 0 && score6491 < learnedFloor6491)")
            .substringBefore("return true")
        assertTrue(learned.contains("LANE_LOCAL_LEARNED_FLOOR_ADVISORY_8663"))
        assertFalse("the second score opinion must never veto ahead of CandidateAdmission", learned.contains("return false"))
    }

    @Test fun heliusExecutedSwapIsTapedBeforeOptionalCopyTradeAndBroadCallbacks() {
        val o = src("engine/DataOrchestrator.kt")
        val callback = o.substringAfter("private fun startHeliusWebSocket(apiKey: String)")
            .substringBefore("// ── DexScreener WebSocket")
        val tape = callback.indexOf("ProofDataSwapTape8144.noteHelius8144(")
        val copy = callback.indexOf("copyTradeEngine?.onSwapDetected(")
        val broad = callback.indexOf("onHeliusTrade7773(")
        assertTrue("a Helius event must hit the executed tape before the optional downstream consumers",
            tape >= 0 && copy > tape && broad > tape)
        assertTrue("held marks subscribe to the deduplicated tape", src("engine/BotService.kt").contains("TradeTape8100.setOnExecutedTrade"))
    }

    @Test fun shorterBaseAndPostMigrationRetestHaveTheirOwnClocks() {
        val base = listOf(bar(0, 1.10)) + (1..8).map { bar(it, 1.0) } + bar(9, 1.06)
        assertNotNull(MemeStrategies8546.baseBreakout8546(base))
        val retest = listOf(bar(0, 1.5), bar(1, 1.2), bar(2, 1.03), bar(3, 1.05), bar(4, 1.08))
        assertNotNull(MemeStrategies8546.graduationRetest(retest, 60_000L))
        assertNull(MemeStrategies8546.graduationRetest(retest, 120_000L))
        assertTrue(MemeStrategies8546.provenRecord8546(30, 10.0, 1.0, Double.POSITIVE_INFINITY))
        assertFalse(MemeStrategies8546.provenRecord8546(30, 10.0, 1.0, Double.NaN))
    }

    @Test fun rangeLowBuysTheBottomQuarterOfAnIntactRange() {
        val range = (0..15).map { bar(it, 1.15, hi = 1.30, lo = 1.00) }
        assertNotNull(MemeStrategies8546.rangeLow8546(range + (16..19).map { bar(it, 1.05, hi = 1.07, lo = 1.03) }))
        assertNull("mid-range", MemeStrategies8546.rangeLow8546(range + (16..19).map { bar(it, 1.20, hi = 1.22, lo = 1.18) }))
    }

    @Test fun flowIsReadFromExecutedSwapsAndConfirmsEachFamily() {
        val now = 1_000_000L
        val prints = (0 until 8).map { TradeTape8100.Print8128(now - 200_000L + it * 25_000L, 1e-6 * (1 + it * 0.01), if (it % 4 == 3) 0.2 else 0.5, it % 4 != 3) }
        val f = FlowRead8546.of8546(prints, now, smartWallets = 2)
        assertTrue(f.known); assertTrue(f.netSol2m > 0.0); assertEquals(2, f.smartWallets5m)
        assertFalse("too few prints: no flow", FlowRead8546.of8546(prints.take(3), now).known)
        assertFalse("price-only marks are not trades", FlowRead8546.of8546(prints.map { it.copy(priceOnly = true) }, now).known)
        val buyers = FlowRead8546.Flow8546(true, 8, 2, 4.0, 1.0, 12, 4, 6.0, 2.0, 1.1, false, 0)
        val dumping = FlowRead8546.Flow8546(true, 2, 8, 1.0, 4.0, 4, 12, 2.0, 6.0, 0.5, false, 0)
        for (fam in listOf(Family8546.PULLBACK_RECLAIM, Family8546.BASE_BREAKOUT_FLOW, Family8546.GRADUATION_RETEST, Family8546.RANGE_LOW)) {
            assertTrue("$fam buyers", MemeStrategies8546.flowConfirms8546(fam, buyers))
            assertFalse("$fam dump", MemeStrategies8546.flowConfirms8546(fam, dumping))
        }
        assertTrue(MemeStrategies8546.flowConfirms8546(Family8546.WALLET_CLUSTER, buyers.copy(smartWallets5m = 2)))
        assertFalse(MemeStrategies8546.flowConfirms8546(Family8546.WALLET_CLUSTER, buyers.copy(smartWallets5m = 1)))
        assertEquals("SHITCOIN", MemeStrategies8546.exitLane8546(Family8546.PULLBACK_RECLAIM, 300_000.0))
        assertEquals("EXPRESS", MemeStrategies8546.exitLane8546(Family8546.BASE_BREAKOUT_FLOW, 5_000_000.0))
        assertEquals("QUALITY", MemeStrategies8546.exitLane8546(Family8546.RANGE_LOW, 5_000_000.0))
    }

    @Test fun theRegimeIsColdOnlyOnEnoughLosingBreadth() {
        val n = MemeRegime8546.MIN_N_8546
        assertEquals(MemeRegime8546.Regime8546.COLD, MemeRegime8546.regimeOf8546(List(n) { -10.0 }, List(n) { -5.0 }))
        assertEquals("thin evidence is never cold", MemeRegime8546.Regime8546.NEUTRAL, MemeRegime8546.regimeOf8546(List(n - 1) { -10.0 }, List(n - 1) { -5.0 }))
        assertEquals(MemeRegime8546.Regime8546.HOT, MemeRegime8546.regimeOf8546(List(n) { 5.0 }, List(n) { 8.0 }))
        assertEquals(MemeRegime8546.Regime8546.NEUTRAL, MemeRegime8546.regimeOf8546(List(n) { -10.0 }, List(n) { if (it % 2 == 0) 3.0 else -3.0 }))
        assertTrue(MemeRegime8546.statusLine8546().startsWith("memeRegime8546["))
        assertTrue(src("engine/truth/ForwardReturnLabeler7731.kt").contains("if (horizon == 60 && MemePaperTrader8544.memeLane8544(o.lane)) try { MemeRegime8546.note8546("))
    }

    @Test fun aFamilyEarnsLiveMoneyOnlyOnAProvenPaperRecord() {
        assertTrue(MemeStrategies8546.provenRecord8546(30, 5.0, 2.0, 1.5))
        assertFalse(MemeStrategies8546.provenRecord8546(29, 5.0, 2.0, 1.5))
        assertFalse(MemeStrategies8546.provenRecord8546(30, 1.0, 2.0, 1.5))
        assertFalse(MemeStrategies8546.provenRecord8546(30, 5.0, 2.0, 1.2))
        // [n, wins, sumNet, sumPnl, sumSq, sumWin, sumLoss]: +10, -5, +10 -> mean 5, pf 20/5 = 4
        val p = MemePaperTrader8544.proofOf8546(doubleArrayOf(3.0, 2.0, 15.0, 0.0, 225.0, 20.0, 5.0))
        assertEquals(3.0, p[0], 0.0); assertEquals(5.0, p[1], 1e-9); assertEquals(4.0, p[3], 1e-9); assertTrue(p[2] > 0.0)
        assertFalse(MemePaperTrader8544.familyProven8546("PULLBACK_RECLAIM"))
        assertEquals("FAM_", MemePaperTrader8544.FAMILY_PREFIX_8546)
        assertNull(MemeStrategies8546.liveProven8546("no-signal-mint"))
        val gate = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(gate.contains("MemeStrategies8546.liveProven8546(ts.mint, nowMs)"))
    }

    private fun liveCandidate(stage: String = "NO_SETUP") = CandidateAdmission8136.Candidate8136(
        mint = "regime8546", lane = "MOONSHOT", selectedSetup = null, live = true, stage = stage, priceAgeMs = 10L,
        expectedGrossPct = 30.0, costPct = 4.5, genericScore = 70.0, genericFloor = 15.0,
        nativeQualified8213 = true, nativeSetup8213 = "BREAKOUT_RUNNER", liveEdge8114 = "CHART_BUY")

    @Test fun aColdRegimeStopsNewLiveEntriesUnlessAProvenFamilyCarriesThem() {
        val cold = liveCandidate().copy(regimeCold8546 = true)
        assertEquals(CandidateAdmission8136.LIVE_REGIME_COLD_8546, CandidateAdmission8136.decide8136(cold).reason)
        assertTrue(CandidateAdmission8136.decide8136(cold.copy(liveEdge8114 = CandidateAdmission8136.FAMILY_PROVEN_PREFIX_8546)).reason != CandidateAdmission8136.LIVE_REGIME_COLD_8546)
        assertTrue("paper trades every regime", CandidateAdmission8136.decide8136(cold.copy(live = false)) { false }.reason != CandidateAdmission8136.LIVE_REGIME_COLD_8546)
        assertTrue("a proven family's own event is not an unproven buy into the high",
            CandidateAdmission8136.decide8136(liveCandidate("CONTROLLED_MARKUP").copy(liveEdge8114 = CandidateAdmission8136.FAMILY_PROVEN_PREFIX_8546)).reason !=
                CandidateAdmission8136.LIVE_INTO_HIGH_UNPROVEN_8545)
    }

    @Test fun theFamiliesRunInTheLoopAndNeverTradeHardSafetyOrTouchLiveSlots() {
        val worker = src("engine/BotService.kt").substringAfter("private fun dispatchShadowEvidenceTicks8372(")
            .substringBefore("shadowEvidenceTickJob8372.set(job)")
        assertTrue(worker.contains("com.lifecyclebot.engine.truth.MemeStrategies8546.tick8546(tokenStatesCopy, nowShadow7307)"))
        assertTrue("short-lived meme setups must not wait behind two shadow-book sweeps",
            worker.indexOf("MemeStrategies8546.tick8546") < worker.indexOf("EvidenceShadow8344.tick8344") &&
                worker.indexOf("EvidenceShadow8344.tick8344") < worker.indexOf("ExploreShadow8347.tick8347"))
        val s = src("engine/truth/MemeStrategies8546.kt")
        assertTrue(s.contains("ts.safety.tier == com.lifecyclebot.engine.SafetyTier.HARD_BLOCK") && s.contains("SKIP_HARD_BLOCK"))
        assertTrue(s.contains("MemePaperTrader8544.openFamily8546(ts, s, nowMs)"))
        for (forbidden in listOf("ts.position", "TradeHistoryStore", "liveBuy", "Executor."))
            assertFalse(forbidden, s.substringAfter("object MemeStrategies8546 {").contains(forbidden))
        assertTrue(src("network/PumpCurveKeys7269.kt").contains("fun graduatedAtMs8546(mint: String): Long?"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("MemeStrategies8546.statusLine8546()"))
        assertTrue("a zero-signal run must reveal whether the chart or executed-flow evidence is missing",
            s.contains("funnel8644{") && s.contains("FLOW_UNKNOWN") && s.contains("M1_EMPTY") && s.contains("RAW_") && s.contains("FLOW_REFUSED_"))
    }

    // V5.0.8547 — the research dataset covers every meme lane: unfillable entries are written flagged and never trained on
    @Test fun unfillableMemeOutcomesReachTheDatasetFlaggedAndNeverTrain() {
        val l = src("engine/truth/ForwardReturnLabeler7731.kt").substringAfter("private fun bookEntryTarget8521(").substringBefore("o.bookedNet7997 = net;")
        assertTrue(l.contains("!entryFill8521(o) && MemePaperTrader8544.memeLane8544(o.lane)"))
        assertTrue(l.contains("EdgeModel8537.recordUnfilled8546(it, net, witness)"))
        assertTrue(l.contains("MemeRegime8546.note8546(nowMs, net, gross)"))
        val e = src("engine/truth/EdgeModel8537.kt").substringAfter("fun recordUnfilled8546(").substringBefore("private val unfilled8546")
        // superseded (8568): the dataset keeps the true, uncapped net
        assertTrue(e.contains("appendDataset(s, netPct, filled = false, witnessed = chainWitness)"))
        assertFalse("never trains the model", e.contains("learn("))
        val replay = File("../ci/edge_replay_8537.py").readText()
        assertTrue(replay.contains("if r.get(\"fill\", True) is False:"))
        assertTrue(File("../ci/edge_research_8546.py").exists())
    }
}

package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.market.HuntDelivery8293
import com.lifecyclebot.engine.market.HunterMandates8293
import com.lifecyclebot.engine.market.HunterShape8293
import com.lifecyclebot.engine.market.MarketSweep7297
import com.lifecyclebot.engine.market.OpportunityRoute8293
import com.lifecyclebot.engine.truth.TradeShapeLearner7883
import com.lifecyclebot.engine.truth.TradeTape8100
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8293 — token hunter audit (AUDIT_HUNTER_8293.md): native mandates, realised-shape ranking, opportunity leaders
 * evaluated first, hand-off without churn, the run tape hunter and the runner records that never graded.
 */
class Aate8293HunterAuditTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val t0 = 1_800_000_000_000L

    private fun row(mint: String, cap: Double = 50_000.0, liq: Double = 20_000.0, h1: Double = 10.0, age: Double = 0.5,
                    m5: Double = Double.NaN, observedAt: Long = 0L) = MarketSweep7297.Row(
        mint = mint, symbol = mint.take(6), name = mint.take(6), priceUsd = 1.0, mcapUsd = cap, liquidityUsd = liq,
        volumeH1Usd = 10_000.0, volumeH24Usd = 100_000.0, priceChangeH1Pct = h1, txCountH1 = 50, holders = 100,
        organicScore = 40.0, verified = false, ageHours = age, providers = setOf("T"), priceChange5mPct = m5,
        observedAtMs8169 = observedAt,
    )

    private fun opp(mint: String, rank: Int, score: Double, setup: String) = MarketSweep7297.OpportunitySignal(
        mint = mint, symbol = mint.take(6), score = score, rank = rank, universe = 100, percentile = 0.9, setup = setup,
        relativeStrengthPct = 10.0, priceVelocity5mPct = 6.0, volumeAcceleration = 1.5, txAcceleration = 1.2,
        liquidityDeltaPct = 0.0, buyPressurePct = 60.0, providerAgreement = 2, regime = "NORMAL", generatedAtMs = t0,
    )

    // ── mandates in the native terms ──

    @Test fun expressMandateIsTheNativeOne() {
        assertTrue(HunterMandates8293.expressFits8293(12.0, Double.NaN))
        assertTrue("native has no 5m minimum", HunterMandates8293.expressFits8293(12.0, -2.0))
        assertTrue("native chase line is 20% in 5m", HunterMandates8293.expressFits8293(12.0, 15.0))
        assertFalse(HunterMandates8293.expressFits8293(12.0, 25.0))
        assertFalse("CHASE_EXTENDED above 30% 1h", HunterMandates8293.expressFits8293(45.0, 3.0))
        assertFalse("below the 1% bootstrap continuation", HunterMandates8293.expressFits8293(0.5, 3.0))
        assertTrue(HunterMandates8293.expressM5Rank8293(5.0) > HunterMandates8293.expressM5Rank8293(15.0))
        assertTrue(src("engine/market/LaneHunter7297.kt").contains("HunterMandates8293.expressFits8293(r.priceChangeH1Pct, r.priceChange5mPct)"))
    }

    @Test fun moonshotRanksAwayFromTheRealisedLosingShape() {
        assertTrue(HunterMandates8293.moonshotCapPrior8293(15_000.0) > HunterMandates8293.moonshotCapPrior8293(5_000.0))
        assertTrue(HunterMandates8293.moonshotCapPrior8293(50_000.0) > HunterMandates8293.moonshotCapPrior8293(5_000.0))
        assertTrue(HunterMandates8293.moonshotYouthPrior8293(10.0 / 60.0) > HunterMandates8293.moonshotYouthPrior8293(1.0 / 60.0))
        assertEquals(0.0, HunterMandates8293.moonshotYouthPrior8293(0.0), 0.0)   // unknown age earns nothing
        assertFalse(HunterMandates8293.moonshotFits8293(10.0, 1_800.0, 10, 2_000.0))  // below the native liquidity floor
        assertTrue(HunterMandates8293.moonshotFits8293(10.0, 2_500.0, 10, 2_000.0))
        assertFalse(HunterMandates8293.moonshotFits8293(130.0, 25_000.0, 10, 2_000.0))
        assertTrue(HunterMandates8293.baseMin8293("MOONSHOT", 1_500.0) >= 1_500.0)
        assertTrue(HunterMandates8293.moonshotMinCap8293() in 1_500.0..10_000.0)   // MoonshotTraderAI's fluid floor
        assertEquals(3_000.0, HunterMandates8293.baseMin8293("PROJECT_SNIPER", 3_000.0), 0.0)
        val lh = src("engine/market/LaneHunter7297.kt")
        assertFalse(lh.contains("r.mcapUsd < 10_000.0 -> 6.0"))
        assertTrue(lh.contains("fluidBand(HunterMandates8293.baseMin8293(p.lane, p.baseMin), p.baseMax, bucketStatsFor(p.lane))"))
    }

    @Test fun manipulatedUsesTheNativeFluidLimits() {
        assertTrue(HunterMandates8293.manipulatedFits8293(10.0 / 60.0, 10, 8.0, 2_000.0, 12.0 / 60.0, 1_500.0))
        assertFalse(HunterMandates8293.manipulatedFits8293(10.0 / 60.0, 10, 8.0, 2_000.0, 8.0 / 60.0, 1_500.0))
        assertFalse("unknown age", HunterMandates8293.manipulatedFits8293(0.0, 10, 8.0, 2_000.0, 12.0 / 60.0, 1_500.0))
        assertFalse(HunterMandates8293.manipulatedFits8293(5.0 / 60.0, 10, 8.0, 2_000.0, 12.0 / 60.0, 3_000.0))
    }

    // ── the sweep's ages are as old as the answer they came from ──

    @Test fun carriedRowsAgeForwardAndMergeTakesTheTrueAge() {
        val now = t0 + 10L * 60_000L
        val stale = row("STALE".padEnd(44, 'A'), age = 0.3 / 60.0, observedAt = t0)          // fetched 10 min ago at 0.3 min
        val aged = MarketSweep7297.agedForward8293(listOf(stale), now).single()
        assertEquals(10.3 / 60.0, aged.ageHours, 1e-9)
        val unknown = row("UNK".padEnd(44, 'A'), age = 0.0, observedAt = t0)
        assertEquals(0.0, MarketSweep7297.agedForward8293(listOf(unknown), now).single().ageHours, 0.0)
        val undated = row("UND".padEnd(44, 'A'), age = 2.0, observedAt = 0L)
        assertEquals(2.0, MarketSweep7297.agedForward8293(listOf(undated), now).single().ageHours, 0.0)
        // the same mint from a fresh and a stale feed: the merged age is the true one, not the stale "youngest"
        val m = "SAME".padEnd(44, 'A')
        val fresh = row(m, age = 10.0 / 60.0, observedAt = now)
        val merged = MarketSweep7297.merge(MarketSweep7297.agedForward8293(listOf(fresh, row(m, age = 0.5 / 60.0, observedAt = t0)), now)).single()
        assertEquals(10.5 / 60.0, merged.ageHours, 1e-9) // earliest pool witness wins; a later pool cannot rejuvenate it
        assertTrue(src("engine/market/MarketSweep7297.kt").contains("merge(agedForward8293(results.flatMap { it.second }, System.currentTimeMillis()))"))
    }

    // ── learned losing shapes rank last ──

    @Test fun learnedLosingShapeIsDemotedNotRemoved() {
        val r1 = row("A".padEnd(44, 'A'), cap = 5_000.0); val r2 = row("B".padEnd(44, 'B'), cap = 60_000.0)
        val losing = { _: String, f: TradeShapeLearner7883.Feature, v: Double ->
            if (f == TradeShapeLearner7883.Feature.MCAP_USD && v < 10_000.0) "MCAP_USD_<10k" else null }
        assertEquals("MCAP_USD_<10k", HunterShape8293.losingShape8293("MOONSHOT", r1, losing))
        assertNull(HunterShape8293.losingShape8293("MOONSHOT", r2, losing))
        val ordered = HunterShape8293.order8293(listOf("a", "b", "c", "d"), setOf("c")) { it == "a" }
        assertEquals(listOf("c", "b", "d", "a"), ordered)
        assertEquals(4, ordered.size)
        // a never-seen losing-shape row cannot jump a watched qualified row in the delivery picks
        val picks = HunterShape8293.deliveryPicks8293(listOf("w1", "w2", "w3", "w4", "bad"), { it.startsWith("w") }, { it == "bad" }, 4)
        assertEquals(listOf("w1", "w2", "w3", "w4"), picks)
    }

    @Test fun shapeLearnerAnswersTheHuntersReadWithItsOwnRule() {
        val bin = TradeShapeLearner7883.Stat().apply { repeat(40) { add(-20.0 + (it % 3), 0.0) } }
        val lane = TradeShapeLearner7883.Stat().apply { repeat(100) { add(1.0, 0.0) } }
        val b = TradeShapeLearner7883.binOf(TradeShapeLearner7883.Feature.LIQUIDITY_USD, 2_000.0)
        TradeShapeLearner7883.seedForTest8293("CORE", TradeShapeLearner7883.Feature.LIQUIDITY_USD, b, bin, lane)
        assertNotNull(TradeShapeLearner7883.provenLosingBin8293("CORE", TradeShapeLearner7883.Feature.LIQUIDITY_USD, 2_000.0))
        assertNull(TradeShapeLearner7883.provenLosingBin8293("CORE", TradeShapeLearner7883.Feature.LIQUIDITY_USD, 60_000.0))
    }

    // ── opportunity leaders ──

    @Test fun opportunityLeadersAreRoutedAndEvaluatedFirst() {
        assertTrue(OpportunityRoute8293.isTop8293(opp("J", 1, 99.0, "BREAKOUT_EXPANSION")))
        assertFalse(OpportunityRoute8293.isTop8293(opp("J", 1, 99.0, "EXHAUSTION")))
        assertFalse(OpportunityRoute8293.isTop8293(opp("J", 30, 99.0, "BREAKOUT_EXPANSION")))
        val r = row("JONATHAN".padEnd(44, 'J'), cap = 400_000.0, h1 = 140.0)
        val lanes = listOf(
            OpportunityRoute8293.LaneTest8293("MOONSHOT", { true }, { it.priceChangeH1Pct <= 120.0 }),
            OpportunityRoute8293.LaneTest8293("EXPRESS", { it.mcapUsd <= 300_000.0 }, { true }),
            OpportunityRoute8293.LaneTest8293("SHITCOIN", { true }, { true }),
        )
        val rt = OpportunityRoute8293.route8293(r, opp(r.mint, 1, 99.0, "BREAKOUT_EXPANSION"), lanes, mapOf("SHITCOIN" to listOf(r.mint)))
        assertEquals(listOf("SHITCOIN"), rt.fit)
        assertEquals("MANDATE", rt.refused["MOONSHOT"]); assertEquals("BAND", rt.refused["EXPRESS"])
        assertEquals(listOf("SHITCOIN"), rt.picked)
        val top = (1..8).map { opp("m$it", it, 95.0, "CONTINUATION") }
        val sel = OpportunityRoute8293.select8293(top, top.map { it.mint }.toSet() - "m2", { m -> if (m == "m1") t0 - 5_000L else 0L }, t0, 15_000L)
        assertEquals(listOf("m3", "m4", "m5", "m6"), sel)   // m1 just evaluated, m2 not watched, bounded at 4
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("HunterWindow8293.windowTargets8293(all)"))
        assertTrue(bot.contains("HunterWindow8293.dueTargets8293(otherMints)"))
        assertTrue(src("engine/market/LaneHunter7297.kt").contains("HunterShape8293.orderLive8293(p.lane, order, byMint, top8293)"))
    }

    // ── hand-off churn ──

    @Test fun requeueCooldownEscalatesWhileTheIntakeBouncesAndResetsWhenWatched() {
        assertEquals(10L * 60_000L, HuntDelivery8293.cooldownMs8293(1))
        assertEquals(20L * 60_000L, HuntDelivery8293.cooldownMs8293(2))
        assertEquals(40L * 60_000L, HuntDelivery8293.cooldownMs8293(3))
        assertEquals(60L * 60_000L, HuntDelivery8293.cooldownMs8293(9))
        HuntDelivery8293.resetForTest8293()
        val m = "BOUNCE".padEnd(44, 'B')
        assertTrue(HuntDelivery8293.requeueDue8293(m, t0))
        HuntDelivery8293.noteRequeued8293(m, t0)
        assertTrue(HuntDelivery8293.cooling8293(m, t0 + 60_000L))
        assertTrue(HuntDelivery8293.requeueDue8293(m, t0 + 10L * 60_000L))
        HuntDelivery8293.noteRequeued8293(m, t0 + 10L * 60_000L)                      // bounced once
        assertFalse(HuntDelivery8293.requeueDue8293(m, t0 + 25L * 60_000L))           // now 20 min
        HuntDelivery8293.noteWatched8293(m)
        assertTrue(HuntDelivery8293.requeueDue8293(m, t0 + 25L * 60_000L))
        val lanes = HuntDelivery8293.lanesByMint8293(linkedMapOf("SHITCOIN" to listOf("x", "y"), "MOONSHOT" to listOf("x")))
        assertEquals(listOf("SHITCOIN", "MOONSHOT"), lanes.getValue("x").toList())
        val sc = src("engine/SolanaMarketScanner.kt")
        val emit = sc.substringAfter("suspend fun emitResidentHunt7807(").substringBefore("pending7807.awaitAll()")
        assertTrue(emit.indexOf("if (!queuedThisHunt7807.add(r.mint))") in 0 until emit.indexOf("if (isSeen(r.mint))"))
        assertTrue(emit.contains("laneAffinity = lanesByMint8293[r.mint] ?: setOf(lane)"))
        assertTrue(emit.contains("HuntDelivery8293.requeueDue8293(r.mint, now7301)"))
    }

    @Test fun hunterBoundsAndCadencesAreTheDocumentedOnes() {
        assertEquals(64, HunterShape8293.DELIVERY_HEAD_8293)
        assertEquals(10, OpportunityRoute8293.TOP_RANK_8293)
        assertEquals(80.0, OpportunityRoute8293.TOP_SCORE_8293, 0.0)
        assertTrue("BREAKOUT_EXPANSION" in OpportunityRoute8293.CONSTRUCTIVE_8293)
        assertFalse("EXHAUSTION" in OpportunityRoute8293.CONSTRUCTIVE_8293)
        assertEquals(HuntDelivery8293.BASE_COOLDOWN_MS_8293, HuntDelivery8293.cooldownMs8293(1))
        assertEquals(HuntDelivery8293.MAX_COOLDOWN_MS_8293, HuntDelivery8293.cooldownMs8293(4))
        assertTrue(HuntDelivery8293.due8293(0L, 0, t0))
        assertFalse(HuntDelivery8293.due8293(t0, 1, t0 + 60_000L))
        assertTrue(HuntDelivery8293.due8293(t0, 1, t0 + HuntDelivery8293.BASE_COOLDOWN_MS_8293))
        assertEquals(10_000L, com.lifecyclebot.engine.market.ResidentHunterWorker7807.RUN_TAPE_CADENCE_MS_8293)
    }

    // ── the run tape hunter ──

    private fun p(at: Long, px: Double, sol: Double, buy: Boolean) = TradeTape8100.Print8128(at, px, sol, buy)

    private fun runPrints(now: Long): List<TradeTape8100.Print8128> {
        val out = ArrayList<TradeTape8100.Print8128>()
        var px = 1.0e-7
        for (i in 0 until 14) {
            val at = now - 170_000L + i * 12_000L
            val buy = i % 4 != 3
            if (buy) px *= 1.045 else px *= 0.99
            out += p(at, px, if (buy) 0.3 else 0.1, buy)
        }
        return out
    }

    @Test fun runTapeDetectsAnEarlyRunOnExecutedPrints() {
        val now = t0
        val prints = runPrints(now)
        val run = RunTape8293.detect8293(prints, now)
        assertNotNull(run)
        assertTrue(run!!.risePct in 15.0..150.0)
        assertTrue(run.bpSolPct >= 60.0)
        assertTrue(run.evidence().startsWith("RUN_TAPE_8293"))
        // stale tape (newest print > 20 s old) is not a run now
        assertNull(RunTape8293.detect8293(prints, now + 60_000L))
        // too few prints
        assertNull(RunTape8293.detect8293(prints.takeLast(6), now))
        // sellers in control
        assertNull(RunTape8293.detect8293(prints.map { it.copy(isBuy = !it.isBuy) }, now))
        // already vertical: +400% off the low
        val vertical = prints.mapIndexed { i, x -> x.copy(priceSol = 1.0e-7 * (1.0 + i * 0.35)) }
        assertNull(RunTape8293.detect8293(vertical, now))
        // dumping from its high
        val dump = prints + p(now, prints.maxOf { it.priceSol } * 0.8, 0.5, false)
        assertNull(RunTape8293.detect8293(dump, now + 1L))
    }

    @Test fun runTapeGradesOnTheTapeAndStandsDownOnALosingRecord() {
        val at = t0
        val target = at + RunTape8293.GRADE_AFTER_MS_8293
        assertEquals(2.0, RunTape8293.priceNear8293(listOf(p(target - 60_000L, 1.0, 1.0, true), p(target + 10_000L, 2.0, 1.0, true)), target)!!, 0.0)
        assertNull("no print near the horizon: ungraded, never invented", RunTape8293.priceNear8293(listOf(p(target - 600_000L, 1.0, 1.0, true)), target))
        val st = CortexLedger7885.realisedStat8187()
        repeat(19) { st.add(-30.0, false) }
        assertFalse(RunTape8293.standsDown8293(st))
        st.add(-30.0, false)
        assertTrue(RunTape8293.standsDown8293(st))
        assertEquals(listOf("MOONSHOT", "SHITCOIN", "PROJECT_SNIPER"), RunTape8293.lanesFor8293(60_000L))
        assertEquals(listOf("MOONSHOT", "SHITCOIN"), RunTape8293.lanesFor8293(400_000L))
        assertEquals(listOf("MOONSHOT", "SHITCOIN"), RunTape8293.lanesFor8293(null))
    }

    @Test fun runTapeEvidenceIsFreshOnlyInsideItsWindow() {
        RunTape8293.resetForTest8293()
        val now = System.currentTimeMillis()
        val m = "RUNFRESH".padEnd(44, 'R')
        RunTape8293.noteEvidenceForTest8293(m, RunTape8293.detect8293(runPrints(now), now)!!)
        assertTrue(RunTape8293.fresh8293(m, now + 60_000L))
        assertFalse(RunTape8293.fresh8293(m, now + RunTape8293.EVIDENCE_TTL_MS_8293 + 1L))
        assertNotNull(RunTape8293.evidence8293(m, now))
    }

    @Test fun runnerHuntersAreWired() {
        assertTrue(src("engine/market/ResidentHunterWorker7807.kt").contains("com.lifecyclebot.engine.RunTape8293.scan8293()"))
        val rg = src("engine/RunnerGrab7967.kt")
        assertTrue(rg.contains("RunTape8293.fresh8293(mint, nowMs)"))
        assertTrue(rg.contains("// V5.0.8293 — held-admit signals were never graded"))
        assertTrue(rg.contains("ensureRecord7989(); gradeDue(nowMs)"))
        assertTrue(rg.contains("gradeSoon8293(nowMs)"))
        assertTrue(src("engine/RunnerPlay8018.kt").contains("RunTape8293.requestWatch8293(mint, \"RUN_REENTRY_8018\")"))
        assertTrue(src("engine/truth/TradeTape8100.kt").contains("fun activeMints8293("))
        assertTrue(src("engine/SpikeCapture7943.kt").contains("RunTape8293.statusLine8293()"))
        // safety: a hand-off never passes an open position, HARD_BLOCK or a Mayhem refusal
        val rt = src("engine/RunTape8293.kt")
        assertTrue(rt.contains("if (ts != null && ts.position.isOpen) return false"))
        assertTrue(rt.contains("ts.safety.tier == SafetyTier.HARD_BLOCK"))
        assertTrue(rt.contains("MayhemMode7943.liveRefusal(ts, nowMs)"))
    }
}

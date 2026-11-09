package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.MemePaperTrader8544
import com.lifecyclebot.engine.truth.TokenFacts
import com.lifecyclebot.engine.truth.TokenFacts.Fact
import com.lifecyclebot.engine.truth.TokenFacts.Rank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8559 — runtime repairs from the 8557 owner log (OutOfMemoryError after 44 min; token archive 20,145 resident rows at
 * 50 s uptime against 250-2,660 on earlier builds).
 */
class Aate8559RuntimeRepairTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun theArchiveRefreshNeverCreatesRows() {
        // a transient / desk / frozen row is not the bot's live row: it never archives
        assertFalse(TokenFacts.archiveEligible8559(TokenState(mint = "transient8559")))
        val f = src("engine/truth/TokenFacts.kt").substringAfter("fun archive(ts: TokenState").substringBefore("fun archiveEligible8559(")
        assertTrue(f.indexOf("if (!archiveEligible8559(ts)) return") in 0 until f.indexOf("cache.register("))
        assertTrue(f.indexOf("if (!cache.resident8559(ts.mint)) return") in 0 until f.indexOf("cache.register("))
        assertTrue(src("engine/TokenMetaCache.kt").contains("fun resident8559(mint: String): Boolean = live.containsKey("))
    }

    @Test fun liveDoesNotBuyAFreshLaunchOnAnUnprovenRead() {
        assertTrue(CandidateAdmission8136.liveIntoLaunch8559("FRESH_LAUNCH"))
        assertTrue(CandidateAdmission8136.liveIntoLaunch8559(" fresh_launch "))
        assertFalse("BASE stays open", CandidateAdmission8136.liveIntoLaunch8559("BASE_START"))
        assertFalse(CandidateAdmission8136.liveIntoLaunch8559(null))
        val a = src("engine/truth/CandidateAdmission8136.kt")
        val door = a.indexOf("if (c.live && liveIntoLaunch8559(c.stage) && !supported8136(")
        assertTrue("after the into-high door, before the native-opinion admit",
            door > a.indexOf("if (c.live && liveIntoHigh8545(c.stage)") && door < a.indexOf("if (nativeLive8259 && c.live) {"))
        assertTrue(a.contains("doorBinds8393(LIVE_LAUNCH_UNPROVEN_8559)"))
    }

    @Test fun aProviderPriceReplacesASeedStampedNow() {
        val seed = Fact(0.001, "PUMP_FUN_BC_SYNTHETIC", Rank.AGGREGATE, 200_000L)
        assertTrue(TokenFacts.seedPrice8559("PUMP_FUN_BC_SYNTHETIC"))
        assertTrue(TokenFacts.seedPrice8559("CHAIN_SUPPLY_CAP_SEED_7280"))
        assertFalse(TokenFacts.seedPrice8559("DEXSCREENER_PAIR_POLL"))
        // the pair poll's price at its fetch time (up to 135 s back) replaces the re-stamped seed
        assertTrue(TokenFacts.acceptsPrice(seed, Fact(0.002, "DEXSCREENER_PAIR_POLL", Rank.PROVIDER, 70_000L)))
        assertFalse("too old even for a seed", TokenFacts.acceptsPrice(seed, Fact(0.002, "X", Rank.PROVIDER, 200_000L - TokenFacts.SEED_YIELD_MS - 1)))
        assertFalse("another seed does not jump the queue", TokenFacts.acceptsPrice(seed, Fact(0.002, "CHAIN_SUPPLY_CAP_SEED_7280", Rank.PROVIDER, 70_000L)))
        assertFalse("a non-seed held price keeps the newest-wins rule",
            TokenFacts.acceptsPrice(Fact(0.001, "DEX_WS", Rank.AGGREGATE, 200_000L), Fact(0.002, "DEXSCREENER_PAIR_POLL", Rank.PROVIDER, 70_000L)))
    }

    @Test fun aCorrectionNeverHoldsTheFieldLikeAChainTick() {
        val ts = TokenState(mint = "corr8559")
        assertTrue(TokenFacts.writePrice(ts, 1.0, "GRAD_TICK", Rank.CHAIN, 100_000L, 100_000L))
        assertTrue(TokenFacts.correctPrice(ts, 1.1, "GRAD_TICK+RECONCILED_7046"))
        assertEquals(Rank.AGGREGATE, TokenFacts.priceFact(ts)!!.rank)
        assertEquals(100_000L, TokenFacts.priceFact(ts)!!.observedAtMs)
        assertTrue("a provider read 1 s later replaces the correction", TokenFacts.writePrice(ts, 1.2, "JUPITER", Rank.PROVIDER, 101_000L, 101_000L))
    }

    @Test fun theSideBooksNeverCapAWinButWaitForABigJumpToBeConfirmed() {
        assertTrue(MemePaperTrader8544.markPlausible8559(1.0, 15.0))
        assertFalse(MemePaperTrader8544.markPlausible8559(1.0, 25.0))
        assertTrue("no prior mark: nothing to compare", MemePaperTrader8544.markPlausible8559(0.0, 25.0))
        // a real 30x runner: held once, counted in full when an INDEPENDENT source agrees (8560)
        assertFalse(MemePaperTrader8544.acceptMark8559("t:run", 1.0, 30.0, "TICK:DEXSCREENER_BATCH"))
        assertFalse("the same feed cannot confirm itself", MemePaperTrader8544.acceptMark8559("t:run", 1.0, 30.5, "TICK:DEXSCREENER_BATCH"))
        assertTrue(MemePaperTrader8544.acceptMark8559("t:run", 1.0, 31.0, "CURVE"))
        // a one-off unit mix: never agrees, never used
        assertFalse(MemePaperTrader8544.acceptMark8559("t:bad", 1.0, 50_000.0, "TICK:DEXSCREENER_BATCH"))
        assertTrue("the true mark comes back and is used", MemePaperTrader8544.acceptMark8559("t:bad", 1.0, 1.1, "TICK:DEXSCREENER_BATCH"))
        assertTrue(MemePaperTrader8544.jumpConfirmed8559(30.0, "TICK:A", 45.0, "POOL"))
        assertFalse(MemePaperTrader8544.jumpConfirmed8559(30.0, "TICK:A", 45.0, "TICK:A"))
        assertFalse(MemePaperTrader8544.jumpConfirmed8559(null, null, 30.0, "POOL"))
        assertEquals("TICK:DEXSCREENER_BATCH", MemePaperTrader8544.markSource8560("TICK", TokenState(mint = "m", lastPriceSource = "DEXSCREENER_BATCH")))
        assertEquals("CURVE", MemePaperTrader8544.markSource8560("CURVE", null))
        val book = src("engine/truth/MemePaperTrader8544.kt")
        assertFalse("wins are not clamped", book.contains("coerceAtMost(PAPER_GAIN_CLAMP"))
        assertTrue(book.contains("val net = netPct8544(p.banked, 0.0, g, p.costPct)\n"))
        assertTrue(src("engine/truth/ExploreShadow8347.kt").contains("MemePaperTrader8544.acceptMark8559(\"shadow:\$key\", t.lastPx, it.priceUsd, MemePaperTrader8544.markSource8560(it.source, tokens[t.mint]))"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("MemePaperTrader8544.jumpLine8559()"))
    }

    /** Runtime Smoke 8552-8558: ANR opening the app with paper positions; accounting FAILED with every delta 0. */
    @Test fun nothingReplaysTheJournalUnderALockTheScreenWaitsOn() {
        val ledger = src("engine/truth/PaperAccountLedger6430.kt")
        assertFalse(ledger.contains("JournalEconomicAuthority6616.notifyEconomicMutation(\"BUY\")"))
        assertFalse(ledger.contains("JournalEconomicAuthority6616.notifyEconomicMutation(\"SELL\")"))
        assertTrue(ledger.contains("JournalEconomicAuthority6616.notifyEconomicMutationAsync8077(\"BUY\")"))
        assertTrue(ledger.contains("JournalEconomicAuthority6616.notifyEconomicMutationAsync8077(\"SELL\")"))
        val snap = src("engine/truth/UnifiedAccountSnapshot6635.kt").substringAfter("private fun reconcileThrottled7302()").substringBefore("fun read(")
        assertFalse("never inline inside the synchronized read", snap.contains("if (!onMain)"))
        assertTrue(snap.contains("\"reconcile-6635\""))
    }

    @Test fun cyclicDoesNotWriteASecondJournalRowWithoutAPosition() {
        val r = src("engine/V3JournalRecorder.kt")
        val open = r.substringAfter("private fun executorAlreadyJournaledCyclicOpen6699(").substringBefore("private fun executorAlreadyJournaledCyclicClose6699(")
        assertFalse("the race-prone durable-row read is gone", open.contains("getAllValidTradesSnapshot"))
        assertTrue(open.contains("if (!canonicalOpen) return false"))
        val close = r.substringAfter("private fun executorAlreadyJournaledCyclicClose6699(").substringBefore("private fun emitExecutorSupersession6699(")
        assertFalse(close.contains("getAllValidTradesSnapshot"))
        assertTrue(close.contains("if (stillOpen) return false"))
    }

    @Test fun aPaperRequestIsPromotedToTheFloorNoFurther() {
        assertEquals(0.03, TicketExec8304.sealCeiling8552(true, 0.012, 0.4, Double.MAX_VALUE, 0.03), 1e-12)
        assertEquals("a request above the floor stays its own size", 0.05, TicketExec8304.sealCeiling8552(true, 0.05, 0.4, Double.MAX_VALUE, 0.03), 1e-12)
        assertEquals("the market still binds", 0.02, TicketExec8304.sealCeiling8552(true, 0.012, 0.02, Double.MAX_VALUE, 0.03), 1e-12)
        assertEquals(0.012, TicketExec8304.sealCeiling8552(false, 0.012, 0.4, Double.MAX_VALUE, 0.03), 1e-12)
    }

    /** V5.0.8560 — two winning paper closes made a ~-2.5 % label record read +223.5 % ±3.0 n30 PROVEN (CASHGEN OUTSIDE_ENVELOPE). */
    @Test fun aFewRealisedWinsCannotLiftALabelRecord() {
        assertEquals(0.0, com.lifecyclebot.engine.cortex.LanePlaybook7907.realisedLift8559(2.0, 450.0), 0.0)
        assertEquals(0.0, com.lifecyclebot.engine.cortex.LanePlaybook7907.realisedLift8559(29.0, 12.0), 0.0)
        assertEquals(30.0, com.lifecyclebot.engine.cortex.LanePlaybook7907.realisedLift8559(30.0, 12.0), 0.0)
        assertEquals("losing closes stay with the 8295 governor", 0.0, com.lifecyclebot.engine.cortex.LanePlaybook7907.realisedLift8559(40.0, -5.0), 0.0)
        val p = src("engine/cortex/LanePlaybook7907.kt")
        assertFalse(p.contains("if (rrM.isFinite() && rrM > 0.0) rrN else 0.0"))
        assertTrue(p.contains("Cortex7885.executableCopy8187(raw, Cortex7885.executableOffset8187(lane), realisedLift8559(rrN, rrM), rrM)"))
        assertTrue(p.contains("Cortex7885.executableCopy8187(local, Cortex7885.executableOffset8187(lane), realisedLift8559(rrN, rrM), rrM)"))
    }

    /** V5.0.8560 — the DexScreener batch priced the mint's highest-"liquidity" pool, whatever it traded against. */
    @Test fun dexScreenerPricesComeFromTheLanesPoolChooser() {
        val d = File("src/main/kotlin/com/lifecyclebot/network/DexscreenerApi.kt").readText()
        val batch = d.substringAfter("val wanted8560 = ").substringBefore("} catch (_: Exception) {")
        assertFalse(batch.contains("liq > bestLiq"))
        assertTrue(batch.contains("if (mint !in wanted8560) continue"))
        assertTrue(batch.contains("DexPools8285.choose8285(list.map { it.first }, DexPools8285.chosen8285(mint))"))
        val o = File("src/main/kotlin/com/lifecyclebot/perps/AlternativeOracles.kt").readText()
        assertTrue(o.contains("com.lifecyclebot.network.DexPools8285.choose8285(rows8560,"))
        assertFalse(o.contains("if (price > 0 && liquidity > bestLiquidity)"))
    }

    /** V5.0.8562 — Runtime Smoke 8560: the only failure left was one PAPER PARTIAL_SELL receipt whose journal row landed 14 s late. */
    @Test fun anInFlightReceiptGetsTheWholeBoundedGraceNotTenSeconds() {
        val a = src("engine/truth/ExecutionSpineAcceptance6647.kt")
        assertTrue(a.contains("CanonicalEconomicEvent6635.onlyYoungOpen8322(nowMs, IN_FLIGHT_WAIT_GRACE_MS_8339)"))
        assertFalse(a.contains("onlyYoungOpen8322(nowMs, 10_000L)"))
        assertTrue("the wait stays bounded", a.contains("const val IN_FLIGHT_WAIT_GRACE_MS_8339 = 30_000L"))
    }

    /** V5.0.8564 — the acceptance FAIL witness names what the journal replay skipped (the reason was logcat-only). */
    @Test fun theAcceptanceFailureNamesTheReplaysSkippedEvents() {
        assertEquals("-", com.lifecyclebot.engine.truth.JournalEconomicReplay6619.lastSkipped8563.takeIf { it == "-" } ?: "-")
        assertTrue(src("engine/truth/ExecutionSpineAcceptance6647.kt").contains("append(\" replaySkipped8563=\${try { JournalEconomicReplay6619.lastSkipped8563 }"))
        assertTrue(src("engine/truth/JournalEconomicReplay6619.kt").contains("lastSkipped8563 = if (skippedEvents6899 == 0) \"-\" else"))
    }

    /** V5.0.8565 — 8562 LIVE: +2,600 % runners handed to a quarantined MOONSHOT; runner spike sells suppressed 29/29. */
    @Test fun runnersReachALaneThatCanBuyAndTheirSpikeSellsAreNotSuppressedByALaggingCap() {
        val h = com.lifecyclebot.engine.truth.AdmissionHandoff8144
        assertTrue(h.liveBlocked8565("MOONSHOT", paper = false, quarantined = true))
        assertFalse("paper still samples a quarantined lane", h.liveBlocked8565("MOONSHOT", paper = true, quarantined = true))
        assertFalse(h.liveBlocked8565("MOONSHOT", paper = false, quarantined = false))
        val k = com.lifecyclebot.engine.truth.IdentitySkew8332
        val skew = com.lifecyclebot.engine.truth.IdentitySkew8332.Verdict8332.SKEW
        val broken = com.lifecyclebot.engine.truth.IdentitySkew8332.Verdict8332.BROKEN
        assertEquals("a 4x spike with the cap written 9 s earlier is a lagging cap", skew, k.classify8332(4.0, 0.02, 10_000L, 1_000L, unitFactor8565 = false))
        assertEquals("a unit factor stays broken", broken, k.classify8332(4.0, 0.02, 10_000L, 1_000L, unitFactor8565 = true))
        assertEquals("inside one write a 4x disagreement stays broken", broken, k.classify8332(4.0, 0.02, 1_000L, 1_000L, unitFactor8565 = false))
        assertEquals("beyond 10x stays broken (the wrong-pool class)", broken, k.classify8332(20.0, 0.02, 10_000L, 1_000L, unitFactor8565 = false))
        assertEquals(0.1, k.LAG_RATIO_MIN_8565, 0.0); assertEquals(10.0, k.LAG_RATIO_MAX_8565, 0.0)
    }

    /** V5.0.8567 — 8562: held ticks blocked 63 s in CortexExit7897.observe; -100 % paper fills off $1 liquidity; lessons retried forever. */
    @Test fun exitsNeverWaitOnTheExitCortexAndPaperAndLessonsBookWhatHappened() {
        val x = src("engine/cortex/CortexExit7897.kt")
        val obs = x.substringAfter("fun observe(ts: TokenState").substringBefore("private val sampling8567")
        assertTrue("the tick hands the read off", obs.contains("sampler8567.execute {"))
        assertFalse("no voter read on the tick", obs.contains("CortexVoters7885.readAll"))
        assertFalse(x.contains("@Synchronized private fun persist()"))
        val p = com.lifecyclebot.engine.truth.PaperFillCore8418
        assertFalse("$1 against a $10k cap is not this pool", p.liquidityCapUsable8567(1.0, 10_000.0))
        assertTrue(p.liquidityCapUsable8567(5_000.0, 50_000.0))
        assertTrue("a rug moves the cap with it", p.liquidityCapUsable8567(50.0, 20_000.0))
        assertFalse(p.liquidityCapUsable8567(0.0, 10_000.0))
        val t = com.lifecyclebot.v4.meta.TradeLessonRecorder
        assertFalse(t.missingSettled8567(60_000L)); assertTrue(t.missingSettled8567(t.MISSING_WAIT_MS_8567 + 1))
    }

    /** V5.0.8567 — the edge model learned 5-min nets clipped to -60..+200 %: a 20x taught the same as a 3x. */
    @Test fun theEdgeModelLearnsHowBigAWinnerIs() {
        val e = com.lifecyclebot.engine.truth.EdgeModel8537
        assertEquals(0.0, e.target8567(0.0), 1e-9)
        assertTrue("a 20x counts more than a 3x", e.target8567(1_900.0) > e.target8567(200.0) + 100.0)
        assertTrue("a total loss is finite", e.target8567(-100.0).isFinite())
        assertEquals(250.0, e.netOf8567(e.target8567(250.0)), 1e-6)
        assertEquals(-9.53, e.target8567(-9.1), 0.05)
        val src8537 = src("engine/truth/EdgeModel8537.kt")
        assertFalse(src8537.contains("coerceIn(Y_MIN, Y_MAX)"))
        assertTrue(src8537.contains("appendDataset(s, netPct, witnessed = chainWitness)"))
    }

    /** V5.0.8567 — fast meme coins' outcomes never trained: no mark inside the 5-60 s rebase window. */
    @Test fun aChainPricedMemeDecisionIsABuyableEntry() {
        val f = com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
        assertTrue(f.decisionChainFill8567("CURVE_ACCOUNT", false, 1_200L))
        assertTrue(f.decisionChainFill8567("TAPE_LAST_PRINT_8475", false, 4_000L))
        assertTrue("stamped chain proof", f.decisionChainFill8567("X", true, 0L))
        assertFalse("an aggregator price is never a fill", f.decisionChainFill8567("DEXSCREENER_BATCH", false, 500L))
        assertFalse("too old at the decision", f.decisionChainFill8567("CURVE_ACCOUNT", false, f.DECISION_FILL_MAX_AGE_MS_8567 + 1))
    }
}

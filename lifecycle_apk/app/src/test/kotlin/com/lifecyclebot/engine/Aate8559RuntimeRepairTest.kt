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
        // a real 30x runner: held once, counted in full when the next mark agrees
        assertFalse(MemePaperTrader8544.acceptMark8559("t:run", 1.0, 30.0))
        assertTrue(MemePaperTrader8544.acceptMark8559("t:run", 1.0, 31.0))
        // a one-off unit mix: never agrees, never used
        assertFalse(MemePaperTrader8544.acceptMark8559("t:bad", 1.0, 50_000.0))
        assertTrue("the true mark comes back and is used", MemePaperTrader8544.acceptMark8559("t:bad", 1.0, 1.1))
        assertTrue(MemePaperTrader8544.jumpConfirmed8559(30.0, 45.0)); assertFalse(MemePaperTrader8544.jumpConfirmed8559(null, 30.0))
        val book = src("engine/truth/MemePaperTrader8544.kt")
        assertFalse("wins are not clamped", book.contains("coerceAtMost(PAPER_GAIN_CLAMP"))
        assertTrue(book.contains("val net = netPct8544(p.banked, 0.0, g, p.costPct)\n"))
        assertTrue(src("engine/truth/ExploreShadow8347.kt").contains("MemePaperTrader8544.acceptMark8559(\"shadow:\$key\", t.lastPx, it.priceUsd)"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("MemePaperTrader8544.jumpLine8559()"))
    }

    /** Runtime Smoke 8552-8558: ANR opening the app with paper positions; accounting FAILED with every delta 0. */
    @Test fun nothingReplaysTheJournalUnderALockTheScreenWaitsOn() {
        val ledger = src("engine/truth/PaperAccountLedger6430.kt")
        assertFalse(ledger.contains("JournalEconomicAuthority6616.notifyEconomicMutation(\"BUY\")"))
        assertFalse(ledger.contains("JournalEconomicAuthority6616.notifyEconomicMutation(\"SELL\")"))
        assertTrue(ledger.contains("JournalEconomicAuthority6616.notifyEconomicMutationAsync8077(\"BUY\")"))
        assertTrue(ledger.contains("JournalEconomicAuthority6616.notifyEconomicMutationAsync8077(\"SELL\")"))
        val snap = src("engine/truth/UnifiedAccountSnapshot6635.kt").substringAfter("private fun reconcileThrottled7302()").substringBefore("@Synchronized")
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
}

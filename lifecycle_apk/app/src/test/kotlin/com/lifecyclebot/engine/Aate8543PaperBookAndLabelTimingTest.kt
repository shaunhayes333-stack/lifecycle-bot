package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.MemePaperBook8543
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8543 — owner (8542 log): "0 paper meme trades at all". The session ran LIVE, where the meme executor's paperBuy is
 * blocked by design (V5.0.3725: one TokenState.position slot). The lane-qualified picks' simulated trades (ExploreShadow
 * NATIVE / NOW / lane live exit) are now a durable per-lane paper book beside LIVE. Also from that log: the label batch
 * was slowed by the 8541 pool discovery (avg 8.2 s, max 287 s, horizonMissed 1,607), past horizons had no SOL rate
 * (NO_SOL_RATE 186) and a quiet Pyth stream left 1,092 marks without a rate.
 */
class Aate8543PaperBookAndLabelTimingTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    // 15. full simulated trade lifecycle and learning acknowledgment: each closed leg folds into its lane exactly once
    @Test fun aPaperBookTallyFoldsEveryCloseOnce() {
        val t = MemePaperBook8543.LaneTally8543()
        listOf(12.0, -6.0, -3.0, 40.0).forEach { MemePaperBook8543.fold8543(t, it) }
        MemePaperBook8543.fold8543(t, Double.NaN)   // an unpriced close is not a trade
        assertEquals(4, t.n); assertEquals(2, t.wins)
        assertEquals(43.0, t.sumNet, 1e-9); assertEquals(40.0, t.best, 0.0); assertEquals(-6.0, t.worst, 0.0)
        val line = MemePaperBook8543.laneLine8543("MOONSHOT", t)
        assertTrue(line, line.startsWith("MOONSHOT[n4 wr50% +10.8%"))
        assertTrue("nominal stake, labelled", line.contains("SOL@${MemePaperBook8543.NOMINAL_STAKE_SOL_8543}"))
        assertEquals("MOONSHOT[n0]", MemePaperBook8543.laneLine8543("MOONSHOT", MemePaperBook8543.LaneTally8543()))
    }

    @Test fun theBookRecordsTheLiveModelledLegAndNeverTouchesALiveSlot() {
        val shadow = src("engine/truth/ExploreShadow8347.kt")
        assertTrue(shadow.contains("if (leg.exit == liveExit) MemePaperBook8543.onClose8543(t.lane, t.mint, t.symbol, leg.entryAtMs, nowMs, net, why)"))
        val book = src("engine/truth/MemePaperBook8543.kt")
        for (forbidden in listOf("ts.position", "TradeHistoryStore", "CanonicalPaperTransaction6486", "CanonicalPositionAuthority6441", "Executor"))
            assertTrue(forbidden, !book.contains(forbidden))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("MemePaperBook8543.statusLine8543(com.lifecyclebot.engine.truth.ExploreShadow8347.openNativeByLane8543())"))
        // the meme executor's own paperBuy stays blocked in LIVE (unchanged)
        assertTrue(src("engine/ExecutionRouteGuard.kt").contains("PAPER_ROUTE_BLOCKED_IN_LIVE_USE_SHADOW_PATH"))
    }

    // 2. delayed provider recovery: the label batch no longer does pool discovery itself
    @Test fun theLabelBatchHandsPoolDiscoveryOff() {
        val l = src("engine/truth/ForwardReturnLabeler7731.kt")
        val rung = l.substringAfter("val missedChain8540 = batch.filter { it !in got }").substringBefore("got.putAll(chain8540)")
        assertTrue(rung.contains("OnChainDepth8038.requestMints8543(ask8543)"))
        assertTrue("no per-mint discovery on the batch thread", !rung.contains("OnChainDepth8038.requestMint8540(m)"))
        assertTrue(src("engine/truth/OnChainDepth8038.kt").contains("fun requestMints8543(mints: List<String>)"))
    }

    // 1 / 13. a past horizon converts at the SOL rate observed then; a gap needs agreeing sides; nothing invented
    @Test fun aPastHorizonUsesTheRateObservedThen() {
        val h = 3_600_000L
        val obs = listOf(0L to 100.0, h to 101.0, 2 * h to 150.0)
        assertEquals(101.0, SolRateHistory8543.rateAt8543(obs, h + 5 * 60_000L, Double.NaN, 3 * h), 0.0)
        assertEquals("gap bracketed by agreeing sides", 100.0, SolRateHistory8543.rateAt8543(obs, 20 * 60_000L, Double.NaN, 3 * h), 0.0)
        assertTrue("sides 101 -> 150 disagree: unknown", SolRateHistory8543.rateAt8543(obs, h + 30 * 60_000L, Double.NaN, 3 * h).isNaN())
        assertTrue("nothing observed: unknown", SolRateHistory8543.rateAt8543(emptyList(), h, Double.NaN, 2 * h).isNaN())
        assertEquals(0.02, SolRateHistory8543.MAX_GAP_DRIFT_8543, 0.0)
        assertTrue(src("engine/WalletManager.kt").contains("try { SolRateHistory8543.note8543(price, at8512) } catch (_: Throwable) {}"))
        val c = src("engine/truth/ChainHistoryMark8541.kt")
        assertTrue(c.contains("val rate = rateFor8543(pick.second)"))
        assertTrue("passing failures are retried", c.contains("private val TRANSIENT_8543 = setOf(\"NO_SOL_RATE\", \"NO_TRANSACTION\", \"NO_SIGNATURES\", \"WORKER_FAILURE\")"))
    }

    @Test fun aStaleSolRateAsksTheFetchChain() {
        val w = src("engine/WalletManager.kt")
        assertTrue(w.contains("if (fresh <= 0.0) refreshStaleSol8543(nowMs)"))
        assertTrue(w.contains("if (nowMs - last < 30_000L || !solRefreshAt8543.compareAndSet(last, nowMs)) return"))
        assertEquals(0.0, WalletManager.freshSolPrice8163(-1L), 0.0)
    }
}

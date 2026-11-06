package com.lifecyclebot.engine

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

    // superseded by V5.0.8544 — the 8543 shadow-view paper book is replaced by MemePaperTrader8544 (Aate8544MemePaperTraderTest).

    // 2. delayed provider recovery: the label batch no longer does pool discovery itself
    @Test fun theLabelBatchHandsPoolDiscoveryOff() {
        val l = src("engine/truth/ForwardReturnLabeler7731.kt")
        val rung = l.substringAfter("val missedChain8540 = batch.filter { it !in got }").substringBefore("got.putAll(chain8540)")
        // superseded by V5.0.8572 — "requestMints8543(ask8543)": the ask carries the rung's own 20 s freshness.
        assertTrue(rung.contains("OnChainDepth8038.requestMints8543(ask8543, CHAIN_MARK_MAX_AGE_MS_8540)"))
        assertTrue("no per-mint discovery on the batch thread", !rung.contains("OnChainDepth8038.requestMint8540(m)"))
        // superseded by V5.0.8572 — "fun requestMints8543(mints: List<String>)": it also takes the caller's freshness.
        assertTrue(src("engine/truth/OnChainDepth8038.kt").contains("fun requestMints8543(mints: List<String>, maxAgeMs: Long = FRESH_MS)"))
    }

    // 1 / 13. a past horizon converts at the SOL rate observed then; a gap needs agreeing sides; nothing invented
    @Test fun aPastHorizonUsesTheRateObservedThen() {
        val h = 3_600_000L
        // observation times are real clock times (> 0); time 0 is no observation
        val obs = listOf(h to 100.0, 2 * h to 101.0, 3 * h to 150.0)
        assertEquals(101.0, SolRateHistory8543.rateAt8543(obs, 2 * h + 5 * 60_000L, Double.NaN, 4 * h), 0.0)
        assertEquals("gap bracketed by agreeing sides", 100.0, SolRateHistory8543.rateAt8543(obs, h + 20 * 60_000L, Double.NaN, 4 * h), 0.0)
        assertTrue("sides 101 -> 150 disagree: unknown", SolRateHistory8543.rateAt8543(obs, 2 * h + 30 * 60_000L, Double.NaN, 4 * h).isNaN())
        assertTrue("time 0 is not an observation", SolRateHistory8543.rateAt8543(listOf(0L to 100.0), 60_000L, Double.NaN, 4 * h).isNaN())
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

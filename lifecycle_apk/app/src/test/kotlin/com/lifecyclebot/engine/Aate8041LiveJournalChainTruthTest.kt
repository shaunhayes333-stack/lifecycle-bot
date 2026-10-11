package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8041 — a live trade the chain proves is never excluded from the journal or learning on its % numbers. */
class Aate8041LiveJournalChainTruthTest {
    private val sig = "5".repeat(88)

    @Test fun chainProofIsTheProofState() {
        assertTrue(LearningPnlSanitizer.liveChainProven8041("live", "LIVE_FINALIZED", ""))
        assertTrue(LearningPnlSanitizer.liveChainProven8041("live", "LIVE_SIG_CONFIRMED", ""))
        // A row written before proof states: its transaction signature.
        assertTrue(LearningPnlSanitizer.liveChainProven8041("live", "", sig))
        // Sent but not confirmed: pending, not proven.
        assertFalse(LearningPnlSanitizer.liveChainProven8041("live", "LIVE_BROADCAST", sig))
        // Paper is never chain-proven.
        assertFalse(LearningPnlSanitizer.liveChainProven8041("paper", "LIVE_FINALIZED", sig))
        assertFalse(LearningPnlSanitizer.liveChainProven8041("live", "", "not-a-signature"))
    }

    @Test fun aRealRunnerAndAMoonbagSliceAreKept() {
        // Altai-like: +16,516% on chain.
        val runner = Trade(side = "SELL", mode = "live", sol = 4.5, price = 1.0, ts = 1L, pnlSol = 4.47, pnlPct = 16_516.0, sig = sig, proofState = "LIVE_FINALIZED", entryCostSol = 0.027, entryPriceSnapshot = 1.0e-5)
        assertTrue(LearningPnlSanitizer.inspectTrade(runner, emit = false).ok)
        // A 1,500x runner beyond the old +100,000% ceiling.
        val mega = runner.copy(pnlPct = 150_000.0, pnlSol = 40.0)
        assertTrue(LearningPnlSanitizer.inspectTrade(mega, emit = false).ok)
        // A moonbag set-aside slice: its SOL is a slice of the position, so its % never matched the whole cost.
        val slice = runner.copy(side = "PARTIAL_SELL", pnlPct = 300.0, pnlSol = 0.02)
        assertTrue(LearningPnlSanitizer.inspectTrade(slice, emit = false).ok)
        // The same slice as PAPER still faces every check.
        assertFalse(LearningPnlSanitizer.inspectTrade(slice.copy(mode = "paper", proofState = "PAPER_SIMULATED"), emit = false).ok)
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText().contains("LearningPnlSanitizer.liveChainProven8041(t)"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/V3JournalRecorder.kt").readText().contains("if (isPaper) 5000.0 else Double.MAX_VALUE"))
    }
}

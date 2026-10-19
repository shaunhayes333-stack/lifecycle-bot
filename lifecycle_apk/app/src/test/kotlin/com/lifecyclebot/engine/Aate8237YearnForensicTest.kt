package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EntryMcapReconcile8237
import com.lifecyclebot.engine.truth.RunnerCapture8017
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8237 — YEARN forensic: entry cap at the fill, runner peaks over one start, disproven marks, sell-balance proof. */
class Aate8237YearnForensicTest {
    @Test fun theEntryCapFollowsTheProvenFill() {
        // YEARN: snapshot cap $627,227 at a snapshot price 22x the fill -> cap re-stated at the fill's implied supply
        val snapPx = 0.000028554555 * 21.97
        val r = EntryMcapReconcile8237.reconciled8237(627_227.0, snapPx, 0.000028554555)
        assertEquals(627_227.0 / 21.97, r, 1.0)
        assertTrue(EntryMcapReconcile8237.disagreement8237(627_227.0, r) > 20.0)
        // consistent records are untouched; unreadable inputs keep the snapshot cap
        assertEquals(50_000.0, EntryMcapReconcile8237.reconciled8237(50_000.0, 0.00005, 0.00005), 1e-9)
        assertEquals(50_000.0, EntryMcapReconcile8237.reconciled8237(50_000.0, 0.0, 0.00005), 1e-9)
    }

    @Test fun aLaterObservationsPeakIsReadOverTheRunsOwnStart() {
        // observation B started at 1 and saw +2000% (price 21); the run started at 10 -> +110%, not +2000%
        assertEquals(110.0, RunnerCapture8017.rebasedPeakPct8237(2000.0, 1.0, 10.0), 1e-9)
        assertEquals(2000.0, RunnerCapture8017.rebasedPeakPct8237(2000.0, 1.0, 1.0), 1e-9)
        assertEquals(2000.0, RunnerCapture8017.rebasedPeakPct8237(2000.0, 0.0, 10.0), 1e-9)
    }

    @Test fun aPeakOutsideTheHoldingWindowIsNotTheBotsRun() {
        val close = 1_000_000L
        assertTrue(RunnerCapture8017.peakInHold8237(close - 60_000L, close, 300_000L))
        assertFalse(RunnerCapture8017.peakInHold8237(close - 400_000L, close, 300_000L))   // before entry
        assertFalse(RunnerCapture8017.peakInHold8237(close + 60_000L, close, 300_000L))    // after close
        assertFalse(RunnerCapture8017.peakInHold8237(0L, close, 300_000L))                 // missing clock is UNKNOWN, never captured
    }

    @Test fun theExecutableQuoteDisprovesAMarkFarAboveIt() {
        // 114,643 tokens (6 dp) quoted at 0.0242 SOL with SOL at $105 -> ~$0.0000222 per token
        val q = ProfitExitTruth8163.quoteImpliedUsd8237(24_198_000L, 114_643_000_000L, 6, 105.0)
        assertEquals(0.0000222, q, 0.000001)
        assertTrue(ProfitExitTruth8163.markDisproven8237(0.000028554555 * 21.0, q))   // the +2000% mark
        assertFalse(ProfitExitTruth8163.markDisproven8237(0.0000295, q))               // +3.5%: a mark the quote agrees with
        assertTrue(ProfitExitTruth8163.quoteImpliedUsd8237(0L, 1L, 6, 105.0).isNaN())
    }

    @Test fun aBalanceProofIsReusedOnlyWhileFreshAndUnspent() {
        assertTrue(SellBalanceProof8237.usable8237(1_000L, 3L, 3_000L, 3L))
        assertFalse(SellBalanceProof8237.usable8237(1_000L, 3L, 6_000L, 3L))   // stale
        assertFalse(SellBalanceProof8237.usable8237(1_000L, 3L, 2_000L, 4L))   // a sell was registered since
    }

    @Test fun theOriginalExitReasonSurvivesTheOutcomeLabel() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CloseOutcomeLabelSanitizer.kt").readText()
        assertTrue(src.contains("originalExitReason = row.originalExitReason.ifBlank { row.reason }"))
        val exe = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(exe.contains("EntryMcapReconcile8237.reconciled8237(entryMarketSnapshot.marketCapUsd, entryMarketSnapshot.priceUsd, verifiedEntryPrice)"))
        assertTrue(exe.contains("entryMcap = com.lifecyclebot.engine.truth.EntryMcapReconcile8237.reconciled8237(ts.position.entryMcap, oldEntry, proofEntryUsd)"))
    }
}

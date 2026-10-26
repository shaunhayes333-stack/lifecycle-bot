package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.truth.ExecutionSpineAcceptanceWindow6647
import com.lifecyclebot.engine.truth.Rehearsal8339
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8339 — the 5.0.8336 runtime smoke: no acceptance verdict for 300 s (the single-shot closer's 8322 in-flight wait
 * had no later tick), paperBuyTickets=0 (rehearsal ran the live wallet door against a paper book), and a 22 s bootstrap
 * stall inside the cross-asset history repair's per-row hive upload.
 */
class Aate8339SmokeTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private fun repairRow(ts: Long) = Trade(
        side = "SELL", mode = "paper", sol = 0.0011, price = 0.132, ts = ts,
        reason = HistoryRepairHive8339.REPAIR_REASON_8339, mint = "PersistedMint$ts", positionId = "persisted-position-$ts",
    )

    // ── 1. bounded in-flight wait ──

    @Test fun firstWaitingTickStartsTheClockAndWaits() {
        val (since, wait) = ExecutionSpineAcceptanceWindow6647.inFlightWaitDecision8339(0L, 1_000_000L)
        assertEquals(1_000_000L, since)
        assertTrue(wait)
    }

    @Test fun laterTicksKeepTheFirstTimestampUntilTheGraceIsSpent() {
        val grace = ExecutionSpineAcceptanceWindow6647.IN_FLIGHT_WAIT_GRACE_MS_8339
        val (since1, wait1) = ExecutionSpineAcceptanceWindow6647.inFlightWaitDecision8339(1_000_000L, 1_000_000L + grace - 1L)
        assertEquals(1_000_000L, since1)
        assertTrue(wait1)
        val (since2, wait2) = ExecutionSpineAcceptanceWindow6647.inFlightWaitDecision8339(1_000_000L, 1_000_000L + grace)
        assertEquals(1_000_000L, since2)
        assertFalse("grace elapsed: evaluate now", wait2)
    }

    @Test fun aWaitStampFromTheFutureOrZeroIsRestartedNotTrusted() {
        val (since, wait) = ExecutionSpineAcceptanceWindow6647.inFlightWaitDecision8339(5_000_000L, 1_000_000L)
        assertEquals(1_000_000L, since)
        assertTrue(wait)
        assertTrue(ExecutionSpineAcceptanceWindow6647.IN_FLIGHT_RETRY_MS_8339 < ExecutionSpineAcceptanceWindow6647.IN_FLIGHT_WAIT_GRACE_MS_8339)
    }

    @Test fun closerWaitIsBoundedAndReTicked() {
        val s = src("engine/truth/ExecutionSpineAcceptance6647.kt")
        assertTrue(s.contains("val wait8339 = inFlightWaitDecision8339(inFlightWaitSinceMs8339.get(), nowMs)"))
        assertTrue(s.contains("if (wait8339.second) {"))
        assertTrue(s.contains("scheduleRetry8339(start)"))
        assertTrue(s.contains("EXECUTION_SPINE_IN_FLIGHT_WAIT_BOUNDED_8339"))
        assertTrue(s.contains("EXECUTION_SPINE_CLOSE_WAITS_IN_FLIGHT_RECEIPT_8322"))
        // Never healed: the unreconciled deltas still read NaN / ONE after the bounded wait.
        assertTrue(s.contains("if (forensic?.reconciled == true && value != null) value else Double.NaN"))
        assertTrue(s.contains("quantityDeltaRaw = if (forensic?.reconciled == true) forensic.quantityRaw else java.math.BigInteger.ONE"))
        // The retry closes the SAME window in the SAME runtime generation only.
        assertTrue(s.contains("if (baseline === start && sameGeneration) {"))
        assertTrue(s.contains("inFlightWaitSinceMs8339.set(0L)  // V5.0.8339 — a new window starts with no in-flight wait"))
        // The 6668 scheduler is still single-shot (BotService untouched): the re-tick lives in the window.
        val bs = src("engine/BotService.kt")
        assertTrue(bs.contains("acceptanceWindowFuture6668 = acceptanceWindowExecutor6668.schedule({"))
    }

    // ── 2. rehearsal: live wallet door / shadow book ──

    @Test fun walletDoorBindsLiveOnlyAndShadowFollowsTheRuntimeBook() {
        assertTrue(Rehearsal8339.walletDoorBinds8339(true))
        assertFalse(Rehearsal8339.walletDoorBinds8339(false))
        assertTrue(Rehearsal8339.shadowPaper8339(true))
        assertFalse(Rehearsal8339.shadowPaper8339(false))
    }

    @Test fun rehearsalHookPoints() {
        val gate=src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(gate.contains("canonicalRefusal8392(ts, l, paper, nowMs, candidateVersion, candidateAdmission)"))
        assertTrue(gate.contains("FdgGate8365.inContext8365(ts, l, basis, paper, shadowOnly8393)"))
        assertTrue(gate.contains("EvidenceHold8336.refusal8336(ts, l, false, nowMs, candidateVersion8397 = candidateVersion)"))
        assertTrue(gate.contains("if (paper && final == null) Rehearsal8339.notePaperAdmit8342"))
        assertFalse(gate.contains("entryRefusal8114"))
    }

    // ── 3. repair rows off the init path ──

    @Test fun repairRowsAreQueuedAndDrainedThroughTheSameUpload() {
        assertTrue(HistoryRepairHive8339.isRepairRow8339("CROSS_ASSET_HISTORY_REPAIR_6660"))
        assertFalse(HistoryRepairHive8339.isRepairRow8339("TAKE_PROFIT"))
        assertFalse(HistoryRepairHive8339.isRepairRow8339(null))
        val before = HistoryRepairHive8339.queued8339()
        assertFalse("a normal row is uploaded by the caller", HistoryRepairHive8339.enqueue8339(repairRow(1L).copy(reason = "STOP_LOSS")))
        assertTrue(HistoryRepairHive8339.enqueue8339(repairRow(2L)))
        assertTrue(HistoryRepairHive8339.enqueue8339(repairRow(3L)))
        assertEquals(before + 2, HistoryRepairHive8339.queued8339())
        val seen = mutableListOf<Long>()
        val n = HistoryRepairHive8339.drain8339 { seen.add(it.ts) }
        assertTrue(n >= 2)
        assertTrue(seen.containsAll(listOf(2L, 3L)))
        assertEquals(0, HistoryRepairHive8339.queued8339())
        assertEquals(0, HistoryRepairHive8339.drain8339 { seen.add(it.ts) })
    }

    @Test fun recordTradeHandsRepairRowsToTheDeferralAndTheRepairStillJournals() {
        val ths = src("engine/TradeHistoryStore.kt")
        assertTrue(ths.contains("try { if (!HistoryRepairHive8339.deferIfRepairRow8339(tradeToStore) { uploadCollectiveJournalRow(it) }) uploadCollectiveJournalRow(tradeToStore) } catch (_: Throwable) {}"))
        val repair = src("engine/truth/CanonicalPaperTransaction6486.kt")
        assertTrue(repair.contains("reason = \"CROSS_ASSET_HISTORY_REPAIR_6660\", pnlSol = grossPnl,"))
        assertTrue(repair.contains("TradeHistoryStore.recordTrade(Trade("))
        assertEquals("CROSS_ASSET_HISTORY_REPAIR_6660", HistoryRepairHive8339.REPAIR_REASON_8339)
        // The drain runs after bootstrap (the smoke's crypto-alt init timed out at ~41 s) and holds the 4 096-row seed whole.
        assertTrue(HistoryRepairHive8339.DRAIN_DELAY_MS_8339 >= 60_000L)
        assertTrue(HistoryRepairHive8339.MAX_QUEUE_8339 >= 4_096)
        assertEquals("ENTRY8114_REHEARSAL_WALLET_DOOR_SKIPPED_8339", Rehearsal8339.WALLET_DOOR_SKIPPED_8339)
    }
}

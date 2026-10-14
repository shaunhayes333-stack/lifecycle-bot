package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import com.lifecyclebot.engine.truth.LiveTruth8064
import com.lifecyclebot.engine.truth.EntryStrategySnapshot6450
import org.junit.Assert.*
import org.junit.Test

/**
 * V5.0.8092 — behavioural tests for the confirmed-fill attribution and exploration-budget repairs:
 *  1. a close files under the EXECUTED position's immutable entry strategy, not a later admit of the same mint;
 *  2. failed/deferred cold-start attempts release the 3-mint/6h budget; only confirmed fills consume it;
 *  3. a runner ticket confirms only against a canonical LIVE position with positive cost.
 */
class Aate8092AttributionBudgetTest {

    private fun entrySnapshot(positionId: String, mint: String, setup: String): EntryStrategySnapshot6450.Snapshot =
        EntryStrategySnapshot6450.Snapshot(
            positionId = positionId, mint = mint, entryLane = "MOONSHOT",
            candidateVersion8090 = 8092L, mode8090 = "LIVE", entrySetup = setup,
            entryStrategyPid = "test", entryRiskProfile = "test", entryExitProfile = "test",
            entrySource = "test", entryScore = 60, entryLiquiditySol = 12.0,
            entryMarketCapUsd = 45_000.0, entryTimestampMs = System.currentTimeMillis(),
            entryThresholdSnapshot = "test", entryTradeType = "BREAKOUT", entryStyle = "DIAMOND_HANDS_RUNNER",
            entryTactic = "MOMENTUM",
        )

    @Test fun closeFilesUnderTheExecutedPositionsImmutableSetupNotTheLatestAdmit() {
        val mint = "attribSnapshot8092"
        // A later admit of the same mint claims a DIFFERENT setup than the executed fill.
        LiveTruth8064.noteLiveAdmit8083(mint, "MOONSHOT", "LATER_ADMIT_SETUP", System.currentTimeMillis())
        assertTrue(EntryStrategySnapshot6450.setEntry(entrySnapshot("pos8092snap", mint, "EXECUTED_SETUP")))
        LiveTruth8064.recordSetup8083(mint, "MOONSHOT", -3.0, System.currentTimeMillis(), "pos8092snap")
        assertTrue("the close must be filed under the executed position's frozen setup",
            LiveTruth8064.statusLine().contains("MOONSHOT|EXECUTED_SETUP"))
        assertFalse("a later mint-keyed admit must not own the close's setup book",
            LiveTruth8064.statusLine().contains("MOONSHOT|LATER_ADMIT_SETUP"))
    }

    @Test fun missingEntrySnapshotFallsBackToTheAdmitBook() {
        val mint = "attribPending8092"
        LiveTruth8064.noteLiveAdmit8083(mint, "MOONSHOT", "PENDING_FALLBACK_SETUP", System.currentTimeMillis())
        LiveTruth8064.recordSetup8083(mint, "MOONSHOT", 2.0, System.currentTimeMillis(), "")
        assertTrue(LiveTruth8064.statusLine().contains("MOONSHOT|PENDING_FALLBACK_SETUP"))
    }

    @Test fun snapshotOfADifferentMintNeverReattributesTheClose() {
        val mint = "attribGuard8092"
        assertTrue(EntryStrategySnapshot6450.setEntry(entrySnapshot("pos8092other", "differentMint8092", "OTHER_SETUP")))
        LiveTruth8064.noteLiveAdmit8083(mint, "MOONSHOT", "ADMIT_SETUP_GUARD", System.currentTimeMillis())
        LiveTruth8064.recordSetup8083(mint, "MOONSHOT", 1.0, System.currentTimeMillis(), "pos8092other")
        assertFalse("a position snapshot of another mint must not be used",
            LiveTruth8064.statusLine().contains("MOONSHOT|OTHER_SETUP"))
    }

    @Test fun failedColdStartAttemptsReleaseAndOnlyConfirmedFillsConsumeTheBudget() {
        val now = System.currentTimeMillis()
        val gate = LiveEdgeGate7877
        gate.coldPending8092.clear(); gate.coldAdmits8080.clear()
        try {
            gate.coldPending8092["stale8092"] = now - 11L * 60_000L
            gate.coldPendingSweep8092(now)
            assertFalse("a stale unconfirmed attempt must release its mint", gate.coldPending8092.containsKey("stale8092"))
            assertTrue(gate.coldBudgetOpen8080(gate.coldAdmits8080, "fresh8092", now))

            gate.coldPending8092["fresh8092"] = now
            gate.coldStartConfirm8092("fresh8092", now)
            assertTrue("the confirmed fill consumes a budget slot", gate.coldAdmits8080.containsKey("fresh8092"))
            assertFalse("the promoted attempt leaves the pending book", gate.coldPending8092.containsKey("fresh8092"))
            assertFalse("the 3-mint budget is now spent for a fourth mint",
                gate.coldBudgetOpen8080(mapOf("a" to now, "b" to now, "fresh8092" to now), "fourth8092", now))

            gate.coldStartConfirm8092("neverAdmitted8092", now)   // no pending attempt: no-op
            assertFalse(gate.coldAdmits8080.containsKey("neverAdmitted8092"))
        } finally {
            gate.coldPending8092.clear(); gate.coldAdmits8080.clear()
        }
    }

    @Test fun runnerTicketConfirmsOnlyAgainstACanonicalLivePosition() {
        val before = RunnerTicket8073.confirmed8092.get()
        // No canonical position exists for this id: the confirmation guard must refuse.
        RunnerTicket8073.confirm8092("nonexistent8092", "ticketGuard8092")
        assertEquals("a guard refusal must not count a confirmed ticket", before, RunnerTicket8073.confirmed8092.get())
        assertTrue(RunnerTicket8073.statusLine().contains("confirmed="))
    }
}

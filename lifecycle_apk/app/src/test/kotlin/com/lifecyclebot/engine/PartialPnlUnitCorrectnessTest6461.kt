package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.PaperAccountLedger6430
import com.lifecyclebot.engine.truth.PaperAccountReplay6461
import com.lifecyclebot.engine.truth.PartialSellUnitTypes6461
import com.lifecyclebot.engine.truth.PendingEntryProjectionGuard6461
import com.lifecyclebot.engine.truth.RiskExitPriorityDomain6461
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * V5.0.6461 §P0 — HARD CI ASSERTIONS FOR PARTIAL PNL UNIT + PENDING_ENTRY
 *                 + PAPER REPLAY + RISK DOMAIN.
 *
 * Locks the invariants the 6457 dump called out:
 *   §P0-#1 Partial PnL unit isolation — SOL cannot be mixed with %
 *   §P0-#2 PENDING_ENTRY → OPEN collapse — pending never counts as open
 *   §P0-#3 Paper account replay — economic events reconstruct cash
 *   §P0-#4 Risk exit priority domain — HIGH-latency alerts emit
 */
class PartialPnlUnitCorrectnessTest6461 {

    // ─── §P0-#1  PARTIAL PNL UNIT ISOLATION ─────────────────────────────

    @Test
    fun `RealizedSol and ReturnPct are compile-time distinct types`() {
        val sol = PartialSellUnitTypes6461.RealizedSol(0.05)
        val pct = PartialSellUnitTypes6461.ReturnPct(5.0)
        // If these were the same type this test could accidentally compile
        // a cross-assign. That is a compile-time property; the runtime
        // assertion here just proves both values coexist without confusion.
        assertEquals(0.05, sol.sol, 1e-9)
        assertEquals(5.0, pct.pct, 1e-9)
        assertEquals(0.05, pct.toRatio().ratio, 1e-9)
    }

    @Test
    fun `computeRealizedSol produces net-of-fee SOL delta`() {
        // proceeds=0.12, cost=0.10, fee=0.005 → realized = 0.015
        val r = PartialSellUnitTypes6461.computeRealizedSol(
            netProceedsSol = 0.12, costBasisSoldSol = 0.10, feesSol = 0.005,
        )
        assertEquals(0.015, r.sol, 1e-9)
    }

    @Test
    fun `assertSolPlausible clamps a percent-into-SOL leak to zero`() {
        PartialSellUnitTypes6461.resetForTest()
        // A 5.0% loss accidentally cast to SOL would be −5.0 SOL — far
        // above the 30 SOL plausibility bound is −500 (a 500% cast).
        // Both must clamp to 0. The 30 SOL bound treats -5.0 as valid
        // (a real 5 SOL loss is within one moonshot outcome), so we use
        // a clearly-corrupt −500.0 for the corruption test.
        val corrupt = PartialSellUnitTypes6461.assertSolPlausible(-500.0, "test_fi4fam")
        assertEquals(0.0, corrupt, 1e-9)
        // A legitimate value is passed through.
        val ok = PartialSellUnitTypes6461.assertSolPlausible(0.087, "test_ok")
        assertEquals(0.087, ok, 1e-9)
    }

    @Test
    fun `PaperAccountLedger6430 onSell firewalled against Fi4FaM values`() {
        PaperAccountLedger6430.resetForTest()
        PaperAccountLedger6430.initialize(startingCashSol = 1.0)
        PaperAccountLedger6430.onBuy(costSol = 0.5, feeSol = 0.001)
        // Simulate the Fi4FaM bug — someone passes a "%" (say 40.0) into
        // grossProceedsSol. The firewall must clamp it to 0.
        PaperAccountLedger6430.onSell(grossProceedsSol = 40.0, costBasisSoldSol = 0.5, feeSol = 0.0)
        // Cash should NOT jump by +40 SOL; it should reflect the clamped 0.
        // openCost drops by 0.5 (basis is plausible), realized becomes
        // 0 - 0.5 = -0.5 SOL because the clamped gross is 0.
        assertTrue(
            "cash must not absorb Fi4FaM injection, got=${PaperAccountLedger6430.cashSol()}",
            PaperAccountLedger6430.cashSol() < 1.0,
        )
    }

    // ─── §P0-#2  PENDING_ENTRY PROJECTION ────────────────────────────────

    @Test
    fun `pendingEntryPositions6461 returns pending rows and excludes them from openPositions`() {
        // Establish a clean-slate scenario. We cannot call resetForTest on
        // CanonicalPositionAuthority6441 (not exposed) — instead use a
        // guaranteed-unique positionId so we operate in isolation.
        val pid = "PID_6461_TEST_${System.nanoTime()}"
        val res = CanonicalPositionAuthority6441.openPosition(
            idempotencyKey = "IDEM_6461_TEST_${System.nanoTime()}",
            positionId = pid, mint = "MINT_6461_TEST", symbol = "T",
            lane = "TEST", runId = "R", entryCostSol = 0.0,
            openedQtyRaw = BigInteger.ZERO, tokenDecimals = 6, feesSol = 0.0,
            paperMode = false, // avoid touching paper cash for this test
        )
        assertEquals(CanonicalPositionAuthority6441.MutateResult.APPLIED, res)
        val pos = CanonicalPositionAuthority6441.getPosition(pid)
        assertNotEquals(null, pos)
        assertEquals(CanonicalPositionAuthority6441.Lifecycle.PENDING_ENTRY, pos!!.lifecycle)

        // Assert that openPositions() does NOT include this PENDING_ENTRY.
        val openIds = CanonicalPositionAuthority6441.openPositions().map { it.positionId }
        assertTrue("PENDING_ENTRY must not appear in openPositions", pid !in openIds)

        // Assert pendingEntryPositions6461 DOES include it.
        val pendingIds = CanonicalPositionAuthority6441.pendingEntryPositions6461().map { it.positionId }
        assertTrue("PENDING_ENTRY must appear in pendingEntryPositions6461", pid in pendingIds)

        // Defensive audit — no leak.
        assertEquals(0, PendingEntryProjectionGuard6461.assertNotInOpenSet())
    }

    @Test
    fun `sweepStalePendingEntries quarantines pending rows past TTL`() {
        val pid = "PID_6461_SWEEP_${System.nanoTime()}"
        CanonicalPositionAuthority6441.openPosition(
            idempotencyKey = "IDEM_6461_SWEEP_${System.nanoTime()}",
            positionId = pid, mint = "MINT_6461_SWEEP", symbol = "T",
            lane = "TEST", runId = "R", entryCostSol = 0.0,
            openedQtyRaw = BigInteger.ZERO, tokenDecimals = 6, feesSol = 0.0,
            paperMode = false,
        )
        // Sweep with a very small ttlMs so the just-created row is stale.
        Thread.sleep(20)
        val cancelled = PendingEntryProjectionGuard6461.sweepStalePendingEntries(ttlMs = 1L)
        assertTrue("expected >=1 cancellation, got $cancelled", cancelled >= 1)
        val pos = CanonicalPositionAuthority6441.getPosition(pid)
        assertEquals(CanonicalPositionAuthority6441.Lifecycle.QUARANTINED, pos?.lifecycle)
        assertEquals("PENDING_ENTRY_TTL_CANCELLED_6461", pos?.quarantineReason)
    }

    // ─── §P0-#3  PAPER ACCOUNT REPLAY ────────────────────────────────────

    @Test
    fun `replay on empty ledger returns starting cash and zero deltas`() {
        PaperAccountReplay6461.resetForTest()
        val snap = PaperAccountReplay6461.replay(startingCashSol = 5.0)
        assertEquals(5.0, snap.cashSol, 1e-9)
        assertEquals(0.0, snap.openCostBasisSol, 1e-9)
        assertEquals(0.0, snap.realizedPnlSol, 1e-9)
        assertEquals(0.0, snap.feesSol, 1e-9)
        assertEquals(0, snap.buyCount)
        assertEquals(0, snap.partialSellCount)
        assertEquals(0, snap.fullSellCount)
    }

    // ─── §P0-#4  RISK EXIT PRIORITY DOMAIN ──────────────────────────────

    @Test
    fun `high-priority block records latency and does not throw`() {
        RiskExitPriorityDomain6461.resetForTest()
        RiskExitPriorityDomain6461.runHighPriority("test_high") { /* trivial */ }
        val status = RiskExitPriorityDomain6461.statusLine()
        assertTrue("status must include highTicks=1: $status", status.contains("highTicks=1"))
    }

    @Test
    fun `high-priority latency exceeding threshold emits alert`() {
        RiskExitPriorityDomain6461.resetForTest()
        // Directly record synthetic latency to avoid a real sleep in CI.
        RiskExitPriorityDomain6461.recordHighLatency("synthetic_slow", 1500L)
        val status = RiskExitPriorityDomain6461.statusLine()
        assertTrue(
            "expected highLatencyAlerts=1 in: $status",
            status.contains("highLatencyAlerts=1"),
        )
    }
}

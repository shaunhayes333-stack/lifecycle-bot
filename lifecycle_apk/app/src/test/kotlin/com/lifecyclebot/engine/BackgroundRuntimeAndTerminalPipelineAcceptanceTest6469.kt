package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.BackgroundTradingAuthority6469
import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464
import com.lifecyclebot.engine.truth.CanonicalMintOccupancyRegistry6464
import com.lifecyclebot.engine.truth.CanonicalPaperTerminalBridge6469
import com.lifecyclebot.engine.truth.CapitalConservationTracer6469
import com.lifecyclebot.engine.truth.EconomicEventSchema6464
import com.lifecyclebot.engine.truth.MaintenanceBudgetGovernor6469
import com.lifecyclebot.engine.truth.PaperAccountLedger6430
import com.lifecyclebot.engine.truth.TerminalMutationAuthority6466
import com.lifecyclebot.engine.truth.TerminalSellIdempotency6464
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * V5.0.6469 — HARD CI ASSERTIONS for §P0/§P1 items.
 *
 * §P0 Canonical paper terminal bridge — every paper sell fans out to
 *      EconomicEventSchema6464 + FinalizedBus + TerminalIdempotency +
 *      TerminalMutationAuthority + Occupancy.
 * §P0 Background runtime authority — UI-lifecycle callers rejected;
 *      service callers accepted; forensic counters wired.
 * §P0 Capital conservation tracer — non-clamping, records identity delta.
 * §P1 Maintenance budget governor — coalesces + cools down + budgets.
 */
class BackgroundRuntimeAndTerminalPipelineAcceptanceTest6469 {

    // ─── §P0 canonical paper terminal bridge ────────────────────────────
    @Test
    fun `bridge emits full canonical fanout on first paper terminal sell`() {
        EconomicEventSchema6464.resetForTest()
        TerminalSellIdempotency6464.resetForTest()
        TerminalMutationAuthority6466.resetForTest()
        CanonicalMintOccupancyRegistry6464.resetForTest()
        CanonicalFinalizedTradeBus6464.resetForTest()
        CanonicalPaperTerminalBridge6469.resetForTest()
        CapitalConservationTracer6469.resetForTest()

        CanonicalMintOccupancyRegistry6464.markOpen("paper", "MINT_ONE", "T1", "test")
        val res = CanonicalPaperTerminalBridge6469.emitCanonicalFanout(
            positionId = "MINT_ONE", mint = "MINT_ONE", symbol = "T1",
            generation = 1L, sellSig = "paper_sig_1",
            soldQtyRaw = BigInteger.valueOf(1_000_000L),
            preRemainingRaw = BigInteger.valueOf(1_000_000L),
            preRemainingCostBasisSol = 1.0,
            grossProceedsSol = 1.5, soldCostBasisSol = 1.0, feesSol = 0.01,
            lane = "MEME", exitReason = "MOONSHOT", terminal = true,
        )
        assertTrue(res.applied)
        assertTrue("terminalClaimed", res.terminalClaimed)
        assertTrue("busPublished", res.busPublished)
        assertFalse("occupancy released", CanonicalMintOccupancyRegistry6464.isOpen("paper", "MINT_ONE"))
        val sched = EconomicEventSchema6464.snapshot()
        assertTrue("economicSchema recorded a SELL", sched.any { it is EconomicEventSchema6464.Event.Sell })
    }

    @Test
    fun `bridge is idempotent on duplicate sellSig`() {
        EconomicEventSchema6464.resetForTest()
        TerminalSellIdempotency6464.resetForTest()
        TerminalMutationAuthority6466.resetForTest()
        CanonicalMintOccupancyRegistry6464.resetForTest()
        CanonicalFinalizedTradeBus6464.resetForTest()
        CanonicalPaperTerminalBridge6469.resetForTest()

        val a = CanonicalPaperTerminalBridge6469.emitCanonicalFanout(
            positionId = "MINT_DUP", mint = "MINT_DUP", symbol = "T",
            generation = 5L, sellSig = "paper_sig_dup",
            soldQtyRaw = BigInteger.valueOf(100L),
            preRemainingRaw = BigInteger.valueOf(100L),
            preRemainingCostBasisSol = 0.5,
            grossProceedsSol = 0.8, soldCostBasisSol = 0.5, feesSol = 0.005,
            lane = "MEME", exitReason = "PARTIAL_TP", terminal = false,
        )
        val b = CanonicalPaperTerminalBridge6469.emitCanonicalFanout(
            positionId = "MINT_DUP", mint = "MINT_DUP", symbol = "T",
            generation = 5L, sellSig = "paper_sig_dup",
            soldQtyRaw = BigInteger.valueOf(100L),
            preRemainingRaw = BigInteger.valueOf(100L),
            preRemainingCostBasisSol = 0.5,
            grossProceedsSol = 0.8, soldCostBasisSol = 0.5, feesSol = 0.005,
            lane = "MEME", exitReason = "PARTIAL_TP", terminal = false,
        )
        assertTrue("first grants", a.terminalClaimed)
        assertFalse("second is duplicate", b.terminalClaimed)
        assertFalse("second does not publish again", b.busPublished)
    }

    // ─── §P0 background runtime authority ───────────────────────────────
    @Test
    fun `UI-lifecycle caller cannot mutate runtime active`() {
        BackgroundTradingAuthority6469.resetForTest()
        val accepted = BackgroundTradingAuthority6469.setRuntimeActive(true, "BotService.startLoop")
        assertTrue(accepted)
        assertTrue(BackgroundTradingAuthority6469.isRuntimeActive())
        val rejected = BackgroundTradingAuthority6469.setRuntimeActive(false, "MainActivity.onStop")
        assertFalse("UI caller rejected", rejected)
        assertTrue("state preserved", BackgroundTradingAuthority6469.isRuntimeActive())
    }

    @Test
    fun `UI-lifecycle caller cannot register runtime job`() {
        BackgroundTradingAuthority6469.resetForTest()
        val serviceId = BackgroundTradingAuthority6469.registerRuntimeJob("BotService.launchBotLoop")
        assertEquals(1L, serviceId)
        val rejected = BackgroundTradingAuthority6469.registerRuntimeJob("Fragment.onPause")
        assertEquals("UI caller cannot bump jobId", 1L, rejected)
    }

    @Test
    fun `screen-off tick increments while service reports it`() {
        BackgroundTradingAuthority6469.resetForTest()
        repeat(3) { BackgroundTradingAuthority6469.onScreenOffTick() }
        BackgroundTradingAuthority6469.onUiAbsentTick()
        assertEquals(3L, BackgroundTradingAuthority6469.screenOffTicks())
        assertEquals(1L, BackgroundTradingAuthority6469.uiAbsentTicks())
    }

    @Test
    fun `service replacing a runtime job bumps jobReplacements`() {
        BackgroundTradingAuthority6469.resetForTest()
        BackgroundTradingAuthority6469.registerRuntimeJob("BotService.first")
        assertEquals(0L, BackgroundTradingAuthority6469.jobReplacements())
        BackgroundTradingAuthority6469.registerRuntimeJob("BotService.rescueRelaunch")
        assertEquals(1L, BackgroundTradingAuthority6469.jobReplacements())
    }

    // ─── §P0 capital conservation tracer ────────────────────────────────
    @Test
    fun `capital conservation tracer publishes delta only when identity breaks`() {
        CapitalConservationTracer6469.resetForTest()
        // baseline + realized = cash + openCost   ⇒ 10 + 0 = 10 + 0 ⇒ delta 0
        val clean = CapitalConservationTracer6469.reconcile(
            baselineSol = 10.0, cashSol = 10.0, openCostBasisSol = 0.0, realizedFromLedger = 0.0,
        )
        assertEquals(0.0, clean, 1e-9)
        assertEquals(0L, CapitalConservationTracer6469.violationCount())
        // Break the identity — cash + openCost > baseline + realized ⇒ +1.5
        val broken = CapitalConservationTracer6469.reconcile(
            baselineSol = 10.0, cashSol = 10.0, openCostBasisSol = 1.5, realizedFromLedger = 0.0,
        )
        assertEquals(1.5, broken, 1e-9)
        assertEquals(1L, CapitalConservationTracer6469.violationCount())
    }

    @Test
    fun `capital conservation tracer does not clamp the delta`() {
        CapitalConservationTracer6469.resetForTest()
        val d = CapitalConservationTracer6469.reconcile(
            baselineSol = 10.0, cashSol = 8.0, openCostBasisSol = 0.5, realizedFromLedger = 0.0,
        )
        assertEquals(-1.5, d, 1e-9)
    }

    // ─── §P1 maintenance budget governor ────────────────────────────────
    @Test
    fun `governor coalesces duplicate work while lease is held`() {
        MaintenanceBudgetGovernor6469.resetForTest()
        val first = MaintenanceBudgetGovernor6469.tryAcquire("lab_universe_tick")
        assertTrue(first is MaintenanceBudgetGovernor6469.Decision.Run)
        val second = MaintenanceBudgetGovernor6469.tryAcquire("lab_universe_tick")
        assertEquals(MaintenanceBudgetGovernor6469.Decision.Coalesced, second)
    }

    @Test
    fun `governor cools down after a run`() {
        MaintenanceBudgetGovernor6469.resetForTest()
        val r = MaintenanceBudgetGovernor6469.tryAcquire("probation_expiry")
        assertTrue(r is MaintenanceBudgetGovernor6469.Decision.Run)
        MaintenanceBudgetGovernor6469.release("probation_expiry")
        val cold = MaintenanceBudgetGovernor6469.tryAcquire("probation_expiry")
        assertEquals(MaintenanceBudgetGovernor6469.Decision.CoolingDown, cold)
    }

    @Test
    fun `governor withBudget executes block and releases`() {
        MaintenanceBudgetGovernor6469.resetForTest()
        var ran = 0
        MaintenanceBudgetGovernor6469.withBudget("hot_watchlist_rebalance", onCoalesced = { ran = -1 }) { _ ->
            ran = 1
        }
        assertEquals(1, ran)
        // Second acquire hits cooldown
        MaintenanceBudgetGovernor6469.withBudget("hot_watchlist_rebalance", onCoalesced = { ran = 2 }) { _ ->
            ran = 3
        }
        assertEquals(2, ran)
    }

    @Test
    fun `governor statusLine surfaces counters`() {
        MaintenanceBudgetGovernor6469.resetForTest()
        MaintenanceBudgetGovernor6469.tryAcquire("token_map_hydration")
        MaintenanceBudgetGovernor6469.tryAcquire("token_map_hydration")
        val s = MaintenanceBudgetGovernor6469.statusLine()
        assertTrue(s.contains("acquires="))
        assertTrue(s.contains("coalesced="))
    }
}

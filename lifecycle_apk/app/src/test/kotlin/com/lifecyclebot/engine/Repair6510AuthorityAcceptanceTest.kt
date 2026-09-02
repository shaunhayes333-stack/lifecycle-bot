package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.*
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class Repair6510AuthorityAcceptanceTest {
    private fun resetPaper() {
        FillLotLedger6504.setTestMemoryMode6641(true)
        PaperAccountLedger6430.resetForTest(); PaperAccountLedger6430.initialize(10.0)
        CanonicalPositionAuthority6441.resetForTest(); CanonicalLotQuantity6464.resetForTest()
        EconomicEventSchema6464.resetForTest(); SellQtyBoundaryClamp6427.resetForTest()
        PositionStateLedger6454.resetForTest(); RootCauseIncidentLifecycle6510.resetForTest()
    }

    @Test fun fdg_snapshot_keeps_project_sniper_lane_when_source_is_pump_scanner_and_signal_mutates() {
        ExecutableOpenGate.resetForTests(); ExecutionDecisionSnapshot6510.resetForTest()
        val mint = "LANE6510_${System.nanoTime()}"; val cv = 77L
        ExecutableOpenGate.recordFdg(mint, "PS", "PROJECT_SNIPER", true, null,
            signal = "BUY", rugScore = 90, safetyTier = "SAFE", liquidityUsd = 5000.0,
            preFdgVerdict = "BUY", candidateVersion = cv, entryScore = 88)
        val snap = requireNotNull(ExecutionDecisionSnapshot6510.get(mint, cv, "PROJECT_SNIPER"))
        assertEquals("PROJECT_SNIPER", snap.executionLane); assertEquals("BUY", snap.verdict); assertEquals(cv, snap.candidateVersion)
        val id = requireNotNull(TradeIdentityManager.get(mint))
        assertEquals("PROJECT_SNIPER", id.executionLane); assertNotEquals("PUMP_FUN_NEW,SCANNER_DIRECT", id.executionLane)
        assertNotNull(ExecutionDecisionSnapshot6510.consume(mint, cv, "BUY", "PROJECT_SNIPER"))
    }

    @Test fun version_change_is_explicitly_revalidated_or_cancelled() {
        ExecutionDecisionSnapshot6510.resetForTest(); val mint = "VER6510_${System.nanoTime()}"
        ExecutionDecisionSnapshot6510.record(ExecutionDecisionSnapshot(mint, 1L, "BUY", "PROJECT_SNIPER", 80.0, 1L))
        assertNull(ExecutionDecisionSnapshot6510.consume(mint, 2L, "BUY", "PROJECT_SNIPER"))
        assertNull(ExecutionDecisionSnapshot6510.consume(mint, 3L, "NO_BUY", "PROJECT_SNIPER"))
    }

    @Test fun synthetic_mint_route_is_neither_price_authoritative_nor_executable() {
        val mint = "MARK6510_${System.nanoTime()}"
        val r = MarkAuthorityIntegrityGate6496.evaluate(mint, 0.001, 100_000.0, 25_000.0,
            "DEXSCREENER_PAIR_POLL", "MINT_ROUTE:$mint", fresh = true)
        assertFalse(r.priceAuthoritative); assertFalse(r.routeExecutable)
    }

    @Test fun ten_identical_partials_commit_economics_once_and_replay_is_duplicate() {
        resetPaper(); val mint = "PART6510_${System.nanoTime()}"; val pid = "PAPER:PART6510:${System.nanoTime()}"
        val raw = BigInteger.valueOf(10_000_000L)
        assertTrue(CanonicalPaperTransaction6486.open(pid, mint, "P", "PROJECT_SNIPER", "6510", 1.0, 0.0, raw, 6).applied)
        val cashBefore = PaperAccountLedger6430.cashSol(); val eventsBefore = EconomicEventSchema6464.snapshot().size
        val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(10)
        val futures = (1..10).map { pool.submit<CanonicalPaperPartialOperation6510.Receipt> { start.await(); CanonicalPaperPartialOperation6510.commit(pid, mint, "P", 0.25, 0.30, 0.001, "partial_25pct") } }
        start.countDown(); val results = futures.map { it.get() }; pool.shutdown()
        assertEquals(1, results.count { it.applied })
        val post = requireNotNull(CanonicalPositionAuthority6441.getPosition(pid))
        assertEquals(BigInteger.valueOf(7_500_000L), post.remainingQtyRaw)
        assertEquals(eventsBefore + 1, EconomicEventSchema6464.snapshot().size)
        val cashAfter = PaperAccountLedger6430.cashSol(); assertEquals(cashBefore + 0.299, cashAfter, 1e-9)
        val replay = CanonicalPaperPartialOperation6510.commit(pid, mint, "P", 0.25, 0.30, 0.001, "partial_25pct")
        assertFalse(replay.applied); assertEquals(cashAfter, PaperAccountLedger6430.cashSol(), 0.0)
        assertEquals(BigInteger.valueOf(7_500_000L), CanonicalPositionAuthority6441.getPosition(pid)?.remainingQtyRaw)
    }

    @Test fun size_resolver_respects_minimum_risk_cash_and_lane_caps() {
        resetPaper(); OrderSizeResolver6441.updatePaperExecutableMinimumSol(0.05)
        fun r(req: Double, cap: Double, cash: Double): OrderSizeResolver6441.Resolution {
            PaperAccountLedger6430.resetForTest(); PaperAccountLedger6430.initialize(cash)
            return OrderSizeResolver6441.resolve(req, "PROJECT_SNIPER", cash, true, cap, 0.05)
        }
        // V5.0.6598 §SIZING_LADDER_HONORED — operator directive Feb 2026:
        //   > "If final BUY risk budget can afford the minimum executable
        //   >  notional: clamp the executable order to canonical minimum."
        // Snapshot 6595: RunnerCompounding recommendedSizeSol=0.400 with
        // requested=0.010 was silently collapsed to BELOW_MIN_EXECUTABLE.
        // With `cash=10 SOL` the runner ladder recommends ~4 SOL (>= 3×
        // minExec=0.15), so an intentionally-dust request 0.028 no longer
        // silently fails — the rescue clamps it to the executable minimum
        // and beyond. Test updated to exercise the rescue exit: r() at
        // req < minExec with a small wallet where the ladder ALSO stays
        // under 3×minExec (so rescue does not apply and the sub-floor
        // rejection contract is preserved for that path).
        assertFalse(r(0.028, 1.0, 0.05).executable) // wallet 0.05 SOL -> ladder 0.02 < 3*minExec, rescue skipped, sub-floor rejection stands
        assertTrue(r(0.028, 1.0, 10.0).executable)   // 6598 rescue: ladder 4.0 >> 3*minExec, request lifted to minExec
        assertTrue(r(0.05, 1.0, 10.0).executable)
        assertTrue(r(0.10, 1.0, 10.0).executable)
        assertFalse(r(0.10, 0.04, 10.0).executable)
        assertFalse(r(0.10, 1.0, 0.04).executable)
    }

    @Test fun resolved_conservation_incident_is_not_active_root_cause() {
        RootCauseIncidentLifecycle6510.resetForTest()
        RootCauseIncidentLifecycle6510.open("PAPER_EQUITY_CONSERVATION_VIOLATION_6467", "old")
        assertTrue(RootCauseIncidentLifecycle6510.isOpen("PAPER_EQUITY_CONSERVATION_VIOLATION_6467"))
        RootCauseIncidentLifecycle6510.resolve("PAPER_EQUITY_CONSERVATION_VIOLATION_6467", "delta=0")
        assertFalse(RootCauseIncidentLifecycle6510.isOpen("PAPER_EQUITY_CONSERVATION_VIOLATION_6467"))
    }

    @Test fun distinct_partial_requests_use_monotonic_ids_and_never_terminally_remove_position() {
        resetPaper(); val mint = "PART6566_${System.nanoTime()}"; val pid = "PAPER:PART6566:${System.nanoTime()}"
        assertTrue(CanonicalPaperTransaction6486.open(pid, mint, "P", "CRYPTO_ALT", "6566", 1.0,
            qtyRaw = BigInteger.valueOf(1_000_000L), decimals = 6, quantityScale = 6).applied)
        val first = CanonicalPaperTransaction6486.partial(pid, mint, "P", 0.5, 20.0, 0.0, "RUNNER_RUNG_1")
        val second = CanonicalPaperTransaction6486.partial(pid, mint, "P", 0.5, -10.0, 0.0, "RUNNER_RUNG_2")
        assertTrue(first.applied); assertTrue(second.applied)
        assertEquals("$pid:1", first.operationId); assertEquals("$pid:2", second.operationId)
        assertEquals(0.5, first.remainingCostSol, 1e-9); assertEquals(0.25, second.remainingCostSol, 1e-9)
        val remaining = requireNotNull(CanonicalPositionAuthority6441.getPosition(pid))
        assertEquals(CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED, remaining.lifecycle)
        assertEquals(BigInteger.valueOf(250_000L), remaining.remainingQtyRaw)
        assertEquals(9.825, PaperAccountLedger6430.cashSol(), 1e-9)
    }

}

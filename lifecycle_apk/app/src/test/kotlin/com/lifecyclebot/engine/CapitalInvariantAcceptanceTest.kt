package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalIntegrityGuards6449
import com.lifecyclebot.engine.truth.CanonicalTradeFinalizedBus6450
import com.lifecyclebot.engine.truth.PaperAccountLedger6430
import com.lifecyclebot.engine.truth.TerminalCloseIdempotencyLatch6450
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.6452 §P0-RECONCILIATION+ACCEPTANCE — HARD CI ASSERTIONS.
 *
 * Per operator mandate: "Rewrite acceptance checks as hard assertions,
 * not status-string logging. CI/build FAILS if any of these occur…"
 *
 * These tests EXECUTE the invariants against the real authorities and
 * fail the CI build red if any of the operator-mandated invariants
 * regress. They are deliberately implementation-agnostic — each test
 * uses only the public authority API and checks an algebraic identity.
 */
class CapitalInvariantAcceptanceTest {

    @Test
    fun `capital conservation delta is zero after buy and sell (fee double-count regression guard)`() {
        // V5.0.6452 §P0-#1 — the pre-6452 PaperAccountLedger onSell was
        // simultaneously crediting cash by gross (not net) AND subtracting
        // fees from realized PnL, which broke the invariant
        //   startingCash + realized − fees == cash + openCost + reserved
        // by −2·f_s per sell. This test locks the algebra: after a
        // full round-trip with symmetric fees the delta must be zero.
        PaperAccountLedger6430.resetForTest()
        PaperAccountLedger6430.initialize(startingCashSol = 10.0)

        // BUY: cost 1.0 SOL, fee 0.01 SOL
        assertTrue(PaperAccountLedger6430.onBuy(costSol = 1.0, feeSol = 0.01))
        assertNull(
            "invariant must hold after buy",
            PaperAccountLedger6430.assertInvariant(toleranceSol = 1e-9),
        )

        // SELL: gross 1.5 SOL, basis 1.0 SOL, fee 0.015 SOL
        PaperAccountLedger6430.onSell(grossProceedsSol = 1.5, costBasisSoldSol = 1.0, feeSol = 0.015)
        val delta = PaperAccountLedger6430.assertInvariant(toleranceSol = 1e-9)
        assertNull(
            "capital conservation must hold after full round-trip — delta $delta " +
                "(cash=${PaperAccountLedger6430.cashSol()} realized=${PaperAccountLedger6430.realizedPnlSol()} " +
                "fees=${PaperAccountLedger6430.feesSol()} openCost=${PaperAccountLedger6430.openCostBasisSol()})",
            delta,
        )
    }

    @Test
    fun `capital conservation delta is zero after loss round-trip`() {
        PaperAccountLedger6430.resetForTest()
        PaperAccountLedger6430.initialize(startingCashSol = 5.0)
        assertTrue(PaperAccountLedger6430.onBuy(costSol = 0.5, feeSol = 0.005))
        // Sell at loss: gross 0.30, basis 0.50, sellFee 0.003
        PaperAccountLedger6430.onSell(grossProceedsSol = 0.30, costBasisSoldSol = 0.50, feeSol = 0.003)
        assertNull(
            "capital conservation must hold on losing round-trip",
            PaperAccountLedger6430.assertInvariant(toleranceSol = 1e-9),
        )
        // Realized PnL must be GROSS (not net) so callers wanting NET pnl
        // subtract fees explicitly (V5.0.6452 §P0-#7 typed contract).
        assertEquals(-0.20, PaperAccountLedger6430.realizedPnlSol(), 1e-9)
    }

    @Test
    fun `terminal close idempotency latch rejects duplicate sell`() {
        // V5.0.6452 §P0-#6 — one settlement engine; duplicate SELL for
        // an already-CLOSED PositionId must be rejected BEFORE any cash /
        // PnL / journal mutation.
        val pid = "TEST_PID_${System.nanoTime()}"
        val epoch1 = System.currentTimeMillis()
        val r1 = TerminalCloseIdempotencyLatch6450.tryClaim(pid, epoch1, "test_close_1")
        assertEquals(TerminalCloseIdempotencyLatch6450.ClaimResult.CLAIMED, r1)

        // A different epoch for the SAME positionId must also be rejected
        // (position can only terminally close once).
        val epoch2 = epoch1 + 1_000L
        val r2 = TerminalCloseIdempotencyLatch6450.tryClaim(pid, epoch2, "test_close_2_dup")
        assertNotEquals(
            "duplicate terminal claim for same positionId must be rejected",
            TerminalCloseIdempotencyLatch6450.ClaimResult.CLAIMED,
            r2,
        )
    }

    @Test
    fun `finalized bus publishes exactly once per positionId`() {
        // V5.0.6452 §P1 — one PositionId contributes exactly one terminal
        // W/L/BE observation. Bus dedupes by positionId; a re-publish for
        // the same positionId must return false and NOT re-invoke the
        // subscribers.
        var deliveries = 0
        CanonicalTradeFinalizedBus6450.subscribe { deliveries++ }

        val pid = "TEST_PID_BUS_${System.nanoTime()}"
        val event = CanonicalTradeFinalizedBus6450.Event(
            positionId = pid,
            mint = "TEST_MINT",
            outcome = CanonicalTradeFinalizedBus6450.Outcome.WIN,
            netRealizedPnlSol = 0.02,
            grossRealizedPnlSol = 0.025,
            returnFraction = 0.04,
            netReturnPct = 4.0,
            feesSol = 0.005,
            entryLane = "TEST",
            entryStrategyPid = "",
            entryTactic = "",
            exitReason = "test",
            holdingTimeMs = 1_000L,
            dataQuality = "OK",
            priceIntegrity = "OK",
            mode = "paper",
            settledAtMs = System.currentTimeMillis(),
        )
        val before = deliveries
        assertTrue("first publish must succeed", CanonicalTradeFinalizedBus6450.publish(event))
        val afterFirst = deliveries
        assertTrue(
            "publish must have invoked at least one subscriber (we just subscribed)",
            afterFirst > before,
        )
        // Second publish for same positionId must be idempotent.
        val republished = CanonicalTradeFinalizedBus6450.publish(event.copy(exitReason = "dup"))
        assertTrue(
            "duplicate publish for same positionId must return false",
            !republished,
        )
        assertEquals(
            "duplicate publish must NOT invoke subscribers again",
            afterFirst,
            deliveries,
        )
    }

    @Test
    fun `unknown canonical sell state must not fail-open to full quantity`() {
        // V5.0.6452 §P0-#8 — clampToRemainingStrict for a mint with no
        // canonical position must return UNKNOWN_POSITION with qty=0.
        val result = CanonicalIntegrityGuards6449.clampToRemainingStrict(
            mint = "UNKNOWN_MINT_${System.nanoTime()}",
            requestedQtyToken = 100.0,
        )
        assertEquals(
            "unknown canonical sell state must return UNKNOWN_POSITION",
            CanonicalIntegrityGuards6449.ClampReason.UNKNOWN_POSITION,
            result.reason,
        )
        assertEquals(
            "unknown canonical sell state must not fail-open — qty must be 0",
            0.0,
            result.qtyToken,
            1e-9,
        )
    }
}

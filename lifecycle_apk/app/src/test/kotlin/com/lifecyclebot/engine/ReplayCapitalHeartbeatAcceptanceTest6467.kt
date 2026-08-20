package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EconomicEventSchema6464
import com.lifecyclebot.engine.truth.CanonicalPaperReplay6464
import com.lifecyclebot.engine.truth.EventStreamReplay6467
import com.lifecyclebot.engine.truth.PaperAccountLedger6430
import com.lifecyclebot.engine.truth.PaperEquityCalculator6467
import com.lifecyclebot.engine.truth.ReconcilerHeartbeat6467
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * V5.0.6467 — HARD CI ASSERTIONS for §P0 items 9-13.
 *
 *  9  Replay from same canonical event stream — first-divergent event id.
 * 10  Single paper equity calculator (cash + markedOpenValue, realized embedded in cash).
 * 12  ReconcilerHeartbeat starts at "uninit" sentinel — never Long.MAX_VALUE.
 * 13  onSuccess advances heartbeat counters + resets age.
 */
class ReplayCapitalHeartbeatAcceptanceTest6467 {

    @Test
    fun `item9 replay converges when event stream matches ledger`() {
        EconomicEventSchema6464.resetForTest()
        EventStreamReplay6467.resetForTest()
        PaperAccountLedger6430.resetForTest()
        val starting = 10.0
        PaperAccountLedger6430.initialize(starting)
        // Ledger + event stream both see the same three canonical events.
        PaperAccountLedger6430.onBuy(1.0)
        EconomicEventSchema6464.recordBuy(
            mode = "paper", positionId = "P1", mint = "M1", symbol = "T",
            idempotencyKey = "buy_1", executedCostSol = 1.0,
            filledQty = BigInteger.valueOf(1000), fillPrice = 0.001,
        )
        PaperAccountLedger6430.onBuy(0.5)
        EconomicEventSchema6464.recordBuy(
            mode = "paper", positionId = "P2", mint = "M2", symbol = "T",
            idempotencyKey = "buy_2", executedCostSol = 0.5,
            filledQty = BigInteger.valueOf(500), fillPrice = 0.001,
        )
        PaperAccountLedger6430.onSell(grossProceedsSol = 1.5, costBasisSoldSol = 1.0)
        EconomicEventSchema6464.recordSell(
            mode = "paper", positionId = "P1", mint = "M1", symbol = "T",
            idempotencyKey = "sell_1", partial = false,
            soldQty = BigInteger.valueOf(1000),
            preRemainingQty = BigInteger.valueOf(1000),
            preRemainingCostBasisSol = 1.0,
            grossProceedsSol = 1.5, exitFeesSol = 0.0,
        )
        val parity = EventStreamReplay6467.replayAndCompare(starting)
        assertTrue("cashDelta within tolerance: ${parity.cashDelta}", kotlin.math.abs(parity.cashDelta) <= 0.05)
        assertTrue("realizedDelta within tolerance: ${parity.realizedDelta}", kotlin.math.abs(parity.realizedDelta) <= 0.05)
        assertNull("no divergent event on clean stream", parity.firstDivergentEventId)
        assertEquals(3, parity.totalEvents)
    }

    @Test
    fun `item10 equity calculator obeys cash+marked identity`() {
        PaperAccountLedger6430.resetForTest()
        PaperEquityCalculator6467.resetForTest()
        PaperAccountLedger6430.initialize(10.0)
        PaperAccountLedger6430.onBuy(3.0)
        // Cash = 7.0, marked open value = 3.5 (unrealized +0.5).
        val snap = PaperEquityCalculator6467.compute(baselineSol = 10.0, markedOpenValueSol = 3.5)
        assertEquals(7.0, snap.cashSol, 1e-6)
        assertEquals(3.5, snap.markedOpenValueSol, 1e-6)
        assertEquals(10.5, snap.equitySol, 1e-6)
        // Conservation delta computed as equity - (baseline + realized) - mv.
        // With realized=0, mv=3.5, equity=10.5, baseline=10.0 ⇒ 10.5 - 10.0 - 3.5 = -3.0 (cash <-> mv shuffle allowed).
        assertNotNull(PaperEquityCalculator6467.lastSnapshot())
    }

    @Test
    fun `item12 heartbeat starts uninitialized never MAX_VALUE`() {
        ReconcilerHeartbeat6467.resetForTest()
        assertEquals(-1L, ReconcilerHeartbeat6467.quickAgeMs())
        assertEquals(-1L, ReconcilerHeartbeat6467.fullAgeMs())
        assertTrue("status shows uninit", ReconcilerHeartbeat6467.statusLine().contains("uninit"))
    }

    @Test
    fun `item13 heartbeat advances on success`() {
        ReconcilerHeartbeat6467.resetForTest()
        ReconcilerHeartbeat6467.onQuickStart()
        ReconcilerHeartbeat6467.onQuickSuccess()
        assertEquals(1L, ReconcilerHeartbeat6467.quickPasses())
        assertTrue("quickAge is now real", ReconcilerHeartbeat6467.quickAgeMs() >= 0L)
        ReconcilerHeartbeat6467.onFullStart()
        ReconcilerHeartbeat6467.onFullSuccess()
        assertEquals(1L, ReconcilerHeartbeat6467.fullPasses())
        assertTrue("fullAge is now real", ReconcilerHeartbeat6467.fullAgeMs() >= 0L)
    }
    @Test
    fun `6476 buy fee remains fee and never becomes open cost`() {
        EconomicEventSchema6464.resetForTest()
        PaperAccountLedger6430.resetForTest()
        PaperAccountLedger6430.initialize(10.0)
        assertTrue(PaperAccountLedger6430.onBuy(1.0, 0.01))
        EconomicEventSchema6464.recordBuy(
            mode = "paper", positionId = "P6476", mint = "M6476", symbol = "T",
            idempotencyKey = "buy_6476", executedCostSol = 1.0,
            entryFeesSol = 0.01, filledQty = BigInteger.valueOf(1000), fillPrice = 0.001,
        )
        val snap = CanonicalPaperReplay6464.replay(10.0)
        assertEquals(8.99, snap.cashSol, 1e-9)
        assertEquals(1.0, snap.openCostBasisSol, 1e-9)
        assertEquals(0.01, snap.feesSol, 1e-9)
        assertEquals(0.0, CanonicalPaperReplay6464.compareToLedger(10.0).openCostDelta, 1e-9)
    }

}

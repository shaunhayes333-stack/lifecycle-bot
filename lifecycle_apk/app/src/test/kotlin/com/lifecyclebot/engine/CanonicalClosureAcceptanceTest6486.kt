package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalLotQuantity6464
import com.lifecyclebot.engine.truth.CanonicalPaperTransaction6486
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.EconomicEventSchema6464
import com.lifecyclebot.engine.truth.PaperAccountLedger6430
import com.lifecyclebot.engine.truth.SellQtyBoundaryClamp6427
import com.lifecyclebot.engine.truth.PositionStateLedger6454
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import kotlin.math.abs

/** V5.0.6486 funded transaction + conservation acceptance contracts. */
class CanonicalClosureAcceptanceTest6486 {
    private fun reset(starting: Double) {
        com.lifecyclebot.engine.truth.FillLotLedger6504.setTestMemoryMode6641(true)
        PaperAccountLedger6430.resetForTest()
        CanonicalPositionAuthority6441.resetForTest()
        SellQtyBoundaryClamp6427.resetForTest()
        PositionStateLedger6454.resetForTest()
        CanonicalLotQuantity6464.resetForTest()
        EconomicEventSchema6464.resetForTest()
        PaperAccountLedger6430.initialize(starting)
    }

    @Test
    fun `funded open add partial and terminal close conserve paper capital`() {
        reset(2.0)
        val id = "PAPER:6486:${System.nanoTime()}"
        val mint = "MINT6486${System.nanoTime()}"
        val unit = BigInteger.valueOf(1_000_000_000L)

        assertTrue(CanonicalPaperTransaction6486.open(
            id, mint, "T48", "TEST", "acceptance", 0.50, 0.01, unit, 9,
        ).applied)
        assertEquals("paper", CanonicalPositionAuthority6441.getPosition(id)?.mode)
        assertEquals(1.49, PaperAccountLedger6430.cashSol(), 1e-9)
        assertEquals(0.50, PaperAccountLedger6430.openCostBasisSol(), 1e-9)

        assertTrue(CanonicalPaperTransaction6486.add(
            id, mint, "T48", 0.25, 0.005, unit,
        ).applied)
        assertEquals(1.235, PaperAccountLedger6430.cashSol(), 1e-9)
        assertEquals(0.75, PaperAccountLedger6430.openCostBasisSol(), 1e-9)

        assertTrue(CanonicalPaperTransaction6486.close(
            id, mint, "T48", grossProceedsSol = 0.45,
            soldQtyRaw = unit, soldCostBasisSol = 0.375, sellFeeSol = 0.005,
            exitReason = "PARTIAL_ACCEPTANCE", terminalSequence = 1L,
        ).applied)
        val partial = requireNotNull(CanonicalPositionAuthority6441.getPosition(id))
        assertEquals(CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED, partial.lifecycle)
        assertEquals(unit, partial.remainingQtyRaw)

        assertTrue(CanonicalPaperTransaction6486.close(
            id, mint, "T48", grossProceedsSol = 0.40,
            soldQtyRaw = unit, soldCostBasisSol = 0.375, sellFeeSol = 0.005,
            exitReason = "TERMINAL_ACCEPTANCE", terminalSequence = 2L,
        ).applied)
        val closed = requireNotNull(CanonicalPositionAuthority6441.getPosition(id))
        assertEquals(CanonicalPositionAuthority6441.Lifecycle.CLOSED, closed.lifecycle)
        assertEquals(BigInteger.ZERO, closed.remainingQtyRaw)
        assertEquals(0.0, PaperAccountLedger6430.openCostBasisSol(), 1e-9)

        val left = PaperAccountLedger6430.startingCashSol() +
            PaperAccountLedger6430.realizedPnlSol() - PaperAccountLedger6430.feesSol()
        val right = PaperAccountLedger6430.cashSol() + PaperAccountLedger6430.openCostBasisSol()
        assertTrue("capital conservation delta=${left - right}", abs(left - right) <= 1e-9)
        assertEquals(2.075, PaperAccountLedger6430.cashSol(), 1e-9)
    }

    @Test
    fun `refund requires canonical paper debit and preserves paid entry fee`() {
        reset(1.0)
        val unknown = CanonicalPaperTransaction6486.refund("PAPER:UNKNOWN:${System.nanoTime()}", "missing")
        assertFalse(unknown.applied)
        assertEquals("NO_CANONICAL_DEBIT", unknown.reason)

        val id = "PAPER:REFUND6486:${System.nanoTime()}"
        val mint = "REFUNDMINT6486${System.nanoTime()}"
        assertTrue(CanonicalPaperTransaction6486.open(
            id, mint, "R48", "TEST", "refund-test", 0.20, 0.01,
        ).applied)
        assertTrue(CanonicalPaperTransaction6486.refund(id, "startup_orphan").applied)
        assertEquals(CanonicalPositionAuthority6441.Lifecycle.CLOSED,
            CanonicalPositionAuthority6441.getPosition(id)?.lifecycle)
        assertEquals(0.99, PaperAccountLedger6430.cashSol(), 1e-9)
        assertEquals(0.0, PaperAccountLedger6430.openCostBasisSol(), 1e-9)
    }
}

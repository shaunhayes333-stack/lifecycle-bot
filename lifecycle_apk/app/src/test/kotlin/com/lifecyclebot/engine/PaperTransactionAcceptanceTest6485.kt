package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.OrderSizeResolver6441
import com.lifecyclebot.engine.truth.PaperAccountLedger6430
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/** V5.0.6485 acceptance contracts for paper transaction atomicity. */
class PaperTransactionAcceptanceTest6485 {
    @Test
    fun `below canonical paper minimum is rejected before execution`() {
        PaperAccountLedger6430.resetForTest()
        PaperAccountLedger6430.initialize(1.0)
        OrderSizeResolver6441.updatePaperExecutableMinimumSol(0.05)
        val result = OrderSizeResolver6441.resolve(
            requestedSol = 0.04, laneName = "TEST", walletSol = 1.0,
            paperMode = true, laneRiskCapSol = 0.04,
            laneMinExecutableSol = OrderSizeResolver6441.paperExecutableMinimumSol(),
        )
        assertFalse(result.executable)
        assertEquals(0.0, result.finalSizeSol, 0.0)
        assertEquals("BELOW_MIN_EXECUTABLE", result.reason)
    }

    @Test
    fun `canonical paper minimum accepts a funded affordable size`() {
        PaperAccountLedger6430.resetForTest()
        PaperAccountLedger6430.initialize(1.0)
        OrderSizeResolver6441.updatePaperExecutableMinimumSol(0.05)
        val result = OrderSizeResolver6441.resolve(
            requestedSol = 0.10, laneName = "TEST", walletSol = 1.0,
            paperMode = true, laneRiskCapSol = 0.25,
            laneMinExecutableSol = OrderSizeResolver6441.paperExecutableMinimumSol(),
        )
        assertTrue(result.executable)
        assertTrue(result.finalSizeSol >= 0.05)
        assertEquals("OK", result.reason)
    }

    @Test
    fun `startup heal removes unfunded paper lifecycle without manufacturing open`() {
        val suffix = System.nanoTime().toString()
        val positionId = "PAPER:UNFUNDED_6485_$suffix"
        val mint = "UNFUNDED_MINT_6485_$suffix"
        val applied = CanonicalPositionAuthority6441.openPosition(
            idempotencyKey = "UNFUNDED_IDEM_6485_$suffix", positionId = positionId,
            mint = mint, symbol = "UF", lane = "TEST", runId = "PAPER_TEST",
            entryCostSol = 0.0, openedQtyRaw = BigInteger.ZERO,
            tokenDecimals = 9, feesSol = 0.0, paperMode = false,
        )
        assertEquals(CanonicalPositionAuthority6441.MutateResult.APPLIED, applied)
        val purged = CanonicalPositionAuthority6441.healUnfundedPaperEntries6485()
        assertTrue(purged.any { it.positionId == positionId })
        assertNull(CanonicalPositionAuthority6441.getPosition(positionId))
        assertFalse(CanonicalPositionAuthority6441.openPositions().any { it.positionId == positionId })
    }
}

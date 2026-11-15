package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.SellFinalizationCoordinator
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.EconomicEventSchema6464
import com.lifecyclebot.engine.truth.JournalEconomicReplay6619
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger as BI

/** V5.0.8760 — code audit (accounting 3): a live final sell after a partial is journaled, so the live record sees it. */
class Aate8760FinalSliceBookedTest {
    private fun pos() = CanonicalPositionAuthority6441.Position(
        positionId = "position-8760", mode = "live", mint = "mint8760", symbol = "TEST", lane = "MOONSHOT", runId = "test",
        openedAtMs = 1000, entryCostSol = 0.1, remainingQtyRaw = BI.ZERO, originalQtyRaw = BI.valueOf(1_000_000),
        soldCostBasisSol = 0.1, realizedPnlSol = -0.02, realizedProceedsSol = 0.08, feesSol = 0.002,
        tokenDecimals = 6, lifecycle = CanonicalPositionAuthority6441.Lifecycle.CLOSED,
        lastMutationMs = 2000, quarantineReason = "", entryPriceUsd = 10.0)

    @Test fun the_pre_sell_state_is_the_position_as_it_stood() {
        // a terminal sell closes what remained: the whole remaining quantity and cost
        assertEquals(BI.valueOf(600_000), SellFinalizationCoordinator.preRemaining8760(false, BI.valueOf(600_000), BI.valueOf(600_000), BI.valueOf(1_000_000)))
        assertEquals(BI.valueOf(600_000), SellFinalizationCoordinator.preRemaining8760(false, BI.valueOf(600_000), BI.valueOf(620_000), BI.valueOf(1_000_000)))
        assertEquals("a partial reads the canonical remainder", BI.valueOf(600_000),
            SellFinalizationCoordinator.preRemaining8760(true, BI.valueOf(200_000), BI.valueOf(600_000), BI.valueOf(1_000_000)))
        assertEquals("no canonical remainder: the caller's", BI.valueOf(1_000_000),
            SellFinalizationCoordinator.preRemaining8760(true, BI.valueOf(200_000), BI.ZERO, BI.valueOf(1_000_000)))
        assertEquals(0.06, SellFinalizationCoordinator.preRemainingCost8760(0.06, 0.1), 1e-12)
        assertEquals(0.1, SellFinalizationCoordinator.preRemainingCost8760(Double.NaN, 0.1), 1e-12)
        val src = File("src/main/kotlin/com/lifecyclebot/engine/sell/SellFinalizationCoordinator.kt").readText()
        assertTrue(src.contains("preRemainingQty = preRemaining8760(partial, actualConsumedRaw, canonicalPosition6522.remainingQtyRaw,"))
    }

    @Test fun a_receipt_written_before_the_fix_projects_as_the_close_it_was() {
        // final sell of the last 60 % after a 40 % partial, recorded against the original buy (remainingQty 400000)
        val old = EconomicEventSchema6464.Sell(
            atMs = 2000, mode = "live", positionId = "position-8760", mint = "mint8760", symbol = "TEST", idempotencyKey = "r8760",
            partial = false, soldQty = BI.valueOf(600_000), allocatedCostBasisSol = 0.06, grossProceedsSol = 0.03,
            exitFeesSol = 0.001, netProceedsSol = 0.029, realizedPnlSol = -0.03, realizedReturnPct = -50.0,
            remainingQty = BI.valueOf(400_000), remainingCostBasisSol = 0.04, solUsdAtExit = 100.0,
            confirmedSignature8422 = "sig8760", exitReason8422 = "RAPID_HARD_FLOOR_STOP")
        val t = JournalEconomicReplay6619.confirmedSellTrade8422(old, pos())!!
        assertEquals("SELL", t.side)
        assertEquals(-0.03, t.pnlSol, 1e-12)
        assertEquals(BI.ZERO, t.remainingRawQty)
        assertEquals("LIVE_FINALIZED", t.proofState)
        assertNull("a partial with nothing left is still not a fact", JournalEconomicReplay6619.confirmedSellTrade8422(old.copy(partial = true, remainingQty = BI.ZERO), pos()))
        assertEquals(BI.ZERO, JournalEconomicReplay6619.terminalRemainder8760(old))
    }
}

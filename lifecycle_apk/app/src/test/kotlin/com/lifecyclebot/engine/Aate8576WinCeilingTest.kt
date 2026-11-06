package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PaperAccountLedger6430
import com.lifecyclebot.engine.truth.PartialSellUnitTypes6461
import com.lifecyclebot.engine.truth.SellQtyBoundaryClamp6427
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.8576 — a real win above 30 SOL is not a unit leak, and a sale the ledger would refuse is refused before the position
 * is cut. 8572 smoke: a +2,323 % paper partial left POSITION committed alone (pos=true led=false) -> QUANTITY_DELTA.
 */
class Aate8576WinCeilingTest {
    @Test fun the_proceeds_ceiling_scales_with_the_slice() {
        assertEquals(30.0, PartialSellUnitTypes6461.proceedsCeiling8576(0.001), 0.0)
        assertEquals(40_000.0, PartialSellUnitTypes6461.proceedsCeiling8576(4.0), 0.0)
        assertEquals(30.0, PartialSellUnitTypes6461.proceedsCeiling8576(Double.NaN), 0.0)
        // a 24x on a 4 SOL slice (96 SOL) passes; a percent leak on a tiny slice does not
        assertTrue(PartialSellUnitTypes6461.solPlausible8576(96.0, PartialSellUnitTypes6461.proceedsCeiling8576(4.0)))
        assertFalse(PartialSellUnitTypes6461.solPlausible8576(2323.0, PartialSellUnitTypes6461.proceedsCeiling8576(0.01)))
        assertEquals(96.0, PartialSellUnitTypes6461.assertSolPlausible(96.0, "test8576", PartialSellUnitTypes6461.proceedsCeiling8576(4.0)), 0.0)
        assertEquals(0.0, PartialSellUnitTypes6461.assertSolPlausible(96.0, "test8576"), 0.0)   // default ceiling unchanged
    }

    @Test fun the_ledger_refusal_is_readable_before_any_store_moves() {
        PaperAccountLedger6430.resetForTest()
        PaperAccountLedger6430.initialize(startingCashSol = 10.0)
        PaperAccountLedger6430.onBuy(costSol = 4.0, feeSol = 0.0)
        assertNull(PaperAccountLedger6430.economicRefusal8576(96.0, 4.0, 0.01))           // a real 24x applies
        assertEquals("BASIS_EXCEEDS_OPEN", PaperAccountLedger6430.economicRefusal8576(1.0, 5.0, 0.0))
        assertEquals("PROCEEDS_IMPLAUSIBLE", PaperAccountLedger6430.economicRefusal8576(500.0, 0.01, 0.0))
        assertEquals("FEE_EXCEEDS_GROSS", PaperAccountLedger6430.economicRefusal8576(0.001, 0.5, 0.01))
        assertEquals("NON_FINITE", PaperAccountLedger6430.economicRefusal8576(Double.NaN, 0.5, 0.0))
        assertTrue(PaperAccountLedger6430.onSell(grossProceedsSol = 96.0, costBasisSoldSol = 4.0, feeSol = 0.0))
        assertTrue("the 24x is credited, not zeroed", PaperAccountLedger6430.cashSol() > 100.0)
        assertFalse(SellQtyBoundaryClamp6427.canCommitRaw8576("NO_SUCH_POSITION_8576", BigInteger.ONE))
    }

    @Test fun the_bridge_checks_before_the_claim_and_the_position_cut() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTerminalBridge6469.kt").readText()
        val fn = src.substringAfter("fun finalizeSell(")
        val pre = fn.indexOf("PaperAccountLedger6430.economicRefusal8576(grossProceedsSol, soldCostBasisSol, feesSol)")
        val qty = fn.indexOf("SellQtyBoundaryClamp6427.canCommitRaw8576(positionId, soldQtyRaw)")
        val claim = fn.indexOf("val claim = claimBeforeSideEffects(")
        val cut = fn.indexOf("Store.POSITION")
        assertTrue(pre in 0 until claim && qty in 0 until claim && claim < cut)
    }
}

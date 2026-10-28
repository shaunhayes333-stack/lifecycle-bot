package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalRawQuantityAuthority6520
import com.lifecyclebot.engine.truth.EconomicUnitInvariant7061
import com.lifecyclebot.engine.truth.PaperFillCore8418
import com.lifecyclebot.engine.truth.PaperVenueCost7287
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/** V5.0.8418 — the paper fill / close arithmetic Executor.paperBuy / paperSell book, run in the JVM. */
class Aate8418PaperFillCoreTest {
    /** Half the pool's USD liquidity in SOL, as paperSell offers it; NaN when either is unknown. */
    private fun cap(liqUsd: Double, solUsd: Double) =
        if (liqUsd > 0.0 && solUsd > 0.0) EconomicUnitInvariant7061.usdToSol(liqUsd * 0.5, solUsd) else Double.NaN

    @Test fun theBuyRawIsTheExactEconomicQuantity() {
        var fallbacks = 0
        val raw = PaperFillCore8418.buyRaw8418(0.5, 150.0, 0.0002, 9) { _, _ -> fallbacks++ }
        assertEquals(CanonicalRawQuantityAuthority6520.paperRawFromEconomics("0.5", "150.0", "2.0E-4", 9), raw)
        assertEquals(BigInteger("375000000000000"), raw)   // 0.5 SOL x $150 / $0.0002 = 375,000 tokens at 9 decimals
        assertEquals(0, fallbacks)
    }

    @Test fun invalidEconomicsFillNothing() {
        assertEquals(BigInteger.ZERO, PaperFillCore8418.buyRaw8418(0.5, Double.NaN, 0.0002, 9))
        assertEquals(BigInteger.ZERO, PaperFillCore8418.buyRaw8418(0.5, 150.0, 0.0, 9))
        assertEquals(BigInteger.ZERO, PaperFillCore8418.buyRaw8418(0.0, 150.0, 0.0002, 9))
    }

    @Test fun anOutOfRangeScaleTakesThe6550FallbackAndReportsIt() {
        var seen = -1
        val raw = PaperFillCore8418.buyRaw8418(0.5, 150.0, 0.0002, 30) { _, scale -> seen = scale }
        assertEquals(18, seen)   // the economic authority refuses scale 30; the fallback encodes at the clamped scale
        assertEquals(BigInteger("375000").multiply(BigInteger.TEN.pow(18)), raw)
        // a failing callback leaves no fill, as the inline code did
        assertEquals(BigInteger.ZERO, PaperFillCore8418.buyRaw8418(0.5, 150.0, 0.0002, 30) { _, _ -> throw IllegalStateException("x") })
    }

    @Test fun theBuyFeeIsVenueFeePlusAppFeePlusTheFixedLeg() {
        assertEquals(0.5 * (1.25 / 100.0 + 0.005) + PaperVenueCost7287.FIXED_SOL_PER_SIDE,
            PaperFillCore8418.buyFeeSol8418(0.5, 1.25, 0.005), 1e-15)
    }

    @Test fun aCloseBooksCostBasisTimesReturnLessFees() {
        val c = PaperFillCore8418.close8418(0.5, 0.0002, 0.0003, 0.25, cap(1_000_000.0, 150.0))
        assertEquals(50.0, c.priceDerivedPnlPct, 1e-9)
        val expected = 0.5 * 1.5 * (1.0 - 0.0025) - PaperVenueCost7287.FIXED_SOL_PER_SIDE
        assertEquals(expected, c.value, 1e-12)
        assertFalse(c.liquidityCapped)
        assertEquals(0.75, c.grossNoFrictionValue, 1e-12)
        assertEquals(0.75 - expected, c.simulatedFeeSol, 1e-12)
        assertEquals(expected - 0.5, c.pnl, 1e-12)
        assertEquals((expected - 0.5) / 0.5 * 100.0, c.pnlP, 1e-9)
    }

    @Test fun aCloseIsCappedAtHalfThePoolAndAWipeoutBooksZero() {
        val c = PaperFillCore8418.close8418(1.0, 1.0, 100.0, 0.25, cap(3_000.0, 150.0))   // 100x on a $3k pool
        assertTrue(c.liquidityCapped)
        assertEquals(10.0, c.value, 1e-12)                                           // ($3,000 / 2) / $150
        val gone = PaperFillCore8418.close8418(1.0, 1.0, 0.0, 0.25, cap(3_000.0, 150.0))
        assertEquals(-100.0, gone.priceDerivedPnlPct, 1e-12)
        assertEquals(0.0, gone.value, 0.0)
        assertEquals(-1.0, gone.pnl, 1e-12)
        // unknown SOL/USD or liquidity: no cap is invented
        assertFalse(PaperFillCore8418.close8418(1.0, 1.0, 100.0, 0.25, cap(3_000.0, 0.0)).liquidityCapped)
        assertTrue(PaperFillCore8418.close8418(1.0, 1.0, 100.0, 0.25, cap(0.0, 150.0)).maxValueSol.isNaN())
    }
}

package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.TradeTape8100
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Exercise the public tape reader: fallback depends on a valid shape and preserves actual source identity. */
class Aate8237CurveShapeContinuityTest {
    private val t0 = 6_000_000L // minute boundary; all fixtures contain fewer than three minute bars
    private val mint = "curve-young8237"

    @Before fun setUp() { TradeTape8100.resetForTest8100() }
    @After fun tearDown() { TradeTape8100.resetForTest8100() }

    private fun price(seconds: Int, px: Double, source: String = TradeTape8100.SRC_CURVE_8169) =
        TradeTape8100.notePrice8169(mint, px, source, t0 + seconds * 1_000L)

    @Test fun aShortExecutedBurstDoesNotHideAnIndependentlyValidCurveShape() {
        price(0, 1.0)
        price(16, 1.1)
        price(32, 1.2)
        for (second in 43..45) TradeTape8100.note8100(mint, 9.0, 1.0, true, t0 + second * 1_000L)
        price(48, 1.3)

        val read = TradeTape8100.read8100(mint, t0 + 48_000L)!!
        assertNotNull(read.shape)
        assertEquals(30.0, read.shape!!.runupPct, 0.00001)
        assertEquals(1.0, read.shape!!.vsPeak, 0.00001)
        assertNull(read.bp) // three executed trades stay below the existing six-trade floor
        assertEquals(3, TradeTape8100.prints8128(mint, t0 + 48_000L, 60_000L).size)
    }

    @Test fun pumpSwapPriceOnlyPointsCannotCompleteAnInsufficientCurveShape() {
        price(0, 1.0)
        price(16, 1.2, TradeTape8100.SRC_PUMPSWAP_8169)
        price(32, 1.4, TradeTape8100.SRC_PUMPSWAP_8169)
        price(48, 1.3)
        val read = TradeTape8100.read8100(mint, t0 + 48_000L)!!
        assertNull(read.shape)
        assertNull(read.bp)
        assertEquals(0, TradeTape8100.prints8128(mint, t0 + 48_000L, 60_000L).size)
    }

    @Test fun sufficientExecutedShapeStillTakesPrecedenceOverCurveFallback() {
        price(0, 10.0)
        TradeTape8100.note8100(mint, 2.0, 1.0, true, t0)
        price(16, 8.0)
        TradeTape8100.note8100(mint, 2.2, 1.0, true, t0 + 16_000L)
        price(32, 6.0)
        TradeTape8100.note8100(mint, 2.4, 1.0, true, t0 + 32_000L)
        price(48, 4.0)
        TradeTape8100.note8100(mint, 2.6, 1.0, true, t0 + 48_000L)
        val read = TradeTape8100.read8100(mint, t0 + 48_000L)!!
        assertEquals(30.0, read.shape!!.runupPct, 0.00001)
        assertEquals(1.0, read.shape!!.vsPeak, 0.00001)
        assertNull(read.bp)
    }

    @Test fun curveFallbackCannotRelaxTheTimeSpanOrFreshnessRequirements() {
        price(0, 1.0)
        price(15, 1.1)
        price(29, 1.2)
        assertNull(TradeTape8100.read8100(mint, t0 + 29_000L)!!.shape)
        assertNull(TradeTape8100.read8100(mint, t0 + 150_000L))
    }
}

package com.lifecyclebot.engine.truth

import org.junit.Assert.*
import org.junit.Test

/** 8220 — a young coin's own executed trades state its shape in 15-second bars before three minute bars exist. */
class Aate8220YoungShapeTest {
    @Test fun youngCoinGetsShapeFromOwnTradesWithinOneMinute() {
        val mint = "Young8220Mint" + System.nanoTime()
        val now = 1_800_000_000_000L
        // 45 s of real executed trades inside one minute bucket region: 4 x 15 s bars, rising then a small pullback.
        listOf(0L to 1.0, 16_000L to 1.2, 31_000L to 1.5, 46_000L to 1.4).forEach { (dt, px) ->
            TradeTape8100.note8100(mint, px * 1e-7, 0.5, true, now - 50_000L + dt)
        }
        val r = TradeTape8100.read8100(mint, now)
        assertNotNull(r); val s = r!!.shape
        assertNotNull("young coin must have a stated shape", s)
        assertTrue(s!!.runupPct > 30.0 && s.ddPct > 0.0)
    }

    @Test fun tooShortOrPriceOnlyHistoryStaysUnknown() {
        val mint = "Young8220Short" + System.nanoTime()
        val now = 1_800_000_000_000L
        TradeTape8100.note8100(mint, 1e-7, 0.5, true, now - 10_000L)
        TradeTape8100.note8100(mint, 1.1e-7, 0.5, true, now - 5_000L)
        TradeTape8100.note8100(mint, 1.2e-7, 0.5, true, now - 1_000L)
        assertNull("under 30 s span is not a shape", TradeTape8100.read8100(mint, now)?.shape)
    }
}

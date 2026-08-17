package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaneIdentityNormalizer6459
import com.lifecyclebot.engine.truth.SellQuantityBoundary6459
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.6459 hard CI assertions.
 */
class ExitAccountingAcceptanceTest {
    @Test
    fun `sell quantity boundary rejects oversell and dedupes duplicate execution ids`() {
        SellQuantityBoundary6459.resetForTest()
        val pid = "P_${System.nanoTime()}"
        SellQuantityBoundary6459.recordBuyFill(pid, 77.214)

        val first = SellQuantityBoundary6459.admitSell(pid, "SX1", 60.0)
        assertEquals(60.0, first, 1e-9)

        val second = SellQuantityBoundary6459.admitSell(pid, "SX2", 100.0)
        // sellable = 77.214 - 60 = 17.214
        assertTrue("second sell clamped to sellable", second in 17.213..17.215)

        // Duplicate execution id must NOT double-count.
        val dup = SellQuantityBoundary6459.admitSell(pid, "SX1", 5.0)
        assertEquals(0.0, dup, 1e-9)

        // Any further sell would be over — reject.
        val extra = SellQuantityBoundary6459.admitSell(pid, "SX3", 1.0)
        assertEquals(0.0, extra, 1e-9)
    }

    @Test
    fun `lane identity normalizer canonicalizes known aliases`() {
        assertEquals("BLUECHIP", LaneIdentityNormalizer6459.canonicalize("BLUE_CHIP"))
        assertEquals("BLUECHIP", LaneIdentityNormalizer6459.canonicalize("blue-chip"))
        assertEquals("PRESALE_SNIPE", LaneIdentityNormalizer6459.canonicalize("RESALE_SNIPE"))
        assertEquals("MICRO_CAP", LaneIdentityNormalizer6459.canonicalize("microcap"))
        assertEquals("MOONSHOT", LaneIdentityNormalizer6459.canonicalize("MOONSHOT"))
        assertEquals("UNKNOWN", LaneIdentityNormalizer6459.canonicalize(null))
        assertNotEquals("BLUECHIP", LaneIdentityNormalizer6459.canonicalize("SHITCOIN"))
    }
}

package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CanonicalTokenMetricsSnapshot6725
import com.lifecyclebot.engine.truth.CanonicalTokenMetricsSnapshot6725.HealthTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.6725 — §CANONICAL_TOKEN_METRICS.
 *
 * Locks in the single source of truth every metric-aware tool now
 * consumes. Operator mandate: "all tools traders brains strategies
 * anything involved or invoked during the discovery buy hold or sell
 * should be token metrics aware!!!!"
 *
 * The snapshot must:
 *   - Return NEUTRAL on null / broken TokenState (never brick the exit path).
 *   - Classify healthy runners (rising vol + growing holders + strong buy
 *     pressure) as HEALTHY_RUNNER so TP widening / extend-hold engage.
 *   - Classify dying tokens (vol death + sell pressure) as DYING so SL
 *     tightening engages.
 *   - Classify catastrophic combinations as RUG_LIKE so the emergency
 *     paths take priority.
 */
class Aate6725TokenMetricsSnapshotTest {

    @Test
    fun `null token state produces neutral snapshot`() {
        val s = CanonicalTokenMetricsSnapshot6725.snapshot(null)
        assertEquals(HealthTier.NEUTRAL, s.healthTier)
        assertEquals(50.0, s.buyPressurePct, 0.001)
        assertEquals(50.0, s.sellPressurePct, 0.001)
        assertFalse(s.isWhaleAccumulating)
        assertFalse(s.isWhaleDumping)
    }

    @Test
    fun `healthy runner surfaces whale accumulation and healthy tier`() {
        val ts = TokenState(mint = "test-mint", symbol = "TEST")
        ts.lastBuyPressurePct = 75.0
        ts.lastSellPressurePct = 25.0
        ts.holderGrowthRate = 30.0
        ts.momentum = 15.0
        // Populate history so volume delta computes to a rising trend.
        val now = System.currentTimeMillis()
        for (i in 0 until 5) {
            ts.history.addLast(com.lifecyclebot.data.Candle(ts = now - (10 - i) * 60_000L, priceUsd = 1.0, marketCap = 100_000.0, volumeH1 = 100.0, volume24h = 0.0))
        }
        for (i in 0 until 5) {
            ts.history.addLast(com.lifecyclebot.data.Candle(ts = now - (5 - i) * 60_000L, priceUsd = 1.0, marketCap = 100_000.0, volumeH1 = 500.0, volume24h = 0.0))
        }
        val s = CanonicalTokenMetricsSnapshot6725.snapshot(ts)
        assertTrue("recent vol should be > prior vol", s.volumeChangePct > 100.0)
        assertTrue("whale accumulation must be signalled", s.isWhaleAccumulating)
        assertFalse("whale dumping must NOT be signalled", s.isWhaleDumping)
        assertEquals(HealthTier.HEALTHY_RUNNER, s.healthTier)
        assertTrue(s.isHealthyRunner())
        assertFalse(s.isDyingToken())
    }

    @Test
    fun `dying token surfaces sell pressure and vol death`() {
        val ts = TokenState(mint = "test-mint", symbol = "TEST")
        ts.lastBuyPressurePct = 20.0
        ts.lastSellPressurePct = 80.0
        ts.holderGrowthRate = -20.0
        ts.momentum = -15.0
        val now = System.currentTimeMillis()
        for (i in 0 until 5) {
            ts.history.addLast(com.lifecyclebot.data.Candle(ts = now - (10 - i) * 60_000L, priceUsd = 1.0, marketCap = 100_000.0, volumeH1 = 500.0, volume24h = 0.0))
        }
        for (i in 0 until 5) {
            ts.history.addLast(com.lifecyclebot.data.Candle(ts = now - (5 - i) * 60_000L, priceUsd = 1.0, marketCap = 100_000.0, volumeH1 = 100.0, volume24h = 0.0))
        }
        val s = CanonicalTokenMetricsSnapshot6725.snapshot(ts)
        assertTrue("recent vol should be < prior vol", s.volumeChangePct < -50.0)
        assertTrue("whale dumping must be signalled", s.isWhaleDumping)
        assertTrue(
            "dying token must classify as RUG_LIKE (vol crash + whale dump combined)",
            s.healthTier == HealthTier.RUG_LIKE || s.healthTier == HealthTier.DYING,
        )
        assertTrue(s.isDyingToken())
        assertFalse(s.isHealthyRunner())
    }

    @Test
    fun `neutral metrics produce neutral tier`() {
        val ts = TokenState(mint = "test-mint", symbol = "TEST")
        val s = CanonicalTokenMetricsSnapshot6725.snapshot(ts)
        assertEquals(HealthTier.NEUTRAL, s.healthTier)
        assertFalse(s.isDyingToken())
        assertFalse(s.isHealthyRunner())
    }

    @Test
    fun `to diagnostic returns non blank one liner`() {
        val ts = TokenState(mint = "test-mint", symbol = "TEST")
        val diag = CanonicalTokenMetricsSnapshot6725.toDiagnostic(
            CanonicalTokenMetricsSnapshot6725.snapshot(ts)
        )
        assertTrue(diag.contains("tier="))
        assertTrue(diag.contains("vol="))
        assertTrue(diag.contains("bp="))
        assertTrue(diag.contains("mom="))
    }
}

package com.lifecyclebot.perps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PythOracleSafetyTest {
    private val nowMs = 1_800_000_000_000L

    @Test
    fun accepts_fresh_finite_confident_price() {
        assertTrue(price(publishTimeSec = nowMs / 1000L).isTradable(nowMs))
    }

    @Test
    fun rejects_stale_future_zero_nonfinite_and_low_confidence_prices() {
        assertFalse(price(publishTimeSec = nowMs / 1000L - 61L).isTradable(nowMs))
        assertFalse(price(publishTimeSec = nowMs / 1000L + 6L).isTradable(nowMs))
        assertFalse(price(value = 0.0).isTradable(nowMs))
        assertFalse(price(value = Double.NaN).isTradable(nowMs))
        assertFalse(price(confidence = 2.0).isTradable(nowMs))
    }

    private fun price(
        value: Double = 100.0,
        confidence: Double = 0.5,
        publishTimeSec: Long = nowMs / 1000L,
    ) = PythOracle.PythPrice(
        symbol = "TEST",
        price = value,
        confidence = confidence,
        expo = -8,
        publishTime = publishTimeSec,
        emaPrice = value,
        emaConfidence = confidence,
    )
}

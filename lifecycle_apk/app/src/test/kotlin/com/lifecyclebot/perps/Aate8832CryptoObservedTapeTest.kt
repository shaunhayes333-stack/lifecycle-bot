package com.lifecyclebot.perps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Regression: clocked price observations, not repeated registry scans, own dynamic entry structure. */
class Aate8832CryptoObservedTapeTest {
    private val now = 1_800_000_000_000L

    @Test fun cached_reads_cannot_forge_candles_or_a_breakout() {
        val id = "solana|aate8832-observed-tape"
        fun tick(price: Double, observedAt: Long, readAt: Long = observedAt + 500L) =
            CryptoLaneDesk7391.recordTick(id, price, 50_000.0, 100_000.0, 60, 40, observedAt, readAt)

        tick(100.0, now)
        tick(100.0, now, now + 65_000L)  // a cached price 65 s later is still one observation
        assertEquals(listOf(100.0), CryptoLaneDesk7391.prices(id))

        tick(102.0, now + 10_000L)       // genuine second observation in the same 20 s bar
        assertEquals(listOf(102.0), CryptoLaneDesk7391.prices(id))
        tick(99.0, now + 9_000L)         // out-of-order observation is not a reversal
        assertEquals(listOf(102.0), CryptoLaneDesk7391.prices(id))

        tick(103.0, now + 35_000L)      // a genuinely newer bar may now form
        assertEquals(listOf(102.0, 103.0), CryptoLaneDesk7391.prices(id))
        tick(500.0, now + 36_000L, now + 200_000L) // stale, even if its carried number spikes
        tick(500.0, 0L, now + 200_000L)            // no proof, no chart
        assertEquals(listOf(102.0, 103.0), CryptoLaneDesk7391.prices(id))
    }

    @Test fun the_brain_sees_the_current_observation_and_requires_proven_recent_counts() {
        val trader = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
            .substringAfter("private suspend fun runDynamicTokenScan")
        val record = trader.indexOf("CryptoLaneDesk7391.recordTick(")
        val grade = trader.indexOf("val cryptoDecision7244 = scoreDynamicCrypto7244(")
        assertTrue("price tape precedes every dynamic CryptoBrain tactic decision", record >= 0 && record < grade)
        assertTrue(trader.contains("observedAtMs = priceObserved8832"))
        assertTrue(trader.contains("shortWindow8832 = shortWindow8832"))
        assertTrue(trader.contains("refreshed.buys5m8832, refreshed.sells5m8832"))
        assertFalse("24h counts alone are not evidence of current buyer activity", trader.contains("buyersObserved8535 = buys24h7391 + sells24h7391 > 0"))
    }

    @Test fun five_minute_provenance_prevents_old_activity_from_becoming_live_buyer_confirmation() {
        fun evidence(at: Long, buys: Int, sells: Int, volume: Double, change: Double) =
            CryptoShortWindow8832.from(now, at, buys, sells, volume, change)
        val good = evidence(now - 3_000L, 18, 8, 17_000.0, 2.5)
        assertTrue(good.activeBuyerFlow && good.priceRising5m && good.volumeActive5m)
        assertTrue((good.volumeAccelerationVsDay(100_000.0) ?: 0.0) > 3.0)
        val old = evidence(now - 220_000L, 180, 8, 100_000.0, 7.0)
        assertFalse(old.activeBuyerFlow || old.priceRising5m || old.volumeActive5m)
        val missing = evidence(now, -1, -1, Double.NaN, Double.NaN)
        assertFalse(missing.flowObserved)
        assertEquals(null, missing.change5mPct)
        assertEquals(null, missing.volume5mUsd)
        val sellers = evidence(now, 2, 20, 1_000.0, 1.5)
        assertFalse(sellers.activeBuyerFlow)
        val trueZero = evidence(now, 0, 0, 0.0, 0.0)
        assertEquals(0.0, trueZero.volume5mUsd ?: -1.0, 0.0)
        assertFalse(trueZero.flowObserved)
    }
}

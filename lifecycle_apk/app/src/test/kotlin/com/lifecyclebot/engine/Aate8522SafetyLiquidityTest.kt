package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.network.PairInfo
import org.junit.Assert.*
import org.junit.Test

class Aate8522SafetyLiquidityTest {
    private val now = 1_800_000_000_000L
    private fun observation(usd: Double, age: Long = 0L, pool: String = "selected-pool") =
        SafetyLiquidity8522.Observation(usd, now - age, pool, "OBSERVED")

    @Test fun currentLowDepthCannotBeReplacedWithAnOlderHigherReading() {
        val result = SafetyLiquidity8522.choose(observation(4.36), observation(97_916.0, 30_000L), "selected-pool", now)
        assertEquals(4.36, result!!.usd, 0.0)
        assertEquals(now, result.observedAtMs)
    }

    @Test fun observedZeroRemainsZeroInsteadOfResurrectingOldLiquidity() {
        assertEquals(0.0, SafetyLiquidity8522.choose(observation(0.0), observation(100_000.0), "selected-pool", now)!!.usd, 0.0)
    }

    @Test fun staleFutureAndWrongPoolReadingsCannotAuthorizeSafety() {
        assertNull(SafetyLiquidity8522.choose(observation(50_000.0, 120_001L), null, "selected-pool", now))
        assertNull(SafetyLiquidity8522.choose(observation(50_000.0, -5_001L), null, "selected-pool", now))
        assertNull(SafetyLiquidity8522.choose(null, observation(50_000.0, pool = "different-pool"), "selected-pool", now))
        assertNull(SafetyLiquidity8522.choose(observation(Double.NaN), observation(-1.0), "selected-pool", now))
    }

    @Test fun independentlyObservedSamePoolFallbackRetainsItsOwnClock() {
        val fallback = observation(7_500.0, 45_000L)
        assertEquals(fallback, SafetyLiquidity8522.choose(null, fallback, "selected-pool", now))
    }

    @Test fun syntheticPairCannotTurnStoredOrEstimatedDepthIntoProviderEvidence() {
        val pair = PairInfo("selected-pool", "X", "X", "", Candle(ts = now, priceUsd = 1.0,
            marketCap = 1_000_000.0, volumeH1 = 0.0, volume24h = 0.0, synthetic = true),
            liquidity = 850_000.0, baseTokenAddress = "mint")
        assertNull(SafetyLiquidity8522.fromPair(pair, "mint"))
        assertNull(SafetyLiquidity8522.fromPair(pair.copy(candle = pair.candle.copy(synthetic = false)), "another-mint"))
        assertEquals(850_000.0, SafetyLiquidity8522.fromPair(pair.copy(candle = pair.candle.copy(synthetic = false)), "mint")!!.usd, 0.0)
    }

    @Test fun newerTradeFramePreservesAccountProofWithoutRefreshingItsOriginalTime() {
        val mint = "Account8522FrameProofAAAAAAAAAAAAAAAAAApump"
        val curves = com.lifecyclebot.engine.truth.CurveReserves7951
        curves.noteAccount7951(mint, 40.0, 800_000.0, 10.0, now - 10_000L)
        curves.noteFrame7951(mint, 42.0, 780_000.0, now - 500L)
        assertEquals(now - 10_000L, curves.accountObservation8522(mint, now)!!.observedAtMs)
        assertEquals(10.0, curves.accountObservation8522(mint, now)!!.realSol, 0.0)
        assertNull(curves.accountObservation8522(mint, now + 20_001L))
        // A delayed read can contribute account evidence without rolling the latest frame back.
        curves.noteAccount7951(mint, 41.0, 790_000.0, 11.0, now - 1_000L)
        assertEquals(now - 1_000L, curves.accountObservation8522(mint, now)!!.observedAtMs)
        assertEquals(now - 500L, curves.acceptedObservation8385(mint)!!.atMs)
        curves.noteFrame7951(mint, 30.1, 1_000_000.0, now)
        assertNull("newer unsafe reserves cannot borrow the earlier healthy balance", curves.accountObservation8522(mint, now))
    }
}

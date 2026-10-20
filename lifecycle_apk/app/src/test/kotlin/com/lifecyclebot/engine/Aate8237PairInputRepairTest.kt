package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PairFlow8169
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A repair must use the same freshness and input completeness as the specialist reading the answer. */
class Aate8237PairInputRepairTest {
    private val now = 8_000_000_000L
    private val mint = "PairRepairxxxxxxxxxxxxxxxxxxxxxxxxxxxxpump"

    @Before fun reset() {
        DataPipeline8191.resetForTests8191()
        PairFlow8169.clearForTest8169()
    }

    @After fun clean() = reset()

    private fun flow(
        at: Long = now, buys: Int = 6, sells: Int = 4,
        chg5m: Double = 0.0, chg1h: Double = 0.0, liquidity: Double = 20_000.0,
    ) = PairFlow8169.Flow8169(buys, sells, -1, -1, chg5m, chg1h, liquidity, at)

    @Test fun aLaunchFlowPastSixtySecondsRefreshesInsteadOfWaitingFifteenMinutes() {
        PairFlow8169.note8169(mint, flow(at = now - 60_001L))
        assertFalse(DataPipeline8191.pairFlowSatisfies8191(PairFlow8169.peek8169(mint), setOf("bp", "chg"), now, ageMin = 2.0))
        assertTrue(DataPipeline8191.claimPairFetch8191(mint, now, setOf("bp", "chg"), ageMin = 2.0))
        assertFalse(DataPipeline8191.claimPairFetch8191(mint, now + 1, setOf("bp", "chg"), ageMin = 2.0))
    }

    @Test fun matureFlowRetainsItsNormalFreshnessWindow() {
        PairFlow8169.note8169(mint, flow(at = now - 120_000L))
        assertTrue(DataPipeline8191.pairFlowSatisfies8191(PairFlow8169.peek8169(mint), setOf("bp", "chg", "liq"), now, ageMin = 120.0))
        assertFalse(DataPipeline8191.claimPairFetch8191(mint, now, setOf("bp", "chg", "liq"), ageMin = 120.0))
        assertTrue(DataPipeline8191.claimPairFetch8191(mint, now + 15L * 60_000L, setOf("bp", "chg", "liq"), ageMin = 135.0))
    }

    @Test fun aFreshPairWithOnlyLiquidityCannotSuppressABuyPressureRepair() {
        PairFlow8169.note8169(mint, flow(buys = -1, sells = -1, chg5m = Double.NaN, chg1h = Double.NaN))
        assertFalse(DataPipeline8191.claimPairFetch8191(mint, now, setOf("liq"), ageMin = 1.0))
        assertTrue(DataPipeline8191.claimPairFetch8191(mint, now, setOf("bp"), ageMin = 1.0))
        assertFalse(DataPipeline8191.claimPairFetch8191(mint, now + 1, setOf("chg"), ageMin = 1.0))
    }

    @Test fun observedZeroChangesAreDataButAMissingHorizonIsNot() {
        val zero = flow()
        assertTrue(DataPipeline8191.pairFlowSatisfies8191(zero, setOf("chg"), now, ageMin = 1.0))
        assertFalse(DataPipeline8191.pairFlowSatisfies8191(flow(chg1h = Double.NaN), setOf("chg"), now, ageMin = 1.0))
        assertFalse(DataPipeline8191.pairFlowSatisfies8191(flow(buys = 0, sells = 0), setOf("bp"), now, ageMin = 1.0))
        assertFalse(DataPipeline8191.pairFlowSatisfies8191(zero, setOf("mcap"), now, ageMin = 1.0))
        assertFalse(DataPipeline8191.pairFlowSatisfies8191(flow(at = now + 60_001L), setOf("bp"), now, ageMin = 1.0))
    }

    @Test fun aCompleteAnswerEndsTheSharedRepairWithoutInventingMissingFields() {
        assertTrue(DataPipeline8191.claimPairFetch8191(mint, now, setOf("bp", "chg"), ageMin = 1.0))
        PairFlow8169.note8169(mint, flow(at = now + 5_000L))
        assertTrue(DataPipeline8191.pairFlowSatisfies8191(PairFlow8169.peek8169(mint), setOf("bp", "chg"), now + 5_000L, ageMin = 1.1))
        assertFalse(DataPipeline8191.claimPairFetch8191(mint, now + 60_000L, setOf("bp", "chg"), ageMin = 2.0))
        assertTrue(DataPipeline8191.claimPairFetch8191(mint, now + 65_001L, setOf("bp", "chg"), ageMin = 2.1))
    }

    @Test fun freshLiquidityCannotSatisfyAMissingExecutableRouteProof() {
        for (missingKey in listOf("route", "token_map", "quote", "sellability")) {
            val routeMint = mint + missingKey
            PairFlow8169.note8169(routeMint, flow())
            val inputs = DataPipeline8191.pairRepairInputs8237(DataDeferFetch8180.inputsFor8180(listOf("$missingKey:PENDING")))
            assertEquals(setOf(missingKey, "liq"), inputs)
            assertFalse(DataPipeline8191.pairFlowSatisfies8191(PairFlow8169.peek8169(routeMint), inputs, now, ageMin = 2.0))
            assertTrue(missingKey, DataPipeline8191.claimPairFetch8191(routeMint, now, inputs, ageMin = 2.0))
            assertFalse(missingKey, DataPipeline8191.claimPairFetch8191(routeMint, now + 1L, inputs, ageMin = 2.0))
        }
    }

    @Test fun simultaneousRepairsFromAllTwelveLanesMakeOnlyOneProviderClaim() {
        val pool = Executors.newFixedThreadPool(12)
        val ready = CountDownLatch(12)
        val start = CountDownLatch(1)
        try {
            val calls = (0 until 12).map { lane ->
                pool.submit<Boolean> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    val input = listOf("bp", "chg", "liq")[lane % 3]
                    DataPipeline8191.claimPairFetch8191(mint, now, setOf(input), ageMin = 1.0)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(1, calls.count { it.get(5, TimeUnit.SECONDS) })
            assertFalse(DataPipeline8191.claimPairFetch8191(mint, now + 59_999L, setOf("bp"), ageMin = 2.0))
            assertTrue(DataPipeline8191.claimPairFetch8191(mint, now + 60_000L, setOf("bp"), ageMin = 2.0))
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }
}

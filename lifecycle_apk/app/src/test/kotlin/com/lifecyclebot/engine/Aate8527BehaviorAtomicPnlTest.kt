package com.lifecyclebot.engine

import com.lifecyclebot.v3.scoring.BehaviorAI
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Actual production recording must finish and retain every concurrent PnL addition. */
class Aate8527BehaviorAtomicPnlTest {
    private val asset = "BEHAVIOR_ATOMIC_8527"
    private val otherAsset = "BEHAVIOR_ATOMIC_OTHER_8527"

    @Before fun before() = clearOwnRows()
    @After fun after() = clearOwnRows()

    private fun clearOwnRows() {
        for (name in listOf("perAssetTrades", "perAssetWins", "perAssetLosses",
                "perAssetReasonCount", "perAssetReasonPnlSum")) {
            val field = BehaviorAI.javaClass.getDeclaredField(name).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val rows = field.get(BehaviorAI) as MutableMap<String, Any>
            rows.keys.filter { it == asset || it == otherAsset ||
                it.startsWith("$asset#") || it.startsWith("$otherAsset#") }.forEach { rows.remove(it) }
        }
    }

    @Test(timeout = 2_000L)
    fun uncontendedFiniteOutcomesReturnAndKeepTheirSignedSum() {
        BehaviorAI.recordTradeForAsset(12.5, "TAKE_PROFIT_FIRST", "atomic8527a", true, asset)
        BehaviorAI.recordTradeForAsset(-2.5, "TAKE_PROFIT_SECOND", "atomic8527b", false, asset)
        val row = BehaviorAI.getReasonStatsSnapshot()["$asset#TAKE_PROFIT"]!!
        assertEquals(2, row.first)
        assertEquals(10.0, row.second, 0.0)
        assertEquals(5.0, row.third, 0.0)
    }

    @Test(timeout = 10_000L)
    fun concurrentActualRecordersNeverLoseFiniteUpdates() {
        // Daemon workers keep a regressed non-interruptible CAS spin from holding
        // the test process open after its bounded future/JUnit timeout reports it.
        val pool = Executors.newFixedThreadPool(4) { task ->
            Thread(task, "BehaviorAtomic8527").apply { isDaemon = true }
        }
        val start = CountDownLatch(1)
        try {
            val work = (0 until 4).map { worker ->
                pool.submit {
                    assertTrue(start.await(3, TimeUnit.SECONDS))
                    repeat(250) { index ->
                        BehaviorAI.recordTradeForAsset(0.125, "TAKE_PROFIT", "atomic8527-$worker-$index", true, asset)
                    }
                }
            }
            start.countDown()
            work.forEach { it.get(3, TimeUnit.SECONDS) }
            val row = BehaviorAI.getReasonStatsSnapshot()["$asset#TAKE_PROFIT"]!!
            assertEquals(1_000, row.first)
            assertEquals(125.0, row.second, 0.0)
            assertEquals(0.125, row.third, 0.0)
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }

    @Test(timeout = 2_000L)
    fun nonFiniteOutcomesStillCannotPoisonTheReasonAccumulator() {
        for (pnl in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            BehaviorAI.recordTradeForAsset(pnl, "TAKE_PROFIT", "atomic8527invalid", true, asset)
        }
        assertFalse(BehaviorAI.getReasonStatsSnapshot().containsKey("$asset#TAKE_PROFIT"))
    }

    @Test(timeout = 2_000L)
    fun canonicalReasonAndAssetBucketsRemainSeparate() {
        BehaviorAI.recordTradeForAsset(-3.0, "STRICT_SL_-3", "atomic8527stop", true, asset)
        BehaviorAI.recordTradeForAsset(7.0, "TAKE_PROFIT", "atomic8527take", true, asset)
        BehaviorAI.recordTradeForAsset(11.0, "TAKE_PROFIT", "atomic8527other", true, otherAsset)
        val rows = BehaviorAI.getReasonStatsSnapshot()
        assertEquals(Triple(1, -3.0, -3.0), rows["$asset#STRICT_SL"])
        assertEquals(Triple(1, 7.0, 7.0), rows["$asset#TAKE_PROFIT"])
        assertEquals(Triple(1, 11.0, 11.0), rows["$otherAsset#TAKE_PROFIT"])
    }
}

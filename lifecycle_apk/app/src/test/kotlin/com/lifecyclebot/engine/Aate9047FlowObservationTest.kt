package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.market.BuyerFlow8666
import com.lifecyclebot.engine.truth.LearnerColumns8798
import com.lifecyclebot.engine.truth.SolTrend8798
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/** Actual producers, playbook extraction and named learner columns; no synthesized zero-to-NaN rule. */
class Aate9047FlowObservationTest {
    private val now = System.currentTimeMillis()
    private val names = listOf("buyTx15s", "buyTxPrev15s", "repeatBuyers60s")

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).also { it.isAccessible = true }
    @Suppress("UNCHECKED_CAST") private fun <T> read(owner: Any, name: String): T = field(owner, name).get(owner) as T

    private fun withMint(label: String, body: (TokenState) -> Unit) {
        // An isolated in-memory key, outside the remote metadata request length.
        val mint = "test-only-flow-observation-9047-$label-${System.nanoTime()}"
        val ts = TokenState(mint = mint)
        val arrays = listOf("seen", "finite").map { name ->
            val array = read<AtomicLongArray>(LearnerColumns8798, name)
            array to LongArray(array.length()) { array.get(it) }
        }
        val oldRows = read<Long>(LearnerColumns8798, "rows")
        val buyerCounters = BuyerFlow8666.javaClass.declaredFields.filter { it.type == AtomicLong::class.java }.map {
            it.isAccessible = true
            val counter = it.get(BuyerFlow8666) as AtomicLong
            counter to counter.get()
        }
        val trendPrices = read<DoubleArray>(SolTrend8798, "px")
        val trendMinutes = read<LongArray>(SolTrend8798, "minute")
        val oldPrices = trendPrices.copyOf(); val oldMinutes = trendMinutes.copyOf()
        val maps = listOf(BuyerFlow8666 to "prints", LanePlaybook7907 to "lastF8399", WhaleDetector to "launchTrades7401").map { (owner, name) ->
            val map = read<MutableMap<String, Any>>(owner, name)
            map to map.toMap()
        }
        try { body(ts) } finally {
            WhaleDetector.clearToken(mint)
            // Restore owner maps as well as the unique keys: bounded producers may
            // have evicted an older entry while accepting this fixture's mint.
            maps.forEach { (map, original) -> map.clear(); map.putAll(original) }
            arrays.forEach { (array, original) -> original.indices.forEach { array.set(it, original[it]) } }
            field(LearnerColumns8798, "rows").setLong(LearnerColumns8798, oldRows)
            buyerCounters.forEach { (counter, original) -> counter.set(original) }
            synchronized(SolTrend8798) { oldPrices.copyInto(trendPrices); oldMinutes.copyInto(trendMinutes) }
        }
    }

    private fun counts(ts: TokenState, at: Long = now): DoubleArray {
        val frame = LanePlaybook7907.features(ts, at)
        val columns = LearnerColumns8798.extract(ts, frame, at)
        val actual = names.map { LearnerColumns8798.valueOf8873(columns, it) }.toDoubleArray()
        val frameValues = doubleArrayOf(frame.buyTx15s, frame.buyTxPrev15s, frame.repeatBuyers60s)
        actual.indices.forEach { i ->
            if (frameValues[i].isNaN()) assertTrue(actual[i].isNaN()) else assertEquals(frameValues[i], actual[i], 0.0)
        }
        val row = JSONObject("{\"t\":$at${LearnerColumns8798.toRow(columns)}}")
        val restored = LearnerColumns8798.fromRow(row)
        actual.indices.forEach { i ->
            if (actual[i].isNaN()) {
                assertFalse("unobserved ${names[i]} cannot be serialized as a measured zero", row.has(names[i]))
                assertTrue(LearnerColumns8798.valueOf8873(restored, names[i]).isNaN())
            } else assertEquals(actual[i], LearnerColumns8798.valueOf8873(restored, names[i]), 0.0)
        }
        return actual
    }

    private fun unknown(x: DoubleArray) = x.forEach { assertTrue("missing flow must remain unknown: ${x.toList()}", it.isNaN()) }

    @Test fun absentMintRemainsUnknownThroughTheActualFrameAndLearnerExtractor() = withMint("absent") { ts ->
        assertFalse(WhaleDetector.launchFlow7401(ts.mint, nowMs = now).countsObserved9047(now))
        assertNull(BuyerFlow8666.read(ts.mint, now))
        unknown(counts(ts))
    }

    @Test fun observedSellsOnlyWindowPreservesRealZeroCounts() = withMint("sells") { ts ->
        WhaleDetector.recordLaunchOnly8190(ts.mint, "seller", 0.5, false, now - 1_000L)
        assertTrue(WhaleDetector.launchFlow7401(ts.mint, nowMs = now).countsObserved9047(now))
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0), counts(ts), 0.0)
    }

    @Test fun observedPositiveLegacyCountsAndRepeatBuyersAreNotDiscarded() = withMint("buys") { ts ->
        WhaleDetector.recordLaunchOnly8190(ts.mint, "alice", 0.5, true, now - 28_000L)
        WhaleDetector.recordLaunchOnly8190(ts.mint, "alice", 0.5, true, now - 14_000L)
        WhaleDetector.recordLaunchOnly8190(ts.mint, "bob", 0.5, true, now - 3_000L)
        assertArrayEquals(doubleArrayOf(2.0, 1.0, 1.0), counts(ts), 0.0)
    }

    @Test fun legacyWindowBoundariesAndExpiryUseTheActualPrintTime() = withMint("expiry") { ts ->
        WhaleDetector.recordLaunchOnly8190(ts.mint, "alice", 0.5, true, now)
        fun flow(age: Long) = WhaleDetector.launchFlow7401(ts.mint, nowMs = now + age)
        assertEquals(1, flow(15_000L).buyTx15s)
        assertEquals(0, flow(15_001L).buyTx15s)
        assertEquals(1, flow(15_001L).buyTxPrev15s)
        assertEquals(1, flow(30_000L).buyTxPrev15s)
        assertEquals(0, flow(30_001L).buyTxPrev15s)
        assertTrue(flow(60_000L).countsObserved9047(now + 60_000L))
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0), counts(ts, now + 60_000L), 0.0)
        assertFalse(flow(60_001L).countsObserved9047(now + 60_001L))
        unknown(counts(ts, now + 60_001L))
    }

    @Test fun futureOnlyRowsDoNotInventObservationOrBuyCounts() = withMint("future") { ts ->
        WhaleDetector.recordLaunchOnly8190(ts.mint, "future-buyer", 0.5, true, now + 1L)
        assertFalse(WhaleDetector.launchFlow7401(ts.mint, nowMs = now).countsObserved9047(now))
        unknown(counts(ts))
        WhaleDetector.recordLaunchOnly8190(ts.mint, "seller", 0.5, false, now - 1L)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0), counts(ts), 0.0)
    }

    @Test fun freshSignedZeroOutranksPositiveLegacyCounts() = withMint("priority") { ts ->
        repeat(3) { WhaleDetector.recordLaunchOnly8190(ts.mint, "old-$it", 0.5, true, now - 1_000L) }
        BuyerFlow8666.note(ts.mint, "seller", 0.5, false, now - 500L, "signed-sell-9047", now)
        assertEquals(3, WhaleDetector.launchFlow7401(ts.mint, nowMs = now).buyTx15s)
        assertNotNull(BuyerFlow8666.read(ts.mint, now))
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0), counts(ts), 0.0)
    }

    @Test fun expiredSignedSourceFallsBackOnlyToAnObservedLegacyWindow() = withMint("fallback") { ts ->
        BuyerFlow8666.note(ts.mint, "signed-buyer", 0.5, true, now, "signed-buy-9047", now)
        repeat(3) { WhaleDetector.recordLaunchOnly8190(ts.mint, "legacy-$it", 0.5, true, now + 7_000L + it * 1_000L) }
        assertArrayEquals(doubleArrayOf(1.0, 0.0, 0.0), counts(ts, now + 10_000L), 0.0)
        assertArrayEquals(doubleArrayOf(0.0, 1.0, 0.0), counts(ts, now + 20_000L), 0.0)
        assertNull(BuyerFlow8666.read(ts.mint, now + 20_001L))
        assertArrayEquals(doubleArrayOf(3.0, 0.0, 0.0), counts(ts, now + 20_001L), 0.0)
        unknown(counts(ts, now + 70_001L))
    }

    @Test fun historicalZeroColumnsAreRetainedWithoutInventingTheirProvenance() {
        val row = JSONObject("""{"t":1800000000000,"buyTx15s":0,"buyTxPrev15s":0,"repeatBuyers60s":0}""")
        val originalSize = LearnerColumns8798.NAMES.size
        val decoded = LearnerColumns8798.fromRow(row)
        assertEquals(originalSize, decoded.size)
        names.forEach { assertEquals(0.0, LearnerColumns8798.valueOf8873(decoded, it), 0.0) }
        val encoded = JSONObject("{\"t\":1800000000000${LearnerColumns8798.toRow(decoded)}}")
        names.forEach { assertTrue(encoded.has(it)); assertEquals(0.0, encoded.getDouble(it), 0.0) }
    }
}

package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.cortex.CortexDecisionSnapshot8522
import com.lifecyclebot.engine.cortex.CortexVoterMemo8522
import com.lifecyclebot.engine.truth.MintSupply8418
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong

class Aate8522CortexDecisionCaptureTest {
    private val at = 1_000_000L
    private fun candle(price: Double) = Candle(at, price, 10_000.0, 100.0, 200.0)

    @Test fun queuedDecisionKeepsItsChartsRoutePositionAndMeasuredFeatureClocks() {
        val token = TokenState("capture8522", lastPrice = 1.0, lastPriceUpdate = at)
        val charts = listOf(token.history, token.history5m, token.history15m, token.history1h, token.history4h)
        charts.forEach { it.add(candle(1.0)) }
        token.position.tradingMode = "QUALITY"
        token.position.highestPrice = 1.0
        token.tokenMap.volume1hUsd = 1_000.0
        token.tokenMap.providerTimestamps["POOL"] = at
        token.tokenMap.hydrationFailureReasons.add("ROUTE_PENDING")
        token.trades.add(Trade("SELL", "PAPER", 1.0, 1.0, at, pnlPct = 5.0))
        token.recentEntryTimes.add(at)
        token.laneAffinity.add("QUALITY")
        token.toolAffinity.add("SCANNER")
        val reasons = mutableListOf("UNKNOWN_LP")
        val objections = mutableListOf("THIN_TAPE")
        val pools = mutableListOf(LiquidityOwnership8522.Pool("original-pool", "raydium", 0.0, 20_000.0))
        token.safety = SafetyReport(hardBlockReasons = reasons,
            lpOwnership8522 = LiquidityOwnership8522.Evidence(token.mint, pools, explicitUnlocked = true, riskInventoryComplete = true))
        token.lastConsensusObjections = objections
        token.buyPressureObservedAtMs8084 = at - 10
        token.priceChangeObservedAtMs8084 = at - 20
        token.hourChangeObservedAtMs8506 = at - 30
        token.mintSupply8418 = MintSupply8418.Supply8418(token.mint, BigInteger.valueOf(1_000_000), 6, 123L,
            at - 40, MintSupply8418.Source8418.MINT_ACCOUNT)

        val frozen = CortexDecisionSnapshot8522().capture(token, at, at)
        // These are mutations that occur while a cold capture waits behind other lane reads.
        charts.forEach { it.clear(); it.add(candle(9.0)) }
        token.lastPrice = 9.0
        token.position.tradingMode = "MOONSHOT"
        token.position.highestPrice = 9.0
        token.tokenMap.volume1hUsd = 99_000.0
        token.tokenMap.providerTimestamps["POOL"] = at + 60_000
        token.tokenMap.hydrationFailureReasons.clear()
        token.trades.single().pnlPct = 900.0
        token.recentEntryTimes.clear()
        token.laneAffinity.clear()
        token.toolAffinity.clear()
        reasons.clear()
        objections.clear()
        pools.clear()
        token.buyPressureObservedAtMs8084 = 0L
        token.priceChangeObservedAtMs8084 = 0L
        token.hourChangeObservedAtMs8506 = 0L
        token.mintSupply8418 = null

        assertEquals(1.0, frozen.lastPrice, 0.0)
        listOf(frozen.history, frozen.history5m, frozen.history15m, frozen.history1h, frozen.history4h).forEach {
            assertEquals(1.0, it.single().priceUsd, 0.0)
        }
        assertEquals("QUALITY", frozen.position.tradingMode)
        assertEquals(1.0, frozen.position.highestPrice, 0.0)
        assertEquals(1_000.0, frozen.tokenMap.volume1hUsd!!, 0.0)
        assertEquals(at, frozen.tokenMap.providerTimestamps["POOL"])
        assertEquals(listOf("ROUTE_PENDING"), frozen.tokenMap.hydrationFailureReasons)
        assertEquals(5.0, frozen.trades.single().pnlPct, 0.0)
        assertEquals(listOf(at), frozen.recentEntryTimes)
        assertEquals(setOf("QUALITY"), frozen.laneAffinity)
        assertEquals(setOf("SCANNER"), frozen.toolAffinity)
        assertEquals(listOf("UNKNOWN_LP"), frozen.safety.hardBlockReasons)
        assertEquals(listOf("THIN_TAPE"), frozen.lastConsensusObjections)
        assertEquals("original-pool", frozen.safety.lpOwnership8522.pools.single().address)
        assertTrue(frozen.safety.lpOwnership8522.explicitUnlocked)
        assertEquals(at - 10, frozen.buyPressureObservedAtMs8084)
        assertEquals(at - 20, frozen.priceChangeObservedAtMs8084)
        assertEquals(at - 30, frozen.hourChangeObservedAtMs8506)
        assertEquals(at - 40, frozen.mintSupply8418!!.observedAtMs)
        assertEquals(BigInteger.valueOf(1_000_000), frozen.mintSupply8418!!.rawAmount)
    }

    @Test fun oneNativeCommitteeEventSharesAReadOnlyInputAndNextDecisionGetsNewFeatures() {
        val snapshots = CortexDecisionSnapshot8522()
        val token = TokenState("committee8522", lastPrice = 1.0, lastPriceUpdate = at)
        val first = snapshots.capture(token, at, at)
        token.lastPrice = 2.0
        repeat(12) { assertSame(first, snapshots.capture(token, at, at + it)) }
        assertEquals(1.0, first.lastPrice, 0.0)
        val nextDecision = snapshots.capture(token, at + 1, at + 20)
        assertNotSame(first, nextDecision)
        assertEquals(2.0, nextDecision.lastPrice, 0.0)
        val differentSource = TokenState(token.mint, lastPrice = 3.0, lastPriceUpdate = at)
        assertNotSame(nextDecision, snapshots.capture(differentSource, at + 1, at + 20))
    }

    @Test fun sharedSnapshotRetentionIsBoundedAndDoesNotDiscardAlreadyQueuedInputs() {
        val snapshots = CortexDecisionSnapshot8522(capacity = 2, retentionMs = 100L)
        val token = TokenState("bounds8522", lastPrice = 1.0)
        val queued = snapshots.capture(token, at, at)
        snapshots.capture(token, at + 1, at + 1)
        snapshots.capture(token, at + 2, at + 2)
        assertEquals(2, snapshots.size())
        token.lastPrice = 5.0
        assertEquals(1.0, queued.lastPrice, 0.0)
        assertEquals(2, snapshots.trim(at + 103))
        assertEquals(0, snapshots.size())
        assertEquals(5.0, snapshots.capture(token, at + 103, at + 103).lastPrice, 0.0)
    }

    @Test fun simultaneousNativeLanesReadThe288LaneIndependentVotersOnlyOnce() {
        val snapshots = CortexDecisionSnapshot8522()
        val token = TokenState("fanout8522", lastPrice = 1.0, lastPriceUpdate = at)
        val free = BooleanArray(331) { it < 288 }
        val memo = CortexVoterMemo8522(free, clock = { at })
        val calls = AtomicIntegerArray(free.size)
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(12)
        try {
            val jobs = (0 until 12).map { lane -> workers.submit<Pair<TokenState, DoubleArray>> {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                val frozen = snapshots.capture(token, at, at)
                val reads = memo.read(token.mint, frozen, frozen.lastPriceUpdate, at, null, IntArray(free.size) { it }) { i ->
                    calls.incrementAndGet(i)
                    if (free[i]) i.toDouble() else lane.toDouble()
                }
                frozen to reads
            } }
            start.countDown()
            val completed = jobs.map { it.get(5, TimeUnit.SECONDS) }
            completed.forEachIndexed { lane, result ->
                assertSame(completed.first().first, result.first)
                repeat(free.size) { i -> assertEquals(if (free[i]) i.toDouble() else lane.toDouble(), result.second[i], 0.0) }
            }
            repeat(free.size) { i -> assertEquals("voter $i", if (free[i]) 1 else 12, calls.get(i)) }
            assertEquals(1L, memo.misses.get())
            assertEquals(11L, memo.hits.get())
        } finally { workers.shutdownNow() }
    }

    @Test fun budgetAbstentionInOneLaneCannotSuppressAnotherLanesSeatedVoterOrMutateTheFirstPrediction() {
        val wall = AtomicLong(at)
        val memo = CortexVoterMemo8522(booleanArrayOf(true), clock = wall::get)
        val input = Any()
        val reads = AtomicInteger(0)
        val first = memo.read("core8522", input, at, at, booleanArrayOf(false), intArrayOf(0)) {
            reads.incrementAndGet(); Double.NaN // first lane ran out of its non-core read budget
        }
        wall.set(at + 1_000)
        val core = memo.read("core8522", input, at, at + 1_000, booleanArrayOf(true), intArrayOf(0)) {
            reads.incrementAndGet(); 42.0
        }
        assertTrue(first.single().isNaN())
        assertEquals(42.0, core.single(), 0.0)
        assertEquals(2, reads.get())
        core[0] = 999.0 // the memo must not borrow a returned assessment's mutable array either
        val next = memo.read("core8522", input, at, at + 1_000, null, intArrayOf(0)) { error("filled shared voter was reread") }
        assertEquals(42.0, next.single(), 0.0)
        wall.set(at + 3_001)
        val expired = memo.read("core8522", input, at, at + 3_001, null, intArrayOf(0)) { reads.incrementAndGet(); 84.0 }
        assertEquals("supplementing a missing voter must not rejuvenate the entire memo", 84.0, expired.single(), 0.0)
        assertEquals(3, reads.get())
    }

    @Test fun memoNeverCarriesReadsAcrossChangedInputPriceClockOrDecisionWindow() {
        val wall = AtomicLong(at)
        val memo = CortexVoterMemo8522(booleanArrayOf(true), clock = wall::get)
        val calls = AtomicInteger()
        fun read(input: Any, price: Long = at, decision: Long = wall.get()): Double =
            memo.read("identity8522", input, price, decision, null, intArrayOf(0)) { calls.incrementAndGet().toDouble() }.single()
        val input = Any()
        assertEquals(1.0, read(input), 0.0)
        assertEquals(1.0, read(input), 0.0)
        assertEquals(2.0, read(Any()), 0.0)
        assertEquals(3.0, read(input), 0.0)
        assertEquals(4.0, read(input, at + 1), 0.0)
        wall.set(at - 1)
        assertEquals(5.0, read(input, at + 1), 0.0)
        assertEquals(6.0, read(input, at + 1, decision = at + 4_000), 0.0)
    }
}

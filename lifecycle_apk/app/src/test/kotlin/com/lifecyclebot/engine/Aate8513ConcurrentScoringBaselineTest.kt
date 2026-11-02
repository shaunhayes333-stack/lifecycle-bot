package com.lifecyclebot.engine

import com.lifecyclebot.v3.scoring.OrderFlowImbalanceAI
import com.lifecyclebot.v3.scoring.VolatilityRegimeAI
import com.lifecyclebot.v3.scoring.SmartMoneyDivergenceAI
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Executes the real brains as the market-tick and V3 callers do. No market/fill simulation. */
class Aate8513ConcurrentScoringBaselineTest {
    private fun concurrentReadersAndWriters(label: String, call: (String) -> Unit) {
        val mint = "8513-$label-${System.nanoTime()}"
        call(mint) // Warm singleton initialization before releasing concurrent callers.
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val errors = ConcurrentLinkedQueue<Throwable>()
        try {
            val jobs = (0 until 8).map {
                pool.submit {
                    start.await()
                    repeat(200) {
                        try { call(mint) } catch (t: Throwable) { errors.add(t) }
                    }
                }
            }
            start.countDown()
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
            assertTrue("$label corrupted shared state: ${errors.take(5).map { it.javaClass.simpleName + ":" + it.message }} (n=${errors.size})", errors.isEmpty())
        } finally { pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS) }
    }

    @Test fun orderFlowConcurrentTickAndScoreRemainConsistent() {
        concurrentReadersAndWriters("orderflow") { mint ->
            val signal = OrderFlowImbalanceAI.analyze(mint, "TEST", List(30) { 10.0 }, List(30) { 10.0 }, List(30) { 1.0 })
            assertTrue(signal.cumulativeDelta.isFinite())
            assertTrue(signal.absorptionLevel.isFinite())
            assertTrue(OrderFlowImbalanceAI.getCumulativeDelta(mint).isFinite())
        }
    }

    @Test fun volatilityConcurrentTickAndScoreRemainConsistent() {
        concurrentReadersAndWriters("volatility") { mint ->
            val signal = VolatilityRegimeAI.analyze(mint, "TEST", List(30) { 1.01 }, List(30) { 0.99 }, List(30) { 1.0 })
            assertTrue(signal.currentAtr.isFinite())
            assertTrue(signal.squeezeScore.isFinite())
            VolatilityRegimeAI.isInSqueeze(mint)
        }
    }

    @Test fun smartMoneyConcurrentTickAndScoreRemainConsistent() {
        concurrentReadersAndWriters("smartmoney") { mint ->
            val signal = SmartMoneyDivergenceAI.analyze(mint, "TEST", List(30) { 1.0 }, whaleRecommendation = "NEUTRAL")
            assertTrue(signal.entryBoost in -100..100)
        }
    }
}

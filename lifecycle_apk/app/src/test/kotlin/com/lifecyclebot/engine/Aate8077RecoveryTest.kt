package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalPaperTransaction6486
import com.lifecyclebot.engine.truth.EconomicEventSchema6464
import com.lifecyclebot.engine.truth.HeldQuoteBatch8077
import com.lifecyclebot.network.NetworkRetry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Aate8077RecoveryTest {
    private val qty = BigInteger("1000000000")
    private fun buy() = EconomicEventSchema6464.Buy(1, "paper", "p", "m", "M", "buy",
        0.001, 0.000001, qty, 0.12)
    private fun sell() = EconomicEventSchema6464.Sell(2, "paper", "p", "m", "M", "sell",
        false, qty, 0.001, 0.0011, 0.000001, 0.001099, 0.000099, 9.9,
        BigInteger.ZERO, 0.0, 0.132, 120.0)

    @Test fun durableSellRetainsItsOriginalValuationProof() {
        val original = sell()
        assertEquals(original, EconomicEventSchema6464.decode6486(EconomicEventSchema6464.encode6486(original)))
        val legacy = JSONObject(EconomicEventSchema6464.encode6486(original))
        legacy.remove("exitPriceUsd"); legacy.remove("solUsdAtExit")
        val restored = EconomicEventSchema6464.decode6486(legacy.toString()) as EconomicEventSchema6464.Sell
        assertEquals(0.0, restored.exitPriceUsd, 0.0)
        assertEquals(0.0, restored.solUsdAtExit, 0.0)
    }

    @Test fun journalEntryFeeExcludesExitFeesWithEitherCompleteProof() {
        fun fee(buys: List<EconomicEventSchema6464.Buy>, sells: List<EconomicEventSchema6464.Sell>) =
            CanonicalPaperTransaction6486.historicalEntryFees8077(0.001, qty, 0.000002, 0.001, qty, buys, sells)
        assertEquals(0.000001, fee(listOf(buy()), listOf(sell()))!!, 1e-15)
        assertEquals(0.000001, fee(emptyList(), listOf(sell()))!!, 1e-15)
        assertNull(fee(emptyList(), emptyList()))
        assertNull(fee(emptyList(), listOf(sell().copy(allocatedCostBasisSol = Double.NaN))))
        assertNull(fee(emptyList(), listOf(sell().copy(exitFeesSol = Double.POSITIVE_INFINITY))))
        // Principal + entry fee + exit fee are charged exactly once.
        val cashChange = -buy().executedCostSol - fee(listOf(buy()), listOf(sell()))!! + sell().netProceedsSol
        assertEquals(0.000098, cashChange, 1e-15)
    }

    @Test fun stalledFirstQuoteCannotConsumeANewDeadlineForEveryHolding() {
        val pool = Executors.newFixedThreadPool(4)
        val entered = CountDownLatch(3)
        val interrupted = CountDownLatch(3)
        val ready = CountDownLatch(1)
        try {
            val requests = linkedMapOf<String, () -> Double?>()
            repeat(3) { i -> requests["stalled$i"] = {
                entered.countDown()
                try { CountDownLatch(1).await(); null }
                finally { interrupted.countDown() }
            } }
            requests["healthy"] = { ready.countDown(); 1.25 }
            val start = System.nanoTime()
            val result = HeldQuoteBatch8077.resolve(requests, pool, 500)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertEquals(0L, entered.count)
            assertEquals(0L, ready.count)
            assertTrue("batch took $elapsedMs ms", elapsedMs < 1200)
            assertEquals(1.25, result.getValue("healthy").price!!, 0.0)
            repeat(3) { assertTrue(result.getValue("stalled$it").timedOut) }
            assertTrue(interrupted.await(2, TimeUnit.SECONDS))
        } finally { pool.shutdownNow() }
    }

    @Test fun manualWebsocketAttemptsObserveCooldownAndProbeFailure() {
        val retry = NetworkRetry("test", failureThreshold = 1, openDurationMs = 60_000)
        assertTrue(retry.allowManualAttempt8077())
        retry.recordFailure()
        assertFalse(retry.allowManualAttempt8077())
        assertTrue(retry.allowManualAttempt8077(System.currentTimeMillis() + 60_001))
        retry.recordFailure()
        assertFalse(retry.allowManualAttempt8077())
        retry.recordSuccess()
        assertTrue(retry.allowManualAttempt8077())
    }
}

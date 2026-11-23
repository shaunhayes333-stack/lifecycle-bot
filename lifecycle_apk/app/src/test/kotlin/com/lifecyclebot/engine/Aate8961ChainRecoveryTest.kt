package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.market.MarketSweep7297
import com.lifecyclebot.engine.market.PocketRecovery8961
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class Aate8961ChainRecoveryTest {
    private val mint = "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr"
    private val pool = "So11111111111111111111111111111111111111112"

    private fun pending(): PocketRecovery8961.Pending {
        val row = MarketSweep7297.Row(mint, "COIN", "Coin", 0.01, 50_000.0, Double.NaN,
            10_000.0, 20_000.0, Double.NaN, 20, 50, 0.0, false, 0.1, setOf("dexscreener"),
            observedAtMs8169 = 9_000L)
        return PocketRecovery8961.Pending(row, TokenState(mint = mint), setOf("CORE"), 10_000L, 7L)
    }

    @Test fun aLatePoolStartsItsReadImmediatelyAndDoesNotRewriteTheObservationClock() {
        val p = pending()
        var reads = 0
        assertTrue(PocketRecovery8961.startPoolRead8961(p, pool, 11_000L, 7L) {
            reads++
            assertTrue("a selected pool does not prove the sweep price's venue", p.token.lastPricePoolAddr.isBlank())
            assertEquals(pool, p.token.tokenMap.poolAddress)
            assertEquals(pool, p.token.tokenMap.pairAddress)
        })
        assertEquals(1, reads)
        assertEquals(9_000L, p.row.observedAtMs8169)
        assertEquals(10_000L, p.askedAtMs)
        assertEquals(setOf("CORE"), p.lanes)
        assertTrue("a pool address alone cannot supply liquidity", p.token.lastLiquidityUsd <= 0.0 || !p.token.lastLiquidityUsd.isFinite())
    }

    @Test fun racingPairCallbacksAndDiscoveryPassesStartOnlyOneRead() {
        val p = pending()
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val reads = AtomicInteger()
        val claims = AtomicInteger()
        val workers = (0 until 8).map {
            Thread {
                ready.countDown()
                try {
                    start.await(5, TimeUnit.SECONDS)
                    if (PocketRecovery8961.startPoolRead8961(p, pool, 11_000L, 7L) { reads.incrementAndGet() })
                        claims.incrementAndGet()
                } finally { done.countDown() }
            }.apply { isDaemon = true; start() }
        }
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(1, claims.get())
            assertEquals(1, reads.get())
            assertFalse(PocketRecovery8961.startPoolRead8961(p, pool, 12_000L, 7L) { reads.incrementAndGet() })
            assertEquals(1, reads.get())
        } finally {
            start.countDown()
            workers.forEach { it.join(1_000L) }
        }
    }

    @Test fun staleFutureOrOtherGenerationCallbacksDoNotConsumeAUsableRead() {
        val p = pending()
        var reads = 0
        for ((at, generation) in listOf(9_999L to 7L, 130_001L to 7L, 11_000L to 8L)) {
            assertFalse(PocketRecovery8961.startPoolRead8961(p, pool, at, generation) { reads++ })
            assertFalse(p.poolReadStarted.get())
        }
        assertFalse(PocketRecovery8961.startPoolRead8961(p, "", 11_000L, 7L) { reads++ })
        assertFalse(PocketRecovery8961.startPoolRead8961(p, mint, 11_000L, 7L) { reads++ })
        assertEquals(0, reads)
        assertTrue(PocketRecovery8961.startPoolRead8961(p, pool, 11_000L, 7L) { reads++ })
        assertEquals(1, reads)
    }

    @Test fun aFailedDispatchCanBeRetriedWithoutInventingAnAnswer() {
        val p = pending()
        try {
            PocketRecovery8961.startPoolRead8961(p, pool, 11_000L, 7L) { throw IllegalStateException("worker unavailable") }
            fail("the dispatch error must propagate to its caller")
        } catch (_: IllegalStateException) {}
        assertFalse(p.poolReadStarted.get())
        assertTrue(PocketRecovery8961.startPoolRead8961(p, pool, 12_000L, 7L) {})
        assertEquals(10_000L, p.askedAtMs)
    }

    @Test fun discoveryCannotPreclaimThePoolThenLoseItsReadToTheNormalRetryGap() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/market/PocketRecovery8961.kt").readText()
        val request = source.substringAfter("fun request(").substringBefore("fun poolAvailable(")
        assertTrue(request.contains("poolAvailable(row.mint, pool, nowMs)"))
        assertFalse(request.contains("poolReadStarted"))
    }
}

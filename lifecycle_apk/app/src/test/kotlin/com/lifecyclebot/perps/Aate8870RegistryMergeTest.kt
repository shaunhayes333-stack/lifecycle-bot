package com.lifecyclebot.perps

import com.lifecyclebot.data.Candle
import com.lifecyclebot.network.PairInfo
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class Aate8870RegistryMergeTest {
    private fun token(mint: String, at: Long) = DynamicAltTokenRegistry.DynToken(
        mint = mint, chainId = "solana", symbol = "CLOCK", name = "Clock", price = 1.0,
        priceObservedAtMs8372 = at, priceChange24h = 7.0, change24hObservedAtMs8752 = at,
        buys24h = 90, sells24h = 10, txns24hObservedAtMs8752 = at,
        buys5m8832 = 9, sells5m8832 = 1, shortWindowObservedAtMs8832 = at)

    private fun pair(at: Long, price: Double = 2.0) = PairInfo(
        pairAddress = "Pool8870", baseSymbol = "CLOCK", baseName = "Clock", url = "",
        candle = Candle(at, price, 0.0, 0.0, 0.0, buys24h = 0, sells24h = 0),
        priceChangeH24 = 0.0, txnsH24Observed = true, buysM5 = 0, sellsM5 = 0)

    @Suppress("UNCHECKED_CAST")
    private fun registry(): ConcurrentHashMap<String, DynamicAltTokenRegistry.DynToken> =
        DynamicAltTokenRegistry::class.java.getDeclaredField("registry").let { field ->
            field.isAccessible = true
            field.get(DynamicAltTokenRegistry) as ConcurrentHashMap<String, DynamicAltTokenRegistry.DynToken>
        }

    @Test fun cachedPairKeepsItsRealH24ClockAndCannotRollBackANewerDaySnapshot() {
        val now = 1_800_000_000_000L
        val original = token("PairClock8870", now - 90_000L)
        val cached = pair(now - 30_000L)
        val merged = DynamicAltTokenRegistry.mergePairObservation8870(original, cached, now)
        assertEquals(cached.candle.ts, merged.priceObservedAtMs8372)
        assertEquals(cached.candle.ts, merged.change24hObservedAtMs8752)
        assertEquals(cached.candle.ts, merged.txns24hObservedAtMs8752)
        assertEquals("a provider-observed zero remains a measured zero", 0.0, merged.priceChange24h, 0.0)
        assertEquals(0, merged.buys24h)
        val newer = token("PairClock8870", now - 10_000L)
        val afterOldReply = DynamicAltTokenRegistry.mergePairObservation8870(newer, cached, now)
        assertEquals(newer.price, afterOldReply.price, 0.0)
        assertEquals(newer.priceChange24h, afterOldReply.priceChange24h, 0.0)
        assertEquals(newer.buys24h, afterOldReply.buys24h)
        assertEquals(newer.change24hObservedAtMs8752, afterOldReply.change24hObservedAtMs8752)
        assertEquals(newer.txns24hObservedAtMs8752, afterOldReply.txns24hObservedAtMs8752)
        val unobserved = DynamicAltTokenRegistry.mergePairObservation8870(newer,
            pair(now).copy(priceChangeH24 = Double.NaN, txnsH24Observed = false), now)
        assertEquals(newer.change24hObservedAtMs8752, unobserved.change24hObservedAtMs8752)
        assertEquals(newer.txns24hObservedAtMs8752, unobserved.txns24hObservedAtMs8752)
    }

    @Test fun futurePairDoesNotReplaceCurrentPriceOrAnyObservedSignalClock() {
        val now = 1_800_000_000_000L
        val original = token("FuturePair8870", now - 1_000L)
        val merged = DynamicAltTokenRegistry.mergePairObservation8870(original, pair(now + 60_000L), now)
        assertEquals(original.price, merged.price, 0.0)
        assertEquals(original.priceObservedAtMs8372, merged.priceObservedAtMs8372)
        assertEquals(original.change24hObservedAtMs8752, merged.change24hObservedAtMs8752)
        assertEquals(original.txns24hObservedAtMs8752, merged.txns24hObservedAtMs8752)
        assertEquals(original.shortWindowObservedAtMs8832, merged.shortWindowObservedAtMs8832)
    }

    @Test fun futureMarkCannotPinTheRowAndARealObservationCanRepairAnAlreadyBadClock() {
        val now = System.currentTimeMillis()
        val original = token("FutureMark8870", now - 3_000L)
        val key = original.canonicalIdentity6544
        val rows = registry()
        rows[key] = original
        try {
            assertFalse(DynamicAltTokenRegistry.observeHeldMarkAt8800(key, 4.0, now + 60_000L))
            assertEquals(original, rows[key])
            assertTrue(DynamicAltTokenRegistry.observeHeldMarkAt8800(key, 2.0, now - 2_000L))
            rows[key] = rows[key]!!.copy(price = 5.0, priceObservedAtMs8372 = now + 60_000L)
            assertTrue("an invalid future clock cannot veto a real market observation",
                DynamicAltTokenRegistry.observeHeldMarkAt8800(key, 3.0, now - 1_000L))
            assertEquals(now - 1_000L, rows[key]!!.priceObservedAtMs8372)
            assertEquals(3.0, rows[key]!!.price, 0.0)
        } finally { rows.remove(key) }
    }

    @Test fun metadataWaitingBehindANewMarketFactMustMergeTheNewCurrentRow() {
        val now = System.currentTimeMillis()
        val old = token("AtomicMeta8870", now - 10_000L)
        val key = old.canonicalIdentity6544
        val rows = registry()
        rows[key] = old
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val upsert = DynamicAltTokenRegistry::class.java.getDeclaredMethod("upsert", DynamicAltTokenRegistry.DynToken::class.java)
            .apply { isAccessible = true }
        val writer = Thread {
            try {
                rows.compute(key) { _, current ->
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    current!!.copy(price = 2.0, priceObservedAtMs8372 = now - 1_000L,
                        buys5m8832 = 20, shortWindowObservedAtMs8832 = now - 1_000L,
                        priceChange24h = 21.0, change24hObservedAtMs8752 = now - 1_000L,
                        buys24h = 200, txns24hObservedAtMs8752 = now - 1_000L)
                }
            } catch (e: Throwable) { failure.compareAndSet(null, e) }
        }
        val metadata = Thread {
            try { upsert.invoke(DynamicAltTokenRegistry, old.copy(price = 0.0, priceObservedAtMs8372 = 0L,
                logoUrl = "observed-logo", buys5m8832 = -1, shortWindowObservedAtMs8832 = 0L, txns24hObservedAtMs8752 = 0L)) }
            catch (e: Throwable) { failure.compareAndSet(null, e) }
        }
        try {
            writer.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            metadata.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (metadata.isAlive && metadata.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals("metadata must actually contend on the same production key", Thread.State.BLOCKED, metadata.state)
        } finally {
            release.countDown()
            writer.join(5_000L)
            metadata.join(5_000L)
        }
        try {
            assertFalse(writer.isAlive)
            assertFalse(metadata.isAlive)
            assertNull(failure.get())
            val merged = rows[key]!!
            assertEquals("observed-logo", merged.logoUrl)
            assertEquals(2.0, merged.price, 0.0)
            assertEquals(now - 1_000L, merged.priceObservedAtMs8372)
            assertEquals(20, merged.buys5m8832)
            assertEquals(now - 1_000L, merged.shortWindowObservedAtMs8832)
            assertEquals(21.0, merged.priceChange24h, 0.0)
            assertEquals(now - 1_000L, merged.change24hObservedAtMs8752)
            assertEquals(200, merged.buys24h)
            assertEquals(now - 1_000L, merged.txns24hObservedAtMs8752)
        } finally { rows.remove(key) }
    }
}

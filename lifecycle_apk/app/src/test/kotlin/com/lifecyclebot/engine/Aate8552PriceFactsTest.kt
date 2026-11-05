package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.TokenFacts
import com.lifecyclebot.engine.truth.TokenFacts.Fact
import com.lifecyclebot.engine.truth.TokenFacts.Rank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8552 — DATA_MAP phase 2: the price and its observation time live in the one store (TokenFacts.PRICE_USD). 38 sites
 * wrote ts.lastPrice / lastPriceUpdate directly with their own clocks: the DexScreener pair poll wrote the price and never
 * its time (it inherited the previous mark's), a meta-cache hydrate stamped an archive price "now" so it passed as a fresh
 * entry quote, the route-cache hydrate replaced a newer mark with an older one, and only two of the writers respected a
 * recent chain tick. Every writer now offers a ranked, timed fact; one rule decides.
 */
class Aate8552PriceFactsTest {
    private val root = File("src/main/kotlin/com/lifecyclebot")
    private fun src(p: String) = File(root, p).readText()
    private fun f(v: Double, rank: Int, at: Long) = Fact(v, "S", rank, at)

    @Test fun thePriceRule() {
        assertFalse(TokenFacts.acceptsPrice(null, f(0.0, Rank.PROVIDER, 1L)))
        assertTrue(TokenFacts.acceptsPrice(null, f(1.0, Rank.PROVIDER, 1L)))
        // an older observation never replaces, whatever its rank
        assertFalse(TokenFacts.acceptsPrice(f(1.0, Rank.PROVIDER, 10_000L), f(2.0, Rank.CHAIN, 9_999L)))
        // a newer one replaces, up or down — any rank over a non-chain fact
        assertTrue(TokenFacts.acceptsPrice(f(1.0, Rank.PROVIDER, 10_000L), f(0.5, Rank.AGGREGATE, 10_001L)))
        // a chain tick holds the field for the ChainFedMarks8418 window against weaker sources
        val chain = f(1.0, Rank.CHAIN, 10_000L)
        assertFalse(TokenFacts.acceptsPrice(chain, f(2.0, Rank.PROVIDER, 10_000L + TokenFacts.PRICE_CHAIN_HOLD_MS)))
        assertTrue(TokenFacts.acceptsPrice(chain, f(2.0, Rank.PROVIDER, 10_001L + TokenFacts.PRICE_CHAIN_HOLD_MS)))
        assertTrue("a newer chain tick replaces a chain tick", TokenFacts.acceptsPrice(chain, f(2.0, Rank.CHAIN, 10_001L)))
        assertEquals(60_000L, TokenFacts.PRICE_CHAIN_HOLD_MS)
        // restores fill only an empty or restored slot; any observation replaces a restore
        assertTrue(TokenFacts.acceptsPrice(null, f(1.0, Rank.RESTORE, 0L)))
        assertFalse(TokenFacts.acceptsPrice(f(1.0, Rank.PROVIDER, 5L), f(2.0, Rank.RESTORE, 0L)))
        assertTrue(TokenFacts.acceptsPrice(f(1.0, Rank.RESTORE, 0L), f(2.0, Rank.PROVIDER, 1L)))
    }

    @Test fun theStoreOwnsPriceAndTime() {
        val ts = TokenState(mint = "mint8552", symbol = "PX")
        assertNull(TokenFacts.priceFact(ts))
        assertTrue(TokenFacts.restorePrice(ts, 0.5, "TOKEN_META_ARCHIVE_6908"))
        assertEquals("a restore is never observed here", 0L, ts.lastPriceUpdate)
        assertTrue(TokenFacts.writePrice(ts, 1.0, "DEXSCREENER_WS", Rank.PROVIDER, 100_000L, 100_000L, pool = "POOL1", dex = "RAYDIUM"))
        assertEquals(1.0, ts.lastPrice, 0.0); assertEquals(100_000L, ts.lastPriceUpdate)
        assertEquals("DEXSCREENER_WS", ts.lastPriceSource); assertEquals("POOL1", ts.lastPricePoolAddr); assertEquals("RAYDIUM", ts.lastPriceDex)
        assertEquals(1.0, ts.tokenMap.priceUsd!!, 0.0); assertEquals(100_000L, ts.tokenMap.priceObservedAtMs7858)
        assertFalse("an older cached pair never replaces a newer mark", TokenFacts.writePrice(ts, 9.0, "DEXSCREENER_PAIR_POLL", Rank.PROVIDER, 55_000L, 100_500L))
        assertFalse(TokenFacts.restorePrice(ts, 9.0, "TOKEN_META_ARCHIVE_6908"))
        assertTrue(TokenFacts.writePrice(ts, 1.2, "GRAD_TICK", Rank.CHAIN, 101_000L, 101_000L))
        assertFalse("a poll waits out the chain tick", TokenFacts.writePrice(ts, 1.5, "JUPITER", Rank.PROVIDER, 120_000L, 120_000L))
        assertEquals(1.2, ts.lastPrice, 0.0)
        // a correction keeps the observation's time
        assertTrue(TokenFacts.correctPrice(ts, 1.25, "GRAD_TICK+RECONCILED_7046"))
        assertEquals(1.25, ts.lastPrice, 0.0); assertEquals(101_000L, ts.lastPriceUpdate)
        assertEquals("GRAD_TICK+RECONCILED_7046", ts.lastPriceSource)
        // a row created / copied with a price is read as its legacy fact (observed at its lastPriceUpdate)
        val legacy = TokenState(mint = "mint8552b", lastPrice = 2.0, lastPriceUpdate = 50_000L)
        assertEquals(Rank.AGGREGATE, TokenFacts.priceFact(legacy)!!.rank)
        assertFalse(TokenFacts.writePrice(legacy, 3.0, "X", Rank.PROVIDER, 40_000L, 60_000L))
        assertTrue(TokenFacts.statusLine().contains("price accepted{"))
    }

    /** The guard: price and its time are written only by the store (display rows and the backtester excepted). */
    @Test fun noDirectPriceWritesOutsideTheStore() {
        val px = Regex("""\.lastPrice\s*=(?!=)"""); val at = Regex("""\.lastPriceUpdate\s*=(?!=)""")
        val allowed = setOf("engine/truth/TokenFacts.kt",
            "perps/CryptoLaneDesk7391.kt", "perps/PerpsUnifiedScorerBridge.kt", "perps/WatchlistEngine.kt",   // the desks' own rows
            "engine/TokenMetaCache.kt",                                                                         // its cache entry
            "v3/scoring/UltraFastRugDetectorAI.kt",                                                             // its own position record
            "ui/MainActivity.kt", "ui/BacktestActivity.kt")                                                     // display row / candle replay
        val bad = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { file ->
            val rel = file.relativeTo(root).path.replace('\\', '/')
            if (rel in allowed) return@forEach
            file.readLines().forEachIndexed { i, line ->
                val code = line.substringBefore("//").let { c -> if (c.trimStart().startsWith("*") || c.trimStart().startsWith("/*")) "" else c }
                if (px.containsMatchIn(code) || at.containsMatchIn(code)) bad += "$rel:${i + 1}"
            }
        }
        assertTrue("direct price writes bypass TokenFacts: $bad", bad.isEmpty())
    }

    // DATA_MAP D5 — the three clocks that lied
    @Test fun everyPriceCarriesItsOwnObservationTime() {
        val bot = src("engine/BotService.kt")
        assertTrue("the pair poll writes its fetch time", bot.contains("TokenFacts.writePrice(ts, validatedPrice, markSource7271, if (!synthPair7271) TokenFacts.Rank.PROVIDER"))
        assertTrue(bot.contains("evidenceAt7271, pool = pair.pairAddress, dex = when {"))
        assertTrue("the route cache offers at the cache's time", bot.contains("cached.updatedAtMs, pool = cached.poolAddress.ifBlank { cached.pairAddress }, dex = cached.dexId)"))
        assertTrue("a chain tick is a CHAIN fact", bot.contains("TokenFacts.writePrice(ts, px, source8018, TokenFacts.Rank.CHAIN, now, pool = pool8018,"))
        val ex = src("engine/Executor.kt")
        val cache = ex.substringAfter("private fun hydrateMintEntryMarketSnapshotFromCache(").substringBefore("private fun ")
        assertFalse("a meta-cache hydrate no longer re-dates the price", cache.contains("ts.lastPriceUpdate = System.currentTimeMillis()"))
        assertTrue(cache.contains("com.lifecyclebot.engine.truth.TokenFacts.restorePrice(ts, cached.lastPrice, null)"))
        assertTrue("basis repairs keep the observation's time", ex.contains("com.lifecyclebot.engine.truth.TokenFacts.correctPrice(ts, reconciled7017, \"\$base7048+RECONCILED_7046\")"))
        assertTrue(src("engine/DataOrchestrator.kt").contains("TokenFacts.writePrice(ts, priceUsd, \"DEXSCREENER_WS\""))
        val d = File("../../docs/DATA_MAP.md").readText()
        assertTrue(d.contains("PRICE_USD") && d.contains("8552"))
    }
}

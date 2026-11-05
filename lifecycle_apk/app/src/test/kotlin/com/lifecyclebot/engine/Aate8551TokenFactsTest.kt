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
 * V5.0.8551 — owner: "its meant to have a single data cache that all brains and readers call from and add to … we need
 * unification and clarification". Phase 1 of docs/DATA_MAP.md: liquidity lives in ONE store (TokenFacts on the mint's
 * TokenState) with one write path, a stated replacement rule, source / rank / observation time on every fact, and every
 * reader (LiquidityTruth8037.hydrate8037, TokenMapAuthority.observedLiquidityUsd, the raw field) reading the same number.
 */
class Aate8551TokenFactsTest {
    private val root = File("src/main/kotlin/com/lifecyclebot")
    private fun src(p: String) = File(root, p).readText()
    private fun f(v: Double, rank: Int, at: Long, source: String = "S") = Fact(v, source, rank, at)

    @Test fun theReplacementRule() {
        // not a reading is never a fact
        assertFalse(TokenFacts.accepts(null, f(0.0, Rank.CHAIN, 1_000L), false))
        assertFalse(TokenFacts.accepts(null, f(Double.NaN, Rank.CHAIN, 1_000L), false))
        // an empty slot takes any reading
        assertTrue(TokenFacts.accepts(null, f(5_000.0, Rank.AGGREGATE, 1_000L), false))
        // an older observation never replaces, whatever its rank
        assertFalse(TokenFacts.accepts(f(5_000.0, Rank.PROVIDER, 10_000L), f(9_000.0, Rank.CHAIN, 9_999L), false))
        // newer and equal-or-higher rank replaces, up or down
        assertTrue(TokenFacts.accepts(f(5_000.0, Rank.PROVIDER, 10_000L), f(1_000.0, Rank.PROVIDER, 10_001L), false))
        assertTrue("same time: the higher rank", TokenFacts.accepts(f(5_000.0, Rank.PROVIDER, 10_000L), f(4_000.0, Rank.CHAIN, 10_000L), false))
        assertFalse("same time: not the lower rank", TokenFacts.accepts(f(5_000.0, Rank.CHAIN, 10_000L), f(4_000.0, Rank.PROVIDER, 10_000L), false))
        // a weaker source waits out the stronger fact's hold
        val chain = f(5_000.0, Rank.CHAIN, 10_000L)
        assertFalse(TokenFacts.accepts(chain, f(9_000.0, Rank.PROVIDER, 10_000L + TokenFacts.holdMs(Rank.CHAIN) - 1), false))
        assertTrue(TokenFacts.accepts(chain, f(9_000.0, Rank.PROVIDER, 10_000L + TokenFacts.holdMs(Rank.CHAIN)), false))
        assertEquals(90_000L, TokenFacts.holdMs(Rank.CHAIN)); assertEquals(30_000L, TokenFacts.holdMs(Rank.PROVIDER))
        // restores fill only an empty slot (or refresh a restore); any observation replaces a restore
        assertTrue(TokenFacts.accepts(null, f(5_000.0, Rank.RESTORE, 0L), false))
        assertFalse("a legacy reading is not overwritten by an archive", TokenFacts.accepts(null, f(5_000.0, Rank.RESTORE, 0L), true))
        assertFalse(TokenFacts.accepts(f(5_000.0, Rank.AGGREGATE, 10_000L), f(9_000.0, Rank.RESTORE, 0L), false))
        assertTrue(TokenFacts.accepts(f(5_000.0, Rank.RESTORE, 0L), f(9_000.0, Rank.RESTORE, 0L), false))
        assertTrue(TokenFacts.accepts(f(5_000.0, Rank.RESTORE, 0L), f(9_000.0, Rank.AGGREGATE, 1L), false))
        assertEquals("OLDER_THAN_HELD", TokenFacts.refusal(f(5_000.0, Rank.PROVIDER, 10_000L), f(9_000.0, Rank.CHAIN, 9_999L)))
        assertEquals("HELD_BY_STRONGER", TokenFacts.refusal(chain, f(9_000.0, Rank.PROVIDER, 10_001L)))
    }

    @Test fun observationTimesAreStampedHonestly() {
        assertEquals(0L, TokenFacts.stampOf(Rank.RESTORE, 123_456L, 200_000L))
        assertEquals(150_000L, TokenFacts.stampOf(Rank.PROVIDER, 150_000L, 200_000L))
        assertEquals("a clock ahead is clamped", 200_000L, TokenFacts.stampOf(Rank.PROVIDER, 900_000L, 200_000L))
        assertEquals("unstated time = now", 200_000L, TokenFacts.stampOf(Rank.CHAIN, 0L, 200_000L))
        assertTrue(TokenFacts.isReading(1.0)); assertFalse(TokenFacts.isReading(0.0)); assertFalse(TokenFacts.isReading(Double.POSITIVE_INFINITY))
    }

    @Test fun theStoreMirrorsTheLegacyFields() {
        val ts = TokenState(mint = "mint8551", symbol = "FACT")
        assertNull(TokenFacts.fact(ts, TokenFacts.Field.LIQUIDITY_USD))
        assertTrue(TokenFacts.writeLiquidity(ts, 4_000.0, "DEX_PAIR_POLL", Rank.PROVIDER, 100_000L, 100_000L))
        assertEquals(4_000.0, ts.lastLiquidityUsd, 0.0)
        assertEquals(4_000.0, ts.tokenMap.liquidityUsd!!, 0.0)
        assertTrue(TokenFacts.writeLiquidity(ts, 2_500.0, "CHAIN_VAULT_8038", Rank.CHAIN, 100_500L, 100_500L))
        assertFalse("the API figure waits out the chain hold", TokenFacts.writeLiquidity(ts, 4_100.0, "DEX_PAIR_POLL", Rank.PROVIDER, 101_000L, 101_000L))
        assertEquals(2_500.0, ts.lastLiquidityUsd, 0.0)
        assertFalse("an archive never replaces an observation", TokenFacts.writeLiquidity(ts, 9_999.0, "META_CACHE_RESTORE", Rank.RESTORE, 0L, 102_000L))
        assertFalse("not reported is not zero", TokenFacts.writeLiquidity(ts, 0.0, "DEX_PAIR_POLL", Rank.PROVIDER, 300_000L, 300_000L))
        assertEquals(2_500.0, ts.lastLiquidityUsd, 0.0)
        assertTrue(TokenFacts.writeLiquidity(ts, 3_000.0, "DEX_PAIR_POLL", Rank.PROVIDER, 191_000L, 191_000L))
        assertEquals("DEX_PAIR_POLL", TokenFacts.fact(ts, TokenFacts.Field.LIQUIDITY_USD)!!.source)
        ts.tokenMap.liquidityUsd = 1.0
        TokenFacts.mirror(ts)
        assertEquals(3_000.0, ts.tokenMap.liquidityUsd!!, 0.0)
        val restored = TokenState(mint = "mint8551b")
        assertTrue(TokenFacts.writeLiquidity(restored, 7_000.0, "POSITION_RESTORE", Rank.RESTORE, 0L, 1_000L))
        assertTrue(TokenFacts.liquidityRestoredOnly(restored))
        assertTrue(TokenFacts.statusLine().startsWith("tokenFacts8551["))
    }

    /** The guard: no code outside the store writes the liquidity fields directly. */
    @Test fun noDirectLiquidityWritesOutsideTheStore() {
        val tick = Regex("""\.lastLiquidityUsd\s*=(?!=)""")
        val map = Regex("""(tokenMap|\btm)\.liquidityUsd\s*=(?!=)""")
        // the crypto / perps desks keep synthetic rows of their own (not meme market data); TokenMetaCache writes its cache entry
        val tickAllowed = setOf("engine/truth/TokenFacts.kt", "perps/CryptoLaneDesk7391.kt", "perps/PerpsUnifiedScorerBridge.kt", "engine/TokenMetaCache.kt")
        // copies FROM the store's mirror into a (new / swapped) token map
        val mapAllowed = setOf("engine/truth/TokenFacts.kt", "data/Models.kt", "engine/TokenMapAuthority.kt", "engine/RouteTruthHydrator.kt")
        val bad = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { file ->
            val rel = file.relativeTo(root).path.replace('\\', '/')
            file.readLines().forEachIndexed { i, line ->
                val code = line.substringBefore("//")
                if (tick.containsMatchIn(code) && rel !in tickAllowed) bad += "$rel:${i + 1}"
                if (map.containsMatchIn(code) && rel !in mapAllowed) bad += "$rel:${i + 1}"
            }
        }
        assertTrue("direct liquidity writes bypass TokenFacts: $bad", bad.isEmpty())
        for (allowed in listOf("engine/RouteTruthHydrator.kt", "engine/TokenMapAuthority.kt"))
            assertFalse(allowed, map.findAll(src(allowed)).any { m -> !src(allowed).substring(m.range.last + 1).trimStart().startsWith("ts.lastLiquidityUsd") })
    }

    @Test fun everyProducerOffersToTheStore() {
        val h = src("engine/HydrationReeval8343.kt")
        assertTrue(h.contains("com.lifecyclebot.engine.truth.TokenFacts.writeLiquidity(ts, usd, source, rank, observedAtMs, nowMs)"))
        assertTrue(h.contains("TokenFacts.writeLiquidity(ts, depthUsd, \"CHAIN_VAULT_8038\""))
        val l = src("engine/truth/LiquidityTruth8037.kt")
        assertFalse("the private ladder write is gone", l.contains("private fun write8343("))
        assertTrue(l.contains("TokenFacts.writeLiquidity(ts, usd, if (fromAccount) \"CURVE_ACCOUNT_7951\" else \"CURVE_FRAME_7951\", TokenFacts.Rank.CHAIN, atMs)"))
        assertTrue(l.contains("TokenFacts.writeLiquidity(ts, chain.first, \"CHAIN_VAULT_8038\", TokenFacts.Rank.CHAIN, chain.second, now)"))
        for ((file, call) in listOf(
            "engine/DataOrchestrator.kt" to "\"DEXSCREENER_WS\", com.lifecyclebot.engine.truth.TokenFacts.Rank.PROVIDER",
            "engine/BotService.kt" to "\"BIRDEYE_OVERVIEW\", com.lifecyclebot.engine.truth.TokenFacts.Rank.PROVIDER",
            "engine/DexBatchLane8287.kt" to "\"DEX_BATCH_8460\", com.lifecyclebot.engine.truth.TokenFacts.Rank.PROVIDER",
            "engine/LaneAgeLiquidityTruth8140.kt" to "\"RAYDIUM_TVL_8128\", com.lifecyclebot.engine.truth.TokenFacts.Rank.AGGREGATE",
            "engine/truth/OnChainDepth8038.kt" to "\"CHAIN_VERIFIED_8038\", TokenFacts.Rank.CHAIN, read.atMs",
            "engine/PositionPersistence.kt" to "TokenFacts.restoreLiquidity(existing, saved.lastKnownLiquidity, \"POSITION_RESTORE\")",
            "engine/BotService.kt" to "TokenFacts.restoreLiquidity(fresh, cached.lastLiquidityUsd, \"META_CACHE_RESTORE\")",
            "engine/Executor.kt" to "\"ENTRY_SNAPSHOT_7382\", com.lifecyclebot.engine.truth.TokenFacts.Rank.AGGREGATE, snap.capturedAtMs",
        )) assertTrue("$file: $call", src(file).contains(call))
    }

    @Test fun everyReaderReadsTheSameNumber() {
        val a = src("engine/TokenMapAuthority.kt").substringAfter("fun observedLiquidityUsd(ts: TokenState): Double =").substringBefore("fun mapLiquidityUsd8551(")
        assertTrue(a.contains("com.lifecyclebot.engine.truth.LiquidityTruth8037.hydrate8037(ts)"))
        assertFalse("its own curve ladder is retired", a.contains("curveRealSolFor8173"))
        assertTrue(src("engine/truth/LiquidityTruth8037.kt").contains("com.lifecyclebot.engine.TokenMapAuthority.mapLiquidityUsd8551(ts)"))
    }

    // DATA_MAP D7 — the collapse guard read authority fields of the token map that nothing writes
    @Test fun collapseGuardReadsTheSafetyProof() {
        val e = src("engine/Executor.kt")
        assertFalse(e.contains("!ts.tokenMap.mintAuthority.isNullOrBlank()"))
        assertFalse(e.contains("!ts.tokenMap.freezeAuthority.isNullOrBlank()"))
        assertTrue(e.contains("val mintAuthLive = ts.safety.mintAuthorityDisabled == false"))
        assertTrue(e.contains("mintAuthorityLive = ts.safety.mintAuthorityDisabled == false,"))
    }

    @Test fun theMapIsWrittenDown() {
        val doc = File("../../docs/DATA_MAP.md")
        assertTrue(doc.exists())
        val d = doc.readText()
        for (s in listOf("TokenFacts", "LIQUIDITY_USD", "Rank", "Migration order", "D7")) assertTrue(s, d.contains(s))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("TokenFacts.statusLine()"))
    }
}

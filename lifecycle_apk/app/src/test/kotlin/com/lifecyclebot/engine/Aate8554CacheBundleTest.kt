package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.TokenFacts
import com.lifecyclebot.engine.truth.TokenFacts.Field
import com.lifecyclebot.engine.truth.TokenFacts.Rank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8554 — owner: "the cache work … finish that in a bundle as well fully wired correctly". The single cache is the
 * store every brain reads AND adds to: the side caches a reader pulled market values from (pair answers, sweep rows, tape
 * fallbacks, the resolver's fill) now add what they hold to TokenFacts at their own observation time; supply is stamped on
 * the row the moment the chain answers and carried as a fact; the frozen decision copy keeps the facts; a shared token map
 * swapped in is re-mirrored from the store; and the archive (TokenMetaCache) is refreshed from the store, not from intake.
 */
class Aate8554CacheBundleTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun sideCachesAddToTheStore() {
        val pf = src("engine/truth/SetupFeatureResolver8169.kt")
        assertTrue("every pair producer adds its answer", pf.contains("try { TokenFacts.notePairFlow(mint, f) } catch (_: Throwable) {}"))
        val t = src("engine/truth/TokenFacts.kt").substringAfter("fun notePairFlow(").substringBefore("fun noteSweepRow(")
        assertTrue(t.contains("writeSnapshot(ts, Field.BUY_PRESSURE_PCT, f.buysM5 * 100.0 / m5, \"DEX_PAIR_M5\", Rank.PROVIDER, at)"))
        assertTrue(t.contains("writeSnapshot(ts, Field.TX_PER_MIN, h1 / 60.0, \"DEX_PAIR_H1\", Rank.AGGREGATE, at)"))
        assertTrue("a curve coin's depth is its curve", t.contains("if (!activeCurve && isReading(f.liquidityUsd)) writeLiquidity(ts, f.liquidityUsd, \"DEX_PAIR\", Rank.PROVIDER, at)"))
        assertFalse("a cap comes from verified supply, not the pair", t.contains("Field.MCAP_USD"))
        assertTrue(src("engine/market/MarketSweep7297.kt").contains("com.lifecyclebot.engine.truth.TokenFacts.noteSweepRow(r.mint, r.priceChange5mPct,"))
        val lit = src("engine/LaneInputTruth8236.kt")
        assertTrue(lit.contains("TokenFacts.noteTapeRead(ts, it, null, nowMs)") && lit.contains("TokenFacts.noteTapeRead(ts, null, it, nowMs)"))
    }

    @Test fun aTapeReadIsAChainFact() {
        val ts = TokenState(mint = "mint8554tape")
        TokenFacts.noteTapeRead(ts, 64.0, -2.5, 50_000L)
        assertEquals(64.0, ts.lastBuyPressurePct, 0.0); assertEquals(36.0, ts.lastSellPressurePct, 1e-9)
        assertEquals(-2.5, ts.lastPriceChange5m, 0.0)
        assertEquals(Rank.CHAIN, TokenFacts.snapshotFact(ts, Field.BUY_PRESSURE_PCT)!!.rank)
        TokenFacts.noteTapeRead(ts, 140.0, null, 60_000L)
        assertEquals("an impossible share is no reading", 64.0, ts.lastBuyPressurePct, 0.0)
    }

    @Test fun theResolverFillCarriesItsOwnTime() {
        val l = src("engine/truth/LiquidityTruth8037.kt")
        assertTrue(l.contains("SetupFeatureResolver8169.observedLiquidityTimed8554(ts.mint, age, now)"))
        assertTrue(l.contains("val at = timed?.second ?: ts.tokenMap.updatedAtMs.takeIf { it in 1L..now } ?: now"))
        assertTrue(src("engine/truth/SetupFeatureResolver8169.kt").contains("fun observedLiquidityTimed8554(mint: String, ageMin: Double, nowMs: Long): Pair<Double, Long>?"))
    }

    @Test fun supplyIsStampedOnAcceptAndCarriedAsAFact() {
        val m = src("engine/truth/MintSupply8418.kt")
        assertTrue(m.contains("com.lifecyclebot.engine.BotService.status.tokens[s.mint]?.let { stamp8418(it) }"))
        assertTrue(m.contains("TokenFacts.writeSnapshot(ts, TokenFacts.Field.SUPPLY_WHOLE, s.wholeTokens, \"MINT_ACCOUNT_\" + s.source.name, TokenFacts.Rank.CHAIN, s.observedAtMs)"))
        assertTrue(TokenFacts.validFor(Field.SUPPLY_WHOLE, 1.0e9)); assertFalse(TokenFacts.validFor(Field.SUPPLY_WHOLE, 0.0))
    }

    // D10 — the decision copy keeps the facts it saw
    @Test fun theFrozenDecisionKeepsItsFacts() {
        val live = TokenState(mint = "mint8554frz")
        assertTrue(TokenFacts.writeSnapshot(live, Field.TX_PER_MIN, 3.0, "DEX_PAIR_M5", Rank.PROVIDER, 10_000L, 10_000L))
        val frozen = live.copy()
        assertTrue("copy() starts empty", frozen.facts8551.isEmpty())
        TokenFacts.freezeInto(frozen, live)
        assertEquals("DEX_PAIR_M5", frozen.facts8551[Field.TX_PER_MIN]!!.source)
        assertTrue(src("engine/cortex/CortexDecisionSnapshot8522.kt").contains("com.lifecyclebot.engine.truth.TokenFacts.freezeInto(frozen, ts)"))
    }

    @Test fun aSwappedMapNeverOverridesTheStore() {
        val ts = TokenState(mint = "mint8554map")
        assertTrue(TokenFacts.writeSnapshot(ts, Field.MCAP_USD, 2.0e6, "DEX_PAIR_POLL", Rank.PROVIDER, 10_000L, 10_000L))
        ts.tokenMap.marketCap = 9.0e9
        TokenFacts.mirror(ts)
        assertEquals(2.0e6, ts.tokenMap.marketCap!!, 0.0)
        assertTrue(src("engine/TokenMapAuthority.kt").substringAfter("private fun installShared6492(").substringBefore("fun ensureDiscoveryTokenMap(")
            .contains("com.lifecyclebot.engine.truth.TokenFacts.mirror(ts)"))
    }

    @Test fun theArchiveRoundTripsTheStore() {
        assertTrue(TokenFacts.archiveDue(null, 1_000L))
        assertFalse(TokenFacts.archiveDue(1_000L, 30_000L))
        assertTrue(TokenFacts.archiveDue(1_000L, 61_000L))
        val f = src("engine/truth/TokenFacts.kt")
        assertTrue(f.contains("if (rank > Rank.RESTORE) archive(ts, nowMs)"))
        val bot = src("engine/BotService.kt")
        assertTrue("the intake register no longer overwrites the store's archived values",
            bot.contains("lastMcap = row8554?.lastMcap?.takeIf { it > 0.0 } ?: trustedMarketCapUsd6492.takeIf { it > 0.0 },"))
        assertFalse(bot.contains("lastMcap = trustedMarketCapUsd6492.takeIf { it > 0.0 },\n                    lastLiquidityUsd = liquidityUsd.takeIf { it > 0.0 },"))
        val d = File("../../docs/DATA_MAP.md").readText()
        assertTrue(d.contains("8554") && d.contains("archive"))
    }
}

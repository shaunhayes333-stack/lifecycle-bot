package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.market.MarketSweep7297
import com.lifecyclebot.engine.market.PocketRecovery8961
import com.lifecyclebot.engine.truth.TokenFacts
import com.lifecyclebot.engine.truth.TokenFacts.Field
import com.lifecyclebot.engine.truth.TokenFacts.Rank
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8961RecoveryProvenanceTest {
    private val mint = "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr"
    private val pool = "So11111111111111111111111111111111111111112"
    private val now = 1_000_000L

    private fun pending(at: Long = now - 10_000L): PocketRecovery8961.Pending {
        val row = MarketSweep7297.Row(mint, "COIN", "Coin", 0.01, 50_000.0, 8_000.0,
            10_000.0, 20_000.0, 3.0, 20, 50, 0.0, false, 0.1, setOf("dexscreener"),
            observedAtMs8169 = at)
        val ts = TokenState(mint = mint)
        TokenFacts.writePrice(ts, row.priceUsd, "MARKET_POCKET_8961", Rank.AGGREGATE, at, now)
        TokenFacts.writeSnapshot(ts, Field.MCAP_USD, row.mcapUsd, "MARKET_POCKET_8961", Rank.AGGREGATE, at, now)
        TokenFacts.writeLiquidity(ts, row.liquidityUsd, "MARKET_POCKET_8961", Rank.AGGREGATE, at, now)
        return PocketRecovery8961.Pending(row, ts, setOf("CORE"), now - 5_000L, 7L)
    }

    @Test fun observedRecoveryKeepsTheRowAndChainClocksInsteadOfTheIntakeClock() {
        val p = pending()
        TokenFacts.writeLiquidity(p.token, 7_500.0, "CHAIN_VAULT_8038", Rank.CHAIN, now - 2_000L, now)
        val target = TokenState(mint = mint)
        PocketRecovery8961.applyObservedFacts8961(p, target, now)
        // These are actual TokenFacts writes, including the legacy mirrors, not a reimplementation of acceptance.
        for (field in listOf(Field.PRICE_USD, Field.MCAP_USD, Field.LIQUIDITY_USD))
            assertEquals(p.token.facts8551[field], target.facts8551[field])
        assertEquals(now - 10_000L, target.lastPriceUpdate)
        assertEquals("MARKET_POCKET_8961", target.lastPriceSource)
        assertEquals(7_500.0, target.lastLiquidityUsd, 0.0)
        assertEquals(Rank.CHAIN, target.facts8551.getValue(Field.LIQUIDITY_USD).rank)
        assertEquals(now - 2_000L, target.facts8551.getValue(Field.LIQUIDITY_USD).observedAtMs)
        TokenFacts.restorePrice(target, 99.0, "RESTORE")
        TokenFacts.restoreMcap(target, 9_999_999.0)
        TokenFacts.restoreLiquidity(target, 999_999.0, "META_CACHE_RESTORE")
        assertEquals(0.01, target.lastPrice, 0.0)
        assertEquals(50_000.0, target.lastMcap, 0.0)
        assertEquals(7_500.0, target.lastLiquidityUsd, 0.0)
    }

    @Test fun genuineNewerFactsCanReplaceRecoveryAndCannotBeReplacedByItsOlderCopy() {
        val p = pending()
        val target = TokenState(mint = mint)
        PocketRecovery8961.applyObservedFacts8961(p, target, now)
        assertTrue(TokenFacts.writeLiquidity(target, 2_000.0, "CHAIN_VAULT_8038", Rank.CHAIN, now - 1_000L, now))
        assertTrue(TokenFacts.writeSnapshot(target, Field.MCAP_USD, 40_000.0, "CHAIN_MARK", Rank.CHAIN, now - 1_000L, now))
        assertTrue(TokenFacts.writePrice(target, 0.008, "POOL_RESERVES_VAULT_8571", Rank.CHAIN, now - 1_000L, now, pool = pool))
        val current = target.facts8551.toMap()
        PocketRecovery8961.applyObservedFacts8961(p, target, now)
        assertEquals(current, target.facts8551.toMap())
        assertEquals(pool, target.lastPricePoolAddr)
        assertEquals(2_000.0, target.lastLiquidityUsd, 0.0)
    }

    @Test fun findingAPoolDoesNotRelabelTheEarlierAggregatePriceAsItsPrice() {
        val p = pending()
        assertTrue(PocketRecovery8961.startPoolRead8961(p, pool, now, 7L) {})
        val target = TokenState(mint = mint)
        PocketRecovery8961.applyObservedFacts8961(p, target, now)
        assertEquals(pool, target.tokenMap.poolAddress)
        assertEquals(pool, target.tokenMap.pairAddress)
        assertTrue(target.lastPricePoolAddr.isBlank())
        assertEquals("MARKET_POCKET_8961", target.lastPriceSource)
        assertEquals(p.row.observedAtMs8169, target.lastPriceUpdate)
    }

    @Test fun staleOrWrongIdentityRecoveryCannotCreateFreshFacts() {
        val p = pending(now - 120_001L)
        val target = TokenState(mint = mint)
        PocketRecovery8961.applyObservedFacts8961(p, target, now)
        assertTrue(target.facts8551.isEmpty())
        assertEquals(0L, target.lastPriceUpdate)
        val other = TokenState(mint = "other")
        PocketRecovery8961.applyObservedFacts8961(pending(), other, now)
        assertTrue(other.facts8551.isEmpty())
        assertTrue(other.laneAffinity.isEmpty())
    }

    @Test fun protectedIntakeKeepsDatedFactsBeforePublicationAndSkipsUndatedSeeds() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val intake = source.substringAfter("private fun admitProtectedMemeIntake(").substringBefore("private fun ")
        val initializer = intake.substringAfter("val ts = status.tokens.getOrPut(mint)")
            .substringBefore("// symbol/name are immutable identity fields")
        assertTrue(initializer.indexOf("PocketRecovery8961.applyObservedFacts8961(it, fresh, nowMs)") >= 0)
        assertTrue(initializer.indexOf("PocketRecovery8961.applyObservedFacts8961(it, fresh, nowMs)") <
            initializer.indexOf("IntakeFirstSight8317.firstSight8317("))
        assertTrue(intake.contains("if (recovery8961 == null) HydrationReeval8343.intakeLiquidity8345(ts, liquidityUsd)"))
        assertTrue(intake.contains("if (recovery8961 == null) {\n                    com.lifecyclebot.engine.truth.DiscoveryCapture8400.captureAtIntake8400("))
        assertTrue(intake.contains("if (recovery8961 == null && trustedMarketCapUsd6492 > 0.0 && ts.lastPrice <= 0.0 && seedPrice7089 > 0.0)"))
        assertTrue(intake.contains("if (recovery8961 == null && volumeH1 > 0.0 && ts.history.isEmpty())"))
        assertTrue(intake.contains("if (recovery8961 == null && ts.history.isEmpty() && ts.lastPrice > 0.0)"))
        assertTrue(source.contains("expectedRuntimeGeneration = generation, recovery8961 = recovery"))
    }
}

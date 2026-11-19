package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.cortex.CrossAssetCortex7931
import com.lifecyclebot.engine.truth.CryptoSpotPaperBacking8788
import com.lifecyclebot.engine.truth.TokenFacts
import com.lifecyclebot.perps.CryptoAltTrader
import com.lifecyclebot.perps.DynamicAltTokenRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A real observation survives signal -> canonical decision; a mutable cache row cannot date a different price. */
class Aate8870CryptoDecisionClockTest {
    private val now = 1_800_000_000_000L
    private val mint = "Aate887oCryptoPrice11111111111111111111111"
    private val other = "Aate887oCryptoOther11111111111111111111111"
    private val key = "solana|$mint"

    private fun observed(at: Long = now - 2_000L, identity: String = key,
                         source: String = "DEX_PAIR", price: Double = 1.20): Long =
        CrossAssetCortex7931.candidateObservationAt8870(mint, price, identity, at, source,
            key, 1.25, now - 100L, now, 15_000L)

    @Test fun aNewerRegistryTickDoesNotEraseTheCapturedDecisionPrice() {
        assertEquals(now - 2_000L, observed())
        val dyn = DynamicAltTokenRegistry.DynToken(mint = mint, symbol = "CLOCK", name = "CLOCK", chainId = "solana",
            price = 1.25, priceObservedAtMs8372 = now - 100L)
        val state = TokenState(mint = mint, symbol = "CLOCK", name = "CLOCK")
        CrossAssetCortex7931.bridgeCryptoObservedContext8834(state, dyn, 1.20, 15_000.0, 120_000.0, now,
            now - 2_000L, "DEX_PAIR", key, 15_000L)
        assertEquals(1.20, state.lastPrice, 0.0)
        assertEquals(now - 2_000L, state.lastPriceUpdate)
        assertEquals(now - 2_000L, TokenFacts.priceFact(state)?.observedAtMs)
        assertTrue("it remains a provider observation, never a confirmed fill", TokenFacts.priceFact(state)?.rank == TokenFacts.Rank.PROVIDER)
    }

    @Test fun missingStaleFutureAndWrongAssetClocksCannotBeLaundered() {
        assertEquals(0L, observed(at = 0L))
        assertEquals(0L, observed(at = now - 350_000L))
        assertEquals(0L, observed(at = now + 1L))
        assertEquals(0L, observed(identity = "solana|$other"))
        assertEquals(0L, observed(source = ""))
        assertEquals(0L, observed(price = Double.NaN))
        assertFalse(CryptoAltTrader.decisionPriceUsable8870(0L, now))
        assertFalse(CryptoAltTrader.decisionPriceUsable8870(now + 1L, now))
        assertTrue("a refresh miss cannot create a new 15-second veto", CryptoAltTrader.decisionPriceUsable8870(now - 20_000L, now))
        assertTrue(CryptoAltTrader.decisionPriceUsable8870(now - 15_000L, now))
        for (age in listOf(20_000L, 60_000L, 75_001L, 90_001L, 350_000L)) {
            for (hot in listOf(false, true)) {
                assertEquals(!com.lifecyclebot.engine.truth.CandidateAdmission8136.priceStale8136(age, hot, true,
                    com.lifecyclebot.engine.truth.CandidateAdmission8136.feedPeriodMs8855("CRYPTO_ALT")),
                    CryptoAltTrader.decisionPriceUsable8870(now - age, now, "CRYPTO_ALT", hot))
            }
        }
    }

    @Test fun legacyCallersNeedExactPriceAndSameAssetRegistryProof() {
        fun old(price: Double, registry: String = key) = CrossAssetCortex7931.candidateObservationAt8870(
            mint, price, "", 0L, "", registry, 1.25, now - 100L, now, 15_000L)
        assertEquals(now - 100L, old(1.25))
        assertEquals(0L, old(1.20))
        assertEquals(0L, old(1.25, "solana|$other"))
    }

    @Test fun sharedLiquidityKeepsItsOwnTimeRankAndSource() {
        val shared = TokenState(mint = mint, symbol = "CLOCK", name = "CLOCK")
        TokenFacts.writeLiquidity(shared, 14_500.0, "CHAIN_VAULT_8038", TokenFacts.Rank.CHAIN, now - 3_000L, now)
        val target = TokenState(mint = mint, symbol = "CLOCK", name = "CLOCK")
        TokenFacts.restoreLiquidity(target, 20_000.0, "CRYPTO_CANDIDATE_UNCLOCKED_8835")
        assertTrue(CrossAssetCortex7931.bridgeSharedLiquidity8870(target, shared, now, 10.0))
        assertEquals(TokenFacts.fact(shared, TokenFacts.Field.LIQUIDITY_USD), TokenFacts.fact(target, TokenFacts.Field.LIQUIDITY_USD))
        assertEquals(14_500.0, target.lastLiquidityUsd, 0.0)
    }

    @Test fun unrelatedRestoredOrStaleLiquidityIsNotPromoted() {
        val target = TokenState(mint = mint, symbol = "CLOCK", name = "CLOCK")
        val wrong = TokenState(mint = other, symbol = "CLOCK", name = "CLOCK")
        TokenFacts.writeLiquidity(wrong, 900_000.0, "CHAIN_VAULT_8038", TokenFacts.Rank.CHAIN, now - 100L, now)
        assertFalse(CrossAssetCortex7931.bridgeSharedLiquidity8870(target, wrong, now, 10.0))
        val restored = TokenState(mint = mint, symbol = "CLOCK", name = "CLOCK")
        TokenFacts.restoreLiquidity(restored, 900_000.0, "RESTORE")
        assertFalse(CrossAssetCortex7931.bridgeSharedLiquidity8870(target, restored, now, 10.0))
        val stale = TokenState(mint = mint, symbol = "CLOCK", name = "CLOCK")
        TokenFacts.writeLiquidity(stale, 900_000.0, "CHAIN_VAULT_8038", TokenFacts.Rank.CHAIN, now - 91_000L, now)
        assertFalse(CrossAssetCortex7931.bridgeSharedLiquidity8870(target, stale, now, 60.0))
        assertEquals(0.0, target.lastLiquidityUsd, 0.0)
    }

    @Test fun backingCapacityDoesNotBecomeNegativeExpectancyOrAnUnlimitedException() {
        fun state(stopped: Boolean, record: Boolean, admitted: Int, pending: Int) =
            CryptoSpotPaperBacking8788.classifyAvailability8870(true, true, stopped, record, admitted, pending)
        assertEquals(CryptoSpotPaperBacking8788.Availability8870.READY, state(false, true, 3, 0))
        assertEquals(CryptoSpotPaperBacking8788.Availability8870.CAPACITY, state(false, true, 3, 1))
        assertEquals(CryptoSpotPaperBacking8788.Availability8870.CAPACITY, state(false, true, 4, 0))
        assertEquals(CryptoSpotPaperBacking8788.Availability8870.LOSS_STOP, state(true, true, 4, 0))
        assertEquals(CryptoSpotPaperBacking8788.Availability8870.RECORD_NOT_BACKING, state(false, false, 4, 0))
        assertTrue(CryptoAltTrader.retryableEntryWait8870("CANONICAL_DEFERRED:CRYPTO_PAPER_BACKING_CAPACITY_8870"))
        assertTrue(CryptoAltTrader.retryableEntryWait8870("CRYPTO_LIVE_PRICE_REFRESH_WAIT_8870"))
        assertTrue(CryptoAltTrader.retryableEntryWait8870(CryptoLiveQuoteBoundary8870.WAIT_REASON))
        assertFalse(CryptoAltTrader.retryableEntryWait8870("CANONICAL_BLOCKED:SAFETY_REFUSED_8136"))
        assertFalse(CryptoAltTrader.retryableEntryWait8870("CANONICAL_BLOCKED:LIVE_NON_POSITIVE_EXPECTANCY_7828"))
        assertEquals(4, CryptoSpotPaperBacking8788.MAX_PER_HOUR_8788)
        assertEquals(0.1, CryptoSpotPaperBacking8788.LOSS_STOP_SOL_8788, 0.0)
    }

    @Test fun refreshedRowsReleaseOnlyTheSignalsOriginalEvaluationLease() {
        val original = DynamicAltTokenRegistry.DynToken(mint = "Aate887oLeaseAAAAAAAAAAAAAAAAAAAAAAA",
            chainId = "solana", symbol = "LEASE", name = "LEASE", price = 1.0)
        val refreshed = original.copy(price = 1.2)
        assertTrue(DynamicAltTokenRegistry.markEvaluationStarted6567(original))
        assertFalse("the mutable current row is not the owner", DynamicAltTokenRegistry.releaseEvaluationForRetry7418(refreshed, "CAPACITY"))
        assertTrue("the captured original row releases its own pass", DynamicAltTokenRegistry.releaseEvaluationForRetry7418(original, "CAPACITY"))
        assertTrue(DynamicAltTokenRegistry.markEvaluationStarted6567(refreshed))
        assertFalse("an old signal cannot release a newer owner's lease", DynamicAltTokenRegistry.releaseEvaluationForRetry7418(original, "CAPACITY"))
        assertTrue(DynamicAltTokenRegistry.releaseEvaluationForRetry7418(refreshed, "CAPACITY"))
    }
}

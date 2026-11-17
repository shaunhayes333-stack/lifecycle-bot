package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.cortex.CrossAssetCortex7931
import com.lifecyclebot.perps.CryptoLaneDesk7391
import com.lifecyclebot.perps.DynamicAltTokenRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 8833's source-dated crypto evidence must survive the canonical FDG boundary. */
class Aate8834CryptoCanonicalEvidenceTest {
    private val now = 1_800_000_000_000L

    @Test fun realFiveMinuteEvidenceAndDatedCandlesSurviveTheBridge() {
        val mint = "aate8834-real-mint"
        val token = DynamicAltTokenRegistry.DynToken(
            mint = mint, tokenAddress = mint, chainId = "solana", symbol = "UP", name = "UP",
            price = 1.12, priceObservedAtMs8372 = now - 2_000L,
            volume24h = 180_000.0, buys24h = 6_000, sells24h = 1_000,
            buys5m8832 = 18, sells5m8832 = 6, volume5m8832 = 16_000.0,
            change5mPct8832 = 3.2, shortWindowObservedAtMs8832 = now - 3_000L,
        )
        val key = token.canonicalIdentity6544
        CryptoLaneDesk7391.recordTick(key, 1.00, 100_000.0, 180_000.0, 6_000, 1_000, now - 54_000L, now)
        CryptoLaneDesk7391.recordTick(key, 1.06, 100_000.0, 180_000.0, 6_000, 1_000, now - 29_000L, now)
        CryptoLaneDesk7391.recordTick(key, 1.12, 100_000.0, 180_000.0, 6_000, 1_000, now - 2_000L, now)

        val state = TokenState(mint = mint, symbol = "UP", name = "UP")
        CrossAssetCortex7931.bridgeCryptoObservedContext8834(state, token, 1.12, now)
        assertEquals(now - 2_000L, state.lastPriceUpdate)
        assertEquals(now - 2_000L, state.tokenMap.priceObservedAtMs7858)
        assertEquals(NativeDecisionEnrollment8372.CRYPTO_DESK_PRICE_SOURCE, state.lastPriceSource)
        assertEquals(now - 3_000L, state.buyPressureObservedAtMs8084)
        assertEquals(75.0, state.lastBuyPressurePct, 1e-8)
        assertEquals(25.0, state.lastSellPressurePct, 1e-8)
        assertEquals(now - 3_000L, state.priceChangeObservedAtMs8084)
        assertEquals(3.2, state.lastPriceChange5m, 1e-8)
        assertEquals(16_000.0, state.tokenMap.volume5mUsd ?: -1.0, 1e-8)
        assertEquals(3, state.history.size)
        assertTrue(state.history.all { it.synthetic && it.ts <= state.lastPriceUpdate })
    }

    @Test fun staleActivityAndMismatchedPriceCannotBecomeObservedProof() {
        val token = DynamicAltTokenRegistry.DynToken(
            mint = "aate8834-stale", tokenAddress = "aate8834-stale", chainId = "solana",
            symbol = "STALE", name = "STALE", price = 2.0,
            priceObservedAtMs8372 = now - 180_000L,
            buys24h = 1_000, sells24h = 2, volume24h = 400_000.0,
            buys5m8832 = 900, sells5m8832 = 1, volume5m8832 = 200_000.0,
            change5mPct8832 = 8.0, shortWindowObservedAtMs8832 = now - 240_000L,
        )
        val state = TokenState(mint = token.mint, symbol = "STALE", name = "STALE")
        CrossAssetCortex7931.bridgeCryptoObservedContext8834(state, token, 2.0, now)
        assertEquals(0L, state.lastPriceUpdate)
        assertEquals(0L, state.tokenMap.priceObservedAtMs7858)
        assertEquals("", state.lastPriceSource)
        assertEquals(0L, state.buyPressureObservedAtMs8084)
        assertEquals(50.0, state.lastBuyPressurePct, 0.0)
        assertEquals(50.0, state.lastSellPressurePct, 0.0)
        assertEquals(0L, state.priceChangeObservedAtMs8084)
        assertEquals(0.0, state.lastPriceChange5m, 0.0)
        assertNull(state.tokenMap.volume5mUsd)
        assertTrue(state.history.isEmpty())

        val freshToken = token.copy(priceObservedAtMs8372 = now - 1_000L,
            shortWindowObservedAtMs8832 = now - 1_000L)
        val priceMismatch = TokenState(mint = token.mint, symbol = "STALE", name = "STALE")
        CrossAssetCortex7931.bridgeCryptoObservedContext8834(priceMismatch, freshToken, 3.0, now)
        assertEquals(0L, priceMismatch.lastPriceUpdate)
        assertTrue(priceMismatch.history.isEmpty())
        assertFalse(priceMismatch.lastPriceSource == NativeDecisionEnrollment8372.CRYPTO_DESK_PRICE_SOURCE)
    }

    @Test fun sellerDominatedOrZeroFiveMinuteFlowIsNeverUpgradedToBullish() {
        val token = DynamicAltTokenRegistry.DynToken(
            mint = "aate8834-selling", chainId = "solana", symbol = "DOWN", name = "DOWN",
            price = 1.0, priceObservedAtMs8372 = now - 500L,
            buys5m8832 = 3, sells5m8832 = 21, volume5m8832 = 9_000.0,
            change5mPct8832 = -2.1, shortWindowObservedAtMs8832 = now - 700L,
        )
        val sellers = TokenState(mint = token.mint, symbol = "DOWN", name = "DOWN")
        CrossAssetCortex7931.bridgeCryptoObservedContext8834(sellers, token, 1.0, now)
        assertEquals(12.5, sellers.lastBuyPressurePct, 1e-8)
        assertEquals(-2.1, sellers.lastPriceChange5m, 1e-8)
        assertTrue(sellers.buyPressureObservedAtMs8084 > 0L)

        val zero = TokenState(mint = token.mint, symbol = "ZERO", name = "ZERO")
        CrossAssetCortex7931.bridgeCryptoObservedContext8834(
            zero, token.copy(buys5m8832 = 0, sells5m8832 = 0), 1.0, now,
        )
        assertEquals(50.0, zero.lastBuyPressurePct, 0.0)
        assertEquals(0L, zero.buyPressureObservedAtMs8084)
    }

    @Test fun canonicalRouteStillUsesOriginalSafetyAndLiveEdgeAuthority() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/cortex/CrossAssetCortex7931.kt").readText()
        val contract = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalAssetEntryContract6551.kt").readText()
        assertTrue(src.contains("bridgeCryptoObservedContext8834(ts, dyn8063, c.price, nowMs)"))
        assertTrue(src.contains("LiveEdgeGate7877.liveRefusal(ts, lane, paper)"))
        assertTrue(contract.contains("if (candidate.hardSafetyReasons.isNotEmpty())"))
        assertTrue(contract.contains("if (!sizing.executable) return blocked"))
        assertTrue(contract.contains("CryptoSpotPaperBacking8788.reserve8832("))
    }
}

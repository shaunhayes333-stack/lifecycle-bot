package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.HeliusSwapCandles7819
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8961LatePoolHistoryTest {
    private val mint = "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr"
    private val pool = "So11111111111111111111111111111111111111112"
    private val curve = "6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P"

    @Test fun aLatePoolCanSupplyHistoryWithoutClaimingItSuppliedTheEarlierPrice() {
        val ts = TokenState(mint = mint)
        ts.lastPriceSource = "MARKET_POCKET_8961"
        ts.lastPriceUpdate = 10_000L
        ts.tokenMap.poolAddress = pool
        assertEquals(pool, HeliusSwapCandles7819.historyPool8961(ts))
        assertTrue(ts.pairAddress.isBlank())
        assertTrue(ts.lastPricePoolAddr.isBlank())
        assertEquals("MARKET_POCKET_8961", ts.lastPriceSource)
        assertEquals(10_000L, ts.lastPriceUpdate)
    }

    @Test fun mapPairFallbackRejectsInvalidAddressesAndTheMintItself() {
        val ts = TokenState(mint = mint)
        ts.tokenMap.poolAddress = mint
        ts.tokenMap.pairAddress = "UNRESOLVED"
        assertEquals("", HeliusSwapCandles7819.historyPool8961(ts))
        ts.tokenMap.pairAddress = pool
        assertEquals(pool, HeliusSwapCandles7819.historyPool8961(ts))
    }

    @Test fun establishedPoolPrecedenceAndGraduatedCurveRulesRemainIntact() {
        val ts = TokenState(mint = mint, pairAddress = pool)
        ts.tokenMap.poolAddress = curve
        assertEquals(pool, HeliusSwapCandles7819.historyPool8961(ts))
        val late = TokenState(mint = mint)
        late.tokenMap.poolAddress = pool
        val venue = HeliusSwapCandles7819.historyPool8961(late)
        assertEquals(curve, HeliusSwapCandles7819.historyAddress8269(mint, venue, curve, false))
        assertEquals(pool, HeliusSwapCandles7819.historyAddress8269(mint, venue, curve, true))
        late.tokenMap.poolAddress = ""
        assertNull(HeliusSwapCandles7819.historyAddress8269(mint,
            HeliusSwapCandles7819.historyPool8961(late), curve, true))
    }

    @Test fun theHistoryRequestUsesTheRecoveredVenueResolver() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/truth/HeliusSwapCandles7819.kt").readText()
        val resolver = source.substringAfter("private fun activeMarketAddress8269(")
            .substringBefore("fun request7819(")
        assertTrue(resolver.contains("val pool = historyPool8961(ts)"))
        assertTrue(resolver.contains("historyAddress8269(mint, pool, pumpCurve.orEmpty(), graduated)"))
    }
}

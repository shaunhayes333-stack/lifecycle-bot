package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CrossAssetCortex7931
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8063 — coins are graded by what kind of coin they are (crypto and memes). */
class Aate8063CoinKindGradingTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun cryptoKinds() {
        assertEquals("MAJOR", CrossAssetCortex7931.cryptoKind8063("BTC", "", ""))
        assertEquals("WRAPPED", CrossAssetCortex7931.cryptoKind8063("JITOSOL", "", ""))
        assertEquals("MEME", CrossAssetCortex7931.cryptoKind8063("WIF", "Meme", ""))
        assertEquals("DEFI", CrossAssetCortex7931.cryptoKind8063("JUP", "DeFi", ""))
        assertEquals("AI", CrossAssetCortex7931.cryptoKind8063("X", "AI Agents", ""))
        assertEquals("L1", CrossAssetCortex7931.cryptoKind8063("X", "Blockchain", ""))  // "CHAIN" is not AI
        assertEquals("CAP_GT1B", CrossAssetCortex7931.capTier8063(2e9))
        assertEquals("CAPU", CrossAssetCortex7931.capTier8063(0.0))
        assertTrue(src("engine/cortex/CrossAssetCortex7931.kt").contains("PeggedAssetGuard7270.isPegged(c.symbol, c.price, mcap8063, key)"))
    }

    @Test fun memeVenues() {
        assertEquals("CURVE", ForwardReturnLabeler7731.memeVenue8063("Abcpump", "PUMP_FUN", true))
        assertEquals("CURVE", ForwardReturnLabeler7731.memeVenue8063("Abcpump", "PUMP_FUN", false))
        assertEquals("PUMPSWAP", ForwardReturnLabeler7731.memeVenue8063("Abcpump", "PUMPSWAP", false))
        assertEquals("PUMPSWAP", ForwardReturnLabeler7731.memeVenue8063("Abcpump", "UNKNOWN", false))
        assertEquals("BONK", ForwardReturnLabeler7731.memeVenue8063("Abcbonk", "RAYDIUM", false))
        assertEquals("RAYDIUM", ForwardReturnLabeler7731.memeVenue8063("Abc", "raydium", false))
        assertEquals("OTHER", ForwardReturnLabeler7731.memeVenue8063("Abc", "", false))
    }

    @Test fun kindBooksFeedEdgeAndSize() {
        assertTrue(src("engine/truth/LiveEdgeGate7877.kt").contains("ForwardReturnLabeler7731.kindStatsFor8063(ts, lane)"))
        assertTrue(src("engine/truth/LiveRiskPolicy7807.kt").contains("maxOf(setupFrac, cellFrac, kindFrac)"))
        assertTrue(src("engine/truth/ForwardReturnLabeler7731.kt").contains("kindKey8063(o.lane, k[0]"))
    }
}

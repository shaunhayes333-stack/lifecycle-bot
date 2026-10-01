package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7630LiquidityFragilityProductionFeedTest {
    @Test fun `toolkit refresh feeds fragility from cached evidence only`() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()
        val build = s.substringAfter("fun build(ts: TokenState")
        assertTrue(build.contains("LiquidityFragilityAI.analyze("))
        assertTrue(build.contains("depthUsd = liq"))
        assertTrue(build.contains("volume24hUsd = latestReal7630?.volume24h ?: 0.0"))
        assertTrue(build.contains("topHolderPct = topHolder7630"))
        assertTrue(build.contains("poolAgeDays = poolAgeDays7630"))
        assertTrue(build.contains("recentWickPcts = upperWickPct7630"))
        assertTrue(build.contains("LIQUIDITY_FRAGILITY_CACHED_FEED_7630"))
    }

    @Test fun `unknown execution microstructure is not fabricated`() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()
        val block = s.substringAfter("V5.0.7630 — restore the V4 liquidity-fragility brain")
            .substringBefore("val mcap =")
        assertTrue(!block.contains("recentSlippagePct ="))
        assertTrue(!block.contains("priceImpactPct ="))
        assertTrue(!block.contains("spreadBps ="))
    }
}

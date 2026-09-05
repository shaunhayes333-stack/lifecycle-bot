package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate6675RuntimeLearningAuthorityRegressionTest {

    @Test
    fun `decision facing lane expectancy remains clean live terminal only`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/LaneExpectancyDamper.kt").readText()
        assertTrue(src.contains("computeCleanLiveTerminalLeaderboard"))
        assertFalse(src.contains("computeCleanPaperTerminalLeaderboard"))
        assertFalse(src.contains("computeLiveTerminalLeaderboard("))
        assertFalse(src.contains("computePaperTerminalLeaderboard("))
    }

    @Test
    fun `standalone market sentinel repair marker remains wired`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/MarketDataProvenance6471.kt").readText()
        assertTrue(src.contains("V5.0.6658 §SENTINEL_PRICE_STANDALONE"))
        assertTrue(src.contains("SENTINEL_PRICES_STANDALONE_6658 = doubleArrayOf"))
        assertTrue(src.contains("sentinel_price_standalone(\$price)"))
    }
}

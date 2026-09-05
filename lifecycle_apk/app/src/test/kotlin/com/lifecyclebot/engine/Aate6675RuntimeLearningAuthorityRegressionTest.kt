package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate6675RuntimeLearningAuthorityRegressionTest {

    @Test
    fun `lane expectancy damper follows runtime paper live truth and mode keyed cache`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/LaneExpectancyDamper.kt").readText()
        assertTrue("damper must resolve current runtime mode", src.contains("RuntimeModeAuthority.isPaper()"))
        assertTrue("paper runtime must use clean paper terminal leaderboard", src.contains("computeCleanPaperTerminalLeaderboard"))
        assertTrue("live runtime must use clean live terminal leaderboard", src.contains("computeCleanLiveTerminalLeaderboard"))
        assertTrue("damper cache must be keyed by paper/live runtime mode", src.contains("cachePaperMode"))
        assertTrue("repair marker must remain grep-visible", src.contains("V5.0.6675 §RUNTIME_EXPECTANCY_AUTHORITY"))
    }

    @Test
    fun `standalone market sentinel repair marker remains wired`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/MarketDataProvenance6471.kt").readText()
        assertTrue(src.contains("V5.0.6658 §SENTINEL_PRICE_STANDALONE"))
        assertTrue(src.contains("SENTINEL_PRICES_STANDALONE_6658 = doubleArrayOf"))
        assertTrue(src.contains("sentinel_price_standalone(\$price)"))
    }
}

package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Repair6518ExecutorVerifyErrorTest {
    private val source = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()

    @Test
    fun `wide DNA arithmetic is isolated from giant liveBuy dex frame`() {
        val helper = source.indexOf("private fun dnaProvenWinnerSizeBoost6518")
        val liveBuy = source.indexOf("private fun liveBuy(", helper)
        val call = source.indexOf("dnaProvenWinnerSizeBoost6518(ts, layerTag)", liveBuy)
        val extractedHelper = source.substring(helper, liveBuy)
        assertTrue(helper > 0 && liveBuy > helper && call > liveBuy)
        assertTrue(source.substring(helper - 80, helper).contains("@androidx.annotation.Keep"))
        assertTrue(extractedHelper.contains("(avgWin - 20.0) / 100.0"))
        assertTrue(extractedHelper.contains("LiveWinDNAStore.setupFrequency"))
    }

    @Test
    fun `extracted helper preserves proven winner thresholds and exact boost formula`() {
        val helper = source.substring(
            source.indexOf("private fun dnaProvenWinnerSizeBoost6518"),
            source.indexOf("private fun liveBuy(")
        )
        assertTrue(helper.contains("setupFrequency(minCount = 3)"))
        assertTrue(helper.contains("losingSetupFrequency(minCount = 1)"))
        assertTrue(helper.contains("winCount >= 3 && wr >= 0.40 && avgWin > 20.0 && winCount > lossCount"))
        assertTrue(helper.contains("(1.0 + (wr - 0.40) * 1.5 + (avgWin - 20.0) / 100.0).coerceIn(1.10, 1.50)"))
        assertTrue(helper.contains("DNA_PROVEN_WINNER_SIZE_BOOST_6265"))
    }
}

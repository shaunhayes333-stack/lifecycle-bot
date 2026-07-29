package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * V5.0.6381 — Rugcheck Tier Recalibration invariants.
 *
 * Operator directive: "if the rugcheck scoring is right a score of 70 is
 * stupidly high. slot of good tokens only hit 6."
 *
 * Traditional-finance carry-over thresholds of 70/72/80/85/90 blocked
 * literally every Solana memecoin trade. Real distribution (rugcheck.xyz
 * feed Feb 2026): fresh pump.fun = 0-3, graduates = 3-10, runners = 10-25,
 * blue-chip memes = 25-50. New floors match reality and let the AATE
 * learning stack decide from actual trade outcomes.
 */
class Bundle6381RugcheckTiersTest {

    private fun scalingModeText(): String =
        File("src/main/kotlin/com/lifecyclebot/engine/ScalingMode.kt").readText()

    @Test
    fun micro_tier_matches_fresh_pump_fun_launch_distribution() {
        val txt = scalingModeText()
        assertEquals(
            "V5.0.6381: MICRO tier floor must be 1 (fresh pump.fun launches score 0-3)",
            1,
            "minRugcheckScore        = (\\d+),".toRegex().find(txt)?.groupValues?.get(1)?.toInt(),
        )
    }

    @Test
    fun standard_tier_matches_graduate_distribution() {
        val txt = scalingModeText()
        // Grab the STANDARD tier's rugcheck floor by finding the block after MICRO's.
        val standardBlock = txt.substringAfter("STANDARD(").substringBefore("GROWTH(")
        val floor = "minRugcheckScore\\s*=\\s*(\\d+)".toRegex().find(standardBlock)?.groupValues?.get(1)?.toInt()
        assertEquals(
            "V5.0.6381: STANDARD tier floor must be 5 (graduated bonders score 3-10)",
            5, floor,
        )
    }

    @Test
    fun growth_tier_matches_runner_distribution() {
        val txt = scalingModeText()
        val growthBlock = txt.substringAfter("GROWTH(").substringBefore("SCALED(")
        val floor = "minRugcheckScore\\s*=\\s*(\\d+)".toRegex().find(growthBlock)?.groupValues?.get(1)?.toInt()
        assertEquals(
            "V5.0.6381: GROWTH tier floor must be 12 (established runners score 10-25)",
            12, floor,
        )
    }

    @Test
    fun scaled_tier_matches_bluechip_meme_distribution() {
        val txt = scalingModeText()
        val scaledBlock = txt.substringAfter("SCALED(").substringBefore("INSTITUTIONAL(")
        val floor = "minRugcheckScore\\s*=\\s*(\\d+)".toRegex().find(scaledBlock)?.groupValues?.get(1)?.toInt()
        assertEquals(
            "V5.0.6381: SCALED tier floor must be 25 (bluechip memes like WIF/POPCAT score 25-50)",
            25, floor,
        )
    }

    @Test
    fun institutional_tier_matches_true_bluechip_distribution() {
        val txt = scalingModeText()
        val instBlock = txt.substringAfter("INSTITUTIONAL(").substringBefore(");")
        val floor = "minRugcheckScore\\s*=\\s*(\\d+)".toRegex().find(instBlock)?.groupValues?.get(1)?.toInt()
        assertEquals(
            "V5.0.6381: INSTITUTIONAL tier floor must be 45 (true bluechips SOL/USDC/ETH score 45+)",
            45, floor,
        )
    }
}

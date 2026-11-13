package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExitCensus8747
import com.lifecyclebot.engine.truth.LaunchReplay8744
import com.lifecyclebot.engine.truth.LaunchReplay8744.Ladder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8747 — a 50x path under each ladder; every exit reason graded on what the coin did after the sale. */
class Aate8747RunnerLaddersTest {
    private val solUsd = 100.0
    // $10k at entry (1e-7), climbs to 50x (5e-6), gives back to 3e-6 at the end of the hour
    private val t = longArrayOf(0, 1_000, 3_000, 60_000, 120_000, 300_000, 600_000, 900_000, 1_200_000, 3_000_000)
    private val p = doubleArrayOf(1e-7, 1e-7, 1e-7, 2e-7, 5e-7, 1e-6, 2e-6, 5e-6, 3e-6, 3e-6)
    private val b = BooleanArray(t.size) { true }
    private fun net(l: Ladder) = LaunchReplay8744.simulate8744(t, p, b, LaunchReplay8744.Rule(10_000.0, 3, 3.0, 35.0, l), solUsd, 0.0)!!

    @Test fun eachLadderBanksAFiftyXByItsOwnRungs() {
        // first print >= 3x is the 5x print (+400 %); the 10x print is +900 %; the hour ends at 30x (+2900 %), 40 % under the peak
        assertEquals(0.5 * 400.0 + 0.5 * 2900.0, net(Ladder.HALF_TRAIL), 1e-6)            // 30 % trail fires at the 40 % give-back
        assertEquals(0.5 * 400.0 + 0.3 * 900.0 + 0.2 * 2900.0, net(Ladder.MOONBAG), 1e-6)  // 60 % trail never fires
        assertEquals(0.25 * 100.0 + 0.18 * 400.0 + 0.57 * 0.67 * 900.0 + 0.57 * 0.33 * 2900.0, net(Ladder.DOCTRINE), 1e-6)
    }

    @Test fun exitReasonsAreFamilies() {
        assertEquals("RUNNER_STRUCTURE_BREAK", ExitCensus8747.family8747("RUNNER_STRUCTURE_BREAK_7967_1M_-13PCT"))
        assertEquals("LANE_HARD_SL_EXPRESS", ExitCensus8747.family8747("LANE_HARD_15PCT_SL_EXPRESS"))
        val bus = File("src/main/kotlin/com/lifecyclebot/engine/CanonicalLearning.kt").readText()
        assertEquals(2, Regex("ExitCensus8747\\.noteClose8747\\(").findAll(bus).count())
    }
}

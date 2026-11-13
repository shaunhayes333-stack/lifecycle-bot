package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaunchReplay8744
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8744 — real first hours replayed against entry triggers and exits, no look-ahead; the parity record persists. */
class Aate8744LaunchReplayTest {
    private val solUsd = 100.0   // price 1e-7 SOL/token x 1e9 x $100 = $10k
    private fun path(vararg pts: Pair<Long, Double>) = Triple(pts.map { it.first }.toLongArray(), pts.map { it.second }.toDoubleArray(),
        BooleanArray(pts.size) { true })

    @Test fun aRunnerBanksHalfAtTheTakeAndTrailsTheRest() {
        // crosses $10k at 1 s with 3 buys; entry at the first print >= 2 s later (1.0e-7); 4x at 60 s, peak 1e-6, falls 30 %
        val (t, p, b) = path(0L to 0.5e-7, 500L to 0.8e-7, 1_000L to 1.0e-7, 3_000L to 1.0e-7, 60_000L to 4.0e-7,
            120_000L to 1.0e-6, 130_000L to 0.69e-6)
        val r = LaunchReplay8744.Rule(10_000.0, 3, 4.0, 35.0)
        val net = LaunchReplay8744.simulate8744(t, p, b, r, solUsd, 2.5)!!
        // half at +300 %, half at +590 % (0.69e-6 / 1e-7 - 1), less 2.5 % cost
        assertEquals(0.5 * 300.0 + 0.5 * 590.0 - 2.5, net, 1e-6)
    }

    @Test fun aDudIsStoppedAndAnUntriggeredCoinIsNoTrade() {
        val (t, p, b) = path(0L to 1.0e-7, 1_000L to 1.0e-7, 2_000L to 1.0e-7, 4_000L to 1.0e-7, 30_000L to 0.6e-7, 40_000L to 0.2e-7)
        val net = LaunchReplay8744.simulate8744(t, p, b, LaunchReplay8744.Rule(10_000.0, 3, 3.0, 35.0), solUsd, 2.5)!!
        assertEquals(-40.0 - 2.5, net, 1e-6)   // first print past the -35 % stop: 0.6e-7
        assertNull(LaunchReplay8744.simulate8744(t, p, b, LaunchReplay8744.Rule(50_000.0, 3, 3.0, 35.0), solUsd, 2.5))
    }

    @Test fun theReplayAndParityAreReported() {
        val rep = File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue(rep.contains("Launch replay (§8744): "))
        val tune = File("src/main/kotlin/com/lifecyclebot/engine/truth/TuningReport8618.kt").readText()
        assertTrue(tune.contains("tuning_8744.json") && tune.contains("save8744()") && tune.contains("restore8744()"))
        assertTrue(LaunchReplay8744.GRID.size == 162)   // V5.0.8747: 3 caps x 3 buys x 3 takes x 2 stops x 3 ladders
    }
}

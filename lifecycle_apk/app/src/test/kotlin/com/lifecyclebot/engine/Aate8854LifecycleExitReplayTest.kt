package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.BaseFamily8754
import com.lifecyclebot.engine.truth.LaunchReplay8744
import com.lifecyclebot.engine.truth.LaunchReplay8744.Ladder
import com.lifecyclebot.engine.truth.LaunchReplay8744.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8854 — the exit APK 5.0.8096 / 5.0.8114 ran (LifecycleHold8065 from entry) is graded in the launch replay beside
 * DOCTRINE, on the hold's own constants: -40 % floor at any time, half at 2x then 30 % of the rest at 5x and 10x, the
 * 45 %-of-peak give-back after +300 %, no trail and no time stop.
 */
class Aate8854LifecycleExitReplayTest {
    private val cost = 3.0
    private val timeStop = 600_000L

    /** Prints 10 s apart (each fills at its own print: the next is past the 1.5 s sell latency), entry at print 0. */
    private fun path(vararg x: Double) = LaunchReplay8744.Path8748(LongArray(x.size) { it * 10_000L }, DoubleArray(x.size) { x[it] * 1e-7 },
        BooleanArray(x.size) { true }, BooleanArray(x.size) { true }, 0)
    private fun net(p: LaunchReplay8744.Path8748, l: Ladder, stopFrac: Double = 0.2) =
        LaunchReplay8744.exitNet8754(p, 0, l, 2.0, p.p[0] * (1.0 - stopFrac), timeStop, cost)!!

    @Test fun theRungsAreTheHoldsOwnTakes() {
        val r = LaunchReplay8744.LIFECYCLE_RUNGS_8854
        assertEquals(LifecycleHold8065.ladder8071().size, r.size)
        assertEquals(listOf(2.0, 5.0, 10.0), r.map { it.first })
        assertEquals(0.5, r[0].second, 1e-12); assertEquals(0.15, r[1].second, 1e-12); assertEquals(0.105, r[2].second, 1e-12)
    }

    @Test fun aDipThatTheLaneStopSellsIsHeldToTheRun() {
        val p = path(1.0, 0.79, 0.65, 0.7, 1.0, 1.5, 2.0, 3.0, 2.5)
        assertEquals("a 20 % stop sells the dip", -21.0 - cost, net(p, Ladder.DOCTRINE), 1e-6)
        // half at 2x (+100 %), the rest held to the hour's last print (+150 %)
        assertEquals(0.5 * 100.0 + 0.5 * 150.0 - cost, net(p, Ladder.LIFECYCLE), 1e-6)
    }

    @Test fun theFloorIsFortyPercentWhateverTheRuleSays() {
        val p = path(1.0, 0.9, 0.59, 2.0)
        assertEquals(-41.0 - cost, net(p, Ladder.LIFECYCLE, stopFrac = 0.05), 1e-6)
        assertEquals(40.0, LifecycleHold8065.FLOOR_PCT, 0.0)
        assertTrue(LaunchReplay8744.LIFECYCLE_8854.all { it.ladder == Ladder.LIFECYCLE && it.stopPct == LifecycleHold8065.FLOOR_PCT })
    }

    @Test fun afterAFourXPeakItGivesBackToFortyFivePercent() {
        // half at 2x, 15 % of the original at 5x; peak 5x, out at 2.2x (<= 0.45 x 5) with the 35 % still held
        val p = path(1.0, 2.0, 5.0, 3.0, 2.2, 4.0)
        assertEquals(0.5 * 100.0 + 0.15 * 400.0 + 0.35 * 120.0 - cost, net(p, Ladder.LIFECYCLE), 1e-6)
    }

    @Test fun itHasNoTimeStop() {
        val flat = DoubleArray(90) { 1.0 } + doubleArrayOf(2.0)
        val p = path(*flat)
        assertEquals("DOCTRINE leaves at ten minutes", -cost, net(p, Ladder.DOCTRINE), 1e-6)
        assertEquals("the hold is still in when it doubles", 100.0 - cost, net(p, Ladder.LIFECYCLE), 1e-6)
    }

    @Test fun itIsGradedEverywhereButNeverTraded() {
        assertTrue(LaunchReplay8744.GRID.containsAll(LaunchReplay8744.LIFECYCLE_8854))
        assertEquals(69, LaunchReplay8744.LIFECYCLE_8854.size)
        assertEquals(LaunchReplay8744.LIFECYCLE_8854.size, LaunchReplay8744.LIFECYCLE_8854.map { it.id }.toSet().size)
        assertTrue(LaunchReplay8744.rulesFor8829(38_000L).any { it.ladder == Ladder.LIFECYCLE && it.delayMs == 40_000L })
        assertTrue(BaseFamily8754.GRID.any { it.ladder == Ladder.LIFECYCLE })
        // a strong LIFECYCLE record is reported, not armed: the live exits run DOCTRINE
        val strong = LaunchReplay8744.Result(Rule(10_000.0, 3, 2.0, 40.0, Ladder.LIFECYCLE, 40_000L), 300, 20.0, 2.0, 0.4, 0.2, 0.05, 0.0, 15.0, 2.0)
        assertNull(LaunchReplay8744.provenOf8748(listOf(strong), 38_000L))
        val line = LaunchReplay8744.ladderLine8756(listOf(strong))
        assertTrue(line, line.contains("LIFECYCLE=exTop3+15.0±2.0%"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/lab/LlmLabEngine.kt").readText().contains("DOCTRINE|HALF_TRAIL|MOONBAG|LIFECYCLE"))
    }
}

package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaunchReplay8744
import com.lifecyclebot.engine.truth.LaunchReplay8744.Ladder
import com.lifecyclebot.engine.truth.LaunchReplay8744.Rule
import com.lifecyclebot.engine.truth.LaunchSniper8748
import com.lifecyclebot.engine.truth.Mind8746
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8749 — the sniper arms only a rule graded at the latency the bot really has, and the mind keeps what it learned
 * across a restart.
 */
class Aate8749LatencyAndMemoryTest {
    private val edges = longArrayOf(1, 2, 3, 5, 8, 12, 15, 20, 30, 60, 120)

    @Test fun theTapeLagQuantileIsTheBucketEdge() {
        // 50 prints under 1 s, 30 under 5 s, 20 at 30-60 s
        val h = LongArray(edges.size + 1); h[0] = 50; h[3] = 30; h[9] = 20
        assertEquals(1_000L, LaunchSniper8748.quantileMs8749(h, edges, 0.5))
        assertEquals(5_000L, LaunchSniper8748.quantileMs8749(h, edges, 0.75))
        assertEquals(60_000L, LaunchSniper8748.quantileMs8749(h, edges, 0.9))
        assertNull(LaunchSniper8748.quantileMs8749(LongArray(edges.size + 1), edges, 0.5))
        assertTrue(LaunchSniper8748.requiredDelayMs8749() >= 10_000L)
    }

    @Test fun aRuleGradedFasterThanTheBotCanActIsNotArmed() {
        val broad = { d: Long -> LaunchReplay8744.stats8748(Rule(10_000.0, 8, 3.0, 20.0, Ladder.DOCTRINE, d), List(120) { if (it % 3 == 0) 40.0 else -5.0 })!! }
        val r10 = broad(10_000L); val r30 = broad(30_000L)
        assertEquals(r10, LaunchReplay8744.provenOf8748(listOf(r10, r30), 10_000L))
        assertEquals(r30, LaunchReplay8744.provenOf8748(listOf(r10, r30), 20_000L))   // the bot needs 20 s: only the 30 s grade counts
        assertNull(LaunchReplay8744.provenOf8748(listOf(r10), 20_000L))
        assertTrue(LaunchReplay8744.GRID.any { it.delayMs == 5_000L } && LaunchReplay8744.GRID.any { it.delayMs == 20_000L })
    }

    @Test fun theMindsStateIsWrittenWhole() {
        val xtx = Array(10) { i -> DoubleArray(10) { j -> (i * 10 + j).toDouble() } }
        val j = JSONObject(Mind8746.toJson8749(xtx, DoubleArray(10) { it.toDouble() }, 412L, doubleArrayOf(3.0, -9.0, 50.0),
            doubleArrayOf(2.0, -8.0, 40.0), doubleArrayOf(4.0, 0.9, 1.0), Double.NaN))
        assertEquals(412L, j.getLong("n"))
        assertEquals(10, j.getJSONArray("xtx").length())
        assertEquals(99.0, j.getJSONArray("xtx").getJSONArray(9).getDouble(9), 0.0)
        assertTrue(j.isNull("spreadMedian"))
        val mind = File("src/main/kotlin/com/lifecyclebot/engine/truth/Mind8746.kt").readText()
        assertTrue(mind.contains("if (n % SAVE_EVERY_8749 == 0L) save8749()"))
        assertTrue(mind.substringAfter("fun learn8746(").substringBefore("frozen.remove").contains("restore8749()"))
        val cohort = File("src/main/kotlin/com/lifecyclebot/engine/truth/LaunchCohort8173.kt").readText()
        assertTrue(cohort.contains("LaunchSniper8748.noteBuyConfirmed8749(mint, nowMs)"))
    }
}

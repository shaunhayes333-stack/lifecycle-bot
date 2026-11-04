package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.cortex.LanePlaybook7907.PlaybookEdge8535
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8535 — audit C05: the playbook score mapped every expected net above ~+5 % to the same 100. */
class Aate8535PlaybookScaleTest {

    @Test fun distinct_expected_nets_stay_distinct_and_ordered_on_the_display() {
        val scores = listOf(2.0, 5.0, 15.0, 50.0).map { LanePlaybook7907.scoreOf7937(it, 0) }
        assertEquals("monotonic", scores, scores.sorted())
        assertEquals("no ties", 4, scores.toSet().size)
        assertTrue(scores.all { it in 50.0..100.0 })
        // superseded behaviour: 50 + 10x clipped — +5, +15 and +50 all read 100
        assertEquals(1, listOf(5.0, 15.0, 50.0).map { (50.0 + 10.0 * it).coerceIn(0.0, 100.0) }.toSet().size)
        assertTrue("negative records stay negative on the display without collapsing to one 0", LanePlaybook7907.scoreOf7937(-5.0, 0) > LanePlaybook7907.scoreOf7937(-20.0, 0))
        assertTrue(LanePlaybook7907.scoreOf7937(-20.0, 0) > 0.0)
    }

    @Test fun arbitration_reads_the_native_edge_not_the_display() {
        val thin = PlaybookEdge8535("S", expectedNetPct = 15.0, n = 1.0, stderrPct = Double.NaN, runnerRate = 0.0, extraSetups = 0)
        assertEquals("unmeasured: the shrunk prior is the conservative read", 15.0, thin.conservativeNetPct, 0.0)
        val measured = PlaybookEdge8535("S", expectedNetPct = 15.0, n = 40.0, stderrPct = 4.0, runnerRate = 0.1, extraSetups = 1)
        assertEquals(11.0, measured.conservativeNetPct, 0.0)
        val big = PlaybookEdge8535("S", expectedNetPct = 50.0, n = 40.0, stderrPct = 4.0, runnerRate = 0.3, extraSetups = 1)
        assertTrue("+15 % and +50 % are different trades on the native scale", big.conservativeNetPct > measured.conservativeNetPct)
        assertTrue(big.runnerRate > measured.runnerRate)
        assertTrue(big.displayScore >= measured.displayScore)
        assertEquals(LanePlaybook7907.scoreOf7937(15.0, 1), measured.displayScore, 0.0)
        assertEquals(8.0, LanePlaybook7907.DISPLAY_SCALE_PCT_8535, 0.0)
    }
}

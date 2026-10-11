package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.StopLearner8040
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8040 — the stack grades its own stops on every labelled path, and grades past the hour on tokenomics. */
class Aate8040LearnsItsStopsTest {
    private val c = StopLearner8040.CANDIDATES_8040

    /** A lane whose coins dip 12% before running +60%: an 8% stop is stopped out, 15%+ rides it. */
    private fun dipThenRun(): DoubleArray = DoubleArray(c.size) { i -> if (c[i] <= 12.0) -c[i] else 60.0 }

    @Test fun stopsThatCutRunnersAreWidened() {
        val rows = List(StopLearner8040.MIN_N_8040 + 10) { k -> dipThenRun().also { it[0] += (k % 3) * 0.01 } }
        val pick = StopLearner8040.decide8040(rows, StopLearner8040.nearest8040(8.0))!!
        assertTrue(c[pick.first] >= 15.0)
        assertTrue(pick.second >= StopLearner8040.MIN_EDGE_PCT_8040)
    }

    @Test fun widerStopsThatOnlyLoseMoreAreNotAdopted() {
        // Coins that just bleed: a wider stop banks a bigger loss, so the tight stop stays.
        val rows = List(StopLearner8040.MIN_N_8040 + 10) { DoubleArray(c.size) { i -> -c[i] } }
        val pick = StopLearner8040.decide8040(rows, StopLearner8040.nearest8040(8.0))
        assertTrue(pick == null || c[pick.first] < 8.0)
        // Too few paths: nothing is learned.
        assertNull(StopLearner8040.decide8040(rows.take(10), 3))
        assertEquals(10.0, c[StopLearner8040.nearest8040(10.4)], 1e-9)
    }

    @Test fun pastTheHourOnTokenomics() {
        // Healthy: alive, trading, spread holders, cap its pool can carry — followed even when flat.
        assertTrue(ForwardReturnLabeler7731.worthWatching8040(-5.0, 10_000.0, 9_000.0, 60_000L, 12.0, 400_000.0, false))
        // One holder owns 70%: not worth watching.
        assertFalse(ForwardReturnLabeler7731.worthWatching8040(40.0, 10_000.0, 9_000.0, 60_000L, 70.0, 400_000.0, false))
        // Nobody trading it for 20 minutes.
        assertFalse(ForwardReturnLabeler7731.worthWatching8040(40.0, 10_000.0, 9_000.0, 20L * 60_000L, 12.0, 400_000.0, false))
        // A cap 500x its pool.
        assertFalse(ForwardReturnLabeler7731.worthWatching8040(40.0, 10_000.0, 9_000.0, 60_000L, 12.0, 4_500_000.0, false))
        // Liquidity pulled (below 40% of the decision's).
        assertFalse(ForwardReturnLabeler7731.worthWatching8040(40.0, 10_000.0, 3_000.0, 60_000L, 12.0, 400_000.0, false))
        // Safety-blocked (rug / honeypot).
        assertFalse(ForwardReturnLabeler7731.worthWatching8040(40.0, 10_000.0, 9_000.0, 60_000L, 12.0, 400_000.0, true))
        // Down 70% but the pool holds and it still trades: followed, so a recovery is graded.
        assertTrue(ForwardReturnLabeler7731.worthWatching8040(-70.0, 10_000.0, 6_000.0, 60_000L, 12.0, 400_000.0, false))
        // Down 70% with no live mark: dead.
        assertFalse(ForwardReturnLabeler7731.worthWatching8040(-70.0, 10_000.0, 6_000.0, null, 12.0, 400_000.0, false))
        // Off the watchlist: only the price rule can judge.
        assertTrue(ForwardReturnLabeler7731.worthWatching8040(25.0, 0.0, Double.NaN, null, null, Double.NaN, null))
        assertFalse(ForwardReturnLabeler7731.worthWatching8040(5.0, 0.0, Double.NaN, null, null, Double.NaN, null))
        val frl = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        assertTrue(frl.contains("StopLearner8040.onPath8040") && frl.contains("1L * 60_000L") && frl.contains("7L * 24L * 3_600_000L"))
        // V5.0.8042 — only hour-old paths teach a stop, and a runner lane's floor bounds the choice.
        assertTrue(frl.contains("System.currentTimeMillis() - o.atMs) { s ->"))
        assertEquals(60L * 60_000L, StopLearner8040.MATURE_PATH_MS_8042)
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/cortex/StopAuthority7887.kt").readText().contains("StopLearner8040.learnedStop8040"))
    }
}

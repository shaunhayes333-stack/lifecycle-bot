package com.lifecyclebot.engine

import com.lifecyclebot.engine.chart.ColourShape8807
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** V5.0.8822 — every completed stored launch hour is one chart for the colour/shape setup learner, once. */
class Aate8822LaunchChartsTest {
    private val min = 60_000L
    @Before fun setUp() { ColourShape8807.resetForTest8807() }

    private fun hour(minutes: Int): Pair<LongArray, DoubleArray> {
        val t = LongArray(minutes * 2) { it * 30_000L + 1_000L }
        var px = 1e-7
        val p = DoubleArray(t.size) { i -> px *= if (i % 7 == 3) 0.96 else 1.015; px }
        return t to p
    }

    @Test fun printsBecomeMinuteCandlesFromBirth() {
        val born = 7_000L * min
        val bars = ColourShape8807.minuteBars8822(longArrayOf(1_000L, 20_000L, 130_000L), doubleArrayOf(1.0, 1.2, 0.9), doubleArrayOf(0.5, 0.5, 1.0), born)
        assertEquals(3, bars.size)
        assertEquals(listOf(1.0, 1.2, 1.0, 1.2, 1.0), listOf(bars[0].o, bars[0].h, bars[0].l, bars[0].c, bars[0].v))
        assertEquals("a quiet minute is flat", 0.0, bars[1].v, 0.0)
        assertEquals(listOf(1.2, 1.2, 0.9, 0.9), listOf(bars[2].o, bars[2].h, bars[2].l, bars[2].c))
    }

    @Test fun aCompletedLaunchHourIsLearnedOnce() {
        val born = 10_000L * min
        val (t, p) = hour(60)
        assertFalse("its hour is not complete yet", ColourShape8807.learnLaunch8822("LaunchA", born, t, p, null, born + 30 * min))
        assertTrue(ColourShape8807.learnLaunch8822("LaunchA", born, t, p, null, born + 70 * min))
        assertFalse("one coin, one chart", ColourShape8807.learnLaunch8822("LaunchA", born, t, p, null, born + 100 * min))
        val (ts, ps) = hour(20)
        assertFalse("twenty minutes is too short to read", ColourShape8807.learnLaunch8822("LaunchB", born, ts, ps, null, born + 70 * min))
        assertTrue(ColourShape8807.statusLine8807().contains("launches8822{learned=1 short=1}"))
    }

    @Test fun theLaunchReplayFeedsTheLearner() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/LaunchReplay8744.kt").readText()
        assertTrue(src.contains("ColourShape8807.learnLaunch8822(r.mint, r.bornMs, x.t, x.p, x.sol, nowMs)"))
    }
}

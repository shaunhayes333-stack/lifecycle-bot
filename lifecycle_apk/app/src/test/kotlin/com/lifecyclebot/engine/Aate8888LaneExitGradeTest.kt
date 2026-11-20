package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaneExitGrade8888
import com.lifecyclebot.engine.truth.ShadowFill8364
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8888 — plan W5: each doctrine lane's own launches grade the doctrine's give-back (45 %) against keeping 70 % and 40 %
 * of a 2x-plus peak, paired on the same prints, run by the shadow's own doctrine exit. The live doctrine is unchanged.
 */
class Aate8888LaneExitGradeTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val noClock = Long.MAX_VALUE / 4

    @After fun tearDown() = LaneExitGrade8888.setForTest8888(emptyList())

    private fun net(prices: List<Double>, keep: Double, stop: Double = 20.0, cost: Double = 3.0): Double {
        val t = LongArray(prices.size) { it * 1_000L }
        return LaneExitGrade8888.doctrineNet8888(t, prices.toDoubleArray(), 0, prices[0], "SHITCOIN", stop, noClock, cost, keep, 0L)!!
    }

    @Test fun theDoctrineShareIsTheLiveLineBitForBit() {
        assertEquals(RunnerDoctrine8584.KEEP_8584, 0.55, 0.0)
        assertEquals(3.0 * 0.55, RunnerDoctrine8584.line8584(3.0), 0.0)
        assertEquals(RunnerDoctrine8584.line8584(3.0), RunnerDoctrine8584.line8584(3.0, RunnerDoctrine8584.KEEP_8584), 0.0)
        assertEquals(1.5, RunnerDoctrine8584.line8584(2.0), 1e-9)
        assertEquals(RunnerDoctrine8584.paperStopLevel8584(25.0, 300.0), RunnerDoctrine8584.paperStopLevel8584(25.0, 300.0, 0.55), 0.0)
        assertEquals("the graded shares are the doctrine's and one either side", listOf(0.70, 0.55, 0.40),
            LaneExitGrade8888.KEEPS_8888.toList())
        val doc = src("engine/RunnerDoctrine8584.kt")
        assertTrue(doc.contains("fun line8584(peakMult: Double, keep: Double = KEEP_8584): Double ="))
        assertTrue(doc.contains("fun paperStopLevel8584(stopPct: Double, peakPct: Double, keep: Double = KEEP_8584): Double ="))
    }

    @Test fun aTighterLineKeepsMoreOfAFadeAndAWiderOneMoreOfARun() {
        val fade = listOf(1.0, 1.5, 2.0, 3.0, 4.0, 5.0, 4.5, 4.0, 3.6, 3.4, 3.2, 3.0, 2.8, 2.6, 2.4, 2.2, 2.0, 1.9, 1.8)
        val k70 = net(fade, 0.70); val k55 = net(fade, 0.55); val k40 = net(fade, 0.40)
        assertTrue("a 5x that fades: the tighter line sells higher ($k70 > $k55 > $k40)", k70 > k55 && k55 > k40)
        val run = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 3.4, 3.3, 6.0, 10.0, 20.0, 19.0)
        assertTrue("a 5x that dips to 3.3x and runs to 20x: the doctrine's line holds it, the tight one sold it",
            net(run, 0.55) > net(run, 0.70) + 100.0)
    }

    @Test fun belowTheTrailEveryShareIsTheSameExitAndOnePrintIsNotABreach() {
        val dump = listOf(1.0, 0.9, 0.85, 0.75, 0.7, 0.5)
        assertEquals(net(dump, 0.70), net(dump, 0.55), 0.0)
        assertEquals(net(dump, 0.40), net(dump, 0.55), 0.0)
        val wick = listOf(1.0, 1.2, 0.79, 1.1, 1.3)
        assertEquals("a single print under the stop is not a sale: held to the hour's end",
            ShadowFill8364.exitGrossPct8364(30.0) - 3.0, net(wick, 0.55), 1e-9)
    }

    @Test fun eachLanesShareIsGradedPairedAndDeflated() {
        fun rows(lane: String, n: Int, d70: Double, d40: Double) = (0 until n).map { i ->
            val noise = if (i % 2 == 0) 1.0 else -1.0
            LaneExitGrade8888.Graded8888(lane, i % 3 == 0, doubleArrayOf(10.0 + d70 + noise, 10.0, 10.0 + d40 - noise))
        }
        val grades = LaneExitGrade8888.grade8888(rows("SHITCOIN", 120, 5.0, -5.0) + rows("MOONSHOT", 120, 0.0, 0.0) + rows("EXPRESS", 50, 9.0, 9.0))
        val shit = grades.single { it.lane == "SHITCOIN" }
        assertEquals(LaneExitGrade8888.Verdict8888.BETTER, shit.diffs.single { it.keep == 0.70 }.verdict)
        assertEquals(LaneExitGrade8888.Verdict8888.WORSE, shit.diffs.single { it.keep == 0.40 }.verdict)
        assertEquals(10.0, shit.doctrineMean, 0.0); assertEquals(40, shit.admitted)
        val moon = grades.single { it.lane == "MOONSHOT" }
        assertTrue("a noise-only difference is not proven", moon.diffs.all { it.verdict == LaneExitGrade8888.Verdict8888.SAME })
        assertTrue("under 100 decisions nothing is proven", grades.single { it.lane == "EXPRESS" }.diffs.all { it.verdict == LaneExitGrade8888.Verdict8888.SAME })
        LaneExitGrade8888.setForTest8888(grades)
        assertEquals(0.70, LaneExitGrade8888.provenKeep8888("shitcoin")!!, 0.0)
        assertNull(LaneExitGrade8888.provenKeep8888("MOONSHOT"))
        assertTrue(LaneExitGrade8888.statusLine8888().contains("SHITCOIN{n120 a40 doctrine=+10.0%"))
    }

    @Test fun theReplayGradesEveryDoctrineLanesFirstDecisionOnItsLaunch() {
        val replay = src("engine/truth/LaunchReplay8744.kt")
        assertTrue(replay.contains("set.add(x); mints8888.add(r.mint)"))
        // superseded by V5.0.8894 — the first decisions are read by the replayed launches' own mints on the primary key
        // (8888 sorted every decision row of 48 h: the app's top CPU frame in 5.0.8889 / 5.0.8893); same rows, same values
        assertTrue(replay.contains("LaneExitGrade8888.evaluate8888(set, mints8888, LaunchHour8628.firstDecisions8888(nowMs, mints8888), nowMs)"))
        assertTrue(src("engine/truth/LaunchHour8628.kt").contains("SELECT l,MIN(t),a,c FROM decision WHERE m=? AND t >= CAST(? AS INTEGER) GROUP BY l ORDER BY l"))
        val g = src("engine/truth/LaneExitGrade8888.kt")
        assertTrue(g.contains("if (breached && MemePaperTrader8544.paperStopConfirmed8683(breachAt, t[k], g))"))
        assertTrue(g.contains("com.lifecyclebot.engine.RunnerDoctrine8584.paperStopLevel8584(stopPct, peak, keep)"))
        assertTrue(g.contains("ShadowFill8364.laneEntryPx8364(x.p[e], d.lane)"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("com.lifecyclebot.engine.truth.LaneExitGrade8888.statusLine8888()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 77. Each doctrine lane grades its own give-back (8888)"))
        // V5.0.8889 — the doctrine's share is found by exact comparison (floating-point arrays have no indexOf here)
        assertTrue(g.contains("val doctrineIdx = KEEPS_8888.indexOfFirst { it == com.lifecyclebot.engine.RunnerDoctrine8584.KEEP_8584 }"))
    }
}

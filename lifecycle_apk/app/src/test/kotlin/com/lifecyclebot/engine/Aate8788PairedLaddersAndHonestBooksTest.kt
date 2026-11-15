package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaunchReplay8744
import com.lifecyclebot.engine.truth.LaunchReplay8744.Ladder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8788 — the 8757 data: the paper book went broke and stopped measuring; the replays graded only launches that later
 * printed 20 trades; ladders were ranked on their own noise. The book keeps measuring, the replays grade every launch with
 * three prints, and the ladders are compared launch by launch.
 */
class Aate8788PairedLaddersAndHonestBooksTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    /** A launch printed every 10 s, every print a live buy, from 1e-7 SOL/token ($7k cap at $70). */
    private fun path(prices: List<Double>): LaunchReplay8744.Path8748 {
        val n = prices.size
        return LaunchReplay8744.clean8748(LongArray(n) { it * 10_000L }, prices.toDoubleArray(), BooleanArray(n) { true }, BooleanArray(n) { true })
    }
    private val dump = (0..60).map { 1.0e-7 * Math.pow(0.95, it.toDouble()) }
    private val runner = (0..140).map { i -> 1.0e-7 * (if (i <= 130) 1.0 + i * 0.1 else 14.0 - (i - 130) * 1.2) }
    private val base = LaunchReplay8744.Rule(5_000.0, 3, 2.0, 35.0, Ladder.DOCTRINE, 2_000L)

    @Test fun laddersAreComparedOnTheSameLaunches() {
        // a dump never reaches a rung: every ladder is DOCTRINE exactly, launch by launch
        val same = LaunchReplay8744.pairedLadders8788(List(150) { path(dump) }, base, 70.0, 3.0)
        assertEquals(Ladder.values().size - 1, same.size)
        for (p in same) {
            assertEquals(150, p.n); assertEquals(0.0, p.meanDiff, 1e-9); assertEquals("same", p.verdict)
        }
        // the same runner every time: the difference is the same every launch, so it is decided (no noise left)
        val run = LaunchReplay8744.pairedLadders8788(List(120) { path(runner) }, base, 70.0, 3.0)
        assertTrue(run.all { it.n == 120 && it.meanDiff.isFinite() })
        assertTrue(run.any { it.verdict == "BEATS_DOCTRINE" || it.verdict == "WORSE" })
        // under the sniper's n bar it says thin, whatever the difference
        assertTrue(LaunchReplay8744.pairedLadders8788(List(40) { path(runner) }, base, 70.0, 3.0).all { it.verdict == "thin" })
        // a launch the trigger never fires on is not compared
        val quiet = LaunchReplay8744.pairedLadders8788(listOf(path(listOf(1.0e-9, 1.0e-9, 1.0e-9))), base, 70.0, 3.0)
        assertTrue(quiet.all { it.n == 0 })
    }

    @Test fun thePairedComparisonIsReportedAndBriefed() {
        val replay = src("engine/truth/LaunchReplay8744.kt")
        assertTrue(replay.contains("paired = pairedLadders8788(set, base, solUsd, cost); pairedBase = base"))
        assertTrue(replay.contains("\${ladderLine8756(last)} \${pairedLine8788()}"))
        assertTrue(src("engine/lab/StrategyLab8755.kt").contains("LaunchReplay8744.pairedLine8788()"))
    }

    @Test fun theReplaysGradeEveryLaunchNotOnlyTheSurvivors() {
        val hour = src("engine/truth/LaunchHour8628.kt")
        assertTrue(hour.contains("private const val REPLAY_MIN_PRINTS_8788 = 3"))
        assertTrue(hour.contains("if (ts.size >= REPLAY_MIN_PRINTS_8788) cb(Launch8756("))
        assertTrue("the export keeps its floor", hour.contains("if (prints.size >= PATH_MIN_PRINTS_8741) w.appendLine(pathRow8741("))
        assertFalse(hour.contains("fun forEachLaunch8744("))
        assertTrue(src("engine/truth/LaunchReplay8744.kt").contains("launches>=3prints=\${set.size}"))
    }

    @Test fun thePaperBookNeverStopsMeasuring() {
        val book = src("engine/truth/MemePaperTrader8544.kt")
        assertFalse(book.contains("NO_PAPER_BALANCE"))
        assertTrue(book.contains("fun roomFor8544(openTotal: Int, openInLane: Int, mintOpen: Boolean, sinceLastEntryMs: Long): String?"))
        assertTrue("the balance is still reported", book.contains("balance=\${\"%.3f\".format(balanceSol)}SOL"))
    }
}

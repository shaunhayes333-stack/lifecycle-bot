package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.BaseFamily8754
import com.lifecyclebot.engine.truth.LaunchReplay8744
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8907 — the lab grades BaseFamily8754 on the paths it is handed; both files round-trip exactly. */
class Aate8907LabFamilyTest {
    private fun path(seed: Int): LaunchReplay8744.Path8748 {
        val n = 300
        val t = LongArray(n) { 1_795_000_000_000L + it * 4_000L }
        val p = DoubleArray(n) { i -> 1e-6 * (1.0 + 0.6 * kotlin.math.sin((i + seed) / 17.0) + i / 200.0) }
        return LaunchReplay8744.Path8748(t, p, BooleanArray(n) { (it + seed) % 3 != 0 }, BooleanArray(n) { true }, seed,
            DoubleArray(n) { 0.5 + it * 0.01 }, if (seed % 2 == 0) IntArray(n) { it % 7 } else null, 1_795_000_000_000L - 60_000L)
    }

    @Test fun thePathsRoundTripExactly() {
        val set = (0 until 5).map { path(it) }
        val f = File.createTempFile("paths8907", ".bin")
        try {
            LabProcess8906.writePaths8906(f, set, 140.5, 3.2, 12_000L)
            val (args, back) = LabProcess8906.readPaths8906(f)
            assertEquals(140.5, args.first, 0.0); assertEquals(3.2, args.second, 0.0); assertEquals(12_000L, args.third)
            assertEquals(set.size, back.size)
            for ((a, b) in set.zip(back)) {
                assertArrayEquals(a.t, b.t); assertArrayEquals(a.p, b.p, 0.0)
                assertTrue(a.buy.contentEquals(b.buy) && a.live.contentEquals(b.live))
                assertEquals(a.spikes, b.spikes); assertEquals(a.bornMs, b.bornMs)
                assertTrue((a.sol == null && b.sol == null) || a.sol!!.contentEquals(b.sol!!))
                assertTrue((a.wallet == null && b.wallet == null) || a.wallet!!.contentEquals(b.wallet!!))
            }
        } finally { f.delete() }
    }

    @Test fun theGradeTheLabReturnsIsTheGradeMadeHere() {
        val set = (0 until 40).map { path(it) }
        val here = BaseFamily8754.grade8906(set, 140.0, 3.0, 12_000L)
        val f = File.createTempFile("graded8907", ".bin")
        try {
            LabProcess8906.writeGraded8906(f, here, 12_000L)
            assertEquals(here, LabProcess8906.readGraded8906(f, 12_000L))
            assertEquals("the grid, plus DOCTRINE at the bot's 15 s latency once per trigger",
                BaseFamily8754.GRID.size + BaseFamily8754.TRIGGERS.size, BaseFamily8754.rules8906(12_000L).size)
            assertEquals(BaseFamily8754.GRID, BaseFamily8754.rules8906(0L))
        } finally { f.delete() }
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/BaseFamily8754.kt").readText()
        assertTrue(src.contains("val res = lab8906 ?: grade8906(set, solUsd, costPct, needDelayMs)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 95. The lab process, stage 2: the base family (8907)"))
    }
}

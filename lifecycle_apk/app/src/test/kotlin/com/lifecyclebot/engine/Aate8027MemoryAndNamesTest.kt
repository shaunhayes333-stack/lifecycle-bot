package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8027 — 8026 died of OOM at 22.7 min with 0 token rows pruned; the unmanaged coin had no name. */
class Aate8027MemoryAndNamesTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun tokenRowsPruneOnIntakeAgeAtEveryTrim() {
        assertTrue(MemoryGuard7977.prunable8025(watched = false, open = false, ticketed = false, staleMs = 11 * 60_000L))
        assertFalse(MemoryGuard7977.prunable8025(watched = false, open = false, ticketed = false, staleMs = 9 * 60_000L))
        val g = src("engine/MemoryGuard7977.kt")
        assertTrue(g.contains("val last = ts.addedToWatchlistAt"))
        assertTrue(g.contains("        try { pruneTokens8025() } catch (_: Throwable) {}"))
        assertFalse(g.contains("if (hard) try { pruneTokens8025() }"))
    }

    @Test fun heldAndUnreconciledCoinsAreNamed() {
        assertTrue(com.lifecyclebot.engine.truth.MissingMarkExitVeto6835.statusLine().contains("held=["))
        assertTrue(com.lifecyclebot.engine.truth.OnChainCost8024.statusLine().contains("unreconciledNow=["))
    }

    @Test fun deadAndFlatChartsAreNotBoughtLive() {
        // ƙөƙ: top $0.000063, bought at $0.0000034.
        assertTrue(FlatChart8027.deadRunner8027(0.000063, 0.0000034))
        assertFalse(FlatChart8027.deadRunner8027(0.000010, 0.0000034))
        // MINEPAD: flat at $0.0000044 for minutes.
        assertTrue(FlatChart8027.flatline8027(List(6) { 0.0000044 } + 0.00000441))
        assertFalse(FlatChart8027.flatline8027(listOf(0.0000044, 0.0000046, 0.0000044, 0.0000045, 0.0000047)))
        assertFalse(FlatChart8027.flatline8027(listOf(0.0000044, 0.0000044, 0.0000044)))   // too new to judge
        assertTrue(FlatChart8027.FLAT_MIN_BARS == 5 && FlatChart8027.FLAT_RANGE_PCT == 2.0 && FlatChart8027.DEAD_RATIO == 5.0)
        assertTrue(FlatChart8027.FLAT_WINDOW_MS == 6L * 60_000L)
        assertTrue(src("engine/Executor.kt").contains("        if (flatChartRefused8027(ts, sol)) return false"))
        assertTrue(FlatChart8027.statusLine().contains("deadRunnerRefused="))
    }
}

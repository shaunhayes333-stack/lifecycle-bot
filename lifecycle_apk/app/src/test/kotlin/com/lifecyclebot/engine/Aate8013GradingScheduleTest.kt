package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8013GradingScheduleTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val h = 3_600_000L

    @Test fun theOwnersScheduleAndBeyond() {
        val f = src("engine/truth/ForwardReturnLabeler7731.kt")
        // V5.0.8040 — owner's schedule: 1 m / 5 m labels, then 1 h, 4 h, 24 h and beyond for coins worth watching.
        // superseded by V5.0.8357 — owner's schedule restores the 15-minute checkpoint ahead of the hour (1m, 5m, 15m, 1h, 4h, 24h, then 48h/72h/7d on tokenomics)
        assertTrue(f.contains("longArrayOf(15L * 60_000L, 60L * 60_000L, 4L * 3_600_000L, 24L * 3_600_000L,"))
        assertTrue(f.contains("48L * 3_600_000L, 72L * 3_600_000L, 7L * 24L * 3_600_000L)"))
        assertTrue(ForwardReturnLabeler7731.scheduleOpen8013(0, 1 * h))
        // superseded by V5.0.8357 — indices shift by one: 0=15m 1=1h 2=4h 3=24h 4=48h 5=72h 6=7d
        assertTrue(ForwardReturnLabeler7731.scheduleOpen8013(4, 30 * h))      // the 24 h checkpoint passed, 48 h next
        assertTrue(ForwardReturnLabeler7731.scheduleOpen8013(6, 6 * 24 * h))  // the 7 d checkpoint still ahead
        assertFalse(ForwardReturnLabeler7731.scheduleOpen8013(7, 2 * h))      // schedule finished
        assertFalse(ForwardReturnLabeler7731.scheduleOpen8013(6, 8 * 24 * h)) // past the last checkpoint's grace
    }

    @Test fun followedCoinsSurviveRestartsAndLeavingTheWatchlist() {
        val f = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(f.contains(".filter { nowMs - it.atMs <= H240_MS_7731 + LOST_GRACE_MS_7731 || followed8013(it, nowMs) }"))
        // superseded by V5.0.8291 — the checkpoint still asks for an off-watch mark, its due time now carries the off-watch rank.
        assertTrue(f.contains("else if (checkpointDue8013(o, age)) dueUnpriced7737.add(o.mint to com.lifecyclebot.engine.cortex.CortexLabelFeed8291.offWatchKey8291(rank8291, o.atMs + CK_MS_7997[o.ck7997]))"))
        assertTrue(f.contains("else if (!followed8013(o, nowMs)) {"))
        assertTrue(f.contains("o.feats7972 = f[30].split('\\u0002').filter { it.isNotBlank() }"))
        val c = src("engine/cortex/Cortex7885.kt")
        assertTrue(c.contains(".put(\"g8013\", garr)"))
        assertTrue(c.contains("restoreGraded8013(root, dict, nowMs)"))
    }
}

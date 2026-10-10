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
        assertTrue(f.contains("longArrayOf(15L * 60_000L, 30L * 60_000L, 60L * 60_000L, 5L * 3_600_000L,"))
        assertTrue(f.contains("24L * 3_600_000L, 48L * 3_600_000L, 72L * 3_600_000L, 7L * 24L * 3_600_000L)"))
        assertTrue(ForwardReturnLabeler7731.scheduleOpen8013(0, 1 * h))
        assertTrue(ForwardReturnLabeler7731.scheduleOpen8013(4, 30 * h))      // the 24 h checkpoint passed, 48 h next
        assertTrue(ForwardReturnLabeler7731.scheduleOpen8013(7, 6 * 24 * h))  // the 7 d checkpoint still ahead
        assertFalse(ForwardReturnLabeler7731.scheduleOpen8013(8, 2 * h))      // schedule finished
        assertFalse(ForwardReturnLabeler7731.scheduleOpen8013(7, 8 * 24 * h)) // past the last checkpoint's grace
    }

    @Test fun followedCoinsSurviveRestartsAndLeavingTheWatchlist() {
        val f = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(f.contains(".filter { nowMs - it.atMs <= H240_MS_7731 + LOST_GRACE_MS_7731 || followed8013(it, nowMs) }"))
        assertTrue(f.contains("else if (checkpointDue8013(o, age)) dueUnpriced7737.add(o.mint to (o.atMs + CK_MS_7997[o.ck7997]))"))
        assertTrue(f.contains("else if (!followed8013(o, nowMs)) {"))
        assertTrue(f.contains("o.feats7972 = f[30].split('\\u0002').filter { it.isNotBlank() }"))
        val c = src("engine/cortex/Cortex7885.kt")
        assertTrue(c.contains(".put(\"g8013\", garr)"))
        assertTrue(c.contains("restoreGraded8013(root, dict, nowMs)"))
    }
}

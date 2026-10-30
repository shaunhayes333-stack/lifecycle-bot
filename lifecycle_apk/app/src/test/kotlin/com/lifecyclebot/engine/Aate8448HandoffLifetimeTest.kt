package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8448 — a hand-off task lives long enough for the receiver lane's scheduled evaluation to reach FDG on a slow loop.
 * 8439 (24 s cycles): SCHEDULED_NO_FDG_VERDICT=66, STALE_NOT_SCHEDULED=125; 8441 (29.5 s): 44 scheduled, 28 evaluated.
 * The window is unchanged on a healthy loop and never passes the task's maximum age.
 */
class Aate8448HandoffLifetimeTest {
    private val t = HandoffReceiverTasks8153

    @Test fun theWindowFollowsTheMeasuredLoop() {
        assertEquals("no cycles measured: the 45 s window", t.TASK_TTL_MS_8153, t.ttlFor8448(0L))
        assertEquals("a healthy 3 s loop: unchanged", t.TASK_TTL_MS_8153, t.ttlFor8448(3_000L))
        assertEquals("8441's 29.5 s loop: three cycles", 88_500L, t.ttlFor8448(29_500L))
        assertEquals("never past the maximum age", t.TASK_MAX_AGE_MS_8153, t.ttlFor8448(120_000L))
        assertEquals(t.TASK_TTL_MS_8153, t.ttlFor8448(-5L))
    }

    @Test fun everyRefreshUsesTheLoopAwareWindow() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/HandoffReceiverTasks8153.kt").readText()
        assertFalse(s.contains("nowMs + TASK_TTL_MS_8153"))
        assertFalse(s.contains("untilMs + TASK_TTL_MS_8153"))
        assertTrue(Regex("nowMs \\+ ttl8448\\(\\)").findAll(s).count() >= 8)
        assertTrue(PipelineHealthCollector.recentAvgCycleMs8448() >= 0L)
    }
}

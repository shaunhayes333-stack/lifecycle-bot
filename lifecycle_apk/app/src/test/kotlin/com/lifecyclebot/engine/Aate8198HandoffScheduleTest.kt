package com.lifecyclebot.engine

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** 8198 P2 — a hand-off the cycle could not schedule expires with the cause named, never STALE_NOT_SCHEDULED. */
class Aate8198HandoffScheduleTest {
    private val t = HandoffReceiverTasks8153

    @Before fun setUp() = t.resetForTests8153()
    @After fun tearDown() = t.resetForTests8153()

    @Test fun notInPoolAndBeyondBoundAreNamedAtExpiry() {
        val now = 1_000_000L
        for (i in 1..14) t.openTask8153("m$i", "BLUECHIP", "SNIPER", "RECEIVER_QUALIFIED", 7L, now, now)
        val targets = t.pendingTargets8190(now)
        assertEquals(14, targets.size)
        val present = (1..13).map { "m$it" }                                   // m14 left the pool
        val chosen = HandoffPriority8190.select8190(targets, present)          // bound 12: one present mint is left over
        assertEquals(12, chosen.size)
        HandoffPriority8190.accountDropped8198(targets, present, chosen, now)
        val leftOver = present.first { it !in chosen }
        assertEquals("BEYOND_CYCLE_BOUND", t.taskOf8153(leftOver, now)!!.lastBlock)
        assertEquals("NOT_IN_CYCLE_POOL", t.taskOf8153("m14", now)!!.lastBlock)
        assertEquals("", t.taskOf8153(chosen.first(), now)!!.lastBlock)      // a scheduled target keeps its own record
        val expired = t.taskOf8153("m14", now + HandoffReceiverTasks8153.TASK_TTL_MS_8153 + 1L)!!
        assertEquals(HandoffReceiverTasks8153.Outcome8153.REFUSED, expired.outcome)
        assertEquals("NOT_SCHEDULED:NOT_IN_CYCLE_POOL", expired.verdict)
    }

    @Test fun aReceiverBlockOrScheduleIsNotOverwrittenByTheCycleCause() {
        val now = 2_000_000L
        t.openTask8153("mx", "BLUECHIP", "SNIPER", "RECEIVER_QUALIFIED", 7L, now, now)
        t.markScheduled8153("mx", "BLUECHIP", now)
        t.noteUnscheduled8198("mx", "BEYOND_CYCLE_BOUND", now)
        assertEquals("", t.taskOf8153("mx", now)!!.lastBlock)
        assertEquals(1, t.taskOf8153("mx", now)!!.schedules)
    }
}

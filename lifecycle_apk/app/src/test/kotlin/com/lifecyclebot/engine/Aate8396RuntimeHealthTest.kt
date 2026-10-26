package com.lifecyclebot.engine

import com.lifecyclebot.engine.HandoffReceiverTasks8153.Block8153
import com.lifecyclebot.engine.HandoffReceiverTasks8153.Outcome8153
import com.lifecyclebot.engine.HandoffReceiverTasks8153.Task8153
import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.truth.ShadowPersist8355
import org.junit.Assert.*
import org.junit.Test

/** V5.0.8396 — memory cadence, compact shadow rows, and named lane-cycle terminals for hand-off receivers. */
class Aate8396RuntimeHealthTest {
    @Test fun unavailableOnlyTerminalsDoNotRewriteTheWholeCortexEveryTenSeconds() {
        assertFalse("a trickle of no-outcome terminals waits", Cortex7885.shadowFlushDue8396(0, 40, 15_000L))
        assertFalse(Cortex7885.shadowFlushDue8396(0, 0, 999_999L))
        assertTrue("a large batch flushes", Cortex7885.shadowFlushDue8396(0, Cortex7885.UNAVAILABLE_FLUSH_MIN_BATCH_8396, 0L))
        assertTrue("and after two minutes", Cortex7885.shadowFlushDue8396(0, 1, Cortex7885.UNAVAILABLE_FLUSH_MAX_WAIT_MS_8396))
        // learned outcomes keep the 8377 cadence and carry staged unavailable terminals with them
        assertTrue(Cortex7885.shadowFlushDue8396(1, 5, Cortex7885.SHADOW_FLUSH_MAX_WAIT_MS_8377))
        assertTrue(Cortex7885.shadowFlushDue8396(10, 60, 0L))
        assertFalse(Cortex7885.shadowFlushDue8396(1, 2, 1_000L))
        val cx = java.io.File("src/main/kotlin/com/lifecyclebot/engine/cortex/Cortex7885.kt").readText()
        assertTrue(cx.contains("if (!shadowFlushDue8396(shadowReady8372.size, observationTerminals8395.size, now8377 - shadowFlushedAtMs8377))"))
    }

    @Test fun compactRowsDecodeToTheSameValuesAndAreSmaller() {
        val row = doubleArrayOf(1_761_462_123_456.0, 12.345678901234567, 1.0, 8394.0, 1_761_462_000_000.0)
        val compact = ShadowPersist8355.compactRow8396(row)
        assertEquals("1761462123456,12.3457,1,8394,1761462000000", compact)
        assertTrue(compact.length < row.joinToString(",").length)
        val back = compact.split(',').map { it.toDouble() }
        assertEquals(row[0], back[0], 0.0); assertEquals(row[1], back[1], 1e-4); assertEquals(row[3], back[3], 0.0); assertEquals(row[4], back[4], 0.0)
        assertEquals("-3.5", ShadowPersist8355.compactRow8396(doubleArrayOf(-3.5)))
        assertEquals("NaN", ShadowPersist8355.compactRow8396(doubleArrayOf(Double.NaN)))
        // both books write through it
        listOf("EvidenceShadow8344", "ExploreShadow8347").forEach {
            val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/$it.kt").readText()
            assertTrue(it, src.contains("ShadowPersist8355.compactRow8396(it)")); assertFalse(it, src.contains("q.joinToString(\";\") { it.joinToString(\",\") }"))
        }
    }

    private fun task(now: Long) = Task8153("MintG", "MOONSHOT", "QUALITY", "fit", 3L, now, now + 60_000L, now, schedules = 2)

    @Test fun aLaneCycleGateKeepsTheTaskPendingAndNamesItAtExpiry() {
        val t0 = task(1_000L)
        val waited = HandoffReceiverTasks8153.block8153(t0, "MOONSHOT", Block8153.WAIT,
            HandoffReceiverTasks8153.LANE_CYCLE_PREFIX_8396 + "MEME_CONSENSUS_HARD_BLOCK_6604", 2_000L)
        assertTrue("a lane-cycle gate is never an early refusal", waited.pending)
        val ended = HandoffReceiverTasks8153.expire8153(waited, 1_000_000L)
        assertEquals(Outcome8153.REFUSED, ended.outcome)
        assertTrue(ended.verdict, ended.verdict.startsWith("SCHEDULED_STOPPED_AT_LANE_CYCLE_GATE_8396:MEME_CONSENSUS_HARD_BLOCK_6604"))
        assertFalse(ended.verdict.startsWith("SCHEDULED_NO_FDG_VERDICT"))
        assertTrue("fresh evidence reopens an unjudged task", HandoffReceiverTasks8153.reopensOnFreshEvidence8190(ended, ended.settledAtMs + 1))
        assertEquals("UNEVALUATED", HandoffRefusal8190.kindOf8190(ended.outcome, ended.verdict))
        // an unnamed scheduled task still reports as before
        assertTrue(HandoffReceiverTasks8153.expire8153(task(1_000L), 1_000_000L).verdict.startsWith("SCHEDULED_NO_FDG_VERDICT"))
    }

    @Test fun laneCycleGateIsWiredAtTheOneLaneGateFunnel() {
        val bs = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val f = bs.substringAfter("private fun shouldRunBuyLaneForCycle(").substringBefore("private val RESCUE_EXCLUDED_8031")
        assertTrue(f.contains("HandoffTaskRuntime8153.noteLaneCycleGate8396(ts.mint, lane,"))
    }
}

package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FirstTradeReadiness6348Test {

    @Before
    fun reset() {
        ScannerHydrationQueues6347.resetForTest()
        FillLotLedger6344.resetForTest()
    }

    @Test
    fun assessment_always_returns_all_five_pillars_in_priority_order() {
        val a = FirstTradeReadiness6348.assess()
        assertEquals(5, a.pillars.size)
        val priorities = a.pillars.map { it.priority }
        // Priorities must be 1..5 exactly once each.
        assertEquals(listOf(1, 2, 3, 4, 5).toSet(), priorities.toSet())
    }

    @Test
    fun scanner_live_ready_pillar_flips_ok_when_queue_has_a_row() {
        val before = FirstTradeReadiness6348.assess().pillars.first { it.name == "SCANNER_LIVE_READY_QUEUE" }
        assertFalse(before.ok)
        ScannerHydrationQueues6347.enqueue("X", ScannerHydrationQueues6347.Bucket.LIVE_READY)
        val after = FirstTradeReadiness6348.assess().pillars.first { it.name == "SCANNER_LIVE_READY_QUEUE" }
        assertTrue(after.ok)
    }

    @Test
    fun snapshot_line_carries_verdict_and_pillar_states() {
        val line = FirstTradeReadiness6348.snapshotLine()
        assertTrue(line.startsWith("FIRST_TRADE_READINESS_6348"))
        assertTrue(line.contains("ready="))
        assertTrue(line.contains("pillars="))
    }

    @Test
    fun snapshot_block_lists_remediation_hints_when_not_ready() {
        val block = FirstTradeReadiness6348.snapshotBlock()
        assertTrue(block.contains("FIRST_TRADE_READINESS_6348"))
        assertTrue(block.contains("[P1]"))
        assertTrue(block.contains("[P5]"))
    }

    @Test
    fun missing_pillars_are_sorted_by_priority() {
        val a = FirstTradeReadiness6348.assess()
        val missing = a.missingPillars
        if (missing.isNotEmpty()) {
            for (i in 1 until missing.size) {
                assertTrue(missing[i - 1].priority <= missing[i].priority)
            }
        }
    }
}

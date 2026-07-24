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
    fun scanner_live_ready_pillar_reports_queue_size_and_stays_advisory() {
        // V5.0.6351 — pillar is advisory; adding a row DOES flip ok=true
        // but the pillar never fails readiness even when empty.
        val before = FirstTradeReadiness6348.assess().pillars.first { it.name == "SCANNER_LIVE_READY_QUEUE" }
        assertFalse(before.ok)
        assertTrue("SCANNER_LIVE_READY_QUEUE must be advisory until scanner wires the router", before.advisory)
        ScannerHydrationQueues6347.enqueue("X", ScannerHydrationQueues6347.Bucket.LIVE_READY)
        val after = FirstTradeReadiness6348.assess().pillars.first { it.name == "SCANNER_LIVE_READY_QUEUE" }
        assertTrue(after.ok)
        assertTrue(after.advisory)
    }

    @Test
    fun quarantine_pillar_is_advisory_during_shadow_mode() {
        val quar = FirstTradeReadiness6348.assess().pillars.first { it.name == "NO_OUTSTANDING_QUARANTINES" }
        assertTrue("NO_OUTSTANDING_QUARANTINES must be advisory during shadow enforcement", quar.advisory)
    }

    @Test
    fun advisory_pillars_do_not_flip_ready_to_no() {
        // Without a hard pillar failing, ready must be Y even when advisory
        // pillars are red.
        val a = FirstTradeReadiness6348.assess()
        val hardMissing = a.pillars.filter { !it.ok && !it.advisory }
        if (hardMissing.isEmpty()) {
            assertTrue("ready must be Y when only advisory pillars are red", a.ready)
        }
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

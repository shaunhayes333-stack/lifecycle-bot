package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625
import com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7537 — later stages must never fabricate missing causal predecessors. */
class Aate6688CausalTicketWitnessTest {
    @After fun tearDown() {
        ExpressHandoffFunnel6625.resetForTest()
        SpecialistCausalFunnel6625.resetForTest()
    }

    @Test fun expressExecutionDoesNotInventMissingTicketTelemetry() {
        val attempt = "mint6688:42:EXPRESS"
        ExpressHandoffFunnel6625.onExecuted6625(attempt)
        val status = ExpressHandoffFunnel6625.statusLine()
        assertTrue(status.contains("ticketSealed=0"))
        assertTrue(status.contains("executed=1"))
    }

    @Test fun causalExecWithoutTicketRemainsRawOnly() {
        val key = SpecialistCausalFunnel6625.CausalKey("1","PAPER","mint6688","EXPRESS",6551L,"mint6688:42:EXPRESS")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.DISCOVER, "POOL")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.INTENT, "BUY_INTENT")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.FDG, "FDG_ALLOW")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.MARK, "MARK_READY")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.SIZE, "SIZED_EXECUTABLE")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.EXEC, "EXEC")
        val snap = SpecialistCausalFunnel6625.laneSnapshot6647("EXPRESS")
        assertNull(snap.counts[SpecialistCausalFunnel6625.Stage.TICKET])
        assertNull(snap.counts[SpecialistCausalFunnel6625.Stage.EXEC])
        assertEquals(1, snap.rawCounts7086[SpecialistCausalFunnel6625.Stage.EXEC])
        assertEquals(1, snap.outcomes["ORPHAN_EXEC_NO_TICKET_7537"])
    }

    @Test fun causalOpenWithoutExecAndTicketRemainsRawOnly() {
        val key = SpecialistCausalFunnel6625.CausalKey("1","PAPER","mint6688open","QUALITY",6551L,"mint6688open:43:QUALITY")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.DISCOVER, "POOL")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.INTENT, "BUY_INTENT")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.FDG, "FDG_ALLOW")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.MARK, "MARK_READY")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.SIZE, "SIZED_EXECUTABLE")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.OPEN, "POSITION_OPENED")
        val snap = SpecialistCausalFunnel6625.laneSnapshot6647("QUALITY")
        assertNull(snap.counts[SpecialistCausalFunnel6625.Stage.TICKET])
        assertNull(snap.counts[SpecialistCausalFunnel6625.Stage.EXEC])
        assertNull(snap.counts[SpecialistCausalFunnel6625.Stage.OPEN])
        assertEquals(1, snap.rawCounts7086[SpecialistCausalFunnel6625.Stage.OPEN])
        assertEquals(1, snap.outcomes["ORPHAN_OPEN_NO_EXEC_7537"])
    }
}

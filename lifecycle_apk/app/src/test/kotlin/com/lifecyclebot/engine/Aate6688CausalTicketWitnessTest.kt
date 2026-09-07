package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625
import com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the 5.0.6687 runtime contradiction ticket=0 while exec/open>0. */
class Aate6688CausalTicketWitnessTest {

    @After
    fun tearDown() {
        ExpressHandoffFunnel6625.resetForTest()
        SpecialistCausalFunnel6625.resetForTest()
    }

    @Test
    fun expressExecutionBackfillsMissingTicketTelemetryExactlyOnce() {
        val attempt = "mint6688:42:EXPRESS"
        ExpressHandoffFunnel6625.onExecuted6625(attempt)
        ExpressHandoffFunnel6625.onTicketSealed6625(attempt)
        val status = ExpressHandoffFunnel6625.statusLine()
        assertTrue(status.contains("ticketSealed=1"))
        assertTrue(status.contains("executed=1"))
    }

    @Test
    fun causalExecBackfillsTicketOnSameImmutableRecord() {
        val key = SpecialistCausalFunnel6625.CausalKey(
            runId = "1",
            mode = "PAPER",
            mint = "mint6688",
            lane = "EXPRESS",
            authorityVersion = 6551L,
            intentId = "mint6688:42:EXPRESS",
        )
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.DISCOVER, "POOL")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.INTENT, "BUY_INTENT")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.FDG, "FDG_ALLOW")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.MARK, "MARK_READY")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.SIZE, "SIZED_EXECUTABLE")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.EXEC, "EXEC")

        val snap = SpecialistCausalFunnel6625.laneSnapshot6647("EXPRESS")
        assertEquals(1, snap.counts[SpecialistCausalFunnel6625.Stage.TICKET])
        assertEquals(1, snap.counts[SpecialistCausalFunnel6625.Stage.EXEC])
    }

    @Test
    fun causalOpenBackfillsBothTicketAndExecButNoEconomicPredecessors() {
        val key = SpecialistCausalFunnel6625.CausalKey(
            runId = "1",
            mode = "PAPER",
            mint = "mint6688open",
            lane = "QUALITY",
            authorityVersion = 6551L,
            intentId = "mint6688open:43:QUALITY",
        )
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.DISCOVER, "POOL")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.INTENT, "BUY_INTENT")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.FDG, "FDG_ALLOW")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.MARK, "MARK_READY")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.SIZE, "SIZED_EXECUTABLE")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.OPEN, "POSITION_OPENED")

        val snap = SpecialistCausalFunnel6625.laneSnapshot6647("QUALITY")
        assertEquals(1, snap.counts[SpecialistCausalFunnel6625.Stage.TICKET])
        assertEquals(1, snap.counts[SpecialistCausalFunnel6625.Stage.EXEC])
        assertEquals(1, snap.counts[SpecialistCausalFunnel6625.Stage.OPEN])
        assertEquals(1, snap.counts[SpecialistCausalFunnel6625.Stage.INTENT])
        assertEquals(1, snap.counts[SpecialistCausalFunnel6625.Stage.FDG])
        assertEquals(1, snap.counts[SpecialistCausalFunnel6625.Stage.MARK])
        assertEquals(1, snap.counts[SpecialistCausalFunnel6625.Stage.SIZE])
    }
}

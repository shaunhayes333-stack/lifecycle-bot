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
        val funnel = SpecialistCausalFunnel6625
        val key = funnel.CausalKey(
            runId = "1",
            mode = "PAPER",
            mint = "mint6688",
            lane = "EXPRESS",
            authorityVersion = 6551L,
            intentId = "mint6688:42:EXPRESS",
        )
        funnel.stamp6625(key, funnel.Stage.DISCOVER, "POOL")
        funnel.stamp6625(key, funnel.Stage.INTENT, "BUY_INTENT")
        funnel.stamp6625(key, funnel.Stage.FDG, "FDG_ALLOW")
        funnel.stamp6625(key, funnel.Stage.MARK, "MARK_READY")
        funnel.stamp6625(key, funnel.Stage.SIZE, "SIZED_EXECUTABLE")
        funnel.stamp6625(key, funnel.Stage.EXEC, "EXEC")

        val snap = funnel.laneSnapshot6647("EXPRESS")
        assertEquals(1, snap.counts[funnel.Stage.TICKET])
        assertEquals(1, snap.counts[funnel.Stage.EXEC])
    }

    @Test
    fun causalOpenBackfillsBothTicketAndExecButNoEconomicPredecessors() {
        val funnel = SpecialistCausalFunnel6625
        val key = funnel.CausalKey(
            runId = "1",
            mode = "PAPER",
            mint = "mint6688open",
            lane = "QUALITY",
            authorityVersion = 6551L,
            intentId = "mint6688open:43:QUALITY",
        )
        funnel.stamp6625(key, funnel.Stage.DISCOVER, "POOL")
        funnel.stamp6625(key, funnel.Stage.INTENT, "BUY_INTENT")
        funnel.stamp6625(key, funnel.Stage.FDG, "FDG_ALLOW")
        funnel.stamp6625(key, funnel.Stage.MARK, "MARK_READY")
        funnel.stamp6625(key, funnel.Stage.SIZE, "SIZED_EXECUTABLE")
        funnel.stamp6625(key, funnel.Stage.OPEN, "POSITION_OPENED")

        val snap = funnel.laneSnapshot6647("QUALITY")
        assertEquals(1, snap.counts[funnel.Stage.TICKET])
        assertEquals(1, snap.counts[funnel.Stage.EXEC])
        assertEquals(1, snap.counts[funnel.Stage.OPEN])
        assertEquals(1, snap.counts[funnel.Stage.INTENT])
        assertEquals(1, snap.counts[funnel.Stage.FDG])
        assertEquals(1, snap.counts[funnel.Stage.MARK])
        assertEquals(1, snap.counts[funnel.Stage.SIZE])
    }
}

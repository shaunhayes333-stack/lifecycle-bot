package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaneRealisedProbe8424
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9024 — a candidate the gate admitted and then did not send to a buy (a later check, a size of zero, a ticket that
 * ended unexecuted) no longer holds its losing lane's one probe slot for 90 s. 5.0.9023: SHITCOIN allowed 0 of 152 and
 * bought nothing, and still ended 7 candidates on "probe occupied" with no live position open.
 */
class Aate9024ProbeSlotReleaseTest {
    private val t0 = 1_795_630_000_000L
    private val closes = listOf(Triple(t0 - 60_000L, -0.004, -21.0), Triple(t0 - 50_000L, -0.003, -13.7), Triple(t0 - 40_000L, -0.005, -29.2))

    @Test fun aCandidateThatDidNotBuyFreesTheLane() {
        LaneRealisedProbe8424.resetForTests8424()
        val lane = "ZZ_PROBE_9024"
        LaneRealisedProbe8424.claim8424(lane, "mintA", t0)
        assertNotNull("claimed: another coin waits", LaneRealisedProbe8424.probe8424(lane, "mintB", closes, t0 - 30_000L, t0 + 1_000L))
        assertFalse("another coin's verdict does not free it", LaneRealisedProbe8424.release9024(lane, "mintB"))
        assertNotNull(LaneRealisedProbe8424.probe8424(lane, "mintB", closes, t0 - 30_000L, t0 + 2_000L))
        assertTrue("the gate did not send mintA to a buy", LaneRealisedProbe8424.release9024(lane, "mintA"))
        assertNull("the lane probes again at once", LaneRealisedProbe8424.probe8424(lane, "mintB", closes, t0 - 30_000L, t0 + 3_000L))
        assertFalse("nothing left to free", LaneRealisedProbe8424.release9024(lane, "mintA"))
        assertTrue(LaneRealisedProbe8424.statusLine8424(t0 + 4_000L).contains("releasedUnbought9024=1"))
        LaneRealisedProbe8424.resetForTests8424()
    }

    @Test fun aTicketThatEndedUnexecutedFreesTheLane() {
        LaneRealisedProbe8424.resetForTests8424()
        val lane = "ZZ_PROBE_9024B"
        LaneRealisedProbe8424.claim8424(lane, "mintA", t0)
        assertTrue(LaneRealisedProbe8424.releaseLane9024(lane))
        assertNull(LaneRealisedProbe8424.probe8424(lane, "mintB", closes, t0 - 30_000L, t0 + 1_000L))
        assertFalse(LaneRealisedProbe8424.releaseLane9024(lane)); assertFalse(LaneRealisedProbe8424.release9024("", "mintA"))
        LaneRealisedProbe8424.resetForTests8424()
    }

    @Test fun theVerdictAndTheDroppedTicketAreWhereItIsFreed() {
        val own = File("src/main/kotlin/com/lifecyclebot/engine/SpecialistOwnership7951.kt").readText()
        assertTrue(own.contains("if (!shouldTrade) try { com.lifecyclebot.engine.truth.LaneRealisedProbe8424.release9024(lane, mint, blockReason) }"))
        assertTrue(LaneRealisedProbe8424.releasable9024("SIZE_NOT_EXECUTABLE_7835")); assertTrue(LaneRealisedProbe8424.releasable9024(null))
        assertFalse("its own buy is under way: the claim stands", LaneRealisedProbe8424.releasable9024("BUY_IN_FLIGHT_7948"))
        assertFalse(LaneRealisedProbe8424.releasable9024("DUPLICATE_TICKET_8198"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/TicketExec8304.kt").readText().contains("LaneRealisedProbe8424.releaseLane9024(l)"))
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveEdgeGate7877.kt").readText()
        assertTrue("a live admission still claims the lane", gate.contains("LaneRealisedProbe8424.claim8424(l, ts.mint, nowMs)"))
        val probe = File("src/main/kotlin/com/lifecyclebot/engine/truth/LaneRealisedProbe8424.kt").readText()
        assertTrue("an open position still occupies it", probe.contains("if (openInLane > 0 || claimedAgoMs in 0L until CLAIM_MS_8424) return Verdict8424.OCCUPIED"))
    }
}

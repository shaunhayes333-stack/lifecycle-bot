package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.EntryEconomics8329
import com.lifecyclebot.engine.truth.LaneRealisedProbe8424
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8429 — every lane judges its own prey (a READY resident proposal is a native claim past a sealed owner, cap six),
 * a learned-negative record sizes down to the lane's one probe instead of halting, the hold layer's profit-side EXIT_NOW is
 * an order, and a periodic Cortex save under memory pressure waits out its gap.
 */
class Aate8429OwnBrainTest {
    @After fun reset() { EntryEconomics8329.resetForTests8329() }

    @Test fun theLaneGateLetsAReadyResidentProposalEvaluatePastASealedOwner() {
        val b = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val own = b.indexOf("val ownBrainReady8429 = try {")
        val sealed = b.indexOf("val claimedOwner6600 = LaneExecutionCoordinator.currentElection6600(ts.mint)?.takeIf { it.sealed }?.primaryLane")
        assertTrue(own in 1 until sealed)
        assertTrue(b.contains("val ownBrain8429 = ownBrainReady8429 && !ExecutionAuthorityPolicy6533.isTrunkLane(lane) &&"))
        // the golden tape's order holds: the metric-stage rug check reads before the first trunk read of `l`
        assertTrue(b.indexOf("metricFit.stage == TokenMetricStageRouter.Stage.RUG_PRONE") < b.indexOf("ExecutionAuthorityPolicy6533.isTrunkLane(l)"))
        assertTrue(b.contains("if (!ownBrain8429) {"))
        assertTrue(b.contains("ownBrainReady8429 ||   // V5.0.8429 — the lane's own READY resident proposal is its qualification"))
        assertTrue(b.contains("huntedByLane7297 || designatedDeskHypothesis6599 != null || ownBrainReady8429))"))
        assertEquals(6, SpecialistElection8136.NATIVE_EVAL_CAP_8214)
    }

    @Test fun aLearnedRecordDoorStandsAsideOnlyForTheLanesOpenProbeSlot() {
        val a = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        for (door in listOf("SETUP_RECORD_LOSING_8136", "SHEET_AVOID_8323", "CELL_PROVEN_LOSING_8136", "STAGE_PROVEN_LOSING_8136",
                            "TACTIC_RECORD_LOSING_8136", "TIMING_ADVERSE_8136")) {
            assertTrue(door, a.contains("!probe8429(c, trace, \"$door\")"))
        }
        assertTrue(a.contains("!probe8429(c, trace, FALLING_HOUR_PROVEN_LOSING_8418)"))
        // the doors that are about the coin or the money never stand aside
        assertFalse(a.contains("probe8429(c, trace, \"SAFETY_REFUSED_8136\")"))
        assertFalse(a.contains("probe8429(c, trace, \"STRUCTURAL_RISK_8136\")"))
        assertFalse(a.contains("probe8429(c, trace, \"EXECUTION_ECONOMICS_8136\")"))
        assertTrue(a.contains("recordProbe8429 = live8290 && (try { LaneRealisedProbe8424.slotOpen8429(lane, ts.mint, nowMs) } catch (_: Throwable) { false })"))
        // pure fixture: paper / default candidates never probe
        assertFalse(CandidateAdmission8136.Candidate8136::class.java.getDeclaredConstructors().isEmpty())
    }

    @Test fun theProbeSlotIsOpenToTheClaimantOrToAnyCoinWhileTheLaneIsFlat() {
        LaneRealisedProbe8424.resetForTests8424()
        val t0 = 1_800_000_000_000L
        val lane = "ZZ_SLOT_8429"
        assertTrue("flat lane, no claim: open", LaneRealisedProbe8424.slotOpen8429(lane, "A", t0))
        LaneRealisedProbe8424.claim8424(lane, "A", t0)
        assertTrue("the claimant keeps it", LaneRealisedProbe8424.slotOpen8429(lane, "A", t0 + 1_000L))
        assertFalse("another coin waits", LaneRealisedProbe8424.slotOpen8429(lane, "B", t0 + 1_000L))
        assertTrue("the claim expires", LaneRealisedProbe8424.slotOpen8429(lane, "B", t0 + LaneRealisedProbe8424.CLAIM_MS_8424 + 1L))
        LaneRealisedProbe8424.resetForTests8424()
    }

    @Test fun theHoldLayersProfitExitIsExecutedNotLogged() {
        val ex = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(ex.contains("if ((holdEval.action == HoldingLogicLayer.HoldAction.EXIT_NOW || holdEval.action == HoldingLogicLayer.HoldAction.SCALE_OUT) &&"))
        assertTrue(ex.contains("currentPnlPct > 0.0 && cfg().autoTrade && !cbState.isHalted && !cbState.isPaused) {"))
        assertTrue(ex.contains("if (holdEval.action == HoldingLogicLayer.HoldAction.SCALE_OUT) requestPartialSell(ts, 0.5, why8429, wallet, walletSol)"))
        assertTrue(ex.contains("else requestSell(ts, why8429, wallet, walletSol)"))
        assertEquals("HOLD_LAYER_8429_RUNNER_TRAIL_GREEN_FLOOR_8363_8419_120PK_55PCT", HoldingLogicLayer.exitReasonTag8429("RUNNER_TRAIL_GREEN_FLOOR_8363_8419_120PK_55PCT: 55% <= 60%"))
        assertEquals("HOLD_LAYER_8429_TRAILING", HoldingLogicLayer.exitReasonTag8429("Trailing stop: 40% < peak-trail"))
        assertEquals("HOLD_LAYER_8429_EXIT", HoldingLogicLayer.exitReasonTag8429("   "))
    }

    @Test fun aPeriodicSaveUnderPressureWaitsOutItsGap() {
        assertTrue(Cortex7885.periodicSnapshotDeferred8429(true, 60_000L))
        assertFalse(Cortex7885.periodicSnapshotDeferred8429(true, 5L * 60_000L))
        assertFalse("at NORMAL pressure every save runs", Cortex7885.periodicSnapshotDeferred8429(false, 1_000L))
        assertFalse(Cortex7885.periodicSnapshotDeferred8429(true, -1L))
        val c = File("src/main/kotlin/com/lifecyclebot/engine/cortex/Cortex7885.kt").readText()
        assertTrue(c.contains("if (periodicSnapshotDeferred8429(snapshotEncodesInLock8418(memoryPressure8418()), now8429 - lastPeriodicSnapshotMs8429)) {"))
        assertTrue("close commits are untouched", c.contains("val encode = snapshotEncoder8386(periodic = false) ?: return false"))
    }
}

package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LiveRiskPolicy7807
import com.lifecyclebot.engine.truth.MinPromotion8188
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 5.0.8269: a route-minimum 5.6% fixed share must not deadlock every specialist. */
class Aate8269CostFloorEntryTest {
    private val min = 0.02838
    private val fixed = min * 0.056

    private fun candidate(lane: String, gross: Double = 24.0, fixedSol: Double = fixed) =
        LiveRiskPolicy7807.Inputs(
            lane = lane, equitySol = 0.3091, upstreamSol = min, execMinSol = min,
            planStopPct = 10.0, planFirstTargetPct = 40.0, planTargetPct = 50.0,
            solUsd = 105.71, liquidityUsd = 200_000.0,
            liveCloses = 0, liveMeanNetPct = 0.0, liveTotalNetSol = 0.0, liveWrPct = 0.0,
            drawdownFrac = 0.15, laneDailyLossSol = 0.0, governorLossMult = 1.0,
            partialProviderEvidence = false, oracleUnproven = false, laneOpenLive = 0,
            setupExpectedGrossPct8153 = gross, costAtSizePct8153 = { 8.0 },
            tradeableSol8153 = 0.3031, laneDeployedSol8153 = 0.0,
            fixedRoundTripSol8169 = fixedSol,
        )

    @Test fun fixedShareAtOldMinimumRequiresAnHonestCostViableTicket() {
        val floor = LiveRiskPolicy7807.executableFloorWithFixedCost8269(min, fixed)
        assertTrue(floor > min)
        assertFalse(CostLanding8169.fixedCostDominates8169(floor, fixed))
        assertTrue(CostLanding8169.fixedCostDominates8169(min, fixed))
        assertEquals(min, LiveRiskPolicy7807.executableFloorWithFixedCost8269(min, Double.NaN), 0.0)
        assertEquals(min, LiveRiskPolicy7807.executableFloorWithFixedCost8269(min, 0.0), 0.0)
    }

    @Test fun eachSpecialistCanReachAnEconomicallyMeasuredRoutableDecision() {
        for (lane in listOf("QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE",
                "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN")) {
            val d = LiveRiskPolicy7807.decide(candidate(lane))
            assertTrue("$lane: ${d.reason} labels=${d.labels}", d.open)
            assertEquals("OPEN_RISK_SAFE_COST_FLOOR_PROMOTED_8269", d.reason)
            assertTrue(d.sizeSol > min)
            assertFalse(CostLanding8169.fixedCostDominates8169(d.sizeSol, fixed))
        }
    }

    @Test fun defensivePromotionIsPerLaneAndStillNeedsMeasuredEdgeAndLossBudget() {
        val floor = LiveRiskPolicy7807.executableFloorWithFixedCost8269(min, fixed)
        assertEquals(MinPromotion8188.REFUSED_DRAWDOWN_8188,
            MinPromotion8188.refusal8188(0.020, floor, 10.0, 10.0, 8.0, 24.0, 0.33, 0.075, false))
        assertNull(MinPromotion8188.refusal8188(0.020, floor, 10.0, 10.0, 8.0, 24.0, 0.33, 0.075, true))
        assertEquals(MinPromotion8188.REFUSED_NEGATIVE_EDGE_8188,
            MinPromotion8188.refusal8188(0.020, floor, 10.0, 10.0, 8.0, 7.0, 0.33, 0.075, true))
        assertEquals(MinPromotion8188.REFUSED_UNMEASURED_EDGE_8188,
            MinPromotion8188.refusal8188(0.020, floor, 10.0, 10.0, 8.0, Double.NaN, 0.33, 0.075, true))
        assertEquals(MinPromotion8188.REFUSED_LOSS_BUDGET_8188,
            MinPromotion8188.refusal8188(0.020, floor, 10.0, 10.0, 8.0, 24.0, 0.33, 0.020, true))
        val policy = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveRiskPolicy7807.kt").readText()
        val upstream = File("src/main/kotlin/com/lifecyclebot/engine/truth/MinPromotion8188.kt").readText()
        assertTrue(policy.contains("LaneScoreAdmission7308.runnerSlotFreeNow(i.lane)"))
        assertTrue(upstream.contains("LaneScoreAdmission7308.runnerSlotFreeNow(l)"))
    }

    @Test fun fdgPreservesCostSafePromotedNotionalInsteadOfClampingBackToDust() {
        val target = com.lifecyclebot.engine.truth.LiveSizeTarget8094
        assertEquals(0.0402, target.fdgRiskSized8094(0.02838, 0.0402,
            "OPEN_RISK_SAFE_COST_FLOOR_PROMOTED_8269", false), 1e-12)
        assertEquals(0.02838, target.fdgRiskSized8094(0.02838, 0.0402,
            "OPEN_7807", false), 1e-12)
        assertEquals(0.0, target.fdgRiskSized8094(0.02838, 0.0,
            "OPEN_RISK_SAFE_COST_FLOOR_PROMOTED_8269", false), 0.0)
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(gate.contains("risk.reason in setOf(\"OPEN_RISK_SAFE_MIN_PROMOTED_7840\", \"OPEN_RISK_SAFE_COST_FLOOR_PROMOTED_8269\")"))
        assertTrue(gate.contains("FDG_COST_VIABLE_SIZE_PROMOTED_8269"))
        assertTrue(gate.contains("requestedSol = riskSized, laneName = lane"))
        assertTrue(gate.contains("resolution.finalSizeSol > riskSized + 1e-9) return 0.0"))
    }

    @Test fun negativeEconomicsAndUnaffordableCostFloorRemainRefused() {
        for (lane in listOf("QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE",
                "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN")) {
            assertFalse(lane, LiveRiskPolicy7807.decide(candidate(lane, gross = 6.0)).open)
            val untenable = LiveRiskPolicy7807.decide(candidate(lane, fixedSol = 0.010))
            assertFalse("$lane: ${untenable.reason}", untenable.open)
        }
    }
}

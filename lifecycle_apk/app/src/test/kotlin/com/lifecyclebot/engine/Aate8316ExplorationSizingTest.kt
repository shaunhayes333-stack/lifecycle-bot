package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaneScoreAdmission7308
import com.lifecyclebot.engine.truth.MinPromotion8188
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8316 P2 — specialists can size into a trade. 5.0.8314: QUALITY / BLUECHIP / CORE / CYCLIC / TREASURY qualified
 * hundreds and opened none: on a 0.29 SOL wallet every ticket needs a route-minimum promotion, which three gates refused
 * on a lane with no measured edge (REFUSED_UNMEASURED_EDGE 784, ORDER_SIZE_CONVICTION_REFUSED_MIN_PROMOTION 1,093,
 * SMART_SIZER_V3_SUB_ROUTABLE_REFUSED 733). An unmeasured edge now takes the lane's ONE exploration slot; the loss
 * budget, a measured negative edge and every later gate still bind; the funnel names the refusal at size.
 */
class Aate8316ExplorationSizingTest {
    private fun src(rel: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun anUnmeasuredEdgePromotesOnlyWhenTheLanesExplorationSlotIsFree() {
        // 0.0270 -> 0.02862 inside the loss budget (the 8188 owner example), expected gross unmeasured
        assertEquals(MinPromotion8188.REFUSED_UNMEASURED_EDGE_8188, MinPromotion8188.refusal8188(0.0270, 0.02862, 10.0, 3.67, 3.43, Double.NaN, 0.0))
        assertEquals(MinPromotion8188.REFUSED_UNMEASURED_EDGE_8188, MinPromotion8188.refusal8188(0.0270, 0.02862, 10.0, 3.67, 3.43, Double.NaN, 0.0, explorationSlotFree = false))
        assertNull(MinPromotion8188.refusal8188(0.0270, 0.02862, 10.0, 3.67, 3.43, Double.NaN, 0.0, explorationSlotFree = true))
        // an unmeasured COST is never promoted, slot or not
        assertEquals(MinPromotion8188.REFUSED_UNMEASURED_EDGE_8188, MinPromotion8188.refusal8188(0.0270, 0.02862, 10.0, 3.67, Double.NaN, Double.NaN, 0.0, explorationSlotFree = true))
        // a MEASURED negative edge still refuses on a free slot
        assertEquals(MinPromotion8188.REFUSED_NEGATIVE_EDGE_8188, MinPromotion8188.refusal8188(0.0270, 0.02862, 10.0, 3.67, 3.43, 3.40, 0.0, explorationSlotFree = true))
        // the loss budget is still checked first
        assertEquals(MinPromotion8188.REFUSED_LOSS_BUDGET_8188, MinPromotion8188.refusal8188(0.02424, 0.02862, 10.0, 3.69, 3.43, Double.NaN, 0.0, explorationSlotFree = true))
        // the defensive tier still needs the same slot
        assertEquals(MinPromotion8188.REFUSED_DRAWDOWN_8188, MinPromotion8188.refusal8188(0.0270, 0.02862, 10.0, 3.67, 3.43, Double.NaN, 0.608, explorationSlotFree = false))
        assertTrue(MinPromotion8188.UNMEASURED_EXPLORATION_SLOT_8316.startsWith("ROUTE_MIN_PROMOTION_"))
        val m = src("engine/truth/MinPromotion8188.kt")
        assertTrue(m.contains("if (!costPromotedPct.isFinite()) return REFUSED_UNMEASURED_EDGE_8188"))
        assertTrue(m.contains("if (!explorationSlotFree) return REFUSED_UNMEASURED_EDGE_8188"))
        assertTrue(m.indexOf("return REFUSED_LOSS_BUDGET_8188\n        if (!costPromotedPct.isFinite())") > 0)
    }

    @Test fun theSlotIsOneUnprovenPromotedOrderPerLaneAtATime() {
        val now = 1_000_000L
        assertTrue(LaneScoreAdmission7308.laneSlotFree7722("QUALITY", 0, 0L, now))
        assertFalse(LaneScoreAdmission7308.laneSlotFree7722("QUALITY", 1, 0L, now))             // one open: busy
        assertFalse(LaneScoreAdmission7308.laneSlotFree7722("QUALITY", 0, now - 60_000L, now))   // spaced 5 min
        assertTrue(LaneScoreAdmission7308.runnerSlotFree("MOONSHOT", 1, 0L, now))
        assertFalse(LaneScoreAdmission7308.runnerSlotFree("MOONSHOT", 2, 0L, now))
        assertFalse(LaneScoreAdmission7308.runnerSlotFree("SHITCOIN", 1, 0L, now))
        assertTrue(src("engine/truth/MinPromotion8188.kt").contains("LaneScoreAdmission7308.runnerSlotFreeNow(LiveRiskPolicy7807.canonicalLane(lane))"))
    }

    @Test fun theUpstreamSizersMakeTheSameExceptionAndTheFunnelNamesTheRefusalAtSize() {
        val osr = src("engine/truth/OrderSizeResolver6441.kt")
        assertTrue(osr.contains("!liveEdgeAllows7877(mint, laneName) && !MinPromotion8188.explorationSlotFree8316(laneName)"))
        assertTrue(osr.contains("promotionRefusal8188 != null -> promotionRefusal8188"))   // the 8188 re-proof still refuses
        val ssv3 = src("v3/sizing/SmartSizerV3.kt")
        // superseded by V5.0.8326 — the exploration slot is one of three promotion grounds (Entry8114Mode8326 admitted is the third)
        assertTrue(ssv3.contains("com.lifecyclebot.engine.truth.MinPromotion8188.explorationSlotFree8316(candidate.extraString(\"lane\")) ||"))
        assertTrue(ssv3.contains("(cappedSize >= liveFloorEff7697 || com.lifecyclebot.engine.truth.MinPromotion8188.runtimeRefusal8188("))
        for (r in listOf("ROUTE_MIN_PROMOTION_REFUSED_UNMEASURED_EDGE_8188", "ROUTE_MIN_PROMOTION_REFUSED_LOSS_BUDGET_8188",
            "ORDER_SIZE_CONVICTION_REFUSED_MIN_PROMOTION_6909", "SMART_SIZER_V3_SUB_ROUTABLE_REFUSED_7831", "CAPITAL_BELOW_MIN_EXECUTABLE_6490")) {
            assertTrue(r, SpecialistOwnership7951.isCapitalRefusal7951(r))
            assertTrue(r, r in SpecialistOwnership7951.capitalReasons8316())
            assertEquals(r, SpecialistOwnership7951.capitalOnlyReason7951("SIZE_NOT_EXECUTABLE_7835", r))
        }
        assertFalse(SpecialistOwnership7951.isCapitalRefusal7951("ROUTE_MIN_PROMOTION_REFUSED_NEGATIVE_EDGE_8188"))   // a measured verdict, not size
        assertFalse(SpecialistOwnership7951.isCapitalRefusal7951("RISK_SIZE_BELOW_EXECUTABLE_MINIMUM_7835"))
        assertTrue(src("engine/truth/MinPromotion8188.kt").contains("SpecialistOwnership7951.onCapitalRefusal7951(lane, mint, r, postIntent = false)"))
    }

    @Test fun moonshotOwnsACycleOnlyInsideItsOwnZone() {
        val b = src("engine/BotService.kt")
        assertTrue(b.contains("val readyAligned8081 = moonshotElectionZone8316(ts, classification, LaneExecutionCoordinator.readyOwnerForCycle8081("))
        val fn = b.substringAfter("private fun moonshotElectionZone8316(").substringBefore("private fun moonshotUnknownMcapLiquid7792(")
        assertTrue(fn.contains("if (forced != null || !elected.equals(\"MOONSHOT\", ignoreCase = true)) return elected"))
        assertTrue(fn.contains("if (moonshotMcapInZone7792(ts) || moonshotUnknownMcapLiquid7792(ts) || runnerShaped) return elected"))
        assertFalse(b.contains("zone=10k..100M"))
        assertTrue(b.contains("zone=fluidMin7719..5M_or_unknownMcapLiq>=2k_or_runnerShaped7266"))
    }
}

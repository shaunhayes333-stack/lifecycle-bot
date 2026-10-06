package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CurrentCandidateExpectancy7832
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7832LiveEntryQualityRepairTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun strong_current_candidate_can_be_positive_when_oracle_is_non_binding() {
        val e = CurrentCandidateExpectancy7832.estimate(
            score = 77, candidateConfidence = 0.77, quality = "B+",
            edgePhase = "BREAKOUT_EXPANSION", oraclePWin = 0.48, expectedSlipPct = 0.5,
        )
        assertTrue(e.positive)
        assertTrue(e.netExpectancyPct > 0.0)
    }

    @Test fun weak_or_wait_candidate_still_refuses() {
        assertFalse(CurrentCandidateExpectancy7832.estimate(
            score = 33, candidateConfidence = 0.25, quality = "C",
            edgePhase = "WAIT", oraclePWin = 0.50, expectedSlipPct = 0.0,
        ).positive)
    }

    @Test fun execution_cost_can_turn_apparent_edge_into_no_trade() {
        assertFalse(CurrentCandidateExpectancy7832.estimate(
            score = 60, candidateConfidence = 0.60, quality = "B",
            edgePhase = "MOMENTUM", oraclePWin = 0.52, expectedSlipPct = 3.0,
        ).positive)
    }

    @Test fun degenerate_paths_share_current_candidate_fallback() {
        val learned = src("engine/truth/LearnedAdmissionInputs6909.kt")
        val cross = src("engine/truth/CanonicalAssetEntryContract6551.kt")
        assertTrue(learned.contains("CurrentCandidateExpectancy7832.estimate("))
        assertTrue(learned.contains("ORACLE_DEGENERATE_CURRENT_CANDIDATE_FALLBACK_7832"))
        assertTrue(cross.contains("CurrentCandidateExpectancy7832.estimate("))
        assertTrue(cross.contains("CROSS_ASSET_CURRENT_CANDIDATE_EV_FALLBACK_7832"))
    }

    @Test fun wallet_reconciliation_is_not_counted_as_live_buy_ok() {
        val ph = src("engine/PipelineHealthCollector.kt")
        val block = ph.substringAfter("when (event) {").substringBefore("// V5.9.1046")
        val successArm = block.substringAfter("\"LIVE_BUY_LANDED\"").substringBefore("->")
        assertFalse(successArm.contains("LIVE_POSITION_CONFIRMED_FROM_WALLET"))
        assertTrue(block.contains("LIVE_WALLET_RECONCILED_NOT_EXEC_BUY_OK_7832"))
    }

    @Test fun live_sub_routable_promotions_remain_disabled() {
        val smart = src("v3/sizing/SmartSizerV3.kt")
        val resolver = src("engine/truth/OrderSizeResolver6441.kt")
        val guard = src("engine/truth/RoutableMinRiskGuard7236.kt")
        assertTrue(smart.contains("SMART_SIZER_V3_SUB_ROUTABLE_REFUSED_7831"))
        assertTrue(resolver.contains("LIVE_SUB_ROUTABLE_INTENT_REFUSED_7831"))
        assertTrue(guard.contains("ROUTABLE_MIN_LIFT_DISABLED_7831"))
        assertFalse(guard.contains("return Decision(Verdict.ALLOW_LIFT"))
    }
}

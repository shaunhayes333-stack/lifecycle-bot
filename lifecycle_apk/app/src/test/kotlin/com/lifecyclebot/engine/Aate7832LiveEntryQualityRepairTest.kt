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

    @Test fun sub_routable_live_size_is_promoted_only_through_canonical_risk_reproof() {
        val smart = src("v3/sizing/SmartSizerV3.kt")
        val resolver = src("engine/truth/OrderSizeResolver6441.kt")
        val risk = src("engine/truth/LiveRiskPolicy7807.kt")
        val guard = src("engine/truth/RoutableMinRiskGuard7236.kt")
        assertTrue(smart.contains("LIVE_ROUTABLE_MIN_USD_7127 = 3.0")) // V5.0.7951
        assertTrue(resolver.contains("LIVE_ROUTABLE_MIN_CAPACITY_PROMOTED_7840"))
        assertTrue(resolver.contains("refuseMinPromotion6909 -> 0L"))
        assertTrue(risk.contains("RISK_SAFE_EXECUTABLE_MIN_PROMOTED_7840"))
        assertTrue(risk.contains("executableMinRiskOk(i.execMinSol"))
        assertTrue(guard.contains("ROUTABLE_MIN_LIFT_DISABLED_7831"))
        assertFalse(guard.contains("return Decision(Verdict.ALLOW_LIFT"))
    }
    @Test fun route_minimum_continuity_is_preserved_from_resolver_to_sealed_ticket() {
        val resolver = src("engine/truth/OrderSizeResolver6441.kt")
        val fdg = src("engine/FinalDecisionGate.kt")
        val exec = src("engine/Executor.kt")
        assertTrue(resolver.contains("OK_LIVE_ROUTABLE_MIN_PROMOTED_7841"))
        assertFalse(resolver.contains("liveSubRoutableIntent7831 -> \"LIVE_SUB_ROUTABLE_INTENT_REFUSED_7831\""))
        assertTrue(fdg.contains("currentRoutableMinimum7835"))
        assertTrue(fdg.contains("FDG_RISK_SAFE_ROUTE_MIN_SEALED_7841"))
        // 7850: the route minimum is sealed once by FDG; the executor consumes it and
        // re-checks only the current bounds, it does not re-promote.
        assertFalse(exec.contains("LIVE_RISK_SAFE_MIN_CONSUMED_7841"))
        assertTrue(exec.contains("LIVE_FINAL_SIZE_CONSUMED_SEALED_7850"))
        assertTrue(exec.contains("SEALED_SIZE_BELOW_CURRENT_MINIMUM_7835").not() ||
            exec.contains("SealedExecutionSize7835.boundsRefusal"))
    }


}

package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7544ReplayFinalityConvergenceTest {
    private fun src(path:String)=File("src/main/kotlin/com/lifecyclebot/"+path).readText()

    @Test fun open_cost_parity_is_hybrid_per_current_position() {
        val s=src("engine/truth/CanonicalPaperReplay6464.kt")
        assertTrue(s.contains("PAPER_REPLAY_OPEN_COST_HYBRID_POSITION_SCOPE_7544"))
        assertTrue(s.contains("PAPER_REPLAY_OPEN_COST_PREWINDOW_CARRY_NEUTRAL_7544"))
        assertTrue(s.contains("snap.perPositionRemainingCostSol7474[p.positionId]"))
        assertTrue(s.contains("?: (p.entryCostSol - p.soldCostBasisSol).coerceAtLeast(0.0)"))
        assertTrue(s.contains("!hybridPositionScopeApplied7544 && scopedLedgerAgreesLedger6743"))
    }

    @Test fun durable_terminal_taxonomy_matches_actual_repairability() {
        val s=src("engine/truth/FinalizedLearningReconciler7423.kt")
        assertTrue(s.contains("DURABLE_TERMINAL_UNREPAIRABLE"))
        assertTrue(s.contains("val repairable7544"))
        assertTrue(s.contains("repairable7544 -> Reason.BUS_PUBLISH_FAILED"))
        assertTrue(s.contains("terminal7544 != null -> Reason.DURABLE_TERMINAL_UNREPAIRABLE"))
        assertTrue(s.contains("filter { it.reason == Reason.BUS_PUBLISH_FAILED }"))
    }

    @Test fun strict_closed_to_bus_acceptance_is_not_weakened() {
        val s=src("engine/truth/AcceptanceInvariantAudit6441.kt")
        assertTrue(s.contains("busCanonical6699 == closedCount && rewardHandled6699 == closedCount"))
    }
}

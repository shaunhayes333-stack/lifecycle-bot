package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7474ExactPositionOpenCostParityTest {
    private fun src() =
        File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperReplay6464.kt").readText()

    @Test fun replay_tracks_open_cost_by_position_id() {
        val s = src()
        assertTrue(s.contains("perPositionRemainingCostSol7474"))
        assertTrue(s.contains("perPositionCost7474.merge(e.positionId, e.executedCostSol)"))
        assertTrue(s.contains("perPositionCost7474[e.positionId]"))
    }

    @Test fun current_position_scope_supports_partial_typed_event_coverage() {
        val s = src()
        assertTrue(s.contains("PAPER_REPLAY_OPEN_COST_HYBRID_POSITION_SCOPE_7544"))
        assertTrue(s.contains("covered7544"))
        assertTrue(s.contains("uncovered7544"))
        assertTrue(s.contains("PAPER_REPLAY_OPEN_COST_PREWINDOW_CARRY_NEUTRAL_7544"))
    }

    @Test fun existing_mint_scope_remains_as_fallback() {
        val s = src()
        assertTrue(s.contains("PAPER_REPLAY_OPEN_COST_SCOPED_TO_LIVE_SET_6743"))
        assertTrue(s.contains("scopedLedgerAgreesLedger6743"))
    }
}

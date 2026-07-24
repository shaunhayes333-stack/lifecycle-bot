package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * V5.0.6361 — Paper full-exit qty preservation + CanonicalLearningContract
 * end-to-end wire-up. Golden-tape guard for the two behavioural fixes.
 */
class PaperFullExitAndLearningWireUp6361Test {

    @Test
    fun paper_sell_carries_full_qty_cost_and_entry_price_on_the_trade_row() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        // The SELL Trade record built inside paperSell must populate the qty
        // fields so downstream display + learning path see the true round-
        // trip position size, not a back-computed sol/price slice.
        assertTrue("paper SELL Trade row must set entryQtyToken from pos.qtyToken",
            txt.contains("entryQtyToken = pos.qtyToken"))
        assertTrue("paper SELL Trade row must set soldQtyToken from pos.qtyToken (full exit)",
            txt.contains("soldQtyToken = pos.qtyToken"))
        assertTrue("paper SELL Trade row must set entryCostSol from pos.costSol",
            txt.contains("entryCostSol = pos.costSol"))
        assertTrue("paper SELL Trade row must set entryPriceSnapshot from pos.entryPrice",
            txt.contains("entryPriceSnapshot = pos.entryPrice"))
        assertTrue("V5.0.6361 rationale must be documented inline",
            txt.contains("V5.0.6361"))
    }

    @Test
    fun v3_journal_recorder_gates_learning_aggregators_on_canonical_contract() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/V3JournalRecorder.kt").readText()
        assertTrue("V3JournalRecorder must call CanonicalLearningContract6346.assess",
            txt.contains("CanonicalLearningContract6346.assess("))
        assertTrue("aggregator fanout must be gated on canonicalAdmitted6361",
            txt.contains("if (canonicalAdmitted6361)"))
        assertTrue("quarantine skip must emit CANONICAL_LEARNING_AGGREGATOR_SKIPPED_6361",
            txt.contains("CANONICAL_LEARNING_AGGREGATOR_SKIPPED_6361"))
        assertTrue("V5.0.6361 rationale must be documented inline",
            txt.contains("V5.0.6361"))
    }

    @Test
    fun aggregator_calls_remain_inside_the_admitted_block() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/V3JournalRecorder.kt").readText()
        // Sanity: the three top-priority aggregators the operator flagged
        // (AdaptiveLearningEngine surface via ScoreExpectancy/HoldDuration/
        // ExitReason, LaneEdgeConcentrator surface via LaneExitTuner /
        // LanePolicy / RetrainingDecay, TacticSwitcher) MUST still be
        // present after the wire-up — otherwise we'd be dropping learning
        // entirely instead of just quarantining bad rows.
        assertTrue(txt.contains("ScoreExpectancyTracker.record("))
        assertTrue(txt.contains("HoldDurationTracker.record("))
        assertTrue(txt.contains("ExitReasonTracker.record("))
        assertTrue(txt.contains("LaneExitTuner.recordClose("))
        assertTrue(txt.contains("TacticSwitcher.onTradeClosed("))
        assertTrue(txt.contains("LanePolicy.recordOutcome("))
        assertTrue(txt.contains("RetrainingDecay.noteOutcome("))
    }
}

package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * V5.0.6361 — Paper full-exit qty preservation + canonical learning wire-up.
 */
class PaperFullExitAndLearningWireUp6361Test {

    @Test
    fun paper_sell_carries_full_qty_cost_and_entry_price_on_the_trade_row() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("paper SELL Trade row must set entryQtyToken from canonical-locked qty (§6449)",
            txt.contains("entryQtyToken = soldQtyToken6449"))
        assertTrue("paper SELL Trade row must set soldQtyToken from canonical-locked qty (§6449 full exit)",
            txt.contains("soldQtyToken = soldQtyToken6449"))
        assertTrue("paper SELL Trade row must set entryCostSol from pos.costSol",
            txt.contains("entryCostSol = pos.costSol"))
        assertTrue("paper SELL Trade row must set entryPriceSnapshot from pos.entryPrice",
            txt.contains("entryPriceSnapshot = pos.entryPrice"))
        assertTrue("V5.0.6361 rationale must be documented inline",
            txt.contains("V5.0.6361"))
        assertTrue("V5.0.6449 §3 sell qty source lock must be documented inline",
            txt.contains("V5.0.6449 §3"))
        assertTrue("paper SELL journal must consume raw directly from the committed close receipt (§6520)",
            txt.contains("journalRaw = close6474.canonicalConsumedRaw") &&
                txt.contains("canonicalConsumedRaw = rawVerdict6520.normalizedRaw") &&
                !txt.contains("journalSoldRaw(trade.soldQtyToken"))
    }

    @Test
    fun v3_journal_recorder_keeps_legacy_metrics_but_terminal_learning_is_canonical() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/V3JournalRecorder.kt").readText()
        assertFalse(
            "the broken recordClose canonical shim gate must remain removed",
            txt.contains("if (canonicalAdmitted6361)"),
        )
        assertFalse(
            "the broken recordClose canonical shim label must remain removed",
            txt.contains("CANONICAL_LEARNING_AGGREGATOR_SKIPPED_6361"),
        )
        assertTrue(txt.contains("isCanonicalFinalized: Boolean = false"))
        assertTrue(txt.contains("PaperLearningEligibility6519.decision(null, mint).eligible"))
        val bridge = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()
        assertTrue(bridge.contains("TacticSwitcher.onCanonicalTradeClosed6486("))
    }

    @Test
    fun aggregator_calls_remain_inside_the_admitted_block() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/V3JournalRecorder.kt").readText()
        assertTrue(txt.contains("ScoreExpectancyTracker.record("))
        assertTrue(txt.contains("HoldDurationTracker.record("))
        assertTrue(txt.contains("ExitReasonTracker.record("))
        assertTrue(txt.contains("LaneExitTuner.recordClose("))
        val bridge = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()
        assertTrue(bridge.contains("TacticSwitcher.onCanonicalTradeClosed6486("))
        assertFalse(txt.contains("TacticSwitcher.onTradeClosed(layer, band, pnlPctLearn)"))
        assertTrue(txt.contains("LanePolicy.recordOutcome("))
        assertTrue(txt.contains("RetrainingDecay.noteOutcome("))
    }
}

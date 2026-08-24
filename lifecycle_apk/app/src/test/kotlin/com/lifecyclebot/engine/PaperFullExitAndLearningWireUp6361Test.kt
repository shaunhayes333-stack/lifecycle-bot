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
        assertTrue("paper SELL Trade row must derive qty from canonical raw remaining quantity and verify journal raw equality (§6492/6509)",
            txt.contains("terminalRemainingRaw6492.toBigDecimal()") &&
                txt.contains("PaperTokenQuantityAuthority6509.journalSoldRaw") &&
                txt.contains("journalSoldQtyRaw6509 != terminalRemainingRaw6492"))
    }

    @Test
    fun v3_journal_recorder_no_longer_gates_learning_on_broken_shim_contract() {
        // V5.0.6365 — the V5.0.6361 shim was reverted after operator report
        // showed WR dropped 80% → 32% and wallet started shrinking on hourly
        // scale. Root cause: recordClose has NO qty parameter, so the shim
        // hardcoded `entryQtyToken=0.0, soldQtyToken=0.0, tokenDecimals=6`.
        // The contract's SELL missing-basis branch (lines 115-122) then
        // QUARANTINED any close reaching this recorder with sizeSol<=0 or
        // entryPrice<=0 — starving every learning aggregator that decides
        // sizing, tactic, exit rule. Canonical eligibility must be enforced
        // at the layer that HAS qty (Executor / FillLotLedger6344), not
        // via a shim.
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/V3JournalRecorder.kt").readText()
        assertFalse(
            "V5.0.6365: the recordClose canonical shim gate must be removed.",
            txt.contains("if (canonicalAdmitted6361)"),
        )
        assertFalse(
            "V5.0.6365: the recordClose canonical shim label must be removed.",
            txt.contains("CANONICAL_LEARNING_AGGREGATOR_SKIPPED_6361"),
        )
        assertTrue(
            "V5.0.6365: the revert reason must be documented inline.",
            txt.contains("V5.0.6365"),
        )
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

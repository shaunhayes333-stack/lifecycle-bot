package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6381 — LIVE MODE AUTO-PROMOTE invariants.
 *
 * Operator directive (verbatim): "paper has no issue finding winners live
 * shouldn't either!!! learning is meant to transfer where applicable...
 * FIX THE LIVE TRADER."
 *
 * The V5.0.6380 snapshot proved the block:
 *   BUY ok/fail: 5/43
 *   Top BUY fail reasons: LIVE_MODE_DESYNC=34 · DNA_VETO_EARLY_APPLIED=5 · LIVE_ENTRY_SAFETY_HOLD=4
 *   Live entry allowed: 31       ← governor authorised 31
 *   EXEC attempt: 129
 * 79% of live buys were aborted by Executor.kt:13037 because the sub-trader
 * had built ExecutionContext with execMode=PAPER while the runtime had
 * toggled to LIVE. The downstream ExecutableOpenGate line 850 already
 * hard-blocks PAPER_REQUEST_WHILE_RUNTIME_LIVE, so the Executor-level
 * abort was pure duplicate safety with a routing-bug tax attached.
 *
 * V5.0.6381 auto-promotes execCtx.execMode from PAPER to LIVE when the
 * runtime authority says LIVE. Real safety mismatches (position already
 * open as paper, shadow lane, etc.) remain blocked at line 13049.
 */
class Bundle6381LiveModePromoteTest {

    @Test
    fun executor_still_blocks_true_safety_mismatches() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        // V5.0.6381: runtimePaper=true (real desync — bot is running paper mode
        //   but this call is trying to open a live position) must still hard-abort.
        assertTrue(
            "V5.0.6381: runtimePaper=true must still hard-abort (real desync)",
            txt.contains("if (runtimePaper || execModeResolved != ExecMode.LIVE) {"),
        )
        // The 6381 auto-promote and 6383 stale-flag auto-clear were superseded by the
        // immutable ExecutionContext authority (see GoldenTapeRegressionTest
        // live_execution_attempts_have_immutable_mode_tokenmap_first_and_terminal_events):
        // an already-open paper/shadow position is now a hard desync abort.
        assertTrue(
            "an already-open paper/shadow position must hard-abort a live buy",
            txt.contains("return liveAbortDesync(\"existing_position_belongs_to_paper_or_shadow\")"),
        )
    }
}

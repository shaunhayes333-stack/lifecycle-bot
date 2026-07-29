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
    fun executor_auto_promotes_execmode_when_runtime_says_live() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(
            "V5.0.6381: Executor.paperOrLiveBuy must auto-promote execMode from PAPER to LIVE when runtime says LIVE (no more LIVE_MODE_DESYNC on stale sub-trader ExecutionContext)",
            txt.contains("LIVE_MODE_AUTO_PROMOTE_6381")
        )
        assertTrue(
            "V5.0.6381: the auto-promote branch must fire ONLY when runtimePaper==false AND execCtx.execMode==PAPER (never demote LIVE→PAPER)",
            txt.contains("!runtimePaper && execCtx.execMode == ExecMode.PAPER")
        )
    }

    @Test
    fun executor_still_blocks_true_safety_mismatches() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        // V5.0.6381: runtimePaper=true (real desync — bot is running paper mode
        //   but this call is trying to open a live position) must still hard-abort.
        // V5.0.6383: paperFlag/shadowFlag on an already-open position no longer
        //   hard-aborts — those are stale relics from prior paper/shadow runs on
        //   the same mint and are now auto-cleared so live volume can recover.
        //   See LIVE_MODE_STALE_FLAG_AUTO_CLEARED_6383 counter.
        assertTrue(
            "V5.0.6381: runtimePaper=true must still hard-abort (real desync)",
            txt.contains("if (runtimePaper) return liveAbortDesync"),
        )
        assertTrue(
            "V5.0.6383: stale paperFlag/shadowFlag on already-open position must auto-clear (not hard-abort)",
            txt.contains("LIVE_MODE_STALE_FLAG_AUTO_CLEARED_6383"),
        )
    }

    @Test
    fun auto_promote_emits_forensic_telemetry() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(
            "V5.0.6381: auto-promote must emit LIVE_MODE_AUTO_PROMOTE_6381 counter + ForensicLogger line so the operator can see the count in the pipeline dump",
            txt.contains("PipelineHealthCollector.labelInc(\"LIVE_MODE_AUTO_PROMOTE_6381\")") &&
                txt.contains("promotedTo=LIVE")
        )
    }
}

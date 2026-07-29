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
        // The V5.0.4017 block at line 13049 must remain intact — it guards
        // against a live buy against a mint that already has an OPEN paper
        // or shadow position (real safety concern, not routing).
        assertTrue(
            "V5.0.6381: alreadyOpenPosition + paperFlag/shadowFlag safety block must remain — that is a REAL mode mismatch, not a routing bug",
            txt.contains("if (runtimePaper || paperFlag || shadowFlag) return liveAbortDesync")
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

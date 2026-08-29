package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6582 §P0-7 — TAKE-PROFIT WIRED.
 *
 * Operator forensic (6580):
 *   ProtectiveExitScheduler eval=13381 SL=0 CATA=0 TP=0 TRAIL=0
 *   with +7.8 SOL unrealised across 61 open positions.
 *
 * Root cause: Executor.riskCheck called scheduler.evaluate with tpPx=0.0
 * hard-coded — the scheduler had no take-profit threshold to trip.
 *
 * Fix: derive tpPx from position (treasuryTakeProfit if set) or a 25%
 * meme-runner default. Now winners have a canonical latch point.
 */
class TakeProfitWired6582Test {

    private val execSrc = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()

    @Test
    fun tp_px_is_no_longer_hard_zero_at_the_scheduler_eval_call() {
        // The pre-6582 riskCheck contained a literal `tpPx = 0.0,` inside
        // the scheduler.evaluate() call. The 6582 fix computes it from
        // effTpPct * entryPrice.
        assertTrue(
            "Executor.riskCheck must compute tpPx from effTpPct (was hard-zero)",
            execSrc.contains("val tpPx = if (pos.entryPrice > 0.0 && effTpPct > 0.0) pos.entryPrice * (1.0 + effTpPct / 100.0) else 0.0")
        )
        assertTrue(
            "Executor.riskCheck must derive effTpPct from treasuryTakeProfit or a 25% default",
            execSrc.contains("pos.isTreasuryPosition && pos.treasuryTakeProfit > 0.0 -> pos.treasuryTakeProfit") &&
                execSrc.contains("else -> 25.0")
        )
    }
}

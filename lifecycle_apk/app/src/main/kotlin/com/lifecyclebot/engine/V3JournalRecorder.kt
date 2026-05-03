package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade

/**
 * V5.9.434 — Central journal recorder for the V3 meme sub-traders
 * (ShitCoinTraderAI, MoonshotTraderAI, QualityTraderAI, BlueChipTraderAI,
 * CashGenerationAI, ManipulatedTraderAI).
 *
 * V5.9.436 — Now ALSO the central outcome-attribution hub. Every V3
 * close is automatically fed into:
 *   - ScoreExpectancyTracker  (per-layer score-bucket P&L)
 *   - HoldDurationTracker     (per-layer hold-time-bucket P&L)
 *   - ExitReasonTracker       (per-layer exit-reason P&L)
 *
 * Sub-traders soft-reject incoming entries by querying these trackers,
 * closing the open feedback loop that left WR stuck at 30% over 5000
 * trades (no actual outcome attribution to entry score / hold time /
 * exit reason previously existed).
 *
 * Root cause this fixes: the V3 sub-traders each have their own
 * closePosition() that books into FluidLearning / SmartSizer /
 * RunTracker30D / dailyWins but was NEVER calling
 * TradeHistoryStore.recordTrade. Only Executor-routed lanes (stocks /
 * crypto alts / perps / metals / forex / commodities) showed up in the
 * Journal, so with 4791 bot trades the Journal had only ~300 rows.
 */
object V3JournalRecorder {

    fun recordClose(
        symbol: String,
        mint: String,
        entryPrice: Double,
        exitPrice: Double,
        sizeSol: Double,
        pnlPct: Double,
        pnlSol: Double,
        isPaper: Boolean,
        layer: String,         // "SHITCOIN" | "MOONSHOT" | "BLUECHIP" | "CASHGEN" | "MANIPULATED" | "QUALITY"
        exitReason: String,
        // V5.9.436 — outcome-attribution metadata. Defaults so older callers
        // still compile; pass real values to feed the learning trackers.
        entryScore: Int = 0,
        holdMinutes: Long = 0L,
    ) {
        // 1. Persist to the on-device SQLite Journal so the user UI sees it.
        try {
            val t = Trade(
                side       = "SELL",
                mode       = if (isPaper) "paper" else "live",
                sol        = sizeSol,
                price      = exitPrice,
                ts         = System.currentTimeMillis(),
                reason     = "${layer}_${exitReason}",
                pnlSol     = pnlSol,
                pnlPct     = pnlPct,
                netPnlSol  = pnlSol,
                tradingMode = layer,
                tradingModeEmoji = when (layer) {
                    "SHITCOIN"    -> "💩"
                    "MOONSHOT"    -> "🚀"
                    "BLUECHIP"    -> "💎"
                    "CASHGEN"     -> "💰"
                    "MANIPULATED" -> "🎭"
                    "QUALITY"     -> "⭐"
                    else          -> "📈"
                },
                mint       = mint,
            )
            TradeHistoryStore.recordTrade(t)
        } catch (e: Exception) {
            ErrorLogger.debug("V3JournalRecorder", "journal error ($symbol): ${e.message}")
        }

        // 2. V5.9.436 — feed all three outcome-attribution trackers.
        //    Each tracker is fail-open and thread-safe.
        try { ScoreExpectancyTracker.record(layer, entryScore, pnlPct) } catch (_: Exception) {}
        try { HoldDurationTracker.record(layer, holdMinutes, pnlPct) } catch (_: Exception) {}
        try { ExitReasonTracker.record(layer, exitReason, pnlPct) } catch (_: Exception) {}
    }
}

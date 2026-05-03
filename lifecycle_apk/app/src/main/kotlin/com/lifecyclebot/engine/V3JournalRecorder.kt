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
        var wrote = false
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
            wrote = true
            // V5.9.441 — always-on log so user can verify every V3 exit
            // lands in the Journal in real time.
            ErrorLogger.info("V3JournalRecorder",
                "📓 [$layer] $symbol ${exitReason} | " +
                "pnl=${"%+.2f".format(pnlPct)}% (${"%+.4f".format(pnlSol)} SOL) | " +
                "score=${entryScore} hold=${holdMinutes}m")
        } catch (e: Exception) {
            // V5.9.441 — promoted from debug→error. If journal writes are
            // failing we MUST see it in the log instead of silently losing
            // trades.
            ErrorLogger.error("V3JournalRecorder",
                "⚠️ JOURNAL WRITE FAILED for $symbol ($layer/${exitReason}): ${e.message}", e)
        }

        // 2. V5.9.436 — feed all three outcome-attribution trackers.
        //    Each tracker is fail-open and thread-safe. Only feed when the
        //    journal write actually landed so trackers don't diverge from
        //    the on-disk truth.
        if (wrote) {
            try { ScoreExpectancyTracker.record(layer, entryScore, pnlPct) } catch (_: Exception) {}
            try { HoldDurationTracker.record(layer, holdMinutes, pnlPct) } catch (_: Exception) {}
            try { ExitReasonTracker.record(layer, exitReason, pnlPct) } catch (_: Exception) {}
        }
    }
}

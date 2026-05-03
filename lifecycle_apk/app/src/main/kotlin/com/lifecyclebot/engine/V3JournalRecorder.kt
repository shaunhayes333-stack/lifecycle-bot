package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade

/**
 * V5.9.434 — Central journal recorder for the V3 meme sub-traders
 * (ShitCoinTraderAI, MoonshotTraderAI, QualityTraderAI, BlueChipTraderAI,
 * CashGenerationAI, ManipulatedTraderAI).
 *
 * Root cause this fixes: the V3 sub-traders each have their own
 * closePosition(mint, exitPrice, exitReason) that books into
 * FluidLearning / SmartSizer / RunTracker30D / dailyWins but was NEVER
 * calling TradeHistoryStore.recordTrade. Only Executor-routed lanes
 * (stocks / crypto alts / perps / metals / forex / commodities) showed
 * up in the Journal, so with 4791 bot trades the Journal had only ~300
 * rows. Fix: every V3 sub-trader's closePosition now calls
 * V3JournalRecorder.recordClose(...) which builds a sell-side Trade
 * and hands it to TradeHistoryStore.
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
    ) {
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
            ErrorLogger.debug("V3JournalRecorder", "record error ($symbol): ${e.message}")
        }
    }
}

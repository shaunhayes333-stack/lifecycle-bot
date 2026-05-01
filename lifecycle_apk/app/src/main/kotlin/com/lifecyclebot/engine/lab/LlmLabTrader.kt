package com.lifecyclebot.engine.lab

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.SentienceHooks

/**
 * V5.9.402 — LLM Lab paper trader.
 *
 * Owns the execution side of the lab sandbox:
 *   • openPaper: deduct from lab paper bankroll, register a LabPosition.
 *   • checkExit: evaluate a position against its strategy's TP/SL/timeout
 *     rules; on close, credit the bankroll, update strategy stats, and
 *     telegraph the outcome through SentienceHooks so the rest of the
 *     AATE universe sees Lab P&L too.
 *
 * Live (real-money) execution is OUT OF SCOPE here — that flows through
 * the main Executor, gated by user approval (LabPromotedFeed +
 * LlmLabEngine.requestSingleLiveTrade). The Lab's role is to invent and
 * prove; the real bot remains the single point of real-money execution.
 */
object LlmLabTrader {
    private const val TAG = "LlmLabTrader"

    fun openPaper(strategy: LabStrategy, tick: LlmLabEngine.LabUniverseTick, sizeSol: Double) {
        if (sizeSol <= 0.0) return
        if (LlmLabStore.openPosition(strategy.id, tick.mint) != null) return
        if (LlmLabStore.getPaperBalance() < sizeSol) return

        // Deduct from lab bankroll
        LlmLabStore.adjustPaperBalance(-sizeSol)

        val pos = LabPosition(
            id = LlmLabStore.newPositionId(),
            strategyId = strategy.id,
            symbol = tick.symbol,
            mint = tick.mint,
            asset = tick.asset,
            entryPrice = tick.price,
            sizeSol = sizeSol,
            entryTime = System.currentTimeMillis(),
            isLive = false,
            lastSeenPrice = tick.price,
        )
        LlmLabStore.addPosition(pos)

        // Mark strategy as last-evaluated
        LlmLabStore.updateStrategy(strategy.copy(lastEvaluatedAt = System.currentTimeMillis()))

        ErrorLogger.info(TAG, "🧪 OPEN ${strategy.name} → ${tick.symbol} ${"%.6f".format(tick.price)} size=${"%.4f".format(sizeSol)}◎ (asset=${tick.asset})")
    }

    fun checkExit(pos: LabPosition, currentPrice: Double) {
        if (currentPrice <= 0) return
        val strategy = LlmLabStore.getStrategy(pos.strategyId) ?: run {
            // Strategy was archived under our feet — close defensively.
            closePosition(pos, currentPrice, reason = "ORPHAN")
            return
        }

        val pnlPct = (currentPrice - pos.entryPrice) / pos.entryPrice * 100.0
        val holdMin = (System.currentTimeMillis() - pos.entryTime) / 60_000L

        // Track peak and update last-seen.
        val peak = if (pnlPct > pos.peakPnlPct) pnlPct else pos.peakPnlPct
        if (pnlPct > pos.peakPnlPct || currentPrice != pos.lastSeenPrice) {
            LlmLabStore.updatePosition(pos.copy(lastSeenPrice = currentPrice, peakPnlPct = peak))
        }

        // Exit rules (in priority order).
        when {
            pnlPct <= strategy.stopLossPct      -> closePosition(pos, currentPrice, "STOP_LOSS")
            pnlPct >= strategy.takeProfitPct    -> closePosition(pos, currentPrice, "TAKE_PROFIT")
            holdMin >= strategy.maxHoldMins     -> closePosition(pos, currentPrice, "TIMEOUT")
        }
    }

    fun closePosition(pos: LabPosition, exitPrice: Double, reason: String) {
        val strategy = LlmLabStore.getStrategy(pos.strategyId)
        val pnlPct = (exitPrice - pos.entryPrice) / pos.entryPrice * 100.0
        val pnlSol = pos.sizeSol * (pnlPct / 100.0)
        val isWin = pnlPct >= 1.0   // unified 1% threshold across AATE

        // Refund principal + pnl to lab bankroll.
        LlmLabStore.adjustPaperBalance(pos.sizeSol + pnlSol)
        LlmLabStore.removePosition(pos.id)

        // Update strategy aggregate stats.
        if (strategy != null) {
            val updated = strategy.copy(
                paperTrades = strategy.paperTrades + 1,
                paperWins   = strategy.paperWins + if (isWin) 1 else 0,
                paperPnlSol = strategy.paperPnlSol + pnlSol,
                lastTradeAt = System.currentTimeMillis(),
                lastEvaluatedAt = System.currentTimeMillis(),
            )
            LlmLabStore.updateStrategy(updated)
        }

        // Telegraph into the universe so Symbiosis/cross-engine biases see Lab too.
        try { SentienceHooks.recordEngineOutcome("LAB", pnlSol, isWin) } catch (_: Throwable) {}

        ErrorLogger.info(TAG, "🧪 CLOSE ${strategy?.name ?: pos.strategyId} → ${pos.symbol} ${reason} " +
            "pnl=${"%+.2f".format(pnlPct)}% (${"%+.4f".format(pnlSol)}◎) hold=${(System.currentTimeMillis() - pos.entryTime) / 60_000L}min " +
            "${if (isWin) "WIN" else "LOSS"}")
    }
}

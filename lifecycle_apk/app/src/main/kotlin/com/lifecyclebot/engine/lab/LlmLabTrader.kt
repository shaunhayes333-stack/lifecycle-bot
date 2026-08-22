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

    @Synchronized
    fun openPaper(strategy: LabStrategy, tick: LlmLabEngine.LabUniverseTick, sizeSol: Double) {
        if (sizeSol <= 0.0) return
        val existingMint6490 = LlmLabStore.allPositions().firstOrNull { it.mint == tick.mint }
        if (existingMint6490 != null) {
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LAB_SAME_MINT_HYPOTHESIS_COALESCED_6490")
                com.lifecyclebot.engine.ForensicLogger.lifecycle("LAB_SAME_MINT_HYPOTHESIS_COALESCED_6490", "mint=${tick.mint.take(10)} symbol=${tick.symbol} existingStrategy=${existingMint6490.strategyId} observingStrategy=${strategy.id} action=no_second_economic_position")
            } catch (_: Throwable) {}
            return
        }
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

        // V5.0.6490 — LAB is an isolated hypothesis sandbox. Its positions
        // remain in LlmLabStore and must never masquerade as canonical paper
        // inventory in TradeHistoryStore / sell pressure / wallet reports.
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LAB_SANDBOX_OPEN_ISOLATED_6490") } catch (_: Throwable) {}

        ErrorLogger.info(TAG, "🧪 OPEN ${strategy.name} → ${tick.symbol} ${"%.6f".format(tick.price)} size=${"%.4f".format(sizeSol)}◎ (asset=${tick.asset})")
    }

    fun checkExit(pos: LabPosition, currentPrice: Double) {
        if (currentPrice <= 0) return
        if (pos.entryPrice <= 0.0) {
            // V5.9.733 — entryPrice corruption guard. Cannot compute pnl
            // from a zero/negative entry. Close at entry with zero pnl so
            // the position doesn't sit forever, and log loudly so we can
            // trace where the bad entry came from.
            ErrorLogger.warn(TAG, "🧪 LAB_BAD_ENTRY: ${pos.symbol} entryPrice=${pos.entryPrice} — closing at entry with 0 pnl")
            closePosition(pos, pos.entryPrice.coerceAtLeast(1e-12), reason = "BAD_ENTRY_PRICE")
            return
        }
        val strategy = LlmLabStore.getStrategy(pos.strategyId) ?: run {
            // Strategy was archived under our feet — close defensively.
            closePosition(pos, currentPrice, reason = "ORPHAN")
            return
        }

        // V5.9.734 — REAL DATA ONLY: reject glitched ticks, never substitute.
        // Operator policy is no price simulation, no PnL clamps. If a tick
        // shows >100x movement from entry it is a feed glitch (Pump.fun
        // thin-pool quote artifact) — drop it entirely and wait for the
        // next clean quote. Position stays open at last real price. If
        // the move is genuine, the next clean tick confirms it.
        val priceRatio = currentPrice / pos.entryPrice
        if (priceRatio > 100.0 || priceRatio < 0.01) {
            ErrorLogger.warn(TAG,
                "🚫 LAB_TICK_REJECT: ${pos.symbol} entry=${"%.8f".format(pos.entryPrice)} " +
                "tick=${"%.8f".format(currentPrice)} ratio=${"%.1f".format(priceRatio)}x — feed glitch, skipping eval")
            return
        }

        val pnlPct = com.lifecyclebot.engine.OpenPnlSanity.inspect(pos.entryPrice, currentPrice, context = "LlmLabTrader_6038", emit = true).takeIf { it.ok }?.pnlPct ?: 0.0
        val holdMin = (System.currentTimeMillis() - pos.entryTime) / 60_000L

        // Track peak and update last-seen using the real (validated) tick.
        val peak = if (pnlPct > pos.peakPnlPct) pnlPct else pos.peakPnlPct
        if (pnlPct > pos.peakPnlPct || currentPrice != pos.lastSeenPrice) {
            LlmLabStore.updatePosition(pos.copy(lastSeenPrice = currentPrice, peakPnlPct = peak))
        }

        // Exit rules (in priority order) — exits fire on the real tick.
        // V5.9.1224: Lab was still static TP/SL. Route through the shared
        // FluidLearningAI dynamic stop/profit-floor so lab strategies learn
        // under the same recovery/profit-lock behavior as the main bot.
        val holdSeconds = (System.currentTimeMillis() - pos.entryTime) / 1000.0
        val fluidStop = try {
            com.lifecyclebot.v3.scoring.FluidLearningAI.getDynamicFluidStop(
                modeDefaultStop = kotlin.math.abs(strategy.stopLossPct).coerceAtLeast(1.0),
                currentPnlPct = pnlPct,
                peakPnlPct = peak,
                holdTimeSeconds = holdSeconds,
                volatility = 50.0,
            )
        } catch (_: Throwable) { strategy.stopLossPct }
        // V5.0.6299 — HARD LAB STOP CAP (regreen).
        // Paper report 01:20 showed SELL Fi6rZjXs lane=LAB pnl=-93.1%/-0.2792 SOL
        // via LAB_FLUID_STOP_LOSS. Even if FluidLearningAI drifts to -60%,
        // no single LAB experiment should crater more than -20%. Non-destructive
        // clamp — fluidStop is negative, so max() picks the tighter (less negative)
        // of the two, which forces earlier exit.
        val cappedFluidStop = kotlin.math.max(fluidStop, -20.0)
        when {
            pnlPct <= cappedFluidStop            -> closePosition(pos, currentPrice, if (peak > 3.0) "LAB_FLUID_PROFIT_FLOOR" else "LAB_FLUID_STOP_LOSS_6299_CAPPED")
            pnlPct >= strategy.takeProfitPct    -> closePosition(pos, currentPrice, "TAKE_PROFIT")
            holdMin >= strategy.maxHoldMins     -> closePosition(pos, currentPrice, "TIMEOUT")
        }
    }

    fun closePosition(pos: LabPosition, exitPrice: Double, reason: String) {
        val strategy = LlmLabStore.getStrategy(pos.strategyId)
        // V5.9.734 — REAL DATA ONLY. The PnL clamp from V5.9.733 is
        // removed per operator policy. checkExit's reject-tick filter
        // is the upstream truth gate; closePosition records exactly
        // what the validated price says. If a forced close (ORPHAN,
        // TIMEOUT) lands with a stale lastSeenPrice that's the real
        // last value we observed, not a synthesized one — record it
        // as-is so the operator sees the true outcome.
        val pnlPct = if (pos.entryPrice > 0.0) {
            (exitPrice - pos.entryPrice) / pos.entryPrice * 100.0
        } else 0.0
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

        // V5.0.6490 — close remains in the LAB store/outcome hooks only.
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LAB_SANDBOX_CLOSE_ISOLATED_6490") } catch (_: Throwable) {}

        ErrorLogger.info(TAG, "🧪 CLOSE ${strategy?.name ?: pos.strategyId} → ${pos.symbol} ${reason} " +
            "pnl=${"%+.2f".format(pnlPct)}% (${"%+.4f".format(pnlSol)}◎) hold=${(System.currentTimeMillis() - pos.entryTime) / 60_000L}min " +
            "${if (isWin) "WIN" else "LOSS"}")
    }
}

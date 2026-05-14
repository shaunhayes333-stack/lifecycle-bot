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

        // V5.9.447 — UNIVERSAL JOURNAL COVERAGE. Lab paper trades now show
        // up in the user's Journal alongside main-bot trades.
        try {
            com.lifecyclebot.engine.V3JournalRecorder.recordOpen(
                symbol = tick.symbol, mint = tick.mint,
                entryPrice = tick.price, sizeSol = sizeSol,
                isPaper = true, layer = "LAB",
                entryReason = strategy.name.take(24),
            )
        } catch (_: Throwable) {}

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

        // V5.9.733 — PRICE SANITY CLAMP. A single Pump.fun pool quote can
        // glitch 100x in one tick (thin liquidity + small order = huge
        // displayed price). Reject any tick that moves the position more
        // than 10x up or 20x down from entry — clamp to last-seen-valid
        // and DO NOT trigger TP on the glitch. Real 10x+ moves happen
        // gradually; if the gain is real, the next clean tick will
        // confirm it. This is the difference between +53,936% and a
        // believable +500%.
        val priceRatio = currentPrice / pos.entryPrice
        val sanePrice = if (priceRatio > 10.0 || priceRatio < 0.05) {
            ErrorLogger.warn(TAG,
                "🧪 LAB_PRICE_GLITCH: ${pos.symbol} entry=${"%.8f".format(pos.entryPrice)} " +
                "tick=${"%.8f".format(currentPrice)} ratio=${"%.1f".format(priceRatio)}x " +
                "— ignoring tick, holding last-seen=${"%.8f".format(pos.lastSeenPrice)}")
            pos.lastSeenPrice.takeIf { it > 0.0 } ?: pos.entryPrice
        } else {
            currentPrice
        }

        val pnlPct = (sanePrice - pos.entryPrice) / pos.entryPrice * 100.0
        val holdMin = (System.currentTimeMillis() - pos.entryTime) / 60_000L

        // Track peak and update last-seen (using sanitized price).
        val peak = if (pnlPct > pos.peakPnlPct) pnlPct else pos.peakPnlPct
        if (pnlPct > pos.peakPnlPct || sanePrice != pos.lastSeenPrice) {
            LlmLabStore.updatePosition(pos.copy(lastSeenPrice = sanePrice, peakPnlPct = peak))
        }

        // Exit rules (in priority order) — all exits use sanePrice so a
        // glitch tick can never trigger TP at a fake price.
        when {
            pnlPct <= strategy.stopLossPct      -> closePosition(pos, sanePrice, "STOP_LOSS")
            pnlPct >= strategy.takeProfitPct    -> closePosition(pos, sanePrice, "TAKE_PROFIT")
            holdMin >= strategy.maxHoldMins     -> closePosition(pos, sanePrice, "TIMEOUT")
        }
    }

    fun closePosition(pos: LabPosition, exitPrice: Double, reason: String) {
        val strategy = LlmLabStore.getStrategy(pos.strategyId)
        val rawPnlPct = if (pos.entryPrice > 0.0) {
            (exitPrice - pos.entryPrice) / pos.entryPrice * 100.0
        } else 0.0

        // V5.9.733 — HARD PnL CEILING. checkExit's sanity clamp should
        // prevent glitch ticks from reaching here, but a forced close
        // (ORPHAN, BAD_ENTRY_PRICE, external sweep) can still land with
        // a wild exitPrice. Cap realised pnl at ±2000% so a single bad
        // close can never inject >20x of fake money into the Lab
        // bankroll or — more importantly — into the global Journal's
        // TOTAL P&L (operator showed $1.33B total polluted by exactly
        // this path: COPIUM +53,936%, PBTC +13,626%). Real 20x runs
        // exist but they accumulate over minutes; a single TP firing at
        // 500x is always a feed glitch.
        val PNL_CEILING_PCT = 2000.0
        val PNL_FLOOR_PCT = -95.0
        val pnlPct = when {
            rawPnlPct > PNL_CEILING_PCT -> {
                ErrorLogger.warn(TAG,
                    "🧪 LAB_PNL_CEILING: ${pos.symbol} raw=${"%.1f".format(rawPnlPct)}% " +
                    "→ capped to ${PNL_CEILING_PCT}% (entry=${"%.8f".format(pos.entryPrice)} " +
                    "exit=${"%.8f".format(exitPrice)} reason=$reason) — feed glitch suspected")
                PNL_CEILING_PCT
            }
            rawPnlPct < PNL_FLOOR_PCT -> {
                ErrorLogger.warn(TAG,
                    "🧪 LAB_PNL_FLOOR: ${pos.symbol} raw=${"%.1f".format(rawPnlPct)}% " +
                    "→ capped to ${PNL_FLOOR_PCT}%")
                PNL_FLOOR_PCT
            }
            else -> rawPnlPct
        }
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

        // V5.9.447 — UNIVERSAL JOURNAL COVERAGE. Every Lab close now writes
        // a SELL row to TradeHistoryStore so the user's Journal reflects
        // Lab P&L too. (Lab live trades flow through the main Executor and
        // are journaled there.)
        try {
            val holdMins = (System.currentTimeMillis() - pos.entryTime) / 60_000L
            com.lifecyclebot.engine.V3JournalRecorder.recordClose(
                symbol = pos.symbol, mint = pos.mint,
                entryPrice = pos.entryPrice, exitPrice = exitPrice,
                sizeSol = pos.sizeSol, pnlPct = pnlPct, pnlSol = pnlSol,
                isPaper = !pos.isLive, layer = "LAB",
                exitReason = reason,
                holdMinutes = holdMins,
            )
        } catch (_: Throwable) {}

        ErrorLogger.info(TAG, "🧪 CLOSE ${strategy?.name ?: pos.strategyId} → ${pos.symbol} ${reason} " +
            "pnl=${"%+.2f".format(pnlPct)}% (${"%+.4f".format(pnlSol)}◎) hold=${(System.currentTimeMillis() - pos.entryTime) / 60_000L}min " +
            "${if (isWin) "WIN" else "LOSS"}")
    }
}

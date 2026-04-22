package com.lifecyclebot.v3.scoring

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ErrorLogger

/**
 * V5.9.123 — ReflexAI
 *
 * Sub-second reaction layer. Most of the bot's exit logic runs on
 * multi-second scan ticks. Meme snipers die in the gap. This module
 * offers three stateless "reflex checks" that can be called from any
 * tight loop (price tick, volume tick, liquidity tick) to force
 * immediate action when a catastrophic event is in progress.
 *
 * Returns one of:
 *   • null                — no reflex
 *   • REFLEX_ABORT        — catastrophic red candle in first 30s
 *   • REFLEX_PARTIAL_LOCK — 2x+ pump within 2 min; lock 50%
 *   • REFLEX_LIQ_DRAIN    — liquidity just dropped >25% in 30s
 *
 * Caller routes these to Executor sell/partial-sell paths. The actual
 * fast-loop caller is added to BotService (price tick handler) in a
 * separate commit; this file defines the decision logic only so it can
 * be unit-tested without touching any live pipeline.
 */
object ReflexAI {

    private const val TAG = "Reflex"

    enum class Reflex { ABORT, PARTIAL_LOCK, LIQ_DRAIN }

    /**
     * Evaluate the 3 reflexes for an open position. All inputs are
     * already tracked in TokenState; caller passes the current tick.
     */
    fun evaluate(
        ts: TokenState,
        currentPriceUsd: Double,
        currentLiqUsd: Double,
    ): Reflex? {
        val pos = ts.position
        if (!pos.isOpen) return null
        if (pos.entryTime <= 0 || pos.entryPrice <= 0) return null

        val heldSec = (System.currentTimeMillis() - pos.entryTime) / 1000L
        val gainPct = if (pos.entryPrice > 0) {
            (currentPriceUsd - pos.entryPrice) / pos.entryPrice * 100.0
        } else 0.0

        // Reflex 1 — abort-fast on catastrophic red candle in first 30s
        if (heldSec in 0..30 && gainPct <= -15.0) {
            ErrorLogger.warn(TAG, "⚡ REFLEX ABORT: ${ts.symbol} gain=${gainPct.toInt()}% at ${heldSec}s")
            return Reflex.ABORT
        }

        // Reflex 2 — immediate 2x pump → partial lock 50%
        if (heldSec in 0..120 && gainPct >= 100.0 && pos.partialSoldPct < 0.25) {
            ErrorLogger.info(TAG, "⚡ REFLEX PARTIAL: ${ts.symbol} +${gainPct.toInt()}% at ${heldSec}s → lock 50%")
            return Reflex.PARTIAL_LOCK
        }

        // Reflex 3 — liquidity drain >25% vs entry
        val entryLiq = pos.entryLiquidityUsd
        if (entryLiq > 0 && currentLiqUsd > 0) {
            val liqDropPct = (entryLiq - currentLiqUsd) / entryLiq * 100.0
            if (liqDropPct >= 25.0) {
                ErrorLogger.warn(TAG, "⚡ REFLEX LIQ_DRAIN: ${ts.symbol} liq −${liqDropPct.toInt()}% ($${entryLiq.toInt()}→$${currentLiqUsd.toInt()})")
                return Reflex.LIQ_DRAIN
            }
        }

        return null
    }
}

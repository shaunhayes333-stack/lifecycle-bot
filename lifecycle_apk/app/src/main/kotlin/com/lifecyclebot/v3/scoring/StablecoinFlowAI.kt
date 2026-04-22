package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.9.123 — StablecoinFlowAI
 *
 * Macro regime signal: net change in USDC + USDT circulating supply over
 * 24h is a reliable proxy for liquidity flowing INTO or OUT OF crypto as
 * a whole. Net inflow → new money arriving → lean into risk. Net outflow
 * → capital leaving → flatten exposure, tighten locks.
 *
 * Updated by a background poller (see updateFromPoll); consumed by
 * UnifiedScorer as a small regime bias and by FluidLearningAI score
 * floor as a macro multiplier.
 *
 * Default bias = 0.0 (neutral) until the first poll lands.
 */
object StablecoinFlowAI {

    private const val TAG = "StableFlow"

    data class FlowSnapshot(
        val usdcDeltaPct24h: Double,
        val usdtDeltaPct24h: Double,
        val capturedAtMs: Long,
    ) {
        val combinedDeltaPct: Double get() = (usdcDeltaPct24h + usdtDeltaPct24h) / 2.0
    }

    private val snapshot = AtomicReference<FlowSnapshot?>(null)

    /**
     * Called by a background poller once per hour with the latest CoinGecko
     * values. Poller lives in NetworkSignalAutoBuyer's existing macro loop
     * to avoid a new thread for a slow update.
     */
    fun updateFromPoll(usdcDeltaPct24h: Double, usdtDeltaPct24h: Double) {
        snapshot.set(FlowSnapshot(usdcDeltaPct24h, usdtDeltaPct24h, System.currentTimeMillis()))
        ErrorLogger.info(TAG, "🌊 USDC=${"%.2f".format(usdcDeltaPct24h)}%% USDT=${"%.2f".format(usdtDeltaPct24h)}%% combined=${"%.2f".format((usdcDeltaPct24h + usdtDeltaPct24h) / 2.0)}%%")
    }

    /** Returns bias in [-1, +1]. +1 = maximal risk-on, -1 = maximal risk-off. */
    fun getRegimeBias(): Double {
        val snap = snapshot.get() ?: return 0.0
        val combined = snap.combinedDeltaPct
        // Historically 1% 24h stable delta is a meaningful move. Saturate at 3%.
        return (combined / 3.0).coerceIn(-1.0, 1.0)
    }

    fun score(@Suppress("UNUSED_PARAMETER") candidate: CandidateSnapshot, @Suppress("UNUSED_PARAMETER") ctx: TradingContext): ScoreComponent {
        val bias = getRegimeBias()
        // Bias ∈ [-1, +1] → score ∈ [-5, +5]
        val value = (bias * 5.0).toInt()
        val reason = if (snapshot.get() == null) "🌊 NO_MACRO_DATA"
        else "🌊 MACRO ${if (bias >= 0) "RISK_ON" else "RISK_OFF"} bias=${"%.2f".format(bias)}"
        return ScoreComponent("StablecoinFlowAI", value, reason)
    }
}

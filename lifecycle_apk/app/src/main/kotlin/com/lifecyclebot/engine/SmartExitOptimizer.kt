package com.lifecyclebot.engine

import com.lifecyclebot.v4.meta.StrategyTrustAI

/**
 * SmartExitOptimizer — ADVISORY exit intelligence utility
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Provides advisory signals to SymbolicExitReasoner and other AI layers.
 * NOT a decision maker — produces suggestions that the symbolic reasoner
 * and per-layer AIs weigh alongside their own signals.
 *
 * Used by: TokenizedStockTrader, CommoditiesTrader, MetalsTrader, ForexTrader
 * (CryptoAltTrader uses SymbolicExitReasoner directly)
 */
object SmartExitOptimizer {

    /**
     * Get exit pressure (0.0-1.0) for sub-traders that don't have
     * their own full symbolic reasoning layer yet.
     * Used by Commodities/Metals/Forex as an upgrade from fixed TP/SL.
     */
    fun getExitPressure(
        currentPnlPct: Double,
        peakPnlPct: Double,
        tradingMode: String,
        holdTimeSec: Long
    ): Double {
        return SymbolicExitReasoner.assess(
            currentPnlPct   = currentPnlPct,
            peakPnlPct      = peakPnlPct,
            entryConfidence = 50.0,
            tradingMode     = tradingMode,
            holdTimeSec     = holdTimeSec,
            priceVelocity   = 0.0,
            volumeRatio     = 1.0
        ).conviction
    }

    /**
     * Dynamic SL suggestion based on trust.
     */
    fun getSuggestedSlPct(tradingMode: String): Double {
        val trust = try {
            StrategyTrustAI.getAllTrustScores()[tradingMode]?.trustScore ?: 0.5
        } catch (_: Exception) { 0.5 }

        return when {
            trust >= 0.7 -> 6.0
            trust >= 0.4 -> 4.0
            else         -> 2.5
        }
    }

    /**
     * Dynamic TP suggestion based on trust + momentum.
     */
    fun getSuggestedTpPct(tradingMode: String, currentPnlPct: Double, peakPnlPct: Double): Double {
        val trust = try {
            StrategyTrustAI.getAllTrustScores()[tradingMode]?.trustScore ?: 0.5
        } catch (_: Exception) { 0.5 }

        val baseTp = when {
            trust >= 0.7 -> 10.0
            trust >= 0.4 -> 6.0
            else         -> 4.0
        }

        return if (currentPnlPct > baseTp * 0.7 && currentPnlPct >= peakPnlPct * 0.95) {
            baseTp * 1.5
        } else {
            baseTp
        }
    }

    /**
     * Confidence advisory based on accuracy.
     */
    fun getMinConfidenceAdvisory(currentAccuracy: Double): Double {
        return when {
            currentAccuracy >= 55.0 -> 35.0
            currentAccuracy >= 45.0 -> 45.0
            currentAccuracy >= 35.0 -> 55.0
            else                    -> 65.0
        }
    }
}

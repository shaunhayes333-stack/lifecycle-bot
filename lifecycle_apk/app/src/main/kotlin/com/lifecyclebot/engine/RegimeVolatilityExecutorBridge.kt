package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.v3.scoring.RegimeTransitionAI
import com.lifecyclebot.v3.scoring.VolatilityRegimeAI

/** V5.0.4268 — executor-side consumer for persisted regime/volatility intelligence. */
object RegimeVolatilityExecutorBridge {
    data class Shape(val multiplier: Double, val reason: String)

    fun sizeShape(ts: TokenState): Shape {
        return try {
            val candles = ts.history.toList().takeLast(30)
            val volSignal = if (candles.size >= 5) {
                VolatilityRegimeAI.analyze(
                    mint = ts.mint,
                    symbol = ts.symbol,
                    recentHighs = candles.map { if (it.highUsd > 0.0) it.highUsd else it.priceUsd },
                    recentLows = candles.map { if (it.lowUsd > 0.0) it.lowUsd else it.priceUsd },
                    recentCloses = candles.map { it.priceUsd },
                )
            } else null
            val transition = RegimeTransitionAI.analyzeTransition(
                mint = ts.mint,
                symbol = ts.symbol,
                currentRegime = ts.phase.ifBlank { "MEME" },
                liquidity = ts.lastLiquidityUsd,
                volatility = ts.volatility ?: ts.meta.volScore,
                momentum = ts.momentum ?: ts.meta.momScore,
                holderCount = ts.peakHolderCount,
                volume24h = ts.tokenMap.volume24hUsd ?: 0.0,
            )
            val volMult = (volSignal?.recommendedSizeMult ?: 1.0).coerceIn(0.85, 1.12)
            val transitionMult = when (transition.type.name) {
                "GRADUATION_FORMING", "BREAKOUT_TO_MAJOR", "RANGE_BREAKOUT", "SQUEEZE_BUILDING", "ACCUMULATION_PHASE" -> if (transition.confidence >= 55.0) 1.06 else 1.02
                "RUG_FORMING", "TREND_EXHAUSTION", "DISTRIBUTION_PHASE", "LIQUIDITY_DRAIN" -> if (transition.confidence >= 55.0) 0.92 else 0.97
                else -> 1.0
            }
            Shape((volMult * transitionMult).coerceIn(0.90, 1.12), "vol=${volSignal?.regime ?: "NO_CANDLES"}/${volSignal?.pattern ?: "NONE"} transition=${transition.type} conf=${transition.confidence.toInt()}")
        } catch (_: Throwable) { Shape(1.0, "unavailable") }
    }
}

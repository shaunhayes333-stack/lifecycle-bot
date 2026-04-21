package com.lifecyclebot.perps

/**
 * V5.9.93 — shared fluid sizing / TP-SL utility for Perps-family traders.
 *
 * CryptoAltTrader has had fluid sizing (0.45x..2.00x) and fluid TP/SL
 * since V5.9.88. Forex / Metals / Commodities were still flat 5% every
 * trade with fixed TP/SL. This object centralises the exact same curves
 * so all four traders scale position size and exit ladder by conviction.
 *
 * Curves intentionally match CryptoAltTrader.fluidSizeMultiplier /
 * fluidTpSlMultiplier so cross-trader learning is apples-to-apples.
 */
object PerpsFluidSizing {

    /**
     * Returns a size multiplier in [0.45, 2.00] based on combined
     * score (60%) + confidence (40%). Low-conviction signals ride
     * small probes; high-conviction go big.
     */
    fun sizeMultiplier(score: Int, confidence: Int): Double {
        val s = score.coerceIn(0, 100)
        val c = confidence.coerceIn(0, 100)
        val blended = (s * 0.6 + c * 0.4)
        return when {
            blended >= 88 -> 2.00
            blended >= 80 -> 1.65
            blended >= 72 -> 1.35
            blended >= 64 -> 1.10
            blended >= 56 -> 0.90
            blended >= 48 -> 0.70
            else          -> 0.45
        }
    }

    /**
     * Returns (tpMult, slMult) where 1.0 == base pct. High-conviction
     * trades get a wider TP and tighter SL; low-conviction trades get
     * a tighter TP and wider SL to absorb noise.
     */
    fun tpSlMultiplier(score: Int, confidence: Int): Pair<Double, Double> {
        val s = score.coerceIn(0, 100)
        val c = confidence.coerceIn(0, 100)
        val blended = (s * 0.6 + c * 0.4)
        return when {
            blended >= 85 -> 1.75 to 0.75
            blended >= 75 -> 1.40 to 0.85
            blended >= 65 -> 1.15 to 0.95
            blended >= 55 -> 1.00 to 1.00
            blended >= 45 -> 0.85 to 1.15
            else          -> 0.70 to 1.30
        }
    }
}

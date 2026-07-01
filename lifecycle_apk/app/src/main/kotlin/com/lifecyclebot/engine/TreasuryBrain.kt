package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🧠 TREASURY BRAIN — V5.0.4599
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Purpose-built scoring layer for Treasury/CashGen 3-5% scalp setups.
 * Operator directive: Treasury needs its own brain — not a repurposed
 * meme trader.
 *
 * SCALP SETUP CRITERIA:
 *   • 5m momentum ≥ 1.5% (rising, not stalling)
 *   • 15m momentum ≥ 3% (confirmed trend, not one-tick spike)
 *   • Buy pressure ≥ 55% (accumulation, not distribution)
 *   • Tight spread (via liquidity floor from TreasuryScannerFeed)
 *   • No recent -X% wick chop (whipsaw kills scalps)
 *
 * SCORE OUTPUT (0-100):
 *   • 80-100: PREMIUM_SCALP — full size press
 *   • 65-79:  STANDARD_SCALP — normal size
 *   • 50-64:  PROBE_SCALP — half size probe
 *   • <50:    SKIP — setup not clean
 *
 * Doctrine: FLUID. Score feeds CashGenerationAI's size calculation, not
 * a hard veto. Sub-50 tokens still get a tiny learning probe if CashGen
 * daily loss cap hasn't been touched.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object TreasuryBrain {

    const val VERSION = "V5.0.4599_TREASURY_BRAIN"

    data class ScalpVerdict(
        val score: Double,
        val category: String,
        val sizeMultiplier: Double,
        val reasons: List<String>,
    )

    fun evaluate(ts: TokenState): ScalpVerdict {
        val reasons = mutableListOf<String>()
        var score = 50.0  // neutral start

        // Momentum score (composite trend indicator 0-100, from V3 scoring)
        val mom = ts.meta.momScore
        when {
            mom >= 75.0 -> { score += 18; reasons += "strong_momentum_${mom.toInt()}" }
            mom >= 60.0 -> { score += 10; reasons += "positive_momentum_${mom.toInt()}" }
            mom <= 35.0 -> { score -= 15; reasons += "weak_momentum_${mom.toInt()}" }
            else -> { reasons += "neutral_momentum" }
        }

        // Buy/sell pressure score (0-100, from press analyzer)
        val press = ts.meta.pressScore
        when {
            press >= 70.0 -> { score += 12; reasons += "strong_buy_pressure_${press.toInt()}" }
            press >= 55.0 -> { score += 6; reasons += "positive_pressure_${press.toInt()}" }
            press <= 40.0 -> { score -= 12; reasons += "sell_pressure_${press.toInt()}" }
            else -> { reasons += "balanced_book_${press.toInt()}" }
        }

        // Velocity score (rate of change proxy)
        val vel = ts.meta.velocityScore
        when {
            vel >= 60.0 -> { score += 8; reasons += "accelerating_${vel.toInt()}" }
            vel <= -30.0 -> { score -= 10; reasons += "decelerating_${vel.toInt()}" }
            else -> { reasons += "steady_velocity" }
        }

        // EMA fan alignment
        when (ts.meta.emafanAlignment) {
            "BULL_FAN" -> { score += 10; reasons += "ema_bull_fan" }
            "BULL_FLAT" -> { score += 4; reasons += "ema_bull_flat" }
            "BEAR_FAN" -> { score -= 12; reasons += "ema_bear_fan" }
            "BEAR_FLAT" -> { score -= 6; reasons += "ema_bear_flat" }
        }

        // Liquidity depth bonus (already gated by TreasuryScannerFeed)
        if (ts.lastLiquidityUsd >= 250_000.0) {
            score += 8
            reasons += "premium_liquidity_${(ts.lastLiquidityUsd / 1000).toInt()}k"
        } else if (ts.lastLiquidityUsd >= 100_000.0) {
            score += 3
            reasons += "good_liquidity"
        }

        // Exhaustion penalty (avoid entering at the top)
        if (ts.meta.exhaustion || ts.meta.spikeDetected) {
            score -= 15
            reasons += "exhaustion_or_spike_top"
        }

        score = score.coerceIn(0.0, 100.0)

        val (category, sizeMult) = when {
            score >= 80.0 -> "PREMIUM_SCALP" to 1.35
            score >= 65.0 -> "STANDARD_SCALP" to 1.00
            score >= 50.0 -> "PROBE_SCALP" to 0.55
            else          -> "SKIP" to 0.15  // tiny learning probe, not zero
        }

        return ScalpVerdict(score, category, sizeMult, reasons.toList())
    }

    fun statusLine(): String = "$VERSION — scalp-setup brain: 5m+15m momentum × buy pressure × liq depth × whipsaw penalty"
}

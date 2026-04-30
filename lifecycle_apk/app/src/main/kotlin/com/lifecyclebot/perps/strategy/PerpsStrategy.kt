package com.lifecyclebot.perps.strategy

import com.lifecyclebot.perps.PerpsDirection
import kotlin.math.abs

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * ⚡ PERPS STRATEGY — V5.9.378
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Perps have three native edges that spot simply does not have:
 *
 *   1. FUNDING RATE arbitrage
 *      • Positive funding = longs paying shorts → market overleveraged long
 *        → contrarian SHORT opportunity, especially when funding > +0.05%/8h
 *      • Negative funding = shorts paying longs → overleveraged short
 *        → contrarian LONG opportunity when funding < -0.05%/8h
 *      • Funding at 0 = balanced, no bias
 *
 *   2. OPEN INTEREST build-up (liquidation hunting)
 *      • Rapid OI growth in one direction during a rally/dump = weak hands
 *        about to get force-liquidated → mean-revert tradeable
 *      • Combined with funding: positive funding + rising OI = SHORT setup
 *
 *   3. LIQUIDATION-AWARE stops
 *      • Stops should NOT sit in obvious liquidation clusters (round numbers,
 *        0.5/1/2% wick zones) — move them just beyond
 *
 * Bidirectional by definition. Leverage gated by funding edge + volatility.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object PerpsStrategy {

    data class PerpsSetup(
        val direction: PerpsDirection,
        val conviction: Int,
        val tpPct: Double,
        val slPct: Double,
        val leverage: Double,
        val holdBiasHours: Double,      // suggested hold time
        val reasons: List<String>,
    )

    /**
     * Core decision. Inputs are optional — if you don't have funding/OI data,
     * the strategy gracefully falls back to momentum + volatility. As more
     * data arrives the edge sharpens.
     */
    fun decide(
        symbol: String,
        priceChange24hPct: Double,
        volatility24h: Double = 5.0,
        fundingRate8h: Double? = null,       // e.g. +0.0005 = 0.05% per 8h
        openInterestChange24h: Double? = null,  // % change in OI
        rsi: Double? = null,
        longShortRatio: Double? = null,      // > 1.0 = more longs (top-heavy)
    ): PerpsSetup? {
        val reasons = mutableListOf<String>()
        var conviction = 45
        var bias = 0.0

        // 1) FUNDING RATE arbitrage (the crown jewel of perp edges)
        fundingRate8h?.let { fr ->
            val frPct = fr * 100.0
            when {
                fr > 0.0010 -> {
                    // Extreme positive: longs paying heavy, high short conviction
                    bias -= 5
                    conviction += 20
                    reasons.add("💰 EXTREME+ funding ${"%.3f".format(frPct)}%/8h → contrarian SHORT")
                }
                fr > 0.0005 -> {
                    bias -= 3
                    conviction += 12
                    reasons.add("📊 Positive funding ${"%.3f".format(frPct)}%/8h → SHORT bias")
                }
                fr < -0.0010 -> {
                    bias += 5
                    conviction += 20
                    reasons.add("💰 EXTREME- funding ${"%.3f".format(frPct)}%/8h → contrarian LONG")
                }
                fr < -0.0005 -> {
                    bias += 3
                    conviction += 12
                    reasons.add("📊 Negative funding ${"%.3f".format(frPct)}%/8h → LONG bias")
                }
                else -> reasons.add("⚖️ Funding flat ${"%.3f".format(frPct)}%/8h")
            }
        }

        // 2) OI + price combo (liquidation-hunt setups)
        openInterestChange24h?.let { oiChange ->
            if (oiChange > 15 && priceChange24hPct > 5) {
                bias -= 2
                conviction += 10
                reasons.add("🎯 OI +${oiChange.toInt()}% on rally → longs crowded, SHORT opportunity")
            }
            if (oiChange > 15 && priceChange24hPct < -5) {
                bias += 2
                conviction += 10
                reasons.add("🎯 OI +${oiChange.toInt()}% on dump → shorts crowded, LONG opportunity")
            }
        }

        // 3) Long/short ratio (Binance/Bybit)
        longShortRatio?.let { lsr ->
            when {
                lsr > 2.5 -> { bias -= 2; reasons.add("⚠️ L/S ratio ${("%.2f".format(lsr))} — top-heavy") }
                lsr < 0.4 -> { bias += 2; reasons.add("⚠️ L/S ratio ${("%.2f".format(lsr))} — bottom-heavy") }
                else -> Unit
            }
        }

        // 4) Momentum (secondary when funding/OI unavailable)
        bias += priceChange24hPct.coerceIn(-10.0, 10.0) * 1.0
        when {
            abs(priceChange24hPct) > 8.0 -> { conviction += 15 }
            abs(priceChange24hPct) > 4.0 -> { conviction += 10 }
            abs(priceChange24hPct) > 1.5 -> { conviction += 5 }
        }

        // 5) RSI overlay (tighter thresholds for perps because leverage amplifies)
        rsi?.let { r ->
            when {
                r <= 22 -> { bias += 4; reasons.add("📉 Deep oversold ${r.toInt()}") }
                r <= 30 -> { bias += 2 }
                r >= 78 -> { bias -= 4; reasons.add("📈 Deep overbought ${r.toInt()}") }
                r >= 70 -> { bias -= 2 }
                else -> Unit
            }
        }

        if (abs(bias) < 1.5) return null
        val dir = if (bias > 0) PerpsDirection.LONG else PerpsDirection.SHORT
        conviction = conviction.coerceIn(0, 100)

        // Leverage scales with conviction AND inverts with volatility.
        // High vol × high leverage = liquidation factory. Dampen it.
        val volDamp = when {
            volatility24h > 12 -> 0.5
            volatility24h > 7 -> 0.75
            else -> 1.0
        }
        val leverage = (2.0 + 13.0 * (conviction / 100.0) * volDamp).coerceIn(2.0, 20.0)

        // TP/SL scale with vol. Liquidation-aware offset: pull SL slightly
        // beyond round-number zones (approximated here as +0.2% buffer).
        val baseTp = when {
            volatility24h > 10 -> 6.0
            volatility24h > 5 -> 4.0
            else -> 2.5
        }
        val baseSl = baseTp * 0.55  // R:R roughly 1.8:1
        val tpPct = baseTp * (0.8 + conviction / 200.0)
        val slPct = (baseSl * (1.0 + 0.2)) * (1.2 - conviction / 200.0)  // wider SL on low conviction

        val holdHours = when {
            abs(bias) > 6 -> 4.0      // strong setups: ride longer
            abs(bias) > 3 -> 2.0
            else -> 0.75
        }

        return PerpsSetup(
            direction = dir,
            conviction = conviction,
            tpPct = tpPct,
            slPct = slPct,
            leverage = leverage,
            holdBiasHours = holdHours,
            reasons = reasons,
        )
    }
}

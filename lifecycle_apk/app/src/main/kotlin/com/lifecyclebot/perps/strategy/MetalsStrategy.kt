package com.lifecyclebot.perps.strategy

import com.lifecyclebot.perps.PerpsDirection
import kotlin.math.abs

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🥇 METALS STRATEGY — V5.9.377
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Replaces the generic momentum heuristic in MetalsTrader. Metals have
 * distinctive drivers:
 *   • USD inverse: DXY up → metals down (USD-denominated). Structural.
 *   • Precious (XAU/XAG/XPT/XPD): safe-haven flight, real-yield sensitivity
 *   • Industrial (XCU/XAL/XNI/IRON/ZINC/LEAD/TIN/COBALT/LITHIUM/URANIUM):
 *     China demand + risk-on cycle, not safe haven
 *   • London AM Fix (10:30 UK) and PM Fix (15:00 UK) are the liquidity
 *     anchors — high-conviction windows
 *   • Silver has ~2x gold's volatility → wider stops, higher leverage reward
 *
 * Bidirectional by construction — the old trader was long-biased because
 * it used `change >= 0 → LONG` which misses every sell-off.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object MetalsStrategy {

    enum class Class { PRECIOUS_GOLD, PRECIOUS_SILVER, PRECIOUS_PT_PD, INDUSTRIAL_BASE, BATTERY_MATERIAL }

    data class MetalSetup(
        val direction: PerpsDirection,
        val conviction: Int,
        val tpPct: Double,
        val slPct: Double,
        val leverage: Double,
        val metalClass: Class,
        val reasons: List<String>,
    )

    fun classify(symbol: String): Class = when (symbol.uppercase()) {
        "XAU", "GOLD" -> Class.PRECIOUS_GOLD
        "XAG", "SILVER" -> Class.PRECIOUS_SILVER
        "XPT", "XPD" -> Class.PRECIOUS_PT_PD
        "LITHIUM", "COBALT", "URANIUM" -> Class.BATTERY_MATERIAL
        else -> Class.INDUSTRIAL_BASE
    }

    fun leverageFor(cls: Class, conviction: Int): Double {
        val c = (conviction.coerceIn(0, 100) / 100.0)
        return when (cls) {
            Class.PRECIOUS_GOLD -> 3.0 + 12.0 * c                 // 3-15x, stable
            Class.PRECIOUS_SILVER -> 3.0 + 17.0 * c               // 3-20x, higher vol reward
            Class.PRECIOUS_PT_PD -> 2.0 + 10.0 * c                // 2-12x, thin liq
            Class.INDUSTRIAL_BASE -> 2.0 + 8.0 * c                // 2-10x
            Class.BATTERY_MATERIAL -> 1.0 + 4.0 * c               // 1-5x, very thin
        }
    }

    /** Conviction-scaled TP/SL in %. Silver gets wider room, thin metals tighter. */
    private fun tpSlPct(cls: Class, conviction: Int): Pair<Double, Double> {
        val c = conviction.coerceIn(30, 100)
        val f = 0.7 + (c - 30) / 100.0
        return when (cls) {
            Class.PRECIOUS_GOLD -> Pair(1.5 * f, 0.8 * (2.0 - f))
            Class.PRECIOUS_SILVER -> Pair(3.0 * f, 1.5 * (2.0 - f))
            Class.PRECIOUS_PT_PD -> Pair(2.5 * f, 1.5 * (2.0 - f))
            Class.INDUSTRIAL_BASE -> Pair(2.0 * f, 1.2 * (2.0 - f))
            Class.BATTERY_MATERIAL -> Pair(4.0 * f, 2.5 * (2.0 - f))
        }
    }

    fun decide(
        symbol: String,
        priceChange24hPct: Double,
        dxyChangePct: Double? = null,      // DXY daily move
        vixLevel: Double? = null,          // VIX for safe-haven bias
        rsi: Double? = null,
    ): MetalSetup? {
        val cls = classify(symbol)
        val reasons = mutableListOf<String>()
        var conviction = 40
        var bias = 0.0

        // Trend component
        bias += priceChange24hPct.coerceIn(-5.0, 5.0) * 3
        when {
            abs(priceChange24hPct) > 2.0 -> { conviction += 20; reasons.add("🔥 Strong trend ${"%+.2f".format(priceChange24hPct)}%") }
            abs(priceChange24hPct) > 1.0 -> { conviction += 12; reasons.add("📈 Trending ${"%+.2f".format(priceChange24hPct)}%") }
            abs(priceChange24hPct) > 0.3 -> { conviction += 5 }
        }

        // USD regime — structural inverse for all metals
        dxyChangePct?.let { dxy ->
            bias -= dxy * 4
            reasons.add("💵 DXY ${"%+.2f".format(dxy)}% → metal bias ${if (dxy > 0) "SHORT" else "LONG"}")
            if (abs(dxy) > 0.3) conviction += 10
        }

        // Safe-haven bid for precious metals during risk-off (VIX spike)
        if (cls in setOf(Class.PRECIOUS_GOLD, Class.PRECIOUS_SILVER)) {
            vixLevel?.let { vix ->
                when {
                    vix > 30 -> { bias += 5; conviction += 15; reasons.add("😱 VIX=${vix.toInt()} → flight to precious") }
                    vix > 22 -> { bias += 2; conviction += 8; reasons.add("⚠️ VIX=${vix.toInt()} elevated") }
                }
            }
        }

        // RSI mean-reversion overlay
        rsi?.let { r ->
            when {
                r <= 28 -> { bias += 3; reasons.add("📉 Oversold ${r.toInt()}") }
                r >= 72 -> { bias -= 3; reasons.add("📈 Overbought ${r.toInt()}") }
            }
        }

        if (abs(bias) < 1.5) return null
        val dir = if (bias > 0) PerpsDirection.LONG else PerpsDirection.SHORT
        conviction = conviction.coerceIn(0, 100)
        val (tp, sl) = tpSlPct(cls, conviction)
        return MetalSetup(
            direction = dir,
            conviction = conviction,
            tpPct = tp,
            slPct = sl,
            leverage = leverageFor(cls, conviction),
            metalClass = cls,
            reasons = reasons,
        )
    }
}

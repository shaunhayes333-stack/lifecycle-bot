package com.lifecyclebot.perps.strategy

import com.lifecyclebot.perps.PerpsDirection
import kotlin.math.abs

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🛢️ COMMODITIES STRATEGY — V5.9.377
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Commodities have genuine seasonal structure that the meme-native scorer
 * was ignoring entirely:
 *   • Energy (oil/gas/gasoline/heating oil): winter heating demand,
 *     summer driving season, OPEC announcements, inventory weeks (Wed API)
 *   • Grains (corn/wheat/soybean): spring planting, fall harvest, drought
 *     & USDA WASDE reports
 *   • Softs (coffee/cocoa/sugar/cotton/OJ): weather events, ENSO cycle
 *   • Lumber: housing starts, interest-rate sensitivity
 *   • Livestock (cattle/hogs): feed costs, seasonal slaughter cycles
 *
 * All bidirectional. Default bias is trend-following because commodity
 * trends persist (long contango runs, supply shock runs).
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object CommoditiesStrategy {

    enum class Class { ENERGY_CRUDE, ENERGY_GAS, ENERGY_PRODUCT, GRAIN, SOFT, LUMBER, LIVESTOCK }

    data class CommoditySetup(
        val direction: PerpsDirection,
        val conviction: Int,
        val tpPct: Double,
        val slPct: Double,
        val leverage: Double,
        val commodityClass: Class,
        val seasonalBias: Double,   // -1..+1, for diagnostics
        val reasons: List<String>,
    )

    fun classify(symbol: String): Class = when (symbol.uppercase()) {
        "BRENT", "WTI" -> Class.ENERGY_CRUDE
        "NATGAS" -> Class.ENERGY_GAS
        "RBOB", "HEATING" -> Class.ENERGY_PRODUCT
        "CORN", "WHEAT", "SOYBEAN" -> Class.GRAIN
        "COFFEE", "COCOA", "SUGAR", "COTTON", "OJ" -> Class.SOFT
        "LUMBER" -> Class.LUMBER
        "CATTLE", "HOGS" -> Class.LIVESTOCK
        else -> Class.GRAIN
    }

    fun leverageFor(cls: Class, conviction: Int): Double {
        val c = conviction.coerceIn(0, 100) / 100.0
        return when (cls) {
            Class.ENERGY_CRUDE -> 2.0 + 8.0 * c         // 2-10x, deep liq
            Class.ENERGY_GAS -> 1.5 + 5.5 * c           // 1.5-7x, volatile
            Class.ENERGY_PRODUCT -> 2.0 + 6.0 * c       // 2-8x
            Class.GRAIN -> 2.0 + 6.0 * c                // 2-8x
            Class.SOFT -> 1.5 + 4.5 * c                 // 1.5-6x, weather risk
            Class.LUMBER -> 1.0 + 3.0 * c               // 1-4x, thin
            Class.LIVESTOCK -> 1.0 + 3.0 * c            // 1-4x, thin
        }
    }

    private fun tpSlPct(cls: Class, conviction: Int): Pair<Double, Double> {
        val c = conviction.coerceIn(30, 100)
        val f = 0.7 + (c - 30) / 100.0
        return when (cls) {
            Class.ENERGY_CRUDE -> Pair(2.5 * f, 1.5 * (2.0 - f))
            Class.ENERGY_GAS -> Pair(5.0 * f, 2.5 * (2.0 - f))       // very volatile
            Class.ENERGY_PRODUCT -> Pair(3.0 * f, 1.8 * (2.0 - f))
            Class.GRAIN -> Pair(2.5 * f, 1.5 * (2.0 - f))
            Class.SOFT -> Pair(3.5 * f, 2.0 * (2.0 - f))
            Class.LUMBER -> Pair(4.0 * f, 2.5 * (2.0 - f))
            Class.LIVESTOCK -> Pair(2.5 * f, 1.5 * (2.0 - f))
        }
    }

    /**
     * Seasonal bias by month & commodity. Simple lookup; not perfect but
     * directionally correct for the broad seasonal patterns. Returns
     * -1..+1 (negative = short-seasonal, positive = long-seasonal).
     */
    fun seasonalBias(cls: Class, monthOneIndexed: Int): Double {
        val m = monthOneIndexed.coerceIn(1, 12)
        return when (cls) {
            Class.ENERGY_GAS -> when (m) {
                11, 12, 1, 2 -> 0.6     // winter heating demand
                6, 7, 8 -> -0.3         // shoulder season
                else -> 0.0
            }
            Class.ENERGY_PRODUCT -> when (m) {
                10, 11, 12, 1, 2 -> 0.4  // heating oil winter
                4, 5, 6, 7 -> 0.3        // RBOB driving season
                else -> 0.0
            }
            Class.ENERGY_CRUDE -> when (m) {
                4, 5, 6, 7 -> 0.3        // driving-season demand
                else -> 0.0
            }
            Class.GRAIN -> when (m) {
                3, 4 -> 0.4              // planting uncertainty
                7, 8 -> 0.3              // weather scare window
                9, 10, 11 -> -0.3        // harvest pressure
                else -> 0.0
            }
            Class.SOFT -> when (m) {
                12, 1, 2 -> -0.2         // frost risk premium unwinds after
                6, 7, 8 -> 0.3           // hurricane/drought peak
                else -> 0.0
            }
            Class.LUMBER -> when (m) {
                3, 4, 5 -> 0.4           // spring building season demand
                else -> 0.0
            }
            Class.LIVESTOCK -> 0.0       // feed-cost driven, non-trivially seasonal
        }
    }

    fun decide(
        symbol: String,
        priceChange24hPct: Double,
        dxyChangePct: Double? = null,
        rsi: Double? = null,
        monthOneIndexed: Int = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1,
    ): CommoditySetup? {
        val cls = classify(symbol)
        val reasons = mutableListOf<String>()
        var conviction = 40
        var bias = 0.0

        // 1) Trend-following core
        bias += priceChange24hPct.coerceIn(-6.0, 6.0) * 2.5
        when {
            abs(priceChange24hPct) > 3.0 -> { conviction += 20; reasons.add("🔥 Strong trend ${"%+.2f".format(priceChange24hPct)}%") }
            abs(priceChange24hPct) > 1.5 -> { conviction += 12; reasons.add("📈 Trending ${"%+.2f".format(priceChange24hPct)}%") }
            abs(priceChange24hPct) > 0.5 -> { conviction += 5 }
        }

        // 2) USD regime (commodities USD-denominated, weaker effect than metals)
        dxyChangePct?.let { dxy ->
            bias -= dxy * 2.5
            if (abs(dxy) > 0.3) conviction += 5
            reasons.add("💵 DXY ${"%+.2f".format(dxy)}%")
        }

        // 3) Seasonal bias
        val seasonal = seasonalBias(cls, monthOneIndexed)
        if (abs(seasonal) >= 0.3) {
            bias += seasonal * 4
            conviction += 8
            reasons.add("📅 Seasonal ${if (seasonal > 0) "long" else "short"} bias (M=$monthOneIndexed)")
        }

        // 4) RSI overlay
        rsi?.let { r ->
            when {
                r <= 28 -> { bias += 2; reasons.add("📉 Oversold ${r.toInt()}") }
                r >= 72 -> { bias -= 2; reasons.add("📈 Overbought ${r.toInt()}") }
                else -> Unit
            }
        }

        if (abs(bias) < 1.5) return null
        val dir = if (bias > 0) PerpsDirection.LONG else PerpsDirection.SHORT
        conviction = conviction.coerceIn(0, 100)
        val (tp, sl) = tpSlPct(cls, conviction)
        return CommoditySetup(
            direction = dir,
            conviction = conviction,
            tpPct = tp,
            slPct = sl,
            leverage = leverageFor(cls, conviction),
            commodityClass = cls,
            seasonalBias = seasonal,
            reasons = reasons,
        )
    }
}

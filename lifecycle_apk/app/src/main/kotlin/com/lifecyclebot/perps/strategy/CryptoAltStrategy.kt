package com.lifecyclebot.perps.strategy

import com.lifecyclebot.perps.PerpsDirection
import kotlin.math.abs

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🪙 CRYPTO ALT STRATEGY — V5.9.378
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Alts trade ON BTC, not in a vacuum. This strategy bakes in the two dominant
 * forces every real alt trader knows:
 *   • BTC Dominance regime: BTC.D rising = alts underperform (flight to
 *     quality inside crypto). BTC.D falling = altseason rotation.
 *   • BTC correlation: if BTC is dumping, don't long alts regardless of
 *     alt's own chart. If BTC is ranging, alts can diverge and run.
 *   • Volatility regime: high vol = mean-revert small bounce trades;
 *     low vol = trend-follow with room to run.
 *
 * Spot = LONG only (can't short on spot). Perps = bidirectional.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object CryptoAltStrategy {

    enum class Mode { SPOT_LONG_ONLY, PERPS_BIDIRECTIONAL }
    enum class VolRegime { LOW, NORMAL, HIGH, EXTREME }
    enum class BtcRegime { ALT_SEASON, MIXED, BTC_SEASON }

    data class AltSetup(
        val direction: PerpsDirection,
        val conviction: Int,
        val tpPct: Double,
        val slPct: Double,
        val leverage: Double,         // 1.0 for spot
        val mode: Mode,
        val volRegime: VolRegime,
        val btcRegime: BtcRegime,
        val reasons: List<String>,
    )

    fun classifyVolRegime(volatility24h: Double): VolRegime = when {
        volatility24h > 15.0 -> VolRegime.EXTREME
        volatility24h > 8.0 -> VolRegime.HIGH
        volatility24h > 3.0 -> VolRegime.NORMAL
        else -> VolRegime.LOW
    }

    fun classifyBtcRegime(btcDominanceChange7d: Double): BtcRegime = when {
        btcDominanceChange7d < -1.5 -> BtcRegime.ALT_SEASON
        btcDominanceChange7d > 1.5 -> BtcRegime.BTC_SEASON
        else -> BtcRegime.MIXED
    }

    fun decide(
        symbol: String,
        priceChange24hPct: Double,
        mode: Mode,
        volatility24h: Double = 5.0,
        btcDominanceChange7d: Double = 0.0,
        btcPriceChange24h: Double = 0.0,
        rsi: Double? = null,
    ): AltSetup? {
        val reasons = mutableListOf<String>()
        var conviction = 40
        var bias = 0.0

        val volR = classifyVolRegime(volatility24h)
        val btcR = classifyBtcRegime(btcDominanceChange7d)

        // 1) BTC dominance regime shapes overall alt bias
        when (btcR) {
            BtcRegime.ALT_SEASON -> { bias += 3; conviction += 15; reasons.add("🚀 ALT SEASON (BTC.D ${"%+.2f".format(btcDominanceChange7d)}%)") }
            BtcRegime.BTC_SEASON -> { bias -= 3; conviction += 10; reasons.add("🪙 BTC SEASON (BTC.D ${"%+.2f".format(btcDominanceChange7d)}%)") }
            BtcRegime.MIXED -> reasons.add("⚖️ Mixed regime")
        }

        // 2) BTC spot direction — hard gate when BTC is dumping
        if (btcPriceChange24h < -3.0) {
            bias -= 4
            conviction += 10
            reasons.add("🔴 BTC dumping ${"%+.2f".format(btcPriceChange24h)}% → alt SHORT bias")
        } else if (btcPriceChange24h > 3.0) {
            bias += 3
            reasons.add("🟢 BTC pumping ${"%+.2f".format(btcPriceChange24h)}% → alt LONG bias")
        }

        // 3) Alt's own momentum
        bias += priceChange24hPct.coerceIn(-10.0, 10.0) * 1.5
        when {
            abs(priceChange24hPct) > 10.0 -> { conviction += 20 }
            abs(priceChange24hPct) > 5.0 -> { conviction += 12 }
            abs(priceChange24hPct) > 2.0 -> { conviction += 5 }
        }

        // 4) Volatility regime changes strategy style
        when (volR) {
            VolRegime.EXTREME -> {
                // Mean-revert: contrarian small size, tight stops
                bias = -bias * 0.5  // flip partial for mean-revert
                conviction -= 10
                reasons.add("⚡ EXTREME vol → mean-revert mode")
            }
            VolRegime.HIGH -> {
                reasons.add("📊 High vol")
            }
            VolRegime.LOW -> {
                conviction += 8  // trends hold better in low vol
                reasons.add("😴 Low vol → trend-friendly")
            }
            else -> {}
        }

        // 5) RSI overlay
        rsi?.let { r ->
            when {
                r <= 25 -> { bias += 3; reasons.add("📉 Oversold ${r.toInt()}") }
                r >= 75 -> { bias -= 3; reasons.add("📈 Overbought ${r.toInt()}") }
                else -> Unit
            }
        }

        // 6) Spot = LONG only enforcement
        if (mode == Mode.SPOT_LONG_ONLY && bias < 0) {
            return null  // spot can't short
        }

        if (abs(bias) < 1.5) return null
        val dir = if (bias > 0) PerpsDirection.LONG else PerpsDirection.SHORT
        conviction = conviction.coerceIn(0, 100)

        val (tpPct, slPct) = when (volR) {
            VolRegime.EXTREME -> Pair(3.0, 2.0)     // tight mean-revert
            VolRegime.HIGH -> Pair(6.0, 3.0)
            VolRegime.NORMAL -> Pair(5.0, 2.5)
            VolRegime.LOW -> Pair(4.0, 1.8)
        }
        val lev = if (mode == Mode.SPOT_LONG_ONLY) 1.0
            else (2.0 + 8.0 * (conviction / 100.0)).coerceIn(2.0, 10.0)

        return AltSetup(
            direction = dir,
            conviction = conviction,
            tpPct = tpPct,
            slPct = slPct,
            leverage = lev,
            mode = mode,
            volRegime = volR,
            btcRegime = btcR,
            reasons = reasons,
        )
    }
}

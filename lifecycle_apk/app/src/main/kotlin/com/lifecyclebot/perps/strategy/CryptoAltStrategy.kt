package com.lifecyclebot.perps.strategy

import com.lifecyclebot.perps.PerpsDirection
import kotlin.math.abs

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🪙 CRYPTO ALT STRATEGY — V5.9.378 · V5.0.8535 §C12
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
 *
 * 8535 §C12 — the caller passed `abs(change)` as "volatility" (displacement is not amplitude: a monotone +12 % day and
 * a 12 %-wide chop entered the same regime, and a quiet +2 % day earned the LOW-vol trend bonus), BTC dominance as a
 * literal `0.0` (so ALT/BTC season could never fire) and in EXTREME flipped the sign of the whole bias (a verified
 * nothing turned into a contrarian spot LONG). Each submodel now takes its input as nullable: unknown is UNKNOWN, with
 * the submodel OFF and named in the reasons, and the EXTREME mean-revert entry requires an observed RSI extreme AND a
 * confirmed reversal against a displacement that leaves room for the target. There is no sign inversion.
 */
object CryptoAltStrategy {

    enum class Mode { SPOT_LONG_ONLY, PERPS_BIDIRECTIONAL }
    enum class VolRegime { LOW, NORMAL, HIGH, EXTREME, UNKNOWN }
    enum class BtcRegime { ALT_SEASON, MIXED, BTC_SEASON, UNKNOWN }

    /** EXTREME mean-revert needs at least this much 24h displacement to revert (twice the 3 % EXTREME target). */
    const val EXTREME_MIN_DISPLACEMENT_PCT = 6.0

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

    /** [volatility24h] is a realised 24h range in % of price; null (or a non-finite / negative value) is UNKNOWN. */
    fun classifyVolRegime(volatility24h: Double?): VolRegime = when {
        volatility24h == null || !volatility24h.isFinite() || volatility24h < 0.0 -> VolRegime.UNKNOWN
        volatility24h > 15.0 -> VolRegime.EXTREME
        volatility24h > 8.0 -> VolRegime.HIGH
        volatility24h > 3.0 -> VolRegime.NORMAL
        else -> VolRegime.LOW
    }

    /** [btcDominanceChange7d] is an observed 7-day BTC.D change in points; null is UNKNOWN (the submodel is off). */
    fun classifyBtcRegime(btcDominanceChange7d: Double?): BtcRegime = when {
        btcDominanceChange7d == null || !btcDominanceChange7d.isFinite() -> BtcRegime.UNKNOWN
        btcDominanceChange7d < -1.5 -> BtcRegime.ALT_SEASON
        btcDominanceChange7d > 1.5 -> BtcRegime.BTC_SEASON
        else -> BtcRegime.MIXED
    }

    fun decide(
        symbol: String,
        priceChange24hPct: Double,
        mode: Mode,
        volatility24h: Double? = null,
        btcDominanceChange7d: Double? = null,
        btcPriceChange24h: Double = 0.0,
        rsi: Double? = null,
        /** Observed reversal against the 24h displacement (MACD cross on real ticks); null = not enough observation. */
        reversalConfirmed: Boolean? = null,
    ): AltSetup? {
        val reasons = mutableListOf<String>()
        var conviction = 40
        var bias = 0.0

        val volR = classifyVolRegime(volatility24h)
        val btcR = classifyBtcRegime(btcDominanceChange7d)
        reasons.add(
            "feeds vol=" + (if (volR == VolRegime.UNKNOWN) "UNOBSERVED" else "%.1f%%".format(volatility24h)) +
                " BTC.D=" + (if (btcR == BtcRegime.UNKNOWN) "UNOBSERVED" else "%+.2f".format(btcDominanceChange7d)) +
                " RSI=" + (rsi?.let { "%.0f".format(it) } ?: "UNOBSERVED") +
                " reversal=" + (reversalConfirmed?.toString() ?: "UNOBSERVED"),
        )

        // 1) BTC dominance regime shapes overall alt bias — only when BTC.D was observed
        when (btcR) {
            BtcRegime.ALT_SEASON -> { bias += 3; conviction += 15; reasons.add("🚀 ALT SEASON (BTC.D ${"%+.2f".format(btcDominanceChange7d)}%)") }
            BtcRegime.BTC_SEASON -> { bias -= 3; conviction += 10; reasons.add("🪙 BTC SEASON (BTC.D ${"%+.2f".format(btcDominanceChange7d)}%)") }
            BtcRegime.MIXED -> reasons.add("⚖️ Mixed regime")
            BtcRegime.UNKNOWN -> reasons.add("⚖️ BTC.D unobserved → dominance submodel off")
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
                // 8535 §C12 — no unconditional sign inversion. The only EXTREME entry is a verified reversal against a
                // displacement large enough to revert into the 3 % target; anything else is a stand-down, because
                // chasing or fading an extreme move blind is exactly the trade the old flip manufactured.
                val contrarian = when {
                    priceChange24hPct <= -EXTREME_MIN_DISPLACEMENT_PCT && rsi != null && rsi <= 30.0 && reversalConfirmed == true -> PerpsDirection.LONG
                    priceChange24hPct >= EXTREME_MIN_DISPLACEMENT_PCT && rsi != null && rsi >= 70.0 && reversalConfirmed == true -> PerpsDirection.SHORT
                    else -> null
                } ?: return null
                bias = if (contrarian == PerpsDirection.LONG) 4.0 else -4.0
                conviction -= 10
                reasons.add("⚡ EXTREME vol: verified reversal (RSI ${"%.0f".format(rsi)}, MACD cross) → mean-revert ${contrarian.name}")
            }
            VolRegime.HIGH -> {
                reasons.add("📊 High vol")
            }
            VolRegime.LOW -> {
                conviction += 8  // trends hold better in low vol
                reasons.add("😴 Low vol → trend-friendly")
            }
            VolRegime.UNKNOWN -> reasons.add("📊 vol unobserved → regime submodel off")
            VolRegime.NORMAL -> {}
        }

        // 5) RSI overlay (outside EXTREME, where it was already consumed as the reversal evidence)
        if (volR != VolRegime.EXTREME) rsi?.let { r ->
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
            VolRegime.NORMAL, VolRegime.UNKNOWN -> Pair(5.0, 2.5)
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

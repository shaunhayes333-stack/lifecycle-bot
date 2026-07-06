package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * V5.0.6139 — EntryArchetypeClassifier.
 *
 * Shared entry-style taxonomy for Meme Trader now, Crypto Universe parity later.
 * Local-only, deterministic, no network/LLM, no hard block. The label is stable
 * enough for clean-live StrategyTruth lane|style compounding and later
 * CryptoAlt/Meme cross-stack alignment.
 */
object EntryArchetypeClassifier {
    data class Archetype(
        val label: String,
        val confidence: Int,
        val sizeMultiplier: Double,
        val reason: String,
    )

    fun classify(ts: TokenState, lane: String): Archetype {
        val latest = try { ts.history.lastOrNull() } catch (_: Throwable) { null }
        val open = try { latest?.openUsd ?: 0.0 } catch (_: Throwable) { 0.0 }
        val close = try { latest?.priceUsd ?: ts.lastPrice } catch (_: Throwable) { ts.lastPrice }
        val candlePct = if (open > 0.0 && close > 0.0) ((close - open) / open) * 100.0 else 0.0
        val upperWick = try { latest?.upperWickRatio ?: 0.0 } catch (_: Throwable) { 0.0 }
        val liq = try { ts.lastLiquidityUsd } catch (_: Throwable) { 0.0 }
        val mcap = try { ts.lastMcap } catch (_: Throwable) { 0.0 }
        val buyPressure = try { ts.lastBuyPressurePct } catch (_: Throwable) { 50.0 }
        val sellPressure = try { ts.lastSellPressurePct } catch (_: Throwable) { 50.0 }
        val holder = try { ts.topHolderPct ?: 0.0 } catch (_: Throwable) { 0.0 }
        val momentum = try { ts.momentum ?: 0.0 } catch (_: Throwable) { 0.0 }
        val source = try { ts.source.uppercase() } catch (_: Throwable) { "" }
        val laneUpper = lane.uppercase().ifBlank { ts.position.tradingMode.uppercase().ifBlank { "STANDARD" } }

        val label = when {
            source.contains("PUMP") && candlePct >= 6.0 && buyPressure >= 65.0 -> "pump_graduation"
            candlePct >= 8.0 && momentum >= 10.0 && buyPressure >= 65.0 -> "green_momentum_breakout"
            liq >= 15_000.0 && holder in 1.0..24.0 && sellPressure <= 42.0 -> "liquidity_depth_quality"
            candlePct in -6.0..3.0 && buyPressure >= 58.0 && upperWick < 0.35 -> "pullback_reclaim"
            upperWick >= 0.45 && sellPressure >= 42.0 -> "thin_churn_trap"
            mcap > 0.0 && liq / mcap.coerceAtLeast(1.0) >= 0.08 -> "liq_mcap_efficiency"
            else -> "standard_flow"
        }
        val conf = when (label) {
            "green_momentum_breakout", "pump_graduation" -> 78
            "liquidity_depth_quality", "pullback_reclaim" -> 72
            "thin_churn_trap" -> 68
            "liq_mcap_efficiency" -> 66
            else -> 55
        }
        val sizeMult = when (label) {
            "green_momentum_breakout" -> 1.10
            "pump_graduation" -> 1.08
            "liquidity_depth_quality" -> 1.06
            "pullback_reclaim" -> 1.03
            "thin_churn_trap" -> 0.72
            else -> 1.0
        }
        return Archetype(
            label = label,
            confidence = conf,
            sizeMultiplier = sizeMult,
            reason = "lane=$laneUpper candle=${"%.1f".format(candlePct)} bp=${"%.1f".format(buyPressure)} sp=${"%.1f".format(sellPressure)} liq=${liq.toInt()} holder=${"%.1f".format(holder)} wick=${"%.2f".format(upperWick)}",
        )
    }
}

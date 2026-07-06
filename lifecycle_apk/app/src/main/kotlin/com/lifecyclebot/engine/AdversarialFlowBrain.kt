package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * V5.0.6137 — AdversarialFlowBrain.
 *
 * Mempool/transaction-flow adapter shell without provider dependency. When a
 * future pending-tx/mempool feed exists, it belongs here as background-cached
 * inputs. Today this uses local, already-present microstructure proxies: wick,
 * buy/sell pressure, liquidity, holder concentration, tick freshness and route
 * reliability. No network calls, no LLM calls, no hard blocks.
 */
object AdversarialFlowBrain {
    data class Posture(
        val riskScore: Int,
        val sizeMultiplier: Double,
        val urgentMevTip: Boolean,
        val reason: String,
    )

    fun evaluate(ts: TokenState): Posture {
        val latest = try { ts.history.lastOrNull() } catch (_: Throwable) { null }
        val upperWick = try { latest?.upperWickRatio ?: 0.0 } catch (_: Throwable) { 0.0 }
        val buyPressure = try { ts.lastBuyPressurePct } catch (_: Throwable) { 50.0 }
        val sellPressure = try { ts.lastSellPressurePct } catch (_: Throwable) { 50.0 }
        val liq = try { ts.lastLiquidityUsd } catch (_: Throwable) { 0.0 }
        val topHolder = try { ts.topHolderPct ?: 0.0 } catch (_: Throwable) { 0.0 }
        val tickAge = if (ts.lastPriceUpdate > 0L) System.currentTimeMillis() - ts.lastPriceUpdate else Long.MAX_VALUE
        val routeReliability = try { ExecutionRouteReliabilityMemory.sizeMultiplierForSource(ts.source, ts.mint) } catch (_: Throwable) { 1.0 }

        var risk = 0
        val reasons = mutableListOf<String>()
        if (upperWick >= 0.45) { risk += 2; reasons += "upper_wick" }
        if (buyPressure >= 82.0 && sellPressure >= 42.0) { risk += 2; reasons += "two_sided_churn" }
        if (liq in 1.0..2_500.0) { risk += 2; reasons += "thin_liq" }
        if (topHolder >= 28.0) { risk += 2; reasons += "holder_concentration" }
        if (tickAge > 5_000L) { risk += 1; reasons += "stale_tick" }
        if (routeReliability < 0.86) { risk += 1; reasons += "route_degraded" }

        val sizeMult = when {
            risk >= 7 -> 0.55
            risk >= 5 -> 0.68
            risk >= 3 -> 0.82
            risk >= 1 -> 0.92
            else -> 1.0
        }
        val urgent = risk in 1..4 && tickAge <= 2_500L && buyPressure >= 68.0
        return Posture(
            riskScore = risk.coerceIn(0, 10),
            sizeMultiplier = sizeMult,
            urgentMevTip = urgent,
            reason = reasons.joinToString("+").ifBlank { "normal_flow" },
        )
    }
}

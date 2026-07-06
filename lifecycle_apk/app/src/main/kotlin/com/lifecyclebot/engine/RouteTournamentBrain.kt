package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * V5.0.6138 — RouteTournamentBrain.
 *
 * Route-aware live execution tournament. Paper/shadow/lab may propose route
 * variants elsewhere, but this hot-path reader uses only cached local route
 * reliability and clean-live StrategyTruth style bias. It never calls network,
 * never calls LLM, never blocks a trade, and never lets paper authorize live.
 */
object RouteTournamentBrain {
    data class Posture(
        val preferredRoute: String,
        val pumpFirstAllowed: Boolean,
        val sizeMultiplier: Double,
        val reason: String,
    )

    fun evaluate(ts: TokenState, lane: String): Posture {
        val source = try { ts.source.uppercase() } catch (_: Throwable) { "" }
        val routeReliability = try { ExecutionRouteReliabilityMemory.sizeMultiplierForSource(source, ts.mint) } catch (_: Throwable) { 1.0 }
        val laneKey = lane.uppercase().ifBlank { ts.position.tradingMode.uppercase().ifBlank { "STANDARD" } }
        val pumpSource = source.contains("PUMP") || source.contains("PORTAL")
        val dexSource = source.contains("RAYDIUM") || source.contains("DEX") || source.contains("JUPITER")
        val cleanLaneBias = try { StrategyTelemetry.liveStyleSizeMultiplier(laneKey, if (pumpSource) "pump_graduation" else "liquidity_depth_quality") } catch (_: Throwable) { 1.0 }
        val pumpFirst = when {
            pumpSource && routeReliability >= 0.86 -> true
            pumpSource && cleanLaneBias >= 1.10 -> true
            else -> false
        }
        val preferred = when {
            pumpFirst -> "PUMPPORTAL_FIRST"
            dexSource -> "JUPITER_FIRST"
            else -> "JUPITER_DEFAULT"
        }
        val routeMult = when {
            routeReliability < 0.74 -> 0.82
            routeReliability < 0.86 -> 0.92
            cleanLaneBias > 1.0 -> cleanLaneBias.coerceIn(1.0, 1.12)
            else -> 1.0
        }
        val reason = "routeReliability=${"%.2f".format(routeReliability)} cleanLiveBias=${"%.2f".format(cleanLaneBias)} source=${source.take(32)}"
        return Posture(preferred, pumpFirst, routeMult.coerceIn(0.75, 1.12), reason)
    }
}

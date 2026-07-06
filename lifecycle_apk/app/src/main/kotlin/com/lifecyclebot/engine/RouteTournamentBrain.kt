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
        val venue = VenueUniverse.classify(source)
        val routeReliability = try { ExecutionRouteReliabilityMemory.sizeMultiplierForSource(venue.canonical, ts.mint) } catch (_: Throwable) { 1.0 }
        val laneKey = lane.uppercase().ifBlank { ts.position.tradingMode.uppercase().ifBlank { "STANDARD" } }
        val styleKey = when (venue.route) {
            VenueUniverse.RouteFamily.PUMP_NATIVE -> "pump_graduation"
            VenueUniverse.RouteFamily.SOL_AMM_DIRECT -> "liquidity_depth_quality"
            VenueUniverse.RouteFamily.SOL_AGGREGATOR -> "route_aggregator_quality"
            VenueUniverse.RouteFamily.CHAIN_SPECIFIC_DEX -> "chain_specific_venue"
            VenueUniverse.RouteFamily.CEX_SIGNAL_ONLY -> "cex_listing_trend"
            VenueUniverse.RouteFamily.TREND_SIGNAL_ONLY -> "regional_social_trend"
            else -> "liquidity_depth_quality"
        }
        val cleanLaneBias = try { StrategyTelemetry.liveStyleSizeMultiplier(laneKey, styleKey) } catch (_: Throwable) { 1.0 }
        val pumpFirst = venue.route == VenueUniverse.RouteFamily.PUMP_NATIVE && (routeReliability >= 0.86 || cleanLaneBias >= 1.10)
        val preferred = when (venue.route) {
            VenueUniverse.RouteFamily.PUMP_NATIVE -> if (pumpFirst) "PUMPPORTAL_FIRST" else "SOL_AGGREGATOR_FIRST"
            VenueUniverse.RouteFamily.SOL_AMM_DIRECT -> "SOL_AMM_OR_AGGREGATOR_FIRST:${venue.canonical}"
            VenueUniverse.RouteFamily.SOL_AGGREGATOR -> "SOL_AGGREGATOR_FIRST:${venue.canonical}"
            VenueUniverse.RouteFamily.CHAIN_SPECIFIC_DEX -> "CHAIN_SPECIFIC_VENUE:${venue.canonical}"
            VenueUniverse.RouteFamily.CEX_SIGNAL_ONLY -> "CEX_SIGNAL_ONLY:${venue.canonical}"
            VenueUniverse.RouteFamily.TREND_SIGNAL_ONLY -> "TREND_SIGNAL_ONLY:${venue.canonical}"
            VenueUniverse.RouteFamily.DATA_ONLY -> "DATA_SIGNAL_ONLY:${venue.canonical}"
            else -> "VENUE_UNKNOWN_AGGREGATOR_FALLBACK"
        }
        val routeMult = when {
            routeReliability < 0.74 -> 0.82
            routeReliability < 0.86 -> 0.92
            cleanLaneBias > 1.0 -> cleanLaneBias.coerceIn(1.0, 1.12)
            else -> 1.0
        }
        val reason = "venue=${venue.canonical} chain=${venue.chain} family=${venue.venue} route=${venue.route} routeReliability=${"%.2f".format(routeReliability)} cleanLiveBias=${"%.2f".format(cleanLaneBias)} source=${source.take(32)} multi_exchange_universe=true"
        return Posture(preferred, pumpFirst, routeMult.coerceIn(0.75, 1.12), reason)
    }
}

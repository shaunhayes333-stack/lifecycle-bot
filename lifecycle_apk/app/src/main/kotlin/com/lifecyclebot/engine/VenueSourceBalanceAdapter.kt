package com.lifecyclebot.engine

/**
 * V5.0.6141 — VenueSourceBalanceAdapter.
 *
 * Soft intake pressure for a multi-exchange/multi-RPC universe. This does not
 * block sources and does not replace ScannerSourceBrain. It adds a venue-family
 * prior so Meme Trader and Crypto Universe do not collapse to Pump/Jupiter-only
 * intake while waiting for per-source learned samples.
 */
object VenueSourceBalanceAdapter {
    fun intakeMultiplier(rawSource: String?): Double {
        val venue = VenueUniverse.classify(rawSource)
        return when (venue.route) {
            VenueUniverse.RouteFamily.PUMP_NATIVE -> 0.96
            VenueUniverse.RouteFamily.SOL_AGGREGATOR -> 1.04
            VenueUniverse.RouteFamily.SOL_AMM_DIRECT -> 1.12
            VenueUniverse.RouteFamily.CHAIN_SPECIFIC_DEX -> 1.14
            VenueUniverse.RouteFamily.CEX_SIGNAL_ONLY -> 1.08
            VenueUniverse.RouteFamily.TREND_SIGNAL_ONLY -> 1.10
            VenueUniverse.RouteFamily.DATA_ONLY -> 1.03
            else -> 1.0
        }.coerceIn(0.80, 1.20)
    }

    fun bestMultiplier(sources: Collection<String>): Double = try {
        sources.map { intakeMultiplier(it) }.maxOrNull() ?: 1.0
    } catch (_: Throwable) { 1.0 }

    fun compact(rawSource: String?): String {
        val v = VenueUniverse.classify(rawSource)
        return "venue=${v.canonical} chain=${v.chain} family=${v.venue} route=${v.route} venueMult=${"%.2f".format(intakeMultiplier(rawSource))}"
    }
}

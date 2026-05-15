package com.lifecyclebot.engine.sell

/**
 * V5.9.779 — EMERGENT MEME ROUTER FIX.
 *
 * Lightweight venue classifier for live MEME sells. Operator forensics
 * showed AATE falling through to Jupiter exact-in slippage ladder for
 * pump.fun bonding curve tokens BEFORE trying pump-native routes. This
 * classifier surfaces the venue decision into forensics
 * (VENUE_RESOLVE phase) so the operator can see which route the sell
 * picked and why.
 *
 * Full PumpSwap / Raydium / Orca / Meteora direct routing is staged for
 * a follow-up push — this object focuses on EXPOSING the decision and
 * letting the sell path pick the pump-native route first.
 *
 * Decision rules (in priority order):
 *  1. mint ends with "pump"      → PUMP_FUN_BONDING (use PumpPortal)
 *  2. mint registry says PUMPSWAP → PUMPSWAP (PumpPortal handles too)
 *  3. (future) Raydium/Orca/Meteora pool lookups
 *  4. fallback                    → JUPITER_AGGREGATOR
 */
object MemeVenueRouter {

    enum class Venue {
        PUMP_FUN_BONDING,
        PUMPSWAP,
        RAYDIUM,
        ORCA,
        METEORA,
        JUPITER_AGGREGATOR,
        UNKNOWN,
    }

    data class VenueResolution(
        val mint: String,
        val symbol: String,
        val venue: Venue,
        val bondingCurveActive: Boolean,
        val pumpSwapPoolFound: Boolean,
        val raydiumPoolFound: Boolean,
        val meteoraPoolFound: Boolean,
        val orcaPoolFound: Boolean,
        val jupiterRouteFound: Boolean,
        val reason: String,
    )

    /**
     * Resolve and forensically log the venue for a given mint. Heuristics
     * are intentionally light — full pool lookups are out of scope for
     * this push; we use the existing PumpFunDirectApi.isPumpFunMint()
     * + GlobalTradeRegistry source tag as the primary signal.
     */
    fun resolve(mint: String, symbol: String): VenueResolution {
        val isPumpMint = try {
            com.lifecyclebot.network.PumpFunDirectApi.isPumpFunMint(mint)
        } catch (_: Throwable) { false }

        val regEntry = try {
            com.lifecyclebot.engine.GlobalTradeRegistry.getEntry(mint)
        } catch (_: Throwable) { null }
        val srcTag = regEntry?.source?.uppercase() ?: ""

        val pumpSwapPoolFound = srcTag.contains("PUMPSWAP") || srcTag.contains("GRADUATED")
        val raydiumPoolFound  = srcTag.contains("RAYDIUM")
        val orcaPoolFound     = srcTag.contains("ORCA") || srcTag.contains("WHIRLPOOL")
        val meteoraPoolFound  = srcTag.contains("METEORA") || srcTag.contains("DLMM")

        val venue: Venue
        val bondingCurveActive: Boolean
        val reason: String
        when {
            isPumpMint && !pumpSwapPoolFound -> {
                venue = Venue.PUMP_FUN_BONDING
                bondingCurveActive = true
                reason = "mint ends with 'pump' and no migration signal"
            }
            isPumpMint && pumpSwapPoolFound -> {
                venue = Venue.PUMPSWAP
                bondingCurveActive = false
                reason = "pump mint with PumpSwap pool signal — graduated"
            }
            pumpSwapPoolFound -> {
                venue = Venue.PUMPSWAP
                bondingCurveActive = false
                reason = "registry source indicates PumpSwap pool"
            }
            raydiumPoolFound -> {
                venue = Venue.RAYDIUM
                bondingCurveActive = false
                reason = "registry source indicates Raydium pool"
            }
            orcaPoolFound -> {
                venue = Venue.ORCA
                bondingCurveActive = false
                reason = "registry source indicates Orca pool"
            }
            meteoraPoolFound -> {
                venue = Venue.METEORA
                bondingCurveActive = false
                reason = "registry source indicates Meteora pool"
            }
            else -> {
                venue = Venue.JUPITER_AGGREGATOR
                bondingCurveActive = false
                reason = "no direct venue signal — Jupiter aggregator fallback"
            }
        }

        val resolution = VenueResolution(
            mint = mint,
            symbol = symbol,
            venue = venue,
            bondingCurveActive = bondingCurveActive,
            pumpSwapPoolFound = pumpSwapPoolFound,
            raydiumPoolFound = raydiumPoolFound,
            meteoraPoolFound = meteoraPoolFound,
            orcaPoolFound = orcaPoolFound,
            jupiterRouteFound = true,    // Jupiter is always reachable as a fallback
            reason = reason,
        )

        try {
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "VENUE_RESOLVE",
                "mint=${mint.take(10)} symbol=$symbol selectedVenue=${venue.name} " +
                    "bondingCurveActive=$bondingCurveActive pumpSwapPoolFound=$pumpSwapPoolFound " +
                    "raydiumPoolFound=$raydiumPoolFound meteoraPoolFound=$meteoraPoolFound " +
                    "orcaPoolFound=$orcaPoolFound jupiterRouteFound=true reason='$reason'",
            )
        } catch (_: Throwable) {}

        return resolution
    }

    /** Convenience: should the sell try pump-native (PumpPortal) FIRST? */
    fun preferPumpNative(v: Venue): Boolean = when (v) {
        Venue.PUMP_FUN_BONDING, Venue.PUMPSWAP -> true
        else -> false
    }
}

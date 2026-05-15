/*
 * V5.9.785 — CanonicalFeaturesBuilder (operator audit Wave 5 producer sweep)
 * ─────────────────────────────────────────────────────────────────────────
 *
 * Single helper that constructs a feature-rich CandidateFeatures payload
 * from the data already available at every meme-trade close site
 * (TokenState + Position + Trade + mode/source enums).
 *
 * Every producer that closes a trade should call:
 *   val cand = CanonicalFeaturesBuilder.fromTokenState(ts, trade, mode, source, env)
 *   val outcome = CanonicalTradeOutcome(..., candidate = cand, featuresIncomplete = false, ...)
 *   CanonicalOutcomeBus.publish(outcome)
 *
 * The builder returns featuresIncomplete=true ONLY when key fields couldn't
 * be derived (no liquidity / no mcap / unknown venue) — strategy learners
 * (BehaviorLearning, AdaptiveLearningEngine) skip those samples.
 */
package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.data.Trade

object CanonicalFeaturesBuilder {

    /**
     * Build CandidateFeatures from a TokenState at close time.
     *
     * @return Pair<CandidateFeatures, isIncomplete>
     */
    fun fromTokenState(
        ts: TokenState,
        trade: Trade,
        mode: TradeMode,
        source: TradeSource,
        env: TradeEnvironment,
    ): Pair<CandidateFeatures, Boolean> {

        val liqUsd = ts.lastLiquidityUsd
        val mcapUsd = ts.lastMcap
        val topHolderPct = ts.topHolderPct ?: ts.safety.topHolderPct
        val ageMs = if (ts.position.entryTime > 0) {
            System.currentTimeMillis() - ts.position.entryTime
        } else 0L
        val ageMins = ageMs.toDouble() / 60_000.0
        val holdSec = if (ts.position.entryTime > 0) {
            (System.currentTimeMillis() - ts.position.entryTime) / 1000
        } else 0L

        val safetyTier = when (ts.safety.tier) {
            SafetyTier.SAFE -> "SAFE"
            SafetyTier.CAUTION -> "CAUTION"
            SafetyTier.HARD_BLOCK -> "DANGER"
        }
        val mintAuthority = when (ts.safety.mintAuthorityDisabled) {
            true -> "RENOUNCED"
            false -> "RETAINED"
            else -> "UNKNOWN"
        }
        val freezeAuthority = when (ts.safety.freezeAuthorityDisabled) {
            true -> "RENOUNCED"
            false -> "RETAINED"
            else -> "UNKNOWN"
        }
        val (venue, route, bondingCurveActive, migrated) = inferVenueRoute(ts)

        val cand = CandidateFeatures(
            assetClass = when (source) {
                TradeSource.SHITCOIN, TradeSource.MOONSHOT, TradeSource.MANIP,
                TradeSource.EXPRESS, TradeSource.CYCLIC, TradeSource.COPYTRADE,
                TradeSource.TREASURY -> "MEME"
                TradeSource.BLUECHIP -> "BLUECHIP"
                TradeSource.MARKETS -> "CRYPTO_ALT_SPOT"
                else -> "MEME"
            },
            runtimeMode = env.name,
            trader = mode.name,
            venue = venue,
            route = route,
            bondingCurveActive = bondingCurveActive,
            migrated = migrated,
            ageBucket = ageBucket(ageMins),
            liqBucket = liqBucket(liqUsd),
            mcapBucket = mcapBucket(mcapUsd),
            volVelocity = volVelocity(ts),
            buyPressure = buyPressure(ts.lastBuyPressurePct),
            sellPressure = sellPressure(ts.lastBuyPressurePct),
            holderGrowth = holderGrowth(ts.holderGrowthRate),
            holderConcentration = holderConcentration(topHolderPct ?: 0.0),
            rugTier = safetyTier,
            safetyTier = safetyTier,
            mintAuthority = mintAuthority,
            freezeAuthority = freezeAuthority,
            slippageBucket = "",   // not captured in TokenState — Push 6 wiring
            entryPattern = ts.phase.ifBlank { "STANDARD" },
            bubbleClusterPattern = ts.safety.bundleType.ifBlank { "CLEAN" },
            fdgReasonFamily = ts.signal.ifBlank { "STANDARD" },
            symbolicVerdict = "",  // populated by FDG wiring in Push 6
            exitReasonFamily = trade.reason.ifBlank { "" },
            holdBucket = holdBucket(holdSec),
            manualOrExternalClose = false,
        )

        // Key-field completeness gate: trader, venue, liqBucket, mcapBucket must
        // be populated for the sample to count as feature-rich. If any key field
        // is blank/unknown, mark incomplete so strategy learners skip the sample.
        val isIncomplete = cand.trader.isBlank() || cand.trader == "UNKNOWN" ||
                cand.venue.isBlank() || cand.venue == "UNKNOWN" ||
                cand.liqBucket.isBlank() ||
                cand.mcapBucket.isBlank() ||
                cand.runtimeMode.isBlank()

        return cand to isIncomplete
    }

    /** Map TokenState.lastPriceDex / .lastPriceSource into venue + route + curve status. */
    private fun inferVenueRoute(ts: TokenState): Tuple4 {
        val dex = ts.lastPriceDex.uppercase()
        val src = ts.lastPriceSource.uppercase()
        val isBondingCurve = src.contains("PUMP_FUN_BC") || src.contains("PUMP_PORTAL")
        val isMigrated = dex.contains("RAYDIUM") || dex.contains("BONK") || dex.contains("METEORA")
        val venue = when {
            isBondingCurve && !isMigrated -> "PUMP_FUN_BONDING"
            dex.contains("PUMP") -> "PUMPSWAP"
            dex.contains("RAYDIUM") -> "RAYDIUM"
            dex.contains("METEORA") -> "METEORA"
            dex.contains("ORCA") -> "ORCA"
            dex.contains("BONK") -> "BONK"
            dex.isNotBlank() -> dex
            else -> "UNKNOWN"
        }
        val route = when {
            isBondingCurve && !isMigrated -> "PUMP_NATIVE"
            venue == "PUMPSWAP" -> "PUMPPORTAL"
            venue == "JUPITER" || venue == "RAYDIUM" || venue == "ORCA" || venue == "METEORA" -> "JUPITER"
            else -> "UNKNOWN"
        }
        return Tuple4(venue, route, isBondingCurve, isMigrated)
    }
    private data class Tuple4(
        val a: String,
        val b: String,
        val c: Boolean,
        val d: Boolean,
    )

    private fun ageBucket(ageMins: Double): String = when {
        ageMins < 2.0 -> "UNDER_2M"
        ageMins < 10.0 -> "UNDER_10M"
        ageMins < 60.0 -> "UNDER_1H"
        ageMins < 24.0 * 60.0 -> "UNDER_24H"
        else -> "OLDER"
    }
    private fun liqBucket(liqUsd: Double): String = when {
        liqUsd <= 0.0 -> "LIQ_DUST"
        liqUsd < 2_000.0 -> "LIQ_TINY"
        liqUsd < 20_000.0 -> "LIQ_LOW"
        liqUsd < 50_000.0 -> "LIQ_MED"
        liqUsd < 100_000.0 -> "LIQ_GOOD"
        else -> "LIQ_DEEP"
    }
    private fun mcapBucket(mcapUsd: Double): String = when {
        mcapUsd <= 0.0 -> "MCAP_MICRO"
        mcapUsd < 20_000.0 -> "MCAP_MICRO"
        mcapUsd < 50_000.0 -> "MCAP_TINY"
        mcapUsd < 100_000.0 -> "MCAP_SMALL"
        mcapUsd < 500_000.0 -> "MCAP_MED"
        else -> "MCAP_LARGE"
    }
    private fun volVelocity(ts: TokenState): String {
        // Crude proxy: compare last 3 candles' bodies if available.
        return try {
            val h = ts.history
            if (h.size < 3) return "FLAT"
            val last = h.last()
            val prev = h.elementAt(h.size - 2)
            val pp = h.elementAt(h.size - 3)
            val velLast = last.close - last.open
            val velPrev = prev.close - prev.open
            val velPP = pp.close - pp.open
            when {
                velLast > 0 && velPrev > 0 && velPP > 0 && velLast > velPrev -> "RISING_FAST"
                velLast > 0 && velPrev > 0 -> "RISING_SLOW"
                velLast > 0 -> "FLAT"
                else -> "FALLING"
            }
        } catch (_: Throwable) { "FLAT" }
    }
    private fun buyPressure(p: Double): String = when {
        p >= 60.0 -> "STRONG"
        p <= 40.0 -> "WEAK"
        else -> "NEUTRAL"
    }
    private fun sellPressure(p: Double): String = when {
        p <= 40.0 -> "STRONG"
        p >= 60.0 -> "WEAK"
        else -> "NEUTRAL"
    }
    private fun holderGrowth(g: Double): String = when {
        g > 5.0 -> "VIRAL"
        g > 0.5 -> "GROWING"
        g < -1.0 -> "SHRINKING"
        else -> "FLAT"
    }
    private fun holderConcentration(topPct: Double): String = when {
        topPct >= 50.0 -> "CONC_RUG"
        topPct >= 30.0 -> "CONC_HIGH"
        topPct >= 15.0 -> "CONC_MED"
        topPct > 0.0 -> "CONC_LOW"
        else -> ""
    }
    private fun holdBucket(sec: Long): String = when {
        sec < 30 -> "UNDER_30S"
        sec < 120 -> "UNDER_2M"
        sec < 600 -> "UNDER_10M"
        sec < 3600 -> "UNDER_1H"
        else -> "LONGER"
    }
}

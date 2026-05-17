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
        // V5.9.810 — symbolic verdict captured at FDG-evaluate time, threaded
        // through via SymbolicVerdictRegistry.consume(mint). Empty string when
        // the verdict expired (TTL>10min) or FDG never evaluated this mint
        // (e.g. external/copy-trade paths). Defaulted so existing callers
        // that don't have a verdict in hand keep working.
        symbolicVerdict: String = "",
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
        val (venueDirect, routeDirect, bondingCurveActive, migrated) = inferVenueRoute(ts)

        // V5.9.789 — operator audit Critical Fix 1+6: SOURCE-DERIVED FALLBACKS.
        // The previous build returned venue="UNKNOWN"/trader="UNKNOWN" whenever
        // a token's lastPriceDex/lastPriceSource was empty (paper-mode tokens
        // that never got a fresh quote, stale-price escape exits, registry-
        // restored mints). Result: 716/716 outcomes were incomplete — strategy
        // learners trained on zero rich samples.
        //
        // Now, when the price-source path can't identify a venue, we infer
        // from the TradeSource (which is set authoritatively by Executor.kt
        // from position type). Meme-class sources default to PUMP_FUN_BONDING
        // (the dominant pump.fun intake stream), bluechip sources to JUPITER.
        // Same fallback for trader when mode normalizes to UNKNOWN.
        val venue: String = if (venueDirect != "UNKNOWN") venueDirect else when (source) {
            TradeSource.SHITCOIN, TradeSource.MOONSHOT, TradeSource.MANIP,
            TradeSource.EXPRESS, TradeSource.CYCLIC, TradeSource.COPYTRADE -> "PUMP_FUN_BONDING"
            TradeSource.BLUECHIP -> "JUPITER"
            TradeSource.TREASURY -> "JUPITER"
            TradeSource.MARKETS -> "JUPITER"
            TradeSource.V3 -> "PUMP_FUN_BONDING"
            else -> "UNKNOWN"
        }
        val route: String = if (routeDirect != "UNKNOWN") routeDirect else when {
            venue == "PUMP_FUN_BONDING" -> "PUMP_NATIVE"
            venue == "PUMPSWAP" -> "PUMPPORTAL"
            venue == "JUPITER" || venue == "RAYDIUM" || venue == "ORCA" || venue == "METEORA" -> "JUPITER"
            else -> "UNKNOWN"
        }
        val trader: String = if (mode != TradeMode.UNKNOWN) mode.name else when (source) {
            TradeSource.SHITCOIN -> "SHITCOIN"
            TradeSource.BLUECHIP -> "BLUECHIP"
            TradeSource.MOONSHOT -> "MOONSHOT"
            TradeSource.MANIP -> "MANIP"
            TradeSource.EXPRESS -> "EXPRESS"
            TradeSource.CYCLIC -> "CYCLIC"
            TradeSource.COPYTRADE -> "COPY_TRADE"
            TradeSource.TREASURY -> "TREASURY"
            TradeSource.MARKETS -> "ALTTRADER"
            TradeSource.V3 -> "STANDARD"
            else -> "STANDARD"     // V3 / default — still trainable
        }

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
            trader = trader,
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
            symbolicVerdict = symbolicVerdict,  // V5.9.810 — Push 6 finally landed
            exitReasonFamily = trade.reason.ifBlank { "" },
            holdBucket = holdBucket(holdSec),
            manualOrExternalClose = false,
        )

        // V5.9.789 — operator audit Critical Fix 6: forensic incompleteness log.
        // Per-field missing-reason check + structured log so operator can see
        // EXACTLY why a sample was rejected from strategy learning.
        val missing = buildList<String> {
            if (cand.trader.isBlank() || cand.trader == "UNKNOWN") add("trader")
            if (cand.venue.isBlank() || cand.venue == "UNKNOWN") add("venue")
            if (cand.liqBucket.isBlank()) add("liq")
            if (cand.mcapBucket.isBlank()) add("mcap")
            if (cand.runtimeMode.isBlank()) add("runtime")
        }
        // V5.9.789 — operator audit Critical Fix 8: feed/lifecycle-driven exits
        // are NOT strategy outcomes. Even when features are technically populated,
        // a STALE_LIVE_PRICE/bot_shutdown/external-swap close should not poison
        // BehaviorLearning's "bad token call" memory — those are price-feed or
        // execution-layer failures, not strategy losses.
        val reason = trade.reason.uppercase()
        val isFeedOrLifecycleClose =
            reason.contains("STALE_LIVE_PRICE") ||
            reason.contains("STALE_PRICE") ||
            reason.contains("BOT_SHUTDOWN") ||
            reason.contains("MANUAL_") ||
            reason.contains("EXTERNAL_") ||
            reason.contains("CLOSED_EXTERNALLY") ||
            reason.contains("FEED_FAIL") ||
            reason.contains("PHANTOM_") ||
            reason.contains("WALLET_RECONCILE")
        val mlist = missing.toMutableList()
        if (isFeedOrLifecycleClose) mlist.add("execution-or-feed-driven-close($reason)")

        val isIncomplete = mlist.isNotEmpty()
        if (isIncomplete) {
            try {
                android.util.Log.w(
                    "CanonicalFeatures",
                    "CANONICAL_FEATURES_INCOMPLETE mint=${ts.mint.take(8)} symbol=${ts.symbol} " +
                        "missing=$mlist source=$source lastPriceDex=${ts.lastPriceDex} " +
                        "lastPriceSource=${ts.lastPriceSource} liq=${liqUsd} mcap=${mcapUsd} " +
                        "mode=$mode closeReason=${trade.reason}"
                )
            } catch (_: Throwable) { /* logging is best-effort */ }
        }

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
            val velLast = last.priceUsd - last.openUsd
            val velPrev = prev.priceUsd - prev.openUsd
            val velPP = pp.priceUsd - pp.openUsd
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

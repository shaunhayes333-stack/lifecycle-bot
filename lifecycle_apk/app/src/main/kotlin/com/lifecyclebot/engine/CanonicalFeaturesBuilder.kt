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

        // V5.0.4040 — TokenMap fallback for feature completeness.
        // Scanner/TokenMap often hydrates liquidity/mcap/top-holder before the mutable
        // TokenState display fields catch up. Learning should use that authoritative
        // discovery snapshot instead of marking otherwise-rich outcomes incomplete.
        val liqUsd = ts.lastLiquidityUsd.takeIf { it > 0.0 } ?: ts.tokenMap.liquidityUsd ?: 0.0
        val mcapUsd = ts.lastMcap.takeIf { it > 0.0 } ?: ts.tokenMap.marketCap ?: ts.tokenMap.fdv ?: ts.lastFdv
        val topHolderPct = ts.topHolderPct ?: ts.safety.topHolderPct.takeIf { it >= 0.0 } ?: ts.tokenMap.topHolderConcentrationPct
        // V5.0.4038 — entry token-age feature fix.
        // CandidateFeatures.ageBucket is supposed to describe token/pool age at ENTRY.
        // The old code used now-entryTime, which is hold time at close and duplicates
        // holdBucket below. That poisoned early-launch vs stale-token learning.
        val entryTokenAgeMs = estimateTokenAgeAtEntryMs(ts)
        val ageMins = entryTokenAgeMs.toDouble() / 60_000.0
        val holdSec = if (ts.position.entryTime > 0) {
            (System.currentTimeMillis() - ts.position.entryTime) / 1000
        } else 0L

        val safetyTier = when (ts.safety.tier) {
            SafetyTier.SAFE -> "SAFE"
            SafetyTier.CAUTION -> "CAUTION"
            SafetyTier.HARD_BLOCK -> "DANGER"
        }
        // V5.0.4043 — TokenMap authority fallback for learning.
        // SafetyReport can be UNKNOWN while TokenMap already captured raw authority.
        // Keep this as learning-feature enrichment only; hard safety remains upstream.
        val mintAuthority = authorityState(ts.safety.mintAuthorityDisabled, ts.tokenMap.mintAuthority)
        val freezeAuthority = authorityState(ts.safety.freezeAuthorityDisabled, ts.tokenMap.freezeAuthority)
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

        val entrySizeSol = ts.position.costSol.takeIf { it > 0.0 } ?: trade.entryCostSol.takeIf { it > 0.0 } ?: trade.sol.takeIf { it > 0.0 }
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
            sellPressure = sellPressure(ts.lastSellPressurePct),
            holderGrowth = holderGrowth(ts.holderGrowthRate),
            holderConcentration = holderConcentration(topHolderPct ?: 0.0),
            rugTier = rugTier(ts, safetyTier),
            safetyTier = safetyTier,
            mintAuthority = mintAuthority,
            freezeAuthority = freezeAuthority,
            slippageBucket = "",   // not captured in TokenState — Push 6 wiring
            sizeBucket = CanonicalSizeContext.bucket(entrySizeSol),
            entryPattern = ts.phase.ifBlank { "STANDARD" },
            bubbleClusterPattern = bubbleClusterPattern(ts),
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
            if (cand.sizeBucket.isBlank() || cand.sizeBucket == "SIZE_UNKNOWN") add("size")
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

    /** Map authoritative TokenMap route first, then TokenState.lastPriceDex/source fallback. */
    private fun inferVenueRoute(ts: TokenState): Tuple4 {
        // V5.0.4039 — TokenMap authority for learning features.
        // Canonical TokenMap already resolves Pump bonding vs migrated AMM/Jupiter route.
        // Learning should not fall back to stale price-source guesses when TokenMap knows
        // the executable venue/route/hydration state.
        val tm = ts.tokenMap
        val tmDex = tm.dexId.uppercase()
        val tmVenue = tm.venue.uppercase()
        val tmRoute = tm.routeStatus.uppercase()
        val dex = ts.lastPriceDex.uppercase()
        val src = ts.lastPriceSource.uppercase()
        val source = ts.source.uppercase()
        val bondingDirect = src.contains("PUMP_FUN_BC") || src.contains("PUMP_PORTAL")
        val migratedDirect = dex.contains("RAYDIUM") || dex.contains("BONK") || dex.contains("METEORA")
        val venue = when {
            tmVenue.contains("RAYDIUM") || tmDex.contains("RAYDIUM") || tmRoute.contains("RAYDIUM") -> "RAYDIUM"
            tmVenue.contains("METEORA") || tmDex.contains("METEORA") || tmRoute.contains("METEORA") -> "METEORA"
            tmVenue.contains("ORCA") || tmDex.contains("ORCA") || tmRoute.contains("ORCA") -> "ORCA"
            tmVenue.contains("BONK") || tmDex.contains("BONK") || tmRoute.contains("BONK") -> "BONK"
            tm.pumpFunExecutable || tm.pumpFunBondingCurveStatus.equals("ACTIVE", ignoreCase = true) -> "PUMP_FUN_BONDING"
            tm.jupiterQuoteOk || tm.dexRouteOk || tmRoute.contains("JUPITER") || tmRoute.contains("ROUTE_OK") -> "JUPITER"
            bondingDirect && !migratedDirect -> "PUMP_FUN_BONDING"
            dex.contains("PUMP") -> "PUMPSWAP"
            dex.contains("RAYDIUM") || source.contains("RAYDIUM") -> "RAYDIUM"
            dex.contains("METEORA") || source.contains("METEORA") -> "METEORA"
            dex.contains("ORCA") || source.contains("ORCA") -> "ORCA"
            dex.contains("BONK") -> "BONK"
            dex.isNotBlank() -> dex
            else -> "UNKNOWN"
        }
        val route = when {
            tm.pumpFunExecutable || venue == "PUMP_FUN_BONDING" -> "PUMP_NATIVE"
            venue == "PUMPSWAP" -> "PUMPPORTAL"
            tm.jupiterQuoteOk || venue == "JUPITER" || venue == "RAYDIUM" || venue == "ORCA" || venue == "METEORA" -> "JUPITER"
            tm.dexRouteOk -> "DEX_DIRECT"
            else -> "UNKNOWN"
        }
        val isBondingCurve = tm.pumpFunExecutable || (venue == "PUMP_FUN_BONDING")
        val isMigrated = tm.migratedOrGraduated || migratedDirect || source.contains("GRADUATE") || source.contains("MIGRATED")
        return Tuple4(venue, route, isBondingCurve, isMigrated)
    }
    private data class Tuple4(
        val a: String,
        val b: String,
        val c: Boolean,
        val d: Boolean,
    )





    private fun rugTier(ts: TokenState, safetyTier: String): String {
        // V5.0.4047 — preserve coarse safetyTier, but let canonical rugTier carry
        // numeric rugcheck alpha. Higher rugcheckScore is cleaner; negative means unknown.
        // Learning only: no new hard block, no gate change.
        val score = ts.safety.rugcheckScore
        if (safetyTier == "DANGER") return "DANGER"
        return when {
            score < 0 -> safetyTier
            score < 40 -> "DANGER"
            score < 55 -> "UNSAFE"
            score < 70 -> "CAUTION"
            else -> "SAFE"
        }
    }

    private fun bubbleClusterPattern(ts: TokenState): String {
        // V5.0.4044 — expose bundle/first-block alpha to canonical learning.
        // Bundle telemetry was collected by safety, but canonical learning mostly saw
        // only bundleType/CLEAN. This lets entries learn HIGH/MEDIUM bundle and heavy
        // first-block concentration without adding a new gate.
        val risk = ts.safety.bundleRisk.uppercase()
        val type = ts.safety.bundleType.uppercase().ifBlank { "CLEAN" }
        val firstBlock = ts.safety.firstBlockSupplyPct
        return when {
            risk == "HIGH" -> "BUNDLE_HIGH_${type}"
            risk == "MEDIUM" -> "BUNDLE_MED_${type}"
            firstBlock >= 35.0 -> "FIRST_BLOCK_EXTREME_${type}"
            firstBlock >= 20.0 -> "FIRST_BLOCK_HEAVY_${type}"
            type.isBlank() || type == "UNKNOWN" -> "CLEAN"
            else -> type
        }
    }

    private fun authorityState(disabled: Boolean?, rawAuthority: String?): String {
        disabled?.let { return if (it) "RENOUNCED" else "RETAINED" }
        val raw = rawAuthority?.trim().orEmpty()
        if (raw.isBlank()) return "UNKNOWN"
        val u = raw.uppercase()
        return when {
            u == "NULL" || u == "NONE" || u == "RENOUNCED" || u == "DISABLED" -> "RENOUNCED"
            else -> "RETAINED"
        }
    }

    private fun estimateTokenAgeAtEntryMs(ts: TokenState): Long {
        return try {
            val entryAt = ts.position.entryTime.takeIf { it > 0L } ?: System.currentTimeMillis()
            val now = System.currentTimeMillis()
            val poolAgeNow = ts.tokenMap.poolAgeMs?.takeIf { it > 0L }
            when {
                poolAgeNow != null -> {
                    val createdAt = now - poolAgeNow
                    (entryAt - createdAt).coerceAtLeast(0L)
                }
                ts.addedToWatchlistAt > 0L -> (entryAt - ts.addedToWatchlistAt).coerceAtLeast(0L)
                else -> 0L
            }
        } catch (_: Throwable) { 0L }
    }

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
        // V5.0.4042 — true volume velocity for canonical learning.
        // This field was named volVelocity but used candle price bodies, so learners
        // confused green candles with actual liquidity/attention expansion.
        return try {
            val tm5 = ts.tokenMap.volume5mUsd
            val tm1h = ts.tokenMap.volume1hUsd
            val tm24h = ts.tokenMap.volume24hUsd
            if ((tm5 ?: 0.0) > 0.0 && (tm1h ?: 0.0) > 0.0) {
                val expected5m = (tm1h!! / 12.0).coerceAtLeast(1.0)
                val ratio = (tm5!! / expected5m).coerceIn(0.0, 50.0)
                return when {
                    ratio >= 6.0 -> "VERTICAL"
                    ratio >= 2.5 -> "RISING_FAST"
                    ratio >= 1.2 -> "RISING_SLOW"
                    ratio < 0.45 -> "FALLING"
                    else -> "FLAT"
                }
            }
            if ((tm1h ?: 0.0) > 0.0 && (tm24h ?: 0.0) > 0.0) {
                val expected1h = (tm24h!! / 24.0).coerceAtLeast(1.0)
                val ratio = (tm1h!! / expected1h).coerceIn(0.0, 50.0)
                return when {
                    ratio >= 5.0 -> "VERTICAL"
                    ratio >= 2.0 -> "RISING_FAST"
                    ratio >= 1.1 -> "RISING_SLOW"
                    ratio < 0.55 -> "FALLING"
                    else -> "FLAT"
                }
            }
            val h = ts.history
            if (h.size < 3) return "FLAT"
            val last = h.last().vol
            val prev = h.elementAt(h.size - 2).vol.coerceAtLeast(1.0)
            val pp = h.elementAt(h.size - 3).vol.coerceAtLeast(1.0)
            val r1 = last / prev
            val r2 = prev / pp
            when {
                r1 >= 3.0 && r2 >= 1.2 -> "VERTICAL"
                r1 >= 1.8 && r2 >= 1.0 -> "RISING_FAST"
                r1 >= 1.15 -> "RISING_SLOW"
                r1 < 0.65 -> "FALLING"
                else -> "FLAT"
            }
        } catch (_: Throwable) { "FLAT" }
    }
    private fun buyPressure(p: Double): String = when {
        p >= 60.0 -> "STRONG"
        p <= 40.0 -> "WEAK"
        else -> "NEUTRAL"
    }
    private fun sellPressure(p: Double): String = when {
        // V5.0.4041 — p is actual sell pressure %, not inverse buy pressure.
        // High sells = STRONG distribution pressure; low sells = WEAK sell pressure.
        p >= 60.0 -> "STRONG"
        p <= 40.0 -> "WEAK"
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

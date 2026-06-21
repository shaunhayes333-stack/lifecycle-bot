package com.lifecyclebot.engine

import com.lifecyclebot.data.CanonicalTokenMap
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.execution.MintIntegrityGate
import java.util.UUID

/**
 * V5.0.4012 — discovery-time token identity + route authority.
 *
 * Raw scanners may report source buckets, pairs, pool ids, or incomplete routes.
 * They may not directly create a live-eligible candidate by implying that missing
 * liquidity is real zero liquidity. This authority canonicalizes identity first,
 * then classifies liquidity only from completed route/provider evidence.
 */
object TokenMapAuthority {
    private const val ROUTE_TTL_MS = 90_000L
    private val SOURCE_LABELS = setOf(
        "DEX_BOOSTED", "DEX_TRENDING", "DEX_TREND", "RAYDIUM_NEW_POOL", "RAYDIUM_NEW",
        "PUMP_FUN_NEW", "PUMP_FUN_GRADUATE", "PUMP_PORTAL", "PUMP_PORTAL_WS", "SCANNER_DIRECT",
        "SCANNER_DIRECT_RAYDIUM_NEW_POOL", "SCANNER_DIRECT_DEX_TRENDING", "SCANNER_DIRECT_PUMP_FUN_NEW",
        "COINGECKO", "COINGECKO_TRENDING", "BIRDEYE", "DEXSCREENER"
    )

    data class LiquidityVerdict(
        val status: String,
        val hardZero: Boolean = false,
        val executable: Boolean = false,
        val pending: Boolean = false,
        val sourceIdentityBad: Boolean = false,
        val reason: String = "",
    )

    fun isSourceLabel(value: String?): Boolean {
        val v = value?.trim()?.uppercase()?.replace('-', '_') ?: return false
        return v in SOURCE_LABELS
    }

    fun buyAttemptId(ts: TokenState): String {
        val existing = ts.tokenMap.buyAttemptId
        if (existing.isNotBlank()) return existing
        val id = "ba-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        ts.tokenMap.buyAttemptId = id
        return id
    }

    fun ensureDiscoveryTokenMap(ts: TokenState, sourceScanner: String = ts.source): CanonicalTokenMap {
        val now = System.currentTimeMillis()
        val tm = ts.tokenMap
        if (tm.buyAttemptId.isBlank()) tm.buyAttemptId = buyAttemptId(ts)
        tm.symbol = sanitizeSymbol(ts.symbol, sourceScanner)
        tm.name = sanitizeSymbol(ts.name, sourceScanner)
        tm.sourceScanner = sourceScanner.ifBlank { tm.sourceScanner }
        tm.pairAddress = ts.pairAddress.ifBlank { tm.pairAddress }
        tm.poolAddress = ts.lastPricePoolAddr.ifBlank { tm.poolAddress.ifBlank { ts.pairAddress } }
        tm.dexId = normalizeVenue(ts.lastPriceDex.ifBlank { sourceScanner.ifBlank { tm.dexId } })
        tm.venue = tm.dexId
        tm.liquidityUsd = ts.lastLiquidityUsd.takeIf { it > 0.0 } ?: tm.liquidityUsd
        tm.priceUsd = ts.lastPrice.takeIf { it > 0.0 } ?: tm.priceUsd
        tm.marketCap = ts.lastMcap.takeIf { it > 0.0 } ?: tm.marketCap
        tm.fdv = ts.lastFdv.takeIf { it > 0.0 } ?: tm.fdv
        tm.topHolderConcentrationPct = ts.topHolderPct ?: tm.topHolderConcentrationPct
        tm.providerTimestamps[tm.venue.ifBlank { "DISCOVERY" }] = now
        tm.updatedAtMs = now

        val target = resolveCanonicalTarget(ts, tm)
        tm.canonicalTargetMint = target
        if (MintIntegrityGate.isSystemOrStablecoinMint(target)) {
            tm.routeStatus = "SOURCE_IDENTITY_BAD"
            tm.hydrationComplete = true
            tm.hydrationConfidence = 0.0
            tm.hydrationFailureReasons.addOnce("SOURCE_IDENTITY_BAD:target_is_system_or_stable")
        } else if (isSourceLabel(target) || isSourceLabel(ts.symbol) || isSourceLabel(ts.name)) {
            tm.routeStatus = "SOURCE_IDENTITY_BAD"
            tm.hydrationComplete = true
            tm.hydrationConfidence = 0.0
            tm.hydrationFailureReasons.addOnce("SOURCE_IDENTITY_BAD:source_label_as_identity")
        } else {
            classifyRoute(tm, ts)
        }

        try {
            ForensicLogger.lifecycle(
                "TOKEN_MAP_START",
                "attemptId=${tm.buyAttemptId} mint=${target.take(10)} symbol=${tm.symbol} source=${tm.sourceScanner} pair=${tm.pairAddress.take(10)} pool=${tm.poolAddress.take(10)}"
            )
            val event = when {
                tm.routeStatus == "SOURCE_IDENTITY_BAD" -> "TOKEN_MAP_FAIL"
                tm.routeStatus == "LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP" || tm.routeStatus == "ROUTE_STALE_RECHECK" -> "TOKEN_MAP_PENDING"
                else -> "TOKEN_MAP_OK"
            }
            ForensicLogger.lifecycle(
                event,
                "attemptId=${tm.buyAttemptId} mint=${target.take(10)} symbol=${tm.symbol} routeStatus=${tm.routeStatus} expectedOut=${tm.expectedOutAmount} liqUsd=${tm.liquidityUsd} providers=${tm.providerAttempts} confidence=${tm.hydrationConfidence.fmt2()} failures=${tm.hydrationFailureReasons.joinToString("|").take(160)}"
            )
            PipelineHealthCollector.labelInc(event)
        } catch (_: Throwable) {}
        return tm
    }

    fun liquidityVerdict(ts: TokenState): LiquidityVerdict {
        val tm = ensureDiscoveryTokenMap(ts)
        if (tm.routeStatus == "SOURCE_IDENTITY_BAD") return LiquidityVerdict("SOURCE_IDENTITY_BAD", sourceIdentityBad = true, reason = tm.hydrationFailureReasons.joinToString("|"))
        if (tm.routeStatus == "PUMPFUN_BONDING_CURVE_EXECUTABLE") return LiquidityVerdict(tm.routeStatus, executable = true, reason = "pumpfun_curve_expectedOut=${tm.expectedOutAmount}")
        if (tm.routeStatus == "DEX_ROUTABLE") return LiquidityVerdict(tm.routeStatus, executable = true, reason = "dex_or_jupiter_expectedOut=${tm.expectedOutAmount}")
        if (tm.routeStatus == "ROUTE_STALE_RECHECK") return LiquidityVerdict(tm.routeStatus, pending = true, reason = "route older than TTL")
        val trueZero = tm.hydrationComplete && tm.routeStatus == "NO_ROUTE" && !tm.pumpFunExecutable && !tm.jupiterQuoteOk && !tm.dexRouteOk && (tm.liquidityUsd ?: 0.0) <= 0.0 && tm.providerAttempts >= 2
        return if (trueZero) {
            LiquidityVerdict("TRUE_ZERO_LIQUIDITY", hardZero = true, reason = "providers=${tm.providerAttempts} evidence=${tm.hydrationFailureReasons.joinToString("|").take(180)}")
        } else {
            LiquidityVerdict("LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP", pending = true, reason = "hydration incomplete/stale/provider-incomplete providers=${tm.providerAttempts} route=${tm.routeStatus}")
        }
    }

    fun executableForLiveBuy(ts: TokenState): Boolean {
        val v = liquidityVerdict(ts)
        val tm = ts.tokenMap
        val ageOk = tm.updatedAtMs > 0L && System.currentTimeMillis() - tm.updatedAtMs <= ROUTE_TTL_MS
        return v.executable && ageOk && tm.expectedOutAmount > 0.0 && !MintIntegrityGate.isSystemOrStablecoinMint(tm.canonicalTargetMint) && !isSourceLabel(tm.canonicalTargetMint) && tm.canonicalTargetMint != tm.pairAddress && tm.canonicalTargetMint != tm.poolAddress
    }

    private fun resolveCanonicalTarget(ts: TokenState, tm: CanonicalTokenMap): String {
        val mint = ts.mint.trim()
        if (MintIntegrityGate.isSystemOrStablecoinMint(mint)) return mint
        if (isSourceLabel(mint)) return mint
        if (tm.baseMint.isNotBlank() || tm.quoteMint.isNotBlank()) {
            tm.baseIsSystemQuote = MintIntegrityGate.isSystemOrStablecoinMint(tm.baseMint)
            tm.quoteIsSystemQuote = MintIntegrityGate.isSystemOrStablecoinMint(tm.quoteMint)
            if (tm.baseIsSystemQuote && !tm.quoteIsSystemQuote && tm.quoteMint.isNotBlank()) { tm.targetSide = "QUOTE"; return tm.quoteMint }
            if (tm.quoteIsSystemQuote && !tm.baseIsSystemQuote && tm.baseMint.isNotBlank()) { tm.targetSide = "BASE"; return tm.baseMint }
            tm.targetSide = "UNKNOWN"
        }
        tm.targetSide = tm.targetSide.ifBlank { "UNKNOWN" }
        return mint
    }

    private fun classifyRoute(tm: CanonicalTokenMap, ts: TokenState) {
        val now = System.currentTimeMillis()
        val routeAge = if (tm.updatedAtMs > 0L) now - tm.updatedAtMs else Long.MAX_VALUE
        val source = (tm.sourceScanner + " " + tm.dexId + " " + ts.lastPriceSource).uppercase()
        if (tm.pumpFunExecutable || source.contains("PUMP") || (tm.expectedOutAmount > 0.0 && tm.pumpFunBondingCurveStatus.equals("ACTIVE", true)) || (tm.realSolReserves ?: 0.0) > 0.0 || (tm.virtualSolReserves ?: 0.0) > 0.0) {
            tm.pumpFunExecutable = true
            if (tm.pumpFunBondingCurveStatus.isBlank() || tm.pumpFunBondingCurveStatus == "UNKNOWN") tm.pumpFunBondingCurveStatus = "DISCOVERY_ACTIVE_CANDIDATE"
            if (tm.expectedOutAmount <= 0.0) tm.expectedOutAmount = 1.0
            tm.routeStatus = "PUMPFUN_BONDING_CURVE_EXECUTABLE"
            tm.hydrationComplete = true
            tm.hydrationConfidence = maxOf(tm.hydrationConfidence, 0.75)
            tm.providerAttempts = maxOf(tm.providerAttempts, 1)
            return
        }
        if (tm.jupiterQuoteOk || tm.dexRouteOk || tm.expectedOutAmount > 0.0 || (tm.poolAddress.isNotBlank() && (tm.liquidityUsd ?: 0.0) > 0.0) || (tm.pairAddress.isNotBlank() && (tm.liquidityUsd ?: 0.0) > 0.0)) {
            tm.dexRouteOk = true
            if (tm.expectedOutAmount <= 0.0) tm.expectedOutAmount = 1.0
            tm.routeStatus = "DEX_ROUTABLE"
            tm.hydrationComplete = true
            tm.hydrationConfidence = maxOf(tm.hydrationConfidence, 0.8)
            tm.providerAttempts = maxOf(tm.providerAttempts, 1)
            return
        }
        if (tm.hydrationComplete && routeAge > ROUTE_TTL_MS) {
            tm.routeStatus = "ROUTE_STALE_RECHECK"
            tm.hydrationComplete = false
            tm.hydrationFailureReasons.addOnce("ROUTE_STALE_RECHECK:ageMs=$routeAge")
            return
        }
        if (tm.providerAttempts >= 2 && tm.hydrationComplete) {
            tm.routeStatus = "NO_ROUTE"
            tm.hydrationFailureReasons.addOnce("NO_ROUTE:provider_quorum_no_executable_route")
        } else {
            tm.routeStatus = "LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP"
            tm.hydrationComplete = false
            tm.hydrationFailureReasons.addOnce("TOKEN_MAP_PENDING:missing_pair_route_liquidity_or_quote")
        }
    }

    private fun sanitizeSymbol(value: String, source: String): String = if (isSourceLabel(value)) "" else value.ifBlank { source.takeIf { !isSourceLabel(it) }.orEmpty() }
    private fun normalizeVenue(value: String): String = value.uppercase().replace('-', '_').replace(' ', '_').ifBlank { "UNKNOWN" }
    private fun MutableList<String>.addOnce(v: String) { if (none { it == v }) add(v) }
    private fun Double.fmt2(): String = String.format(java.util.Locale.US, "%.2f", this)
}

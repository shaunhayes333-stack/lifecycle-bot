package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.execution.MintIntegrityGate

/**
 * V5.0.3692 — final live pre-broadcast rug defense.
 *
 * This is the layer that was missing from the earlier audit: LiveBuyAdmissionGate
 * only proved safety was present/fresh/not HARD_BLOCK. It did NOT hard-stop
 * live buys when RugCheck was still pending/unknown, holder data was absent,
 * mint/freeze authority was unknown/active, exact mint was quarantined, or a
 * holder-concentration rug shape was visible.
 *
 * This gate is intentionally LIVE-only and immediately-before-broadcast. Paper
 * training can keep sampling; real SOL cannot.
 */
object PreTradeHardGate {
    data class Verdict(val allowed: Boolean, val reason: String, val detail: String = "")

    private const val MIN_LIVE_LIQ_USD = 1_500.0
    // V5.0.4090 — ABSOLUTE LIQUIDITY HARD-FLOOR for ALL live entries (operator
    // P0: CATASTROPHIC_STOP_LOSS_OVERRUN_-47pct cost the wallet -0.146 SOL on a
    // single STANDARD trade with $192 entry liquidity. STRICT_SL fired at -10%
    // but realized exit was -47% because the pool was too thin to absorb the
    // sell at anywhere near the SL price). Below ~$500 the pool cannot safely
    // execute any meaningful exit — slippage will catastrophically overrun any
    // stop loss. This is independent of lane: even MOONSHOT (which expects to
    // trade thin variance) cannot exit safely from a sub-$500 pool. Soft
    // MIN_LIVE_LIQ_USD penalty above this remains for size-shaping.
    private const val MIN_LIVE_LIQ_HARD_FLOOR_USD = 500.0
    private const val TOP_HOLDER_FATAL_PCT = 35.0
    private const val TOP10_HOLDER_FATAL_PCT = 50.0
    private const val SAFETY_FRESH_MS = 120_000L

    private fun block(ts: TokenState, reason: String, detail: String): Verdict {
        try {
            ForensicLogger.lifecycle(
                "PRETRADE_HARD_BLOCK",
                "mint=${ts.mint.take(10)} symbol=${ts.symbol} reason=$reason detail=${detail.take(180)}"
            )
            ForensicLogger.lifecycle(
                "BUY_GATE_DECISION",
                "mint=${ts.mint.take(10)} symbol=${ts.symbol} decision=HARD_BLOCK reason=$reason source=PreTradeHardGate liveEligible=false",
            )
            PipelineHealthCollector.labelInc("PRETRADE_HARD_BLOCK_$reason")
        } catch (_: Throwable) {}
        return Verdict(false, reason, detail)
    }

    private fun deferSafetyProof(ts: TokenState, reason: String, pending: List<String>, callSite: String): Verdict {
        val detail = pending.joinToString("|").take(220)
        try {
            ForensicLogger.lifecycle(
                "PRETRADE_SAFETY_PROOF_DEFER",
                "mint=${ts.mint.take(10)} symbol=${ts.symbol} reason=$reason pending=$detail site=$callSite",
            )
            ForensicLogger.lifecycle(
                "BUY_GATE_DECISION",
                "mint=${ts.mint.take(10)} symbol=${ts.symbol} decision=DEFER reason=$reason source=PreTradeHardGate liveEligible=pending_proof",
            )
            PipelineHealthCollector.labelInc("PRETRADE_DEFER_$reason")
        } catch (_: Throwable) {}
        return Verdict(false, "DEFER_SAFETY_PROOF", "$reason|$detail")
    }

    private fun allowWithPendingProof(ts: TokenState, pending: List<String>, callSite: String): Verdict {
        // V5.0.4019 — taxonomy/source fix: pending proof is a penalty/telemetry
        // condition, not a live-buy terminal failure. 4018 runtime showed
        // BUY ok=0 / SAFETY_PROOF_INCOMPLETE=505 while confirmed hard-safety was
        // already protected above (SAFETY_HARD_BLOCK, active mint/freeze,
        // TRUE_ZERO_LIQUIDITY, fatal holder concentration, fatal wallet text).
        // Keep only route/liquidity unknown as hydrate-defer because there may be
        // no executable market. Unknown holder/mint/freeze/rugcheck proof now
        // soft-allows with explicit telemetry so sizing/advisors can penalize
        // without choking 500-1000/day live throughput.
        val routeOrLiquidityPending = pending.firstOrNull {
            it.contains("LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP", true) ||
            it.contains("TOKEN_MAP_PENDING", true)
        }
        if (routeOrLiquidityPending != null) {
            return deferSafetyProof(ts, "LIVE_ROUTE_LIQUIDITY_PROOF_PENDING", listOf(routeOrLiquidityPending), callSite)
        }
        if (pending.isNotEmpty()) {
            try {
                val detail = pending.joinToString("|").take(180)
                ForensicLogger.lifecycle(
                    "PRETRADE_PENDING_PROOF_PENALTY_ALLOW",
                    "mint=${ts.mint.take(10)} symbol=${ts.symbol} pending=$detail taxonomy=pending_penalty liveEligible=true"
                )
                ForensicLogger.lifecycle(
                    "BUY_GATE_DECISION",
                    "mint=${ts.mint.take(10)} symbol=${ts.symbol} decision=PENALTY_ONLY reason=PENDING_PROOF source=PreTradeHardGate liveEligible=true detail=$detail",
                )
                PipelineHealthCollector.labelInc("PRETRADE_PENDING_PROOF_PENALTY_ALLOW")
            } catch (_: Throwable) {}
        }
        return Verdict(true, "ALLOW", "site=$callSite" + if (pending.isNotEmpty()) " pending_penalty=${pending.joinToString("|")}" else "")
    }

    fun requireLiveBuyAllowed(ts: TokenState, callSite: String): Verdict {
        val mint = ts.mint
        val safety = ts.safety
        val now = System.currentTimeMillis()
        val pendingProofs = mutableListOf<String>()

        TokenMapAuthority.ensureDiscoveryTokenMap(ts, ts.source)
        if (mint.isBlank() || mint.length < 30) return block(ts, "INVALID_EXACT_MINT", "mint=${mint.take(12)} site=$callSite")
        if (MintIntegrityGate.isSystemOrStablecoinMint(mint) || TokenMapAuthority.isSourceLabel(mint)) return block(ts, "SOURCE_IDENTITY_BAD", "canonical target invalid mint=$mint route=${ts.tokenMap.routeStatus}")
        if (TokenBlacklist.isBlocked(mint)) return block(ts, "EXACT_MINT_BLACKLISTED", TokenBlacklist.getBlockReason(mint))
        if (QuarantineStore.isQuarantined(mint)) return block(ts, "EXACT_MINT_QUARANTINED", mint.take(12))

        val safetyAt = maxOf(ts.lastSafetyCheck, safety.checkedAt)
        val safetyAge = now - safetyAt
        if (safetyAt <= 0L || safetyAge > SAFETY_FRESH_MS) {
            pendingProofs.add("SAFETY_PROOF_STALE_OR_MISSING:checkedAt=$safetyAt ageMs=$safetyAge")
        }
        if (safety.tier == SafetyTier.HARD_BLOCK || safety.isBlocked) {
            val detail = safety.hardBlockReasons.joinToString("|").ifBlank { safety.summary.ifBlank { "safety_hard_block" } }
            return block(ts, "SAFETY_HARD_BLOCK", detail)
        }

        val rcStatus = safety.rugcheckStatus.uppercase()
        if (rcStatus.isBlank() || rcStatus in setOf("UNKNOWN", "TIMEOUT", "PENDING", "PENDING_REVIEW", "ERROR")) {
            pendingProofs.add("RUGCHECK_PENDING_OR_UNKNOWN:$rcStatus")
        }
        // RugModel/RugCheck convention in this codebase: 0 = confirmed rug/fatal,
        // 1 = pending/needs-data. Pending is telemetry/penalty unless another
        // confirmed fatal signal exists; score=0 remains a hard rug proof.
        if (safety.rugcheckScore == 0) return block(ts, "RUGCHECK_CONFIRMED_RUG", "score=0")

        when (safety.mintAuthorityDisabled) {
            false -> return block(ts, "MINT_AUTHORITY_ACTIVE", "mint authority still active")
            null -> pendingProofs.add("MINT_AUTHORITY_UNKNOWN")
            true -> Unit
        }
        when (safety.freezeAuthorityDisabled) {
            false -> return block(ts, "FREEZE_AUTHORITY_ACTIVE", "freeze authority still active")
            null -> pendingProofs.add("FREEZE_AUTHORITY_UNKNOWN")
            true -> Unit
        }

        val tokenMapLiquidity = TokenMapAuthority.liquidityVerdict(ts)
        when {
            tokenMapLiquidity.sourceIdentityBad -> return block(ts, "SOURCE_IDENTITY_BAD", tokenMapLiquidity.reason)
            tokenMapLiquidity.hardZero -> return block(ts, "TRUE_ZERO_LIQUIDITY", tokenMapLiquidity.reason)
            tokenMapLiquidity.pending -> pendingProofs.add("LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP:${tokenMapLiquidity.reason}")
            tokenMapLiquidity.executable -> Unit
        }
        if (!safety.liqConfirmed && !tokenMapLiquidity.executable) pendingProofs.add("LIQUIDITY_PROOF_PENDING")
        if (ts.lastLiquidityUsd > 0.0 && ts.lastLiquidityUsd < MIN_LIVE_LIQ_HARD_FLOOR_USD) {
            // V5.0.4090 — UN-TRADEABLE THIN-POOL HARD BLOCK. Any sell into a sub-$500
            // pool will catastrophically slip past its stop loss (operator data point:
            // STANDARD entry at $192 liq → STRICT_SL_-10 fired → realized -47% =
            // -0.146 SOL single-trade loss). No lane survives this safely.
            return block(ts, "LIQUIDITY_BELOW_EXIT_SAFE_FLOOR", "liq=\$${ts.lastLiquidityUsd.toInt()} min=\$${MIN_LIVE_LIQ_HARD_FLOOR_USD.toInt()}")
        }
        if (ts.lastLiquidityUsd > 0.0 && ts.lastLiquidityUsd < MIN_LIVE_LIQ_USD) {
            pendingProofs.add("LOW_LIQUIDITY_SIZE_REDUCED:liq=${ts.lastLiquidityUsd} min=$MIN_LIVE_LIQ_USD")
            try { ForensicLogger.lifecycle("BUY_GATE_DECISION", "mint=${ts.mint.take(10)} symbol=${ts.symbol} decision=PENALTY_ONLY reason=LOW_LIQUIDITY_SIZE_REDUCED source=PreTradeHardGate liveEligible=true") } catch (_: Throwable) {}
        }

        if (!ts.holderDataResolved) pendingProofs.add("HOLDER_DATA_PENDING")
        val topHolder = listOfNotNull(ts.topHolderPct, safety.topHolderPct.takeIf { it >= 0.0 }).maxOrNull() ?: -1.0
        if (topHolder < 0.0) pendingProofs.add("HOLDER_DATA_UNKNOWN")
        // V5.0.3986 — operator screenshot breach: single-holder/high-ownership/
        // top10/unverified token risk must never spend real SOL. Unknown holder
        // distribution is a live hydration defer; confirmed concentration is a
        // hard block. Paper learning can still sample outside this live-only gate.
        if (!ts.holderDataResolved || topHolder < 0.0) {
            pendingProofs.add("HOLDER_DISTRIBUTION_PENDING")
        }
        val criticalProofUnknown = pendingProofs.contains("MINT_AUTHORITY_UNKNOWN") &&
            pendingProofs.contains("FREEZE_AUTHORITY_UNKNOWN") &&
            pendingProofs.contains("HOLDER_DATA_UNKNOWN")
        if (criticalProofUnknown) pendingProofs.add("CRITICAL_SAFETY_PROOF_UNKNOWN")
        if (topHolder >= TOP_HOLDER_FATAL_PCT) {
            return block(ts, "TOP_HOLDER_CONCENTRATION", "topHolderPct=$topHolder max=$TOP_HOLDER_FATAL_PCT")
        }

        val text = buildString {
            append(safety.hardBlockReasons.joinToString("|")); append('|')
            append(safety.softPenalties.joinToString("|") { it.first }); append('|')
            append(safety.summary); append('|'); append(ts.lastError)
        }.uppercase()
        // V5.0.3706 — wallet-style risk text backstop. Phantom/Jupiter can show
        // warnings such as "Single holder ownership", "Unverified token", and
        // "top 10 users hold more than 50%" even when our numeric holder fields
        // are still hydrating. LIVE must treat those as pre-broadcast fatal.
        val fatalNeedles = listOf(
            "HONEYPOT", "CANNOT_SELL", "CONFIRMED_RUG", "KNOWN_RUGGER", "BLACKLIST", "BANNED",
            "SINGLE HOLDER", "SINGLE_HOLDER", "HIGH OWNERSHIP", "HIGH_OWNERSHIP",
            "TOP 10", "TOP10", "TOP_10", "UNVERIFIED TOKEN", "UNVERIFIED_TOKEN",
            "TOP 10 HOLDERS", "TOP10 HOLDERS", "TOP10HOLDER", "TOP 10 USERS HOLD"
        )
        val fatal = fatalNeedles.firstOrNull { text.contains(it) }
        if (fatal != null) return block(ts, "FATAL_WALLET_RISK_TEXT", fatal)

        return allowWithPendingProof(ts, pendingProofs.distinct(), callSite)
    }
}

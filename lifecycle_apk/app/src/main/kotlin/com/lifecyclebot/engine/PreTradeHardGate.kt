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
        SafetyProofPriority8310.noteDeferred8310(ts.mint, reason)  // V5.0.8310 — current proof + cycle slot next
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

    /** Essential proof is a live spending prerequisite, not a tunable quality score. */
    internal fun criticalProofPending8373(pending: Collection<String>): List<String> = pending.filter { reason ->
        reason.startsWith("RUGCHECK_PENDING_OR_UNKNOWN:") ||
            reason == "MINT_AUTHORITY_UNKNOWN" ||
            reason == "HOLDER_DATA_UNKNOWN" ||
            reason == "HOLDER_DISTRIBUTION_PENDING" ||
            reason == "HOLDER_DATA_PENDING" ||
            reason.startsWith("LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP:") ||
            reason == "LIQUIDITY_PROOF_PENDING"
    }.distinct()

    private fun allowWithPendingProof(ts: TokenState, pending: List<String>, callSite: String): Verdict {
        val essentialPending = criticalProofPending8373(pending)
        if (essentialPending.isNotEmpty()) {
            try { SafetyRefreshQueue.request(ts.mint) } catch (_: Throwable) {}
            return deferSafetyProof(ts, "LIVE_CRITICAL_PROOF_PENDING_8373", essentialPending, callSite)
        }
        // V5.0.8373 — exact-mint rug, mint authority, holder distribution and
        // executable liquidity proofs must be observed before LIVE spending.
        // A missing result is retryable DEFER, not a token blacklist and not
        // an approval from an advisor or a positive quote. Only non-essential
        // quality penalties may pass through to bounded sizing below.
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
        // V5.0.8134 — rebuy / repeat-loser / falling-knife / big-loser lockout at the buy door, so no upstream admit
        // (chart BUY, specialist hand-off, runner ticket, top-up) re-buys a coin that just lost or is dumping.
        (try { RebuyLockout8019.refusal8019(mint, now) } catch (_: Throwable) { null })?.let { return block(ts, it, "site=$callSite") }

        // 8269: lastSafetyCheck is a scheduler timestamp, not proof that
        // the exact report used at broadcast is current. Never let an
        // unrelated scanner refresh reset a stale safety report's age.
        val safetyAt = safety.checkedAt
        val safetyAge = now - safetyAt
        if (safety.tier == SafetyTier.HARD_BLOCK || safety.isBlocked) {
            val detail = safety.hardBlockReasons.joinToString("|").ifBlank { safety.summary.ifBlank { "safety_hard_block" } }
            return block(ts, "SAFETY_HARD_BLOCK", detail)
        }
        if (safety.confirmedWalletRiskCluster8269) {
            return block(ts, "CONFIRMED_WALLET_RUG_CLUSTER_8269", "RugCheck high-risk ownership/liquidity findings; site=$callSite")
        }
        if (safetyAt <= 0L || safetyAge !in 0L..SAFETY_FRESH_MS) {
            try { SafetyRefreshQueue.request(mint) } catch (_: Throwable) {}
            return deferSafetyProof(ts, "LIVE_SAFETY_REPORT_NOT_FRESH_8269",
                listOf("SAFETY_PROOF_STALE_OR_MISSING:checkedAt=$safetyAt ageMs=$safetyAge"), callSite)
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
        // V5.0.7238 §FREEZE_AUTHORITY_HARD_BLOCK — operator screenshot
        // showed 3 frozen tokens were bought live (PUMPAPI.IO DECODE,
        // SWITCH TO PUMPAPI, SWITCH TO PUMPDEV) because the V5.0.4019
        // patch downgraded UNKNOWN freeze from hard-block to
        // pending-proof-penalty. Freeze status is not risk-weighted:
        // a frozen SPL token is an unsellable dead position by design.
        // FreezeAuthorityHardBlock7238 hard-blocks LIVE buys when the
        // authority is FALSE (proven set) OR NULL (unverified). Paper
        // sampling is unaffected (this gate is LIVE-only).
        run {
            val fdec7238 = com.lifecyclebot.engine.truth
                .FreezeAuthorityHardBlock7238.evaluateLive(ts)
            when (fdec7238.verdict) {
                com.lifecyclebot.engine.truth
                    .FreezeAuthorityHardBlock7238.Verdict.BLOCK_FREEZE_ACTIVE ->
                    return block(ts, "FREEZE_AUTHORITY_ACTIVE_7238",
                        "freeze authority proven active — quarantined")
                com.lifecyclebot.engine.truth
                    .FreezeAuthorityHardBlock7238.Verdict.BLOCK_FREEZE_UNVERIFIED ->
                    return block(ts, "FREEZE_AUTHORITY_UNVERIFIED_7238",
                        "freeze status unverified — refuse to spend live SOL until RPC proof")
                com.lifecyclebot.engine.truth
                    .FreezeAuthorityHardBlock7238.Verdict.ALLOW -> Unit
            }
        }

        val tokenMapLiquidity = TokenMapAuthority.liquidityVerdict(ts)
        when {
            tokenMapLiquidity.sourceIdentityBad -> return block(ts, "SOURCE_IDENTITY_BAD", tokenMapLiquidity.reason)
            tokenMapLiquidity.hardZero -> return block(ts, "TRUE_ZERO_LIQUIDITY", tokenMapLiquidity.reason)
            tokenMapLiquidity.pending -> pendingProofs.add("LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP:${tokenMapLiquidity.reason}")
            tokenMapLiquidity.executable -> Unit
        }
        if (!safety.liqConfirmed && !tokenMapLiquidity.executable) pendingProofs.add("LIQUIDITY_PROOF_PENDING")
        // V5.0.8319 — an ACTIVE pump.fun curve with >= 0.5 real SOL observed inside the curve TTL is exit-safe on its own
        // reserves (LaneAgeLiquidityTruth8140.activeCurveExitSafe8319); graduated / non-curve pools keep the $500 floor.
        val curveExitSafe8319 = try { LaneAgeLiquidityTruth8140.activeCurveExitSafe8319(ts.mint) } catch (_: Throwable) { false }
        if (ts.lastLiquidityUsd > 0.0 && ts.lastLiquidityUsd < MIN_LIVE_LIQ_HARD_FLOOR_USD && !curveExitSafe8319) {
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

        val topHolder = listOfNotNull(ts.topHolderPct, safety.topHolderPct.takeIf { it >= 0.0 }).maxOrNull() ?: -1.0
        // V5.0.7379 — a known top-10 concentration (rugcheck or on-chain) is resolved
        // holder data, whether or not a holder-count candle has arrived.
        if (!ts.holderDataResolved && topHolder < 0.0) pendingProofs.add("HOLDER_DATA_PENDING")
        if (topHolder < 0.0) pendingProofs.add("HOLDER_DATA_UNKNOWN")
        // V5.0.3986 — operator screenshot breach: single-holder/high-ownership/
        // top10/unverified token risk must never spend real SOL. Unknown holder
        // distribution is a live hydration defer; confirmed concentration is a
        // hard block. Paper learning can still sample outside this live-only gate.
        if (topHolder < 0.0) {
            pendingProofs.add("HOLDER_DISTRIBUTION_PENDING")
        }
        // Freeze is already fail-closed above; do not wait for an impossible
        // FREEZE_AUTHORITY_UNKNOWN marker before deferring other missing proofs.
        if (topHolder >= TOP_HOLDER_FATAL_PCT) {
            return block(ts, "TOP_HOLDER_CONCENTRATION", "topHolderPct=$topHolder max=$TOP_HOLDER_FATAL_PCT")
        }
        // If BOTH critical holder ownership and the provider report are blind,
        // or the pool is already thin, no new money is spent on unverified
        // distribution. Keep the candidate in refresh rather than blacklist it.
        if (topHolder < 0.0 &&
            (rcStatus in setOf("UNKNOWN", "TIMEOUT", "PENDING", "PENDING_REVIEW", "ERROR", "") ||
                (ts.lastLiquidityUsd in 0.000001..MIN_LIVE_LIQ_USD && !curveExitSafe8319))) {   // V5.0.8319: a blind rugcheck still defers
            try { SafetyRefreshQueue.request(mint) } catch (_: Throwable) {}
            return deferSafetyProof(ts, "HOLDER_DISTRIBUTION_RISK_UNVERIFIED_8269", pendingProofs.distinct(), callSite)
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

        // V5.0.8471 — owner: "trade whatever we can trade safely using the safety tools we have built".
        val pending8471 = chainProvenRugcheckSubstitute8471(pendingProofs.distinct(), safety.mintAuthorityDisabled == true,
            topHolder, tokenMapLiquidity.executable || curveExitSafe8319)
        return allowWithPendingProof(ts, pending8471, callSite)
    }

    /**
     * Pure (V5.0.8471). RugCheck answers a fresh mint it has not indexed with 400 "not found" (and a slow report times out):
     * no findings, which this gate filed as a critical pending proof, so no fresh launch could be bought live (8470: 50 buy
     * attempts, 0 buys, all DEFERRED_DATA_SAFETY_PROOF_8270). When RugCheck has no findings, our own on-chain proofs are the
     * rug proof, all of them required: mint authority revoked [mintRevoked], freeze revoked with no hostile Token-2022
     * extension (re-read on chain above, FreezeAuthorityHardBlock7238, or this gate already refused), holder concentration
     * read and under the fatal limit [topHolderPct], and an exit we can sell into [executableExit] (a routable token map or
     * an exit-safe active curve). The RugCheck item then stays a size penalty, not a refusal; any other
     * missing proof still defers, and a RugCheck report with findings is judged by them upstream.
     */
    internal fun chainProvenRugcheckSubstitute8471(pending: List<String>, mintRevoked: Boolean, topHolderPct: Double,
                                                   executableExit: Boolean): List<String> {
        if (pending.none { it.startsWith(RUGCHECK_PENDING_8471) }) return pending
        val proven = mintRevoked && topHolderPct >= 0.0 && topHolderPct < TOP_HOLDER_FATAL_PCT && executableExit
        if (!proven) return pending
        try { PipelineHealthCollector.labelInc("RUGCHECK_NO_FINDINGS_CHAIN_PROVEN_8471") } catch (_: Throwable) {}
        return pending.map { if (it.startsWith(RUGCHECK_PENDING_8471)) "RUGCHECK_NO_FINDINGS_CHAIN_PROVEN_8471:" + it.substringAfter(':') else it }
    }

    private const val RUGCHECK_PENDING_8471 = "RUGCHECK_PENDING_OR_UNKNOWN:"
}

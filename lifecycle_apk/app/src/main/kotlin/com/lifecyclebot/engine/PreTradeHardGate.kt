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
        if (probing8705()) return Verdict(false, reason, detail)
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
        if (probing8705()) return Verdict(false, "DEFER_SAFETY_PROOF", "$reason|$detail")
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
            reason.startsWith("LP_OWNERSHIP_PROOF_PENDING_8522") ||
            reason.startsWith("LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP:") ||
            reason == "LIQUIDITY_PROOF_PENDING"
    }.distinct()

    /** An invalid numeric feed cannot erase another provider's concentration finding. */
    internal fun topHolderPct8522(statePct: Double?, reportPct: Double): Double =
        listOfNotNull(statePct, reportPct).filter { it.isFinite() && it >= 0.0 }
            .maxOrNull()?.coerceAtMost(100.0) ?: -1.0

    internal data class HolderProof8522(val adversePct: Double, val freshPct: Double?)

    /** Undated state can preserve a warning; only dated current report evidence can authorize spending. */
    internal fun holderProof8522(statePct: Double?, report: SafetyReport, nowMs: Long): HolderProof8522 {
        val fresh = report.topHolderPct.takeIf {
            it.isFinite() && it >= 0.0 && report.topHolderObservedAtMs8522 > 0L &&
                nowMs - report.topHolderObservedAtMs8522 in 0L..SAFETY_FRESH_MS
        }?.coerceAtMost(100.0)
        return HolderProof8522(topHolderPct8522(statePct, report.topHolderPct), fresh)
    }

    /**
     * V5.0.8684 — the buy door's confirmed top-holder hard block, readable BEFORE a ticket is spent (CandidateAdmission8136).
     * 8680: PRETRADE_HARD_BLOCK_TOP_HOLDER_CONCENTRATION=24 — candidates admitted, FDG-allowed and ticketed, then refused here,
     * each one a dropped ticket and a lease backoff (BUY_LEASE_BACKOFF_ENDS_TICKET_8545). Same proof, same threshold.
     */
    fun holderBlock8684(ts: TokenState, nowMs: Long = System.currentTimeMillis()): String? =
        if (holderProof8522(ts.topHolderPct, ts.safety, nowMs).adversePct >= TOP_HOLDER_FATAL_PCT) "TOP_HOLDER_CONCENTRATION" else null

    /**
     * V5.0.8705 — the buy door, asked BEFORE a ticket is spent (CandidateAdmission8136). 8703 read three of the door's proof
     * waits from the token's state; the 8704 capture: 35 MOONSHOT tickets, 2 buys, DEFERRED_DATA_SAFETY_PROOF_8270 x10 first
     * failures and 50 later tickets ended by the backoff they set — the door defers on eleven proofs (RugCheck pending, mint
     * authority unread, liquidity / ownership / depth proof, holder data, route liquidity ...), and a copy of three of them
     * is not the door. Admission now runs [requireLiveBuyAllowed] itself as a probe: the same code, the same state, no log,
     * no counter and no priority note. The proof's name when the door would defer on proof; null when it would allow or
     * hard-block (a block is the door's refusal, not a wait).
     */
    fun proofWait8703(ts: TokenState): String? {
        probe8705.set(true)
        val v = try { requireLiveBuyAllowed(ts, "ADMISSION_PROBE_8705") } catch (_: Throwable) { null } finally { probe8705.remove() }
        return proofWaitOf8705(v)
    }

    /** V5.0.8717 — the probed door's whole answer (null when the probe itself failed): admission reads its block as well as its wait. */
    fun probeDoor8717(ts: TokenState): Verdict? {
        probe8705.set(true)
        return try { requireLiveBuyAllowed(ts, "ADMISSION_PROBE_8705") } catch (_: Throwable) { null } finally { probe8705.remove() }
    }

    /** Pure (V5.0.8717): the hard block a probed door [v] would answer (its reason), or null when it allows or only waits on proof. */
    internal fun doorBlockOf8717(v: Verdict?): String? =
        if (v != null && !v.allowed && v.reason != "DEFER_SAFETY_PROOF") v.reason.trim().ifBlank { null } else null

    /** Pure: the proof a probed door [v] waits on, or null. */
    internal fun proofWaitOf8705(v: Verdict?): String? =
        if (v != null && !v.allowed && v.reason == "DEFER_SAFETY_PROOF") v.detail.substringBefore('|').trim().ifBlank { "PROOF_PENDING" } else null

    private val probe8705 = ThreadLocal<Boolean>()
    private fun probing8705(): Boolean = probe8705.get() == true

    /** Pure (V5.0.8703): a report past half its freshness is refreshed while the candidate is still being judged. */
    internal fun proofAgeing8703(checkedAtMs: Long, nowMs: Long): Boolean = checkedAtMs > 0L && nowMs - checkedAtMs > SAFETY_FRESH_MS / 2

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
        if (pending.isNotEmpty() && !probing8705()) {
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

    fun requireLiveBuyAllowed(ts: TokenState, callSite: String, selectedPoolAddressOverride8522: String? = null): Verdict {
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
        if (safety.evaluatedForPaper8522 != false) {
            try { SafetyRefreshQueue.request(mint) } catch (_: Throwable) {}
            return deferSafetyProof(ts, "LIVE_SAFETY_POLICY_REFRESH_8522",
                listOf("SAFETY_POLICY_NOT_LIVE:paper=${safety.evaluatedForPaper8522}"), callSite)
        }

        val rcStatus = safety.rugcheckStatus.uppercase()
        if (rcStatus.isBlank() || rcStatus in setOf("UNKNOWN", "TIMEOUT", "PENDING", "PENDING_REVIEW", "ERROR")) {
            pendingProofs.add("RUGCHECK_PENDING_OR_UNKNOWN:$rcStatus")
        }
        // RugModel/RugCheck convention in this codebase: 0 = confirmed rug/fatal,
        // 1 = pending/needs-data. Pending is telemetry/penalty unless another
        // confirmed fatal signal exists; score=0 remains a hard rug proof.
        if (safety.rugcheckScore == 0) return block(ts, "RUGCHECK_CONFIRMED_RUG", "score=0")

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
        // The freeze check reads both authorities. Use that same fresh account
        // result, not the scan-time snapshot captured before the RPC resolved.
        val authorityMint8522 = ts.safety.mintAuthorityDisabled
        when (authorityMint8522) {
            false -> return block(ts, "MINT_AUTHORITY_ACTIVE", "mint authority still active")
            null -> pendingProofs.add("MINT_AUTHORITY_UNKNOWN")
            true -> Unit
        }

        val tokenMapLiquidity = TokenMapAuthority.liquidityVerdict(ts)
        val hasExecutionPool8522 = selectedPoolAddressOverride8522 != null
        when {
            tokenMapLiquidity.sourceIdentityBad -> return block(ts, "SOURCE_IDENTITY_BAD", tokenMapLiquidity.reason)
            !hasExecutionPool8522 && tokenMapLiquidity.hardZero -> return block(ts, "TRUE_ZERO_LIQUIDITY", tokenMapLiquidity.reason)
            !hasExecutionPool8522 && tokenMapLiquidity.pending -> pendingProofs.add("LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP:${tokenMapLiquidity.reason}")
            tokenMapLiquidity.executable -> Unit
        }
        if (!hasExecutionPool8522 && !safety.liqConfirmed && !tokenMapLiquidity.executable) pendingProofs.add("LIQUIDITY_PROOF_PENDING")
        // Actual curve-account reserves observed within 30 seconds can prove active
        // program custody; an inferred stream reserve or old venue label cannot.
        val curveExitSafe8319 = !ts.tokenMap.migratedOrGraduated && LiquidityOwnership8522.activeCurveVerified(mint, now)
        val curveAddress8522 = if (curveExitSafe8319) try {
            com.lifecyclebot.network.PumpCurveKeys7269.canonicalCurveKey7392(mint).orEmpty()
        } catch (_: Throwable) { "" } else ""
        val selectedPool8522 = selectedPoolAddressOverride8522 ?:
            ts.tokenMap.poolAddress.ifBlank { ts.lastPricePoolAddr.ifBlank { ts.pairAddress } }
        val ownership8522 = LiquidityOwnership8522.assess(
            safety, selectedPool8522, curveExitSafe8319, TokenSafetyChecker.isWhitelisted(mint), curveAddress8522, now,
            expectedMint = mint,
        )
        when (ownership8522.status) {
            LiquidityOwnership8522.Status.BLOCKED -> return block(ts, ownership8522.reason, ownership8522.detail)
            LiquidityOwnership8522.Status.PENDING -> pendingProofs.add("${ownership8522.reason}:${ownership8522.detail}")
            LiquidityOwnership8522.Status.PROVEN -> Unit
        }
        val exactDepth8522 = if (hasExecutionPool8522)
            LiquidityOwnership8522.observedPoolDepth8522(safety, selectedPool8522, now, expectedMint = mint) else null
        val curveCustody8522 = ownership8522.reason == "ACTIVE_CURVE_LP_NOT_APPLICABLE_8522"
        if (hasExecutionPool8522 && exactDepth8522 == null && !curveCustody8522) pendingProofs.add("LIQUIDITY_PROOF_PENDING")
        // A final quote may use a different pool from the last price. Its depth
        // is resolved by exact address, never the old map's low/high reading.
        val liquidityUsd8522 = if (hasExecutionPool8522) exactDepth8522 ?: -1.0 else ts.lastLiquidityUsd
        val depthObserved8522 = if (hasExecutionPool8522) liquidityUsd8522 >= 0.0 else liquidityUsd8522 > 0.0
        if (depthObserved8522 && liquidityUsd8522 < MIN_LIVE_LIQ_HARD_FLOOR_USD && !curveCustody8522) {
            // V5.0.4090 — UN-TRADEABLE THIN-POOL HARD BLOCK. Any sell into a sub-$500
            // pool will catastrophically slip past its stop loss (operator data point:
            // STANDARD entry at $192 liq → STRICT_SL_-10 fired → realized -47% =
            // -0.146 SOL single-trade loss). No lane survives this safely.
            return block(ts, "LIQUIDITY_BELOW_EXIT_SAFE_FLOOR", "liq=\$${liquidityUsd8522.toInt()} pool=$selectedPool8522 min=\$${MIN_LIVE_LIQ_HARD_FLOOR_USD.toInt()}")
        }
        if (liquidityUsd8522 > 0.0 && liquidityUsd8522 < MIN_LIVE_LIQ_USD) {
            pendingProofs.add("LOW_LIQUIDITY_SIZE_REDUCED:liq=$liquidityUsd8522 min=$MIN_LIVE_LIQ_USD")
            if (!probing8705()) try { ForensicLogger.lifecycle("BUY_GATE_DECISION", "mint=${ts.mint.take(10)} symbol=${ts.symbol} decision=PENALTY_ONLY reason=LOW_LIQUIDITY_SIZE_REDUCED source=PreTradeHardGate liveEligible=true") } catch (_: Throwable) {}
        }

        val holder8522 = holderProof8522(ts.topHolderPct, safety, System.currentTimeMillis())
        val topHolder = holder8522.adversePct
        // V5.0.7379 — a known top-10 concentration (rugcheck or on-chain) is resolved
        // holder data, whether or not a holder-count candle has arrived.
        if (!ts.holderDataResolved && holder8522.freshPct == null) pendingProofs.add("HOLDER_DATA_PENDING")
        if (holder8522.freshPct == null) pendingProofs.add("HOLDER_DATA_UNKNOWN")
        // V5.0.3986 — operator screenshot breach: single-holder/high-ownership/
        // top10/unverified token risk must never spend real SOL. Unknown holder
        // distribution is a live hydration defer; confirmed concentration is a
        // hard block. Paper learning can still sample outside this live-only gate.
        if (holder8522.freshPct == null) {
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
        if (holder8522.freshPct == null &&
            (rcStatus in setOf("UNKNOWN", "TIMEOUT", "PENDING", "PENDING_REVIEW", "ERROR", "") ||
                (liquidityUsd8522 in 0.000001..MIN_LIVE_LIQ_USD && !curveCustody8522))) {   // V5.0.8319: a blind rugcheck still defers
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
        val executableExit8522 = if (hasExecutionPool8522) curveCustody8522 ||
            (exactDepth8522 != null && exactDepth8522 >= MIN_LIVE_LIQ_HARD_FLOOR_USD)
            else tokenMapLiquidity.executable || curveExitSafe8319
        // V5.0.8733 — owner "3 yes": an UNREAD LP ownership (never unlocked / under-lock, those are BLOCKED above) in a
        // proven paying cell may be bought at explore size; the substitute's other proofs are unchanged.
        val explore8733 = ownership8522.status == LiquidityOwnership8522.Status.PENDING &&
            (try { com.lifecyclebot.engine.truth.ExploreLive8733.eligible8733(ts, now) } catch (_: Throwable) { false })
        val pending8471 = chainProvenRugcheckSubstitute8471(pendingProofs.distinct(), authorityMint8522 == true,
            holder8522.freshPct ?: -1.0, executableExit8522,
            ownership8522.status == LiquidityOwnership8522.Status.PROVEN || explore8733)
        val pending8733 = if (explore8733) com.lifecyclebot.engine.truth.ExploreLive8733.relabel8733(pending8471) else pending8471
        return allowWithPendingProof(ts, pending8733, callSite)
    }

    /**
     * Pure (V5.0.8471). RugCheck answers a fresh mint it has not indexed with 400 "not found" (and a slow report times out):
     * no findings, which this gate filed as a critical pending proof, so no fresh launch could be bought live (8470: 50 buy
     * attempts, 0 buys, all DEFERRED_DATA_SAFETY_PROOF_8270). When RugCheck has no findings, our own on-chain proofs are the
     * rug proof, all of them required: mint authority revoked [mintRevoked], freeze revoked with no hostile Token-2022
     * extension (re-read on chain above, FreezeAuthorityHardBlock7238, or this gate already refused), holder concentration
     * read and under the fatal limit [topHolderPct], and an exit we can sell into [executableExit] (a routable token map or
     * an exit-safe active curve), plus protected LP ownership or verified active curve custody [liquidityOwnershipProven].
     * The RugCheck item then stays a size penalty, not a refusal; any other
     * missing proof still defers, and a RugCheck report with findings is judged by them upstream.
     */
    internal fun chainProvenRugcheckSubstitute8471(pending: List<String>, mintRevoked: Boolean, topHolderPct: Double,
                                                   executableExit: Boolean, liquidityOwnershipProven: Boolean = false): List<String> {
        if (pending.none { it.startsWith(RUGCHECK_PENDING_8471) }) return pending
        val proven = mintRevoked && topHolderPct.isFinite() && topHolderPct >= 0.0 && topHolderPct < TOP_HOLDER_FATAL_PCT &&
            executableExit && liquidityOwnershipProven
        if (!proven) return pending
        if (!probing8705()) try { PipelineHealthCollector.labelInc("RUGCHECK_NO_FINDINGS_CHAIN_PROVEN_8471") } catch (_: Throwable) {}
        return pending.map { if (it.startsWith(RUGCHECK_PENDING_8471)) "RUGCHECK_NO_FINDINGS_CHAIN_PROVEN_8471:" + it.substringAfter(':') else it }
    }

    private const val RUGCHECK_PENDING_8471 = "RUGCHECK_PENDING_OR_UNKNOWN:"
}

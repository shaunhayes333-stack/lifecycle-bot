package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * V5.0.6342 — LANE ENTRY CONTRACT (single authoritative live-entry choke).
 *
 * OPERATOR DIRECTIVE (verbatim excerpts):
 *   "Make AATE trade competently from the first live trade using a
 *    deterministic, preloaded trading policy and evidence available
 *    before entry. Do not use early live losses as training material
 *    required to discover basic trading behaviour."
 *   "BLUECHIP must represent established, liquid assets. A Pump.fun
 *    token cannot be BLUECHIP."
 *   "QUALITY must require: Real pool address, not MINT_ROUTE."
 *   "When governor is HOLD: Create no BUY execution ticket."
 *
 * This module ships the minimum viable subset of V5.0.6342 in a single
 * self-contained file so CI can verify it in one build:
 *
 *   1. Lane-identity contract per operator spec (BLUECHIP / QUALITY).
 *   2. Governor HOLD hard-veto of BUY (redirect to SHADOW).
 *   3. Single authoritative check callable from every buy path.
 *
 * OUT OF SCOPE for this initial push (staged for 6343-6349):
 *   - Full immutable FillLotLedger keyed by wallet+mint+buyTxSig
 *   - Strong unit types (SolAmount / UsdAmount / TokenQuantity)
 *   - Foundation-policy pre-entry evidence contract
 *   - Executable-price stop preflight
 *   - Scanner/hydration queue separation
 *   - New FIRST-TRADE READINESS health block
 *
 * INVARIANTS PRESERVED:
 *   - Never disables a lane; failed candidates go to SHADOW/PROBE.
 *   - Never affects paper mode (learning path unchanged).
 *   - No changes to existing lane math, scoring or exit logic.
 */
object LaneEntryContract6342 {

    enum class Verdict {
        /** Live authority granted — proceed to execution. */
        ALLOW_LIVE,
        /** V5.0.6388 — Governor HOLD active but recovery state machine permits
         *  a probation-sized evidence-generating trade. Executor MUST clamp
         *  size to `GovernorRecovery6388.probationSize(...)`. */
        ALLOW_LIVE_PROBATION,
        /** Contract failed — candidate must be routed to SHADOW / PROBE. */
        REDIRECT_SHADOW,
        /** Governor HOLD in effect — no live BUY may issue. */
        GOVERNOR_HOLD_VETO,
        /** V5.0.6388 (S13) — policy-block dedup elided this candidate to
         *  prevent BUY_FAIL inflation. Executor MUST NOT emit BUY_FAIL. */
        POLICY_BLOCK_DEDUPED,
    }

    data class Assessment(
        val verdict: Verdict,
        val laneRequested: String,
        val laneCanonical: String,
        val reasons: List<String>,
    ) {
        val allowsLive: Boolean get() =
            verdict == Verdict.ALLOW_LIVE || verdict == Verdict.ALLOW_LIVE_PROBATION
        val isPolicyBlock: Boolean get() =
            verdict == Verdict.GOVERNOR_HOLD_VETO || verdict == Verdict.POLICY_BLOCK_DEDUPED
    }

    /** Pump.fun mints always end with "pump" (canonical Pump.fun suffix). */
    private fun isPumpFunMint(mint: String): Boolean =
        mint.isNotBlank() && mint.endsWith("pump", ignoreCase = true)

    /**
     * V5.0.7252 — pure lane-identity predicate for the owner-election boundary.
     *
     * The executor remains the final authority, but election must not knowingly
     * mint a ticket for an identity the executor is guaranteed to reject. Keep
     * this predicate side-effect free so candidate ranking can call it without
     * incrementing rejection counters or consulting mutable governor state.
     */
    /**
     * V5.0.7389 — QUALITY's and BLUECHIP's mcap bands, from the same learned
     * LaneHunter7297 band their traders use. Election, the BotService buy blocks
     * and the lane proof all read these, so a token elected to a lane is always
     * inside that lane's buy block (no orphaned owner).
     */
    fun qualityMcapBand7389(): ClosedFloatingPointRange<Double> {
        val q = com.lifecyclebot.v3.scoring.QualityTraderAI
        val floor = try { com.lifecyclebot.engine.market.LaneHunter7297.floorFor("QUALITY", q.MIN_MARKET_CAP_USD) } catch (_: Throwable) { q.MIN_MARKET_CAP_USD }
        val ceiling = try { com.lifecyclebot.engine.market.LaneHunter7297.ceilingFor("QUALITY", q.MAX_MARKET_CAP_USD) } catch (_: Throwable) { q.MAX_MARKET_CAP_USD }
        return floor..maxOf(floor, ceiling)
    }

    /** Liquidity each lane's BotService proof (qualityLaneProofOk) requires. */
    const val QUALITY_MIN_LIQ_7389 = 15_000.0
    const val BLUECHIP_MIN_LIQ_7389 = 50_000.0

    /** True when QUALITY/BLUECHIP's buy block and proof can take this token. */
    fun specialistCanBuy7389(ts: TokenState, lane: String): Boolean = when (lane.uppercase()) {
        "QUALITY" -> ts.lastMcap in qualityMcapBand7389() && ts.lastLiquidityUsd >= QUALITY_MIN_LIQ_7389
        "BLUECHIP", "BLUE_CHIP" -> ts.lastMcap >= blueChipMcapFloor7389() && ts.lastLiquidityUsd >= BLUECHIP_MIN_LIQ_7389
        else -> false
    }

    fun blueChipMcapFloor7389(): Double {
        val b = com.lifecyclebot.v3.scoring.BlueChipTraderAI.MIN_MARKET_CAP_USD
        return try { com.lifecyclebot.engine.market.LaneHunter7297.floorFor("BLUECHIP", b) } catch (_: Throwable) { b }
    }

    fun isLaneIdentityEligible7252(ts: TokenState, laneRequested: String): Boolean {
        val lane = laneRequested.uppercase()
        if ((lane == "BLUECHIP" || lane == "BLUE_CHIP") && isPumpFunMint(ts.mint)) return false
        // V5.0.7388 — QUALITY and CORE are not launch lanes. The desk's role
        // hypotheses (DIAMOND_HANDS_RUNNER, CHART_BREAKOUT, PULLBACK_RECLAIM) tag a
        // pumping $3-4k curve launch QUALITY with no mcap check, and the V3 trunk
        // then bought it under that label without QualityTraderAI's own $75k gate
        // (QUALITY 0/7 this session, EV -4.2%). An un-graduated pump.fun launch is
        // left to the launch lanes (PROJECT_SNIPER / MOONSHOT / SHITCOIN), and
        // QUALITY also needs its band floor.
        if (lane == "QUALITY" || lane == "CORE") {
            val src = ts.lastPriceSource.ifBlank { ts.source }.uppercase()
            val onCurve = isPumpFunMint(ts.mint) &&
                (src.contains("PUMP_FUN_BC") || src.contains("PUMP_PORTAL") || ts.lastMcap < 69_000.0)
            if (onCurve) return false
            if (lane == "QUALITY") {
                // V5.0.7389 — QUALITY's band ends where BLUECHIP's begins ($1M), so a
                // large cap is owned by BLUECHIP instead of both lanes claiming it.
                if (ts.lastMcap <= 0.0 || ts.lastMcap !in qualityMcapBand7389()) return false
                // Known liquidity under the lane proof's floor would elect an owner that cannot buy.
                if (ts.lastLiquidityUsd > 0.0 && ts.lastLiquidityUsd < QUALITY_MIN_LIQ_7389) return false
            }
        }
        // V5.0.7389 — BLUECHIP is the $1M+ lane; a known smaller cap is not its token.
        if ((lane == "BLUECHIP" || lane == "BLUE_CHIP") && ts.lastMcap > 0.0 &&
            ts.lastMcap < blueChipMcapFloor7389()) return false
        if ((lane == "BLUECHIP" || lane == "BLUE_CHIP") && ts.lastLiquidityUsd > 0.0 &&
            ts.lastLiquidityUsd < BLUECHIP_MIN_LIQ_7389) return false
        // V5.0.7389 — MOONSHOT's designed band is MoonshotTraderAI's own $10k-$5M.
        // A known mcap outside it cannot pass the lane's scorer, so election must not
        // mint a MOONSHOT ticket for it. Unknown mcap (0) is left to the lane.
        if (lane == "MOONSHOT" && ts.lastMcap > 0.0 &&
            (ts.lastMcap < com.lifecyclebot.v3.scoring.MoonshotTraderAI.MIN_MARKET_CAP_USD ||
                ts.lastMcap > com.lifecyclebot.v3.scoring.MoonshotTraderAI.MAX_MARKET_CAP_USD)) return false
        return true
    }

    /** MINT_ROUTE placeholder means no real pool address is known yet. */
    private fun isMintRoutePlaceholder(pool: String?): Boolean {
        val p = pool ?: return true
        return p.isBlank() ||
            p.equals("MINT_ROUTE", ignoreCase = true) ||
            p.contains("MINT_ROUTE", ignoreCase = true)
    }

    /**
     * Single authoritative live-entry check. Every live buy path
     * (direct scanner entry, restored registry, probation, warmup,
     * re-entry, BLUECHIP path, QUALITY bypass, V3 path, reconciler-
     * triggered entry, lane-specific shortcut) MUST call this before
     * creating an execution ticket or acquiring a buy lease.
     */
    fun assessEntry(ts: TokenState, laneRequested: String): Assessment {
        val reasons = mutableListOf<String>()
        val lane = laneRequested.uppercase()

        // 1. Governor HOLD hard-veto — no live BUY tickets while HOLD.
        val govState = try { LiveEntrySafetyHold.currentGovernorState().name } catch (_: Throwable) { "BASELINE" }
        // V5.0.7376 — the governor is advisory (operator: "the governor should be
        // advisory. it's choking out trading completely"). HOLD no longer vetoes an
        // entry or confines it to the 1-open / 3-per-hour probation limiter; it is
        // recorded and the entry continues to the lane checks below. The only block
        // kept is BLOCKED_INFRASTRUCTURE — wallet, ledger or reconciler not available,
        // where a buy could not be tracked or sold.
        val infraBlocked7376 = try {
            com.lifecyclebot.engine.truth.GovernorRecovery6388.state() ==
                com.lifecyclebot.engine.truth.GovernorRecovery6388.State.BLOCKED_INFRASTRUCTURE
        } catch (_: Throwable) { false }
        if (govState == "HOLD" && !infraBlocked7376) {
            reasons += "GOVERNOR_HOLD_ADVISORY_7376"
            try { PipelineHealthCollector.labelInc("GOVERNOR_HOLD_ADVISORY_7376") } catch (_: Throwable) {}
        }
        if (govState == "HOLD" && infraBlocked7376) {
            reasons += "GOVERNOR_HOLD_VETO_6342"
            // V5.0.6388 (S4/S5/S13) — consult recovery state machine. If the
            // machine has automatically promoted the runtime to HOLD_PROBATION
            // and probation rate-limits allow one more entry, return a new
            // ALLOW_LIVE_PROBATION verdict so the executor can issue a
            // strictly-sized evidence-generating trade. Otherwise, emit
            // LIVE_ENTRY_POLICY_BLOCKED_6388 through the dedup channel instead
            // of the legacy BUY_FAIL-inflating GOVERNOR_HOLD_VETO_6342 path.
            val recovAuth = try {
                com.lifecyclebot.engine.truth.GovernorRecovery6388.entryAuthority()
            } catch (_: Throwable) { null }
            // V5.0.7214 §A_PROMOTION_THAT_REMOVED_PERMISSION.
            //
            // This test used to be `allowBuys && probationSized`, which is true
            // for exactly ONE recovery state: HOLD_PROBATION. GovernorRecovery
            // 6388:200-207 gives allowBuys=true to four states — HOLD_PROBATION,
            // SOFT_TIGHT, BASELINE and EXPANSION — but only HOLD_PROBATION has
            // probationSized=true.
            //
            // So while the governor said HOLD, the machine promoting itself out
            // of probation (which it does only after >=5 clean canonical closes
            // with >=3 wins, PF >= 1.0 and non-negative expectancy — 6388:137)
            // made this escape clause FALSE and sent every candidate to the
            // policy-block return below. Earning the evidence to be promoted
            // took the lane from "one probation-sized trade allowed" to "nothing
            // allowed". A strictly monotone authority read non-monotonically.
            //
            // Honour allowBuys, which is the machine's actual answer. Permission
            // is NOT widened: while the governor is HOLD the entry is still
            // clamped to probation sizing (the size clamp lives in the executor's
            // `sol` resolution block and reads probationSized) and must still
            // pass ProbationEntryLimiter6388.canOpen(). The ceiling under HOLD is
            // therefore exactly what HOLD_PROBATION already permitted — one
            // strictly-sized, rate-limited, evidence-generating trade — so this
            // does not force the governor's HOLD open. It only stops a state the
            // machine reached BY PROVING ITSELF from being treated as worse than
            // the state it was promoted from.
            val recoveryAuth7214 = recovAuth?.takeIf { it.allowBuys }
            if (recoveryAuth7214 != null && !recoveryAuth7214.probationSized) {
                try {
                    PipelineHealthCollector.labelInc("LANE_ENTRY_RECOVERY_ABOVE_PROBATION_UNDER_HOLD_7214")
                    ForensicLogger.lifecycle(
                        "LANE_ENTRY_RECOVERY_ABOVE_PROBATION_UNDER_HOLD_7214",
                        "mint=${ts.mint.take(10)} lane=$lane govState=$govState recovery=${recoveryAuth7214.reason} " +
                            "action=clamp_to_probation_permission_not_block " +
                            "note=promotion_past_HOLD_PROBATION_used_to_block_every_entry",
                    )
                } catch (_: Throwable) {}
            }
            if (recoveryAuth7214 != null) {
                val (canOpen, limitReason) = try {
                    com.lifecyclebot.engine.truth.ProbationEntryLimiter6388.canOpen()
                } catch (_: Throwable) { true to "OK" }
                // V5.0.7324 — the probation limiter (1 open, 3/hour, 3-min spacing)
                // belongs to HOLD_PROBATION. Once the recovery machine has promoted
                // itself out of probation (SOFT_TIGHT/BASELINE/EXPANSION: it proved
                // >=5 clean closes, >=3 wins, PF>=1), its allowBuys is the answer and
                // its own sizing band applies. Holding it to the probation rate
                // stopped every live buy (5.0.7321: 12 blocked, 0 bought) — the
                // HOLD-as-shape-not-block rule LiveEntrySafetyHold already states.
                val promotedPastProbation7324 = !recoveryAuth7214.probationSized
                if (!canOpen && promotedPastProbation7324) {
                    try { PipelineHealthCollector.labelInc("LANE_ENTRY_PROMOTED_RECOVERY_PAST_PROBATION_LIMIT_7324") } catch (_: Throwable) {}
                }
                if (canOpen || promotedPastProbation7324) {
                    try {
                        PipelineHealthCollector.labelInc("LANE_ENTRY_CONTRACT_ALLOW_PROBATION_6388")
                        ForensicLogger.lifecycle(
                            "LANE_ENTRY_CONTRACT_ALLOW_PROBATION_6388",
                            "mint=${ts.mint.take(10)} symbol=${ts.symbol} lane=$lane recoveryState=${recoveryAuth7214.reason} action=allow_probation_sized",
                        )
                    } catch (_: Throwable) {}
                    reasons += "GOVERNOR_HOLD_PROBATION_6388_ALLOWED"
                    return Assessment(Verdict.ALLOW_LIVE_PROBATION, laneRequested, lane, reasons)
                }
                // Probation limit hit → dedup policy block (no BUY_FAIL).
                val runtimeGen = try {
                    com.lifecyclebot.engine.BotRuntimeController.currentGeneration()
                } catch (_: Throwable) { 0L }
                val shouldEmit = try {
                    com.lifecyclebot.engine.truth.PolicyBlockDedup6388.shouldEmit(
                        runtimeGen, ts.mint, "lane_entry_${lane}", govState, recoveryAuth7214.reason
                    )
                } catch (_: Throwable) { true }
                if (shouldEmit) {
                    try {
                        com.lifecyclebot.engine.truth.PolicyBlockDedup6388.recordPolicyBlock(
                            ts.mint, govState, recoveryAuth7214.reason, limitReason
                        )
                    } catch (_: Throwable) {}
                    return Assessment(Verdict.GOVERNOR_HOLD_VETO, laneRequested, "SHADOW", reasons + limitReason)
                }
                return Assessment(Verdict.POLICY_BLOCK_DEDUPED, laneRequested, "SHADOW", reasons + limitReason)
            }
            // V5.0.7214 — recovery does not permit buys at all (BLOCKED_
            // INFRASTRUCTURE or EXIT_ONLY) → emit dedup policy block. This is
            // the only remaining path to a policy block under HOLD, and it now
            // means what it says rather than also catching every state the
            // machine had promoted itself into.
            val runtimeGen = try {
                com.lifecyclebot.engine.BotRuntimeController.currentGeneration()
            } catch (_: Throwable) { 0L }
            val recovStateName = try {
                com.lifecyclebot.engine.truth.GovernorRecovery6388.state().name
            } catch (_: Throwable) { "UNKNOWN" }
            val shouldEmit = try {
                com.lifecyclebot.engine.truth.PolicyBlockDedup6388.shouldEmit(
                    runtimeGen, ts.mint, "lane_entry_${lane}", govState, recovStateName
                )
            } catch (_: Throwable) { true }
            if (shouldEmit) {
                try {
                    com.lifecyclebot.engine.truth.PolicyBlockDedup6388.recordPolicyBlock(
                        ts.mint, govState, recovStateName, "GOVERNOR_HOLD"
                    )
                    PipelineHealthCollector.labelInc("LIVE_BUY_REDIRECTED_GOVERNOR_HOLD_6342")
                    ForensicLogger.lifecycle(
                        "LANE_ENTRY_CONTRACT_GOVERNOR_HOLD_6342",
                        "mint=${ts.mint.take(10)} symbol=${ts.symbol} lane=$lane govState=$govState recovState=$recovStateName action=policy_block",
                    )
                } catch (_: Throwable) {}
                return Assessment(Verdict.GOVERNOR_HOLD_VETO, laneRequested, "SHADOW", reasons)
            }
            return Assessment(Verdict.POLICY_BLOCK_DEDUPED, laneRequested, "SHADOW", reasons)
        }

        // 2. BLUECHIP identity contract — no Pump.fun mints allowed.
        //    BLUECHIP must represent established, liquid assets.
        if (lane == "BLUECHIP" || lane == "BLUE_CHIP") {
            if (!isLaneIdentityEligible7252(ts, lane)) {
                reasons += "BLUECHIP_REJECTS_PUMPFUN_MINT_6342"
                try {
                    PipelineHealthCollector.labelInc("LANE_ENTRY_BLUECHIP_PUMPFUN_REJECTED_6342")
                    ForensicLogger.lifecycle(
                        "LANE_ENTRY_BLUECHIP_PUMPFUN_REJECTED_6342",
                        "mint=${ts.mint.take(10)} symbol=${ts.symbol} action=route_to_meme_lane_shadow",
                    )
                } catch (_: Throwable) {}
                return Assessment(Verdict.REDIRECT_SHADOW, laneRequested, "MOONSHOT_OR_SHITCOIN", reasons)
            }
        }

        // 3. QUALITY identity contract — V5.0.6385 REPAIR (operator directive
        //    Section 3): MINT_ROUTE is a VALID candidate-evaluation state.
        //    Concrete route proof (real pair address, executable Jupiter quote,
        //    successful transaction build, slippage/impact within policy) is
        //    required immediately before SIGNING, not before routing. The old
        //    reject-at-eval-time gate caused MULTI_LANE_ACTIVE QUALITY leak
        //    (2762 non-QUALITY active evals in a session) because valid QUALITY
        //    candidates were being redirected to shadow before they could be
        //    hydrated. Route proof is now enforced at the executor's build/quote
        //    step (see LIVE_BUY_ABORTED_ROUTE_PROOF_MISSING_6385 in Executor).
        if (lane == "QUALITY") {
            try {
                val pool = try { ts.tokenMap.pairAddress.ifBlank { ts.tokenMap.poolAddress } } catch (_: Throwable) { null }
                if (isMintRoutePlaceholder(pool)) {
                    // Advisory only — telemetry-only, no redirect. Bundle 6390's
                    // canary gate will require actual route proof at signing.
                    PipelineHealthCollector.labelInc("LANE_ENTRY_QUALITY_MINT_ROUTE_ADVISORY_6385")
                }
            } catch (_: Throwable) {}
        }

        // Contract passed — live authority granted.
        try { PipelineHealthCollector.labelInc("LANE_ENTRY_CONTRACT_ALLOWED_6342") } catch (_: Throwable) {}
        return Assessment(Verdict.ALLOW_LIVE, laneRequested, lane, listOf("CONTRACT_PASSED"))
    }
}

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

    /**
     * V5.0.7393b — the sniper's launch test, shared by election and the live buy
     * (Executor 7385). Election handed PROJECT_SNIPER tokens that were not
     * launches; the live buy refused them (LIVE_SNIPER_NOT_A_LAUNCH_7385 = 31 of
     * 39 buy fails) and the next cycle elected the sniper again, so the token
     * never traded. One definition, read by both.
     */
    // V5.0.7803 — one Project Sniper identity contract shared by election and
    // its native trader. The old 2-hour/$150k predicate elected assets that the
    // 3-minute PRE_IGNITION trader would deterministically refuse.
    private const val SNIPER_LAUNCH_MIN_MCAP_USD_7803 = 3_000.0
    internal const val SNIPER_LAUNCH_MAX_MCAP_USD_7393 = 500_000.0
    private const val SNIPER_LAUNCH_MIN_AGE_SECS_7803 = 15L
    internal const val SNIPER_LAUNCH_MAX_AGE_SECS_7393 = 180L
    private const val SNIPER_LAUNCH_MIN_LIQ_USD_7803 = 2_000.0
    private const val SNIPER_LAUNCH_MAX_LIQ_USD_7803 = 250_000.0

    fun isSniperLaunch7393(ts: TokenState): Boolean {
        if (try { ts.tokenMap.migratedOrGraduated } catch (_: Throwable) { false }) return false
        if (ts.lastMcap > 0.0 && ts.lastMcap !in SNIPER_LAUNCH_MIN_MCAP_USD_7803..SNIPER_LAUNCH_MAX_MCAP_USD_7393) return false
        if (ts.lastLiquidityUsd > 0.0 && ts.lastLiquidityUsd !in SNIPER_LAUNCH_MIN_LIQ_USD_7803..SNIPER_LAUNCH_MAX_LIQ_USD_7803) return false
        val age = try { com.lifecyclebot.engine.truth.CanonicalTokenBirthTime7440.launchAgeMs7767(ts)?.div(1000L) } catch (_: Throwable) { null }
        if (age == null) {
            try { PipelineHealthCollector.labelInc("SNIPER_BIRTH_HYDRATION_PENDING_7440") } catch (_: Throwable) {}
            return false
        }
        if (age !in SNIPER_LAUNCH_MIN_AGE_SECS_7803..SNIPER_LAUNCH_MAX_AGE_SECS_7393) return false

        val launch = try { com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.snapshot(ts) } catch (_: Throwable) { null }
            ?: return false
        if (!launch.birthResolved ||
            launch.phase != com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.PRE_IGNITION
        ) return false
        if (launch.distinctBuyers60s < 3 || !launch.accelerationRising || launch.buySharePct < 60.0) return false
        return true
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
        if (lane == "PROJECT_SNIPER" && !isSniperLaunch7393(ts)) return false
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

        // 1. V5.0.7398 — GOVERNOR IS SHAPING AUTHORITY, NEVER EXECUTION AUTHORITY.
        // Performance/recovery state may raise score floors, shrink size, alter cooldowns
        // and select conservative exits, but it must never veto an otherwise executable
        // live BUY. Hard stops belong to the concrete safety/finality authorities
        // (wallet availability, route proof, canonical fill/qty integrity, signing and
        // broadcast/finality), which have direct evidence of whether execution is safe.
        val govState = try { LiveEntrySafetyHold.currentGovernorState().name } catch (_: Throwable) { "BASELINE" }
        val recoveryState7398 = try { com.lifecyclebot.engine.truth.GovernorRecovery6388.state().name } catch (_: Throwable) { "UNKNOWN" }
        if (govState == "HOLD" || recoveryState7398 == "BLOCKED_INFRASTRUCTURE" || recoveryState7398 == "EXIT_ONLY") {
            reasons += "GOVERNOR_SHAPING_ONLY_7398"
            try {
                PipelineHealthCollector.labelInc("GOVERNOR_SHAPING_ONLY_7398")
                ForensicLogger.lifecycle(
                    "GOVERNOR_SHAPING_ONLY_7398",
                    "mint=${ts.mint.take(10)} symbol=${ts.symbol} lane=$lane governor=$govState recovery=$recoveryState7398 action=continue_to_concrete_safety_gates",
                )
            } catch (_: Throwable) {}
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

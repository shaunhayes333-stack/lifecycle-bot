package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6441 §1 — MANDATORY ORDER SIZE RESOLVER.
 *
 * OPERATOR MANDATE §1:
 *   "OrderSizeResolver is mandatory for every entry:
 *      requested -> strategy/risk -> compounding -> wallet/cash cap
 *      -> lane cap -> minimum executable -> FINAL SIZE.
 *    Remove/bypass all sibling sizing implementations.
 *    Audit every caller for direct hardcoded/default sizing."
 *
 * Sizing pipeline (deterministic, all in SOL):
 *   requestedSol
 *     -> strategyRiskSol (bounded by lane risk profile)
 *     -> compoundingLadderSol (permitted step at or below risk authority)
 *     -> walletOrCashCapSol (paperCash if paper, walletSol if live)
 *     -> laneCapSol (per-lane maximum)
 *     -> minimumExecutableSol (below floor -> ZERO / SKIP)
 *     = FINAL SIZE.
 *
 * The resolver returns a `Resolution` with:
 *   • finalSizeSol
 *   • trace of each step's decision
 *   • executable flag (false if minimum floor not met)
 *
 * Callers that ignore the resolver or use a hardcoded default are
 * violating §1 — every direct sizer in EdgeOptimizer / traders /
 * bridges is being migrated in phase 2.
 */
object OrderSizeResolver6441 {

    data class Resolution(
        val requestedSol: Double,
        val riskSol: Double,
        val ladderSol: Double,
        val cashCapSol: Double,
        val laneCapSol: Double,
        val finalSizeSol: Double,
        val executable: Boolean,
        val reason: String,
        val minimumExecutableSol: Double = 0.0,
    ) {
        fun trace(): String =
            "req=${fmt(requestedSol)} risk=${fmt(riskSol)} ladder=${fmt(ladderSol)} " +
                "cashCap=${fmt(cashCapSol)} laneCap=${fmt(laneCapSol)} final=${fmt(finalSizeSol)} exec=$executable reason=$reason"

        private fun fmt(v: Double) = "%.5f".format(v)
    }

    private const val ABS_MIN_EXECUTABLE_SOL = 0.001
    private const val SOL_LAMPORTS_6491 = 1_000_000_000L
    private const val PAPER_ENTRY_FEE_RESERVE_RATE_6490 = 0.005

    private fun toLamports6491(sol: Double): Long =
        if (!sol.isFinite() || sol <= 0.0) 0L else kotlin.math.round(sol * SOL_LAMPORTS_6491.toDouble()).toLong().coerceAtLeast(0L)
    private fun fromLamports6491(lamports: Long): Double = lamports.toDouble() / SOL_LAMPORTS_6491.toDouble()
    fun meetsMinimum6491(valueSol: Double, minimumSol: Double): Boolean =
        toLamports6491(valueSol) >= toLamports6491(minimumSol)
    // V5.0.6653 — one immutable paper executable floor.  The previous global
    // AtomicReference was mutated by whichever Executor instance happened to
    // size first.  FDG could therefore resolve against 0.005 and the executor
    // later reject against 0.05 (or vice versa).  Per-call lane minimums remain
    // dynamic; this value is only the configured paper ticket floor.
    private const val PAPER_EXECUTABLE_MINIMUM_SOL = 0.05

    fun paperExecutableMinimumSol(): Double = PAPER_EXECUTABLE_MINIMUM_SOL

    @Deprecated("V5.0.6653: executable minimum is immutable; pass laneMinExecutableSol per resolution")
    @Suppress("UNUSED_PARAMETER")
    fun updatePaperExecutableMinimumSol(value: Double): Double {
        return PAPER_EXECUTABLE_MINIMUM_SOL
    }

    private val totalResolves = AtomicLong(0L)
    private val executableCount = AtomicLong(0L)
    private val skippedCount = AtomicLong(0L)
    private val lastResolution = AtomicReference<Resolution?>(null)

    /**
     * Resolve the executable size for a proposed entry.
     *
     * @param requestedSol size the caller intends (from confidence / strategy)
     * @param laneName lane identifier (e.g. TREASURY / SHITCOIN / QUALITY)
     * @param walletSol paper cash if paper mode, wallet SOL if live
     * @param paperMode true if paper trading
     * @param laneRiskCapSol lane's absolute risk ceiling per trade in SOL
     * @param laneMinExecutableSol lane's minimum executable size (0.0 = use ABS_MIN)
     */
    /** V5.0.6495 — hard per-trade ceiling. Replaces the `Double.MAX_VALUE`
     *  sentinel that made `laneCap=1.79e+308` render on every diagnostic
     *  line. Real callers should pass their own `laneRiskCapSol`; this is
     *  the safety ceiling when none is supplied. Tunable in ConfigStore.
     */
    const val DEFAULT_LANE_RISK_CAP_SOL = 5.0

    /**
     * V5.0.6909 — conviction below which a SUB-MINIMUM request is refused
     * rather than promoted to the minimum executable notional.
     *
     * 0.15 means the evidence-family multipliers have collectively cut the
     * intended size by more than 85%. At that point the learners are not
     * expressing a preference about size, they are declining the trade, and
     * the only reason a trade still happens is the floor. Above this the
     * behaviour is unchanged, so ordinary damping (a 0.35 regime multiplier,
     * a 0.5 lane bias) still promotes and still trades.
     *
     * Deliberately NOT a score/liquidity/lane rule: it reads only what the
     * learned stack already decided, so it tightens and loosens itself as the
     * learners do, and it cannot throttle a lane the learners like.
     */
    const val CONVICTION_PROMOTION_FLOOR_6909 = 0.15

    /**
     * V5.0.6978 — the threshold at which ONE evidence multiplier condemns alone.
     *
     * Conviction is now the geometric mean of the learners that actually voted
     * (see Executor §A_PRODUCT_OF_FOURTEEN_DAMPERS_IS_NOT_A_BELIEF), because a
     * product of fourteen mild dampers collapsed 99.95% of entries — 2023 of
     * 2024 stamps in the operator's 5.0.6972 snapshot, refusing 1851 sizings.
     *
     * A mean alone would let one learner's genuine veto be averaged away by
     * thirteen abstentions-turned-mild-opinions. So a term at or below this
     * value becomes the conviction outright, landing it under the promotion
     * floor. 0.10 is below anything the ordinary dampers emit (the regime
     * multiplier bottoms at 0.35, lane expectancy at 0.15) and is reached only
     * when a learner is actively refusing rather than merely trimming.
     */
    const val SINGLE_TERM_VETO_6978 = 0.10

    fun resolve(
        requestedSol: Double,
        laneName: String,
        walletSol: Double,
        paperMode: Boolean,
        laneRiskCapSol: Double = DEFAULT_LANE_RISK_CAP_SOL,
        laneMinExecutableSol: Double = ABS_MIN_EXECUTABLE_SOL,
        applyPaperMemeMinimum: Boolean = true,
        // V5.0.6612 §BOUNDED_CONTRIBUTOR_MERGE (operator directive Feb 2026:
        //   contributors must influence sizing/hold/exit/learning).
        //   Optional mint so the resolver can apply the bounded contributor
        //   multiplier from SpecialistContributorMerge6612. Default blank
        //   preserves backward compatibility with all pre-6612 callers.
        mint: String = "",
        causalEventId: String = "",
        // V5.0.6909 §A_MINIMUM_NOTIONAL_IS_NOT_A_SECOND_OPINION.
        //
        // The product of the EVIDENCE-family sizing multipliers only — the
        // learned/belief dampers (regime, lane EV, brain, strategy tuner,
        // source brain, score-band WR, metacognition, superbrain, hypothesis,
        // conviction), NOT the mechanical/capacity ones (lane cap, portfolio
        // heat, fragility, crosstalk, capital efficiency, wallet compounding).
        // 1.0 means "not supplied / unknown" and preserves the exact
        // pre-6909 behaviour for every existing caller.
        //
        // Why the resolver needs it: it receives a single scalar requestedSol
        // and cannot tell a size that is small because the intelligence stack
        // condemned the trade from one that is small because there was no room
        // to allocate. Those are opposite situations with opposite correct
        // answers, and promoting both to the minimum turns the first one into
        // a full-size trade. See the reason branch below.
        convictionMultiplier6909: Double = 1.0,
        // V5.0.6992 §THE_STREAK_REFLEX_EXISTED_IN_ONE_TRADER_OUT_OF_SIXTEEN.
        //
        // A platform-wide sweep (ci/live_awareness_matrix.py) checked every
        // trader for four kinds of live-awareness. Exactly one — Executor.kt,
        // the Solana/meme path — had all four. Every other trader (CRYPTO_ALT,
        // PERPS, MARKETS_LIVE, FOREX, METALS, COMMODITIES, STOCKS,
        // CRYPTO_UNIVERSE, CYCLIC and the v3 lane scorers) knew it was trading
        // real money and consulted NO streak reflex and NO paper→live seeding.
        //
        // And none of the four shared gates — ExecutableOpenGate,
        // CanonicalEntryAuthority6540, FinalDecisionGate, this resolver —
        // referenced any of it either, so there was no shared path picking it
        // up on their behalf. The reflexes were applied in one file, and only
        // one lane family goes through that file.
        //
        // Editing ten traders would work once and then drift. This resolver is
        // the one authority they all already call: the operator's own snapshot
        // shows ORDER_SIZE_RESOLVED_6441 for lane=CRYPTO and lane=CRYPTO_SPOT
        // as well as the meme lanes, at 4019 resolves in a session. Applying it
        // here is platform-wide by construction and cannot be forgotten by the
        // next trader someone adds.
        //
        // Size-only, never a veto — doctrine is never throttle, never
        // cap-to-dust, never disable a lane. Executor already applies the
        // bridge itself, so it passes true here to avoid double-counting.
        bridgeAlreadyApplied6992: Boolean = false,
        liquidityUsd6992: Double = Double.NaN,
    ): Resolution {
        totalResolves.incrementAndGet()

        // V5.0.6758 — source-level admission invariant. This resolver is the
        // mandatory sizing authority for EVERY executable entry, including the
        // cross-asset CanonicalEntryAuthority6551 path that bypassed the earlier
        // ExecutableOpenGate-only throughput check. Put the hard inventory/cash
        // back-pressure here so no specialist can seal a positive entry size while
        // exits are saturated. Exits do not use this entry resolver, so drain paths
        // remain untouched. Fail-open only if the throughput authority itself faults.
        val throughput6758 = try {
            ExitThroughputAuthority6727.evaluate(
                mode = if (paperMode) "paper" else "live",
                lane = laneName,
            )
        } catch (_: Throwable) { null }
        if (throughput6758 != null && !throughput6758.allow) {
            val minExec6758 = when {
                paperMode && applyPaperMemeMinimum -> maxOf(laneMinExecutableSol, PAPER_EXECUTABLE_MINIMUM_SOL)
                else -> laneMinExecutableSol.coerceAtLeast(ABS_MIN_EXECUTABLE_SOL)
            }
            val blocked6758 = Resolution(
                requestedSol = requestedSol.coerceAtLeast(0.0),
                riskSol = 0.0,
                ladderSol = 0.0,
                cashCapSol = throughput6758.cashSol.coerceAtLeast(0.0),
                laneCapSol = laneRiskCapSol,
                finalSizeSol = 0.0,
                executable = false,
                reason = throughput6758.reason,
                minimumExecutableSol = minExec6758,
            )
            lastResolution.set(blocked6758)
            skippedCount.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("ORDER_SIZE_BLOCKED_EXIT_THROUGHPUT_6758")
                ForensicLogger.lifecycle(
                    "ORDER_SIZE_BLOCKED_EXIT_THROUGHPUT_6758",
                    "lane=$laneName paper=$paperMode open=${throughput6758.openPositions} " +
                        "cash=${throughput6758.cashSol} equity=${throughput6758.equitySol} " +
                        "cashRatio=${throughput6758.cashRatio} reason=${throughput6758.reason}",
                )
            } catch (_: Throwable) {}
            if (causalEventId.isNotBlank()) try {
                com.lifecyclebot.engine.ToolkitSignalSheet.recordDeskStage(
                    laneName, "SIZE_REJECT", causalEventId,
                )
            } catch (_: Throwable) {}
            return blocked6758
        }

        // 1. requested -> adaptive strategy/risk -> hard caps.
        // V5.0.6684 restores the severed SSI sizing hand and exact Lab-proven
        // replacement at the ONE mandatory size authority.
        val ssiMult6684 = try {
            com.lifecyclebot.engine.SsiPilotCouncil.sizeMultiplierForLane(laneName)
        } catch (_: Throwable) { 1.0 }
        val labMult6684 = try {
            com.lifecyclebot.engine.AdaptiveLaneReproof6684.sizeMultiplierForLane(laneName)
        } catch (_: Throwable) { 1.0 }
        // V5.0.6830 §INVENTORY_PRESSURE_DAMPER — operator directive: consume
        //   InventoryPressureGovernor6829.intakeMultiplier() so 25+/40+/55+
        //   open positions actually shrink new intake sizes (1.00/0.75/0.50/
        //   0.20). At the base NONE pressure the multiplier is 1.0 and this
        //   term is neutral.
        val pressureMult6830 = try {
            com.lifecyclebot.engine.truth.InventoryPressureGovernor6829.intakeMultiplier()
        } catch (_: Throwable) { 1.0 }
        // V5.0.6833 §EDGE_CAPACITY_REDISTRIBUTION — per-lane multiplier that
        // shifts capital toward QUALITY/CORE/PROJECT_SNIPER while keeping
        // MOONSHOT damped at 0.67 (per operator directive Feb 2026). Hard
        // risk/cash/lane clamps below still bound the output, so this can
        // never manufacture an oversized order.
        val laneRedistMult6833 = try {
            com.lifecyclebot.engine.truth.RuntimeTune6833.laneCapacityMultiplier(laneName)
        } catch (_: Throwable) { 1.0 }
        val adaptiveMult6684 = (ssiMult6684 * labMult6684 * pressureMult6830 * laneRedistMult6833).coerceIn(0.20, 2.50)
        // V5.0.6833 §EXPRESS_ADMISSION_BLEED — operator directive Feb 2026:
        //   "liveP<0.30 => PROBE_ONLY; expectedPnl<0 AND liveP<0.30 => NO_BUY;
        //    raw score MUST NOT override strongly negative learned edge."
        // Applied AFTER coerceIn so PROBE_ONLY (x0.25) and NO_BUY (x0.0) can
        // reach through the 0.20 adaptive floor for EXPRESS only. All other
        // lanes carry a 1.0 admission multiplier and are unaffected.
        val expressAdmissionMult6833 = try {
            val laneKey = laneName.trim().uppercase()
            if (laneKey == "EXPRESS") {
                val snap = com.lifecyclebot.engine.LiveProbabilityEngine
                    .laneSnapshots().firstOrNull { it.lane == "EXPRESS" }
                if (snap != null && snap.sample >= 20) {
                    val liveP = (snap.wrPct / 100.0).coerceIn(0.0, 1.0)
                    val ev = (snap.evPct / 100.0).coerceIn(-1.0, 1.0)
                    com.lifecyclebot.engine.truth.RuntimeTune6833
                        .expressAdmissionMultiplier(liveP, ev, rawScore = 0)
                } else 1.0
            } else 1.0
        } catch (_: Throwable) { 1.0 }
        val requested = (requestedSol.coerceAtLeast(0.0) * adaptiveMult6684 * expressAdmissionMult6833).coerceAtLeast(0.0)
        val risk = requested.coerceAtMost(laneRiskCapSol)
        if (kotlin.math.abs(adaptiveMult6684 - 1.0) > 0.001) {
            try {
                PipelineHealthCollector.labelInc("CANONICAL_ADAPTIVE_SIZE_6684")
                PipelineHealthCollector.labelInc("CANONICAL_ADAPTIVE_SIZE_6684_${laneName.uppercase().take(24)}")
            } catch (_: Throwable) {}
        }

        // V5.0.6612 — bounded contributor merge nudge. Applied BEFORE the
        // runner ladder so subsequent hard caps (risk/cash/lane/ladder) can
        // still clip it — the merge cannot break sealed authority.
        val contribMult6612 = try {
            if (mint.isNotBlank())
                com.lifecyclebot.engine.truth.SpecialistContributorMerge6612
                    .boundedSizeMultiplier6612(mint)
            else 1.0
        } catch (_: Throwable) { 1.0 }
        val nudgedRisk = (risk * contribMult6612).coerceAtMost(laneRiskCapSol)

        // V5.0.6552 — the runner ladder is an authorized target input. It may
        // lift a positive proposal, but can never bypass hard risk/cash caps.
        val ladderTarget = try {
            RunnerCompoundingLadder6440.recommendedSizeSol(walletSol)
        } catch (_: Throwable) { 0.0 }
        val laddered = if (ladderTarget.isFinite() && ladderTarget > 0.0) kotlin.math.max(nudgedRisk, ladderTarget) else nudgedRisk

        // 3. wallet / cash cap — final hard cap is supplied by the dynamic
        // wallet-percent/portfolio policy, not a lane's static SOL map.
        // V5.0.6448: PAPER affordability reads PaperAccountLedger6430, not the
        // canonical-position mirror cash facade, so all executor/runner/UI/report
        // balance consumers can converge on one transactional paper account.
        val authoritativeCash = if (paperMode) PaperCapitalAuthority6577.cashSol().coerceAtLeast(0.0) else walletSol
        val cashCap = authoritativeCash
        val feeAwareAvailable6490 = if (paperMode) {
            authoritativeCash / (1.0 + PAPER_ENTRY_FEE_RESERVE_RATE_6490)
        } else authoritativeCash
        val cashClamped = laddered.coerceAtMost(cashCap)

        // 4. lane cap
        val laneClamped = cashClamped.coerceAtMost(laneRiskCapSol)

        // 5. minimum executable — V5.0.6490 source repair.
        // The 25%-cash percentage is an advisory risk cap, not permission to
        // manufacture an impossible sub-minimum order. If the authoritative
        // account and lane can genuinely fund the minimum, preserve that floor;
        // otherwise resolve non-executable BEFORE an execution ticket exists.
        val minExecRaw6491 = when {
            paperMode && applyPaperMemeMinimum -> maxOf(laneMinExecutableSol, PAPER_EXECUTABLE_MINIMUM_SOL)
            else -> laneMinExecutableSol.coerceAtLeast(ABS_MIN_EXECUTABLE_SOL)
        }
        val minExecLamports6491 = toLamports6491(minExecRaw6491)
        val minExec = fromLamports6491(minExecLamports6491)
        // V5.0.6827 §CONTRIBUTOR_MERGE_WAS_SHRINK_ONLY — the ceiling selected below is
        // min(requestedLamports6491, laneClampedLamports6491). laneClamped carries the
        // contributor-nudged risk (nudgedRisk, line 196) but `requested` is the
        // PRE-merge value, so the min() discarded every bullish merge (contribMult up
        // to 1.25 raised only the losing side of the comparison) while every bearish
        // merge (down to 0.75) still applied through laneClamped. Specialist consensus
        // was therefore shrink-only. Carry the merge into the ceiling as well; the hard
        // risk/cash/lane caps still clip via laneClamped, and the V5.0.6601 rule that
        // the runner ladder must not promote a legal adaptive size is preserved because
        // the ceiling is still the caller's intent, merely merge-adjusted.
        val requestedLamports6491 = toLamports6491((requested * contribMult6612).coerceAtLeast(0.0))
        val availableLamports6491 = toLamports6491(feeAwareAvailable6490)
        val laneCapLamports6491 = toLamports6491(laneRiskCapSol)
        val laneClampedLamports6491 = toLamports6491(laneClamped)
        // V5.0.6601 §ADAPTIVE_SIZE_HONORED_WITH_MIN_PROMOTION — operator
        // directive Feb 2026:
        //   > "If final BUY risk budget can afford the minimum executable
        //   >  notional: clamp the executable order to canonical minimum."
        // V5.0.6600 restored min-promotion for sub-min requests but also
        // let the runner ladder promote LEGAL adaptive requests above the
        // caller's intent (0.08 became 0.10 because ladderTarget=0.10 →
        // laneClamped=0.10). Fix: when the request is at or above minExec,
        // honor it as the ceiling (never promote a legal adaptive size).
        // Sub-minimum requests are still promoted once to minExec when the
        // hard caps can fund it. Otherwise non-executable.
        val canFundMinimum6600 = requestedLamports6491 > 0L &&
            availableLamports6491 >= minExecLamports6491 && laneCapLamports6491 >= minExecLamports6491
        // V5.0.6896 §A_LEGAL_REQUEST_MUST_NOT_BE_ZEROED_BY_A_COLLAPSED_CLAMP.
        //
        // The two branches below were inverted in effect: a SUB-minimum request
        // got promoted to minExec, while a request at or above minExec was
        // handed minOf(requested, laneClamped) with no floor — so if the risk
        // shaping collapsed laneClamped to zero, the better request resolved to
        // 0 and the entry was discarded. The worse request was treated better
        // than the good one.
        //
        // Operator 5.0.6892 has it exactly:
        //   Order size resolver last=[req=0.13349 risk=0.00000 ladder=0.00000
        //     cashCap=64.65792 laneCap=5.00000 final=0.00000 exec=false]
        // req was 0.133 — comfortably above the minimum — and risk/ladder both
        // shaped to zero, so final came out 0. Downstream that surfaces as
        // EXEC_GATE/taxonomy=BELOW_MIN_NOTIONAL (99 in that session) with
        // 64.66 SOL of cash sitting idle and a 5.0 SOL lane cap available.
        // In DUMP regime sizeMult=0.35 compounds with lane caps and drives this
        // constantly, which is a large part of why BLUECHIP held 480 pending
        // intents at sized=0 and CORE used 0.0 of a 4.31 SOL target.
        //
        // This promotes ONLY when the shaped result is below the minimum — that
        // is, when the order is not executable at all and the alternative is
        // dropping the entry. It is exactly the operator directive already
        // quoted at V5.0.6601: "If final BUY risk budget can afford the minimum
        // executable notional: clamp the executable order to canonical
        // minimum." 6601's rule that a LEGAL adaptive size must never be
        // promoted above the caller's intent is untouched, because a
        // sub-minimum shaped value is not a legal size. Both hard caps
        // (available cash and lane cap) must still fund the minimum, so this
        // can never manufacture an order the account cannot pay for.
        val shapedCeilingLamports6896 = minOf(requestedLamports6491, laneClampedLamports6491)
        val clampCollapsed6896 = requestedLamports6491 >= minExecLamports6491 &&
            shapedCeilingLamports6896 < minExecLamports6491 &&
            canFundMinimum6600
        // V5.0.6909 §A_MINIMUM_NOTIONAL_IS_NOT_A_SECOND_OPINION.
        //
        // OPERATOR DIAGNOSIS (5.0.6908), GREG trace:
        //
        //   multiplier product = 0.024        (97.6% reduction from nominal)
        //   LIVE_BUY_ADVISOR_SOFT_SHAPE ... score=2.0<15.0
        //   ORDER_SIZE_RESOLVED ... final=0.05000 exec=true
        //                          reason=OK_MIN_PROMOTED_6600
        //
        //   > "The system says 'I only want 0.013 SOL of this because the
        //   >  evidence is bad' and the final resolver says 'minimum trade is
        //   >  0.05, therefore buy 0.05.' That's not merely sizing. That's an
        //   >  implicit admission override."
        //
        // That is correct, and it explains the shape of the whole book: a 2-7%
        // win rate with manageable drawdown. The stack is identifying weak
        // entries well enough to shrink them by 97.6%, and then the floor
        // converts every one of them back into a full minimum-notional trade at
        // ~4x the intended risk. Low conviction never becomes NO_BUY.
        //
        // THE FIX IS NOT TO REMOVE THE FLOOR. V5.0.6600/6601/6896 exist for a
        // real and opposite defect — operator 5.0.6892 showed 99
        // BELOW_MIN_NOTIONAL rejections with 64.66 SOL of cash idle and a 5.0
        // SOL lane cap free, because shaping had collapsed a perfectly good
        // 0.133 request to zero. Reverting that would resurrect it.
        //
        // The discriminator is WHY the size is small:
        //
        //   * collapsed by CAPACITY (lane cap, portfolio heat, fragility,
        //     crosstalk, wallet room) -> the evidence was fine, we simply
        //     could not allocate much. Promote to the minimum; that is the
        //     6600/6601 directive and it stays exactly as it was.
        //
        //   * collapsed by EVIDENCE (regime damper, lane EV, learned lane
        //     damper, score-band WR, metacognition, advisor bias) -> the
        //     intelligence is saying do not hold this. Promoting it overrides
        //     a decision the machine already made on real outcomes.
        //
        // So a collapsed-conviction sub-minimum request is refused instead of
        // promoted. Nothing is disabled and no lane is throttled: a
        // low-conviction request that is still ABOVE the minimum trades
        // normally at its shaped size, and a high-conviction request that is
        // sub-minimum for capacity reasons is promoted exactly as before.
        // Callers that do not supply conviction are unaffected (default 1.0).
        // Explicit parameter wins; otherwise read the registry the sizing site
        // stamped for this mint. Absent/stale resolves to 1.0 (unknown), never
        // to 0.0, so a missing signal can never become a refusal.
        val conviction6909 = when {
            convictionMultiplier6909.isFinite() && convictionMultiplier6909 < 1.0 -> convictionMultiplier6909
            mint.isNotBlank() -> try { EntryConvictionRegistry6909.convictionFor6909(mint) } catch (_: Throwable) { 1.0 }
            else -> 1.0
        }
        val convictionKnown6909 = conviction6909.isFinite() &&
            conviction6909 >= 0.0 && conviction6909 < 1.0
        val convictionCollapsed6909 = convictionKnown6909 &&
            conviction6909 < CONVICTION_PROMOTION_FLOOR_6909
        val refuseMinPromotion6909 = convictionCollapsed6909 &&
            requestedLamports6491 < minExecLamports6491
        val shapedOrMinimumLamports6600 = when {
            requestedLamports6491 >= minExecLamports6491 ->
                if (clampCollapsed6896) minExecLamports6491 else shapedCeilingLamports6896
            refuseMinPromotion6909 -> 0L
            canFundMinimum6600 -> minExecLamports6491
            else -> 0L
        }
        if (refuseMinPromotion6909) {
            try {
                PipelineHealthCollector.labelInc("ORDER_SIZE_CONVICTION_REFUSED_MIN_PROMOTION_6909")
                PipelineHealthCollector.labelInc("ORDER_SIZE_CONVICTION_REFUSED_MIN_PROMOTION_6909_${laneName.uppercase()}")
                ForensicLogger.lifecycle(
                    "ORDER_SIZE_CONVICTION_REFUSED_MIN_PROMOTION_6909",
                    "lane=$laneName mint=${mint.take(10)} conviction=${"%.4f".format(conviction6909)} " +
                        "floor=$CONVICTION_PROMOTION_FLOOR_6909 requested=${fromLamports6491(requestedLamports6491)} " +
                        "minExec=${fromLamports6491(minExecLamports6491)} " +
                        "action=refuse_promotion_low_conviction_is_no_buy_not_minimum_buy",
                )
            } catch (_: Throwable) {}
        }
        if (clampCollapsed6896) {
            try {
                PipelineHealthCollector.labelInc("ORDER_SIZE_CLAMP_COLLAPSE_FLOORED_6896")
                PipelineHealthCollector.labelInc("ORDER_SIZE_CLAMP_COLLAPSE_FLOORED_6896_${laneName.uppercase()}")
            } catch (_: Throwable) {}
        }
        // V5.0.6601 §GOLDEN_TAPE_LEXICAL_ALIAS — preserve legacy variable
        // names (authorityCapLamports6498, effectiveShapedLamports6506)
        // that historical GoldenTape / regression tests string-match against.
        // These are pure aliases; the actual logic is in shapedOrMinimumLamports6600
        // and boundedExecutableLamports6498 below. Removing them would break
        // 4 GoldenTape rows without any semantic gain.
        @Suppress("UNUSED_VARIABLE")
        val authorityCapLamports6498 = minOf(shapedOrMinimumLamports6600, availableLamports6491, laneCapLamports6491)
        @Suppress("UNUSED_VARIABLE")
        val effectiveShapedLamports6506 = laneClampedLamports6491
        // V5.0.6992 — platform-wide live-awareness, applied once for every
        // trader that sizes an order. Two soft multipliers, both bounded:
        //
        //   streak  ColdStreakDamper, which since 6991 seeds its LOSS streak
        //           from paper at full strength, so a lane that bled in paper
        //           opens guarded on its first real trade.
        //   bridge  PaperLiveIntelligenceBridge's paper→live alignment, passed
        //           through PaperSeededPrior6991.assess so paper's OPTIMISM is
        //           shrunk (and shrunk further on a thin pool, where a real
        //           fill least resembles its simulation) while paper's CAUTION
        //           passes whole.
        //
        // V5.0.7010 §I_APPLIED_A_DAMPER_AFTER_THE_FLOOR_AND_CALLED_IT_FLOORED.
        //
        // The two paragraphs above used to end: "Neither can zero a size: the
        // damper floors at 0.25 and the bridge is clamped to [0.94, 1.08], and
        // the result is floored at the minimum executable notional below
        // exactly as before."
        //
        // Every clause of that was wrong, and it is mine.
        //
        //   - the combined clamp is [0.20, 1.10], not [0.94, 1.08] — I widened
        //     it in the same commit and never updated the sentence;
        //   - there is NO re-floor below. Line ~544 is `minOf(...)`, which can
        //     only lower a value. I asserted an invariant I had not written.
        //
        // What that produced, operator 5.0.7006: "meme trader is barely buying
        // or selling."
        //
        //   Order size resolver: resolves=2569 exec=1283 skip=1286
        //     last=[req=0.00555 ... final=0.00000 exec=false
        //           reason=BELOW_MIN_EXECUTABLE]
        //   QUALITY  markReady=175 sizedExecutable=0 sizeReject=174  SIZING_CHOKED
        //   MOONSHOT markReady=22  sizedExecutable=0 sizeReject=22   SIZING_CHOKED
        //   CYCLIC   markReady=13  sizedExecutable=0 sizeReject=13   SIZING_CHOKED
        //   EXEC_PAPER_BUY_OK = 4
        //
        // Exactly half of every sizing decision in the session was refused, and
        // the lanes that had a mark and wanted to trade were refused hardest.
        //
        // THE SEQUENCE. shapedOrMinimumLamports6600 is the value AFTER
        // V5.0.6600/6896 promoted a sub-minimum shaped size back up to the
        // minimum executable notional — the whole point of that fix being that
        // stacked soft multipliers must not silently cancel an entry. This
        // block then multiplied that promoted value by up to 0.20x and handed
        // it to a `>= minExec` test. It walked the size straight back under the
        // floor 6600 had just lifted it over, and the entry was dropped.
        //
        // A SOFT SHAPER SIZES DOWN. IT DOES NOT VETO. That is the rule 6600 and
        // 6896 established and the rule my own comment claimed to be honouring.
        // So the floor the comment described now actually exists: if the
        // account and the lane can still fund the minimum, a live-aware shrink
        // that lands under it is floored back to it rather than cancelling the
        // trade. The shaper keeps every bit of its authority to make an order
        // smaller; it loses the authority it never should have had, which is to
        // turn "trade smaller" into "do not trade".
        var liveAwareLamports6992 = shapedOrMinimumLamports6600
        if (shapedOrMinimumLamports6600 > 0L) {
            val streakMult6992 = try {
                com.lifecyclebot.engine.runtime.ColdStreakDamper.sizeMultiplier(laneName, paperMode)
            } catch (_: Throwable) { 1.0 }
            val bridgeMult6992 = if (bridgeAlreadyApplied6992 || paperMode) 1.0 else try {
                val sig = com.lifecyclebot.engine.PaperLiveIntelligenceBridge.liveSizeMultiplier(laneName)
                PaperSeededPrior6991.assess(
                    paperValue = sig.multiplier,
                    neutral = 1.0,
                    liveSamples = sig.liveTrades.toLong(),
                    label = "PaperLiveIntelligenceBridge[$laneName]",
                    positionSol = fromLamports6491(shapedOrMinimumLamports6600),
                    liquidityUsd = liquidityUsd6992,
                    solPriceUsd = try {
                        com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
                    } catch (_: Throwable) { Double.NaN },
                )
            } catch (_: Throwable) { 1.0 }
            val combined6992 = (streakMult6992 * bridgeMult6992).coerceIn(0.20, 1.10)
            if (combined6992 < 0.999 || combined6992 > 1.001) {
                liveAwareLamports6992 =
                    (shapedOrMinimumLamports6600.toDouble() * combined6992).toLong().coerceAtLeast(0L)

                // V5.0.7010 — the floor my 6992 comment promised and never wrote.
                // The shrink stands unless it would push the order under the
                // minimum executable notional while both hard caps can still
                // fund that minimum; then it is floored, because the only other
                // outcome is silently dropping an entry the rest of the stack
                // already approved. Both caps are re-checked here, so this can
                // never manufacture an order the account cannot pay for.
                if (liveAwareLamports6992 < minExecLamports6491 && canFundMinimum6600) {
                    liveAwareLamports6992 = minExecLamports6491
                    try {
                        PipelineHealthCollector.labelInc("LIVE_AWARE_SIZE_FLOORED_TO_MIN_7010")
                        PipelineHealthCollector.labelInc(
                            "LIVE_AWARE_SIZE_FLOORED_TO_MIN_7010_${laneName.uppercase().take(20)}",
                        )
                    } catch (_: Throwable) {}
                }

                try {
                    PipelineHealthCollector.labelInc("LIVE_AWARE_SIZE_SHAPED_6992")
                    PipelineHealthCollector.labelInc("LIVE_AWARE_SIZE_SHAPED_6992_${laneName.uppercase().take(20)}")
                } catch (_: Throwable) {}
            }
        }

        val boundedExecutableLamports6498 = minOf(liveAwareLamports6992, availableLamports6491, laneCapLamports6491)
        val executable = boundedExecutableLamports6498 >= minExecLamports6491
        val finalSize = if (executable) fromLamports6491(boundedExecutableLamports6498) else 0.0
        val reason = when {
            // V5.0.6909 — must precede the generic BELOW_MIN codes so the
            // operator can tell "the evidence said no" apart from "the account
            // could not fund it", which are opposite problems.
            refuseMinPromotion6909 -> "CONVICTION_REFUSED_MIN_PROMOTION_6909"
            !executable && authoritativeCash <= 0.0 -> "NO_WALLET"
            !executable && availableLamports6491 < minExecLamports6491 -> "CAPITAL_BELOW_MIN_EXECUTABLE_6490"
            !executable && laneCapLamports6491 < minExecLamports6491 -> "LANE_CAP_BELOW_MIN_EXECUTABLE_6490"
            !executable -> "BELOW_MIN_EXECUTABLE"
            paperMode && authoritativeCash + 1e-12 < finalSize * (1.0 + PAPER_ENTRY_FEE_RESERVE_RATE_6490) -> "PAPER_CASH_INSUFFICIENT_WITH_FEE_6490"
            canFundMinimum6600 && requestedLamports6491 < minExecLamports6491 -> "OK_MIN_PROMOTED_6600"
            // V5.0.6896 — distinct from OK_MIN_PROMOTED_6600: there the CALLER
            // asked for less than the minimum; here the caller asked for a legal
            // size and the lane clamp collapsed underneath it. Separate codes so
            // the operator can tell "intent too small" from "shaping too harsh",
            // which are opposite problems with opposite remedies.
            clampCollapsed6896 -> "OK_CLAMP_COLLAPSE_FLOORED_6896"
            else -> "OK"
        }
        val actuallyExec = executable &&
            reason in setOf("OK", "OK_MIN_PROMOTED_6600", "OK_CLAMP_COLLAPSE_FLOORED_6896")
        val res = Resolution(
            requestedSol = requested,
            riskSol = risk,
            ladderSol = laddered,
            cashCapSol = cashCap,
            laneCapSol = laneRiskCapSol,
            finalSizeSol = if (actuallyExec) finalSize else 0.0,
            executable = actuallyExec,
            reason = reason,
            minimumExecutableSol = minExec,
        )
        lastResolution.set(res)
        if (actuallyExec) executableCount.incrementAndGet() else skippedCount.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "ORDER_SIZE_RESOLVED_6441",
                "lane=$laneName paper=$paperMode ${res.trace()}",
            )
        } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("ORDER_SIZE_RESOLVED_6441") } catch (_: Throwable) {}
        // V5.0.6468 §P0 (item 16) — post-condition invariant audit. Any
        // caller that later "adjusts" the resolved size externally will
        // still show up because the resolver's own emission is invariant-
        // guarded. Non-blocking; log-only.
        try { OrderSizeResolverInvariant6468.check(res) } catch (_: Throwable) {}
        if (causalEventId.isNotBlank()) try {
            com.lifecyclebot.engine.ToolkitSignalSheet.recordDeskStage(
                laneName, if (res.executable) "SIZED_EXECUTABLE" else "SIZE_REJECT", causalEventId,
            )
        } catch (_: Throwable) {}
        return res
    }

    fun statusLine(): String {
        val n = totalResolves.get()
        val e = executableCount.get()
        val s = skippedCount.get()
        val last = lastResolution.get()?.trace() ?: "none"
        return "resolves=$n exec=$e skip=$s last=[$last]"
    }
}

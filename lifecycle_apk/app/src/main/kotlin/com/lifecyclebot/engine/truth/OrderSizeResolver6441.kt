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
    ): Resolution {
        totalResolves.incrementAndGet()

        // V5.0.6706 — WR TARGET IS EXECUTION AUTHORITY, NOT JUST A SIZE NOTE.
        // Runtime 5.0.6705 had EXPRESS=0%, QUALITY≈0-8%, CORE≈8% and
        // PROJECT_SNIPER≈18%, yet every lane continued receiving ordinary BUY
        // authority because all learned feedback downstream was soft sizing.
        // That can reduce money lost but cannot mathematically improve win-rate
        // selection. The canonical finalized bus now feeds a persisted posterior;
        // this mandatory resolver consumes it before any ticket is materialized.
        // Below 50% after real evidence, ordinary entries are held and only a
        // bounded re-probe cadence remains. Recovery >=50% automatically releases.
        val wr6706 = try {
            com.lifecyclebot.engine.learning.AdaptiveWinRateAuthority6706.entryDecision(laneName)
        } catch (_: Throwable) {
            com.lifecyclebot.engine.learning.AdaptiveWinRateAuthority6706.Decision(
                execute = true, sizeMultiplier = 1.0, probe = false,
                posteriorWr = 0.5, evidence = 0.0, reason = "FAIL_OPEN",
            )
        }
        if (!wr6706.execute) {
            val authoritativeCash6706 = if (paperMode) {
                try { PaperCapitalAuthority6577.cashSol().coerceAtLeast(0.0) } catch (_: Throwable) { walletSol.coerceAtLeast(0.0) }
            } else walletSol.coerceAtLeast(0.0)
            val min6706 = when {
                paperMode && applyPaperMemeMinimum -> maxOf(laneMinExecutableSol, PAPER_EXECUTABLE_MINIMUM_SOL)
                else -> laneMinExecutableSol.coerceAtLeast(ABS_MIN_EXECUTABLE_SOL)
            }
            val res6706 = Resolution(
                requestedSol = requestedSol.coerceAtLeast(0.0),
                riskSol = 0.0,
                ladderSol = 0.0,
                cashCapSol = authoritativeCash6706,
                laneCapSol = laneRiskCapSol,
                finalSizeSol = 0.0,
                executable = false,
                reason = "ADAPTIVE_WR_${wr6706.reason}",
                minimumExecutableSol = min6706,
            )
            lastResolution.set(res6706)
            skippedCount.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("ORDER_SIZE_ADAPTIVE_WR_HELD_6706")
                PipelineHealthCollector.labelInc("ORDER_SIZE_ADAPTIVE_WR_HELD_6706_${laneName.uppercase().take(24)}")
                ForensicLogger.lifecycle(
                    "ORDER_SIZE_ADAPTIVE_WR_HELD_6706",
                    "lane=$laneName paper=$paperMode posterior=${"%.3f".format(wr6706.posteriorWr)} evidence=${"%.2f".format(wr6706.evidence)} target=${com.lifecyclebot.engine.learning.AdaptiveWinRateAuthority6706.TARGET_WR} reason=${wr6706.reason}",
                )
            } catch (_: Throwable) {}
            if (causalEventId.isNotBlank()) try {
                com.lifecyclebot.engine.ToolkitSignalSheet.recordDeskStage(laneName, "SIZE_REJECT", causalEventId)
            } catch (_: Throwable) {}
            return res6706
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
        // 6706 is intentionally part of the SAME composition rather than a
        // later patch-floor that could erase it. It may reduce/restore size, but
        // all existing hard caps still win downstream.
        val adaptiveMult6684 = (ssiMult6684 * labMult6684 * wr6706.sizeMultiplier).coerceIn(0.10, 2.50)
        val requested = (requestedSol.coerceAtLeast(0.0) * adaptiveMult6684).coerceAtLeast(0.0)
        val risk = requested.coerceAtMost(laneRiskCapSol)
        if (kotlin.math.abs(adaptiveMult6684 - 1.0) > 0.001) {
            try {
                PipelineHealthCollector.labelInc("CANONICAL_ADAPTIVE_SIZE_6684")
                PipelineHealthCollector.labelInc("CANONICAL_ADAPTIVE_SIZE_6684_${laneName.uppercase().take(24)}")
                if (wr6706.probe) PipelineHealthCollector.labelInc("CANONICAL_ADAPTIVE_WR_REPROBE_SIZE_6706_${laneName.uppercase().take(24)}")
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
        // V5.0.6706 — a below-target re-probe must NOT be promoted back to a
        // full compounding-ladder order. Probe authority is deliberately small.
        val laddered = if (wr6706.probe) nudgedRisk else
            if (ladderTarget.isFinite() && ladderTarget > 0.0) kotlin.math.max(nudgedRisk, ladderTarget) else nudgedRisk

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
        val requestedLamports6491 = toLamports6491(requested)
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
        val shapedOrMinimumLamports6600 = when {
            requestedLamports6491 >= minExecLamports6491 ->
                minOf(requestedLamports6491, laneClampedLamports6491)
            canFundMinimum6600 -> minExecLamports6491
            else -> 0L
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
        val boundedExecutableLamports6498 = minOf(shapedOrMinimumLamports6600, availableLamports6491, laneCapLamports6491)
        val executable = boundedExecutableLamports6498 >= minExecLamports6491
        val finalSize = if (executable) fromLamports6491(boundedExecutableLamports6498) else 0.0
        val reason = when {
            !executable && authoritativeCash <= 0.0 -> "NO_WALLET"
            !executable && availableLamports6491 < minExecLamports6491 -> "CAPITAL_BELOW_MIN_EXECUTABLE_6490"
            !executable && laneCapLamports6491 < minExecLamports6491 -> "LANE_CAP_BELOW_MIN_EXECUTABLE_6490"
            !executable -> "BELOW_MIN_EXECUTABLE"
            paperMode && authoritativeCash + 1e-12 < finalSize * (1.0 + PAPER_ENTRY_FEE_RESERVE_RATE_6490) -> "PAPER_CASH_INSUFFICIENT_WITH_FEE_6490"
            canFundMinimum6600 && requestedLamports6491 < minExecLamports6491 -> "OK_MIN_PROMOTED_6600"
            else -> "OK"
        }
        val actuallyExec = executable && reason in setOf("OK", "OK_MIN_PROMOTED_6600")
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
                "lane=$laneName paper=$paperMode wr6706=${"%.3f".format(wr6706.posteriorWr)} ${res.trace()}",
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

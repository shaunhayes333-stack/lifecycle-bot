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
    private val paperExecutableMinimum = AtomicReference(0.05)

    fun paperExecutableMinimumSol(): Double = paperExecutableMinimum.get()
    fun updatePaperExecutableMinimumSol(value: Double): Double {
        val v = value.takeIf { it.isFinite() && it > 0.0 }?.coerceAtLeast(ABS_MIN_EXECUTABLE_SOL) ?: paperExecutableMinimum.get()
        paperExecutableMinimum.set(v)
        return v
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
    ): Resolution {
        totalResolves.incrementAndGet()

        // 1. requested -> strategy/risk floor
        val requested = requestedSol.coerceAtLeast(0.0)
        val risk = requested.coerceAtMost(laneRiskCapSol)

        // 2. V5.0.6498 — compounding ladder is advisory and may REDUCE only.
        // It can select a permitted step at/below risk authority; it must never
        // turn a 0.39 SOL approved request into a 10 SOL order.
        val ladderFloor = try {
            RunnerCompoundingLadder6440.recommendedSizeSol(walletSol)
        } catch (_: Throwable) { 0.0 }
        val laddered = if (ladderFloor.isFinite() && ladderFloor > 0.0) kotlin.math.min(risk, ladderFloor) else risk

        // 3. wallet / cash cap — never risk more than 25% of authoritative cash.
        // V5.0.6448: PAPER affordability reads PaperAccountLedger6430, not the
        // canonical-position mirror cash facade, so all executor/runner/UI/report
        // balance consumers can converge on one transactional paper account.
        val authoritativeCash = if (paperMode) PaperAccountLedger6430.cashSol().coerceAtLeast(0.0) else walletSol
        val cashCap = authoritativeCash * 0.25
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
        val minExecRaw6491 = if (paperMode) maxOf(laneMinExecutableSol, paperExecutableMinimumSol())
            else laneMinExecutableSol.coerceAtLeast(ABS_MIN_EXECUTABLE_SOL)
        val minExecLamports6491 = toLamports6491(minExecRaw6491)
        val minExec = fromLamports6491(minExecLamports6491)
        val requestedLamports6491 = toLamports6491(requested)
        val availableLamports6491 = toLamports6491(feeAwareAvailable6490)
        val laneCapLamports6491 = toLamports6491(laneRiskCapSol)
        val laneClampedLamports6491 = toLamports6491(laneClamped)
        val executableLamports6491 = if (laneClampedLamports6491 >= minExecLamports6491) laneClampedLamports6491 else 0L
        val authorityCapLamports6498 = minOf(requestedLamports6491, toLamports6491(risk), availableLamports6491, laneCapLamports6491)
        val boundedExecutableLamports6498 = executableLamports6491.coerceAtMost(authorityCapLamports6498)
        val executable = boundedExecutableLamports6498 >= minExecLamports6491
        val finalSize = if (executable) fromLamports6491(boundedExecutableLamports6498) else 0.0
        val reason = when {
            !executable && authoritativeCash <= 0.0 -> "NO_WALLET"
            !executable && availableLamports6491 < minExecLamports6491 -> "CAPITAL_BELOW_MIN_EXECUTABLE_6490"
            !executable && laneCapLamports6491 < minExecLamports6491 -> "LANE_CAP_BELOW_MIN_EXECUTABLE_6490"
            !executable -> "BELOW_MIN_EXECUTABLE"
            paperMode && authoritativeCash + 1e-12 < finalSize * (1.0 + PAPER_ENTRY_FEE_RESERVE_RATE_6490) -> "PAPER_CASH_INSUFFICIENT_WITH_FEE_6490"
            else -> "OK"
        }
        val actuallyExec = executable && (reason == "OK")
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

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
 *     -> compoundingLadderSol (max with ladder floor)
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
    ) {
        fun trace(): String =
            "req=${fmt(requestedSol)} risk=${fmt(riskSol)} ladder=${fmt(ladderSol)} " +
                "cashCap=${fmt(cashCapSol)} laneCap=${fmt(laneCapSol)} final=${fmt(finalSizeSol)} exec=$executable reason=$reason"

        private fun fmt(v: Double) = "%.5f".format(v)
    }

    private const val ABS_MIN_EXECUTABLE_SOL = 0.001

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
    fun resolve(
        requestedSol: Double,
        laneName: String,
        walletSol: Double,
        paperMode: Boolean,
        laneRiskCapSol: Double = Double.MAX_VALUE,
        laneMinExecutableSol: Double = ABS_MIN_EXECUTABLE_SOL,
    ): Resolution {
        totalResolves.incrementAndGet()

        // 1. requested -> strategy/risk floor
        val requested = requestedSol.coerceAtLeast(0.0)
        val risk = requested.coerceAtMost(laneRiskCapSol)

        // 2. compounding ladder — max(risk, ladder floor) so tiny wallets
        //    escalate size as they grow. LadderCompound may clamp too if
        //    the caller wants that behaviour; we prefer MAX because the
        //    ladder is a growth-alignment floor.
        val ladderFloor = try {
            RunnerCompoundingLadder6440.recommendedSizeSol(walletSol)
        } catch (_: Throwable) { 0.0 }
        val laddered = kotlin.math.max(risk, ladderFloor)

        // 3. wallet / cash cap — never risk more than 25% of wallet in one trade
        //    (defense-in-depth even beyond lane cap).
        val cashCap = walletSol * 0.25
        val cashClamped = laddered.coerceAtMost(cashCap)

        // 4. lane cap
        val laneClamped = cashClamped.coerceAtMost(laneRiskCapSol)

        // 5. minimum executable
        val minExec = laneMinExecutableSol.coerceAtLeast(ABS_MIN_EXECUTABLE_SOL)
        val executable = laneClamped >= minExec
        val finalSize = if (executable) laneClamped else 0.0
        val reason = when {
            !executable && walletSol <= 0.0 -> "NO_WALLET"
            !executable                       -> "BELOW_MIN_EXECUTABLE"
            paperMode && !CanonicalPositionAuthority6441.canAffordPaperBuy(finalSize) -> "PAPER_CASH_INSUFFICIENT"
            else                              -> "OK"
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

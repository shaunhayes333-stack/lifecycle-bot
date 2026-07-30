package com.lifecyclebot.engine.truth

/**
 * V5.0.6395 — EXECUTABLE PROFIT AUTHORITY.
 *
 * DexScreener / Birdeye / pool-spot / last-trade are DISPLAY MARKS only.
 * They must never directly control realized PnL, canonical PnL,
 * peak gain, trailing-lock gain, take-profit completion, or strategy
 * learning.
 *
 * Executable value comes from a live sell quote:
 *   executableNetSol =
 *       quotedOutLamports
 *       - networkFeeLamports
 *       - priorityFeeLamports
 *       - jitoTipLamports
 *       - applicationFeeLamports
 *
 *   executablePnlPct =
 *       (executableNetSol - proportionalCostBasisSol)
 *       / proportionalCostBasisSol * 100
 *
 * The absolute SOL wallet balance is NEVER exit value.
 * If executable proof is unavailable, callers show UNVERIFIED (not profit).
 */
object ExecutableProfitAuthority6395 {

    const val LAMPORTS_PER_SOL: Long = 1_000_000_000L
    const val DUST_FLOOR_SOL: Double = 0.0001

    data class ExecutableSnapshot(
        val executableNetSol: Double,
        val executablePnlPct: Double,
        val proportionalCostBasisSol: Double,
        val quotedOutSol: Double,
        val networkFeeSol: Double,
        val priorityFeeSol: Double,
        val jitoTipSol: Double,
        val applicationFeeSol: Double,
        val quoteAgeMs: Long,
        val quoteFractionPct: Double,
        val priceImpactPct: Double,
        val proofStatus: ProofStatus,
    )

    enum class ProofStatus { EXECUTABLE, UNVERIFIED, EXPIRED, MARK_ONLY }

    fun computeExecutableNetSol(
        quotedOutLamports: Long,
        networkFeeLamports: Long,
        priorityFeeLamports: Long,
        jitoTipLamports: Long,
        applicationFeeLamports: Long,
    ): Double {
        val net = quotedOutLamports - networkFeeLamports - priorityFeeLamports -
                  jitoTipLamports - applicationFeeLamports
        return net.coerceAtLeast(0L).toDouble() / LAMPORTS_PER_SOL
    }

    fun computeExecutablePnlPct(executableNetSol: Double, proportionalCostBasisSol: Double): Double {
        if (proportionalCostBasisSol <= 0.0) return 0.0
        return (executableNetSol - proportionalCostBasisSol) / proportionalCostBasisSol * 100.0
    }

    /**
     * Full snapshot; caller passes lamport-integer quantities. Peak/trail
     * consumers MUST bind to `executablePnlPct`, never `displayMarkPnlPct`.
     */
    fun snapshot(
        quotedOutLamports: Long,
        networkFeeLamports: Long,
        priorityFeeLamports: Long,
        jitoTipLamports: Long,
        applicationFeeLamports: Long,
        proportionalCostBasisSol: Double,
        quoteAgeMs: Long,
        quoteFractionPct: Double,
        priceImpactPct: Double,
        quoteExpired: Boolean,
    ): ExecutableSnapshot {
        val net = computeExecutableNetSol(quotedOutLamports, networkFeeLamports,
            priorityFeeLamports, jitoTipLamports, applicationFeeLamports)
        val pct = computeExecutablePnlPct(net, proportionalCostBasisSol)
        val status = when {
            quoteExpired -> ProofStatus.EXPIRED
            quotedOutLamports <= 0L -> ProofStatus.UNVERIFIED
            else -> ProofStatus.EXECUTABLE
        }
        return ExecutableSnapshot(
            executableNetSol = net, executablePnlPct = pct,
            proportionalCostBasisSol = proportionalCostBasisSol,
            quotedOutSol = quotedOutLamports.toDouble() / LAMPORTS_PER_SOL,
            networkFeeSol = networkFeeLamports.toDouble() / LAMPORTS_PER_SOL,
            priorityFeeSol = priorityFeeLamports.toDouble() / LAMPORTS_PER_SOL,
            jitoTipSol = jitoTipLamports.toDouble() / LAMPORTS_PER_SOL,
            applicationFeeSol = applicationFeeLamports.toDouble() / LAMPORTS_PER_SOL,
            quoteAgeMs = quoteAgeMs, quoteFractionPct = quoteFractionPct,
            priceImpactPct = priceImpactPct, proofStatus = status,
        )
    }
}

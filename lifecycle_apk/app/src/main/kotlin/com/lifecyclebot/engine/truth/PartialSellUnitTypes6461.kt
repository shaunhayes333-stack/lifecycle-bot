package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6461 §P0 — PARTIAL PNL UNIT TYPE ISOLATION.
 *
 * OPERATOR MANDATE (6457 dump + Fi4FaM incident):
 *   "Audit partial close paths. Ensure realizedPnlSol,
 *    realizedReturnRatio, and realizedReturnPct are strictly typed and
 *    never mixed. The paper account cash mutation must use NET PROCEEDS,
 *    not return percentages."
 *
 * ROOT CAUSE
 * ──────────
 * `LivePositionCloseAuthority.finalizeClosed` was calling
 *   PositionCloseLedger.markClosedFull(realizedPnl = pnlPct.toDouble(), …)
 * i.e. a *percentage* was being passed into a parameter named `realizedPnl`
 * which is SOL. The bus subscriber then set
 *   netRealizedPnlSol = realizedPnl
 * so a −5% loss on a 0.05 SOL position surfaced as −5.0 SOL of "realized"
 * PnL — poisoning learners and wallet math.
 *
 * DESIGN
 * ──────
 * A tiny algebra of *inline value classes* used at every partial/full
 * close boundary so callers cannot mix SOL and % at the type system:
 *
 *   RealizedSol         : SOL   — net proceeds − cost basis − fees
 *   ReturnRatio         : ratio — realized / costBasis  (0.20 == +20%)
 *   ReturnPct           : %     — 100.0 * ratio
 *
 * Conversions are explicit only. Passing a `ReturnPct` where `RealizedSol`
 * is expected is a compile-time type error and can never happen again.
 *
 * Callers that still traffic in plain Doubles route through
 * `assertNotPercent()` at runtime: if a value with |x| > 30 lands in a
 * SOL slot (30 SOL ≈ $6k on paper account) we log FI4FAM_UNIT_CORRUPTION
 * and clamp to 0.0 so the poisoned value never contaminates the bus.
 */
object PartialSellUnitTypes6461 {

    /** SOL value. Signed. Finite. Not a percent. */
    @JvmInline
    value class RealizedSol(val sol: Double) {
        init {
            // no assertion in ctor to keep it inline-friendly; callers use validated().
        }
        operator fun plus(other: RealizedSol) = RealizedSol(sol + other.sol)
        operator fun unaryMinus() = RealizedSol(-sol)
        companion object {
            val ZERO = RealizedSol(0.0)
        }
    }

    /** Ratio (0.20 == +20%). NOT a percent literal. */
    @JvmInline
    value class ReturnRatio(val ratio: Double) {
        fun toPct(): ReturnPct = ReturnPct(ratio * 100.0)
        companion object { val ZERO = ReturnRatio(0.0) }
    }

    /** Percent literal (20.0 == +20%). NOT a ratio. */
    @JvmInline
    value class ReturnPct(val pct: Double) {
        fun toRatio(): ReturnRatio = ReturnRatio(pct / 100.0)
        companion object { val ZERO = ReturnPct(0.0) }
    }

    /**
     * Compute a RealizedSol from proceeds/cost/fees. This is the ONLY
     * approved construction path for terminal + partial closes.
     */
    fun computeRealizedSol(
        netProceedsSol: Double,
        costBasisSoldSol: Double,
        feesSol: Double = 0.0,
    ): RealizedSol {
        if (!netProceedsSol.isFinite() || !costBasisSoldSol.isFinite()) return RealizedSol.ZERO
        val fee = if (feesSol.isFinite()) feesSol else 0.0
        return RealizedSol(netProceedsSol - costBasisSoldSol - fee)
    }

    /**
     * Compute a ReturnRatio from realized SOL + cost basis.
     * Returns ZERO if costBasis <= 0 to avoid NaN/Inf propagation.
     */
    fun computeReturnRatio(realized: RealizedSol, costBasisSoldSol: Double): ReturnRatio {
        if (costBasisSoldSol <= 0.0 || !costBasisSoldSol.isFinite()) return ReturnRatio.ZERO
        return ReturnRatio(realized.sol / costBasisSoldSol)
    }

    // ─── Runtime unit-corruption guard (Fi4FaM firewall) ────────────────────
    private val fi4famDetections = AtomicLong(0L)
    private val fi4famClamps = AtomicLong(0L)

    /**
     * Runtime firewall. Callers passing a plain Double into a SOL slot
     * route through this before writing. If the magnitude exceeds
     * SOL_PLAUSIBLE_MAX (30 SOL) the value is treated as a poisoned
     * percentage escapee, logged FI4FAM_UNIT_CORRUPTION, and clamped to 0.0.
     *
     * 30 SOL is 60x a maximum entry (≈0.5 SOL). Any realized PnL that
     * exceeds it on a single sell is either a moonshot (should route
     * through the runner ledger which owns >30 SOL claims) or a
     * unit-mix bug.
     */
    private const val SOL_PLAUSIBLE_MAX = 30.0

    fun assertSolPlausible(candidateSol: Double, siteTag: String): Double {
        if (!candidateSol.isFinite()) {
            fi4famDetections.incrementAndGet(); fi4famClamps.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "FI4FAM_UNIT_CORRUPTION_6461",
                    "site=$siteTag value=$candidateSol reason=non_finite clamped=0.0",
                )
                PipelineHealthCollector.labelInc("FI4FAM_UNIT_CORRUPTION_6461")
            } catch (_: Throwable) {}
            return 0.0
        }
        if (kotlin.math.abs(candidateSol) > SOL_PLAUSIBLE_MAX) {
            fi4famDetections.incrementAndGet(); fi4famClamps.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "FI4FAM_UNIT_CORRUPTION_6461",
                    "site=$siteTag value=${"%.4f".format(candidateSol)} reason=exceeds_${SOL_PLAUSIBLE_MAX}_sol_looks_like_percent clamped=0.0",
                )
                PipelineHealthCollector.labelInc("FI4FAM_UNIT_CORRUPTION_6461")
            } catch (_: Throwable) {}
            return 0.0
        }
        return candidateSol
    }

    fun statusLine(): String =
        "fi4famDetections=${fi4famDetections.get()} fi4famClamps=${fi4famClamps.get()} solMaxPlausible=$SOL_PLAUSIBLE_MAX"

    internal fun resetForTest() { fi4famDetections.set(0L); fi4famClamps.set(0L) }
}

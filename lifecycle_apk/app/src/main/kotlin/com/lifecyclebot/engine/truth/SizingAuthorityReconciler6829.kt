package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6829 §SIZING_AUTHORITY_RECONCILE — operator diagnosis item #9 for
 * build 5.0.6828:
 *   "EXEC_SIZE_AUTHORITY_MISMATCH_6497=157. Recent trace: local=0.0234
 *    sealed=0.2916 using=sealed. That is roughly a 12.5x difference
 *    between local sizing and sealed authority. The system correctly
 *    chooses one authority, but 157 mismatches means the sizing stages
 *    themselves are not agreeing."
 *
 * DESIGN — canonical reconciliation policy. Given (localSol, sealedSol),
 * return the authoritative size PLUS the disagreement classification:
 *   • WITHIN_TOLERANCE (< 5% deviation) — trust the sealed value silently
 *   • MODERATE_DIVERGENCE (5-25%) — trust sealed, emit soft counter
 *   • HARD_DIVERGENCE (> 25%) — trust sealed, emit LOUD forensic log
 *     + counter so the operator can drill down on the causal source
 *
 * The authority NEVER blends — this preserves the invariant that
 * exactly ONE sizing authority is authoritative. It merely surfaces
 * the disagreement in a class-labelled way.
 *
 * `preferredAuthority()` returns "SEALED" — sealed always wins because
 * it is the atomic FDG output that flows into the execution ticket.
 */
object SizingAuthorityReconciler6829 {

    enum class Divergence { WITHIN_TOLERANCE, MODERATE, HARD }

    private const val TOL_PCT = 0.05  // 5%
    private const val MODERATE_PCT = 0.25 // 25%

    private val reconciliations = AtomicLong(0L)
    private val withinTolerance = AtomicLong(0L)
    private val moderate = AtomicLong(0L)
    private val hard = AtomicLong(0L)

    data class Reconciliation(
        val useSol: Double,
        val divergence: Divergence,
        val pctDelta: Double,
        val source: String,
    )

    fun preferredAuthority(): String = "SEALED"

    fun reconcile(
        localSol: Double,
        sealedSol: Double,
        mint: String = "",
        lane: String = "",
    ): Reconciliation {
        reconciliations.incrementAndGet()
        return try {
            // Sealed always wins per canonical invariant.
            val use = sealedSol
            // Compute relative divergence against the max of the two so
            // very small local values against non-zero sealed produce a
            // meaningful percentage. Guard against divide-by-zero.
            val denom = maxOf(kotlin.math.abs(localSol), kotlin.math.abs(sealedSol), 1e-9)
            val delta = kotlin.math.abs(localSol - sealedSol) / denom
            val d = when {
                delta <= TOL_PCT -> Divergence.WITHIN_TOLERANCE
                delta <= MODERATE_PCT -> Divergence.MODERATE
                else -> Divergence.HARD
            }
            when (d) {
                Divergence.WITHIN_TOLERANCE -> withinTolerance.incrementAndGet()
                Divergence.MODERATE -> {
                    moderate.incrementAndGet()
                    try {
                        PipelineHealthCollector.labelInc("SIZING_RECONCILE_MODERATE_6829")
                        PipelineHealthCollector.labelInc(
                            "SIZING_RECONCILE_MODERATE_6829_${lane.uppercase().take(24)}"
                        )
                    } catch (_: Throwable) {}
                }
                Divergence.HARD -> {
                    hard.incrementAndGet()
                    try {
                        PipelineHealthCollector.labelInc("SIZING_RECONCILE_HARD_6829")
                        PipelineHealthCollector.labelInc(
                            "SIZING_RECONCILE_HARD_6829_${lane.uppercase().take(24)}"
                        )
                        ForensicLogger.lifecycle(
                            "SIZING_RECONCILE_HARD_6829",
                            "mint=${mint.take(10)} lane=$lane " +
                                "local=${"%.6f".format(localSol)} " +
                                "sealed=${"%.6f".format(sealedSol)} " +
                                "deltaPct=${"%.1f".format(delta * 100.0)} " +
                                "using=SEALED action=canonical_authority_wins",
                        )
                    } catch (_: Throwable) {}
                }
            }
            Reconciliation(use, d, delta * 100.0, "SEALED")
        } catch (_: Throwable) {
            Reconciliation(sealedSol, Divergence.WITHIN_TOLERANCE, 0.0, "SEALED")
        }
    }

    fun statusLine(): String =
        "reconciliations=${reconciliations.get()} withinTol=${withinTolerance.get()} " +
            "moderate=${moderate.get()} hard=${hard.get()}"

    internal fun clearForTest() {
        reconciliations.set(0L); withinTolerance.set(0L)
        moderate.set(0L); hard.set(0L)
    }
}

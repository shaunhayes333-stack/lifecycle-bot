package com.lifecyclebot.engine.cortex

/**
 * V5.0.8539 — owner: "the Cortex needs to prove it's better at finding winners than the main stack. Only then can it
 * influence live trading."
 *
 * The main stack (V3 + the lane brains + their setups) trades on its own. The Cortex is an upgrade on top of it, and it earns
 * a say only by out-picking that stack, per lane, on the same forward labels:
 *   - the Cortex's out-of-sample TOP picks (CortexAuthority8366.Result8366.topN / topMean / topSe, executable net), against
 *   - every coin the main stack ADMITTED in that lane (ForwardReturnLabeler7731 LANEDEC|lane|A, executable net).
 * It must have [MIN_N] picks, the stack must have [MIN_N] admits, its picks must make money (mean > 0), and they must beat
 * the stack's admits by more than one combined standard error. Until then every Cortex lever on live money is off: no
 * refusal (hard safety excepted), no overrule, no size-up or shrink-override, no STRONG evidence, no ordering vote and no
 * operator inverse opportunity. It keeps assessing and being graded the whole time, so the proof can arrive.
 */
object CortexVsStack8539 {
    const val MIN_N = 30

    /** Pure: do the Cortex's out-of-sample picks beat the main stack's admits outright? */
    fun beats(cortexN: Int, cortexMean: Double, cortexSe: Double, stackN: Int, stackMean: Double, stackSe: Double): Boolean =
        code(cortexN, cortexMean, cortexSe, stackN, stackMean, stackSe) == PROVEN

    const val PROVEN = "PROVEN_OVER_STACK"

    /** Pure: why the Cortex has (PROVEN_OVER_STACK) or has not proven it beats the stack. */
    fun code(cortexN: Int, cortexMean: Double, cortexSe: Double, stackN: Int, stackMean: Double, stackSe: Double): String = when {
        cortexN < MIN_N -> "CORTEX_THIN"
        stackN < MIN_N -> "STACK_THIN"
        !cortexMean.isFinite() || !stackMean.isFinite() -> "UNREAD"
        cortexMean <= 0.0 -> "CORTEX_PICKS_LOSE"
        !cortexSe.isFinite() || !stackSe.isFinite() -> "UNREAD"
        cortexMean - stackMean <= kotlin.math.sqrt(cortexSe * cortexSe + stackSe * stackSe) -> "NOT_BETTER_THAN_STACK"
        else -> PROVEN
    }
}

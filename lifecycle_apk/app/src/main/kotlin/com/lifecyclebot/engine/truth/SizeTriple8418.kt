package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8418 — owner item 8: requested, submitted and recorded sizes must agree.
 *
 * Every size adjustment (correlation, concentration, exposure, symbolic bias, rent trim)
 * belongs BEFORE the execution size is sealed into the ticket / intent. After the seal the
 * requested size (the sealed intent), the submitted size (the tx amount) and the recorded
 * size (journal / canonical position) are one number. [check] returns false and counts
 * `SIZE_TRIPLE_MISMATCH_8418_<stage>` when they are not; callers refuse on false.
 *
 * Tolerance is lamport rounding (1e-9 SOL) plus a 1e-6 relative allowance for the
 * Double -> lamports -> Double round trip. Nothing larger is "close enough".
 */
object SizeTriple8418 {
    private const val ABS_TOL_SOL = 1e-9
    private const val REL_TOL = 1e-6
    private val mismatches = ConcurrentHashMap<String, AtomicLong>()

    fun agree(a: Double, b: Double): Boolean {
        if (!a.isFinite() || !b.isFinite()) return false
        val gap = kotlin.math.abs(a - b)
        return gap <= ABS_TOL_SOL || gap <= REL_TOL * maxOf(kotlin.math.abs(a), kotlin.math.abs(b))
    }

    /** True when all three agree; otherwise counts and returns false. */
    fun check(stage: String, requestedSol: Double, submittedSol: Double, recordedSol: Double): Boolean {
        if (agree(requestedSol, submittedSol) && agree(submittedSol, recordedSol) && agree(requestedSol, recordedSol)) return true
        val label = "SIZE_TRIPLE_MISMATCH_8418_$stage"
        mismatches.computeIfAbsent(label) { AtomicLong() }.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc(label) } catch (_: Throwable) {}
        try {
            com.lifecyclebot.engine.ErrorLogger.warn(
                "SizeTriple8418",
                "$label requested=$requestedSol submitted=$submittedSol recorded=$recordedSol — refused",
            )
        } catch (_: Throwable) {}
        return false
    }

    fun mismatchCount(stage: String): Long = mismatches["SIZE_TRIPLE_MISMATCH_8418_$stage"]?.get() ?: 0L
}

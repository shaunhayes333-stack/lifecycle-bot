package com.lifecyclebot.engine

internal object PaperQuantityRepresentation6514 {
    const val DECIMAL_NEUTRAL_STORAGE_SCALE = 12
    fun metadataDecimals(resolved: Int?): Int = resolved?.takeIf { it in 0..18 } ?: -1
    fun accountingScale(resolved: Int?): Int = resolved?.takeIf { it in 0..18 } ?: DECIMAL_NEUTRAL_STORAGE_SCALE
}

internal object PaperPreTicketSizeFloor6511 {
    private const val ABSOLUTE_EXECUTABLE_FLOOR_SOL = 0.05
    private const val MAX_BOUNDED_RUNTIME_MINIMUM_SOL = 0.15

    fun boundedMinimum(runtimeMinimumSol: Double): Double =
        runtimeMinimumSol.takeIf { it.isFinite() && it > 0.0 }
            ?.coerceIn(ABSOLUTE_EXECUTABLE_FLOOR_SOL, MAX_BOUNDED_RUNTIME_MINIMUM_SOL)
            ?: ABSOLUTE_EXECUTABLE_FLOOR_SOL

    fun effectiveRequested(requestedSol: Double, minimumSol: Double, availableCashSol: Double): Double =
        if (requestedSol.isFinite() && requestedSol > 0.0 && requestedSol < minimumSol && availableCashSol >= minimumSol) minimumSol
        else requestedSol
}

/**
 * V5.0.6661 - Keep the adaptive sizing interval mathematically valid.
 *
 * Money mode may promote a small shaped request to the absolute executable
 * floor.  The old caller left the upper bound tied to the unpromoted request,
 * so Kotlin's coerceIn threw when lower > upper and aborted the execution
 * spine.  A promoted floor is itself the minimum permitted cap whenever the
 * wallet eligibility check has already admitted that floor.
 */
internal object MoneyModeSizeBounds6661 {
    fun clamp(raw: Double, lower: Double, upper: Double): Double {
        val safeLower = lower.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val safeUpper = upper.takeIf { it.isFinite() && it >= 0.0 } ?: safeLower
        val normalizedUpper = maxOf(safeLower, safeUpper)
        val safeRaw = raw.takeIf { it.isFinite() } ?: safeLower
        return safeRaw.coerceIn(safeLower, normalizedUpper)
    }
}

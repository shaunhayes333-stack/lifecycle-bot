package com.lifecyclebot.engine.truth

import kotlin.math.ln1p

/** Frozen market population and the receiving desk's own mandate; never an admission gate. */
internal object NativeZoneMarket9061 {
    const val WIDTH = 12
    private val kinds = listOf("CURVE_LAUNCH", "REGISTERED_BLUECHIP", "POOL_TOKEN", "UNKNOWN")
    data class Snapshot(
        val capUsd: Double, val liquidityUsd: Double,
        val exactAgeMinutes: Double, val minimumAgeMinutes: Double,
        val capMin: Double, val capMax: Double, val ageMin: Double, val ageMax: Double,
        val liquidityMin: Double, val tokenKind: String
    ) {
        fun numbers() = listOf(capUsd, liquidityUsd, exactAgeMinutes, minimumAgeMinutes,
            capMin, capMax, ageMin, ageMax, liquidityMin)
        fun valid(): Boolean = tokenKind in kinds && numbers().all { it.isNaN() || (it.isFinite() && it >= 0.0) } &&
            (capMin.isNaN() || capMax.isNaN() || capMin <= capMax) &&
            (ageMin.isNaN() || ageMax.isNaN() || ageMin <= ageMax) &&
            (exactAgeMinutes.isNaN() || minimumAgeMinutes.isNaN() || minimumAgeMinutes <= exactAgeMinutes)
    }

    private fun fit(value: Double, low: Double, high: Double): Double = when {
        value.isNaN() || low.isNaN() -> Double.NaN
        value < low || (!high.isNaN() && value > high) -> 0.0
        else -> 1.0
    }

    /** A minimum age can prove old enough or too old, never prove a young-token upper bound. */
    fun ageFit(s: Snapshot): Double = when {
        !s.exactAgeMinutes.isNaN() -> fit(s.exactAgeMinutes, s.ageMin, s.ageMax)
        s.minimumAgeMinutes.isNaN() || s.ageMin.isNaN() -> Double.NaN
        !s.ageMax.isNaN() && s.minimumAgeMinutes > s.ageMax -> 0.0
        !s.ageMax.isNaN() || s.minimumAgeMinutes < s.ageMin -> Double.NaN
        else -> 1.0
    }

    fun vector(s: Snapshot?): DoubleArray {
        if (s == null || !s.valid()) return DoubleArray(WIDTH) { Double.NaN }
        val out = DoubleArray(WIDTH) { Double.NaN }
        s.numbers().take(4).forEachIndexed { i, v -> out[i] = if (v.isNaN()) v else ln1p(v) }
        out[4] = fit(s.capUsd, s.capMin, s.capMax)
        out[5] = ageFit(s)
        out[6] = fit(s.liquidityUsd, s.liquidityMin, Double.NaN)
        out[7] = if (s.capUsd > 0.0 && !s.liquidityUsd.isNaN()) s.liquidityUsd / s.capUsd else Double.NaN
        kinds.forEachIndexed { i, kind -> out[8 + i] = if (s.tokenKind == kind) 1.0 else 0.0 }
        return out
    }
}

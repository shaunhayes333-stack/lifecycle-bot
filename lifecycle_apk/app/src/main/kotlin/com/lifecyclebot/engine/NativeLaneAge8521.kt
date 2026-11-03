package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LaneCheatSheet8323
import com.lifecyclebot.engine.truth.AdmissionBirth8144

/** Exact birth and a proven minimum age have different admission meanings. */
object NativeLaneAge8521 {
    data class Evidence(val exactMinutes: Double?, val minimumMinutes: Double?) {
        val inputMinutes: Double get() = exactMinutes ?: minimumMinutes ?: Double.NaN
    }

    fun evidence8521(read: AdmissionBirth8144.Read8144?): Evidence = when (read?.kind) {
        AdmissionBirth8144.Kind8144.KNOWN -> Evidence(read.minAgeMs / 60_000.0, read.minAgeMs / 60_000.0)
        AdmissionBirth8144.Kind8144.PROVEN_OLDER_THAN -> Evidence(null, read.minAgeMs / 60_000.0)
        else -> Evidence(null, null)
    }

    internal fun fingerprint8521(read: AdmissionBirth8144.Read8144?, nowMs: Long): Int =
        listOf(read?.kind, read?.birthMs, read?.source,
            read?.takeIf { it.kind == AdmissionBirth8144.Kind8144.PROVEN_OLDER_THAN }?.let { nowMs - it.minAgeMs }).hashCode()

    /** A bound can prove old enough, or too old. It cannot prove a young launch. */
    internal fun reason8521(age: Evidence, minMinutes: Double, maxMinutes: Double): String? {
        val exact = age.exactMinutes?.takeIf { it.isFinite() && it >= 0.0 }
        val minimum = age.minimumMinutes?.takeIf { it.isFinite() && it >= 0.0 }
        if (exact != null) return when {
            exact < minMinutes -> "NATIVE_AGE_BELOW_MIN_8521"
            maxMinutes.isFinite() && exact > maxMinutes -> "NATIVE_AGE_ABOVE_MAX_8521"
            else -> null
        }
        if (maxMinutes.isFinite()) return if (minimum != null && minimum > maxMinutes)
            "NATIVE_AGE_ABOVE_MAX_8521" else "NATIVE_AGE_UNOBSERVED_DEFER_8521"
        if (minMinutes > 0.0 && (minimum == null || minimum < minMinutes)) return "NATIVE_AGE_UNOBSERVED_DEFER_8521"
        return null
    }

    fun review8521(opinion: SpecialistBrainBridge7542.Opinion, age: Evidence): SpecialistBrainBridge7542.Opinion {
        if (!opinion.authoritative || opinion.lane == "PROJECT_SNIPER") return opinion // sniper already reads the same birth proof itself
        val nativeKind = NativeLaneConstraints8521.kind8521(opinion.reason)
        if (!opinion.eligible && nativeKind != null) {
            // The lane's own stricter minimum may exceed its bootstrap sheet.
            // A lower bound below that minimum is insufficient, never "too new".
            if (nativeKind == NativeLaneConstraints8521.Kind.AGE && age.exactMinutes == null &&
                !opinion.reason.uppercase().contains("TOO_OLD") && !opinion.reason.uppercase().contains("ABOVE_MAX")) {
                return opinion.copy(reason = "NATIVE_AGE_UNOBSERVED_DEFER_8521:${opinion.lane}", gradeable = false)
            }
            return opinion
        }
        val band = LaneCheatSheet8323.ageBandMin8323(opinion.lane)
        val reason = reason8521(age, band.first, band.second) ?: return opinion
        return opinion.copy(eligible = false, reason = "$reason:${opinion.lane}",
            gradeable = !NativeLaneConstraints8521.missingData8521(reason))
    }
}

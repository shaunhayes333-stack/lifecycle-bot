package com.lifecyclebot.engine

import kotlin.math.abs

/**
 * V5.0.7651 - evidence ancestry/de-correlation for the Super Intelligence planner.
 *
 * AATE has several adapters over the same underlying facts. Treating every adapter
 * as an independent vote creates false confidence. This fuser first collapses
 * observations inside a shared ancestry family, then combines genuinely independent
 * families with an explicit contradiction penalty.
 *
 * Pure/local/advisory: no provider I/O, execution, capital, threshold or veto authority.
 */
object SuperEvidenceTopology7651 {
    enum class Family {
        NATIVE_SPECIALIST,
        AGGREGATE_CROSSCHECK,
        STRATEGY_LEARNING,
        COUNTERFACTUAL_REPLAY,
    }

    data class Observation(
        val label: String,
        val family: Family,
        val utility: Double,
        val confidence: Double = 1.0,
    )

    data class Result(
        val rawUtility: Double,
        val familyUtility: Map<Family, Double>,
        val decorrelatedUtility: Double,
        val activeSignals: Int,
        val independentFamilies: Int,
        val agreement: Double,
        val redundancyRatio: Double,
        val contradictionPenalty: Double,
    ) {
        fun tag(): String = String.format(
            java.util.Locale.US,
            "topology7651(raw=%+.2f,decor=%+.2f,sig=%d,fam=%d,agree=%.2f,redund=%.2f,conflict=%.2f)",
            rawUtility, decorrelatedUtility, activeSignals, independentFamilies,
            agreement, redundancyRatio, contradictionPenalty,
        )
    }

    fun fuse(input: List<Observation>): Result {
        val active = input.filter {
            it.utility.isFinite() && it.confidence.isFinite() &&
                abs(it.utility) >= 0.02 && it.confidence > 0.0
        }
        if (active.isEmpty()) return Result(
            rawUtility = 0.0,
            familyUtility = emptyMap(),
            decorrelatedUtility = 0.0,
            activeSignals = 0,
            independentFamilies = 0,
            agreement = 1.0,
            redundancyRatio = 0.0,
            contradictionPenalty = 1.0,
        )

        val raw = active.sumOf { it.utility * it.confidence.coerceIn(0.0, 1.0) }
        val family = linkedMapOf<Family, Double>()

        active.groupBy { it.family }.forEach { (key, observations) ->
            // Confidence-weighted mean inside a family: aliases/cousins corroborate
            // quality but do not receive N independent additive votes.
            var weighted = 0.0
            var weight = 0.0
            observations.forEach {
                val w = it.confidence.coerceIn(0.05, 1.0)
                weighted += it.utility * w
                weight += w
            }
            family[key] = if (weight > 0.0) weighted / weight else 0.0
        }

        val independent = family.values
        val signed = independent.sum()
        val mass = independent.sumOf { abs(it) }
        val agreement = if (mass <= 1e-9) 1.0 else (abs(signed) / mass).coerceIn(0.0, 1.0)
        val contradictionPenalty = (0.55 + 0.45 * agreement).coerceIn(0.55, 1.0)
        val redundancyRatio = if (active.isEmpty()) 0.0 else
            (1.0 - family.size.toDouble() / active.size.toDouble()).coerceIn(0.0, 1.0)

        var decorrelated = signed * contradictionPenalty

        // De-correlation must never manufacture more conviction than the original
        // weighted evidence mass; it only removes duplicate/conflicted confidence.
        val maxMagnitude = active.sumOf { abs(it.utility * it.confidence.coerceIn(0.0, 1.0)) }
        decorrelated = decorrelated.coerceIn(-maxMagnitude, maxMagnitude).coerceIn(-6.0, 6.0)

        return Result(
            rawUtility = raw,
            familyUtility = family,
            decorrelatedUtility = decorrelated,
            activeSignals = active.size,
            independentFamilies = family.size,
            agreement = agreement,
            redundancyRatio = redundancyRatio,
            contradictionPenalty = contradictionPenalty,
        )
    }
}

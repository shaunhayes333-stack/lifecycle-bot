package com.lifecyclebot.engine

import kotlin.math.abs

/**
 * V5.0.7632 — SUPER SSI FUSION.
 *
 * Meta-intelligence over the existing brain network. This object does not read
 * providers, invent evidence, make execution decisions or add a new vote.
 * It measures whether the already-collected bounded brain opinions agree.
 *
 * Consensus keeps their existing influence. Conflict increases epistemic
 * uncertainty and attenuates the opinion tier before it reaches the oracle.
 * The transform is one-way conservative: |fused| can never exceed |raw|.
 */
object SuperSsiFusion7632 {
    data class Vote(val label: String, val deltaPct: Double)

    data class Snapshot(
        val rawDeltaPct: Double,
        val fusedDeltaPct: Double,
        val positiveMass: Double,
        val negativeMass: Double,
        val agreement: Double,
        val disagreement: Double,
        val breadth: Double,
        val activeBrains: Int,
        val direction: String,
    ) {
        fun contributionTag(): String =
            "superSSI7632(n=$activeBrains,dir=$direction,agree=${"%.2f".format(agreement)}," +
                "uncert=${"%.2f".format(disagreement)},raw=${"%+.1f".format(rawDeltaPct)}," +
                "fused=${"%+.1f".format(fusedDeltaPct)})"
    }

    fun fuse(votes: Collection<Vote>): Snapshot {
        val clean = votes.asSequence()
            .filter { it.deltaPct.isFinite() && abs(it.deltaPct) >= 0.25 }
            .take(32)
            .toList()

        if (clean.isEmpty()) {
            return Snapshot(0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0, "NEUTRAL")
        }

        val positive = clean.filter { it.deltaPct > 0.0 }.sumOf { it.deltaPct }
        val negative = clean.filter { it.deltaPct < 0.0 }.sumOf { -it.deltaPct }
        val gross = positive + negative
        val raw = positive - negative
        val agreement = if (gross <= 0.0) 0.0 else (abs(raw) / gross).coerceIn(0.0, 1.0)
        val disagreement = (1.0 - agreement).coerceIn(0.0, 1.0)
        val breadth = (clean.size / 6.0).coerceIn(0.0, 1.0)

        // Breadth matters only after directional agreement exists. A crowd of
        // contradictory brains is not confidence. 0.35 is the maximum conflict
        // floor so evidence is softened, not silently erased.
        val epistemicConfidence = (agreement * (0.65 + 0.35 * breadth)).coerceIn(0.0, 1.0)
        val attenuation = (0.35 + 0.65 * epistemicConfidence).coerceIn(0.35, 1.0)
        val fused = raw * attenuation

        return Snapshot(
            rawDeltaPct = raw,
            fusedDeltaPct = fused,
            positiveMass = positive,
            negativeMass = negative,
            agreement = agreement,
            disagreement = disagreement,
            breadth = breadth,
            activeBrains = clean.size,
            direction = when {
                raw > 0.25 -> "BULL"
                raw < -0.25 -> "BEAR"
                else -> "NEUTRAL"
            },
        )
    }
}

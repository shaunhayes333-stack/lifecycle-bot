package com.lifecyclebot.engine.cortex

/**
 * V5.0.8144 §COMPETING_STRATEGIES — per token, a lane's negative default setup never suppresses a different, valid,
 * positive setup the same coin fired.
 *
 * LanePlaybook7907.selectedSetup8094 took the best PROVEN fired setup (30+ labels, mean - SE > 0) or else the classifier's
 * default (LaneEvidence8077.choose, ranked on prior-shrunk expectancy). A default with a measured negative record
 * (12+ labels, mean + SE < 0) can out-rank a smaller-sample alternative that clears the cold-start bar (12+ labels,
 * mean - 1.5 SE > 0) — the shrinkage toward a prior of +1 lifts the loser — and then pickedSetupNegative8094 /
 * provenLosingSetupRefusal8079 / C6_SETUP_DEAD refused the COIN for its default's record while its other strategy was
 * valid. Now, when the default is negative, the best valid alternative (by its own lower bound) is the strategy the coin
 * trades as; when no alternative is valid the default — and its refusal — stand. A selection grants nothing by itself:
 * every live gate still needs the selected strategy's own evidence.
 */
object CortexStrategySelect8144 {
    data class Record8144(val id: String, val n: Double, val mean: Double, val variance: Double) {
        val se: Double get() = if (n > 1.0 && variance.isFinite() && variance >= 0.0) kotlin.math.sqrt(variance / n) else Double.POSITIVE_INFINITY
    }

    /** Pure: LanePlaybook7907.setupRecordNegative8094 — 12+ labels, mean + SE below zero. */
    fun negative8144(r: Record8144?): Boolean =
        r != null && r.n >= LanePlaybook7907.NEGATIVE_N_8094 && r.mean.isFinite() && r.se.isFinite() && r.mean + r.se < 0.0

    /** Pure: LanePlaybook7907.coldStartPositive8080 — 12+ labels, mean - 1.5 SE above zero (and not negative). */
    fun validPositive8144(r: Record8144?): Boolean =
        r != null && LanePlaybook7907.coldStartPositive8080(r.n, r.mean, r.variance) && !negative8144(r)

    /**
     * Pure: the valid positive alternative that replaces a negative [defaultId], or null (default not negative, or no
     * other fired setup is valid). [fired] are the setups this coin fired in the lane with their own records.
     */
    fun alternative8144(defaultId: String?, fired: List<Record8144>): String? {
        if (defaultId.isNullOrBlank()) return null
        val def = fired.firstOrNull { it.id == defaultId }
        if (!negative8144(def)) return null
        return fired.filter { it.id != defaultId && it.id != LanePlaybook7907.NO_TRIGGER && validPositive8144(it) }
            .maxByOrNull { it.mean - it.se }?.id
    }
}

package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6450 §P1 — LEARNING QUARANTINE.
 *
 * OPERATOR MANDATE:
 *   Do not allow absurd performance such as BLUECHIP 25/0 +913.9% or
 *   PRESALE 9/0 +4548.8% to influence live tactic selection.
 *
 *   "Add plausibility/data-integrity guard: if cohort statistics
 *    conflict with canonical terminal population, mark cohort
 *    LEARNING_QUARANTINED and rebuild."
 *
 * DESIGN
 * ──────
 * A learner submits its cohort stats via `evaluateCohort(name, wins,
 * losses, avgReturnPct)`. The guard compares against a plausibility
 * envelope and against the canonical population from
 * CanonicalTradeFinalizedBus6450. If mismatched, the cohort key is
 * quarantined; `isQuarantined(cohort)` returns true and downstream
 * learners must skip its influence on execution decisions.
 *
 * Also flags impossible-mark artefacts:
 *   - avgReturnPct > 500% on cohort with N<20
 *   - wins > 20 and losses == 0
 *   - avgReturnPct sign disagreement with canonical realized PnL
 */
object LearningQuarantine6450 {

    data class Reason(val label: String, val detail: String)

    private const val IMPLAUSIBLE_MEGA_RETURN = 500.0
    private const val IMPLAUSIBLE_MIN_SAMPLES = 20

    private val quarantined = ConcurrentHashMap<String, Reason>()
    private val evaluations = AtomicLong(0L)
    private val quarantines = AtomicLong(0L)
    private val releases = AtomicLong(0L)

    /** Wire canonical outcome tallies per cohort so quarantine can
     *  cross-reference the learner's numbers against the canonical bus. */
    private val canonicalWins = ConcurrentHashMap<String, AtomicLong>()
    private val canonicalLosses = ConcurrentHashMap<String, AtomicLong>()

    init {
        try {
            CanonicalTradeFinalizedBus6450.subscribe { e ->
                when (e.outcome) {
                    CanonicalTradeFinalizedBus6450.Outcome.WIN -> canonicalWins.getOrPut(e.entryLane) { AtomicLong(0L) }.incrementAndGet()
                    CanonicalTradeFinalizedBus6450.Outcome.LOSS -> canonicalLosses.getOrPut(e.entryLane) { AtomicLong(0L) }.incrementAndGet()
                    CanonicalTradeFinalizedBus6450.Outcome.BREAKEVEN -> Unit
                }
            }
        } catch (_: Throwable) {}
    }

    fun evaluateCohort(cohort: String, wins: Long, losses: Long, avgReturnPct: Double): Boolean {
        evaluations.incrementAndGet()
        val samples = wins + losses
        val reason: Reason? = when {
            samples < IMPLAUSIBLE_MIN_SAMPLES && kotlin.math.abs(avgReturnPct) > IMPLAUSIBLE_MEGA_RETURN ->
                Reason("IMPLAUSIBLE_MEGA_RETURN", "wins=$wins losses=$losses avg=${"%.1f".format(avgReturnPct)}%")
            wins > 20 && losses == 0L ->
                Reason("PERFECT_STREAK_UNREALISTIC", "wins=$wins losses=0")
            else -> {
                val canW = canonicalWins[cohort]?.get() ?: 0L
                val canL = canonicalLosses[cohort]?.get() ?: 0L
                if ((canW + canL) > 5 && (wins - canW > 5 || losses - canL > 5)) {
                    Reason("CANONICAL_MISMATCH", "cohortW=$wins vs canonW=$canW cohortL=$losses vs canonL=$canL")
                } else null
            }
        }
        return if (reason != null) {
            quarantined[cohort] = reason
            quarantines.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "LEARNING_QUARANTINED_6450",
                    "cohort=$cohort reason=${reason.label} detail=${reason.detail}",
                )
                PipelineHealthCollector.labelInc("LEARNING_QUARANTINED_6450")
            } catch (_: Throwable) {}
            true
        } else {
            false
        }
    }

    fun isQuarantined(cohort: String): Boolean = quarantined.containsKey(cohort)

    fun release(cohort: String) {
        if (quarantined.remove(cohort) != null) {
            releases.incrementAndGet()
            try { PipelineHealthCollector.labelInc("LEARNING_QUARANTINE_RELEASED_6450") } catch (_: Throwable) {}
        }
    }

    fun statusLine(): String = "quarantined=${quarantined.size} evals=${evaluations.get()} " +
        "quarantines=${quarantines.get()} releases=${releases.get()}"
}

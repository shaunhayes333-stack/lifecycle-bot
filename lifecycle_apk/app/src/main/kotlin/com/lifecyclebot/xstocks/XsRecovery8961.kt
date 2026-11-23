package com.lifecyclebot.xstocks

/** An unanswered history read is scheduling state, never a zero target or fresh budget. */
internal object XsRecovery8961 {
    fun needsEvaluation(day: Long, answered: Set<String>, priorDay: Long, priorAnswered: Set<String>): Boolean =
        answered.isNotEmpty() && (day != priorDay || answered != priorAnswered)

    fun rebalanceSymbols(targets: Set<String>, held: Set<String>, unanswered: Set<String>): Set<String> =
        (targets + held) - unanswered

    fun researchDue(now: Long, nextAttempt: Long, failed: Boolean,
                    previousCosts: Set<String>, availableCosts: Set<String>): Boolean =
        now >= nextAttempt && (failed || previousCosts != availableCosts)

    /** Existing positions (including failed closes and unanswered instruments) retain their capital allocation. */
    fun boundOpenSizes(proposed: Map<String, Double>, budget: Double, committed: Double,
                       minimum: Double): Map<String, Double> {
        if (!budget.isFinite() || !committed.isFinite() || budget <= 0.0 || committed < 0.0) return emptyMap()
        val remaining = (budget - committed).coerceAtLeast(0.0)
        val valid = proposed.filterValues { it.isFinite() && it > 0.0 }
        val total = valid.values.sum()
        if (!total.isFinite() || total <= 0.0 || remaining < minimum) return emptyMap()
        val scale = minOf(1.0, remaining / total)
        return valid.mapValues { (_, size) -> size * scale }.filterValues { it >= minimum }
    }
}

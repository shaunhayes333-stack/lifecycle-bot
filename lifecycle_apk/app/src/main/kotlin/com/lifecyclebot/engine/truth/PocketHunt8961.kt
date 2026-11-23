package com.lifecyclebot.engine.truth

/** Discovery priority only. Missing features nominate a read, never prove membership or grant a buy. */
object PocketHunt8961 {
    enum class Match { NONE, NEEDS_READ, MATCHED }

    data class Observation(val features: Set<String>, val observedAtMs: Long)

    fun fresh(observedAtMs: Long, nowMs: Long): Boolean =
        observedAtMs > 0L && nowMs - observedAtMs in 0L..120_000L

    /** Current sweep facts own cap/liquidity; a lane's recent decision supplies only its derived features. */
    fun withDecision(sweep: Set<String>, decision: Observation?, nowMs: Long): Set<String> {
        if (decision == null || !fresh(decision.observedAtMs, nowMs)) return sweep
        val derived = decision.features.filter { it.substringBefore('=') in setOf("stage", "setup", "v3", "pred") }
        val keys = derived.mapTo(HashSet()) { it.substringBefore('=') }
        return sweep.filterNot { it.substringBefore('=') in keys }.toSet() + derived
    }

    fun match(conditions: List<String>, observed: Set<String>, observedAtMs: Long, nowMs: Long): Match {
        if (conditions.isEmpty() || !fresh(observedAtMs, nowMs)) return Match.NONE
        val known = observed.filterNot { it.endsWith("=NA") }.associateBy { it.substringBefore('=') }
        var answered = 0
        for (condition in conditions) {
            val fact = known[condition.substringBefore('=')] ?: continue
            if (fact != condition) return Match.NONE
            answered++
        }
        return when {
            answered == conditions.size -> Match.MATCHED
            answered > 0 -> Match.NEEDS_READ
            else -> Match.NONE
        }
    }

    /** A bounded share for the lane's pockets; retain ordinary discovery and its stable rank. */
    fun prioritize(order: List<String>, matches: Map<String, Match>, budget: Int = 4): List<String> {
        val preferred = order.filter { matches[it] == Match.MATCHED } + order.filter { matches[it] == Match.NEEDS_READ }
        val chosen = preferred.take(budget.coerceAtLeast(0)).toSet()
        return preferred.filter { it in chosen } + order.filterNot { it in chosen }
    }

    /** Reads may precede a mandate decision; they never put a failing row into the executable candidate list. */
    fun recoveryPicks(order: List<String>, matches: Map<String, Match>, eligible: Set<String>,
                      missing: Set<String>, budget: Int = 4): List<String> =
        prioritize(order, matches).asSequence().filter {
            it !in eligible && it in missing && matches[it] != null && matches[it] != Match.NONE
        }.distinct().take(budget.coerceAtLeast(0)).toList()

    /** A shared read budget rotates across lane books, so the first profile cannot consume every sweep. */
    fun recoveryBudget(byLane: Map<String, List<String>>, turn: Int, budget: Int = 4): List<String> {
        val lanes = byLane.values.filter { it.isNotEmpty() }
        if (lanes.isEmpty() || budget <= 0) return emptyList()
        val start = Math.floorMod(turn, lanes.size)
        val rotated = lanes.drop(start) + lanes.take(start)
        return (0 until rotated.maxOf { it.size }).asSequence()
            .flatMap { i -> rotated.asSequence().mapNotNull { it.getOrNull(i) } }
            .distinct().take(budget).toList()
    }

    /** Keep the bounded pocket allocation through fresh-first rotation, respecting cooldown and losing-shape demotion. */
    fun deliveryPicks(order: List<String>, ordinary: List<String>, matches: Map<String, Match>,
                      back: Set<String>, limit: Int, budget: Int = 4): List<String> {
        if (limit <= 0) return emptyList()
        val preferred = prioritize(order, matches).filter {
            it !in back && matches[it] != null && matches[it] != Match.NONE
        }.take(minOf(limit, budget.coerceAtLeast(0)))
        return (preferred + ordinary).distinct().take(limit)
    }

    /** Measured losing setups rank below unmeasured ones; backed setups rank first. No permission or quota is spent. */
    fun recordRank(backed: Boolean, losing: Boolean): Int = when {
        losing -> -1
        backed -> 1
        else -> 0
    }
}

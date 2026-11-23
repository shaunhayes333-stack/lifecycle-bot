package com.lifecyclebot.engine.truth

/** Discovery priority only. Missing features nominate a read, never prove membership or grant a buy. */
object PocketHunt8961 {
    enum class Match { NONE, NEEDS_READ, MATCHED }

    fun match(conditions: List<String>, observed: Set<String>, observedAtMs: Long, nowMs: Long): Match {
        if (conditions.isEmpty() || observedAtMs <= 0L || nowMs - observedAtMs !in 0L..120_000L) return Match.NONE
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

    /** Measured losing setups rank below unmeasured ones; backed setups rank first. No permission or quota is spent. */
    fun recordRank(backed: Boolean, losing: Boolean): Int = when {
        losing -> -1
        backed -> 1
        else -> 0
    }
}

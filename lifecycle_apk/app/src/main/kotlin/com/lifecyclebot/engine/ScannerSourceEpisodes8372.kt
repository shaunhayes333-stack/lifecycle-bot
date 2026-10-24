package com.lifecyclebot.engine

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A bounded source-learning cohort of DISTINCT token episodes, never a trade ledger. One lane owns each episode's
 * contribution; subsequent labels and terminal shadow outcomes correct that contribution rather than adding samples.
 * Source aliases are indexes over those same rows, not additional observations in a source-family aggregate.
 */
internal class ScannerSourceEpisodes8372(private val capacity: Int = 4_000) {
    data class Outcome(val tokenEpisodeId: String, val decisionId: String, val mode: String,
                       val sourceKeys: List<String>, val pnlPct: Double, val decisionAtMs: Long)
    data class Snapshot(val samples: Long, val wins: Long, val pnlSumPct: Double, val pnlSquareSum: Double) {
        val mean: Double get() = if (samples > 0) pnlSumPct / samples else 0.0
        val winRate: Double get() = if (samples > 0) wins.toDouble() / samples else 0.5
        val variance: Double get() = if (samples > 1)
            ((pnlSquareSum - pnlSumPct * mean) / (samples - 1)).coerceAtLeast(0.0) else 0.0
        val standardError: Double get() = if (samples > 1) sqrt(variance / samples) else Double.POSITIVE_INFINITY
        // Reuse the source brain's existing 20-observation maturity bar. A noisy mean does not replace its legacy read.
        fun supported(minSamples: Long = 20): Boolean = samples >= minSamples && mean.isFinite() &&
            standardError.isFinite() && abs(mean) > 2.0 * standardError
        /** Do not reward high-WR/net-losing sources or punish low-WR/net-winning sources on WR alone. */
        fun netAwareCentred(): Double {
            val wr = (winRate - 0.5) * 2.0
            return when {
                mean > 2.0 * standardError -> wr.coerceAtLeast(0.0)
                mean + 2.0 * standardError < 0.0 -> wr.coerceAtMost(0.0)
                else -> wr
            }
        }
    }
    data class State(val retiredThroughMs: Long, val outcomes: List<Outcome>)
    private class Totals(var n: Long = 0, var wins: Long = 0, var sum: Double = 0.0, var squares: Double = 0.0) {
        fun add(p: Double, direction: Int) {
            n += direction
            if (p > 0.0) wins += direction
            sum += direction * p
            squares += direction * p * p
        }
        fun snapshot() = Snapshot(n, wins, sum, squares)
    }

    private val outcomes = LinkedHashMap<String, Outcome>()
    private val totals = HashMap<String, Totals>()
    private var retiredThroughMs = 0L

    init { require(capacity > 0) }

    private fun key(o: Outcome) = "${o.mode}|${o.tokenEpisodeId}"
    private fun valid(o: Outcome): Boolean = o.tokenEpisodeId.isNotBlank() && o.decisionId.isNotBlank() &&
        o.mode in setOf("LIVE", "PAPER", "SHADOW") && o.decisionAtMs > 0L && o.sourceKeys.isNotEmpty() &&
        o.sourceKeys.all { it.isNotBlank() } && o.sourceKeys.distinct().size == o.sourceKeys.size &&
        o.pnlPct.isFinite() && (o.pnlPct * o.pnlPct).isFinite()

    private fun apply(o: Outcome, direction: Int) {
        o.sourceKeys.forEach { source ->
            val s = totals.getOrPut(source) { Totals() }
            s.add(o.pnlPct, direction)
            if (s.n == 0L) totals.remove(source)
        }
    }

    @Synchronized fun record(o: Outcome): Boolean {
        if (!valid(o) || o.decisionAtMs <= retiredThroughMs || outcomes.containsKey(key(o))) return false
        if (outcomes.size >= capacity) {
            val oldest = outcomes.entries.minByOrNull { it.value.decisionAtMs } ?: return false
            // Do not let a late replay evict a newer, still-correctable observation.
            if (o.decisionAtMs <= oldest.value.decisionAtMs) return false
            retiredThroughMs = maxOf(retiredThroughMs, oldest.value.decisionAtMs)
            // Retire a tied timestamp together so the persisted replay watermark never excludes a retained row.
            val expired = outcomes.filterValues { it.decisionAtMs <= retiredThroughMs }.keys.toList()
            expired.forEach { id -> outcomes.remove(id)?.let { apply(it, -1) } }
        }
        outcomes[key(o)] = o
        apply(o, 1)
        return true
    }

    @Synchronized fun revise(o: Outcome): Boolean {
        if (!valid(o)) return false
        val previous = outcomes[key(o)] ?: return false
        if (previous.decisionId != o.decisionId || previous.decisionAtMs != o.decisionAtMs ||
            previous.sourceKeys != o.sourceKeys || previous.pnlPct == o.pnlPct) return false
        apply(previous, -1)
        outcomes[key(o)] = o
        apply(o, 1)
        return true
    }

    /**
     * Called on a separately restored, successfully committed snapshot. Exact owner corrections must match its return;
     * another lane's durable ownership and a durable retirement/capacity rejection are legitimate terminal dedupes.
     * Invalid/non-source observations are deliberately ignored, never retried into invented training evidence.
     */
    @Synchronized fun acknowledges(o: Outcome): Boolean {
        if (!valid(o)) return true
        val previous = outcomes[key(o)]
        if (previous != null) {
            if (previous.decisionId != o.decisionId || previous.decisionAtMs != o.decisionAtMs ||
                previous.sourceKeys != o.sourceKeys) return true
            return previous.pnlPct == o.pnlPct
        }
        if (o.decisionAtMs <= retiredThroughMs) return true
        return outcomes.size >= capacity && o.decisionAtMs <=
            (outcomes.values.minOfOrNull { it.decisionAtMs } ?: Long.MIN_VALUE)
    }

    @Synchronized fun snapshot(source: String): Snapshot? = totals[source]?.snapshot()

    /** Family union: a compound key and each of its component keys still represent one token observation. */
    @Synchronized fun familySnapshot(tokens: List<String>): Snapshot? {
        if (tokens.isEmpty()) return null
        val union = Totals()
        outcomes.values.forEach { o ->
            if (o.sourceKeys.any { key -> tokens.any { key.contains(it) } }) union.add(o.pnlPct, 1)
        }
        return union.takeIf { it.n > 0 }?.snapshot()
    }

    @Synchronized fun export(): State = State(retiredThroughMs, outcomes.values.toList())

    @Synchronized fun restore(state: State) {
        outcomes.clear()
        totals.clear()
        retiredThroughMs = state.retiredThroughMs.coerceAtLeast(0L)
        state.outcomes.sortedBy { it.decisionAtMs }.forEach { record(it) }
    }

    @Synchronized fun size(): Int = outcomes.size
    @Synchronized fun sources(): Set<String> = totals.keys.toSet()
}

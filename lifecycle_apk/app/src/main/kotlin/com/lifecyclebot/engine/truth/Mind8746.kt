package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8746 §THE_MIND — owner: "I want them really built really implemented and engineered ... they have to interact
 * correctly with whats already there".
 *
 * MetaCognition, made real: a learned stacker over every graded voter, trained on the forward label of each frozen decision
 * (the outcome the coin produced, exits cannot touch it). SuperSSI, made real: the voters' disagreement is a measured input
 * the stacker weighs, and its record shows whether high-agreement calls beat low-agreement ones.
 *
 * Voters (each in the edge model's managed-target unit, NaN when it has nothing to say):
 *   TREE   — TreeRanker8737 (boosted trees on the bot's own labels)
 *   RIDGE  — EdgeModel8537's ridge
 *   CELL   — PayingCell8723's cell mean for this coin, when the cell is proven
 *   LLM    — LlmAnalyst8746's probability the coin is up net of cost (0..1), when it has answered
 * Features: bias, each voter (0 when absent) + a presence flag, the voters' spread (SSI) and its presence.
 *
 * Interaction with what exists: EdgeModel8537 asks [predict8746] in its verdict/snapshot path. Until the stacker has
 * [WARM_N] labelled decisions it answers null and the tree / ridge answer as before. Once warm its prediction is filed
 * under the edge model's record key MIND8746: it starts with no authority and earns admission weight and refusal rights
 * only on its own out-of-sample record (EdgeModel8537.authority / refusalAuthority8715). Nothing else changes.
 */
object Mind8746 {
    const val KEY_8746 = "MIND8746"
    private const val WARM_N = 300
    private const val DIM = 10
    private const val RIDGE_L = 8.0
    private const val FORGET = 0.999
    private const val MAX_FROZEN = 20_000

    /** Pure: the stacker's feature vector from the voters. */
    fun features8746(tree: Double, ridge: Double, cell: Double, llmP: Double): DoubleArray {
        fun v(x: Double) = if (x.isFinite()) x.coerceIn(-100.0, 200.0) else 0.0
        fun h(x: Double) = if (x.isFinite()) 1.0 else 0.0
        val llmPct = if (llmP.isFinite()) (llmP.coerceIn(0.0, 1.0) - 0.5) * 100.0 else Double.NaN
        val present = listOf(tree, ridge, cell, llmPct).filter { it.isFinite() }
        val spread = if (present.size >= 2) {
            val m = present.average(); kotlin.math.sqrt(present.sumOf { (it - m) * (it - m) } / present.size)
        } else Double.NaN
        return doubleArrayOf(1.0, v(tree), h(tree), v(ridge), v(cell), h(cell), v(llmPct), h(llmPct), v(spread), h(spread))
    }

    private val xtx = Array(DIM) { DoubleArray(DIM) }
    private val xty = DoubleArray(DIM)
    @Volatile private var w: DoubleArray? = null
    @Volatile private var n = 0L
    private val frozen = ConcurrentHashMap<String, DoubleArray>()
    // SSI record: outcomes of the calls by agreement (spread below / above the running median spread)
    private val agree = DoubleArray(3); private val disagree = DoubleArray(3)   // n, sum, sumSq
    @Volatile private var spreadMedian = Double.NaN
    private val llm = DoubleArray(3)   // n, Brier sum, hits

    /** EdgeModel8537: the stacked prediction for these voters, or null while cold. */
    fun predict8746(f: DoubleArray): Double? {
        val ww = w ?: return null
        if (n < WARM_N) return null
        var s = 0.0; for (i in 0 until DIM) s += ww[i] * f[i]
        return s.takeIf { it.isFinite() }
    }

    /** The latest reading per mint, for the Cortex's voters: (atMs, features, stacked prediction or NaN). */
    private val last = ConcurrentHashMap<String, Triple<Long, DoubleArray, Double>>()
    fun note8746(mint: String, f: DoubleArray, pred: Double?, nowMs: Long) {
        if (last.size > 8_000) last.entries.removeIf { nowMs - it.value.first > 15 * 60_000L }
        last[mint] = Triple(nowMs, f, pred ?: Double.NaN)
    }
    private fun fresh(mint: String, nowMs: Long) = last[mint]?.takeIf { nowMs - it.first in 0L..10 * 60_000L }
    /** Cortex voters: the mind's prediction / the tree's / the LLM's probability / the voters' spread for [mint], or null. */
    fun mindFor8746(mint: String, nowMs: Long): Double? = fresh(mint, nowMs)?.third?.takeIf { it.isFinite() }
    fun treeFor8746(mint: String, nowMs: Long): Double? = fresh(mint, nowMs)?.second?.takeIf { it[2] > 0.0 }?.get(1)
    fun llmFor8746(mint: String, nowMs: Long): Double? = fresh(mint, nowMs)?.second?.takeIf { it[7] > 0.0 }?.let { it[6] / 100.0 + 0.5 }
    fun spreadFor8746(mint: String, nowMs: Long): Double? = fresh(mint, nowMs)?.second?.takeIf { it[9] > 0.0 }?.get(8)

    /** EdgeModel8537.snapshot: freeze the voters of the decision on [mint] at [atMs]. */
    fun freeze8746(mint: String, atMs: Long, f: DoubleArray) {
        if (frozen.size >= MAX_FROZEN) frozen.keys.take(MAX_FROZEN / 10).forEach { frozen.remove(it) }
        frozen["$mint|$atMs"] = f
    }

    /** EdgeModel8537.learn: the decision on [mint] at [atMs] produced [y] (managed target). */
    fun learn8746(mint: String, atMs: Long, y: Double) {
        val f = frozen.remove("$mint|$atMs") ?: return
        if (!y.isFinite()) return
        val yy = y.coerceIn(-100.0, 200.0)
        synchronized(this) {
            for (i in 0 until DIM) { xty[i] = xty[i] * FORGET + f[i] * yy; for (j in 0 until DIM) xtx[i][j] = xtx[i][j] * FORGET + f[i] * f[j] }
            n++
            if (n % 25L == 0L) w = solve()
            if (f[7] > 0.0) {   // the LLM's own grade: Brier of its probability vs the base rate's
                val p = (f[6] / 100.0 + 0.5).coerceIn(0.0, 1.0); val hit = if (yy > 0.0) 1.0 else 0.0
                llm[0] += 1.0; llm[1] += (p - hit) * (p - hit); llm[2] += hit
            }
            if (f[9] > 0.0) {
                val sp = f[8]
                spreadMedian = if (spreadMedian.isFinite()) spreadMedian + 0.05 * (if (sp > spreadMedian) 1.0 else -1.0) * kotlin.math.max(1.0, kotlin.math.abs(spreadMedian) * 0.05) else sp
                val a = if (sp <= spreadMedian) agree else disagree
                a[0] += 1.0; a[1] += yy; a[2] += yy * yy
            }
        }
        try { PipelineHealthCollector.labelInc("MIND_LEARNED_8746") } catch (_: Throwable) {}
    }

    private fun solve(): DoubleArray? {
        val a = Array(DIM) { i -> DoubleArray(DIM + 1) { j -> if (j < DIM) xtx[i][j] + (if (i == j && i > 0) RIDGE_L else 0.0) else xty[i] } }
        for (c in 0 until DIM) {
            var p = c; for (r in c + 1 until DIM) if (kotlin.math.abs(a[r][c]) > kotlin.math.abs(a[p][c])) p = r
            if (kotlin.math.abs(a[p][c]) < 1e-9) return w
            val t = a[c]; a[c] = a[p]; a[p] = t
            for (r in 0 until DIM) if (r != c) { val k = a[r][c] / a[c][c]; for (j in c..DIM) a[r][j] -= k * a[c][j] }
        }
        return DoubleArray(DIM) { a[it][DIM] / a[it][it] }.takeIf { v -> v.all { it.isFinite() } }
    }

    private fun llmGrade(): String {
        if (llm[0] < 2) return "-"
        val base = llm[2] / llm[0]
        return "n${llm[0].toInt()} brier=${"%.3f".format(llm[1] / llm[0])} baseRateBrier=${"%.3f".format(base * (1 - base))}"
    }

    private fun mse(a: DoubleArray): String = if (a[0] < 2) "-" else "n${a[0].toInt()}/${"%+.1f".format(a[1] / a[0])}%"

    fun statusLine8746(): String {
        val ww = w
        val names = listOf("bias", "tree", "tree?", "ridge", "cell", "cell?", "llm", "llm?", "ssi", "ssi?")
        val weights = ww?.let { v -> names.indices.joinToString(",") { "${names[it]}=${"%.2f".format(v[it])}" } } ?: "-"
        return "mind8746[learned=$n warm=${n >= WARM_N} frozen=${frozen.size} weights{$weights} " +
            "ssi{agree=${mse(agree)} disagree=${mse(disagree)}} llmGrade{${llmGrade()}} key=$KEY_8746 rule=stacked voters earn authority only on the mind's own out-of-sample record]"
    }
}

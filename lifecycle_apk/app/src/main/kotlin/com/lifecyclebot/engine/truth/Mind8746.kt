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
    // V5.0.8842 — the 8819 clean-rebuild reset left mind_8746.json untouched, so its 44k pre-clean lessons
    // continued to dominate decisions while the edge model restarted on clean labels. A new prediction must
    // earn its OWN out-of-sample authority, not inherit MIND8767's old refusal record.
    const val KEY_8746 = "MIND8842"
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

    /**
     * V5.0.8749 — the mind's learning survives a restart. 8748 phone, 20:42: learned=135 warm=false — every app restart threw
     * away the stacker's normal equations and its SSI / LLM records, so it re-learned from nothing each session and never
     * reached authority. They are written to [FILE_8749] every [SAVE_EVERY_8749] lessons and read back once at first use.
     * (Frozen decisions still waiting for their label are not kept: a label that lands after a restart is simply missed.)
     */
    private const val FILE_8749 = "mind_8842.json"
    private const val SAVE_EVERY_8749 = 25L
    private val restored8749 = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Pure: the persisted state as JSON. */
    internal fun compatibleSavedEpoch8842(version: Int, modelEpoch: Int, savedLearningEpochMs: Long, activeLearningEpochMs: Long): Boolean =
        version == 2 && modelEpoch == EdgeModel8537.MODEL_EPOCH_8642 && savedLearningEpochMs == activeLearningEpochMs

    internal fun toJson8749(xtx: Array<DoubleArray>, xty: DoubleArray, n: Long, agree: DoubleArray, disagree: DoubleArray,
                            llm: DoubleArray, spreadMedian: Double, learningEpochMs: Long = LearningEpoch8164.epochMs8164()): String {
        fun arr(a: DoubleArray) = org.json.JSONArray().apply { a.forEach { put(if (it.isFinite()) it else 0.0) } }
        return org.json.JSONObject()
            .put("v", 2).put("modelEpoch", EdgeModel8537.MODEL_EPOCH_8642).put("learningEpochMs", learningEpochMs)
            .put("n", n).put("spreadMedian", if (spreadMedian.isFinite()) spreadMedian else null)
            .put("xtx", org.json.JSONArray().apply { xtx.forEach { put(arr(it)) } }).put("xty", arr(xty))
            .put("agree", arr(agree)).put("disagree", arr(disagree)).put("llm", arr(llm)).toString()
    }

    private fun restore8749() {
        if (!restored8749.compareAndSet(false, true)) return
        try {
            val ctx = com.lifecyclebot.AATEApp.appContextOrNull() ?: run { restored8749.set(false); return }
            val f = java.io.File(ctx.filesDir, FILE_8749)
            if (!f.exists()) return
            val j = org.json.JSONObject(f.readText())
            if (!compatibleSavedEpoch8842(j.optInt("v", 0), j.optInt("modelEpoch", 0),
                    j.optLong("learningEpochMs", -1L), LearningEpoch8164.epochMs8164())) {
                try { PipelineHealthCollector.labelInc("MIND_STALE_EPOCH_DISCARDED_8842") } catch (_: Throwable) {}
                return
            }
            fun read(a: org.json.JSONArray?, into: DoubleArray) { if (a != null && a.length() == into.size) for (i in into.indices) into[i] = a.optDouble(i, 0.0) }
            synchronized(this) {
                val m = j.optJSONArray("xtx")
                if (m != null && m.length() == DIM) for (i in 0 until DIM) read(m.optJSONArray(i), xtx[i])
                read(j.optJSONArray("xty"), xty); read(j.optJSONArray("agree"), agree); read(j.optJSONArray("disagree"), disagree)
                read(j.optJSONArray("llm"), llm)
                n = j.optLong("n", 0L)
                spreadMedian = if (j.isNull("spreadMedian")) Double.NaN else j.optDouble("spreadMedian", Double.NaN)
                w = if (n > 0L) solve() else null
            }
            try { PipelineHealthCollector.labelInc("MIND_RESTORED_8749") } catch (_: Throwable) {}
        } catch (_: Throwable) { try { PipelineHealthCollector.labelInc("MIND_RESTORE_FAILED_8749") } catch (_: Throwable) {} }
    }

    private fun save8749() {
        try {
            val ctx = com.lifecyclebot.AATEApp.appContextOrNull() ?: return
            val text = synchronized(this) { toJson8749(xtx, xty, n, agree, disagree, llm, spreadMedian) }
            val dst = java.io.File(ctx.filesDir, FILE_8749)
            val tmp = java.io.File(ctx.filesDir, "$FILE_8749.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(dst)) { dst.writeText(text); tmp.delete() }
        } catch (_: Throwable) { try { PipelineHealthCollector.labelInc("MIND_SAVE_FAILED_8749") } catch (_: Throwable) {} }
    }

    /** EdgeModel8537: the stacked prediction for these voters, or null while cold. */
    /** V5.0.8767 — the mind answers (predict8746 is non-null) once it has learned [WARM_N] labels. */
    fun warm8767(): Boolean { restore8749(); return w != null && n >= WARM_N }

    fun predict8746(f: DoubleArray): Double? {
        restore8749()
        val ww = w ?: return null
        if (n < WARM_N) return null
        var s = 0.0; for (i in 0 until DIM) s += ww[i] * f[i]
        return s.takeIf { it.isFinite() }
    }

    /**
     * The latest reading per mint AND strategy lane, for the Cortex's voters.
     * A single launch is evaluated by many lanes; caching by mint alone let the last
     * lane overwrite another lane's prediction and supplied cross-lane evidence to
     * the Cortex even though the frozen forward labels were already lane-scoped.
     */
    private val last = ConcurrentHashMap<String, Triple<Long, DoubleArray, Double>>()
    private fun readingKey8837(mint: String, lane: String) = "$mint|${lane.trim().uppercase(java.util.Locale.ROOT)}"
    fun note8746(mint: String, lane: String, f: DoubleArray, pred: Double?, nowMs: Long) {
        if (mint.isBlank() || lane.isBlank() || f.size != DIM || f.any { !it.isFinite() }) return
        if (last.size > 8_000) last.entries.removeIf { nowMs - it.value.first > 15 * 60_000L }
        last.compute(readingKey8837(mint, lane)) { _, prev ->
            if (prev != null && prev.first > nowMs) prev else Triple(nowMs, f.copyOf(), pred ?: Double.NaN)
        }
    }
    private fun fresh(mint: String, lane: String, nowMs: Long) = last[readingKey8837(mint, lane)]
        ?.takeIf { nowMs - it.first in 0L..10 * 60_000L }
    /** Cortex voters: lane-bound mind, tree, LLM probability and spread. Unknown lanes abstain. */
    fun mindFor8746(mint: String, lane: String, nowMs: Long): Double? = fresh(mint, lane, nowMs)?.third?.takeIf { it.isFinite() }
    fun treeFor8746(mint: String, lane: String, nowMs: Long): Double? = fresh(mint, lane, nowMs)?.second?.takeIf { it[2] > 0.0 }?.get(1)
    fun llmFor8746(mint: String, lane: String, nowMs: Long): Double? = fresh(mint, lane, nowMs)?.second?.takeIf { it[7] > 0.0 }?.let { it[6] / 100.0 + 0.5 }
    fun spreadFor8746(mint: String, lane: String, nowMs: Long): Double? = fresh(mint, lane, nowMs)?.second?.takeIf { it[9] > 0.0 }?.get(8)

    /**
     * V5.0.8767 §ONE_FROZEN_DECISION_PER_LANE — code audit (learning 4): the frozen voters were keyed "$mint|$atMs", and one
     * decision pass observes every lane of a coin at the same instant, so lane B's freeze overwrote lane A's: the first label
     * to land trained on another lane's features and every other lane's label found nothing. Eviction also dropped arbitrary
     * keys (hash order), and the frozen voters were memory-only, so after a restart only short-horizon lanes taught the mind.
     * Keyed by lane now, evicted oldest first, and carried with the labeler's checkpoint (EdgeModel8537.encodeSnap8571).
     */
    private fun frozenKey8767(mint: String, lane: String, atMs: Long) = "$mint|${lane.trim().uppercase()}|$atMs"

    private fun evictOldest8767() {
        if (frozen.size < MAX_FROZEN) return
        frozen.keys.sortedBy { it.substringAfterLast('|').toLongOrNull() ?: 0L }.take(MAX_FROZEN / 10).forEach { frozen.remove(it) }
    }

    /** EdgeModel8537.snapshot: freeze the voters of the decision on [mint] in [lane] at [atMs]. */
    fun freeze8746(mint: String, lane: String, atMs: Long, f: DoubleArray) {
        evictOldest8767()
        frozen[frozenKey8767(mint, lane, atMs)] = f
    }

    /** EdgeModel8537.encodeSnap8571: the frozen voters of a pending decision, to persist with it. */
    fun frozenFor8767(mint: String, lane: String, atMs: Long): DoubleArray? = frozen[frozenKey8767(mint, lane, atMs)]

    /** EdgeModel8537.decodeSnap8571: a restored pending decision brings its frozen voters back. */
    fun restoreFrozen8767(mint: String, lane: String, atMs: Long, f: DoubleArray) {
        if (f.size != DIM) return
        evictOldest8767()
        frozen.putIfAbsent(frozenKey8767(mint, lane, atMs), f)
    }

    /** EdgeModel8537.learn: the decision on [mint] in [lane] at [atMs] produced [y] (managed target). */
    fun learn8746(mint: String, lane: String, atMs: Long, y: Double) {
        restore8749()
        val f = frozen.remove(frozenKey8767(mint, lane, atMs)) ?: return
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
        if (n % SAVE_EVERY_8749 == 0L) save8749()
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
        restore8749()
        val ww = w
        val names = listOf("bias", "tree", "tree?", "ridge", "cell", "cell?", "llm", "llm?", "ssi", "ssi?")
        val weights = ww?.let { v -> names.indices.joinToString(",") { "${names[it]}=${"%.2f".format(v[it])}" } } ?: "-"
        return "mind8746[learned=$n warm=${n >= WARM_N} kept=$FILE_8749 frozen=${frozen.size} weights{$weights} " +
            "ssi{agree=${mse(agree)} disagree=${mse(disagree)}} llmGrade{${llmGrade()}} key=$KEY_8746 rule=stacked voters earn authority only on the mind's own out-of-sample record]"
    }
}

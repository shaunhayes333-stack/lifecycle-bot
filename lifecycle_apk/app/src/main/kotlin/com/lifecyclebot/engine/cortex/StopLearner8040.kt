package com.lifecyclebot.engine.cortex

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8040 — the stack grades its own stops as it learns.
 *
 * Owner: "the stack should be evaluating its strategy as it learns!!" 5.0.8038 log: of 67 graded lane plays, 62 hit
 * the lane's stop BEFORE their peak (8029: 922 of 1,013 earlier). The stop widths were constants (StopAuthority7887
 * LANE_BASE: SHITCOIN 8%, EXPRESS 10% ...) times a multiplier learned from the few LIVE closes; the hundreds of
 * graded forward paths an hour never fed back into them.
 *
 * Every forward label's path (the dip before its peak, the peak, the low, the mark now) is replayed under each
 * candidate stop in [CANDIDATES_8040], on the same spike-ladder exits the labels are graded with
 * (ForwardReturnLabeler7731.captured7945). Per lane, over its most recent [RING_8040] labels (each label keeps its
 * latest, longest-horizon path), the stop that banks the most is learned when it beats the lane's current stop by a
 * paired margin: mean(best - current) - SE >= [MIN_EDGE_PCT_8040] on [MIN_N_8040]+ labels. Re-decided every
 * [REDECIDE_EVERY_8040] labels, so it keeps moving with the market — wider when stops cut runners, tighter when
 * wider stops only lose more. StopAuthority7887 uses the learned stop as the lane's base (its clamps, runner floor
 * and the independent catastrophe backstops are unchanged). The decision persists across restarts.
 */
object StopLearner8040 {
    val CANDIDATES_8040 = doubleArrayOf(5.0, 6.0, 8.0, 10.0, 12.0, 15.0, 18.0, 21.0, 25.0)
    const val RING_8040 = 300
    const val MIN_N_8040 = 60
    const val MIN_EDGE_PCT_8040 = 0.5
    private const val REDECIDE_EVERY_8040 = 20
    private const val FILE = "stop_learner_8040.txt"

    class Decision(val stopPct: Double, val n: Int, val edgePct: Double, val atMs: Long)

    private class Book {
        val paths = LinkedHashMap<String, DoubleArray>()   // label key -> banked gross per candidate
        var sinceDecide = 0
    }

    private val books = ConcurrentHashMap<String, Book>()
    private val learned = ConcurrentHashMap<String, Decision>()
    private val labels = AtomicLong(0)
    private val decisions = AtomicLong(0)
    @Volatile private var loaded = false

    // ── pure ──

    /** Pure: the index of the candidate nearest [stopPct]. */
    fun nearest8040(stopPct: Double): Int {
        var best = 0
        for (i in CANDIDATES_8040.indices) if (kotlin.math.abs(CANDIDATES_8040[i] - stopPct) < kotlin.math.abs(CANDIDATES_8040[best] - stopPct)) best = i
        return best
    }

    /**
     * Pure: from per-label banked gross under each candidate ([rows], one array per label), the candidate that beats
     * [currentIdx] by a paired mean - SE of at least [MIN_EDGE_PCT_8040], as (index, edge); null when none does.
     */
    fun decide8040(rows: List<DoubleArray>, currentIdx: Int): Pair<Int, Double>? {
        if (rows.size < MIN_N_8040) return null
        var best: Pair<Int, Double>? = null
        for (c in CANDIDATES_8040.indices) {
            if (c == currentIdx) continue
            val d = rows.map { it[c] - it[currentIdx] }.filter { it.isFinite() }
            if (d.size < MIN_N_8040) continue
            val mean = d.average()
            val sd = kotlin.math.sqrt(d.sumOf { (it - mean) * (it - mean) } / (d.size - 1).coerceAtLeast(1))
            val lower = mean - sd / kotlin.math.sqrt(d.size.toDouble())
            if (lower >= MIN_EDGE_PCT_8040 && (best == null || mean > best.second)) best = c to mean
        }
        return best
    }

    // ── learning ──

    /**
     * ForwardReturnLabeler7731.captured7945: one label's path. [play] banks the path under a stop (positive
     * magnitude) — the same exits the label is graded with.
     */
    fun onPath8040(lane: String, key: String, baseStopPct: Double, play: (Double) -> Double) {
        val l = lane.trim().uppercase()
        if (l.isBlank() || !baseStopPct.isFinite() || baseStopPct <= 0.0) return
        load()
        val row = DoubleArray(CANDIDATES_8040.size) { i -> try { play(CANDIDATES_8040[i]) } catch (_: Throwable) { Double.NaN } }
        val book = books.computeIfAbsent(l) { Book() }
        val decideNow = synchronized(book) {
            val fresh = book.paths.remove(key) == null
            book.paths[key] = row
            while (book.paths.size > RING_8040) book.paths.remove(book.paths.keys.first())
            if (fresh) book.sinceDecide++
            book.sinceDecide >= REDECIDE_EVERY_8040
        }
        labels.incrementAndGet()
        if (decideNow) redecide(l, book, baseStopPct)
    }

    private fun redecide(lane: String, book: Book, baseStopPct: Double) {
        val rows = synchronized(book) { book.sinceDecide = 0; book.paths.values.toList() }
        val current = learned[lane]?.stopPct ?: baseStopPct
        val pick = decide8040(rows, nearest8040(current)) ?: return
        val stop = CANDIDATES_8040[pick.first]
        learned[lane] = Decision(stop, rows.size, pick.second, System.currentTimeMillis())
        decisions.incrementAndGet()
        save()
        try {
            PipelineHealthCollector.labelInc("STOP_LEARNED_8040_$lane")
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "STOP_LEARNED_8040",
                "lane=$lane from=${"%.0f".format(current)}% to=${"%.0f".format(stop)}% edge=${"%+.2f".format(pick.second)}%/label n=${rows.size}",
            )
        } catch (_: Throwable) {}
    }

    /** StopAuthority7887: the stop this lane's own graded paths chose (positive magnitude), or null. */
    fun learnedStop8040(lane: String): Double? { load(); return learned[lane.trim().uppercase()]?.stopPct }

    // ── persistence (the decisions only; paths re-accrue from labels) ──

    private fun file(): java.io.File? = com.lifecyclebot.AATEApp.appContextOrNull()?.let { java.io.File(it.filesDir, FILE) }

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            file()?.takeIf { it.exists() }?.readLines()?.forEach { line ->
                val f = line.split(',')
                if (f.size >= 5) learned[f[0]] = Decision(f[1].toDoubleOrNull() ?: return@forEach, f[2].toIntOrNull() ?: 0, f[3].toDoubleOrNull() ?: 0.0, f[4].toLongOrNull() ?: 0L)
            }
        } catch (_: Throwable) {}
    }

    private fun save() {
        try { file()?.writeText(learned.entries.joinToString("\n") { (l, d) -> "$l,${d.stopPct},${d.n},${d.edgePct},${d.atMs}" }) } catch (_: Throwable) {}
    }

    fun statusLine(): String {
        load()
        val ls = learned.entries.sortedBy { it.key }.joinToString(" · ") { (l, d) -> "$l=${d.stopPct.toInt()}%(n${d.n},${"%+.1f".format(d.edgePct)})" }
        val ns = books.entries.sortedBy { it.key }.joinToString(",") { (l, b) -> "$l:${synchronized(b) { b.paths.size }}" }
        return "learned=[${ls.ifBlank { "-" }}] paths=[${ns.ifBlank { "-" }}] labels=${labels.get()} decisions=${decisions.get()} " +
            "rule=replay every graded path under ${CANDIDATES_8040.first().toInt()}-${CANDIDATES_8040.last().toInt()}% stops; adopt when mean-SE >= +$MIN_EDGE_PCT_8040%/label on $MIN_N_8040+"
    }
}

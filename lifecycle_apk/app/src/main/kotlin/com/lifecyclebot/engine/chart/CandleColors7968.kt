package com.lifecyclebot.engine.chart

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * V5.0.7968 — candle-colour sequences, read the same way at any price or market cap.
 *
 * Owner observation (Spiralism at $47k, Jean Phil / BERT / baton at $13M-$27M): before
 * a run the red/green candles "line up regardless of chart shape". ChartMotif7950 matches
 * the price SHAPE of a 20-bar window; this matches only the colour and relative body
 * size of the last [SEQ] candles, so one pattern is the same pattern on every chart.
 *
 * Alphabet per candle (body relative to the mean absolute body of the [REF] bars before it):
 *   G = big green (>= 1.5x), g = green, d = doji (< 0.25x or < 0.05% of price), r = red, R = big red.
 * Fine key = last 5 letters (3,125 keys); coarse key = last 6 colours g/d/r (729 keys).
 * The fine key reads when it has evidence; otherwise the coarse one does.
 *
 * Learned from every window ChartLibrary7950 ingests (download, live tapes, expert charts),
 * labelled with the same outcome as the motif library (+2 ATR before -1 ATR over 20 bars,
 * and the 20-bar end %). A sequence says BUY only when its hit rate clears the base
 * rate by [LIFT] after one standard error AND its mean end % is positive after one SE.
 * Nothing is a fixed opinion: an unproven or negative sequence says nothing.
 */
object CandleColors7968 {

    private const val SEQ = 5
    private const val COARSE_SEQ = 6
    private const val REF = 20
    private const val MIN_N = 40
    private const val LIFT = 0.08
    /** Per-key counts halve past this, so old regimes fade and re-ingested series cannot inflate certainty. */
    private const val DECAY_AT = 4_000

    private class Stat(var n: Int = 0, var hits: Int = 0, var sumEnd: Double = 0.0, var sumEndSq: Double = 0.0)

    data class ColorRead7968(val key: String, val n: Int, val pUp: Double, val baseUp: Double, val meanEndPct: Double, val seEndPct: Double, val buy: Boolean)

    private val lock = Any()
    private val fine = HashMap<String, Stat>()
    private val coarse = HashMap<String, Stat>()
    private var totalN = 0L
    private var totalHits = 0L
    private val learned = AtomicLong(0)
    private val buys = AtomicLong(0)
    private val reads = AtomicLong(0)

    /** Pure: the letter for bar [i] given the mean absolute body of the [REF] bars before it. */
    fun letter7968(b: Bar7950, refBody: Double): Char {
        val body = b.c - b.o
        val px = if (b.o > 0.0) b.o else b.c
        if (!(px > 0.0) || !body.isFinite()) return 'd'
        val a = abs(body)
        if (a < px * 0.0005 || (refBody > 0.0 && a < 0.25 * refBody)) return 'd'
        val big = refBody > 0.0 && a >= 1.5 * refBody
        return if (body > 0) (if (big) 'G' else 'g') else (if (big) 'R' else 'r')
    }

    private fun refBody(bars: List<Bar7950>, before: Int): Double {
        val from = (before - REF).coerceAtLeast(0)
        if (before <= from) return 0.0
        var s = 0.0
        for (i in from until before) s += abs(bars[i].c - bars[i].o)
        return s / (before - from)
    }

    /** Pure: (fine, coarse) keys for the sequence ending at [end], or null when there are too few bars. */
    fun keys7968(bars: List<Bar7950>, end: Int): Pair<String, String>? {
        if (end < COARSE_SEQ - 1 || end >= bars.size) return null
        val ref = refBody(bars, end - SEQ + 1)
        val sbFine = StringBuilder(SEQ)
        for (i in end - SEQ + 1..end) sbFine.append(letter7968(bars[i], ref))
        val sbCoarse = StringBuilder(COARSE_SEQ)
        for (i in end - COARSE_SEQ + 1..end) sbCoarse.append(letter7968(bars[i], ref).lowercaseChar())
        return sbFine.toString() to sbCoarse.toString()
    }

    /** Pure: does this evidence say BUY? */
    fun proven7968(n: Int, hits: Int, sumEnd: Double, sumEndSq: Double, baseUp: Double): Boolean {
        if (n < MIN_N) return false
        val p = hits.toDouble() / n
        val seP = sqrt((p * (1.0 - p)).coerceAtLeast(0.0) / n)
        val mean = sumEnd / n
        val sd = sqrt(((sumEndSq / n) - mean * mean).coerceAtLeast(0.0))
        return p - seP >= baseUp + LIFT && mean - sd / sqrt(n.toDouble()) > 0.0
    }

    private fun bump(m: HashMap<String, Stat>, k: String, o: MotifOutcome7950) {
        val s = m.getOrPut(k) { Stat() }
        s.n++; if (o.hitUpFirst) s.hits++
        val e = o.endPct.toDouble().coerceIn(-100.0, 500.0)
        s.sumEnd += e; s.sumEndSq += e * e
        if (s.n >= DECAY_AT) { s.n /= 2; s.hits /= 2; s.sumEnd /= 2.0; s.sumEndSq /= 2.0 }
    }

    /** ChartLibrary7950.ingestSeries: one labelled window. */
    fun learn7968(bars: List<Bar7950>, end: Int, o: MotifOutcome7950) {
        val (kf, kc) = keys7968(bars, end) ?: return
        synchronized(lock) {
            bump(fine, kf, o); bump(coarse, kc, o)
            totalN++; if (o.hitUpFirst) totalHits++
            if (totalN >= 2_000_000L) { totalN /= 2; totalHits /= 2 }
        }
        learned.incrementAndGet()
    }

    /** The colour read of the latest candle in [bars] (null without enough bars). */
    fun read7968(bars: List<Bar7950>): ColorRead7968? {
        val end = bars.size - 1
        val (kf, kc) = keys7968(bars, end) ?: return null
        reads.incrementAndGet()
        return synchronized(lock) {
            val base = if (totalN > 0) totalHits.toDouble() / totalN else 0.0
            val f: Stat? = fine[kf]
            val k: String
            val s: Stat
            if (f != null && f.n >= MIN_N) { k = kf; s = f } else {
                k = kc
                s = coarse[kc] ?: return@synchronized ColorRead7968(kf, 0, Double.NaN, base, Double.NaN, Double.NaN, false)
            }
            val n = s.n.coerceAtLeast(1)
            val mean = s.sumEnd / n
            val sd = sqrt(((s.sumEndSq / n) - mean * mean).coerceAtLeast(0.0))
            val buy = proven7968(s.n, s.hits, s.sumEnd, s.sumEndSq, base)
            if (buy) buys.incrementAndGet()
            ColorRead7968(k, s.n, s.hits.toDouble() / n, base, mean, sd / sqrt(n.toDouble()), buy)
        }
    }

    /** The best proven sequences (for the diag): key pUp% end% n. */
    private fun top(k: Int): String = synchronized(lock) {
        val base = if (totalN > 0) totalHits.toDouble() / totalN else 0.0
        (fine.entries + coarse.entries)
            .filter { proven7968(it.value.n, it.value.hits, it.value.sumEnd, it.value.sumEndSq, base) }
            .sortedByDescending { it.value.hits.toDouble() / it.value.n }
            .take(k)
            .joinToString(",") { "${it.key}:${"%.0f".format(it.value.hits * 100.0 / it.value.n)}%/${"%+.0f".format(it.value.sumEnd / it.value.n)}%/n${it.value.n}" }
            .ifBlank { "-" }
    }

    fun statusLine7968(): String {
        val (keys, base, proven) = synchronized(lock) {
            val b = if (totalN > 0) totalHits.toDouble() / totalN else 0.0
            Triple(fine.size + coarse.size, b, (fine.values + coarse.values).count { proven7968(it.n, it.hits, it.sumEnd, it.sumEndSq, b) })
        }
        return "learned=${learned.get()} keys=$keys baseUp=${"%.0f".format(base * 100)}% proven=$proven reads=${reads.get()} buySays=${buys.get()} top[${top(4)}]"
    }

    // ── persistence (beside the motif library file) ──

    private const val MAGIC = 0x79680C01

    fun save7968(file: File) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            synchronized(lock) {
                DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 15)).use { out ->
                    out.writeInt(MAGIC); out.writeLong(totalN); out.writeLong(totalHits)
                    for (m in listOf(fine, coarse)) {
                        out.writeInt(m.size)
                        for ((k, s) in m) { out.writeUTF(k); out.writeInt(s.n); out.writeInt(s.hits); out.writeDouble(s.sumEnd); out.writeDouble(s.sumEndSq) }
                    }
                }
            }
            tmp.renameTo(file)
        } catch (_: Throwable) {}
    }

    fun load7968(file: File): Int {
        if (!file.exists()) return 0
        return try {
            DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 15)).use { inp ->
                if (inp.readInt() != MAGIC) return 0
                synchronized(lock) {
                    totalN = inp.readLong(); totalHits = inp.readLong()
                    var count = 0
                    for (m in listOf(fine, coarse)) {
                        m.clear()
                        repeat(inp.readInt().coerceIn(0, 50_000)) {
                            m[inp.readUTF()] = Stat(inp.readInt(), inp.readInt(), inp.readDouble(), inp.readDouble()); count++
                        }
                    }
                    count
                }
            }
        } catch (_: Throwable) { 0 }
    }

    /** V5.0.7977 — MemoryGuard7977: drop thin keys (hard: everything under 20 samples). */
    fun trim7977(hard: Boolean) {
        val minN = if (hard) 20 else 5
        synchronized(lock) {
            fine.entries.removeIf { it.value.n < minN }
            coarse.entries.removeIf { it.value.n < minN }
        }
    }

    fun size7977(): Int = synchronized(lock) { fine.size + coarse.size }

    internal fun resetForTest7968() = synchronized(lock) { fine.clear(); coarse.clear(); totalN = 0; totalHits = 0; learned.set(0); buys.set(0); reads.set(0) }
}

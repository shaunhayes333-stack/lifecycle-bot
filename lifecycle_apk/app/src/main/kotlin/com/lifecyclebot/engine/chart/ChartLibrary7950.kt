package com.lifecyclebot.engine.chart

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * V5.0.7950 — the chart library: scale-free fingerprints of thousands of real
 * charts (top crypto, pump.fun / Solana memes, BSC memes, and every chart the bot
 * watches live), each with what happened next. A live window is read by its
 * nearest neighbours: "the last N times a chart looked exactly like this, it ran
 * +2 ATR before -1 ATR this often, by this much".
 *
 * Fingerprints are quantised to bytes (40,000 x 123 = 4.9 MB) and searched by
 * brute force (a few ms on a phone). Downloaded history is a uniform reservoir
 * sample of every window seen (no source crowds out another); live motifs
 * replace a random slot a quarter of the time, so the library keeps learning
 * the market it trades without forgetting history.
 */
object ChartLibrary7950 {
    const val CAPACITY = 40_000
    private const val Q = 20f
    private const val DIM = ChartMotif7950.DIM
    private const val MAGIC = 0x43484C37 // "CHL7"
    private const val VERSION = 1
    const val SRC_CRYPTO = 0
    const val SRC_SOL_MEME = 1
    const val SRC_BSC_MEME = 2
    const val SRC_LIVE = 3

    private val lock = Any()
    private val feats = ByteArray(CAPACITY * DIM)
    private val hit = BooleanArray(CAPACITY)
    private val upPct = FloatArray(CAPACITY)
    private val dnPct = FloatArray(CAPACITY)
    private val endPct = FloatArray(CAPACITY)
    private val src = ByteArray(CAPACITY)
    @Volatile private var size = 0
    private var hits = 0
    private var seen = 0L
    private val rng = java.util.Random(7950)
    private val added = AtomicLong(0)
    private val queries = AtomicLong(0)
    private val bySrc = LongArray(4)

    fun size(): Int = size


    private fun q(x: Float): Byte = (x * Q).roundToInt().coerceIn(-127, 127).toByte()

    /** Add one fingerprint with its outcome. */
    fun add(f: FloatArray, o: MotifOutcome7950, source: Int) {
        if (f.size != DIM) return
        synchronized(lock) {
            seen++
            val idx = if (size < CAPACITY) size++ else {
                val r = if (source == SRC_LIVE) { if (rng.nextInt(4) != 0) return; rng.nextInt(CAPACITY) }
                else (rng.nextDouble() * seen).toLong().let { if (it < CAPACITY) it.toInt() else return }
                if (hit[r]) hits--
                r
            }
            val base = idx * DIM
            for (i in 0 until DIM) feats[base + i] = q(f[i])
            hit[idx] = o.hitUpFirst
            if (o.hitUpFirst) hits++
            upPct[idx] = o.maxUpPct
            dnPct[idx] = o.maxDnPct
            endPct[idx] = o.endPct
            src[idx] = source.coerceIn(0, 3).toByte()
            bySrc[source.coerceIn(0, 3)]++
        }
        added.incrementAndGet()
    }

    /**
     * Fingerprint the windows of [bars] that have their future: at most [maxWindows],
     * spread evenly from [fromEnd]. Returns motifs added.
     */
    fun ingestSeries(bars: List<Bar7950>, source: Int, maxWindows: Int = 40, fromEnd: Int = ChartMotif7950.WINDOW): Int {
        val first = fromEnd.coerceAtLeast(ChartMotif7950.WINDOW)
        val last = bars.size - ChartMotif7950.HORIZON - 1
        if (last < first || maxWindows <= 0) return 0
        val stride = ((last - first + 1) / maxWindows).coerceAtLeast(1)
        var n = 0
        var end = first
        while (end <= last && n < maxWindows) {
            val f = ChartMotif7950.encode(bars, end)
            val o = if (f != null) ChartMotif7950.outcome(bars, end) else null
            if (f != null && o != null) { add(f, o, source); n++ }
            end += stride
        }
        return n
    }

    /** The last window end [ingestSeries] could use for a series of [n] bars. */
    fun lastLabelledEnd(n: Int): Int = n - ChartMotif7950.HORIZON - 1

    /**
     * V5.0.7951 review — the library reads live charts only once it is mature: a
     * quarter full, with at least [MIN_PER_FAMILY] motifs from memes and from crypto
     * (5 BTC series must not overrule every learned refusal during the first build).
     */
    fun mature(): Boolean = synchronized(lock) {
        size >= CAPACITY / 4 && bySrc[SRC_CRYPTO] >= MIN_PER_FAMILY && (bySrc[SRC_SOL_MEME] + bySrc[SRC_BSC_MEME] + bySrc[SRC_LIVE]) >= MIN_PER_FAMILY
    }
    private const val MIN_PER_FAMILY = 2_000L

    @Volatile private var distEwma = Double.NaN

    /** Typical mean neighbour distance of recent reads (EWMA), NaN before any. */
    fun typicalDist(): Double = distEwma

    /** The [k] nearest motifs' verdict on [f], or null when the library is not mature. */
    fun query(f: FloatArray, k: Int = 80, requireMature: Boolean = true): MotifRead7950? {
        if (f.size != DIM) return null
        if (requireMature && !mature()) return null
        queries.incrementAndGet()
        val qf = IntArray(DIM) { q(f[it]).toInt() }
        synchronized(lock) {
            val n = size
            if (n < k * 5) return null
            val bestD = IntArray(k) { Int.MAX_VALUE }
            val bestI = IntArray(k) { -1 }
            var worst = Int.MAX_VALUE
            for (i in 0 until n) {
                val base = i * DIM
                var d = 0
                var j = 0
                while (j < DIM) {
                    val x = qf[j] - feats[base + j]
                    d += x * x
                    if (d >= worst) break
                    j++
                }
                if (d >= worst) continue
                // insert into the sorted k-best
                var p = k - 1
                while (p > 0 && bestD[p - 1] > d) { bestD[p] = bestD[p - 1]; bestI[p] = bestI[p - 1]; p-- }
                bestD[p] = d; bestI[p] = i
                worst = bestD[k - 1]
            }
            var m = 0; var h = 0; var up = 0.0; var dn = 0.0; var end = 0.0; var dist = 0.0
            for (r in 0 until k) {
                val i = bestI[r]
                if (i < 0) continue
                m++
                if (hit[i]) h++
                up += upPct[i]; dn += dnPct[i]; end += endPct[i]
                dist += kotlin.math.sqrt(bestD[r].toDouble()) / Q
            }
            if (m == 0) return null
            val p = h.toDouble() / m
            val md = dist / m
            distEwma = if (distEwma.isFinite()) distEwma * 0.98 + md * 0.02 else md
            return MotifRead7950(m, p, p - hits.toDouble() / n, up / m, dn / m, end / m, md)
        }
    }

    // ── persistence ──

    fun save(file: File) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            synchronized(lock) {
                DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { out ->
                    out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(DIM); out.writeInt(size)
                    out.write(feats, 0, size * DIM)
                    for (i in 0 until size) {
                        out.writeBoolean(hit[i]); out.writeFloat(upPct[i]); out.writeFloat(dnPct[i]); out.writeFloat(endPct[i]); out.writeByte(src[i].toInt())
                    }
                }
            }
            tmp.renameTo(file)
        } catch (_: Throwable) {}
    }

    fun load(file: File): Int {
        if (!file.exists()) return 0
        return try {
            DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16)).use { inp ->
                if (inp.readInt() != MAGIC || inp.readInt() != VERSION || inp.readInt() != DIM) return 0
                val n = inp.readInt().coerceIn(0, CAPACITY)
                synchronized(lock) {
                    inp.readFully(feats, 0, n * DIM)
                    hits = 0
                    for (i in 0 until n) {
                        hit[i] = inp.readBoolean(); upPct[i] = inp.readFloat(); dnPct[i] = inp.readFloat(); endPct[i] = inp.readFloat()
                        src[i] = inp.readByte()
                        if (hit[i]) hits++
                        bySrc[src[i].toInt().coerceIn(0, 3)]++
                    }
                    size = n
                    seen = n.toLong()
                }
                n
            }
        } catch (_: Throwable) { 0 }
    }

    fun statusLine(): String = synchronized(lock) {
        "motifs=$size/$CAPACITY baseUp=${"%.0f".format(if (size > 0) hits * 100.0 / size else 0.0)}% added=${added.get()} queries=${queries.get()} " +
            "src[crypto=${bySrc[SRC_CRYPTO]} solMeme=${bySrc[SRC_SOL_MEME]} bscMeme=${bySrc[SRC_BSC_MEME]} live=${bySrc[SRC_LIVE]}]"
    }

    internal fun resetForTest() = synchronized(lock) { size = 0; hits = 0; seen = 0L; added.set(0); queries.set(0); for (i in bySrc.indices) bySrc[i] = 0 }
}

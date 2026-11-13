package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.8737 §ONE_LEARNED_FILTER — owner, 14 Nov: "the filters have to be redesigned".
 *
 * The 8736 edge export (62,184 labelled decisions, 12,276 coin windows over 3 days): the coins the stack ADMITTED averaged
 * -7.9 % net, the coins it REFUSED -7.3 %; no single gate, lane, buy-pressure band or momentum band separated winners. The
 * ridge edge model is a sum of one-feature bins and picked nothing out of sample (oosPicks ~ +0 %).
 *
 * Walk-forward on that export, gradient-boosted trees over the same decision-time facts rank coins: AUC 0.74 for a win,
 * 0.81 for +30 %; the best 5 % by prediction on the newest 30 % averaged +12.2 % (win 31 %) against -6.9 % for all, and in
 * rolling folds the best 5 % were +2.6 / -5.0 / +6.4 / +8.0 % against -7.7 / -6.1 / -5.2 / -8.7 %. A tree reads "a deep pool
 * on a minutes-old non-pump coin with few top holders" where a sum of bins cannot.
 *
 * This is that ranker, trained on the device from the bot's own labelled dataset (no hand thresholds): histogram gradient
 * boosting, squared loss on the edge model's own managed target clipped to [CLIP_LO, CLIP_HI], one coin window weighing one
 * (rows of the same coin in several lanes share its weight), retrained every [RETRAIN_MS]. EdgeModel8537 uses its
 * prediction in place of the ridge's under its own record key, so the tree starts with NO authority and earns it only on
 * its own out-of-sample record (picks paid after one SE and beat the lane; refusals proven losing) — the existing rules.
 * Hard safety, sellability (8736) and the LP rule are untouched.
 */
object TreeRanker8737 {
    const val KEY_8737 = "TREE8737"
    private const val TREES = 200
    private const val LR = 0.05
    private const val DEPTH = 4
    private const val MIN_LEAF_W = 40.0
    private const val L2 = 1.0
    private const val BINS = 32
    private const val CLIP_LO = -60.0
    private const val CLIP_HI = 200.0
    private const val MIN_ROWS = 2_000
    private const val RETRAIN_MS = 2L * 60L * 60_000L
    private const val WINDOW_MS = 15L * 60_000L
    private const val MAX_LINES_8737 = 60_000

    /** Feature names, in vector order (the dataset's own keys; isPump and lane added). */
    val FEATURES = listOf("liq", "mcap", "age", "runup", "dd", "bp", "chg5m", "chg1h", "top", "txVel", "turnover", "curve",
        "pxPeak", "cost", "volAccel", "isPump", "lane")

    class Tree(val feat: IntArray, val split: IntArray, val nanLeft: BooleanArray, val left: IntArray, val right: IntArray,
               val value: DoubleArray) {
        fun eval(bins: IntArray): Double {
            var n = 0
            while (feat[n] >= 0) {
                val b = bins[feat[n]]
                n = if (b == BINS) (if (nanLeft[n]) left[n] else right[n]) else if (b <= split[n]) left[n] else right[n]
            }
            return value[n]
        }
    }

    class Model(val base: Double, val edges: List<DoubleArray>, val trees: List<Tree>, val rows: Int, val atMs: Long) {
        fun binsOf(x: DoubleArray): IntArray = IntArray(x.size) { f -> bin(x[f], edges[f]) }
        fun predict(x: DoubleArray): Double { val b = binsOf(x); var s = base; for (t in trees) s += t.eval(b); return s }
    }

    @Volatile private var model: Model? = null
    private val training = AtomicBoolean(false)
    @Volatile private var lastTryMs = 0L
    @Volatile private var lastStatus = "not-trained"
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "tree-ranker-8737").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    /** Pure: the feature vector of a decision (NaN = unobserved). */
    fun vectorOf(raw: EdgeModel8537.Raw, mint: String): DoubleArray = doubleArrayOf(
        raw.liq, raw.mcap, raw.ageMin, raw.runup, raw.dd, raw.bp, raw.chg5m, raw.chg1h, raw.top, raw.txVel, raw.turnover,
        raw.curve, raw.pxPeak, raw.costPct, raw.volAccel, if (mint.trim().endsWith("pump")) 1.0 else 0.0, laneIndex(raw.lane))

    /** The ranker's prediction for [raw] of [mint] in the edge model's unit, or null while untrained. */
    fun predict8737(raw: EdgeModel8537.Raw, mint: String): Double? =
        model?.predict(vectorOf(raw, mint))?.takeIf { it.isFinite() }

    fun trainedRows8737(): Long = (model?.rows ?: 0).toLong()

    /** EdgeModel8537: retrain from [dataset] in the background when due. */
    fun maybeRetrain8737(dataset: File?, nowMs: Long) {
        if (dataset == null || !dataset.exists()) return
        val m = model
        if (m != null && nowMs - m.atMs < RETRAIN_MS) return
        if (nowMs - lastTryMs < 10 * 60_000L || !training.compareAndSet(false, true)) return
        lastTryMs = nowMs
        exec.execute {
            try {
                val rows = dataset.useLines { rowsOf8737(it.take(MAX_LINES_8737)) }   // streamed: the export is ~40 MB
                if (rows.size < MIN_ROWS) { lastStatus = "waiting rows=${rows.size}/$MIN_ROWS"; return@execute }
                val t0 = System.currentTimeMillis()
                model = train8737(rows.map { it.first }, rows.map { it.second }, rows.map { it.third }, System.currentTimeMillis())
                lastStatus = "trained rows=${rows.size} trees=$TREES ms=${System.currentTimeMillis() - t0}"
                try { PipelineHealthCollector.labelInc("TREE_RANKER_TRAINED_8737") } catch (_: Throwable) {}
            } catch (t: Throwable) {
                lastStatus = "train-failed:${t.javaClass.simpleName}"
            } finally { training.set(false) }
        }
    }

    /** Pure: (features, target, weight) of the exported dataset's labelled lines; one coin window weighs one in total. */
    fun rowsOf8737(lines: Sequence<String>): List<Triple<DoubleArray, Double, Double>> {
        class R(val x: DoubleArray, val y: Double, val key: String)
        val out = ArrayList<R>()
        for (line in lines) {
            val o = try { org.json.JSONObject(line) } catch (_: Throwable) { continue }
            val net = o.optDouble("net", Double.NaN)
            val lane = o.optString("lane", "")
            if (!net.isFinite() || net < -100.0 || lane.startsWith("LPSTATE") || lane.startsWith("SETUP") ||
                lane.startsWith("PLANWAIT") || lane.startsWith("ORIGIN")) continue
            fun d(k: String) = if (o.isNull(k)) Double.NaN else o.optDouble(k, Double.NaN)
            if (!EdgeModel8537.plausible8617(d("mcap"), net, o.optBoolean("witness", false))) continue
            val mint = o.optString("mint", "")
            val cost = d("cost")
            val y = EdgeModel8537.managedTarget8722(lane.uppercase(), net, if (cost.isFinite()) cost else 0.0)
                .coerceIn(CLIP_LO, CLIP_HI)
            if (!y.isFinite()) continue
            val x = doubleArrayOf(d("liq"), d("mcap"), d("age"), d("runup"), d("dd"), d("bp"), d("chg5m"), d("chg1h"), d("top"),
                d("txVel"), d("turnover"), d("curve"), d("pxPeak"), cost, d("volAccel"),
                if (mint.trim().endsWith("pump")) 1.0 else 0.0, laneIndex(lane))
            out.add(R(x, y, "$mint|${o.optLong("t", 0L) / WINDOW_MS}"))
        }
        val per = out.groupingBy { it.key }.eachCount()
        return out.map { Triple(it.x, it.y, 1.0 / (per[it.key] ?: 1)) }
    }

    /** Pure: a boosted ensemble fitted to [xs] -> [ys] with sample weights [ws]. */
    fun train8737(xs: List<DoubleArray>, ys: List<Double>, ws: List<Double>, atMs: Long, trees: Int = TREES): Model {
        val n = xs.size
        val nf = xs.first().size
        val edges = List(nf) { f -> edgesOf(xs.map { it[f] }) }
        val bins = Array(n) { i -> IntArray(nf) { f -> bin(xs[i][f], edges[f]) } }
        val w = ws.toDoubleArray()
        val wSum = w.sum()
        val base = (0 until n).sumOf { ys[it] * w[it] } / wSum
        val f = DoubleArray(n) { base }
        val out = ArrayList<Tree>(trees)
        val idx = IntArray(n) { it }
        repeat(trees) {
            val g = DoubleArray(n) { ys[it] - f[it] }
            val t = grow(bins, g, w, idx, nf)
            out.add(t)
            for (i in 0 until n) f[i] += t.eval(bins[i])
        }
        return Model(base, edges, out, n, atMs)
    }

    private fun grow(bins: Array<IntArray>, g: DoubleArray, w: DoubleArray, all: IntArray, nf: Int): Tree {
        val feat = ArrayList<Int>(); val split = ArrayList<Int>(); val nanL = ArrayList<Boolean>()
        val left = ArrayList<Int>(); val right = ArrayList<Int>(); val value = ArrayList<Double>()
        fun newNode(): Int { feat.add(-1); split.add(0); nanL.add(false); left.add(-1); right.add(-1); value.add(0.0); return feat.size - 1 }
        fun build(rows: IntArray, depth: Int): Int {
            val node = newNode()
            var sg = 0.0; var sw = 0.0
            for (i in rows) { sg += g[i] * w[i]; sw += w[i] }
            value[node] = LR * sg / (sw + L2)
            if (depth >= DEPTH || sw < 2 * MIN_LEAF_W) return node
            val parent = sg * sg / (sw + L2)
            var bestGain = 0.0; var bf = -1; var bs = 0; var bNanLeft = false
            val hg = DoubleArray(BINS + 1); val hw = DoubleArray(BINS + 1)
            for (fi in 0 until nf) {
                java.util.Arrays.fill(hg, 0.0); java.util.Arrays.fill(hw, 0.0)
                for (i in rows) { val b = bins[i][fi]; hg[b] += g[i] * w[i]; hw[b] += w[i] }
                var lg = 0.0; var lw = 0.0
                for (b in 0 until BINS - 1) {
                    lg += hg[b]; lw += hw[b]
                    for (nanLeft in booleanArrayOf(false, true)) {
                        val lgg = lg + if (nanLeft) hg[BINS] else 0.0
                        val lww = lw + if (nanLeft) hw[BINS] else 0.0
                        val rgg = sg - lgg; val rww = sw - lww
                        if (lww < MIN_LEAF_W || rww < MIN_LEAF_W) continue
                        val gain = lgg * lgg / (lww + L2) + rgg * rgg / (rww + L2) - parent
                        if (gain > bestGain) { bestGain = gain; bf = fi; bs = b; bNanLeft = nanLeft }
                    }
                }
            }
            if (bf < 0) return node
            val goLeft = rows.filter { i -> val b = bins[i][bf]; if (b == BINS) bNanLeft else b <= bs }.toIntArray()
            val goRight = rows.filter { i -> val b = bins[i][bf]; !(if (b == BINS) bNanLeft else b <= bs) }.toIntArray()
            feat[node] = bf; split[node] = bs; nanL[node] = bNanLeft
            val l = build(goLeft, depth + 1); val r = build(goRight, depth + 1)
            left[node] = l; right[node] = r
            return node
        }
        build(all, 0)
        return Tree(feat.toIntArray(), split.toIntArray(), nanL.toBooleanArray(), left.toIntArray(), right.toIntArray(), value.toDoubleArray())
    }

    /** Pure: quantile cut points of the finite values (at most BINS - 1). */
    private fun edgesOf(v: List<Double>): DoubleArray {
        val s = v.filter { it.isFinite() }.sorted()
        if (s.isEmpty()) return DoubleArray(0)
        return (1 until BINS).map { q -> s[((s.size - 1) * q) / BINS] }.distinct().toDoubleArray()
    }

    /** Pure: the bin of [x] (BINS = unobserved). */
    private fun bin(x: Double, edges: DoubleArray): Int {
        if (!x.isFinite()) return BINS
        var lo = 0; var hi = edges.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (x <= edges[mid]) hi = mid else lo = mid + 1 }
        return lo.coerceAtMost(BINS - 1)
    }

    private fun laneIndex(lane: String): Double =
        EdgeModel8537.LANES.indexOf(lane.trim().uppercase()).takeIf { it >= 0 }?.toDouble() ?: Double.NaN

    fun statusLine8737(): String = "tree8737[$lastStatus rows=${trainedRows8737()} key=$KEY_8737 " +
        "rule=boosted trees on the bot's own labelled dataset; earns authority only on its own out-of-sample record]"
}

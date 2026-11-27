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
    const val KEY_8737 = "TREE8767"   // V5.0.8767 — a model trained on clean rows earns its own record (was "TREE8737", kept on disk)
    private const val TREES = 150
    private const val LR = 0.05
    private const val DEPTH = 4
    internal const val MIN_LEAF_W = 40.0
    private const val L2 = 1.0
    private const val BINS = 32
    private const val CLIP_LO = -60.0
    private const val CLIP_HI = 200.0
    private const val MIN_ROWS = 2_000
    private const val RETRAIN_MS = 2L * 60L * 60_000L
    private const val WINDOW_MS = 15L * 60_000L
    private const val MAX_LINES_8737 = 25_000

    /** The core columns, in vector order (the dataset's own keys; isPump and lane added). */
    private val CORE = listOf("liq", "mcap", "age", "runup", "dd", "bp", "chg5m", "chg1h", "top", "txVel", "turnover", "curve",
        "pxPeak", "cost", "volAccel", "isPump", "lane")
    /**
     * V5.0.8798 — feature names, in vector order: the core, then the dataset's other observed columns (holder velocity, new
     * buyers) and every LearnerColumns8798 column. A row written before 8798 reads NaN for the new ones (the trees route
     * an unobserved value down its own learned side).
     */
    val FEATURES: List<String> = CORE + listOf("holderVel", "newBuyers15s8666") + LearnerColumns8798.NAMES
    /** V5.0.8798 — each tree grows on a random half of the rows and a random half of the columns (stochastic boosting): the
     *  77-column fit costs what the 17-column one did, and no single noisy column dominates. */
    private const val ROW_FRAC_8798 = 0.5
    private const val COL_FRAC_8798 = 0.5

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

    /** Pure: the feature vector of a decision (NaN = unobserved), in [FEATURES] order. */
    fun vectorOf(raw: EdgeModel8537.Raw, mint: String): DoubleArray {
        val core = doubleArrayOf(
            raw.liq, raw.mcap, raw.ageMin, raw.runup, raw.dd, raw.bp, raw.chg5m, raw.chg1h, raw.top, raw.txVel, raw.turnover,
            raw.curve, raw.pxPeak, raw.costPct, raw.volAccel, if (mint.trim().endsWith("pump")) 1.0 else 0.0, laneIndex(raw.lane),
            raw.holderVel, raw.newBuyers15s8666)
        val ext = if (raw.ext.size == LearnerColumns8798.SIZE) raw.ext else DoubleArray(LearnerColumns8798.SIZE) { Double.NaN }
        return core + ext
    }

    /** The ranker's prediction for [raw] of [mint] in the edge model's unit, or null while untrained. */
    fun predict8737(raw: EdgeModel8537.Raw, mint: String): Double? =
        model?.predict(vectorOf(raw, mint))?.takeIf { it.isFinite() }

    fun trainedRows8737(): Long = (model?.rows ?: 0).toLong()

    /** EdgeModel8537: retrain from [dataset] in the background when due. */
    fun maybeRetrain8737(dataset: File?, nowMs: Long) {
        restore8865()
        if (dataset == null || !dataset.exists()) return
        val m = model
        if (m != null && nowMs - m.atMs < RETRAIN_MS) return
        if (nowMs - lastTryMs < 10 * 60_000L || !training.compareAndSet(false, true)) return
        lastTryMs = nowMs
        exec.execute {
            try {
                // V5.0.8740 — 8739 phone: training on 45,557 rows took 349 s of CPU on a loaded phone. The newest
                // [MAX_LINES_8737] lines only (the market the bot trades now).
                // V5.0.8798 — counted first, then parsed straight into rows: the tail used to hold 25,000 JSON strings
                // (~45 MB of heap, more with the wider rows) while it was parsed.
                // V5.0.8863 — the count is taken on the file's bytes (EdgeModel8537.countLines8863), and a row its own text
                // refuses is not parsed (rowsOf8737): on the phone this job spent 5.6 s of CPU counting 57,955 lines and
                // parsing the newest 25,000 of them to keep 409 rows.
                val rows = com.lifecyclebot.engine.JobCost8813.measureYielding8866("treeParse") {
                    val total = EdgeModel8537.countLines8863(dataset)
                    val skip = (total - MAX_LINES_8737).coerceAtLeast(0)
                    var idx = 0
                    dataset.useLines { seq -> rowsOf8737(seq.filter { idx++ >= skip }) }
                }
                if (rows.size < MIN_ROWS) { lastStatus = "waiting rows=${rows.size}/$MIN_ROWS"; return@execute }
                val t0 = System.currentTimeMillis()
                model = com.lifecyclebot.engine.JobCost8813.measureYielding8866("treeTrain") {
                    train8737(rows.map { it.first }, rows.map { it.second }, rows.map { it.third }, System.currentTimeMillis(),
                        rowFrac = ROW_FRAC_8798, colFrac = COL_FRAC_8798)
                }
                lastStatus = "trained rows=${rows.size} trees=$TREES ms=${System.currentTimeMillis() - t0}"
                save8865()
                try { PipelineHealthCollector.labelInc("TREE_RANKER_TRAINED_8737") } catch (_: Throwable) {}
            } catch (t: Throwable) {
                lastStatus = "train-failed:${t.javaClass.simpleName}"
            } finally { training.set(false) }
        }
    }

    // ── V5.0.8865 §THE_TRAINED_TREES_SURVIVE_A_RESTART ──────────────────────────────────────────────────────────────────
    // Owner's dataset, 19 Nov (5.0.8862): after the 08:42 restart the forecast was -6 +/- 1 in every lane (none at zero or
    // better) for 25 minutes, and the session's four live buys were made in them (08:46-08:54); from 09:10 it spread (10-25
    // points, 11-44 % at zero or better). That spread arrived with this model's first fit of the session (job meter
    // treeTrain:runs1). The fitted model lived only in this object: every restart began with none, then waited for a parse
    // of the dataset and a fit — and that day, 5 h after a learning reset, for the 2,000 usable rows the fit needs (1,285 at
    // 08:45; 2,152 at 09:05). The owner restarts with every build he installs.
    // The fitted model is now written when it is trained and read back at the first use after a start. It is the same
    // model the bot was deciding with before the restart; it is retrained on the same clock as before (its own training
    // time is kept, so a model older than RETRAIN_MS is replaced by the first fit after the start). A file from another
    // learning line (a reset), another model epoch or another feature list is refused: after a reset the trees are earned
    // again from the first row, as before.

    private const val FILE_8865 = "tree_model_8865.bin"
    private const val MAGIC_8865 = 0x54523635
    private const val MAX_TREES_8865 = 2_000
    private val MAX_NODES_8865 = 1 shl (DEPTH + 1)   // a tree of depth DEPTH holds fewer nodes than this
    private val restored8865 = AtomicBoolean(false)

    /** Pure: [m] in the stored form, stamped with the learning line, model epoch and feature count it was fitted under. */
    internal fun write8865(out: java.io.DataOutputStream, m: Model, modelEpoch: Int, learningEpochMs: Long) {
        out.writeInt(MAGIC_8865); out.writeInt(modelEpoch); out.writeLong(learningEpochMs)
        out.writeInt(m.edges.size); out.writeDouble(m.base); out.writeInt(m.rows); out.writeLong(m.atMs)
        for (e in m.edges) { out.writeInt(e.size); for (v in e) out.writeDouble(v) }
        out.writeInt(m.trees.size)
        for (t in m.trees) {
            out.writeInt(t.feat.size)
            for (i in t.feat.indices) {
                out.writeInt(t.feat[i]); out.writeInt(t.split[i]); out.writeBoolean(t.nanLeft[i])
                out.writeInt(t.left[i]); out.writeInt(t.right[i]); out.writeDouble(t.value[i])
            }
        }
        out.flush()
    }

    /**
     * Pure: the stored model, or null when it is not one this build may decide with — a different [modelEpoch], learning
     * line or [features] count, or a tree that is not well formed (every split's children lie after it, so [Tree.eval]
     * ends; every number finite). Throws only what the stream throws on a short file.
     */
    internal fun read8865(inp: java.io.DataInputStream, features: Int, modelEpoch: Int, learningEpochMs: Long): Model? {
        if (inp.readInt() != MAGIC_8865 || inp.readInt() != modelEpoch || inp.readLong() != learningEpochMs) return null
        if (inp.readInt() != features) return null
        val base = inp.readDouble(); val rows = inp.readInt(); val atMs = inp.readLong()
        if (!base.isFinite() || rows <= 0) return null
        val edges = ArrayList<DoubleArray>(features)
        repeat(features) {
            val len = inp.readInt()
            if (len < 0 || len >= BINS) return null
            val e = DoubleArray(len) { inp.readDouble() }
            if (e.any { v -> !v.isFinite() }) return null
            edges.add(e)
        }
        val nTrees = inp.readInt()
        if (nTrees <= 0 || nTrees > MAX_TREES_8865) return null
        val trees = ArrayList<Tree>(nTrees)
        repeat(nTrees) {
            val nodes = inp.readInt()
            if (nodes <= 0 || nodes > MAX_NODES_8865) return null
            val feat = IntArray(nodes); val split = IntArray(nodes); val nanLeft = BooleanArray(nodes)
            val left = IntArray(nodes); val right = IntArray(nodes); val value = DoubleArray(nodes)
            for (i in 0 until nodes) {
                feat[i] = inp.readInt(); split[i] = inp.readInt(); nanLeft[i] = inp.readBoolean()
                left[i] = inp.readInt(); right[i] = inp.readInt(); value[i] = inp.readDouble()
                if (!value[i].isFinite()) return null
                if (feat[i] >= 0) {
                    if (feat[i] >= features || split[i] !in 0 until BINS || left[i] <= i || left[i] >= nodes || right[i] <= i || right[i] >= nodes) return null
                } else if (feat[i] != -1) return null
            }
            trees.add(Tree(feat, split, nanLeft, left, right, value))
        }
        return Model(base, edges, trees, rows, atMs)
    }

    private fun file8865(): File? = try { com.lifecyclebot.AATEApp.appContextOrNull()?.filesDir?.let { File(it, FILE_8865) } } catch (_: Throwable) { null }

    /** The tree thread, after a fit: the model is written beside the dataset (a temporary file, then renamed over the old one). */
    private fun save8865() {
        val m = model ?: return
        try {
            val f = file8865() ?: return
            val tmp = File(f.path + ".tmp")
            java.io.DataOutputStream(tmp.outputStream().buffered()).use { write8865(it, m, EdgeModel8537.MODEL_EPOCH_8642, LearningEpoch8164.epochMs8164()) }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (_: Throwable) { try { PipelineHealthCollector.labelInc("TREE_RANKER_SAVE_FAILED_8865") } catch (_: Throwable) {} }
    }

    /** Once a process, at the first use: the model of the last fit, when this build may decide with it. */
    private fun restore8865() {
        if (restored8865.get() || !restored8865.compareAndSet(false, true)) return
        try {
            val f = file8865() ?: run { restored8865.set(false); return }   // no application context yet: asked again
            if (!f.exists()) return
            val m = java.io.DataInputStream(f.inputStream().buffered()).use {
                read8865(it, FEATURES.size, EdgeModel8537.MODEL_EPOCH_8642, LearningEpoch8164.epochMs8164())
            }
            if (m == null) { try { PipelineHealthCollector.labelInc("TREE_RANKER_SAVED_MODEL_REFUSED_8865") } catch (_: Throwable) {}; return }
            if (model == null) {
                model = m
                lastStatus = "restored rows=${m.rows} trees=${m.trees.size} fittedMinAgo=${((System.currentTimeMillis() - m.atMs) / 60_000L).coerceAtLeast(0L)}"
                try { PipelineHealthCollector.labelInc("TREE_RANKER_RESTORED_8865") } catch (_: Throwable) {}
            }
        } catch (_: Throwable) { try { PipelineHealthCollector.labelInc("TREE_RANKER_RESTORE_FAILED_8865") } catch (_: Throwable) {} }
    }

    /** Pure: (features, target, weight) of the exported dataset's labelled lines; one coin window weighs one in total. */
    fun rowsOf8737(lines: Sequence<String>): List<Triple<DoubleArray, Double, Double>> {
        class R(val x: DoubleArray, val y: Double, val key: String)
        val out = ArrayList<R>()
        for (line in lines) {
            com.lifecyclebot.engine.JobCost8813.cooperate9043()
            if (EdgeModel8537.refusedUnparsed8863(line)) continue   // V5.0.8863 — replayAccept8642 below would refuse it: no object built
            val o = try { org.json.JSONObject(line) } catch (_: Throwable) { continue }
            val net = o.optDouble("net", Double.NaN)
            val lane = o.optString("lane", "")
            if (!net.isFinite() || net < -100.0 || lane.startsWith("LPSTATE") || lane.startsWith("SETUP") ||
                lane.startsWith("PLANWAIT") || lane.startsWith("ORIGIN")) continue
            fun d(k: String) = EdgeModel8537.column8801(o, k)   // V5.0.8801 — old rows' price shape reads unknown
            if (!EdgeModel8537.plausible8617(d("mcap"), net, o.optBoolean("witness", false))) continue
            // V5.0.8767 — code audit (learning 1, data P0-2): the tree trained on rows the edge model itself refuses — entries
            // never buyable (fill=false: most fast meme coins), pre-8660 non-pump rows carrying pump.fun curve marks, 5 / 15 /
            // legacy 60-minute targets mixed, and pseudo-lanes (PLANADMIT_ / CHASEMISS_). As Mind8746's dominant voter it held
            // the live refusal authority. One acceptance rule for every learner of the dataset: replayAccept8642, real lanes only.
            if (lane.uppercase() !in EdgeModel8537.LANES || !EdgeModel8537.replayAccept8642(o)) continue
            val mint = o.optString("mint", "")
            val cost = d("cost")
            val y = EdgeModel8537.trainingTarget9043(o)
                .coerceIn(CLIP_LO, CLIP_HI)
            if (!y.isFinite()) continue
            val x = doubleArrayOf(d("liq"), d("mcap"), d("age"), d("runup"), d("dd"), d("bp"), d("chg5m"), d("chg1h"), d("top"),
                d("txVel"), d("turnover"), d("curve"), d("pxPeak"), cost, d("volAccel"),
                if (mint.trim().endsWith("pump")) 1.0 else 0.0, laneIndex(lane), d("holderVel"), d("newBuyers15s8666")) +
                LearnerColumns8798.fromRow(o)
            out.add(R(x, y, "$mint|${o.optLong("t", 0L) / WINDOW_MS}"))
        }
        val per = out.groupingBy { it.key }.eachCount()
        return out.map { Triple(it.x, it.y, 1.0 / (per[it.key] ?: 1)) }
    }

    /**
     * Pure: a boosted ensemble fitted to [xs] -> [ys] with sample weights [ws]. V5.0.8798: each tree may grow on a seeded
     * random [rowFrac] of the rows and [colFrac] of the columns (1.0 = all, the deterministic fit).
     */
    fun train8737(xs: List<DoubleArray>, ys: List<Double>, ws: List<Double>, atMs: Long, trees: Int = TREES,
                  rowFrac: Double = 1.0, colFrac: Double = 1.0): Model {
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
        val allFeats = IntArray(nf) { it }
        repeat(trees) { k ->
            com.lifecyclebot.engine.JobCost8813.cooperate9043()
            val g = DoubleArray(n) { ys[it] - f[it] }
            val rnd = java.util.Random(8798L + k)
            val rows = if (rowFrac >= 1.0) idx else idx.filter { rnd.nextDouble() < rowFrac }.toIntArray().takeIf { it.size >= 2 } ?: idx
            val feats = if (colFrac >= 1.0) allFeats else allFeats.filter { rnd.nextDouble() < colFrac }.toIntArray().takeIf { it.isNotEmpty() } ?: allFeats
            val t = grow(bins, g, w, rows, feats)
            out.add(t)
            for (i in 0 until n) f[i] += t.eval(bins[i])
        }
        return Model(base, edges, out, n, atMs)
    }

    private fun grow(bins: Array<IntArray>, g: DoubleArray, w: DoubleArray, all: IntArray, feats: IntArray): Tree {
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
            for (fi in feats) {
                com.lifecyclebot.engine.JobCost8813.cooperate9043()
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

    fun statusLine8737(): String = "tree8737[$lastStatus rows=${trainedRows8737()} features=${FEATURES.size} key=$KEY_8737 " +
        "rule=boosted trees on the bot's own labelled dataset; earns authority only on its own out-of-sample record; " +
        "the fitted model is kept across a restart within one learning line (8865)]"
}

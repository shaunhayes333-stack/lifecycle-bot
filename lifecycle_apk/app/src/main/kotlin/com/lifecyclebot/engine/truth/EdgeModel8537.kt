package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8537 — a LEARNED expected-net scorer per lane, judged only on predictions it made before the outcome existed.
 *
 * Owner: "I need a lot more edge from everything". 8535 showed why gates never produced it: the lanes qualified 98-100 % of
 * candidates, V3 stacked hand-set bonuses (+35 fresh-launch on a $167-liquidity coin), and admitted coins fell 9.3 % in
 * 5 minutes against 6.9 % for the refused. Every later gate filtered the output of a scorer that could not rank.
 *
 * This model learns, from the forward labels the bot already books for EVERY coin it judges (bought or refused), what a
 * coin's features predict about its executable net return after cost:
 *   - features: the coin's observed state at the decision, binned (liquidity, cap, age, run from the low, position under the
 *     peak, drawdown, buy pressure, 5-minute and 1-hour change, volume acceleration, top-holder share, launch phase,
 *     discovery source, lane) plus the round-trip cost and the V3 score. An unobserved value is its own bin, never a zero.
 *   - target: the label's net % (ForwardReturnLabeler7731 slot60 read, cost already charged), clipped to [-60, +200].
 *   - model: ridge regression on sufficient statistics with exponential forgetting, one global model (lane one-hot) and one
 *     per lane once that lane has [LANE_OWN_MIN_N] labels.
 *   - WALK-FORWARD: the prediction for a decision is stored when the decision is made; when its label lands the pair
 *     (prediction, outcome) is scored BEFORE the model learns from it. Authority is earned only by that out-of-sample record:
 *     the coins it would have bought ([MIN_EDGE_PCT]+ predicted net) must have paid on 30+ labels with mean - SE > 0, and
 *     beaten the lane's unselected average.
 * The model never overrides hard safety. Until it has authority it decides nothing; with authority it refuses coins it
 * expects to lose (paper and live) and its positive picks count as proven evidence for live.
 */
object EdgeModel8537 {

    const val MIN_EDGE_PCT = 1.0
    const val WARM_N = 300
    const val LANE_OWN_MIN_N = 600
    const val AUTH_MIN_N = 30
    private const val FORGET = 0.9997
    private const val RIDGE = 4.0
    /**
     * V5.0.8567 — the model learns the LOG return, uncapped: was netPct.coerceIn(-60, 200), so a 20x runner taught the same
     * as a 3x and the model could not learn what a big winner looks like. ln(1 + net) keeps a 50x from swamping the ridge
     * while it still counts far more than a 3x; below -99.9 % (a total loss) is the floor of the log. Small returns are almost
     * unchanged (+10 % -> +9.5, -10 % -> -10.5), so the stored model keeps learning without a reset.
     */
    fun target8567(netPct: Double): Double = 100.0 * kotlin.math.ln(1.0 + netPct.coerceAtLeast(-99.9) / 100.0)
    /** The learned log return as the net % it means. */
    fun netOf8567(target: Double): Double = if (target.isFinite()) 100.0 * (kotlin.math.exp(target / 100.0) - 1.0) else target
    private const val SOLVE_EVERY = 50
    private const val SAVE_EVERY = 400
    private const val DATASET_MAX_LINES = 60_000
    private const val MODEL_FILE = "edge_model_8537.bin"
    private const val DATASET_FILE = "edge_dataset_8537.jsonl"

    // ── features ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** The raw, human-readable observation (also the exported dataset row). NaN / "" = unobserved. */
    data class Raw(
        val lane: String, val source: String, val liq: Double, val mcap: Double, val ageMin: Double, val runup: Double,
        val pxPeak: Double, val dd: Double, val bp: Double, val chg5m: Double, val chg1h: Double, val volAccel: Double,
        val top: Double, val phase: String, val v3: Double, val costPct: Double,
        val holderVel: Double = Double.NaN, val txVel: Double = Double.NaN, val curve: Double = Double.NaN,
        val turnover: Double = Double.NaN, val flags: Int = 0,
    )

    /** Boolean facts the coin carried at the decision (bit flags in [Raw.flags]). */
    val FLAGS = listOf("EXPERT_ENTRY", "LIVE_OR_KOTH", "COPYCAT_BETA", "MIGRATED", "HL_RECLAIM", "SECOND_WAVE", "CROWD_FORMING")

    val LANES = listOf("SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "MANIPULATED", "DIP_HUNTER", "QUALITY", "BLUECHIP",
        "TREASURY", "CASHGEN", "CYCLIC", "CORE")
    val SOURCES = listOf("RAYDIUM_NEW_POOL", "PUMP_PORTAL", "PUMP_FUN_NEW", "PUMP_FUN_GRADUATE", "DEX_TRENDING", "DEX_BOOSTED",
        "WATCHLIST", "MARKET_HUNT", "SOLANA_BLUECHIP_WATCHLIST")
    val PHASES = listOf("PRE_IGNITION", "IGNITION", "EXPANDING", "POST_PUMP_FADE")

    // bin edges (upper bounds); a value >= the last edge lands in the last bin; NaN lands in the unknown bin (index 0)
    val LIQ_EDGES = doubleArrayOf(3_000.0, 10_000.0, 50_000.0, 250_000.0)
    val MCAP_EDGES = doubleArrayOf(10_000.0, 50_000.0, 250_000.0, 1_000_000.0, 5_000_000.0)
    val AGE_EDGES = doubleArrayOf(5.0, 30.0, 120.0, 720.0, 4_320.0)
    val RUNUP_EDGES = doubleArrayOf(10.0, 40.0, 100.0, 300.0)
    val PEAK_EDGES = doubleArrayOf(0.5, 0.75, 0.9, 0.97)
    val DD_EDGES = doubleArrayOf(5.0, 15.0, 35.0, 60.0)
    val BP_EDGES = doubleArrayOf(45.0, 55.0, 65.0, 80.0)
    val CHG5_EDGES = doubleArrayOf(-10.0, -2.0, 2.0, 10.0, 30.0)
    val CHG1H_EDGES = doubleArrayOf(-30.0, -5.0, 5.0, 30.0, 100.0)
    val VOLACC_EDGES = doubleArrayOf(30.0, 55.0, 80.0)
    val TOP_EDGES = doubleArrayOf(10.0, 20.0, 35.0)
    val HOLDERVEL_EDGES = doubleArrayOf(0.5, 2.0, 6.0, 15.0)
    val TXVEL_EDGES = doubleArrayOf(2.0, 10.0, 40.0, 150.0)
    val CURVE_EDGES = doubleArrayOf(0.2, 0.5, 0.8, 0.98)
    val TURNOVER_EDGES = doubleArrayOf(0.1, 0.5, 2.0, 6.0)

    private val NUMERIC_BINS: List<DoubleArray> = listOf(LIQ_EDGES, MCAP_EDGES, AGE_EDGES, RUNUP_EDGES, PEAK_EDGES, DD_EDGES,
        BP_EDGES, CHG5_EDGES, CHG1H_EDGES, VOLACC_EDGES, TOP_EDGES, HOLDERVEL_EDGES, TXVEL_EDGES, CURVE_EDGES, TURNOVER_EDGES)

    /** Width of the feature vector: bias + every binned block (+1 unknown each) + phase + source + lane (+1 other each) + flags + 2 numerics. */
    val DIM: Int = 1 + NUMERIC_BINS.sumOf { it.size + 2 } + (PHASES.size + 1) + (SOURCES.size + 1) + (LANES.size + 1) + FLAGS.size + 2

    /** Pure: the bin of [v] for [edges] — 0 unknown, 1..edges.size+1 by value. */
    fun binOf(v: Double, edges: DoubleArray): Int {
        if (!v.isFinite()) return 0
        for ((i, e) in edges.withIndex()) if (v < e) return i + 1
        return edges.size + 1
    }

    /** Pure: the source family bucket of a discovery source string. */
    fun sourceBucket(source: String): Int {
        val s = source.uppercase()
        val i = SOURCES.indexOfFirst { s.contains(it) }
        return if (i >= 0) i else SOURCES.size
    }

    /** Pure: the feature vector of [r]. */
    fun vectorOf(r: Raw): DoubleArray {
        val x = DoubleArray(DIM)
        var o = 0
        x[o++] = 1.0
        val values = doubleArrayOf(r.liq, r.mcap, r.ageMin, r.runup, r.pxPeak, r.dd, r.bp, r.chg5m, r.chg1h, r.volAccel, r.top,
            r.holderVel, r.txVel, r.curve, r.turnover)
        for ((k, edges) in NUMERIC_BINS.withIndex()) {
            x[o + binOf(values[k], edges)] = 1.0
            o += edges.size + 2
        }
        val ph = PHASES.indexOf(r.phase.trim().uppercase()).let { if (it < 0) PHASES.size else it }
        x[o + ph] = 1.0; o += PHASES.size + 1
        x[o + sourceBucket(r.source)] = 1.0; o += SOURCES.size + 1
        val ln = LANES.indexOf(r.lane.trim().uppercase()).let { if (it < 0) LANES.size else it }
        x[o + ln] = 1.0; o += LANES.size + 1
        for (b in FLAGS.indices) { if (r.flags and (1 shl b) != 0) x[o + b] = 1.0 }
        o += FLAGS.size
        x[o++] = if (r.costPct.isFinite()) (r.costPct / 10.0).coerceIn(0.0, 3.0) else 0.5
        x[o++] = if (r.v3.isFinite()) (r.v3 / 100.0).coerceIn(-1.0, 1.5) else 0.0
        return x
    }

    // ── model ────────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Ridge regression on decayed sufficient statistics; [weights] re-solved every [SOLVE_EVERY] updates. */
    private class Ridge(val dim: Int) {
        val xtx = DoubleArray(dim * dim)
        val xty = DoubleArray(dim)
        var n = 0.0
        var count = 0L
        var weights: DoubleArray? = null
        private var sinceSolve = 0

        fun learn(x: DoubleArray, y: Double, forget: Double = FORGET) {
            if (forget < 1.0) { for (i in xtx.indices) xtx[i] *= forget; for (i in xty.indices) xty[i] *= forget; n *= forget }
            for (i in 0 until dim) {
                val xi = x[i]; if (xi == 0.0) continue
                xty[i] += xi * y
                val row = i * dim
                for (j in 0 until dim) { val xj = x[j]; if (xj != 0.0) xtx[row + j] += xi * xj }
            }
            n += 1.0; count++
            if (++sinceSolve >= SOLVE_EVERY || weights == null) { weights = solve(); sinceSolve = 0 }
        }

        fun predict(x: DoubleArray): Double {
            val w = weights ?: return Double.NaN
            var s = 0.0
            for (i in 0 until dim) if (x[i] != 0.0) s += w[i] * x[i]
            return s
        }

        /** (X'X + λI) w = X'y by Cholesky; the bias (index 0) is barely penalised. */
        fun solve(lambda: Double = RIDGE): DoubleArray? {
            val a = xtx.copyOf()
            for (i in 0 until dim) a[i * dim + i] += if (i == 0) 1e-3 else lambda
            val l = DoubleArray(dim * dim)
            for (i in 0 until dim) for (j in 0..i) {
                var s = a[i * dim + j]
                for (k in 0 until j) s -= l[i * dim + k] * l[j * dim + k]
                if (i == j) { if (s <= 0.0) return weights; l[i * dim + i] = kotlin.math.sqrt(s) }
                else l[i * dim + j] = s / l[j * dim + j]
            }
            val z = DoubleArray(dim)
            for (i in 0 until dim) { var s = xty[i]; for (k in 0 until i) s -= l[i * dim + k] * z[k]; z[i] = s / l[i * dim + i] }
            val w = DoubleArray(dim)
            for (i in dim - 1 downTo 0) { var s = z[i]; for (k in i + 1 until dim) s -= l[k * dim + i] * w[k]; w[i] = s / l[i * dim + i] }
            return if (w.all { it.isFinite() }) w else weights
        }
    }

    /** Out-of-sample tally: predictions scored against outcomes that did not exist when they were made. */
    internal class Oos {
        var selN = 0; var selSum = 0.0; var selSq = 0.0; var selWin = 0
        var allN = 0; var allSum = 0.0
        var negN = 0; var negSum = 0.0
        fun add(pred: Double, net: Double) {
            allN++; allSum += net
            if (pred >= MIN_EDGE_PCT) { selN++; selSum += net; selSq += net * net; if (net > 0.0) selWin++ }
            else if (pred < 0.0) { negN++; negSum += net }
        }
        val selMean: Double get() = if (selN > 0) selSum / selN else Double.NaN
        val selSe: Double get() = if (selN > 1) kotlin.math.sqrt(((selSq / selN) - selMean * selMean).coerceAtLeast(0.0) / selN) else Double.POSITIVE_INFINITY
        val allMean: Double get() = if (allN > 0) allSum / allN else Double.NaN
        val negMean: Double get() = if (negN > 0) negSum / negN else Double.NaN
    }

    /** Pure: has an out-of-sample record earned authority? Picks paid after one SE and beat the lane's average. */
    fun authority(selN: Int, selMean: Double, selSe: Double, allMean: Double): Boolean =
        selN >= AUTH_MIN_N && selMean.isFinite() && selSe.isFinite() && selMean - selSe > 0.0 &&
            (!allMean.isFinite() || selMean > allMean)

    private val global = Ridge(DIM)
    private val byLane = HashMap<String, Ridge>()
    private val oos = HashMap<String, Oos>()
    private val learned = AtomicLong(0)
    private val skippedCold = AtomicLong(0)
    @Volatile private var loaded = false
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "AATE-EdgeModel8537").apply { isDaemon = true } }

    /** A decision's frozen features and the prediction made at that moment. */
    class Snap(val raw: Raw, val x: DoubleArray, val pred: Double, val modelKey: String, val atMs: Long, val mint: String, val admitted: Boolean)

    data class Verdict(val predPct: Double, val authority: Boolean, val modelKey: String, val trainedN: Long,
                       val oosSelN: Int, val oosSelMeanPct: Double, val oosSelSePct: Double, val oosAllMeanPct: Double)

    private fun lane(l: String) = l.trim().uppercase()

    @Synchronized private fun predictLocked(x: DoubleArray, laneKey: String): Pair<Double, String> {
        val own = byLane[laneKey]
        if (own != null && own.count >= LANE_OWN_MIN_N) return own.predict(x) to laneKey
        if (global.count >= WARM_N) return global.predict(x) to "GLOBAL"
        return Double.NaN to "COLD"
    }

    /**
     * Pure (V5.0.8553): the dataset's phase when the launch selector names none — it names a phase only for a dated coin under
     * 30 minutes, so 418 of 559 exported rows read "" for both "mature" and "age unknown". Both stay in the model's "other"
     * bucket (PHASES unchanged); the export now says which.
     */
    fun phaseOf8553(ageMin: Double): String = if (ageMin.isFinite() && ageMin > 30.0) "MATURE" else "UNKNOWN_AGE"

    /** Pure-ish: the raw observation of [ts] in [laneRaw] at [costPct] (playbook feature frame; unobserved stays NaN). */
    private fun rawOf(ts: TokenState, laneRaw: String, costPct: Double, nowMs: Long): Raw {
        val f = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.features(ts, nowMs) } catch (_: Throwable) { null }
        fun pos(v: Double?) = v?.takeIf { it.isFinite() && it > 0.0 } ?: Double.NaN
        return Raw(
            lane = lane(laneRaw), source = ts.source.ifBlank { ts.tokenMap.sourceScanner },
            liq = pos(f?.liq ?: ts.lastLiquidityUsd), mcap = pos(f?.mcap ?: ts.lastMcap), ageMin = f?.age ?: Double.NaN,
            runup = f?.runup ?: Double.NaN, pxPeak = f?.pxPeak ?: Double.NaN, dd = f?.dd ?: Double.NaN, bp = f?.bp ?: Double.NaN,
            chg5m = f?.chg5m ?: Double.NaN, chg1h = f?.chg1h ?: Double.NaN, volAccel = f?.volAccel ?: Double.NaN,
            top = f?.top ?: Double.NaN, phase = f?.launchPhase.orEmpty().ifBlank { phaseOf8553(f?.age ?: Double.NaN) },
            v3 = (ts.lastV3Score?.toDouble() ?: ts.entryScore).takeIf { it.isFinite() } ?: Double.NaN, costPct = costPct,
            holderVel = f?.holderVel7978 ?: Double.NaN, txVel = f?.txVelocity ?: Double.NaN, curve = f?.curve7978 ?: Double.NaN,
            turnover = f?.turnover7978 ?: Double.NaN,
            flags = if (f == null) 0 else flagsOf(f.expertEntry7962, f.live7978, f.beta7978, f.migrated8308, f.structureHlReclaim,
                f.secondWave7974, f.crowdForming),
        )
    }

    /** Pure: the bit flags of the boolean facts, in [FLAGS] order. */
    fun flagsOf(vararg on: Boolean): Int { var m = 0; for ((i, b) in on.withIndex()) if (b && i < FLAGS.size) m = m or (1 shl i); return m }

    /** ForwardReturnLabeler7731.observe: freeze this decision's features and the model's prediction now. */
    fun snapshot(ts: TokenState, laneRaw: String, costPct: Double, admitted: Boolean, nowMs: Long = System.currentTimeMillis()): Snap? = try {
        ensureLoaded()
        val raw = rawOf(ts, laneRaw, costPct, nowMs)
        val x = vectorOf(raw)
        val (p, key) = predictLocked(x, raw.lane)
        Snap(raw, x, p, key, nowMs, ts.mint, admitted)
    } catch (_: Throwable) { null }

    /** ForwardReturnLabeler7731.book (slot60): score the frozen prediction out of sample, THEN learn from the outcome. */
    fun learn(s: Snap, netPct: Double) {
        if (!netPct.isFinite()) return
        val y = target8567(netPct)
        synchronized(this) {
            if (s.pred.isFinite()) oos.getOrPut(s.raw.lane) { Oos() }.add(s.pred, y) else skippedCold.incrementAndGet()
            global.learn(s.x, y)
            byLane.getOrPut(s.raw.lane) { Ridge(DIM) }.learn(s.x, y)
        }
        val k = learned.incrementAndGet()
        appendDataset(s, netPct)   // V5.0.8567 — the dataset keeps the true net, uncapped
        if (k % SAVE_EVERY == 0L) saveAsync()
        try { PipelineHealthCollector.labelInc("EDGE_MODEL_LEARNED_8537") } catch (_: Throwable) {}
    }

    /** The current verdict for [ts] in [laneRaw]: the prediction and whether this lane's model has earned authority. */
    fun verdict(ts: TokenState, laneRaw: String, costPct: Double, nowMs: Long = System.currentTimeMillis()): Verdict? = try {
        ensureLoaded()
        val raw = rawOf(ts, laneRaw, costPct, nowMs)
        val x = vectorOf(raw)
        synchronized(this) {
            val (p, key) = predictLocked(x, raw.lane)
            val o = oos[raw.lane]
            val trained = if (key == raw.lane) byLane[raw.lane]?.count ?: 0L else global.count
            Verdict(netOf8567(p), p.isFinite() && o != null && authority(o.selN, o.selMean, o.selSe, o.allMean), key, trained,
                o?.selN ?: 0, o?.selMean ?: Double.NaN, o?.selSe ?: Double.NaN, o?.allMean ?: Double.NaN)
        }
    } catch (_: Throwable) { null }

    /** Pure: the decision a verdict supports — NEGATIVE refuses, POSITIVE is proven evidence, NONE decides nothing. */
    enum class Call { NEGATIVE, POSITIVE, NONE }
    fun callOf(v: Verdict?): Call = when {
        v == null || !v.authority || !v.predPct.isFinite() -> Call.NONE
        v.predPct < 0.0 -> Call.NEGATIVE
        v.predPct >= MIN_EDGE_PCT -> Call.POSITIVE
        else -> Call.NONE
    }

    // ── persistence and dataset ──────────────────────────────────────────────────────────────────────────────────────

    private fun dir(): File? = try { com.lifecyclebot.AATEApp.appContextOrNull()?.filesDir } catch (_: Throwable) { null }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            try {
                val f = File(dir() ?: return, MODEL_FILE)
                if (f.exists()) DataInputStream(f.inputStream().buffered()).use { readLocked(it) }
            } catch (_: Throwable) { try { PipelineHealthCollector.labelInc("EDGE_MODEL_LOAD_FAILED_8537") } catch (_: Throwable) {} }
        }
    }

    private fun writeRidge(o: DataOutputStream, r: Ridge) {
        o.writeDouble(r.n); o.writeLong(r.count)
        for (v in r.xtx) o.writeDouble(v)
        for (v in r.xty) o.writeDouble(v)
    }

    private fun readRidge(i: DataInputStream, r: Ridge) {
        r.n = i.readDouble(); r.count = i.readLong()
        for (k in r.xtx.indices) r.xtx[k] = i.readDouble()
        for (k in r.xty.indices) r.xty[k] = i.readDouble()
        r.weights = r.solve()
    }

    private fun readLocked(i: DataInputStream) {
        if (i.readInt() != DIM) return            // a changed feature layout starts fresh rather than misreading
        readRidge(i, global)
        repeat(i.readInt()) { val l = i.readUTF(); readRidge(i, byLane.getOrPut(l) { Ridge(DIM) }) }
        repeat(i.readInt()) {
            val l = i.readUTF(); val o = oos.getOrPut(l) { Oos() }
            o.selN = i.readInt(); o.selSum = i.readDouble(); o.selSq = i.readDouble(); o.selWin = i.readInt()
            o.allN = i.readInt(); o.allSum = i.readDouble(); o.negN = i.readInt(); o.negSum = i.readDouble()
        }
    }

    private fun saveAsync() { io.execute { saveNow() } }

    /** Persist the model and its out-of-sample record (also called when the service stops). */
    fun saveNow() {
        try {
            val d = dir() ?: return
            val tmp = File(d, "$MODEL_FILE.tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { o ->
                synchronized(this) {
                    o.writeInt(DIM)
                    writeRidge(o, global)
                    o.writeInt(byLane.size); for ((l, r) in byLane) { o.writeUTF(l); writeRidge(o, r) }
                    o.writeInt(oos.size); for ((l, s) in oos) {
                        o.writeUTF(l); o.writeInt(s.selN); o.writeDouble(s.selSum); o.writeDouble(s.selSq); o.writeInt(s.selWin)
                        o.writeInt(s.allN); o.writeDouble(s.allSum); o.writeInt(s.negN); o.writeDouble(s.negSum)
                    }
                }
            }
            if (!tmp.renameTo(File(d, MODEL_FILE))) tmp.delete()
        } catch (_: Throwable) { try { PipelineHealthCollector.labelInc("EDGE_MODEL_SAVE_FAILED_8537") } catch (_: Throwable) {} }
    }

    /** Pure: one dataset row (JSON) — the raw observation, the frozen prediction and the outcome. */
    fun datasetLine(s: Snap, net: Double, filled: Boolean = true): String {
        fun n(v: Double) = if (v.isFinite()) "%.6g".format(java.util.Locale.US, v) else "null"
        val r = s.raw
        return "{\"t\":${s.atMs},\"mint\":\"${s.mint.take(44)}\",\"lane\":\"${r.lane}\",\"src\":\"${r.source.replace("\"", "").take(60)}\"," +
            "\"liq\":${n(r.liq)},\"mcap\":${n(r.mcap)},\"age\":${n(r.ageMin)},\"runup\":${n(r.runup)},\"pxPeak\":${n(r.pxPeak)}," +
            "\"dd\":${n(r.dd)},\"bp\":${n(r.bp)},\"chg5m\":${n(r.chg5m)},\"chg1h\":${n(r.chg1h)},\"volAccel\":${n(r.volAccel)}," +
            "\"top\":${n(r.top)},\"holderVel\":${n(r.holderVel)},\"txVel\":${n(r.txVel)},\"curve\":${n(r.curve)},\"turnover\":${n(r.turnover)}," +
            "\"flags\":${r.flags},\"phase\":\"${r.phase}\",\"v3\":${n(r.v3)},\"cost\":${n(r.costPct)},\"admitted\":${s.admitted}," +
            "\"pred\":${n(netOf8567(s.pred))},\"model\":\"${s.modelKey}\",\"net\":${n(net)},\"fill\":$filled}"
    }

    /**
     * V5.0.8546 — a 5-minute outcome whose entry could not be rebased to a fillable mark (fast meme coins: no reachable mark
     * inside the rebase window) is written to the dataset flagged "fill":false — research coverage of every meme lane — and
     * never trains the model (its entry price is not one a buy could have got). 8537 exports held TREASURY / CASHGEN / crypto
     * only: not one SHITCOIN, MOONSHOT or EXPRESS decision.
     */
    fun recordUnfilled8546(s: Snap, netPct: Double) {
        if (!netPct.isFinite()) return
        unfilled8546.incrementAndGet()
        appendDataset(s, netPct, filled = false)
    }
    private val unfilled8546 = AtomicLong(0)

    private fun appendDataset(s: Snap, net: Double, filled: Boolean = true) {
        val line = try { datasetLine(s, net, filled) } catch (_: Throwable) { return }
        io.execute {
            try {
                val f = File(dir() ?: return@execute, DATASET_FILE)
                f.appendText(line + "\n")
                if (learned.get() % 5_000L == 0L && f.length() > 0) {
                    val lines = f.readLines()
                    if (lines.size > DATASET_MAX_LINES) f.writeText(lines.takeLast(DATASET_MAX_LINES / 2 * 3 / 2).joinToString("\n", postfix = "\n"))
                }
            } catch (_: Throwable) {}
        }
    }

    /** The dataset file (for the report's Save to Downloads), or null. */
    fun datasetFile(): File? {
        val d = dir() ?: return null
        return try { File(d, DATASET_FILE).takeIf { it.exists() && it.length() > 0 } } catch (_: Throwable) { null }
    }

    /** Brief: per lane, the out-of-sample record and authority. */
    fun statusLine(): String = synchronized(this.also { ensureLoaded() }) {
        val lanes = (oos.keys + byLane.keys).toSortedSet()
        val rows = lanes.joinToString(" · ") { l ->
            val o = oos[l]; val r = byLane[l]
            val auth = o != null && authority(o.selN, o.selMean, o.selSe, o.allMean)
            "$l[n${r?.count ?: 0} oosPicks=n${o?.selN ?: 0}/${pct(o?.selMean)}±${pct(o?.selSe)} all=${pct(o?.allMean)} avoid=${pct(o?.negMean)} ${if (auth) "AUTHORITY" else "learning"}]"
        }
        "edge8537[trained=${global.count} learned=${learned.get()} cold=${skippedCold.get()} minEdge=+${MIN_EDGE_PCT}% authBar=n$AUTH_MIN_N,mean-se>0,>lane] ${rows.ifBlank { "no labels yet" }}"
    }

    private fun pct(v: Double?) = if (v != null && v.isFinite()) "%+.1f%%".format(v) else "-"

    internal fun resetForTest8537() = synchronized(this) {
        byLane.clear(); oos.clear(); learned.set(0); skippedCold.set(0); loaded = true
        java.util.Arrays.fill(global.xtx, 0.0); java.util.Arrays.fill(global.xty, 0.0); global.n = 0.0; global.count = 0; global.weights = null
    }

    /** Test seam: learn one labelled raw observation directly (no file IO). */
    internal fun learnRawForTest8537(r: Raw, net: Double) = synchronized(this) {
        val x = vectorOf(r)
        val (p, key) = predictLocked(x, r.lane)
        val y = target8567(net)
        if (p.isFinite()) oos.getOrPut(r.lane) { Oos() }.add(p, y)
        global.learn(x, y)
        byLane.getOrPut(r.lane) { Ridge(DIM) }.learn(x, y)
        learned.incrementAndGet()
        key
    }

    internal fun predictRawForTest8537(r: Raw): Pair<Double, String> = predictLocked(vectorOf(r), r.lane)

    internal fun oosForTest8537(lane: String): Oos? = synchronized(this) { oos[lane(lane)] }
}

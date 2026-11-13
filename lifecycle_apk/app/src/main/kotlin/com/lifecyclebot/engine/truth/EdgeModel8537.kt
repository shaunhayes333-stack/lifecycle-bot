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
    /**
     * V5.0.8722 §THE_MODEL_LEARNS_WHAT_THE_LANE_BANKS — owner (8720, live, 70 minutes, no trade): "its totally dead now".
     * The model learned [target8567], the LOG return. A log return charges variance: a -60 % coin is -92, a +400 % coin is
     * +161, so the launch coins that hold every 4x average far below zero and the model's positive picks are the quiet coins
     * that cannot move. 8720 dataset, 75,931 labels walk-forward: the coins it predicted >= +1 % banked -4.4 % each
     * (the round trip) and 0.1 % of them ran 100 %; 1.6 % of the coins it refused did. 8715 then gave that prediction the
     * right to refuse live, and live could only be offered the coins that do not run.
     *
     * The target is now the outcome the lane's own exits bank on the label (ManagedBands8719.managed8719: the loss stops
     * at the lane's stop plus a round trip unless the label fell through twice that distance, the gain is kept to the
     * pocket cap), in net %. Same data, same walk-forward, with the three structure crosses below.
     * V5.0.8724 correction: the 8722 note here read -1.0 % / +1.9 % for the kept coins; that counted every pulled pool as
     * a filled stop. With a pull counted as it printed the kept coins bank -3 .. -5 % each (2 - 3.7 % running, against
     * 0.1 % under the log target) and the refused coins -8 % (1.3 % running). The model keeps the runners and names the
     * losers; it is not by itself a profitable selector. Predictions,
     * the out-of-sample records and both authorities are in this unit; the epoch moves so the stored log model is rebuilt
     * from the device's own dataset and earns its records again.
     */
    fun managedTarget8722(lane: String, netPct: Double, costPct: Double): Double =
        ManagedBands8719.managed8719(netPct, try { FieldManual7715.laneStopPct8392(lane.trim().uppercase()) } catch (_: Throwable) { Double.NaN }, costPct)
    /** The learned log return as the net % it means. */
    fun netOf8567(target: Double): Double = if (target.isFinite()) 100.0 * (kotlin.math.exp(target / 100.0) - 1.0) else target
    private const val SOLVE_EVERY = 50
    private const val SAVE_EVERY = 400
    private const val DATASET_MAX_LINES = 60_000
    // Keep the pre-8658 curve-tainted sufficient statistics intact for inspection; never load them as authority.
    private const val MODEL_FILE = "edge_model_8722.bin"
    private const val REFUSAL_BLOCK_8715 = 8715
    private const val DATASET_FILE = "edge_dataset_8537.jsonl"
    // V5.0.8642 — the old binary included false-priced/seed-basis outcomes before 8617 started excluding them.
    // DIM alone cannot attest the model's evidence quality, so a validated epoch is now part of the format.
    // V5.0.8660: 8657/8658 fixed non-pump PumpPortal frames priced with pump.fun's curve, but the 8650 model
    // remained loaded with those phantom 2,900% labels and reported CORE/TREASURY authority. A fresh model
    // must replay only venue-scoped rows and earn OOS authority again, never inherit the poisoned old OOS bar.
    internal const val MODEL_EPOCH_8642 = 8722
    internal const val VENUE_SCOPE_EPOCH_8660 = 8660
    private val SOLANA_MINT_8660 = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")
    private fun unscopedSolana8661(mint: String, scopeEpoch: Int): Boolean =
        SOLANA_MINT_8660.matches(mint) &&
            !com.lifecyclebot.network.PumpCurveKeys7269.isPumpMint8658(mint) &&
            scopeEpoch < VENUE_SCOPE_EPOCH_8660

    /** Same venue-price provenance gate for every downstream learner, not only this model's private replay. */
    internal fun venueScopedForLearning8663(mint: String, modelEpoch: Int?): Boolean =
        !unscopedSolana8661(mint, if (modelEpoch != null && modelEpoch >= VENUE_SCOPE_EPOCH_8660) VENUE_SCOPE_EPOCH_8660 else 0)

    // ── features ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** The raw, human-readable observation (also the exported dataset row). NaN / "" = unobserved. */
    data class Raw(
        val lane: String, val source: String, val liq: Double, val mcap: Double, val ageMin: Double, val runup: Double,
        val pxPeak: Double, val dd: Double, val bp: Double, val chg5m: Double, val chg1h: Double, val volAccel: Double,
        val top: Double, val phase: String, val v3: Double, val costPct: Double,
        val holderVel: Double = Double.NaN, val txVel: Double = Double.NaN, val curve: Double = Double.NaN,
        val turnover: Double = Double.NaN, val flags: Int = 0,
        // The persisted model schema includes buyer arrivals and observed demand/reclaim.
        val newBuyers15s8666: Double = Double.NaN,
        val sustainedDemand: Boolean = false,
        val marketReclaim: Boolean = false,
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

    val NEW_BUYER_EDGES = doubleArrayOf(1.0, 2.0, 5.0, 10.0)

    private val NUMERIC_BINS: List<DoubleArray> = listOf(LIQ_EDGES, MCAP_EDGES, AGE_EDGES, RUNUP_EDGES, PEAK_EDGES, DD_EDGES,
        BP_EDGES, CHG5_EDGES, CHG1H_EDGES, VOLACC_EDGES, TOP_EDGES, HOLDERVEL_EDGES, TXVEL_EDGES, CURVE_EDGES, TURNOVER_EDGES, NEW_BUYER_EDGES)

    /**
     * V5.0.8722 — the pairs read together (indices into [NUMERIC_BINS]): age x pool, curve x distance from the high, top
     * holder x pool. A sum of single-feature bins cannot say "a deep pool on a five-minute-old coin"; the 8712 dataset paid
     * on exactly those three pairs (+35 %, +10 %, +11 % banked) and on neither feature alone.
     */
    private val CROSSES_8722: List<Pair<Int, Int>> = listOf(2 to 0, 13 to 4, 10 to 0)

    /** Width of the feature vector: bias + every binned block (+1 unknown each) + phase + source + lane (+1 other each) + flags + 2 numerics. */
    val DIM: Int = 1 + NUMERIC_BINS.sumOf { it.size + 2 } + (PHASES.size + 1) + (SOURCES.size + 1) + (LANES.size + 1) + FLAGS.size + 4 +
        CROSSES_8722.sumOf { (a, b) -> (NUMERIC_BINS[a].size + 2) * (NUMERIC_BINS[b].size + 2) }

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
            r.holderVel, r.txVel, r.curve, r.turnover, r.newBuyers15s8666)
        val bins = IntArray(NUMERIC_BINS.size)
        for ((k, edges) in NUMERIC_BINS.withIndex()) {
            bins[k] = binOf(values[k], edges)
            x[o + bins[k]] = 1.0
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
        x[o++] = if (r.sustainedDemand) 1.0 else 0.0
        x[o++] = if (r.marketReclaim) 1.0 else 0.0
        for ((a, b) in CROSSES_8722) {
            val wb = NUMERIC_BINS[b].size + 2
            x[o + bins[a] * wb + bins[b]] = 1.0
            o += (NUMERIC_BINS[a].size + 2) * wb
        }
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
        // V5.0.8722 — the decay is carried as one factor (the statistics are stored / gain) instead of multiplying the whole
        // matrix on every label: a 60,000-row replay of the wider vector was 7 billion multiplications on the first read.
        private var gain = 1.0

        /** Bring the stored statistics back to their true scale (before a write, and before the factor underflows). */
        fun fold8722() {
            if (gain == 1.0) return
            for (i in xtx.indices) xtx[i] *= gain
            for (i in xty.indices) xty[i] *= gain
            gain = 1.0
        }

        fun learn(x: DoubleArray, y: Double, forget: Double = FORGET, solveNow: Boolean = true) {
            if (forget < 1.0) { gain *= forget; n *= forget; if (gain < 1e-120) fold8722() }
            val inc = 1.0 / gain
            val on = IntArray(dim); var m = 0
            for (i in 0 until dim) if (x[i] != 0.0) on[m++] = i
            for (a in 0 until m) {
                val i = on[a]; val xi = x[i] * inc
                xty[i] += xi * y
                val row = i * dim
                for (b in 0 until m) { val j = on[b]; xtx[row + j] += xi * x[j] }
            }
            n += 1.0; count++
            sinceSolve++
            if (solveNow && (sinceSolve >= SOLVE_EVERY || weights == null)) { weights = solve(); sinceSolve = 0 }
        }

        fun finishReplay8642() { weights = solve(); sinceSolve = 0 }

        fun clear8642() {
            java.util.Arrays.fill(xtx, 0.0); java.util.Arrays.fill(xty, 0.0)
            n = 0.0; count = 0; weights = null; sinceSolve = 0; gain = 1.0
        }

        fun predict(x: DoubleArray): Double {
            val w = weights ?: return Double.NaN
            var s = 0.0
            for (i in 0 until dim) if (x[i] != 0.0) s += w[i] * x[i]
            return s
        }

        /** (X'X + λI) w = X'y by Cholesky; the bias (index 0) is barely penalised. */
        fun solve(lambda: Double = RIDGE): DoubleArray? {
            val a = DoubleArray(xtx.size) { xtx[it] * gain }
            for (i in 0 until dim) a[i * dim + i] += if (i == 0) 1e-3 else lambda
            val l = DoubleArray(dim * dim)
            for (i in 0 until dim) for (j in 0..i) {
                var s = a[i * dim + j]
                for (k in 0 until j) s -= l[i * dim + k] * l[j * dim + k]
                if (i == j) { if (s <= 0.0) return weights; l[i * dim + i] = kotlin.math.sqrt(s) }
                else l[i * dim + j] = s / l[j * dim + j]
            }
            val z = DoubleArray(dim)
            for (i in 0 until dim) { var s = xty[i] * gain; for (k in 0 until i) s -= l[i * dim + k] * z[k]; z[i] = s / l[i * dim + i] }
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
        // V5.0.8715 — the refusal record: what the coins it predicted to lose ([refN]) and the rest ([keepN]) went on to do.
        var refN = 0; var refSum = 0.0; var refSq = 0.0
        var keepN = 0; var keepSum = 0.0
        /** [pred] and [net] in the model's unit since 8722: the net % the lane banks ([managedTarget8722]). */
        fun add(pred: Double, net: Double) {
            allN++; allSum += net
            if (pred >= MIN_EDGE_PCT) { selN++; selSum += net; selSq += net * net; if (net > 0.0) selWin++ }
            else if (pred < 0.0) { negN++; negSum += net }
            // the outcome is clipped as the model's own target is, so one runner cannot acquit a cohort
            val clipped = net.coerceIn(-100.0, REFUSAL_CLIP_PCT_8715)
            if (pred < 0.0) { refN++; refSum += clipped; refSq += clipped * clipped } else { keepN++; keepSum += clipped }
        }
        val refMean: Double get() = if (refN > 0) refSum / refN else Double.NaN
        val refSe: Double get() = if (refN > 1) kotlin.math.sqrt(((refSq / refN) - refMean * refMean).coerceAtLeast(0.0) / refN) else Double.POSITIVE_INFINITY
        val keepMean: Double get() = if (keepN > 0) keepSum / keepN else Double.NaN
        val selMean: Double get() = if (selN > 0) selSum / selN else Double.NaN
        val selSe: Double get() = if (selN > 1) kotlin.math.sqrt(((selSq / selN) - selMean * selMean).coerceAtLeast(0.0) / selN) else Double.POSITIVE_INFINITY
        val allMean: Double get() = if (allN > 0) allSum / allN else Double.NaN
        val negMean: Double get() = if (negN > 0) negSum / negN else Double.NaN
    }

    /** Pure: has an out-of-sample record earned authority? Picks paid after one SE and beat the lane's average. */
    fun authority(selN: Int, selMean: Double, selSe: Double, allMean: Double): Boolean =
        selN >= AUTH_MIN_N && selMean.isFinite() && selSe.isFinite() && selMean - selSe > 0.0 &&
            (!allMean.isFinite() || selMean > allMean)

    /**
     * V5.0.8715 §THE_MODEL_REFUSES_ON_ITS_REFUSAL_RECORD — the 8712 edge dataset (72,220 labelled decisions, 3 days): the
     * prediction made before each outcome ranks it (rank IC +0.31; worst fifth -22 % net, best fifth -3 %), and LIVE-side
     * admission ignored it — 53 % of everything admitted carried a prediction of -20 % or worse and averaged -18.7 %; the
     * admitted coins predicted >= 0 averaged 0.0 %. The model could refuse only after its positive PICKS had paid
     * ([authority]); in a market where nothing pays that never happens, so the part of the model that works was switched
     * off by the part that has nothing to find. The right to refuse is now earned on the refusal's own out-of-sample
     * record: [AUTH_MIN_N]+ coins it predicted to lose, proven losing (mean + SE < 0) and worse than the coins it kept.
     */
    fun refusalAuthority8715(refN: Int, refMean: Double, refSe: Double, keepMean: Double): Boolean =
        refN >= AUTH_MIN_N && refMean.isFinite() && refSe.isFinite() && refMean + refSe < 0.0 &&
            (!keepMean.isFinite() || refMean < keepMean)
    const val REFUSAL_CLIP_PCT_8715 = 200.0

    private val global = Ridge(DIM)
    private val byLane = HashMap<String, Ridge>()
    // Legacy records used log outcomes and pooled global and specialist predictions.
    private fun recordKey(lane: String, model: String) = "net|$lane|$model"
    /** V5.0.8722 — a lane the vector names keeps its own model; every other label (crypto, EVM, shadow tags) learns the global one. */
    private fun ownModel8722(l: String): Ridge? = if (l in LANES) byLane.getOrPut(l) { Ridge(DIM) } else null
    private fun activeModel(l: String) = if ((byLane[l]?.count ?: 0L) >= LANE_OWN_MIN_N) l else "GLOBAL"
    private val oos = HashMap<String, Oos>()
    private val learned = AtomicLong(0)
    private val skippedCold = AtomicLong(0)
    private val oldPredictionsIgnored8642 = AtomicLong(0)
    @Volatile private var replayStatus8642 = "not-needed"
    @Volatile private var loaded = false
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "AATE-EdgeModel8537").apply { isDaemon = true } }

    /** A decision's frozen features and the prediction made at that moment. */
    class Snap(val raw: Raw, val x: DoubleArray, val pred: Double, val modelKey: String, val atMs: Long, val mint: String,
               val admitted: Boolean, val horizonMs: Long = 0L, val modelEpoch: Int = MODEL_EPOCH_8642)

    // V5.0.8571 — the frozen decision snapshot travels with the labeler's pending checkpoint. It was memory-only, so every
    // observation restored after a restart (2,414 in the 8562 session) could never train the model: an hour-graded meme
    // lane rarely survives an hour of uptime, which is why the dataset held 1-10 rows per meme lane. NaN is "unobserved"
    // and JSON has no NaN, so doubles travel as strings.
    private fun d8571(v: Double): String = v.toString()
    private fun d8571(j: org.json.JSONObject, k: String): Double = j.optString(k).toDoubleOrNull() ?: Double.NaN

    fun encodeSnap8571(s: Snap): org.json.JSONObject {
        val r = s.raw
        val raw = org.json.JSONObject().put("lane", r.lane).put("source", r.source).put("phase", r.phase).put("flags", r.flags)
        listOf("liq" to r.liq, "mcap" to r.mcap, "ageMin" to r.ageMin, "runup" to r.runup, "pxPeak" to r.pxPeak, "dd" to r.dd,
            "bp" to r.bp, "chg5m" to r.chg5m, "chg1h" to r.chg1h, "volAccel" to r.volAccel, "top" to r.top, "v3" to r.v3,
            "costPct" to r.costPct, "holderVel" to r.holderVel, "txVel" to r.txVel, "curve" to r.curve, "turnover" to r.turnover,
            "newBuyers15s8666" to r.newBuyers15s8666,
        ).forEach { (k, v) -> raw.put(k, d8571(v)) }
        raw.put("sustainedDemand", r.sustainedDemand).put("marketReclaim", r.marketReclaim)
        return org.json.JSONObject().put("raw", raw).put("pred", d8571(s.pred)).put("horizonMs", s.horizonMs)
            .put("modelEpoch", s.modelEpoch)
            .put("key", s.modelKey).put("at", s.atMs).put("mint", s.mint).put("admitted", s.admitted)
    }

    /** The feature vector is rebuilt from the frozen raw features ([vectorOf] is pure), so it is never stored. */
    fun decodeSnap8571(j: org.json.JSONObject?): Snap? {
        if (j == null) return null
        return try {
            val r = j.getJSONObject("raw")
            val raw = Raw(r.optString("lane"), r.optString("source"), d8571(r, "liq"), d8571(r, "mcap"), d8571(r, "ageMin"),
                d8571(r, "runup"), d8571(r, "pxPeak"), d8571(r, "dd"), d8571(r, "bp"), d8571(r, "chg5m"), d8571(r, "chg1h"),
                d8571(r, "volAccel"), d8571(r, "top"), r.optString("phase"), d8571(r, "v3"), d8571(r, "costPct"),
                d8571(r, "holderVel"), d8571(r, "txVel"), d8571(r, "curve"), d8571(r, "turnover"), r.optInt("flags"),
                d8571(r, "newBuyers15s8666"), r.optBoolean("sustainedDemand"), r.optBoolean("marketReclaim"))
            Snap(raw, vectorOf(raw), d8571(j, "pred"), j.optString("key"), j.optLong("at"), j.optString("mint"),
                j.optBoolean("admitted"), j.optLong("horizonMs"), j.optInt("modelEpoch", 0))
        } catch (_: Throwable) { null }
    }

    data class Verdict(val predPct: Double, val authority: Boolean, val modelKey: String, val trainedN: Long,
                       val oosSelN: Int, val oosSelMeanPct: Double, val oosSelSePct: Double, val oosAllMeanPct: Double,
                       /** V5.0.8715 — the model's refusals have earned authority on their own record ([refusalAuthority8715]). */
                       val refuses8715: Boolean = false)

    private fun lane(l: String) = l.trim().uppercase()

    /** V5.0.8747 — the paying-cell voter read at most once a minute per coin and lane (it rebuilds the playbook frame). */
    private val cellCache8747 = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Double>>()
    private fun cellCached8747(ts: TokenState?, lane: String, now: Long): Double? {
        ts ?: return null
        val k = ts.mint + "|" + lane
        cellCache8747[k]?.let { if (now - it.first in 0L..60_000L) return it.second.takeIf { v -> v.isFinite() } }
        val v = try { PayingCell8723.evidence8723(ts, lane, now)?.meanPct } catch (_: Throwable) { null }
        if (cellCache8747.size > 20_000) cellCache8747.clear()
        cellCache8747[k] = now to (v ?: Double.NaN)
        return v
    }

    /**
     * V5.0.8737 — the boosted-tree ranker predicts once it has trained on this device's dataset; its record key is its own,
     * so it earns authority from nothing on its own out-of-sample picks and refusals. Until then the ridge answers.
     */
    private fun predict8737(raw: Raw, x: DoubleArray, mint: String, ts: TokenState? = null, freezeAtMs: Long? = null): Pair<Double, String> {
        val now = System.currentTimeMillis()
        try { TreeRanker8737.maybeRetrain8737(dir()?.let { File(it, DATASET_FILE) }, now) } catch (_: Throwable) {}
        val tree = try { TreeRanker8737.predict8737(raw, mint) } catch (_: Throwable) { null }
        // V5.0.8746 — the mind: every voter, stacked; it answers only once it has learned (else tree / ridge as before).
        val ridge = predictLocked(x, raw.lane)
        val cell = cellCached8747(ts, raw.lane, now)
        if (tree != null && tree >= 0.0) try { LlmAnalyst8746.request8746(raw, mint, now) } catch (_: Throwable) {}
        val llmP = try { LlmAnalyst8746.prob8746(mint, now) } catch (_: Throwable) { null }
        val f = Mind8746.features8746(tree ?: Double.NaN, ridge.first, cell ?: Double.NaN, llmP ?: Double.NaN)
        if (freezeAtMs != null) try { Mind8746.freeze8746(mint, freezeAtMs, f) } catch (_: Throwable) {}
        val mind = try { Mind8746.predict8746(f) } catch (_: Throwable) { null }
        try { Mind8746.note8746(mint, f, mind, now) } catch (_: Throwable) {}
        return when {
            mind != null -> mind to Mind8746.KEY_8746
            tree != null -> tree to TreeRanker8737.KEY_8737
            else -> ridge
        }
    }

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

    /** V5.0.8610 — PocketLedger8610.verdict8610: the same observation the ledger learned from, read now. */
    fun rawFor8610(ts: TokenState, laneRaw: String, nowMs: Long): Raw = rawOf(ts, laneRaw, Double.NaN, nowMs)

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
            newBuyers15s8666 = f?.newBuyers15s8666 ?: Double.NaN,
            sustainedDemand = f?.sustainedDemand == true, marketReclaim = f?.marketReclaim == true,
        )
    }

    /** Pure: the bit flags of the boolean facts, in [FLAGS] order. */
    fun flagsOf(vararg on: Boolean): Int { var m = 0; for ((i, b) in on.withIndex()) if (b && i < FLAGS.size) m = m or (1 shl i); return m }

    /** ForwardReturnLabeler7731.observe: freeze this decision's features and the model's prediction now. */
    fun snapshot(ts: TokenState, laneRaw: String, costPct: Double, admitted: Boolean, nowMs: Long = System.currentTimeMillis()): Snap? = try {
        ensureLoaded()
        val raw = rawOf(ts, laneRaw, costPct, nowMs)
        val x = vectorOf(raw)
        val (p, key) = predict8737(raw, x, ts.mint, ts, freezeAtMs = nowMs)
        Snap(raw, x, p, key, nowMs, ts.mint, admitted, PocketLedger8610.targetHorizon(raw.lane))
    } catch (_: Throwable) { null }

    /**
     * V5.0.8650: a cap at the DECISION instant cannot impose a 100%-400% ceiling on a FUTURE realised runner.
     * That 8617 outlier filter correctly rejected the old +518,638% and +1.4-billion-percent basis errors, but
     * silently dropped legitimate 500%-1,100% opportunities from every setup, sheet, model and forward learner.
     * A real same-venue chain price witness can validate a still larger move; without it the conservative 12x/50x
     * ceilings remain. An unknown market cap by itself supplies no evidence for a large win.
     */
    fun plausible8617(mcapUsd: Double, netPct: Double, chainWitness: Boolean = false): Boolean {
        // A spot position cannot lose more than the entire cost basis. Reject broken quote/basis
        // arithmetic from ALL learners; the log transform's -99.9% mathematical floor is not proof.
        if (!netPct.isFinite() || netPct < -100.0) return false
        val unwitnessedCap = when {
            !mcapUsd.isFinite() || mcapUsd <= 0.0 -> 400.0
            mcapUsd < 100_000.0 -> 5_000.0
            else -> 1_200.0
        }
        return netPct <= unwitnessedCap || (chainWitness && netPct <= 10_000.0)
    }
    private val implausible8617 = AtomicLong(0L)

    /** ForwardReturnLabeler7731.book (slot60): score the frozen prediction out of sample, THEN learn from the outcome. */
    fun learn(s: Snap, netPct: Double, chainWitness: Boolean = false) {
        if (!netPct.isFinite()) return
        // An in-flight decision captured before the curve fix can arrive AFTER the new
        // model's first boot. Its stored prediction epoch identifies that old price basis;
        // never reintroduce its phantom outcome through the live learn path or pocket ledger.
        if (!venueScopedForLearning8663(s.mint, s.modelEpoch)) {
            try { PipelineHealthCollector.labelInc("EDGE_LEGACY_NONPUMP_UNSCOPED_8661") } catch (_: Throwable) {}
            return
        }
        // V5.0.8617 — an implausible 5-minute outcome never trains anything (the model, the pocket ledger, the dataset's
        // training rows). The 03:50 export held a $2.96 M day-old coin at +1,406,280,000 % with a -1.9 % 5 m move and a
        // $112 k coin at +518,638 %: 13 rows over 5 mints, each a mark from the wrong basis. The model learns ln(1 + net),
        // so one such row weighed ~1,650 against a normal label's single digits.
        if (!plausible8617(s.raw.mcap, netPct, chainWitness)) {
            implausible8617.incrementAndGet()
            try { PipelineHealthCollector.labelInc("EDGE_LABEL_IMPLAUSIBLE_8617") } catch (_: Throwable) {}
            return
        }
        val y = managedTarget8722(s.raw.lane, netPct, s.raw.costPct)
        try { Mind8746.learn8746(s.mint, s.atMs, y) } catch (_: Throwable) {}   // V5.0.8746 — the mind learns the same label
        synchronized(this) {
            // A pending observation from the old model can teach its verified outcome, but its prediction
            // was made under a contaminated model. It must never earn walk-forward authority in this epoch.
            if (s.modelEpoch != MODEL_EPOCH_8642) oldPredictionsIgnored8642.incrementAndGet()
            else if (s.pred.isFinite()) oos.getOrPut(recordKey(s.raw.lane, s.modelKey)) { Oos() }.add(s.pred, y)
            else skippedCold.incrementAndGet()
            val x = if (s.modelEpoch == MODEL_EPOCH_8642 && s.x.size == DIM) s.x else vectorOf(s.raw)
            global.learn(x, y)
            ownModel8722(s.raw.lane)?.learn(x, y)
        }
        val k = learned.incrementAndGet()
        appendDataset(s, netPct, witnessed = chainWitness)   // retain independently provable multi-X outcomes
        try { PocketLedger8610.learn8610(s, netPct) } catch (_: Throwable) {}   // V5.0.8610 — the learned pocket ledger
        try { ManagedBands8719.learn8719(s.raw.lane, s.raw.liq, s.raw.mcap, netPct, s.raw.costPct) } catch (_: Throwable) {}   // V5.0.8719 — the floors' record
        try { PayingCell8723.learn8723(s.mint, s.raw.lane, s.raw.ageMin, s.raw.liq, netPct, s.atMs) } catch (_: Throwable) {}   // V5.0.8723
        if (k % SAVE_EVERY == 0L) saveAsync()
        try { PipelineHealthCollector.labelInc("EDGE_MODEL_LEARNED_8537") } catch (_: Throwable) {}
    }

    /** The current verdict for [ts] in [laneRaw]: the prediction and whether this lane's model has earned authority. */
    fun verdict(ts: TokenState, laneRaw: String, costPct: Double, nowMs: Long = System.currentTimeMillis()): Verdict? = try {
        ensureLoaded()
        val raw = rawOf(ts, laneRaw, costPct, nowMs)
        val x = vectorOf(raw)
        // V5.0.8747 — the voters (tree, paying cell, LLM cache, the mind) are read BEFORE the model lock; only the ridge
        // read inside predict8737 takes it (predictLocked). 8746 held the lock across the paying-cell feature read.
        val (p, key) = predict8737(raw, x, ts.mint, ts)
        synchronized(this) {
            val o = oos[recordKey(raw.lane, key)]
            val trained = when (key) {
                TreeRanker8737.KEY_8737 -> TreeRanker8737.trainedRows8737()
                Mind8746.KEY_8746 -> TreeRanker8737.trainedRows8737()
                raw.lane -> byLane[raw.lane]?.count ?: 0L
                else -> global.count
            }
            Verdict(p, p.isFinite() && o != null && authority(o.selN, o.selMean, o.selSe, o.allMean), key, trained,
                o?.selN ?: 0, o?.selMean ?: Double.NaN, o?.selSe ?: Double.NaN, o?.allMean ?: Double.NaN,
                refuses8715 = p.isFinite() && o != null && refusalAuthority8715(o.refN, o.refMean, o.refSe, o.keepMean))
        }
    } catch (_: Throwable) { null }

    /** Pure: the decision a verdict supports — NEGATIVE refuses, POSITIVE is proven evidence, NONE decides nothing. */
    enum class Call { NEGATIVE, POSITIVE, NONE }
    fun callOf(v: Verdict?): Call = when {
        v == null || !v.predPct.isFinite() -> Call.NONE
        v.predPct < 0.0 && (v.authority || v.refuses8715) -> Call.NEGATIVE
        v.authority && v.predPct >= MIN_EDGE_PCT -> Call.POSITIVE
        else -> Call.NONE
    }

    // ── persistence and dataset ──────────────────────────────────────────────────────────────────────────────────────

    private fun dir(): File? = try { com.lifecyclebot.AATEApp.appContextOrNull()?.filesDir } catch (_: Throwable) { null }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val f = File(dir() ?: return, MODEL_FILE)
                val current = f.exists() && DataInputStream(f.inputStream().buffered()).use { readLocked(it) }
                if (!current) {
                    // No opaque pre-8617 sufficient statistics or OOS verdicts survive. Original file remains
                    // untouched until a clean replay is saved; the raw labelled dataset is never deleted.
                    clearModel8642()
                    val (seen, used) = replayDataset8642(File(f.parentFile, DATASET_FILE))
                    replayStatus8642 = "rebuilt:$used/$seen"
                    try { PipelineHealthCollector.labelInc("EDGE_MODEL_REPLAY_VERIFIED_8642") } catch (_: Throwable) {}
                }
                loaded = true
                if (!current) saveNow()
            } catch (_: Throwable) {
                clearModel8642(); loaded = true; replayStatus8642 = "failed-cold"
                try { PipelineHealthCollector.labelInc("EDGE_MODEL_LOAD_FAILED_8537") } catch (_: Throwable) {}
            }
        }
    }

    private fun clearModel8642() {
        global.clear8642(); byLane.clear(); oos.clear()
    }

    /** Only real, fillable, plausible labels at the lane's intended horizon can seed a prediction model. */
    internal fun replayAccept8642(row: org.json.JSONObject): Boolean {
        if (!row.optBoolean("fill", false)) return false
        // The older export has no captured venue-price provenance. Its non-pump Solana mints
        // could have received a pump.fun curve mark from PumpPortal, RPC, Helius or cache,
        // regardless of their later discovery label. Reject those old observations from ALL
        // learned authorities; keep their raw JSONL untouched. Other chains are unaffected.
        val mint8660 = row.optString("mint")
        if (mint8660.isBlank()) return false
        if (unscopedSolana8661(mint8660, row.optInt("venueScopeEpoch", 0))) return false
        val lane = row.optString("lane")
        val horizon = row.optLong("horizonMs", 0L)
        if (lane.isBlank() || horizon <= 0L || horizon != PocketLedger8610.targetHorizon(lane)) return false
        val net = row.optDouble("net", Double.NaN)
        return plausible8617(row.optDouble("mcap", Double.NaN), net, row.optBoolean("witness", false))
    }

    /** One streaming replay, not readLines(): the 24 MB dataset cannot be copied wholesale onto Android's heap. */
    private fun replayDataset8642(file: File): Pair<Int, Int> {
        if (!file.isFile) return 0 to 0
        var seen = 0; var used = 0
        file.forEachLine { line ->
            if (seen++ >= DATASET_MAX_LINES) return@forEachLine
            try {
                val o = org.json.JSONObject(line)
                if (!replayAccept8642(o)) return@forEachLine
                fun d(k: String) = o.optDouble(k, Double.NaN)
                val r = Raw(o.optString("lane").trim().uppercase(), o.optString("src"), d("liq"), d("mcap"), d("age"),
                    d("runup"), d("pxPeak"), d("dd"), d("bp"), d("chg5m"), d("chg1h"), d("volAccel"), d("top"),
                    o.optString("phase"), d("v3"), d("cost"), d("holderVel"), d("txVel"), d("curve"), d("turnover"), o.optInt("flags"),
                    d("newBuyers15s8666"), o.optBoolean("sustainedDemand"), o.optBoolean("marketReclaim"))
                val x = vectorOf(r)
                val y = managedTarget8722(r.lane, d("net"), r.costPct)
                global.learn(x, y, solveNow = false)
                ownModel8722(r.lane)?.learn(x, y, solveNow = false)
                used++
            } catch (_: Throwable) { /* corrupted dataset row is excluded, never used as an outcome */ }
        }
        if (used > 0) { global.finishReplay8642(); byLane.values.forEach { it.finishReplay8642() } }
        // Historical predictions cannot supply a valid OOS bar; newly issued epochs earn authority afresh.
        oos.clear()
        return seen to used
    }

    private fun writeRidge(o: DataOutputStream, r: Ridge) {
        r.fold8722()
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

    private fun readLocked(i: DataInputStream): Boolean {
        if (i.readInt() != DIM || i.readInt() != MODEL_EPOCH_8642) return false
        readRidge(i, global)
        repeat(i.readInt()) { val l = i.readUTF(); readRidge(i, byLane.getOrPut(l) { Ridge(DIM) }) }
        repeat(i.readInt()) {
            val l = i.readUTF(); val o = Oos()
            if (l.startsWith("net|")) oos[l] = o
            o.selN = i.readInt(); o.selSum = i.readDouble(); o.selSq = i.readDouble(); o.selWin = i.readInt()
            o.allN = i.readInt(); o.allSum = i.readDouble(); o.negN = i.readInt(); o.negSum = i.readDouble()
        }
        // V5.0.8715 — the refusal records follow the 8675 body; a file written before them simply ends here.
        try {
            if (i.readInt() == REFUSAL_BLOCK_8715) repeat(i.readInt()) {
                val l = i.readUTF(); val refN = i.readInt(); val refSum = i.readDouble(); val refSq = i.readDouble()
                val keepN = i.readInt(); val keepSum = i.readDouble()
                oos[l]?.let { o -> o.refN = refN; o.refSum = refSum; o.refSq = refSq; o.keepN = keepN; o.keepSum = keepSum }
            }
        } catch (_: java.io.EOFException) {}
        return true
    }

    private fun saveAsync() { io.execute { saveNow() } }

    /** Persist the model and its out-of-sample record (also called when the service stops). */
    @Synchronized fun saveNow() {
        ensureLoaded()
        try {
            val d = dir() ?: return
            val tmp = File(d, "$MODEL_FILE.tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { o ->
                synchronized(this) {
                    o.writeInt(DIM)
                    o.writeInt(MODEL_EPOCH_8642)
                    writeRidge(o, global)
                    o.writeInt(byLane.size); for ((l, r) in byLane) { o.writeUTF(l); writeRidge(o, r) }
                    o.writeInt(oos.size); for ((l, s) in oos) {
                        o.writeUTF(l); o.writeInt(s.selN); o.writeDouble(s.selSum); o.writeDouble(s.selSq); o.writeInt(s.selWin)
                        o.writeInt(s.allN); o.writeDouble(s.allSum); o.writeInt(s.negN); o.writeDouble(s.negSum)
                    }
                    o.writeInt(REFUSAL_BLOCK_8715); o.writeInt(oos.size); for ((l, s) in oos) {
                        o.writeUTF(l); o.writeInt(s.refN); o.writeDouble(s.refSum); o.writeDouble(s.refSq); o.writeInt(s.keepN); o.writeDouble(s.keepSum)
                    }
                }
            }
            if (!tmp.renameTo(File(d, MODEL_FILE))) tmp.delete()
        } catch (_: Throwable) { try { PipelineHealthCollector.labelInc("EDGE_MODEL_SAVE_FAILED_8537") } catch (_: Throwable) {} }
    }

    /** Pure: one dataset row (JSON) — the raw observation, the frozen prediction and the outcome. */
    fun datasetLine(s: Snap, net: Double, filled: Boolean = true, witnessed: Boolean = false, labelObservedAtMs: Long? = null): String {
        fun n(v: Double) = if (v.isFinite()) "%.6g".format(java.util.Locale.US, v) else "null"
        val r = s.raw
        // The device may still be delivering decisions frozen on a PRE-fix APK. An 8661
        // serializer must never retroactively stamp those old observations as venue-safe.
        val venueScope8661 = if (s.modelEpoch >= VENUE_SCOPE_EPOCH_8660) VENUE_SCOPE_EPOCH_8660 else 0
        return "{\"t\":${s.atMs},\"horizonMs\":${s.horizonMs},\"mint\":\"${s.mint.take(44)}\",\"lane\":\"${r.lane}\",\"src\":\"${r.source.replace("\"", "").take(60)}\"," +
            "\"liq\":${n(r.liq)},\"mcap\":${n(r.mcap)},\"age\":${n(r.ageMin)},\"runup\":${n(r.runup)},\"pxPeak\":${n(r.pxPeak)}," +
            "\"dd\":${n(r.dd)},\"bp\":${n(r.bp)},\"chg5m\":${n(r.chg5m)},\"chg1h\":${n(r.chg1h)},\"volAccel\":${n(r.volAccel)}," +
            "\"top\":${n(r.top)},\"holderVel\":${n(r.holderVel)},\"txVel\":${n(r.txVel)},\"curve\":${n(r.curve)},\"turnover\":${n(r.turnover)},\"newBuyers15s8666\":${n(r.newBuyers15s8666)},\"sustainedDemand\":${r.sustainedDemand},\"marketReclaim\":${r.marketReclaim}," +
            "\"flags\":${r.flags},\"phase\":\"${r.phase}\",\"v3\":${n(r.v3)},\"cost\":${n(r.costPct)},\"admitted\":${s.admitted}," +
            "\"pred\":${n(s.pred)},\"model\":\"${s.modelKey}\",\"modelEpoch\":${s.modelEpoch},\"venueScopeEpoch\":$venueScope8661,\"net\":${n(net)},\"fill\":$filled,\"witness\":$witnessed,\"labelObservedAtMs\":${labelObservedAtMs ?: "null"}}"
    }

    /**
     * V5.0.8546 — a 5-minute outcome whose entry could not be rebased to a fillable mark (fast meme coins: no reachable mark
     * inside the rebase window) is written to the dataset flagged "fill":false — research coverage of every meme lane — and
     * never trains the model (its entry price is not one a buy could have got). 8537 exports held TREASURY / CASHGEN / crypto
     * only: not one SHITCOIN, MOONSHOT or EXPRESS decision.
     */
    fun recordUnfilled8546(s: Snap, netPct: Double, chainWitness: Boolean = false) {
        if (!netPct.isFinite()) return
        unfilled8546.incrementAndGet()
        appendDataset(s, netPct, filled = false, witnessed = chainWitness)
    }
    private val unfilled8546 = AtomicLong(0)
    private val lastTrimMilestone8642 = AtomicLong(0)

    private fun appendDataset(s: Snap, net: Double, filled: Boolean = true, witnessed: Boolean = false) {
        val line = try { datasetLine(s, net, filled, witnessed, System.currentTimeMillis()) } catch (_: Throwable) { return }
        io.execute {
            try {
                val f = File(dir() ?: return@execute, DATASET_FILE)
                f.appendText(line + "\n")
                // 8642: readLines() materialised 24+ MB of JSON and its objects during a heap-starved trading loop.
                // Count/check once per 5k fresh labels; stream the retained tail on the model's single IO worker.
                val milestone = learned.get() / 5_000L
                if (milestone > lastTrimMilestone8642.get() && lastTrimMilestone8642.getAndSet(milestone) < milestone) {
                    trimDataset8642(f)
                }
            } catch (_: Throwable) {}
        }
    }

    private fun trimDataset8642(file: File) {
        var lines = 0
        file.forEachLine { lines++ }
        if (lines <= DATASET_MAX_LINES) return
        val skip = lines - 45_000
        val tmp = File(file.parentFile, "$DATASET_FILE.trim")
        var idx = 0
        tmp.bufferedWriter().use { out ->
            file.forEachLine { line -> if (idx++ >= skip) out.appendLine(line) }
        }
        if (!tmp.renameTo(file)) tmp.delete() // preserve the original if replacement fails
    }

    /** The dataset file (for the report's Save to Downloads), or null. */
    fun datasetFile(): File? {
        val d = dir() ?: return null
        return try { File(d, DATASET_FILE).takeIf { it.exists() && it.length() > 0 } } catch (_: Throwable) { null }
    }

    /** Brief: per lane, the out-of-sample record and authority. */
    fun statusLine(): String = synchronized(this.also { ensureLoaded() }) {
        val lanes = byLane.keys.toSortedSet()
        val rows = lanes.joinToString(" · ") { l ->
            val o = oos[recordKey(l, activeModel(l))]; val r = byLane[l]
            val auth = o != null && authority(o.selN, o.selMean, o.selSe, o.allMean)
            "$l[n${r?.count ?: 0} oosPicks=n${o?.selN ?: 0}/${pct(o?.selMean)}±${pct(o?.selSe)} all=${pct(o?.allMean)} avoid=${pct(o?.negMean)} refuse8715=n${o?.refN ?: 0}/${pct(o?.refMean)}±${pct(o?.refSe)}vsKept${pct(o?.keepMean)} ${if (auth) "AUTHORITY" else if (o != null && refusalAuthority8715(o.refN, o.refMean, o.refSe, o.keepMean)) "REFUSES" else "learning"}]"
        }
        "edge8537[trained=${global.count} learned=${learned.get()} cold=${skippedCold.get()} implausible8617=${implausible8617.get()} oldPredIgnored8642=${oldPredictionsIgnored8642.get()} replay8642=$replayStatus8642 minEdge=+${MIN_EDGE_PCT}% authBar=n$AUTH_MIN_N,mean-se>0,>lane] ${rows.ifBlank { "no labels yet" }}"
    }

    private fun pct(v: Double?) = if (v != null && v.isFinite()) "%+.1f%%".format(v) else "-"

    internal fun resetForTest8537() = synchronized(this) {
        byLane.clear(); oos.clear(); learned.set(0); skippedCold.set(0); loaded = true
        global.clear8642(); oldPredictionsIgnored8642.set(0); replayStatus8642 = "test"
    }

    /** Test seam: learn one labelled raw observation directly (no file IO). */
    internal fun learnRawForTest8537(r: Raw, net: Double) = synchronized(this) {
        val x = vectorOf(r)
        val (p, key) = predictLocked(x, r.lane)
        val y = managedTarget8722(r.lane, net, r.costPct)
        if (p.isFinite()) oos.getOrPut(recordKey(r.lane, key)) { Oos() }.add(p, y)
        global.learn(x, y)
        ownModel8722(r.lane)?.learn(x, y)
        learned.incrementAndGet()
        key
    }

    internal fun predictRawForTest8537(r: Raw): Pair<Double, String> = predictLocked(vectorOf(r), r.lane)

    internal fun oosForTest8537(lane: String): Oos? = synchronized(this) { oos[recordKey(lane(lane), activeModel(lane(lane)))] }
}

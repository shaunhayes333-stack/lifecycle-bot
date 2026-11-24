package com.lifecyclebot.engine.truth

import com.lifecyclebot.AATEApp
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.truth.LaunchReplay8744.Ladder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8861 §THE_RUNNER_MODEL — owner: "what can we do to exponentially boost edge and its forward predictive stack?" ...
 * "cool do that"; and "watch them cross a 5k floor price and make a decision earlier"; "runners caught and held".
 *
 * What the phone had at the floor cross was a handful of rules ("3 buys in the last minute"). What separates the launches
 * that run is how the tape is ACCELERATING into the cross: in the owner's 7,617 first looks the top fifth by volume
 * acceleration ran +50 % 13.0 % of the time against a 4.5 % base, run-up from the low 10.8 %, transaction velocity 8.9 %,
 * new-buyer velocity 8.7 % — columns the edge model saw on 4 to 9 % of decisions, because they were read from a tape that
 * only follows watched coins.
 *
 * This model reads them from the launch tape itself (LaunchHour8628: every witnessed pump.fun print), at the one moment
 * FloorWatch8856 already decides on: the live print that takes the curve to $5,000.
 *  - ROWS: for every stored launch that crossed the floor, [features8861] of its live prints up to the cross, and what
 *    buying it returned — entered where the curve stood after the bot's latency, the live DOCTRINE ladder, a
 *    [STOP_PCT_8861] % stop, cost — with [doubled8861]: did it double before that stop. The exit is graded on the WHOLE
 *    tape the phone holds for the coin ([HOLD_MS_8861], LaunchHour8628.tail8861), not cut at the hour. A row is written once
 *    the hold window is complete and is KEPT (runner_rows_8861.bin): the training set outlives the tape's 48 hours and grows
 *    for as long as the bot runs.
 *  - MODEL: TreeRanker8737's boosted trees fitted to "doubled before the stop". The share it buys ([QUANTILES_8861]) is
 *    chosen the way SmartMoney8756 chooses its variant: fitted on the oldest 60 % of rows, chosen on the next 20 %, read on
 *    the newest 20 %.
 *  - PROOF: forward only. Every row that arrives is first scored by the model that was fitted WITHOUT it (and only when the
 *    launch is newer than every launch that model saw); the picks' returns are added to a record that is kept. The model
 *    is ARMED on that record alone (this install's own rows only — V5.0.8862: a row another install graded teaches the model
 *    and never enters the record), by the launch replay's own forward bar: [LaunchReplay8744.PROVEN_MIN_N_8748]+ picks, mean
 *    [LaunchReplay8744.FORWARD_Z_8856] standard errors above zero, still positive without its best pick. All crossers are
 *    recorded beside the picks, so the lift is read against buying every cross.
 *  - LIVE: the same prints reach [onPrint8861] as they are stored (same price, time, size, side, wallet), so the features at
 *    the live cross are the features a row will have; each run compares them ([parity]). Unarmed, a pick is counted in
 *    shadow. Armed, the pick is market evidence for the coin FloorWatch8856 has just seated (CandidateAdmission8136 market
 *    cell): hard safety, sellability, balance and the economics doors still decide.
 * Nothing is hand-set but the floor (the owner's) and the stop (the replay's watched and best-measured rules').
 */
object RunnerModel8861 {
    /** The stop of the rule the replay watches and of the best rule it has measured (cap $10k, 3 buys, half at 2x, stop 20 %). */
    const val STOP_PCT_8861 = 20.0
    /** The hold is graded over the followed clock's next horizon after the hour. */
    internal const val HOLD_MS_8861 = TrajectoryGrades8851.FOUR_HOURS
    private const val HOUR = 3_600_000L
    /** A launch with more live prints than this before its cross is not scored (live and replay cap the same way). */
    internal const val MAX_PRINTS_8861 = 400
    private const val MAX_ROWS = 12_000
    private const val MAX_LIVE_MINTS = 1_500
    private const val EVIDENCE_TTL_MS = 3L * 60_000L
    private const val FILE = "runner_rows_8861.bin"
    private const val MAGIC = 0x52_4D_38_36   // "RM86"
    /** V5.0.8862 — the same file with each row's origin (this install's, or another's). */
    private const val MAGIC_8862 = 0x52_4D_38_37
    private const val UNNAMED = Int.MIN_VALUE
    /** Two trees' worth of leaves on each side of a split: below this TreeRanker8737 cannot grow a depth-2 tree. */
    internal val MIN_TRAIN_ROWS_8861 = (4 * TreeRanker8737.MIN_LEAF_W).toInt()
    private const val MIN_N_VALID = 30
    /** The share of crosses the model may pass on: it buys the scores above this quantile of its fitted scores. */
    internal val QUANTILES_8861 = listOf(0.5, 0.7, 0.8, 0.9)

    val FEATURES_8861: List<String> = listOf(
        "secsSinceFirstPrint", "prints", "printsPerMin", "buyTxShare", "netSolShare", "buySol", "avgBuySol", "uniqueBuyers",
        "largestBuyerShare", "top3BuyerShare", "repeatBuyerShare", "earlyBuyShare3s", "runupFromLow", "fromPeak",
        "ret10s", "ret30s", "ret60s", "buys10s", "buys30s", "buys60s", "txAccel", "volAccel", "newBuyers60s", "sellSolShare60s")

    /** [mine]: graded by this install from its own tape; false for a row another install graded (V5.0.8862, HiveRunnerRows8862). */
    class Row(val mint: String, val bornMs: Long, val x: DoubleArray, val doubled: Boolean, val net: Double, val pastHour: Boolean,
              val mine: Boolean = true)

    internal class Record { var n = 0; var sum = 0.0; var sq = 0.0; var best = Double.NEGATIVE_INFINITY; var wins = 0; var twoX = 0
        fun add(v: Double) { n++; sum += v; sq += v * v; if (v > best) best = v; if (v > 0.0) wins++; if (v >= 100.0) twoX++ }
        val mean: Double get() = if (n > 0) sum / n else Double.NaN
        val se: Double get() = if (n > 1) kotlin.math.sqrt(((sq - n * mean * mean) / (n - 1)).coerceAtLeast(0.0) / n) else Double.NaN
        val exBest: Double get() = if (n > 1) (sum - best) / (n - 1) else Double.NaN
    }

    // ── pure ──

    /** Pure: the first of the first [n] prints at or above [floorPriceSol]; -1 when none. */
    fun cross8861(p: DoubleArray, n: Int, floorPriceSol: Double): Int {
        for (i in 0 until minOf(n, p.size)) if (p[i] >= floorPriceSol) return i
        return -1
    }

    /**
     * Pure: the tape as it stands at the cross — the first [n] prints (live, in time order; print n-1 is the cross): times
     * [t] (ms, any origin), prices [p], SOL sizes [sol], sides [buy] and buyer keys [wallet] ([UNNAMED] = the feed named none).
     * A reading that cannot be made (no print that far back, no buy to divide by) is NaN, never 0.
     */
    fun features8861(t: LongArray, p: DoubleArray, sol: DoubleArray, buy: BooleanArray, wallet: IntArray, n: Int): DoubleArray {
        val c = n - 1
        val tc = t[c]; val t0 = t[0]
        var buys = 0; var buySol = 0.0; var sellSol = 0.0; var early = 0.0
        var low = Double.POSITIVE_INFINITY; var peak = 0.0
        var b10 = 0; var b30 = 0; var b60 = 0; var bPrev30 = 0; var v30 = 0.0; var vPrev30 = 0.0; var sell60 = 0.0; var all60 = 0.0
        val bySol = HashMap<Int, Double>(); val byCount = HashMap<Int, Int>(); val firstBuy = HashMap<Int, Long>()
        for (i in 0..c) {
            val px = p[i]; val v = if (sol[i].isFinite() && sol[i] > 0.0) sol[i] else 0.0
            if (px < low) low = px
            if (px > peak) peak = px
            val back = tc - t[i]
            if (back < 60_000L) all60 += v
            if (buy[i]) {
                buys++; buySol += v
                if (t[i] - t0 < SmartMoney8756.SNIPER_MS_8860) early += v
                if (back < 10_000L) b10++
                if (back < 30_000L) { b30++; v30 += v } else if (back < 60_000L) { bPrev30++; vPrev30 += v }
                if (back < 60_000L) b60++
                val w = wallet[i]
                if (w != UNNAMED) { bySol[w] = (bySol[w] ?: 0.0) + v; byCount[w] = (byCount[w] ?: 0) + 1; if (w !in firstBuy) firstBuy[w] = t[i] }
            } else {
                sellSol += v
                if (back < 60_000L) sell60 += v
            }
        }
        fun ret(ms: Long): Double {
            var j = -1
            for (i in 0..c) if (t[i] <= tc - ms) j = i else break
            return if (j >= 0 && p[j] > 0.0) p[c] / p[j] - 1.0 else Double.NaN
        }
        fun ratio(a: Double, b: Double): Double = if (b > 0.0) a / b else Double.NaN
        val secs = (tc - t0) / 1000.0
        val shares = bySol.values.sortedDescending()
        return doubleArrayOf(
            secs, n.toDouble(), n / maxOf(secs / 60.0, 1.0 / 60.0), buys.toDouble() / n, ratio(buySol - sellSol, buySol + sellSol), buySol,
            ratio(buySol, buys.toDouble()), bySol.size.toDouble(),
            ratio(shares.firstOrNull() ?: Double.NaN, buySol), ratio(if (shares.isEmpty()) Double.NaN else shares.take(3).sum(), buySol),
            ratio(byCount.values.count { it >= 2 }.toDouble(), byCount.size.toDouble()), ratio(early, buySol),
            if (low > 0.0 && low.isFinite()) p[c] / low - 1.0 else Double.NaN, if (peak > 0.0) p[c] / peak - 1.0 else Double.NaN,
            ret(10_000L), ret(30_000L), ret(60_000L), b10.toDouble(), b30.toDouble(), b60.toDouble(),
            ratio(b30.toDouble(), bPrev30.toDouble()), ratio(v30, vPrev30),
            firstBuy.values.count { tc - it < 60_000L }.toDouble(), ratio(sell60, all60))
    }

    /**
     * Pure: from the entry print [e], did the price reach twice the entry before the stop [stopPx], and before the DOCTRINE
     * time stop ([timeStopMs] without ever making +20 %)? The same order LaunchReplay8744.exitNet8754 applies before its first rung.
     */
    fun doubled8861(t: LongArray, p: DoubleArray, e: Int, stopPx: Double, timeStopMs: Long): Boolean {
        val entry = p[e]; var peak = entry
        for (k in e + 1 until t.size) {
            val px = p[k]
            if (px > peak) peak = px
            if (px >= entry * 2.0 * (1.0 - 1e-9)) return true
            if (px <= stopPx) return false
            if (t[k] - t[e] >= timeStopMs && peak < entry * 1.2) return false
        }
        return false
    }

    /** Pure: the [q]-quantile of [scores] (the lowest score the model still buys). */
    fun quantile8861(scores: DoubleArray, q: Double): Double {
        if (scores.isEmpty()) return Double.NaN
        val s = scores.sortedArray()
        return s[((s.size - 1) * q).toInt().coerceIn(0, s.size - 1)]
    }

    /** Pure: is the forward record [r] proof? The launch replay's forward bar (LaunchReplay8744.forwardProves8856). */
    internal fun proves8861(r: Record): Boolean = r.n >= LaunchReplay8744.PROVEN_MIN_N_8748 && r.mean.isFinite() && r.se.isFinite() &&
        r.mean - LaunchReplay8744.FORWARD_Z_8856 * r.se > 0.0 && r.exBest > 0.0

    private fun fit(rows: List<Row>, atMs: Long): TreeRanker8737.Model =
        TreeRanker8737.train8737(rows.map { it.x }, rows.map { if (it.doubled) 1.0 else 0.0 }, rows.map { 1.0 }, atMs)

    /**
     * Pure: the quantile to buy above, chosen on [valid] from a model fitted on [train] (time-ordered): the one whose picks'
     * mean less its SE is highest with at least [MIN_N_VALID] picks; null when none has that many.
     */
    internal fun choose8861(train: List<Row>, valid: List<Row>, atMs: Long): Double? {
        if (train.size < MIN_TRAIN_ROWS_8861 || valid.isEmpty()) return null
        val m = fit(train, atMs)
        val fitted = DoubleArray(train.size) { m.predict(train[it].x) }
        var best: Double? = null; var bestLower = Double.NEGATIVE_INFINITY
        for (q in QUANTILES_8861) {
            val theta = quantile8861(fitted, q)
            val rec = Record(); for (r in valid) if (m.predict(r.x) >= theta) rec.add(r.net)
            if (rec.n < MIN_N_VALID || !rec.se.isFinite()) continue
            val lower = rec.mean - rec.se
            if (lower > bestLower) { bestLower = lower; best = q }
        }
        return best
    }

    // ── the replay's run (LaunchReplay8744's worker) ──

    private class Candidate(val r: LaunchHour8628.Launch8756, val crossT: Long, val crossP: Double, val x: DoubleArray)

    private val rows = ArrayList<Row>()
    private val known = HashSet<String>()
    private val picks = Record(); private val everyCross = Record()
    private var loaded = false
    private val candidates = ArrayList<Candidate>()
    @Volatile private var model: TreeRanker8737.Model? = null
    @Volatile private var theta = Double.NaN
    @Volatile private var quantile = Double.NaN
    @Volatile private var armed = false
    @Volatile private var status = "not-run"
    private var lateRows = 0L; private var spikeCross = 0L; private var overflow = 0L; private var pastHourRows = 0L
    private var fromHive = 0L
    private val hiveInbox = ArrayList<Row>()

    /** HiveRunnerRows8862: this install's own rows born after [afterBornMs], oldest first, at most [limit]. */
    @Synchronized
    internal fun ownRowsAfter8862(afterBornMs: Long, limit: Int): List<Row> {
        load()
        return rows.filter { it.mine && it.bornMs > afterBornMs }.sortedBy { it.bornMs }.take(limit)
    }

    /** HiveRunnerRows8862: rows another install graded, for launches this install has no row for. Returns how many were taken. */
    @Synchronized
    internal fun offerHive8862(rs: List<Row>): Int {
        load()
        var n = 0
        for (r in rs) {
            if (r.mine || r.x.size != FEATURES_8861.size || !r.net.isFinite() || r.mint in known || hiveInbox.any { it.mint == r.mint }) continue
            if (hiveInbox.size >= MAX_ROWS) break
            hiveInbox.add(r); n++
        }
        return n
    }
    private var paritySame = 0L; private var parityDiffer = 0L; private var parityOtherCross = 0L

    /** The live prints of [r] (first hour, time order) up to and including its floor cross as compact arrays, or null when it never crossed. */
    private fun livePrefix(r: LaunchHour8628.Launch8756, floorPriceSol: Double): Candidate? {
        val idx = IntArray(minOf(r.t.size, MAX_PRINTS_8861 + 1)); var n = 0; var cross = -1
        for (i in r.t.indices) {
            if (!r.live[i] || !(r.p[i] > 0.0)) continue
            if (n >= idx.size) { overflow++; return null }
            idx[n++] = i
            if (r.p[i] >= floorPriceSol) { cross = i; break }
        }
        if (cross < 0 || n > MAX_PRINTS_8861) return null
        val t = LongArray(n) { r.t[idx[it]] }; val p = DoubleArray(n) { r.p[idx[it]] }; val v = DoubleArray(n) { r.sol[idx[it]] }
        val b = BooleanArray(n) { r.buy[idx[it]] }; val w = IntArray(n) { r.wallet[idx[it]].let { id -> if (id < 0) UNNAMED else id } }
        return Candidate(r, r.t[cross], r.p[cross], features8861(t, p, v, b, w, n))
    }

    /** LaunchReplay8744's read: one stored launch. Kept for this run when it crossed the floor, its hold window is over and it has no row yet. */
    @Synchronized
    internal fun onLaunch8861(r: LaunchHour8628.Launch8756, solUsd: Double, nowMs: Long) {
        load()
        if (!(solUsd > 0.0) || r.bornMs <= 0L || r.bornMs + HOLD_MS_8861 > nowMs || r.mint in known) return
        livePrefix(r, FloorWatch8856.FLOOR_CAP_USD_8856 / (LaunchReplay8744.SUPPLY * solUsd))?.let { candidates.add(it) }
    }

    /** Pure-ish: the row of one candidate on its whole stored tape ([tail]: the prints after the first hour), or null when its cross was a dropped spike. */
    internal fun rowOf8861(mint: String, bornMs: Long, x: DoubleArray, t: LongArray, p: DoubleArray, buy: BooleanArray, live: BooleanArray,
                           tail: Triple<LongArray, DoubleArray, BooleanArray>?, crossT: Long, crossP: Double, delayMs: Long, costPct: Double): Row? {
        val n1 = t.size; val n2 = tail?.first?.size ?: 0
        val path = LaunchReplay8744.clean8748(
            LongArray(n1 + n2) { if (it < n1) t[it] else tail!!.first[it - n1] }, DoubleArray(n1 + n2) { if (it < n1) p[it] else tail!!.second[it - n1] },
            BooleanArray(n1 + n2) { if (it < n1) buy[it] else tail!!.third[it - n1] }, BooleanArray(n1 + n2) { it < n1 && live[it] })
        var trig = -1
        for (k in path.t.indices) if (path.t[k] == crossT && path.p[k] == crossP) { trig = k; break }
        if (trig < 0) return null
        val e = LaunchReplay8744.entryIndex8754(path, trig, delayMs)
        val entry = path.p[e]
        if (!(entry > 0.0)) return null
        val stopPx = entry * (1.0 - STOP_PCT_8861 / 100.0)
        val net = LaunchReplay8744.exitNet8754(path, e, Ladder.DOCTRINE, 2.0, stopPx, LaunchReplay8744.TIME_STOP_MS_8828, costPct) ?: return null
        return Row(mint, bornMs, x, doubled8861(path.t, path.p, e, stopPx, LaunchReplay8744.TIME_STOP_MS_8828), net, n2 > 0)
    }

    /** After the read: write this run's rows, score them forward with the model that never saw them, refit, and read the proof. */
    @Synchronized
    internal fun evaluate8861(costPct: Double, needDelayMs: Long, nowMs: Long) {
        load()
        val delay = maxOf(LaunchReplay8744.ARM_MIN_DELAY_MS_8748, needDelayMs)
        val fresh = ArrayList<Row>()
        for (c in candidates.sortedBy { it.r.bornMs }) {
            val tail = try { LaunchHour8628.tail8861(c.r.mint, c.r.bornMs, HOLD_MS_8861) } catch (_: Throwable) { null }
            val row = rowOf8861(c.r.mint, c.r.bornMs, c.x, c.r.t, c.r.p, c.r.buy, c.r.live, tail, c.crossT, c.crossP, delay, costPct)
            if (row == null) { spikeCross++; continue }
            fresh.add(row)
            liveSeen.remove(c.r.mint)?.let { seen ->   // the features the live path computed at this coin's cross
                if (seen.first != c.r.bornMs + c.crossT) parityOtherCross++
                else if (seen.second.indices.all { i -> same(seen.second[i], c.x[i]) }) paritySame++ else parityDiffer++
            }
        }
        candidates.clear()
        // V5.0.8862 — rows other installs graded join here: they teach the model, they never enter the forward record
        for (r in hiveInbox) if (r.mint !in known && fresh.none { it.mint == r.mint }) fresh.add(r)
        hiveInbox.clear()
        fresh.sortBy { it.bornMs }
        // after a restart the model is refitted from the kept rows first: it is the model the last run left, and saw none of [fresh]
        if (model == null) refit(nowMs)
        // forward: a fresh row newer than everything the fitted model saw is a pick the model made without it
        val m0 = model; val th0 = theta
        val newestSeen = rows.maxOfOrNull { it.bornMs } ?: 0L
        for (r in fresh) {
            if (!r.mine) fromHive++
            else if (m0 != null && th0.isFinite() && r.bornMs > newestSeen) { everyCross.add(r.net); if (m0.predict(r.x) >= th0) picks.add(r.net) }
            else if (m0 != null) lateRows++
            if (r.pastHour) pastHourRows++
            rows.add(r); known.add(r.mint)
        }
        if (rows.size > MAX_ROWS) { rows.sortBy { it.bornMs }; val drop = rows.size - MAX_ROWS; repeat(drop) { known.remove(rows[it].mint) }; rows.subList(0, drop).clear() }
        refit(nowMs)
        armed = proves8861(picks)
        if (fresh.isNotEmpty()) save()
        status = "ran rows=${rows.size} new=${fresh.size} doubledBeforeStop=${rows.count { it.doubled }} delay=${delay / 1000}s"
    }

    private fun same(a: Double, b: Double): Boolean = (a.isNaN() && b.isNaN()) || a == b || kotlin.math.abs(a - b) <= 1e-9 * maxOf(1.0, kotlin.math.abs(a), kotlin.math.abs(b))

    /** Fit the model the live path scores with (every row), and choose the share it buys on the time-ordered split. */
    private fun refit(nowMs: Long) {
        if (rows.size < MIN_TRAIN_ROWS_8861) { model = null; theta = Double.NaN; quantile = Double.NaN; return }
        rows.sortBy { it.bornMs }
        val a = rows.size * 6 / 10; val b = rows.size * 8 / 10
        val q = try { choose8861(rows.subList(0, a), rows.subList(a, b), nowMs) } catch (_: Throwable) { null }
        // the newest fifth, read with a model that saw only the rows before it
        testLine = if (q == null || b < MIN_TRAIN_ROWS_8861) "-" else try {
            val mt = fit(rows.subList(0, b), nowMs)
            val th = quantile8861(DoubleArray(b) { mt.predict(rows[it].x) }, q)
            val pick = Record(); val all = Record()
            for (r in rows.subList(b, rows.size)) { all.add(r.net); if (mt.predict(r.x) >= th) pick.add(r.net) }
            "picks ${line(pick)} of all ${line(all)}"
        } catch (_: Throwable) { "-" }
        if (q == null) { model = null; theta = Double.NaN; quantile = Double.NaN; return }
        val m = fit(rows, nowMs)
        model = m; quantile = q; theta = quantile8861(DoubleArray(rows.size) { m.predict(rows[it].x) }, q)
    }
    @Volatile private var testLine = "-"

    private fun line(r: Record): String = if (r.n == 0) "n0" else
        "n${r.n}/${"%+.1f".format(java.util.Locale.US, r.mean)}±${if (r.se.isFinite()) "%.1f".format(java.util.Locale.US, r.se) else "-"}%/win${r.wins * 100 / r.n}%/2x+${r.twoX * 100 / r.n}%"

    // ── live ──

    private class Buf(val bornMs: Long) {
        var t = LongArray(32); var p = DoubleArray(32); var v = DoubleArray(32); var b = BooleanArray(32); var w = IntArray(32)
        var n = 0; var done = false; var lastSeen = 0L
        fun add(tm: Long, px: Double, sol: Double, isBuy: Boolean, key: Int) {
            if (n == t.size) { val m = n * 2; t = t.copyOf(m); p = p.copyOf(m); v = v.copyOf(m); b = b.copyOf(m); w = w.copyOf(m) }
            t[n] = tm; p[n] = px; v[n] = sol; b[n] = isBuy; w[n] = key; n++
        }
    }
    private class Fire(val atMs: Long)

    private val bufs = ConcurrentHashMap<String, Buf>()
    private val fires = ConcurrentHashMap<String, Fire>()
    /** mint -> (wall-clock time of the live cross print, the features computed there), until the replay writes that coin's row. */
    private val liveSeen = ConcurrentHashMap<String, Pair<Long, DoubleArray>>()
    private val liveCrosses = AtomicLong(); private val shadowPicks = AtomicLong(); private val armedPicks = AtomicLong()
    private val liveOverflow = AtomicLong(); private val served = AtomicLong()

    /**
     * TradeTape8100.launchEvidence8750, beside LaunchHour8628.trade: the print exactly as the tape stores it ([priceSol] is
     * the stored price). Live prints of a pump.fun launch in its first hour are kept until its floor cross, where the model scores it.
     */
    fun onPrint8861(mint: String, eventMs: Long, recvMs: Long, priceSol: Double, sol: Double, isBuy: Boolean, wallet: String) {
        try {
            if (!(priceSol > 0.0) || !priceSol.isFinite() || recvMs - eventMs !in -5_000L..LaunchReplay8744.LIVE_LAG_MS_8748) return
            val s: Buf = bufs[mint] ?: run {
                if (!com.lifecyclebot.network.PumpCurveKeys7269.isPumpMint8658(mint)) return
                val born = CanonicalTokenBirthTime7440.peekBirthMs8418(mint, eventMs) ?: return
                if (eventMs - born !in 0L..HOUR) return
                if (bufs.size >= MAX_LIVE_MINTS) bufs.entries.removeIf { recvMs - it.value.bornMs > HOUR || recvMs - it.value.lastSeen > 10L * 60_000L }
                if (bufs.size >= MAX_LIVE_MINTS) return
                bufs.computeIfAbsent(mint) { Buf(born) }
            }
            val solUsd = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
            val scored = synchronized(s) {
                s.lastSeen = recvMs
                if (s.done || eventMs - s.bornMs !in 0L..HOUR) return
                if (s.n >= MAX_PRINTS_8861) { s.done = true; liveOverflow.incrementAndGet(); return }
                s.add(eventMs - s.bornMs, priceSol, sol, isBuy, if (wallet.isBlank()) UNNAMED else wallet.hashCode().let { if (it == UNNAMED) 0 else it })
                if (!(solUsd > 0.0) || priceSol * LaunchReplay8744.SUPPLY * solUsd < FloorWatch8856.FLOOR_CAP_USD_8856) return
                s.done = true
                features8861(s.t, s.p, s.v, s.b, s.w, s.n)
            }
            liveCrosses.incrementAndGet()
            if (liveSeen.size < MAX_LIVE_MINTS * 2) liveSeen[mint] = eventMs to scored
            val m = model ?: return
            val th = theta; if (!th.isFinite()) return
            val score = m.predict(scored)
            if (score < th) return
            if (!armed) { shadowPicks.incrementAndGet(); try { PipelineHealthCollector.labelInc("RUNNER_MODEL_SHADOW_PICK_8861") } catch (_: Throwable) {}; return }
            armedPicks.incrementAndGet()
            if (fires.size > 2_000) fires.entries.removeIf { recvMs - it.value.atMs > EVIDENCE_TTL_MS }
            fires[mint] = Fire(recvMs)
            try { PipelineHealthCollector.labelInc("RUNNER_MODEL_ARMED_PICK_8861") } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    /** V5.0.8987 — the evidence kind an armed pick carries (LaneAuthority8577 reads it). */
    const val KIND_8861 = "RUNNER_MODEL_8861"

    /** CandidateAdmission8136: the armed pick on [mint] as market-cell evidence — the forward record without its best pick — or null. */
    fun evidence8861(mint: String, nowMs: Long): CandidateAdmission8136.Evidence8136? {
        fires[mint]?.takeIf { nowMs - it.atMs in 0L..EVIDENCE_TTL_MS } ?: return null
        val rec = synchronized(this) { if (!armed) return null; Triple(picks.n, picks.exBest, picks.se) to picks.twoX.toDouble() / maxOf(1, picks.n) }
        served.incrementAndGet()
        return CandidateAdmission8136.Evidence8136(KIND_8861, "floor cross, score in the top ${((1.0 - quantile) * 100).toInt()}%",
            rec.first.first, rec.first.second, rec.first.third, runnerRate = rec.second, netOfCost = true)
    }

    // ── persistence: the rows and the forward record are kept; the model is refitted from the rows ──

    private fun file(): File? = AATEApp.appContextOrNull()?.filesDir?.let { File(it, FILE) }

    internal fun write8861(out: DataOutputStream, rs: List<Row>, pick: Record, all: Record) {
        out.writeInt(MAGIC_8862); out.writeInt(FEATURES_8861.size)
        for (r in listOf(pick, all)) { out.writeInt(r.n); out.writeDouble(r.sum); out.writeDouble(r.sq); out.writeDouble(if (r.n > 0) r.best else 0.0); out.writeInt(r.wins); out.writeInt(r.twoX) }
        out.writeInt(rs.size)
        for (r in rs) { out.writeUTF(r.mint); out.writeLong(r.bornMs); for (v in r.x) out.writeDouble(v); out.writeBoolean(r.doubled); out.writeDouble(r.net); out.writeBoolean(r.pastHour)
            out.writeBoolean(r.mine) }
    }

    /** The inverse of [write8861]; null for a file that is not one, or one written with a different feature list (it is then started again). */
    internal fun read8861(inp: DataInputStream): Triple<List<Row>, Record, Record>? = try {
        val magic = inp.readInt()   // V5.0.8862 — a file 8861 wrote carries no origin: every row in it is this install's
        if ((magic != MAGIC && magic != MAGIC_8862) || inp.readInt() != FEATURES_8861.size) null else {
            val recs = List(2) { Record().also { r -> r.n = inp.readInt(); r.sum = inp.readDouble(); r.sq = inp.readDouble()
                r.best = inp.readDouble().let { b -> if (r.n > 0) b else Double.NEGATIVE_INFINITY }; r.wins = inp.readInt(); r.twoX = inp.readInt() } }
            val n = inp.readInt()
            if (n !in 0..MAX_ROWS * 2 || recs.any { it.n < 0 || !it.sum.isFinite() || !it.sq.isFinite() }) null else {
                val out = ArrayList<Row>(n)
                repeat(n) {
                    val mint = inp.readUTF(); val born = inp.readLong(); val x = DoubleArray(FEATURES_8861.size) { inp.readDouble() }
                    val doubled = inp.readBoolean(); val net = inp.readDouble(); val past = inp.readBoolean()
                    val mine = if (magic == MAGIC_8862) inp.readBoolean() else true
                    if (mint.isNotBlank() && net.isFinite()) out.add(Row(mint, born, x, doubled, net, past, mine))
                }
                Triple(out, recs[0], recs[1])
            }
        }
    } catch (_: Throwable) { null }

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            val f = file()?.takeIf { it.isFile } ?: return
            val got = DataInputStream(f.inputStream().buffered()).use { read8861(it) } ?: return
            rows.clear(); rows.addAll(got.first); known.clear(); got.first.forEach { known.add(it.mint) }
            copy(got.second, picks); copy(got.third, everyCross)
            pastHourRows = rows.count { it.pastHour }.toLong(); fromHive = rows.count { !it.mine }.toLong()
        } catch (_: Throwable) {}
    }

    private fun copy(from: Record, to: Record) { to.n = from.n; to.sum = from.sum; to.sq = from.sq; to.best = from.best; to.wins = from.wins; to.twoX = from.twoX }

    private fun save() {
        try {
            val f = file() ?: return
            val tmp = File(f.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { write8861(it, rows, picks, everyCross) }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (_: Throwable) {}
    }

    /**
     * V5.0.8865 — pure: what a model that is not fitted waits for at [rowsN] rows. 5.0.8862 printed "waiting rows=252/160":
     * the 160 is the bar for the TRAINING rows, the oldest six tenths ([refit]), which were 151. Past that bar the share to
     * buy is chosen on the next fifth, where a share needs [MIN_N_VALID] picks ([choose8861]).
     */
    internal fun waitingFor8865(rowsN: Int): String {
        val train = rowsN * 6 / 10
        return if (train < MIN_TRAIN_ROWS_8861) "trainRows=$train/$MIN_TRAIN_ROWS_8861 (the oldest 60% of rows=$rowsN)"
        else "picks<$MIN_N_VALID at every share on the validation fifth (${rowsN * 8 / 10 - train} of rows=$rowsN)"
    }

    fun statusLine8861(): String {
        val (pl, al, rowsN) = synchronized(this) { Triple(line(picks), line(everyCross), rows.size) }
        return "runnerModel8861[$status ${if (armed) "ARMED" else "SHADOW"} model=${if (model != null) "fitted buys top ${((1.0 - quantile) * 100).toInt()}%" else "waiting ${waitingFor8865(rowsN)}"} " +
            "newestFifth{$testLine} forward{picks $pl of every cross $al} rowsGradedPastTheHour=$pastHourRows rowsFromOtherInstalls8862=$fromHive " +
            "live{crosses=${liveCrosses.get()} shadowPicks=${shadowPicks.get()} armedPicks=${armedPicks.get()} overflow=${liveOverflow.get()} evidenceServed=${served.get()}} " +
            "parity{same=$paritySame differ=$parityDiffer otherCrossPrint=$parityOtherCross} skipped{crossWasSpike=$spikeCross overflow=$overflow lateRows=$lateRows} " +
            "rule=tape features at the \$${FloorWatch8856.FLOOR_CAP_USD_8856.toInt()} cross -> doubled before a ${STOP_PCT_8861.toInt()}% stop; armed on the forward record only " +
            "(n>=${LaunchReplay8744.PROVEN_MIN_N_8748}, mean-${LaunchReplay8744.FORWARD_Z_8856.toInt()}SE>0, positive without its best)]"
    }

    @Synchronized
    internal fun resetForTest8861() {
        rows.clear(); known.clear(); candidates.clear(); copy(Record(), picks); copy(Record(), everyCross); loaded = true
        model = null; theta = Double.NaN; quantile = Double.NaN; armed = false; status = "not-run"; testLine = "-"
        bufs.clear(); fires.clear(); liveSeen.clear()
        lateRows = 0; spikeCross = 0; overflow = 0; pastHourRows = 0; paritySame = 0; parityDiffer = 0; parityOtherCross = 0
        fromHive = 0; hiveInbox.clear()
        listOf(liveCrosses, shadowPicks, armedPicks, liveOverflow, served).forEach { it.set(0) }
    }

    /** Test seam: the rows and the forward record as the next run will find them. */
    @Synchronized
    internal fun seedForTest8861(rs: List<Row>, pick: List<Double>, all: List<Double>, nowMs: Long) {
        rows.clear(); rows.addAll(rs); known.clear(); rs.forEach { known.add(it.mint) }
        copy(Record(), picks); copy(Record(), everyCross); pick.forEach(picks::add); all.forEach(everyCross::add)
        refit(nowMs); armed = proves8861(picks)
    }
}

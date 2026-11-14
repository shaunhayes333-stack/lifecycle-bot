package com.lifecyclebot.engine.lab

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.truth.BaseFamily8754
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.EdgeModel8537
import com.lifecyclebot.engine.truth.LaunchReplay8744
import com.lifecyclebot.engine.truth.TreeRanker8737
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8755 §THE_LAB_GRADES_HONESTLY_AND_THINKS_WIDER — owner: "build it all now. but you need to go wider, more intelligent,
 * better thinking better strategies" / "it already has the llm lab. successful strategies get built and are meant to
 * automatically implement".
 *
 * The LLM Lab was the right home and the wrong judge. 8753 phone: strategies=405 promoted=17 paperTrades=2006
 * paperPnl=+205.863 SOL — while every live lane lost. Lab strategies were scalars (a composite-score floor, TP, SL, hold) traded
 * on paper at marks with no cost, and promoted on 8 trades, WR >= 33 % and any positive PnL. The composite score does not rank
 * outcomes (AUC 0.51 on the 8753 dataset); a 33 % win rate on memes is the base rate. Promotion proved nothing.
 *
 * Now every Solana-meme Lab strategy is a typed hypothesis in one of five families, graded on evidence it could not have seen:
 *   SCORE   the legacy strategy: composite score >= its floor            graded on HELD-OUT labelled decisions
 *   CELL    a box over the bot's own features (+ lane / source)          graded on HELD-OUT labelled decisions
 *   RUNNER  the runner head's top 10 / 5 / 2 % ("which hot coin runs")    graded on HELD-OUT labelled decisions
 *   LAUNCH  a launch-cap trigger + ladder + latency                       graded by the witnessed launch replay
 *   BASE    a base / breakout trigger + ladder + latency                  graded by the witnessed launch replay
 * The labelled decisions (EdgeModel8537's dataset: each decision's 15-minute executable net) are split by time: the oldest
 * [DISCOVERY_SHARE] discover (the miner searches there, the runner head trains there, the LLM is briefed from there); the
 * newest are the held-out grade. A record is net of cost, ranked by its mean WITHOUT its three best trades.
 *
 * Wider: a miner searches feature boxes (single features and pairs, per lane) for cells the discovery data says pay and keeps
 * the ones the held-out data confirms; the runner head (boosted trees on "doubled in 15 minutes") ranks hot coins; the LLM is
 * briefed with the measured evidence (lanes, sources, stages, the best and worst cells, the replays) and must answer with a
 * family and parameters this lab can grade — not a guess at a score floor.
 *
 * Automatic implementation, under one bar ([passes8755]: n >= 100 and the ex-top-3 mean clear of [Z_HELDOUT] SEs for held-out
 * families, of one SE for replayed ones with the bot's DOCTRINE ladder at a latency it has): a promoted CELL / RUNNER / SCORE
 * strategy is admission evidence for every coin it matches (CandidateAdmission8136, the paying-cell slot: it answers the
 * generic data waits, never hard safety, rug structure, sellability or the quoted economics); a promoted LAUNCH / BASE rule is
 * what LaunchSniper8748 runs live when the replays have not proven one of their own. A promoted strategy that stops passing
 * is demoted; one that fails on [ARCHIVE_N]+ is archived. Discovery-source runner rates also feed scanner intake priority.
 */
object StrategyLab8755 {
    enum class Family { SCORE, CELL, RUNNER, LAUNCH, BASE }
    data class Cond(val feature: String, val lo: Double, val hi: Double)
    data class Spec(val family: Family, val lane: String = "ANY", val source: String = "ANY", val conds: List<Cond> = emptyList(),
                    val runnerPct: Int = 95, val launch: LaunchReplay8744.Rule? = null, val base: BaseFamily8754.Rule? = null,
                    val scoreMin: Int = 0)
    data class Record(val n: Int, val mean: Double, val median: Double, val exTop3: Double, val se: Double, val tenX: Double)
    class Row(val t: Long, val lane: String, val src: String, val net: Double, val f: DoubleArray)

    val FEATURES = listOf("liq", "mcap", "age", "runup", "dd", "bp", "chg5m", "chg1h", "top", "txVel", "turnover", "curve",
        "pxPeak", "cost", "volAccel", "v3", "newBuyers15s8666")
    private const val DISCOVERY_SHARE = 0.7
    private const val MIN_N = 100
    private const val Z_HELDOUT = 2.0
    private const val ARCHIVE_N = 300
    private const val RUN_EVERY_MS = 60L * 60_000L
    private const val MAX_LINES = 25_000
    private const val WINDOW_MS = 15L * 60_000L
    private const val RUNNER_NET = 100.0
    private const val MAX_MINED = 8
    private const val MINE_MIN_N = 150

    // ── pure pieces ────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Pure: one labelled decision of the exported dataset, or null (unlabelled, synthetic lanes, implausible). */
    fun row8755(line: String): Row? {
        val o = try { JSONObject(line) } catch (_: Throwable) { return null }
        val net = o.optDouble("net", Double.NaN)
        val lane = o.optString("lane", "").uppercase()
        if (!net.isFinite() || net < -100.0 || lane.isBlank() || lane.startsWith("LPSTATE") || lane.startsWith("SETUP") ||
            lane.startsWith("PLANWAIT") || lane.startsWith("ORIGIN") || lane == "ALL_LAUNCHES") return null
        fun d(k: String) = if (o.isNull(k)) Double.NaN else o.optDouble(k, Double.NaN)
        if (!EdgeModel8537.plausible8617(d("mcap"), net, o.optBoolean("witness", false))) return null
        val src = o.optString("src", "").split(',').firstOrNull()?.trim()?.uppercase().orEmpty().ifBlank { "UNKNOWN" }
        return Row(o.optLong("t", 0L), lane, src, net.coerceAtMost(1_000.0), DoubleArray(FEATURES.size) { d(FEATURES[it]) })
    }

    /** Pure: the strategy's live feature vector for a decision (the dataset's field order). */
    private fun vectorOf8755(r: EdgeModel8537.Raw): DoubleArray = doubleArrayOf(r.liq, r.mcap, r.ageMin, r.runup, r.dd, r.bp, r.chg5m,
        r.chg1h, r.top, r.txVel, r.turnover, r.curve, r.pxPeak, r.costPct, r.volAccel, r.v3, r.newBuyers15s8666)

    /** Pure: does a decision in [lane] from [src] with features [f] fall in [spec]'s cell (CELL / SCORE families)? */
    fun matches8755(spec: Spec, lane: String, src: String, f: DoubleArray): Boolean {
        if (spec.lane != "ANY" && spec.lane != lane.uppercase()) return false
        if (spec.source != "ANY" && spec.source != src.uppercase()) return false
        if (spec.family == Family.SCORE) { val v3 = f[FEATURES.indexOf("v3")]; return v3.isFinite() && v3 >= spec.scoreMin }
        for (c in spec.conds) {
            val i = FEATURES.indexOf(c.feature); if (i < 0) return false
            val v = f[i]; if (!v.isFinite() || v < c.lo || v > c.hi) return false
        }
        return spec.conds.isNotEmpty()
    }

    /** Pure: n, mean, median, the mean and SE without the three best, the 10x share. */
    fun record8755(v: List<Double>): Record? {
        if (v.size < 5) return null
        val s = v.sorted(); val ex = s.dropLast(3)
        val m3 = ex.average()
        val se3 = kotlin.math.sqrt(ex.sumOf { (it - m3) * (it - m3) } / (ex.size - 1)) / kotlin.math.sqrt(ex.size.toDouble())
        val med = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
        return Record(s.size, s.average(), med, m3, se3, s.count { it >= 900.0 }.toDouble() / s.size)
    }

    /** Pure: does [rec] of a [family] strategy pass the live bar? (LAUNCH / BASE also need the ladder and latency: [replayOk].) */
    fun passes8755(rec: Record?, family: Family, replayOk: Boolean = true): Boolean {
        rec ?: return false
        val z = if (family == Family.LAUNCH || family == Family.BASE) 1.0 else Z_HELDOUT
        return rec.n >= MIN_N && rec.exTop3.isFinite() && rec.se.isFinite() && rec.exTop3 - z * rec.se > 0.0 &&
            (replayOk || (family != Family.LAUNCH && family != Family.BASE))
    }

    // ── spec <-> JSON (what the Lab stores and what the LLM answers) ──────────────────────────────────────────────────

    fun specJson8755(s: Spec): String {
        val o = JSONObject().put("family", s.family.name).put("lane", s.lane).put("source", s.source)
        when (s.family) {
            Family.SCORE -> o.put("scoreMin", s.scoreMin)
            Family.CELL -> o.put("conds", JSONArray().apply { s.conds.forEach { put(JSONObject().put("f", it.feature).put("lo", it.lo).put("hi", it.hi)) } })
            Family.RUNNER -> o.put("pct", s.runnerPct)
            Family.LAUNCH -> s.launch?.let { r -> o.put("capUsd", r.capUsd).put("minBuys", r.minBuys).put("takeX", r.takeX).put("stopPct", r.stopPct)
                .put("ladder", r.ladder.name).put("delayMs", r.delayMs) }
            Family.BASE -> s.base?.let { r -> with(r.trigger) { o.put("peakCapUsd", minPeakCapUsd).put("pullback", pullback).put("baseWinMs", baseWinMs)
                .put("maxRange", maxRange).put("minBuys", minBuys).put("mode", mode.name) }; o.put("ladder", r.ladder.name).put("delayMs", r.delayMs) }
        }
        return o.toString()
    }

    /** Pure: a spec from stored / LLM JSON, its parameters held to the ranges the graders cover; null when unusable. */
    fun specOf8755(o: JSONObject): Spec? {
      return try {
        val fam = Family.valueOf(o.optString("family", "").trim().uppercase())
        val lane = o.optString("lane", "ANY").trim().uppercase().ifBlank { "ANY" }
        val source = o.optString("source", "ANY").trim().uppercase().ifBlank { "ANY" }
        fun ladder() = try { LaunchReplay8744.Ladder.valueOf(o.optString("ladder", "DOCTRINE").trim().uppercase()) } catch (_: Throwable) { LaunchReplay8744.Ladder.DOCTRINE }
        val delay = o.optLong("delayMs", 10_000L).coerceIn(2_000L, 60_000L)
        when (fam) {
            Family.SCORE -> Spec(fam, lane, source, scoreMin = o.optInt("scoreMin", 60).coerceIn(0, 100))
            Family.CELL -> {
                val a = o.optJSONArray("conds") ?: return null
                val conds = (0 until minOf(a.length(), 3)).mapNotNull { i ->
                    val c = a.optJSONObject(i) ?: return@mapNotNull null
                    val f = c.optString("f", ""); val lo = c.optDouble("lo", Double.NEGATIVE_INFINITY); val hi = c.optDouble("hi", Double.POSITIVE_INFINITY)
                    if (f !in FEATURES || lo.isNaN() || hi.isNaN() || lo > hi) null else Cond(f, lo, hi)
                }
                if (conds.isEmpty()) null else Spec(fam, lane, source, conds)
            }
            Family.RUNNER -> Spec(fam, lane, source, runnerPct = o.optInt("pct", 95).let { p -> listOf(90, 95, 98).minByOrNull { kotlin.math.abs(it - p) } ?: 95 })
            Family.LAUNCH -> Spec(fam, launch = LaunchReplay8744.Rule(o.optDouble("capUsd", 10_000.0).coerceIn(3_000.0, 60_000.0),
                o.optInt("minBuys", 8).coerceIn(1, 60), o.optDouble("takeX", 3.0).coerceIn(1.5, 10.0), o.optDouble("stopPct", 30.0).coerceIn(5.0, 60.0),
                ladder(), delay))
            Family.BASE -> Spec(fam, base = BaseFamily8754.Rule(BaseFamily8754.Trigger(o.optDouble("peakCapUsd", 20_000.0).coerceIn(5_000.0, 200_000.0),
                o.optDouble("pullback", 0.5).coerceIn(0.2, 0.9), o.optLong("baseWinMs", 120_000L).coerceIn(30_000L, 900_000L),
                o.optDouble("maxRange", 1.4).coerceIn(1.05, 3.0), o.optInt("minBuys", 3).coerceIn(0, 60),
                try { BaseFamily8754.Mode.valueOf(o.optString("mode", "IN_BASE").trim().uppercase()) } catch (_: Throwable) { BaseFamily8754.Mode.IN_BASE }),
                ladder(), delay))
        }
      } catch (_: Throwable) { null }
    }

    fun specOf8755(s: LabStrategy): Spec? =
        if (s.spec8755.isBlank()) Spec(Family.SCORE, scoreMin = s.entryScoreMin) else try { specOf8755(JSONObject(s.spec8755)) } catch (_: Throwable) { null }

    /** Pure: the miner's discovery accumulator — n, sum, sum of squares and the three best, without keeping the values. */
    class Acc {
        var n = 0; var sum = 0.0; var sq = 0.0
        private val top = DoubleArray(3) { Double.NEGATIVE_INFINITY }
        fun add(v: Double) {
            n++; sum += v; sq += v * v
            if (v > top[2]) { top[2] = v; top.sortDescending() }
        }
        /** The ex-top-3 mean less its SE (the discovery score), or -inf when too thin. */
        fun score(): Double {
            if (n < MINE_MIN_N) return Double.NEGATIVE_INFINITY
            val m = n - 3; val s = sum - top.sum(); val q = sq - top.sumOf { it * it }
            val mean = s / m; val v = ((q / m) - mean * mean).coerceAtLeast(0.0) * m / (m - 1)
            return mean - kotlin.math.sqrt(v / m)
        }
    }

    // ── state ──────────────────────────────────────────────────────────────────────────────────────────────────────────

    private class Head(val model: TreeRanker8737.Model, val thresholds: Map<Int, Double>, val records: Map<Int, Record?>, val baseRate: Double)
    @Volatile private var head: Head? = null
    @Volatile private var liveCells: List<Pair<LabStrategy, Spec>> = emptyList()
    @Volatile private var liveRunner: Pair<LabStrategy, Spec>? = null
    @Volatile private var liveLaunch: LaunchReplay8744.Result? = null
    @Volatile private var liveBase: BaseFamily8754.Graded? = null
    @Volatile private var sourceFactor: Map<String, Double> = emptyMap()
    @Volatile private var brief = ""
    @Volatile private var status = "not-run"
    @Volatile private var lastRunMs = 0L
    private val running = AtomicBoolean(false)
    private val served = AtomicLong(); private val minedTested = AtomicLong()
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "strategy-lab-8755").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    // ── the cycle ──────────────────────────────────────────────────────────────────────────────────────────────────────

    /** LlmLabEngine.tick: run the grading cycle in the background when due. Never blocks. */
    fun maybeRun8755(nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - lastRunMs < RUN_EVERY_MS || !running.compareAndSet(false, true)) return
        lastRunMs = nowMs
        exec.execute {
            try { run(nowMs) } catch (t: Throwable) { status = "failed:${t.javaClass.simpleName}" } finally { running.set(false) }
        }
    }

    private fun run(nowMs: Long) {
        val file = EdgeModel8537.datasetFile8755()
        if (file == null || !file.exists()) { status = "no dataset"; return }
        // V5.0.8756 — each line is parsed as it is read and only the parsed row is kept (the tail of raw JSON strings held
        // tens of MB at once on the phone's heap)
        val tail = java.util.ArrayDeque<Row>(MAX_LINES)
        file.useLines { seq -> seq.forEach { line -> row8755(line)?.let { if (tail.size == MAX_LINES) tail.removeFirst(); tail.addLast(it) } } }
        val seen = HashSet<String>()
        val rows = tail.sortedBy { it.t }.filter { seen.add("${it.lane}|${it.src}|${it.t / WINDOW_MS}|${it.f.contentHashCode()}") }
        tail.clear()
        if (rows.size < 2_000) { status = "waiting rows=${rows.size}/2000"; return }
        val cut = (rows.size * DISCOVERY_SHARE).toInt()
        val disc = rows.subList(0, cut); val held = rows.subList(cut, rows.size)

        trainHead(disc, held, nowMs)
        val mined = mine(disc, held)
        sourceFactor = sourceFactors(rows)
        brief = briefOf(disc, mined)
        registerMined(mined)
        gradeLab(held)
        status = "ran rows=${rows.size} discovery=${disc.size} heldOut=${held.size} mined=${mined.count { it.third?.let { r -> passes8755(r, Family.CELL) } == true }}/${mined.size}"
        try { PipelineHealthCollector.labelInc("STRATEGY_LAB_RAN_8755") } catch (_: Throwable) {}
    }

    private fun laneIndex(lane: String): Double = listOf("QUALITY", "BLUECHIP", "SHITCOIN", "EXPRESS", "MOONSHOT", "PROJECT_SNIPER",
        "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN", "CYCLIC", "CORE").indexOf(lane).toDouble()

    private fun headX(lane: String, f: DoubleArray): DoubleArray = f + doubleArrayOf(laneIndex(lane))

    private fun trainHead(disc: List<Row>, held: List<Row>, nowMs: Long) {
        val ys = disc.map { if (it.net >= RUNNER_NET) 1.0 else 0.0 }
        if (ys.sum() < 20.0) { head = null; return }
        val model = TreeRanker8737.train8737(disc.map { headX(it.lane, it.f) }, ys, List(disc.size) { 1.0 }, nowMs, trees = 60)
        val scored = held.map { it to model.predict(headX(it.lane, it.f)) }.filter { it.second.isFinite() }
        val sorted = scored.map { it.second }.sorted()
        if (sorted.isEmpty()) { head = null; return }
        val thr = listOf(90, 95, 98).associateWith { p -> sorted[((sorted.size - 1) * p / 100.0).toInt()] }
        val recs = thr.mapValues { (_, t) -> record8755(scored.filter { it.second >= t }.map { it.first.net }) }
        head = Head(model, thr, recs, ys.average())
    }

    /** The miner: feature boxes (quintile ranges, single features then pairs) per lane scope; returns (spec, discovery score, held-out record). */
    private fun mine(disc: List<Row>, held: List<Row>): List<Triple<Spec, Double, Record?>> {
        val lanes = listOf("ANY") + disc.groupingBy { it.lane }.eachCount().filter { it.value >= 1_500 }.keys
        val edges = FEATURES.indices.map { i ->
            val v = disc.map { it.f[i] }.filter { it.isFinite() }.sorted()
            if (v.size < 500) null else DoubleArray(6) { q -> v[((v.size - 1) * q / 5.0).toInt()] }
        }
        val singles = ArrayList<Pair<Spec, Double>>()
        for (lane in lanes) {
            val scope = if (lane == "ANY") disc else disc.filter { it.lane == lane }
            for (i in FEATURES.indices) {
                val e = edges[i] ?: continue
                for (a in 0 until 5) for (b in a + 1..5) {
                    if (a == 0 && b == 5) continue
                    val lo = if (a == 0) Double.NEGATIVE_INFINITY else e[a]; val hi = if (b == 5) Double.POSITIVE_INFINITY else e[b]
                    val acc = Acc(); for (r in scope) { val v = r.f[i]; if (v.isFinite() && v >= lo && v <= hi) acc.add(r.net) }
                    minedTested.incrementAndGet()
                    val sc = acc.score(); if (sc > 0.0) singles.add(Spec(Family.CELL, lane, conds = listOf(Cond(FEATURES[i], lo, hi))) to sc)
                }
            }
        }
        val best = singles.sortedByDescending { it.second }.take(40)
        val pairs = ArrayList<Pair<Spec, Double>>()
        for (x in best.indices) for (y in x + 1 until best.size) {
            val s1 = best[x].first; val s2 = best[y].first
            if (s1.lane != s2.lane || s1.conds[0].feature == s2.conds[0].feature) continue
            val spec = Spec(Family.CELL, s1.lane, conds = s1.conds + s2.conds)
            val acc = Acc(); for (r in disc) if (matches8755(spec, r.lane, r.src, r.f)) acc.add(r.net)
            minedTested.incrementAndGet()
            val sc = acc.score(); if (sc > 0.0) pairs.add(spec to sc)
        }
        return (best + pairs).sortedByDescending { it.second }.take(30).map { (spec, sc) ->
            Triple(spec, sc, record8755(held.filter { matches8755(spec, it.lane, it.src, it.f) }.map { it.net }))
        }
    }

    private fun sourceFactors(rows: List<Row>): Map<String, Double> {
        val all = rows.size.toDouble(); val allRun = rows.count { it.net >= RUNNER_NET }.toDouble()
        val base = (allRun + 1.0) / (all + 50.0)
        return rows.groupBy { it.src }.filter { it.value.size >= 200 }.mapValues { (_, v) ->
            val rr = (v.count { it.net >= RUNNER_NET } + 1.0) / (v.size + 50.0)
            kotlin.math.sqrt(rr / base).coerceIn(0.5, 2.0)
        }
    }

    private fun registerMined(mined: List<Triple<Spec, Double, Record?>>) {
        val existing = LlmLabStore.allStrategies().map { it.spec8755 }.toSet()
        val minedNow = LlmLabStore.allStrategies().count { it.name.startsWith("MINED") && it.status != LabStrategyStatus.ARCHIVED }
        var room = (MAX_MINED - minedNow).coerceAtLeast(0)
        for ((spec, _, rec) in mined) {
            if (room <= 0) break
            if (!passes8755(rec, Family.CELL)) continue
            val js = specJson8755(spec)
            if (js in existing) continue
            val name = ("MINED " + spec.lane + " " + spec.conds.joinToString("&") { "${it.feature}${fmtRange(it)}" }).take(32)
            LlmLabStore.addStrategy(LabStrategy(id = LlmLabStore.newStrategyId(), name = name,
                rationale = "Mined cell (8755): discovery-split box confirmed on held-out decisions".take(140), asset = LabAssetClass.MEME,
                entryScoreMin = 0, entryRegime = "ANY", takeProfitPct = 100.0, stopLossPct = -30.0, maxHoldMins = 60, sizingSol = 0.05,
                status = LabStrategyStatus.ACTIVE, spec8755 = js))
            room--
            try { PipelineHealthCollector.labelInc("STRATEGY_LAB_MINED_REGISTERED_8755") } catch (_: Throwable) {}
        }
    }

    private fun fmtRange(c: Cond): String = when {
        c.lo == Double.NEGATIVE_INFINITY -> "<=${"%.3g".format(c.hi)}"
        c.hi == Double.POSITIVE_INFINITY -> ">=${"%.3g".format(c.lo)}"
        else -> "${"%.3g".format(c.lo)}..${"%.3g".format(c.hi)}"
    }

    /** Grade every Solana-meme Lab strategy and set its status by the bar; publish what the live engine implements. */
    private fun gradeLab(held: List<Row>) {
        val paths = LaunchReplay8744.paths8755; val solUsd = LaunchReplay8744.solUsd8755; val cost = LaunchReplay8744.cost8755
        val need = try { com.lifecyclebot.engine.truth.LaunchSniper8748.requiredDelayMs8749() } catch (_: Throwable) { 10_000L }
        val cells = ArrayList<Pair<LabStrategy, Spec>>(); var runner: Pair<LabStrategy, Spec>? = null
        var launch: Pair<LabStrategy, Record>? = null; var base: Pair<LabStrategy, Record>? = null
        for (s0 in LlmLabStore.allStrategies()) {
            if (s0.status == LabStrategyStatus.ARCHIVED || (s0.asset != LabAssetClass.MEME && s0.asset != LabAssetClass.ANY)) continue
            val spec = specOf8755(s0) ?: continue
            val rec: Record? = when (spec.family) {
                Family.SCORE, Family.CELL -> record8755(held.filter { matches8755(spec, it.lane, it.src, it.f) }.map { it.net })
                Family.RUNNER -> head?.records?.get(spec.runnerPct)
                Family.LAUNCH -> spec.launch?.let { r -> if (paths.isEmpty() || !(solUsd > 0.0)) null
                    else record8755(paths.mapNotNull { LaunchReplay8744.simulateRule8755(it, r, solUsd, cost) }) }
                Family.BASE -> spec.base?.let { r -> if (paths.isEmpty() || !(solUsd > 0.0)) null
                    else record8755(paths.mapNotNull { BaseFamily8754.simulateRule8755(it, r, solUsd, cost) }) }
            }
            val replayOk = when (spec.family) {
                Family.LAUNCH -> spec.launch?.let { it.ladder == LaunchReplay8744.Ladder.DOCTRINE && it.delayMs >= maxOf(10_000L, need) } == true
                Family.BASE -> spec.base?.let { it.ladder == LaunchReplay8744.Ladder.DOCTRINE && it.delayMs >= maxOf(10_000L, need) } == true
                else -> true
            }
            val pass = passes8755(rec, spec.family, replayOk)
            val now = System.currentTimeMillis()
            val graded = s0.copy(honestN8755 = rec?.n ?: 0, honestMean8755 = rec?.mean ?: Double.NaN, honestMedian8755 = rec?.median ?: Double.NaN,
                honestExTop3_8755 = rec?.exTop3 ?: Double.NaN, honestSe8755 = rec?.se ?: Double.NaN, honestTenX8755 = rec?.tenX ?: Double.NaN,
                honestAt8755 = now)
            val next = when {
                pass -> LabStrategyStatus.PROMOTED
                (rec?.n ?: 0) >= ARCHIVE_N && s0.spec8755.isNotBlank() -> LabStrategyStatus.ARCHIVED
                s0.status == LabStrategyStatus.PROMOTED && (rec?.n ?: 0) >= MIN_N -> LabStrategyStatus.ACTIVE
                else -> s0.status
            }
            if (next != s0.status) try { PipelineHealthCollector.labelInc("STRATEGY_LAB_${next.name}_8755") } catch (_: Throwable) {}
            LlmLabStore.updateStrategy(graded.copy(status = next))
            if (!pass || rec == null) continue
            when (spec.family) {
                Family.SCORE, Family.CELL -> cells.add(graded to spec)
                Family.RUNNER -> if (runner == null || (graded.honestExTop3_8755 - graded.honestSe8755) > (runner.first.honestExTop3_8755 - runner.first.honestSe8755)) runner = graded to spec
                Family.LAUNCH -> if (launch == null || rec.exTop3 - rec.se > launch.second.exTop3 - launch.second.se) launch = graded to rec
                Family.BASE -> if (base == null || rec.exTop3 - rec.se > base.second.exTop3 - base.second.se) base = graded to rec
            }
        }
        liveCells = cells; liveRunner = runner
        liveLaunch = launch?.let { (s, r) -> specOf8755(s)?.launch?.let { rule -> LaunchReplay8744.Result(rule, r.n, r.mean, r.se, Double.NaN, Double.NaN, r.tenX, r.median, r.exTop3, r.se) } }
        liveBase = base?.let { (s, r) -> specOf8755(s)?.base?.let { rule -> BaseFamily8754.Graded(rule, r.n, r.mean, r.median, r.exTop3, r.se, Double.NaN, Double.NaN, r.tenX, Double.NaN) } }
    }

    private fun briefOf(disc: List<Row>, mined: List<Triple<Spec, Double, Record?>>): String {
        fun line(name: String, v: List<Row>): String {
            val r = record8755(v.map { it.net }) ?: return "$name n${v.size}"
            return "$name n${r.n} mean${"%+.1f".format(r.mean)}% exTop3${"%+.1f".format(r.exTop3)}% runners${"%.1f".format(100.0 * v.count { it.net >= RUNNER_NET } / v.size)}%"
        }
        val sb = StringBuilder()
        sb.append(line("ALL", disc)).append('\n')
        sb.append("BY LANE: ").append(disc.groupBy { it.lane }.entries.sortedByDescending { it.value.size }.take(8).joinToString(" | ") { line(it.key, it.value) }).append('\n')
        sb.append("BY SOURCE: ").append(disc.groupBy { it.src }.entries.sortedByDescending { it.value.size }.take(8).joinToString(" | ") { line(it.key, it.value) }).append('\n')
        sb.append("BEST CELLS (discovery): ").append(mined.take(6).joinToString(" | ") { (s, sc, _) -> "${s.lane} ${s.conds.joinToString("&") { "${it.feature}${fmtRange(it)}" }} score${"%+.1f".format(sc)}" }).append('\n')
        head?.let { h -> sb.append("RUNNER HEAD base rate ${"%.1f".format(h.baseRate * 100)}% (doubled in 15 min)\n") }
        sb.append("LAUNCH REPLAY: ").append(LaunchReplay8744.statusLine8744().substringAfter("best{").take(400)).append('\n')
        sb.append("BASE REPLAY: ").append(BaseFamily8754.statusLine8754().substringAfter("best{").take(400)).append('\n')
        // V5.0.8756 — which exit ladder keeps more of a run, the graded smart-money signal, and where stops lose their level
        sb.append("EXIT LADDERS (launch replay): ").append(LaunchReplay8744.ladderLine8756(LaunchReplay8744.results8756()).take(400)).append('\n')
        sb.append("SMART MONEY: ").append(com.lifecyclebot.engine.truth.SmartMoney8756.statusLine8756().take(400)).append('\n')
        sb.append("STOP SLIP: ").append(com.lifecyclebot.engine.truth.StopSlip8756.statusLine8756().take(400))
        return sb.toString()
    }

    // ── what the Lab and the live engine read ─────────────────────────────────────────────────────────────────────────

    /** LlmLabEngine: the measured evidence (discovery split only) the LLM strategist is briefed with. */
    fun brief8755(): String = brief

    /** LlmLabEngine / LabPromotedFeed: has [s] passed the honest bar (Solana meme strategies; others are not graded here)? */
    fun honestPass8755(s: LabStrategy): Boolean {
        if (s.asset != LabAssetClass.MEME && s.asset != LabAssetClass.ANY) return true
        val fam = specOf8755(s)?.family ?: return false
        return passes8755(Record(s.honestN8755, s.honestMean8755, s.honestMedian8755, s.honestExTop3_8755, s.honestSe8755, s.honestTenX8755), fam)
    }

    /** LaunchSniper8748: the Lab's promoted launch / base rule, used when the replays have not proven one of their own. */
    fun launchProven8755(): LaunchReplay8744.Result? = liveLaunch
    fun baseProven8755(): BaseFamily8754.Graded? = liveBase

    /** Scanner intake priority: a discovery source's runner rate against the whole tape's (0.5 .. 2.0; 1.0 when unmeasured). */
    fun sourceFactor8755(source: String): Double = sourceFactor[source.uppercase()] ?: 1.0

    /** CandidateAdmission8136: the best promoted CELL / SCORE / RUNNER strategy this coin matches, as market-cell evidence. */
    fun evidence8755(ts: TokenState, lane: String, nowMs: Long): CandidateAdmission8136.Evidence8136? {
        val cells = liveCells; val runner = liveRunner
        if (cells.isEmpty() && runner == null) return null
        val raw = try { EdgeModel8537.rawFor8610(ts, lane, nowMs) } catch (_: Throwable) { return null }
        val f = vectorOf8755(raw)
        val src = raw.source.split(',').firstOrNull()?.trim().orEmpty()
        var best: LabStrategy? = null
        for ((s, spec) in cells) if (matches8755(spec, lane, src, f) && (best == null || s.honestExTop3_8755 - s.honestSe8755 > best.honestExTop3_8755 - best.honestSe8755)) best = s
        if (runner != null) {
            val h = head; val thr = h?.thresholds?.get(runner.second.runnerPct)
            val p = h?.model?.predict(headX(lane.uppercase(), f))
            if (thr != null && p != null && p.isFinite() && p >= thr &&
                (best == null || runner.first.honestExTop3_8755 - runner.first.honestSe8755 > best.honestExTop3_8755 - best.honestSe8755)) best = runner.first
        }
        val s = best ?: return null
        served.incrementAndGet()
        return CandidateAdmission8136.Evidence8136("LAB_STRATEGY_8755", s.name, s.honestN8755, s.honestExTop3_8755, s.honestSe8755,
            runnerRate = s.honestTenX8755, netOfCost = true)
    }

    fun statusLine8755(): String {
        maybeRun8755()
        val h = head
        val headLine = h?.let { hh -> "base=${"%.1f".format(hh.baseRate * 100)}% " + listOf(90, 95, 98).joinToString(" ") { p ->
            val r = hh.records[p]; "top${100 - p}%{" + (r?.let { "n${it.n} exTop3${"%+.1f".format(it.exTop3)}±${"%.1f".format(it.se)} med${"%+.0f".format(it.median)}" } ?: "-") + "}" } } ?: "-"
        val all = LlmLabStore.allStrategies().filter { it.asset == LabAssetClass.MEME || it.asset == LabAssetClass.ANY }
        val fams = all.filter { it.status == LabStrategyStatus.PROMOTED }.groupingBy { specOf8755(it)?.family?.name ?: "?" }.eachCount()
        return "strategyLab8755[$status runnerHead{$headLine} minedTested=${minedTested.get()} memeStrategies=${all.size} promoted=$fams " +
            "live{cells=${liveCells.size} runner=${liveRunner?.first?.name ?: "-"} launch=${liveLaunch?.rule?.id ?: "-"} base=${liveBase?.rule?.id ?: "-"}} " +
            "evidenceServed=${served.get()} sources=${sourceFactor.entries.sortedByDescending { it.value }.take(4).joinToString(",") { "${it.key}=${"%.2f".format(it.value)}" }} " +
            "rule=held-out / witnessed-replay record, net of cost, ex-top-3 mean - ${Z_HELDOUT.toInt()} SE (held-out) or 1 SE (replay, DOCTRINE, the bot's latency) > 0, n>=$MIN_N]"
    }
}

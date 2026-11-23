package com.lifecyclebot.xstocks

import com.lifecyclebot.hyperliquid.HlBrain
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Per-instrument, per-family research beside the aggregate xStocks brain. Each existing rule is read on the same
 * actual panel, but its positive weight means holding this instrument long, otherwise flat. A variant is selected
 * on the first half only; second-half labels never select or fit a model. Each half starts flat and pays its
 * measured entry/exit costs. Missing price intervals are excluded and missing held intervals disqualify support.
 *
 * This is research, not live authority: neither supported rows nor their ranking change XsDesk's targets or gate.
 * The lower bound uses the larger daily/weekly-block SE and a conservative total-grid penalty. It is an estimate,
 * not a profitability guarantee; verified underlying-history splices and today's cost snapshot remain explicit.
 */
internal object XsPocketResearch8961 {
    private const val MAX_COST_AGE_MS = 24L * 60L * 60_000L
    private const val MIN_ACTIVE_DAYS = 20
    private const val MIN_BLOCKS = 16

    data class Segment(val record: HlBrain.Result?, val activeDays: Int, val missingHeldDays: Int,
                       val weeklyBlocks: Int, val weeklySePct: Double)
    data class Row(val symbol: String, val family: HlBrain.Family, val strategy: String, val train: Segment,
                   val test: Segment, val costSide: Double, val costAtMs: Long, val lowerPct: Double,
                   val state: String) {
        val supported: Boolean get() = state == "RESEARCH_SUPPORTED"
    }
    data class Report(val rows: List<Row>, val testedVariants: Int, val z: Double, val firstDay: Long,
                      val splitDay: Long, val lastDay: Long, val atMs: Long) {
        fun summary(): String {
            fun pct(x: Double) = if (x.isFinite()) String.format(Locale.US, "%+.3f", x) else "?"
            val states = rows.groupingBy { it.state }.eachCount().entries.joinToString(",") { "${it.key}=${it.value}" }
            val best = rows.take(10).joinToString(" ") {
                "${it.symbol}/${it.strategy}{${it.state} train=${pct(it.train.record?.meanPct ?: Double.NaN)} " +
                    "test=${pct(it.test.record?.meanPct ?: Double.NaN)} lower=${pct(it.lowerPct)} " +
                    "n=${it.test.record?.days ?: 0} active=${it.test.activeDays} gaps=${it.test.missingHeldDays}}"
            }
            return "xsPockets8961[researchOnly=true rows=${rows.size} grid=$testedVariants z=${pct(z)} " +
                "days=$firstDay/$splitDay/$lastDay states{$states} ranked{$best} noLiveAuthority]"
        }

        fun json(): JSONObject = JSONObject().put("researchOnly", true).put("atMs", atMs)
            .put("firstDay", firstDay).put("splitDay", splitDay).put("lastDay", lastDay)
            .put("testedVariants", testedVariants).put("z", finite(z))
            .put("history", "token daily bars; verified underlying splice may precede token history")
            .put("costBasis", "current measured round trip plus app fee; not historical quote reconstruction")
            .put("splitConvention", "train decisions before splitDay-1; test decisions from splitDay; split-boundary return omitted")
            .put("learnedFit", "targets dated before splitDay only; earlier holdout outcomes are never refit")
            .put("rows", JSONArray().also { a -> rows.forEach { r ->
                a.put(JSONObject().put("symbol", r.symbol).put("family", r.family.name).put("strategy", r.strategy)
                    .put("state", r.state).put("costSide", finite(r.costSide)).put("costAtMs", r.costAtMs)
                    .put("lowerPct", finite(r.lowerPct)).put("train", segmentJson(r.train)).put("test", segmentJson(r.test)))
            } })
    }

    private fun finite(value: Double): Any = value.takeIf { it.isFinite() } ?: JSONObject.NULL
    private fun segmentJson(s: Segment): JSONObject = JSONObject().put("days", s.record?.days ?: 0)
        .put("activeDays", s.activeDays).put("missingHeldDays", s.missingHeldDays).put("weeklyBlocks", s.weeklyBlocks)
        .put("meanPct", finite(s.record?.meanPct ?: Double.NaN)).put("sePct", finite(s.record?.sePct ?: Double.NaN))
        .put("weeklySePct", finite(s.weeklySePct)).put("totalPct", finite(s.record?.totalPct ?: Double.NaN))

    fun penalty(testedVariants: Int): Double = sqrt(2.0 * ln(maxOf(1, testedVariants) / 0.05))

    /** A target at index d contains price[d+1]; exclude d == half-1 as well as all later outcomes. */
    internal fun trainingOnlyDesign(design: HlBrain.Design, half: Int): HlBrain.Design =
        HlBrain.Design(design.x, Array(design.y.size) { d ->
            if (d < half - 1) design.y[d].copyOf() else DoubleArray(design.y[d].size) { Double.NaN }
        })

    fun analyze(p: HlBrain.Panel, costClocks: Map<String, Long>, universeSize: Int, nowMs: Long,
                betweenChunks: () -> Unit = {}): Report {
        val design = trainingOnlyDesign(HlBrain.design(p), p.days / 2)
        val paths = linkedMapOf<HlBrain.Strategy, Array<DoubleArray>>()
        for (variants in HlBrain.GRID.values) for (strategy in variants) {
            paths[strategy] = HlBrain.path(p, strategy, design)
            betweenChunks()
        }
        return evaluate(p, paths, costClocks, universeSize, nowMs, betweenChunks)
    }

    /** Supplied paths make chronological selection and missing-data behavior directly testable. */
    internal fun evaluate(p: HlBrain.Panel, paths: Map<HlBrain.Strategy, Array<DoubleArray>>,
                          costClocks: Map<String, Long>, universeSize: Int, nowMs: Long,
                          betweenChunks: () -> Unit = {}): Report {
        val half = p.days / 2
        val tested = maxOf(universeSize, p.coins.size) * paths.size
        val z = penalty(tested)
        val rows = ArrayList<Row>()
        for (c in p.coins.indices) {
            val symbol = p.coins[c].removePrefix("xs:")
            for ((family, variants) in paths.keys.groupBy { it.family }) {
                // No second-half metric participates in this choice. Discard the split-boundary return.
                val selected = variants.map { s -> s to segment(p, paths.getValue(s), c, 0, half - 1) }
                    .maxWithOrNull(compareBy<Pair<HlBrain.Strategy, Segment>> { it.second.record?.sharpe ?: Double.NEGATIVE_INFINITY }
                        .thenBy { it.second.record?.meanPct ?: Double.NEGATIVE_INFINITY }) ?: continue
                val train = selected.second
                val test = segment(p, paths.getValue(selected.first), c, half, p.days - 1)
                val clock = costClocks[symbol] ?: 0L
                val costKnown = clock > 0L && nowMs - clock in 0L..MAX_COST_AGE_MS &&
                    p.costSide[c].isFinite() && p.costSide[c] >= 0.0
                val se = maxOf(test.record?.sePct ?: Double.NaN, test.weeklySePct)
                val lower = (test.record?.meanPct ?: Double.NaN) - z * se
                val state = when {
                    !costKnown -> "COST_UNOBSERVED"
                    train.missingHeldDays > 0 || test.missingHeldDays > 0 -> "HISTORY_GAPS"
                    (train.record?.days ?: 0) < HlBrain.MIN_TEST_DAYS || (test.record?.days ?: 0) < HlBrain.MIN_TEST_DAYS ||
                        train.activeDays < MIN_ACTIVE_DAYS || test.activeDays < MIN_ACTIVE_DAYS || test.weeklyBlocks < MIN_BLOCKS -> "THIN"
                    !(train.record!!.meanPct > 0.0) -> "TRAIN_NOT_POSITIVE"
                    !lower.isFinite() || lower <= 0.0 -> "HOLDOUT_NOT_SUPPORTED"
                    else -> "RESEARCH_SUPPORTED"
                }
                rows.add(Row(symbol, family, selected.first.id, train, test, p.costSide[c], clock, lower, state))
            }
            betweenChunks()
        }
        val ranked = rows.sortedWith(compareByDescending<Row> { it.supported }
            .thenByDescending { it.lowerPct.takeIf { n -> n.isFinite() } ?: Double.NEGATIVE_INFINITY }
            .thenBy { it.symbol }.thenBy { it.family.name })
        return Report(ranked, tested, z, p.firstDay, p.firstDay + half, p.firstDay + p.days - 1, nowMs)
    }

    private fun segment(p: HlBrain.Panel, path: Array<DoubleArray>, c: Int, from: Int, until: Int): Segment {
        val returns = ArrayList<Pair<Int, Double>>()
        var previous = 0.0
        var active = 0
        var gaps = 0
        val cost = p.costSide[c]
        for (d in from.coerceAtLeast(0) until minOf(until, p.days - 1)) {
            val weight = if ((path.getOrNull(d)?.getOrNull(c) ?: 0.0) > 0.0) 1.0 else 0.0
            val change = p.ret(c, d + 1)
            if (!change.isFinite()) {
                if (weight > 0.0 || previous > 0.0) gaps++
                previous = weight
                continue
            }
            val net = weight * change - cost * abs(weight - previous)
            if (net.isFinite()) returns.add(d to net)
            if (weight > 0.0) active++
            previous = weight
        }
        if (returns.isNotEmpty() && previous > 0.0 && cost.isFinite()) {
            val last = returns.last(); returns[returns.lastIndex] = last.first to (last.second - cost)
        }
        val values = returns.map { it.second }.toDoubleArray()
        val blocks = returns.groupBy { Math.floorDiv(p.firstDay + it.first, 7L) }.values.map { b -> b.map { it.second }.average() }
        val mean = blocks.average()
        val blockSe = if (blocks.size > 1) sqrt(blocks.sumOf { (it - mean) * (it - mean) } / (blocks.size - 1) / blocks.size) * 100.0 else Double.NaN
        return Segment(HlBrain.grade("instrument", values), active, gaps, blocks.size, blockSe)
    }
}

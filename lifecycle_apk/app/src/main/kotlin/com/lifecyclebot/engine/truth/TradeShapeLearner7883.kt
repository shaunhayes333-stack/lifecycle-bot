package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7883 §CHANGE_HOW_THE_TRADE_IS_MADE.
 *
 * Operator: "it shouldn't be just cutting sizing ... it should be changing how
 * the trade was made, size, timing, tokenomics, volume ... cutting size just
 * bleeds into the same losses repeatedly."
 *
 * The 08 Oct audit found ~20 loss-driven size multipliers (inert at the route
 * minimum) and no learner that attributes a loss to a cause. This one does, per
 * lane, on the tokenomics and timing the lane's own logic traded on:
 *
 *   AGE_MIN          token age at the decision           (timing: too early / too late)
 *   RUNUP_PCT        run-up from the local low           (timing: chasing)
 *   PEAK_POS         price as a fraction of its peak     (timing: buying the top)
 *   DRAWDOWN_PCT     drawdown from the peak              (timing: catching a knife)
 *   BUY_PRESSURE     buy share of flow                   (volume / flow)
 *   LIQUIDITY_USD    pool depth                          (exitability)
 *   MCAP_USD         market cap                          (size band)
 *   MCAP_LIQ         mcap / liquidity                    (valuation air)
 *   TOP_HOLDER_PCT   top holder concentration            (tokenomics)
 *
 * Every forward-labeled decision (admitted AND refused — so the lane learns
 * from trade one, not only from its own fills) is captured with these values
 * (TokenMetricStageRouter.snapshot) and graded at 60 minutes net of cost. Per
 * lane x feature x value bin the learner keeps n, mean, variance and the
 * runner rate (+50% gross).
 *
 * A bin becomes a learned entry rule for that lane — "MOONSHOT does not buy
 * top-holder 30-50%", "SHITCOIN does not buy run-up > 400%" — when it is
 * PROVEN losing: n >= [MIN_N], mean + 1 SE below [LOSING_PCT], worse than the
 * lane's own average by [MARGIN_PCT], and (runner lanes) without a measured
 * tail (runner rate < 10%). It is enforced through LiveEdgeGate7877 as a
 * refusal naming the feature and bin, and lifted automatically when the bin's
 * numbers recover. Nothing here resizes a trade.
 */
object TradeShapeLearner7883 {
    enum class Feature(val edges: DoubleArray) {
        AGE_MIN(doubleArrayOf(3.0, 15.0, 60.0, 240.0, 1_440.0)),
        RUNUP_PCT(doubleArrayOf(25.0, 70.0, 150.0, 400.0)),
        PEAK_POS(doubleArrayOf(0.5, 0.7, 0.88)),
        DRAWDOWN_PCT(doubleArrayOf(10.0, 25.0, 50.0)),
        BUY_PRESSURE(doubleArrayOf(45.0, 55.0, 65.0, 80.0)),
        LIQUIDITY_USD(doubleArrayOf(3_000.0, 10_000.0, 50_000.0, 250_000.0)),
        MCAP_USD(doubleArrayOf(10_000.0, 50_000.0, 250_000.0, 1_000_000.0, 5_000_000.0)),
        MCAP_LIQ(doubleArrayOf(3.0, 8.0, 25.0, 85.0)),
        TOP_HOLDER_PCT(doubleArrayOf(10.0, 20.0, 30.0, 50.0)),
    }

    private const val MIN_N = 30
    private const val LOSING_PCT = -2.0
    private const val MARGIN_PCT = 2.0
    private const val TAIL_RUNNER_RATE = 0.10
    private const val RUNNER_GROSS_PCT = 50.0
    private const val PENDING_TTL_MS = 90L * 60_000L
    private const val MAX_PENDING = 8_000
    private const val PERSIST_KEY = "TRADE_SHAPE_LEARNER_7883"
    private const val PERSIST_EVERY = 25

    /** Pure: the bin index of [v] on [f]'s edges (0..edges.size), or -1 when unknown. */
    fun binOf(f: Feature, v: Double): Int {
        if (!v.isFinite() || v < 0.0) return -1
        var i = 0
        while (i < f.edges.size && v >= f.edges[i]) i++
        return i
    }

    /** Pure: a readable bin label, e.g. "30-50" or ">=50". */
    fun binLabel(f: Feature, bin: Int): String = when {
        bin <= 0 -> "<${fmt(f.edges[0])}"
        bin >= f.edges.size -> ">=${fmt(f.edges.last())}"
        else -> "${fmt(f.edges[bin - 1])}-${fmt(f.edges[bin])}"
    }

    private fun fmt(v: Double): String = when {
        v >= 1_000_000 -> "${(v / 1_000_000).toInt()}M"
        v >= 1_000 -> "${(v / 1_000).toInt()}k"
        v < 1.0 -> "%.2f".format(v)
        else -> v.toInt().toString()
    }

    class Stat {
        var n = 0; var sum = 0.0; var sumSq = 0.0; var runners = 0
        fun mean() = if (n > 0) sum / n else 0.0
        fun se(): Double = if (n > 1) kotlin.math.sqrt(((sumSq / n) - mean() * mean()).coerceAtLeast(0.0) / n) else Double.POSITIVE_INFINITY
        fun runnerRate() = if (n > 0) runners.toDouble() / n else 0.0
        fun add(net: Double, gross: Double) { n++; sum += net; sumSq += net * net; if (gross >= RUNNER_GROSS_PCT) runners++ }
        fun encode() = "$n,$sum,$sumSq,$runners"
        fun decode(s: String) {
            val f = s.split(','); if (f.size != 4) return
            n = f[0].toIntOrNull() ?: 0; sum = f[1].toDoubleOrNull() ?: 0.0
            sumSq = f[2].toDoubleOrNull() ?: 0.0; runners = f[3].toIntOrNull() ?: 0
        }
    }

    /** Pure: is this bin a proven-losing shape for a lane whose own mean is [laneMean]? */
    fun provenLosing(bin: Stat, laneMean: Double, runnerLane: Boolean): Boolean {
        if (bin.n < MIN_N) return false
        val se = bin.se().let { if (it.isFinite()) it else return false }
        if (bin.mean() + se >= LOSING_PCT) return false
        if (bin.mean() >= laneMean - MARGIN_PCT) return false
        if (runnerLane && bin.runnerRate() >= TAIL_RUNNER_RATE) return false
        return true
    }

    private class Pending(val lane: String, val bins: IntArray, val atMs: Long)

    private val pending = ConcurrentHashMap<String, Pending>()               // mint|lane
    private val stats = ConcurrentHashMap<String, Stat>()                    // lane|feature|bin
    private val laneAll = ConcurrentHashMap<String, Stat>()                  // lane
    private val captured = AtomicLong(0)
    private val graded = AtomicLong(0)
    private val refusals = ConcurrentHashMap<String, AtomicLong>()
    @Volatile private var loaded = false
    private val sincePersist = AtomicLong(0)

    private fun learnable(lane: String): Boolean =
        lane.isNotBlank() && !lane.startsWith("PLANWAIT_") && !lane.startsWith("PLANADMIT_")

    private fun canon(lane: String): String =
        CanonicalLaneIdentity6506.canonical(lane).uppercase().ifBlank { lane.trim().uppercase() }

    /** Raw feature values for [ts], from the stage router's own snapshot. */
    private fun values(ts: TokenState): DoubleArray? = try {
        val s = com.lifecyclebot.engine.TokenMetricStageRouter.snapshot(ts)
        // An unknown figure stays unknown (-1 → no bin): the router reads a missing
        // liquidity as 0 and the ratio it implies as 9999.
        val liq = if (s.liquidityUsd > 0.0) s.liquidityUsd else -1.0
        val mcap = if (s.marketCapUsd > 0.0) s.marketCapUsd else -1.0
        val ratio = if (liq > 0.0 && mcap > 0.0) s.mcapToLiq else -1.0
        doubleArrayOf(
            s.ageMin, s.runupFromLocalLowPct, s.currentVsPeak, s.drawdownFromPeakPct, s.buyPressurePct,
            liq, mcap, ratio, s.topHolderPct,
        )
    } catch (_: Throwable) { null }

    private fun binsFor(ts: TokenState): IntArray? {
        val v = values(ts) ?: return null
        return IntArray(Feature.values().size) { i -> binOf(Feature.values()[i], v[i]) }
    }

    /** Called by ForwardReturnLabeler7731 when it opens an observation. */
    fun capture(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()) {
        if (!learnable(lane)) return
        val bins = binsFor(ts) ?: return
        val l = canon(lane)
        if (pending.size >= MAX_PENDING) pending.entries.removeIf { nowMs - it.value.atMs > PENDING_TTL_MS }
        if (pending.size >= MAX_PENDING) return
        pending["${ts.mint}|${lane.trim().uppercase()}"] = Pending(l, bins, nowMs)
        captured.incrementAndGet()
    }

    /** Called by ForwardReturnLabeler7731 when the 60-minute label books. */
    fun onLabel60(mint: String, labelLane: String, netPct: Double, grossPct: Double) {
        if (!netPct.isFinite()) return
        val p = pending.remove("$mint|${labelLane.trim().uppercase()}") ?: return
        ensureLoaded()
        synchronized(this) {
            laneAll.getOrPut(p.lane) { Stat() }.add(netPct, grossPct)
            Feature.values().forEachIndexed { i, f ->
                val b = p.bins[i]
                if (b >= 0) stats.getOrPut("${p.lane}|${f.name}|$b") { Stat() }.add(netPct, grossPct)
            }
        }
        graded.incrementAndGet()
        if (sincePersist.incrementAndGet() >= PERSIST_EVERY) { sincePersist.set(0); persist() }
    }

    /** The learned shape rule [ts] breaks in [lane], or null. Pure read, no side effects. */
    fun shapeRefusal(ts: TokenState, lane: String): String? {
        ensureLoaded()
        val l = canon(lane)
        val bins = binsFor(ts) ?: return null
        val runner = try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(l) } catch (_: Throwable) { false }
        return synchronized(this) {
            val laneMean = laneAll[l]?.mean() ?: 0.0
            var found: String? = null
            for ((i, f) in Feature.values().withIndex()) {
                val b = bins[i]
                if (b < 0) continue
                val st = stats["$l|${f.name}|$b"] ?: continue
                if (provenLosing(st, laneMean, runner)) {
                    found = "SHAPE_7883_${f.name}_${binLabel(f, b)}_n${st.n}_${"%.1f".format(st.mean())}PCT"
                    break
                }
            }
            found
        }
    }

    /** Counts a refusal (called by the gate after it decides to refuse on the shape). */
    fun noteRefusal(lane: String, reason: String) {
        val key = "${canon(lane)}|${reason.removePrefix("SHAPE_7883_").substringBefore("_n")}"
        refusals.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("SHAPE_7883_REFUSED_${canon(lane)}")
            if (com.lifecyclebot.engine.ForensicEmitRateLimiter6356.shouldEmit("SHAPE_7883", key)) {
                ForensicLogger.lifecycle("SHAPE_7883_REFUSED", "lane=${canon(lane)} rule=$reason action=lane_learned_entry_rule")
            }
        } catch (_: Throwable) {}
    }

    // ── persistence ──

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            try {
                val o = org.json.JSONObject(LearningPersistence.load(PERSIST_KEY) ?: return)
                o.optJSONObject("bins")?.let { j -> for (k in j.keys()) stats[k] = Stat().also { it.decode(j.optString(k)) } }
                o.optJSONObject("lanes")?.let { j -> for (k in j.keys()) laneAll[k] = Stat().also { it.decode(j.optString(k)) } }
            } catch (_: Throwable) {}
        }
    }

    private fun persist() {
        try {
            val o = org.json.JSONObject()
            synchronized(this) {
                o.put("bins", org.json.JSONObject().also { j -> stats.forEach { (k, v) -> j.put(k, v.encode()) } })
                o.put("lanes", org.json.JSONObject().also { j -> laneAll.forEach { (k, v) -> j.put(k, v.encode()) } })
            }
            LearningPersistence.save(PERSIST_KEY, o.toString())
        } catch (_: Throwable) {}
    }

    /** Learned rules currently in force, for the snapshot. */
    fun statusLine(): String {
        ensureLoaded()
        return synchronized(this) {
            val rules = ArrayList<String>()
            for ((k, st) in stats) {
                val parts = k.split('|'); if (parts.size != 3) continue
                val lane = parts[0]
                val f = try { Feature.valueOf(parts[1]) } catch (_: Throwable) { continue }
                val b = parts[2].toIntOrNull() ?: continue
                val runner = try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane) } catch (_: Throwable) { false }
                if (provenLosing(st, laneAll[lane]?.mean() ?: 0.0, runner)) {
                    rules += "$lane:${f.name}${binLabel(f, b)}[n=${st.n} ${"%+.1f".format(st.mean())}% run=${(st.runnerRate() * 100).toInt()}%]"
                }
            }
            "captured=${captured.get()} graded=${graded.get()} pending=${pending.size} bins=${stats.size} " +
                "lanes=[${laneAll.entries.sortedByDescending { it.value.n }.take(8).joinToString(",") { "${it.key}:n${it.value.n}/${"%+.1f".format(it.value.mean())}%" }}]\n" +
                "      rules in force: ${rules.sorted().take(20).joinToString(" · ").ifBlank { "none yet (a bin needs n>=$MIN_N and to be proven losing)" }}\n" +
                "      refusals: ${refusals.entries.sortedByDescending { it.value.get() }.take(10).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}"
        }
    }
}

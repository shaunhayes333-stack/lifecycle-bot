package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7961 — the price the bot actually pays versus the price it decided on.
 *
 * Every learner that picks trades (Cortex, playbook setups, trade shapes, the forecast
 * model) grades a decision by its forward return from the price at the decision. A
 * live buy fills seconds later, after the pipeline, the quote and the land — on a coin
 * that is moving, higher. 5.0.7958: MOONSHOT's STRONG reads graded +20% at five minutes
 * (n61) while its STRONG-read live positions realised -8% (n4): the edge the labels saw
 * was spent before the fill.
 *
 * Each fill is matched to the forward-label observation of its decision (same mint and
 * lane, admitted): chase = fill / decision - 1. The lane's median chase (live fills;
 * paper latency fills as a floor until live has [MIN_LIVE_N] samples) is added to the
 * cost every new label of that lane is graded net of, so the whole selection stack
 * learns the edge it can actually execute, and re-learns it as fills get faster or slower.
 */
object EntryChase7961 {
    private const val MIN_LIVE_N = 8
    private const val MIN_PAPER_N = 20
    private const val RING = 120
    private const val MAX_PENALTY_PCT = 30.0
    private const val MAX_DECISION_AGE_MS = 10L * 60_000L
    private const val PERSIST_KEY = "ENTRY_CHASE_7961"

    private val live = HashMap<String, ArrayDeque<Double>>()
    private val paper = HashMap<String, ArrayDeque<Double>>()
    private val latencyMs = HashMap<String, ArrayDeque<Double>>()
    private val matched = AtomicLong(0)
    private val unmatched = AtomicLong(0)
    private val sincePersist = AtomicLong(0)
    @Volatile private var loaded = false

    /** Pure: the chase in percent, or null when the two prices cannot be the same unit/token. */
    fun chasePct7961(decisionPx: Double, fillPx: Double): Double? {
        if (!decisionPx.isFinite() || decisionPx <= 0.0 || !fillPx.isFinite() || fillPx <= 0.0) return null
        val r = fillPx / decisionPx
        if (r < 0.2 || r > 5.0) return null
        return (r - 1.0) * 100.0
    }

    /** Pure: the median of [xs] clipped to [0, MAX_PENALTY_PCT] (a cheaper fill is not credited as edge). */
    fun penaltyOf7961(xs: List<Double>): Double {
        val s = xs.filter { it.isFinite() }.sorted()
        if (s.isEmpty()) return 0.0
        val m = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
        return m.coerceIn(0.0, MAX_PENALTY_PCT)
    }

    private fun lane(raw: String): String {
        val c = try { CanonicalLaneIdentity6506.canonical(raw).uppercase() } catch (_: Throwable) { "" }
        return c.ifBlank { raw.trim().uppercase() }.ifBlank { "UNKNOWN" }
    }

    /** EntryStrategySnapshot6450.setEntry: a position opened at [fillPx] (USD) at [fillAtMs]. */
    fun onEntry7961(mint: String, laneRaw: String, fillPx: Double, fillAtMs: Long, paperMode: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank() || fillAtMs <= 0L || kotlin.math.abs(nowMs - fillAtMs) > 3L * 60_000L) return   // a recovered/old position
        val l = lane(laneRaw)
        val d = try { ForwardReturnLabeler7731.decisionPx7961(mint, l) } catch (_: Throwable) { null }
        val chase = d?.takeIf { fillAtMs - it.second in 0L..MAX_DECISION_AGE_MS }?.let { chasePct7961(it.first, fillPx) }
        if (chase == null || d == null) {
            unmatched.incrementAndGet()
            try { PipelineHealthCollector.labelInc("ENTRY_CHASE_UNMATCHED_7961") } catch (_: Throwable) {}
            return
        }
        ensureLoaded()
        synchronized(this) {
            val book = if (paperMode) paper else live
            book.getOrPut(l) { ArrayDeque() }.apply { addLast(chase); while (size > RING) removeFirst() }
            if (!paperMode) latencyMs.getOrPut(l) { ArrayDeque() }.apply { addLast((fillAtMs - d.second).toDouble()); while (size > RING) removeFirst() }
        }
        matched.incrementAndGet()
        try { PipelineHealthCollector.labelInc(if (paperMode) "ENTRY_CHASE_PAPER_7961" else "ENTRY_CHASE_LIVE_7961") } catch (_: Throwable) {}
        if (sincePersist.incrementAndGet() >= 5) { sincePersist.set(0); persist() }
    }

    /** The lane's learned chase in percent, added to the cost new labels are graded net of. 0 until measured. */
    fun lanePenaltyPct7961(laneRaw: String): Double {
        ensureLoaded()
        val l = lane(laneRaw)
        return synchronized(this) {
            val lv = live[l]
            val pp = paper[l]
            when {
                lv != null && lv.size >= MIN_LIVE_N -> penaltyOf7961(lv.toList())
                pp != null && pp.size >= MIN_PAPER_N -> penaltyOf7961(pp.toList())
                else -> 0.0
            }
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        if (!LearningPersistence.ready()) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            try {
                val o = org.json.JSONObject(LearningPersistence.load(PERSIST_KEY) ?: return)
                fun read(name: String, into: HashMap<String, ArrayDeque<Double>>) {
                    val j = o.optJSONObject(name) ?: return
                    for (k in j.keys()) {
                        val a = j.optJSONArray(k) ?: continue
                        into[k] = ArrayDeque((0 until a.length()).map { a.optDouble(it) }.filter { it.isFinite() }.takeLast(RING))
                    }
                }
                read("live", live); read("paper", paper); read("lat", latencyMs)
            } catch (_: Throwable) {}
        }
    }

    private fun persist() {
        if (!loaded) return
        try {
            val json = synchronized(this) {
                fun enc(m: HashMap<String, ArrayDeque<Double>>) = org.json.JSONObject().also { j ->
                    m.forEach { (k, v) -> j.put(k, org.json.JSONArray(v.map { kotlin.math.round(it * 100.0) / 100.0 })) }
                }
                org.json.JSONObject().put("live", enc(live)).put("paper", enc(paper)).put("lat", enc(latencyMs)).toString()
            }
            LearningPersistence.save(PERSIST_KEY, json)
        } catch (_: Throwable) {}
    }

    fun statusLine(): String {
        ensureLoaded()
        return synchronized(this) {
            val lanes = (live.keys + paper.keys).distinct().sorted()
            "matched=${matched.get()} unmatched=${unmatched.get()} " + lanes.joinToString(" · ") { l ->
                val lv = live[l]; val pp = paper[l]; val lat = latencyMs[l]
                "$l live=${lv?.size ?: 0}/${"%+.1f".format(if (lv.isNullOrEmpty()) 0.0 else lv.sorted()[lv.size / 2])}%" +
                    " paper=${pp?.size ?: 0}/${"%+.1f".format(if (pp.isNullOrEmpty()) 0.0 else pp.sorted()[pp.size / 2])}%" +
                    " lat=${if (lat.isNullOrEmpty()) "-" else "${(lat.sorted()[lat.size / 2] / 1000.0).toInt()}s"} penalty=${"%.1f".format(lanePenaltyPct7961(l))}%"
            }.ifBlank { "no fills yet" }
        }
    }
}

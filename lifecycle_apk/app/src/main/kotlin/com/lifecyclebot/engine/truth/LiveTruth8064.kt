package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8064 — the live book has the last word.
 *
 * Owner, 5.0.8063: "bro wtf... seriously its got worse not better". 44 live closes, 3 wins, PF 0.09, while the label
 * book read the best cells at +529% / +588% (wr 86-95%). The labels credited the spike ladder on every peak (302 of 341
 * graded labels) — exits the live bot never banks (spike tiers fired=0; a +156% quick take landed at -40%; a +236% peak
 * closed at -18%). From 8064 the labels are the lane's own play without that credit (ForwardReturnLabeler7731), and this
 * book checks that what the labels promise is what live money gets: a lane whose recent live closes (the last
 * [WINDOW_MS], at least [MIN_N]) are proven losing — mean + SE < 0, net of every fee — takes no new live entries until
 * those closes age out of the window. The labels keep grading it the whole time, so it comes back on its own.
 */
object LiveTruth8064 {
    private const val WINDOW_MS = 6L * 60L * 60_000L
    private const val MIN_N = 8
    private const val MAX_PER_LANE = 60
    // V5.0.8070 — a fresh book: the 8064 book was filled from the boot replay with closes made before the honest era.
    private const val KEY = "LIVE_TRUTH_8070"
    /** V5.0.8070 — the first build of the honest era (labels without spike credit, 8064). */
    private const val HONEST_ERA_BUILD = 8064

    private val byLane = ConcurrentHashMap<String, ArrayDeque<DoubleArray>>()  // [atMs, netPct]
    private val subscribed = AtomicBoolean(false)
    @Volatile private var loaded = false
    private val recorded = AtomicLong(0)
    private val refused = AtomicLong(0)

    /** Pure: is a book of live net returns [pcts] proven losing (n >= MIN_N, mean + SE < 0)? */
    fun provenLosing8064(pcts: List<Double>): Boolean {
        val xs = pcts.filter { it.isFinite() }
        if (xs.size < MIN_N) return false
        val mean = xs.average()
        val variance = xs.sumOf { (it - mean) * (it - mean) } / (xs.size - 1)
        return mean + kotlin.math.sqrt(variance / xs.size) < 0.0
    }

    /** Pure (V5.0.8070): was a close produced by an honest-era build (5.0.8064 or later)? Unknown builds do not count. */
    fun honestEra8070(version: String?): Boolean {
        val build = version?.trim()?.substringAfterLast('.')?.takeWhile { it.isDigit() }?.toIntOrNull() ?: return false
        return build >= HONEST_ERA_BUILD
    }

    private fun laneOf(raw: String?): String {
        val c = try { CanonicalLaneIdentity6506.canonical(raw).uppercase() } catch (_: Throwable) { "" }
        return c.ifBlank { raw?.trim()?.uppercase().orEmpty() }.ifBlank { "UNKNOWN" }
    }

    private fun ensure() {
        if (subscribed.compareAndSet(false, true)) try {
            CanonicalTradeFinalizedBus6450.subscribe { e ->
                // V5.0.8070 — only closes made in the honest era count. 5.0.8064 at 577 s held CASHGEN / MOONSHOT /
                // SHITCOIN on closes the pre-8064 (spike-credited) regime bought — 0 trades of the new regime — and
                // that hold refused INUX (+1,724%) and DOPE (+486%).
                if (e.mode.equals("live", ignoreCase = true) && honestEra8070(e.producedByVersion7103)) record(laneOf(e.entryLane), e.netReturnPct, e.settledAtMs)
            }
        } catch (_: Throwable) { subscribed.set(false) }
        if (!loaded && (try { LearningPersistence.ready() } catch (_: Throwable) { false })) synchronized(this) {
            if (loaded) return
            loaded = true
            try {
                val o = org.json.JSONObject(LearningPersistence.load(KEY) ?: return)
                for (lane in o.keys()) {
                    val a = o.optJSONArray(lane) ?: continue
                    val q = byLane.getOrPut(lane) { ArrayDeque() }
                    for (i in 0 until a.length()) a.optJSONArray(i)?.let { r -> q.addLast(doubleArrayOf(r.optDouble(0), r.optDouble(1))) }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun record(lane: String, netPct: Double, atMs: Long) {
        if (!netPct.isFinite()) return
        val t = if (atMs > 0L) atMs else System.currentTimeMillis()
        synchronized(this) {
            byLane.getOrPut(lane) { ArrayDeque() }.apply { addLast(doubleArrayOf(t.toDouble(), netPct)); while (size > MAX_PER_LANE) removeFirst() }
        }
        recorded.incrementAndGet()
        try {
            val o = org.json.JSONObject()
            synchronized(this) { for ((l, q) in byLane) o.put(l, org.json.JSONArray().apply { q.forEach { put(org.json.JSONArray().put(it[0]).put(it[1])) } }) }
            LearningPersistence.save(KEY, o.toString())
        } catch (_: Throwable) {}
    }

    private fun recent(lane: String, nowMs: Long): List<Double> = synchronized(this) {
        byLane[lane]?.filter { nowMs - it[0].toLong() in 0L..WINDOW_MS }?.map { it[1] }.orEmpty()
    }

    /** LiveEdgeGate7877: the refusal when [lane]'s recent live closes are proven losing, else null. */
    fun refusal8064(lane: String, nowMs: Long = System.currentTimeMillis()): String? {
        ensure()
        val l = laneOf(lane)
        if (!provenLosing8064(recent(l, nowMs))) return null
        refused.incrementAndGet()
        try { PipelineHealthCollector.labelInc("LIVE_BOOK_LOSING_8064_$l") } catch (_: Throwable) {}
        return "LIVE_BOOK_LOSING_8064"
    }

    fun statusLine(): String {
        ensure()
        val now = System.currentTimeMillis()
        val lanes = byLane.keys.sorted().mapNotNull { l ->
            val xs = recent(l, now)
            if (xs.isEmpty()) null else "$l[n=${xs.size} mean=${"%+.1f".format(xs.average())}%${if (provenLosing8064(xs)) " HELD" else ""}]"
        }
        return "recorded=${recorded.get()} refused=${refused.get()} window=6h bar=n>=$MIN_N,mean+SE<0 lanes=${lanes.joinToString(" · ").ifBlank { "none" }}"
    }
}

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
 *
 * V5.0.8083 §NO_LANE_WIDE_DEADLOCK. 5.0.8082: FDG 0 allowed / 577 blocked, 352 of them LIVE_BOOK_LOSING_8064 — eight
 * negative live closes held a whole lane for six hours whatever the token, setup or strategy. The lane book is now a
 * bounded NEGATIVE PRIOR (lanePrior8083: evidence + a size factor), never a refusal on its own. Every live close is also
 * filed under the exact playbook setup it was bought on (noteLiveAdmit8083 at the live admit, persisted across restarts),
 * and only a candidate whose OWN setup's live closes are proven losing (n >= MIN_N, mean + SE < 0, net of fees) is
 * refused (setupRefusal8083). A losing lane still admits a candidate carrying its own validated positive evidence.
 */
object LiveTruth8064 {
    private const val WINDOW_MS = 6L * 60L * 60_000L
    // V5.0.8103 — 8 -> 5. 5.0.8098: CASHGEN 6 live closes, 20% WR, mean -18.5%, one near-total loss, and the book was
    // still "immature", so neither the lane prior nor the setup refusal saw it. mean + SE < 0 still has to hold.
    private const val MIN_N = 5
    private const val MAX_PER_LANE = 60
    // V5.0.8070 — a fresh book: the 8064 book was filled from the boot replay with closes made before the honest era.
    private const val KEY = "LIVE_TRUTH_8070"
    /** V5.0.8070 — the first build of the honest era (labels without spike credit, 8064). */
    private const val HONEST_ERA_BUILD = 8064

    private val byLane = ConcurrentHashMap<String, ArrayDeque<DoubleArray>>()  // [atMs, netPct]
    /** V5.0.8083 — lane|setup -> [atMs, netPct] of live closes bought on that exact setup. */
    private val bySetup8083 = ConcurrentHashMap<String, ArrayDeque<DoubleArray>>()
    /** V5.0.8083 — mint -> lane|setup|admittedAtMs of a live admit awaiting its close (persisted). */
    private val pendingSetup8083 = ConcurrentHashMap<String, String>()
    private const val SETUP_KEY_8083 = "LIVE_TRUTH_SETUP_8083"
    private const val PENDING_TTL_MS_8083 = 72L * 60L * 60_000L
    const val UNATTRIBUTED_SETUP_8083 = "UNATTRIBUTED"
    /** Bounded negative-prior size factor for a proven-losing lane (never 0, never a block). */
    const val LOSING_LANE_SIZE_FACTOR_8083 = 0.6
    private val setupRefused8083 = AtomicLong(0)
    private val priorShaped8083 = AtomicLong(0)
    private val subscribed = AtomicBoolean(false)
    @Volatile private var loaded = false
    private val recorded = AtomicLong(0)

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
                if (e.mode.equals("live", ignoreCase = true) && honestEra8070(e.producedByVersion7103)) {
                    val lane8083 = laneOf(e.entryLane)
                    record(lane8083, e.netReturnPct, e.settledAtMs)
                    recordSetup8083(e.mint, lane8083, e.netReturnPct, e.settledAtMs, e.positionId)
                }
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
            try {
                val o = org.json.JSONObject(LearningPersistence.load(SETUP_KEY_8083) ?: "{}")
                o.optJSONObject("books")?.let { b ->
                    for (k in b.keys()) {
                        val a = b.optJSONArray(k) ?: continue
                        val q = bySetup8083.getOrPut(k) { ArrayDeque() }
                        for (i in 0 until a.length()) a.optJSONArray(i)?.let { r -> q.addLast(doubleArrayOf(r.optDouble(0), r.optDouble(1))) }
                    }
                }
                o.optJSONObject("pending")?.let { p -> for (k in p.keys()) pendingSetup8083[k] = p.optString(k) }
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

    /** Pure (V5.0.8083): the bounded negative prior of a lane book — 1.0 unless proven losing, then [LOSING_LANE_SIZE_FACTOR_8083]. */
    fun priorSizeFactor8083(pcts: List<Double>): Double = if (provenLosing8064(pcts)) LOSING_LANE_SIZE_FACTOR_8083 else 1.0

    data class LanePrior8083(val lane: String, val n: Int, val meanPct: Double, val losing: Boolean, val sizeFactor: Double)

    /**
     * V5.0.8083 — the lane's recent live book as a NEGATIVE PRIOR, never a refusal. LiveEdgeGate7877 requires a
     * candidate in a losing lane to carry its OWN validated positive evidence and shapes its size by [sizeFactor].
     */
    fun lanePrior8083(lane: String, nowMs: Long = System.currentTimeMillis()): LanePrior8083 {
        ensure()
        val l = laneOf(lane)
        val xs = recent(l, nowMs)
        val losing = provenLosing8064(xs)
        if (losing) {
            priorShaped8083.incrementAndGet()
            try { PipelineHealthCollector.labelInc("LIVE_LANE_NEGATIVE_PRIOR_8083_$l") } catch (_: Throwable) {}
        }
        return LanePrior8083(l, xs.size, if (xs.isEmpty()) Double.NaN else xs.average(), losing, priorSizeFactor8083(xs))
    }

    private fun setupKey8083(lane: String, setup: String?): String =
        "${laneOf(lane)}|${setup?.trim()?.uppercase()?.ifBlank { null } ?: UNATTRIBUTED_SETUP_8083}"

    /**
     * V5.0.8083 — binding ONLY on the candidate's own setup: the live closes bought on this exact lane + setup are
     * proven losing (n >= [MIN_N], mean + SE < 0, net of fees). The unattributed bucket never refuses anything.
     */
    fun setupRefusal8083(lane: String, setup: String?, nowMs: Long = System.currentTimeMillis()): String? {
        ensure()
        if (setup.isNullOrBlank()) return null
        val key = setupKey8083(lane, setup)
        val xs = synchronized(this) { bySetup8083[key]?.filter { nowMs - it[0].toLong() in 0L..WINDOW_MS }?.map { it[1] }.orEmpty() }
        if (!provenLosing8064(xs)) return null
        setupRefused8083.incrementAndGet()
        try { PipelineHealthCollector.labelInc("LIVE_SETUP_LOSING_8083_$key") } catch (_: Throwable) {}
        return "LIVE_SETUP_LOSING_8083"
    }

    /**
     * V5.0.8365 — [n, mean %] of the realised live closes bought on [lane] + [setup] inside the window (the record
     * [setupRefusal8083] judges); null when there are none or the setup is unattributed. FdgGate8365 reads it so a label
     * "setup losing" door does not outrank the setup's own realised money.
     */
    fun setupRealised8365(lane: String, setup: String?, nowMs: Long = System.currentTimeMillis()): DoubleArray? {
        ensure()
        if (setup.isNullOrBlank()) return null
        val key = setupKey8083(lane, setup)
        if (key.endsWith("|$UNATTRIBUTED_SETUP_8083")) return null
        val xs = synchronized(this) { bySetup8083[key]?.filter { nowMs - it[0].toLong() in 0L..WINDOW_MS }?.map { it[1] }.orEmpty() }
            .filter { it.isFinite() }
        if (xs.isEmpty()) return null
        return doubleArrayOf(xs.size.toDouble(), xs.average())
    }

    /** V5.0.8083 — LiveEdgeGate7877 at a LIVE admit: file this mint's eventual close under its exact lane + setup. */
    fun noteLiveAdmit8083(mint: String, lane: String, setup: String?, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        ensure()
        pendingSetup8083[mint] = "${setupKey8083(lane, setup)}|$nowMs"
        if (pendingSetup8083.size > 500) pendingSetup8083.entries.removeIf { nowMs - (it.value.substringAfterLast('|').toLongOrNull() ?: 0L) > PENDING_TTL_MS_8083 }
        persistSetup8083()
    }

    /**
     * V5.0.8092 — the close files under the EXECUTED position's immutable entry strategy first; the mint-keyed
     * admit book is only the fallback. The admit book can be overwritten by later admits of the same mint
     * (LiveEdgeGate re-classifies every cycle), but the frozen strategy of the position that actually filled
     * cannot: EntryStrategySnapshot6450 rejects rewrites. [positionId] is blank only for legacy replay.
     */
    internal fun recordSetup8083(mint: String, lane: String, netPct: Double, atMs: Long, positionId: String = "") {
        if (!netPct.isFinite()) return
        val l = laneOf(lane)
        var key: String? = null
        if (positionId.isNotBlank()) {
            try {
                val snap = com.lifecyclebot.engine.truth.EntryStrategySnapshot6450.snapshot(positionId)
                if (snap != null && snap.mint == mint && laneOf(snap.entryLane) == l) {
                    val setup = snap.entrySetup.ifBlank {
                        snap.cortexSetupTag8085.substringBefore(';').substringBefore('#')
                    }.trim().uppercase()
                    if (setup.isNotBlank()) {
                        key = setupKey8083(l, setup)
                        try { PipelineHealthCollector.labelInc("LIVE_CLOSE_SETUP_FROM_ENTRY_SNAPSHOT_8092") } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }
        val p = pendingSetup8083.remove(mint)
        if (key == null) {
            key = p?.substringBeforeLast('|')?.takeIf { it.substringBefore('|') == l } ?: setupKey8083(lane, null)
        }
        if (key.endsWith("|$UNATTRIBUTED_SETUP_8083")) try { PipelineHealthCollector.labelInc("LIVE_CLOSE_SETUP_UNATTRIBUTED_8083_$lane") } catch (_: Throwable) {}
        val t = if (atMs > 0L) atMs else System.currentTimeMillis()
        synchronized(this) {
            bySetup8083.getOrPut(key) { ArrayDeque() }.apply { addLast(doubleArrayOf(t.toDouble(), netPct)); while (size > MAX_PER_LANE) removeFirst() }
        }
        persistSetup8083()
    }

    private fun persistSetup8083() {
        try {
            val books = org.json.JSONObject()
            synchronized(this) { for ((k, q) in bySetup8083) books.put(k, org.json.JSONArray().apply { q.forEach { put(org.json.JSONArray().put(it[0]).put(it[1])) } }) }
            val pending = org.json.JSONObject().apply { pendingSetup8083.forEach { (k, v) -> put(k, v) } }
            LearningPersistence.save(SETUP_KEY_8083, org.json.JSONObject().put("books", books).put("pending", pending).toString())
        } catch (_: Throwable) {}
    }

    fun statusLine(): String {
        ensure()
        val now = System.currentTimeMillis()
        val lanes = byLane.keys.sorted().mapNotNull { l ->
            val xs = recent(l, now)
            if (xs.isEmpty()) null else "$l[n=${xs.size} mean=${"%+.1f".format(xs.average())}%${if (provenLosing8064(xs)) " HELD" else ""}]"
        }
        val setups = synchronized(this) { bySetup8083.keys.sorted() }.mapNotNull { k ->
            val xs = synchronized(this) { bySetup8083[k]?.filter { now - it[0].toLong() in 0L..WINDOW_MS }?.map { it[1] }.orEmpty() }
            if (xs.isEmpty()) null else "$k[n=${xs.size} mean=${"%+.1f".format(xs.average())}%${if (provenLosing8064(xs)) " REFUSED" else ""}]"
        }
        return "recorded=${recorded.get()} lanePriorShaped=${priorShaped8083.get()} setupRefused=${setupRefused8083.get()} window=6h bar=n>=$MIN_N,mean+SE<0 " +
            "lanes(prior,size x$LOSING_LANE_SIZE_FACTOR_8083 when HELD)=${lanes.joinToString(" · ").ifBlank { "none" }} setups=${setups.joinToString(" · ").ifBlank { "none" }}"
    }
}

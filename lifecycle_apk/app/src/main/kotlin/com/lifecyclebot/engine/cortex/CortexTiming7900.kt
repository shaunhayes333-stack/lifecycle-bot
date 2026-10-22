package com.lifecyclebot.engine.cortex

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7900 — Cortex v6: the timing cortex (enter now, or wait for the dip).
 *
 * Plan v2 §C.3 timing: no learner changed WHEN a lane enters. Every candidate
 * the entry Cortex captures is also graded on the price move over the next
 * [WAIT_MS] (5 minutes): a reliably negative move means the signal fires
 * before the pullback and a patient entry gets a better price.
 *
 * Same ledger, fusion and regime cells as the entry Cortex, on its own target.
 * TIMING_BAR_V1 per lane (decaying): reads that fused a 5-minute move below
 * -[DIP_PCT]% (DIP bucket) proven at n >= 40 with mean + SE < -2%. Then a
 * candidate read DIP is deferred (Constitution rule C4_WAIT_FOR_DIP) — never a
 * STRONG entry read, and never a runner lane (their edge is the launch itself).
 * The candidate is re-evaluated on later cycles, at the lower price if the dip
 * comes. Until proven: SHADOW_WAIT counts only.
 */
object CortexTiming7900 {
    private const val WAIT_MS = 5L * 60_000L
    private const val GRADE_WINDOW_MS = 4L * 60_000L
    private const val MARK_MAX_AGE_MS = 90_000L
    private const val DIP_PCT = 3.0
    private const val PROOF_PCT = 2.0
    private const val MIN_N = 40
    private const val BOOK_DECAY = 0.997
    private const val MAX_PENDING = 6_000
    private const val PERSIST_KEY = "CORTEX_TIMING_7900"
    private const val PERSIST_EVERY = 50

    private class Sample(val lane: String, val regime: String, val ids: List<String>, val edges: List<DoubleArray>,
                         val raws: DoubleArray, val dip: Boolean, val mint: String, val px0: Double, val atMs: Long)

    private val ledger = CortexLedger7885()
    private val dipBook = HashMap<String, CortexLedger7885.Stat>()
    /** V5.0.8309 — census: timing seat ledger + dip book (learning: counted, never trimmed). */
    fun ledgerBytes8309(): Long = ledger.estBytes8309().coerceAtLeast(0L) + (try { dipBook.size } catch (_: Throwable) { 0 }) * 200L
    private val pending = ConcurrentHashMap<String, Sample>()
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val graded = AtomicLong(0)
    private val sincePersist = AtomicLong(0)
    @Volatile private var loaded = false

    private fun inc(k: String) { counters.computeIfAbsent(k) { AtomicLong(0) }.incrementAndGet() }

    /** Pure: TIMING_BAR_V1. */
    fun waitProven(dip: CortexLedger7885.Stat): Boolean {
        if (dip.n < MIN_N) return false
        val se = if (dip.n > 1.0) kotlin.math.sqrt(dip.variance() / dip.n) else return false
        return dip.mean() + se < -PROOF_PCT
    }

    private fun votesOf(a: Cortex7885.Assessment): List<CortexLedger7885.Vote> =
        a.ids.indices.map { i -> CortexLedger7885.Vote(a.ids[i], a.edges[i], a.raws[i], setOf(a.ids[i])) }

    /** The fused 5-minute move for an assessment (lane prior when nothing is seated). */
    private fun fusedMove(a: Cortex7885.Assessment): Double {
        ensureLoaded()
        return synchronized(this) { ledger.fuse(a.lane, votesOf(a), a.regime).edgePct }
    }

    /** Cortex7885.capture: one candidate opens a 5-minute timing label. */
    fun capture(ts: TokenState, a: Cortex7885.Assessment, nowMs: Long) {
        // V5.0.7948 — a candidate priced through the canonical registry (lastPriceUpdate=0)
        // opened no sample at all; read the forward label's own entry mark instead.
        val tsFresh = ts.lastPrice.isFinite() && ts.lastPrice > 0.0 && nowMs - ts.lastPriceUpdate <= MARK_MAX_AGE_MS
        val px = if (tsFresh) ts.lastPrice else (com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.markFor7948(ts.mint, { null }, nowMs) ?: return)
        if (!px.isFinite() || px <= 0.0) return
        if (pending.size >= MAX_PENDING) pending.entries.removeIf { nowMs - it.value.atMs > WAIT_MS + GRADE_WINDOW_MS }
        if (pending.size >= MAX_PENDING) return
        val dip = fusedMove(a) < -DIP_PCT
        pending["${ts.mint}|${a.lane}|$nowMs"] = Sample(a.lane, a.regime, a.ids, a.edges, a.raws, dip, ts.mint, px, nowMs)
        persistPendingMaybe8187(nowMs)
    }

    // ── V5.0.8187 — timing samples survive a restart (their 5-minute window outlives most installs) ──
    // The exit cortex has kept its pending samples since 7960; the timing cortex dropped every one on each restart, so a
    // build installed inside the 5-9 minute window graded nothing (5.0.8175: pending=19 graded=0 at 179 s uptime).
    private const val PENDING_PERSIST_MAX_8187 = 300
    private const val PENDING_PERSIST_EVERY_MS_8187 = 120_000L
    @Volatile private var lastPendingPersist8187 = 0L
    private val persistInFlight8187 = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun persistPendingMaybe8187(nowMs: Long) {
        if (!loaded || nowMs - lastPendingPersist8187 < PENDING_PERSIST_EVERY_MS_8187) return
        if (!persistInFlight8187.compareAndSet(false, true)) return
        lastPendingPersist8187 = nowMs
        try {
            Thread({ try { persist() } finally { persistInFlight8187.set(false) } }, "cortex-timing-persist-8187").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        } catch (_: Throwable) { persistInFlight8187.set(false) }
    }

    /** Pure (V5.0.8187): may a sample read at [atMs] still be graded at [nowMs] (inside its 5 + 4 minute window)? */
    internal fun gradeable8187(atMs: Long, nowMs: Long): Boolean = atMs > 0L && atMs <= nowMs && nowMs - atMs <= WAIT_MS + GRADE_WINDOW_MS

    private fun encodePending8187(nowMs: Long): org.json.JSONObject {
        val dict = LinkedHashMap<String, Int>()
        val arr = org.json.JSONArray()
        pending.entries.filter { gradeable8187(it.value.atMs, nowMs) }.sortedByDescending { it.value.atMs }.take(PENDING_PERSIST_MAX_8187).forEach { (k, sm) ->
            val ids = org.json.JSONArray(); val xs = org.json.JSONArray()
            for (i in sm.ids.indices) {
                val r = sm.raws.getOrNull(i) ?: continue
                if (!r.isFinite()) continue
                ids.put(dict.getOrPut(sm.ids[i]) { dict.size }); xs.put(r)
            }
            arr.put(org.json.JSONObject().put("k", k).put("l", sm.lane).put("g", sm.regime).put("d", sm.dip).put("m", sm.mint)
                .put("p", sm.px0).put("at", sm.atMs).put("i", ids).put("x", xs))
        }
        return org.json.JSONObject().put("dict", org.json.JSONArray(dict.keys.toList())).put("p", arr)
    }

    private fun restorePending8187(o: org.json.JSONObject?, nowMs: Long) {
        if (o == null) return
        val jd = o.optJSONArray("dict") ?: return
        val dict = List(jd.length()) { jd.optString(it) }
        val arr = o.optJSONArray("p") ?: return
        var n = 0L
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            val at = j.optLong("at"); val k = j.optString("k"); val px = j.optDouble("p", 0.0)
            if (k.isBlank() || !gradeable8187(at, nowMs) || !(px > 0.0) || pending.containsKey(k)) continue
            val ji = j.optJSONArray("i") ?: continue
            val jx = j.optJSONArray("x") ?: continue
            val ids = ArrayList<String>(); val edges = ArrayList<DoubleArray>(); val raws = ArrayList<Double>()
            for (q in 0 until minOf(ji.length(), jx.length())) {
                val id = dict.getOrNull(ji.optInt(q, -1)) ?: continue
                val e = CortexVoters7885.edgesFor(id) ?: continue
                ids.add(id); edges.add(e); raws.add(jx.optDouble(q))
            }
            if (ids.isEmpty()) continue
            pending[k] = Sample(j.optString("l"), j.optString("g"), ids, edges, raws.toDoubleArray(), j.optBoolean("d"), j.optString("m"), px, at)
            n++
        }
        if (n > 0L) counters.computeIfAbsent("PENDING_RESTORED_8187") { AtomicLong(0) }.addAndGet(n)
    }

    /** ExitRegret7752.tick clock: grade samples whose 5-minute window has come. */
    fun tick(priceFor: (String) -> Double?, nowMs: Long = System.currentTimeMillis()) {
        if (pending.isEmpty()) return
        ensureLoaded()
        for ((k, s) in pending.entries.toList()) {
            val age = nowMs - s.atMs
            if (age < WAIT_MS) continue
            if (age > WAIT_MS + GRADE_WINDOW_MS) { pending.remove(k); inc("WINDOW_MISSED"); continue }
            // V5.0.7948 — the forward label's mark chain (loop → registry → off-watch batch).
            val px = com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.markFor7948(s.mint, priceFor, nowMs)
                ?.takeIf { it.isFinite() && it > 0.0 } ?: continue
            pending.remove(k)
            if (com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.basisSuspect7738(s.px0, px)) { inc("BASIS_SUSPECT_8187"); continue }
            val move = (px / s.px0 - 1.0) * 100.0
            synchronized(this) {
                ledger.grade(s.lane, s.ids, s.edges, s.raws, move, move, s.regime)
                if (s.dip) {
                    val b = dipBook.getOrPut(s.lane) { CortexLedger7885.Stat() }
                    b.scale(BOOK_DECAY)
                    b.add(move.coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX), false)  // V5.0.8322 — stored raw (8187 clamped at ingestion)
                }
            }
            graded.incrementAndGet()
            if (sincePersist.incrementAndGet() >= PERSIST_EVERY) { sincePersist.set(0); persist() }
        }
    }

    /**
     * Cortex7885.entryRefusal: C4_WAIT_FOR_DIP when this candidate's fused
     * 5-minute move is a dip and the lane's DIP record is proven.
     */
    fun waitRefusal(a: Cortex7885.Assessment): String? {
        if (a.runnerLane) return null
        // V5.0.7948 — a STRONG read is exempt only where STRONG still means strong.
        if (a.bucket == CortexScoreboard7885.Bucket.STRONG && !Cortex7885.laneInverted7948(a.lane)) return null
        val move = fusedMove(a)
        if (move >= -DIP_PCT) return null
        val proven = synchronized(this) { dipBook[a.lane]?.let { waitProven(it) } ?: false }
        if (!proven) { inc("SHADOW_WAIT"); return null }
        inc("WAITED_${a.lane}")
        try {
            PipelineHealthCollector.labelInc("CORTEX_7900_WAIT_FOR_DIP_${a.lane}")
            if (com.lifecyclebot.engine.ForensicEmitRateLimiter6356.shouldEmit("CORTEX_7900_WAIT", a.lane)) {
                ForensicLogger.lifecycle("CORTEX_7900_WAIT_FOR_DIP", "lane=${a.lane} fusedMove5m=${"%.2f".format(move)} action=defer_entry")
            }
        } catch (_: Throwable) {}
        return "C4_WAIT_FOR_DIP"
    }

    private fun ensureLoaded() {
        if (loaded) return
        // V5.0.7930 — never latch "loaded" before the store opens: an early read came back
        // empty and the next save overwrote the real ledgers with it.
        if (!LearningPersistence.ready()) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            try {
                val o = org.json.JSONObject(LearningPersistence.load(PERSIST_KEY) ?: return)
                o.optJSONObject("ledger")?.let { ledger.decode(it) }
                o.optJSONObject("dip")?.let { j -> for (k in j.keys()) dipBook[k] = CortexLedger7885.Stat().also { it.decode(j.optString(k)) } }
                restorePending8187(o.optJSONObject("pending8187"), System.currentTimeMillis())  // V5.0.8187
            } catch (e: Throwable) { LearningPersistence.decodeFailed8086(PERSIST_KEY, e) } finally {
                // V5.0.8086 — the row is on disk but would not read: stay unloaded (persist() is gated) and retry.
                if (LearningPersistence.readFailed8086(PERSIST_KEY)) loaded = false
            }
        }
    }

    /** V5.0.7930 — BotService.onDestroy: save now (graded state between periodic saves was lost on restart). */
    fun persistNow7930() {
        if (!loaded) return
        persist()
    }

    private fun persist() {
        if (!loaded) return
        try {
            val json = synchronized(this) {
                org.json.JSONObject().put("ledger", ledger.encode())
                    .put("dip", org.json.JSONObject().also { j -> dipBook.forEach { (k, v) -> j.put(k, v.encode()) } })
                    .put("pending8187", encodePending8187(System.currentTimeMillis())).toString()
            }
            LearningPersistence.save(PERSIST_KEY, json)
        } catch (_: Throwable) {}
    }

    fun statusLine(): String {
        ensureLoaded()
        return synchronized(this) {
            val seated = ledger.seats.entries.filter { !it.key.contains('@') && it.value.authority() > 0.0 }.size
            "bar=TIMING_BAR_V1 window=5m pending=${pending.size} graded=${graded.get()} seated=$seated " +
                "actions=${counters.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }} " +
                "dip=[${dipBook.entries.joinToString(",") { (l, s) -> "$l:n${s.n.toInt()}/${"%+.1f".format(s.mean())}%${if (waitProven(s)) "*" else ""}" }.ifBlank { "-" }}]"
        }
    }
}

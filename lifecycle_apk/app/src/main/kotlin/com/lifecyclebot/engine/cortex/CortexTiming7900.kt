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
        val px = ts.lastPrice
        if (!px.isFinite() || px <= 0.0 || nowMs - ts.lastPriceUpdate > MARK_MAX_AGE_MS) return
        if (pending.size >= MAX_PENDING) pending.entries.removeIf { nowMs - it.value.atMs > WAIT_MS + GRADE_WINDOW_MS }
        if (pending.size >= MAX_PENDING) return
        val dip = fusedMove(a) < -DIP_PCT
        pending["${ts.mint}|${a.lane}|$nowMs"] = Sample(a.lane, a.regime, a.ids, a.edges, a.raws, dip, ts.mint, px, nowMs)
    }

    /** ExitRegret7752.tick clock: grade samples whose 5-minute window has come. */
    fun tick(priceFor: (String) -> Double?, nowMs: Long = System.currentTimeMillis()) {
        if (pending.isEmpty()) return
        ensureLoaded()
        for ((k, s) in pending.entries.toList()) {
            val age = nowMs - s.atMs
            if (age < WAIT_MS) continue
            if (age > WAIT_MS + GRADE_WINDOW_MS) { pending.remove(k); inc("WINDOW_MISSED"); continue }
            val px = (try { priceFor(s.mint) } catch (_: Throwable) { null })?.takeIf { it.isFinite() && it > 0.0 } ?: continue
            pending.remove(k)
            if (com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.basisSuspect7738(s.px0, px)) continue
            val move = (px / s.px0 - 1.0) * 100.0
            synchronized(this) {
                ledger.grade(s.lane, s.ids, s.edges, s.raws, move, move, s.regime)
                if (s.dip) {
                    val b = dipBook.getOrPut(s.lane) { CortexLedger7885.Stat() }
                    b.scale(BOOK_DECAY)
                    b.add(move.coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX), false)
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
        if (a.runnerLane || a.bucket == CortexScoreboard7885.Bucket.STRONG) return null
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
            } catch (_: Throwable) {}
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
                    .put("dip", org.json.JSONObject().also { j -> dipBook.forEach { (k, v) -> j.put(k, v.encode()) } }).toString()
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

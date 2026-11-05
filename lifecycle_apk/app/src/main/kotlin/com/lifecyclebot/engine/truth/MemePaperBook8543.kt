package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.PipelineHealthCollector
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8543 §MEME_PAPER_BOOK_BESIDE_LIVE — owner: "0 paper meme trades at all".
 *
 * The owner runs LIVE. In LIVE the meme executor's own paperBuy is blocked by design (ExecutionRouteGuard, V5.0.3725): a
 * paper position would occupy the same TokenState.position slot the live trade uses, and its journal rows would mix into
 * the live books. So no build since 3725 has opened a meme paper trade in a LIVE session — the 8540 paper-explore door only
 * acts in PAPER mode.
 *
 * Every lane-qualified meme pick is already traded in simulation: ExploreShadow8347 opens a NATIVE ticket when a lane's own
 * brain qualifies a coin, enters it NOW at the observed mark, and runs it on the lane's live exit levers (runner tiers / the
 * scalp ladder, the lane stop, the clock, the sell latency, the round-trip cost). This book records exactly that leg — the
 * one that models the live position — as the lane's paper trade, per lane, durable across restarts, and reports it. It
 * never touches a TokenState, a live slot, the live journal, the canonical ledger or the wallet: paper economics stay
 * separate from live, and these nets are never reported as realised money. Stake is a NOMINAL [NOMINAL_STAKE_SOL_8543].
 */
object MemePaperBook8543 {
    const val NOMINAL_STAKE_SOL_8543 = 0.1
    private const val KEY_8543 = "MEME_PAPER_BOOK_8543"
    private const val RECENT_MAX_8543 = 40

    class LaneTally8543 { var n = 0; var wins = 0; var sumNet = 0.0; var sumSq = 0.0; var best = Double.NaN; var worst = Double.NaN }
    private data class Trade8543(val lane: String, val mint: String, val symbol: String, val entryAtMs: Long, val exitAtMs: Long,
                         val netPct: Double, val why: String)

    private val lanes = ConcurrentHashMap<String, LaneTally8543>()
    private val recent = ArrayDeque<Trade8543>()
    @Volatile private var restored = false
    @Volatile private var dirty = false
    @Volatile private var lastSaveMs = 0L

    /** Pure: one closed paper trade folded into a lane tally. */
    fun fold8543(t: LaneTally8543, netPct: Double) {
        if (!netPct.isFinite()) return
        t.n++; if (netPct > 0.0) t.wins++
        t.sumNet += netPct; t.sumSq += netPct * netPct
        t.best = if (t.best.isFinite()) maxOf(t.best, netPct) else netPct
        t.worst = if (t.worst.isFinite()) minOf(t.worst, netPct) else netPct
    }

    /** ExploreShadow8347: the NATIVE / NOW / lane-live-exit leg of [lane]'s pick closed at [netPct] (after cost). */
    fun onClose8543(lane: String, mint: String, symbol: String, entryAtMs: Long, exitAtMs: Long, netPct: Double, why: String) {
        if (!netPct.isFinite() || lane.isBlank()) return
        restore8543()
        val l = lane.trim().uppercase()
        synchronized(this) {
            fold8543(lanes.getOrPut(l) { LaneTally8543() }, netPct)
            recent.addLast(Trade8543(l, mint, symbol, entryAtMs, exitAtMs, netPct, why.take(40)))
            while (recent.size > RECENT_MAX_8543) recent.removeFirst()
            dirty = true
        }
        try { PipelineHealthCollector.labelInc("MEME_PAPER_BOOK_CLOSE_8543_$l") } catch (_: Throwable) {}
        if (exitAtMs - lastSaveMs > 30_000L) save8543(exitAtMs)
    }

    private fun save8543(nowMs: Long) {
        if (!dirty || !LearningPersistence.ready()) return
        val j = synchronized(this) {
            dirty = false; lastSaveMs = nowMs
            JSONObject().put("lanes", JSONObject().also { o ->
                lanes.forEach { (k, t) -> o.put(k, JSONArray().put(t.n).put(t.wins).put(t.sumNet).put(t.sumSq).put(t.best).put(t.worst)) }
            }).put("recent", JSONArray().also { a ->
                recent.forEach { r -> a.put(JSONArray().put(r.lane).put(r.mint).put(r.symbol).put(r.entryAtMs).put(r.exitAtMs).put(r.netPct).put(r.why)) }
            })
        }
        try { LearningPersistence.save(KEY_8543, j.toString()) } catch (_: Throwable) { dirty = true }
    }

    private fun restore8543() {
        if (restored || !LearningPersistence.ready()) return
        synchronized(this) {
            if (restored) return
            restored = true
            val raw = try { LearningPersistence.load(KEY_8543) } catch (_: Throwable) { null } ?: return
            try {
                val j = JSONObject(raw)
                val o = j.optJSONObject("lanes")
                o?.keys()?.forEach { k ->
                    val a = o.optJSONArray(k) ?: return@forEach
                    lanes.getOrPut(k) { LaneTally8543() }.apply {
                        n += a.optInt(0); wins += a.optInt(1); sumNet += a.optDouble(2, 0.0); sumSq += a.optDouble(3, 0.0)
                        val b = a.optDouble(4, Double.NaN); val w = a.optDouble(5, Double.NaN)
                        if (b.isFinite()) best = if (best.isFinite()) maxOf(best, b) else b
                        if (w.isFinite()) worst = if (worst.isFinite()) minOf(worst, w) else w
                    }
                }
                val r = j.optJSONArray("recent")
                if (r != null) for (i in 0 until r.length()) {
                    val x = r.optJSONArray(i) ?: continue
                    recent.addFirst(Trade8543(x.optString(0), x.optString(1), x.optString(2), x.optLong(3), x.optLong(4), x.optDouble(5), x.optString(6)))
                    if (recent.size > RECENT_MAX_8543) recent.removeLast()
                }
            } catch (_: Throwable) {}
        }
    }

    /** Pure: one lane's line — n, win rate, mean net ± se, best / worst, nominal SOL. */
    fun laneLine8543(lane: String, t: LaneTally8543): String {
        if (t.n <= 0) return "$lane[n0]"
        val mean = t.sumNet / t.n
        val se = if (t.n > 1) kotlin.math.sqrt(((t.sumSq / t.n) - mean * mean).coerceAtLeast(0.0) / t.n) else Double.NaN
        val sol = t.sumNet / 100.0 * NOMINAL_STAKE_SOL_8543
        return "$lane[n${t.n} wr${t.wins * 100 / t.n}% ${"%+.1f".format(mean)}%${if (se.isFinite()) "±${"%.1f".format(se)}" else ""} " +
            "best${"%+.0f".format(t.best)}% worst${"%+.0f".format(t.worst)}% ${"%+.3f".format(sol)}SOL@${NOMINAL_STAKE_SOL_8543}]"
    }

    /** Brief / report: the paper meme book per lane (closed), what is open now, and the last trades. */
    fun statusLine8543(openByLane: Map<String, Int>): String {
        restore8543()
        save8543(System.currentTimeMillis())
        val rows = synchronized(this) { lanes.entries.sortedByDescending { it.value.n }.map { laneLine8543(it.key, it.value) } }
        val last = synchronized(this) { recent.toList().takeLast(6).reversed() }
            .joinToString(" · ") { "${it.lane}:${it.symbol.ifBlank { it.mint.take(6) }} ${"%+.1f".format(it.netPct)}% ${it.why}" }
        return "paperBook8543 (simulated beside LIVE, nominal stake, not realised) open{" +
            openByLane.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "-" } +
            "} closed: ${rows.joinToString(" ").ifBlank { "none yet" }} | last: ${last.ifBlank { "-" }}"
    }
}

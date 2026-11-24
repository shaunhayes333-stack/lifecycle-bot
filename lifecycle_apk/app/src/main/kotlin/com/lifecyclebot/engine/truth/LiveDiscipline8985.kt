package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8985 §THE_DOOR_ANSWERS_FOR_ITS_LIVE_MONEY — owner, 24 Nov 21:44: "how does the bot interpret this data in a live state
 * and then tune off it?? self discovery and tuning learning and self discipline are an inherent part of what aate is."
 *
 * What the 21:27 capture (5.0.8984) measured: every learner that admits a live buy proves itself on forward labels and
 * research rows (pockets, agreement, lane triggers, the runner model, the lab), and the live money those admissions
 * then made was recorded but never answered back to the door that let them in. EntryReceipt8500 grades each live close
 * by its admission door ("read-only evidence: it never admits, refuses or sizes anything"); LiveTruth8064 refuses only a
 * named SETUP proven losing in 6 h, and live closes spread over so many setups (11:52: DIAMOND_HANDS_RUNNER n2,
 * RUNUP_HOLD_8383 n3, MICRO_FLAG n2 ...) that none reached its n=5: setupRefused=0 on both captures. Meanwhile the paper
 * book, trading every candidate the paper way and filing each close by what LIVE decided, read LIVE_ADMIT negative in
 * every meme lane (MOONSHOT n35 -9.5 %, SHITCOIN n5 -22.8 %, EXPRESS n4 -33.2 %, CASHGEN n48 -7.0 %, TREASURY n10 -7.6 %)
 * and the live window 69 closes, 28 % won, PF 0.59 — while the same doors kept admitting.
 *
 * Here every LIVE admission is filed under the door that admitted it — the admission reason, and the market-cell evidence
 * it carried (lane pocket, base pocket, agreement, launch rule, runner model, lab strategy ...) — and the realised net of
 * the position it bought is filed back under both when it closes (the canonical finalized bus, the honest era only). A
 * door whose live closes are proven losing ([provenLosing8985]: the last [WINDOW_N_8985] closes within [WINDOW_MS_8985],
 * n >= [MIN_N_8985], mean + SE < 0, net of every fee — LiveTruth8064's bar) keeps exactly one live position open at a time
 * (the probe): its record keeps learning and it re-opens by itself the moment its closes stop proving a loss. A second
 * admission through it while its probe is open is refused (specific: terminal) as [REASON_8985]. A door proven paying
 * (mean - SE > 0) is named in the brief. Nothing is deleted, no gate is removed: the doors are made to answer for money.
 */
object LiveDiscipline8985 {
    const val REASON_8985 = "LIVE_DOOR_PROVEN_LOSING_8985"
    private const val MIN_N_8985 = 6
    const val WINDOW_N_8985 = 20
    private const val WINDOW_MS_8985 = 72L * 60L * 60_000L
    private const val PENDING_TTL_MS_8985 = 6L * 60L * 60_000L
    private const val HELD_TTL_MS_8985 = 7L * 24L * 60L * 60_000L
    private const val MAX_KEYS_8985 = 400
    private const val MAX_PENDING_8985 = 600
    private const val KEY_8985 = "LIVE_DISCIPLINE_8985"
    private const val NATIVE_8985 = "NATIVE"

    /** cohort key -> [atMs, netPct] of live closes, newest last. */
    private val books = ConcurrentHashMap<String, ArrayDeque<DoubleArray>>()
    /** mint -> "fine\u0001coarse\u0001atMs": the door of the newest LIVE admission of that mint, awaiting its buy. */
    private val pending = ConcurrentHashMap<String, String>()
    /** mint -> "fine\u0001coarse\u0001atMs": a live position that filled through that door, awaiting its close. */
    private val held = ConcurrentHashMap<String, String>()
    private val probes = AtomicLong(0)
    private val refused = AtomicLong(0)
    private val filed = AtomicLong(0)
    private val unattributed = AtomicLong(0)
    private val subscribed = AtomicBoolean(false)
    @Volatile private var loaded = false

    /** Pure: the admission reason without its execution suffixes ("SPECIFIC_EDGE_PROVEN_8136+GRAB" -> "SPECIFIC_EDGE_PROVEN_8136"). */
    fun doorCore8985(reason: String?): String = reason.orEmpty().substringBefore('+').trim().uppercase().take(48).ifBlank { "UNKNOWN" }

    /** Pure: the coarse cohort (lane + door) and the fine one (lane + door + the market-cell evidence carried, NATIVE when none). */
    private fun coarse8985(lane: String, reason: String?): String = "${lane.trim().uppercase()}|${doorCore8985(reason)}"
    fun fine8985(lane: String, reason: String?, evidence: String?): String =
        "${coarse8985(lane, reason)}|${evidence?.trim()?.uppercase()?.take(32)?.ifBlank { null } ?: NATIVE_8985}"

    /** Pure: mean and standard error of [xs] (NaN SE below two values). */
    private fun meanSe8985(xs: List<Double>): Pair<Double, Double> {
        val v = xs.filter { it.isFinite() }
        if (v.isEmpty()) return Double.NaN to Double.NaN
        val m = v.average()
        if (v.size < 2) return m to Double.NaN
        val variance = v.sumOf { (it - m) * (it - m) } / (v.size - 1)
        return m to kotlin.math.sqrt(variance / v.size)
    }

    /** Pure: a book of live net returns is proven losing — n >= [MIN_N_8985] and mean + SE < 0 (LiveTruth8064's bar). */
    fun provenLosing8985(xs: List<Double>): Boolean {
        val v = xs.filter { it.isFinite() }
        if (v.size < MIN_N_8985) return false
        val (m, se) = meanSe8985(v)
        return se.isFinite() && m + se < 0.0
    }

    /** Pure: proven paying — n >= [MIN_N_8985] and mean - SE > 0. */
    fun provenPaying8985(xs: List<Double>): Boolean {
        val v = xs.filter { it.isFinite() }
        if (v.size < MIN_N_8985) return false
        val (m, se) = meanSe8985(v)
        return se.isFinite() && m - se > 0.0
    }

    /** Pure: the window a door is judged on — its last [WINDOW_N_8985] closes no older than [WINDOW_MS_8985]. */
    fun window8985(rows: List<DoubleArray>, nowMs: Long): List<Double> =
        rows.filter { nowMs - it[0].toLong() in 0L..WINDOW_MS_8985 }.takeLast(WINDOW_N_8985).map { it[1] }

    private fun windowOf(key: String, nowMs: Long): List<Double> = synchronized(this) { books[key]?.toList() }?.let { window8985(it, nowMs) }.orEmpty()

    /** The book that judges a door: the fine one once it holds [MIN_N_8985] closes, else the coarse one. */
    private fun judgedKey8985(fine: String, coarse: String, nowMs: Long): String =
        if (windowOf(fine, nowMs).size >= MIN_N_8985) fine else coarse

    /** Live positions open now that filled through [key] (fine or coarse). */
    private fun openThrough8985(key: String): Int = held.values.count { v -> v.split('\u0001').let { it.getOrNull(0) == key || it.getOrNull(1) == key } }

    /**
     * CandidateAdmission8136, after the buy-door proof: a LIVE admit through a door proven losing on live closes keeps one
     * probe open at a time; a second is refused (specific: the live gate's terminal). Every LIVE admit's door is noted so
     * the position it buys is filed back under it. Paper, shadow rehearsals and non-admits pass unchanged.
     */
    fun discipline8985(c: CandidateAdmission8136.Candidate8136, d: CandidateAdmission8136.Decision8136, nowMs: Long): CandidateAdmission8136.Decision8136 {
        if (!c.live || !d.admitted) return d
        if (try { FdgGate8365.shadowOnly8393() } catch (_: Throwable) { true }) return d
        ensure8985()
        val fine = fine8985(c.lane, d.reason, c.marketCell8723?.kind)
        val coarse = coarse8985(c.lane, d.reason)
        val judged = judgedKey8985(fine, coarse, nowMs)
        val xs = windowOf(judged, nowMs)
        if (provenLosing8985(xs) && openThrough8985(judged) > 0) {
            refused.incrementAndGet()
            try { PipelineHealthCollector.labelInc("${REASON_8985}_${judged.take(80)}") } catch (_: Throwable) {}
            val (m, se) = meanSe8985(xs)
            return d.copy(terminal = CandidateAdmission8136.Terminal8136.REFUSE, reason = REASON_8985, specific = true,
                trace = d.trace + "liveDiscipline8985 door=$judged n=${xs.size} mean=${"%+.1f".format(m)} se=${"%.1f".format(se)} probeOpen=1")
        }
        if (provenLosing8985(xs)) {
            probes.incrementAndGet()
            try { PipelineHealthCollector.labelInc("LIVE_DOOR_PROBE_8985_${judged.take(80)}") } catch (_: Throwable) {}
        }
        notePending8985(c.mint, fine, coarse, nowMs)
        return d
    }

    /** V5.0.8988 — is the door that [mint]'s newest LIVE admission was noted under proven PAYING on its own live closes? */
    fun doorPaying8988(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val p = pending[mint]?.split('\u0001') ?: return false
        val fine = p.getOrNull(0) ?: return false
        val coarse = p.getOrNull(1) ?: return false
        return provenPaying8985(windowOf(judgedKey8985(fine, coarse, nowMs), nowMs))
    }

    private fun notePending8985(mint: String, fine: String, coarse: String, nowMs: Long) {
        if (mint.isBlank()) return
        pending[mint] = "$fine\u0001$coarse\u0001$nowMs"
        if (pending.size > MAX_PENDING_8985) pending.entries.removeIf { nowMs - (it.value.substringAfterLast('\u0001').toLongOrNull() ?: 0L) > PENDING_TTL_MS_8985 }
    }

    /** EntryReceipt8500.onLiveBuy8500: a landed LIVE buy holds the door its admission was noted under (or its receipt's door). */
    fun onLiveBuy8985(mint: String, lane: String, receiptDoor: String?, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        ensure8985()
        val p = pending.remove(mint)?.takeIf { nowMs - (it.substringAfterLast('\u0001').toLongOrNull() ?: 0L) in 0L..PENDING_TTL_MS_8985 }
        held[mint] = p?.let { it.substringBeforeLast('\u0001') + "\u0001$nowMs" }
            ?: "${fine8985(lane, receiptDoor, null)}\u0001${coarse8985(lane, receiptDoor)}\u0001$nowMs"
        if (held.size > MAX_PENDING_8985) held.entries.removeIf { nowMs - (it.value.substringAfterLast('\u0001').toLongOrNull() ?: 0L) > HELD_TTL_MS_8985 }
        persist8985()
    }

    /** The canonical finalized close of a LIVE position: its realised net is filed under the door it filled through. */
    internal fun onLiveClose8985(mint: String, netPct: Double, atMs: Long) {
        if (mint.isBlank() || !netPct.isFinite()) return
        val h = held.remove(mint)
        if (h == null) { unattributed.incrementAndGet(); return }
        val parts = h.split('\u0001')
        val t = if (atMs > 0L) atMs else System.currentTimeMillis()
        synchronized(this) {
            for (k in listOfNotNull(parts.getOrNull(0), parts.getOrNull(1)).distinct()) {
                if (books.size >= MAX_KEYS_8985 && !books.containsKey(k)) continue
                books.getOrPut(k) { ArrayDeque() }.apply { addLast(doubleArrayOf(t.toDouble(), netPct.coerceIn(-100.0, 10_000.0))); while (size > WINDOW_N_8985 * 2) removeFirst() }
            }
        }
        filed.incrementAndGet()
        persist8985()
    }

    private fun ensure8985() {
        if (subscribed.compareAndSet(false, true)) try {
            CanonicalTradeFinalizedBus6450.subscribe { e ->
                if (e.mode.equals("live", ignoreCase = true) && LiveTruth8064.honestEra8070(e.producedByVersion7103))
                    onLiveClose8985(e.mint, e.netReturnPct, e.settledAtMs)
            }
        } catch (_: Throwable) { subscribed.set(false) }
        if (loaded || !(try { LearningPersistence.ready() } catch (_: Throwable) { false })) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            try { decode8985(LearningPersistence.load(KEY_8985)) } catch (_: Throwable) {}
        }
    }

    internal fun encode8985(): String {
        val b = org.json.JSONObject()
        synchronized(this) { for ((k, q) in books) b.put(k, org.json.JSONArray().apply { q.forEach { put(org.json.JSONArray().put(it[0]).put(it[1])) } }) }
        val h = org.json.JSONObject().apply { held.forEach { (k, v) -> put(k, v) } }
        val p = org.json.JSONObject().apply { pending.forEach { (k, v) -> put(k, v) } }
        return org.json.JSONObject().put("books", b).put("held", h).put("pending", p).toString()
    }

    internal fun decode8985(raw: String?) {
        if (raw.isNullOrBlank()) return
        val o = org.json.JSONObject(raw)
        o.optJSONObject("books")?.let { b ->
            for (k in b.keys()) {
                val a = b.optJSONArray(k) ?: continue
                val q = ArrayDeque<DoubleArray>()
                for (i in 0 until a.length()) a.optJSONArray(i)?.let { r -> if (r.length() == 2) q.addLast(doubleArrayOf(r.optDouble(0), r.optDouble(1))) }
                synchronized(this) { if (!books.containsKey(k)) books[k] = q }
            }
        }
        o.optJSONObject("held")?.let { h -> for (k in h.keys()) held.putIfAbsent(k, h.optString(k)) }
        o.optJSONObject("pending")?.let { p -> for (k in p.keys()) pending.putIfAbsent(k, p.optString(k)) }
    }

    private fun persist8985() {
        if (!loaded) return   // never overwrite the stored book with an unrestored one
        try { LearningPersistence.save(KEY_8985, encode8985()) } catch (_: Throwable) {}
    }

    /** Brief: each door's live record, worst first, PROVEN_LOSING / PAYING named, with how many it refused and probed. */
    fun line8985(nowMs: Long = System.currentTimeMillis()): String {
        ensure8985()
        val keys = synchronized(this) { books.keys.toList() }
        val rows = keys.mapNotNull { k ->
            val xs = windowOf(k, nowMs)
            if (xs.isEmpty()) null else Triple(k, xs, meanSe8985(xs))
        }.sortedBy { it.third.first }
        val body = rows.take(12).joinToString(" ") { (k, xs, ms) ->
            val tag = when { provenLosing8985(xs) -> " PROVEN_LOSING open=${openThrough8985(k)}"; provenPaying8985(xs) -> " PAYING"; else -> "" }
            "$k{n${xs.size} w${xs.count { it > 0.0 }} ${"%+.1f".format(ms.first)}%${if (ms.second.isFinite()) "±${"%.1f".format(ms.second)}" else ""}$tag}"
        }.ifBlank { "-" }
        return "liveDiscipline8985[filed=${filed.get()} unattributed=${unattributed.get()} held=${held.size} probes=${probes.get()} refused=${refused.get()} " +
            "doors{$body} rule=each LIVE admission is filed under its door (reason + evidence carried); a door whose last $WINDOW_N_8985 live closes " +
            "(72 h, n>=$MIN_N_8985) are mean+SE<0 keeps one probe position open at a time, the rest refused until its record stops proving a loss]"
    }

    internal fun resetForTest8985() {
        synchronized(this) { books.clear() }
        pending.clear(); held.clear(); probes.set(0); refused.set(0); filed.set(0); unattributed.set(0); loaded = true
        subscribed.set(true)
    }
}

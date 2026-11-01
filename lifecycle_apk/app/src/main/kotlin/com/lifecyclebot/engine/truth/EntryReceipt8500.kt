package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8500 §EVERY_LIVE_BUY_NAMES_ITS_DOOR. Owner: "how does any of that correct entry timing, improve trade quality".
 * 5.0.8499: runnerAdmits8297 live LOSS_BOUGHT_TOP=19 / LOSS_GAVE_BACK=8 / WIN=7 and 0 of 9 live closes won, but no report
 * said WHICH admission door let each buy through or WHERE on its chart it was bought, so the door buying tops could not
 * be named and cut. Every LIVE buy that lands now carries a receipt: the admission terminal that admitted it (reason,
 * setup, runner grab / re-entry ticket), its stage and chart position at the fill (run-up off its local low, price vs
 * its local peak, 5-minute change, launch age). Its finalized LIVE close grades the receipt with the same outcome classes
 * as the runner ledger (WIN / WIN_CAPPED / LOSS_BOUGHT_TOP / LOSS_GAVE_BACK) and its realised return, folded per door,
 * per stage and per run-up band. Read-only evidence: it never admits, refuses or sizes anything. It survives restarts.
 */
object EntryReceipt8500 {
    data class Receipt8500(val lane: String, val door: String, val setup: String, val stage: String,
                           val runupPct: Double, val vsPeak: Double, val chg5mPct: Double, val ageMin: Double, val atMs: Long)

    private const val PERSIST_KEY_8500 = "ENTRY_RECEIPT_8500"
    private val open = ConcurrentHashMap<String, Receipt8500>()
    /** key -> [n, wins, boughtTop, gaveBack, sumRealisedPct] */
    private val agg = ConcurrentHashMap<String, DoubleArray>()
    @Volatile private var restored = false

    /** Pure: the run-up band a receipt folds into. */
    fun runupBand8500(runupPct: Double): String = when {
        !runupPct.isFinite() -> "?"
        runupPct < 15.0 -> "<15"
        runupPct < 40.0 -> "15-40"
        runupPct < 80.0 -> "40-80"
        runupPct < 150.0 -> "80-150"
        else -> "150+"
    }

    /** Pure: the peak-position band (price / local peak). */
    fun peakBand8500(vsPeak: Double): String = when {
        !vsPeak.isFinite() || vsPeak <= 0.0 -> "?"
        vsPeak >= 0.97 -> "atHigh"
        vsPeak >= 0.88 -> "nearHigh"
        vsPeak >= 0.70 -> "pullback"
        else -> "deep"
    }

    internal fun atEntry8506(ts: TokenState, lane: String, version: Long, nowMs: Long,
                             s: com.lifecyclebot.engine.TokenMetricStageRouter.Snapshot, move: Double): Receipt8500 {
        val proof = AdmissionEdge8180.proof8198("LIVE", ts.mint, lane, version, nowMs)
        val grab = try { com.lifecyclebot.engine.RunnerGrab7967.grab7967(ts.mint, nowMs) } catch (_: Exception) { false }
        val ticket = try { com.lifecyclebot.engine.RunnerPlay8018.ticketActive8018(ts.mint, nowMs) } catch (_: Exception) { false }
        val door = (proof?.reason ?: "NO_LIVE_ADMISSION_RECORD") + (if (grab) "+GRAB" else "") + (if (ticket) "+TICKET" else "")
        return Receipt8500(lane, door, proof?.chosenSetup.orEmpty(), s.stage.name, s.runupFromLocalLowPct, s.currentVsPeak,
            move, s.ageMin, nowMs)
    }

    /** The landed fill commits the evidence captured for this exact execution attempt. */
    fun onLiveBuy8500(ts: TokenState, receipt: Receipt8500?) {
        if (receipt == null) return
        try {
            restore8500()
            val mint = ts.mint
            if (mint.isBlank()) return
            val nowMs = receipt.atMs
            open[mint] = receipt
            if (open.size > 500) open.entries.removeIf { nowMs - it.value.atMs > 7 * 24 * 3_600_000L }
            PipelineHealthCollector.labelInc("ENTRY_RECEIPT_8500_CUT")
            persist8500()
        } catch (_: Throwable) {}
    }

    /** RunnerCapture8017.onClose8017: a finalized LIVE close grades its receipt. */
    fun onLiveClose8500(mint: String, mode: String, terminal: Boolean, realizedPct: Double, mfePct: Double) {
        if (!terminal || !mode.equals("live", true) || mint.isBlank() || !realizedPct.isFinite()) return
        try {
            restore8500()
            val r = open.remove(mint) ?: run { PipelineHealthCollector.labelInc("ENTRY_RECEIPT_8500_CLOSE_UNMATCHED"); return }
            val o = RunnerFunnel8297.outcome8297(realizedPct, mfePct)
            for (k in keysFor8500(r)) fold8500(k, o, realizedPct)
            persist8500()
        } catch (_: Throwable) {}
    }

    /** Pure: the cohorts one receipt folds into. */
    fun keysFor8500(r: Receipt8500): List<String> = listOf(
        "door|${r.lane}|${r.door}", "stage|${r.lane}|${r.stage}", "runup|${runupBand8500(r.runupPct)}", "peak|${peakBand8500(r.vsPeak)}")

    internal fun fold8500(key: String, o: RunnerFunnel8297.Outcome8297, realizedPct: Double) {
        if (agg.size >= 400 && !agg.containsKey(key)) return
        val a = agg.computeIfAbsent(key) { DoubleArray(5) }
        synchronized(a) {
            a[0] += 1.0
            if (o == RunnerFunnel8297.Outcome8297.WIN || o == RunnerFunnel8297.Outcome8297.WIN_CAPPED) a[1] += 1.0
            if (o == RunnerFunnel8297.Outcome8297.LOSS_BOUGHT_TOP) a[2] += 1.0
            if (o == RunnerFunnel8297.Outcome8297.LOSS_GAVE_BACK) a[3] += 1.0
            a[4] += realizedPct.coerceIn(-100.0, 10_000.0)
        }
    }

    /** One report line per cohort family, most-closed first: n, wins, bought-top, gave-back, mean realised. */
    fun line8500(): String {
        restore8500()
        fun fam(p: String, limit: Int) = agg.entries.filter { it.key.startsWith("$p|") }.sortedByDescending { it.value[0] }.take(limit)
            .joinToString(" ") { (k, a) -> "${k.substringAfter('|')}{n${a[0].toInt()} w${a[1].toInt()} top${a[2].toInt()} gave${a[3].toInt()} ${"%+.1f".format(if (a[0] > 0) a[4] / a[0] else 0.0)}%}" }
            .ifBlank { "-" }
        return "entryReceipts8500[open=${open.size}] byDoor{${fam("door", 8)}} byStage{${fam("stage", 8)}} byRunup{${fam("runup", 5)}} byPeak{${fam("peak", 4)}}"
    }

    // ── persistence (doctrine: learnt state survives restarts) ──
    internal fun encode8500(): String {
        val o = org.json.JSONObject()
        val ag = org.json.JSONObject(); agg.forEach { (k, a) -> ag.put(k, org.json.JSONArray(a.toList())) }
        val op = org.json.JSONObject(); open.forEach { (m, r) -> op.put(m, org.json.JSONArray(listOf(r.lane, r.door, r.setup, r.stage,
            r.runupPct.takeIf { it.isFinite() } ?: -1.0, r.vsPeak.takeIf { it.isFinite() } ?: -1.0,
            r.chg5mPct.takeIf { it.isFinite() } ?: 0.0, r.ageMin.takeIf { it.isFinite() } ?: -1.0, r.atMs))) }
        return o.put("agg", ag).put("open", op).toString()
    }

    internal fun decode8500(raw: String?) {
        if (raw.isNullOrBlank()) return
        val o = org.json.JSONObject(raw)
        o.optJSONObject("agg")?.let { ag -> for (k in ag.keys()) ag.optJSONArray(k)?.let { arr ->
            if (arr.length() == 5) agg[k] = DoubleArray(5) { arr.optDouble(it, 0.0) } } }
        o.optJSONObject("open")?.let { op -> for (m in op.keys()) op.optJSONArray(m)?.let { a ->
            if (a.length() == 9) open.putIfAbsent(m, Receipt8500(a.optString(0), a.optString(1), a.optString(2), a.optString(3),
                a.optDouble(4), a.optDouble(5), a.optDouble(6), a.optDouble(7), a.optLong(8))) } }
    }

    private fun restore8500() {
        if (restored) return
        if (!(try { com.lifecyclebot.engine.LearningPersistence.ready() } catch (_: Throwable) { false })) return
        synchronized(this) {
            if (restored) return
            restored = true
            try { decode8500(com.lifecyclebot.engine.LearningPersistence.load(PERSIST_KEY_8500)) } catch (_: Throwable) {}
        }
    }

    private fun persist8500() {
        if (!restored) return   // never overwrite the stored book with an unrestored one
        try { com.lifecyclebot.engine.LearningPersistence.save(PERSIST_KEY_8500, encode8500()) } catch (_: Throwable) {}
    }

    internal fun resetForTest8500() { open.clear(); agg.clear(); restored = true }
}

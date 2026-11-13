package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8618 §TUNING_REPORT — owner: "add any additional data and detail that will help us better tune the bot towards its
 * intended design" (find the launch runners, ride them, cut the losers; live at least as good as paper).
 *
 * Built from the paper book beside live (MemePaperTrader8544), which trades every meme candidate the paper way:
 *
 *  1. PAPER vs LIVE on the same coin. When paper opens a coin, the live admission's verdict on that same candidate is kept;
 *     when paper closes it, its result is filed under that verdict. "Live refused X, paper made +Y % on those coins" is the
 *     direct cost (or saving) of every live gate, measured on trades, not on 5-minute labels.
 *  2. EXITS. Every paper close by lane x exit reason (STOP, TRAIL_STOP, TAKE_PROFIT tiers, CLOCK, LOST_MARK): n, mean net,
 *     win rate, mean hold — where the ladder banks, where the stop bleeds.
 *  3. ENTRY STATE. Every paper close by lane x the coin's launch phase and age band at entry: which states each lane should
 *     take.
 *
 * Session memory only (bounded): the paper book's own record persists; this is the diagnostic cut of it.
 */
object TuningReport8618 {
    private const val MAX_OPEN = 2_000

    private class OpenNote(val lane: String, val liveVerdict: String, val phase: String, val ageBand: String, val atMs: Long)
    private class Agg { var n = 0; var wins = 0; var runners = 0; var sum = 0.0; var pnl = 0.0; var holdMs = 0L }

    private val opens = ConcurrentHashMap<String, OpenNote>()
    private val byVerdict = ConcurrentHashMap<String, Agg>()
    private val byExit = ConcurrentHashMap<String, Agg>()
    private val byState = ConcurrentHashMap<String, Agg>()

    /** Pure: the age band of a coin [ageMin] minutes old. */
    fun ageBand8618(ageMin: Double): String = when {
        !ageMin.isFinite() -> "?"
        ageMin < 1 -> "<1m"; ageMin < 5 -> "<5m"; ageMin < 30 -> "<30m"; ageMin < 180 -> "<3h"; ageMin < 1440 -> "<24h"
        else -> ">=1d"
    }

    /** Pure: a live admission's verdict as the report files it ("ADMIT" or the refusal's door, without its detail). */
    fun verdictKey8618(admitted: Boolean, reason: String?): String =
        if (admitted) "LIVE_ADMIT" else "LIVE_REFUSE:" + reason.orEmpty().substringBefore(':').take(48).ifBlank { "?" }

    private val liveVerdict = ConcurrentHashMap<String, Pair<String, Long>>()

    /** CandidateAdmission8136.assess8136 (LIVE), just before the paper book is offered the same candidate: the live verdict. */
    fun noteLiveVerdict8618(mint: String, lane: String, admitted: Boolean, reason: String?, nowMs: Long) {
        if (liveVerdict.size > 4_000) liveVerdict.entries.removeIf { nowMs - it.value.second > 10 * 60_000L }
        liveVerdict["${lane.trim().uppercase()}|$mint"] = verdictKey8618(admitted, reason) to nowMs
    }

    /** The live verdict last noted for [mint] in [lane] (within 10 minutes), or "LIVE_UNSEEN". */
    fun liveVerdictFor8618(mint: String, lane: String, nowMs: Long): String =
        liveVerdict["${lane.trim().uppercase()}|$mint"]?.takeIf { nowMs - it.second in 0L..10 * 60_000L }?.first ?: "LIVE_UNSEEN"

    /** MemePaperTrader8544.consider8544: paper opened [mint] in [lane]; the live verdict on the same candidate was [liveVerdict]. */
    fun notePaperOpen8618(mint: String, lane: String, liveVerdict: String, phase: String, ageMin: Double, nowMs: Long) {
        if (opens.size >= MAX_OPEN) opens.entries.removeIf { nowMs - it.value.atMs > 24L * 3_600_000L }
        if (opens.size >= MAX_OPEN) return
        opens[mint] = OpenNote(lane, liveVerdict, phase.ifBlank { "?" }, ageBand8618(ageMin), nowMs)
    }

    private fun add(m: ConcurrentHashMap<String, Agg>, key: String, net: Double, pnl: Double, holdMs: Long) {
        val a = m.getOrPut(key) { Agg() }
        synchronized(a) {
            a.n++; if (net > 0.0) a.wins++; if (net >= 100.0) a.runners++; a.sum += net; a.pnl += pnl; a.holdMs += holdMs.coerceAtLeast(0L)
        }
    }

    /** MemePaperTrader8544.fill8544: the paper position on [mint] closed at [net] % ([pnlSol]) for [why]. */
    fun notePaperClose8618(mint: String, lane: String, net: Double, pnlSol: Double, why: String, holdMs: Long) {
        if (!net.isFinite()) return
        restore8744()
        val o = opens.remove(mint)
        add(byExit, "$lane|${why.substringBefore("_T").ifBlank { "?" }}", net, pnlSol, holdMs)
        if (o != null) {
            add(byVerdict, "$lane|${o.liveVerdict}", net, pnlSol, holdMs)
            add(byState, "$lane|phase=${o.phase}", net, pnlSol, holdMs)
            add(byState, "$lane|age=${o.ageBand}", net, pnlSol, holdMs)
        }
        save8744()
    }

    // ── V5.0.8744 — owner: "build both now" (the launch replay and the paper/live parity check). This report IS the parity
    // check (each paper close filed by LIVE's verdict on the same candidate) but it restarted empty with every process:
    // the 8739 capture held 2 closes after 8.5 min. The three records now persist and accumulate across restarts.
    @Volatile private var restored8744 = false
    private fun file8744(): java.io.File? = try {
        com.lifecyclebot.AATEApp.appContextOrNull()?.filesDir?.let { java.io.File(it, "tuning_8744.json") }
    } catch (_: Throwable) { null }

    /** Pure: the records as JSON. */
    private fun json8744(): org.json.JSONObject = org.json.JSONObject().apply {
        for ((name, m) in listOf("verdict" to byVerdict, "exit" to byExit, "state" to byState)) {
            val o = org.json.JSONObject()
            for ((k, a) in m) synchronized(a) {
                o.put(k, org.json.JSONArray().put(a.n).put(a.wins).put(a.runners).put(a.sum).put(a.pnl).put(a.holdMs))
            }
            put(name, o)
        }
    }

    private fun save8744() {
        val f = file8744() ?: return
        try { val tmp = java.io.File(f.parentFile, f.name + ".tmp"); tmp.writeText(json8744().toString()); if (!tmp.renameTo(f)) tmp.delete() } catch (_: Throwable) {}
    }

    @Synchronized private fun restore8744() {
        if (restored8744) return
        restored8744 = true
        val f = file8744()?.takeIf { it.exists() } ?: return
        try {
            val j = org.json.JSONObject(f.readText())
            for ((name, m) in listOf("verdict" to byVerdict, "exit" to byExit, "state" to byState)) {
                val o = j.optJSONObject(name) ?: continue
                for (k in o.keys()) {
                    val a = o.optJSONArray(k) ?: continue
                    m.getOrPut(k) { Agg() }.apply {
                        n += a.optInt(0); wins += a.optInt(1); runners += a.optInt(2); sum += a.optDouble(3, 0.0)
                        pnl += a.optDouble(4, 0.0); holdMs += a.optLong(5)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun rows(m: ConcurrentHashMap<String, Agg>, title: String): String {
        val sb = StringBuilder("  ").append(title).append('\n')
        val list = m.entries.map { (k, a) -> synchronized(a) { Triple(k, a.n, doubleArrayOf(a.sum, a.pnl, a.wins.toDouble(), a.runners.toDouble(), a.holdMs.toDouble())) } }
            .sortedWith(compareBy<Triple<String, Int, DoubleArray>> { it.first.substringBefore('|') }.thenByDescending { it.second })
        if (list.isEmpty()) return sb.append("    (no paper closes yet this session)\n").toString()
        for ((k, n, v) in list) {
            sb.append("    ").append(k.padEnd(52)).append(" n=").append(n)
                .append(" mean=").append("%+.1f".format(v[0] / n)).append('%')
                .append(" wr=").append("%.0f".format(100.0 * v[2] / n)).append('%')
                .append(" run>=100%=").append(v[3].toInt())
                .append(" pnl=").append("%+.3f".format(v[1])).append("SOL")
                .append(" hold=").append("%.1f".format(v[4] / n / 60_000.0)).append("m\n")
        }
        return sb.toString()
    }

    /** The report section. */
    fun section8618(): String = buildString {
        restore8744()
        append("  READ       paper trades every meme candidate the paper way; each close is filed by what LIVE decided on that same\n")
        append("             candidate. A LIVE_REFUSE row with a positive mean is a live gate costing money; a negative one is a gate saving it.\n")
        append(rows(byVerdict, "PAPER RESULT BY LIVE VERDICT (lane | live verdict on the same candidate)"))
        append(rows(byExit, "PAPER EXITS (lane | exit reason)"))
        append(rows(byState, "PAPER ENTRY STATE (lane | phase / age at entry)"))
        append("  OPEN       paper positions awaiting close with a live verdict noted: ").append(opens.size).append('\n')
    }
}

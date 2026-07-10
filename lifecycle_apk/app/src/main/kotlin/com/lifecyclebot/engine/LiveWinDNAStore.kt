package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * LIVE WIN DNA STORE — V5.0.6238
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Captures the FULL fingerprint of every winning close so the AGI / LLM /
 * super-AGI / meta-cog / sentience brains have a shared, transferable
 * knowledge base of "what actually made money" — patterns, chart behaviour,
 * entries, exits, strategies, where profit lays.
 *
 * DOCTRINE:
 *   • Not a gate. Never blocks a trade. Purely knowledge capture + advisory.
 *   • Any brain layer can query top-K winning fingerprints for its own bias.
 *   • Persists across restarts (SharedPreferences JSON, capped at MAX_ROWS).
 *   • Fed automatically from TokenWinMemory.recordTradeOutcome on every win.
 *
 * The intent per operator directive: transfer good learning + good behaviour
 * so paper-mode wins compound into a live-mode advantage.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object LiveWinDNAStore {

    private const val TAG = "LiveWinDNAStore"
    private const val PREFS_NAME = "live_win_dna_v6238"
    private const val KEY_ROWS = "rows"
    private const val MAX_ROWS = 500   // cap the corpus so persistence stays cheap

    data class WinDNA(
        val mint: String,
        val symbol: String,
        val lane: String,
        val source: String,
        val phase: String,
        val entrySetup: String,        // e.g. degen_micro_snipe / liquidity_depth_quality
        val chartPattern: String,      // e.g. accumulation_compression / fresh_pool_momentum
        val entryScore: Int,
        val entryMcap: Double,
        val exitMcap: Double,
        val entryLiquidity: Double,
        val holdTimeMinutes: Int,
        val buyPercent: Double,
        val pnlPct: Double,
        val peakPnl: Double,
        val exitReason: String,        // REALIZED_WIN_AFTER_PROFIT_SIGNAL etc.
        val paperOrLive: String,       // "PAPER" or "LIVE"
        val ts: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("mint", mint); put("symbol", symbol); put("lane", lane)
            put("source", source); put("phase", phase); put("entrySetup", entrySetup)
            put("chartPattern", chartPattern); put("entryScore", entryScore)
            put("entryMcap", entryMcap); put("exitMcap", exitMcap)
            put("entryLiquidity", entryLiquidity); put("holdTimeMinutes", holdTimeMinutes)
            put("buyPercent", buyPercent); put("pnlPct", pnlPct); put("peakPnl", peakPnl)
            put("exitReason", exitReason); put("paperOrLive", paperOrLive); put("ts", ts)
        }
        companion object {
            fun fromJson(o: JSONObject): WinDNA = WinDNA(
                mint = o.optString("mint"), symbol = o.optString("symbol"),
                lane = o.optString("lane"), source = o.optString("source"),
                phase = o.optString("phase"), entrySetup = o.optString("entrySetup"),
                chartPattern = o.optString("chartPattern"),
                entryScore = o.optInt("entryScore", 0),
                entryMcap = o.optDouble("entryMcap", 0.0), exitMcap = o.optDouble("exitMcap", 0.0),
                entryLiquidity = o.optDouble("entryLiquidity", 0.0),
                holdTimeMinutes = o.optInt("holdTimeMinutes", 0),
                buyPercent = o.optDouble("buyPercent", 0.0),
                pnlPct = o.optDouble("pnlPct", 0.0), peakPnl = o.optDouble("peakPnl", 0.0),
                exitReason = o.optString("exitReason"),
                paperOrLive = o.optString("paperOrLive", "PAPER"),
                ts = o.optLong("ts", 0L),
            )
        }
    }

    private var ctx: Context? = null
    private var prefs: SharedPreferences? = null
    private val rows = ConcurrentHashMap<String, WinDNA>()    // key = "mint:ts"
    private val loaded = AtomicLong(0L)

    fun init(context: Context) {
        ctx = context.applicationContext
        prefs = ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        load()
        try { ErrorLogger.info(TAG, "🧬 LiveWinDNAStore initialised — ${rows.size} winning fingerprints loaded") } catch (_: Throwable) {}
    }

    /** Called from TokenWinMemory on every WINNING close. */
    fun capture(
        mint: String, symbol: String, lane: String, source: String, phase: String,
        entrySetup: String, chartPattern: String, entryScore: Int,
        entryMcap: Double, exitMcap: Double, entryLiquidity: Double,
        holdTimeMinutes: Int, buyPercent: Double,
        pnlPct: Double, peakPnl: Double, exitReason: String, paperOrLive: String,
    ) {
        if (mint.isBlank() || pnlPct <= 0.0) return
        val now = System.currentTimeMillis()
        val dna = WinDNA(
            mint = mint, symbol = symbol, lane = lane, source = source, phase = phase,
            entrySetup = entrySetup, chartPattern = chartPattern, entryScore = entryScore,
            entryMcap = entryMcap, exitMcap = exitMcap, entryLiquidity = entryLiquidity,
            holdTimeMinutes = holdTimeMinutes, buyPercent = buyPercent,
            pnlPct = pnlPct, peakPnl = peakPnl, exitReason = exitReason,
            paperOrLive = paperOrLive, ts = now,
        )
        rows["${mint}:${now}"] = dna
        trimIfOverCap()
        persist()
        try { ForensicLogger.lifecycle("LIVE_WIN_DNA_CAPTURED_6238", "sym=$symbol lane=$lane setup=$entrySetup pnl=${"%.1f".format(pnlPct)}% mode=$paperOrLive") } catch (_: Throwable) {}
    }

    /** Best K winning fingerprints by PnL — for AGI/LLM query. */
    fun topByPnl(k: Int = 25): List<WinDNA> =
        rows.values.sortedByDescending { it.pnlPct }.take(k)

    /** Winning-setup histogram (setup name → count, avgPnl). AGI layers use this. */
    fun setupFrequency(minCount: Int = 2): List<Triple<String, Int, Double>> {
        val map = HashMap<String, MutableList<Double>>()
        rows.values.forEach { map.getOrPut(it.entrySetup) { mutableListOf() }.add(it.pnlPct) }
        return map.filter { it.value.size >= minCount }
            .map { (k, v) -> Triple(k, v.size, v.average()) }
            .sortedByDescending { it.second }
    }

    /** Winning chart-pattern histogram. Chart brains use this. */
    fun chartPatternFrequency(minCount: Int = 2): List<Triple<String, Int, Double>> {
        val map = HashMap<String, MutableList<Double>>()
        rows.values.forEach { map.getOrPut(it.chartPattern) { mutableListOf() }.add(it.pnlPct) }
        return map.filter { it.value.size >= minCount }
            .map { (k, v) -> Triple(k, v.size, v.average()) }
            .sortedByDescending { it.second }
    }

    /** Winning source × lane × phase route histogram. LLM/meta-cog use this. */
    fun routeFrequency(minCount: Int = 2): List<Triple<String, Int, Double>> {
        val map = HashMap<String, MutableList<Double>>()
        rows.values.forEach {
            val k = "${it.source}|${it.lane}|${it.phase}"
            map.getOrPut(k) { mutableListOf() }.add(it.pnlPct)
        }
        return map.filter { it.value.size >= minCount }
            .map { (k, v) -> Triple(k, v.size, v.average()) }
            .sortedByDescending { it.second }
    }

    /** Hold-time distribution of winners — exit optimisers use this. */
    fun holdTimeStats(): Triple<Int, Int, Int> {
        if (rows.isEmpty()) return Triple(0, 0, 0)
        val holds = rows.values.map { it.holdTimeMinutes }.sorted()
        val p50 = holds[holds.size / 2]
        val p75 = holds[(holds.size * 3) / 4]
        val p90 = holds[(holds.size * 9) / 10]
        return Triple(p50, p75, p90)
    }

    /** Exit-reason distribution among winners — sell-brain uses this. */
    fun winningExitReasons(): List<Pair<String, Int>> {
        val map = HashMap<String, Int>()
        rows.values.forEach { map[it.exitReason] = (map[it.exitReason] ?: 0) + 1 }
        return map.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    /** One-line snapshot for the operational report. */
    fun statusLine(): String {
        if (rows.isEmpty()) return "V5.0.6238_LIVE_WIN_DNA: rows=0 (bootstrap — need one winning close)"
        val n = rows.size
        val avgPnl = rows.values.map { it.pnlPct }.average()
        val topSetup = setupFrequency(1).firstOrNull()?.let { "${it.first}(n=${it.second}, μ=${"%.1f".format(it.third)}%)" } ?: "none"
        val topPattern = chartPatternFrequency(1).firstOrNull()?.let { "${it.first}(n=${it.second})" } ?: "none"
        val (p50, p75, p90) = holdTimeStats()
        return "V5.0.6238_LIVE_WIN_DNA: rows=$n avgWin=${"%.1f".format(avgPnl)}% topSetup=$topSetup topPattern=$topPattern hold_p50/p75/p90=${p50}/${p75}/${p90}m"
    }

    /** Multi-line detail block for the operational report. */
    fun reportBlock(): String {
        if (rows.isEmpty()) return "  (no winning fingerprints captured yet)"
        val sb = StringBuilder()
        sb.appendLine("  Setups (top 5):")
        setupFrequency(1).take(5).forEach { (k, n, avg) ->
            sb.appendLine("    ${k.padEnd(30)} n=$n μ=${"%+.1f".format(avg)}%")
        }
        sb.appendLine("  Chart patterns (top 5):")
        chartPatternFrequency(1).take(5).forEach { (k, n, avg) ->
            sb.appendLine("    ${k.padEnd(30)} n=$n μ=${"%+.1f".format(avg)}%")
        }
        sb.appendLine("  Routes (top 5):")
        routeFrequency(1).take(5).forEach { (k, n, avg) ->
            sb.appendLine("    ${k.take(60).padEnd(60)} n=$n μ=${"%+.1f".format(avg)}%")
        }
        sb.appendLine("  Winning exit reasons: " + winningExitReasons().take(4).joinToString(", ") { "${it.first}(${it.second})" })
        return sb.toString().trimEnd()
    }

    // ─── persistence ─────────────────────────────────────────────────────────
    private fun persist() {
        val p = prefs ?: return
        try {
            val arr = JSONArray()
            rows.values.sortedByDescending { it.ts }.take(MAX_ROWS).forEach { arr.put(it.toJson()) }
            p.edit().putString(KEY_ROWS, arr.toString()).apply()
        } catch (t: Throwable) {
            try { ErrorLogger.warn(TAG, "persist failed: ${t.message}") } catch (_: Throwable) {}
        }
    }

    private fun load() {
        val p = prefs ?: return
        try {
            val raw = p.getString(KEY_ROWS, null) ?: return
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val d = WinDNA.fromJson(arr.getJSONObject(i))
                rows["${d.mint}:${d.ts}"] = d
            }
            loaded.set(System.currentTimeMillis())
        } catch (_: Throwable) {}
    }

    private fun trimIfOverCap() {
        if (rows.size <= MAX_ROWS) return
        val oldest = rows.values.sortedBy { it.ts }.take(rows.size - MAX_ROWS)
        oldest.forEach { rows.remove("${it.mint}:${it.ts}") }
    }

    fun size(): Int = rows.size
    fun reset() { rows.clear(); persist() }
}

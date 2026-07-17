package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * LIVE WIN DNA STORE — V5.0.6238 (V5.0.6244 perf pass)
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
 * V5.0.6244 — performance triage after "hundreds of ANRs over hours":
 *   • topByPnl() now returns a memoised snapshot (invalidated on capture());
 *     the previous impl sorted 500 rows on every call site, and LaneBucketPivot
 *     was calling it on every lane eval.
 *   • persist() is now debounced through an IO-scoped coroutine so a hot
 *     streak of wins can no longer stall the caller thread.
 *   • beginBulk()/endBulk() suppresses the 500× persist storm the BotService
 *     backfill triggered at boot.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object LiveWinDNAStore {

    private const val TAG = "LiveWinDNAStore"
    private const val PREFS_NAME = "live_win_dna_v6238"
    private const val KEY_ROWS = "rows"
    private const val MAX_ROWS = 500   // cap the corpus so persistence stays cheap
    private const val PERSIST_DEBOUNCE_MS = 5_000L

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

    // V5.0.6244 — bulk-load guard and debounced persist state
    private val bulkLoadDepth = java.util.concurrent.atomic.AtomicInteger(0)
    private val persistDirty = AtomicBoolean(false)
    private val persistJob = AtomicReference<Job?>(null)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // V5.0.6244 — memoised top-by-pnl snapshot (invalidated on capture()).
    // LaneBucketPivot/AGI layers query top-K on every lane eval; the prior
    // impl sorted 500 rows per call.
    private val topSortedSnapshot = AtomicReference<List<WinDNA>?>(null)

    fun init(context: Context) {
        ctx = context.applicationContext
        prefs = ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        load()
        invalidateSnapshots()
        try { ErrorLogger.info(TAG, "🧬 LiveWinDNAStore initialised — ${rows.size} winning fingerprints loaded") } catch (_: Throwable) {}
    }

    /**
     * V5.0.6244 — suspend persist() during a bulk hydrate/backfill.
     * BotService.onCreate backfills 500 winners in a tight loop; before this
     * guard, each capture() persisted the entire 500-row JSON blob on the
     * caller thread, stalling app start.
     */
    fun beginBulk() { bulkLoadDepth.incrementAndGet() }
    fun endBulk() {
        if (bulkLoadDepth.decrementAndGet() <= 0) {
            bulkLoadDepth.set(0)
            invalidateSnapshots()
            schedulePersist()
        }
    }

    /** Called from TokenWinMemory on EVERY decisive close (wins + losses).
     *  V5.0.6258 — PAPER→LIVE AGI REWIRE. Prior impl bailed on losses
     *  (`pnlPct <= 0.0`), so the DNA store only ever saw winners and could
     *  never learn what SHAPE a losing trade takes vs a winner. Losing
     *  patterns are 5x more valuable than winning ones because they tell
     *  the bot what to AVOID. Op-report V5.0.6257 showed 500 rows but only
     *  2 real wins captured — everything else was backfill garbage. Now
     *  the store journals both classes; downstream aggregators split by
     *  pnlPct sign. */
    fun capture(
        mint: String, symbol: String, lane: String, source: String, phase: String,
        entrySetup: String, chartPattern: String, entryScore: Int,
        entryMcap: Double, exitMcap: Double, entryLiquidity: Double,
        holdTimeMinutes: Int, buyPercent: Double,
        pnlPct: Double, peakPnl: Double, exitReason: String, paperOrLive: String,
    ) {
        if (mint.isBlank()) return
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
        invalidateSnapshots()
        if (bulkLoadDepth.get() <= 0) schedulePersist()
        try { ForensicLogger.lifecycle("LIVE_WIN_DNA_CAPTURED_6238", "sym=$symbol lane=$lane setup=$entrySetup pnl=${"%.1f".format(pnlPct)}% mode=$paperOrLive") } catch (_: Throwable) {}
    }

    /**
     * V5.0.6251 — BACKFILL FILTER. Report 6249 showed 490/500 rows were
     * synthetic `backfill` entrySetup rows, poisoning topSetup, hold_p50/p75/p90
     * and every aggregator that reads real DNA. Filter them out at every
     * aggregator so percentiles and setup histograms only reflect REAL live/paper
     * wins. Rows are retained on disk (may still count toward volume) but do
     * not contribute to shape learning.
     * V5.0.6254 — ANR RE-TRIAGE. The initial impl reallocated a filtered list
     * on every aggregator call (setupFrequency + chartPatternFrequency +
     * routeFrequency + holdTimeStats + winningExitReasons + statusLine each
     * ran a fresh `rows.values.filter { ... }` per paint). UI paints × 6
     * aggregators × 500 rows = the ANR storm the operator flagged. Now
     * memoised alongside topSortedSnapshot and invalidated on the same
     * write paths (capture / init / clear / prune).
     */
    private val realRowsSnapshot = AtomicReference<List<WinDNA>?>(null)

    private fun realRows(): List<WinDNA> {
        realRowsSnapshot.get()?.let { return it }
        val fresh = rows.values.filter {
            val setup = it.entrySetup.lowercase()
            val pattern = it.chartPattern.lowercase()
            val src = it.source.lowercase()
            !(setup.contains("backfill") || pattern.contains("backfill") || src.contains("backfill"))
        }
        realRowsSnapshot.compareAndSet(null, fresh)
        return fresh
    }

    /**
     * Best K winning fingerprints by PnL — for AGI/LLM query.
     * V5.0.6244 — returns a memoised sorted snapshot; only rebuilt on
     * capture() / init(). LaneBucketPivot.sizeShape() is called per lane
     * eval and used to sort 500 rows every time.
     * V5.0.6251 — skip backfill-synthetic rows.
     */
    fun topByPnl(k: Int = 25): List<WinDNA> {
        val cached = topSortedSnapshot.get()
        val list = if (cached != null) cached else {
            val fresh = realRows().sortedByDescending { it.pnlPct }
            topSortedSnapshot.compareAndSet(null, fresh)
            fresh
        }
        return if (k >= list.size) list else list.take(k)
    }

    /** Winning-setup histogram (setup name → count, avgPnl). AGI layers use this.
     *  V5.0.6258 — filters to WINNING rows only (pnlPct > 0). */
    fun setupFrequency(minCount: Int = 2): List<Triple<String, Int, Double>> {
        val map = HashMap<String, MutableList<Double>>()
        realRows().filter { it.pnlPct > 0.0 }.forEach { map.getOrPut(it.entrySetup) { mutableListOf() }.add(it.pnlPct) }
        return map.filter { it.value.size >= minCount }
            .map { (k, v) -> Triple(k, v.size, v.average()) }
            .sortedByDescending { it.second }
    }

    /** V5.0.6258 — LOSING-setup histogram. The AGI must learn what NOT to trade,
     *  not just what worked. Losing patterns are 5x more informative than winners
     *  because they define the AVOID surface for live-trade shape. */
    fun losingSetupFrequency(minCount: Int = 2): List<Triple<String, Int, Double>> {
        val map = HashMap<String, MutableList<Double>>()
        realRows().filter { it.pnlPct < 0.0 }.forEach { map.getOrPut(it.entrySetup) { mutableListOf() }.add(it.pnlPct) }
        return map.filter { it.value.size >= minCount }
            .map { (k, v) -> Triple(k, v.size, v.average()) }
            .sortedByDescending { it.second }
    }

    /** Winning chart-pattern histogram. Chart brains use this.
     *  V5.0.6258 — filters to WINNING rows only. */
    fun chartPatternFrequency(minCount: Int = 2): List<Triple<String, Int, Double>> {
        val map = HashMap<String, MutableList<Double>>()
        realRows().filter { it.pnlPct > 0.0 }.forEach { map.getOrPut(it.chartPattern) { mutableListOf() }.add(it.pnlPct) }
        return map.filter { it.value.size >= minCount }
            .map { (k, v) -> Triple(k, v.size, v.average()) }
            .sortedByDescending { it.second }
    }

    /** V5.0.6258 — LOSING chart-pattern histogram. */
    fun losingChartPatternFrequency(minCount: Int = 2): List<Triple<String, Int, Double>> {
        val map = HashMap<String, MutableList<Double>>()
        realRows().filter { it.pnlPct < 0.0 }.forEach { map.getOrPut(it.chartPattern) { mutableListOf() }.add(it.pnlPct) }
        return map.filter { it.value.size >= minCount }
            .map { (k, v) -> Triple(k, v.size, v.average()) }
            .sortedByDescending { it.second }
    }

    /** Winning source × lane × phase route histogram. LLM/meta-cog use this. */
    fun routeFrequency(minCount: Int = 2): List<Triple<String, Int, Double>> {
        val map = HashMap<String, MutableList<Double>>()
        realRows().forEach {
            val k = "${it.source}|${it.lane}|${it.phase}"
            map.getOrPut(k) { mutableListOf() }.add(it.pnlPct)
        }
        return map.filter { it.value.size >= minCount }
            .map { (k, v) -> Triple(k, v.size, v.average()) }
            .sortedByDescending { it.second }
    }

    /** Hold-time distribution of winners — exit optimisers use this. */
    fun holdTimeStats(): Triple<Int, Int, Int> {
        val real = realRows()
        if (real.isEmpty()) return Triple(0, 0, 0)
        val holds = real.map { it.holdTimeMinutes }.sorted()
        val p50 = holds[holds.size / 2]
        val p75 = holds[(holds.size * 3) / 4]
        val p90 = holds[(holds.size * 9) / 10]
        return Triple(p50, p75, p90)
    }

    /** Exit-reason distribution among winners — sell-brain uses this.
     *  V5.0.6258 — winners only (pnlPct > 0). */
    fun winningExitReasons(): List<Pair<String, Int>> {
        val map = HashMap<String, Int>()
        realRows().filter { it.pnlPct > 0.0 }.forEach { map[it.exitReason] = (map[it.exitReason] ?: 0) + 1 }
        return map.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    /** V5.0.6258 — LOSING exit-reasons. Tells the sell-brain which exit
     *  reasons correlate with LOSSES so it can avoid or refactor them. */
    fun losingExitReasons(): List<Pair<String, Int>> {
        val map = HashMap<String, Int>()
        realRows().filter { it.pnlPct < 0.0 }.forEach { map[it.exitReason] = (map[it.exitReason] ?: 0) + 1 }
        return map.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    /** One-line snapshot for the operational report. */
    fun statusLine(): String {
        val real = realRows()
        if (rows.isEmpty()) return "V5.0.6258_LIVE_WIN_DNA: rows=0 (bootstrap — need one decisive close)"
        val n = rows.size
        val nReal = real.size
        val winners = real.filter { it.pnlPct > 0.0 }
        val losers  = real.filter { it.pnlPct < 0.0 }
        val avgWin  = if (winners.isNotEmpty()) winners.map { it.pnlPct }.average() else 0.0
        val avgLoss = if (losers.isNotEmpty())  losers.map { it.pnlPct }.average() else 0.0
        val topWinSetup  = setupFrequency(1).firstOrNull()?.let { "${it.first}(n=${it.second}, μ=${"%.1f".format(it.third)}%)" } ?: "none"
        val topLossSetup = losingSetupFrequency(1).firstOrNull()?.let { "${it.first}(n=${it.second}, μ=${"%.1f".format(it.third)}%)" } ?: "none"
        val topPattern = chartPatternFrequency(1).firstOrNull()?.let { "${it.first}(n=${it.second})" } ?: "none"
        val (p50, p75, p90) = holdTimeStats()
        return "V5.0.6258_LIVE_WIN_DNA: rows=$n real=$nReal W/L=${winners.size}/${losers.size} avgWin=${"%.1f".format(avgWin)}% avgLoss=${"%.1f".format(avgLoss)}% topWinSetup=$topWinSetup topLossSetup=$topLossSetup topPattern=$topPattern hold_p50/p75/p90=${p50}/${p75}/${p90}m"
    }

    /** Multi-line detail block for the operational report. */
    fun reportBlock(): String {
        if (rows.isEmpty()) return "  (no decisive fingerprints captured yet)"
        val sb = StringBuilder()
        sb.appendLine("  Winning setups (top 5):")
        setupFrequency(1).take(5).forEach { (k, n, avg) ->
            sb.appendLine("    ${k.padEnd(30)} n=$n μ=${"%+.1f".format(avg)}%")
        }
        sb.appendLine("  Losing setups (top 5, AVOID):")
        losingSetupFrequency(1).take(5).forEach { (k, n, avg) ->
            sb.appendLine("    ${k.padEnd(30)} n=$n μ=${"%+.1f".format(avg)}%")
        }
        sb.appendLine("  Winning chart patterns (top 5):")
        chartPatternFrequency(1).take(5).forEach { (k, n, avg) ->
            sb.appendLine("    ${k.padEnd(30)} n=$n μ=${"%+.1f".format(avg)}%")
        }
        sb.appendLine("  Losing chart patterns (top 5, AVOID):")
        losingChartPatternFrequency(1).take(5).forEach { (k, n, avg) ->
            sb.appendLine("    ${k.padEnd(30)} n=$n μ=${"%+.1f".format(avg)}%")
        }
        sb.appendLine("  Routes (top 5):")
        routeFrequency(1).take(5).forEach { (k, n, avg) ->
            sb.appendLine("    ${k.take(60).padEnd(60)} n=$n μ=${"%+.1f".format(avg)}%")
        }
        sb.appendLine("  Winning exit reasons: " + winningExitReasons().take(4).joinToString(", ") { "${it.first}(${it.second})" })
        sb.appendLine("  Losing exit reasons: " + losingExitReasons().take(4).joinToString(", ") { "${it.first}(${it.second})" })
        return sb.toString().trimEnd()
    }

    // ─── persistence ─────────────────────────────────────────────────────────

    /**
     * V5.0.6244 — mark dirty and schedule ONE debounced write. Coalesces
     * the previous "persist on every capture()" storm into a single IO
     * write every PERSIST_DEBOUNCE_MS.
     */
    private fun schedulePersist() {
        persistDirty.set(true)
        val existing = persistJob.get()
        if (existing != null && existing.isActive) return
        val job = scope.launch {
            try {
                delay(PERSIST_DEBOUNCE_MS)
                if (persistDirty.compareAndSet(true, false)) {
                    persistNow()
                }
            } finally {
                persistJob.compareAndSet(persistJob.get(), null)
            }
        }
        persistJob.set(job)
    }

    private fun persistNow() {
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

    private fun invalidateSnapshots() {
        topSortedSnapshot.set(null)
        realRowsSnapshot.set(null)
    }

    fun size(): Int = rows.size

    /** V5.0.6285 — public accessor for the *real* (non-backfill) row count.
     *  Executor uses this to bootstrap-gate the DNA_PROVEN_LOSER_VETO so a
     *  tiny 7-row DNA snapshot cannot lock the whole live pipeline out of
     *  buys. Uses the cached realRowsSnapshot when available; O(1). */
    fun realCount(): Int = realRows().size
    fun reset() {
        rows.clear()
        invalidateSnapshots()
        schedulePersist()
    }
}

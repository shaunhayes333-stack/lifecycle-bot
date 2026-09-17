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
            // V5.0.6925 — ALSO CHECK exitReason.
            //
            // The V5.0.6251 filter looks for "backfill" in entrySetup,
            // chartPattern and source. The V5.0.6285 paper-history backfill at
            // BotService:6906 writes rows whose setup is a phase or a
            // "LANE_MCAPBAND" proxy, whose pattern is an mcap band, and whose
            // source is the real source or "PAPER_HISTORY" — none of which
            // contain the word. Its marker is in exitReason:
            // "PAPER_HISTORY_BACKFILL_6285".
            //
            // So a second synthetic-row path was added after the filter and
            // slipped straight through it, back into exactly the aggregators
            // 6251 was written to protect. Checking exitReason closes it.
            //
            // NOT excluded here, deliberately: the historical/daily CORPUS
            // rows (paperOrLive == "CORPUS"). They are synthetic too, but
            // they exist on purpose to bootstrap the learners out of a cold
            // start, and pulling them out of every aggregator at once would
            // starve layers that have depended on them for builds. They ARE
            // excluded from the V5.0.6925 capture-ratio cohort, because their
            // peak is fabricated (peakPnl = pnlPct * 1.15) and would inject a
            // constant fake 87% capture that masks the real number.
            val reason = it.exitReason.lowercase()
            !(setup.contains("backfill") || pattern.contains("backfill") || src.contains("backfill") ||
              reason.contains("backfill"))
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

    /** V5.0.6286 — Winning LANE histogram. Coarser than setup, but survives
     *  the paper-backfill setup-namespace mismatch: even when backfilled
     *  winners are stamped with entrySetup="UNKNOWN", their LANE field
     *  (MOONSHOT/BLUECHIP/etc) is faithful. Executor DNA veto uses this
     *  as a fallback when setup-level lookup finds no winners. */
    fun laneFrequency(minCount: Int = 1): List<Triple<String, Int, Double>> {
        val map = HashMap<String, MutableList<Double>>()
        realRows().filter { it.pnlPct > 0.0 }.forEach { map.getOrPut(it.lane) { mutableListOf() }.add(it.pnlPct) }
        return map.filter { it.value.size >= minCount }
            .map { (k, v) -> Triple(k, v.size, v.average()) }
            .sortedByDescending { it.second }
    }

    /** V5.0.6286 — LOSING lane histogram, mirror of laneFrequency. */
    fun losingLaneFrequency(minCount: Int = 1): List<Triple<String, Int, Double>> {
        val map = HashMap<String, MutableList<Double>>()
        realRows().filter { it.pnlPct < 0.0 }.forEach { map.getOrPut(it.lane) { mutableListOf() }.add(it.pnlPct) }
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

    /**
     * Hold-time distribution across ALL decisive rows, winners and losers.
     *
     * V5.0.6920 — the docstring here used to read "Hold-time distribution of
     * winners — exit optimisers use this", and the body has never filtered to
     * winners. At the observed win rate these percentiles are dominated by
     * LOSERS, so anything that trusted the comment was reading the hold time
     * of failed trades and calling it the winning hold time — then shortening
     * holds toward it, which is the paper-hands loop.
     *
     * Kept as-is for the operator status line (a population figure is the
     * right thing to display). Anything making an EXIT-TIMING decision wants
     * winnerHoldTimeStats6920() below, which is what the old comment
     * described.
     */
    fun holdTimeStats(): Triple<Int, Int, Int> {
        val real = realRows()
        if (real.isEmpty()) return Triple(0, 0, 0)
        val holds = real.map { it.holdTimeMinutes }.sorted()
        val p50 = holds[holds.size / 2]
        val p75 = holds[(holds.size * 3) / 4]
        val p90 = holds[(holds.size * 9) / 10]
        return Triple(p50, p75, p90)
    }

    /**
     * V5.0.6920 — hold-time distribution of WINNERS only (pnlPct > 0), which
     * is what an exit optimiser actually needs: "how long did the trades that
     * worked need to be held". Mirrors setupFrequency/chartPatternFrequency,
     * which already filter to winners.
     *
     * Returns null rather than zeros when there is not enough winning
     * evidence to form a percentile — a caller must be able to tell "no data"
     * apart from "hold for zero minutes", and Triple(0,0,0) cannot express
     * that. MIN_WINNERS is deliberately small; the readers treat this as a
     * soft ceiling, not a trigger.
     */
    fun winnerHoldTimeStats6920(minWinners: Int = 6): Triple<Int, Int, Int>? {
        val winners = realRows().filter { it.pnlPct > 0.0 && it.holdTimeMinutes >= 0 }
        if (winners.size < minWinners) return null
        val holds = winners.map { it.holdTimeMinutes }.sorted()
        val p50 = holds[holds.size / 2]
        val p75 = holds[((holds.size * 3) / 4).coerceAtMost(holds.size - 1)]
        val p90 = holds[((holds.size * 9) / 10).coerceAtMost(holds.size - 1)]
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

    /* ===================== V5.0.6925 · CAPTURE RATIO ========================= */
    /*
     * The number that decides whether this bot makes money.
     *
     * A memecoin book does not get paid for being right. It gets paid for what
     * it COLLECTS when it is right. 13% win rate with +900% average winners is
     * a printer; 13% with +40% winners is a shredder. Same win rate, opposite
     * business. So "why is the win rate 13%" is the wrong question, and
     * chasing 60% would just mean taking +8% scalps and losing faster with a
     * prettier dashboard.
     *
     * The right question is: of the gain a position actually REACHED, how much
     * did we keep? That is capture ratio, and every input for it has been
     * sitting in these rows the whole time — peakPnl and pnlPct, on every
     * single close — completely unqueried.
     *
     * It matters because V5.0.6921 found PeakSlipExit6390 force-closing an
     * entire position on a 4% dip from a +1000% peak, and PeakAdaptiveTrail6390
     * firing its "hold 92% of peak" rule at 0.8% off the high. Both were unit
     * errors in wired code. Capture ratio is how we see whether that was THE
     * problem or just A problem, instead of arguing about it.
     *
     * It also settles a live disagreement empirically rather than by doctrine:
     * PeakAdaptiveTrail6390 tightens monotonically as a runner climbs, while
     * AdvancedExitManager.calculateProgressiveTrailingStop deliberately
     * re-loosens above +500% on the argument that a tight trail there "would
     * clip a runner before T2 (+1500%) ever fires". Capture ratio per exit
     * reason names which one is actually collecting.
     *
     * DESIGN NOTES, because each of these would otherwise produce a pretty lie:
     *
     *  - Cohort is every row that HAD something to capture (peak >= 20%), not
     *    just winners. A position that peaked +80% and closed at -10% is the
     *    worst capture case in the book; counting only winners would hide
     *    exactly the trades that need finding.
     *  - Peak is clamped to at least the realised gain. TokenWinMemory's own
     *    sanity check tolerates peakPnl up to 50 points BELOW pnlPercent, so
     *    the stored peak is not always clean, and a peak under the realised
     *    result is impossible in truth.
     *  - CORPUS rows are dropped. Their peak is fabricated as pnlPct * 1.15,
     *    which is a fixed 87% capture that would drown the real signal.
     *  - The headline is money-weighted, sum(captured)/sum(peak), not the mean
     *    of per-row ratios. A mean of ratios lets fifty +20% scalps outvote one
     *    +2000% runner, which is precisely backwards for a fat-tailed book.
     *    Median per-row is reported alongside it, because the gap between the
     *    two IS the story: money-weighted far below median means the big ones
     *    are the ones being clipped.
     */
    private const val CAPTURE_MIN_PEAK_PCT_6925 = 20.0
    private const val ROUND_TRIP_PEAK_PCT_6925 = 50.0

    data class CaptureStat6925(
        val label: String,
        val n: Int,
        val meanPeakPct: Double,
        val meanCapturedPct: Double,
        /** Money-weighted: sum(captured) / sum(peak) * 100. The headline. */
        val aggregateCapturePct: Double,
        /** Median of per-row captured/peak. Compare against the aggregate. */
        val medianCapturePct: Double,
        /** Peaked >= 50% and closed flat or negative. Pure give-back. */
        val roundTrips: Int,
    ) {
        val compact: String get() =
            "$label n=$n capture=${"%.0f".format(aggregateCapturePct)}% " +
            "(median ${"%.0f".format(medianCapturePct)}%) " +
            "peak=${"%.0f".format(meanPeakPct)}%→kept=${"%.0f".format(meanCapturedPct)}% " +
            "roundTrips=$roundTrips"
    }

    /** Effective peak — can never be below what was actually realised. */
    private fun effectivePeak6925(r: WinDNA): Double = maxOf(r.peakPnl, r.pnlPct)

    private fun captureCohort6925(): List<WinDNA> = realRows().filter {
        !it.paperOrLive.equals("CORPUS", true) &&
            it.pnlPct.isFinite() && it.peakPnl.isFinite() &&
            effectivePeak6925(it) >= CAPTURE_MIN_PEAK_PCT_6925
    }

    private fun captureStatOf6925(label: String, group: List<WinDNA>): CaptureStat6925? {
        if (group.isEmpty()) return null
        var sumPeak = 0.0
        var sumKept = 0.0
        val perRow = ArrayList<Double>(group.size)
        var roundTrips = 0
        for (r in group) {
            val peak = effectivePeak6925(r)
            if (peak <= 0.0) continue
            sumPeak += peak
            sumKept += r.pnlPct
            perRow.add(r.pnlPct / peak * 100.0)
            if (peak >= ROUND_TRIP_PEAK_PCT_6925 && r.pnlPct <= 0.0) roundTrips++
        }
        if (perRow.isEmpty() || sumPeak <= 0.0) return null
        perRow.sort()
        val median = perRow[perRow.size / 2]
        return CaptureStat6925(
            label = label,
            n = perRow.size,
            meanPeakPct = sumPeak / perRow.size,
            meanCapturedPct = sumKept / perRow.size,
            aggregateCapturePct = sumKept / sumPeak * 100.0,
            medianCapturePct = median,
            roundTrips = roundTrips,
        )
    }

    fun captureRatioOverall6925(): CaptureStat6925? =
        captureStatOf6925("ALL", captureCohort6925())

    fun captureRatioByLane6925(minN: Int = 3): List<CaptureStat6925> =
        captureCohort6925().groupBy { it.lane.ifBlank { "UNKNOWN" } }
            .mapNotNull { (lane, g) -> if (g.size < minN) null else captureStatOf6925(lane, g) }
            .sortedBy { it.aggregateCapturePct }

    /**
     * Capture ratio per exit reason, worst first — this names the guilty exit
     * path directly. winningExitReasons/losingExitReasons already counted which
     * reasons appear on wins and losses, but a count cannot tell you that
     * PEAK_SLIP_CUT_FULL fires on genuine winners and keeps 4% of them.
     */
    fun captureRatioByExitReason6925(minN: Int = 3): List<CaptureStat6925> =
        captureCohort6925().groupBy { it.exitReason.ifBlank { "UNKNOWN" }.take(40) }
            .mapNotNull { (reason, g) -> if (g.size < minN) null else captureStatOf6925(reason, g) }
            .sortedBy { it.aggregateCapturePct }

    /** Operator block. Worst capture first, because that is the actionable end. */
    fun captureRatioBlock6925(): String {
        val overall = captureRatioOverall6925()
            ?: return "V5.0.6925_CAPTURE_RATIO: no rows with peak >= ${CAPTURE_MIN_PEAK_PCT_6925.toInt()}% yet " +
                "(need closes that actually went somewhere before this means anything)"
        val sb = StringBuilder()
        sb.appendLine("===== V5.0.6925 · CAPTURE RATIO — of the gain we REACHED, how much did we KEEP? =====")
        sb.appendLine("  ${overall.compact}")
        if (overall.medianCapturePct - overall.aggregateCapturePct >= 15.0) {
            sb.appendLine("  ⚠ money-weighted capture is ${"%.0f".format(overall.medianCapturePct - overall.aggregateCapturePct)}pp " +
                "BELOW median → the BIGGEST runners are the ones being clipped, not the small ones")
        }
        val byLane = captureRatioByLane6925()
        if (byLane.isNotEmpty()) {
            sb.appendLine("  by lane (worst capture first):")
            byLane.take(8).forEach { sb.appendLine("    ${it.compact}") }
        }
        val byReason = captureRatioByExitReason6925()
        if (byReason.isNotEmpty()) {
            sb.appendLine("  by exit reason (worst capture first — this is the guilty exit path):")
            byReason.take(8).forEach { sb.appendLine("    ${it.compact}") }
        }
        return sb.toString()
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
        // V5.0.6925 — capture ratio on the headline. Win rate without capture
        // ratio is half a sentence: it says how often we were right and says
        // nothing about whether being right paid.
        val cap6925 = captureRatioOverall6925()?.let {
            " capture=${"%.0f".format(it.aggregateCapturePct)}%(n=${it.n},rt=${it.roundTrips})"
        } ?: " capture=n/a"
        return "V5.0.6258_LIVE_WIN_DNA: rows=$n real=$nReal W/L=${winners.size}/${losers.size} avgWin=${"%.1f".format(avgWin)}% avgLoss=${"%.1f".format(avgLoss)}%$cap6925 topWinSetup=$topWinSetup topLossSetup=$topLossSetup topPattern=$topPattern hold_p50/p75/p90=${p50}/${p75}/${p90}m"
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

    /** V5.0.6302 — Idempotent seed capture with a caller-supplied `ts`. Used
     *  by HistoricalCorpusSeeder so re-seeding on every boot upserts by the
     *  same synthetic key (mint:ts) instead of creating new rows every start
     *  and blowing past MAX_ROWS after a few days. Everything else matches
     *  capture() semantics — trim, invalidate, schedulePersist. */
    fun captureSeed(
        mint: String, symbol: String, lane: String, source: String, phase: String,
        entrySetup: String, chartPattern: String, entryScore: Int,
        entryMcap: Double, exitMcap: Double, entryLiquidity: Double,
        holdTimeMinutes: Int, buyPercent: Double,
        pnlPct: Double, peakPnl: Double, exitReason: String, paperOrLive: String,
        ts: Long,
    ) {
        if (mint.isBlank()) return
        val dna = WinDNA(
            mint = mint, symbol = symbol, lane = lane, source = source, phase = phase,
            entrySetup = entrySetup, chartPattern = chartPattern, entryScore = entryScore,
            entryMcap = entryMcap, exitMcap = exitMcap, entryLiquidity = entryLiquidity,
            holdTimeMinutes = holdTimeMinutes, buyPercent = buyPercent,
            pnlPct = pnlPct, peakPnl = peakPnl, exitReason = exitReason,
            paperOrLive = paperOrLive, ts = ts,
        )
        rows["${mint}:${ts}"] = dna
        trimIfOverCap()
        invalidateSnapshots()
        if (bulkLoadDepth.get() <= 0) schedulePersist()
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

package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8723 §THE_CELL_THAT_PAYS_IS_BOUGHT — owner: "its made money before theres no reason it shouldn't be now".
 *
 * The 8720 edge dataset (78,859 labelled decisions, 75 hours), one row per coin per 15 minutes, by where the coin was
 * minted x its age x its pool: 45 cells, 44 of them flat or losing, and one that pays —
 *   not pump.fun, under five minutes old, pool $10k-50k: 91 fillable decisions, +37 % banked each (+37 % in the first
 *   half of the run, +38 % in the second), half of them winners, one in five doubling inside 15 minutes, about 29 a day.
 * The bot judged every one of them and bought none live: each learned door (stage unread, at its high, launch stage
 * loses, setup cold, flow unobserved, chart economics) is graded on everything it refuses, and this cell is a few
 * coins an hour inside thousands, so no door's record ever shows it. MarketCohort8701's FRESH cohort (8717) could not
 * prove it either: it waits for 100 labels of a coin whose birth is chain-proven and splits the pool band in two.
 *
 * One record per origin x age band x pool band (the edge model's own edges), one label per coin per cell per 15
 * minutes, seeded from the device's own dataset and learned from every later label (EdgeModel8537.learn). The outcome
 * is what a position banks, read conservatively: the lane's stop caps a loss ([ManagedBands8719.managed8719]) unless
 * the label fell through twice that distance — a pulled pool does not fill a stop, so that label counts as it printed.
 * A cell is PAYING at [MIN_N]+ labels with the mean above zero by [PROOF_SE] standard errors (two, not one: 45 cells are
 * read at once, and at one the dataset also "proves" a thin outlier cell). The record decays, so a cell that stops
 * paying stops admitting.
 *
 * A paying cell is candidate-specific evidence (CandidateAdmission8136: the cell slot), and the entry-state selection
 * doors stand aside for its coins (LaneAuthority8577.memeEntryRefusal8626). Safety, sellability, LP ownership, the
 * route, the price freshness and the size checks are untouched.
 */
object PayingCell8723 {
    const val MIN_N = 30.0
    private const val PROOF_SE = 2.0
    private const val DECAY = PocketLedger8610.DECAY
    private const val REFILE_MS = 15L * 60_000L
    const val KIND_8723 = "MARKET_CELL"

    private class Cell { var n = 0.0; var sum = 0.0; var sq = 0.0; var ran = 0.0 }
    private val cells = ConcurrentHashMap<String, Cell>()
    private val lastFiled = ConcurrentHashMap<String, Long>()
    @Volatile private var ready = false
    @Volatile private var loading = false
    private val learned = AtomicLong(0L)
    private val carried = ConcurrentHashMap<String, AtomicLong>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "payingCell8723").apply { isDaemon = true } }

    /** Pure: the cell of a coin minted at [mint], [ageMin] minutes old, with a pool of [liqUsd]; null when either is unread. */
    fun key8723(mint: String, ageMin: Double, liqUsd: Double): String? {
        if (mint.isBlank() || !(ageMin.isFinite() && ageMin >= 0.0) || !(liqUsd.isFinite() && liqUsd > 0.0)) return null
        return MarketCohort8701.origin8701(mint) + "_A" + EdgeModel8537.binOf(ageMin, EdgeModel8537.AGE_EDGES) +
            "_L" + EdgeModel8537.binOf(liqUsd, EdgeModel8537.LIQ_EDGES)
    }

    /**
     * Pure: what a position with a [stopPct] stop and a [costPct] round trip banks of a label's [netPct], when a label that
     * fell through twice the stop's distance is a pull the stop never filled.
     */
    fun outcome8723(netPct: Double, stopPct: Double, costPct: Double): Double {
        val stop = if (stopPct.isFinite() && stopPct > 0.0) stopPct else 100.0
        val cost = if (costPct.isFinite() && costPct > 0.0) costPct else 0.0
        val distance = (stop + cost).coerceAtMost(100.0)
        return if (netPct <= -2.0 * distance) netPct.coerceAtLeast(-100.0) else ManagedBands8719.managed8719(netPct, stopPct, costPct)
    }

    /** Pure: a record of [n] labels (sum and sum of squares of the outcome) is proven to pay. */
    fun paying8723(n: Double, sum: Double, sq: Double): Boolean {
        if (!(n >= MIN_N)) return false
        val m = sum / n
        val se = kotlin.math.sqrt(((sq / n) - m * m).coerceAtLeast(0.0) / n)
        return m.isFinite() && se.isFinite() && m - PROOF_SE * se > 0.0
    }

    private fun stopOf(lane: String): Double = try { FieldManual7715.laneStopPct8392(lane) } catch (_: Throwable) { Double.NaN }

    private fun add(mint: String, key: String, atMs: Long, net: Double, stopPct: Double, costPct: Double) {
        val last = lastFiled["$mint|$key"]
        if (last != null && atMs >= last && atMs - last < REFILE_MS) return
        if (lastFiled.size > 20_000) lastFiled.entries.removeIf { atMs - it.value > REFILE_MS }
        lastFiled["$mint|$key"] = atMs
        val y = outcome8723(net, stopPct, costPct)
        val c = cells.getOrPut(key) { Cell() }
        synchronized(c) {
            c.n = c.n * DECAY + 1.0; c.sum = c.sum * DECAY + y; c.sq = c.sq * DECAY + y * y
            c.ran = c.ran * DECAY + (if (net >= 100.0) 1.0 else 0.0)
        }
        learned.incrementAndGet()
    }

    /** EdgeModel8537.learn: file one labelled decision. */
    fun learn8723(mint: String, lane: String, ageMin: Double, liqUsd: Double, net: Double, costPct: Double, atMs: Long) {
        if (!net.isFinite()) return
        val key = key8723(mint, ageMin, liqUsd) ?: return
        ensureLoaded()
        io.execute { try { add(mint, key, atMs, net, stopOf(lane.trim().uppercase()), costPct) } catch (_: Throwable) {} }
    }

    private fun recordOf(key: String): DoubleArray? {
        if (!ready) { ensureLoaded(); return null }
        val c = cells[key] ?: return null
        return synchronized(c) { doubleArrayOf(c.n, c.sum, c.sq, c.ran) }
    }

    /** Pure: the admission record of a cell's [r] = (n, sum, sum of squares, runners), when it is proven to pay. */
    fun evidenceOf8723(key: String, r: DoubleArray?): CandidateAdmission8136.Evidence8136? {
        if (r == null || r.size < 4 || !paying8723(r[0], r[1], r[2])) return null
        val m = r[1] / r[0]
        val se = kotlin.math.sqrt(((r[2] / r[0]) - m * m).coerceAtLeast(0.0) / r[0])
        // the admission reads mean - SE; the record is carried at the proof's own margin so its lower bound is the proven one
        return CandidateAdmission8136.Evidence8136(KIND_8723, key, r[0].toInt(), m, PROOF_SE * se, runnerRate = r[3] / r[0])
    }

    /** CandidateAdmission8136.candidateFor8136: [ts]'s cell as admission evidence when that cell is proven to pay, else null. */
    fun evidence8723(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): CandidateAdmission8136.Evidence8136? = try {
        val solana = try { com.lifecyclebot.network.HeliusSolanaScope7819.isSolanaMint7819(ts.mint) } catch (_: Throwable) { false }
        val raw = if (solana) EdgeModel8537.rawFor8610(ts, lane, nowMs) else null
        val key = raw?.let { key8723(ts.mint, it.ageMin, it.liq) }
        if (key == null) null else evidenceOf8723(key, recordOf(key))
    } catch (_: Throwable) { null }

    /** LaneAuthority8577: does [ts] sit in a paying cell now? Counted per lane when it does. */
    fun pays8723(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val e = evidence8723(ts, lane, nowMs) ?: return false
        carried.computeIfAbsent("${lane.trim().uppercase()}:${e.id}") { AtomicLong(0L) }.incrementAndGet()
        return true
    }

    private fun ensureLoaded() {
        if (ready || loading) return
        synchronized(this) { if (ready || loading) return; loading = true }
        io.execute {
            try { seedFromDataset(); ready = true }
            catch (_: Throwable) { try { PipelineHealthCollector.labelInc("PAYING_CELL_8723_LOAD_FAILED") } catch (_: Throwable) {} }
            finally { loading = false }
        }
    }

    /** Replay the device's own labelled decisions (the dataset EdgeModel8537 exports), oldest first as they were written. */
    private fun seedFromDataset() {
        val f = EdgeModel8537.datasetFile() ?: return
        if (!f.exists()) return
        fun d(o: JSONObject, k: String) = if (o.isNull(k)) Double.NaN else o.optDouble(k, Double.NaN)
        val stops = HashMap<String, Double>()
        f.forEachLine { line ->
            try {
                if (!line.contains("\"horizonMs\"")) return@forEachLine
                val o = JSONObject(line)
                if (!EdgeModel8537.replayAccept8642(o)) return@forEachLine
                val net = d(o, "net"); if (!net.isFinite()) return@forEachLine
                val mint = o.optString("mint")
                val key = key8723(mint, d(o, "age"), d(o, "liq")) ?: return@forEachLine
                val lane = o.optString("lane").trim().uppercase()
                add(mint, key, o.optLong("t", 0L), net, stops.getOrPut(lane) { stopOf(lane) }, d(o, "cost"))
            } catch (_: Throwable) {}
        }
    }

    fun statusLine8723(): String {
        val rows = cells.entries.map { (k, c) -> synchronized(c) { Triple(k, doubleArrayOf(c.n, c.sum, c.sq, c.ran), paying8723(c.n, c.sum, c.sq)) } }
        val paying = rows.filter { it.third }.sortedBy { it.first }.joinToString(",") { (k, r, _) ->
            "$k=n${r[0].toInt()}/${"%+.1f".format(r[1] / r[0])}%/run${(100.0 * r[3] / r[0]).toInt()}%" }.ifBlank { "-" }
        val used = carried.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        return "payingCell8723[ready=$ready cells=${rows.size} learned=${learned.get()} paying{$paying} carried{$used} rule=origin x age x pool, one label per coin per 15 min, loss capped at the stop unless it fell through twice, n>=${MIN_N.toInt()} mean-${PROOF_SE.toInt()}SE>0]"
    }

    internal fun resetForTest8723() { cells.clear(); lastFiled.clear(); carried.clear(); learned.set(0L); ready = true }
    internal fun addForTest8723(mint: String, ageMin: Double, liqUsd: Double, net: Double, atMs: Long, stopPct: Double = 35.0, costPct: Double = 4.0) {
        add(mint, key8723(mint, ageMin, liqUsd) ?: return, atMs, net, stopPct, costPct)
    }
    internal fun recordForTest8723(mint: String, ageMin: Double, liqUsd: Double): DoubleArray? = key8723(mint, ageMin, liqUsd)?.let { recordOf(it) }
}

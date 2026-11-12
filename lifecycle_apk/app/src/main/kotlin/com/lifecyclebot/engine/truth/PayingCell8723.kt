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
 * minted x its age x its pool: 45 cells, 44 of them flat or losing, and one that pays — not pump.fun, under five minutes
 * old, pool $10k-50k: 91 fillable decisions, +34.5 % net each at the label (+-11.7), in both halves of the run, half of
 * them winners, one in five doubling inside 15 minutes, about 29 a day. The bot judged every one and bought none live:
 * each learned door is graded on everything it refuses, and this cell is a few coins an hour inside thousands.
 *
 * V5.0.8724 §ONE_LANE_ONE_RECORD_NO_SIMULATED_STOP — owner's audit of 8723: "the cell identity does not include the
 * lane ... a simulated stop fill is not automatically a real stop fill". 8723 pooled every lane's label into one cell and
 * graded each with the labelling lane's stop, so one lane's outcomes could authorise another with a different horizon,
 * stop and cost. Now:
 *  - one record per LANE x origin x age band x pool band (the edge model's own edges): a lane is authorised only by its
 *    own labels, at its own horizon and cost, one label per coin per cell per 15 minutes;
 *  - the outcome is the label's executable net as it printed (a total loss is -100 %); no stop is assumed to fill. Only
 *    the gain is kept to the pocket cap, so one outlier cannot prove a cell;
 *  - PAYING at [MIN_N]+ labels with the mean above zero by two standard errors (many cells are read at once).
 * Same dataset, per lane, no stop: CORE n64 +47.5 % (lower bound +17.9), MANIPULATED n65 +49.0 (+19.7), MOONSHOT n62
 * +44.1 (+13.5), EXPRESS n61 +44.4 (+12.1), SHITCOIN n54 +40.9 (+6.1), CYCLIC n32 +63.7 (+22.7) are proven, each
 * positive in both halves; BLUECHIP, QUALITY, TREASURY, CASHGEN and PROJECT_SNIPER are not and stay unauthorised.
 *
 * Precedence (CandidateAdmission8136 / LaneAuthority8577): a lane's paying cell is positive lane-specific evidence about
 * a narrower group of coins than the lane's stage record or an origin cohort, so it answers those and the generic
 * unobserved-flow / unread-stage waits, and it is an admitting edge. Hard safety, structure, sellability, LP ownership,
 * executable economics, the coin's own chart shapes, the losing record of the setup it trades as, its timing band, the
 * cheat sheet, the lane's losing cell and the lane's realised live losses all outrank it. The record decays, so a cell
 * that stops paying stops admitting.
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

    /** Pure: [lane]'s cell of a coin minted at [mint], [ageMin] minutes old, with a pool of [liqUsd]; null when any is unread. */
    fun key8723(lane: String, mint: String, ageMin: Double, liqUsd: Double): String? {
        val l = lane.trim().uppercase()
        if (l.isBlank() || mint.isBlank() || !(ageMin.isFinite() && ageMin >= 0.0) || !(liqUsd.isFinite() && liqUsd > 0.0)) return null
        return l + "_" + MarketCohort8701.origin8701(mint) + "_A" + EdgeModel8537.binOf(ageMin, EdgeModel8537.AGE_EDGES) +
            "_L" + EdgeModel8537.binOf(liqUsd, EdgeModel8537.LIQ_EDGES)
    }

    /** Pure: the outcome a label of [netPct] is recorded as: as it printed, the gain kept to the pocket cap; no stop is assumed. */
    fun outcome8723(netPct: Double): Double = PocketLedger8610.managed8610(netPct).coerceAtLeast(-100.0)

    /** Pure: a record of [n] labels (sum and sum of squares of the outcome) is proven to pay. */
    fun paying8723(n: Double, sum: Double, sq: Double): Boolean {
        if (!(n >= MIN_N)) return false
        val m = sum / n
        val se = kotlin.math.sqrt(((sq / n) - m * m).coerceAtLeast(0.0) / n)
        return m.isFinite() && se.isFinite() && m - PROOF_SE * se > 0.0
    }

    private fun add(mint: String, key: String, atMs: Long, net: Double) {
        val last = lastFiled["$mint|$key"]
        if (last != null && atMs >= last && atMs - last < REFILE_MS) return
        if (lastFiled.size > 20_000) lastFiled.entries.removeIf { atMs - it.value > REFILE_MS }
        lastFiled["$mint|$key"] = atMs
        val y = outcome8723(net)
        val c = cells.getOrPut(key) { Cell() }
        synchronized(c) {
            c.n = c.n * DECAY + 1.0; c.sum = c.sum * DECAY + y; c.sq = c.sq * DECAY + y * y
            c.ran = c.ran * DECAY + (if (net >= 100.0) 1.0 else 0.0)
        }
        learned.incrementAndGet()
    }

    /** EdgeModel8537.learn: file one labelled decision. */
    fun learn8723(mint: String, lane: String, ageMin: Double, liqUsd: Double, net: Double, atMs: Long) {
        if (!net.isFinite()) return
        val key = key8723(lane, mint, ageMin, liqUsd) ?: return
        ensureLoaded()
        io.execute { try { add(mint, key, atMs, net) } catch (_: Throwable) {} }
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
        val key = raw?.let { key8723(it.lane, ts.mint, it.ageMin, it.liq) }
        if (key == null) null else evidenceOf8723(key, recordOf(key))
    } catch (_: Throwable) { null }

    /** LaneAuthority8577: does [ts] sit in a paying cell now? Counted per lane when it does. */
    fun pays8723(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val e = evidence8723(ts, lane, nowMs) ?: return false
        carried.computeIfAbsent(e.id) { AtomicLong(0L) }.incrementAndGet()
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
        f.forEachLine { line ->
            try {
                if (!line.contains("\"horizonMs\"")) return@forEachLine
                val o = JSONObject(line)
                if (!EdgeModel8537.replayAccept8642(o)) return@forEachLine
                val net = d(o, "net"); if (!net.isFinite()) return@forEachLine
                val mint = o.optString("mint")
                val key = key8723(o.optString("lane"), mint, d(o, "age"), d(o, "liq")) ?: return@forEachLine
                add(mint, key, o.optLong("t", 0L), net)
            } catch (_: Throwable) {}
        }
    }

    fun statusLine8723(): String {
        val rows = cells.entries.map { (k, c) -> synchronized(c) { Triple(k, doubleArrayOf(c.n, c.sum, c.sq, c.ran), paying8723(c.n, c.sum, c.sq)) } }
        val paying = rows.filter { it.third }.sortedBy { it.first }.joinToString(",") { (k, r, _) ->
            "$k=n${r[0].toInt()}/${"%+.1f".format(r[1] / r[0])}%/run${(100.0 * r[3] / r[0]).toInt()}%" }.ifBlank { "-" }
        val used = carried.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        return "payingCell8723[ready=$ready cells=${rows.size} learned=${learned.get()} paying{$paying} carried{$used} rule=lane x origin x age x pool, one label per coin per 15 min, net as printed (no stop assumed), n>=${MIN_N.toInt()} mean-${PROOF_SE.toInt()}SE>0]"
    }

    internal fun resetForTest8723() { cells.clear(); lastFiled.clear(); carried.clear(); learned.set(0L); ready = true }
    internal fun addForTest8723(lane: String, mint: String, ageMin: Double, liqUsd: Double, net: Double, atMs: Long) {
        add(mint, key8723(lane, mint, ageMin, liqUsd) ?: return, atMs, net)
    }
    internal fun recordForTest8723(lane: String, mint: String, ageMin: Double, liqUsd: Double): DoubleArray? = key8723(lane, mint, ageMin, liqUsd)?.let { recordOf(it) }
}

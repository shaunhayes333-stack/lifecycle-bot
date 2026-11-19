package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8875 §THE_LLM_EARNS_A_SEAT — owner, 20 Nov: "the llms are fine. but they needs to be proving a much much bigger
 * boost all round and should be helping a lot more than they do. especially live."
 *
 * Until now the analyst's answer (LlmAnalyst8746: the council's probability that the coin is up by more than the round trip
 * at the lane's label horizon) was one input of the Mind8746 stacker and nothing else: graded only by a Brier score, it
 * could never admit a coin, however right it was. And its answer was never written to the dataset, so no export could
 * show whether it was right.
 *
 * Now every labelled decision that carried an answer files its outcome under the lane and the answer's quarter of the
 * probability scale (p < 0.25, 0.25-0.5, 0.5-0.75, 0.75+), by the same rules as PayingCell8723: one record per LANE x band,
 * one label per coin per 15 minutes, decay 0.995 a label, net as printed with no stop assumed, the gain kept to the pocket
 * cap, PROVEN at 30+ labels with the mean above zero by two standard errors. No band is chosen by hand: whichever band of
 * a lane proves to pay is the one that admits. A coin whose current answer (cached 15 minutes) sits in its lane's proven
 * band carries that record as market-cell evidence in CandidateAdmission8136 — the same standing and the same limits as a
 * paying cell: hard safety, structure, sellability, the LP-lock rule, economics and the narrower losing records still
 * refuse; nothing is refused because of the LLM. The answer travels in the dataset row ("llmP") so the device's own
 * dataset seeds these records at start (DatasetReplay8863) and every export grades the analyst.
 */
object LlmBands8875 {
    const val KIND_8875 = "LLM_BAND"
    private const val MIN_N = PayingCell8723.MIN_N
    private const val PROOF_SE = 2.0
    private const val DECAY = PocketLedger8610.DECAY
    private const val REFILE_MS = 15L * 60_000L

    private class Cell { var n = 0.0; var sum = 0.0; var sq = 0.0; var ran = 0.0 }
    private val cells = ConcurrentHashMap<String, Cell>()
    private val lastFiled = ConcurrentHashMap<String, Long>()
    @Volatile private var ready = false
    @Volatile private var loading = false
    private val learned = AtomicLong(0L)
    private val served = ConcurrentHashMap<String, AtomicLong>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "llmBands8875").apply { isDaemon = true } }

    /** Pure: the quarter of the probability scale [p] sits in (0..3), or null when there is no answer. */
    fun band8875(p: Double?): Int? = if (p == null || !p.isFinite() || p < 0.0 || p > 1.0) null else minOf(3, (p * 4.0).toInt())

    /** Pure: the record of [lane]'s answers in [band]; null for a blank lane. */
    fun key8875(lane: String, band: Int): String? = lane.trim().uppercase().takeIf { it.isNotBlank() }?.let { "${it}_LLM$band" }

    /** Pure: the analyst's probability as Mind8746 froze it with a decision ([f] = Mind8746.features8746), or NaN when it had not answered. */
    fun frozenP8875(f: DoubleArray?): Double =
        if (f == null || f.size < 8 || f[7] <= 0.0 || !f[6].isFinite()) Double.NaN else (f[6] / 100.0 + 0.5).coerceIn(0.0, 1.0)

    private fun add(mint: String, key: String, atMs: Long, net: Double) {
        val last = lastFiled["$mint|$key"]
        if (last != null && atMs >= last && atMs - last < REFILE_MS) return
        if (lastFiled.size > 20_000) lastFiled.entries.removeIf { atMs - it.value > REFILE_MS }
        lastFiled["$mint|$key"] = atMs
        val y = PayingCell8723.outcome8723(net)
        val c = cells.getOrPut(key) { Cell() }
        synchronized(c) {
            c.n = c.n * DECAY + 1.0; c.sum = c.sum * DECAY + y; c.sq = c.sq * DECAY + y * y
            c.ran = c.ran * DECAY + (if (net >= 100.0) 1.0 else 0.0)
        }
        learned.incrementAndGet()
    }

    /** EdgeModel8537.learn: file one labelled decision whose frozen answer was [p]. */
    fun learn8875(mint: String, lane: String, p: Double, net: Double, atMs: Long) {
        if (!net.isFinite()) return
        val key = key8875(lane, band8875(p) ?: return) ?: return
        ensureLoaded()
        io.execute { try { add(mint, key, atMs, net) } catch (_: Throwable) {} }
    }

    private fun recordOf(key: String): DoubleArray? {
        if (!ready) { ensureLoaded(); return null }
        val c = cells[key] ?: return null
        return synchronized(c) { doubleArrayOf(c.n, c.sum, c.sq, c.ran) }
    }

    /** Pure: the admission record of a band's [r] = (n, sum, sum of squares, runners), when it is proven to pay. */
    fun evidenceOf8875(key: String, r: DoubleArray?): CandidateAdmission8136.Evidence8136? {
        if (r == null || r.size < 4 || !PayingCell8723.paying8723(r[0], r[1], r[2])) return null
        val m = r[1] / r[0]
        val se = kotlin.math.sqrt(((r[2] / r[0]) - m * m).coerceAtLeast(0.0) / r[0])
        return CandidateAdmission8136.Evidence8136(KIND_8875, key, r[0].toInt(), m, PROOF_SE * se, runnerRate = r[3] / r[0])
    }

    /** CandidateAdmission8136.candidateFor8136: [ts] in [lane] as evidence when its current answer sits in a proven band. */
    fun evidence8875(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): CandidateAdmission8136.Evidence8136? = try {
        val band = band8875(LlmAnalyst8746.prob8746(ts.mint, lane, nowMs))
        val key = band?.let { key8875(lane, it) }
        if (key == null) null else evidenceOf8875(key, recordOf(key))?.also { e -> served.computeIfAbsent(e.id) { AtomicLong(0L) }.incrementAndGet() }
    } catch (_: Throwable) { null }

    private fun ensureLoaded() {
        if (ready || loading) return
        synchronized(this) { if (ready || loading) return; loading = true }
        io.execute {
            // seeded by the one shared read of the dataset (DatasetReplay8863); a live label queued behind it is filed after.
            try {
                ready = DatasetReplay8863.await8863()
                if (!ready) PipelineHealthCollector.labelInc("LLM_BANDS_8875_LOAD_FAILED")
            }
            catch (_: Throwable) { try { PipelineHealthCollector.labelInc("LLM_BANDS_8875_LOAD_FAILED") } catch (_: Throwable) {} }
            finally { loading = false }
        }
    }

    /** DatasetReplay8863: one accepted row of the device's own labelled decisions (rows from 8875 on carry "llmP"). */
    internal fun seedRow8863(o: JSONObject) {
        fun d(k: String) = if (!o.has(k) || o.isNull(k)) Double.NaN else o.optDouble(k, Double.NaN)
        val net = d("net"); if (!net.isFinite()) return
        val key = key8875(o.optString("lane"), band8875(d("llmP")) ?: return) ?: return
        add(o.optString("mint"), key, o.optLong("t", 0L), net)
    }

    /** Brief: each lane's answer bands (n / mean / runner share, * = proven) and how often a proven band carried a coin. */
    fun statusLine8875(): String {
        val rows = cells.entries.map { (k, c) -> synchronized(c) { Triple(k, doubleArrayOf(c.n, c.sum, c.sq, c.ran), PayingCell8723.paying8723(c.n, c.sum, c.sq)) } }
            .sortedBy { it.first }
        val recs = rows.joinToString(",") { (k, r, p) ->
            "$k=n${r[0].toInt()}/${"%+.1f".format(r[1] / r[0])}%/run${(100.0 * r[3] / r[0]).toInt()}%${if (p) "*" else ""}" }.ifBlank { "-" }
        val used = served.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        return "llm8875[ready=$ready learned=${learned.get()} bands{$recs} carried{$used} " +
            "rule=the analyst's answer by quarter of p (LLM0 <0.25 .. LLM3 >=0.75), per lane, one label per coin per 15 min; " +
            "* = n>=${MIN_N.toInt()} mean-${PROOF_SE.toInt()}SE>0 (admits as market-cell evidence)]"
    }

    internal fun resetForTest8875() { cells.clear(); lastFiled.clear(); served.clear(); learned.set(0L); ready = true }
    internal fun addForTest8875(lane: String, p: Double, mint: String, net: Double, atMs: Long) {
        add(mint, key8875(lane, band8875(p) ?: return) ?: return, atMs, net)
    }
    internal fun recordForTest8875(lane: String, band: Int): DoubleArray? = key8875(lane, band)?.let { recordOf(it) }
}

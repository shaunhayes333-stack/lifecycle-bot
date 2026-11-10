package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8610 §THE_BOT_LEARNS_WHERE_THE_MONEY_IS — owner: "it has to learn to hunt better opportunities then. its not a Sniper
 * bot." 8609 hard-coded the one coin state the data paid on (EXPANDING). This replaces the rule with what the bot learns.
 *
 * Every forward-labelled decision (ForwardReturnLabeler7731 -> EdgeModel8537.learn) is filed into coin-state cells: each
 * banded feature (phase, age, liquidity, mcap, buy pressure, 5 m and 1 h move, distance from the peak, top holder, volume
 * acceleration) and every pair of them, market-wide and per lane. Each cell keeps a recency-weighted record (decay 0.995 per
 * observation) of the observed downside and conservatively capped upside. A coin is scored by all of its evidence (V5.0.8613:
 * the mean clipped t of its cells with n >= 40, see [callOf8610]).
 *
 * Replayed online over 24,427 labelled decisions with the label held back 5 minutes (as on the device): 302 picks across
 * every lane, +19.9 % +-5.1 (first half +17.9 %, second +20.7 %), runner-shaped (15 % of coins win, 9 of 102 coins >= +100 %;
 * +5.1 % without the best five coins); 70 % EXPANDING but also mature, post-pump and day-old pockets. Taking any positive
 * cell without the veto made ~0 %. Until the ledger is seeded, live falls back to the 8609 state rule.
 */
object PocketLedger8610 {
    const val N_MIN = 40.0
    const val DECAY = 0.995
    private const val MAX_RECENT_OBSERVATIONS = 32_768

    internal fun targetHorizon(lane: String): Long =
        com.lifecyclebot.engine.cortex.CortexForwardEpoch8521.run { targetHorizon(targetKind(lane)) }

    // Accessed only on the single ledger worker, including dataset replay.
    private val recent = LinkedHashMap<String, MutableSet<String>>()

    private class Cell { var n = 0.0; var sum = 0.0; var sq = 0.0 }
    private val cells = ConcurrentHashMap<String, Cell>()
    @Volatile private var ready = false
    @Volatile private var loading = false
    private val learned = AtomicLong(0L)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "pocket8610").apply { isDaemon = true } }

    /** Pure: the managed outcome a cell records — the observed downside and a conservative +400 % gain cap. */
    fun managed8610(net: Double): Double = minOf(net, 400.0)

    /** Pure: the banded features of an observation; an unobserved value is left out. */
    fun bands8610(liq: Double, mcap: Double, ageMin: Double, bp: Double, chg5m: Double, chg1h: Double, pxPeak: Double,
                  volAccel: Double, top: Double, phase: String): Map<String, String> {
        val b = LinkedHashMap<String, String>()
        if (phase.isNotBlank()) b["phase"] = phase.trim().uppercase()
        if (ageMin.isFinite()) b["age"] = when { ageMin < 1 -> "<1m"; ageMin < 5 -> "<5m"; ageMin < 30 -> "<30m"; ageMin < 180 -> "<3h"; ageMin < 1440 -> "<24h"; else -> ">=1d" }
        if (liq.isFinite()) b["liq"] = when { liq < 2e3 -> "<2k"; liq < 1e4 -> "<10k"; liq < 5e4 -> "<50k"; liq < 2.5e5 -> "<250k"; else -> ">=250k" }
        if (mcap.isFinite()) b["mcap"] = when { mcap < 1e4 -> "<10k"; mcap < 5e4 -> "<50k"; mcap < 2.5e5 -> "<250k"; mcap < 2e6 -> "<2M"; else -> ">=2M" }
        if (bp.isFinite()) b["bp"] = when { bp < 45 -> "<45"; bp < 55 -> "<55"; bp < 65 -> "<65"; bp < 80 -> "<80"; else -> ">=80" }
        if (chg5m.isFinite()) b["chg5m"] = when { chg5m < -10 -> "<-10"; chg5m < -2 -> "<-2"; chg5m < 2 -> "<2"; chg5m < 10 -> "<10"; chg5m < 30 -> "<30"; else -> ">=30" }
        if (pxPeak.isFinite()) b["pxPeak"] = when { pxPeak < 0.6 -> "<0.6"; pxPeak < 0.8 -> "<0.8"; pxPeak < 0.92 -> "<0.92"; pxPeak < 0.98 -> "<0.98"; else -> ">=0.98" }
        if (top.isFinite()) b["top"] = when { top < 15 -> "<15"; top < 30 -> "<30"; else -> ">=30" }
        if (chg1h.isFinite()) b["chg1h"] = when { chg1h < -20 -> "<-20"; chg1h < 0 -> "<0"; chg1h < 20 -> "<20"; chg1h < 100 -> "<100"; else -> ">=100" }
        if (volAccel.isFinite()) b["volAccel"] = EdgeModel8537.VOLACC_EDGES.firstOrNull { volAccel < it }?.let { "<${it.toInt()}" } ?: ">=80"
        return b
    }

    /** Pure: the cells an observation in [lane] belongs to — each feature and each pair market-wide, each pair per lane. */
    fun keys8610(lane: String, b: Map<String, String>): List<String> {
        val l = lane.trim().uppercase()
        val f = b.entries.toList()
        val out = ArrayList<String>(f.size + f.size * f.size)
        for (e in f) out += "M|${e.key}=${e.value}"
        for (i in f.indices) for (j in i + 1 until f.size) {
            val pair = "${f[i].key}=${f[i].value}&${f[j].key}=${f[j].value}"
            out += "M|$pair"; out += "$l|$pair"
        }
        return out
    }

    private fun bandsOf(r: EdgeModel8537.Raw) =
        bands8610(r.liq, r.mcap, r.ageMin, r.bp, r.chg5m, r.chg1h, r.pxPeak, r.volAccel, r.top, r.phase)

    /** EdgeModel8537.learn: file one labelled decision. */
    fun learn8610(s: EdgeModel8537.Snap, net: Double) {
        if (!net.isFinite() || s.horizonMs <= 0L) return
        ensureLoaded()
        io.execute { record(s.mint, s.atMs, s.horizonMs, s.raw.lane, bandsOf(s.raw), net) }
    }

    private fun record(mint: String, at: Long, horizon: Long, lane: String, bands: Map<String, String>, net: Double) {
        if (mint.isBlank() || lane.isBlank() || at <= 0L || horizon <= 0L) return
        val id = "$mint|$at|$horizon"
        val lanes = recent.getOrPut(id) { mutableSetOf() }
        val first = lanes.isEmpty()
        if (!lanes.add(lane)) return
        if (recent.size > MAX_RECENT_OBSERVATIONS) recent.entries.iterator().run { next(); remove() }
        val keys = keys8610(lane, bands).filter { first || !it.startsWith("M|") }
        add(keys.map { "$horizon|$it" }, managed8610(net))
        learned.incrementAndGet()
    }

    private fun add(ks: List<String>, y: Double) {
        for (k in ks) {
            val c = cells.getOrPut(k) { Cell() }
            synchronized(c) { c.n = c.n * DECAY + 1.0; c.sum = c.sum * DECAY + y; c.sq = c.sq * DECAY + y * y }
        }
    }

    enum class Call { PAYS, UNPROVEN, LOSES }

    /** V5.0.8613 — the coin's score: the mean of its mature cells' t-statistics (mean / se), each clipped to +-5. */
    private const val T_CLIP = 5.0
    private const val PAY_SCORE = 0.15

    /**
     * Pure: the call of a set of cell records (n, sum, sumsq). V5.0.8613 — superseded: "one cell proven > 0 and none proven < 0"
     * (8610). With ~60 cells per coin and broad single-feature cells (MATURE, liq < 10k) proving a loss in a market that loses on
     * average, one losing cell vetoed nearly every coin: 02:57 dump (8611, 15 min), ledger paying=20 losing=1683, live buys 0,
     * LIVE_POCKET_PROVEN_LOSING_8610 on every lane. The coin is now scored by the weight of ALL its evidence: the mean clipped t
     * of its cells with n >= 40; PAYS above +0.15, LOSES below -0.15. Same online replay (24,904 decisions, label held 5 min):
     * 1,120 picks (4.5 % of decisions, 3.6x the veto rule) at +10.3 % +-2.3, first half +12.4 %, second +9.6 %, +2.0 % without
     * the best five coins.
     */
    fun callOf8610(records: List<DoubleArray>): Call {
        var sumT = 0.0; var cnt = 0
        for (s in records) {
            val n = s[0]; if (n < N_MIN) continue
            val m = s[1] / n
            val se = kotlin.math.sqrt(((s[2] / n) - m * m).coerceAtLeast(0.0) / n)
            val t = if (se > 0.0) m / se else kotlin.math.sign(m) * T_CLIP
            sumT += t.coerceIn(-T_CLIP, T_CLIP); cnt++
        }
        if (cnt == 0) return Call.UNPROVEN
        val score = sumT / cnt
        return when { score > PAY_SCORE -> Call.PAYS; score < -PAY_SCORE -> Call.LOSES; else -> Call.UNPROVEN }
    }

    /** The ledger's call for [ts] in [lane] now; null while the ledger is not yet seeded. */
    fun verdict8610(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): Call? = try {
        ensureLoaded()
        if (!ready) null else {
            val r = EdgeModel8537.rawFor8610(ts, lane, nowMs)
            val horizon = targetHorizon(r.lane)
            val recs = keys8610(r.lane, bandsOf(r)).mapNotNull { k -> cells["$horizon|$k"]?.let { c -> synchronized(c) { doubleArrayOf(c.n, c.sum, c.sq) } } }
            callOf8610(recs).also { try { PipelineHealthCollector.labelInc("POCKET_8610_${it.name}") } catch (_: Throwable) {} }
        }
    } catch (_: Throwable) { null }

    // ── persistence: the ledger file, else a replay of the device's own edge dataset ─────────────────────────────────

    @Synchronized private fun ensureLoaded() {
        if (ready || loading) return
        loading = true
        io.execute {
            try {
                seedFromDataset()
                ready = true
            } catch (_: Exception) {
                PipelineHealthCollector.labelInc("POCKET_8610_LOAD_FAILED")
            } finally {
                loading = false
            }
        }
    }

    /** Replay the device's own labelled decisions in time order (the dataset EdgeModel8537 exports). */
    private fun seedFromDataset() {
        val f = EdgeModel8537.datasetFile() ?: return
        if (!f.exists()) return
        fun d(o: JSONObject, k: String) = if (o.isNull(k)) Double.NaN else o.optDouble(k, Double.NaN)
        f.forEachLine { line ->
            try {
                if (!line.contains("\"horizonMs\"")) return@forEachLine
                val o = JSONObject(line)
                // Share the model's exact venue/epoch/fillability/horizon proof. The old
                // path replayed poisoned non-pump curve outcomes into the pocket cells even
                // when the edge model discarded them, creating false paying pockets.
                if (!EdgeModel8537.replayAccept8642(o)) return@forEachLine
                val net = d(o, "net"); if (!net.isFinite()) return@forEachLine
                val b = bands8610(d(o, "liq"), d(o, "mcap"), d(o, "age"), d(o, "bp"), d(o, "chg5m"), d(o, "chg1h"), d(o, "pxPeak"),
                    d(o, "volAccel"), d(o, "top"), o.optString("phase"))
                record(o.optString("mint"), o.optLong("t"), o.optLong("horizonMs"), o.optString("lane"), b, net)
            } catch (_: Throwable) {}
        }
        try { PipelineHealthCollector.labelInc("POCKET_8610_SEEDED_FROM_DATASET") } catch (_: Throwable) {}
    }

    /**
     * V5.0.8618 — the tuning report: the [n] strongest paying and losing pockets the ledger has learned (cells with n >= 40,
     * ranked by t = mean / se of the managed outcome), so the operator sees WHERE the bot thinks the money is.
     */
    fun topPockets8618(n: Int = 15): String {
        val rows = ArrayList<Triple<String, Double, DoubleArray>>()
        for ((k, c) in cells) {
            val v = synchronized(c) { doubleArrayOf(c.n, c.sum, c.sq) }
            if (v[0] < N_MIN) continue
            val m = v[1] / v[0]
            val se = kotlin.math.sqrt(((v[2] / v[0]) - m * m).coerceAtLeast(0.0) / v[0])
            if (se > 0.0) rows += Triple(k, m / se, doubleArrayOf(v[0], m))
        }
        if (rows.isEmpty()) return "  POCKETS    none mature yet (a pocket needs n >= 40)\n"
        rows.sortByDescending { it.second }
        fun line(r: Triple<String, Double, DoubleArray>) =
            "    ${r.first.take(70).padEnd(70)} n=${"%.0f".format(r.third[0])} mean=${"%+.1f".format(r.third[1])}% t=${"%+.2f".format(r.second)}\n"
        return buildString {
            append("  POCKETS PAYING (strongest first; M| = market-wide, LANE| = that lane only)\n")
            rows.take(n).filter { it.second > 0.0 }.forEach { append(line(it)) }
            append("  POCKETS LOSING (strongest first)\n")
            rows.takeLast(n).reversed().filter { it.second < 0.0 }.forEach { append(line(it)) }
        }
    }

    /** Brief: how many cells the ledger holds and how many of them have proven a pay or a loss. */
    fun statusLine8610(): String {
        var pays = 0; var loses = 0
        for (c in cells.values) synchronized(c) {
            when (callOf8610(listOf(doubleArrayOf(c.n, c.sum, c.sq)))) { Call.PAYS -> pays++; Call.LOSES -> loses++; else -> {} }
        }
        return "pocket8610[ready=$ready cells=${cells.size} paying=$pays losing=$loses learned=${learned.get()} rule=mean clipped t of cells n>=40 > +$PAY_SCORE, decay0.995]"
    }
}

package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.JobCost8813
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8887 §THE_HOURLY_AUTHORITY_TABLE (plan W2) — "every hour, replays the last 48 h of decisions ... with no look-ahead
 * and an embargo. It grades each door, score band, setup ... on ex-top-3 mean, median and halves, deflated for the number
 * tried. It publishes one authority table." Owner: "there are 60,000% profit opportunities out there literally every day".
 *
 * The rows are the device's own: every meme decision's research rows (ResearchRows8877, 8884) — the decision's lane,
 * admitted or not, the refusal it met, stage, setup, forecast, V3 score, cap and pool, with its net (after the round trip)
 * read 1 h, 4 h and 24 h after it. Once an hour, for each horizon on its own:
 *  - window: decisions of the last 48 h whose read is in (decided at least one horizon ago: the embargo; a row is written
 *    only when its read lands, and a row whose clock says otherwise is not used). Nothing later than a row's own read
 *    enters it: no look-ahead.
 *  - cells, within each lane: the lane itself; admitted / refused; each refusal door (FdgGate8365.gateKey8392 of the reason,
 *    refused rows only); each stage; each setup; and the quintile of the window's own V3 score, forecast, cap and pool
 *    (cut points from the window itself, published with the table: no hand-set band).
 *  - per cell: n, mean, median, the mean without its three best rows, the standard error, and the means of its earlier and
 *    later halves (in decision order).
 *  - deflation: k = the cells of that horizon holding [MIN_N_8887]+ rows; the margin is CryptoTrend8886.familyZ8886(k) standard
 *    errors (Šidák: the one-SE confidence held across every cell tried).
 *  - verdict: PAYS when the ex-top-3 mean clears the margin and both halves are positive; LOSES when the mean is below zero by
 *    the margin and both halves are negative; otherwise UNPROVEN. A 4 h / 24 h read the price source could not price (8884
 *    NO_PRICE) stays in its cells as unpriced: PAYS is read with it counted as a total loss, LOSES without it, so a dead coin
 *    can flatter neither; a late read (8884 LATE_READ) is not that horizon's read and is left out.
 *
 * Published: the brief (one line per horizon with its graded cells, the margin, and the best and worst), the table file
 * ([FILE_8887], saved with the report), and [verdict8887] / [band8887] for the authority consumers. Measurement and
 * publication only in this build: nothing refuses or admits on it yet.
 */
object AuthorityTable8887 {
    private const val FILE_8887 = "authority_table_8887.json"
    internal const val MIN_N_8887 = 20
    internal const val WINDOW_MS_8887 = 48L * 3_600_000L
    internal val HORIZONS_8887 = longArrayOf(3_600_000L, 4L * 3_600_000L, 24L * 3_600_000L)
    private const val RUN_EVERY_MS_8887 = 3_600_000L
    private const val FIRST_RUN_DELAY_MS_8887 = 10L * 60_000L
    private val BANDED_8887 = listOf("v3", "pred", "mcap", "liq")
    private const val LATE_8887 = "LATE_READ_8884:"
    private const val UNPRICED_8887 = "NO_PRICE_JUP_8884:"
    private const val PRICED_8887 = "JUP_8884:"
    /** A token position's floor: an unpriced read counted as everything lost (the leaderboard's own -100 % clamp). */
    internal const val TOTAL_LOSS_8887 = -100.0

    enum class Verdict8887 { PAYS, LOSES, UNPROVEN }

    /** One decision's read, as the research row carries it. */
    internal data class Row8887(val atMs: Long, val horizonMs: Long, val lane: String, val admitted: Boolean, val stage: String,
                                val setup: String, val pred: Double, val v3: Double, val mcap: Double, val liq: Double,
                                val net: Double, val reason: String)

    /** One graded cell. */
    data class Cell8887(val horizonMs: Long, val lane: String, val factor: String, val value: String, val n: Int, val unpriced: Int,
                        val mean: Double, val median: Double, val exTop3: Double, val se: Double, val half1: Double, val half2: Double,
                        val verdict: Verdict8887)

    // ── pure ──

    private val HEAD_8887 = Regex("^\\{\"t\":(\\d+),\"horizonMs\":(\\d+),")

    /** Pure: (decision time, horizon) from a research line's head without parsing it; null when it is not a research row. */
    internal fun head8887(line: String): Pair<Long, Long>? =
        HEAD_8887.find(line)?.let { m -> m.groupValues[1].toLongOrNull()?.let { t -> m.groupValues[2].toLongOrNull()?.let { h -> t to h } } }

    /** Pure: one research row, or null when it carries no net. */
    internal fun row8887(o: JSONObject): Row8887? {
        fun d(k: String) = if (o.isNull(k)) Double.NaN else o.optDouble(k, Double.NaN)
        val lane = o.optString("lane").trim().uppercase()
        val raw = o.optString("reason")
        // 8884's later reads: a read taken late is not the horizon's read (dropped); an unroutable coin stays in as unpriced
        // (net NaN), so a cell's dead coins are never silently left out; the decision's own reason follows the prefix.
        if (lane.isBlank() || raw.startsWith(LATE_8887)) return null
        val unpriced = raw.startsWith(UNPRICED_8887)
        val net = d("net")
        if (!net.isFinite() && !unpriced) return null
        val reason = when {
            unpriced -> raw.removePrefix(UNPRICED_8887)
            raw.startsWith(PRICED_8887) -> raw.removePrefix(PRICED_8887)
            else -> raw
        }
        return Row8887(o.optLong("t", 0L), o.optLong("horizonMs", 0L), lane,
            o.optBoolean("admitted"), o.optString("stage"), o.optString("setup"), d("pred"), d("v3"), d("mcap"), d("liq"),
            if (unpriced) Double.NaN else net, reason)
    }

    /** Pure: is [r] inside the window at [nowMs] for its horizon — decided in the last 48 h and read (decided one horizon ago)? */
    internal fun inWindow8887(r: Row8887, nowMs: Long): Boolean =
        r.atMs > 0L && r.atMs >= nowMs - WINDOW_MS_8887 && r.atMs + r.horizonMs <= nowMs

    /** Pure: the four inner quintile cut points of [values] (finite only); null with fewer than five. */
    internal fun cuts8887(values: List<Double>): DoubleArray? {
        val s = values.filter { it.isFinite() }.sorted()
        if (s.size < 5) return null
        return DoubleArray(4) { i -> s[((i + 1) * s.size) / 5] }
    }

    /** Pure: the quintile ("Q1".."Q5") of [v] under [cuts]; null when either is missing. */
    fun band8887(v: Double, cuts: DoubleArray?): String? {
        if (cuts == null || !v.isFinite()) return null
        return "Q" + (1 + cuts.count { v >= it })
    }

    /** Pure: the stats of [nets] in decision order and its verdict under the family margin [z]. */
    /** Pure: (mean, se, earlier-half mean, later-half mean) of [x] in decision order (population variance, as proofOf8546). */
    private fun moments8887(x: List<Double>): DoubleArray {
        val n = x.size
        if (n == 0) return doubleArrayOf(Double.NaN, Double.NaN, Double.NaN, Double.NaN)
        val mean = x.average()
        val variance = (x.sumOf { it * it } / n - mean * mean).coerceAtLeast(0.0)
        val se = if (n > 1) kotlin.math.sqrt(variance / n) else Double.NaN
        val h1 = x.take(n / 2).let { if (it.isEmpty()) Double.NaN else it.average() }
        val h2 = x.drop(n / 2).let { if (it.isEmpty()) Double.NaN else it.average() }
        return doubleArrayOf(mean, se, h1, h2)
    }

    /**
     * Pure: the stats of [nets] in decision order (NaN = a read the price source could not price) and the verdict under the
     * family margin [z]. Each verdict is read where the unpriced reads cannot flatter it: PAYS with every unpriced read
     * counted as a total loss ([TOTAL_LOSS_8887]); LOSES on the priced reads alone. Mean and median are of the priced reads.
     */
    internal fun grade8887(horizonMs: Long, lane: String, factor: String, value: String, nets: List<Double>, z: Double): Cell8887 {
        val n = nets.size
        val priced = nets.filter { it.isFinite() }
        val worst = nets.map { if (it.isFinite()) it else TOTAL_LOSS_8887 }
        val sortedP = priced.sorted()
        val median = when {
            sortedP.isEmpty() -> Double.NaN
            sortedP.size % 2 == 1 -> sortedP[sortedP.size / 2]
            else -> (sortedP[sortedP.size / 2 - 1] + sortedP[sortedP.size / 2]) / 2.0
        }
        val w = moments8887(worst)
        val exTop3 = if (n > 3) worst.sorted().dropLast(3).average() else Double.NaN
        val p = moments8887(priced)
        val verdict = when {
            n >= MIN_N_8887 && w[1].isFinite() && exTop3.isFinite() && exTop3 - z * w[1] > 0.0 && w[2] > 0.0 && w[3] > 0.0 -> Verdict8887.PAYS
            priced.size >= MIN_N_8887 && p[1].isFinite() && p[0] + z * p[1] < 0.0 && p[2] < 0.0 && p[3] < 0.0 -> Verdict8887.LOSES
            else -> Verdict8887.UNPROVEN
        }
        return Cell8887(horizonMs, lane, factor, value, n, n - priced.size, p[0], median, exTop3, w[1], w[2], w[3], verdict)
    }

    /** Pure: every cell of one horizon's [rows] (any order), graded; and the cut points used. */
    internal fun table8887(horizonMs: Long, rows: List<Row8887>): Pair<List<Cell8887>, Map<String, DoubleArray>> {
        val byCell = LinkedHashMap<Triple<String, String, String>, ArrayList<Double>>()
        val cutsOut = HashMap<String, DoubleArray>()
        for ((lane, laneRows) in rows.sortedBy { it.atMs }.groupBy { it.lane }) {
            val cuts = BANDED_8887.associateWith { f ->
                cuts8887(laneRows.map { when (f) { "v3" -> it.v3; "pred" -> it.pred; "mcap" -> it.mcap; else -> it.liq } })
            }
            cuts.forEach { (f, c) -> if (c != null) cutsOut["$lane|$f"] = c }
            for (r in laneRows) {
                fun add(factor: String, value: String?) {
                    if (value.isNullOrBlank()) return
                    byCell.getOrPut(Triple(lane, factor, value.take(48))) { ArrayList() }.add(r.net)
                }
                add("lane", "ALL")
                add("admitted", if (r.admitted) "Y" else "N")
                if (!r.admitted && r.reason.isNotBlank()) add("door", try { FdgGate8365.gateKey8392(r.reason) } catch (_: Throwable) { r.reason.take(48) })
                add("stage", r.stage)
                add("setup", r.setup)
                add("v3", band8887(r.v3, cuts["v3"]))
                add("pred", band8887(r.pred, cuts["pred"]))
                add("mcap", band8887(r.mcap, cuts["mcap"]))
                add("liq", band8887(r.liq, cuts["liq"]))
            }
        }
        val k = byCell.values.count { it.size >= MIN_N_8887 }
        val z = com.lifecyclebot.perps.CryptoTrend8886.familyZ8886(k)
        return byCell.map { (key, nets) -> grade8887(horizonMs, key.first, key.second, key.third, nets, z) } to cutsOut
    }

    // ── state ──

    private class Table(val atMs: Long, val rows: Int, val k: Int, val z: Double, val cells: Map<String, Cell8887>, val cuts: Map<String, DoubleArray>)

    @Volatile private var tables: Map<Long, Table> = emptyMap()
    @Volatile private var status = "not run"
    @Volatile private var lastRunMs = 0L
    private val running = AtomicBoolean(false)
    private val runs = AtomicLong(0L)
    private val failures = AtomicLong(0L)
    @Volatile private var started = false
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "authority-8887").apply { isDaemon = true } }

    private fun key(lane: String, factor: String, value: String) = "${lane.trim().uppercase()}|$factor|$value"

    fun file8887(): File? = try { com.lifecyclebot.AATEApp.appContextOrNull()?.filesDir?.let { File(it, FILE_8887) } } catch (_: Throwable) { null }

    /** ResearchRows8877.note8877 and the brief: the hourly pass starts once (first pass ten minutes in, then every hour). */
    @Synchronized fun ensureStarted8887() {
        if (started) return
        started = true
        exec.scheduleWithFixedDelay({ try { run8887(System.currentTimeMillis()) } catch (_: Throwable) { failures.incrementAndGet() } },
            FIRST_RUN_DELAY_MS_8887, RUN_EVERY_MS_8887, TimeUnit.MILLISECONDS)
    }

    private fun run8887(nowMs: Long) {
        if (!running.compareAndSet(false, true)) return
        try {
            val f = ResearchRows8877.file8877()
            if (f == null || !f.exists()) { status = "no research rows yet"; return }
            val byHorizon = HashMap<Long, ArrayList<Row8887>>()
            JobCost8813.measureBackground8817("authorityTable8887") {
                f.forEachLine { line ->
                    val (t, h) = head8887(line) ?: return@forEachLine
                    if (h !in HORIZONS_8887 || t < nowMs - WINDOW_MS_8887 || t + h > nowMs) return@forEachLine
                    val r = try { row8887(JSONObject(line)) } catch (_: Throwable) { null } ?: return@forEachLine
                    if (inWindow8887(r, nowMs)) byHorizon.getOrPut(h) { ArrayList() }.add(r)
                }
            }
            val built = HashMap<Long, Table>()
            for (h in HORIZONS_8887) {
                val rows = byHorizon[h].orEmpty()
                val (cells, cuts) = table8887(h, rows)
                val k = cells.count { it.n >= MIN_N_8887 }
                built[h] = Table(nowMs, rows.size, k, com.lifecyclebot.perps.CryptoTrend8886.familyZ8886(k),
                    cells.associateBy { key(it.lane, it.factor, it.value) }, cuts)
            }
            tables = built
            lastRunMs = nowMs
            runs.incrementAndGet()
            status = "ran"
            save8887(built)
        } catch (t: Throwable) {
            failures.incrementAndGet(); status = "failed:${t.javaClass.simpleName}"
        } finally { running.set(false) }
    }

    /** The table file: every graded cell (n >= MIN_N) of each horizon, with its cut points. */
    private fun save8887(built: Map<Long, Table>) {
        val f = file8887() ?: return
        try {
            val root = JSONObject()
            for ((h, t) in built) {
                val cells = JSONArray()
                t.cells.values.filter { it.n >= MIN_N_8887 }.sortedWith(compareBy({ it.lane }, { it.factor }, { it.value })).forEach { c ->
                    fun n(v: Double): Any = if (v.isFinite()) v else JSONObject.NULL
                    cells.put(JSONObject().put("lane", c.lane).put("factor", c.factor).put("value", c.value).put("n", c.n).put("unpriced", c.unpriced)
                        .put("mean", n(c.mean)).put("median", n(c.median)).put("exTop3", n(c.exTop3)).put("se", n(c.se))
                        .put("half1", n(c.half1)).put("half2", n(c.half2)).put("verdict", c.verdict.name))
                }
                val cuts = JSONObject()
                t.cuts.forEach { (k, v) -> cuts.put(k, JSONArray(v.toList())) }
                root.put((h / 3_600_000L).toString() + "h", JSONObject().put("atMs", t.atMs).put("rows", t.rows).put("graded", t.k)
                    .put("z", t.z).put("cuts", cuts).put("cells", cells))
            }
            val tmp = File(f.parentFile, "$FILE_8887.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (_: Throwable) { failures.incrementAndGet() }
    }

    /** The latest verdict of [lane]'s cell ([factor], [value]) at [horizonMs]; null before the first pass or for an unseen cell. */
    fun verdict8887(horizonMs: Long, lane: String, factor: String, value: String): Cell8887? = tables[horizonMs]?.cells?.get(key(lane, factor, value))

    /** The quintile of [v] for [lane]'s [factor] ("v3" / "pred" / "mcap" / "liq") under the latest pass's cut points at [horizonMs]. */
    fun bandOf8887(horizonMs: Long, lane: String, factor: String, v: Double): String? =
        band8887(v, tables[horizonMs]?.cuts?.get("${lane.trim().uppercase()}|$factor"))

    /** Brief: per horizon, rows, graded cells, the margin, PAYS / LOSES counts and the best and worst graded cells. */
    fun statusLine8887(): String {
        ensureStarted8887()
        val now = System.currentTimeMillis()
        val per = HORIZONS_8887.joinToString(" ") { h ->
            val t = tables[h] ?: return@joinToString "${h / 3_600_000L}h{-}"
            val graded = t.cells.values.filter { it.n >= MIN_N_8887 }
            val pays = graded.filter { it.verdict == Verdict8887.PAYS }.sortedByDescending { it.exTop3 - t.z * it.se }.take(3)
            val loses = graded.filter { it.verdict == Verdict8887.LOSES }.sortedBy { it.mean + t.z * it.se }.take(3)
            fun c(x: Cell8887) = "${x.lane}/${x.factor}=${x.value} n${x.n}" + (if (x.unpriced > 0) " unpriced${x.unpriced}" else "") +
                " mean${"%+.1f".format(x.mean)} ex3${"%+.1f".format(x.exTop3)} med${"%+.1f".format(x.median)}"
            "${h / 3_600_000L}h{rows=${t.rows} graded=${t.k} z=${"%.2f".format(t.z)} pays=${graded.count { it.verdict == Verdict8887.PAYS }} " +
                "loses=${graded.count { it.verdict == Verdict8887.LOSES }} best[${pays.joinToString("; ") { c(it) }}] worst[${loses.joinToString("; ") { c(it) }}]}"
        }
        val ago = if (lastRunMs > 0L) (now - lastRunMs) / 60_000L else -1L
        return "authority8887[$status runs=${runs.get()} failed=${failures.get()} agoMin=$ago $per rule=48 h of the device's decision reads per horizon, " +
            "embargoed; PAYS = ex-top-3 mean > z SE and both halves > 0, LOSES = mean < -z SE and both halves < 0; z deflated across the cells graded]"
    }

    internal fun runForTest8887(nowMs: Long) = run8887(nowMs)
}

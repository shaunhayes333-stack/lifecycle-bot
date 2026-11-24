package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8934 §EACH_LANE_ITS_OWN_POCKET — owner, 22 Nov 05:44-06:35: "are all of the specialists, traders, lanes being fed coins
 * that can make money within their designed intent?" then "fix it properly. that means all lanes specialists and traders".
 *
 * The lane hunters already deal each lane coins in its own cap band (LaneHunter7297: QUALITY $75k-$1M, BLUECHIP $1M+, TREASURY /
 * CASHGEN $1M-$5M ...). What was missing is each lane's own record of WHICH of those in-band coins pay, at the lane's own
 * hold, from data a learning reset leaves. The research rows (ResearchRows8877: every lane's decision on every coin, its
 * executable net after cost at 1 h, and at 4 h / 24 h from ResearchFollow8884) hold it. On the owner's 20-21 Nov rows, each coin
 * once per lane at its first read inside the lane's band, single features and pairs (origin, stage, setup, V3, forecast,
 * pool, cap), kept only when 30+ coins, both time halves positive, and the mean without the best three clears the family-
 * deflated margin (CryptoTrend8886.familyZ8886 over the cells holding 30+ coins):
 *   CORE 7 cells (best: off pump.fun + forecast >= +10 %, n98 +39.2 %, +33.7 % without the best three), MANIPULATED 6,
 *   SHITCOIN 4 (two of them its LAUNCH_CONTINUATION setup at $10k-$20k, n36 +48.6 %), MOONSHOT 4, EXPRESS 4, DIP_HUNTER 1
 *   (off pump.fun, pool under $5k, n46 +8.1 %); QUALITY (n301 in band, -6.1 %), BLUECHIP (n123, -3.7 %), TREASURY / CASHGEN
 *   (n45, -6.5 %), PROJECT_SNIPER and CYCLIC none; every CRYPTO_* lane none.
 *
 * This ledger is that search, run on the device: seeded from the research file, filed from every row as it is written, mined
 * every hour. Each lane is read at its designed hold ([horizon8934]: BLUECHIP holds up to 8 h and reads at 4 h; every other
 * lane holds an hour or less and reads at 1 h). A coin in a lane's proven cell carries that cell as market-cell evidence in
 * CandidateAdmission8136 (after the base pocket), with the agreement / base pockets' precedence: hard safety, the LP-lock rule,
 * sellability, executable economics, the coin's avoid shapes and the losing record of the setup it trades as all still decide.
 * A lane with no proven cell gets nothing from here, and that is printed (`proven{...}`), not guessed. The CRYPTO_* lanes are
 * mined and printed (they enter through CanonicalAssetEntryContract6551 and their own twin records); none is proven today.
 */
object LanePocket8934 {
    const val KIND_8934 = "LANE_POCKET"
    internal const val MIN_COINS_8934 = 30
    internal const val MIN_HALF_8934 = 8
    private const val MAX_COINS_8934 = 3_000
    private const val MINE_EVERY_MS_8934 = 60L * 60_000L
    private const val H1_8934 = 3_600_000L
    private const val H4_8934 = 4L * 3_600_000L

    /** One coin's first in-band read in a lane: its features ("k=v") at the decision, when, and the read's net. */
    internal class Coin8934(val atMs: Long, val feats: List<String>, val net: Double)

    /** One proven cell of a lane: its conditions, coins, mean / SE / mean without the best three (outcome %), and the margin. */
    data class Cell8934(val conds: List<String>, val n: Int, val mean: Double, val se: Double, val exTop3: Double, val z: Double, val runRate: Double) {
        val id: String get() = conds.joinToString("&")
    }

    // ── pure ──

    /** Pure: the research horizon a lane is read at — BLUECHIP (holds up to 8 h) at 4 h, every other lane at 1 h. */
    internal fun horizon8934(lane: String): Long = if (lane.trim().uppercase() == "BLUECHIP") H4_8934 else H1_8934

    private fun bucket(x: Double, cuts: DoubleArray, names: Array<String>): String {
        if (!x.isFinite()) return "NA"
        for (i in cuts.indices) if (x < cuts[i]) return names[i]
        return names.last()
    }

    /** Pure: the seven features of a decision, as the research row records them (each feature word held once, 8945). */
    internal fun features8934(mint: String, stage: String, setup: String, v3: Double, pred: Double, liq: Double, mcap: Double): List<String> = listOf(
        "origin=" + MarketCohort8701.origin8701(mint),
        "stage=" + stage.ifBlank { "NA" },
        "setup=" + setup.ifBlank { "NA" },
        "v3=" + bucket(v3, doubleArrayOf(15.0, 23.0, 35.0), arrayOf("<15", "15-23", "23-35", "35+")),
        "pred=" + bucket(pred, doubleArrayOf(-5.0, 0.0, 5.0, 10.0), arrayOf("<-5", "-5-0", "0-5", "5-10", "10+")),
        "liq=" + bucket(liq, doubleArrayOf(5e3, 2e4, 1e5, 5e5), arrayOf("<5k", "5-20k", "20-100k", "100-500k", "500k+")),
        "mcap=" + bucket(mcap, doubleArrayOf(1e4, 2e4, 1e5, 1e6), arrayOf("<10k", "10-20k", "20-100k", "100k-1M", "1M+")),
    ).map { word8945(it) }

    // V5.0.8945 — the 19:34 heap map: this pocket's books held 7.0 MB for 15,878 coins, nearly all of it seven freshly built
    // feature strings per coin, though the features take a few hundred distinct values; each is now held once (bounded).
    private val words8945 = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun word8945(s: String): String = if (words8945.size >= 2_000) s else (words8945.putIfAbsent(s, s) ?: s)

    /** Pure: every cell a feature list belongs to — each feature alone and each pair. */
    internal fun cellsOf8934(feats: List<String>): List<List<String>> {
        val out = ArrayList<List<String>>(feats.size * (feats.size + 1) / 2)
        for (i in feats.indices) {
            out += listOf(feats[i])
            for (j in i + 1 until feats.size) out += listOf(feats[i], feats[j])
        }
        return out
    }

    /** Pure: is [mcap] inside [lane]'s hunt band ([lo], [hi])? */
    internal fun inBand8934(mcap: Double, lo: Double, hi: Double): Boolean = mcap.isFinite() && mcap > 0.0 && mcap >= lo && mcap <= hi

    /**
     * Pure: the proven cells of one lane's [coins] (time order). Each coin counts once per cell; [splitMs] splits the halves.
     * Proven: [MIN_COINS_8934]+ coins, [MIN_HALF_8934]+ in each half with both halves' means above zero, and the mean without
     * the best three above z x SE, z deflated for the cells holding [MIN_COINS_8934]+ coins.
     */
    internal fun mine8934(coins: List<Coin8934>, splitMs: Long): List<Cell8934> {
        val byCell = HashMap<List<String>, MutableList<Coin8934>>()
        for (c in coins) for (cell in cellsOf8934(c.feats)) byCell.getOrPut(cell) { ArrayList() }.add(c)
        val k = byCell.values.count { it.size >= MIN_COINS_8934 }
        if (k == 0) return emptyList()
        val z = maxOf(2.0, try { com.lifecyclebot.perps.CryptoTrend8886.familyZ8886(k) } catch (_: Throwable) { 3.0 })
        val out = ArrayList<Cell8934>()
        for ((cell, v) in byCell) {
            if (v.size < MIN_COINS_8934) continue
            val ys = v.map { PayingCell8723.outcome8723(it.net) }
            val n = ys.size
            val mean = ys.sum() / n
            val se = kotlin.math.sqrt((ys.sumOf { it * it } / n - mean * mean).coerceAtLeast(0.0) / n)
            val sorted = ys.sorted()
            val exTop3 = sorted.take(n - 3).sum() / (n - 3)
            val first = v.filter { it.atMs < splitMs }.map { PayingCell8723.outcome8723(it.net) }
            val second = v.filter { it.atMs >= splitMs }.map { PayingCell8723.outcome8723(it.net) }
            if (first.size < MIN_HALF_8934 || second.size < MIN_HALF_8934 || first.sum() <= 0.0 || second.sum() <= 0.0) continue
            if (!(exTop3 > z * se)) continue
            out += Cell8934(cell, n, mean, se, exTop3, z, v.count { it.net >= 100.0 }.toDouble() / n)
        }
        return out.sortedByDescending { it.exTop3 - it.z * it.se }
    }

    /**
     * V5.0.8991 §A_POCKET_S_RANGE_IS_EVERY_HORIZON_IT_PROVES_AT — owner, 24 Nov 23:00: "we need to extend the time range of
     * the pockets where appropriate". Two ranges were short. (1) The data: the research file kept 45,000-60,000 lines, set
     * when it held one hour row a decision; since 8884 each decision also writes its 4 h and 24 h rows, so the file — and
     * every pocket mined from it — spanned 2.3 days (the 24 Nov export: 64,519 lines, decisions 22 Nov 04:38 to 24 Nov),
     * and a 24 h row had at most a day of history behind it (ResearchRows8877 now keeps about a week). (2) The horizon:
     * a meme lane was mined at 1 h only, though its 4 h and 24 h reads are in the same file. Each lane is now mined at
     * every longer horizon too ([EXT_HORIZONS_8991]) on the held net, an unpriced read counted -100 % (8983), by the same
     * rule ([mine8934]: 30+ coins, both time halves positive, the mean without its best three > z SE). A coin in no
     * hour cell that sits in a cell proven at a longer horizon carries that cell as its evidence, named with its horizon.
     * Measured on the 24 Nov rows by this rule: 1 h proves cells in MOONSHOT, CORE, EXPRESS and MANIPULATED; 4 h and 24 h
     * prove none in any lane (lane means -30 % to -46 % at 4 h; the hour-proven off-pump cohort n202 reads +25.4 % at
     * 1 h, then 158 unpriced and the 44 priced -9.2 % at 4 h). So nothing is extended today on the evidence; the range
     * extends by itself, per lane and cell, when a longer horizon proves. A held position's clock is unchanged: a time exit
     * already holds only on the coin's own live metrics (HoldSupport8964).
     */
    internal val EXT_HORIZONS_8991 = longArrayOf(H4_8934, 24L * 3_600_000L)
    private val extBooks8991 = ConcurrentHashMap<String, LinkedHashMap<String, Coin8934>>()   // "LANE@4h" -> mint -> its first in-band read
    @Volatile private var provenExt8991: Map<String, List<Cell8934>> = emptyMap()
    private val servedExt8991 = AtomicLong(0L)

    /** Pure: the book of [lane]'s reads at [horizonMs] ("MOONSHOT@4h"). */
    internal fun extKey8991(lane: String, horizonMs: Long): String = "${lane.trim().uppercase()}@${horizonMs / 3_600_000L}h"

    /** Pure: is [horizonMs] a longer horizon [lane] is also mined at? (Never a crypto lane: they are read by their own twins.) */
    internal fun extended8991(lane: String, horizonMs: Long): Boolean {
        val l = lane.trim().uppercase()
        return !l.startsWith("CRYPTO_") && horizonMs > horizon8934(l) && EXT_HORIZONS_8991.any { it == horizonMs }
    }

    /** Pure: is [line] a research row any book here files (the hour, 4 h or 24 h read)? */
    internal fun wanted8991(line: String): Boolean =
        line.contains("\"horizonMs\":3600000") || line.contains("\"horizonMs\":14400000") || line.contains("\"horizonMs\":86400000")

    private fun fileExt8991(o: JSONObject, lane: String, horizonMs: Long) {
        val net = ResearchRows8877.rowNet8983(o)
        if (!net.isFinite()) return
        fun d(k: String) = if (o.isNull(k)) Double.NaN else o.optDouble(k, Double.NaN)
        val mcap = d("mcap")
        val (lo, hi) = band(lane) ?: return
        if (!inBand8934(mcap, lo, hi)) return
        val mint = o.optString("mint")
        if (mint.isBlank()) return
        val coin = Coin8934(o.optLong("t", 0L), features8934(mint, o.optString("stage"), o.optString("setup"), d("v3"), d("pred"), d("liq"), mcap), net)
        val book = extBooks8991.getOrPut(extKey8991(lane, horizonMs)) { LinkedHashMap() }
        synchronized(book) {
            if (book.containsKey(mint)) return
            book[mint] = coin
            while (book.size > MAX_COINS_8934) book.remove(book.keys.first())
        }
    }

    // ── state ──

    private val books = ConcurrentHashMap<String, LinkedHashMap<String, Coin8934>>()   // lane -> mint -> its first in-band read
    @Volatile private var proven: Map<String, List<Cell8934>> = emptyMap()
    @Volatile private var minedAtMs = 0L
    @Volatile private var ready = false
    @Volatile private var loading = false
    @Volatile private var loadNotBefore8961 = 0L
    private val rows = AtomicLong(0L)
    private val exitsGraded8971 = AtomicLong(0L)
    private val served = ConcurrentHashMap<String, AtomicLong>()
    private val huntDecisions8961 = ConcurrentHashMap<String, PocketHunt8961.Observation>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "lane-pocket-8934").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    private fun band(lane: String): Pair<Double, Double>? = try {
        com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mcapMin8323(lane) to com.lifecyclebot.engine.cortex.LaneCheatSheet8323.huntMcapMax8435(lane)
    } catch (_: Throwable) { null }

    /** One research row into its lane's book: the lane's designed horizon only, inside the lane's hunt band (crypto lanes unbanded). */
    private fun file(o: JSONObject) {
        // Historical files can contain numeric late marks written before 8957. Those are not four-hour evidence.
        if (o.optString("reason").startsWith("LATE_READ_8884:")) return
        val lane = o.optString("lane").trim().uppercase()
        if (lane.isBlank() || lane.startsWith("LPSTATE") || lane.startsWith("SETUP") || lane.startsWith("PLANWAIT") || lane.startsWith("ORIGIN")) return
        val h8991 = o.optLong("horizonMs", 0L)
        if (h8991 != horizon8934(lane)) { if (extended8991(lane, h8991)) fileExt8991(o, lane, h8991); return }   // V5.0.8991
        fun d(k: String) = if (o.isNull(k)) Double.NaN else o.optDouble(k, Double.NaN)
        val net = ResearchRows8877.rowNet8983(o)   // V5.0.8983 row reader; V5.0.8992 — an unpriced read is unknown, not filed
        val mcap = d("mcap")
        if (!net.isFinite()) return
        if (!lane.startsWith("CRYPTO_")) {
            val (lo, hi) = band(lane) ?: return
            if (!inBand8934(mcap, lo, hi)) return
        }
        val mint = o.optString("mint")
        if (mint.isBlank()) return
        // V5.0.8971 — a meme lane's coin is graded under the lane's own exits (stop first, then the doctrine line from +10 %),
        // exactly as the base pocket grades its coins (BasePocket8904.doctrineNet8910); a row without its low / peak read keeps
        // the hour's net. 24 Nov research (27.4 h): proven cells CORE 9 -> 28, MOONSHOT 15 -> 27, SHITCOIN 22 -> 28,
        // EXPRESS 15 -> 28, MANIPULATED 12 -> 27; every other lane none either way. Crypto lanes keep the held net.
        val graded = if (lane.startsWith("CRYPTO_")) net
            else BasePocket8904.doctrineNet8910(lane, d("gross"), d("minPct"), d("heldPeakPct"), d("cost"), net)
        if (graded != net) exitsGraded8971.incrementAndGet()
        val coin = Coin8934(o.optLong("t", 0L), features8934(mint, o.optString("stage"), o.optString("setup"), d("v3"), d("pred"), d("liq"), mcap), graded)
        val book = books.getOrPut(lane) { LinkedHashMap() }
        synchronized(book) {
            if (book.containsKey(mint)) return
            book[mint] = coin
            while (book.size > MAX_COINS_8934) book.remove(book.keys.first())
        }
        rows.incrementAndGet()
    }

    /** ResearchRows8877.note8877: every row as it is written (cheap text test first). */
    fun learnLine8934(line: String) {
        if (!wanted8991(line)) return
        ensureLoaded()
        io.execute {
            try { file(JSONObject(line)) } catch (_: Throwable) {}
            mineIfDue(System.currentTimeMillis())
        }
    }

    private fun ensureLoaded() {
        val now = System.currentTimeMillis()
        if (ready || loading || now < loadNotBefore8961) return
        val source = synchronized(this) {
            if (ready || loading || now < loadNotBefore8961) return
            val f = ResearchRows8877.file8877()
            if (f == null || (!f.isFile && seeded8990)) { loadNotBefore8961 = now + 5_000L; return }
            loading = true
            f
        }
        io.execute {
            try {
                // V5.0.8990 — the shipped rows first (once); with no device file yet they are the book
                val seedRows = if (!seeded8990) { seeded8990 = true; ResearchRows8877.forEachSeedLine8990 { l -> fileLine8990(l) } } else 0
                val loaded = if (source.isFile) loadFile8961(source) else seedRows > 0
                ready = loaded
                if (loaded) mine(System.currentTimeMillis()) else loadNotBefore8961 = System.currentTimeMillis() + 5_000L
            } finally { loading = false }
        }
    }

    @Volatile private var seeded8990 = false
    private fun fileLine8990(l: String) {
        if (wanted8991(l)) try { file(JSONObject(l)) } catch (_: Throwable) {}
    }

    /** A missing context/file or failed read is retryable; an existing empty research file is a legitimate empty book. */
    internal fun loadFile8961(source: java.io.File): Boolean = try {
        if (!source.isFile) false else {
            source.useLines { lines -> lines.forEach { l ->
                if (wanted8991(l))
                    try { file(JSONObject(l)) } catch (_: Throwable) {}
            } }
            true
        }
    } catch (_: Throwable) {
        try { PipelineHealthCollector.labelInc("LANE_POCKET_8934_LOAD_FAILED") } catch (_: Throwable) {}
        false
    }

    private fun mineIfDue(nowMs: Long) { if (nowMs - minedAtMs >= MINE_EVERY_MS_8934) mine(nowMs) }

    private fun mine(nowMs: Long) {
        minedAtMs = nowMs
        val out = HashMap<String, List<Cell8934>>()
        for ((lane, book) in books) {
            val coins = synchronized(book) { book.values.sortedBy { it.atMs } }
            if (coins.size < MIN_COINS_8934) continue
            val split = coins[coins.size / 2].atMs
            out[lane] = try { mine8934(coins, split) } catch (_: Throwable) { emptyList() }
        }
        proven = out
        // V5.0.8991 — and each lane's longer horizons, by the same rule on the held net
        val ext = HashMap<String, List<Cell8934>>()
        for ((key, book) in extBooks8991) {
            val coins = synchronized(book) { book.values.sortedBy { it.atMs } }
            if (coins.size < MIN_COINS_8934) continue
            ext[key] = try { mine8934(coins, coins[coins.size / 2].atMs) } catch (_: Throwable) { emptyList() }
        }
        provenExt8991 = ext
    }

    /**
     * CandidateAdmission8136: [ts] in [lane] as admission evidence when the coin sits in one of the lane's proven cells now
     * ([predPct] = the edge model's forecast for this decision); the best such cell, else null. Meme lanes only.
     */
    fun evidence8934(ts: TokenState, lane: String, predPct: Double?, nowMs: Long = System.currentTimeMillis()): CandidateAdmission8136.Evidence8136? {
        try {
            ensureLoaded()
            val l = lane.trim().uppercase()
            val cells = proven[l]
            val ext = EXT_HORIZONS_8991.flatMap { h -> provenExt8991[extKey8991(l, h)].orEmpty().map { it to h } }   // V5.0.8991
            if (!ready || (cells.isNullOrEmpty() && ext.isEmpty()) || l.startsWith("CRYPTO_")) return null
            val (lo, hi) = band(l) ?: return null
            if (!inBand8934(ts.lastMcap, lo, hi)) return null
            val raw = EdgeModel8537.rawFor8610(ts, l, nowMs)
            val stage = try { com.lifecyclebot.engine.TokenMetricStageRouter.snapshot(ts).stage.name } catch (_: Throwable) { "" }
            val setup = try { com.lifecyclebot.engine.ExitProfile7955.entrySetup7955(ts, l, nowMs) } catch (_: Throwable) { "" }
            val liq = (try { LiquidityTruth8037.hydrate8037(ts) } catch (_: Throwable) { ts.lastLiquidityUsd }).let { if (it.isFinite()) it else 0.0 }
            val feats = features8934(ts.mint, stage, setup, raw.v3, predPct ?: Double.NaN, liq, ts.lastMcap).toSet()
            val c = cells.orEmpty().firstOrNull { feats.containsAll(it.conds) } ?: run {
                // V5.0.8991 — in no cell of the lane's own hold: a cell proven at a longer horizon is its evidence, named with it
                val (e, h) = ext.firstOrNull { feats.containsAll(it.first.conds) } ?: return null
                servedExt8991.incrementAndGet()
                return CandidateAdmission8136.Evidence8136(KIND_8934, "LANE8934:${extKey8991(l, h)}:${e.id}", e.n, e.mean, e.z * e.se, runnerRate = e.runRate)
            }
            served.computeIfAbsent(l) { AtomicLong(0L) }.incrementAndGet()
            return CandidateAdmission8136.Evidence8136(KIND_8934, "LANE8934:$l:${c.id}", c.n, c.mean, c.z * c.se, runnerRate = c.runRate)
        } catch (_: Throwable) { return null }
    }

    /** Every candidate assessment, regardless of which evidence source wins, supplies its already-computed forecast. */
    fun noteHuntDecision8961(ts: TokenState, lane: String, stage: String, predPct: Double?, nowMs: Long) {
        if (!PocketHunt8961.fresh(ts.lastPriceUpdate, nowMs)) return
        val setup = try { com.lifecyclebot.engine.ExitProfile7955.entrySetup7955(ts, lane, nowMs) } catch (_: Throwable) { "" }
        val v3 = ts.lastV3Score?.toDouble() ?: ts.entryScore.takeIf { it != 0.0 } ?: Double.NaN
        rememberHuntDecision8961(lane, ts.mint, features8934(ts.mint, stage, setup, v3,
            predPct ?: Double.NaN, Double.NaN, Double.NaN).toSet(), ts.lastPriceUpdate, nowMs)
    }

    internal fun rememberHuntDecision8961(lane: String, mint: String, features: Set<String>, observedAtMs: Long, nowMs: Long) {
        if (!PocketHunt8961.fresh(observedAtMs, nowMs)) return
        val key = "${lane.trim().uppercase()}|$mint"
        if (huntDecisions8961.size >= 4_096 && !huntDecisions8961.containsKey(key)) {
            huntDecisions8961.entries.removeIf { !PocketHunt8961.fresh(it.value.observedAtMs, nowMs) }
            if (huntDecisions8961.size >= 4_096) return
        }
        huntDecisions8961[key] = PocketHunt8961.Observation(features, observedAtMs)
    }

    /** Read the lane's existing pocket book at discovery, including unresolved features for bounded hydration. */
    fun huntMatch8961(lane: String, mint: String, liq: Double, mcap: Double, observedAtMs: Long,
                      nowMs: Long): PocketHunt8961.Match {
        ensureLoaded()
        val cells = proven[lane.trim().uppercase()].orEmpty()
        if (!ready || cells.isEmpty()) return PocketHunt8961.Match.NONE
        val sweep = features8934(mint, "NA", "NA", Double.NaN, Double.NaN,
            liq.takeIf { it > 0.0 } ?: Double.NaN, mcap.takeIf { it > 0.0 } ?: Double.NaN).toSet()
        val observed = PocketHunt8961.withDecision(sweep, huntDecisions8961["${lane.trim().uppercase()}|$mint"], nowMs)
        return cells.map { PocketHunt8961.match(it.conds, observed, observedAtMs, nowMs) }
            .maxByOrNull { it.ordinal } ?: PocketHunt8961.Match.NONE
    }

    fun statusLine8934(): String {
        val coins = books.entries.sortedBy { it.key }.joinToString(",") { (k, v) -> "$k=${synchronized(v) { v.size }}" }.ifBlank { "-" }
        val p = proven.entries.sortedBy { it.key }.joinToString(" ") { (k, v) ->
            if (v.isEmpty()) "$k=none" else "$k=${v.size}[${v.first().id} n${v.first().n} ${"%+.1f".format(v.first().mean)}% ex3${"%+.1f".format(v.first().exTop3)} z${"%.2f".format(v.first().z)}]"
        }.ifBlank { "-" }
        val used = served.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        // V5.0.8991 — the longer horizons: coins filed and cells proven per "LANE@4h" / "LANE@24h"
        val extLine = extBooks8991.entries.sortedBy { it.key }.joinToString(",") { (k, v) ->
            val pr = provenExt8991[k].orEmpty()
            "$k=${synchronized(v) { v.size }}" + (if (pr.isEmpty()) "" else "/proven${pr.size}[${pr.first().id} n${pr.first().n} ${"%+.1f".format(pr.first().mean)}%]")
        }.ifBlank { "-" }
        return "lanePocket8934[ready=$ready rows=${rows.get()} exitsGraded8971=${exitsGraded8971.get()} coins{$coins} proven{$p} served{$used} extended8991{$extLine servedExt=${servedExt8991.get()}} minedAgoMin=${if (minedAtMs > 0L) (System.currentTimeMillis() - minedAtMs) / 60_000L else -1L} " +
            "rule=each lane's own in-band coins at its designed hold (BLUECHIP 4 h, others 1 h) under the lane's own exits (8971), one coin once; a single feature or a pair proves at 30+ coins, " +
            "both halves positive, mean without the best three > z SE (z deflated over the lane's cells); meme lanes carry it as market-cell evidence]"
    }

    internal fun provenExtForTest8991(lane: String, horizonMs: Long): List<Cell8934> = provenExt8991[extKey8991(lane, horizonMs)].orEmpty()
    internal fun resetForTest8934() { extBooks8991.clear(); provenExt8991 = emptyMap(); servedExt8991.set(0L); books.clear(); proven = emptyMap(); served.clear(); huntDecisions8961.clear(); rows.set(0L); ready = true; loading = false; loadNotBefore8961 = 0L; minedAtMs = 0L }
    internal fun fileForTest8934(o: JSONObject) = file(o)
    internal fun mineForTest8934() = mine(System.currentTimeMillis())
    internal fun provenForTest8934(lane: String): List<Cell8934> = proven[lane].orEmpty()
}

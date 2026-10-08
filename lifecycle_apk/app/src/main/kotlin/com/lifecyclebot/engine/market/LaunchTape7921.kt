package com.lifecyclebot.engine.market

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.cortex.CortexLedger7885
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7921 — Cortex v16: the launch tape. Catch a runner in its first minutes.
 *
 * 5.0.7914: every pump.fun create was judged at age 0 with no tape (bp=50/sp=50
 * defaults, FLOW_NONE, TOO_FEW_BARS), and nothing re-read it once its first
 * buyers arrived. "bird" was refused at birth and ran. WhaleDetector's launch
 * flow keeps only the last 90 seconds; nothing kept the tape SINCE BIRTH, which
 * is where a launch shows whether a crowd is forming.
 *
 * Per launch (from the unified Helius/PumpPortal tape, keyless via Helius):
 * crowd buyers (not the creator), crowd net SOL, buyers per minute, buy share,
 * largest-buyer and top-3 share, dev sold, price vs first print and vs peak.
 *
 * Heat = crowd buyers per minute x buy share x (1 - largest buyer share), x0.2
 * after a dev sell. A launch is PROMOTED — re-evaluated by the full pipeline
 * immediately, tagged LAUNCH_HEAT_7921, and protected from Helius eviction — the
 * moment its tape clears the bar. The bar is educated, then learned:
 *
 *   prior ("crowd forming"): age 1-20 min, >= 15 crowd buyers, >= 3 SOL crowd
 *   net, buy share >= 60%, largest buyer <= 30%, top-3 <= 55%, no dev sell,
 *   not more than 35% off its peak.
 *   learned: every launch with a tape is checkpointed once at 3 min and graded
 *   on its price 15 min later (runner = +100% peak). By heat bin: a bin proven
 *   positive (n >= 30, mean - SE > +2%) promotes by itself; if the prior's own
 *   graded record is proven negative (n >= 30, mean + SE < -2%) the prior stops
 *   promoting. Promotion only orders and re-evaluates: every gate still decides.
 */
object LaunchTape7921 {
    private const val TTL_MS = 45L * 60_000L
    private const val MAX_RECS = 3_000
    private const val WALLET_CAP = 400
    private const val PROMOTE_MIN_AGE_MS = 60_000L
    private const val PROMOTE_MAX_AGE_MS = 20L * 60_000L
    private const val CHECKPOINT_AGE_MS = 3L * 60_000L
    private const val GRADE_AFTER_MS = 15L * 60_000L
    private const val EVICT_GRACE_MS = 2L * 60_000L
    private const val PROOF_N = 30
    private const val PROOF_PCT = 2.0
    private const val PERSIST_KEY = "LAUNCH_TAPE_7921"

    /** Heat bins for the learned bar. */
    val HEAT_EDGES = doubleArrayOf(0.5, 1.0, 2.0, 4.0, 8.0)

    class Feat(
        val ageMin: Double, val crowdBuyers: Int, val crowdNetSol: Double, val buyersPerMin: Double,
        val buySharePct: Double, val largestBuyerPct: Double, val top3Pct: Double, val devSold: Boolean,
        val fromFirstPct: Double, val fromPeakPct: Double, val heat: Double,
    )

    private class Rec(val birthMs: Long) {
        var symbol = ""
        var name = ""
        var buys = 0
        var sells = 0
        var crowdBuySol = 0.0
        var sellSol = 0.0
        val buyerSol = HashMap<String, Double>()
        var devSold = false
        var firstPx = 0.0
        var lastPx = 0.0
        var peakPx = 0.0
        var promotedAtMs = 0L
        var checkpointed = false
    }

    private class Checkpoint(val mint: String, val bin: Int, val priorPass: Boolean, val px: Double, val atMs: Long) {
        var peakPx = px
    }

    private val recs = ConcurrentHashMap<String, Rec>()
    private val checkpoints = ConcurrentHashMap<String, Checkpoint>()
    private val binBook = HashMap<Int, CortexLedger7885.Stat>()
    private val priorBook = CortexLedger7885.Stat()
    private val promotedBook = CortexLedger7885.Stat()
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val sinceGraded = AtomicLong(0)
    @Volatile private var loaded = false
    @Volatile private var onPromote: ((String, String, String) -> Unit)? = null

    private fun inc(k: String) { counters.computeIfAbsent(k) { AtomicLong(0) }.incrementAndGet() }

    /** BotService.wireExternalStreams: (mint, symbol, name) -> re-evaluate now. */
    fun setOnPromote(cb: (String, String, String) -> Unit) { onPromote = cb }

    /** A pump.fun create (PumpPortal). Birth is the curve's create time when known. */
    fun onCreate(mint: String, symbol: String, name: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        val born = try { com.lifecyclebot.network.PumpCurveKeys7269.createdAtMs7280(mint) } catch (_: Throwable) { null } ?: nowMs
        val r = recs.computeIfAbsent(mint) { Rec(born) }
        r.symbol = symbol
        r.name = name
        if (recs.size > MAX_RECS) recs.entries.removeIf { nowMs - it.value.birthMs > TTL_MS }
    }

    /** DataOrchestrator.onTapeTrade7773: one trade from the unified tape. */
    fun onTrade(mint: String, wallet: String, sol: Double, isBuy: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val r = recs[mint] ?: return   // launches only: a create must have been seen
        if (nowMs - r.birthMs > TTL_MS || !sol.isFinite() || sol <= 0.0) return
        val dev = try { com.lifecyclebot.engine.OperatorRegistry.getDevWallet(mint) } catch (_: Throwable) { null }
        synchronized(r) {
            if (isBuy) {
                r.buys++
                if (wallet.isNotBlank() && wallet != dev) {
                    r.crowdBuySol += sol
                    if (r.buyerSol.size < WALLET_CAP || r.buyerSol.containsKey(wallet)) r.buyerSol[wallet] = (r.buyerSol[wallet] ?: 0.0) + sol
                }
            } else {
                r.sells++
                r.sellSol += sol
                if (dev != null && wallet == dev) r.devSold = true
            }
        }
        maybePromote(mint, r, nowMs)
    }

    /** DataOrchestrator.onTradePrint7819: the executed price (SOL per token). */
    fun onPrice(mint: String, priceSol: Double) {
        if (!priceSol.isFinite() || priceSol <= 0.0) return
        recs[mint]?.let { r -> synchronized(r) { if (r.firstPx <= 0.0) r.firstPx = priceSol; r.lastPx = priceSol; if (priceSol > r.peakPx) r.peakPx = priceSol } }
        checkpoints[mint]?.let { c -> if (priceSol > c.peakPx) c.peakPx = priceSol }
    }

    /** Pure: heat from the tape (crowd buyers per minute, weighted by flow quality). */
    fun heatOf(crowdBuyers: Int, ageMin: Double, buySharePct: Double, largestBuyerPct: Double, devSold: Boolean): Double {
        if (crowdBuyers <= 0 || !ageMin.isFinite()) return 0.0
        val perMin = crowdBuyers / ageMin.coerceAtLeast(1.0)
        return perMin * (buySharePct / 100.0).coerceIn(0.0, 1.0) * (1.0 - largestBuyerPct / 100.0).coerceIn(0.0, 1.0) * (if (devSold) 0.2 else 1.0)
    }

    /** Pure: the educated prior bar ("a crowd is forming"). */
    fun priorPass(f: Feat): Boolean =
        f.ageMin >= 1.0 && f.ageMin <= 20.0 && f.crowdBuyers >= 15 && f.crowdNetSol >= 3.0 && f.buySharePct >= 60.0 &&
            f.largestBuyerPct <= 30.0 && f.top3Pct <= 55.0 && !f.devSold && f.fromPeakPct >= -35.0

    /** Pure: is a graded record proven positive / negative? */
    fun provenPositive(s: CortexLedger7885.Stat): Boolean =
        s.n >= PROOF_N && s.n > 1.0 && s.mean() - kotlin.math.sqrt(s.variance() / s.n) > PROOF_PCT

    fun provenNegative(s: CortexLedger7885.Stat): Boolean =
        s.n >= PROOF_N && s.n > 1.0 && s.mean() + kotlin.math.sqrt(s.variance() / s.n) < -PROOF_PCT

    fun features(mint: String, nowMs: Long = System.currentTimeMillis()): Feat? {
        val r = recs[mint] ?: return null
        return synchronized(r) {
            val ageMin = (nowMs - r.birthMs) / 60_000.0
            val crowd = r.buyerSol.size
            val ranked = r.buyerSol.values.sortedDescending()
            val total = r.crowdBuySol
            val largest = if (total > 0.0) (ranked.firstOrNull() ?: 0.0) / total * 100.0 else 0.0
            val top3 = if (total > 0.0) ranked.take(3).sum() / total * 100.0 else 0.0
            val share = if (total + r.sellSol > 0.0) total / (total + r.sellSol) * 100.0 else 50.0
            Feat(
                ageMin = ageMin, crowdBuyers = crowd, crowdNetSol = total - r.sellSol,
                buyersPerMin = crowd / ageMin.coerceAtLeast(1.0), buySharePct = share,
                largestBuyerPct = largest, top3Pct = top3, devSold = r.devSold,
                fromFirstPct = if (r.firstPx > 0.0 && r.lastPx > 0.0) (r.lastPx / r.firstPx - 1.0) * 100.0 else Double.NaN,
                fromPeakPct = if (r.peakPx > 0.0 && r.lastPx > 0.0) (r.lastPx / r.peakPx - 1.0) * 100.0 else 0.0,
                heat = heatOf(crowd, ageMin, share, largest, r.devSold),
            )
        }
    }

    private fun binOf(heat: Double): Int = CortexLedger7885.binOf(HEAT_EDGES, heat)

    private fun maybePromote(mint: String, r: Rec, nowMs: Long) {
        val age = nowMs - r.birthMs
        if (r.promotedAtMs > 0L || age < PROMOTE_MIN_AGE_MS || age > PROMOTE_MAX_AGE_MS) return
        val f = features(mint, nowMs) ?: return
        ensureLoaded()
        val (prior, learned) = synchronized(this) {
            (priorPass(f) && !provenNegative(priorBook)) to (binBook[binOf(f.heat)]?.let { provenPositive(it) } == true && !f.devSold)
        }
        if (!prior && !learned) return
        r.promotedAtMs = nowMs
        val why = if (learned) "LEARNED_HEAT_BIN" else "PRIOR_CROWD_FORMING"
        inc("PROMOTED_$why")
        try {
            PipelineHealthCollector.labelInc("LAUNCH_TAPE_7921_PROMOTED")
            ForensicLogger.lifecycle(
                "LAUNCH_TAPE_7921_PROMOTED",
                "mint=${mint.take(10)} sym=${r.symbol} why=$why age=${"%.1f".format(f.ageMin)}m crowd=${f.crowdBuyers} net=${"%.2f".format(f.crowdNetSol)}SOL " +
                    "buyShare=${f.buySharePct.toInt()}% largest=${f.largestBuyerPct.toInt()}% top3=${f.top3Pct.toInt()}% heat=${"%.2f".format(f.heat)} fromFirst=${"%+.0f".format(f.fromFirstPct)}%",
            )
        } catch (_: Throwable) {}
        try { onPromote?.invoke(mint, r.symbol, r.name) } catch (_: Throwable) {}
    }

    /** Was this mint promoted (protect it from Helius eviction; voters read it)? */
    fun promoted(mint: String): Boolean = (recs[mint]?.promotedAtMs ?: 0L) > 0L

    /**
     * HeliusWebSocket eviction: the coldest launch past its grace period goes
     * first, so hot launches keep their tape. Null = no opinion (caller falls back).
     */
    fun evictionVictim(candidates: Collection<String>, nowMs: Long = System.currentTimeMillis()): String? =
        candidates.asSequence()
            .map { m -> m to recs[m] }
            .filter { (_, r) -> r == null || (nowMs - r.birthMs > EVICT_GRACE_MS && r.promotedAtMs == 0L) }
            .minByOrNull { (m, r) -> if (r == null) -1.0 else features(m, nowMs)?.heat ?: 0.0 }
            ?.first

    /** ExitRegret7752.tick clock: checkpoints at 3 min, grades at +15 min, expiry. */
    fun tick(nowMs: Long = System.currentTimeMillis()) {
        ensureLoaded()
        for ((mint, r) in recs.entries) {
            if (nowMs - r.birthMs > TTL_MS) { recs.remove(mint); continue }
            if (!r.checkpointed && nowMs - r.birthMs >= CHECKPOINT_AGE_MS && r.lastPx > 0.0 && r.buys >= 3) {
                r.checkpointed = true
                val f = features(mint, nowMs) ?: continue
                checkpoints[mint] = Checkpoint(mint, binOf(f.heat), priorPass(f), r.lastPx, nowMs)
                inc("CHECKPOINTED")
            }
        }
        for ((mint, c) in checkpoints.entries) {
            if (nowMs - c.atMs < GRADE_AFTER_MS) continue
            checkpoints.remove(mint)
            val px = recs[mint]?.lastPx ?: continue
            if (px <= 0.0 || c.px <= 0.0) continue
            val ret = ((px / c.px - 1.0) * 100.0).coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX)
            val runner = c.peakPx / c.px >= 2.0
            val wasPromoted = promoted(mint)
            synchronized(this) {
                binBook.getOrPut(c.bin) { CortexLedger7885.Stat() }.add(ret, runner)
                if (c.priorPass) priorBook.add(ret, runner)
                if (wasPromoted) promotedBook.add(ret, runner)
            }
            inc("GRADED")
            if (sinceGraded.incrementAndGet() >= 20) { sinceGraded.set(0); persist() }
        }
        if (checkpoints.size > 2_000) checkpoints.entries.removeIf { nowMs - it.value.atMs > GRADE_AFTER_MS * 2 }
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            try {
                val o = org.json.JSONObject(LearningPersistence.load(PERSIST_KEY) ?: return)
                o.optJSONObject("bins")?.let { j -> for (k in j.keys()) k.toIntOrNull()?.let { b -> binBook[b] = CortexLedger7885.Stat().also { it.decode(j.optString(k)) } } }
                o.optString("prior").takeIf { it.isNotBlank() }?.let { priorBook.decode(it) }
                o.optString("promoted").takeIf { it.isNotBlank() }?.let { promotedBook.decode(it) }
            } catch (_: Throwable) {}
        }
    }

    private fun persist() {
        try {
            val json = synchronized(this) {
                org.json.JSONObject()
                    .put("bins", org.json.JSONObject().also { j -> binBook.forEach { (k, v) -> j.put(k.toString(), v.encode()) } })
                    .put("prior", priorBook.encode()).put("promoted", promotedBook.encode()).toString()
            }
            LearningPersistence.save(PERSIST_KEY, json)
        } catch (_: Throwable) {}
    }

    private fun fmt(s: CortexLedger7885.Stat): String =
        if (s.n < 1.0) "-" else "n${s.n.toInt()}/${"%+.1f".format(s.mean())}%/run${(s.runnerRate() * 100).toInt()}%"

    fun statusLine(): String {
        ensureLoaded()
        return synchronized(this) {
            "launches=${recs.size} checkpoints=${checkpoints.size} actions=${counters.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }} " +
                "priorBar=${fmt(priorBook)}${if (provenNegative(priorBook)) " SUSPENDED" else ""} promoted=${fmt(promotedBook)} " +
                "byHeat15m=[${(0..HEAT_EDGES.size).joinToString(" ") { b -> "h$b:${binBook[b]?.let { fmt(it) + if (provenPositive(it)) "*" else "" } ?: "-"}" }}]"
        }
    }
}

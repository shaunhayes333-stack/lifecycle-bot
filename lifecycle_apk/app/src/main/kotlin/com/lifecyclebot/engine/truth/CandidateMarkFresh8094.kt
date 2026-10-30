package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8094 §NO_CANDIDATE_DECIDES_ON_AN_OLD_PRICE.
 *
 * 5.0.8093: CYCLIC refused 977 candidates as PRICE_NOT_FRESH (price older than 90 s) and missed a +1,185% runner.
 * Watchlist prices were only refreshed when the round-robin pair poll reached the token. Owner: "no data should be
 * 90 seconds". Every candidate a lane brain evaluates now asks for a fresh mark when its price is older than
 * [FRESH_MS_8094]: stale mints are batched (50 per call, one call per [BATCH_GAP_MS]) into Jupiter's price API and
 * written back before the next cycle. Held positions are never touched here — their exit marks are route-locked.
 *
 * DexScreener's batch tokens endpoint (30 mints per call) supplies the price; Jupiter prices what it does not list.
 * Only prices are written: the classifier's flow / change inputs come from the bot's own tape (entry repair).
 */
object CandidateMarkFresh8094 {
    const val FRESH_MS_8094 = 15_000L
    private const val BATCH_GAP_MS = 1_500L
    private const val WANTED_TTL_MS = 60_000L
    private val wanted = ConcurrentHashMap<String, Long>()
    private val inFlight = AtomicBoolean(false)
    @Volatile private var lastBatchMs = 0L
    private val requested = AtomicLong(0)
    private val refreshed = AtomicLong(0)
    private val batches = AtomicLong(0)
    private val pool = java.util.concurrent.ThreadPoolExecutor(0, 1, 30L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.SynchronousQueue<Runnable>(), { r -> Thread(r, "candidate-mark-8094").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.DiscardPolicy())

    /** Pure: is this price missing or older than [FRESH_MS_8094]? */
    fun stale8094(lastPrice: Double, lastUpdateMs: Long, nowMs: Long): Boolean =
        !(lastPrice.isFinite() && lastPrice > 0.0) || lastUpdateMs <= 0L || nowMs - lastUpdateMs > FRESH_MS_8094

    /** Pure: the market cap moved with the new price, so price x supply stays one identity. */
    fun rescaledMcap8094(oldMcap: Double, oldPrice: Double, newPrice: Double): Double =
        if (oldMcap > 0.0 && oldPrice > 0.0 && newPrice > 0.0) oldMcap * (newPrice / oldPrice) else oldMcap

    /** SpecialistBrainBridge7542.evaluate: request a fresh mark / inputs for a stale candidate. True when requested. */
    fun ensureFresh8094(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Boolean {
        val mint = ts.mint
        if (mint.length !in 32..44 || ts.position.isOpen) return false
        if (!stale8094(ts.lastPrice, ts.lastPriceUpdate, nowMs)) return false
        if (wanted.putIfAbsent(mint, nowMs) == null) requested.incrementAndGet()
        if (wanted.size > 2_000) wanted.entries.removeIf { nowMs - it.value > WANTED_TTL_MS }
        maybeRun(nowMs)
        return true
    }

    /**
     * V5.0.8287 — the stale candidates join the ONE DexScreener pair batch lane (DexBatchLane8287: up to 30 mints per call,
     * <= 60 calls / min, shared host budget) instead of a second 30-mint reader on the same host (8286: dexReads=573 beside
     * a starved lane). The lane's answer comes back through [onLaneAnswer8287]; whatever it did not price goes to Jupiter.
     */
    private fun maybeRun(nowMs: Long) {
        if (nowMs - lastBatchMs < BATCH_GAP_MS) return
        lastBatchMs = nowMs
        val batch = wanted.entries.sortedBy { it.value }.take(30).map { it.key }
        batch.forEach { wanted.remove(it) }
        if (batch.isEmpty()) return
        batches.incrementAndGet()
        if (laneAsked8287.size > 2_000) laneAsked8287.entries.removeIf { nowMs - it.value > LANE_TTL_MS_8287 }
        for (m in batch) {
            laneAsked8287[m] = nowMs
            try { com.lifecyclebot.engine.DexBatchLane8287.enqueue8287(m) } catch (_: Throwable) {}
        }
    }

    private const val LANE_TTL_MS_8287 = 60_000L
    private val laneAsked8287 = ConcurrentHashMap<String, Long>()

    /**
     * V5.0.8359 — KeylessMarks8359: the shadow books' wanted mints go straight onto the DexScreener pair batch lane (30 per
     * call, its own cadence and the shared host budget); the answer is applied by [onLaneAnswer8287] like any candidate's.
     */
    fun requestLane8359(mints: List<String>, nowMs: Long = System.currentTimeMillis()): Int {
        var n = 0
        if (laneAsked8287.size > 2_000) laneAsked8287.entries.removeIf { nowMs - it.value > LANE_TTL_MS_8287 }
        for (m in mints) {
            if (m.length !in 32..44) continue
            laneAsked8287[m] = nowMs
            try { if (com.lifecyclebot.engine.DexBatchLane8287.enqueue8287(m)) n++ } catch (_: Throwable) {}
        }
        if (n > 0) requested.addAndGet(n.toLong())
        return n
    }

    /** V5.0.8359 — chain-read marks (the bonding curve's own reserves) for wanted, not-held candidates. */
    fun applyMarks8359(prices: Map<String, Double>, source: String) = apply8094(prices.filterValues { it.isFinite() && it > 0.0 }, source)

    /**
     * V5.0.8287 — DexBatchLane8287: one sent batch's answer. Candidates this refresh asked for are priced from it (the
     * same best-pair price getBestPair serves); those it did not price are asked of Jupiter, off the lane's thread.
     */
    fun onLaneAnswer8287(batch: List<String>, prices: Map<String, Double>) {
        val mine = batch.filter { laneAsked8287.remove(it) != null }
        if (mine.isEmpty()) return
        val priced = prices.filterKeys { it in mine }.filterValues { it.isFinite() && it > 0.0 }
        // V5.0.8459 — the pair lane's answer is DexScreener's price and is filed under its own name: it was written with the
        // Jupiter default (8457 identity unitBreak{OTHER@JUPITER_CANDIDATE_REFRESH_8094=155} were DexScreener pair prices).
        if (priced.isNotEmpty()) { dexReads.addAndGet(priced.size.toLong()); apply8094(priced, DEX_LANE_SOURCE_8459) }
        val unpriced = mine.filter { it !in priced.keys }
        if (unpriced.isEmpty() || !inFlight.compareAndSet(false, true)) return
        try {
            pool.execute {
                try { apply8094(com.lifecyclebot.engine.sell.PriceResolverFallback.jupiterBatchPrices7737(unpriced)) } catch (_: Throwable) {
                } finally { inFlight.set(false) }
            }
        } catch (_: Throwable) { inFlight.set(false) }
    }

    const val DEX_LANE_SOURCE_8459 = "DEXSCREENER_LANE_8287"

    // ── V5.0.8459 §A_CHAIN_PRINT_OUTRANKS_A_LAGGING_AGGREGATOR ──
    // Owner: "there isn't meant to be ... a stale price, a stale mark, nothing". A candidate's price was overwritten by the
    // DexScreener pair lane / Jupiter batch whenever the held value was over 1 s old, so a chain-exact curve or pool print
    // from seconds ago was replaced by an aggregator's lagging price stamped as new.
    private const val CHAIN_KEEP_MS_8459 = 30_000L
    private val chainKept8459 = AtomicLong(0)

    /** Pure: the coin's current price is its own chain print (StopFeed8327.chainMark8327) under [CHAIN_KEEP_MS_8459] old. */
    fun chainHeld8459(currentSource: String?, currentAtMs: Long, nowMs: Long): Boolean =
        currentAtMs > 0L && nowMs - currentAtMs in 0L until CHAIN_KEEP_MS_8459 && com.lifecyclebot.engine.StopFeed8327.chainMark8327(currentSource)

    private fun apply8094(prices: Map<String, Double>, source8359: String = "JUPITER_CANDIDATE_REFRESH_8094") {
        if (prices.isEmpty()) return
        val tokens = com.lifecyclebot.engine.BotService.status.tokens
        val at = System.currentTimeMillis()
        for ((mint, px) in prices) {
            val ts = try { synchronized(tokens) { tokens[mint] } } catch (_: Throwable) { null } ?: continue
            val applied = synchronized(ts) {
                val fresher = ts.lastPriceUpdate > 0L && at - ts.lastPriceUpdate < 1_000L
                val suspect = ts.lastPrice > 0.0 && ForwardReturnLabeler7731.basisSuspect7738(ts.lastPrice, px)
                // V5.0.8459 — an aggregator price never replaces the coin's own chain print while that print is recent.
                val chainHeld = !com.lifecyclebot.engine.StopFeed8327.chainMark8327(source8359) &&
                    chainHeld8459(ts.lastPriceSource, ts.lastPriceUpdate, at)
                if (chainHeld) chainKept8459.incrementAndGet()
                if (ts.position.isOpen || fresher || suspect || chainHeld) false else {
                    ts.lastMcap = rescaledMcap8094(ts.lastMcap, ts.lastPrice, px)
                    ts.lastPrice = px
                    ts.lastPriceUpdate = at
                    ts.lastPriceSource = source8359
                    true
                }
            }
            if (!applied) continue
            refreshed.incrementAndGet()
            // Entry repair: an aggregator price is never written into the mark bars — mixed-source ticks there
            // drew moves that never happened.
        }
        try { PipelineHealthCollector.labelInc("CANDIDATE_MARK_REFRESHED_8094") } catch (_: Throwable) {}
    }

    /** One DexScreener pair reading for a mint (the most liquid Solana pair whose base token is the mint). */
    data class DexRead(val priceUsd: Double, val buys5m: Int, val sells5m: Int, val chg5m: Double, val chg1h: Double, val liqUsd: Double)

    private val dexReads = AtomicLong(0)

    /** Pure: DexScreener tokens JSON -> the most liquid Solana pair per requested mint. */
    fun parseDex8094(body: String, mints: Set<String>): Map<String, DexRead> {
        val pairs = try { org.json.JSONObject(body).optJSONArray("pairs") } catch (_: Throwable) { null } ?: return emptyMap()
        val best = HashMap<String, Pair<Double, DexRead>>()
        for (i in 0 until pairs.length()) {
            val p = pairs.optJSONObject(i) ?: continue
            if (!p.optString("chainId").equals("solana", true)) continue
            val mint = p.optJSONObject("baseToken")?.optString("address").orEmpty()
            if (mint !in mints) continue
            val price = p.optString("priceUsd", "").toDoubleOrNull() ?: continue
            if (!price.isFinite() || price <= 0.0) continue
            val m5 = p.optJSONObject("txns")?.optJSONObject("m5")
            val chg = p.optJSONObject("priceChange")
            val liq = p.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0
            val read = DexRead(price, m5?.optInt("buys", -1) ?: -1, m5?.optInt("sells", -1) ?: -1,
                chg?.optDouble("m5", Double.NaN) ?: Double.NaN, chg?.optDouble("h1", Double.NaN) ?: Double.NaN,
                if (liq.isFinite()) liq else 0.0)
            val cur = best[mint]
            if (cur == null || read.liqUsd > cur.first) best[mint] = read.liqUsd to read
        }
        return best.mapValues { it.value.second }
    }

    // V5.0.8094 entry repair — only the PRICE is taken (onLaneAnswer8287). DexScreener's 5-minute transaction counts /
    // change are not written as the classifier's observed inputs: filling them turned thousands of coins the bot had no
    // native tape for from DATA_INCOMPLETE (refused live) into "classified" setups matched against records proven on the
    // native tape. (The lane's pair flow goes to PairFlow8169 with its source named, read only where a reading is absent.)

    fun statusLine(): String =
        "CANDIDATE_MARK_FRESH_8094 freshMs=$FRESH_MS_8094 requested=${requested.get()} refreshed=${refreshed.get()} batches=${batches.get()} dexReads=${dexReads.get()} waiting=${wanted.size} laneAsked8287=${laneAsked8287.size} chainKept8459=${chainKept8459.get()}"
}

package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8094 §NO_CANDIDATE_DECIDES_ON_AN_OLD_PRICE (and §THE_SETUP_INPUTS_COME_WITH_IT).
 *
 * 5.0.8093: CYCLIC refused 977 candidates as PRICE_NOT_FRESH (price older than 90 s) and missed a +1,185% runner.
 * Watchlist prices were only refreshed when the round-robin pair poll reached the token. Owner: "no data should be
 * 90 seconds". Every candidate a lane brain evaluates now asks for a fresh mark when its price is older than
 * [FRESH_MS_8094]: stale mints are batched (50 per call, one call per [BATCH_GAP_MS]) into Jupiter's price API and
 * written back before the next cycle. Held positions are never touched here — their exit marks are route-locked.
 *
 * The same batch fills the setup classifier's CORE inputs: 8093 classified most candidates DATA_INCOMPLETE (missing
 * bp 7,562 / chg 7,765 / shape 7,126 on SHITCOIN alone), so LIVE_PROVEN_SETUP_ONLY refused them unclassified (4,110).
 * DexScreener's batch tokens endpoint (30 mints per call) returns the 5-minute buys / sells, the 5-minute and 1-hour
 * change, liquidity and price in one answer; a candidate whose buy pressure or change is unobserved for
 * [INPUT_STALE_MS] joins the batch. Jupiter prices whatever DexScreener does not list. Values are written only as
 * observations (stamped), never as defaults; liquidity only fills an unknown (0), never lowers a reading.
 */
object CandidateMarkFresh8094 {
    const val FRESH_MS_8094 = 15_000L
    const val INPUT_STALE_MS = 10L * 60_000L
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

    /** Pure: are the classifier's flow / change inputs unobserved or older than [INPUT_STALE_MS]? */
    fun inputsStale8094(bpObservedAtMs: Long, chgObservedAtMs: Long, nowMs: Long): Boolean =
        bpObservedAtMs <= 0L || chgObservedAtMs <= 0L || nowMs - bpObservedAtMs > INPUT_STALE_MS || nowMs - chgObservedAtMs > INPUT_STALE_MS

    /** SpecialistBrainBridge7542.evaluate: request a fresh mark / inputs for a stale candidate. True when requested. */
    fun ensureFresh8094(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Boolean {
        val mint = ts.mint
        if (mint.length !in 32..44 || ts.position.isOpen) return false
        if (!stale8094(ts.lastPrice, ts.lastPriceUpdate, nowMs) &&
            !inputsStale8094(ts.buyPressureObservedAtMs8084, ts.priceChangeObservedAtMs8084, nowMs)) return false
        if (wanted.putIfAbsent(mint, nowMs) == null) requested.incrementAndGet()
        if (wanted.size > 2_000) wanted.entries.removeIf { nowMs - it.value > WANTED_TTL_MS }
        maybeRun(nowMs)
        return true
    }

    private fun maybeRun(nowMs: Long) {
        if (nowMs - lastBatchMs < BATCH_GAP_MS || !inFlight.compareAndSet(false, true)) return
        lastBatchMs = nowMs
        val batch = wanted.entries.sortedBy { it.value }.take(30).map { it.key }
        batch.forEach { wanted.remove(it) }
        if (batch.isEmpty()) { inFlight.set(false); return }
        try {
            pool.execute {
                try {
                    batches.incrementAndGet()
                    val dex = dexBatch8094(batch)
                    applyDex8094(dex)
                    val unpriced = batch.filter { it !in dex.keys }
                    if (unpriced.isNotEmpty()) apply8094(com.lifecyclebot.engine.sell.PriceResolverFallback.jupiterBatchPrices7737(unpriced))
                } catch (_: Throwable) {
                } finally { inFlight.set(false) }
            }
        } catch (_: Throwable) { inFlight.set(false) }
    }

    private fun apply8094(prices: Map<String, Double>) {
        if (prices.isEmpty()) return
        val tokens = com.lifecyclebot.engine.BotService.status.tokens
        val at = System.currentTimeMillis()
        for ((mint, px) in prices) {
            val ts = try { synchronized(tokens) { tokens[mint] } } catch (_: Throwable) { null } ?: continue
            val applied = synchronized(ts) {
                val fresher = ts.lastPriceUpdate > 0L && at - ts.lastPriceUpdate < 1_000L
                val suspect = ts.lastPrice > 0.0 && ForwardReturnLabeler7731.basisSuspect7738(ts.lastPrice, px)
                if (ts.position.isOpen || fresher || suspect) false else {
                    ts.lastMcap = rescaledMcap8094(ts.lastMcap, ts.lastPrice, px)
                    ts.lastPrice = px
                    ts.lastPriceUpdate = at
                    ts.lastPriceSource = "JUPITER_CANDIDATE_REFRESH_8094"
                    true
                }
            }
            if (!applied) continue
            refreshed.incrementAndGet()
            try { MarkBars7948.note7948(mint, px, at) } catch (_: Throwable) {}
        }
        try { PipelineHealthCollector.labelInc("CANDIDATE_MARK_REFRESHED_8094") } catch (_: Throwable) {}
    }

    /** One DexScreener pair reading for a mint (the most liquid Solana pair whose base token is the mint). */
    data class DexRead(val priceUsd: Double, val buys5m: Int, val sells5m: Int, val chg5m: Double, val chg1h: Double, val liqUsd: Double)

    private val http by lazy {
        com.lifecyclebot.network.SharedHttpClient.builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
    private val dexReads = AtomicLong(0)
    private val inputsFilled = AtomicLong(0)

    private fun dexBatch8094(mints: List<String>): Map<String, DexRead> = try {
        val req = okhttp3.Request.Builder().url("https://api.dexscreener.com/latest/dex/tokens/" + mints.joinToString(",")).build()
        com.lifecyclebot.engine.HealthAwareHttp.execute(http, req, host = "dexscreener").use { resp ->
            if (!resp.isSuccessful) emptyMap() else parseDex8094(resp.body?.string().orEmpty(), mints.toSet())
        }
    } catch (_: Throwable) { emptyMap() }

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

    private fun applyDex8094(reads: Map<String, DexRead>) {
        if (reads.isEmpty()) return
        dexReads.addAndGet(reads.size.toLong())
        apply8094(reads.mapValues { it.value.priceUsd })
        val tokens = com.lifecyclebot.engine.BotService.status.tokens
        val at = System.currentTimeMillis()
        for ((mint, r) in reads) {
            val ts = try { synchronized(tokens) { tokens[mint] } } catch (_: Throwable) { null } ?: continue
            var filled = false
            synchronized(ts) {
                if (!ts.position.isOpen) {
                    val n = r.buys5m + r.sells5m
                    if (r.buys5m >= 0 && r.sells5m >= 0 && n > 0) {
                        ts.lastBuyPressurePct = r.buys5m * 100.0 / n
                        ts.lastSellPressurePct = r.sells5m * 100.0 / n
                        ts.buyPressureObservedAtMs8084 = at
                        filled = true
                    }
                    if (r.chg5m.isFinite()) {
                        ts.lastPriceChange5m = r.chg5m
                        if (r.chg1h.isFinite()) ts.lastPriceChange1h = r.chg1h
                        ts.priceChangeObservedAtMs8084 = at
                        filled = true
                    }
                    if (r.liqUsd > 0.0 && !(ts.lastLiquidityUsd > 0.0)) ts.lastLiquidityUsd = r.liqUsd
                }
            }
            if (filled) inputsFilled.incrementAndGet()
        }
        try { PipelineHealthCollector.labelInc("CANDIDATE_SETUP_INPUTS_FILLED_8094") } catch (_: Throwable) {}
    }

    fun statusLine(): String =
        "CANDIDATE_MARK_FRESH_8094 freshMs=$FRESH_MS_8094 requested=${requested.get()} refreshed=${refreshed.get()} batches=${batches.get()} dexReads=${dexReads.get()} inputsFilled=${inputsFilled.get()} waiting=${wanted.size}"
}

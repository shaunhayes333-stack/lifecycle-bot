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
        java.util.concurrent.ArrayBlockingQueue<Runnable>(1), { r -> Thread(r, "candidate-mark-8094").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy())

    /** Pure: is this price missing or older than [FRESH_MS_8094]? */
    fun stale8094(lastPrice: Double, lastUpdateMs: Long, nowMs: Long): Boolean =
        !(lastPrice.isFinite() && lastPrice > 0.0) || lastUpdateMs <= 0L || nowMs - lastUpdateMs !in 0L..FRESH_MS_8094

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
        } catch (_: Throwable) {
            // A rejected executor submission did no work. Preserve demand and
            // release ownership so the next cycle can retry this exact batch.
            batch.forEach { wanted.putIfAbsent(it, nowMs) }
            inFlight.set(false)
            try { PipelineHealthCollector.labelInc("CANDIDATE_MARK_QUEUE_REJECTED_8239") } catch (_: Throwable) {}
        }
    }

    private fun apply8094(prices: Map<String, Double>, source: String = "JUPITER_CANDIDATE_REFRESH_8094") {
        if (prices.isEmpty()) return
        val tokens = com.lifecyclebot.engine.BotService.status.tokens
        val at = System.currentTimeMillis()
        for ((mint, px) in prices) {
            val ts = try { synchronized(tokens) { tokens[mint] } } catch (_: Throwable) { null } ?: continue
            val applied = synchronized(ts) {
                val fresher = ts.lastPriceUpdate > 0L && at - ts.lastPriceUpdate in 0L until 1_000L
                val suspect = ts.lastPrice > 0.0 && ForwardReturnLabeler7731.basisSuspect7738(ts.lastPrice, px)
                if (ts.position.isOpen || fresher || suspect) false else {
                    ts.lastMcap = rescaledMcap8094(ts.lastMcap, ts.lastPrice, px)
                    ts.lastPrice = px
                    ts.lastPriceUpdate = at
                    ts.lastPriceSource = source
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

    private val http by lazy {
        com.lifecyclebot.network.SharedHttpClient.builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
    private val dexReads = AtomicLong(0)

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

    /**
     * V5.0.8094 entry repair — only the PRICE is taken. DexScreener's 5-minute transaction counts / change are not written
     * as the classifier's observed inputs: filling them turned thousands of coins the bot had no native tape for from
     * DATA_INCOMPLETE (refused live) into "classified" setups matched against records proven on the native tape.
     */
    private fun applyDex8094(reads: Map<String, DexRead>) {
        if (reads.isEmpty()) return
        dexReads.addAndGet(reads.size.toLong())
        apply8094(reads.mapValues { it.value.priceUsd }, "DEXSCREENER_CANDIDATE_REFRESH_8094")
    }

    fun statusLine(): String =
        "CANDIDATE_MARK_FRESH_8094 freshMs=$FRESH_MS_8094 requested=${requested.get()} refreshed=${refreshed.get()} batches=${batches.get()} dexReads=${dexReads.get()} waiting=${wanted.size}"
}

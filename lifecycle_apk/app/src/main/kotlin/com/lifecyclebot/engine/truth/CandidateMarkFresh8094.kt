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

    /** SpecialistBrainBridge7542.evaluate: request a fresh mark for a stale candidate. True when one was requested. */
    fun ensureFresh8094(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Boolean {
        val mint = ts.mint
        if (mint.length !in 32..44 || ts.position.isOpen || !stale8094(ts.lastPrice, ts.lastPriceUpdate, nowMs)) return false
        if (wanted.putIfAbsent(mint, nowMs) == null) requested.incrementAndGet()
        if (wanted.size > 2_000) wanted.entries.removeIf { nowMs - it.value > WANTED_TTL_MS }
        maybeRun(nowMs)
        return true
    }

    private fun maybeRun(nowMs: Long) {
        if (nowMs - lastBatchMs < BATCH_GAP_MS || !inFlight.compareAndSet(false, true)) return
        lastBatchMs = nowMs
        val batch = wanted.entries.sortedBy { it.value }.take(50).map { it.key }
        batch.forEach { wanted.remove(it) }
        if (batch.isEmpty()) { inFlight.set(false); return }
        try {
            pool.execute {
                try {
                    batches.incrementAndGet()
                    apply8094(com.lifecyclebot.engine.sell.PriceResolverFallback.jupiterBatchPrices7737(batch))
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

    fun statusLine(): String =
        "CANDIDATE_MARK_FRESH_8094 freshMs=$FRESH_MS_8094 requested=${requested.get()} refreshed=${refreshed.get()} batches=${batches.get()} waiting=${wanted.size}"
}

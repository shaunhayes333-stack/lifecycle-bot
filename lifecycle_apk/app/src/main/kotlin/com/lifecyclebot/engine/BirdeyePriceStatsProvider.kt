package com.lifecyclebot.engine

import com.lifecyclebot.network.BirdeyeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.938 — BirdeyePriceStatsProvider.
 *
 * Volatility-regime classifier from /defi/v3/price/stats/single (30m/1h/24h
 * min/max/stddev in one call). FDG consumes for position sizing soft-shape:
 *   CALM       stdDev30m/price < 1%     → × 1.10
 *   NORMAL     1% .. 3%                 → × 1.00
 *   CHOPPY     3% .. 6%                 → × 0.85
 *   VOLATILE   6% .. 12%                → × 0.70
 *   INSANE     > 12%                    → × 0.60
 *
 * Cache 30min, ~40 CU/call. 50 admitted × 2/hr × 40 CU = 4K CU/hr.
 */
object BirdeyePriceStatsProvider {
    private const val TAG = "BirdeyePriceStats"
    private const val CACHE_TTL_MS = 30L * 60_000L
    private const val REQUEST_TIMEOUT_MS = 2500L

    data class StatsSnapshot(
        val priceMin30m: Double, val priceMax30m: Double, val stdDev30m: Double,
        val priceMin1h: Double,  val priceMax1h: Double,  val stdDev1h: Double,
        val priceMin24h: Double, val priceMax24h: Double, val stdDev24h: Double,
    ) {
        fun volatilityRegime(): Pair<String, Double> {
            val ref = (priceMin30m + priceMax30m) / 2.0
            if (ref <= 0.0) return "UNKNOWN" to 1.00
            val pct = (stdDev30m / ref) * 100.0
            return when {
                pct < 1.0  -> "CALM" to 1.10
                pct < 3.0  -> "NORMAL" to 1.00
                pct < 6.0  -> "CHOPPY" to 0.85
                pct < 12.0 -> "VOLATILE" to 0.70
                else       -> "INSANE" to 0.60
            }
        }
    }

    private data class Cached(val snapshot: StatsSnapshot, val ts: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    @Volatile private var consecutiveFails: Int = 0
    @Volatile private var circuitOpenUntilMs: Long = 0L

    fun peekCached(mint: String): StatsSnapshot? {
        val c = cache[mint] ?: return null
        if (System.currentTimeMillis() - c.ts > CACHE_TTL_MS) {
            cache.remove(mint); return null
        }
        return c.snapshot
    }

    suspend fun maybePrefetch(mint: String, apiKey: String) {
        if (mint.isBlank() || apiKey.isBlank()) return
        val now = System.currentTimeMillis()
        if (now < circuitOpenUntilMs) return
        val existing = cache[mint]
        if (existing != null && (now - existing.ts) < CACHE_TTL_MS) return
        // V5.0.4186 — BIRDEYE = BACKUP. Operator P0: Birdeye is to be a
        // last-resort fallback only. PriceStats is non-essential metadata
        // — skip when scanner-lane budget is throttled. Real-time price
        // history still comes from WS feeds.
        if (!BirdeyeBudgetGate.canAffordScannerLane()) {
            try { PipelineHealthCollector.labelInc("BIRDEYE_PRICESTATS_SKIPPED_BUDGET") } catch (_: Throwable) {}
            return
        }
        if (inFlight.putIfAbsent(mint, true) != null) return

        try {
            val ps = withContext(Dispatchers.IO) {
                withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                    BirdeyeApi(apiKey).getPriceStats(mint)
                }
            }
            if (ps == null) {
                val fails = (consecutiveFails + 1).also { consecutiveFails = it }
                if (fails >= 5 && circuitOpenUntilMs < now) {
                    circuitOpenUntilMs = now + 30L * 60_000L
                }
            } else {
                consecutiveFails = 0
                cache[mint] = Cached(
                    StatsSnapshot(
                        priceMin30m = ps.priceMin30m, priceMax30m = ps.priceMax30m, stdDev30m = ps.stdDev30m,
                        priceMin1h  = ps.priceMin1h,  priceMax1h  = ps.priceMax1h,  stdDev1h  = ps.stdDev1h,
                        priceMin24h = ps.priceMin24h, priceMax24h = ps.priceMax24h, stdDev24h = ps.stdDev24h,
                    ),
                    now,
                )
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "prefetch fail ${mint.take(10)}: ${e.message?.take(40)}")
        } finally {
            inFlight.remove(mint)
        }
    }

    fun cacheSize(): Int = cache.size
}

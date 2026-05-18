package com.lifecyclebot.engine

import com.lifecyclebot.network.BirdeyeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.937 — Birdeye trade-data cache provider.
 *
 * Operator upgraded Birdeye to Starter ($99/mo, 5M CUs, 15 rps) on
 * 2026-05-19 which unlocks /defi/v3/token/trade-data/single. That
 * endpoint returns 5m/30m/1h/2h/4h/8h/24h aggregated buy/sell counts
 * and volume splits in a single call — flow-imbalance gold.
 *
 * Same pattern as BirdeyeSecurityProvider (V5.9.910):
 *   - fetchAndCache happens async at scanner intake (background)
 *   - peekCached returns null if not cached (FDG never blocks waiting)
 *   - 15-minute TTL (see CU budget math in file body)
 *   - Circuit-breaker: 5 consecutive failures opens 10-min cooldown
 *   - Fail-open everywhere
 *
 * CU budget on Starter (5M CU/mo):
 *   ~50 admitted mints × 4 fetches/hour × 25 CU = 5K CU/hour
 *   × 720h = 3.6M CU/month. Fits with ~1.4M CU spare for sec checks
 *   and price fallbacks.
 *
 * Hard cap: never exceeds 1 actual fetch per mint per TTL window.
 */
object BirdeyeTradeDataProvider {
    private const val TAG = "BirdeyeTradeData"
    private const val CACHE_TTL_MS = 15L * 60_000L
    private const val MAX_CACHE_SIZE = 5_000
    private const val REQUEST_TIMEOUT_MS = 2500L

    private data class Cached(
        val snapshot: TradeSnapshot,
        val timestamp: Long,
    )

    data class TradeSnapshot(
        val priceUsd: Double,
        val priceChange30mPct: Double,
        val priceChange1hPct: Double,
        val priceChange4hPct: Double,
        val priceChange24hPct: Double,
        val volume30m: Double,
        val volume1h: Double,
        val volume24h: Double,
        val buys30m: Int,
        val sells30m: Int,
        val buys1h: Int,
        val sells1h: Int,
        val buys24h: Int,
        val sells24h: Int,
        val uniqueWallets24h: Int,
        val volBuy24h: Double,
        val volSell24h: Double,
    )

    private val cache = ConcurrentHashMap<String, Cached>()

    @Volatile private var consecutiveFails: Int = 0
    @Volatile private var circuitOpenUntilMs: Long = 0L
    private const val CIRCUIT_TRIP_THRESHOLD = 5
    private const val CIRCUIT_COOLDOWN_MS = 10L * 60_000L

    private val inFlight = ConcurrentHashMap<String, Boolean>()

    /**
     * Returns cached snapshot, null if not yet fetched / expired.
     * Does NOT trigger a fetch. FDG calls this — must never block.
     */
    fun peekCached(mint: String): TradeSnapshot? {
        if (mint.isBlank()) return null
        val c = cache[mint] ?: return null
        if (System.currentTimeMillis() - c.timestamp > CACHE_TTL_MS) {
            cache.remove(mint)
            return null
        }
        return c.snapshot
    }

    /**
     * Background fetch. Call from scanner intake or V3 admit path.
     * Fire-and-forget — must be invoked from inside a coroutine scope.
     */
    suspend fun maybePrefetch(mint: String, apiKey: String) {
        if (mint.isBlank() || apiKey.isBlank()) return

        val now = System.currentTimeMillis()
        if (now < circuitOpenUntilMs) return

        val existing = cache[mint]
        if (existing != null && (now - existing.timestamp) < CACHE_TTL_MS) return

        if (inFlight.putIfAbsent(mint, true) != null) return

        try {
            val snapshot = withContext(Dispatchers.IO) {
                withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                    val api = BirdeyeApi(apiKey)
                    val td = api.getTradeData(mint) ?: return@withTimeoutOrNull null
                    TradeSnapshot(
                        priceUsd          = td.price,
                        priceChange30mPct = td.priceChange30mPct,
                        priceChange1hPct  = td.priceChange1hPct,
                        priceChange4hPct  = td.priceChange4hPct,
                        priceChange24hPct = td.priceChange24hPct,
                        volume30m         = td.volume30m,
                        volume1h          = td.volume1h,
                        volume24h         = td.volume24h,
                        buys30m           = td.buys30m,
                        sells30m          = td.sells30m,
                        buys1h            = td.buys1h,
                        sells1h           = td.sells1h,
                        buys24h           = td.buys24h,
                        sells24h          = td.sells24h,
                        uniqueWallets24h  = td.uniqueWallets24h,
                        volBuy24h         = td.volBuy24h,
                        volSell24h        = td.volSell24h,
                    )
                }
            }

            if (snapshot != null) {
                cache[mint] = Cached(snapshot, System.currentTimeMillis())
                consecutiveFails = 0
                if (cache.size > MAX_CACHE_SIZE) {
                    val oldest = cache.entries.minByOrNull { it.value.timestamp }
                    if (oldest != null) cache.remove(oldest.key)
                }
            } else {
                val fails = (consecutiveFails + 1).also { consecutiveFails = it }
                if (fails >= CIRCUIT_TRIP_THRESHOLD && circuitOpenUntilMs < System.currentTimeMillis()) {
                    circuitOpenUntilMs = System.currentTimeMillis() + CIRCUIT_COOLDOWN_MS
                    ErrorLogger.warn(TAG, "⚡ Circuit OPEN — $fails consecutive failures, suppressing for 10min")
                }
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "prefetch fail-open for ${mint.take(10)}: ${e.message?.take(40)}")
        } finally {
            inFlight.remove(mint)
        }
    }

    fun clearCache() {
        cache.clear()
        inFlight.clear()
        consecutiveFails = 0
        circuitOpenUntilMs = 0L
    }

    fun cacheSize(): Int = cache.size
    fun isCircuitOpen(): Boolean = System.currentTimeMillis() < circuitOpenUntilMs
}

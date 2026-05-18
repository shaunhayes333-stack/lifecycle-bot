package com.lifecyclebot.engine

import com.lifecyclebot.network.BirdeyeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.938 — BirdeyeCreationInfoProvider.
 *
 * True deploy-time, creator wallet, creation tx. Feeds:
 *   1. Real token age (vs WE-saw-it-at addedToWatchlistAt)
 *   2. Creator wallet → SolscanDevTracker rug-history lookup
 *   3. FDG fresh-deploy soft-shape (< 1h = volatile = smaller size)
 *
 * 24h cache (creation info is immutable).
 */
object BirdeyeCreationInfoProvider {
    private const val TAG = "BirdeyeCreationInfo"
    private const val CACHE_TTL_MS = 24L * 60L * 60_000L
    private const val REQUEST_TIMEOUT_MS = 2000L

    data class Info(
        val creatorAddress: String,
        val createdAtMs: Long,
        val creationTx: String,
        val createdSlot: Long,
    ) {
        fun ageHours(): Double {
            if (createdAtMs <= 0L) return -1.0
            return (System.currentTimeMillis() - createdAtMs) / 3_600_000.0
        }
        fun isFreshDeploy(): Boolean = ageHours() in 0.0..1.0
        fun isYoungToken(): Boolean  = ageHours() in 0.0..24.0
    }

    private data class Cached(val info: Info, val ts: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    fun peekCached(mint: String): Info? {
        val c = cache[mint] ?: return null
        if (System.currentTimeMillis() - c.ts > CACHE_TTL_MS) {
            cache.remove(mint); return null
        }
        return c.info
    }

    suspend fun maybePrefetch(mint: String, apiKey: String) {
        if (mint.isBlank() || apiKey.isBlank()) return
        val existing = cache[mint]
        val now = System.currentTimeMillis()
        if (existing != null && (now - existing.ts) < CACHE_TTL_MS) return
        if (inFlight.putIfAbsent(mint, true) != null) return

        try {
            val ci = withContext(Dispatchers.IO) {
                withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                    BirdeyeApi(apiKey).getCreationInfo(mint)
                }
            }
            if (ci != null) {
                cache[mint] = Cached(
                    Info(
                        creatorAddress = ci.creatorAddress,
                        createdAtMs    = ci.createdAtMs,
                        creationTx     = ci.creationTx,
                        createdSlot    = ci.createdSlot,
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

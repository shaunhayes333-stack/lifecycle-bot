package com.lifecyclebot.engine

import com.lifecyclebot.network.BirdeyeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.938 — BirdeyeMetaDataProvider.
 *
 * Richer socials than DexScreener — feeds TokenSocialScorer with
 * twitter/telegram/discord/website/coingeckoId. 24h cache.
 */
object BirdeyeMetaDataProvider {
    private const val TAG = "BirdeyeMetaData"
    private const val CACHE_TTL_MS = 24L * 60L * 60_000L
    private const val REQUEST_TIMEOUT_MS = 2000L

    data class Meta(
        val name: String,
        val symbol: String,
        val twitter: String,
        val telegram: String,
        val discord: String,
        val website: String,
        val coingeckoId: String,
        val description: String,
    ) {
        fun socialChannelCount(): Int {
            var n = 0
            if (twitter.isNotBlank())  n++
            if (telegram.isNotBlank()) n++
            if (discord.isNotBlank())  n++
            if (website.isNotBlank())  n++
            return n
        }
        fun isListed(): Boolean = coingeckoId.isNotBlank()
    }

    private data class Cached(val meta: Meta, val ts: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    fun peekCached(mint: String): Meta? {
        val c = cache[mint] ?: return null
        if (System.currentTimeMillis() - c.ts > CACHE_TTL_MS) {
            cache.remove(mint); return null
        }
        return c.meta
    }

    suspend fun maybePrefetch(mint: String, apiKey: String) {
        if (mint.isBlank() || apiKey.isBlank()) return
        val now = System.currentTimeMillis()
        val existing = cache[mint]
        if (existing != null && (now - existing.ts) < CACHE_TTL_MS) return
        if (inFlight.putIfAbsent(mint, true) != null) return

        try {
            val m = withContext(Dispatchers.IO) {
                withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                    BirdeyeApi(apiKey).getTokenMetaData(mint)
                }
            }
            if (m != null) {
                cache[mint] = Cached(
                    Meta(
                        name = m.name, symbol = m.symbol,
                        twitter = m.twitter, telegram = m.telegram,
                        discord = m.discord, website = m.website,
                        coingeckoId = m.coingeckoId, description = m.description,
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

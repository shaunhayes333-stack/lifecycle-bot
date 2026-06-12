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

    /**
     * V5.9.945 — Seed cache from FREE source (DexScreener socials/websites).
     *
     * DexScreener returns info.socials (twitter/telegram/discord platform
     * markers) and info.websites in every pair payload. We already parse
     * these into PairInfo.socials + PairInfo.websites (V5.9.911 social
     * harvest). We were paying Birdeye to get the same fields.
     *
     * This seeder populates the cache from those free fields so the FDG
     * social-depth soft-shape fires without burning any Birdeye CU.
     *
     * Lower fidelity than Birdeye:
     *   - coingeckoId: empty (DexScreener doesn't tell us if CG-listed)
     *     → isListed() always false from free-seed → FDG +10% boost
     *       for CG-listed unavailable. Operator can still upgrade
     *       via maybePrefetch() if budget allows.
     *   - description: empty (we don't need it)
     *   - name/symbol: from PairInfo
     *   - twitter/telegram/discord/website: HEURISTIC — DexScreener
     *     gives platform TYPES (e.g. "twitter","telegram") rather than
     *     handles. We map presence-of-type → "present" so
     *     socialChannelCount() works correctly.
     */
    fun seedFromFreeSource(
        mint: String,
        symbol: String,
        socialTypes: List<String>,
        websites: List<String>,
    ) {
        if (mint.isBlank()) return
        val existing = cache[mint]
        // Don't overwrite an entry with a real CG-listing
        if (existing != null && existing.meta.coingeckoId.isNotBlank()) return
        val lowerSet = socialTypes.map { it.lowercase() }.toSet()
        cache[mint] = Cached(
            Meta(
                name = symbol,
                symbol = symbol,
                twitter  = if ("twitter"  in lowerSet) "present" else "",
                telegram = if ("telegram" in lowerSet) "present" else "",
                discord  = if ("discord"  in lowerSet) "present" else "",
                website  = if (websites.isNotEmpty()) websites.first() else "",
                coingeckoId = "",
                description = "",
            ),
            System.currentTimeMillis(),
        )
    }

    /** V5.9.1555 — seed cache from hive-shared metadata. */
    fun seedFromHive(
        mint: String,
        name: String,
        symbol: String,
        twitter: String,
        telegram: String,
        discord: String,
        website: String,
        coingeckoId: String,
    ) {
        if (mint.isBlank()) return
        val existing = cache[mint]
        if (existing != null && existing.meta.coingeckoId.isNotBlank() && coingeckoId.isBlank()) return
        cache[mint] = Cached(
            Meta(
                name = name,
                symbol = symbol,
                twitter = twitter,
                telegram = telegram,
                discord = discord,
                website = website,
                coingeckoId = coingeckoId,
                description = existing?.meta?.description ?: "",
            ),
            System.currentTimeMillis(),
        )
    }

    fun cacheSize(): Int = cache.size
}

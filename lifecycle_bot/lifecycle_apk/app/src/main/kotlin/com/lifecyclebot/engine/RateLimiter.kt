package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * RateLimiter — centralized API rate limiting
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Prevents hammering external APIs and getting rate-limited/banned.
 * Each API source has its own rate limit configuration.
 *
 * RATE LIMITS (per source):
 * ─────────────────────────────
 *   Dexscreener:   30 req/min (free tier)
 *   Birdeye:       60 req/min (free tier)
 *   Helius RPC:    100 req/min (free tier)
 *   Jupiter:       60 req/min (swap quotes)
 *   Solscan:       30 req/min (free tier)
 *   CoinGecko:     30 req/min (free tier)
 *   Groq LLM:      30 req/min (free tier)
 *
 * Usage:
 *   if (RateLimiter.allowRequest("dexscreener")) {
 *       // make API call
 *   } else {
 *       // skip or queue for later
 *   }
 */
object RateLimiter {

    data class RateConfig(
        val maxRequestsPerMinute: Int,
        val burstAllowance: Int = 3,  // allow small bursts above limit
    )

    private val configs = mapOf(
        "dexscreener"  to RateConfig(30),
        "birdeye"      to RateConfig(60),
        "helius"       to RateConfig(100),
        "jupiter"      to RateConfig(60),
        "solscan"      to RateConfig(30),
        "coingecko"    to RateConfig(30),
        "groq"         to RateConfig(30),
        "pumpfun"      to RateConfig(60),
        "default"      to RateConfig(60),
    )

    // source → list of request timestamps (last 60 seconds)
    private val requestTimes = ConcurrentHashMap<String, MutableList<Long>>()
    
    // Global last request time for minimum spacing
    private val lastRequestMs = AtomicLong(0L)
    private const val MIN_SPACING_MS = 50L  // 50ms between any requests

    /**
     * Check if a request to the given source is allowed.
     * Returns true if under rate limit, false if throttled.
     */
    fun allowRequest(source: String): Boolean {
        val config = configs[source.lowercase()] ?: configs["default"]!!
        val now = System.currentTimeMillis()
        val windowStart = now - 60_000L

        // Global minimum spacing
        val lastReq = lastRequestMs.get()
        if (now - lastReq < MIN_SPACING_MS) {
            return false
        }

        // Get or create request list for this source
        val times = requestTimes.getOrPut(source.lowercase()) { mutableListOf() }

        synchronized(times) {
            // Remove requests outside the 60-second window
            times.removeIf { it < windowStart }

            // Check if under limit (with burst allowance)
            val limit = config.maxRequestsPerMinute + config.burstAllowance
            if (times.size >= limit) {
                return false
            }

            // Record this request
            times.add(now)
            lastRequestMs.set(now)
        }

        return true
    }

    /**
     * Wait until a request is allowed (blocking).
     * Use sparingly — prefer allowRequest() with skip logic.
     */
    fun waitForSlot(source: String, maxWaitMs: Long = 5000L): Boolean {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            if (allowRequest(source)) return true
            Thread.sleep(100)
        }
        return false
    }

    /**
     * Get current usage stats for a source.
     */
    fun getUsage(source: String): Pair<Int, Int> {
        val config = configs[source.lowercase()] ?: configs["default"]!!
        val times = requestTimes[source.lowercase()]
        val windowStart = System.currentTimeMillis() - 60_000L
        val currentCount = times?.count { it >= windowStart } ?: 0
        return currentCount to config.maxRequestsPerMinute
    }

    /**
     * Get usage summary for all sources.
     */
    fun getAllUsage(): Map<String, Pair<Int, Int>> {
        return configs.keys.associateWith { getUsage(it) }
    }

    /**
     * Reset rate limiting (e.g., after API key change).
     */
    fun reset(source: String? = null) {
        if (source != null) {
            requestTimes.remove(source.lowercase())
        } else {
            requestTimes.clear()
        }
    }
}

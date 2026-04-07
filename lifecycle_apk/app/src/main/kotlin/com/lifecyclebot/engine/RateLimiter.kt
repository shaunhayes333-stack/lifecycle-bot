package com.lifecyclebot.engine

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * RateLimiter — centralized API rate limiting
 *
 * Prevents hammering external APIs and getting rate-limited/banned.
 * Each API source has its own rate limit configuration.
 */
object RateLimiter {

    data class RateConfig(
        val maxRequestsPerMinute: Int,
        val burstAllowance: Int = 3,
        val minSpacingMs: Long = DEFAULT_MIN_SPACING_MS,
    )

    private data class Bucket(
        val timestamps: ArrayDeque<Long> = ArrayDeque(),
        val lastRequestMs: AtomicLong = AtomicLong(0L),
    )

    private const val WINDOW_MS = 60_000L
    private const val DEFAULT_MIN_SPACING_MS = 50L
    private const val WAIT_POLL_MS = 50L

    private val configs = mapOf(
        "dexscreener" to RateConfig(maxRequestsPerMinute = 300, burstAllowance = 10, minSpacingMs = 25L),
        "birdeye" to RateConfig(maxRequestsPerMinute = 60, burstAllowance = 3, minSpacingMs = 50L),
        "helius" to RateConfig(maxRequestsPerMinute = 100, burstAllowance = 5, minSpacingMs = 40L),
        "quicknode" to RateConfig(maxRequestsPerMinute = 300, burstAllowance = 20, minSpacingMs = 15L),
        "jupiter" to RateConfig(maxRequestsPerMinute = 60, burstAllowance = 5, minSpacingMs = 50L),
        "solscan" to RateConfig(maxRequestsPerMinute = 30, burstAllowance = 3, minSpacingMs = 75L),
        "coingecko" to RateConfig(maxRequestsPerMinute = 30, burstAllowance = 3, minSpacingMs = 75L),
        "groq" to RateConfig(maxRequestsPerMinute = 30, burstAllowance = 3, minSpacingMs = 75L),
        "pumpfun" to RateConfig(maxRequestsPerMinute = 60, burstAllowance = 5, minSpacingMs = 50L),
        "default" to RateConfig(maxRequestsPerMinute = 60, burstAllowance = 3, minSpacingMs = DEFAULT_MIN_SPACING_MS),
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()

    private fun normalizeSource(source: String): String {
        return source.trim().lowercase().ifBlank { "default" }
    }

    private fun configFor(source: String): RateConfig {
        return configs[normalizeSource(source)] ?: configs.getValue("default")
    }

    private fun bucketFor(source: String): Bucket {
        return buckets.getOrPut(normalizeSource(source)) { Bucket() }
    }

    /**
     * Check if a request to the given source is allowed.
     * Returns true if under rate limit, false if throttled.
     */
    fun allowRequest(source: String): Boolean {
        val normalized = normalizeSource(source)
        val config = configFor(normalized)
        val bucket = bucketFor(normalized)
        val now = System.currentTimeMillis()
        val windowStart = now - WINDOW_MS
        val hardLimit = config.maxRequestsPerMinute + config.burstAllowance

        synchronized(bucket) {
            pruneOld(bucket, windowStart)

            val lastReq = bucket.lastRequestMs.get()
            if (now - lastReq < config.minSpacingMs) {
                return false
            }

            if (bucket.timestamps.size >= hardLimit) {
                return false
            }

            bucket.timestamps.addLast(now)
            bucket.lastRequestMs.set(now)
            return true
        }
    }

    /**
     * Wait until a request is allowed (blocking).
     * Returns true if a slot was acquired before timeout.
     */
    fun waitForSlot(source: String, maxWaitMs: Long = 5_000L): Boolean {
        if (maxWaitMs <= 0L) return allowRequest(source)

        val deadline = System.currentTimeMillis() + maxWaitMs

        while (System.currentTimeMillis() < deadline) {
            if (allowRequest(source)) return true

            val sleepMs = minOf(
                WAIT_POLL_MS,
                maxOf(1L, deadline - System.currentTimeMillis())
            )

            try {
                Thread.sleep(sleepMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }

        return false
    }

    /**
     * Get current usage stats for a source.
     * Returns Pair(currentCountInWindow, configuredBaseLimit).
     */
    fun getUsage(source: String): Pair<Int, Int> {
        val normalized = normalizeSource(source)
        val config = configFor(normalized)
        val bucket = buckets[normalized] ?: return 0 to config.maxRequestsPerMinute
        val now = System.currentTimeMillis()
        val windowStart = now - WINDOW_MS

        synchronized(bucket) {
            pruneOld(bucket, windowStart)
            return bucket.timestamps.size to config.maxRequestsPerMinute
        }
    }

    /**
     * Get usage summary for all configured sources.
     */
    fun getAllUsage(): Map<String, Pair<Int, Int>> {
        return configs.keys.associateWith { getUsage(it) }
    }

    /**
     * Returns milliseconds until the next request is likely allowed for this source.
     * 0 means probably available now.
     */
    fun getRetryAfterMs(source: String): Long {
        val normalized = normalizeSource(source)
        val config = configFor(normalized)
        val bucket = buckets[normalized] ?: return 0L
        val now = System.currentTimeMillis()
        val windowStart = now - WINDOW_MS
        val hardLimit = config.maxRequestsPerMinute + config.burstAllowance

        synchronized(bucket) {
            pruneOld(bucket, windowStart)

            val spacingWait = (bucket.lastRequestMs.get() + config.minSpacingMs - now).coerceAtLeast(0L)

            if (bucket.timestamps.size < hardLimit) {
                return spacingWait
            }

            val oldest = bucket.timestamps.firstOrNull()
            val windowWait = if (oldest != null) {
                (oldest + WINDOW_MS - now).coerceAtLeast(0L)
            } else {
                0L
            }

            return maxOf(spacingWait, windowWait)
        }
    }

    /**
     * Reset rate limiting for one source or all sources.
     */
    fun reset(source: String? = null) {
        if (source != null) {
            buckets.remove(normalizeSource(source))
        } else {
            buckets.clear()
        }
    }

    /**
     * Optional helper if you want to inspect the effective limit including burst.
     */
    fun getEffectiveLimit(source: String): Int {
        val config = configFor(source)
        return config.maxRequestsPerMinute + config.burstAllowance
    }

    private fun pruneOld(bucket: Bucket, windowStart: Long) {
        while (bucket.timestamps.isNotEmpty()) {
            val oldest = bucket.timestamps.first()
            if (oldest >= windowStart) break
            bucket.timestamps.removeFirst()
        }
    }
}
package com.lifecyclebot.perps

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8717 §AN_UNPRICEABLE_TOKEN_IS_NOT_ASKED_EVERY_PASS — 8712 (31 min): CRYPTO_PRICE_UNAVAILABLE_RETRY_7418 = 1,825,
 * and the crypto scan's own line read `batch=200 scanned=16`. A crypto-universe token that arrives without a price is
 * priced by one blocking DEX lookup inside the scan loop (CryptoAltTrader, 9.147); a token with no pair at all failed that
 * lookup, was released "for retry" with no delay, and was asked again on the next pass — about one wasted lookup a second,
 * each one spending the scan's time and the DEX host's budget, so the pass reached 16 of its 200 tokens.
 *
 * A miss now waits before it is asked again: [BASE_MS] after the first, doubling per consecutive miss to [MAX_MS]; a
 * token inside its wait is skipped without any call. One price clears it. Nothing is dropped from the universe.
 */
internal object PriceMissBackoff8717 {
    const val BASE_MS = 60_000L
    const val MAX_MS = 60L * 60_000L
    private const val CAP = 8_000

    private class Miss(var count: Int, var untilMs: Long)
    private val misses = ConcurrentHashMap<String, Miss>()
    private val skipped = AtomicLong(0L)
    private val noted = AtomicLong(0L)

    /** Pure: the wait after the [count]-th consecutive miss (1 = first). */
    fun waitMs(count: Int): Long = (BASE_MS shl (count - 1).coerceIn(0, 10)).coerceAtMost(MAX_MS)

    /** Is [identity] inside the wait a failed price lookup set? Counted. */
    fun cooling(identity: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val m = misses[identity] ?: return false
        if (m.untilMs <= nowMs) return false
        skipped.incrementAndGet()
        return true
    }

    fun noteMiss(identity: String, nowMs: Long = System.currentTimeMillis()) {
        if (identity.isBlank()) return
        if (misses.size > CAP) misses.entries.removeIf { it.value.untilMs <= nowMs }
        val m = misses.getOrPut(identity) { Miss(0, 0L) }
        synchronized(m) { m.count += 1; m.untilMs = nowMs + waitMs(m.count) }
        noted.incrementAndGet()
    }

    fun notePriced(identity: String) { misses.remove(identity) }

    fun statusLine(): String = "priceMiss8717[waiting=${misses.size} misses=${noted.get()} lookupsSaved=${skipped.get()}]"

    internal fun resetForTest() { misses.clear(); skipped.set(0L); noted.set(0L) }
}

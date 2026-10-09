package com.lifecyclebot.engine.truth

/**
 * V5.0.7960 — the risk clock's fallback mark. When the canonical EXIT_ECONOMIC mark is
 * missing or stale, a token's own runtime price observed within 15 s (pump trade stream,
 * scanner, wallet tracker) still lets stops evaluate. 5.0.7958 live: the canonical mark was
 * missing on 3,998 of 7,663 ticks and stops fired at -37%..-43%. The 50x band around entry
 * guards against unit mix-ups (SOL vs USD prices).
 */
object RiskClockMark7960 {
    private const val MAX_AGE_MS = 15_000L

    /** Pure: a runtime price usable as a risk-clock mark, or null. */
    fun runtimeRiskMark7960(lastPrice: Double, lastPriceUpdateMs: Long, entryPrice: Double, now: Long): Double? {
        if (!lastPrice.isFinite() || lastPrice <= 0.0 || lastPriceUpdateMs <= 0L) return null
        if (now - lastPriceUpdateMs > MAX_AGE_MS || now < lastPriceUpdateMs - 5_000L) return null
        if (!entryPrice.isFinite() || entryPrice <= 0.0) return null
        val r = lastPrice / entryPrice
        return if (r in 0.02..50.0) lastPrice else null
    }
}

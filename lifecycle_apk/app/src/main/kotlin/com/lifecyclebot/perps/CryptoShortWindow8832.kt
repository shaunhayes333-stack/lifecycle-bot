package com.lifecyclebot.perps

/**
 * Source-clocked five-minute Dex pair activity for dynamic crypto selection.
 *
 * A rolling m5 buy count measures buy TRANSACTIONS, not unique new wallets.
 * A 24-hour count, an undated registry refresh, and missing provider fields
 * must never silently become evidence of buyers arriving right now.
 */
internal object CryptoShortWindow8832 {
    private const val FRESH_MS = 120_000L

    data class Evidence(
        val flowObserved: Boolean,
        val buys5m: Int,
        val sells5m: Int,
        val buyPressurePct: Double,
        val volume5mUsd: Double?,
        val change5mPct: Double?,
    ) {
        val activeBuyerFlow: Boolean get() = flowObserved && buys5m >= 3 && buys5m > sells5m
        val priceRising5m: Boolean get() = change5mPct?.let { it in 0.5..12.0 } == true
        val volumeActive5m: Boolean get() = volume5mUsd?.let { it >= 500.0 } == true

        /** A 5m burst relative to the 24h baseline; not a replacement for source 5m volume. */
        fun volumeAccelerationVsDay(volume24h: Double): Double? {
            val v5 = volume5mUsd ?: return null
            if (!volume24h.isFinite() || volume24h <= 0.0) return null
            return v5 / (volume24h / 288.0).coerceAtLeast(1.0)
        }
    }

    fun from(
        nowMs: Long, observedAtMs: Long, buys5m: Int, sells5m: Int,
        volume5mUsd: Double, change5mPct: Double,
    ): Evidence {
        val fresh = observedAtMs > 0L && nowMs - observedAtMs in 0L..FRESH_MS
        val flow = fresh && buys5m >= 0 && sells5m >= 0
        val count = if (flow) buys5m.toLong() + sells5m.toLong() else 0L
        return Evidence(
            flowObserved = flow && count > 0L,
            buys5m = if (flow) buys5m else -1,
            sells5m = if (flow) sells5m else -1,
            buyPressurePct = if (count > 0L) buys5m * 100.0 / count else 50.0,
            volume5mUsd = volume5mUsd.takeIf { fresh && it.isFinite() && it >= 0.0 },
            change5mPct = change5mPct.takeIf { fresh && it.isFinite() },
        )
    }
}

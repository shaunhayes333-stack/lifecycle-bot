package com.lifecyclebot.engine

/** A newer observation can date a frozen price only when it observed that same asset and value. */
internal object ObservedPriceIdentity8871 {
    data class Observation(val assetId: String, val priceUsd: Double, val observedAtMs: Long)

    fun observedAt(input: Observation, inputAtMs: Long, corroboration: Observation? = null): Long {
        if (input.assetId.isBlank() || !input.priceUsd.isFinite() || input.priceUsd <= 0.0) return 0L
        val ownAt = input.observedAtMs.takeIf { it > 0L && it <= inputAtMs } ?: 0L
        val matchingAt = corroboration?.takeIf {
            it.assetId == input.assetId && it.priceUsd == input.priceUsd &&
                it.observedAtMs > 0L && it.observedAtMs <= inputAtMs
        }?.observedAtMs ?: 0L
        return maxOf(ownAt, matchingAt)
    }
}

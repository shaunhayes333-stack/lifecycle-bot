package com.lifecyclebot.engine.truth

/** A source observation, not the time a consumer happened to read its cache. */
data class ObservedMark8239(val priceUsd: Double, val observedAtMs: Long, val source: String) {
    fun fresh(nowMs: Long, maxAgeMs: Long): Boolean =
        priceUsd.isFinite() && priceUsd > 0.0 && observedAtMs > 0L && source.isNotBlank() &&
            nowMs - observedAtMs in 0L..maxAgeMs

    /** A forward horizon must be priced by an observation at or after that horizon. */
    fun atHorizon(decisionAtMs: Long, horizonMs: Long, graceMs: Long): Boolean =
        observedAtMs - decisionAtMs in horizonMs..(horizonMs + graceMs)
}

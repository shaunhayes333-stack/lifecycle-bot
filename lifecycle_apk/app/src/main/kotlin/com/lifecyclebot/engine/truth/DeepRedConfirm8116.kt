package com.lifecyclebot.engine.truth

/**
 * V5.0.8116 §A_RUG_HAS_NO_ROUTE. The tick floor treats a <= -50% read as a phantom unless an executable quote confirms
 * it (V5.9.1564 / 7927: a basis switch once read a winner as -96%). A real rug drains the pool, so there is no route and
 * no quote, and the crash was never confirmed: 5.0.8098 BanYKy (CASHGEN, 0.0318 SOL) rode to about -97% while the tick
 * floor, the plan stop and the slow-path backstop all stood down on the "unconfirmed" read.
 *
 * Without a quote the read is confirmed when the pool's own depth collapsed (live liquidity under
 * [LIQ_COLLAPSE_FRACTION] of the entry depth), or when it has persisted [PERSIST_MS] on a mark at most [MAX_MARK_AGE_MS]
 * old. An uncertain basis never confirms on this path.
 */
object DeepRedConfirm8116 {
    const val PERSIST_MS = 45_000L
    private const val MAX_MARK_AGE_MS = 30_000L
    private const val LIQ_COLLAPSE_FRACTION = 0.30

    fun confirms(uncertainBasis: Boolean, firstSeenMs: Long, nowMs: Long, markAgeMs: Long, entryLiqUsd: Double, liqNowUsd: Double): Boolean {
        if (uncertainBasis) return false
        val drained = entryLiqUsd > 0.0 && liqNowUsd.isFinite() && liqNowUsd >= 0.0 && liqNowUsd < entryLiqUsd * LIQ_COLLAPSE_FRACTION
        val persisted = nowMs - firstSeenMs >= PERSIST_MS && markAgeMs in 0L..MAX_MARK_AGE_MS
        return drained || persisted
    }
}

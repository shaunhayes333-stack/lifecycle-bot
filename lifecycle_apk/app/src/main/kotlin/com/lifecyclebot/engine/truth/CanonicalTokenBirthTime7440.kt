package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.TokenMetaCache
import com.lifecyclebot.network.PumpCurveKeys7269

/**
 * V5.0.7440 — canonical token/launch birth-time authority.
 *
 * Observation time is never birth time. Watchlist insertion, first local candle,
 * scanner discovery and registry insertion are deliberately absent.
 */
object CanonicalTokenBirthTime7440 {
    enum class Source { TOKEN_META_CREATION, PUMP_CREATE_EVENT, FIRST_POOL_CREATION }
    data class Resolution(val birthMs: Long, val source: Source)

    private const val MIN_VALID_MS = 1_577_836_800_000L
    private fun plausible(ms: Long, nowMs: Long): Boolean =
        ms >= MIN_VALID_MS && ms <= nowMs + 60_000L

    fun resolve(mint: String, nowMs: Long = System.currentTimeMillis()): Resolution? {
        val m = mint.trim()
        if (m.isBlank()) return null
        val candidates = ArrayList<Resolution>(3)

        try {
            val ctx = com.lifecyclebot.AATEApp.appContextOrNull()
            val metaMs = ctx?.let { TokenMetaCache.get(it).lookup(m)?.creationTimeMs } ?: 0L
            if (plausible(metaMs, nowMs)) candidates += Resolution(metaMs, Source.TOKEN_META_CREATION)
        } catch (_: Throwable) {}

        try {
            val pumpMs = PumpCurveKeys7269.createdAtMs7280(m) ?: 0L
            if (plausible(pumpMs, nowMs)) candidates += Resolution(pumpMs, Source.PUMP_CREATE_EVENT)
        } catch (_: Throwable) {}

        try {
            val poolMs = PoolCreationTime7385.createdAtMs(m) ?: 0L
            if (plausible(poolMs, nowMs)) candidates += Resolution(poolMs, Source.FIRST_POOL_CREATION)
        } catch (_: Throwable) {}

        val resolved = candidates.minByOrNull { it.birthMs }
        if (resolved == null) {
            try { PipelineHealthCollector.labelInc("TOKEN_BIRTH_HYDRATION_PENDING_7440") } catch (_: Throwable) {}
            return null
        }
        try { PipelineHealthCollector.labelInc("TOKEN_BIRTH_RESOLVED_7440_${resolved.source.name}") } catch (_: Throwable) {}

        if (resolved.source == Source.PUMP_CREATE_EVENT) {
            try {
                val ctx = com.lifecyclebot.AATEApp.appContextOrNull()
                if (ctx != null) {
                    val cache = TokenMetaCache.get(ctx)
                    val prior = cache.lookup(m)?.creationTimeMs ?: 0L
                    if (prior <= 0L || resolved.birthMs < prior) cache.register(mint = m, creationTimeMs = resolved.birthMs)
                }
            } catch (_: Throwable) {}
        }
        return resolved
    }

    fun resolvedAgeMs(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Long? =
        resolve(ts.mint, nowMs)?.let { (nowMs - it.birthMs).coerceAtLeast(0L) }

    fun resolvedAgeMinutes(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Double? =
        resolvedAgeMs(ts, nowMs)?.div(60_000.0)
}

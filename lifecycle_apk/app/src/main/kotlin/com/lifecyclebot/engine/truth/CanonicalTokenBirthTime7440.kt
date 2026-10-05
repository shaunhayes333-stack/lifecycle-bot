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

        // V5.0.7770 — on a tie the direct witness is credited, not the cache copy
        // this function wrote from it (create event, then pool, then cache).
        val resolved = candidates.minWithOrNull(compareBy<Resolution>({ it.birthMs }, { sourceRank7770(it.source) }))
        if (resolved == null) {
            try { TokenBirthHydrator7441.request(m) } catch (_: Throwable) {}
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

    private fun sourceRank7770(s: Source): Int = when (s) {
        Source.PUMP_CREATE_EVENT -> 0
        Source.FIRST_POOL_CREATION -> 1
        Source.TOKEN_META_CREATION -> 2
    }

    fun resolvedAgeMs(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Long? =
        resolve(ts.mint, nowMs)?.let { (nowMs - it.birthMs).coerceAtLeast(0L) }

    /**
     * V5.0.7767 §ONE_LAUNCH_AGE. Four consumers read a token's age four ways:
     * LaunchPhaseAuthority treated an unresolved birth as infinitely old,
     * FreshLaunchSelector fell back to watchlist age, and the live sniper gate read
     * only PoolCreationTime7385 and treated "unknown" as fresh. One rule: the
     * resolved birth; else, for a launch-feed token under $300k, its watchlist age
     * (a lower bound — never younger than the truth); else unknown, and unknown is
     * never fresh. Field Manual §4: an early entry needs a known start.
     */
    fun launchAgeMs7767(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Long? =
        rememberedLaunchAge7809(ts.mint, nowMs, resolvedAgeMs(ts, nowMs) ?: try {
            FreshLaunchSelector7737.unresolvedLaunchAgeMs7738(ts.source, ts.addedToWatchlistAt, ts.lastMcap, nowMs)
        } catch (_: Throwable) { null })

    /**
     * V5.0.7809 §BIRTH_EVIDENCE_SURVIVES_HANDOFF. 5.0.7808 live refused sniper
     * buys as LIVE_SNIPER_NOT_A_LAUNCH_7385 (LAUNCH_AGE_UNKNOWN) on mints the
     * coordinator had just classified as launches: the watchlist-age fallback
     * reads mutable TokenState fields (source, watchlist insertion time) that a
     * canonical handoff / new candidate version rewrites, so the same mint read
     * "unknown" one step later. A birth is immutable: once any evidence yields
     * one, it is remembered per mint and every later read uses the EARLIEST
     * birth seen (the oldest age, never younger than any evidence — Field
     * Manual §4 / L153). A genuinely old or unknown mint is unchanged: no
     * evidence, no memory, still refused.
     */
    private val birthMemory7809 = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val BIRTH_MEMORY_CAP_7809 = 8_192

    private fun rememberedLaunchAge7809(mint: String, nowMs: Long, observedAgeMs: Long?): Long? {
        val m = mint.trim()
        if (m.isBlank()) return observedAgeMs
        val observedBirth = observedAgeMs?.takeIf { it >= 0L }?.let { nowMs - it }
        val prior = birthMemory7809[m]
        val birth = when {
            observedBirth != null && prior != null -> minOf(observedBirth, prior)
            else -> observedBirth ?: prior
        } ?: return observedAgeMs
        if (prior == null || birth < prior) {
            if (birthMemory7809.size >= BIRTH_MEMORY_CAP_7809) {
                val cutoff = nowMs - 24L * 60L * 60_000L
                birthMemory7809.entries.removeIf { it.value < cutoff }
                if (birthMemory7809.size >= BIRTH_MEMORY_CAP_7809) birthMemory7809.clear()
            }
            birthMemory7809[m] = birth
        }
        if (observedAgeMs == null) {
            try { PipelineHealthCollector.labelInc("LAUNCH_BIRTH_FROM_MEMORY_7809") } catch (_: Throwable) {}
        }
        return (nowMs - birth).coerceAtLeast(0L)
    }

    fun resolvedAgeMinutes(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Double? =
        resolvedAgeMs(ts, nowMs)?.div(60_000.0)
}

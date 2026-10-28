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

    /**
     * V5.0.8418 — the verified birth of [mint] from the witnesses already held, with NO hydration request and no counters
     * (MarketSweep7297.merge reads it for hundreds of mints a sweep). Null when no direct witness is held.
     */
    fun peekBirthMs8418(mint: String, nowMs: Long = System.currentTimeMillis()): Long? {
        val m = mint.trim()
        if (m.isBlank()) return null
        return candidates8418(m, nowMs).minWithOrNull(compareBy<Resolution>({ it.birthMs }, { sourceRank7770(it.source) }))?.birthMs
    }

    fun resolve(mint: String, nowMs: Long = System.currentTimeMillis()): Resolution? {
        val m = mint.trim()
        if (m.isBlank()) return null
        val candidates = candidates8418(m, nowMs)

        // Ordinary pool creation is only an age lower bound (AdmissionBirth8144), never verified birth.

        // V5.0.7770 — on a tie the direct witness is credited, not the cache copy
        // this function wrote from it (create event, then pool, then cache).
        val resolved = candidates.minWithOrNull(compareBy<Resolution>({ it.birthMs }, { sourceRank7770(it.source) }))
        return afterResolve8418(m, resolved)
    }

    private fun candidates8418(m: String, nowMs: Long): List<Resolution> {
        val candidates = ArrayList<Resolution>(3)

        var chainProvenMs8418 = 0L
        try {
            val ctx = com.lifecyclebot.AATEApp.appContextOrNull()
            val row8418 = ctx?.let { TokenMetaCache.get(it).lookup(m) }
            val metaMs = row8418?.creationTimeMs ?: 0L
            // V5.0.8418 — a stored birth is the birth only with InitializeMint proof; an unproven one is a HISTORY_FLOOR
            // (published by StoredBirth8418) and is queued for proof, never a candidate here.
            if (plausible(metaMs, nowMs) && StoredBirth8418.usableAsBirth8418(metaMs, row8418?.birthProof8418 ?: 0)) {
                candidates += Resolution(metaMs, Source.TOKEN_META_CREATION)
                chainProvenMs8418 = metaMs
            } else if (row8418 != null && plausible(metaMs, nowMs)) StoredBirth8418.onLoaded8418(m, metaMs, row8418.birthProof8418)
        } catch (_: Throwable) {}

        try {
            val pumpMs = PumpCurveKeys7269.createdAtMs7280(m) ?: 0L
            if (plausible(pumpMs, nowMs)) candidates += Resolution(pumpMs, Source.PUMP_CREATE_EVENT)
        } catch (_: Throwable) {}
        // V5.0.8144 — every create frame (also throttled / reserve-less ones) and pump.fun's coin record are the create event too.
        // V5.0.8418 §THE_CHAIN_PROOF_WINS — pump.fun's coin record (created_timestamp, an API observation) counts only while no
        // chain proof of the mint's initialisation is held; with one, the record is not a candidate and the proof is the birth.
        val chainHeld8418 = chainProof8418(chainProvenMs8418, m, nowMs)
        try { (if (chainHeld8418 != null) AdmissionBirth8144.createFrameMs8169(m) else AdmissionBirth8144.pumpCreateMs8144(m))?.let { if (plausible(it, nowMs)) candidates += Resolution(it, Source.PUMP_CREATE_EVENT) } } catch (_: Throwable) {}
        // V5.0.8173 — the other direct witnesses AdmissionBirth8144 already holds by mint: a pump mint's own bonding-curve pair
        // (MarketSweep7297 enrichment notes it there and nowhere else, so launch lanes read UNKNOWN_DEFER beside it) and the
        // mint's oldest signature with its history read to the end (in memory before the meta cache's lazy disk read).
        try { AdmissionBirth8144.curvePairMs8169(m)?.let { if (plausible(it, nowMs)) candidates += Resolution(it, Source.PUMP_CREATE_EVENT) } } catch (_: Throwable) {}
        try { AdmissionBirth8144.oldestSignatureMs8169(m)?.let { if (plausible(it, nowMs)) candidates += Resolution(it, Source.TOKEN_META_CREATION) } } catch (_: Throwable) {}
        // V5.0.8179 — the mint was initialised in the confirmed transaction that created its first pool (chain launch feed).
        try { AdmissionBirth8144.chainMintInitMs8179(m)?.let { if (plausible(it, nowMs)) candidates += Resolution(it, Source.FIRST_POOL_CREATION) } } catch (_: Throwable) {}
        return candidates
    }

    private fun afterResolve8418(m: String, resolved: Resolution?): Resolution? {
        if (resolved == null) {
            try { TokenBirthHydrator7441.request(m) } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("TOKEN_BIRTH_HYDRATION_PENDING_7440") } catch (_: Throwable) {}
            try { MarketEvidence8346.noteBirthUnresolved8346() } catch (_: Throwable) {}   // V5.0.8346
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

    /** V5.0.8418 — the earliest chain-proven birth held for [mint] (proven meta row, proven oldest signature, chain launch), or null. */
    private fun chainProof8418(metaProvenMs: Long, mint: String, nowMs: Long): Resolution? {
        val sig = try { AdmissionBirth8144.oldestSignatureMs8169(mint) } catch (_: Throwable) { null } ?: 0L
        val init = try { AdmissionBirth8144.chainMintInitMs8179(mint) } catch (_: Throwable) { null } ?: 0L
        return chainProofOf8418(metaProvenMs, sig, init, nowMs)
    }

    /** Pure (V5.0.8418): the chain-proven birth among a proven meta row, a proven oldest signature and a chain mint init. */
    internal fun chainProofOf8418(metaProvenMs: Long, oldestSigProvenMs: Long, chainMintInitMs: Long, nowMs: Long): Resolution? {
        val c = ArrayList<Resolution>(3)
        if (plausible(chainMintInitMs, nowMs)) c += Resolution(chainMintInitMs, Source.FIRST_POOL_CREATION)
        if (plausible(oldestSigProvenMs, nowMs)) c += Resolution(oldestSigProvenMs, Source.TOKEN_META_CREATION)
        if (plausible(metaProvenMs, nowMs)) c += Resolution(metaProvenMs, Source.TOKEN_META_CREATION)
        return c.minByOrNull { it.birthMs }
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
        rememberedLaunchAge7809(ts.mint, nowMs, resolvedAgeMs(ts, nowMs))

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

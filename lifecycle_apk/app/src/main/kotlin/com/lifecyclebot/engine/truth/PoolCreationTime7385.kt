package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.7385 — when a token's first pool was created, per mint.
 *
 * The sniper judged "token age" from when THIS BOT first saw the mint
 * (addedToWatchlistAt), so a long-graduated token that was merely first
 * noticed read as a 15–600s launch and was bought at $481k (GyxJsD, −51%).
 * The launch time was already on the wire and dropped: DexScreener returns
 * pairCreatedAt on every pair it serves, and a PumpPortal create frame IS the
 * launch. Both are recorded here; the earliest observation wins, because a
 * graduated token also has a later AMM pair and its launch is the first one.
 */
object PoolCreationTime7385 {

    private val createdAt = ConcurrentHashMap<String, Long>()
    private const val MAX_ENTRIES = 20_000

    /** Record [createdAtMs] for [mint]; keeps the earliest plausible value. */
    fun record(mint: String, createdAtMs: Long, source: String) {
        if (mint.isBlank()) return
        val now = System.currentTimeMillis()
        // Reject zero, future, and pre-Solana timestamps (seconds passed as ms etc.).
        if (createdAtMs <= 1_577_836_800_000L || createdAtMs > now + 60_000L) return
        if (createdAt.size > MAX_ENTRIES) createdAt.clear()
        var recorded = false
        createdAt.compute(mint) { _, old ->
            if (old == null || createdAtMs < old) { recorded = true; createdAtMs } else old
        }
        if (recorded) {
            try { PipelineHealthCollector.labelInc("POOL_CREATED_AT_RECORDED_7385_$source") } catch (_: Throwable) {}
        }
    }

    /** Earliest known pool creation time for [mint], or null. */
    fun createdAtMs(mint: String): Long? {
        if (mint.isBlank()) return null
        val own = createdAt[mint]
        val seeded = try {
            com.lifecyclebot.engine.BirdeyeCreationInfoProvider.peekCached(mint)?.createdAtMs?.takeIf { it > 0L }
        } catch (_: Throwable) { null }
        return listOfNotNull(own, seeded).minOrNull()
    }

    /** Seconds since the first pool was created, or null when unknown. */
    fun ageSecs(mint: String): Long? =
        createdAtMs(mint)?.let { ((System.currentTimeMillis() - it) / 1000L).coerceAtLeast(0L) }
}

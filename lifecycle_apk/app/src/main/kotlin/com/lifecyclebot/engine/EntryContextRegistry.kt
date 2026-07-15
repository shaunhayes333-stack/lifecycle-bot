package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * EntryContextRegistry — V5.0.6258 (Paper→Live AGI Rewire)
 * ════════════════════════════════════════════════════════════════════════════
 * At entry time the FDG/AgenticStyleRouter picks a concrete setup + chart
 * pattern (degen_micro_snipe / accumulation_compression / etc). At close time
 * (TokenWinMemory.recordTradeOutcome) that context was LOST — every capture
 * fell back to "unknown", making LiveWinDNAStore statusline show
 *   topSetup=unknown(n=2) topPattern=unknown(n=2)
 * even after thousands of closes. The DNA store was blind because nothing
 * bridged entry→close.
 *
 * This registry is the bridge. Stamp on every entry, read on every close.
 * Bounded (2000 mints) so a stale stamp is harmless.
 *
 * V5_0_6258_ENTRY_CONTEXT_REGISTRY
 */
object EntryContextRegistry {

    data class EntryCtx(
        val entrySetup: String,
        val chartPattern: String,
        val lane: String,
        val entryScore: Int,
        val regime: String,
        val stampedAt: Long,
    )

    private const val MAX_STAMPS = 2000
    private val stamps = ConcurrentHashMap<String, EntryCtx>()

    fun stamp(
        mint: String,
        entrySetup: String,
        chartPattern: String,
        lane: String,
        entryScore: Int,
        regime: String,
    ) {
        if (mint.isBlank()) return
        try {
            stamps[mint] = EntryCtx(
                entrySetup = entrySetup.ifBlank { "unknown" },
                chartPattern = chartPattern.ifBlank { "unknown" },
                lane = lane.ifBlank { "STANDARD" },
                entryScore = entryScore.coerceIn(0, 100),
                regime = regime.ifBlank { "NORMAL" },
                stampedAt = System.currentTimeMillis(),
            )
            if (stamps.size > MAX_STAMPS) trim()
        } catch (_: Throwable) {}
    }

    fun peek(mint: String): EntryCtx? = stamps[mint]

    /** Consume the stamp (returned + removed). Called by close-side capture so
     *  the registry stays bounded and same-mint re-entries stamp fresh. */
    fun consume(mint: String): EntryCtx? = stamps.remove(mint)

    fun size(): Int = stamps.size

    private fun trim() {
        try {
            val oldest = stamps.entries.sortedBy { it.value.stampedAt }.take(stamps.size - MAX_STAMPS + 200)
            oldest.forEach { stamps.remove(it.key) }
        } catch (_: Throwable) {}
    }

    fun statusLine(): String = "V5_0_6258_ENTRY_CONTEXT_REGISTRY: stamps=${stamps.size}/$MAX_STAMPS"
}

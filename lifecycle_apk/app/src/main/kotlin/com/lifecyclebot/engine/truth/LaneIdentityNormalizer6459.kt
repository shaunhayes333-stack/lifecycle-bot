package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6459 §P1 — LANE / STRATEGY IDENTITY NORMALISATION.
 *
 * Operator: alias merges=80 rising. Rows mix RESALE_SNIPE vs PRESALE_SNIPE,
 * BLUE_CHIP vs BLUECHIP, etc. Canonicalize at ingestion so origin lane
 * is immutable. Rewrites are recorded but treated as migration events,
 * never normal runtime state.
 */
object LaneIdentityNormalizer6459 {
    private val ALIASES: Map<String, String> = mapOf(
        "BLUE_CHIP" to "BLUECHIP",
        "BLUE-CHIP" to "BLUECHIP",
        "RESALE_SNIPE" to "PRESALE_SNIPE",
        "RESALE-SNIPE" to "PRESALE_SNIPE",
        "MOMENTUM_SWING" to "MOMENTUM_SWING",
        "MOMENTUM-SWING" to "MOMENTUM_SWING",
        "MICROCAP" to "MICRO_CAP",
        "WHALE-FOLLOW" to "WHALE_FOLLOW",
    )
    private val counts = ConcurrentHashMap<String, AtomicLong>()
    private val rewrites = AtomicLong(0L)

    fun canonicalize(lane: String?): String {
        val raw = lane?.uppercase()?.trim().orEmpty()
        if (raw.isBlank()) return "UNKNOWN"
        val canonical = ALIASES[raw] ?: raw
        counts.getOrPut(canonical) { AtomicLong(0L) }.incrementAndGet()
        if (canonical != raw) {
            rewrites.incrementAndGet()
            try { PipelineHealthCollector.labelInc("LANE_ALIAS_REWRITE_6459") } catch (_: Throwable) {}
        }
        return canonical
    }

    fun statusLine(): String {
        val top = counts.entries.sortedByDescending { it.value.get() }.take(5)
            .joinToString(",") { "${it.key}=${it.value.get()}" }
        return "rewrites=${rewrites.get()} top[$top]"
    }
}

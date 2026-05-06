package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.91 — per-mint cache of the latest SmartChartScanner result.
 *
 * V5.9.495z14 — extended to also store the list of detected pattern
 * names so the entry decision can apply learned per-pattern multipliers
 * from PatternAutoTuner (closing the previously-orphaned chart pattern
 * learning loop).
 */
object SmartChartCache {

    private const val MAX_AGE_MS = 120_000L   // 2 min — anything older is stale

    private data class Entry(
        val bias: String,                   // "BULLISH" / "BEARISH" / "NEUTRAL"
        val confidence: Double,             // 0..100
        val timestamp: Long,
        val patternNames: List<String>,     // V5.9.495z14 — both candle + chart pattern enum names
    )

    private val store = ConcurrentHashMap<String, Entry>()

    fun update(mint: String, bias: String, confidence: Double) {
        // Legacy callers without pattern names — preserve previous behavior.
        store[mint] = Entry(bias.uppercase(), confidence.coerceIn(0.0, 100.0), System.currentTimeMillis(), emptyList())
    }

    /**
     * V5.9.495z14 — extended update including detected pattern enum names
     * (e.g. "BULLISH_ENGULFING", "CUP_HANDLE", "DEAD_CAT_BOUNCE") so the
     * entry layer can ask PatternAutoTuner.getPatternMultiplier(name) and
     * blend learned per-pattern win-rate weights into entry scoring.
     */
    fun update(mint: String, bias: String, confidence: Double, patternNames: List<String>) {
        store[mint] = Entry(
            bias.uppercase(),
            confidence.coerceIn(0.0, 100.0),
            System.currentTimeMillis(),
            patternNames,
        )
    }

    /**
     * Returns bearish confidence (0..100) when we have a fresh bearish read
     * for this mint, else null.
     */
    fun getBearishConfidence(mint: String): Double? {
        val e = store[mint] ?: return null
        if (System.currentTimeMillis() - e.timestamp > MAX_AGE_MS) return null
        if (e.bias != "BEARISH") return null
        return e.confidence
    }

    /** V5.9.495z14 — fresh detected patterns for this mint (or empty if stale/missing). */
    fun getPatternNames(mint: String): List<String> {
        val e = store[mint] ?: return emptyList()
        if (System.currentTimeMillis() - e.timestamp > MAX_AGE_MS) return emptyList()
        return e.patternNames
    }

    fun clear(mint: String) {
        store.remove(mint)
    }

    fun size(): Int = store.size
}

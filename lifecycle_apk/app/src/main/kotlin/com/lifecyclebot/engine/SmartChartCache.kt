package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.91 — per-mint cache of the latest SmartChartScanner result.
 *
 * Motivation: SmartChart was running on every evaluation tick, logging a
 * "BEARISH (100%)" reading, then throwing the result away. The entry layer
 * never saw it and would happily execute LONG + EXECUTE_AGGRESSIVE on a chart
 * that was screaming distribution.
 *
 * This cache lets the buy path read the most recent bias + confidence
 * (populated async by BotService) without blocking or re-running the scanner.
 */
object SmartChartCache {

    private const val MAX_AGE_MS = 120_000L   // 2 min — anything older is stale

    private data class Entry(
        val bias: String,          // "BULLISH" / "BEARISH" / "NEUTRAL"
        val confidence: Double,    // 0..100
        val timestamp: Long
    )

    private val store = ConcurrentHashMap<String, Entry>()

    fun update(mint: String, bias: String, confidence: Double) {
        store[mint] = Entry(bias.uppercase(), confidence.coerceIn(0.0, 100.0), System.currentTimeMillis())
    }

    /**
     * Returns bearish confidence (0..100) when we have a fresh bearish read
     * for this mint, else null. Null = no cached read or stale or not bearish.
     */
    fun getBearishConfidence(mint: String): Double? {
        val e = store[mint] ?: return null
        if (System.currentTimeMillis() - e.timestamp > MAX_AGE_MS) return null
        if (e.bias != "BEARISH") return null
        return e.confidence
    }

    fun clear(mint: String) {
        store.remove(mint)
    }

    fun size(): Int = store.size
}

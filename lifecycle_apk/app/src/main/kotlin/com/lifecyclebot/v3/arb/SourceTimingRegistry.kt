package com.lifecyclebot.v3.arb

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * SourceTimingRegistry - Tracks when/where tokens are first seen
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * CRITICAL for VENUE_LAG detection.
 * 
 * Records:
 * - First source seen
 * - First timestamp  
 * - First liquidity
 * - First price
 * - First buy pressure
 * 
 * This powers venue-lag detection by identifying when a token appears
 * on multiple sources with timing gaps.
 */
object SourceTimingRegistry {
    
    private const val TAG = "SourceTiming"
    private const val MAX_RECORDS_PER_TOKEN = 10
    private const val MAX_TOKENS = 5000
    private const val RECORD_TTL_MS = 30 * 60 * 1000L  // 30 minutes
    
    // Thread-safe storage: mint -> list of source records
    private val firstSeen = ConcurrentHashMap<String, MutableList<SourceSeenRecord>>()
    
    // Stats
    @Volatile private var totalRecords = 0
    @Volatile private var venueLagsDetected = 0
    
    /**
     * Record a token being seen on a source.
     * Only adds if this source hasn't been recorded yet for this token.
     */
    fun record(record: SourceSeenRecord) {
        try {
            val list = firstSeen.getOrPut(record.mint) { mutableListOf() }
            
            synchronized(list) {
                // Check if this source already recorded
                if (list.none { it.source == record.source }) {
                    list.add(record)
                    totalRecords++
                    
                    // Keep list bounded
                    if (list.size > MAX_RECORDS_PER_TOKEN) {
                        list.removeAt(0)
                    }
                    
                    // Log multi-source detection
                    if (list.size >= 2) {
                        val first = list.minByOrNull { it.seenAtMs }
                        val latest = list.maxByOrNull { it.seenAtMs }
                        if (first != null && latest != null) {
                            val lagMs = latest.seenAtMs - first.seenAtMs
                            if (lagMs in 1000..120_000) {
                                venueLagsDetected++
                                ErrorLogger.debug(TAG, "[ARB] Venue lag: ${record.mint.take(8)}... | ${first.source} -> ${latest.source} | lag=${lagMs}ms")
                            }
                        }
                    }
                }
            }
            
            // Cleanup if too many tokens
            if (firstSeen.size > MAX_TOKENS) {
                cleanup()
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "record error: ${e.message}")
        }
    }
    
    /**
     * Record from individual parameters (convenience method).
     */
    fun record(
        mint: String,
        source: String,
        price: Double?,
        liquidityUsd: Double,
        buyPressurePct: Double?
    ) {
        record(SourceSeenRecord(
            mint = mint,
            source = source,
            seenAtMs = System.currentTimeMillis(),
            price = price,
            liquidityUsd = liquidityUsd,
            buyPressurePct = buyPressurePct
        ))
    }
    
    /**
     * Get all source records for a token.
     */
    fun getRecords(mint: String): List<SourceSeenRecord> {
        return try {
            firstSeen[mint]?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Get the first source record for a token.
     */
    fun getFirstSeen(mint: String): SourceSeenRecord? {
        return try {
            firstSeen[mint]?.minByOrNull { it.seenAtMs }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Get the latest source record for a token.
     */
    fun getLatestSeen(mint: String): SourceSeenRecord? {
        return try {
            firstSeen[mint]?.maxByOrNull { it.seenAtMs }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Check if venue lag exists for a token.
     * Returns lag in milliseconds, or null if no lag detected.
     */
    fun getVenueLagMs(mint: String): Long? {
        return try {
            val records = firstSeen[mint] ?: return null
            if (records.size < 2) return null
            
            val first = records.minByOrNull { it.seenAtMs } ?: return null
            val latest = records.maxByOrNull { it.seenAtMs } ?: return null
            
            val lag = latest.seenAtMs - first.seenAtMs
            if (lag in 1000..120_000) lag else null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Get number of unique sources a token has been seen on.
     */
    fun getSourceCount(mint: String): Int {
        return try {
            firstSeen[mint]?.map { it.source }?.distinct()?.size ?: 0
        } catch (e: Exception) {
            0
        }
    }
    
    /**
     * Get price change since first seen.
     */
    fun getPriceChangeSinceFirstSeen(mint: String, currentPrice: Double): Double? {
        return try {
            val first = getFirstSeen(mint) ?: return null
            val firstPrice = first.price ?: return null
            if (firstPrice <= 0) return null
            ((currentPrice - firstPrice) / firstPrice) * 100.0
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Cleanup old records.
     */
    fun cleanup() {
        try {
            val now = System.currentTimeMillis()
            val cutoff = now - RECORD_TTL_MS
            
            val toRemove = mutableListOf<String>()
            
            firstSeen.forEach { (mint, records) ->
                synchronized(records) {
                    records.removeAll { it.seenAtMs < cutoff }
                    if (records.isEmpty()) {
                        toRemove.add(mint)
                    }
                }
            }
            
            toRemove.forEach { firstSeen.remove(it) }
            
            if (toRemove.isNotEmpty()) {
                ErrorLogger.debug(TAG, "Cleaned up ${toRemove.size} stale tokens")
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "cleanup error: ${e.message}")
        }
    }
    
    /**
     * Get stats for logging.
     */
    fun getStats(): String {
        return "SourceTiming: ${firstSeen.size} tokens | $totalRecords records | $venueLagsDetected lags detected"
    }
    
    /**
     * Clear all data.
     */
    fun clear() {
        firstSeen.clear()
        totalRecords = 0
        venueLagsDetected = 0
    }
}

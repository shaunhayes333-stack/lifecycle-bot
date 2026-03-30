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
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V4.1 SOURCE TIMING LAG SCORING (P2)
    // 
    // Penalize tokens that are ONLY seen on DEX trending (late signals).
    // If a token appears on DEX trending but was NOT seen on pump.fun or new-pool,
    // it means we're late to the party and entry risk is higher.
    // 
    // Scoring:
    //   - First seen on PUMP_FUN_NEW/RAYDIUM_NEW_POOL: +0 (ideal)
    //   - First seen on DEX_TRENDING but ALSO on early source: -5 (moderate lag)
    //   - ONLY seen on DEX_TRENDING/GAINERS: -15 (late entry)
    //   - ONLY seen on BIRDEYE/COINGECKO trending: -20 (very late)
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Early sources (tokens discovered here first = good timing)
    private val EARLY_SOURCES = setOf(
        "PUMP_FUN_NEW", "PUMP_FUN_GRADUATE", "RAYDIUM_NEW_POOL", "MOONSHOT_NEW"
    )
    
    // Trending sources (tokens only found here = late timing)
    private val TRENDING_SOURCES = setOf(
        "DEX_TRENDING", "DEX_GAINERS", "BIRDEYE_TRENDING", "COINGECKO_TRENDING"
    )
    
    /**
     * Calculate source timing penalty for a token.
     * Returns a negative score adjustment (penalty) for late-discovered tokens.
     * 
     * @param mint Token mint address
     * @return Pair(penalty, reason) where penalty is 0 to -20
     */
    fun getSourceTimingPenalty(mint: String): Pair<Int, String> {
        return try {
            val records = firstSeen[mint] ?: return Pair(0, "no_source_data")
            if (records.isEmpty()) return Pair(0, "no_source_data")
            
            val sources = records.map { it.source.uppercase() }.distinct()
            val firstSource = records.minByOrNull { it.seenAtMs }?.source?.uppercase() ?: "UNKNOWN"
            
            // Check if ANY early source was seen
            val hasEarlySource = sources.any { it in EARLY_SOURCES }
            
            // Check if ONLY trending sources
            val onlyTrending = sources.all { it in TRENDING_SOURCES || it.contains("TRENDING") || it.contains("GAINERS") }
            
            // Check first source type
            val firstWasEarly = firstSource in EARLY_SOURCES
            val firstWasTrending = firstSource in TRENDING_SOURCES || firstSource.contains("TRENDING")
            
            when {
                // Best case: first seen on early source
                firstWasEarly -> Pair(0, "early_discovery:$firstSource")
                
                // Good: seen on trending but also has early source data
                hasEarlySource && firstWasTrending -> {
                    val lag = getVenueLagMs(mint)
                    if (lag != null && lag > 60_000) {
                        // More than 1 minute lag = more penalty
                        Pair(-8, "late_arrival:${lag/1000}s_lag")
                    } else {
                        Pair(-5, "moderate_lag:has_early_but_trending_first")
                    }
                }
                
                // Bad: only seen on DEX trending/gainers
                onlyTrending && (firstSource.contains("DEX") || firstSource.contains("GAINERS")) -> {
                    Pair(-15, "late_signal:only_dex_trending")
                }
                
                // Worst: only seen on social trending (very late)
                onlyTrending -> {
                    Pair(-20, "very_late:only_social_trending")
                }
                
                // Unknown source pattern
                else -> Pair(-3, "unknown_timing:$firstSource")
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "getSourceTimingPenalty error: ${e.message}")
            Pair(0, "error")
        }
    }
    
    /**
     * Check if a token is a "late signal" (only seen on trending, not on new-pool sources).
     */
    fun isLateSignal(mint: String): Boolean {
        val (penalty, _) = getSourceTimingPenalty(mint)
        return penalty <= -10
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

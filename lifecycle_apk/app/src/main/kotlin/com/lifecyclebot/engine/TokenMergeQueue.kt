package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * TOKEN MERGE QUEUE - V4.0 DUPLICATE SCANNER DETECTION
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * PROBLEM SOLVED:
 * Multiple scanners (DEX_BOOSTED, PUMP_FUN_GRADUATE, V3_SCANNER, etc.) find the
 * same token simultaneously. Without coordination:
 * - Same token gets added to watchlist multiple times
 * - Multiple AI layers evaluate it in parallel
 * - Conflicting execution decisions
 * - Duplicate positions opened
 * 
 * SOLUTION:
 * All scanner discoveries go through this merge queue first:
 * 1. Scanner finds token → enqueue(token, scanner)
 * 2. Merge queue batches discoveries within a window
 * 3. If same token found by multiple scanners → merge into single entry
 * 4. Best signal wins (highest confidence scanner)
 * 5. Single merged token goes to watchlist
 * 
 * BENEFITS:
 * - DEX_BOOSTED + PUMP_FUN finding same token = stronger signal
 * - Only ONE watchlist add per token
 * - Scanner attribution for analytics
 * - Confidence boost from multi-scanner detection
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object TokenMergeQueue {
    
    private const val TAG = "TokenMergeQ"
    
    // Merge window - discoveries within this window are considered simultaneous
    private const val MERGE_WINDOW_MS = 5_000L  // 5 seconds
    
    // How often to process the queue
    private const val PROCESS_INTERVAL_MS = 2_000L  // 2 seconds
    
    // Scanner confidence rankings (higher = better)
    // V5.0: DRASTICALLY lowered single-source confidence
    // PUMP_FUN_GRADUATE was flooding the registry at conf=80
    private val scannerConfidence = mapOf(
        "DEX_BOOSTED" to 60,           // Was 90 - still needs multi-source
        "V3_PREMIUM" to 55,            // Was 85
        "PUMP_FUN_GRADUATE" to 35,     // Was 80 - THIS WAS THE BUG
        "WHALE_COPY" to 50,            // Was 75
        "RAYDIUM_NEW_POOL" to 40,      // Was 70
        "RAYDIUM_NEW" to 40,           // Was 70
        "MOONSHOT" to 45,              // Was 65
        "V3_SCANNER" to 40,            // Was 60
        "SOCIAL_TRENDING" to 35,       // Was 55
        "DEX_TRENDING" to 45,          // Added
        "USER_ADDED" to 30,            // Was 50
        "UNKNOWN" to 20,               // Was 40
    )
    
    // Multi-source boost - only significant conf comes from confirmation
    private const val MULTI_SOURCE_BOOST = 25  // Bonus for 2+ sources
    
    // Pending discoveries queue
    private val pendingDiscoveries = ConcurrentHashMap<String, MergeEntry>()
    
    // Last process time
    @Volatile
    private var lastProcessTime = 0L
    
    // Stats
    private val totalDiscoveries = AtomicInteger(0)
    private val totalMerges = AtomicInteger(0)
    private val totalEmitted = AtomicInteger(0)
    
    data class MergeEntry(
        val mint: String,
        var symbol: String,
        var marketCapUsd: Double,
        var liquidityUsd: Double,
        val firstSeenAt: Long,
        var lastSeenAt: Long,
        val scanners: MutableSet<String>,  // All scanners that found this token
        var bestScanner: String,           // Highest confidence scanner
        var confidence: Int,               // Combined confidence score
        var discoveryCount: Int,           // How many times discovered in window
    )
    
    data class MergedToken(
        val mint: String,
        val symbol: String,
        val marketCapUsd: Double,
        val liquidityUsd: Double,
        val primaryScanner: String,
        val allScanners: Set<String>,
        val confidence: Int,
        val multiScannerBoost: Boolean,
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // QUEUE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Enqueue a token discovery from a scanner.
     * Call this instead of directly adding to watchlist.
     */
    fun enqueue(
        mint: String,
        symbol: String,
        scanner: String,
        marketCapUsd: Double = 0.0,
        liquidityUsd: Double = 0.0,
    ) {
        val now = System.currentTimeMillis()
        totalDiscoveries.incrementAndGet()
        
        val existing = pendingDiscoveries[mint]
        
        if (existing != null) {
            // Token already pending - MERGE
            existing.scanners.add(scanner)
            existing.lastSeenAt = now
            existing.discoveryCount++
            
            // Update best scanner if this one is better
            val scannerConf = scannerConfidence[scanner] ?: 40
            if (scannerConf > (scannerConfidence[existing.bestScanner] ?: 0)) {
                existing.bestScanner = scanner
            }
            
            // Boost confidence for multi-scanner detection
            existing.confidence = calculateMergedConfidence(existing.scanners)
            
            // Update market data if better
            if (marketCapUsd > existing.marketCapUsd) {
                existing.marketCapUsd = marketCapUsd
            }
            if (liquidityUsd > existing.liquidityUsd) {
                existing.liquidityUsd = liquidityUsd
            }
            if (symbol.length > existing.symbol.length) {
                existing.symbol = symbol
            }
            
            totalMerges.incrementAndGet()
            ErrorLogger.debug(TAG, "🔀 MERGED: $symbol | scanners=${existing.scanners.joinToString(",")} | conf=${existing.confidence}")
            
        } else {
            // New token - add to pending
            pendingDiscoveries[mint] = MergeEntry(
                mint = mint,
                symbol = symbol,
                marketCapUsd = marketCapUsd,
                liquidityUsd = liquidityUsd,
                firstSeenAt = now,
                lastSeenAt = now,
                scanners = mutableSetOf(scanner),
                bestScanner = scanner,
                confidence = scannerConfidence[scanner] ?: 40,
                discoveryCount = 1,
            )
            
            ErrorLogger.debug(TAG, "➕ QUEUED: $symbol | scanner=$scanner")
        }
    }
    
    /**
     * Process the queue and emit merged tokens.
     * Returns list of tokens ready to be added to watchlist.
     * Call this every ~2 seconds from bot loop.
     */
    fun processQueue(): List<MergedToken> {
        val now = System.currentTimeMillis()
        
        // Throttle processing
        if (now - lastProcessTime < PROCESS_INTERVAL_MS) {
            return emptyList()
        }
        lastProcessTime = now
        
        val readyToEmit = mutableListOf<MergedToken>()
        val toRemove = mutableListOf<String>()
        
        for ((mint, entry) in pendingDiscoveries) {
            val elapsed = now - entry.firstSeenAt
            
            // Emit if merge window expired
            if (elapsed >= MERGE_WINDOW_MS) {
                val merged = MergedToken(
                    mint = entry.mint,
                    symbol = entry.symbol,
                    marketCapUsd = entry.marketCapUsd,
                    liquidityUsd = entry.liquidityUsd,
                    primaryScanner = entry.bestScanner,
                    allScanners = entry.scanners.toSet(),
                    confidence = entry.confidence,
                    multiScannerBoost = entry.scanners.size > 1,
                )
                
                readyToEmit.add(merged)
                toRemove.add(mint)
                totalEmitted.incrementAndGet()
                
                val boostLabel = if (merged.multiScannerBoost) " [MULTI-SCANNER BOOST!]" else ""
                ErrorLogger.debug(TAG, "📤 EMIT: ${entry.symbol} | scanners=${entry.scanners.joinToString(",")} | conf=${entry.confidence}$boostLabel")
            }
        }
        
        // Remove emitted entries
        for (mint in toRemove) {
            pendingDiscoveries.remove(mint)
        }
        
        return readyToEmit
    }
    
    /**
     * Calculate combined confidence from multiple scanners.
     * V5.0: Much more conservative - single source stays LOW
     * Multi-scanner detection increases confidence significantly.
     */
    private fun calculateMergedConfidence(scanners: Set<String>): Int {
        if (scanners.isEmpty()) return 20
        
        // Start with best scanner's confidence (now much lower)
        val baseConfidence = scanners.maxOfOrNull { scannerConfidence[it] ?: 20 } ?: 20
        
        // V5.0: Only boost if MULTIPLE sources confirm
        // Single source stays at base (which is now 35-60 max)
        val multiSourceBonus = when (scanners.size) {
            1 -> 0                        // NO BONUS for single source!
            2 -> MULTI_SOURCE_BOOST       // +25 for confirmation
            3 -> MULTI_SOURCE_BOOST + 10  // +35 for triple confirm
            else -> MULTI_SOURCE_BOOST + 15  // +40 cap
        }
        
        return (baseConfidence + multiSourceBonus).coerceAtMost(95)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // QUERIES
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Check if a token is pending in the queue.
     */
    fun isPending(mint: String): Boolean = pendingDiscoveries.containsKey(mint)
    
    /**
     * Get pending count.
     */
    fun getPendingCount(): Int = pendingDiscoveries.size
    
    /**
     * Get stats.
     */
    fun getStats(): String {
        return "MergeQ: pending=${pendingDiscoveries.size} " +
            "discoveries=${totalDiscoveries.get()} " +
            "merges=${totalMerges.get()} " +
            "emitted=${totalEmitted.get()}"
    }
    
    /**
     * Force emit all pending (e.g., on shutdown).
     */
    fun flushAll(): List<MergedToken> {
        val all = pendingDiscoveries.values.map { entry ->
            MergedToken(
                mint = entry.mint,
                symbol = entry.symbol,
                marketCapUsd = entry.marketCapUsd,
                liquidityUsd = entry.liquidityUsd,
                primaryScanner = entry.bestScanner,
                allScanners = entry.scanners.toSet(),
                confidence = entry.confidence,
                multiScannerBoost = entry.scanners.size > 1,
            )
        }
        
        pendingDiscoveries.clear()
        totalEmitted.addAndGet(all.size)
        
        return all
    }
    
    /**
     * Reset all state.
     */
    fun reset() {
        pendingDiscoveries.clear()
        totalDiscoveries.set(0)
        totalMerges.set(0)
        totalEmitted.set(0)
        lastProcessTime = 0L
    }
}

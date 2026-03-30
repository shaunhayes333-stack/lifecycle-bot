package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * GLOBAL TRADE REGISTRY - V4.0 THREAD-SAFE WATCHLIST & STATE MANAGEMENT
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * PROBLEM SOLVED:
 * The watchlist was randomly resetting from 31 tokens to 1 due to:
 * 1. Multiple threads reading/writing to cfg.watchlist
 * 2. ConfigStore.save() being called with stale watchlist data
 * 3. No synchronization between BotService, scanners, and AI layers
 * 4. Race conditions when tokens are added/removed simultaneously
 * 
 * SOLUTION:
 * Single source of truth for ALL token tracking state:
 * - Watchlist (tokens being monitored)
 * - Active positions (across ALL layers)
 * - Duplicate suppression (prevent re-adding same token)
 * - Exposure tracking (total SOL committed)
 * 
 * USAGE:
 * - ALL watchlist mutations go through GlobalTradeRegistry
 * - ConfigStore.watchlist is READ-ONLY after init
 * - Scanners call addToWatchlist() instead of modifying cfg directly
 * - BotService calls getWatchlist() to get current list
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object GlobalTradeRegistry {
    
    private const val TAG = "GlobalTradeReg"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // THREAD-SAFE WATCHLIST
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Master watchlist - ConcurrentHashMap for thread safety
    // Key: mint address, Value: WatchlistEntry with metadata
    private val watchlist = ConcurrentHashMap<String, WatchlistEntry>()
    
    // Recently processed tokens - prevents duplicate processing in same cycle
    // Key: mint, Value: last processed timestamp
    private val recentlyProcessed = ConcurrentHashMap<String, Long>()
    
    // Tokens that have been rejected - prevents re-adding rejected tokens
    // Key: mint, Value: RejectionEntry
    private val rejectedTokens = ConcurrentHashMap<String, RejectionEntry>()
    
    // Active positions across ALL layers
    // Key: mint, Value: PositionEntry
    private val activePositions = ConcurrentHashMap<String, PositionEntry>()
    
    // Counters
    private val totalTokensAdded = AtomicLong(0)
    private val totalTokensRemoved = AtomicLong(0)
    private val duplicatesBlocked = AtomicLong(0)
    
    // Timing constants
    private const val DUPLICATE_COOLDOWN_MS = 300_000L  // 5 minutes - don't re-add same token
    private const val REJECTION_COOLDOWN_MS = 600_000L  // 10 minutes - rejected tokens can't be re-added
    private const val PROCESS_COOLDOWN_MS = 10_000L     // 10 seconds - between processing same token
    
    // Maximum watchlist size to prevent memory issues
    private const val MAX_WATCHLIST_SIZE = 100
    
    data class WatchlistEntry(
        val mint: String,
        val symbol: String,
        val addedAt: Long,
        val addedBy: String,  // "SCANNER", "USER", "DEX_BOOSTED", "PUMP_FUN", etc.
        val source: String,   // More specific: "pump.fun", "raydium", "moonshot"
        val initialMcap: Double,
        var lastProcessedAt: Long = 0,
        var processCount: Int = 0,
    )
    
    data class RejectionEntry(
        val mint: String,
        val symbol: String,
        val rejectedAt: Long,
        val reason: String,
        val rejectedBy: String,  // "V3", "FDG", "FILTERS", etc.
    )
    
    data class PositionEntry(
        val mint: String,
        val symbol: String,
        val layer: String,  // "V3", "TREASURY", "BLUE_CHIP", "SHITCOIN"
        val openedAt: Long,
        val sizeSol: Double,
        var currentPnlPct: Double = 0.0,
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Initialize from ConfigStore watchlist.
     * Called once at bot startup.
     */
    fun init(initialWatchlist: List<String>, defaultSource: String = "CONFIG") {
        watchlist.clear()
        val now = System.currentTimeMillis()
        
        for (mint in initialWatchlist) {
            if (mint.isNotBlank() && mint.length > 30) {
                watchlist[mint] = WatchlistEntry(
                    mint = mint,
                    symbol = mint.take(8),  // Will be updated when token data is fetched
                    addedAt = now,
                    addedBy = defaultSource,
                    source = defaultSource,
                    initialMcap = 0.0,
                )
            }
        }
        
        ErrorLogger.info(TAG, "✅ Initialized with ${watchlist.size} tokens from $defaultSource")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // WATCHLIST OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Add a token to the watchlist.
     * Returns true if added, false if duplicate/rejected/full.
     */
    fun addToWatchlist(
        mint: String,
        symbol: String,
        addedBy: String,
        source: String = addedBy,
        initialMcap: Double = 0.0,
    ): AddResult {
        // Validate mint
        if (mint.isBlank() || mint.length < 30) {
            return AddResult(false, "INVALID_MINT")
        }
        
        val now = System.currentTimeMillis()
        
        // Check if already in watchlist
        val existing = watchlist[mint]
        if (existing != null) {
            duplicatesBlocked.incrementAndGet()
            return AddResult(false, "DUPLICATE: already watching since ${(now - existing.addedAt)/1000}s ago")
        }
        
        // Check if recently rejected
        val rejection = rejectedTokens[mint]
        if (rejection != null) {
            val elapsed = now - rejection.rejectedAt
            if (elapsed < REJECTION_COOLDOWN_MS) {
                duplicatesBlocked.incrementAndGet()
                return AddResult(false, "REJECTED: ${rejection.reason} (${elapsed/1000}s ago)")
            } else {
                // Cooldown expired, remove rejection
                rejectedTokens.remove(mint)
            }
        }
        
        // Check if recently processed (prevent spam)
        val lastProcessed = recentlyProcessed[mint]
        if (lastProcessed != null) {
            val elapsed = now - lastProcessed
            if (elapsed < DUPLICATE_COOLDOWN_MS) {
                duplicatesBlocked.incrementAndGet()
                return AddResult(false, "COOLDOWN: processed ${elapsed/1000}s ago")
            }
        }
        
        // Check watchlist size
        if (watchlist.size >= MAX_WATCHLIST_SIZE) {
            // Remove oldest non-position entry
            val removed = pruneOldestEntry()
            if (!removed) {
                return AddResult(false, "WATCHLIST_FULL: ${watchlist.size}/$MAX_WATCHLIST_SIZE")
            }
        }
        
        // Add to watchlist
        watchlist[mint] = WatchlistEntry(
            mint = mint,
            symbol = symbol,
            addedAt = now,
            addedBy = addedBy,
            source = source,
            initialMcap = initialMcap,
        )
        
        totalTokensAdded.incrementAndGet()
        ErrorLogger.debug(TAG, "➕ Added $symbol | by=$addedBy | source=$source | mcap=\$${initialMcap.toLong()}")
        
        return AddResult(true, "ADDED")
    }
    
    data class AddResult(
        val added: Boolean,
        val reason: String,
    )
    
    /**
     * Remove a token from the watchlist.
     */
    fun removeFromWatchlist(mint: String, reason: String = "MANUAL"): Boolean {
        val removed = watchlist.remove(mint)
        if (removed != null) {
            totalTokensRemoved.incrementAndGet()
            ErrorLogger.debug(TAG, "➖ Removed ${removed.symbol} | reason=$reason")
            return true
        }
        return false
    }
    
    /**
     * Register that a token was rejected (so it won't be re-added).
     */
    fun registerRejection(mint: String, symbol: String, reason: String, rejectedBy: String) {
        rejectedTokens[mint] = RejectionEntry(
            mint = mint,
            symbol = symbol,
            rejectedAt = System.currentTimeMillis(),
            reason = reason,
            rejectedBy = rejectedBy,
        )
        
        // Also remove from watchlist if present
        removeFromWatchlist(mint, "REJECTED: $reason")
    }
    
    /**
     * Mark a token as processed in this cycle.
     */
    fun markProcessed(mint: String) {
        recentlyProcessed[mint] = System.currentTimeMillis()
        watchlist[mint]?.let {
            it.lastProcessedAt = System.currentTimeMillis()
            it.processCount++
        }
    }
    
    /**
     * Check if we can process a token (not processed too recently).
     */
    fun canProcess(mint: String): Boolean {
        val lastProcessed = recentlyProcessed[mint] ?: return true
        return System.currentTimeMillis() - lastProcessed >= PROCESS_COOLDOWN_MS
    }
    
    /**
     * Get current watchlist as a list of mint addresses.
     * This is the ONLY way to read the watchlist.
     */
    fun getWatchlist(): List<String> {
        return watchlist.keys.toList()
    }
    
    /**
     * Get watchlist entries with metadata.
     */
    fun getWatchlistEntries(): List<WatchlistEntry> {
        return watchlist.values.toList()
    }
    
    /**
     * Get watchlist size.
     */
    fun size(): Int = watchlist.size
    
    /**
     * Check if a token is in the watchlist.
     */
    fun isWatching(mint: String): Boolean = watchlist.containsKey(mint)
    
    /**
     * Update symbol for a token (when we fetch actual data).
     */
    fun updateSymbol(mint: String, symbol: String) {
        watchlist[mint]?.let { entry ->
            watchlist[mint] = entry.copy(symbol = symbol)
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION TRACKING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Register an open position.
     */
    fun registerPosition(mint: String, symbol: String, layer: String, sizeSol: Double) {
        activePositions[mint] = PositionEntry(
            mint = mint,
            symbol = symbol,
            layer = layer,
            openedAt = System.currentTimeMillis(),
            sizeSol = sizeSol,
        )
        ErrorLogger.debug(TAG, "📊 Position opened: $symbol | layer=$layer | size=$sizeSol SOL")
    }
    
    /**
     * Close a position.
     */
    fun closePosition(mint: String): PositionEntry? {
        val pos = activePositions.remove(mint)
        if (pos != null) {
            ErrorLogger.debug(TAG, "📊 Position closed: ${pos.symbol} | layer=${pos.layer}")
        }
        return pos
    }
    
    /**
     * Check if a position is open for a token.
     */
    fun hasOpenPosition(mint: String): Boolean = activePositions.containsKey(mint)
    
    /**
     * Get all open positions.
     */
    fun getOpenPositions(): List<PositionEntry> = activePositions.values.toList()
    
    /**
     * Get total exposure in SOL.
     */
    fun getTotalExposure(): Double = activePositions.values.sumOf { it.sizeSol }
    
    /**
     * Get position count.
     */
    fun getPositionCount(): Int = activePositions.size
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAINTENANCE
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Prune oldest non-position entry from watchlist.
     * Returns true if an entry was removed.
     */
    private fun pruneOldestEntry(): Boolean {
        val oldest = watchlist.values
            .filter { !activePositions.containsKey(it.mint) }
            .minByOrNull { it.addedAt }
        
        if (oldest != null) {
            watchlist.remove(oldest.mint)
            totalTokensRemoved.incrementAndGet()
            ErrorLogger.debug(TAG, "🧹 Pruned ${oldest.symbol} (oldest, no position)")
            return true
        }
        return false
    }
    
    /**
     * Clean up expired rejections and cooldowns.
     * Call this periodically (e.g., every loop).
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        
        // Clean old rejections
        rejectedTokens.entries.removeIf { now - it.value.rejectedAt > REJECTION_COOLDOWN_MS }
        
        // Clean old processed entries
        recentlyProcessed.entries.removeIf { now - it.value > DUPLICATE_COOLDOWN_MS }
    }
    
    /**
     * Sync watchlist back to ConfigStore.
     * Call this periodically to persist state.
     */
    fun syncToConfig(context: android.content.Context) {
        try {
            val cfg = com.lifecyclebot.data.ConfigStore.load(context)
            val currentList = getWatchlist()
            
            // Only save if different
            if (cfg.watchlist.toSet() != currentList.toSet()) {
                val updated = cfg.copy(watchlist = currentList)
                com.lifecyclebot.data.ConfigStore.save(context, updated)
                ErrorLogger.debug(TAG, "💾 Synced ${currentList.size} tokens to ConfigStore")
            }
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to sync to ConfigStore: ${e.message}", e)
        }
    }
    
    /**
     * Get stats for logging/debugging.
     */
    fun getStats(): String {
        return "GlobalTradeReg: watching=${watchlist.size} positions=${activePositions.size} " +
            "added=${totalTokensAdded.get()} removed=${totalTokensRemoved.get()} " +
            "dupes_blocked=${duplicatesBlocked.get()}"
    }
    
    /**
     * Reset all state (for testing or full restart).
     */
    fun reset() {
        watchlist.clear()
        recentlyProcessed.clear()
        rejectedTokens.clear()
        activePositions.clear()
        totalTokensAdded.set(0)
        totalTokensRemoved.set(0)
        duplicatesBlocked.set(0)
        ErrorLogger.info(TAG, "🔄 Registry reset")
    }
}

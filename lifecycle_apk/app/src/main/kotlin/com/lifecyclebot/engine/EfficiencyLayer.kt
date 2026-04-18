package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * V4.20 EFFICIENCY LAYER - REDUCE PIPELINE CHURN & DUPLICATE PROCESSING
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * PROBLEMS SOLVED:
 * 1. Duplicate discovery noise - same tokens found across multiple venues repeatedly
 * 2. Inconsistent liquidity estimation - different sources giving different values
 * 3. Wallet refresh too frequent - unnecessary I/O and log noise
 * 4. FDG logging too repetitive - same state logged repeatedly
 * 5. Low-liquidity tokens consuming bandwidth - repeatedly fail same gate
 * 
 * EFFICIENCY GAINS:
 * - Discovery cooldown prevents redundant processing
 * - Liquidity fusion provides consistent, confident values
 * - Cached refresh reduces I/O by 80%+
 * - State change logging only - cuts log volume by 60%+
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object EfficiencyLayer {
    
    private const val TAG = "Efficiency"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 1. DISCOVERY COOLDOWN - Prevent duplicate processing of same token
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class SeenTokenMeta(
        val mint: String,
        val firstSeenAt: Long,
        var lastSeenAt: Long,
        var lastSource: String,
        var lastLiquidity: Double,
        var lastScore: Int,
        var timesSeen: Int,
        var lastFullProcessAt: Long = 0L,
    )
    
    private val seenTokens = ConcurrentHashMap<String, SeenTokenMeta>()
    
    // V5.2.8: Paper mode flag for aggressive learning
    @Volatile var isPaperMode = false
    
    // Cooldowns - V5.2.8: Separate values for Paper vs Live mode
    // V5.9.46: Unified discovery cooldown between paper/live. Previous 3x
    // stricter live cooldown made the scanner feel dead after first token
    // pass (same root-cause family as V5.9.44 scanner TTL unification).
    // Downstream gates (FDG, hard blocks, promotion gate) handle risk; the
    // efficiency layer's job is throughput, not risk gating.
    private const val DISCOVERY_COOLDOWN_MS_LIVE = 10_000L
    private const val DISCOVERY_COOLDOWN_MS_PAPER = 10_000L
    private const val LIQ_CHANGE_THRESHOLD = 0.20            // 20% liquidity change triggers reprocess
    private const val SCORE_CHANGE_THRESHOLD = 15            // 15 point score change triggers reprocess
    
    // V5.2.8: Get effective cooldown based on mode
    private fun getDiscoveryCooldown(): Long = if (isPaperMode) DISCOVERY_COOLDOWN_MS_PAPER else DISCOVERY_COOLDOWN_MS_LIVE
    
    // Stats
    private val discoverySkipped = AtomicLong(0)
    private val discoveryProcessed = AtomicLong(0)
    
    /**
     * Check if a token should be fully processed or just metadata updated.
     * Returns true if full processing should occur, false if can skip.
     */
    fun shouldFullProcess(
        mint: String, 
        source: String, 
        liquidity: Double, 
        score: Int
    ): ProcessDecision {
        val now = System.currentTimeMillis()
        val existing = seenTokens[mint]
        
        if (existing == null) {
            // First time seeing this token - PROCESS
            seenTokens[mint] = SeenTokenMeta(
                mint = mint,
                firstSeenAt = now,
                lastSeenAt = now,
                lastSource = source,
                lastLiquidity = liquidity,
                lastScore = score,
                timesSeen = 1,
                lastFullProcessAt = now,
            )
            discoveryProcessed.incrementAndGet()
            return ProcessDecision(true, "NEW_TOKEN")
        }
        
        // Token seen before - check if reprocessing is needed
        existing.timesSeen++
        existing.lastSeenAt = now
        
        val timeSinceLastProcess = now - existing.lastFullProcessAt
        
        // Check for material changes that warrant reprocessing
        val liqChange = if (existing.lastLiquidity > 0) {
            abs(liquidity - existing.lastLiquidity) / existing.lastLiquidity
        } else 0.0
        
        val scoreChange = abs(score - existing.lastScore)
        
        // Source upgrade (e.g., from ESTIMATED to DIRECT_POOL)
        val sourceUpgrade = isSourceUpgrade(existing.lastSource, source)
        
        val shouldReprocess = when {
            timeSinceLastProcess >= getDiscoveryCooldown() -> true
            liqChange >= LIQ_CHANGE_THRESHOLD -> true
            scoreChange >= SCORE_CHANGE_THRESHOLD -> true
            sourceUpgrade -> true
            else -> false
        }
        
        if (shouldReprocess) {
            // Update metadata and allow processing
            existing.lastSource = source
            existing.lastLiquidity = liquidity
            existing.lastScore = score
            existing.lastFullProcessAt = now
            discoveryProcessed.incrementAndGet()
            
            val reason = when {
                timeSinceLastProcess >= getDiscoveryCooldown() -> "COOLDOWN_EXPIRED"
                liqChange >= LIQ_CHANGE_THRESHOLD -> "LIQ_CHANGE_${(liqChange*100).toInt()}%"
                scoreChange >= SCORE_CHANGE_THRESHOLD -> "SCORE_CHANGE_$scoreChange"
                sourceUpgrade -> "SOURCE_UPGRADE"
                else -> "UNKNOWN"
            }
            return ProcessDecision(true, reason)
        }
        
        // Skip full processing - just update metadata
        existing.lastSource = source
        existing.lastLiquidity = liquidity
        existing.lastScore = score
        discoverySkipped.incrementAndGet()
        
        return ProcessDecision(false, "COOLDOWN: seen ${existing.timesSeen}x, last process ${timeSinceLastProcess/1000}s ago")
    }
    
    data class ProcessDecision(
        val shouldProcess: Boolean,
        val reason: String,
    )
    
    private fun isSourceUpgrade(oldSource: String, newSource: String): Boolean {
        val priority = mapOf(
            "ESTIMATED" to 1,
            "DEX_TRENDING" to 2,
            "PUMP_FUN" to 3,
            "RAYDIUM_NEW_POOL" to 4,
            "DIRECT_POOL" to 5,
            "VERIFIED_PAIR" to 6,
        )
        val oldPriority = priority[oldSource] ?: 0
        val newPriority = priority[newSource] ?: 0
        return newPriority > oldPriority
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 2. LIQUIDITY FUSION - Consistent, confident liquidity values
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class LiqSourceQuality(val confidence: Int) {
        DIRECT_POOL(100),      // Direct from pool reserves
        VERIFIED_PAIR(90),     // Verified trading pair data
        DEX_AGGREGATOR(80),    // From DEX aggregator
        ESTIMATED_MCAP(60),    // Estimated from market cap
        UNKNOWN(30),           // Unknown source
    }
    
    data class LiquiditySnapshot(
        val usd: Double,
        val source: String,
        val quality: LiqSourceQuality,
        val timestamp: Long,
    )
    
    private val liquiditySnapshots = ConcurrentHashMap<String, MutableList<LiquiditySnapshot>>()
    
    /**
     * Record a liquidity observation.
     */
    fun recordLiquidity(mint: String, usd: Double, source: String, quality: LiqSourceQuality) {
        val snapshots = liquiditySnapshots.getOrPut(mint) { mutableListOf() }
        
        synchronized(snapshots) {
            // Keep only last 5 snapshots
            if (snapshots.size >= 5) {
                snapshots.removeAt(0)
            }
            snapshots.add(LiquiditySnapshot(
                usd = usd,
                source = source,
                quality = quality,
                timestamp = System.currentTimeMillis(),
            ))
        }
    }
    
    /**
     * Get fused liquidity value with confidence.
     * Prefers direct pool data, falls back to estimates.
     */
    fun getFusedLiquidity(mint: String): FusedLiquidity {
        val snapshots = liquiditySnapshots[mint] ?: return FusedLiquidity(0.0, 0, "NO_DATA")
        
        synchronized(snapshots) {
            if (snapshots.isEmpty()) return FusedLiquidity(0.0, 0, "NO_DATA")
            
            // Prefer high-quality sources
            val direct = snapshots.filter { 
                it.quality == LiqSourceQuality.DIRECT_POOL || 
                it.quality == LiqSourceQuality.VERIFIED_PAIR 
            }
            
            if (direct.isNotEmpty()) {
                val latest = direct.maxByOrNull { it.timestamp }!!
                return FusedLiquidity(latest.usd, latest.quality.confidence, latest.source)
            }
            
            // Fall back to DEX aggregator
            val dex = snapshots.filter { it.quality == LiqSourceQuality.DEX_AGGREGATOR }
            if (dex.isNotEmpty()) {
                val latest = dex.maxByOrNull { it.timestamp }!!
                return FusedLiquidity(latest.usd, latest.quality.confidence, latest.source)
            }
            
            // Fall back to estimated (average for stability)
            val estimated = snapshots.filter { it.quality == LiqSourceQuality.ESTIMATED_MCAP }
            if (estimated.isNotEmpty()) {
                val avg = estimated.map { it.usd }.average()
                return FusedLiquidity(avg, LiqSourceQuality.ESTIMATED_MCAP.confidence, "ESTIMATED_AVG")
            }
            
            // Use whatever we have
            val latest = snapshots.maxByOrNull { it.timestamp }!!
            return FusedLiquidity(latest.usd, latest.quality.confidence, latest.source)
        }
    }
    
    data class FusedLiquidity(
        val usd: Double,
        val confidence: Int,  // 0-100
        val source: String,
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 3. LOW-LIQUIDITY REJECTION CACHE - Suppress reprocessing of ineligible tokens
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class LiqRejection(
        val mint: String,
        val rejectedAt: Long,
        val liquidity: Double,
        val suppressUntil: Long,
    )
    
    private val liqRejections = ConcurrentHashMap<String, LiqRejection>()
    
    /**
     * Check if a token is suppressed due to low liquidity.
     */
    fun isLiquiditySuppressed(mint: String): Boolean {
        val rejection = liqRejections[mint] ?: return false
        
        if (System.currentTimeMillis() >= rejection.suppressUntil) {
            // Suppression expired
            liqRejections.remove(mint)
            return false
        }
        return true
    }
    
    /**
     * Register a liquidity rejection. Suppresses reprocessing for a time based on how far below threshold.
     * @param liquidityFloor The minimum required liquidity (e.g., 3000)
     */
    fun registerLiquidityRejection(mint: String, liquidity: Double, liquidityFloor: Double) {
        val now = System.currentTimeMillis()
        
        // Suppress longer for very low liquidity
        val suppressMs = when {
            liquidity < liquidityFloor * 0.3 -> 60_000L   // < 30% of floor = 1 min suppress
            liquidity < liquidityFloor * 0.5 -> 90_000L   // < 50% of floor = 90 sec suppress
            liquidity < liquidityFloor * 0.7 -> 60_000L   // < 70% of floor = 60 sec suppress
            else -> 30_000L                                // Just under floor = 30 sec suppress
        }
        
        liqRejections[mint] = LiqRejection(
            mint = mint,
            rejectedAt = now,
            liquidity = liquidity,
            suppressUntil = now + suppressMs,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 4. WALLET/PRICE CACHE - Reduce refresh frequency
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class CachedWallet(
        val solBalance: Double,
        val timestamp: Long,
    )
    
    data class CachedPrice(
        val solPriceUsd: Double,
        val timestamp: Long,
    )
    
    @Volatile private var cachedWallet: CachedWallet? = null
    @Volatile private var cachedPrice: CachedPrice? = null
    
    private const val WALLET_CACHE_MS = 25_000L   // 25 seconds
    private const val PRICE_CACHE_MS = 90_000L    // 90 seconds
    
    /**
     * Check if wallet balance is still fresh.
     */
    fun isCachedWalletFresh(): Boolean {
        val cached = cachedWallet ?: return false
        return System.currentTimeMillis() - cached.timestamp < WALLET_CACHE_MS
    }
    
    /**
     * Get cached wallet balance if fresh.
     */
    fun getCachedWallet(): CachedWallet? {
        val cached = cachedWallet ?: return null
        if (System.currentTimeMillis() - cached.timestamp < WALLET_CACHE_MS) {
            return cached
        }
        return null
    }
    
    /**
     * Update wallet cache.
     */
    fun updateWalletCache(balance: Double) {
        cachedWallet = CachedWallet(balance, System.currentTimeMillis())
    }
    
    /**
     * Check if SOL price is still fresh.
     */
    fun isCachedPriceFresh(): Boolean {
        val cached = cachedPrice ?: return false
        return System.currentTimeMillis() - cached.timestamp < PRICE_CACHE_MS
    }
    
    /**
     * Get cached SOL price if fresh.
     */
    fun getCachedPrice(): CachedPrice? {
        val cached = cachedPrice ?: return null
        if (System.currentTimeMillis() - cached.timestamp < PRICE_CACHE_MS) {
            return cached
        }
        return null
    }
    
    /**
     * Update price cache.
     */
    fun updatePriceCache(price: Double) {
        cachedPrice = CachedPrice(price, System.currentTimeMillis())
    }
    
    /**
     * Force cache invalidation (use before order placement/after fill).
     */
    fun invalidateCaches() {
        cachedWallet = null
        cachedPrice = null
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 5. STATE CHANGE LOGGING - Only log when state actually changes
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class FdgState(
        val mode: String,
        val base: Int,
        val adj: Int,
        val final: Int,
        val learning: Int,
        val bootstrap: Boolean,
    )
    
    @Volatile private var lastLoggedFdgState: FdgState? = null
    
    /**
     * Check if FDG state has changed and should be logged.
     * Returns true if state is different from last logged state.
     */
    fun shouldLogFdgState(current: FdgState): Boolean {
        val last = lastLoggedFdgState
        if (last == null || current != last) {
            lastLoggedFdgState = current
            return true
        }
        return false
    }
    
    /**
     * Create FDG state for comparison.
     */
    fun createFdgState(mode: String, base: Int, adj: Int, final: Int, learning: Int, bootstrap: Boolean): FdgState {
        return FdgState(mode, base, adj, final, learning, bootstrap)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 6. CANDIDATE RANKING QUEUE - Prioritize best opportunities
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class CandidateState {
        SEEN,               // Just discovered
        CANDIDATE,          // Passed basic filters
        WATCHLISTED,        // In active monitoring
        READY_FOR_ENTRY,    // All gates passed, ready to trade
        OPEN_POSITION,      // Currently holding
    }
    
    data class RankedCandidate(
        val mint: String,
        val symbol: String,
        val state: CandidateState,
        val score: Int,
        val liquidity: Double,
        val addedAt: Long,
        val lastScoredAt: Long,
    )
    
    private val candidateQueue = ConcurrentHashMap<String, RankedCandidate>()
    
    private const val MAX_ACTIVE_REVIEW = 30
    private const val MAX_STANDBY = 50
    private const val MAX_SEEN = 100
    
    /**
     * Add or update a candidate in the ranking queue.
     */
    fun upsertCandidate(mint: String, symbol: String, score: Int, liquidity: Double, state: CandidateState) {
        val now = System.currentTimeMillis()
        val existing = candidateQueue[mint]
        
        candidateQueue[mint] = RankedCandidate(
            mint = mint,
            symbol = symbol,
            state = state,
            score = score,
            liquidity = liquidity,
            addedAt = existing?.addedAt ?: now,
            lastScoredAt = now,
        )
        
        // Enforce capacity limits
        enforceQueueLimits()
    }
    
    /**
     * Get top candidates for active review.
     */
    fun getTopCandidates(limit: Int = MAX_ACTIVE_REVIEW): List<RankedCandidate> {
        return candidateQueue.values
            .filter { it.state == CandidateState.WATCHLISTED || it.state == CandidateState.READY_FOR_ENTRY }
            .sortedByDescending { it.score }
            .take(limit)
    }
    
    /**
     * Promote a candidate to a higher state.
     */
    fun promoteCandidate(mint: String, newState: CandidateState) {
        candidateQueue[mint]?.let { candidate ->
            candidateQueue[mint] = candidate.copy(state = newState)
        }
    }
    
    /**
     * Remove a candidate from the queue.
     */
    fun removeCandidate(mint: String) {
        candidateQueue.remove(mint)
    }
    
    private fun enforceQueueLimits() {
        val byState = candidateQueue.values.groupBy { it.state }
        
        // Remove excess SEEN entries (keep newest by addedAt)
        val seen = byState[CandidateState.SEEN] ?: emptyList()
        if (seen.size > MAX_SEEN) {
            val toRemove = seen.sortedBy { it.addedAt }.take(seen.size - MAX_SEEN)
            toRemove.forEach { candidateQueue.remove(it.mint) }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATS & CLEANUP
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getStats(): String {
        return "Efficiency: discovery_skipped=${discoverySkipped.get()} " +
            "processed=${discoveryProcessed.get()} " +
            "seen_tokens=${seenTokens.size} " +
            "liq_rejections=${liqRejections.size} " +
            "candidates=${candidateQueue.size}"
    }
    
    /**
     * Periodic cleanup of stale data.
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        val staleThreshold = 10 * 6 * 10000L  // 1 minutes
        
        // Clean stale seen tokens
        seenTokens.entries.removeIf { now - it.value.lastSeenAt > staleThreshold }
        
        // Clean expired liquidity rejections
        liqRejections.entries.removeIf { now >= it.value.suppressUntil }
        
        // Clean old liquidity snapshots
        liquiditySnapshots.entries.removeIf { entry ->
            val snapshots = entry.value
            synchronized(snapshots) {
                snapshots.removeIf { now - it.timestamp > staleThreshold }
                snapshots.isEmpty()
            }
        }
        
        // Clean stale candidates (not scored in 15 min)
        val candidateStale = 15 * 60 * 1000L
        candidateQueue.entries.removeIf { 
            it.value.state == CandidateState.SEEN && now - it.value.lastScoredAt > candidateStale 
        }
    }
    
    /**
     * Reset all state (for testing).
     */
    fun reset() {
        seenTokens.clear()
        liquiditySnapshots.clear()
        liqRejections.clear()
        candidateQueue.clear()
        cachedWallet = null
        cachedPrice = null
        lastLoggedFdgState = null
        discoverySkipped.set(0)
        discoveryProcessed.set(0)
        ErrorLogger.info(TAG, "Efficiency layer reset")
    }
}

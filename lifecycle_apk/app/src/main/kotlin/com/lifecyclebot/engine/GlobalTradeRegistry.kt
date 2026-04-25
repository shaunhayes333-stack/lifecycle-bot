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
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.0 PROBATION TIER
    // ═══════════════════════════════════════════════════════════════════════════
    // Single-source or low-confidence tokens must prove themselves before
    // being promoted to the main watchlist. This prevents registry flooding.
    // Key: mint, Value: ProbationEntry
    private val probation = ConcurrentHashMap<String, ProbationEntry>()
    
    // Counters
    private val totalTokensAdded = AtomicLong(0)
    private val totalTokensRemoved = AtomicLong(0)
    private val duplicatesBlocked = AtomicLong(0)
    private val probationPromotions = AtomicLong(0)
    private val probationRejections = AtomicLong(0)
    
    // Timing constants
    // V5.9.162 — aggressive bootstrap volume boost. User: "it was smashing
    // out hundreds of trades an hour". 20s duplicate/rejection cooldowns on
    // the same mint were capping re-discovery throughput on a hot watchlist;
    // 5s per-token processing cooldown was serialising evaluation of the
    // same mint across rapid scan ticks. All dropped hard for memetrader
    // volume. Bootstrap-only: tightens back above 40% learning via
    // effective*() helpers below.
    private const val DUPLICATE_COOLDOWN_MS = 20_000L   // mature: 20s
    private const val DUPLICATE_COOLDOWN_MS_BOOTSTRAP = 3_000L
    private const val REJECTION_COOLDOWN_MS = 20_000L   // mature: 20s
    private const val REJECTION_COOLDOWN_MS_BOOTSTRAP = 3_000L
    private const val PROCESS_COOLDOWN_MS = 5_000L      // mature: 5s
    private const val PROCESS_COOLDOWN_MS_BOOTSTRAP = 1_000L

    private fun isBootstrapPhase(): Boolean = try {
        com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress() < 0.40
    } catch (_: Exception) { false }

    private fun effectiveDuplicateCooldownMs(): Long =
        if (isBootstrapPhase()) DUPLICATE_COOLDOWN_MS_BOOTSTRAP else DUPLICATE_COOLDOWN_MS

    private fun effectiveRejectionCooldownMs(): Long =
        if (isBootstrapPhase()) REJECTION_COOLDOWN_MS_BOOTSTRAP else REJECTION_COOLDOWN_MS

    private fun effectiveProcessCooldownMs(): Long =
        if (isBootstrapPhase()) PROCESS_COOLDOWN_MS_BOOTSTRAP else PROCESS_COOLDOWN_MS
    
    // V5.0 Probation constants
    private const val PROBATION_MIN_TIME_MS = 60_000L       // 1 minute minimum probation
    private const val PROBATION_MAX_TIME_MS = 300_000L      // 5 minutes max probation before auto-reject
    private const val PROBATION_CONF_THRESHOLD = 50         // Confidence below this = probation
    private const val PROBATION_CONF_THRESHOLD_PAPER = 18   // V5.9.263: balanced (was 30 at V5.9.260)
    private const val PROBATION_MULTI_SOURCE_EXEMPT = true  // Multi-source tokens skip probation
    
    // V5.2: Paper mode flag for more aggressive token acceptance
    @Volatile
    var isPaperMode: Boolean = false
    
    // Maximum watchlist size to prevent memory issues
    // V5.2: Increased for paper mode learning - need more exposure
    private const val MAX_WATCHLIST_SIZE = 300  // V5.9.182: doubled for paper bootstrap coverage
    private const val MAX_PROBATION_SIZE = 500
    
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
    
    /**
     * V5.0 PROBATION ENTRY
     * Tokens in probation need additional confirmation before being promoted.
     */
    data class ProbationEntry(
        val mint: String,
        val symbol: String,
        val addedAt: Long,
        val addedBy: String,
        val source: String,
        val initialMcap: Double,
        val initialLiquidity: Double,
        val initialConfidence: Int,
        val isEstimatedLiquidity: Boolean,  // True if liquidity was estimated, not confirmed
        val isSingleSource: Boolean,        // True if only 1 scanner found it
        var additionalScanners: MutableSet<String> = mutableSetOf(),  // Other scanners that found it later
        var rcScore: Int = -1,              // Rugcheck score (-1 = not checked)
        var priceAtAdd: Double = 0.0,       // Track price to check if it holds
        var currentPrice: Double = 0.0,
        var promotionReason: String? = null,
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    // V4.0 CRITICAL: Flag to prevent re-initialization during runtime
    @Volatile
    private var initialized = false
    
    /**
     * Initialize from ConfigStore watchlist.
     * Called once at bot startup. BLOCKED if already initialized.
     */
    fun init(initialWatchlist: List<String>, defaultSource: String = "CONFIG") {
        // V4.0 CRITICAL: Guard against re-initialization
        if (initialized && watchlist.isNotEmpty()) {
            ErrorLogger.warn(TAG, "⚠️ init() called again with ${initialWatchlist.size} tokens - BLOCKED (already has ${watchlist.size} tokens)")
            return
        }
        
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
        
        initialized = true
        ErrorLogger.info(TAG, "✅ Initialized with ${watchlist.size} tokens from $defaultSource (ONE-TIME)")
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
            if (elapsed < effectiveRejectionCooldownMs()) {
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
            if (elapsed < effectiveDuplicateCooldownMs()) {
                duplicatesBlocked.incrementAndGet()
                return AddResult(false, "COOLDOWN: processed ${elapsed/1000}s ago")
            }
        }
        
        // V5.2: No max size check - let watchlist grow as needed
        // Learning requires seeing many tokens
        // Stale tokens are pruned automatically by age/loss tracking
        
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
        val probation: Boolean = false,  // V5.0: True if token went to probation instead
    )
    
    /**
     * V5.0: Add a token with probation awareness.
     * Low-confidence or single-source tokens go to probation first.
     * Multi-source tokens or USER_ADDED bypass probation.
     */
    fun addWithProbation(
        mint: String,
        symbol: String,
        addedBy: String,
        source: String = addedBy,
        initialMcap: Double = 0.0,
        liquidityUsd: Double = 0.0,
        confidence: Int = 50,
        isMultiSource: Boolean = false,
        isEstimatedLiquidity: Boolean = false,
        price: Double = 0.0,
    ): AddResult {
        // Validate mint
        if (mint.isBlank() || mint.length < 30) {
            PipelineTracer.registryRejected(symbol, mint, "INVALID_MINT")
            return AddResult(false, "INVALID_MINT")
        }
        
        val now = System.currentTimeMillis()
        
        // Check if already in watchlist
        if (watchlist.containsKey(mint)) {
            duplicatesBlocked.incrementAndGet()
            PipelineTracer.registryDuplicate(symbol, mint, "WATCHLIST")
            return AddResult(false, "DUPLICATE: already in watchlist")
        }
        
        // Check if already in probation - update with additional scanner
        val existingProbation = probation[mint]
        if (existingProbation != null) {
            existingProbation.additionalScanners.add(addedBy)
            PipelineTracer.registryDuplicate(symbol, mint, "PROBATION")
            // Check if this promotes it
            if (existingProbation.additionalScanners.size >= 1) {
                return promoteFromProbation(mint, "MULTI_SCANNER_CONFIRM")
            }
            return AddResult(false, "ALREADY_IN_PROBATION", probation = true)
        }
        
        // Check if recently rejected
        val rejection = rejectedTokens[mint]
        if (rejection != null) {
            val elapsed = now - rejection.rejectedAt
            if (elapsed < effectiveRejectionCooldownMs()) {
                PipelineTracer.registryRejected(symbol, mint, "COOLDOWN: ${rejection.reason}")
                return AddResult(false, "REJECTED: ${rejection.reason}")
            } else {
                rejectedTokens.remove(mint)
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // PROBATION ROUTING DECISION
        // V5.2: Paper mode is MUCH more lenient for maximum learning exposure
        // V5.9.46: Proven-edge live runs inherit paper leniency — same logic
        // as the BotService promotion gate. Prevents "scanner looks dead in
        // live" symptom after the bot has demonstrated paper performance.
        // ═══════════════════════════════════════════════════════════════════
        val provenEdge = try {
            com.lifecyclebot.engine.TradeHistoryStore.getProvenEdgeCached().hasProvenEdge
        } catch (_: Exception) { false }
        val lenientMode = isPaperMode || provenEdge
        val confThreshold = if (lenientMode) PROBATION_CONF_THRESHOLD_PAPER else PROBATION_CONF_THRESHOLD

        val needsProbation = when {
            // USER_ADDED always bypasses probation
            addedBy == "USER_ADDED" || addedBy == "USER" || addedBy == "CONFIG" -> false
            // Paper or proven-edge live mode - bypass probation for fast learning
            lenientMode && confidence >= confThreshold -> false
            // Multi-source tokens with sufficient confidence bypass
            isMultiSource && confidence >= confThreshold -> false
            // Low confidence = probation
            confidence < confThreshold -> true
            // Estimated liquidity = probation (strict-live only)
            isEstimatedLiquidity && !lenientMode -> true
            // Single source with borderline confidence = probation (strict-live only)
            !isMultiSource && confidence < 60 && !lenientMode -> true
            // Otherwise, allow
            else -> false
        }
        
        if (needsProbation) {
            return addToProbation(
                mint = mint,
                symbol = symbol,
                addedBy = addedBy,
                source = source,
                initialMcap = initialMcap,
                liquidityUsd = liquidityUsd,
                confidence = confidence,
                isEstimatedLiquidity = isEstimatedLiquidity,
                isSingleSource = !isMultiSource,
                price = price,
            )
        }
        
        // Direct add to watchlist
        return addToWatchlist(mint, symbol, addedBy, source, initialMcap)
    }
    
    /**
     * V5.0: Add token to probation tier.
     */
    private fun addToProbation(
        mint: String,
        symbol: String,
        addedBy: String,
        source: String,
        initialMcap: Double,
        liquidityUsd: Double,
        confidence: Int,
        isEstimatedLiquidity: Boolean,
        isSingleSource: Boolean,
        price: Double,
    ): AddResult {
        // Check probation size
        if (probation.size >= MAX_PROBATION_SIZE) {
            // Prune oldest probation entry
            val oldest = probation.values.minByOrNull { it.addedAt }
            if (oldest != null) {
                probation.remove(oldest.mint)
                probationRejections.incrementAndGet()
                ErrorLogger.debug(TAG, "🗑️ Probation pruned: ${oldest.symbol} (full)")
            }
        }
        
        val now = System.currentTimeMillis()
        probation[mint] = ProbationEntry(
            mint = mint,
            symbol = symbol,
            addedAt = now,
            addedBy = addedBy,
            source = source,
            initialMcap = initialMcap,
            initialLiquidity = liquidityUsd,
            initialConfidence = confidence,
            isEstimatedLiquidity = isEstimatedLiquidity,
            isSingleSource = isSingleSource,
            priceAtAdd = price,
            currentPrice = price,
        )
        
        val reason = when {
            isSingleSource -> "SINGLE_SOURCE"
            isEstimatedLiquidity -> "ESTIMATED_LIQ"
            confidence < PROBATION_CONF_THRESHOLD -> "LOW_CONF($confidence)"
            else -> "REVIEW_NEEDED"
        }
        
        ErrorLogger.info(TAG, "⏳ PROBATION: $symbol | $reason | conf=$confidence | liq=$${liquidityUsd.toInt()}")
        return AddResult(false, "PROBATION: $reason", probation = true)
    }
    
    /**
     * V5.0: Promote a token from probation to watchlist.
     */
    fun promoteFromProbation(mint: String, reason: String): AddResult {
        val entry = probation.remove(mint) ?: return AddResult(false, "NOT_IN_PROBATION")
        
        // Add to watchlist
        val now = System.currentTimeMillis()
        watchlist[mint] = WatchlistEntry(
            mint = mint,
            symbol = entry.symbol,
            addedAt = now,
            addedBy = "${entry.addedBy}+PROBATION",
            source = entry.source,
            initialMcap = entry.initialMcap,
        )
        
        totalTokensAdded.incrementAndGet()
        probationPromotions.incrementAndGet()
        
        ErrorLogger.info(TAG, "✅ PROMOTED: ${entry.symbol} | reason=$reason | was in probation ${(now - entry.addedAt)/1000}s")
        return AddResult(true, "PROMOTED: $reason")
    }
    
    /**
     * V5.0: Reject a token from probation.
     */
    fun rejectFromProbation(mint: String, reason: String) {
        val entry = probation.remove(mint) ?: return
        
        registerRejection(mint, entry.symbol, "PROBATION_FAILED: $reason", "PROBATION")
        probationRejections.incrementAndGet()
        
        ErrorLogger.info(TAG, "❌ PROBATION REJECTED: ${entry.symbol} | $reason")
    }
    
    /**
     * V5.0: Update probation entry with new scanner confirmation.
     */
    fun updateProbationScanner(mint: String, scanner: String): Boolean {
        val entry = probation[mint] ?: return false
        entry.additionalScanners.add(scanner)
        
        // Auto-promote if multi-scanner confirmed
        if (entry.additionalScanners.size >= 1 && entry.isSingleSource) {
            promoteFromProbation(mint, "SCANNER_CONFIRM: ${entry.additionalScanners.joinToString(",")}")
            return true
        }
        return false
    }
    
    /**
     * V5.0: Update probation entry with RC score.
     */
    fun updateProbationRC(mint: String, rcScore: Int): Boolean {
        val entry = probation[mint] ?: return false
        entry.rcScore = rcScore
        
        // V5.2: Good RC score can promote (RC >= 2 is great)
        if (rcScore >= 2) {
            promoteFromProbation(mint, "GOOD_RC:$rcScore")
            return true
        }
        // V5.2: Bad RC score = instant reject (RC <= 1 is dangerous)
        if (rcScore <= 1) {
            rejectFromProbation(mint, "BAD_RC:$rcScore")
            return true
        }
        return false
    }
    
    /**
     * V5.0: Update probation entry with price.
     */
    fun updateProbationPrice(mint: String, price: Double) {
        val entry = probation[mint] ?: return
        entry.currentPrice = price
    }
    
    /**
     * V5.0: Process probation tier - check for promotions/rejections.
     * Call this periodically from bot loop.
     */
    fun processProbation(): List<ProbationResult> {
        val results = mutableListOf<ProbationResult>()
        val now = System.currentTimeMillis()
        
        for ((mint, entry) in probation) {
            val elapsed = now - entry.addedAt
            
            // Check timeout - reject if too long in probation
            if (elapsed >= PROBATION_MAX_TIME_MS) {
                rejectFromProbation(mint, "TIMEOUT")
                results.add(ProbationResult(mint, entry.symbol, "REJECTED", "TIMEOUT"))
                continue
            }
            
            // Check minimum time
            if (elapsed < PROBATION_MIN_TIME_MS) continue
            
            // Check price action - promote if price held or increased
            if (entry.priceAtAdd > 0 && entry.currentPrice > 0) {
                val priceChange = (entry.currentPrice - entry.priceAtAdd) / entry.priceAtAdd * 100
                if (priceChange >= 5.0) {
                    // Price up 5%+ = promote
                    promoteFromProbation(mint, "PRICE_UP:${priceChange.toInt()}%")
                    results.add(ProbationResult(mint, entry.symbol, "PROMOTED", "PRICE_UP"))
                    continue
                }
                if (priceChange <= -20.0) {
                    // Price down 20%+ = reject
                    rejectFromProbation(mint, "PRICE_DUMP:${priceChange.toInt()}%")
                    results.add(ProbationResult(mint, entry.symbol, "REJECTED", "PRICE_DUMP"))
                    continue
                }
            }
            
            // Check if multi-scanner confirmed
            if (entry.additionalScanners.isNotEmpty()) {
                promoteFromProbation(mint, "MULTI_CONFIRM")
                results.add(ProbationResult(mint, entry.symbol, "PROMOTED", "MULTI_CONFIRM"))
                continue
            }
            
            // V5.2: Check if RC confirmed (RC >= 2 is great for promotion)
            if (entry.rcScore >= 2) {
                promoteFromProbation(mint, "RC_OK:${entry.rcScore}")
                results.add(ProbationResult(mint, entry.symbol, "PROMOTED", "RC_OK"))
                continue
            }
        }
        
        return results
    }
    
    data class ProbationResult(
        val mint: String,
        val symbol: String,
        val action: String,  // "PROMOTED" or "REJECTED"
        val reason: String,
    )
    
    /**
     * V5.0: Get probation entries.
     */
    fun getProbationEntries(): List<ProbationEntry> = probation.values.toList()
    
    /**
     * V5.0: Get probation entry for a specific token.
     */
    fun getProbationEntry(mint: String): ProbationEntry? = probation[mint]
    
    /**
     * V5.0: Check if token is in probation.
     */
    fun isInProbation(mint: String): Boolean = probation.containsKey(mint)
    
    /**
     * V5.0: Get probation size.
     */
    fun probationSize(): Int = probation.size
    
    /**
     * V5.0: Get probation stats.
     */
    fun getProbationStats(): String {
        return "PROBATION: ${probation.size}/$MAX_PROBATION_SIZE | promoted=${probationPromotions.get()} | rejected=${probationRejections.get()}"
    }
    
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
     * V5.0: Get watchlist entry for a token (for UI display).
     * Returns null if token is not in watchlist.
     */
    fun getEntry(mint: String): WatchlistEntry? = watchlist[mint]
    
    /**
     * Check if we can process a token (not processed too recently).
     */
    fun canProcess(mint: String): Boolean {
        val lastProcessed = recentlyProcessed[mint] ?: return true
        return System.currentTimeMillis() - lastProcessed >= effectiveProcessCooldownMs()
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

        // V5.9.162: cleanup uses effective (bootstrap-aware) cooldowns so
        // rejection/processed entries flush fast during bootstrap and keep
        // their 20s persistence after maturity.
        val rejCd = effectiveRejectionCooldownMs()
        val dupCd = effectiveDuplicateCooldownMs()

        // Clean old rejections
        rejectedTokens.entries.removeIf { now - it.value.rejectedAt > rejCd }

        // Clean old processed entries
        recentlyProcessed.entries.removeIf { now - it.value > dupCd }
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
     * V4.0: Also resets initialized flag to allow reinit on next session.
     * V5.0: Also clears probation tier.
     */
    fun reset() {
        watchlist.clear()
        recentlyProcessed.clear()
        rejectedTokens.clear()
        activePositions.clear()
        probation.clear()
        totalTokensAdded.set(0)
        totalTokensRemoved.set(0)
        duplicatesBlocked.set(0)
        probationPromotions.set(0)
        probationRejections.set(0)
        initialized = false  // Allow reinit
        ErrorLogger.info(TAG, "🔄 Registry reset - can reinit on next session")
    }
}

package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * V4.5 TRADE AUTHORIZER - SINGLE SOURCE OF EXECUTION TRUTH
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * PROBLEM SOLVED:
 * Post-execution gating drift - tokens were being bought BEFORE promotion gate
 * resolved. This caused:
 *   - inokumi bought, THEN blocked by promotion gate (wrong order)
 *   - Same tokens appearing in Open Positions AND Treasury Scalps
 *   - Shadow-only tokens rendered as real positions
 *   - Multiple execution lanes operating on same token
 * 
 * SOLUTION:
 * Single authoritative checkpoint that ALL execution paths MUST pass through
 * BEFORE any PAPER_BUY, LIVE_BUY, ledger insert, or UI position creation.
 * 
 * REQUIRED ORDER:
 *   DISCOVERED -> SCORED -> PRE_FILTER -> TRADE_AUTHORIZER -> SIZE -> EXECUTE
 * 
 * NOT:
 *   DISCOVERED -> SCORED -> SIZE -> EXECUTE -> (promotion gate too late)
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object TradeAuthorizer {
    
    private const val TAG = "TradeAuth"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TOKEN STATE - Strict enum, no conflicting states allowed
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class TokenState {
        DISCOVERED,         // Just found by scanner
        WATCHLISTED,        // Added to watchlist for monitoring
        SCORED,             // V3 scoring complete
        BLOCKED,            // Failed pre-trade checks (hard block)
        SHADOW_TRACKING,    // Track for learning only, NO execution
        PAPER_OPEN,         // Paper position open
        LIVE_OPEN,          // Live position open
        CLOSED,             // Position closed
        BANNED,             // Permanently banned (rug, scam, etc.)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // EXECUTION VERDICT - The ONLY valid outputs from authorization
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class ExecutionVerdict {
        REJECT,             // Do not execute, do not track
        SHADOW_ONLY,        // Track for learning, NO execution, NO position record
        PAPER_EXECUTE,      // Execute paper trade
        LIVE_EXECUTE,       // Execute live trade
    }
    
    data class AuthorizationResult(
        val verdict: ExecutionVerdict,
        val reason: String,
        val blockLevel: BlockLevel? = null,
        val canRetry: Boolean = false,
    ) {
        fun isExecutable(): Boolean = verdict == ExecutionVerdict.PAPER_EXECUTE || 
                                       verdict == ExecutionVerdict.LIVE_EXECUTE
        
        fun isShadowOnly(): Boolean = verdict == ExecutionVerdict.SHADOW_ONLY
    }
    
    enum class BlockLevel {
        SOFT,       // Can retry later
        HARD,       // Blocked for this session
        PERMANENT,  // Banned forever
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TOKEN BOOK LOCKS - V5.1: Allow same token in MULTIPLE books
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class TokenLock(
        val mint: String,
        val state: TokenState,
        val book: ExecutionBook,
        val lockedAt: Long,
        val lastDecisionEpoch: Long,
    )
    
    enum class ExecutionBook {
        CORE,           // Main V3 execution
        TREASURY,       // Treasury scalping
        SHITCOIN,       // ShitCoin layer
        BLUECHIP,       // BlueChip layer
        MOONSHOT,       // Moonshot 10x-1000x hunter layer ($100K-$5M)
        SHADOW,         // Shadow tracking only (NOT a real position)
    }
    
    // V5.1: Active locks per token PER BOOK (allows multi-mode trading)
    // Key: "mint:book" (e.g., "abc123:TREASURY")
    private val tokenLocks = ConcurrentHashMap<String, TokenLock>()
    
    // Helper to create lock key
    private fun lockKey(mint: String, book: ExecutionBook): String = "$mint:${book.name}"
    
    // Decision epoch to prevent stale re-authorization
    private var currentEpoch = 0L
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN AUTHORIZATION - MUST BE CALLED BEFORE ANY EXECUTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * THE SINGLE AUTHORIZATION CHECKPOINT.
     * 
     * Call this BEFORE:
     *   - Any PAPER_BUY
     *   - Any LIVE_BUY
     *   - Any ledger insert
     *   - Any UI position creation
     * 
     * This function checks ALL gates in the correct order:
     *   1. Ban check
     *   2. Book lock check (is token already in another book?)
     *   3. Promotion gate check
     *   4. Final verdict
     */
    fun authorize(
        mint: String,
        symbol: String,
        score: Int,
        confidence: Double,
        quality: String,
        isPaperMode: Boolean,
        requestedBook: ExecutionBook,
        rugcheckScore: Int = 100,
        liquidity: Double = 0.0,
        isBanned: Boolean = false,
    ): AuthorizationResult {
        
        val now = System.currentTimeMillis()
        
        // ─────────────────────────────────────────────────────────────────────
        // GATE 1: PERMANENT BAN CHECK
        // ─────────────────────────────────────────────────────────────────────
        if (isBanned || BannedTokens.isBanned(mint)) {
            ErrorLogger.info(TAG, "❌ REJECT $symbol: BANNED")
            return AuthorizationResult(
                verdict = ExecutionVerdict.REJECT,
                reason = "BANNED",
                blockLevel = BlockLevel.PERMANENT,
                canRetry = false,
            )
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // GATE 2: RUGCHECK HARD BLOCK
        // V5.2 FIX: Shadow mode should track EVERYTHING above RC 2 for learning!
        // Only block execution (paper/live) below RC 6
        // ─────────────────────────────────────────────────────────────────────
        if (rugcheckScore <= 2) {
            // RC <= 2 is catastrophic - don't even shadow track (lowered from 3)
            ErrorLogger.info(TAG, "❌ REJECT $symbol: RC_SCORE_$rugcheckScore <= 2 (catastrophic)")
            return AuthorizationResult(
                verdict = ExecutionVerdict.REJECT,
                reason = "RUGCHECK_CATASTROPHIC_$rugcheckScore",
                blockLevel = BlockLevel.HARD,
                canRetry = false,
            )
        }
        
        // V5.2: RC 3-5 = SHADOW_ONLY (dangerous, but track for learning)
        if (rugcheckScore in 3..5) {
            ErrorLogger.info(TAG, "👁️ SHADOW_ONLY $symbol: RC_SCORE_$rugcheckScore (dangerous, track only)")
            
            // Lock as shadow tracking
            tokenLocks[lockKey(mint, ExecutionBook.SHADOW)] = TokenLock(
                mint = mint,
                state = TokenState.SHADOW_TRACKING,
                book = ExecutionBook.SHADOW,
                lockedAt = now,
                lastDecisionEpoch = currentEpoch,
            )
            
            return AuthorizationResult(
                verdict = ExecutionVerdict.SHADOW_ONLY,
                reason = "RC_SHADOW_$rugcheckScore",
                blockLevel = BlockLevel.SOFT,
                canRetry = true,
            )
        }
        
        // V5.2: RC >= 6 passes rugcheck gate (continues to other gates)
        
        // ─────────────────────────────────────────────────────────────────────
        // GATE 3: LIQUIDITY FLOOR (Live mode only)
        // ─────────────────────────────────────────────────────────────────────
        if (!isPaperMode && liquidity < 3000.0 && liquidity > 0) {
            ErrorLogger.info(TAG, "❌ REJECT $symbol: LIQUIDITY_${"%.0f".format(liquidity)} < 3000")
            return AuthorizationResult(
                verdict = ExecutionVerdict.REJECT,
                reason = "LOW_LIQUIDITY",
                blockLevel = BlockLevel.SOFT,
                canRetry = true,
            )
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // GATE 4: BOOK LOCK CHECK - V5.1: ALLOW multi-mode trading
        // Different modes (V3, TREASURY, SHITCOIN, BLUECHIP) can trade the same token
        // Only block duplicate trades in the SAME book
        // ─────────────────────────────────────────────────────────────────────
        val existingLock = tokenLocks[lockKey(mint, requestedBook)]
        if (existingLock != null) {
            // V5.1: Only check if this SPECIFIC book already has a position
            
            // If in shadow, can upgrade to real execution
            if (existingLock.state == TokenState.SHADOW_TRACKING) {
                // Allow upgrade from shadow to real position
                ErrorLogger.debug(TAG, "⬆️ $symbol: Upgrading from SHADOW to $requestedBook")
            }
            
            // Reject if this book already has an open position
            if (existingLock.state == TokenState.PAPER_OPEN || existingLock.state == TokenState.LIVE_OPEN) {
                ErrorLogger.info(TAG, "❌ REJECT $symbol: ALREADY_OPEN_IN_$requestedBook")
                return AuthorizationResult(
                    verdict = ExecutionVerdict.REJECT,
                    reason = "ALREADY_OPEN",
                    blockLevel = BlockLevel.SOFT,
                    canRetry = false,
                )
            }
        }
        
        // V5.1: Log if other books have this token (for visibility)
        val otherBooks = ExecutionBook.values()
            .filter { it != requestedBook && it != ExecutionBook.SHADOW }
            .filter { tokenLocks[lockKey(mint, it)]?.state in listOf(TokenState.PAPER_OPEN, TokenState.LIVE_OPEN) }
        if (otherBooks.isNotEmpty()) {
            ErrorLogger.debug(TAG, "✅ $symbol: Multi-mode trade - also in: ${otherBooks.joinToString()}")
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // GATE 5: PROMOTION GATE - Grade and confidence check
        // ─────────────────────────────────────────────────────────────────────
        val promotionResult = checkPromotionGate(symbol, quality, confidence, isPaperMode)
        
        if (!promotionResult.allow) {
            // SHADOW ONLY - track but do NOT execute
            ErrorLogger.info(TAG, "👁️ SHADOW_ONLY $symbol: ${promotionResult.reason}")
            
            // Lock as shadow tracking (use SHADOW key, not book-specific)
            tokenLocks[lockKey(mint, ExecutionBook.SHADOW)] = TokenLock(
                mint = mint,
                state = TokenState.SHADOW_TRACKING,
                book = ExecutionBook.SHADOW,
                lockedAt = now,
                lastDecisionEpoch = currentEpoch,
            )
            
            return AuthorizationResult(
                verdict = ExecutionVerdict.SHADOW_ONLY,
                reason = promotionResult.reason,
                blockLevel = BlockLevel.SOFT,
                canRetry = true,
            )
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // ALL GATES PASSED - AUTHORIZE EXECUTION
        // ─────────────────────────────────────────────────────────────────────
        val verdict = if (isPaperMode) ExecutionVerdict.PAPER_EXECUTE else ExecutionVerdict.LIVE_EXECUTE
        val newState = if (isPaperMode) TokenState.PAPER_OPEN else TokenState.LIVE_OPEN
        
        // V5.1: Lock the token to this SPECIFIC book (allows multi-mode)
        tokenLocks[lockKey(mint, requestedBook)] = TokenLock(
            mint = mint,
            state = newState,
            book = requestedBook,
            lockedAt = now,
            lastDecisionEpoch = currentEpoch,
        )
        
        ErrorLogger.info(TAG, "✅ AUTHORIZED $symbol: $verdict in $requestedBook | score=$score conf=${confidence.toInt()}% quality=$quality")
        
        return AuthorizationResult(
            verdict = verdict,
            reason = "AUTHORIZED",
            blockLevel = null,
            canRetry = false,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PROMOTION GATE - Moved here to ensure it runs BEFORE execution
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class PromotionResult(
        val allow: Boolean,
        val reason: String,
    )
    
    private fun checkPromotionGate(
        symbol: String,
        quality: String,
        confidence: Double,
        isPaperMode: Boolean,
    ): PromotionResult {
        
        // Paper mode: ULTRA LENIENT for bootstrap learning
        // The bot MUST trade to learn. No trades = no learning = permanent deadlock
        if (isPaperMode) {
            // V5.0 FIX: Only block absolute garbage (F grade with zero confidence)
            // Everything else should execute to build learning data
            if (quality == "F" && confidence < 5.0) {
                return PromotionResult(false, "F_grade_zero_conf_shadow_only")
            }
            // ALL other paper trades pass - we need data!
            return PromotionResult(true, "PAPER_BOOTSTRAP_PASS")
        }
        
        // Live mode: Stricter requirements
        // A/A+ grades: Always pass
        if (quality in listOf("A+", "A")) {
            return PromotionResult(true, "LIVE_PASS_${quality}")
        }
        
        // B grade: Needs confidence >= 50%
        if (quality == "B") {
            return if (confidence >= 50.0) {
                PromotionResult(true, "LIVE_PASS_B_conf_${confidence.toInt()}")
            } else {
                PromotionResult(false, "B_grade_conf_${confidence.toInt()}_below_50")
            }
        }
        
        // C grade: Needs confidence >= 65%
        if (quality == "C") {
            return if (confidence >= 65.0) {
                PromotionResult(true, "LIVE_PASS_C_conf_${confidence.toInt()}")
            } else {
                PromotionResult(false, "C_grade_conf_${confidence.toInt()}_below_65")
            }
        }
        
        // D/F grades: Never execute live
        return PromotionResult(false, "${quality}_grade_no_live_execution")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION LIFECYCLE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * V5.1: Releases the book lock for a specific book.
     */
    fun releasePosition(mint: String, reason: String = "CLOSED", book: ExecutionBook? = null) {
        if (book != null) {
            // Release specific book
            val lock = tokenLocks.remove(lockKey(mint, book))
            if (lock != null) {
                ErrorLogger.debug(TAG, "🔓 Released $mint from ${lock.book}: $reason")
            }
        } else {
            // Release ALL books for this mint (legacy compatibility)
            ExecutionBook.values().forEach { b ->
                val lock = tokenLocks.remove(lockKey(mint, b))
                if (lock != null) {
                    ErrorLogger.debug(TAG, "🔓 Released $mint from ${lock.book}: $reason")
                }
            }
        }
    }
    
    /**
     * V5.1: Get current state of a token in a specific book.
     */
    fun getTokenState(mint: String, book: ExecutionBook? = null): TokenState? {
        if (book != null) {
            return tokenLocks[lockKey(mint, book)]?.state
        }
        // Return first found state (legacy compatibility)
        return ExecutionBook.values().firstNotNullOfOrNull { tokenLocks[lockKey(mint, it)]?.state }
    }
    
    /**
     * V5.1: Get all books a token is locked to.
     */
    fun getTokenBooks(mint: String): List<ExecutionBook> {
        return ExecutionBook.values().filter { tokenLocks[lockKey(mint, it)] != null }
    }
    
    /**
     * V5.1: Check if a token is in shadow tracking (NOT a real position).
     */
    fun isShadowOnly(mint: String): Boolean {
        return tokenLocks[lockKey(mint, ExecutionBook.SHADOW)]?.state == TokenState.SHADOW_TRACKING
    }
    
    /**
     * V5.1: Check if a token has a real open position in ANY book.
     */
    fun hasOpenPosition(mint: String): Boolean {
        return ExecutionBook.values().any { b ->
            val state = tokenLocks[lockKey(mint, b)]?.state
            state == TokenState.PAPER_OPEN || state == TokenState.LIVE_OPEN
        }
    }
    
    /**
     * V5.1: Check if a token has a position in a specific book.
     */
    fun hasOpenPositionInBook(mint: String, book: ExecutionBook): Boolean {
        val state = tokenLocks[lockKey(mint, book)]?.state
        return state == TokenState.PAPER_OPEN || state == TokenState.LIVE_OPEN
    }
    
    /**
     * V5.1: Get all tokens in a specific book.
     */
    fun getTokensInBook(book: ExecutionBook): List<String> {
        return tokenLocks.filter { it.key.endsWith(":${book.name}") }
            .values.map { it.mint }.distinct()
    }
    
    /**
     * V5.1: Get all real open positions (excludes shadow tracking).
     */
    fun getOpenPositions(): List<String> {
        return tokenLocks.filter { 
            it.value.state == TokenState.PAPER_OPEN || 
            it.value.state == TokenState.LIVE_OPEN 
        }.values.map { it.mint }.distinct()
    }
    
    /**
     * Get all shadow tracking tokens.
     */
    fun getShadowTracking(): List<String> {
        return tokenLocks.filter { it.value.state == TokenState.SHADOW_TRACKING }
            .values.map { it.mint }.distinct()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // EPOCH MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Advance the decision epoch.
     * Call this at the start of each main loop iteration.
     */
    fun advanceEpoch() {
        currentEpoch = System.currentTimeMillis()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATS & CLEANUP
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getStats(): String {
        val byState = tokenLocks.values.groupBy { it.state }
        val paperOpen = byState[TokenState.PAPER_OPEN]?.size ?: 0
        val liveOpen = byState[TokenState.LIVE_OPEN]?.size ?: 0
        val shadow = byState[TokenState.SHADOW_TRACKING]?.size ?: 0
        
        return "TradeAuth: paper_open=$paperOpen live_open=$liveOpen shadow=$shadow total=${tokenLocks.size}"
    }
    
    /**
     * Cleanup stale locks.
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        val staleThreshold = 30 * 60 * 1000L  // 30 minutes
        
        // Remove stale shadow tracking
        tokenLocks.entries.removeIf { 
            it.value.state == TokenState.SHADOW_TRACKING && 
            now - it.value.lockedAt > staleThreshold 
        }
    }
    
    /**
     * Reset all state (for testing or restart).
     */
    fun reset() {
        tokenLocks.clear()
        currentEpoch = 0L
        ErrorLogger.info(TAG, "TradeAuthorizer reset")
    }
}

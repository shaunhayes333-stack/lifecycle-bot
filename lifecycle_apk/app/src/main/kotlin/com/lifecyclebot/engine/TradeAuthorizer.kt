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
    // TOKEN BOOK LOCKS - Prevent same token in multiple books
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
        SHADOW,         // Shadow tracking only (NOT a real position)
    }
    
    // Active locks per token
    private val tokenLocks = ConcurrentHashMap<String, TokenLock>()
    
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
        // ─────────────────────────────────────────────────────────────────────
        if (rugcheckScore < 10) {
            ErrorLogger.info(TAG, "❌ REJECT $symbol: RC_SCORE_$rugcheckScore < 10")
            return AuthorizationResult(
                verdict = ExecutionVerdict.REJECT,
                reason = "RUGCHECK_FAIL_$rugcheckScore",
                blockLevel = BlockLevel.HARD,
                canRetry = false,
            )
        }
        
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
        // GATE 4: BOOK LOCK CHECK - Is token already in another book?
        // ─────────────────────────────────────────────────────────────────────
        val existingLock = tokenLocks[mint]
        if (existingLock != null) {
            // Token is already locked
            if (existingLock.book != requestedBook && existingLock.book != ExecutionBook.SHADOW) {
                // Already in a DIFFERENT execution book (not shadow)
                ErrorLogger.info(TAG, "❌ REJECT $symbol: ALREADY_IN_${existingLock.book}")
                return AuthorizationResult(
                    verdict = ExecutionVerdict.REJECT,
                    reason = "BOOK_CONFLICT_${existingLock.book}",
                    blockLevel = BlockLevel.SOFT,
                    canRetry = false,
                )
            }
            
            // If in shadow, can upgrade to real execution
            if (existingLock.state == TokenState.SHADOW_TRACKING && 
                requestedBook != ExecutionBook.SHADOW) {
                // Allow upgrade from shadow to real position
                ErrorLogger.debug(TAG, "⬆️ $symbol: Upgrading from SHADOW to $requestedBook")
            }
            
            // If already open in same book, reject duplicate
            if (existingLock.state == TokenState.PAPER_OPEN || 
                existingLock.state == TokenState.LIVE_OPEN) {
                ErrorLogger.info(TAG, "❌ REJECT $symbol: ALREADY_OPEN_IN_$requestedBook")
                return AuthorizationResult(
                    verdict = ExecutionVerdict.REJECT,
                    reason = "ALREADY_OPEN",
                    blockLevel = BlockLevel.SOFT,
                    canRetry = false,
                )
            }
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // GATE 5: PROMOTION GATE - Grade and confidence check
        // ─────────────────────────────────────────────────────────────────────
        val promotionResult = checkPromotionGate(symbol, quality, confidence, isPaperMode)
        
        if (!promotionResult.allow) {
            // SHADOW ONLY - track but do NOT execute
            ErrorLogger.info(TAG, "👁️ SHADOW_ONLY $symbol: ${promotionResult.reason}")
            
            // Lock as shadow tracking
            tokenLocks[mint] = TokenLock(
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
        
        // Lock the token to this book
        tokenLocks[mint] = TokenLock(
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
     * Called when a position is closed.
     * Releases the book lock so the token can be re-evaluated.
     */
    fun releasePosition(mint: String, reason: String = "CLOSED") {
        val lock = tokenLocks.remove(mint)
        if (lock != null) {
            ErrorLogger.debug(TAG, "🔓 Released $mint from ${lock.book}: $reason")
        }
    }
    
    /**
     * Get current state of a token.
     */
    fun getTokenState(mint: String): TokenState? {
        return tokenLocks[mint]?.state
    }
    
    /**
     * Get which book a token is locked to.
     */
    fun getTokenBook(mint: String): ExecutionBook? {
        return tokenLocks[mint]?.book
    }
    
    /**
     * Check if a token is in shadow tracking (NOT a real position).
     */
    fun isShadowOnly(mint: String): Boolean {
        return tokenLocks[mint]?.state == TokenState.SHADOW_TRACKING
    }
    
    /**
     * Check if a token has a real open position.
     */
    fun hasOpenPosition(mint: String): Boolean {
        val state = tokenLocks[mint]?.state
        return state == TokenState.PAPER_OPEN || state == TokenState.LIVE_OPEN
    }
    
    /**
     * Get all tokens in a specific book.
     */
    fun getTokensInBook(book: ExecutionBook): List<String> {
        return tokenLocks.filter { it.value.book == book }.keys.toList()
    }
    
    /**
     * Get all real open positions (excludes shadow tracking).
     */
    fun getOpenPositions(): List<String> {
        return tokenLocks.filter { 
            it.value.state == TokenState.PAPER_OPEN || 
            it.value.state == TokenState.LIVE_OPEN 
        }.keys.toList()
    }
    
    /**
     * Get all shadow tracking tokens.
     */
    fun getShadowTracking(): List<String> {
        return tokenLocks.filter { it.value.state == TokenState.SHADOW_TRACKING }.keys.toList()
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

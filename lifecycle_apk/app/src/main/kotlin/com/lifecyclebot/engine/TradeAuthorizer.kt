package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * V4.6 TRADE AUTHORIZER - SINGLE SOURCE OF EXECUTION TRUTH
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * PURPOSE:
 * All execution paths must pass through this gate BEFORE any paper/live buy,
 * ledger insert, or UI position creation.
 *
 * ORDER:
 *   DISCOVERED -> SCORED -> PRE_FILTER -> TRADE_AUTHORIZER -> SIZE -> EXECUTE
 *
 * NEVER:
 *   DISCOVERED -> SCORED -> SIZE -> EXECUTE -> late block
 *
 * V5.1:
 * Supports multi-book execution. Same token may exist in different books
 * (CORE, TREASURY, SHITCOIN, BLUECHIP, MOONSHOT), but not twice in the SAME book.
 *
 * V5.2:
 * Rugcheck policy:
 *   RC 0-1  = REJECT
 *   RC 2-5  = SHADOW_ONLY
 *   RC 6+   = continue through normal gates
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object TradeAuthorizer {

    private const val TAG = "TradeAuth"

    // ───────────────────────────────────────────────────────────────────────────
    // TOKEN STATE
    // ───────────────────────────────────────────────────────────────────────────

    enum class TokenState {
        DISCOVERED,
        WATCHLISTED,
        SCORED,
        BLOCKED,
        SHADOW_TRACKING,
        PAPER_OPEN,
        LIVE_OPEN,
        CLOSED,
        BANNED,
    }

    // ───────────────────────────────────────────────────────────────────────────
    // EXECUTION VERDICT
    // ───────────────────────────────────────────────────────────────────────────

    enum class ExecutionVerdict {
        REJECT,
        SHADOW_ONLY,
        PAPER_EXECUTE,
        LIVE_EXECUTE,
    }

    enum class BlockLevel {
        SOFT,
        HARD,
        PERMANENT,
    }

    data class AuthorizationResult(
        val verdict: ExecutionVerdict,
        val reason: String,
        val blockLevel: BlockLevel? = null,
        val canRetry: Boolean = false,
    ) {
        fun isExecutable(): Boolean {
            return verdict == ExecutionVerdict.PAPER_EXECUTE || verdict == ExecutionVerdict.LIVE_EXECUTE
        }

        fun isShadowOnly(): Boolean {
            return verdict == ExecutionVerdict.SHADOW_ONLY
        }
    }

    // ───────────────────────────────────────────────────────────────────────────
    // BOOKS / LOCKS
    // ───────────────────────────────────────────────────────────────────────────

    enum class ExecutionBook {
        CORE,
        TREASURY,
        SHITCOIN,
        BLUECHIP,
        MOONSHOT,
        SHADOW,
    }

    data class TokenLock(
        val mint: String,
        val state: TokenState,
        val book: ExecutionBook,
        val lockedAt: Long,
        val lastDecisionEpoch: Long,
    )

    private val tokenLocks = ConcurrentHashMap<String, TokenLock>()
    private var currentEpoch = 0L

    private fun lockKey(mint: String, book: ExecutionBook): String = "$mint:${book.name}"

    // ───────────────────────────────────────────────────────────────────────────
    // MAIN AUTHORIZATION
    // ───────────────────────────────────────────────────────────────────────────

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
        val normalizedQuality = quality.trim().uppercase()
        val safeConfidence = confidence.coerceIn(0.0, 100.0)

        // GATE 1: permanent ban
        if (isBanned || BannedTokens.isBanned(mint)) {
            ErrorLogger.info(TAG, "❌ REJECT $symbol: BANNED")
            return AuthorizationResult(
                verdict = ExecutionVerdict.REJECT,
                reason = "BANNED",
                blockLevel = BlockLevel.PERMANENT,
                canRetry = false,
            )
        }

        // GATE 2: rugcheck hard policy
        // V5.8: In paper mode, bypass hard rug block — consistent with DistFade and RugPreFilter.
        // Fresh launches often get RC_SCORE=1 before any analysis exists, blocking all learning data.
        // Paper trades with bad rug scores still train the model on outcomes (good learning signal).
        when {
            rugcheckScore <= 1 -> {
                if (isPaperMode) {
                    ErrorLogger.info(TAG, "⚠️ PAPER BYPASS: $symbol RC_SCORE_$rugcheckScore — allowing for learning")
                    // fall through to GATE 3+
                } else {
                    ErrorLogger.info(TAG, "❌ REJECT $symbol: RC_SCORE_$rugcheckScore <= 1")
                    return AuthorizationResult(
                        verdict = ExecutionVerdict.REJECT,
                        reason = "RUGCHECK_CATASTROPHIC_$rugcheckScore",
                        blockLevel = BlockLevel.HARD,
                        canRetry = false,
                    )
                }
            }

            rugcheckScore in 2..5 -> {
                if (isPaperMode) {
                    ErrorLogger.info(TAG, "⚠️ PAPER BYPASS: $symbol RC_SCORE_$rugcheckScore (2-5) — allowing for learning")
                    // fall through to GATE 3+
                } else {
                    ErrorLogger.info(TAG, "👁️ SHADOW_ONLY $symbol: RC_SCORE_$rugcheckScore")

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
            }
        }

        // GATE 3: live liquidity floor
        if (!isPaperMode && liquidity in 0.0001..1999.9999) {
            ErrorLogger.info(TAG, "❌ REJECT $symbol: LIQUIDITY_${"%.0f".format(liquidity)} < 2000")
            return AuthorizationResult(
                verdict = ExecutionVerdict.REJECT,
                reason = "LOW_LIQUIDITY",
                blockLevel = BlockLevel.SOFT,
                canRetry = true,
            )
        }

        // GATE 4: same-book lock
        val sameBookLock = tokenLocks[lockKey(mint, requestedBook)]
        if (sameBookLock != null) {
            when (sameBookLock.state) {
                TokenState.PAPER_OPEN, TokenState.LIVE_OPEN -> {
                    ErrorLogger.info(TAG, "❌ REJECT $symbol: ALREADY_OPEN_IN_${requestedBook.name}")
                    return AuthorizationResult(
                        verdict = ExecutionVerdict.REJECT,
                        reason = "ALREADY_OPEN",
                        blockLevel = BlockLevel.SOFT,
                        canRetry = false,
                    )
                }

                TokenState.SHADOW_TRACKING -> {
                    ErrorLogger.debug(TAG, "⬆️ $symbol: Upgrading SHADOW -> ${requestedBook.name}")
                }

                else -> {
                    // allow overwrite of stale/non-open states
                }
            }
        }

        val otherBooks = ExecutionBook.values()
            .filter { it != requestedBook && it != ExecutionBook.SHADOW }
            .filter { book ->
                val state = tokenLocks[lockKey(mint, book)]?.state
                state == TokenState.PAPER_OPEN || state == TokenState.LIVE_OPEN
            }

        if (otherBooks.isNotEmpty()) {
            ErrorLogger.debug(
                TAG,
                "✅ $symbol: Multi-book authorization | existing=${otherBooks.joinToString { it.name }}"
            )
        }

        // GATE 5: promotion gate
        val promotion = checkPromotionGate(
            symbol = symbol,
            quality = normalizedQuality,
            confidence = safeConfidence,
            isPaperMode = isPaperMode,
        )

        if (!promotion.allow) {
            ErrorLogger.info(TAG, "👁️ SHADOW_ONLY $symbol: ${promotion.reason}")

            tokenLocks[lockKey(mint, ExecutionBook.SHADOW)] = TokenLock(
                mint = mint,
                state = TokenState.SHADOW_TRACKING,
                book = ExecutionBook.SHADOW,
                lockedAt = now,
                lastDecisionEpoch = currentEpoch,
            )

            return AuthorizationResult(
                verdict = ExecutionVerdict.SHADOW_ONLY,
                reason = promotion.reason,
                blockLevel = BlockLevel.SOFT,
                canRetry = true,
            )
        }

        // PASS: authorize execution
        val verdict = if (isPaperMode) ExecutionVerdict.PAPER_EXECUTE else ExecutionVerdict.LIVE_EXECUTE
        val newState = if (isPaperMode) TokenState.PAPER_OPEN else TokenState.LIVE_OPEN

        tokenLocks[lockKey(mint, requestedBook)] = TokenLock(
            mint = mint,
            state = newState,
            book = requestedBook,
            lockedAt = now,
            lastDecisionEpoch = currentEpoch,
        )

        // Clear shadow lock on successful real execution
        tokenLocks.remove(lockKey(mint, ExecutionBook.SHADOW))

        ErrorLogger.info(
            TAG,
            "✅ AUTHORIZED $symbol: ${verdict.name} in ${requestedBook.name} | score=$score conf=${safeConfidence.toInt()}% quality=$normalizedQuality"
        )

        return AuthorizationResult(
            verdict = verdict,
            reason = "AUTHORIZED",
            blockLevel = null,
            canRetry = false,
        )
    }

    // ───────────────────────────────────────────────────────────────────────────
    // PROMOTION GATE
    // ───────────────────────────────────────────────────────────────────────────

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
        if (isPaperMode) {
            if (quality == "F" && confidence < 5.0) {
                return PromotionResult(
                    allow = false,
                    reason = "F_grade_zero_conf_shadow_only",
                )
            }

            return PromotionResult(
                allow = true,
                reason = "PAPER_BOOTSTRAP_PASS",
            )
        }

        return when (quality) {
            "A+", "A" -> {
                PromotionResult(true, "LIVE_PASS_$quality")
            }

            "B" -> {
                if (confidence >= 40.0) {
                    PromotionResult(true, "LIVE_PASS_B_conf_${confidence.toInt()}")
                } else {
                    PromotionResult(false, "B_grade_conf_${confidence.toInt()}_below_40")
                }
            }

            "C" -> {
                if (confidence >= 25.0) {
                    PromotionResult(true, "LIVE_PASS_C_conf_${confidence.toInt()}")
                } else {
                    PromotionResult(false, "C_grade_conf_${confidence.toInt()}_below_25")
                }
            }

            "D", "F" -> {
                PromotionResult(false, "${quality}_grade_no_live_execution")
            }

            else -> {
                PromotionResult(false, "UNKNOWN_QUALITY_${quality.ifBlank { "BLANK" }}")
            }
        }
    }

    // ───────────────────────────────────────────────────────────────────────────
    // POSITION / LOCK LIFECYCLE
    // ───────────────────────────────────────────────────────────────────────────

    fun releasePosition(
        mint: String,
        reason: String = "CLOSED",
        book: ExecutionBook? = null,
    ) {
        if (book != null) {
            val removed = tokenLocks.remove(lockKey(mint, book))
            if (removed != null) {
                ErrorLogger.debug(TAG, "🔓 Released $mint from ${removed.book.name}: $reason")
            }
            return
        }

        ExecutionBook.values().forEach { b ->
            val removed = tokenLocks.remove(lockKey(mint, b))
            if (removed != null) {
                ErrorLogger.debug(TAG, "🔓 Released $mint from ${removed.book.name}: $reason")
            }
        }
    }

    fun getTokenState(mint: String, book: ExecutionBook? = null): TokenState? {
        if (book != null) {
            return tokenLocks[lockKey(mint, book)]?.state
        }

        return ExecutionBook.values()
            .firstNotNullOfOrNull { b -> tokenLocks[lockKey(mint, b)]?.state }
    }

    fun getTokenBooks(mint: String): List<ExecutionBook> {
        return ExecutionBook.values().filter { b ->
            tokenLocks[lockKey(mint, b)] != null
        }
    }

    fun isShadowOnly(mint: String): Boolean {
        return tokenLocks[lockKey(mint, ExecutionBook.SHADOW)]?.state == TokenState.SHADOW_TRACKING
    }

    fun hasOpenPosition(mint: String): Boolean {
        return ExecutionBook.values().any { b ->
            val state = tokenLocks[lockKey(mint, b)]?.state
            state == TokenState.PAPER_OPEN || state == TokenState.LIVE_OPEN
        }
    }

    fun hasOpenPositionInBook(mint: String, book: ExecutionBook): Boolean {
        val state = tokenLocks[lockKey(mint, book)]?.state
        return state == TokenState.PAPER_OPEN || state == TokenState.LIVE_OPEN
    }

    fun getTokensInBook(book: ExecutionBook): List<String> {
        return tokenLocks
            .filter { it.key.endsWith(":${book.name}") }
            .values
            .map { it.mint }
            .distinct()
    }

    fun getOpenPositions(): List<String> {
        return tokenLocks.values
            .filter { it.state == TokenState.PAPER_OPEN || it.state == TokenState.LIVE_OPEN }
            .map { it.mint }
            .distinct()
    }

    fun getShadowTracking(): List<String> {
        return tokenLocks.values
            .filter { it.state == TokenState.SHADOW_TRACKING }
            .map { it.mint }
            .distinct()
    }

    // ───────────────────────────────────────────────────────────────────────────
    // EPOCH
    // ───────────────────────────────────────────────────────────────────────────

    fun advanceEpoch() {
        currentEpoch = System.currentTimeMillis()
    }

    // ───────────────────────────────────────────────────────────────────────────
    // MAINTENANCE / STATS
    // ───────────────────────────────────────────────────────────────────────────

    fun cleanup() {
        val now = System.currentTimeMillis()
        val staleShadowMs = 30 * 60 * 1000L

        tokenLocks.entries.removeIf { entry ->
            entry.value.state == TokenState.SHADOW_TRACKING &&
                now - entry.value.lockedAt > staleShadowMs
        }
    }

    fun getStats(): String {
        val values = tokenLocks.values
        val paperOpen = values.count { it.state == TokenState.PAPER_OPEN }
        val liveOpen = values.count { it.state == TokenState.LIVE_OPEN }
        val shadow = values.count { it.state == TokenState.SHADOW_TRACKING }

        return "TradeAuth: paper_open=$paperOpen live_open=$liveOpen shadow=$shadow total=${tokenLocks.size}"
    }

    fun reset() {
        tokenLocks.clear()
        currentEpoch = 0L
        ErrorLogger.info(TAG, "TradeAuthorizer reset")
    }
}
package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * HardRugPreFilter — Early Garbage Filtering Before Full Evaluation
 * 
 * This filter runs BEFORE full strategy and FDG evaluation to:
 *   - Kill obvious RC failure patterns early
 *   - Block missing liquidity floor
 *   - Reject garbage holder structure
 *   - Filter suspicious launch profiles
 *   - Reduce scan noise and wasted cycles
 * 
 * Goal: Reduce scan noise by killing likely RC-fails before they
 * consume strategy and FDG cycles. This improves profitability
 * indirectly by freeing bandwidth and reducing false-positive excitement.
 */
object HardRugPreFilter {
    
    private const val TAG = "RugPreFilter"
    
    data class PreFilterResult(
        val pass: Boolean,
        val reason: String?,
        val severity: FilterSeverity,
    )
    
    enum class FilterSeverity {
        PASS,           // Token passes pre-filter
        SOFT_FAIL,      // Minor issue - log but allow
        HARD_FAIL,      // Definite garbage - block completely
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // LIQUIDITY THRESHOLDS BY TOKEN AGE
    // Now uses FluidLearningAI for adaptive thresholds
    // ═══════════════════════════════════════════════════════════════════
    
    // ═══════════════════════════════════════════════════════════════════
    // MAIN PRE-FILTER
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Run pre-filter on token before full evaluation.
     * This is a fast check that should kill obvious garbage early.
     * 
     * V5.2 FIX: Paper Mode bypass - allow all tokens through for maximum learning.
     * The AI needs to see good AND bad trades to learn patterns.
     * 
     * V5.6.29 FIX: Grace period for new tokens - allow tokens with no history yet
     * to pass through until their first API poll completes. Prevents premature
     * ZERO_LIQUIDITY blocks before real data is fetched.
     */
    fun filter(ts: TokenState, isPaperMode: Boolean = false): PreFilterResult {
        // V5.6.29d: GRACE PERIOD FOR NEW TOKENS
        // Tokens just added to watchlist may not have liquidity data yet because:
        // 1. Scanner didn't populate it (async race condition - now fixed to be sync)
        // 2. First API poll hasn't returned yet
        // 3. Token was added before the fix was deployed
        // Give tokens time to get their liquidity data from the API poll.
        val tokenAgeMs = System.currentTimeMillis() - ts.addedToWatchlistAt
        val hasNoLiquidity = ts.lastLiquidityUsd <= 0
        val isVeryNew = tokenAgeMs < 60_000  // < 60 seconds since watchlist add
        
        // Grace period: no liquidity + added recently = let API poll have time to fetch data
        if (hasNoLiquidity && isVeryNew) {
            ErrorLogger.debug(TAG, "⏳ GRACE PERIOD: ${ts.symbol} - no liquidity yet (age=${tokenAgeMs/1000}s, hist=${ts.history.size})")
            return PreFilterResult(
                pass = true,
                reason = "GRACE_PERIOD_AWAITING_LIQUIDITY",
                severity = FilterSeverity.PASS,
            )
        }
        
        // V5.2 FIX: PAPER MODE BYPASS - Skip pre-filter to maximize learning
        // V5.9.47: proven-edge live runs ALSO bypass — they've earned it.
        val lenient = ModeLeniency.useLenientGates(isPaperMode)
        if (lenient) {
            // Even in lenient mode, block tokens with literally zero liquidity (can't trade)
            // But only if they've had enough time to get polled (grace period above handles new tokens)
            if (ts.lastLiquidityUsd <= 0) {
                return PreFilterResult(
                    pass = false,
                    reason = "ZERO_LIQUIDITY (${ModeLeniency.label(isPaperMode).lowercase()})",
                    severity = FilterSeverity.HARD_FAIL,
                )
            }
            // Allow everything else through for learning
            ErrorLogger.debug(TAG, "✅ LENIENT BYPASS (${ModeLeniency.label(isPaperMode)}): ${ts.symbol} pre-filter skipped for learning")
            return PreFilterResult(
                pass = true,
                reason = "LENIENT_MODE_BYPASS",
                severity = FilterSeverity.PASS,
            )
        }
        
        val hist = ts.history.toList()
        val now = System.currentTimeMillis()
        
        // Calculate token age
        val tokenAgeMins = if (hist.isNotEmpty()) {
            (now - hist.first().ts) / 60_000.0
        } else 0.0
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 1: Zero or critically low liquidity (FLUID thresholds)
        // ─────────────────────────────────────────────────────────────────
        val liq = ts.lastLiquidityUsd
        val minLiq = when {
            tokenAgeMins < 5 -> com.lifecyclebot.v3.scoring.FluidLearningAI.getRugFilterLiqFresh()
            tokenAgeMins < 30 -> com.lifecyclebot.v3.scoring.FluidLearningAI.getRugFilterLiqYoung()
            else -> com.lifecyclebot.v3.scoring.FluidLearningAI.getRugFilterLiqEstablished()
        }
        
        if (liq <= 0) {
            return PreFilterResult(
                pass = false,
                reason = "ZERO_LIQUIDITY",
                severity = FilterSeverity.HARD_FAIL,
            )
        }
        
        if (liq < minLiq) {
            return PreFilterResult(
                pass = false,
                reason = "LOW_LIQUIDITY: $${liq.toInt()} < $${minLiq.toInt()} min",
                severity = FilterSeverity.HARD_FAIL,
            )
        }
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 2: Holder concentration (top holder too dominant)
        // ─────────────────────────────────────────────────────────────────
        val topHolderPct = ts.meta.holderConcentration
        if (topHolderPct > 85) {
            return PreFilterResult(
                pass = false,
                reason = "TOXIC_CONCENTRATION: top holder ${topHolderPct.toInt()}%",
                severity = FilterSeverity.HARD_FAIL,
            )
        }
        
        if (topHolderPct > 70 && tokenAgeMins > 10) {
            return PreFilterResult(
                pass = false,
                reason = "HIGH_CONCENTRATION: top holder ${topHolderPct.toInt()}% after ${tokenAgeMins.toInt()}min",
                severity = FilterSeverity.SOFT_FAIL,
            )
        }
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 3: No history (can't evaluate)
        // ─────────────────────────────────────────────────────────────────
        if (hist.isEmpty()) {
            return PreFilterResult(
                pass = false,
                reason = "NO_HISTORY",
                severity = FilterSeverity.HARD_FAIL,
            )
        }
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 4: Instant liquidity drain pattern
        // ─────────────────────────────────────────────────────────────────
        if (hist.size >= 3) {
            val volumes = hist.takeLast(3).map { it.vol }
            val isCollapsing = volumes.zipWithNext { a, b -> b < a * 0.5 }.count { it } >= 2
            
            if (isCollapsing && liq < 5000) {
                return PreFilterResult(
                    pass = false,
                    reason = "LIQUIDITY_COLLAPSE_PATTERN",
                    severity = FilterSeverity.HARD_FAIL,
                )
            }
        }
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 5: Pure sell pressure (no buyers)
        // ─────────────────────────────────────────────────────────────────
        if (hist.size >= 2) {
            val recentBuyRatios = hist.takeLast(3).map { it.buyRatio }
            val avgBuyRatio = recentBuyRatios.average()
            
            if (avgBuyRatio < 0.25) {
                return PreFilterResult(
                    pass = false,
                    reason = "PURE_SELL_PRESSURE: buy% ${(avgBuyRatio * 100).toInt()}%",
                    severity = FilterSeverity.HARD_FAIL,
                )
            }
        }
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 6: Zero holder count (suspicious) - DISABLED for Paper Mode learning
        // DexScreener often returns 0 holders for new tokens due to API lag
        // This was blocking too many legitimate tokens from being evaluated
        // ─────────────────────────────────────────────────────────────────
        // val lastCandle = hist.lastOrNull()
        // if (lastCandle != null && lastCandle.holderCount == 0 && tokenAgeMins > 5) {
        //     return PreFilterResult(
        //         pass = false,
        //         reason = "ZERO_HOLDERS",
        //         severity = FilterSeverity.SOFT_FAIL,
        //     )
        // }
        // NOTE: Holder count check disabled - was causing excessive blocks due to API data lag
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 7: Price crashed to near-zero
        // ─────────────────────────────────────────────────────────────────
        if (hist.size >= 5) {
            val prices = hist.map { it.priceUsd }
            val peakPrice = prices.maxOrNull() ?: 0.0
            val currentPrice = prices.lastOrNull() ?: 0.0
            
            if (peakPrice > 0 && currentPrice < peakPrice * 0.05) {
                return PreFilterResult(
                    pass = false,
                    reason = "PRICE_COLLAPSED: -${((1 - currentPrice / peakPrice) * 100).toInt()}%",
                    severity = FilterSeverity.HARD_FAIL,
                )
            }
        }
        
        // ─────────────────────────────────────────────────────────────────
        // ALL CHECKS PASSED
        // ─────────────────────────────────────────────────────────────────
        return PreFilterResult(
            pass = true,
            reason = null,
            severity = FilterSeverity.PASS,
        )
    }
    
    /**
     * Quick check if token passes basic viability thresholds.
     * Returns true if token should proceed to full evaluation.
     * V5.2 FIX: Added paperMode parameter for Paper Mode bypass.
     */
    fun isViable(ts: TokenState, isPaperMode: Boolean = false): Boolean {
        return filter(ts, isPaperMode).pass
    }
    
    /**
     * Log pre-filter failure for debugging.
     */
    fun logFailure(ts: TokenState, result: PreFilterResult) {
        if (!result.pass) {
            ErrorLogger.debug(TAG, "❌ ${ts.symbol} PRE-FILTER: ${result.reason} [${result.severity}]")
        }
    }
}

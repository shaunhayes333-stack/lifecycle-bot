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
     */
    fun filter(ts: TokenState): PreFilterResult {
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
        // CHECK 6: Zero holder count (suspicious)
        // ─────────────────────────────────────────────────────────────────
        val lastCandle = hist.lastOrNull()
        if (lastCandle != null && lastCandle.holderCount == 0 && tokenAgeMins > 5) {
            return PreFilterResult(
                pass = false,
                reason = "ZERO_HOLDERS",
                severity = FilterSeverity.SOFT_FAIL,
            )
        }
        
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
     */
    fun isViable(ts: TokenState): Boolean {
        return filter(ts).pass
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

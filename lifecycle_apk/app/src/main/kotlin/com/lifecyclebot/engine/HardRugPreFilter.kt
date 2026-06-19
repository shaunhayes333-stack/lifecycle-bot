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
        // V5.9.318: Treat 'no API poll yet returned' as awaiting-data regardless of
        // watchlist age. Previously a token whose addedToWatchlistAt was 0 (legacy
        // / migrated state) computed tokenAgeMs as the full epoch, so isVeryNew was
        // always false → ZERO_LIQUIDITY hard-fail fired even though we'd never even
        // tried to fetch the token's data. The history.isNotEmpty() check makes this
        // robust: if there's NO history yet, the API hasn't responded and we cannot
        // possibly know the real liquidity.
        val hasNoHistoryYet = ts.history.isEmpty()
        
        // Grace period: no liquidity + (added recently OR no API data yet) = let API poll have time to fetch data
        if (hasNoLiquidity && (isVeryNew || hasNoHistoryYet)) {
            // V5.0.3926 — LIVE GRACE TIGHTENING. Operator dump V5.0.3929
            // showed STANDARD-lane rugs (-96.9%) entering during this window
            // because liquidity/LP/holder data hadn't landed yet. For PAPER
            // we still want the learning signal — pass the grace. For LIVE
            // we have no safety proof at all; do not authorize a real-money
            // buy on UNKNOWN data. This is the rug-prevention requirement:
            // 'live entry must block or probe-only if RugCheck/LP/holder
            // proof is unknown.'
            if (!isPaperMode) {
                ErrorLogger.debug(TAG, "⛔ GRACE_BLOCK_LIVE: ${ts.symbol} age=${tokenAgeMs/1000}s hist=${ts.history.size} — live needs proof")
                return PreFilterResult(
                    pass = false,
                    reason = "GRACE_PERIOD_DATA_UNAVAILABLE_LIVE",
                    severity = FilterSeverity.HARD_FAIL,
                )
            }
            ErrorLogger.debug(TAG, "⏳ GRACE PERIOD: ${ts.symbol} - no liquidity yet (age=${tokenAgeMs/1000}s, hist=${ts.history.size})")
            return PreFilterResult(
                pass = true,
                reason = "GRACE_PERIOD_AWAITING_LIQUIDITY",
                severity = FilterSeverity.PASS,
            )
        }
        
        // V5.2 FIX: PAPER MODE BYPASS - Skip pre-filter to maximize learning
        // V5.9.47: proven-edge live runs ALSO bypass — they've earned it.
        // V5.9.449: REMOVED V5.9.421 ladder-tier turn-off. Build-1941 era
        // kept the bypass unconditional in paper mode — that's the
        // learning-rich path that produced the 60% WR. The QualityLadder
        // tier check was choking the paper feedback loop.
        // V5.9.877 — REPLACE blanket LENIENT_MODE_BYPASS with structured telemetry.
        //
        // PRIOR BEHAVIOR: in lenient mode (paper, or proven-edge live), the
        // pre-filter ran ONE check (zero-liq) and let everything else through
        // with reason="LENIENT_MODE_BYPASS". This is exactly the "old bypass
        // thinking under new architecture" the operator + external review
        // flagged: trades passed without labels, so the learning bus had no
        // way to distinguish "clean entry" from "would-have-been-rugged".
        //
        // NEW BEHAVIOR (per doctrine #86 + external GPT audit Item 4):
        //   - Zero-liquidity ALWAYS hard-fails (truly untradeable).
        //   - In lenient mode, the OTHER 6 checks STILL evaluate, but
        //     instead of blocking they return PASS with a structured
        //     PAPER_TELEMETRY:<reason> tag. Learners can soft-down-weight,
        //     pattern clusters can discover "trades labeled X have N% WR",
        //     and the FDG can apply graded confidence penalties per label.
        //   - Strict mode is unchanged: the 6 checks still HARD_FAIL.
        //
        // Net effect: same trade volume in paper mode (learning preserved),
        // but each trade now carries a quality label for the learning fan-out.
        val lenient = ModeLeniency.useLenientGates(isPaperMode)
        if (lenient) {
            // Hard-fail: literally zero liquidity (can't execute even in paper)
            if (ts.lastLiquidityUsd <= 0) {
                return PreFilterResult(
                    pass = false,
                    reason = "ZERO_LIQUIDITY (${ModeLeniency.label(isPaperMode).lowercase()})",
                    severity = FilterSeverity.HARD_FAIL,
                )
            }
            // Run all the strict checks and collect telemetry labels for any
            // that would have failed. Don't block — emit PASS with labels.
            val telemetryReasons = computeLenientTelemetryLabels(ts)
            if (telemetryReasons.isNotEmpty()) {
                ErrorLogger.debug(
                    TAG,
                    "🏷️ LENIENT TELEMETRY (${ModeLeniency.label(isPaperMode)}): " +
                    "${ts.symbol} labels=${telemetryReasons.joinToString(",")}"
                )
                return PreFilterResult(
                    pass = true,
                    reason = "PAPER_TELEMETRY:${telemetryReasons.joinToString("|")}",
                    severity = FilterSeverity.PASS,
                )
            }
            return PreFilterResult(
                pass = true,
                reason = "LENIENT_CLEAN",
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
                pass = true,
                reason = "LOW_LIQUIDITY_SIZE_REDUCED: $${liq.toInt()} < $${minLiq.toInt()} min",
                severity = FilterSeverity.SOFT_FAIL,
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
     * V5.9.877 — for lenient mode, compute the list of telemetry labels that
     * STRICT mode would have used to hard-fail. Returns an empty list when
     * the token would pass strict mode too. Order matches the strict check
     * order so the first label is the "highest-priority" issue.
     *
     * Pure function: read-only on TokenState, no mutation, no logging.
     * Safe to call frequently. Mirrors the strict check thresholds — if a
     * strict check is updated, update its mirror here too.
     */
    private fun computeLenientTelemetryLabels(ts: com.lifecyclebot.data.TokenState): List<String> {
        val labels = mutableListOf<String>()
        val hist = ts.history.toList()
        val now = System.currentTimeMillis()
        val tokenAgeMins = if (hist.isNotEmpty()) {
            (now - hist.first().ts) / 60_000.0
        } else 0.0

        // CHECK 1 mirror — LOW_LIQUIDITY (not zero, just below threshold)
        val liq = ts.lastLiquidityUsd
        val minLiq = when {
            tokenAgeMins < 5 -> com.lifecyclebot.v3.scoring.FluidLearningAI.getRugFilterLiqFresh()
            tokenAgeMins < 30 -> com.lifecyclebot.v3.scoring.FluidLearningAI.getRugFilterLiqYoung()
            else -> com.lifecyclebot.v3.scoring.FluidLearningAI.getRugFilterLiqEstablished()
        }
        if (liq > 0 && liq < minLiq) {
            labels.add("LOW_LIQUIDITY")
        }

        // CHECK 2 mirror — holder concentration
        val topHolderPct = ts.meta.holderConcentration
        if (topHolderPct > 85) {
            labels.add("TOXIC_CONCENTRATION")
        } else if (topHolderPct > 70 && tokenAgeMins > 10) {
            labels.add("HIGH_CONCENTRATION")
        }

        // CHECK 3 mirror — no history
        if (hist.isEmpty()) {
            labels.add("NO_HISTORY")
            return labels  // can't run candle-based checks
        }

        // CHECK 4 mirror — liquidity collapse pattern
        if (hist.size >= 3) {
            val volumes = hist.takeLast(3).map { it.vol }
            val isCollapsing = volumes.zipWithNext { a, b -> b < a * 0.5 }.count { it } >= 2
            if (isCollapsing && liq < 5000) {
                labels.add("LIQUIDITY_COLLAPSE_PATTERN")
            }
        }

        // CHECK 5 mirror — pure sell pressure
        if (hist.size >= 2) {
            val recentBuyRatios = hist.takeLast(3).map { it.buyRatio }
            val avgBuyRatio = recentBuyRatios.average()
            if (avgBuyRatio < 0.25) {
                labels.add("PURE_SELL_PRESSURE")
            }
        }

        // CHECK 7 mirror — price crashed
        if (hist.size >= 5) {
            val prices = hist.map { it.priceUsd }
            val peakPrice = prices.maxOrNull() ?: 0.0
            val currentPrice = prices.lastOrNull() ?: 0.0
            if (peakPrice > 0 && currentPrice < peakPrice * 0.05) {
                labels.add("PRICE_COLLAPSED")
            }
        }

        return labels
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

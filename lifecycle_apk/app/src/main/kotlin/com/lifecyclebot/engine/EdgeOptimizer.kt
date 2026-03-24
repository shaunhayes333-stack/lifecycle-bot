package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState

/**
 * EdgeOptimizer — Phase 2 Edge Optimization
 * ═══════════════════════════════════════════════════════════════════════
 * 
 * This is where money is made. The bot becomes:
 * - Predictive (not reactive)
 * - Selective (not trading everything)
 * - Aggressive when correct
 * 
 * KEY CONCEPTS:
 * 1. Market Phase Detection — classify chart state before acting
 * 2. Entry Timing — Spike → Pullback → Reclaim → ENTER
 * 3. Score Weight Rebalancing — BUY% and MOMENTUM most important
 * 4. Second Leg Exploit — detect reload patterns
 * 5. Dynamic Position Sizing — size based on confidence
 * 6. Trade Filtering — become selective
 */
object EdgeOptimizer {

    // ═══════════════════════════════════════════════════════════════════
    // MARKET PHASE DETECTION
    // ═══════════════════════════════════════════════════════════════════
    
    enum class MarketPhase {
        EXPANSION,        // Volume rising + Price rising → allow entry + top-ups
        DISTRIBUTION,     // Volume high + Price flat → exit / block entries
        REACCUMULATION,   // Sharp spike then pullback → BEST entry zone
        DEAD,             // Volume dropping + Price flat → ignore completely
        EARLY_ACCUMULATION, // Low volume but price starting to rise
        UNKNOWN
    }
    
    data class PhaseAnalysis(
        val phase: MarketPhase,
        val confidence: Double,      // 0-100 confidence in phase detection
        val volumeTrend: Double,     // -100 to +100 (negative = dropping)
        val priceTrend: Double,      // -100 to +100 (negative = dropping)
        val buyPressure: Double,     // 0-100 current buy pressure
        val isSecondLeg: Boolean,    // Second leg opportunity detected
        val entryQuality: String,    // "OPTIMAL", "GOOD", "MARGINAL", "AVOID"
    )
    
    /**
     * Detect market phase based on volume and price action.
     * This is the GAME CHANGER — not every token should be traded the same way.
     */
    fun detectMarketPhase(hist: List<Candle>, prices: List<Double>): PhaseAnalysis {
        if (hist.size < 6 || prices.size < 6) {
            return PhaseAnalysis(
                phase = MarketPhase.UNKNOWN,
                confidence = 0.0,
                volumeTrend = 0.0,
                priceTrend = 0.0,
                buyPressure = 50.0,
                isSecondLeg = false,
                entryQuality = "AVOID"
            )
        }
        
        // Calculate volume trend (recent 6 candles vs previous 6)
        val recentVol = hist.takeLast(6).map { it.vol }
        val olderVol = hist.dropLast(6).takeLast(6).map { it.vol }
        
        val recentVolAvg = if (recentVol.isNotEmpty()) recentVol.average() else 0.0
        val olderVolAvg = if (olderVol.isNotEmpty()) olderVol.average() else recentVolAvg
        
        val volumeTrend = if (olderVolAvg > 0) {
            ((recentVolAvg - olderVolAvg) / olderVolAvg * 100).coerceIn(-100.0, 100.0)
        } else 0.0
        
        // Calculate price trend (recent 6 vs previous 6)
        val recentPrices = prices.takeLast(6)
        val olderPrices = prices.dropLast(6).takeLast(6)
        
        val recentPriceAvg = if (recentPrices.isNotEmpty()) recentPrices.average() else 0.0
        val olderPriceAvg = if (olderPrices.isNotEmpty()) olderPrices.average() else recentPriceAvg
        
        val priceTrend = if (olderPriceAvg > 0) {
            ((recentPriceAvg - olderPriceAvg) / olderPriceAvg * 100).coerceIn(-100.0, 100.0)
        } else 0.0
        
        // Calculate buy pressure from recent candles
        val recentBuyRatios = hist.takeLast(6).map { 
            if (it.buysH1 + it.sellsH1 > 0) it.buysH1.toDouble() / (it.buysH1 + it.sellsH1) else 0.5 
        }
        val buyPressure = (recentBuyRatios.average() * 100).coerceIn(0.0, 100.0)
        
        // Detect spike then pullback pattern (for second leg)
        val isSecondLeg = detectSecondLegOpportunity(hist, prices)
        
        // Classify phase
        val (phase, confidence) = when {
            // EXPANSION: Volume rising AND Price rising
            volumeTrend > 15 && priceTrend > 5 && buyPressure > 52 -> {
                MarketPhase.EXPANSION to minOf(volumeTrend, priceTrend, buyPressure - 50)
            }
            
            // DISTRIBUTION: Volume high AND Price flat/dropping
            recentVolAvg > olderVolAvg * 0.8 && priceTrend < 3 && priceTrend > -10 && buyPressure < 48 -> {
                MarketPhase.DISTRIBUTION to (100 - buyPressure * 2).coerceIn(30.0, 80.0)
            }
            
            // REACCUMULATION: Sharp spike then pullback with recovering buy pressure
            isSecondLeg && buyPressure > 50 -> {
                MarketPhase.REACCUMULATION to (buyPressure + 20).coerceAtMost(95.0)
            }
            
            // DEAD: Volume dropping AND Price flat
            volumeTrend < -20 && kotlin.math.abs(priceTrend) < 5 -> {
                MarketPhase.DEAD to kotlin.math.abs(volumeTrend).coerceAtMost(90.0)
            }
            
            // EARLY_ACCUMULATION: Low volume but price starting to move up
            volumeTrend < 10 && priceTrend > 3 && buyPressure > 52 -> {
                MarketPhase.EARLY_ACCUMULATION to (priceTrend + buyPressure - 50).coerceIn(20.0, 70.0)
            }
            
            else -> MarketPhase.UNKNOWN to 25.0  // LOWERED from 30% - allow more trades in unknown phase
        }
        
        // Determine entry quality based on phase
        val entryQuality = when (phase) {
            MarketPhase.REACCUMULATION -> "OPTIMAL"
            MarketPhase.EXPANSION -> if (buyPressure > 55) "GOOD" else "MARGINAL"
            MarketPhase.EARLY_ACCUMULATION -> "MARGINAL"
            MarketPhase.DISTRIBUTION -> "AVOID"
            MarketPhase.DEAD -> "AVOID"
            MarketPhase.UNKNOWN -> "MARGINAL"
        }
        
        return PhaseAnalysis(
            phase = phase,
            confidence = confidence,
            volumeTrend = volumeTrend,
            priceTrend = priceTrend,
            buyPressure = buyPressure,
            isSecondLeg = isSecondLeg,
            entryQuality = entryQuality
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // SECOND LEG EXPLOIT
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Detect "Second Leg" opportunity — your original insight.
     * Charts repeat because bots behave the same.
     * 
     * Pattern: first pump → pullback < 15% → second push attempt
     */
    private fun detectSecondLegOpportunity(hist: List<Candle>, prices: List<Double>): Boolean {
        if (hist.size < 12 || prices.size < 12) return false
        
        // Look for first pump in last 12 candles
        val lookback = prices.takeLast(12)
        val high = lookback.max()
        val highIndex = lookback.indexOfLast { it == high }
        
        // Need pump to have happened (not at the end)
        if (highIndex < 3 || highIndex > 9) return false
        
        // Calculate pullback from high
        val current = prices.last()
        val pullbackPct = ((high - current) / high) * 100
        
        // Pullback should be 5-20% (not too much, not too little)
        if (pullbackPct < 5 || pullbackPct > 20) return false
        
        // Check if buy pressure is rising again
        val recentBuys = hist.takeLast(3).map { 
            if (it.buysH1 + it.sellsH1 > 0) it.buysH1.toDouble() / (it.buysH1 + it.sellsH1) else 0.5 
        }
        val olderBuys = hist.dropLast(3).takeLast(3).map { 
            if (it.buysH1 + it.sellsH1 > 0) it.buysH1.toDouble() / (it.buysH1 + it.sellsH1) else 0.5 
        }
        
        val buyRising = recentBuys.average() > olderBuys.average() + 0.02
        
        // Check volume is recovering
        val recentVol = hist.takeLast(3).map { it.vol }.average()
        val midVol = hist.dropLast(3).takeLast(3).map { it.vol }.average()
        val volRecovering = recentVol > midVol * 0.8
        
        return buyRising && volRecovering
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // ENTRY TIMING — Spike → Pullback → Reclaim → ENTER
    // ═══════════════════════════════════════════════════════════════════
    
    data class EntryTiming(
        val shouldEnter: Boolean,
        val reason: String,
        val confidence: Double,
        val isOptimalEntry: Boolean,  // True if this is the "reload" zone
    )
    
    /**
     * Determine if NOW is the right time to enter.
     * Don't just: Signal → ENTER
     * Do: Spike → Pullback → Reclaim → ENTER
     */
    fun checkEntryTiming(
        phase: PhaseAnalysis,
        hist: List<Candle>,
        prices: List<Double>,
        currentBuyPct: Double,
    ): EntryTiming {
        // DEAD phase — never enter
        if (phase.phase == MarketPhase.DEAD) {
            return EntryTiming(
                shouldEnter = false,
                reason = "DEAD phase - no activity",
                confidence = phase.confidence,
                isOptimalEntry = false
            )
        }
        
        // DISTRIBUTION phase — block entries
        if (phase.phase == MarketPhase.DISTRIBUTION) {
            return EntryTiming(
                shouldEnter = false,
                reason = "DISTRIBUTION - smart money exiting",
                confidence = phase.confidence,
                isOptimalEntry = false
            )
        }
        
        // Buy pressure too low — skip
        if (currentBuyPct < 50) {
            return EntryTiming(
                shouldEnter = false,
                reason = "Buy pressure too low (${currentBuyPct.toInt()}%)",
                confidence = 70.0,
                isOptimalEntry = false
            )
        }
        
        // REACCUMULATION — BEST entry zone (second leg)
        if (phase.phase == MarketPhase.REACCUMULATION && phase.isSecondLeg) {
            return EntryTiming(
                shouldEnter = true,
                reason = "REACCUMULATION - second leg reload zone",
                confidence = phase.confidence,
                isOptimalEntry = true
            )
        }
        
        // Check for Spike → Pullback → Reclaim pattern
        val isPullbackReclaim = detectPullbackReclaim(hist, prices, currentBuyPct)
        if (isPullbackReclaim) {
            return EntryTiming(
                shouldEnter = true,
                reason = "Pullback reclaim confirmed",
                confidence = 75.0,
                isOptimalEntry = true
            )
        }
        
        // EXPANSION — allow entry if buy pressure confirms
        if (phase.phase == MarketPhase.EXPANSION && currentBuyPct > 54) {
            return EntryTiming(
                shouldEnter = true,
                reason = "EXPANSION phase with strong buy pressure",
                confidence = phase.confidence,
                isOptimalEntry = false  // Good but not optimal
            )
        }
        
        // EARLY_ACCUMULATION — cautious entry
        if (phase.phase == MarketPhase.EARLY_ACCUMULATION && currentBuyPct > 55) {
            return EntryTiming(
                shouldEnter = true,
                reason = "Early accumulation - cautious entry",
                confidence = phase.confidence * 0.8,
                isOptimalEntry = false
            )
        }
        
        // Default: wait for better setup
        return EntryTiming(
            shouldEnter = false,
            reason = "Waiting for better entry timing",
            confidence = 50.0,
            isOptimalEntry = false
        )
    }
    
    /**
     * Detect Spike → Pullback → Reclaim pattern.
     * This is where bots reload.
     */
    private fun detectPullbackReclaim(
        hist: List<Candle>,
        prices: List<Double>,
        currentBuyPct: Double,
    ): Boolean {
        if (prices.size < 10) return false
        
        val lookback = prices.takeLast(10)
        val high = lookback.dropLast(2).max()  // High in earlier candles
        val lowAfterHigh = lookback.takeLast(5).min()  // Low after the high
        val current = prices.last()
        
        // Pullback should be 8-18%
        val pullback = ((high - lowAfterHigh) / high) * 100
        if (pullback < 8 || pullback > 18) return false
        
        // Current should be reclaiming (above the low)
        val reclaim = ((current - lowAfterHigh) / (high - lowAfterHigh)) * 100
        if (reclaim < 30 || reclaim > 80) return false  // 30-80% reclaim
        
        // Buy pressure must be rising
        return currentBuyPct > 52
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // SCORE WEIGHT REBALANCING
    // ═══════════════════════════════════════════════════════════════════
    
    data class WeightedScore(
        val entryScore: Double,      // 0-100 final entry score
        val exitScore: Double,       // 0-100 final exit score  
        val components: Map<String, Double>,  // Individual component contributions
    )
    
    /**
     * Rebalanced scoring with proper weights.
     * Reality:
     *   BUY% + MOMENTUM = most important
     *   ENTRY score = secondary
     *   VOL = confirmation
     *   EXIT = warning
     */
    fun calculateWeightedScores(
        buyPct: Double,           // 0-100 current buy percentage
        momentum: Double,         // -100 to +100 price momentum
        volumeScore: Double,      // 0-100 volume activity score
        rawEntrySignal: Double,   // 0-100 raw entry signal from strategy
        exitPressure: Double,     // 0-100 exit pressure indicators
        buyPctDrop: Double,       // How much buy% has dropped (0-100)
        volumeFade: Double,       // How much volume has faded (0-100)
    ): WeightedScore {
        // Entry score weighting:
        // BUY% * 0.35 + MOMENTUM * 0.30 + VOLUME * 0.20 + ENTRY_SIGNAL * 0.15
        val normalizedMomentum = ((momentum + 100) / 2).coerceIn(0.0, 100.0)  // Convert -100..100 to 0..100
        
        val entryScore = (
            buyPct * 0.35 +
            normalizedMomentum * 0.30 +
            volumeScore * 0.20 +
            rawEntrySignal * 0.15
        ).coerceIn(0.0, 100.0)
        
        // Exit score weighting:
        // EXIT_PRESSURE * 0.40 + BUY_PCT_DROP * 0.30 + VOLUME_FADE * 0.30
        val exitScore = (
            exitPressure * 0.40 +
            buyPctDrop * 0.30 +
            volumeFade * 0.30
        ).coerceIn(0.0, 100.0)
        
        return WeightedScore(
            entryScore = entryScore,
            exitScore = exitScore,
            components = mapOf(
                "buy_pct_contrib" to buyPct * 0.35,
                "momentum_contrib" to normalizedMomentum * 0.30,
                "volume_contrib" to volumeScore * 0.20,
                "signal_contrib" to rawEntrySignal * 0.15,
                "exit_pressure_contrib" to exitPressure * 0.40,
                "buy_drop_contrib" to buyPctDrop * 0.30,
                "vol_fade_contrib" to volumeFade * 0.30,
            )
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // DYNAMIC POSITION SIZING
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Calculate position size based on confidence score.
     * High confidence = larger position.
     */
    fun calculatePositionSize(
        confidenceScore: Double,
        walletBalanceSol: Double,
        maxPositionPct: Double = 10.0,  // Max 10% of wallet
    ): Double {
        val sizePct = when {
            confidenceScore > 80 -> 10.0   // High confidence = 10%
            confidenceScore > 70 -> 8.0
            confidenceScore > 60 -> 6.0
            confidenceScore > 50 -> 4.0
            else -> 3.0                     // Low confidence = 3%
        }
        
        val effectivePct = minOf(sizePct, maxPositionPct)
        return walletBalanceSol * (effectivePct / 100.0)
    }
    
    /**
     * Calculate confidence score for a trade setup.
     */
    fun calculateConfidence(
        phase: PhaseAnalysis,
        entryTiming: EntryTiming,
        weightedScore: WeightedScore,
    ): Double {
        var confidence = 0.0
        
        // Phase confidence (0-30 points)
        confidence += when (phase.phase) {
            MarketPhase.REACCUMULATION -> 30.0
            MarketPhase.EXPANSION -> 22.0
            MarketPhase.EARLY_ACCUMULATION -> 15.0
            MarketPhase.UNKNOWN -> 8.0
            else -> 0.0
        }
        
        // Entry timing (0-25 points)
        if (entryTiming.shouldEnter) {
            confidence += if (entryTiming.isOptimalEntry) 25.0 else 15.0
        }
        
        // Entry score (0-25 points)
        confidence += (weightedScore.entryScore / 100.0) * 25.0
        
        // Buy pressure bonus (0-20 points)
        if (phase.buyPressure > 55) {
            confidence += ((phase.buyPressure - 55) / 45.0) * 20.0
        }
        
        return confidence.coerceIn(0.0, 100.0)
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // TRADE FILTERING — Become selective
    // ═══════════════════════════════════════════════════════════════════
    
    data class FilterResult(
        val shouldTrade: Boolean,
        val reason: String,
        val quality: String,  // "A", "B", "C", or "SKIP"
    )
    
    /**
     * Filter trades for quality. Less trades = higher quality.
     * 
     * PAPER MODE: More lenient thresholds to allow learning.
     * LIVE MODE: Strict thresholds to protect capital.
     * 
     * ADAPTIVE: Thresholds are learned from trade outcomes via EdgeLearning.
     */
    fun filterTrade(
        phase: PhaseAnalysis,
        volumeScore: Double,
        buyPct: Double,
        entryTiming: EntryTiming,
        isPaperMode: Boolean = false,
    ): FilterResult {
        // DEAD phase — always skip (even paper mode)
        if (phase.phase == MarketPhase.DEAD) {
            return FilterResult(
                shouldTrade = false,
                reason = "Phase is DEAD - no activity",
                quality = "SKIP"
            )
        }
        
        // Distribution phase — always skip (even paper mode)
        if (phase.phase == MarketPhase.DISTRIBUTION) {
            return FilterResult(
                shouldTrade = false,
                reason = "Distribution phase - smart money selling",
                quality = "SKIP"
            )
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // ADAPTIVE THRESHOLDS - Learned from trade outcomes via EdgeLearning
        // ═══════════════════════════════════════════════════════════════════
        
        val minVolume = if (isPaperMode) 
            EdgeLearning.getPaperVolumeMin() 
        else 
            EdgeLearning.getLiveVolumeMin()
            
        val minBuyPct = if (isPaperMode) 
            EdgeLearning.getPaperBuyPctMin() 
        else 
            EdgeLearning.getLiveBuyPctMin()
            
        val requireOptimalTiming = !isPaperMode  // Paper: advisory, Live: required
        
        // Volume too low — skip
        if (volumeScore < minVolume) {
            return FilterResult(
                shouldTrade = false,
                reason = "Volume too low (${volumeScore.toInt()} < ${minVolume.toInt()})",
                quality = "SKIP"
            )
        }
        
        // Buy pressure too low — skip
        if (buyPct < minBuyPct) {
            return FilterResult(
                shouldTrade = false,
                reason = "Buy% too low (${buyPct.toInt()}% < ${minBuyPct.toInt()}%)",
                quality = "SKIP"
            )
        }
        
        // Entry timing — PAPER: advisory only, LIVE: conditionally required
        // CHANGED: Allow UNKNOWN phase in live mode if buy pressure is strong
        val allowUnknownWithBuyPressure = phase.phase == MarketPhase.UNKNOWN && buyPct >= 55.0
        
        if (requireOptimalTiming && !entryTiming.shouldEnter && !allowUnknownWithBuyPressure) {
            return FilterResult(
                shouldTrade = false,
                reason = entryTiming.reason,
                quality = "SKIP"
            )
        }
        
        // Grade the trade
        val quality = when {
            phase.phase == MarketPhase.REACCUMULATION && entryTiming.isOptimalEntry -> "A"
            phase.phase == MarketPhase.EXPANSION && buyPct > 56 -> "A"
            entryTiming.isOptimalEntry -> "B"
            phase.phase == MarketPhase.EXPANSION -> "B"
            phase.phase == MarketPhase.EARLY_ACCUMULATION && buyPct > 50 -> "B"
            // NEW: UNKNOWN phase with strong buyers gets C (not SKIP)
            phase.phase == MarketPhase.UNKNOWN && buyPct >= 55 -> "C"
            isPaperMode && buyPct >= 45 -> "C"  // Paper: allow C quality for learning
            else -> "C"
        }
        
        return FilterResult(
            shouldTrade = true,
            reason = "Trade quality: $quality - ${phase.phase.name}" + 
                     if (isPaperMode && !entryTiming.shouldEnter) " (paper: timing override)" else "",
            quality = quality
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // LOGGING HELPER
    // ═══════════════════════════════════════════════════════════════════
    
    fun formatAnalysis(
        symbol: String,
        phase: PhaseAnalysis,
        timing: EntryTiming,
        filter: FilterResult,
        confidence: Double,
    ): String {
        val phaseEmoji = when (phase.phase) {
            MarketPhase.EXPANSION -> "📈"
            MarketPhase.REACCUMULATION -> "🔄"
            MarketPhase.DISTRIBUTION -> "📉"
            MarketPhase.DEAD -> "💀"
            MarketPhase.EARLY_ACCUMULATION -> "🌱"
            MarketPhase.UNKNOWN -> "❓"
        }
        
        return buildString {
            append("$phaseEmoji $symbol | ")
            append("Phase: ${phase.phase.name} (${phase.confidence.toInt()}%) | ")
            append("Buy%: ${phase.buyPressure.toInt()} | ")
            append("Quality: ${filter.quality} | ")
            append("Conf: ${confidence.toInt()}")
            if (phase.isSecondLeg) append(" | 🔥 SECOND LEG")
            if (timing.isOptimalEntry) append(" | ⭐ OPTIMAL")
        }
    }
}

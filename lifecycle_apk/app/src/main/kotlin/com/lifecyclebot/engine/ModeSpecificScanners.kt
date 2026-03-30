package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.data.Candle
import com.lifecyclebot.v3.scoring.FluidLearningAI
import java.util.concurrent.ConcurrentHashMap

/**
 * ModeSpecificScanners — Specialized Token Discovery Systems
 * 
 * V4.1.2: Now uses FLUID LEARNING thresholds that adapt as the bot learns.
 * Bootstrap mode is more lenient to gather data, mature mode is stricter
 * to focus on higher-quality setups with better win rates.
 * 
 * Instead of one universal scanner, we run multiple specialized feeds:
 *   A. Fresh Launch Scanner — Newest tokens with safety floor
 *   B. Breakout Scanner — Tokens near local highs with tight consolidations
 *   C. Reversal Scanner — Dumped tokens with reclaim behavior
 *   D. Smart Wallet Scanner — Aligned wallet accumulations
 *   E. Post-Graduation Scanner — Migration/graduation events
 *   F. Liquidity Regime Scanner — Tokens with improving liquidity slope
 * 
 * Each scanner produces candidates ranked by their specific criteria.
 */
object ModeSpecificScanners {
    
    private const val TAG = "ModeScanner"
    
    // ═══════════════════════════════════════════════════════════════════
    // FLUID THRESHOLDS - Adapt as bot learns
    // Bootstrap: Lower bars to gather learning data
    // Mature: Higher bars to focus on quality setups
    // ═══════════════════════════════════════════════════════════════════
    
    // Minimum score thresholds
    private const val SCORE_BOOTSTRAP = 30.0      // Lower bar at start
    private const val SCORE_MATURE = 55.0         // Higher bar when experienced
    
    // Liquidity thresholds
    private const val LIQ_BOOTSTRAP = 1500.0      // $1.5K minimum at start
    private const val LIQ_MATURE = 5000.0         // $5K minimum when mature
    
    // Buy pressure thresholds  
    private const val BUY_PRESS_BOOTSTRAP = 0.45  // 45% buy ratio at start
    private const val BUY_PRESS_MATURE = 0.55     // 55% buy ratio when mature
    
    // Impulse (prior move) thresholds for breakout
    private const val IMPULSE_BOOTSTRAP = 15.0    // 15% prior move at start
    private const val IMPULSE_MATURE = 30.0       // 30% prior move when mature
    
    // Dip depth for reversal
    private const val DIP_MIN_BOOTSTRAP = 20.0    // 20% dip at start
    private const val DIP_MIN_MATURE = 30.0       // 30% dip when mature
    
    /** Lerp between bootstrap and mature values based on learning progress */
    private fun lerp(bootstrap: Double, mature: Double): Double {
        val progress = FluidLearningAI.getLearningProgress()
        return bootstrap + (mature - bootstrap) * progress
    }
    
    /** Get fluid minimum score threshold */
    fun getMinScoreThreshold(): Double = lerp(SCORE_BOOTSTRAP, SCORE_MATURE)
    
    /** Get fluid minimum liquidity threshold */
    fun getMinLiquidityThreshold(): Double = lerp(LIQ_BOOTSTRAP, LIQ_MATURE)
    
    /** Get fluid minimum buy pressure threshold */
    fun getMinBuyPressure(): Double = lerp(BUY_PRESS_BOOTSTRAP, BUY_PRESS_MATURE)
    
    /** Get fluid minimum impulse for breakout */
    fun getMinImpulse(): Double = lerp(IMPULSE_BOOTSTRAP, IMPULSE_MATURE)
    
    /** Get fluid minimum dip for reversal */
    fun getMinDipDepth(): Double = lerp(DIP_MIN_BOOTSTRAP, DIP_MIN_MATURE)
    
    // ═══════════════════════════════════════════════════════════════════
    // SCANNER RESULTS
    // ═══════════════════════════════════════════════════════════════════
    
    data class ScanResult(
        val mint: String,
        val symbol: String,
        val scannerType: ScannerType,
        val score: Double,           // 0-100
        val signals: List<String>,
        val suggestedMode: ModeRouter.TradeType,
        val timestamp: Long = System.currentTimeMillis(),
    )
    
    enum class ScannerType(val emoji: String, val label: String) {
        FRESH_LAUNCH("🚀", "Fresh Launch"),
        BREAKOUT("📊", "Breakout"),
        REVERSAL("🔄", "Reversal"),
        SMART_WALLET("🐋", "Smart Wallet"),
        GRADUATION("🎓", "Graduation"),
        LIQUIDITY("💧", "Liquidity Regime"),
    }
    
    // Cache recent scan results
    private val recentResults = ConcurrentHashMap<String, ScanResult>()
    private const val CACHE_TTL_MS = 60_000L  // 1 minute
    
    // ═══════════════════════════════════════════════════════════════════
    // A. FRESH LAUNCH SCANNER
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Finds newest tokens with strict safety floor.
     * 
     * Criteria:
     * - Age < 15 minutes
     * - Early liquidity floor met ($2000+)
     * - Ownership/mint/freeze risk acceptable
     * - Early buy spread healthy
     * - No instant liquidity drain
     */
    fun scanFreshLaunch(ts: TokenState): ScanResult? {
        val now = System.currentTimeMillis()
        val hist = ts.history.toList()
        
        // Calculate token age from history (first candle timestamp)
        val tokenAgeMins = if (hist.isNotEmpty()) {
            (now - hist.first().ts) / 60_000.0
        } else 999.0
        
        // Must be fresh
        if (tokenAgeMins > 15) return null
        
        var score = 0.0
        val signals = mutableListOf<String>()
        
        // Age bonus (fresher = higher score)
        val ageFactor = (15.0 - tokenAgeMins) / 15.0
        score += ageFactor * 30
        signals.add("Age: ${tokenAgeMins.toInt()}min")
        
        // Liquidity floor - FLUID
        val minLiq = getMinLiquidityThreshold()
        if (ts.lastLiquidityUsd >= minLiq * 2) {
            score += 25.0
            signals.add("Liq: $${ts.lastLiquidityUsd.toInt()}")
        } else if (ts.lastLiquidityUsd >= minLiq) {
            score += 15.0
            signals.add("Liq: $${ts.lastLiquidityUsd.toInt()}")
        } else {
            return null  // Below safety floor
        }
        
        // Early buy pressure - FLUID
        val minBuyPress = getMinBuyPressure()
        val lastCandle = hist.lastOrNull()
        if (lastCandle != null) {
            if (lastCandle.buyRatio > minBuyPress) {
                score += 20.0
                signals.add("Buy%: ${(lastCandle.buyRatio * 100).toInt()}%")
            }
            
            // Volume activity
            if (lastCandle.vol > 3000) {
                score += 10.0
            }
            
            // Holder count growing
            if (lastCandle.holderCount > 30) {
                score += 10.0
                signals.add("Holders: ${lastCandle.holderCount}")
            }
        }
        
        // Holder concentration (lower is better for fresh launch)
        if (ts.meta.holderConcentration > 0 && ts.meta.holderConcentration < 50) {
            score += 5.0
        } else if (ts.meta.holderConcentration > 80) {
            return null  // Too concentrated - risky
        }
        
        // Minimum score threshold - FLUID
        val minScore = getMinScoreThreshold()
        if (score < minScore) return null
        
        return ScanResult(
            mint = ts.mint,
            symbol = ts.symbol,
            scannerType = ScannerType.FRESH_LAUNCH,
            score = score.coerceAtMost(100.0),
            signals = signals,
            suggestedMode = ModeRouter.TradeType.FRESH_LAUNCH,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // B. BREAKOUT SCANNER
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Finds tokens near local highs with tight consolidations.
     * 
     * Criteria:
     * - Rising liquidity (not just price)
     * - Market cap holding above prior impulse leg
     * - Higher lows after first breakout
     * - Volume contracting during consolidation
     * - Stable holder structure
     */
    fun scanBreakout(ts: TokenState): ScanResult? {
        val hist = ts.history.toList()
        if (hist.size < 12) return null
        
        var score = 0.0
        val signals = mutableListOf<String>()
        
        val prices = hist.map { it.priceUsd }
        val vols = hist.map { it.vol }
        
        // Find prior impulse leg
        val recentHigh = prices.takeLast(12).maxOrNull() ?: return null
        val priorLow = prices.dropLast(6).takeLast(10).minOrNull() ?: return null
        val impulsePct = if (priorLow > 0) ((recentHigh - priorLow) / priorLow) * 100 else 0.0
        
        // Need meaningful prior move - FLUID
        val minImpulse = getMinImpulse()
        if (impulsePct < minImpulse) return null
        
        score += 20.0
        signals.add("Prior move: +${impulsePct.toInt()}%")
        
        // Price holding above impulse midpoint
        val currentPrice = prices.lastOrNull() ?: return null
        val midpoint = (recentHigh + priorLow) / 2
        if (currentPrice > midpoint) {
            score += 15.0
            signals.add("Holding above mid")
        }
        
        // Higher lows forming (consolidation, not distribution)
        val last6Lows = prices.takeLast(6)
        val minLow = last6Lows.minOrNull() ?: 0.0
        val isHigherLows = last6Lows.all { it >= minLow * 0.97 }
        if (isHigherLows) {
            score += 20.0
            signals.add("Higher lows")
        }
        
        // Volume contraction (bull flag pattern)
        val recentVol = vols.takeLast(4).average()
        val priorVol = vols.dropLast(4).takeLast(6).average()
        if (priorVol > 0 && recentVol < priorVol * 0.75) {
            score += 15.0
            signals.add("Vol contracting")
        }
        
        // Near breakout level
        if (currentPrice > recentHigh * 0.92) {
            score += 15.0
            signals.add("Near breakout")
        }
        
        // Liquidity stability - FLUID
        val minLiq = getMinLiquidityThreshold()
        if (ts.lastLiquidityUsd > minLiq) {
            score += 10.0
        }
        
        // Holder structure
        val lastCandle = hist.lastOrNull()
        if (lastCandle != null && lastCandle.holderCount > 80) {
            score += 5.0
        }
        
        // Minimum score - FLUID
        val minScore = getMinScoreThreshold()
        if (score < minScore) return null
        
        return ScanResult(
            mint = ts.mint,
            symbol = ts.symbol,
            scannerType = ScannerType.BREAKOUT,
            score = score.coerceAtMost(100.0),
            signals = signals,
            suggestedMode = ModeRouter.TradeType.BREAKOUT_CONTINUATION,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // C. REVERSAL SCANNER
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Finds dumped tokens with reclaim behavior.
     * 
     * Criteria:
     * - Hard dump followed by stabilization
     * - Sell pressure weakening
     * - Liquidity no longer draining
     * - Wicks getting bought up
     * - Holder count stable despite dump
     */
    fun scanReversal(ts: TokenState): ScanResult? {
        val hist = ts.history.toList()
        if (hist.size < 10) return null
        
        var score = 0.0
        val signals = mutableListOf<String>()
        
        val prices = hist.map { it.priceUsd }
        
        // Find dump
        val recentHigh = prices.dropLast(4).takeLast(10).maxOrNull() ?: return null
        val recentLow = prices.takeLast(6).minOrNull() ?: return null
        val dumpPct = if (recentHigh > 0) ((recentHigh - recentLow) / recentHigh) * 100 else 0.0
        
        // Need meaningful dump - FLUID (min 20% bootstrap, 30% mature)
        val minDip = getMinDipDepth()
        if (dumpPct < minDip || dumpPct > 70) return null
        
        score += 20.0
        signals.add("Dump: -${dumpPct.toInt()}%")
        
        // Stabilization (not making new lows)
        val last4Prices = prices.takeLast(4)
        val stabilized = last4Prices.all { it >= recentLow * 0.97 }
        if (stabilized) {
            score += 20.0
            signals.add("Stabilizing")
        }
        
        // Sell pressure weakening
        val recentCandles = hist.takeLast(4)
        val buyRatios = recentCandles.map { it.buyRatio }
        val avgBuyRatio = buyRatios.average()
        val improving = buyRatios.zipWithNext { a, b -> b >= a }.count { it } >= 2
        
        if (avgBuyRatio > 0.45) {
            score += 15.0
            signals.add("Sell exhausting")
        }
        if (improving) {
            score += 10.0
            signals.add("Buy ratio improving")
        }
        
        // Wicks getting bought (recovery candles)
        val wicksBought = recentCandles.count { c ->
            c.lowUsd > 0 && c.priceUsd > c.lowUsd * 1.03
        }
        if (wicksBought >= 2) {
            score += 15.0
            signals.add("Wicks bought")
        }
        
        // Price reclaiming levels
        val currentPrice = prices.lastOrNull() ?: 0.0
        val recovery = if (dumpPct > 0) ((currentPrice - recentLow) / (recentHigh - recentLow)) * 100 else 0.0
        if (recovery > 30) {
            score += 10.0
            signals.add("Recovery: ${recovery.toInt()}%")
        }
        
        // Liquidity holding - FLUID
        val minLiq = getMinLiquidityThreshold()
        if (ts.lastLiquidityUsd > minLiq) {
            score += 5.0
        }
        
        // Holders stable
        val lastCandle = hist.lastOrNull()
        val firstCandle = hist.dropLast(5).lastOrNull()
        if (lastCandle != null && firstCandle != null && 
            lastCandle.holderCount >= firstCandle.holderCount * 0.8) {
            score += 5.0
        }
        
        // Minimum score - FLUID
        val minScore = getMinScoreThreshold()
        if (score < minScore) return null
        
        return ScanResult(
            mint = ts.mint,
            symbol = ts.symbol,
            scannerType = ScannerType.REVERSAL,
            score = score.coerceAtMost(100.0),
            signals = signals,
            suggestedMode = ModeRouter.TradeType.REVERSAL_RECLAIM,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // D. SMART WALLET SCANNER
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Finds tokens with aligned wallet accumulations.
     * 
     * Criteria:
     * - Repeated buys from smart wallets
     * - Buys clustered in tight time window
     * - No matching distribution from same wallets
     * - Price not yet fully expanded
     * - Liquidity enough to absorb size
     */
    fun scanSmartWallet(ts: TokenState): ScanResult? {
        var score = 0.0
        val signals = mutableListOf<String>()
        
        // Use WhaleDetector for smart money signals
        try {
            val whaleSignal = WhaleDetector.evaluate(ts.mint, ts)
            
            if (!whaleSignal.hasWhaleActivity && !whaleSignal.smartMoneyPresent) {
                return null  // No whale activity
            }
            
            if (whaleSignal.hasWhaleActivity) {
                score += 25.0
                signals.add("Whale buys: ${whaleSignal.whaleBuys}")
            }
            
            if (whaleSignal.smartMoneyPresent) {
                score += 30.0
                signals.add("Smart money")
            }
            
            // High velocity = clustered accumulation
            if (whaleSignal.velocityScore > 60) {
                score += 15.0
                signals.add("High velocity: ${whaleSignal.velocityScore.toInt()}")
            }
            
            // Lower concentration = more distributed (healthier)
            if (whaleSignal.concentration < 50) {
                score += 10.0
                signals.add("Distributed")
            }
            
            // Whale score from detection
            if (whaleSignal.whaleScore > 50) {
                score += 10.0
            }
        } catch (_: Exception) {
            return null
        }
        
        // Price not yet expanded
        val hist = ts.history.toList()
        if (hist.size >= 5) {
            val prices = hist.map { it.priceUsd }
            val low = prices.takeLast(10).minOrNull() ?: 0.0
            val current = prices.lastOrNull() ?: 0.0
            val expansion = if (low > 0) ((current - low) / low) * 100 else 0.0
            
            if (expansion < 80) {
                score += 10.0
                signals.add("Pre-expansion")
            } else if (expansion > 200) {
                score -= 20.0  // Already pumped
                signals.add("Already expanded")
            }
        }
        
        // Liquidity for size - FLUID
        val minLiq = getMinLiquidityThreshold()
        if (ts.lastLiquidityUsd > minLiq * 2) {
            score += 5.0
        } else if (ts.lastLiquidityUsd < minLiq) {
            return null  // Can't absorb whale size
        }
        
        // Minimum score - FLUID
        val minScore = getMinScoreThreshold()
        if (score < minScore) return null
        
        return ScanResult(
            mint = ts.mint,
            symbol = ts.symbol,
            scannerType = ScannerType.SMART_WALLET,
            score = score.coerceAtMost(100.0),
            signals = signals,
            suggestedMode = ModeRouter.TradeType.WHALE_ACCUMULATION,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // E. POST-GRADUATION SCANNER
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Finds tokens graduating from pump.fun to real trading.
     * 
     * Criteria:
     * - Successful graduation/migration event
     * - Liquidity jump after migration
     * - Volume remains active post-graduation
     * - No immediate distribution
     * - Trend structure survives
     */
    fun scanGraduation(ts: TokenState): ScanResult? {
        val hist = ts.history.toList()
        if (hist.size < 6) return null
        
        var score = 0.0
        val signals = mutableListOf<String>()
        
        // Check source for graduation indicators
        val source = ts.source.lowercase()
        val isGraduation = source.contains("pump") || source.contains("grad") || 
                           source.contains("migrat") || source.contains("bonding")
        
        if (isGraduation) {
            score += 25.0
            signals.add("Source: graduation")
        }
        
        // Liquidity jump (migration creates liquidity)
        val volumes = hist.map { it.vol }
        val recentVol = volumes.takeLast(3).average()
        val priorVol = volumes.dropLast(3).takeLast(4).average()
        
        if (priorVol > 0 && recentVol > priorVol * 1.5) {
            score += 20.0
            signals.add("Liq jumped ${(recentVol / priorVol).toInt()}x")
        }
        
        // Active volume post-event
        val lastCandle = hist.lastOrNull()
        if (lastCandle != null && lastCandle.vol > 8000) {
            score += 15.0
            signals.add("Active post-grad")
        }
        
        // Structure surviving (no immediate collapse)
        val prices = hist.map { it.priceUsd }
        val recentHigh = prices.takeLast(6).maxOrNull() ?: 0.0
        val current = prices.lastOrNull() ?: 0.0
        
        if (recentHigh > 0 && current > recentHigh * 0.65) {
            score += 15.0
            signals.add("Structure holding")
        }
        
        // No distribution pattern
        val buyRatios = hist.takeLast(4).map { it.buyRatio }
        val avgBuyRatio = buyRatios.average()
        if (avgBuyRatio > 0.45) {
            score += 10.0
            signals.add("No distribution")
        }
        
        // Holder growth
        if (lastCandle != null && lastCandle.holderCount > 100) {
            score += 10.0
            signals.add("Holders: ${lastCandle.holderCount}")
        }
        
        // Good liquidity floor - FLUID
        val minLiq = getMinLiquidityThreshold()
        if (ts.lastLiquidityUsd > minLiq * 2) {
            score += 5.0
        } else if (ts.lastLiquidityUsd < minLiq) {
            return null
        }
        
        // Minimum score - FLUID
        val minScore = getMinScoreThreshold()
        if (score < minScore) return null
        
        return ScanResult(
            mint = ts.mint,
            symbol = ts.symbol,
            scannerType = ScannerType.GRADUATION,
            score = score.coerceAtMost(100.0),
            signals = signals,
            suggestedMode = ModeRouter.TradeType.GRADUATION,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // F. LIQUIDITY REGIME SCANNER
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Finds tokens with improving liquidity slope.
     * 
     * Criteria:
     * - Liquidity trending up (not just price)
     * - Depth improving
     * - Spread quality
     * - Ranks tradeability
     */
    fun scanLiquidityRegime(ts: TokenState): ScanResult? {
        val hist = ts.history.toList()
        if (hist.size < 8) return null
        
        var score = 0.0
        val signals = mutableListOf<String>()
        
        // Current liquidity level - FLUID
        val currentLiq = ts.lastLiquidityUsd
        val minLiq = getMinLiquidityThreshold()
        if (currentLiq < minLiq) return null
        
        // Liquidity trend (using volume as proxy for depth changes)
        val volumes = hist.map { it.vol }
        val recentVol = volumes.takeLast(4).average()
        val priorVol = volumes.dropLast(4).takeLast(4).average()
        
        if (priorVol > 0) {
            val volTrend = (recentVol - priorVol) / priorVol
            if (volTrend > 0.2) {
                score += 25.0
                signals.add("Liq improving +${(volTrend * 100).toInt()}%")
            } else if (volTrend > 0) {
                score += 15.0
                signals.add("Liq stable")
            } else if (volTrend < -0.2) {
                return null  // Liquidity draining
            }
        }
        
        // Absolute liquidity quality
        when {
            currentLiq > 50000 -> {
                score += 30.0
                signals.add("High liq: $${(currentLiq / 1000).toInt()}K")
            }
            currentLiq > 20000 -> {
                score += 20.0
                signals.add("Good liq: $${(currentLiq / 1000).toInt()}K")
            }
            currentLiq > 10000 -> {
                score += 10.0
                signals.add("Med liq: $${(currentLiq / 1000).toInt()}K")
            }
        }
        
        // Spread quality (buy ratio stability indicates healthy market)
        val buyRatios = hist.takeLast(6).map { it.buyRatio }
        val avgRatio = buyRatios.average()
        val ratioStdDev = buyRatios.standardDeviation()
        
        if (avgRatio in 0.45..0.55 && ratioStdDev < 0.1) {
            score += 15.0
            signals.add("Balanced order flow")
        }
        
        // Transaction activity (not dead)
        val lastCandle = hist.lastOrNull()
        if (lastCandle != null) {
            val txCount = lastCandle.buysH1 + lastCandle.sellsH1
            if (txCount > 50) {
                score += 10.0
                signals.add("Active: $txCount txns/hr")
            }
        }
        
        // Holder base
        if (lastCandle != null && lastCandle.holderCount > 150) {
            score += 10.0
        }
        
        // Minimum score - FLUID
        val minScore = getMinScoreThreshold()
        if (score < minScore) return null
        
        return ScanResult(
            mint = ts.mint,
            symbol = ts.symbol,
            scannerType = ScannerType.LIQUIDITY,
            score = score.coerceAtMost(100.0),
            signals = signals,
            suggestedMode = ModeRouter.TradeType.TREND_PULLBACK,  // Best for liquid tokens
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // UNIFIED SCAN
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Run all scanners on a token and return best match.
     */
    fun scanAll(ts: TokenState): ScanResult? {
        val results = listOfNotNull(
            scanFreshLaunch(ts),
            scanBreakout(ts),
            scanReversal(ts),
            scanSmartWallet(ts),
            scanGraduation(ts),
            scanLiquidityRegime(ts),
        )
        
        if (results.isEmpty()) return null
        
        // Return highest score
        val best = results.maxByOrNull { it.score } ?: return null
        
        // Cache result
        recentResults[ts.mint] = best
        
        // Log if significant
        if (best.score > 60) {
            ErrorLogger.info(TAG, "${best.scannerType.emoji} ${ts.symbol}: " +
                "${best.scannerType.label} (${best.score.toInt()}) | " +
                best.signals.take(3).joinToString(", "))
        }
        
        return best
    }
    
    /**
     * Get cached result if recent.
     */
    fun getCached(mint: String): ScanResult? {
        val result = recentResults[mint] ?: return null
        return if (System.currentTimeMillis() - result.timestamp < CACHE_TTL_MS) {
            result
        } else {
            recentResults.remove(mint)
            null
        }
    }
    
    /**
     * Clear cache (call periodically).
     */
    fun clearExpired() {
        val now = System.currentTimeMillis()
        recentResults.entries.removeIf { now - it.value.timestamp > CACHE_TTL_MS }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════════
    
    private fun List<Double>.standardDeviation(): Double {
        if (this.isEmpty()) return 0.0
        val mean = this.average()
        val variance = this.map { (it - mean) * (it - mean) }.average()
        return kotlin.math.sqrt(variance)
    }
}

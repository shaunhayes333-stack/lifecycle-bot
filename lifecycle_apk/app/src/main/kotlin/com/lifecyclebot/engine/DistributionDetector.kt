package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState

/**
 * DistributionDetector — TRUE Exit Trigger
 * ═══════════════════════════════════════════════════════════════════════
 * 
 * Detects when bots/whales are UNLOADING (distribution phase):
 * 
 * DISTRIBUTION SIGNALS:
 * 1. Exit score RISING FAST (not just high, but accelerating)
 * 2. Volume STILL HIGH (they're selling into liquidity)
 * 3. Price STOPS CLIMBING (absorption)
 * 
 * This = bots unloading = REAL sell signal
 */
object DistributionDetector {

    // Track exit score history per token for rate-of-change detection
    private val exitScoreHistory = java.util.concurrent.ConcurrentHashMap<String, MutableList<Pair<Long, Double>>>()
    private const val HISTORY_SIZE = 10
    
    data class DistributionSignal(
        val isDistributing: Boolean,
        val confidence: Int,           // 0-100
        val reason: String,
        val details: String,
    )
    
    /**
     * Detect distribution phase - THE money signal
     * 
     * @return DistributionSignal with confidence level
     */
    fun detect(
        mint: String,
        ts: TokenState,
        currentExitScore: Double,
        history: List<Candle>,
    ): DistributionSignal {
        
        if (history.size < 5) {
            return DistributionSignal(false, 0, "INSUFFICIENT_DATA", "Need 5+ candles")
        }
        
        val now = System.currentTimeMillis()
        
        // ════════════════════════════════════════════════════════════════
        // 1. Track Exit Score RATE OF CHANGE (not just value)
        // ════════════════════════════════════════════════════════════════
        val scoreHistory = exitScoreHistory.getOrPut(mint) { mutableListOf() }
        scoreHistory.add(Pair(now, currentExitScore))
        
        // Keep only recent history
        while (scoreHistory.size > HISTORY_SIZE) {
            scoreHistory.removeAt(0)
        }
        
        // Calculate exit score velocity (rate of change)
        val exitScoreVelocity = if (scoreHistory.size >= 3) {
            val oldest = scoreHistory.first().second
            val newest = scoreHistory.last().second
            val timeDeltaSec = (scoreHistory.last().first - scoreHistory.first().first) / 1000.0
            if (timeDeltaSec > 0) (newest - oldest) / timeDeltaSec else 0.0
        } else 0.0
        
        val exitScoreRisingFast = exitScoreVelocity > 0.5  // Rising >0.5 points/sec
        
        // ════════════════════════════════════════════════════════════════
        // 2. Check Volume - Still HIGH while dumping
        // ════════════════════════════════════════════════════════════════
        val recentCandles = history.takeLast(3)
        val olderCandles = if (history.size >= 8) history.takeLast(8).take(3) else recentCandles
        
        val recentVol = recentCandles.sumOf { it.vol }
        val olderVol = olderCandles.sumOf { it.vol }
        
        val volumeStillHigh = recentVol >= olderVol * 0.7  // Volume not dropped more than 30%
        val volumeAbsolute = recentVol > 1000  // Minimum volume threshold
        
        // ════════════════════════════════════════════════════════════════
        // 3. Price STOPPED climbing (absorption)
        // ════════════════════════════════════════════════════════════════
        val recentPrices = recentCandles.map { it.priceUsd }
        val olderPrices = olderCandles.map { it.priceUsd }
        
        val recentPriceAvg = recentPrices.average()
        val olderPriceAvg = olderPrices.average()
        val priceChange = if (olderPriceAvg > 0) {
            ((recentPriceAvg - olderPriceAvg) / olderPriceAvg) * 100
        } else 0.0
        
        val priceStopped = priceChange < 2.0  // Less than 2% gain
        val priceDropping = priceChange < -1.0  // Actually dropping
        
        // ════════════════════════════════════════════════════════════════
        // 4. Additional Distribution Signals
        // ════════════════════════════════════════════════════════════════
        
        // Sell pressure increasing
        val recentSells = recentCandles.sumOf { it.sellsH1 }
        val recentBuys = recentCandles.sumOf { it.buysH1 }
        val sellPressure = if (recentBuys + recentSells > 0) {
            recentSells.toDouble() / (recentBuys + recentSells)
        } else 0.0
        val buyPressure = 1.0 - sellPressure
        val highSellPressure = sellPressure > 0.55  // More sells than buys
        
        // ════════════════════════════════════════════════════════════════
        // CRITICAL: HIGH BUY PRESSURE = NOT DISTRIBUTION
        // If buyers are still dominant (>55%), this is NOT distribution!
        // Distribution requires sellers to be in control.
        // ════════════════════════════════════════════════════════════════
        val buyersInControl = buyPressure >= 0.55  // Buyers still dominating
        val strongBuyers = buyPressure >= 0.65     // Very strong buyers
        
        // Price at or near recent high (distribution happens at tops)
        val recentHigh = history.takeLast(10).maxOfOrNull { it.priceUsd } ?: 0.0
        val nearHigh = recentHigh > 0 && recentPriceAvg >= recentHigh * 0.95
        
        // ════════════════════════════════════════════════════════════════
        // CALCULATE CONFIDENCE
        // ════════════════════════════════════════════════════════════════
        var confidence = 0
        val signals = mutableListOf<String>()
        
        // ════════════════════════════════════════════════════════════════
        // CRITICAL CHECK: If buyers are still in control, ABORT DETECTION
        // Distribution REQUIRES sellers to dominate. If buy% > 55%, 
        // this is NOT distribution - it's consolidation or continuation.
        // ════════════════════════════════════════════════════════════════
        if (strongBuyers) {
            // Very strong buyers (65%+) = DEFINITELY not distribution
            return DistributionSignal(
                isDistributing = false,
                confidence = 0,
                reason = "BUYERS_DOMINATING",
                details = "buy%=${(buyPressure*100).toInt()}% - NOT distribution"
            )
        }
        
        if (buyersInControl) {
            // Buyers still in control (55-65%) = unlikely distribution
            // Reduce confidence cap significantly
            signals.add("buy%=${(buyPressure*100).toInt()}%_CAUTION")
            confidence -= 20  // Start negative to raise threshold
        }
        
        if (exitScoreRisingFast) {
            confidence += 30
            signals.add("exitScore↑${exitScoreVelocity.toInt()}/s")
        }
        
        if (currentExitScore > 60) {
            confidence += 15
            signals.add("exitScore=${currentExitScore.toInt()}")
        }
        
        if (volumeStillHigh && volumeAbsolute) {
            confidence += 20
            signals.add("volHigh")
        }
        
        if (priceStopped) {
            confidence += 20
            signals.add("priceStalled")
        }
        
        if (priceDropping) {
            confidence += 15
            signals.add("priceDrop")
        }
        
        if (highSellPressure) {
            confidence += 15
            signals.add("sellPressure=${(sellPressure*100).toInt()}%")
        }
        
        if (nearHigh) {
            confidence += 10
            signals.add("nearHigh")
        }
        
        // ════════════════════════════════════════════════════════════════
        // DISTRIBUTION DETECTED?
        // Need 60% confidence AND sellers must have some presence
        // ════════════════════════════════════════════════════════════════
        val isDistributing = confidence >= 60 && !buyersInControl
        
        val reason = if (isDistributing) "DISTRIBUTION_DETECTED" else "NO_DISTRIBUTION"
        val details = signals.joinToString(" | ")
        
        if (isDistributing) {
            ErrorLogger.warn("Distribution", "🚨 ${ts.symbol}: DISTRIBUTION conf=$confidence% | $details")
        }
        
        return DistributionSignal(
            isDistributing = isDistributing,
            confidence = confidence,
            reason = reason,
            details = details,
        )
    }
    
    /**
     * Clear history for a token
     */
    fun clearHistory(mint: String) {
        exitScoreHistory.remove(mint)
    }
}

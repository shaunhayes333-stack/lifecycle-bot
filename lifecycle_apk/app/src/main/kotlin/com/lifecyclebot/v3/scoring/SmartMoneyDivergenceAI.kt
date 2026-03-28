package com.lifecyclebot.v3.scoring

import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.WhaleTrackerAI
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * V3.2 Smart Money Divergence AI
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Detects when smart money (whales) behavior diverges from price action:
 * 
 * DIVERGENCE TYPES:
 * - BULLISH_DIVERGENCE: Price dropping but whales accumulating → BUY setup
 * - BEARISH_DIVERGENCE: Price pumping but whales selling → SELL warning
 * - CONFIRMATION: Smart money aligns with price direction
 * - NEUTRAL: No clear divergence
 * 
 * SMART MONEY SIGNALS:
 * - Whale wallet activity (from WhaleTrackerAI)
 * - Large holder % changes (accumulation/distribution)
 * - Insider pattern detection (early buys before pumps)
 * 
 * CROSS-TALK INTEGRATION:
 * - WhaleTrackerAI: Primary data source for whale behavior
 * - MomentumPredictorAI: Price momentum vs whale activity
 * - OrderFlowImbalanceAI: Flow patterns to confirm divergence
 * 
 * KEY INSIGHT:
 * When whales and price disagree, trust the whales.
 * They have better information and deeper pockets.
 */
object SmartMoneyDivergenceAI {
    
    private const val TAG = "SmartMoneyAI"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class DivergenceType {
        STRONG_BULLISH,    // Whales heavily accumulating, price weak/down
        BULLISH,           // Moderate whale buying, price flat/down
        NEUTRAL,           // No clear divergence
        BEARISH,           // Moderate whale selling, price flat/up
        STRONG_BEARISH,    // Whales heavily selling, price pumping
        CONFIRMATION_BULL, // Whales buying, price rising (aligned)
        CONFIRMATION_BEAR  // Whales selling, price falling (aligned)
    }
    
    enum class SmartMoneyBehavior {
        HEAVY_ACCUMULATION,  // Strong buying by whales
        ACCUMULATION,        // Moderate buying
        NEUTRAL,             // No clear activity
        DISTRIBUTION,        // Moderate selling
        HEAVY_DISTRIBUTION,  // Strong selling by whales
        INSIDER_PATTERN      // Suspicious early activity
    }
    
    data class DivergenceSignal(
        val divergenceType: DivergenceType,
        val smartMoneyBehavior: SmartMoneyBehavior,
        val whaleBuyScore: Double,        // 0-100, whale buying intensity
        val whaleSellScore: Double,       // 0-100, whale selling intensity
        val priceDirection: Int,          // -1 = down, 0 = flat, 1 = up
        val divergenceStrength: Double,   // 0-100, how strong the divergence
        val insiderScore: Double,         // 0-100, insider pattern probability
        val entryBoost: Int,
        val exitUrgency: Double,
        val reason: String
    )
    
    // Per-token tracking
    private data class SmartMoneyHistory(
        val whaleBuyScores: ArrayDeque<Double> = ArrayDeque(30),
        val whaleSellScores: ArrayDeque<Double> = ArrayDeque(30),
        val priceChanges: ArrayDeque<Double> = ArrayDeque(30),
        var lastDivergence: DivergenceType = DivergenceType.NEUTRAL,
        var consecutiveDivergence: Int = 0,
        var lastUpdate: Long = 0
    )
    private val tokenSmartMoney = ConcurrentHashMap<String, SmartMoneyHistory>()
    
    // Stats
    private var totalAnalyses = 0
    private var bullishDivergences = 0
    private var bearishDivergences = 0
    private var insiderPatternsDetected = 0
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN ANALYSIS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Analyze smart money divergence for a token.
     */
    fun analyze(
        mint: String,
        symbol: String,
        recentPrices: List<Double>,
        whaleRecommendation: String? = null,  // From WhaleTrackerAI
        topHolderPctChange: Double = 0.0,     // Change in top holder %
        holderCountChange: Int = 0             // Change in holder count
    ): DivergenceSignal {
        totalAnalyses++
        
        val history = tokenSmartMoney.getOrPut(mint) { SmartMoneyHistory() }
        history.lastUpdate = System.currentTimeMillis()
        
        // Get whale signal if not provided
        val whaleSignal = whaleRecommendation ?: try {
            WhaleTrackerAI.getWhaleSignal(mint, symbol)?.recommendation ?: "NEUTRAL"
        } catch (e: Exception) { "NEUTRAL" }
        
        // Convert whale recommendation to scores
        val (whaleBuyScore, whaleSellScore) = parseWhaleSignal(whaleSignal)
        
        // Calculate price direction
        val priceChange = if (recentPrices.size >= 2 && recentPrices.first() > 0) {
            ((recentPrices.last() - recentPrices.first()) / recentPrices.first()) * 100.0
        } else 0.0
        
        val priceDirection = when {
            priceChange > 3 -> 1   // Up
            priceChange < -3 -> -1 // Down
            else -> 0              // Flat
        }
        
        // Update history
        history.whaleBuyScores.addLast(whaleBuyScore)
        history.whaleSellScores.addLast(whaleSellScore)
        history.priceChanges.addLast(priceChange)
        
        if (history.whaleBuyScores.size > 30) history.whaleBuyScores.removeFirst()
        if (history.whaleSellScores.size > 30) history.whaleSellScores.removeFirst()
        if (history.priceChanges.size > 30) history.priceChanges.removeFirst()
        
        // Detect smart money behavior
        val behavior = detectBehavior(whaleBuyScore, whaleSellScore, topHolderPctChange, holderCountChange)
        
        // Detect divergence
        val divergenceType = detectDivergence(behavior, priceDirection, whaleBuyScore, whaleSellScore)
        
        // Calculate divergence strength
        val divergenceStrength = calculateDivergenceStrength(
            whaleBuyScore, whaleSellScore, priceDirection, priceChange
        )
        
        // Detect insider patterns
        val insiderScore = detectInsiderPattern(history, behavior, priceChange)
        
        // Track consecutive divergences
        if (divergenceType in listOf(DivergenceType.BULLISH, DivergenceType.STRONG_BULLISH,
                                      DivergenceType.BEARISH, DivergenceType.STRONG_BEARISH)) {
            if (divergenceType == history.lastDivergence) {
                history.consecutiveDivergence++
            } else {
                history.consecutiveDivergence = 1
            }
        } else {
            history.consecutiveDivergence = 0
        }
        history.lastDivergence = divergenceType
        
        // Track stats
        when (divergenceType) {
            DivergenceType.BULLISH, DivergenceType.STRONG_BULLISH -> bullishDivergences++
            DivergenceType.BEARISH, DivergenceType.STRONG_BEARISH -> bearishDivergences++
            else -> {}
        }
        
        if (insiderScore > 60) insiderPatternsDetected++
        
        // Calculate signals
        val entryBoost = calculateEntryBoost(divergenceType, divergenceStrength, insiderScore)
        val exitUrgency = calculateExitUrgency(divergenceType, divergenceStrength)
        
        val reason = buildReason(divergenceType, behavior, divergenceStrength, insiderScore)
        
        if (divergenceType in listOf(DivergenceType.STRONG_BULLISH, DivergenceType.STRONG_BEARISH)) {
            ErrorLogger.info(TAG, "🐋 DIVERGENCE: $symbol | ${divergenceType.name} | whale=$whaleSignal price=$priceDirection")
        }
        
        return DivergenceSignal(
            divergenceType = divergenceType,
            smartMoneyBehavior = behavior,
            whaleBuyScore = whaleBuyScore,
            whaleSellScore = whaleSellScore,
            priceDirection = priceDirection,
            divergenceStrength = divergenceStrength,
            insiderScore = insiderScore,
            entryBoost = entryBoost,
            exitUrgency = exitUrgency,
            reason = reason
        )
    }
    
    /**
     * Quick check for divergence type.
     */
    fun getDivergence(mint: String): DivergenceType {
        return tokenSmartMoney[mint]?.lastDivergence ?: DivergenceType.NEUTRAL
    }
    
    /**
     * Check if strong bearish divergence (exit signal).
     */
    fun hasBearishDivergence(mint: String): Boolean {
        val div = tokenSmartMoney[mint]?.lastDivergence
        return div in listOf(DivergenceType.BEARISH, DivergenceType.STRONG_BEARISH)
    }
    
    /**
     * Check if strong bullish divergence (entry signal).
     */
    fun hasBullishDivergence(mint: String): Boolean {
        val div = tokenSmartMoney[mint]?.lastDivergence
        return div in listOf(DivergenceType.BULLISH, DivergenceType.STRONG_BULLISH)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CALCULATIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun parseWhaleSignal(recommendation: String): Pair<Double, Double> {
        return when (recommendation.uppercase()) {
            "STRONG_BUY" -> Pair(90.0, 10.0)
            "BUY" -> Pair(70.0, 20.0)
            "LEAN_BUY" -> Pair(60.0, 30.0)
            "NEUTRAL", "HOLD" -> Pair(40.0, 40.0)
            "LEAN_SELL" -> Pair(30.0, 60.0)
            "SELL" -> Pair(20.0, 70.0)
            "STRONG_SELL" -> Pair(10.0, 90.0)
            else -> Pair(40.0, 40.0)
        }
    }
    
    private fun detectBehavior(
        buyScore: Double,
        sellScore: Double,
        holderPctChange: Double,
        holderCountChange: Int
    ): SmartMoneyBehavior {
        val netScore = buyScore - sellScore
        
        // Adjust for holder changes
        val adjustedNet = netScore + (holderPctChange * -5) + (holderCountChange * 0.1)
        
        return when {
            adjustedNet > 50 -> SmartMoneyBehavior.HEAVY_ACCUMULATION
            adjustedNet > 20 -> SmartMoneyBehavior.ACCUMULATION
            adjustedNet < -50 -> SmartMoneyBehavior.HEAVY_DISTRIBUTION
            adjustedNet < -20 -> SmartMoneyBehavior.DISTRIBUTION
            else -> SmartMoneyBehavior.NEUTRAL
        }
    }
    
    private fun detectDivergence(
        behavior: SmartMoneyBehavior,
        priceDirection: Int,
        buyScore: Double,
        sellScore: Double
    ): DivergenceType {
        // Strong bullish divergence: whales buying heavily, price down
        if (behavior == SmartMoneyBehavior.HEAVY_ACCUMULATION && priceDirection == -1) {
            return DivergenceType.STRONG_BULLISH
        }
        
        // Bullish divergence: whales buying, price flat/down
        if (behavior == SmartMoneyBehavior.ACCUMULATION && priceDirection <= 0) {
            return DivergenceType.BULLISH
        }
        
        // Strong bearish divergence: whales selling heavily, price up
        if (behavior == SmartMoneyBehavior.HEAVY_DISTRIBUTION && priceDirection == 1) {
            return DivergenceType.STRONG_BEARISH
        }
        
        // Bearish divergence: whales selling, price flat/up
        if (behavior == SmartMoneyBehavior.DISTRIBUTION && priceDirection >= 0) {
            return DivergenceType.BEARISH
        }
        
        // Confirmation patterns (aligned)
        if (behavior == SmartMoneyBehavior.ACCUMULATION && priceDirection == 1) {
            return DivergenceType.CONFIRMATION_BULL
        }
        
        if (behavior == SmartMoneyBehavior.DISTRIBUTION && priceDirection == -1) {
            return DivergenceType.CONFIRMATION_BEAR
        }
        
        return DivergenceType.NEUTRAL
    }
    
    private fun calculateDivergenceStrength(
        buyScore: Double,
        sellScore: Double,
        priceDirection: Int,
        priceChangePct: Double
    ): Double {
        // Strength based on how opposite whale activity is from price
        val netWhale = buyScore - sellScore  // Positive = buying, negative = selling
        
        // Price direction converted to -100 to +100
        val priceSignal = priceChangePct.coerceIn(-20.0, 20.0) * 5  // -100 to +100
        
        // Divergence = opposite signs and high magnitudes
        if ((netWhale > 0 && priceSignal < 0) || (netWhale < 0 && priceSignal > 0)) {
            // Diverging! Strength based on how far apart
            val distance = kotlin.math.abs(netWhale) + kotlin.math.abs(priceSignal)
            return (distance / 2.0).coerceIn(0.0, 100.0)
        }
        
        return 0.0  // Not diverging
    }
    
    private fun detectInsiderPattern(
        history: SmartMoneyHistory,
        behavior: SmartMoneyBehavior,
        currentPriceChange: Double
    ): Double {
        // Insider pattern: Whale activity BEFORE price moves
        // Look for: whale buying → price pump sequence
        
        val recentBuys = history.whaleBuyScores.toList().takeLast(5)
        val recentPrices = history.priceChanges.toList().takeLast(5)
        
        if (recentBuys.size < 4) return 0.0
        
        // Check if early buying was followed by price pump
        val earlyBuying = recentBuys.take(3).average()
        val latePriceMove = recentPrices.takeLast(2).average()
        
        if (earlyBuying > 60 && latePriceMove > 5) {
            // Whales bought before pump - insider-like pattern
            return ((earlyBuying - 40) + (latePriceMove * 2)).coerceIn(0.0, 100.0)
        }
        
        return 0.0
    }
    
    private fun calculateEntryBoost(
        divergence: DivergenceType,
        strength: Double,
        insiderScore: Double
    ): Int {
        var boost = 0
        
        boost += when (divergence) {
            DivergenceType.STRONG_BULLISH -> 12
            DivergenceType.BULLISH -> 7
            DivergenceType.CONFIRMATION_BULL -> 5
            DivergenceType.NEUTRAL -> 0
            DivergenceType.CONFIRMATION_BEAR -> -3
            DivergenceType.BEARISH -> -8
            DivergenceType.STRONG_BEARISH -> -15
        }
        
        // Strength modifier
        boost = (boost * (0.5 + strength / 200.0)).toInt()
        
        // Insider pattern bonus
        if (insiderScore > 50) {
            boost += (insiderScore / 20).toInt()
        }
        
        return boost.coerceIn(-20, 18)
    }
    
    private fun calculateExitUrgency(divergence: DivergenceType, strength: Double): Double {
        val baseUrgency = when (divergence) {
            DivergenceType.STRONG_BEARISH -> 50.0
            DivergenceType.BEARISH -> 30.0
            DivergenceType.CONFIRMATION_BEAR -> 15.0
            else -> 0.0
        }
        
        return (baseUrgency * (0.5 + strength / 200.0)).coerceIn(0.0, 80.0)
    }
    
    private fun buildReason(
        divergence: DivergenceType,
        behavior: SmartMoneyBehavior,
        strength: Double,
        insiderScore: Double
    ): String {
        val parts = mutableListOf<String>()
        
        parts += divergence.name.lowercase().replace("_", " ")
        parts += behavior.name.lowercase().replace("_", " ")
        
        if (strength > 30) {
            parts += "strength=${strength.toInt()}"
        }
        
        if (insiderScore > 40) {
            parts += "insider=${insiderScore.toInt()}"
        }
        
        return parts.joinToString(" | ")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCORING MODULE INTERFACE
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        @Suppress("UNCHECKED_CAST")
        val prices = candidate.extra["recentCloses"] as? List<Double> ?: emptyList()
        val whaleRec = candidate.extra["whaleRecommendation"] as? String
        val holderPctChange = candidate.extra["topHolderPctChange"] as? Double ?: 0.0
        val holderCountChange = candidate.extra["holderCountChange"] as? Int ?: 0
        
        val signal = analyze(
            candidate.mint,
            candidate.symbol,
            prices,
            whaleRec,
            holderPctChange,
            holderCountChange
        )
        
        return ScoreComponent(
            name = "smartmoney",
            value = signal.entryBoost,
            reason = signal.reason
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun saveToJson(): JSONObject {
        return JSONObject().apply {
            put("totalAnalyses", totalAnalyses)
            put("bullishDivergences", bullishDivergences)
            put("bearishDivergences", bearishDivergences)
            put("insiderPatternsDetected", insiderPatternsDetected)
        }
    }
    
    fun loadFromJson(json: JSONObject) {
        try {
            totalAnalyses = json.optInt("totalAnalyses", 0)
            bullishDivergences = json.optInt("bullishDivergences", 0)
            bearishDivergences = json.optInt("bearishDivergences", 0)
            insiderPatternsDetected = json.optInt("insiderPatternsDetected", 0)
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Load error: ${e.message}")
        }
    }
    
    fun getStats(): String {
        return "SmartMoneyAI: $totalAnalyses analyses | bull=$bullishDivergences bear=$bearishDivergences insider=$insiderPatternsDetected"
    }
    
    fun cleanup() {
        val now = System.currentTimeMillis()
        val staleThreshold = 30 * 60 * 1000L
        tokenSmartMoney.entries.removeIf { (_, v) -> now - v.lastUpdate > staleThreshold }
    }
}

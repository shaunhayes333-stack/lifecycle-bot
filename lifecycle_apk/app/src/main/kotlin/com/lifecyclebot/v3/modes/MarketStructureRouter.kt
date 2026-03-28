package com.lifecyclebot.v3.modes

import android.util.Log
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.data.Candle
import java.util.concurrent.ConcurrentHashMap

/**
 * MarketStructureRouter - Multi-Regime Trading Mode System
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * ARCHITECTURE EXPANSION:
 * 
 * The existing ModeRouter handles MEME MICROSTRUCTURE:
 *   - Fresh launches, shallow liquidity, short holds, rug risk, narrative bursts
 * 
 * This router handles DIFFERENT MARKET STRUCTURES:
 *   - MAJORS: SOL, ETH, BTC - deep liquidity, slow moves, trend following
 *   - MID_CAPS: Established tokens - moderate liquidity, technical setups
 *   - PERPS_STYLE: Futures-like - leverage dynamics, funding rates
 *   - CEX_ORDERBOOK: Order book style - level 2 depth, market making
 *   - MEAN_REVERSION: Forex-like - range bound, Bollinger bands, RSI extremes
 *   - TREND_REGIME: Equities-style - sector rotation, momentum factors
 * 
 * Each regime has FUNDAMENTALLY DIFFERENT:
 *   - Entry signals
 *   - Exit strategies
 *   - Position sizing
 *   - Hold times
 *   - Risk management
 *   - Indicator weighting
 *   - AI layer priorities
 */
object MarketStructureRouter {
    
    private const val TAG = "MarketStructureRouter"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MARKET REGIME CLASSIFICATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Primary market regime - determines overall strategy approach
     */
    enum class MarketRegime(
        val label: String,
        val emoji: String,
        val description: String,
    ) {
        MEME_MICRO("Meme Micro", "🎰", "Fresh launches, shallow liq, narrative velocity"),
        MAJORS("Majors", "🏛️", "SOL/ETH/BTC - deep liq, trend following"),
        MID_CAPS("Mid Caps", "📊", "Established tokens - technical setups"),
        PERPS_STYLE("Perps Style", "📈", "Leverage dynamics, funding arbitrage"),
        CEX_ORDERBOOK("CEX Orderbook", "📚", "Level 2 depth, liquidity provision"),
        MEAN_REVERSION("Mean Reversion", "⚖️", "Range bound, oversold/overbought extremes"),
        TREND_REGIME("Trend Regime", "📉", "Sector rotation, momentum factors"),
    }
    
    /**
     * Trading mode within each regime
     */
    enum class StructureMode(
        val regime: MarketRegime,
        val emoji: String,
        val label: String,
        
        // Position parameters
        val maxSizePct: Double,      // Max % of bankroll
        val defaultStopPct: Double,  // Stop loss %
        val defaultTpPct: Double,    // First take profit %
        val trailingStopPct: Double, // Trailing stop % (0 = disabled)
        val maxHoldMins: Int,        // Maximum hold time
        
        // Risk parameters
        val riskTier: Int,           // 1=safest, 5=riskiest
        val minLiquidityUsd: Double, // Minimum liquidity required
        val maxSlippagePct: Double,  // Max allowed slippage
        
        // Indicator weights (which AI layers matter most)
        val momentumWeight: Double,
        val volumeWeight: Double,
        val whaleWeight: Double,
        val narrativeWeight: Double,
        val technicalWeight: Double,
        val regimeWeight: Double,
    ) {
        // ═══════════════════════════════════════════════════════════════════
        // MEME MICRO MODES (existing, optimized for shallow liquidity)
        // ═══════════════════════════════════════════════════════════════════
        
        FRESH_LAUNCH(
            regime = MarketRegime.MEME_MICRO,
            emoji = "🚀", label = "Fresh Launch",
            maxSizePct = 2.0, defaultStopPct = 25.0, defaultTpPct = 50.0, trailingStopPct = 0.0,
            maxHoldMins = 15, riskTier = 5, minLiquidityUsd = 2000.0, maxSlippagePct = 5.0,
            momentumWeight = 1.5, volumeWeight = 1.2, whaleWeight = 0.8, narrativeWeight = 1.3,
            technicalWeight = 0.5, regimeWeight = 0.5,
        ),
        
        MEME_BREAKOUT(
            regime = MarketRegime.MEME_MICRO,
            emoji = "💥", label = "Meme Breakout",
            maxSizePct = 3.0, defaultStopPct = 20.0, defaultTpPct = 40.0, trailingStopPct = 15.0,
            maxHoldMins = 30, riskTier = 4, minLiquidityUsd = 5000.0, maxSlippagePct = 3.0,
            momentumWeight = 1.8, volumeWeight = 1.5, whaleWeight = 1.0, narrativeWeight = 1.2,
            technicalWeight = 0.7, regimeWeight = 0.6,
        ),
        
        NARRATIVE_BURST(
            regime = MarketRegime.MEME_MICRO,
            emoji = "🔥", label = "Narrative Burst",
            maxSizePct = 2.5, defaultStopPct = 22.0, defaultTpPct = 45.0, trailingStopPct = 0.0,
            maxHoldMins = 45, riskTier = 4, minLiquidityUsd = 3000.0, maxSlippagePct = 4.0,
            momentumWeight = 1.2, volumeWeight = 1.0, whaleWeight = 0.7, narrativeWeight = 2.0,
            technicalWeight = 0.5, regimeWeight = 0.5,
        ),
        
        // ═══════════════════════════════════════════════════════════════════
        // MAJORS MODES (SOL, ETH, BTC - deep liquidity, trend following)
        // ═══════════════════════════════════════════════════════════════════
        
        MAJOR_TREND_FOLLOW(
            regime = MarketRegime.MAJORS,
            emoji = "🏛️", label = "Major Trend Follow",
            maxSizePct = 15.0, defaultStopPct = 5.0, defaultTpPct = 15.0, trailingStopPct = 3.0,
            maxHoldMins = 1440, riskTier = 1, minLiquidityUsd = 1_000_000.0, maxSlippagePct = 0.1,
            momentumWeight = 1.5, volumeWeight = 0.8, whaleWeight = 1.2, narrativeWeight = 0.3,
            technicalWeight = 1.8, regimeWeight = 1.5,
        ),
        
        MAJOR_BREAKOUT(
            regime = MarketRegime.MAJORS,
            emoji = "📊", label = "Major Breakout",
            maxSizePct = 12.0, defaultStopPct = 4.0, defaultTpPct = 12.0, trailingStopPct = 2.5,
            maxHoldMins = 720, riskTier = 2, minLiquidityUsd = 500_000.0, maxSlippagePct = 0.15,
            momentumWeight = 1.8, volumeWeight = 1.2, whaleWeight = 1.0, narrativeWeight = 0.4,
            technicalWeight = 1.6, regimeWeight = 1.3,
        ),
        
        MAJOR_DIP_BUY(
            regime = MarketRegime.MAJORS,
            emoji = "🛒", label = "Major Dip Buy",
            maxSizePct = 10.0, defaultStopPct = 6.0, defaultTpPct = 10.0, trailingStopPct = 0.0,
            maxHoldMins = 2880, riskTier = 2, minLiquidityUsd = 1_000_000.0, maxSlippagePct = 0.1,
            momentumWeight = 0.8, volumeWeight = 1.0, whaleWeight = 1.5, narrativeWeight = 0.5,
            technicalWeight = 1.8, regimeWeight = 1.5,
        ),
        
        // ═══════════════════════════════════════════════════════════════════
        // MID CAP MODES (Established tokens - moderate liquidity)
        // ═══════════════════════════════════════════════════════════════════
        
        MIDCAP_MOMENTUM(
            regime = MarketRegime.MID_CAPS,
            emoji = "📈", label = "MidCap Momentum",
            maxSizePct = 8.0, defaultStopPct = 10.0, defaultTpPct = 25.0, trailingStopPct = 8.0,
            maxHoldMins = 480, riskTier = 2, minLiquidityUsd = 100_000.0, maxSlippagePct = 0.5,
            momentumWeight = 1.6, volumeWeight = 1.3, whaleWeight = 1.2, narrativeWeight = 0.8,
            technicalWeight = 1.4, regimeWeight = 1.2,
        ),
        
        MIDCAP_ACCUMULATION(
            regime = MarketRegime.MID_CAPS,
            emoji = "🐋", label = "MidCap Accumulation",
            maxSizePct = 7.0, defaultStopPct = 12.0, defaultTpPct = 30.0, trailingStopPct = 10.0,
            maxHoldMins = 720, riskTier = 2, minLiquidityUsd = 75_000.0, maxSlippagePct = 0.6,
            momentumWeight = 0.9, volumeWeight = 1.4, whaleWeight = 1.8, narrativeWeight = 0.6,
            technicalWeight = 1.2, regimeWeight = 1.0,
        ),
        
        MIDCAP_TECHNICAL_SETUP(
            regime = MarketRegime.MID_CAPS,
            emoji = "📐", label = "MidCap Technical",
            maxSizePct = 6.0, defaultStopPct = 8.0, defaultTpPct = 20.0, trailingStopPct = 5.0,
            maxHoldMins = 360, riskTier = 2, minLiquidityUsd = 100_000.0, maxSlippagePct = 0.4,
            momentumWeight = 1.2, volumeWeight = 1.0, whaleWeight = 0.8, narrativeWeight = 0.4,
            technicalWeight = 2.0, regimeWeight = 1.3,
        ),
        
        // ═══════════════════════════════════════════════════════════════════
        // PERPS STYLE MODES (Leverage dynamics, funding)
        // ═══════════════════════════════════════════════════════════════════
        
        PERP_TREND_TRADE(
            regime = MarketRegime.PERPS_STYLE,
            emoji = "📉", label = "Perp Trend Trade",
            maxSizePct = 5.0, defaultStopPct = 3.0, defaultTpPct = 10.0, trailingStopPct = 2.0,
            maxHoldMins = 240, riskTier = 3, minLiquidityUsd = 500_000.0, maxSlippagePct = 0.2,
            momentumWeight = 1.7, volumeWeight = 1.5, whaleWeight = 0.9, narrativeWeight = 0.3,
            technicalWeight = 1.8, regimeWeight = 1.6,
        ),
        
        PERP_FUNDING_ARB(
            regime = MarketRegime.PERPS_STYLE,
            emoji = "💹", label = "Funding Arbitrage",
            maxSizePct = 10.0, defaultStopPct = 2.0, defaultTpPct = 5.0, trailingStopPct = 0.0,
            maxHoldMins = 480, riskTier = 2, minLiquidityUsd = 1_000_000.0, maxSlippagePct = 0.1,
            momentumWeight = 0.5, volumeWeight = 0.8, whaleWeight = 0.6, narrativeWeight = 0.2,
            technicalWeight = 1.0, regimeWeight = 2.0,  // Regime (funding) is key
        ),
        
        PERP_SQUEEZE_HUNTER(
            regime = MarketRegime.PERPS_STYLE,
            emoji = "🎯", label = "Squeeze Hunter",
            maxSizePct = 4.0, defaultStopPct = 5.0, defaultTpPct = 20.0, trailingStopPct = 8.0,
            maxHoldMins = 120, riskTier = 4, minLiquidityUsd = 300_000.0, maxSlippagePct = 0.3,
            momentumWeight = 2.0, volumeWeight = 1.8, whaleWeight = 1.2, narrativeWeight = 0.5,
            technicalWeight = 1.5, regimeWeight = 1.4,
        ),
        
        // ═══════════════════════════════════════════════════════════════════
        // CEX ORDERBOOK MODES (Level 2 depth, market making style)
        // ═══════════════════════════════════════════════════════════════════
        
        CEX_DEPTH_TRADE(
            regime = MarketRegime.CEX_ORDERBOOK,
            emoji = "📚", label = "Depth Trade",
            maxSizePct = 8.0, defaultStopPct = 2.0, defaultTpPct = 4.0, trailingStopPct = 0.0,
            maxHoldMins = 60, riskTier = 2, minLiquidityUsd = 500_000.0, maxSlippagePct = 0.1,
            momentumWeight = 0.8, volumeWeight = 2.0, whaleWeight = 1.5, narrativeWeight = 0.2,
            technicalWeight = 1.2, regimeWeight = 0.8,
        ),
        
        CEX_SUPPORT_SNIPE(
            regime = MarketRegime.CEX_ORDERBOOK,
            emoji = "🎯", label = "Support Snipe",
            maxSizePct = 6.0, defaultStopPct = 1.5, defaultTpPct = 3.0, trailingStopPct = 0.0,
            maxHoldMins = 30, riskTier = 2, minLiquidityUsd = 300_000.0, maxSlippagePct = 0.15,
            momentumWeight = 0.6, volumeWeight = 1.8, whaleWeight = 1.2, narrativeWeight = 0.1,
            technicalWeight = 1.6, regimeWeight = 0.7,
        ),
        
        CEX_WALL_FADE(
            regime = MarketRegime.CEX_ORDERBOOK,
            emoji = "🧱", label = "Wall Fade",
            maxSizePct = 4.0, defaultStopPct = 2.5, defaultTpPct = 5.0, trailingStopPct = 0.0,
            maxHoldMins = 45, riskTier = 3, minLiquidityUsd = 200_000.0, maxSlippagePct = 0.2,
            momentumWeight = 0.9, volumeWeight = 2.0, whaleWeight = 0.8, narrativeWeight = 0.2,
            technicalWeight = 1.4, regimeWeight = 0.8,
        ),
        
        // ═══════════════════════════════════════════════════════════════════
        // MEAN REVERSION MODES (Forex-like, range bound)
        // ═══════════════════════════════════════════════════════════════════
        
        MEAN_REV_OVERSOLD(
            regime = MarketRegime.MEAN_REVERSION,
            emoji = "⬇️", label = "Oversold Bounce",
            maxSizePct = 6.0, defaultStopPct = 5.0, defaultTpPct = 8.0, trailingStopPct = 0.0,
            maxHoldMins = 180, riskTier = 2, minLiquidityUsd = 200_000.0, maxSlippagePct = 0.3,
            momentumWeight = 0.5, volumeWeight = 1.0, whaleWeight = 0.7, narrativeWeight = 0.3,
            technicalWeight = 2.0, regimeWeight = 1.5,  // RSI, Bollinger key
        ),
        
        MEAN_REV_OVERBOUGHT(
            regime = MarketRegime.MEAN_REVERSION,
            emoji = "⬆️", label = "Overbought Fade",
            maxSizePct = 5.0, defaultStopPct = 6.0, defaultTpPct = 10.0, trailingStopPct = 0.0,
            maxHoldMins = 180, riskTier = 3, minLiquidityUsd = 200_000.0, maxSlippagePct = 0.3,
            momentumWeight = 0.6, volumeWeight = 0.9, whaleWeight = 0.8, narrativeWeight = 0.4,
            technicalWeight = 2.0, regimeWeight = 1.5,
        ),
        
        MEAN_REV_RANGE_PLAY(
            regime = MarketRegime.MEAN_REVERSION,
            emoji = "↔️", label = "Range Play",
            maxSizePct = 7.0, defaultStopPct = 4.0, defaultTpPct = 6.0, trailingStopPct = 0.0,
            maxHoldMins = 120, riskTier = 2, minLiquidityUsd = 150_000.0, maxSlippagePct = 0.25,
            momentumWeight = 0.4, volumeWeight = 1.1, whaleWeight = 0.6, narrativeWeight = 0.2,
            technicalWeight = 2.2, regimeWeight = 1.6,
        ),
        
        // ═══════════════════════════════════════════════════════════════════
        // TREND REGIME MODES (Equities-style, sector rotation)
        // ═══════════════════════════════════════════════════════════════════
        
        TREND_MOMENTUM_FACTOR(
            regime = MarketRegime.TREND_REGIME,
            emoji = "📊", label = "Momentum Factor",
            maxSizePct = 10.0, defaultStopPct = 8.0, defaultTpPct = 20.0, trailingStopPct = 6.0,
            maxHoldMins = 1440, riskTier = 2, minLiquidityUsd = 100_000.0, maxSlippagePct = 0.4,
            momentumWeight = 2.0, volumeWeight = 1.2, whaleWeight = 1.0, narrativeWeight = 0.6,
            technicalWeight = 1.5, regimeWeight = 1.8,
        ),
        
        TREND_SECTOR_ROTATION(
            regime = MarketRegime.TREND_REGIME,
            emoji = "🔄", label = "Sector Rotation",
            maxSizePct = 8.0, defaultStopPct = 10.0, defaultTpPct = 25.0, trailingStopPct = 8.0,
            maxHoldMins = 2880, riskTier = 2, minLiquidityUsd = 150_000.0, maxSlippagePct = 0.5,
            momentumWeight = 1.5, volumeWeight = 1.0, whaleWeight = 1.3, narrativeWeight = 1.2,
            technicalWeight = 1.4, regimeWeight = 2.0,  // Regime/sector is key
        ),
        
        TREND_RELATIVE_STRENGTH(
            regime = MarketRegime.TREND_REGIME,
            emoji = "💪", label = "Relative Strength",
            maxSizePct = 9.0, defaultStopPct = 7.0, defaultTpPct = 18.0, trailingStopPct = 5.0,
            maxHoldMins = 1080, riskTier = 2, minLiquidityUsd = 120_000.0, maxSlippagePct = 0.35,
            momentumWeight = 1.8, volumeWeight = 1.1, whaleWeight = 1.1, narrativeWeight = 0.5,
            technicalWeight = 1.7, regimeWeight = 1.6,
        ),
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CLASSIFICATION RESULT
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class StructureClassification(
        val regime: MarketRegime,
        val mode: StructureMode,
        val confidence: Double,        // 0-100
        val signals: List<String>,     // Why this classification
        val alternativeModes: List<Pair<StructureMode, Double>>,  // Other candidates
        val aiWeights: Map<String, Double>,  // Adjusted AI layer weights
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MARKET STRUCTURE DETECTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Known major tokens (for quick classification)
    private val MAJOR_TOKENS = setOf(
        "So11111111111111111111111111111111111111112",  // SOL
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", // USDT
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", // USDC
        "7vfCXTUXx5WJV5JADk17DUJ4ksgau7utNKj4b963voxs", // ETH (Wormhole)
        "3NZ9JMVBmGAqocybic2c7LQCJScmgsAZ6vQqTDzcqmJh", // BTC (Wormhole)
    )
    
    // Known mid-cap ranges
    private const val MID_CAP_MIN_LIQ = 50_000.0
    private const val MID_CAP_MAX_LIQ = 5_000_000.0
    private const val MAJOR_MIN_LIQ = 5_000_000.0
    
    // Cache for expensive lookups
    private val classificationCache = ConcurrentHashMap<String, Pair<StructureClassification, Long>>()
    private const val CACHE_TTL_MS = 30_000L  // 30 second cache
    
    /**
     * Classify a token into the appropriate market structure and mode.
     */
    fun classify(ts: TokenState): StructureClassification {
        // Check cache first
        val cached = classificationCache[ts.mint]
        if (cached != null && System.currentTimeMillis() - cached.second < CACHE_TTL_MS) {
            return cached.first
        }
        
        val signals = mutableListOf<String>()
        val modeScores = mutableMapOf<StructureMode, Double>()
        StructureMode.values().forEach { modeScores[it] = 0.0 }
        
        val hist = ts.history.toList()
        val liquidity = ts.lastLiquidityUsd
        
        // ─────────────────────────────────────────────────────────────────────
        // STEP 1: DETERMINE PRIMARY REGIME
        // ─────────────────────────────────────────────────────────────────────
        
        val regime = detectRegime(ts, hist, liquidity, signals)
        
        // ─────────────────────────────────────────────────────────────────────
        // STEP 2: SCORE MODES WITHIN REGIME
        // ─────────────────────────────────────────────────────────────────────
        
        when (regime) {
            MarketRegime.MEME_MICRO -> scoreMemeModse(ts, hist, modeScores, signals)
            MarketRegime.MAJORS -> scoreMajorModes(ts, hist, modeScores, signals)
            MarketRegime.MID_CAPS -> scoreMidCapModes(ts, hist, modeScores, signals)
            MarketRegime.PERPS_STYLE -> scorePerpModes(ts, hist, modeScores, signals)
            MarketRegime.CEX_ORDERBOOK -> scoreCexModes(ts, hist, modeScores, signals)
            MarketRegime.MEAN_REVERSION -> scoreMeanRevModes(ts, hist, modeScores, signals)
            MarketRegime.TREND_REGIME -> scoreTrendModes(ts, hist, modeScores, signals)
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // STEP 3: PICK BEST MODE
        // ─────────────────────────────────────────────────────────────────────
        
        val modesInRegime = modeScores.filterKeys { it.regime == regime }
        val bestMode = modesInRegime.maxByOrNull { it.value }?.key 
            ?: StructureMode.MEME_BREAKOUT  // Default fallback
        val bestScore = modesInRegime[bestMode] ?: 0.0
        
        // Calculate confidence
        val sortedScores = modesInRegime.values.sortedDescending()
        val secondBest = if (sortedScores.size > 1) sortedScores[1] else 0.0
        val confidence = when {
            bestScore <= 20 -> 15.0
            bestScore - secondBest > 25 -> 85.0
            bestScore - secondBest > 15 -> 70.0
            bestScore - secondBest > 5 -> 55.0
            else -> 40.0
        }
        
        // Get alternative modes
        val alternatives = modesInRegime
            .filter { it.key != bestMode && it.value > 20 }
            .toList()
            .sortedByDescending { it.second }
            .take(3)
        
        // Build AI weight map
        val aiWeights = mapOf(
            "momentum" to bestMode.momentumWeight,
            "volume" to bestMode.volumeWeight,
            "whale" to bestMode.whaleWeight,
            "narrative" to bestMode.narrativeWeight,
            "technical" to bestMode.technicalWeight,
            "regime" to bestMode.regimeWeight,
        )
        
        val result = StructureClassification(
            regime = regime,
            mode = bestMode,
            confidence = confidence,
            signals = signals,
            alternativeModes = alternatives,
            aiWeights = aiWeights,
        )
        
        // Cache result
        classificationCache[ts.mint] = result to System.currentTimeMillis()
        
        return result
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // REGIME DETECTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun detectRegime(
        ts: TokenState, 
        hist: List<Candle>, 
        liquidity: Double,
        signals: MutableList<String>
    ): MarketRegime {
        
        // Check if it's a known major
        if (ts.mint in MAJOR_TOKENS) {
            signals.add("REGIME: known major token")
            return MarketRegime.MAJORS
        }
        
        // Very high liquidity = treat as major
        if (liquidity >= MAJOR_MIN_LIQ) {
            signals.add("REGIME: major liquidity $${(liquidity/1_000_000).toInt()}M")
            return MarketRegime.MAJORS
        }
        
        // Check token age and volatility for meme vs established
        val tokenAgeMins = if (hist.isNotEmpty()) {
            (System.currentTimeMillis() - hist.first().ts) / 60_000.0
        } else 0.0
        
        // Fresh token = meme micro
        if (tokenAgeMins < 60 && liquidity < MID_CAP_MIN_LIQ) {
            signals.add("REGIME: fresh meme (age=${tokenAgeMins.toInt()}min)")
            return MarketRegime.MEME_MICRO
        }
        
        // Calculate volatility
        val volatility = if (hist.size >= 10) {
            val prices = hist.takeLast(20).map { it.priceUsd }
            val returns = prices.zipWithNext { a, b -> if (a > 0) (b - a) / a else 0.0 }
            val stdDev = if (returns.isNotEmpty()) {
                val mean = returns.average()
                kotlin.math.sqrt(returns.map { (it - mean) * (it - mean) }.average())
            } else 0.0
            stdDev * 100
        } else 5.0
        
        // Very high volatility with low liquidity = meme
        if (volatility > 10 && liquidity < MID_CAP_MIN_LIQ) {
            signals.add("REGIME: high vol meme (vol=${volatility.toInt()}%)")
            return MarketRegime.MEME_MICRO
        }
        
        // Mid-cap range with established history
        if (liquidity in MID_CAP_MIN_LIQ..MID_CAP_MAX_LIQ && hist.size >= 30) {
            signals.add("REGIME: mid-cap established")
            return MarketRegime.MID_CAPS
        }
        
        // Check for range-bound behavior (mean reversion candidate)
        if (hist.size >= 20 && isRangeBound(hist)) {
            signals.add("REGIME: range-bound detected")
            return MarketRegime.MEAN_REVERSION
        }
        
        // Check for strong trend (trend regime candidate)
        if (hist.size >= 20 && hasStrongTrend(hist)) {
            signals.add("REGIME: trend detected")
            return MarketRegime.TREND_REGIME
        }
        
        // Default to meme micro for low liquidity
        if (liquidity < MID_CAP_MIN_LIQ) {
            signals.add("REGIME: low liq default meme")
            return MarketRegime.MEME_MICRO
        }
        
        // Default to mid-caps for moderate liquidity
        signals.add("REGIME: moderate liq default mid-cap")
        return MarketRegime.MID_CAPS
    }
    
    private fun isRangeBound(hist: List<Candle>): Boolean {
        val prices = hist.takeLast(20).map { it.priceUsd }
        if (prices.size < 10) return false
        
        val high = prices.maxOrNull() ?: return false
        val low = prices.minOrNull() ?: return false
        val current = prices.lastOrNull() ?: return false
        
        val range = if (low > 0) (high - low) / low * 100 else 0.0
        val posInRange = if (high > low) (current - low) / (high - low) else 0.5
        
        // Range-bound: total range < 30% and price oscillates
        return range in 5.0..30.0 && posInRange in 0.2..0.8
    }
    
    private fun hasStrongTrend(hist: List<Candle>): Boolean {
        val prices = hist.takeLast(20).map { it.priceUsd }
        if (prices.size < 10) return false
        
        // Calculate linear regression slope
        val n = prices.size
        val xMean = (n - 1) / 2.0
        val yMean = prices.average()
        
        var numerator = 0.0
        var denominator = 0.0
        for (i in prices.indices) {
            numerator += (i - xMean) * (prices[i] - yMean)
            denominator += (i - xMean) * (i - xMean)
        }
        
        val slope = if (denominator > 0) numerator / denominator else 0.0
        val normalizedSlope = if (yMean > 0) (slope / yMean) * 100 else 0.0
        
        // Strong trend: normalized slope > 2% per period
        return kotlin.math.abs(normalizedSlope) > 2.0
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MODE SCORING FUNCTIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun scoreMemeModse(
        ts: TokenState, 
        hist: List<Candle>, 
        scores: MutableMap<StructureMode, Double>,
        signals: MutableList<String>
    ) {
        val prices = hist.map { it.priceUsd }
        val tokenAgeMins = if (hist.isNotEmpty()) {
            (System.currentTimeMillis() - hist.first().ts) / 60_000.0
        } else 999.0
        
        // FRESH_LAUNCH
        if (tokenAgeMins <= 15) {
            scores[StructureMode.FRESH_LAUNCH] = scores[StructureMode.FRESH_LAUNCH]!! + 50.0
            signals.add("MEME: fresh launch ${tokenAgeMins.toInt()}min")
        }
        
        // MEME_BREAKOUT
        if (hist.size >= 8) {
            val recentHigh = prices.takeLast(10).maxOrNull() ?: 0.0
            val currentPrice = prices.lastOrNull() ?: 0.0
            if (recentHigh > 0 && currentPrice > recentHigh * 0.9) {
                scores[StructureMode.MEME_BREAKOUT] = scores[StructureMode.MEME_BREAKOUT]!! + 40.0
                signals.add("MEME: near high, breakout potential")
            }
        }
        
        // NARRATIVE_BURST
        val source = ts.source.lowercase()
        if (source.contains("boost") || source.contains("trend")) {
            scores[StructureMode.NARRATIVE_BURST] = scores[StructureMode.NARRATIVE_BURST]!! + 35.0
            signals.add("MEME: boosted/trending source")
        }
    }
    
    private fun scoreMajorModes(
        ts: TokenState, 
        hist: List<Candle>, 
        scores: MutableMap<StructureMode, Double>,
        signals: MutableList<String>
    ) {
        val prices = hist.map { it.priceUsd }
        if (prices.size < 10) return
        
        val ema8 = prices.takeLast(8).average()
        val ema21 = prices.takeLast(21).average()
        val currentPrice = prices.lastOrNull() ?: 0.0
        
        // MAJOR_TREND_FOLLOW
        if (ema8 > ema21 && currentPrice > ema8) {
            scores[StructureMode.MAJOR_TREND_FOLLOW] = scores[StructureMode.MAJOR_TREND_FOLLOW]!! + 45.0
            signals.add("MAJOR: bullish EMA structure")
        }
        
        // MAJOR_BREAKOUT
        val recentHigh = prices.takeLast(20).maxOrNull() ?: 0.0
        if (currentPrice > recentHigh * 0.98) {
            scores[StructureMode.MAJOR_BREAKOUT] = scores[StructureMode.MAJOR_BREAKOUT]!! + 40.0
            signals.add("MAJOR: near ATH breakout")
        }
        
        // MAJOR_DIP_BUY
        val recentLow = prices.takeLast(10).minOrNull() ?: 0.0
        if (currentPrice < ema21 * 0.95 && currentPrice > recentLow * 1.02) {
            scores[StructureMode.MAJOR_DIP_BUY] = scores[StructureMode.MAJOR_DIP_BUY]!! + 40.0
            signals.add("MAJOR: dip with support")
        }
    }
    
    private fun scoreMidCapModes(
        ts: TokenState, 
        hist: List<Candle>, 
        scores: MutableMap<StructureMode, Double>,
        signals: MutableList<String>
    ) {
        val prices = hist.map { it.priceUsd }
        if (prices.size < 15) return
        
        // MIDCAP_MOMENTUM
        val momentum = if (prices.size >= 5) {
            val recent = prices.takeLast(5).average()
            val prior = prices.dropLast(5).takeLast(5).average()
            if (prior > 0) (recent - prior) / prior * 100 else 0.0
        } else 0.0
        
        if (momentum > 10) {
            scores[StructureMode.MIDCAP_MOMENTUM] = scores[StructureMode.MIDCAP_MOMENTUM]!! + 45.0
            signals.add("MIDCAP: strong momentum +${momentum.toInt()}%")
        }
        
        // MIDCAP_ACCUMULATION (flat with volume)
        val priceRange = if (prices.isNotEmpty()) {
            val high = prices.maxOrNull() ?: 0.0
            val low = prices.minOrNull() ?: 0.0
            if (low > 0) (high - low) / low * 100 else 0.0
        } else 0.0
        
        if (priceRange < 15) {
            scores[StructureMode.MIDCAP_ACCUMULATION] = scores[StructureMode.MIDCAP_ACCUMULATION]!! + 35.0
            signals.add("MIDCAP: consolidation pattern")
        }
        
        // MIDCAP_TECHNICAL_SETUP
        val pullbackFromHigh = if (prices.isNotEmpty()) {
            val high = prices.maxOrNull() ?: 0.0
            val current = prices.lastOrNull() ?: 0.0
            if (high > 0) (high - current) / high * 100 else 0.0
        } else 0.0
        
        if (pullbackFromHigh in 10.0..25.0) {
            scores[StructureMode.MIDCAP_TECHNICAL_SETUP] = scores[StructureMode.MIDCAP_TECHNICAL_SETUP]!! + 40.0
            signals.add("MIDCAP: technical pullback -${pullbackFromHigh.toInt()}%")
        }
    }
    
    private fun scorePerpModes(
        ts: TokenState, 
        hist: List<Candle>, 
        scores: MutableMap<StructureMode, Double>,
        signals: MutableList<String>
    ) {
        // Simplified perp scoring - would need funding rate data in production
        val prices = hist.map { it.priceUsd }
        if (prices.size < 10) return
        
        val momentum = if (prices.size >= 5) {
            val recent = prices.takeLast(3).average()
            val prior = prices.dropLast(3).takeLast(3).average()
            if (prior > 0) (recent - prior) / prior * 100 else 0.0
        } else 0.0
        
        if (kotlin.math.abs(momentum) > 5) {
            scores[StructureMode.PERP_TREND_TRADE] = scores[StructureMode.PERP_TREND_TRADE]!! + 40.0
            signals.add("PERP: trend direction ${if(momentum>0) "LONG" else "SHORT"}")
        }
        
        // Look for squeeze setup (compression then expansion)
        val volatilityRecent = calculateVolatility(prices.takeLast(5))
        val volatilityPrior = calculateVolatility(prices.dropLast(5).takeLast(10))
        
        if (volatilityPrior > 0 && volatilityRecent > volatilityPrior * 1.5) {
            scores[StructureMode.PERP_SQUEEZE_HUNTER] = scores[StructureMode.PERP_SQUEEZE_HUNTER]!! + 45.0
            signals.add("PERP: vol expansion (squeeze)")
        }
    }
    
    private fun scoreCexModes(
        ts: TokenState, 
        hist: List<Candle>, 
        scores: MutableMap<StructureMode, Double>,
        signals: MutableList<String>
    ) {
        val prices = hist.map { it.priceUsd }
        if (prices.size < 10) return
        
        // CEX_SUPPORT_SNIPE - price at support with volume
        val support = prices.takeLast(20).minOrNull() ?: 0.0
        val current = prices.lastOrNull() ?: 0.0
        
        if (support > 0 && current < support * 1.03) {
            scores[StructureMode.CEX_SUPPORT_SNIPE] = scores[StructureMode.CEX_SUPPORT_SNIPE]!! + 45.0
            signals.add("CEX: at support level")
        }
        
        // CEX_DEPTH_TRADE - would need L2 data, using volume as proxy
        val recentVol = hist.takeLast(3).map { it.vol }.average()
        val avgVol = hist.takeLast(20).map { it.vol }.average()
        
        if (avgVol > 0 && recentVol > avgVol * 1.5) {
            scores[StructureMode.CEX_DEPTH_TRADE] = scores[StructureMode.CEX_DEPTH_TRADE]!! + 35.0
            signals.add("CEX: elevated volume")
        }
    }
    
    private fun scoreMeanRevModes(
        ts: TokenState, 
        hist: List<Candle>, 
        scores: MutableMap<StructureMode, Double>,
        signals: MutableList<String>
    ) {
        val prices = hist.map { it.priceUsd }
        if (prices.size < 15) return
        
        // Calculate Bollinger-like bands
        val sma = prices.takeLast(20).average()
        val stdDev = calculateVolatility(prices.takeLast(20)) * sma / 100
        val upperBand = sma + 2 * stdDev
        val lowerBand = sma - 2 * stdDev
        val current = prices.lastOrNull() ?: 0.0
        
        // MEAN_REV_OVERSOLD
        if (current < lowerBand) {
            scores[StructureMode.MEAN_REV_OVERSOLD] = scores[StructureMode.MEAN_REV_OVERSOLD]!! + 50.0
            signals.add("MEANREV: below lower band (oversold)")
        }
        
        // MEAN_REV_OVERBOUGHT
        if (current > upperBand) {
            scores[StructureMode.MEAN_REV_OVERBOUGHT] = scores[StructureMode.MEAN_REV_OVERBOUGHT]!! + 45.0
            signals.add("MEANREV: above upper band (overbought)")
        }
        
        // MEAN_REV_RANGE_PLAY
        if (current in lowerBand..upperBand) {
            val posInBand = if (upperBand > lowerBand) (current - lowerBand) / (upperBand - lowerBand) else 0.5
            if (posInBand in 0.3..0.7) {
                scores[StructureMode.MEAN_REV_RANGE_PLAY] = scores[StructureMode.MEAN_REV_RANGE_PLAY]!! + 35.0
                signals.add("MEANREV: mid-range position")
            }
        }
    }
    
    private fun scoreTrendModes(
        ts: TokenState, 
        hist: List<Candle>, 
        scores: MutableMap<StructureMode, Double>,
        signals: MutableList<String>
    ) {
        val prices = hist.map { it.priceUsd }
        if (prices.size < 20) return
        
        // Calculate trend strength
        val ema10 = prices.takeLast(10).average()
        val ema30 = prices.takeLast(30).average()
        val trendStrength = if (ema30 > 0) (ema10 - ema30) / ema30 * 100 else 0.0
        
        // TREND_MOMENTUM_FACTOR
        if (trendStrength > 10) {
            scores[StructureMode.TREND_MOMENTUM_FACTOR] = scores[StructureMode.TREND_MOMENTUM_FACTOR]!! + 45.0
            signals.add("TREND: strong momentum factor +${trendStrength.toInt()}%")
        }
        
        // TREND_RELATIVE_STRENGTH
        if (trendStrength > 5) {
            scores[StructureMode.TREND_RELATIVE_STRENGTH] = scores[StructureMode.TREND_RELATIVE_STRENGTH]!! + 40.0
            signals.add("TREND: positive relative strength")
        }
        
        // TREND_SECTOR_ROTATION would need cross-token data
        scores[StructureMode.TREND_SECTOR_ROTATION] = scores[StructureMode.TREND_SECTOR_ROTATION]!! + 25.0
    }
    
    private fun calculateVolatility(prices: List<Double>): Double {
        if (prices.size < 2) return 0.0
        val returns = prices.zipWithNext { a, b -> if (a > 0) (b - a) / a * 100 else 0.0 }
        val mean = returns.average()
        return kotlin.math.sqrt(returns.map { (it - mean) * (it - mean) }.average())
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // API FOR OTHER SYSTEMS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get AI layer weight multipliers for the given mode.
     */
    fun getAIWeights(mode: StructureMode): Map<String, Double> = mapOf(
        "momentum" to mode.momentumWeight,
        "volume" to mode.volumeWeight,
        "whale" to mode.whaleWeight,
        "narrative" to mode.narrativeWeight,
        "technical" to mode.technicalWeight,
        "regime" to mode.regimeWeight,
    )
    
    /**
     * Get position parameters for the given mode.
     */
    fun getPositionParams(mode: StructureMode): PositionParams = PositionParams(
        maxSizePct = mode.maxSizePct,
        stopLossPct = mode.defaultStopPct,
        takeProfitPct = mode.defaultTpPct,
        trailingStopPct = mode.trailingStopPct,
        maxHoldMins = mode.maxHoldMins,
        riskTier = mode.riskTier,
        minLiquidityUsd = mode.minLiquidityUsd,
        maxSlippagePct = mode.maxSlippagePct,
    )
    
    data class PositionParams(
        val maxSizePct: Double,
        val stopLossPct: Double,
        val takeProfitPct: Double,
        val trailingStopPct: Double,
        val maxHoldMins: Int,
        val riskTier: Int,
        val minLiquidityUsd: Double,
        val maxSlippagePct: Double,
    )
    
    /**
     * Check if a mode is suitable for a given liquidity level.
     */
    fun isSuitableForLiquidity(mode: StructureMode, liquidityUsd: Double): Boolean {
        return liquidityUsd >= mode.minLiquidityUsd
    }
    
    /**
     * Get status for logging/UI.
     */
    fun getStatus(): String {
        return "MarketStructureRouter: ${StructureMode.values().size} modes across ${MarketRegime.values().size} regimes"
    }
}

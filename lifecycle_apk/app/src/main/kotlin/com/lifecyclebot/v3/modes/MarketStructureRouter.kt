package com.lifecyclebot.v3.modes

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * MarketStructureRouter - Multi-Regime Trading Mode System
 *
 * Handles multiple market structures:
 * - MEME_MICRO: fresh launches, shallow liquidity, narrative bursts
 * - MAJORS: SOL/ETH/BTC style deep-liquidity trend trades
 * - MID_CAPS: established tokens with technical setups
 * - PERPS_STYLE: leverage / squeeze style logic
 * - CEX_ORDERBOOK: support/depth style logic
 * - MEAN_REVERSION: range-bound behavior
 * - TREND_REGIME: stronger directional trend behavior
 * - VOLATILITY_STRATEGIES: compression / expansion setups
 */
object MarketStructureRouter {

    private const val TAG = "MarketStructureRouter"

    // ═══════════════════════════════════════════════════════════════════════════
    // FLUID LEARNING
    // ═══════════════════════════════════════════════════════════════════════════

    private const val FLUID_SCALE_BOOTSTRAP = 0.20
    private const val FLUID_SCALE_MATURE = 1.0

    private fun getFluidScale(): Double {
        return try {
            val progress = com.lifecyclebot.engine.FinalDecisionGate.getLearningProgress(
                com.lifecyclebot.engine.FinalDecisionGate.getFluidConfidenceInfo().totalTradesLearned,
                50.0
            )
            lerp(FLUID_SCALE_BOOTSTRAP, FLUID_SCALE_MATURE, progress)
        } catch (_: Exception) {
            FLUID_SCALE_BOOTSTRAP
        }
    }

    private fun lerp(loose: Double, strict: Double, progress: Double): Double {
        return loose + (strict - loose) * progress.coerceIn(0.0, 1.0)
    }

    fun fluidThreshold(baseValue: Double): Double {
        return baseValue * getFluidScale()
    }

    fun fluidMin(baseMin: Double): Double {
        val scale = getFluidScale()
        return (baseMin * scale).coerceAtLeast(baseMin * 0.1)
    }

    fun fluidScoreBonus(baseBonus: Double): Double {
        val scale = getFluidScale()
        return baseBonus * (2.0 - scale)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REGIMES / MODES
    // ═══════════════════════════════════════════════════════════════════════════

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
        VOLATILITY_STRATEGIES("Volatility", "🎯", "Compression / expansion / vol plays"),
    }

    enum class StructureMode(
        val regime: MarketRegime,
        val emoji: String,
        val label: String,
        val maxSizePct: Double,
        val defaultStopPct: Double,
        val defaultTpPct: Double,
        val trailingStopPct: Double,
        val maxHoldMins: Int,
        val riskTier: Int,
        val minLiquidityUsd: Double,
        val maxSlippagePct: Double,
        val momentumWeight: Double,
        val volumeWeight: Double,
        val whaleWeight: Double,
        val narrativeWeight: Double,
        val technicalWeight: Double,
        val regimeWeight: Double,
    ) {
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
            technicalWeight = 1.0, regimeWeight = 2.0,
        ),

        PERP_SQUEEZE_HUNTER(
            regime = MarketRegime.PERPS_STYLE,
            emoji = "🎯", label = "Squeeze Hunter",
            maxSizePct = 4.0, defaultStopPct = 5.0, defaultTpPct = 20.0, trailingStopPct = 8.0,
            maxHoldMins = 120, riskTier = 4, minLiquidityUsd = 300_000.0, maxSlippagePct = 0.3,
            momentumWeight = 2.0, volumeWeight = 1.8, whaleWeight = 1.2, narrativeWeight = 0.5,
            technicalWeight = 1.5, regimeWeight = 1.4,
        ),

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

        MEAN_REV_OVERSOLD(
            regime = MarketRegime.MEAN_REVERSION,
            emoji = "⬇️", label = "Oversold Bounce",
            maxSizePct = 6.0, defaultStopPct = 5.0, defaultTpPct = 8.0, trailingStopPct = 0.0,
            maxHoldMins = 180, riskTier = 2, minLiquidityUsd = 200_000.0, maxSlippagePct = 0.3,
            momentumWeight = 0.5, volumeWeight = 1.0, whaleWeight = 0.7, narrativeWeight = 0.3,
            technicalWeight = 2.0, regimeWeight = 1.5,
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
            technicalWeight = 1.4, regimeWeight = 2.0,
        ),

        TREND_RELATIVE_STRENGTH(
            regime = MarketRegime.TREND_REGIME,
            emoji = "💪", label = "Relative Strength",
            maxSizePct = 9.0, defaultStopPct = 7.0, defaultTpPct = 18.0, trailingStopPct = 5.0,
            maxHoldMins = 1080, riskTier = 2, minLiquidityUsd = 120_000.0, maxSlippagePct = 0.35,
            momentumWeight = 1.8, volumeWeight = 1.1, whaleWeight = 1.1, narrativeWeight = 0.5,
            technicalWeight = 1.7, regimeWeight = 1.6,
        ),

        VOL_STRANGLE(
            regime = MarketRegime.VOLATILITY_STRATEGIES,
            emoji = "🎯", label = "Strangle",
            maxSizePct = 4.0, defaultStopPct = 15.0, defaultTpPct = 30.0, trailingStopPct = 10.0,
            maxHoldMins = 180, riskTier = 3, minLiquidityUsd = 50_000.0, maxSlippagePct = 0.5,
            momentumWeight = 0.5, volumeWeight = 1.8, whaleWeight = 1.5, narrativeWeight = 1.2,
            technicalWeight = 2.0, regimeWeight = 1.5,
        ),

        VOL_STRADDLE(
            regime = MarketRegime.VOLATILITY_STRATEGIES,
            emoji = "⚖️", label = "Straddle",
            maxSizePct = 3.5, defaultStopPct = 10.0, defaultTpPct = 20.0, trailingStopPct = 8.0,
            maxHoldMins = 120, riskTier = 3, minLiquidityUsd = 50_000.0, maxSlippagePct = 0.4,
            momentumWeight = 0.6, volumeWeight = 2.0, whaleWeight = 1.3, narrativeWeight = 1.0,
            technicalWeight = 2.2, regimeWeight = 1.4,
        ),

        VOL_BREAKOUT_ANTICIPATION(
            regime = MarketRegime.VOLATILITY_STRATEGIES,
            emoji = "💥", label = "Breakout Anticipation",
            maxSizePct = 5.0, defaultStopPct = 8.0, defaultTpPct = 25.0, trailingStopPct = 12.0,
            maxHoldMins = 240, riskTier = 3, minLiquidityUsd = 75_000.0, maxSlippagePct = 0.4,
            momentumWeight = 1.2, volumeWeight = 1.8, whaleWeight = 1.6, narrativeWeight = 1.5,
            technicalWeight = 1.8, regimeWeight = 1.5,
        ),

        VOL_COMPRESSION_PLAY(
            regime = MarketRegime.VOLATILITY_STRATEGIES,
            emoji = "🔋", label = "Vol Compression",
            maxSizePct = 4.5, defaultStopPct = 12.0, defaultTpPct = 35.0, trailingStopPct = 15.0,
            maxHoldMins = 300, riskTier = 4, minLiquidityUsd = 40_000.0, maxSlippagePct = 0.6,
            momentumWeight = 0.4, volumeWeight = 1.5, whaleWeight = 1.2, narrativeWeight = 0.8,
            technicalWeight = 2.5, regimeWeight = 1.2,
        ),

        VOL_GAMMA_SCALP(
            regime = MarketRegime.VOLATILITY_STRATEGIES,
            emoji = "⚡", label = "Gamma Scalp",
            maxSizePct = 2.0, defaultStopPct = 5.0, defaultTpPct = 8.0, trailingStopPct = 0.0,
            maxHoldMins = 30, riskTier = 3, minLiquidityUsd = 100_000.0, maxSlippagePct = 0.2,
            momentumWeight = 1.5, volumeWeight = 2.0, whaleWeight = 0.8, narrativeWeight = 0.5,
            technicalWeight = 1.8, regimeWeight = 0.8,
        ),
    }

    data class StructureClassification(
        val regime: MarketRegime,
        val mode: StructureMode,
        val confidence: Double,
        val signals: List<String>,
        val alternativeModes: List<Pair<StructureMode, Double>>,
        val aiWeights: Map<String, Double>,
    )

    private val MAJOR_TOKENS = setOf(
        "So11111111111111111111111111111111111111112",
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB",
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
        "7vfCXTUXx5WJV5JADk17DUJ4ksgau7utNKj4b963voxs",
        "3NZ9JMVBmGAqocybic2c7LQCJScmgsAZ6vQqTDzcqmJh",
    )

    private const val MID_CAP_MIN_LIQ_BASE = 50_000.0
    private const val MID_CAP_MAX_LIQ = 5_000_000.0
    private const val MAJOR_MIN_LIQ_BASE = 5_000_000.0

    private val classificationCache = ConcurrentHashMap<String, Pair<StructureClassification, Long>>()
    private const val CACHE_TTL_MS = 30_000L

    fun classify(ts: TokenState): StructureClassification {
        val cached = classificationCache[ts.mint]
        if (cached != null && System.currentTimeMillis() - cached.second < CACHE_TTL_MS) {
            return cached.first
        }

        val signals = mutableListOf<String>()
        val modeScores = mutableMapOf<StructureMode, Double>()
        StructureMode.values().forEach { modeScores[it] = 0.0 }

        val hist = ts.history.toList()
        val liquidity = ts.lastLiquidityUsd

        val regime = detectRegime(ts, hist, liquidity, signals)

        when (regime) {
            MarketRegime.MEME_MICRO -> scoreMemeModes(ts, hist, modeScores, signals)
            MarketRegime.MAJORS -> scoreMajorModes(hist, modeScores, signals)
            MarketRegime.MID_CAPS -> scoreMidCapModes(hist, modeScores, signals)
            MarketRegime.PERPS_STYLE -> scorePerpModes(hist, modeScores, signals)
            MarketRegime.CEX_ORDERBOOK -> scoreCexModes(hist, modeScores, signals)
            MarketRegime.MEAN_REVERSION -> scoreMeanRevModes(hist, modeScores, signals)
            MarketRegime.TREND_REGIME -> scoreTrendModes(hist, modeScores, signals)
            MarketRegime.VOLATILITY_STRATEGIES -> scoreVolatilityModes(hist, modeScores, signals)
        }

        val modesInRegime = modeScores.filterKeys { it.regime == regime }
        val bestMode = modesInRegime.maxByOrNull { it.value }?.key ?: StructureMode.MEME_BREAKOUT
        val bestScore = modesInRegime[bestMode] ?: 0.0

        val sortedScores = modesInRegime.values.sortedDescending()
        val secondBest = if (sortedScores.size > 1) sortedScores[1] else 0.0

        val confidence = when {
            bestScore <= 20 -> 15.0
            bestScore - secondBest > 25 -> 85.0
            bestScore - secondBest > 15 -> 70.0
            bestScore - secondBest > 5 -> 55.0
            else -> 40.0
        }

        val alternatives = modesInRegime
            .filter { it.key != bestMode && it.value > 20 }
            .toList()
            .sortedByDescending { it.second }
            .take(3)

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

        classificationCache[ts.mint] = result to System.currentTimeMillis()
        return result
    }

    private fun detectRegime(
        ts: TokenState,
        hist: List<Candle>,
        liquidity: Double,
        signals: MutableList<String>
    ): MarketRegime {
        val fluidMidCapMin = fluidMin(MID_CAP_MIN_LIQ_BASE)
        val fluidMajorMin = fluidMin(MAJOR_MIN_LIQ_BASE)
        val fluidScale = getFluidScale()

        if (ts.mint in MAJOR_TOKENS) {
            signals.add("REGIME: known major token")
            return MarketRegime.MAJORS
        }

        if (liquidity >= fluidMajorMin) {
            signals.add("REGIME: major liquidity $${(liquidity / 1_000_000).toInt()}M (fluid≥$${(fluidMajorMin / 1_000_000).toInt()}M)")
            return MarketRegime.MAJORS
        }

        val tokenAgeMins = if (hist.isNotEmpty()) {
            (System.currentTimeMillis() - hist.first().ts) / 60_000.0
        } else {
            0.0
        }

        val freshAgeThreshold = lerp(120.0, 60.0, fluidScale)
        if (tokenAgeMins < freshAgeThreshold && liquidity < fluidMidCapMin) {
            signals.add("REGIME: fresh meme (age=${tokenAgeMins.toInt()}min < ${freshAgeThreshold.toInt()}min)")
            return MarketRegime.MEME_MICRO
        }

        val volatility = if (hist.size >= 10) {
            val prices = hist.takeLast(20).map { it.priceUsd }
            val returns = prices.zipWithNext { a, b -> if (a > 0) (b - a) / a else 0.0 }
            if (returns.isNotEmpty()) {
                val mean = returns.average()
                sqrt(returns.map { (it - mean) * (it - mean) }.average()) * 100
            } else {
                0.0
            }
        } else {
            5.0
        }

        val volThreshold = lerp(5.0, 10.0, fluidScale)
        if (volatility > volThreshold && liquidity < fluidMidCapMin) {
            signals.add("REGIME: high vol meme (vol=${volatility.toInt()}% > ${volThreshold.toInt()}%)")
            return MarketRegime.MEME_MICRO
        }

        val minHistorySize = lerp(10.0, 30.0, fluidScale).toInt()

        // Important: check specialist regimes BEFORE generic mid-cap
        if (hist.size >= minHistorySize && isRangeBound(hist)) {
            signals.add("REGIME: range-bound detected")
            return MarketRegime.MEAN_REVERSION
        }

        if (hist.size >= minHistorySize && hasStrongTrend(hist)) {
            signals.add("REGIME: trend detected")
            return MarketRegime.TREND_REGIME
        }

        if (liquidity in fluidMidCapMin..MID_CAP_MAX_LIQ && hist.size >= minHistorySize) {
            signals.add("REGIME: mid-cap established (fluid hist≥$minHistorySize)")
            return MarketRegime.MID_CAPS
        }

        if (liquidity < fluidMidCapMin) {
            signals.add("REGIME: low liq default meme")
            return MarketRegime.MEME_MICRO
        }

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

        return range in 5.0..30.0 && posInRange in 0.2..0.8
    }

    private fun hasStrongTrend(hist: List<Candle>): Boolean {
        val prices = hist.takeLast(20).map { it.priceUsd }
        if (prices.size < 10) return false

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

        return abs(normalizedSlope) > 2.0
    }

    private fun scoreMemeModes(
        ts: TokenState,
        hist: List<Candle>,
        scores: MutableMap<StructureMode, Double>,
        signals: MutableList<String>
    ) {
        val prices = hist.map { it.priceUsd }
        val tokenAgeMins = if (hist.isNotEmpty()) {
            (System.currentTimeMillis() - hist.first().ts) / 60_000.0
        } else {
            999.0
        }

        val fluidScale = getFluidScale()
        val freshAgeThreshold = lerp(30.0, 15.0, fluidScale)

        if (tokenAgeMins <= freshAgeThreshold) {
            scores[StructureMode.FRESH_LAUNCH] = scores.getValue(StructureMode.FRESH_LAUNCH) + fluidScoreBonus(50.0)
            signals.add("MEME: fresh launch ${tokenAgeMins.toInt()}min (fluid≤${freshAgeThreshold.toInt()}min)")
        }

        val minHistForBreakout = lerp(4.0, 8.0, fluidScale).toInt()
        val breakoutThreshold = lerp(0.8, 0.9, fluidScale)

        if (hist.size >= minHistForBreakout) {
            val recentHigh = prices.takeLast(10).maxOrNull() ?: 0.0
            val currentPrice = prices.lastOrNull() ?: 0.0
            if (recentHigh > 0 && currentPrice >= recentHigh * breakoutThreshold) {
                scores[StructureMode.MEME_BREAKOUT] = scores.getValue(StructureMode.MEME_BREAKOUT) + fluidScoreBonus(40.0)
                signals.add("MEME: near high, breakout potential (fluid≥${(breakoutThreshold * 100).toInt()}%)")
            }
        }

        val source = ts.source.lowercase()
        val name = ts.name.lowercase()
        val narrativeMatches = listOf("boost", "trend", "hot", "pump", "moon", "ai", "trump", "meme", "doge", "pepe")
        val hasNarrative = narrativeMatches.any { source.contains(it) || name.contains(it) }

        if (hasNarrative) {
            scores[StructureMode.NARRATIVE_BURST] = scores.getValue(StructureMode.NARRATIVE_BURST) + fluidScoreBonus(35.0)
            signals.add("MEME: narrative match detected")
        }
    }

    private fun scoreMajorModes(
        hist: List<Candle>,
        scores: MutableMap<StructureMode, Double>,
        signals: MutableList<String>
    ) {
        val prices = hist.map { it.priceUsd }
        if (prices.size < 21) return

        val ema8 = prices.takeLast(8).average()
        val ema21 = prices.takeLast(21).average()
        val currentPrice = prices.lastOrNull() ?: 0.0

        if (ema8 > ema21 && currentPrice > ema8) {
            scores[StructureMode.MAJOR_TREND_FOLLOW] = scores.getValue(StructureMode.MAJOR_TREND_FOLLOW) + 45.0
            signals.add("MAJOR: bullish EMA structure")
        }

        val recentHigh = prices.takeLast(20).maxOrNull() ?: 0.0
        if (recentHigh > 0 && currentPrice >= recentHigh * 0.98) {
            scores[StructureMode.MAJOR_BREAKOUT] = scores.getValue(StructureMode.MAJOR_BREAKOUT) + 40.0
            signals.add("MAJOR: near ATH breakout")
        }

        val recentLow = prices.takeLast(10).minOrNull() ?: 0.0
        if (currentPrice < ema21 * 0.95 && currentPrice > recentLow * 1.02) {
            scores[StructureMode.MAJOR_DIP_BUY] = scores.getValue(StructureMode.MAJOR_DIP_BUY) + 40.0
            signals.add("MAJOR: dip with support")
        }
    }

    private fun scoreMidCapModes(
        hist: List<Candle>,
        scores: MutableMap<StructureMode, Double>,
        signals: MutableList<String>
    ) {
        val prices = hist.map { it.priceUsd }
        if (prices.size < 15) return

        val momentum = if (prices.size >= 10) {
            val recent = prices.takeLast(5).average()
            val prior = prices.dropLast(5).takeLast(5).average()
            if (prior > 0) (recent - prior) / prior * 100 else 0.0
        } else {
            0.0
        }

        if (momentum > 10) {
            scores[StructureMode.MIDCAP_MOMENTUM] = scores.getValue(StructureMode.MIDCAP_MOMENTUM) + 45.0
            signals.add("MIDCAP: strong momentum +${momentum.toInt()}%")
        }

        val priceRange = if (prices.isNotEmpty()) {
            val high = prices.maxOrNull() ?: 0.0
            val low = prices.minOrNull() ?: 0.0
            if (low > 0) (high - low) / low * 100 else 0.0
        } else {
            0.0
        }

        if (priceRange < 15) {
            scores[StructureMode.MIDCAP_ACCUMULATION] = scores.getValue(StructureMode.MIDCAP_ACCUMULATION) + 35.0
            signals.add("MIDCAP: consolidation pattern")
        }

        val pullbackFromHigh = if (prices.isNotEmpty()) {
            val high = prices.maxOrNull() ?: 0.0
            val current = prices.lastOrNull() ?: 0.0
            if (high > 0) (high - current) / high * 100 else 0.0
        } else {
            0.0
        }

        if (pullbackFromHigh in 10.0..25.0) {
            scores[StructureMode.MIDCAP_TECHNICAL_SETUP] = scores.getValue(StructureMode.MIDCAP_TECHNICAL_SETUP) + 40.0
            signals.add("MIDCAP: technical pullback -${pullbackFromHigh.toInt()}%")
        }
    }

    private fun scorePerpModes(
        hist: List<Candle>,
        scores: MutableMap<StructureMode, Double>,
        signals: MutableList<String>
    ) {
        val prices = hist.map { it.priceUsd }
        if (prices.size < 10) return

        val momentum = if (prices.size >= 6) {
            val recent = prices.takeLast(3).average()
            val prior = prices.dropLast(3).takeLast(3).average()
            if (prior > 0) (recent - prior) / prior * 100 else 0.0
        } else {
            0.0
        }

        if (abs(momentum) > 5) {
            scores[StructureMode.PERP_TREND_TRADE] = scores.getValue(StructureMode.PERP_TREND_TRADE) + 40.0
            signals.add("PERP: trend direction ${if (momentum > 0) "LONG" else "SHORT"}")
        }

        val volatilityRecent = calculateVolatility(prices.takeLast(5))
        val volatilityPrior = calculateVolatility(prices.dropLast(5).takeLast(10))

        if (volatilityPrior > 0 && volatilityRecent > volatilityPrior * 1.5) {
            scores[StructureMode.PERP_SQUEEZE_HUNTER] = scores.getValue(StructureMode.PERP_SQUEEZE_HUNTER) + 45.0
            signals.add("PERP: vol expansion (squeeze)")
        }
    }

    private fun scoreCexModes(
        hist: List<Candle>,
        scores: MutableMap<StructureMode, Double>,
        signals: MutableList<String>
    ) {
        val prices = hist.map { it.priceUsd }
        if (prices.size < 10) return

        val support = prices.takeLast(20).minOrNull() ?: 0.0
        val current = prices.lastOrNull() ?: 0.0

        if (support > 0 && current <= support * 1.03) {
            scores[StructureMode.CEX_SUPPORT_SNIPE] = scores.getValue(StructureMode.CEX_SUPPORT_SNIPE) + 45.0
            signals.add("CEX: at support level")
        }

        val recentVol = hist.takeLast(3).map { it.vol }.average()
        val avgVol = hist.takeLast(20).map { it.vol }.average()

        if (avgVol > 0 && recentVol > avgVol * 1.5) {
            scores[StructureMode.CEX_DEPTH_TRADE] = scores.getValue(StructureMode.CEX_DEPTH_TRADE) + 35.0
            signals.add("CEX: elevated volume")
        }
    }

    private fun scoreMeanRevModes(
        hist: List<Candle>,
        scores: MutableMap<StructureMode, Double>,
        signals: MutableList<String>
    ) {
        val prices = hist.map { it.priceUsd }
        if (prices.size < 20) return

        val window = prices.takeLast(20)
        val sma = window.average()
        val stdDev = calculateStdDev(window)
        val upperBand = sma + 2 * stdDev
        val lowerBand = sma - 2 * stdDev
        val current = prices.lastOrNull() ?: 0.0

        if (current < lowerBand) {
            scores[StructureMode.MEAN_REV_OVERSOLD] = scores.getValue(StructureMode.MEAN_REV_OVERSOLD) + 50.0
            signals.add("MEANREV: below lower band (oversold)")
        }

        if (current > upperBand) {
            scores[StructureMode.MEAN_REV_OVERBOUGHT] = scores.getValue(StructureMode.MEAN_REV_OVERBOUGHT) + 45.0
            signals.add("MEANREV: above upper band (overbought)")
        }

        if (current in lowerBand..upperBand) {
            val posInBand = if (upperBand > lowerBand) (current - lowerBand) / (upperBand - lowerBand) else 0.5
            if (posInBand in 0.3..0.7) {
                scores[StructureMode.MEAN_REV_RANGE_PLAY] = scores.getValue(StructureMode.MEAN_REV_RANGE_PLAY) + 35.0
                signals.add("MEANREV: mid-range position")
            }
        }
    }

    private fun scoreTrendModes(
        hist: List<Candle>,
        scores: MutableMap<StructureMode, Double>,
        signals: MutableList<String>
    ) {
        val prices = hist.map { it.priceUsd }
        if (prices.size < 30) return

        val ema10 = prices.takeLast(10).average()
        val ema30 = prices.takeLast(30).average()
        val trendStrength = if (ema30 > 0) (ema10 - ema30) / ema30 * 100 else 0.0

        if (trendStrength > 10) {
            scores[StructureMode.TREND_MOMENTUM_FACTOR] = scores.getValue(StructureMode.TREND_MOMENTUM_FACTOR) + 45.0
            signals.add("TREND: strong momentum factor +${trendStrength.toInt()}%")
        }

        if (trendStrength > 5) {
            scores[StructureMode.TREND_RELATIVE_STRENGTH] = scores.getValue(StructureMode.TREND_RELATIVE_STRENGTH) + 40.0
            signals.add("TREND: positive relative strength")
        }

        scores[StructureMode.TREND_SECTOR_ROTATION] = scores.getValue(StructureMode.TREND_SECTOR_ROTATION) + 25.0
    }

    private fun scoreVolatilityModes(
        hist: List<Candle>,
        scores: MutableMap<StructureMode, Double>,
        signals: MutableList<String>
    ) {
        val prices = hist.map { it.priceUsd }
        if (prices.size < 20) return

        val currentWindow = prices.takeLast(20)
        val sma20 = currentWindow.average()
        if (sma20 <= 0.0) return

        val stdDev20 = calculateStdDev(currentWindow)
        val bandWidth = (stdDev20 * 4.0 / sma20) * 100.0

        val recentVol = calculateVolatility(prices.takeLast(5))
        val priorVol = calculateVolatility(prices.dropLast(5).takeLast(10))

        val avgBandWidth = if (prices.size >= 40) {
            val rollingBandWidths = mutableListOf<Double>()
            for (i in 19 until prices.size) {
                val window = prices.subList(i - 19, i + 1)
                val sma = window.average()
                if (sma > 0) {
                    val std = calculateStdDev(window)
                    rollingBandWidths.add((std * 4.0 / sma) * 100.0)
                }
            }
            if (rollingBandWidths.isNotEmpty()) rollingBandWidths.average() else bandWidth
        } else {
            bandWidth
        }

        val isCompressed = bandWidth < avgBandWidth * 0.6
        val isExpanding = priorVol > 0 && recentVol > priorVol * 1.3

        if (isCompressed && bandWidth < 3.0) {
            scores[StructureMode.VOL_COMPRESSION_PLAY] = scores.getValue(StructureMode.VOL_COMPRESSION_PLAY) + 50.0
            signals.add("VOL: Extreme compression (BW=${bandWidth.toInt()}%)")
        }

        if (isCompressed) {
            scores[StructureMode.VOL_STRANGLE] = scores.getValue(StructureMode.VOL_STRANGLE) + 40.0
            signals.add("VOL: Compression → Strangle setup")
        }

        if (isCompressed && bandWidth < avgBandWidth * 0.4) {
            scores[StructureMode.VOL_STRADDLE] = scores.getValue(StructureMode.VOL_STRADDLE) + 45.0
            signals.add("VOL: Tight squeeze → Straddle")
        }

        val recentVolume = hist.takeLast(5).map { it.vol }.average()
        val priorVolume = hist.dropLast(5).takeLast(10).map { it.vol }.average()
        val volumeBuilding = priorVolume > 0 && recentVolume > priorVolume * 1.2

        if (isCompressed && volumeBuilding) {
            scores[StructureMode.VOL_BREAKOUT_ANTICIPATION] =
                scores.getValue(StructureMode.VOL_BREAKOUT_ANTICIPATION) + 55.0
            signals.add("VOL: Compression + volume = imminent breakout")
        }

        if (isExpanding && avgBandWidth > 0 && bandWidth > avgBandWidth * 1.5) {
            scores[StructureMode.VOL_GAMMA_SCALP] = scores.getValue(StructureMode.VOL_GAMMA_SCALP) + 40.0
            signals.add("VOL: Expansion active → Gamma scalp")
        }
    }

    private fun calculateVolatility(prices: List<Double>): Double {
        if (prices.size < 2) return 0.0
        val returns = prices.zipWithNext { a, b -> if (a > 0) (b - a) / a * 100 else 0.0 }
        if (returns.isEmpty()) return 0.0
        val mean = returns.average()
        return sqrt(returns.map { (it - mean) * (it - mean) }.average())
    }

    private fun calculateStdDev(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        return sqrt(values.map { (it - mean) * (it - mean) }.average())
    }

    fun getAIWeights(mode: StructureMode): Map<String, Double> = mapOf(
        "momentum" to mode.momentumWeight,
        "volume" to mode.volumeWeight,
        "whale" to mode.whaleWeight,
        "narrative" to mode.narrativeWeight,
        "technical" to mode.technicalWeight,
        "regime" to mode.regimeWeight,
    )

    fun getPositionParams(mode: StructureMode): PositionParams {
        val fluidParams = com.lifecyclebot.v3.scoring.FluidLearningAI.getModeParams(
            modeName = mode.label,
            defaultStopPct = mode.defaultStopPct,
            defaultTpPct = mode.defaultTpPct,
            defaultTrailingPct = mode.trailingStopPct
        )

        return PositionParams(
            maxSizePct = mode.maxSizePct,
            stopLossPct = fluidParams.stopLossPct,
            takeProfitPct = fluidParams.takeProfitPct,
            trailingStopPct = fluidParams.trailingStopPct,
            maxHoldMins = mode.maxHoldMins,
            riskTier = mode.riskTier,
            minLiquidityUsd = mode.minLiquidityUsd,
            maxSlippagePct = mode.maxSlippagePct,
        )
    }

    fun getRawPositionParams(mode: StructureMode): PositionParams = PositionParams(
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

    fun isSuitableForLiquidity(mode: StructureMode, liquidityUsd: Double): Boolean {
        return liquidityUsd >= mode.minLiquidityUsd
    }

    fun getStatus(): String {
        return "MarketStructureRouter: ${StructureMode.values().size} modes across ${MarketRegime.values().size} regimes"
    }
}
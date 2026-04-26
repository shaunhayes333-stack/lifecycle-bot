package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🎯 PERPS TRADE VISUALIZER - V5.7.2
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Advanced visualization and prediction system for perps trades.
 * 
 * FEATURES:
 * ─────────────────────────────────────────────────────────────────────────────
 *   📊 TRADE CHARTS       - Real-time mini charts for each position
 *   🎯 ZONE OVERLAYS      - Entry, TP, SL, liquidation zones
 *   🔮 AI PREDICTIONS     - 26-layer predicted outcome overlay
 *   🌡️ RISK THERMOMETER   - Real-time risk gauge
 *   📈 MOMENTUM RIBBON    - Trend strength indicator
 *   💀 LIQUIDATION RADAR  - Nearby liquidation clusters
 *   📉 P&L PROJECTOR      - Projected P&L at price levels
 *   🧠 CONFIDENCE METER   - Aggregated AI confidence
 *   ⚡ SMART ALERTS       - AI-generated warnings
 *   🔥 TRADE HEATMAP      - Performance by market/direction
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object PerpsTradeVisualizer {
    
    private const val TAG = "🎯PerpsViz"
    
    // Price history for mini charts (last 100 data points per market)
    private val priceHistory = ConcurrentHashMap<PerpsMarket, MutableList<PricePoint>>()
    private const val MAX_HISTORY_POINTS = 100
    
    // Prediction cache
    private val predictionCache = ConcurrentHashMap<String, TradePrediction>()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class PricePoint(
        val timestamp: Long,
        val price: Double,
        val volume: Double = 0.0,
    )
    
    /**
     * Complete trade visualization data for UI rendering
     */
    data class TradeVisualization(
        val position: PerpsPosition,
        val chartData: ChartData,
        val prediction: TradePrediction,
        val riskGauge: RiskGauge,
        val momentumRibbon: MomentumRibbon,
        val liquidationRadar: LiquidationRadar,
        val pnlProjection: PnLProjection,
        val smartAlerts: List<SmartAlert>,
    )
    
    /**
     * Mini chart data with zones
     */
    data class ChartData(
        val pricePoints: List<PricePoint>,
        val currentPrice: Double,
        val entryPrice: Double,
        val takeProfitPrice: Double?,
        val stopLossPrice: Double?,
        val liquidationPrice: Double,
        val predictedPath: List<PricePoint>,  // AI predicted future prices
        val supportLevels: List<Double>,
        val resistanceLevels: List<Double>,
        val zones: List<ChartZone>,
    )
    
    data class ChartZone(
        val type: ZoneType,
        val priceStart: Double,
        val priceEnd: Double,
        val color: String,
        val alpha: Float,
        val label: String,
    )
    
    enum class ZoneType {
        ENTRY,
        TAKE_PROFIT,
        STOP_LOSS,
        LIQUIDATION,
        DANGER,
        SUPPORT,
        RESISTANCE,
        PREDICTED_TARGET,
    }
    
    /**
     * AI Prediction from 26 layers
     */
    data class TradePrediction(
        val predictedDirection: PerpsDirection,
        val directionConfidence: Double,        // 0-100%
        val predictedPriceTarget: Double,
        val predictedTimeToTarget: Long,        // milliseconds
        val probabilityOfTP: Double,            // 0-100%
        val probabilityOfSL: Double,            // 0-100%
        val probabilityOfLiquidation: Double,   // 0-100%
        val optimalExitTime: Long?,             // suggested exit timestamp
        val layerConsensus: Int,                // how many layers agree
        val topContributingLayers: List<String>,
        val reasoning: String,
        val emoji: String,
    )
    
    /**
     * Real-time risk gauge
     */
    data class RiskGauge(
        val riskLevel: Int,                     // 0-100
        val riskCategory: RiskCategory,
        val distanceToLiquidation: Double,      // percentage
        val leverageHealth: Double,             // 0-100
        val volatilityRisk: Double,             // 0-100
        val timeRisk: Double,                   // 0-100 (longer hold = higher risk)
        val color: String,
        val emoji: String,
        val warning: String?,
    )
    
    enum class RiskCategory(val emoji: String, val color: String) {
        SAFE("🟢", "#22C55E"),
        MODERATE("🟡", "#F59E0B"),
        ELEVATED("🟠", "#F97316"),
        HIGH("🔴", "#EF4444"),
        CRITICAL("💀", "#7F1D1D"),
    }
    
    /**
     * Momentum strength ribbon
     */
    data class MomentumRibbon(
        val strength: Int,                      // -100 to +100
        val direction: PerpsDirection,
        val trend: TrendStrength,
        val bars: List<MomentumBar>,            // Visual bars
        val emoji: String,
    )
    
    data class MomentumBar(
        val value: Int,                         // -100 to +100
        val color: String,
    )
    
    enum class TrendStrength(val emoji: String) {
        STRONG_BULLISH("🚀"),
        BULLISH("📈"),
        WEAK_BULLISH("↗️"),
        NEUTRAL("➡️"),
        WEAK_BEARISH("↘️"),
        BEARISH("📉"),
        STRONG_BEARISH("💥"),
    }
    
    /**
     * Liquidation cluster radar
     */
    data class LiquidationRadar(
        val nearbyLiquidations: List<LiquidationCluster>,
        val totalLiquidationVolume: Double,
        val cascadeRisk: Double,                // 0-100%
        val dangerZones: List<PriceRange>,
    )
    
    data class LiquidationCluster(
        val priceLevel: Double,
        val estimatedVolume: Double,            // USD
        val direction: PerpsDirection,          // Which direction gets liquidated
        val distanceFromCurrent: Double,        // percentage
    )
    
    data class PriceRange(
        val low: Double,
        val high: Double,
    )
    
    /**
     * P&L projection at different price levels
     */
    data class PnLProjection(
        val currentPnL: Double,
        val currentPnLPct: Double,
        val projections: List<PnLPoint>,
        val breakEvenPrice: Double,
        val maxGain: Double,
        val maxLoss: Double,
    )
    
    data class PnLPoint(
        val price: Double,
        val pnlUsd: Double,
        val pnlPct: Double,
        val probability: Double,                // AI estimated probability of reaching this price
    )
    
    /**
     * AI-generated smart alerts
     */
    data class SmartAlert(
        val type: AlertType,
        val severity: AlertSeverity,
        val message: String,
        val emoji: String,
        val action: String?,                    // Suggested action
        val timestamp: Long,
    )
    
    enum class AlertType {
        LIQUIDATION_WARNING,
        TAKE_PROFIT_NEAR,
        STOP_LOSS_NEAR,
        MOMENTUM_SHIFT,
        FUNDING_RATE_CHANGE,
        VOLATILITY_SPIKE,
        LAYER_CONSENSUS_CHANGE,
        OPTIMAL_EXIT,
        WHALE_ACTIVITY,
        MARKET_CONDITION,
    }
    
    enum class AlertSeverity(val emoji: String, val color: String) {
        INFO("ℹ️", "#3B82F6"),
        WARNING("⚠️", "#F59E0B"),
        CRITICAL("🚨", "#EF4444"),
        OPPORTUNITY("💎", "#22C55E"),
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN VISUALIZATION GENERATOR
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Generate complete visualization for a position
     */
    suspend fun generateVisualization(position: PerpsPosition): TradeVisualization {
        // Get market data
        val marketData = PerpsMarketDataFetcher.getMarketData(position.market)
        
        // Update price history
        updatePriceHistory(position.market, marketData.price)
        
        // Generate all components in parallel
        val chartData = generateChartData(position, marketData)
        val prediction = generatePrediction(position, marketData)
        val riskGauge = calculateRiskGauge(position, marketData)
        val momentumRibbon = calculateMomentum(position.market, marketData)
        val liquidationRadar = scanLiquidations(position, marketData)
        val pnlProjection = projectPnL(position, marketData)
        val smartAlerts = generateAlerts(position, marketData, prediction, riskGauge)
        
        return TradeVisualization(
            position = position,
            chartData = chartData,
            prediction = prediction,
            riskGauge = riskGauge,
            momentumRibbon = momentumRibbon,
            liquidationRadar = liquidationRadar,
            pnlProjection = pnlProjection,
            smartAlerts = smartAlerts,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CHART DATA
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun generateChartData(position: PerpsPosition, marketData: PerpsMarketData): ChartData {
        val history = priceHistory[position.market] ?: mutableListOf()
        val currentPrice = marketData.price
        
        // Calculate support/resistance
        val supports = calculateSupportLevels(history, currentPrice)
        val resistances = calculateResistanceLevels(history, currentPrice)
        
        // Generate predicted price path
        val predictedPath = generatePredictedPath(position, currentPrice, history)
        
        // Build zones
        val zones = mutableListOf<ChartZone>()
        
        // Entry zone (±0.5%)
        zones.add(ChartZone(
            type = ZoneType.ENTRY,
            priceStart = position.entryPrice * 0.995,
            priceEnd = position.entryPrice * 1.005,
            color = "#3B82F6",
            alpha = 0.3f,
            label = "Entry: \$${position.entryPrice.fmt(2)}",
        ))
        
        // Take profit zone
        position.takeProfitPrice?.let { tp ->
            zones.add(ChartZone(
                type = ZoneType.TAKE_PROFIT,
                priceStart = tp * 0.99,
                priceEnd = tp * 1.01,
                color = "#22C55E",
                alpha = 0.3f,
                label = "TP: \$${tp.fmt(2)}",
            ))
        }
        
        // Stop loss zone
        position.stopLossPrice?.let { sl ->
            zones.add(ChartZone(
                type = ZoneType.STOP_LOSS,
                priceStart = sl * 0.99,
                priceEnd = sl * 1.01,
                color = "#F59E0B",
                alpha = 0.3f,
                label = "SL: \$${sl.fmt(2)}",
            ))
        }
        
        // Liquidation danger zone
        val liqPrice = position.liquidationPrice
        val dangerStart = when (position.direction) {
            PerpsDirection.LONG -> liqPrice
            PerpsDirection.SHORT -> liqPrice
        }
        val dangerEnd = when (position.direction) {
            PerpsDirection.LONG -> liqPrice * 1.1  // 10% above liq
            PerpsDirection.SHORT -> liqPrice * 0.9  // 10% below liq
        }
        
        zones.add(ChartZone(
            type = ZoneType.LIQUIDATION,
            priceStart = minOf(dangerStart, dangerEnd),
            priceEnd = maxOf(dangerStart, dangerEnd),
            color = "#EF4444",
            alpha = 0.5f,
            label = "☠️ LIQUIDATION: \$${liqPrice.fmt(2)}",
        ))
        
        return ChartData(
            pricePoints = history.toList(),
            currentPrice = currentPrice,
            entryPrice = position.entryPrice,
            takeProfitPrice = position.takeProfitPrice,
            stopLossPrice = position.stopLossPrice,
            liquidationPrice = position.liquidationPrice,
            predictedPath = predictedPath,
            supportLevels = supports,
            resistanceLevels = resistances,
            zones = zones,
        )
    }
    
    private fun calculateSupportLevels(history: List<PricePoint>, currentPrice: Double): List<Double> {
        if (history.size < 10) return listOf(currentPrice * 0.95, currentPrice * 0.90)
        
        val prices = history.map { it.price }
        val minPrice = prices.minOrNull() ?: currentPrice * 0.9
        
        // Find local lows
        val supports = mutableListOf<Double>()
        for (i in 2 until prices.size - 2) {
            if (prices[i] < prices[i-1] && prices[i] < prices[i-2] &&
                prices[i] < prices[i+1] && prices[i] < prices[i+2]) {
                supports.add(prices[i])
            }
        }
        
        return supports.filter { it < currentPrice }.sortedDescending().take(3)
    }
    
    private fun calculateResistanceLevels(history: List<PricePoint>, currentPrice: Double): List<Double> {
        if (history.size < 10) return listOf(currentPrice * 1.05, currentPrice * 1.10)
        
        val prices = history.map { it.price }
        
        // Find local highs
        val resistances = mutableListOf<Double>()
        for (i in 2 until prices.size - 2) {
            if (prices[i] > prices[i-1] && prices[i] > prices[i-2] &&
                prices[i] > prices[i+1] && prices[i] > prices[i+2]) {
                resistances.add(prices[i])
            }
        }
        
        return resistances.filter { it > currentPrice }.sorted().take(3)
    }
    
    private fun generatePredictedPath(
        position: PerpsPosition,
        currentPrice: Double,
        history: List<PricePoint>,
    ): List<PricePoint> {
        val predictedPoints = mutableListOf<PricePoint>()
        val now = System.currentTimeMillis()
        
        // Use momentum and trend to predict
        val momentum = if (history.size >= 10) {
            val recent = history.takeLast(10).map { it.price }
            (recent.last() - recent.first()) / recent.first() * 100
        } else 0.0
        
        // Generate 20 predicted future points
        var price = currentPrice
        for (i in 1..20) {
            val timeOffset = i * 60_000L  // 1 minute intervals
            
            // Apply momentum with decay
            val decay = 0.95.pow(i.toDouble())
            val change = momentum * decay * 0.001 + (Math.random() * 0.002 - 0.001)
            price *= (1 + change)
            
            predictedPoints.add(PricePoint(
                timestamp = now + timeOffset,
                price = price,
            ))
        }
        
        return predictedPoints
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // AI PREDICTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun generatePrediction(
        position: PerpsPosition,
        marketData: PerpsMarketData,
    ): TradePrediction {
        // Get aggregated signals from 26 layers
        val aggregated = PerpsLearningBridge.aggregateLayerSignals(position.market, marketData)
        
        val currentPnLPct = position.getUnrealizedPnlPct()
        val distanceToTP = position.takeProfitPrice?.let { tp ->
            abs((tp - marketData.price) / marketData.price * 100)
        } ?: 100.0
        val distanceToSL = position.stopLossPrice?.let { sl ->
            abs((sl - marketData.price) / marketData.price * 100)
        } ?: 100.0
        val distanceToLiq = position.getDistanceToLiquidation()
        
        // Calculate probabilities based on current state and layer signals
        val momentumFavor = when (position.direction) {
            PerpsDirection.LONG -> if (aggregated.direction == PerpsDirection.LONG) 1.2 else 0.8
            PerpsDirection.SHORT -> if (aggregated.direction == PerpsDirection.SHORT) 1.2 else 0.8
        }
        
        // Base probabilities adjusted by AI confidence and current state
        var probTP = 35.0 * momentumFavor * (aggregated.directionConfidence / 100)
        var probSL = 30.0 / momentumFavor
        var probLiq = 5.0
        
        // Adjust based on current P&L
        if (currentPnLPct > 0) {
            probTP += currentPnLPct * 0.5
            probSL -= currentPnLPct * 0.2
        } else {
            probTP -= abs(currentPnLPct) * 0.3
            probSL += abs(currentPnLPct) * 0.4
        }
        
        // Liquidation risk
        if (distanceToLiq < 20) {
            probLiq = 100 - distanceToLiq * 4
        }
        
        // Normalize
        val total = probTP + probSL + probLiq
        probTP = (probTP / total * 100).coerceIn(0.0, 100.0)
        probSL = (probSL / total * 100).coerceIn(0.0, 100.0)
        probLiq = (probLiq / total * 100).coerceIn(0.0, 100.0)
        
        // Predicted target
        val predictedTarget = if (probTP > probSL) {
            position.takeProfitPrice ?: (marketData.price * if (position.direction == PerpsDirection.LONG) 1.1 else 0.9)
        } else {
            position.stopLossPrice ?: (marketData.price * if (position.direction == PerpsDirection.LONG) 0.95 else 1.05)
        }
        
        // Time estimate (rough)
        val avgMovePerHour = 0.5  // 0.5% per hour average
        val distanceToTarget = abs((predictedTarget - marketData.price) / marketData.price * 100)
        val estimatedHours = distanceToTarget / avgMovePerHour
        val predictedTime = (estimatedHours * 60 * 60 * 1000).toLong()
        
        // Optimal exit
        val optimalExit = if (currentPnLPct > 5 && probSL > 40) {
            System.currentTimeMillis() + 5 * 60 * 1000  // Exit in 5 minutes
        } else null
        
        val emoji = when {
            probTP > 60 -> "🚀"
            probTP > 45 -> "📈"
            probSL > 60 -> "📉"
            probLiq > 20 -> "💀"
            else -> "🤔"
        }
        
        val reasoning = buildString {
            append("${aggregated.layerConsensus}/${aggregated.totalLayersVoting} layers favor ")
            append(if (aggregated.direction == position.direction) "your direction" else "opposite direction")
            append(" | Momentum: ${aggregated.sentimentBias.fmt(1)} | ")
            append("Risk: ${aggregated.riskScore.toInt()}%")
        }
        
        return TradePrediction(
            predictedDirection = aggregated.direction,
            directionConfidence = aggregated.directionConfidence,
            predictedPriceTarget = predictedTarget,
            predictedTimeToTarget = predictedTime,
            probabilityOfTP = probTP,
            probabilityOfSL = probSL,
            probabilityOfLiquidation = probLiq,
            optimalExitTime = optimalExit,
            layerConsensus = aggregated.layerConsensus,
            topContributingLayers = aggregated.contributingLayers.take(5),
            reasoning = reasoning,
            emoji = emoji,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // RISK GAUGE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun calculateRiskGauge(position: PerpsPosition, marketData: PerpsMarketData): RiskGauge {
        val distToLiq = position.getDistanceToLiquidation()
        val holdTime = position.getHoldDurationMinutes()
        
        // Leverage risk (higher leverage = higher risk)
        val leverageRisk = (position.leverage / 20.0 * 100).coerceIn(0.0, 100.0)
        
        // Volatility risk
        val volatilityRisk = if (marketData.isVolatile()) 70.0 else 30.0
        
        // Time risk (longer holds accumulate risk)
        val timeRisk = (holdTime / 60.0 * 10).coerceIn(0.0, 100.0)  // 10% per hour
        
        // Liquidation proximity risk
        val liqRisk = when {
            distToLiq < 5 -> 100.0
            distToLiq < 10 -> 80.0
            distToLiq < 20 -> 50.0
            distToLiq < 30 -> 30.0
            else -> 10.0
        }
        
        // Overall risk (weighted)
        val overallRisk = (
            liqRisk * 0.4 +
            leverageRisk * 0.25 +
            volatilityRisk * 0.2 +
            timeRisk * 0.15
        ).toInt().coerceIn(0, 100)
        
        val category = when {
            overallRisk >= 80 -> RiskCategory.CRITICAL
            overallRisk >= 60 -> RiskCategory.HIGH
            overallRisk >= 40 -> RiskCategory.ELEVATED
            overallRisk >= 20 -> RiskCategory.MODERATE
            else -> RiskCategory.SAFE
        }
        
        val warning = when {
            distToLiq < 10 -> "⚠️ LIQUIDATION IMMINENT!"
            distToLiq < 20 -> "🔴 Close to liquidation zone"
            position.leverage > 15 -> "High leverage active"
            holdTime > 240 -> "Extended hold time"
            else -> null
        }
        
        return RiskGauge(
            riskLevel = overallRisk,
            riskCategory = category,
            distanceToLiquidation = distToLiq,
            leverageHealth = 100 - leverageRisk,
            volatilityRisk = volatilityRisk,
            timeRisk = timeRisk,
            color = category.color,
            emoji = category.emoji,
            warning = warning,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MOMENTUM RIBBON
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun calculateMomentum(market: PerpsMarket, marketData: PerpsMarketData): MomentumRibbon {
        val history = priceHistory[market] ?: mutableListOf()
        
        // Calculate momentum over different timeframes
        val bars = mutableListOf<MomentumBar>()
        
        // Last 5 points (short-term)
        val shortTerm = if (history.size >= 5) {
            val recent = history.takeLast(5)
            ((recent.last().price - recent.first().price) / recent.first().price * 1000).toInt().coerceIn(-100, 100)
        } else 0
        
        // Last 20 points (medium-term)
        val mediumTerm = if (history.size >= 20) {
            val recent = history.takeLast(20)
            ((recent.last().price - recent.first().price) / recent.first().price * 500).toInt().coerceIn(-100, 100)
        } else 0
        
        // Last 50 points (long-term)
        val longTerm = if (history.size >= 50) {
            val recent = history.takeLast(50)
            ((recent.last().price - recent.first().price) / recent.first().price * 200).toInt().coerceIn(-100, 100)
        } else 0
        
        bars.add(MomentumBar(shortTerm, if (shortTerm > 0) "#22C55E" else "#EF4444"))
        bars.add(MomentumBar(mediumTerm, if (mediumTerm > 0) "#22C55E" else "#EF4444"))
        bars.add(MomentumBar(longTerm, if (longTerm > 0) "#22C55E" else "#EF4444"))
        
        val overallMomentum = (shortTerm * 0.5 + mediumTerm * 0.3 + longTerm * 0.2).toInt()
        val direction = if (overallMomentum >= 0) PerpsDirection.LONG else PerpsDirection.SHORT
        
        val trend = when {
            overallMomentum > 50 -> TrendStrength.STRONG_BULLISH
            overallMomentum > 20 -> TrendStrength.BULLISH
            overallMomentum > 5 -> TrendStrength.WEAK_BULLISH
            overallMomentum > -5 -> TrendStrength.NEUTRAL
            overallMomentum > -20 -> TrendStrength.WEAK_BEARISH
            overallMomentum > -50 -> TrendStrength.BEARISH
            else -> TrendStrength.STRONG_BEARISH
        }
        
        return MomentumRibbon(
            strength = overallMomentum,
            direction = direction,
            trend = trend,
            bars = bars,
            emoji = trend.emoji,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LIQUIDATION RADAR
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun scanLiquidations(position: PerpsPosition, marketData: PerpsMarketData): LiquidationRadar {
        val currentPrice = marketData.price
        val clusters = mutableListOf<LiquidationCluster>()
        
        // Estimate liquidation clusters based on common leverage levels
        // Longs get liquidated when price drops, shorts when price rises
        
        val leverageLevels = listOf(5.0, 10.0, 15.0, 20.0, 50.0)
        
        for (lev in leverageLevels) {
            // Long liquidations (below current price)
            val longLiqPrice = currentPrice * (1 - 0.9 / lev)
            val longDist = (currentPrice - longLiqPrice) / currentPrice * 100
            
            if (longDist < 30) {
                clusters.add(LiquidationCluster(
                    priceLevel = longLiqPrice,
                    estimatedVolume = 1_000_000.0 * (20 - lev).coerceAtLeast(1.0),
                    direction = PerpsDirection.LONG,
                    distanceFromCurrent = longDist,
                ))
            }
            
            // Short liquidations (above current price)
            val shortLiqPrice = currentPrice * (1 + 0.9 / lev)
            val shortDist = (shortLiqPrice - currentPrice) / currentPrice * 100
            
            if (shortDist < 30) {
                clusters.add(LiquidationCluster(
                    priceLevel = shortLiqPrice,
                    estimatedVolume = 1_000_000.0 * (20 - lev).coerceAtLeast(1.0),
                    direction = PerpsDirection.SHORT,
                    distanceFromCurrent = shortDist,
                ))
            }
        }
        
        val totalVolume = clusters.sumOf { it.estimatedVolume }
        
        // Cascade risk based on clusters near our liquidation
        val myLiqDist = position.getDistanceToLiquidation()
        val cascadeRisk = clusters
            .filter { abs(it.distanceFromCurrent - myLiqDist) < 5 }
            .sumOf { it.estimatedVolume } / 10_000_000 * 100
        
        // Danger zones
        val dangerZones = clusters
            .groupBy { (it.distanceFromCurrent / 5).toInt() * 5 }
            .map { (_, group) ->
                PriceRange(
                    low = group.minOf { it.priceLevel } * 0.99,
                    high = group.maxOf { it.priceLevel } * 1.01,
                )
            }
        
        return LiquidationRadar(
            nearbyLiquidations = clusters.sortedBy { it.distanceFromCurrent },
            totalLiquidationVolume = totalVolume,
            cascadeRisk = cascadeRisk.coerceIn(0.0, 100.0),
            dangerZones = dangerZones,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // P&L PROJECTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun projectPnL(position: PerpsPosition, marketData: PerpsMarketData): PnLProjection {
        val currentPrice = marketData.price
        val projections = mutableListOf<PnLPoint>()
        
        // Project P&L at different price levels
        val priceSteps = listOf(-10.0, -5.0, -3.0, -1.0, 0.0, 1.0, 3.0, 5.0, 10.0)
        
        for (step in priceSteps) {
            val projectedPrice = currentPrice * (1 + step / 100)
            val (pnlUsd, pnlPct) = JupiterPerps.calculatePnL(
                entryPrice = position.entryPrice,
                currentPrice = projectedPrice,
                sizeSol = position.sizeSol,
                leverage = position.leverage,
                direction = position.direction,
            )
            
            // Probability decreases with distance from current
            val prob = (100 - abs(step) * 5).coerceIn(5.0, 95.0)
            
            projections.add(PnLPoint(
                price = projectedPrice,
                pnlUsd = pnlUsd,
                pnlPct = pnlPct,
                probability = prob,
            ))
        }
        
        // Break even
        val breakEven = position.entryPrice
        
        // Max gain (at TP or +20%)
        val maxGainPrice = position.takeProfitPrice ?: (currentPrice * 1.2)
        val (maxGainUsd, _) = JupiterPerps.calculatePnL(
            position.entryPrice, maxGainPrice, position.sizeSol, position.leverage, position.direction
        )
        
        // Max loss (at liquidation)
        val (maxLossUsd, _) = JupiterPerps.calculatePnL(
            position.entryPrice, position.liquidationPrice, position.sizeSol, position.leverage, position.direction
        )
        
        return PnLProjection(
            currentPnL = position.getUnrealizedPnlUsd(),
            currentPnLPct = position.getUnrealizedPnlPct(),
            projections = projections,
            breakEvenPrice = breakEven,
            maxGain = maxGainUsd,
            maxLoss = maxLossUsd,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SMART ALERTS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun generateAlerts(
        position: PerpsPosition,
        marketData: PerpsMarketData,
        prediction: TradePrediction,
        riskGauge: RiskGauge,
    ): List<SmartAlert> {
        val alerts = mutableListOf<SmartAlert>()
        val now = System.currentTimeMillis()
        
        // Liquidation warning
        if (riskGauge.distanceToLiquidation < 15) {
            alerts.add(SmartAlert(
                type = AlertType.LIQUIDATION_WARNING,
                severity = if (riskGauge.distanceToLiquidation < 10) AlertSeverity.CRITICAL else AlertSeverity.WARNING,
                message = "Only ${riskGauge.distanceToLiquidation.fmt(1)}% from liquidation!",
                emoji = "💀",
                action = "Consider reducing position or adding margin",
                timestamp = now,
            ))
        }
        
        // Near TP
        if (prediction.probabilityOfTP > 70) {
            alerts.add(SmartAlert(
                type = AlertType.TAKE_PROFIT_NEAR,
                severity = AlertSeverity.OPPORTUNITY,
                message = "High probability (${prediction.probabilityOfTP.toInt()}%) of hitting TP",
                emoji = "🎯",
                action = "Consider taking partial profits",
                timestamp = now,
            ))
        }
        
        // Near SL
        if (prediction.probabilityOfSL > 60) {
            alerts.add(SmartAlert(
                type = AlertType.STOP_LOSS_NEAR,
                severity = AlertSeverity.WARNING,
                message = "${prediction.probabilityOfSL.toInt()}% chance of hitting stop loss",
                emoji = "🛑",
                action = "Review position sizing",
                timestamp = now,
            ))
        }
        
        // Momentum shift
        if (prediction.predictedDirection != position.direction && prediction.directionConfidence > 60) {
            alerts.add(SmartAlert(
                type = AlertType.MOMENTUM_SHIFT,
                severity = AlertSeverity.WARNING,
                message = "AI layers detecting momentum shift against your position",
                emoji = "🔄",
                action = "Monitor closely for exit signals",
                timestamp = now,
            ))
        }
        
        // Optimal exit
        if (prediction.optimalExitTime != null) {
            alerts.add(SmartAlert(
                type = AlertType.OPTIMAL_EXIT,
                severity = AlertSeverity.INFO,
                message = "AI suggests exiting within 5 minutes",
                emoji = "⏰",
                action = "Lock in current ${if (position.getUnrealizedPnlPct() > 0) "profits" else "losses"}",
                timestamp = now,
            ))
        }
        
        // Volatility spike
        if (marketData.isVolatile()) {
            alerts.add(SmartAlert(
                type = AlertType.VOLATILITY_SPIKE,
                severity = AlertSeverity.INFO,
                message = "High volatility detected - wider price swings expected",
                emoji = "⚡",
                action = null,
                timestamp = now,
            ))
        }
        
        // Layer consensus
        if (prediction.layerConsensus >= 20) {
            alerts.add(SmartAlert(
                type = AlertType.LAYER_CONSENSUS_CHANGE,
                severity = AlertSeverity.OPPORTUNITY,
                message = "${prediction.layerConsensus} layers agree on ${prediction.predictedDirection.symbol} direction",
                emoji = "🧠",
                action = null,
                timestamp = now,
            ))
        }
        
        return alerts.sortedByDescending { 
            when (it.severity) {
                AlertSeverity.CRITICAL -> 4
                AlertSeverity.WARNING -> 3
                AlertSeverity.OPPORTUNITY -> 2
                AlertSeverity.INFO -> 1
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PRICE HISTORY MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun updatePriceHistory(market: PerpsMarket, price: Double) {
        val history = priceHistory.getOrPut(market) { mutableListOf() }
        
        history.add(PricePoint(
            timestamp = System.currentTimeMillis(),
            price = price,
        ))
        
        // Trim to max size
        while (history.size > MAX_HISTORY_POINTS) {
            history.removeAt(0)
        }
    }
    
    fun clearHistory(market: PerpsMarket) {
        priceHistory.remove(market)
    }
    
    fun clearAllHistory() {
        priceHistory.clear()
        predictionCache.clear()
    }
    
    // V5.9.321: Removed private Double.fmt — uses public PerpsModels.fmt
}

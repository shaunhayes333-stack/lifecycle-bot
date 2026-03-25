package com.lifecyclebot.engine.quant

import com.lifecyclebot.engine.ErrorLogger
import kotlin.math.sqrt
import kotlin.math.abs

/**
 * PORTFOLIO HEAT MAP & CORRELATION ANALYSIS
 * ════════════════════════════════════════════════════════════════════════════
 * 
 * Tracks:
 * - Position concentration (exposure heat map)
 * - Token correlation analysis
 * - Sector/narrative clustering
 * - Risk concentration alerts
 */
object PortfolioAnalytics {
    
    private const val TAG = "PortfolioAnalytics"
    
    // ════════════════════════════════════════════════════════════════════════
    // POSITION TRACKING
    // ════════════════════════════════════════════════════════════════════════
    
    data class PositionSnapshot(
        val mint: String,
        val symbol: String,
        val valueSol: Double,
        val costSol: Double,
        val pnlPct: Double,
        val weight: Double,  // % of portfolio
        val entryTime: Long,
        val narrative: String,  // meme, ai, gaming, defi, etc.
    )
    
    private val activePositions = mutableMapOf<String, PositionSnapshot>()
    private val priceHistory = mutableMapOf<String, MutableList<PricePoint>>()
    
    data class PricePoint(
        val timestamp: Long,
        val price: Double,
    )
    
    // ════════════════════════════════════════════════════════════════════════
    // PORTFOLIO HEAT MAP
    // ════════════════════════════════════════════════════════════════════════
    
    data class HeatMapCell(
        val symbol: String,
        val mint: String,
        val weight: Double,      // % of portfolio
        val pnlPct: Double,      // Current P&L
        val heatLevel: HeatLevel,
        val riskScore: Double,   // 0-100
    )
    
    enum class HeatLevel {
        COLD,       // < 5% of portfolio
        WARM,       // 5-15%
        HOT,        // 15-25%
        OVERHEATED, // > 25% - DANGER
    }
    
    data class HeatMapReport(
        val cells: List<HeatMapCell>,
        val totalExposureSol: Double,
        val concentrationRisk: ConcentrationRisk,
        val topHolding: HeatMapCell?,
        val narrativeBreakdown: Map<String, Double>,
        val alerts: List<String>,
    )
    
    data class ConcentrationRisk(
        val herfindahlIndex: Double,  // 0-1, higher = more concentrated
        val top3Weight: Double,       // % in top 3 positions
        val level: RiskLevel,
    )
    
    enum class RiskLevel { LOW, MODERATE, HIGH, EXTREME }
    
    fun updatePosition(
        mint: String,
        symbol: String,
        valueSol: Double,
        costSol: Double,
        narrative: String = "unknown",
        entryTime: Long = System.currentTimeMillis(),
    ) {
        val totalValue = activePositions.values.sumOf { it.valueSol } + valueSol
        val weight = if (totalValue > 0) (valueSol / totalValue) * 100 else 0.0
        val pnlPct = if (costSol > 0) ((valueSol - costSol) / costSol) * 100 else 0.0
        
        activePositions[mint] = PositionSnapshot(
            mint = mint,
            symbol = symbol,
            valueSol = valueSol,
            costSol = costSol,
            pnlPct = pnlPct,
            weight = weight,
            entryTime = entryTime,
            narrative = narrative,
        )
        
        recalculateWeights()
    }
    
    fun removePosition(mint: String) {
        activePositions.remove(mint)
        recalculateWeights()
    }
    
    private fun recalculateWeights() {
        val totalValue = activePositions.values.sumOf { it.valueSol }
        if (totalValue <= 0) return
        
        activePositions.forEach { (mint, pos) ->
            val newWeight = (pos.valueSol / totalValue) * 100
            activePositions[mint] = pos.copy(weight = newWeight)
        }
    }
    
    fun generateHeatMap(): HeatMapReport {
        val cells = activePositions.values.map { pos ->
            val heatLevel = when {
                pos.weight >= 25 -> HeatLevel.OVERHEATED
                pos.weight >= 15 -> HeatLevel.HOT
                pos.weight >= 5 -> HeatLevel.WARM
                else -> HeatLevel.COLD
            }
            
            // Risk score based on multiple factors
            val riskScore = calculatePositionRisk(pos)
            
            HeatMapCell(
                symbol = pos.symbol,
                mint = pos.mint,
                weight = pos.weight,
                pnlPct = pos.pnlPct,
                heatLevel = heatLevel,
                riskScore = riskScore,
            )
        }.sortedByDescending { it.weight }
        
        val totalExposure = activePositions.values.sumOf { it.valueSol }
        
        // Herfindahl Index (concentration metric)
        val herfindahl = cells.sumOf { (it.weight / 100).let { w -> w * w } }
        val top3Weight = cells.take(3).sumOf { it.weight }
        
        val concentrationRisk = ConcentrationRisk(
            herfindahlIndex = herfindahl,
            top3Weight = top3Weight,
            level = when {
                herfindahl >= 0.5 || top3Weight >= 80 -> RiskLevel.EXTREME
                herfindahl >= 0.3 || top3Weight >= 60 -> RiskLevel.HIGH
                herfindahl >= 0.15 || top3Weight >= 40 -> RiskLevel.MODERATE
                else -> RiskLevel.LOW
            }
        )
        
        // Narrative breakdown
        val narrativeBreakdown = activePositions.values
            .groupBy { it.narrative }
            .mapValues { (_, positions) -> positions.sumOf { it.weight } }
        
        // Generate alerts
        val alerts = mutableListOf<String>()
        
        cells.filter { it.heatLevel == HeatLevel.OVERHEATED }.forEach {
            alerts.add("🔥 OVERHEATED: ${it.symbol} is ${it.weight.fmt(1)}% of portfolio")
        }
        
        if (concentrationRisk.level == RiskLevel.EXTREME) {
            alerts.add("⚠️ EXTREME CONCENTRATION: Top 3 holdings = ${top3Weight.fmt(1)}%")
        }
        
        narrativeBreakdown.filter { it.value >= 50 }.forEach { (narrative, weight) ->
            alerts.add("📊 NARRATIVE RISK: ${weight.fmt(1)}% in '$narrative' tokens")
        }
        
        return HeatMapReport(
            cells = cells,
            totalExposureSol = totalExposure,
            concentrationRisk = concentrationRisk,
            topHolding = cells.firstOrNull(),
            narrativeBreakdown = narrativeBreakdown,
            alerts = alerts,
        )
    }
    
    private fun calculatePositionRisk(pos: PositionSnapshot): Double {
        var risk = 0.0
        
        // Weight risk (higher weight = higher risk)
        risk += pos.weight * 2
        
        // P&L risk (large unrealized gains can evaporate)
        if (pos.pnlPct > 100) risk += 20
        else if (pos.pnlPct > 50) risk += 10
        
        // Time risk (newer positions = higher risk)
        val holdHours = (System.currentTimeMillis() - pos.entryTime) / 3600000.0
        if (holdHours < 1) risk += 30
        else if (holdHours < 6) risk += 15
        else if (holdHours < 24) risk += 5
        
        return risk.coerceIn(0.0, 100.0)
    }
    
    // ════════════════════════════════════════════════════════════════════════
    // CORRELATION ANALYSIS
    // ════════════════════════════════════════════════════════════════════════
    
    data class CorrelationPair(
        val token1: String,
        val token2: String,
        val correlation: Double,  // -1 to +1
        val interpretation: String,
    )
    
    data class CorrelationReport(
        val pairs: List<CorrelationPair>,
        val highlyCorrelated: List<CorrelationPair>,  // > 0.7
        val avgCorrelation: Double,
        val diversificationScore: Double,  // 0-100, higher = more diversified
        val alerts: List<String>,
    )
    
    fun recordPrice(mint: String, price: Double) {
        val history = priceHistory.getOrPut(mint) { mutableListOf() }
        history.add(PricePoint(System.currentTimeMillis(), price))
        
        // Keep last 100 price points per token
        if (history.size > 100) history.removeAt(0)
    }
    
    fun calculateCorrelations(): CorrelationReport {
        val mints = priceHistory.keys.toList()
        val pairs = mutableListOf<CorrelationPair>()
        
        // Calculate pairwise correlations
        for (i in mints.indices) {
            for (j in i + 1 until mints.size) {
                val mint1 = mints[i]
                val mint2 = mints[j]
                
                val correlation = calculatePearsonCorrelation(mint1, mint2)
                if (correlation != null) {
                    val symbol1 = activePositions[mint1]?.symbol ?: mint1.take(8)
                    val symbol2 = activePositions[mint2]?.symbol ?: mint2.take(8)
                    
                    val interpretation = when {
                        correlation >= 0.8 -> "HIGHLY CORRELATED - Move together"
                        correlation >= 0.5 -> "Moderately correlated"
                        correlation >= 0.2 -> "Weakly correlated"
                        correlation >= -0.2 -> "Uncorrelated - Good diversification"
                        correlation >= -0.5 -> "Weakly negative - Some hedging"
                        else -> "NEGATIVE CORRELATION - Natural hedge"
                    }
                    
                    pairs.add(CorrelationPair(symbol1, symbol2, correlation, interpretation))
                }
            }
        }
        
        val highlyCorrelated = pairs.filter { it.correlation >= 0.7 }
        val avgCorrelation = if (pairs.isNotEmpty()) pairs.map { it.correlation }.average() else 0.0
        
        // Diversification score (lower average correlation = better diversification)
        val diversificationScore = ((1 - avgCorrelation) * 50 + 50).coerceIn(0.0, 100.0)
        
        val alerts = mutableListOf<String>()
        
        highlyCorrelated.forEach {
            alerts.add("⚠️ HIGH CORRELATION: ${it.token1} & ${it.token2} (${(it.correlation * 100).fmt(0)}%) - diversification risk")
        }
        
        if (avgCorrelation > 0.5) {
            alerts.add("📊 Portfolio highly correlated (avg ${(avgCorrelation * 100).fmt(0)}%) - consider diversifying")
        }
        
        return CorrelationReport(
            pairs = pairs.sortedByDescending { abs(it.correlation) },
            highlyCorrelated = highlyCorrelated,
            avgCorrelation = avgCorrelation,
            diversificationScore = diversificationScore,
            alerts = alerts,
        )
    }
    
    private fun calculatePearsonCorrelation(mint1: String, mint2: String): Double? {
        val history1 = priceHistory[mint1] ?: return null
        val history2 = priceHistory[mint2] ?: return null
        
        // Need at least 10 overlapping data points
        if (history1.size < 10 || history2.size < 10) return null
        
        // Calculate returns (percentage change)
        val returns1 = history1.zipWithNext { a, b -> (b.price - a.price) / a.price }
        val returns2 = history2.zipWithNext { a, b -> (b.price - a.price) / a.price }
        
        // Align by taking minimum length
        val minLen = minOf(returns1.size, returns2.size)
        if (minLen < 10) return null
        
        val r1 = returns1.takeLast(minLen)
        val r2 = returns2.takeLast(minLen)
        
        val mean1 = r1.average()
        val mean2 = r2.average()
        
        var numerator = 0.0
        var denom1 = 0.0
        var denom2 = 0.0
        
        for (i in r1.indices) {
            val d1 = r1[i] - mean1
            val d2 = r2[i] - mean2
            numerator += d1 * d2
            denom1 += d1 * d1
            denom2 += d2 * d2
        }
        
        val denominator = sqrt(denom1) * sqrt(denom2)
        if (denominator == 0.0) return 0.0
        
        return (numerator / denominator).coerceIn(-1.0, 1.0)
    }
    
    // ════════════════════════════════════════════════════════════════════════
    // FULL PORTFOLIO REPORT
    // ════════════════════════════════════════════════════════════════════════
    
    data class PortfolioReport(
        val heatMap: HeatMapReport,
        val correlations: CorrelationReport,
        val totalPositions: Int,
        val totalValueSol: Double,
        val totalPnlSol: Double,
        val totalPnlPct: Double,
        val riskScore: Double,
        val summary: String,
    )
    
    fun generateFullReport(): PortfolioReport {
        val heatMap = generateHeatMap()
        val correlations = calculateCorrelations()
        
        val totalValue = activePositions.values.sumOf { it.valueSol }
        val totalCost = activePositions.values.sumOf { it.costSol }
        val totalPnl = totalValue - totalCost
        val totalPnlPct = if (totalCost > 0) (totalPnl / totalCost) * 100 else 0.0
        
        // Overall risk score
        val concentrationRisk = when (heatMap.concentrationRisk.level) {
            RiskLevel.EXTREME -> 40.0
            RiskLevel.HIGH -> 25.0
            RiskLevel.MODERATE -> 10.0
            RiskLevel.LOW -> 0.0
        }
        val correlationRisk = (1 - correlations.diversificationScore / 100) * 30
        val positionRisk = heatMap.cells.map { it.riskScore }.average()
        
        val overallRisk = (concentrationRisk + correlationRisk + positionRisk).coerceIn(0.0, 100.0)
        
        val summary = buildString {
            append("📊 PORTFOLIO ANALYTICS\n")
            append("═══════════════════════════════════\n")
            append("Positions: ${activePositions.size}\n")
            append("Total Value: ${totalValue.fmt(4)} SOL\n")
            append("Total P&L: ${totalPnl.fmt(4)} SOL (${totalPnlPct.fmt(1)}%)\n")
            append("───────────────────────────────────\n")
            append("CONCENTRATION\n")
            append("  Herfindahl Index: ${heatMap.concentrationRisk.herfindahlIndex.fmt(3)}\n")
            append("  Top 3 Weight: ${heatMap.concentrationRisk.top3Weight.fmt(1)}%\n")
            append("  Risk Level: ${heatMap.concentrationRisk.level}\n")
            append("───────────────────────────────────\n")
            append("CORRELATION\n")
            append("  Avg Correlation: ${(correlations.avgCorrelation * 100).fmt(1)}%\n")
            append("  Diversification Score: ${correlations.diversificationScore.fmt(0)}/100\n")
            append("  Highly Correlated Pairs: ${correlations.highlyCorrelated.size}\n")
            append("───────────────────────────────────\n")
            append("OVERALL RISK SCORE: ${overallRisk.fmt(0)}/100\n")
            
            if (heatMap.alerts.isNotEmpty() || correlations.alerts.isNotEmpty()) {
                append("───────────────────────────────────\n")
                append("ALERTS:\n")
                (heatMap.alerts + correlations.alerts).forEach {
                    append("  $it\n")
                }
            }
        }
        
        return PortfolioReport(
            heatMap = heatMap,
            correlations = correlations,
            totalPositions = activePositions.size,
            totalValueSol = totalValue,
            totalPnlSol = totalPnl,
            totalPnlPct = totalPnlPct,
            riskScore = overallRisk,
            summary = summary,
        )
    }
    
    // ════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════════════════
    
    private fun Double.fmt(d: Int) = "%.${d}f".format(this)
    
    fun reset() {
        activePositions.clear()
        priceHistory.clear()
    }
}

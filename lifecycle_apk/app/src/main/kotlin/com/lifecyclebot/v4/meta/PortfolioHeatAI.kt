package com.lifecyclebot.v4.meta

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * ===============================================================================
 * PORTFOLIO HEAT AI — V4 Meta-Intelligence
 * ===============================================================================
 *
 * Purpose: Prevent correlated stupidity.
 * You may think you have 5 different positions, but really you have:
 *   3 SOL beta bets + 1 meme beta bet + 1 leveraged index beta bet
 * This should see that as one cluster and throttle new entries.
 *
 * ===============================================================================
 */
object PortfolioHeatAI {

    private const val TAG = "PortfolioHeatAI"

    private val currentReport = AtomicReference<PortfolioHeatReport?>(null)

    // Active position tracking
    private val activePositions = ConcurrentHashMap<String, PositionExposure>()

    data class PositionExposure(
        val id: String,
        val symbol: String,
        val market: String,
        val sector: String,
        val direction: String,      // "LONG" or "SHORT"
        val sizeSol: Double,
        val leverage: Double,
        val narrative: String?,     // Theme grouping
        val correlationGroup: String // Beta grouping: "SOL_BETA", "BTC_BETA", "TECH_BETA", etc.
    )

    // Correlation groups for clustering
    private val CORRELATION_GROUPS = mapOf(
        "SOL" to "SOL_BETA", "JUP" to "SOL_BETA", "BONK" to "SOL_BETA", "WIF" to "SOL_BETA", "PYTH" to "SOL_BETA",
        "BTC" to "BTC_BETA", "ETH" to "BTC_BETA",
        "BNB" to "EXCHANGE_BETA", "COIN" to "EXCHANGE_BETA",
        "NVDA" to "TECH_BETA", "AAPL" to "TECH_BETA", "MSFT" to "TECH_BETA", "GOOGL" to "TECH_BETA", "META" to "TECH_BETA", "AMZN" to "TECH_BETA", "TSLA" to "TECH_BETA",
        "SPY" to "INDEX_BETA", "QQQ" to "INDEX_BETA", "IWM" to "INDEX_BETA", "VTI" to "INDEX_BETA",
        "GLD" to "SAFE_HAVEN", "SLV" to "SAFE_HAVEN",
        "XOM" to "ENERGY", "CVX" to "ENERGY", "XLE" to "ENERGY",
        "JPM" to "FINANCE", "BAC" to "FINANCE", "GS" to "FINANCE",
        "JNJ" to "HEALTH", "UNH" to "HEALTH", "PFE" to "HEALTH", "ABBV" to "HEALTH", "LLY" to "HEALTH"
    )

    // ═══════════════════════════════════════════════════════════════════════
    // POSITION TRACKING
    // ═══════════════════════════════════════════════════════════════════════

    fun addPosition(id: String, symbol: String, market: String, sector: String, direction: String, sizeSol: Double, leverage: Double, narrative: String? = null) {
        activePositions[id] = PositionExposure(
            id = id, symbol = symbol, market = market, sector = sector,
            direction = direction, sizeSol = sizeSol, leverage = leverage,
            narrative = narrative,
            correlationGroup = CORRELATION_GROUPS[symbol] ?: "${market}_OTHER"
        )
        recalculate()
    }

    fun removePosition(id: String) {
        activePositions.remove(id)
        recalculate()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RECALCULATE — Core portfolio heat assessment
    // ═══════════════════════════════════════════════════════════════════════

    private fun recalculate() {
        val positions = activePositions.values.toList()
        if (positions.isEmpty()) {
            currentReport.set(PortfolioHeatReport(0.0, emptyMap(), 0.0, 0.0, false, 0.0, "NONE", 0))
            return
        }

        // 1. Sector crowding
        val sectorCrowding = mutableMapOf<String, Double>()
        val bySector = positions.groupBy { it.sector }
        val totalExposure = positions.sumOf { it.sizeSol * it.leverage }
        bySector.forEach { (sector, sectorPos) ->
            val sectorExposure = sectorPos.sumOf { it.sizeSol * it.leverage }
            sectorCrowding[sector] = if (totalExposure > 0) sectorExposure / totalExposure else 0.0
        }

        // 2. Correlation clustering
        val byCorrelation = positions.groupBy { it.correlationGroup }
        val largestCluster = byCorrelation.maxByOrNull { it.value.size }
        val clusterConcentration = if (positions.isNotEmpty() && largestCluster != null) {
            largestCluster.value.size.toDouble() / positions.size
        } else 0.0

        // 3. Correlation stress — how much of portfolio is in same direction + same group
        val sameDirectionSameGroup = byCorrelation.values.map { group ->
            val longCount = group.count { it.direction == "LONG" }
            val shortCount = group.count { it.direction == "SHORT" }
            maxOf(longCount, shortCount).toDouble() / maxOf(group.size, 1)
        }.average()
        val correlationStress = (sameDirectionSameGroup * clusterConcentration).coerceIn(0.0, 1.0)

        // 4. Leverage concentration
        val leveragedPositions = positions.filter { it.leverage > 1.0 }
        val leverageConcentration = if (positions.isNotEmpty()) {
            leveragedPositions.sumOf { it.sizeSol * it.leverage } / totalExposure.coerceAtLeast(0.01)
        } else 0.0

        // 5. Narrative stacking — same narrative = higher risk
        val byNarrative = positions.filter { it.narrative != null }.groupBy { it.narrative }
        val narrativeConcentration = byNarrative.values.maxOfOrNull { it.size }?.toDouble()?.div(positions.size.coerceAtLeast(1)) ?: 0.0

        // 6. Portfolio heat composite
        val portfolioHeat = (
            clusterConcentration * 0.30 +
            correlationStress * 0.25 +
            leverageConcentration.coerceIn(0.0, 1.0) * 0.20 +
            narrativeConcentration * 0.15 +
            (sectorCrowding.values.maxOrNull() ?: 0.0) * 0.10
        ).coerceIn(0.0, 1.0)

        // 7. New entry penalty
        val newEntryPenalty = when {
            portfolioHeat > 0.8 -> 0.9  // Nearly blocked
            portfolioHeat > 0.6 -> 0.5  // Half penalty
            portfolioHeat > 0.4 -> 0.2  // Light penalty
            else -> 0.0
        }

        // 8. Forced de-risk
        val forcedDeRisk = portfolioHeat > 0.85

        val report = PortfolioHeatReport(
            portfolioHeat = portfolioHeat,
            sectorCrowding = sectorCrowding,
            correlationStress = correlationStress,
            newEntryPenalty = newEntryPenalty,
            forcedDeRisk = forcedDeRisk,
            leverageConcentration = leverageConcentration.coerceIn(0.0, 1.0),
            largestCluster = largestCluster?.key ?: "NONE",
            clusterSize = largestCluster?.value?.size ?: 0
        )

        currentReport.set(report)

        // Publish to CrossTalk
        CrossTalkFusionEngine.publish(AATESignal(
            source = TAG,
            market = "PORTFOLIO",
            confidence = 1.0 - portfolioHeat,
            horizonSec = 60,
            riskFlags = buildList {
                if (portfolioHeat > 0.8) add("PORTFOLIO_OVERHEATED")
                if (forcedDeRisk) add("FORCED_DERISK")
                if (correlationStress > 0.7) add("HIGH_CORRELATION")
                if (leverageConcentration > 0.6) add("LEVERAGE_CONCENTRATED")
            }
        ))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // QUERY API
    // ═══════════════════════════════════════════════════════════════════════

    fun getReport(): PortfolioHeatReport? = currentReport.get()
    fun getPortfolioHeat(): Double = currentReport.get()?.portfolioHeat ?: 0.0
    fun getNewEntryPenalty(): Double = currentReport.get()?.newEntryPenalty ?: 0.0
    fun shouldDeRisk(): Boolean = currentReport.get()?.forcedDeRisk ?: false
    fun isNewEntryAllowed(): Boolean = (currentReport.get()?.portfolioHeat ?: 0.0) < 0.9

    fun getSafetyMultiplier(): Double {
        val heat = getPortfolioHeat()
        return (1.0 - heat * 0.8).coerceIn(0.1, 1.0)
    }

    fun clear() {
        activePositions.clear()
        currentReport.set(null)
    }
}

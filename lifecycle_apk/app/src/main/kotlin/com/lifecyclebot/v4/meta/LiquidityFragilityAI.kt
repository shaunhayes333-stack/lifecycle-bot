package com.lifecyclebot.v4.meta

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * ===============================================================================
 * LIQUIDITY FRAGILITY AI — V4 Meta-Intelligence
 * ===============================================================================
 *
 * Purpose: Measure whether a move is tradable or likely to collapse on contact.
 * A lot of fake alpha dies here. This layer saves money.
 *
 * Reads: spread, slippage, depth proxies, top holder concentration, pool age,
 *        liquidation proximity, wickiness, failed breakout frequency, price impact
 *
 * Outputs: fragilityScore, slippageRisk, liquidationCascadeRisk, maxSafeSize
 *
 * Directly controls: position sizing, leverage allowance, DipHunter validity,
 *                    ShitCoinAI blocking even when momentum looks good
 *
 * ===============================================================================
 */
object LiquidityFragilityAI {

    private const val TAG = "FragilityAI"

    // Cached reports per symbol
    private val reports = ConcurrentHashMap<String, FragilityReport>()

    // Historical wick data per symbol
    private val wickHistory = ConcurrentHashMap<String, MutableList<WickEvent>>()

    // Failed breakout tracking
    private val breakoutHistory = ConcurrentHashMap<String, MutableList<BreakoutEvent>>()

    data class WickEvent(val symbol: String, val wickPct: Double, val timestamp: Long)
    data class BreakoutEvent(val symbol: String, val failed: Boolean, val timestamp: Long)

    // ═══════════════════════════════════════════════════════════════════════
    // ANALYZE — Full fragility assessment for a symbol
    // ═══════════════════════════════════════════════════════════════════════

    fun analyze(
        market: String,
        symbol: String,
        spreadBps: Double = 0.0,
        depthUsd: Double = 0.0,
        volume24hUsd: Double = 0.0,
        topHolderPct: Double = 0.0,
        poolAgeDays: Int = 999,
        recentSlippagePct: Double = 0.0,
        priceImpactPct: Double = 0.0,
        liquidationClusterDistancePct: Double = 100.0,
        recentWickPcts: List<Double> = emptyList(),
        recentBreakoutsFailed: Int = 0,
        recentBreakoutsTotal: Int = 0
    ): FragilityReport {
        var fragility = 0.0

        // 1. Spread analysis (wider spread = more fragile)
        fragility += when {
            spreadBps > 200 -> 0.25
            spreadBps > 100 -> 0.15
            spreadBps > 50 -> 0.08
            spreadBps > 20 -> 0.03
            else -> 0.0
        }

        // 2. Depth analysis (shallow depth = fragile)
        val depthScore = when {
            depthUsd < 1_000 -> 0.1
            depthUsd < 10_000 -> 0.3
            depthUsd < 50_000 -> 0.5
            depthUsd < 200_000 -> 0.7
            depthUsd < 1_000_000 -> 0.85
            else -> 1.0
        }
        fragility += (1.0 - depthScore) * 0.20

        // 3. Volume analysis (low volume = fragile)
        fragility += when {
            volume24hUsd < 5_000 -> 0.20
            volume24hUsd < 50_000 -> 0.10
            volume24hUsd < 500_000 -> 0.05
            else -> 0.0
        }

        // 4. Top holder concentration (high concentration = manipulation risk)
        fragility += when {
            topHolderPct > 50 -> 0.15
            topHolderPct > 30 -> 0.10
            topHolderPct > 15 -> 0.05
            else -> 0.0
        }

        // 5. Pool age (new pools = fragile)
        fragility += when {
            poolAgeDays < 1 -> 0.15
            poolAgeDays < 7 -> 0.10
            poolAgeDays < 30 -> 0.05
            else -> 0.0
        }

        // 6. Wick frequency (high wickiness = fragile)
        val wickFrequency = if (recentWickPcts.isNotEmpty()) {
            recentWickPcts.count { it > 3.0 }.toDouble() / recentWickPcts.size
        } else 0.0
        fragility += wickFrequency * 0.10

        // 7. Failed breakout rate
        val failedBreakoutRate = if (recentBreakoutsTotal > 0) {
            recentBreakoutsFailed.toDouble() / recentBreakoutsTotal
        } else 0.0
        fragility += failedBreakoutRate * 0.10

        // 8. Slippage / price impact
        fragility += when {
            recentSlippagePct > 3.0 || priceImpactPct > 5.0 -> 0.15
            recentSlippagePct > 1.0 || priceImpactPct > 2.0 -> 0.08
            recentSlippagePct > 0.5 || priceImpactPct > 1.0 -> 0.03
            else -> 0.0
        }

        // 9. Liquidation cluster proximity
        val liquidationCascadeRisk = when {
            liquidationClusterDistancePct < 2.0 -> 0.9
            liquidationClusterDistancePct < 5.0 -> 0.6
            liquidationClusterDistancePct < 10.0 -> 0.3
            else -> 0.05
        }
        fragility += liquidationCascadeRisk * 0.05

        fragility = fragility.coerceIn(0.0, 1.0)

        val level = when {
            fragility > 0.75 -> FragilityLevel.CRITICAL
            fragility > 0.50 -> FragilityLevel.FRAGILE
            fragility > 0.25 -> FragilityLevel.MODERATE
            else -> FragilityLevel.STABLE
        }

        // Max safe size based on depth and fragility
        val maxSafeSize = when (level) {
            FragilityLevel.CRITICAL -> 0.01    // 0.01 SOL (essentially blocked)
            FragilityLevel.FRAGILE -> 0.1      // 0.1 SOL max
            FragilityLevel.MODERATE -> 0.5     // 0.5 SOL max
            FragilityLevel.STABLE -> 5.0       // 5 SOL max
        }

        val safeHold = when (level) {
            FragilityLevel.CRITICAL -> 1        // 1 minute max
            FragilityLevel.FRAGILE -> 10        // 10 minutes max
            FragilityLevel.MODERATE -> 60       // 1 hour max
            FragilityLevel.STABLE -> 480        // 8 hours max
        }

        val report = FragilityReport(
            market = market,
            symbol = symbol,
            fragilityScore = fragility,
            fragilityLevel = level,
            slippageRisk = recentSlippagePct,
            liquidationCascadeRisk = liquidationCascadeRisk,
            maxSafeSize = maxSafeSize,
            safeHoldMinutes = safeHold,
            spreadBps = spreadBps,
            depthScore = depthScore,
            wickFrequency = wickFrequency,
            failedBreakoutRate = failedBreakoutRate
        )

        reports[symbol] = report

        // Publish to CrossTalk
        CrossTalkFusionEngine.publish(AATESignal(
            source = TAG,
            market = market,
            symbol = symbol,
            confidence = 1.0 - fragility,
            horizonSec = 120,
            fragilityScore = fragility,
            riskFlags = buildList {
                if (level == FragilityLevel.CRITICAL) add("CRITICAL_FRAGILITY")
                if (liquidationCascadeRisk > 0.5) add("LIQUIDATION_PROXIMITY")
                if (wickFrequency > 0.5) add("HIGH_WICK_FREQUENCY")
            }
        ))

        return report
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RECORD EVENTS
    // ═══════════════════════════════════════════════════════════════════════

    fun recordWick(symbol: String, wickPct: Double) {
        val history = wickHistory.getOrPut(symbol) { mutableListOf() }
        synchronized(history) {
            history.add(WickEvent(symbol, wickPct, System.currentTimeMillis()))
            if (history.size > 100) history.removeAt(0)
        }
    }

    fun recordBreakout(symbol: String, failed: Boolean) {
        val history = breakoutHistory.getOrPut(symbol) { mutableListOf() }
        synchronized(history) {
            history.add(BreakoutEvent(symbol, failed, System.currentTimeMillis()))
            if (history.size > 50) history.removeAt(0)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // QUERY API
    // ═══════════════════════════════════════════════════════════════════════

    fun getReport(symbol: String): FragilityReport? = reports[symbol]

    fun getFragilityScore(symbol: String): Double =
        reports[symbol]?.fragilityScore ?: 0.3

    fun getMaxSafeSize(symbol: String): Double =
        reports[symbol]?.maxSafeSize ?: 1.0

    fun isTradeAllowed(symbol: String): Boolean {
        val report = reports[symbol] ?: return true
        return report.fragilityLevel != FragilityLevel.CRITICAL
    }

    fun getSafetyMultiplier(symbol: String): Double {
        val fragility = getFragilityScore(symbol)
        return (1.0 - fragility * 0.8).coerceIn(0.1, 1.0)
    }

    fun clear() {
        reports.clear()
        wickHistory.clear()
        breakoutHistory.clear()
    }
}

package com.lifecyclebot.v4.meta

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.atomic.AtomicReference

/**
 * ===============================================================================
 * LEVERAGE SURVIVAL AI — V4 Meta-Intelligence
 * ===============================================================================
 *
 * Mandatory. Not to find the highest leverage. To decide when leverage should
 * exist at all.
 *
 * Rule: If this model says no leverage, no other model is allowed to override it.
 *
 * Prevents the classic mistake: great directional call, terrible leveraged execution.
 *
 * ===============================================================================
 */
object LeverageSurvivalAI {

    private const val TAG = "LeverageSurvivalAI"

    private val currentVerdict = AtomicReference(LeverageVerdict(
        allowedLeverage = 3.0,
        liquidationDistanceSafety = 1.0,
        maxHoldMinutes = 480,
        forcedTightRisk = false,
        noLeverageOverride = false,
        reasons = listOf("Default — no data yet")
    ))

    // Recent leveraged trade performance
    private val recentLeveragedTrades = mutableListOf<LeveragedTradeResult>()

    data class LeveragedTradeResult(
        val leverage: Double,
        val outcomePct: Double,
        val holdSec: Int,
        val wasLiquidated: Boolean,
        val maePct: Double,
        val timestamp: Long
    )

    // ═══════════════════════════════════════════════════════════════════════
    // ASSESS — Core leverage survival check
    // ═══════════════════════════════════════════════════════════════════════

    fun assess(
        currentVolatility: Double = 0.0,
        fragilityScore: Double = 0.0,
        fundingRate: Double = 0.0,
        openInterestChangePct: Double = 0.0,
        spreadBps: Double = 0.0,
        depthUsd: Double = 0.0,
        liquidationClusterDistancePct: Double = 100.0,
        falseBreakoutRate: Double = 0.0,
        regimeStability: Double = 1.0,
        stopEfficiency: Double = 1.0
    ): LeverageVerdict {
        val reasons = mutableListOf<String>()
        var maxLeverage = 5.0

        // 1. Volatility check
        if (currentVolatility > 8.0) {
            maxLeverage = 0.0
            reasons.add("VETO: Extreme vol ${String.format("%.1f", currentVolatility)}%")
        } else if (currentVolatility > 5.0) {
            maxLeverage = minOf(maxLeverage, 1.0)
            reasons.add("High vol: cap at 1x")
        } else if (currentVolatility > 3.0) {
            maxLeverage = minOf(maxLeverage, 2.0)
            reasons.add("Elevated vol: cap at 2x")
        }

        // 2. Fragility check
        if (fragilityScore > 0.8) {
            maxLeverage = 0.0
            reasons.add("VETO: Critical fragility ${String.format("%.2f", fragilityScore)}")
        } else if (fragilityScore > 0.5) {
            maxLeverage = minOf(maxLeverage, 1.0)
            reasons.add("Fragile conditions: cap at 1x")
        }

        // 3. Funding rate pressure
        if (kotlin.math.abs(fundingRate) > 0.1) {
            maxLeverage = minOf(maxLeverage, 2.0)
            reasons.add("Extreme funding: ${String.format("%.3f", fundingRate)}%")
        }

        // 4. Liquidation proximity
        if (liquidationClusterDistancePct < 3.0) {
            maxLeverage = 0.0
            reasons.add("VETO: Liquidation cluster ${String.format("%.1f", liquidationClusterDistancePct)}% away")
        } else if (liquidationClusterDistancePct < 5.0) {
            maxLeverage = minOf(maxLeverage, 1.0)
            reasons.add("Near liquidation cluster: cap at 1x")
        }

        // 5. Spread/depth check
        if (spreadBps > 100 || depthUsd < 10_000) {
            maxLeverage = minOf(maxLeverage, 1.0)
            reasons.add("Thin market: spread=${String.format("%.0f", spreadBps)}bps depth=\$${String.format("%.0f", depthUsd)}")
        }

        // 6. False breakout rate
        if (falseBreakoutRate > 0.6) {
            maxLeverage = minOf(maxLeverage, 1.0)
            reasons.add("High false breakout rate: ${String.format("%.0f", falseBreakoutRate * 100)}%")
        }

        // 7. Regime stability
        if (regimeStability < 0.3) {
            maxLeverage = minOf(maxLeverage, 1.0)
            reasons.add("Unstable regime")
        }

        // 8. Recent leveraged trade quality
        val recentLev = synchronized(recentLeveragedTrades) {
            recentLeveragedTrades.takeLast(20).toList()
        }
        if (recentLev.size >= 10) {
            val levWinRate = recentLev.count { it.outcomePct > 0 }.toDouble() / recentLev.size
            if (levWinRate < 0.25) {
                maxLeverage = minOf(maxLeverage, 1.0)
                reasons.add("Poor recent leverage WR: ${String.format("%.0f", levWinRate * 100)}%")
            }
            val liquidated = recentLev.count { it.wasLiquidated }
            if (liquidated > 2) {
                maxLeverage = 0.0
                reasons.add("VETO: $liquidated recent liquidations")
            }
        }

        // 9. Stop efficiency
        if (stopEfficiency < 0.5) {
            maxLeverage = minOf(maxLeverage, 2.0)
            reasons.add("Poor stop efficiency: ${String.format("%.0f", stopEfficiency * 100)}%")
        }

        val noOverride = maxLeverage <= 0.0
        val forceTight = maxLeverage <= 2.0 && maxLeverage > 0.0
        val maxHold = when {
            maxLeverage <= 1.0 -> 30   // 30 min max for spot-only forced
            maxLeverage <= 2.0 -> 60   // 1 hour for low leverage
            maxLeverage <= 3.0 -> 240  // 4 hours for moderate
            else -> 480                // 8 hours for full leverage
        }
        val liqSafety = (liquidationClusterDistancePct / 20.0).coerceIn(0.0, 1.0)

        if (reasons.isEmpty()) reasons.add("All clear — full leverage allowed")

        val verdict = LeverageVerdict(
            allowedLeverage = maxLeverage,
            liquidationDistanceSafety = liqSafety,
            maxHoldMinutes = maxHold,
            forcedTightRisk = forceTight,
            noLeverageOverride = noOverride,
            reasons = reasons
        )

        currentVerdict.set(verdict)

        // Publish to CrossTalk
        CrossTalkFusionEngine.publish(AATESignal(
            source = TAG,
            market = "GLOBAL",
            confidence = if (noOverride) 0.0 else maxLeverage / 5.0,
            horizonSec = 300,
            leverageAllowed = maxLeverage,
            riskFlags = if (noOverride) listOf("NO_LEVERAGE") else emptyList()
        ))

        return verdict
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RECORD — Feed leveraged trade results
    // ═══════════════════════════════════════════════════════════════════════

    fun recordLeveragedTrade(leverage: Double, outcomePct: Double, holdSec: Int, wasLiquidated: Boolean, maePct: Double) {
        synchronized(recentLeveragedTrades) {
            recentLeveragedTrades.add(LeveragedTradeResult(leverage, outcomePct, holdSec, wasLiquidated, maePct, System.currentTimeMillis()))
            if (recentLeveragedTrades.size > 100) recentLeveragedTrades.removeAt(0)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // QUERY API
    // ═══════════════════════════════════════════════════════════════════════

    fun getVerdict(): LeverageVerdict = currentVerdict.get()
    fun getAllowedLeverage(): Double = currentVerdict.get().allowedLeverage
    fun isLeverageAllowed(): Boolean = !currentVerdict.get().noLeverageOverride
    fun getMaxHoldMinutes(): Int = currentVerdict.get().maxHoldMinutes

    fun clear() {
        currentVerdict.set(LeverageVerdict(3.0, 1.0, 480, false, false, listOf("Reset")))
        synchronized(recentLeveragedTrades) { recentLeveragedTrades.clear() }
    }
}

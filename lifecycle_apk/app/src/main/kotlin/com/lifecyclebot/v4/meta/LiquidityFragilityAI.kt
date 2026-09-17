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

    /**
     * V5.0.6853 §THE_LAYER_THAT_SAVES_MONEY_WAS_NEVER_FED — `analyze()` had ZERO
     * callers tree-wide, so [reports] was permanently empty and every query below
     * returned its "unknown" default forever: getFragilityScore()=0.10,
     * getMaxSafeSize()=1.0, isTradeAllowed()=true, getSafetyMultiplier()=0.95.
     * SymbolicExitReasoner weights this channel at 0.07 of exit conviction and was
     * reading a constant; TradeLessonRecorder has been writing a constant 0.1 into
     * every lesson it has ever recorded, so the learner was told all tokens are
     * equally liquid. recordWick()/recordBreakout() were equally uncalled, so the
     * wick and failed-breakout terms could never contribute either.
     *
     * [id] lets the caller key the report by mint. Meme symbols collide constantly
     * (dozens of tokens ship as "SOL" or "TRUMP"), so a symbol-only index would have
     * cross-contaminated fragility between unrelated tokens the moment it was fed.
     * The report is stored under both so legacy symbol lookups keep working.
     */
    fun analyze(
        market: String,
        symbol: String,
        id: String = "",
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
        // V5.9.56: skip when depthUsd==0 (no data) — missing data is neutral, not a penalty
        val depthScore = if (depthUsd <= 0.0) 1.0 else when {
            depthUsd < 1_000 -> 0.1
            depthUsd < 10_000 -> 0.3
            depthUsd < 50_000 -> 0.5
            depthUsd < 200_000 -> 0.7
            depthUsd < 1_000_000 -> 0.85
            else -> 1.0
        }
        fragility += (1.0 - depthScore) * 0.20

        // 3. Volume analysis (low volume = fragile)
        // V5.9.56: skip when volume24hUsd==0 (no data) — missing data is neutral, not a penalty
        fragility += if (volume24hUsd <= 0.0) 0.0 else when {
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
        // V5.0.6853 — fall back to the recorded wick history when the caller does
        // not pass a window. recordWick() exists precisely to accumulate this and
        // had no consumer; now an explicit window wins and the recorded history is
        // used otherwise, so either feed path works.
        val wickKey = id.ifBlank { symbol }
        val wickWindow = if (recentWickPcts.isNotEmpty()) recentWickPcts else {
            val h = wickHistory[wickKey] ?: wickHistory[symbol]
            if (h == null) emptyList() else synchronized(h) { h.takeLast(30).map { it.wickPct } }
        }
        val wickFrequency = if (wickWindow.isNotEmpty()) {
            wickWindow.count { it > 3.0 }.toDouble() / wickWindow.size
        } else 0.0
        fragility += wickFrequency * 0.10

        // 7. Failed breakout rate
        val breakoutWindow = if (recentBreakoutsTotal > 0) {
            recentBreakoutsFailed to recentBreakoutsTotal
        } else {
            val h = breakoutHistory[wickKey] ?: breakoutHistory[symbol]
            if (h == null) 0 to 0 else synchronized(h) {
                val w = h.takeLast(25)
                w.count { it.failed } to w.size
            }
        }
        val failedBreakoutRate = if (breakoutWindow.second > 0) {
            breakoutWindow.first.toDouble() / breakoutWindow.second
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

        // Index under the caller's stable id (mint) AND the display symbol, so both
        // mint-aware and legacy symbol-keyed readers resolve. See the KDoc above on
        // why a symbol-only index would cross-contaminate meme tokens.
        if (id.isNotBlank()) reports[id] = report
        if (symbol.isNotBlank()) reports[symbol] = report

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
        val history = wickHistory.computeIfAbsent(symbol) { mutableListOf() }
        synchronized(history) {
            history.add(WickEvent(symbol, wickPct, System.currentTimeMillis()))
            if (history.size > 100) history.removeAt(0)
        }
    }

    fun recordBreakout(symbol: String, failed: Boolean) {
        val history = breakoutHistory.computeIfAbsent(symbol) { mutableListOf() }
        synchronized(history) {
            history.add(BreakoutEvent(symbol, failed, System.currentTimeMillis()))
            if (history.size > 50) history.removeAt(0)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // QUERY API
    // ═══════════════════════════════════════════════════════════════════════

    fun getReport(symbol: String): FragilityReport? = reports[symbol]

    // V5.9.56: default 0.1 (STABLE) — unknown symbols should not be pre-penalised as MODERATE
    fun getFragilityScore(symbol: String): Double =
        reports[symbol]?.fragilityScore ?: 0.1

    /**
     * V5.0.6853 — mint-first lookup. Prefer the canonical mint; fall back to the
     * display symbol only when the mint has no report yet. Callers that hold a mint
     * must use this: two live tokens sharing a ticker would otherwise read each
     * other's fragility.
     */
    fun getFragilityScoreFor(mint: String, symbol: String): Double =
        (if (mint.isNotBlank()) reports[mint] else null)?.fragilityScore
            ?: (if (symbol.isNotBlank()) reports[symbol] else null)?.fragilityScore
            ?: 0.1

    fun getReportFor(mint: String, symbol: String): FragilityReport? =
        (if (mint.isNotBlank()) reports[mint] else null)
            ?: (if (symbol.isNotBlank()) reports[symbol] else null)

    fun getMaxSafeSize(symbol: String): Double =
        reports[symbol]?.maxSafeSize ?: 1.0

    fun getMaxSafeSizeFor(mint: String, symbol: String): Double =
        getReportFor(mint, symbol)?.maxSafeSize ?: 1.0

    fun isTradeAllowed(symbol: String): Boolean {
        val report = reports[symbol] ?: return true
        return report.fragilityLevel != FragilityLevel.CRITICAL
    }

    fun isTradeAllowedFor(mint: String, symbol: String): Boolean =
        getReportFor(mint, symbol)?.fragilityLevel != FragilityLevel.CRITICAL

    // V5.9.56: coefficient 0.5 (was 0.8) — 0.8 was cutting position 40% at fragility=0.5;
    // 0.5 gives a gentler slope so moderate-fragility assets still get reasonable size
    fun getSafetyMultiplier(symbol: String): Double {
        val fragility = getFragilityScore(symbol)
        return (1.0 - fragility * 0.5).coerceIn(0.2, 1.0)
    }

    fun getSafetyMultiplierFor(mint: String, symbol: String): Double =
        (1.0 - getFragilityScoreFor(mint, symbol) * 0.5).coerceIn(0.2, 1.0)

    fun clear() {
        reports.clear()
        wickHistory.clear()
        breakoutHistory.clear()
    }
}

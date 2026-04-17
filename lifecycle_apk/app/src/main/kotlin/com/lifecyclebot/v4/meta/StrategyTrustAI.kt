package com.lifecyclebot.v4.meta

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * ===============================================================================
 * STRATEGY TRUST AI — V4 Meta-Intelligence
 * ===============================================================================
 *
 * Purpose: Score how much each strategy should be trusted RIGHT NOW.
 * Not "is the trade good?" → "Is this strategy a good decision-maker in this environment?"
 *
 * Reads per strategy:
 *   - Recent win rate, expectancy, drawdown slope
 *   - Average MAE/MFE, false positive rate
 *   - Market regime fit, execution quality
 *   - Slippage damage, hold-time efficiency
 *   - Time-of-day performance
 *
 * Outputs: trustScore per strategy (0.0-1.0) + TrustLevel enum
 *
 * Use it to: cap scores, reroute capital, lower aggression, stop dead
 * strategies from poisoning learning globally.
 *
 * ===============================================================================
 */
object StrategyTrustAI {

    private const val TAG = "StrategyTrustAI"
    private const val MIN_TRADES_FOR_TRUST = 20
    private const val RECENT_WINDOW = 50          // Evaluate last 50 trades
    private const val DECAY_FACTOR = 0.95         // Exponential decay for older trades

    // Per-strategy trust records
    private val trustRecords = ConcurrentHashMap<String, StrategyTrustRecord>()

    // Per-strategy trade history (ring buffer style)
    private val tradeHistory = ConcurrentHashMap<String, MutableList<TradeLesson>>()

    // Known strategies
    private val STRATEGIES = listOf(
        "ShitCoinAI", "DipHunter", "BlueChipAI", "SolArbAI",
        "Treasury", "TokenizedStockAI", "LeverageTraderAI",
        "Express", "MoonBag", "Manipulation"
    )

    // ═══════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════

    fun init() {
        STRATEGIES.forEach { strategy ->
            trustRecords.putIfAbsent(strategy, createDefaultRecord(strategy))
            tradeHistory.putIfAbsent(strategy, mutableListOf())
        }
        ErrorLogger.info(TAG, "StrategyTrustAI initialized with ${STRATEGIES.size} strategies")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RECORD TRADE — Called after every trade completes
    // ═══════════════════════════════════════════════════════════════════════

    fun recordTrade(lesson: TradeLesson) {
        val history = tradeHistory.getOrPut(lesson.strategy) { mutableListOf() }
        synchronized(history) {
            history.add(lesson)
            // Keep only recent window
            if (history.size > RECENT_WINDOW * 2) {
                val excess = history.size - RECENT_WINDOW * 2
                repeat(excess) { history.removeAt(0) }
            }
        }
        recalculateTrust(lesson.strategy)

        // Publish to CrossTalk
        val record = trustRecords[lesson.strategy] ?: return
        CrossTalkFusionEngine.publish(AATESignal(
            source = TAG,
            market = lesson.market,
            symbol = lesson.symbol,
            confidence = record.trustScore,
            horizonSec = 300,
            trustScore = record.trustScore,
            riskFlags = if (record.trustLevel == TrustLevel.DISTRUSTED) listOf("STRATEGY_DISTRUSTED:${lesson.strategy}") else emptyList()
        ))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RECALCULATE TRUST — Core algorithm
    // ═══════════════════════════════════════════════════════════════════════

    private fun recalculateTrust(strategy: String) {
        val history = tradeHistory[strategy] ?: return
        val recent = synchronized(history) {
            history.takeLast(RECENT_WINDOW).toList()
        }

        if (recent.size < MIN_TRADES_FOR_TRUST) {
            trustRecords[strategy] = createDefaultRecord(strategy).copy(
                trustLevel = TrustLevel.UNTESTED,
                trustScore = 0.5
            )
            return
        }

        // 1. Recent win rate (exponentially weighted)
        var weightedWins = 0.0
        var totalWeight = 0.0
        recent.forEachIndexed { index, trade ->
            val weight = Math.pow(DECAY_FACTOR, (recent.size - index - 1).toDouble())
            if (trade.outcomePct > 0) weightedWins += weight
            totalWeight += weight
        }
        val recentWinRate = if (totalWeight > 0) weightedWins / totalWeight else 0.5

        // 2. Expectancy (average R per trade)
        val expectancy = recent.map { it.outcomePct }.average()

        // 3. Drawdown slope
        var maxPeak = 0.0
        var maxDrawdown = 0.0
        var cumulative = 0.0
        recent.forEach { trade ->
            cumulative += trade.outcomePct
            if (cumulative > maxPeak) maxPeak = cumulative
            val dd = maxPeak - cumulative
            if (dd > maxDrawdown) maxDrawdown = dd
        }
        val drawdownSlope = if (recent.size > 1) -maxDrawdown / recent.size else 0.0

        // 4. MAE/MFE analysis
        val avgMAE = recent.map { it.maePct }.average()
        val avgMFE = recent.map { it.mfePct }.average()

        // 5. False positive rate (trades that immediately went -2% or worse)
        val falsePositives = recent.count { it.maePct < -2.0 && it.outcomePct < 0 }
        val falsePositiveRate = falsePositives.toDouble() / recent.size

        // 6. Regime fit — how well does strategy perform in current regime
        val currentRegime = CrossTalkFusionEngine.getSnapshot()?.globalRiskMode
        val regimeTrades = if (currentRegime != null) {
            recent.filter { it.entryRegime == currentRegime }
        } else recent
        val regimeFit = if (regimeTrades.size >= 5) {
            regimeTrades.count { it.outcomePct > 0 }.toDouble() / regimeTrades.size
        } else 0.5

        // 7. Execution quality
        val executionQuality = recent.map { it.executionConfidence }.average()

        // 8. Slippage damage
        val slippageDamage = recent.map { it.slippagePct }.sum()

        // 9. Hold time efficiency
        val winHolds = recent.filter { it.outcomePct > 0 }.map { it.holdSec }
        val lossHolds = recent.filter { it.outcomePct <= 0 }.map { it.holdSec }
        val holdTimeEfficiency = if (winHolds.isNotEmpty() && lossHolds.isNotEmpty()) {
            val avgWinHold = winHolds.average()
            val avgLossHold = lossHolds.average()
            if (avgLossHold > 0) (avgWinHold / avgLossHold).coerceIn(0.0, 2.0) / 2.0 else 0.5
        } else 0.5

        // 10. Time-of-day performance
        val todPerf = mutableMapOf<String, Double>()
        recent.groupBy { trade ->
            val hour = java.util.Calendar.getInstance().apply {
                timeInMillis = trade.timestamp
            }.get(java.util.Calendar.HOUR_OF_DAY)
            when (hour) {
                in 0..7 -> "ASIA"
                in 8..12 -> "LONDON"
                in 13..20 -> "NY"
                else -> "OFF_HOURS"
            }
        }.forEach { (session, trades) ->
            if (trades.size >= 3) {
                todPerf[session] = trades.count { it.outcomePct > 0 }.toDouble() / trades.size
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // COMPOSITE TRUST SCORE
        // ═══════════════════════════════════════════════════════════════════

        val trustScore = (
            recentWinRate * 0.25 +
            (expectancy / 10.0).coerceIn(0.0, 0.25) +  // Normalize expectancy
            regimeFit * 0.20 +
            (1.0 - falsePositiveRate) * 0.15 +
            holdTimeEfficiency * 0.10 +
            executionQuality * 0.05
        ).coerceIn(0.0, 1.0)

        val trustLevel = when {
            recent.size < MIN_TRADES_FOR_TRUST -> TrustLevel.UNTESTED
            trustScore < 0.25 -> TrustLevel.DISTRUSTED
            trustScore < 0.45 -> TrustLevel.NEUTRAL
            trustScore < 0.70 -> TrustLevel.TRUSTED
            else -> TrustLevel.ELITE
        }

        trustRecords[strategy] = StrategyTrustRecord(
            strategyName = strategy,
            recentWinRate = recentWinRate,
            expectancy = expectancy,
            drawdownSlope = drawdownSlope,
            avgMAE = avgMAE,
            avgMFE = avgMFE,
            falsePositiveRate = falsePositiveRate,
            regimeFit = regimeFit,
            executionQuality = executionQuality,
            slippageDamage = slippageDamage,
            holdTimeEfficiency = holdTimeEfficiency,
            timeOfDayPerformance = todPerf,
            trustLevel = trustLevel,
            trustScore = trustScore
        )

        ErrorLogger.debug(TAG, "Trust[$strategy]: score=${String.format("%.3f", trustScore)} " +
            "level=$trustLevel WR=${String.format("%.1f", recentWinRate * 100)}% " +
            "exp=${String.format("%.2f", expectancy)} fp=${String.format("%.1f", falsePositiveRate * 100)}%")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // QUERY API
    // ═══════════════════════════════════════════════════════════════════════

    fun getTrustScore(strategy: String): Double =
        trustRecords[strategy]?.trustScore ?: 0.5

    fun getTrustLevel(strategy: String): TrustLevel =
        trustRecords[strategy]?.trustLevel ?: TrustLevel.UNTESTED

    fun getTrustRecord(strategy: String): StrategyTrustRecord? =
        trustRecords[strategy]

    fun getAllTrustScores(): Map<String, StrategyTrustRecord> =
        trustRecords.toMap()

    fun getTrustMultiplier(strategy: String): Double {
        val score = getTrustScore(strategy)
        val base = when {
            score < 0.2 -> 0.0    // Vetoed
            score < 0.35 -> 0.3   // Heavily suppressed
            score < 0.5 -> 0.6    // Suppressed
            score < 0.65 -> 0.85  // Slightly suppressed
            score < 0.8 -> 1.0    // Normal
            else -> 1.15          // Boosted
        }
        if (base == 0.0) return 0.0  // Vetoed stays vetoed

        // V5.9.12: Symbolic fragility feedback — market risk/edge shapes trust mult.
        // High risk → suppress further; strong edge + confidence → modest boost.
        val symFactor = try {
            val sc = com.lifecyclebot.engine.SymbolicContext
            val risk = sc.overallRisk          // 0..1
            val edge = sc.edgeStrength          // 0..1
            // Center near 1.0, range ~0.75..1.15
            (1.0 - (risk - 0.3).coerceAtLeast(0.0) * 0.35 + (edge - 0.5).coerceAtLeast(0.0) * 0.15)
                .coerceIn(0.7, 1.2)
        } catch (_: Exception) { 1.0 }
        return (base * symFactor).coerceIn(0.0, 1.25)
    }

    fun isStrategyAllowed(strategy: String): Boolean =
        getTrustLevel(strategy) != TrustLevel.DISTRUSTED

    fun restoreTrustRecord(record: StrategyTrustRecord) {
        trustRecords[record.strategyName] = record
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private fun createDefaultRecord(strategy: String) = StrategyTrustRecord(
        strategyName = strategy,
        recentWinRate = 0.5,
        expectancy = 0.0,
        drawdownSlope = 0.0,
        avgMAE = 0.0,
        avgMFE = 0.0,
        falsePositiveRate = 0.0,
        regimeFit = 0.5,
        executionQuality = 0.5,
        slippageDamage = 0.0,
        holdTimeEfficiency = 0.5,
        timeOfDayPerformance = emptyMap(),
        trustLevel = TrustLevel.UNTESTED,
        trustScore = 0.5
    )

    fun clear() {
        trustRecords.clear()
        tradeHistory.clear()
        init()
    }
}

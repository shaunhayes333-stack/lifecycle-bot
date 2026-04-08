package com.lifecyclebot.v3.arb

import com.lifecyclebot.engine.ErrorLogger
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * ArbLearning - Outcome learning for arb trades
 *
 * Decisive-trade rules used here:
 * - WIN    = pnlPct >= 0.5
 * - LOSS   = pnlPct <= -2.0
 * - SCRATCH = between those thresholds, ignored
 */
object ArbLearning {

    private const val TAG = "ArbLearning"
    private const val MAX_OUTCOMES = 500
    private const val MAX_PER_TOKEN = 10

    private const val WIN_THRESHOLD_PCT = 0.5
    private const val LOSS_THRESHOLD_PCT = -2.0

    // Decisive outcomes only
    private val outcomes = ConcurrentLinkedDeque<ArbOutcome>()

    // Per-token decisive outcomes
    private val tokenOutcomes = ConcurrentHashMap<String, MutableList<ArbOutcome>>()

    @Volatile var venueLagMinScore = 55
    @Volatile var flowImbalanceMinScore = 55
    @Volatile var panicReversionMinScore = 55

    data class TypeStats(
        var trades: Int = 0,      // decisive trades only
        var wins: Int = 0,
        var losses: Int = 0,
        var totalPnl: Double = 0.0,
        var avgHoldTime: Double = 0.0
    ) {
        val winRate: Double
            get() = if (trades > 0) (wins.toDouble() / trades.toDouble()) * 100.0 else 0.0

        val avgPnl: Double
            get() = if (trades > 0) totalPnl / trades.toDouble() else 0.0
    }

    private val typeStats = ConcurrentHashMap<ArbType, TypeStats>().apply {
        ArbType.values().forEach { put(it, TypeStats()) }
    }

    /**
     * Record an arb trade outcome.
     * Scratch trades are ignored.
     */
    fun recordOutcome(outcome: ArbOutcome) {
        try {
            val isWin = isWin(outcome.pnlPct)
            val isLoss = isLoss(outcome.pnlPct)

            if (!isWin && !isLoss) {
                ErrorLogger.debug(
                    TAG,
                    "[ARB_LEARN] ${outcome.symbol} | ${outcome.arbType} | pnl=${String.format("%.1f", outcome.pnlPct)}% | SCRATCH IGNORED"
                )
                return
            }

            outcomes.addLast(outcome)
            while (outcomes.size > MAX_OUTCOMES) {
                outcomes.pollFirst()
            }

            val tokenList = tokenOutcomes.getOrPut(outcome.mint) { mutableListOf() }
            synchronized(tokenList) {
                tokenList.add(outcome)
                while (tokenList.size > MAX_PER_TOKEN) {
                    tokenList.removeAt(0)
                }
            }

            typeStats[outcome.arbType]?.let { stats ->
                stats.trades++
                if (isWin) {
                    stats.wins++
                } else {
                    stats.losses++
                }
                stats.totalPnl += outcome.pnlPct
                stats.avgHoldTime =
                    ((stats.avgHoldTime * (stats.trades - 1)) + outcome.holdSeconds.toDouble()) / stats.trades.toDouble()
            }

            learnFromOutcome(outcome)

            ErrorLogger.debug(
                TAG,
                "[ARB_LEARN] ${outcome.symbol} | ${outcome.arbType} | pnl=${String.format("%.1f", outcome.pnlPct)}% | ${if (isWin) "WIN" else "LOSS"}"
            )
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "recordOutcome error: ${e.message}")
        }
    }

    /**
     * Learn from an outcome and adjust thresholds.
     * Uses decisive-trade win rate only.
     */
    private fun learnFromOutcome(outcome: ArbOutcome) {
        val stats = typeStats[outcome.arbType] ?: return

        if (stats.trades < 10) return

        when (outcome.arbType) {
            ArbType.VENUE_LAG -> {
                if (stats.winRate < 50.0 && venueLagMinScore < 70) {
                    venueLagMinScore++
                    ErrorLogger.debug(TAG, "[ARB_LEARN] VenueLag threshold raised to $venueLagMinScore")
                } else if (stats.winRate > 65.0 && stats.trades >= 20 && venueLagMinScore > 45) {
                    venueLagMinScore--
                }
            }

            ArbType.FLOW_IMBALANCE -> {
                if (stats.winRate < 50.0 && flowImbalanceMinScore < 70) {
                    flowImbalanceMinScore++
                    ErrorLogger.debug(TAG, "[ARB_LEARN] FlowImbalance threshold raised to $flowImbalanceMinScore")
                } else if (stats.winRate > 65.0 && stats.trades >= 20 && flowImbalanceMinScore > 45) {
                    flowImbalanceMinScore--
                }
            }

            ArbType.PANIC_REVERSION -> {
                if (stats.winRate < 55.0 && panicReversionMinScore < 75) {
                    panicReversionMinScore++
                    ErrorLogger.debug(TAG, "[ARB_LEARN] PanicReversion threshold raised to $panicReversionMinScore")
                } else if (stats.winRate > 70.0 && stats.trades >= 20 && panicReversionMinScore > 50) {
                    panicReversionMinScore--
                }
            }
        }
    }

    /**
     * Get recent decisive loss count for a token.
     * Used to avoid repeated losses on same token.
     */
    fun getRecentLossCount(mint: String): Int {
        return try {
            val list = tokenOutcomes[mint] ?: return 0
            val oneHourAgo = System.currentTimeMillis() - (60 * 60 * 1000L)

            synchronized(list) {
                list.count { isLoss(it.pnlPct) && it.timestampMs >= oneHourAgo }
            }
        } catch (_: Exception) {
            0
        }
    }

    fun getTypeStats(): Map<ArbType, TypeStats> {
        return typeStats.toMap()
    }

    fun getOverallStats(): OverallStats {
        val all = outcomes.toList()
        val wins = all.count { isWin(it.pnlPct) }
        val losses = all.count { isLoss(it.pnlPct) }
        val decisiveTrades = wins + losses
        val totalPnl = all.sumOf { it.pnlPct }

        return OverallStats(
            totalTrades = decisiveTrades,
            wins = wins,
            losses = losses,
            winRate = if (decisiveTrades > 0) (wins.toDouble() / decisiveTrades.toDouble()) * 100.0 else 0.0,
            totalPnl = totalPnl,
            avgPnl = if (decisiveTrades > 0) totalPnl / decisiveTrades.toDouble() else 0.0,
            avgHoldSeconds = if (decisiveTrades > 0) all.sumOf { it.holdSeconds } / decisiveTrades else 0
        )
    }

    data class OverallStats(
        val totalTrades: Int,
        val wins: Int,
        val losses: Int,
        val winRate: Double,
        val totalPnl: Double,
        val avgPnl: Double,
        val avgHoldSeconds: Int
    )

    fun getStats(): String {
        val overall = getOverallStats()
        return "ArbLearning: ${overall.totalTrades} trades | " +
            "${String.format("%.0f", overall.winRate)}% WR | " +
            "${String.format("%.1f", overall.avgPnl)}% avg | " +
            "thresholds: VL=$venueLagMinScore FI=$flowImbalanceMinScore PR=$panicReversionMinScore"
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("venueLagMinScore", venueLagMinScore)
            put("flowImbalanceMinScore", flowImbalanceMinScore)
            put("panicReversionMinScore", panicReversionMinScore)

            val overall = getOverallStats()
            put("totalTrades", overall.totalTrades)
            put("winRate", overall.winRate)
            put("avgPnl", overall.avgPnl)

            val typeStatsJson = JSONObject()
            typeStats.forEach { (type, stats) ->
                typeStatsJson.put(type.name, JSONObject().apply {
                    put("trades", stats.trades)
                    put("wins", stats.wins)
                    put("losses", stats.losses)
                    put("totalPnl", stats.totalPnl)
                    put("winRate", stats.winRate)
                    put("avgPnl", stats.avgPnl)
                    put("avgHoldTime", stats.avgHoldTime)
                })
            }
            put("typeStats", typeStatsJson)

            val recentOutcomes = JSONArray()
            outcomes.toList().takeLast(20).forEach { outcome ->
                recentOutcomes.put(JSONObject().apply {
                    put("symbol", outcome.symbol)
                    put("type", outcome.arbType.name)
                    put("pnl", outcome.pnlPct)
                    put("win", isWin(outcome.pnlPct))
                    put("loss", isLoss(outcome.pnlPct))
                    put("hold", outcome.holdSeconds)
                })
            }
            put("recentOutcomes", recentOutcomes)
        }
    }

    fun loadFromJson(json: JSONObject) {
        try {
            venueLagMinScore = json.optInt("venueLagMinScore", 55)
            flowImbalanceMinScore = json.optInt("flowImbalanceMinScore", 55)
            panicReversionMinScore = json.optInt("panicReversionMinScore", 55)

            json.optJSONObject("typeStats")?.let { stats ->
                ArbType.values().forEach { type ->
                    stats.optJSONObject(type.name)?.let { typeJson ->
                        typeStats[type]?.apply {
                            trades = typeJson.optInt("trades", 0)
                            wins = typeJson.optInt("wins", 0)
                            losses = typeJson.optInt("losses", maxOf(trades - wins, 0))
                            totalPnl = typeJson.optDouble("totalPnl", typeJson.optDouble("avgPnl", 0.0) * trades.toDouble())
                            avgHoldTime = typeJson.optDouble("avgHoldTime", 0.0)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "loadFromJson error: ${e.message}")
        }
    }

    fun reset() {
        outcomes.clear()
        tokenOutcomes.clear()
        typeStats.values.forEach {
            it.trades = 0
            it.wins = 0
            it.losses = 0
            it.totalPnl = 0.0
            it.avgHoldTime = 0.0
        }
        venueLagMinScore = 55
        flowImbalanceMinScore = 55
        panicReversionMinScore = 55
    }

    private fun isWin(pnlPct: Double): Boolean = pnlPct >= WIN_THRESHOLD_PCT

    private fun isLoss(pnlPct: Double): Boolean = pnlPct <= LOSS_THRESHOLD_PCT
}
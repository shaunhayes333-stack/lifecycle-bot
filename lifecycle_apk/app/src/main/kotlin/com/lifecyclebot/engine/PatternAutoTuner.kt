package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/**
 * PatternAutoTuner — Automatically adjusts pattern confidence weights based on backtest results.
 *
 * This system creates a feedback loop between real trading performance and entry decisions:
 * 1. PatternBacktester analyzes historical trades
 * 2. PatternAutoTuner stores the recommended adjustments
 * 3. LifecycleStrategy applies these adjustments to pattern entry boosts
 */
object PatternAutoTuner {

    private const val PREFS_NAME = "pattern_auto_tuner"
    private const val KEY_ADJUSTMENTS = "pattern_adjustments"
    private const val KEY_LAST_UPDATE = "last_update_ts"
    private const val KEY_TRADES_ANALYZED = "trades_analyzed"
    private const val KEY_EMA_ADJUSTMENTS = "ema_adjustments"
    private const val KEY_PHASE_ADJUSTMENTS = "phase_adjustments"

    private var prefs: SharedPreferences? = null

    private val patternMultipliers = mutableMapOf<String, Double>()
    private val emaMultipliers = mutableMapOf<String, Double>()
    private val phaseMultipliers = mutableMapOf<String, Double>()

    private var lastUpdateTs: Long = 0L
    private var tradesAnalyzed: Int = 0

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromPrefs()
        ErrorLogger.info(
            "AutoTuner",
            "Initialized with ${patternMultipliers.size} pattern adjustments, ${emaMultipliers.size} EMA adjustments, ${phaseMultipliers.size} phase adjustments"
        )
    }

    private fun loadFromPrefs() {
        val p = prefs ?: return

        lastUpdateTs = p.getLong(KEY_LAST_UPDATE, 0L)
        tradesAnalyzed = p.getInt(KEY_TRADES_ANALYZED, 0).coerceAtLeast(0)

        patternMultipliers.clear()
        emaMultipliers.clear()
        phaseMultipliers.clear()

        try {
            val json = p.getString(KEY_ADJUSTMENTS, "{}") ?: "{}"
            val obj = JSONObject(json)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = normalizeKey(keys.next())
                patternMultipliers[key] = sanitizeMultiplier(obj.optDouble(key, obj.optDouble(key, 1.0)))
            }
        } catch (e: Exception) {
            ErrorLogger.error("AutoTuner", "Failed to load pattern adjustments: ${e.message}")
        }

        try {
            val json = p.getString(KEY_EMA_ADJUSTMENTS, "{}") ?: "{}"
            val obj = JSONObject(json)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val rawKey = keys.next()
                emaMultipliers[normalizeKey(rawKey)] =
                    sanitizeMultiplier(obj.optDouble(rawKey, 1.0))
            }
        } catch (e: Exception) {
            ErrorLogger.error("AutoTuner", "Failed to load EMA adjustments: ${e.message}")
        }

        try {
            val json = p.getString(KEY_PHASE_ADJUSTMENTS, "{}") ?: "{}"
            val obj = JSONObject(json)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val rawKey = keys.next()
                phaseMultipliers[normalizeKey(rawKey)] =
                    sanitizeMultiplier(obj.optDouble(rawKey, 1.0))
            }
        } catch (e: Exception) {
            ErrorLogger.error("AutoTuner", "Failed to load phase adjustments: ${e.message}")
        }
    }

    private fun saveToPrefs() {
        val p = prefs ?: return

        val patternJson = JSONObject()
        for ((k, v) in patternMultipliers) {
            patternJson.put(k, sanitizeMultiplier(v))
        }

        val emaJson = JSONObject()
        for ((k, v) in emaMultipliers) {
            emaJson.put(k, sanitizeMultiplier(v))
        }

        val phaseJson = JSONObject()
        for ((k, v) in phaseMultipliers) {
            phaseJson.put(k, sanitizeMultiplier(v))
        }

        p.edit()
            .putLong(KEY_LAST_UPDATE, lastUpdateTs)
            .putInt(KEY_TRADES_ANALYZED, tradesAnalyzed)
            .putString(KEY_ADJUSTMENTS, patternJson.toString())
            .putString(KEY_EMA_ADJUSTMENTS, emaJson.toString())
            .putString(KEY_PHASE_ADJUSTMENTS, phaseJson.toString())
            .apply()
    }

    fun updateFromBacktest(report: PatternBacktester.BacktestReport) {
        if (report.totalTrades < 10) {
            ErrorLogger.info(
                "AutoTuner",
                "Skipping update - need at least 10 trades (have ${report.totalTrades})"
            )
            return
        }

        val changes = mutableListOf<String>()

        for (stat in report.patterns) {
            val key = normalizeKey(stat.patternName)
            val newMult = calculateMultiplier(stat)
            val oldMult = patternMultipliers[key] ?: 1.0

            if (abs(newMult - oldMult) > 0.1) {
                patternMultipliers[key] = newMult
                val direction = if (newMult > oldMult) "↑" else "↓"
                changes.add("PATTERN $key: ${oldMult.fmt()}→${newMult.fmt()} $direction")
            }
        }

        for (stat in report.emaFanStats) {
            val key = normalizeKey(stat.patternName)
            val newMult = calculateMultiplier(stat)
            val oldMult = emaMultipliers[key] ?: 1.0

            if (abs(newMult - oldMult) > 0.1) {
                emaMultipliers[key] = newMult
                val direction = if (newMult > oldMult) "↑" else "↓"
                changes.add("EMA $key: ${oldMult.fmt()}→${newMult.fmt()} $direction")
            }
        }

        for (stat in report.phaseStats) {
            val key = normalizeKey(stat.patternName)
            val newMult = calculateMultiplier(stat)
            val oldMult = phaseMultipliers[key] ?: 1.0

            if (abs(newMult - oldMult) > 0.1) {
                phaseMultipliers[key] = newMult
                val direction = if (newMult > oldMult) "↑" else "↓"
                changes.add("PHASE $key: ${oldMult.fmt()}→${newMult.fmt()} $direction")
            }
        }

        lastUpdateTs = System.currentTimeMillis()
        tradesAnalyzed = report.totalTrades.coerceAtLeast(0)
        saveToPrefs()

        if (changes.isNotEmpty()) {
            ErrorLogger.info("AutoTuner", "═══ AUTO-TUNE UPDATE ═══")
            for (change in changes) {
                ErrorLogger.info("AutoTuner", change)
            }
            ErrorLogger.info("AutoTuner", "Based on ${report.totalTrades} trades")
        } else {
            ErrorLogger.info("AutoTuner", "No significant changes from backtest")
        }
    }

    private fun calculateMultiplier(stat: PatternBacktester.PatternStats): Double {
        val totalTrades = stat.totalTrades.coerceAtLeast(0)
        if (totalTrades < 3) return 1.0

        val safeWinRate = sanitizeDouble(stat.winRate, 50.0)
        val safeProfitFactor = sanitizeDouble(stat.profitFactor, 1.0)

        val winRateFactor = when {
            safeWinRate >= 70.0 -> 1.30
            safeWinRate >= 60.0 -> 1.15
            safeWinRate >= 50.0 -> 1.00
            safeWinRate >= 40.0 -> 0.85
            safeWinRate >= 30.0 -> 0.65
            else -> 0.40
        }

        val profitFactorFactor = when {
            safeProfitFactor >= 2.5 -> 1.20
            safeProfitFactor >= 1.8 -> 1.10
            safeProfitFactor >= 1.2 -> 1.00
            safeProfitFactor >= 0.8 -> 0.90
            else -> 0.75
        }

        val rawMultiplier = (winRateFactor * 0.6) + (profitFactorFactor * 0.4)

        val confidenceScale = when {
            totalTrades >= 30 -> 1.0
            totalTrades >= 15 -> 0.7
            totalTrades >= 5 -> 0.4
            else -> 0.2
        }

        val blendedMultiplier = 1.0 + (rawMultiplier - 1.0) * confidenceScale
        return sanitizeMultiplier(blendedMultiplier)
    }

    fun getPatternMultiplier(patternName: String): Double {
        return patternMultipliers[normalizeKey(patternName)] ?: 1.0
    }

    fun getEmaMultiplier(emaState: String): Double {
        return emaMultipliers[normalizeKey(emaState)] ?: 1.0
    }

    fun getPhaseMultiplier(phase: String): Double {
        return phaseMultipliers[normalizeKey(phase)] ?: 1.0
    }

    fun applyAutoTune(
        baseScore: Double,
        chartPattern: String? = null,
        emaState: String? = null,
        entryPhase: String? = null
    ): Double {
        var multiplier = 1.0

        if (!chartPattern.isNullOrBlank()) {
            multiplier *= getPatternMultiplier(chartPattern)
        }

        if (!emaState.isNullOrBlank()) {
            multiplier *= getEmaMultiplier(emaState)
        }

        if (!entryPhase.isNullOrBlank()) {
            multiplier *= getPhaseMultiplier(entryPhase)
        }

        val adjustedMultiplier = if (multiplier > 1.0) {
            1.0 + (multiplier - 1.0) * 0.7
        } else {
            multiplier
        }

        return (sanitizeDouble(baseScore) * adjustedMultiplier).coerceIn(0.0, 100.0)
    }

    fun getStatus(): String {
        val boosts = patternMultipliers.filterValues { it > 1.1 }
        val nerfs = patternMultipliers.filterValues { it < 0.9 }

        return buildString {
            append("AutoTuner: ")
            append(patternMultipliers.size)
            append(" patterns, ")
            append(emaMultipliers.size)
            append(" EMAs, ")
            append(phaseMultipliers.size)
            append(" phases | ")

            if (boosts.isNotEmpty()) {
                append("BOOST: ")
                append(boosts.keys.joinToString(","))
                append(" | ")
            }

            if (nerfs.isNotEmpty()) {
                append("NERF: ")
                append(nerfs.keys.joinToString(","))
            }

            if (boosts.isEmpty() && nerfs.isEmpty()) {
                append("No adjustments (need more trades)")
            }
        }
    }

    fun getDetailedAdjustments(): Map<String, Double> {
        val result = linkedMapOf<String, Double>()

        for ((k, v) in patternMultipliers) {
            result["pattern:$k"] = v
        }
        for ((k, v) in emaMultipliers) {
            result["ema:$k"] = v
        }
        for ((k, v) in phaseMultipliers) {
            result["phase:$k"] = v
        }

        return result
    }

    fun getLastUpdateTs(): Long = lastUpdateTs

    fun getTradesAnalyzed(): Int = tradesAnalyzed

    fun reset() {
        patternMultipliers.clear()
        emaMultipliers.clear()
        phaseMultipliers.clear()
        lastUpdateTs = 0L
        tradesAnalyzed = 0
        saveToPrefs()
        ErrorLogger.info("AutoTuner", "All adjustments reset to default")
    }

    private fun normalizeKey(value: String): String {
        return value.trim().uppercase(Locale.US)
    }

    private fun sanitizeMultiplier(value: Double): Double {
        val safe = sanitizeDouble(value, 1.0)
        return safe.coerceIn(0.3, 1.5)
    }

    private fun sanitizeDouble(value: Double, default: Double = 0.0): Double {
        return if (value.isNaN() || value.isInfinite()) default else value
    }

    private fun Double.fmt(): String {
        return String.format(Locale.US, "%.2f", sanitizeDouble(this))
    }
}
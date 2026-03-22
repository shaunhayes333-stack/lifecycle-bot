package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * PatternAutoTuner — Automatically adjusts pattern confidence weights based on backtest results
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 * 
 * This system creates a feedback loop between real trading performance and entry decisions:
 * 
 * 1. PatternBacktester analyzes historical trades
 * 2. PatternAutoTuner stores the recommended adjustments
 * 3. LifecycleStrategy applies these adjustments to pattern entry boosts
 * 
 * The result: The bot learns which patterns work best for YOUR trading style and market conditions.
 * 
 * Adjustments are stored as multipliers:
 * - 1.0 = no change (default)
 * - 1.3 = boost pattern score by 30%
 * - 0.7 = reduce pattern score by 30%
 * - 0.0 = disable pattern entirely
 */
object PatternAutoTuner {

    private const val PREFS_NAME = "pattern_auto_tuner"
    private const val KEY_ADJUSTMENTS = "pattern_adjustments"
    private const val KEY_LAST_UPDATE = "last_update_ts"
    private const val KEY_TRADES_ANALYZED = "trades_analyzed"
    private const val KEY_EMA_ADJUSTMENTS = "ema_adjustments"
    private const val KEY_PHASE_ADJUSTMENTS = "phase_adjustments"

    private var prefs: SharedPreferences? = null
    
    // In-memory cache for fast access during trading
    private val patternMultipliers = mutableMapOf<String, Double>()
    private val emaMultipliers = mutableMapOf<String, Double>()
    private val phaseMultipliers = mutableMapOf<String, Double>()
    private var lastUpdateTs: Long = 0
    private var tradesAnalyzed: Int = 0

    /**
     * Initialize the auto-tuner with context.
     * Call this once at app/service startup.
     */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromPrefs()
        ErrorLogger.info("AutoTuner", "Initialized with ${patternMultipliers.size} pattern adjustments")
    }

    /**
     * Load saved adjustments from SharedPreferences.
     */
    private fun loadFromPrefs() {
        val p = prefs ?: return
        
        lastUpdateTs = p.getLong(KEY_LAST_UPDATE, 0)
        tradesAnalyzed = p.getInt(KEY_TRADES_ANALYZED, 0)
        
        // Load pattern multipliers
        try {
            val json = p.getString(KEY_ADJUSTMENTS, "{}")
            val obj = JSONObject(json)
            obj.keys().forEach { key ->
                patternMultipliers[key] = obj.getDouble(key)
            }
        } catch (e: Exception) {
            ErrorLogger.error("AutoTuner", "Failed to load pattern adjustments: ${e.message}")
        }
        
        // Load EMA multipliers
        try {
            val json = p.getString(KEY_EMA_ADJUSTMENTS, "{}")
            val obj = JSONObject(json)
            obj.keys().forEach { key ->
                emaMultipliers[key] = obj.getDouble(key)
            }
        } catch (e: Exception) {
            ErrorLogger.error("AutoTuner", "Failed to load EMA adjustments: ${e.message}")
        }
        
        // Load phase multipliers
        try {
            val json = p.getString(KEY_PHASE_ADJUSTMENTS, "{}")
            val obj = JSONObject(json)
            obj.keys().forEach { key ->
                phaseMultipliers[key] = obj.getDouble(key)
            }
        } catch (e: Exception) {
            ErrorLogger.error("AutoTuner", "Failed to load phase adjustments: ${e.message}")
        }
    }

    /**
     * Save adjustments to SharedPreferences.
     */
    private fun saveToPrefs() {
        val p = prefs ?: return
        
        p.edit().apply {
            putLong(KEY_LAST_UPDATE, lastUpdateTs)
            putInt(KEY_TRADES_ANALYZED, tradesAnalyzed)
            
            // Save pattern multipliers
            val patternJson = JSONObject()
            patternMultipliers.forEach { (k, v) -> patternJson.put(k, v) }
            putString(KEY_ADJUSTMENTS, patternJson.toString())
            
            // Save EMA multipliers
            val emaJson = JSONObject()
            emaMultipliers.forEach { (k, v) -> emaJson.put(k, v) }
            putString(KEY_EMA_ADJUSTMENTS, emaJson.toString())
            
            // Save phase multipliers
            val phaseJson = JSONObject()
            phaseMultipliers.forEach { (k, v) -> phaseJson.put(k, v) }
            putString(KEY_PHASE_ADJUSTMENTS, phaseJson.toString())
            
            apply()
        }
    }

    /**
     * Update adjustments from a backtest report.
     * Called automatically by BotService after running backtest.
     */
    fun updateFromBacktest(report: PatternBacktester.BacktestReport) {
        if (report.totalTrades < 10) {
            ErrorLogger.info("AutoTuner", "Skipping update - need at least 10 trades (have ${report.totalTrades})")
            return
        }

        val changes = mutableListOf<String>()

        // Update pattern multipliers
        report.patterns.forEach { stat ->
            val newMult = calculateMultiplier(stat)
            val oldMult = patternMultipliers[stat.patternName] ?: 1.0
            
            // Only apply if change is significant (>10% difference)
            if (kotlin.math.abs(newMult - oldMult) > 0.1) {
                patternMultipliers[stat.patternName] = newMult
                val direction = if (newMult > oldMult) "↑" else "↓"
                changes.add("${stat.patternName}: ${oldMult.fmt()}→${newMult.fmt()} $direction")
            }
        }

        // Update EMA fan multipliers
        report.emaFanStats.forEach { stat ->
            val newMult = calculateMultiplier(stat)
            val oldMult = emaMultipliers[stat.patternName] ?: 1.0
            
            if (kotlin.math.abs(newMult - oldMult) > 0.1) {
                emaMultipliers[stat.patternName] = newMult
                val direction = if (newMult > oldMult) "↑" else "↓"
                changes.add("EMA_${stat.patternName}: ${oldMult.fmt()}→${newMult.fmt()} $direction")
            }
        }

        // Update phase multipliers
        report.phaseStats.forEach { stat ->
            val newMult = calculateMultiplier(stat)
            val oldMult = phaseMultipliers[stat.patternName] ?: 1.0
            
            if (kotlin.math.abs(newMult - oldMult) > 0.1) {
                phaseMultipliers[stat.patternName] = newMult
            }
        }

        lastUpdateTs = System.currentTimeMillis()
        tradesAnalyzed = report.totalTrades
        saveToPrefs()

        if (changes.isNotEmpty()) {
            ErrorLogger.info("AutoTuner", "═══ AUTO-TUNE UPDATE ═══")
            changes.forEach { ErrorLogger.info("AutoTuner", it) }
            ErrorLogger.info("AutoTuner", "Based on ${report.totalTrades} trades")
        } else {
            ErrorLogger.info("AutoTuner", "No significant changes from backtest")
        }
    }

    /**
     * Calculate the multiplier for a pattern based on its stats.
     * Uses a smooth curve to avoid dramatic swings.
     */
    private fun calculateMultiplier(stat: PatternBacktester.PatternStats): Double {
        // Need minimum trades for reliability
        if (stat.totalTrades < 3) return 1.0
        
        // Factors that contribute to the multiplier:
        // 1. Win rate (most important)
        // 2. Profit factor
        // 3. Expectancy
        
        val winRateFactor = when {
            stat.winRate >= 70 -> 1.30    // Excellent
            stat.winRate >= 60 -> 1.15    // Great
            stat.winRate >= 50 -> 1.0     // Neutral
            stat.winRate >= 40 -> 0.85    // Below average
            stat.winRate >= 30 -> 0.65    // Poor
            else -> 0.40                   // Disable
        }
        
        val profitFactorFactor = when {
            stat.profitFactor >= 2.5 -> 1.20
            stat.profitFactor >= 1.8 -> 1.10
            stat.profitFactor >= 1.2 -> 1.0
            stat.profitFactor >= 0.8 -> 0.90
            else -> 0.75
        }
        
        // Combine factors (weighted average)
        val rawMultiplier = (winRateFactor * 0.6) + (profitFactorFactor * 0.4)
        
        // Apply confidence scaling based on sample size
        val confidenceScale = when {
            stat.totalTrades >= 30 -> 1.0      // Full confidence
            stat.totalTrades >= 15 -> 0.7      // Medium confidence
            stat.totalTrades >= 5 -> 0.4       // Low confidence
            else -> 0.2                         // Very low confidence
        }
        
        // Blend toward 1.0 based on confidence
        // Low confidence = stay close to 1.0
        val blendedMultiplier = 1.0 + (rawMultiplier - 1.0) * confidenceScale
        
        // Clamp to reasonable range
        return blendedMultiplier.coerceIn(0.3, 1.5)
    }

    // ═══════════════════════════════════════════════════════════════════
    // PUBLIC API - Used by LifecycleStrategy
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get the multiplier for a chart pattern (DOUBLE_BOTTOM, BULL_FLAG, etc.)
     */
    fun getPatternMultiplier(patternName: String): Double {
        return patternMultipliers[patternName] ?: 1.0
    }

    /**
     * Get the multiplier for an EMA fan state (BULL_FAN, BEAR_FAN, etc.)
     */
    fun getEmaMultiplier(emaState: String): Double {
        return emaMultipliers[emaState] ?: 1.0
    }

    /**
     * Get the multiplier for an entry phase (pumping, cooling, reclaim, etc.)
     */
    fun getPhaseMultiplier(phase: String): Double {
        return phaseMultipliers[phase] ?: 1.0
    }

    /**
     * Apply all relevant multipliers to an entry score.
     * This is the main entry point for LifecycleStrategy.
     */
    fun applyAutoTune(
        baseScore: Double,
        chartPattern: String? = null,
        emaState: String? = null,
        entryPhase: String? = null,
    ): Double {
        var multiplier = 1.0
        
        chartPattern?.let {
            multiplier *= getPatternMultiplier(it)
        }
        
        emaState?.let {
            multiplier *= getEmaMultiplier(it)
        }
        
        entryPhase?.let {
            multiplier *= getPhaseMultiplier(it)
        }
        
        // Apply diminishing returns for stacked multipliers
        // (prevents runaway score inflation)
        val adjustedMultiplier = if (multiplier > 1.0) {
            1.0 + (multiplier - 1.0) * 0.7  // 70% of boost
        } else {
            multiplier  // Full penalty
        }
        
        return (baseScore * adjustedMultiplier).coerceIn(0.0, 100.0)
    }

    /**
     * Get current status for logging.
     */
    fun getStatus(): String {
        val boosts = patternMultipliers.filter { it.value > 1.1 }
        val nerfs = patternMultipliers.filter { it.value < 0.9 }
        
        return buildString {
            append("AutoTuner: ")
            append("${patternMultipliers.size} patterns, ")
            append("${emaMultipliers.size} EMAs | ")
            
            if (boosts.isNotEmpty()) {
                append("BOOST: ${boosts.keys.joinToString(",")} | ")
            }
            if (nerfs.isNotEmpty()) {
                append("NERF: ${nerfs.keys.joinToString(",")}")
            }
            if (boosts.isEmpty() && nerfs.isEmpty()) {
                append("No adjustments (need more trades)")
            }
        }
    }

    /**
     * Get detailed adjustments for display.
     */
    fun getDetailedAdjustments(): Map<String, Double> {
        return patternMultipliers.toMap()
    }

    /**
     * Reset all adjustments to default (1.0).
     */
    fun reset() {
        patternMultipliers.clear()
        emaMultipliers.clear()
        phaseMultipliers.clear()
        lastUpdateTs = 0
        tradesAnalyzed = 0
        saveToPrefs()
        ErrorLogger.info("AutoTuner", "All adjustments reset to default")
    }

    private fun Double.fmt() = String.format("%.2f", this)
}

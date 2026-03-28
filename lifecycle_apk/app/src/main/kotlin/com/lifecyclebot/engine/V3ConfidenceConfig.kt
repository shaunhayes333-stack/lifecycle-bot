package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences

/**
 * V3ConfidenceConfig — User-adjustable V3 confidence thresholds
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Allows users to adjust V3 decision thresholds:
 * - AGGRESSIVE mode: Lower thresholds, more trades, higher risk
 * - STANDARD mode: Default thresholds, balanced
 * - CONSERVATIVE mode: Higher thresholds, fewer trades, lower risk
 * 
 * Persisted to SharedPreferences.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object V3ConfidenceConfig {
    
    private const val TAG = "V3ConfidenceConfig"
    private const val PREFS_NAME = "v3_confidence_config"
    
    private var prefs: SharedPreferences? = null
    
    // Current mode
    enum class ConfidenceMode {
        AGGRESSIVE,  // Lower thresholds, more trades
        STANDARD,    // Default thresholds
        CONSERVATIVE // Higher thresholds, fewer trades
    }
    
    private var currentMode = ConfidenceMode.STANDARD
    
    // Threshold multipliers for each mode
    private val modeMultipliers = mapOf(
        ConfidenceMode.AGGRESSIVE to ThresholdMultipliers(
            scoreMultiplier = 0.8,       // 20% lower score requirements
            confidenceMultiplier = 0.85, // 15% lower confidence requirements
            sizeMultiplier = 1.2,        // 20% larger positions
            description = "More trades, higher risk. Lower thresholds across the board."
        ),
        ConfidenceMode.STANDARD to ThresholdMultipliers(
            scoreMultiplier = 1.0,
            confidenceMultiplier = 1.0,
            sizeMultiplier = 1.0,
            description = "Balanced risk/reward. Default V3 thresholds."
        ),
        ConfidenceMode.CONSERVATIVE to ThresholdMultipliers(
            scoreMultiplier = 1.25,      // 25% higher score requirements
            confidenceMultiplier = 1.2,  // 20% higher confidence requirements
            sizeMultiplier = 0.75,       // 25% smaller positions
            description = "Fewer trades, lower risk. Only high-conviction setups."
        )
    )
    
    data class ThresholdMultipliers(
        val scoreMultiplier: Double,
        val confidenceMultiplier: Double,
        val sizeMultiplier: Double,
        val description: String,
    )
    
    // Custom overrides (for advanced users)
    private var customScoreOverride: Int? = null
    private var customConfidenceOverride: Int? = null
    private var customSizeOverride: Double? = null
    
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromPrefs()
    }
    
    private fun loadFromPrefs() {
        prefs?.let {
            val modeName = it.getString("mode", ConfidenceMode.STANDARD.name)
            currentMode = try {
                ConfidenceMode.valueOf(modeName ?: ConfidenceMode.STANDARD.name)
            } catch (e: Exception) {
                ConfidenceMode.STANDARD
            }
            
            // Load custom overrides if set
            if (it.contains("custom_score")) {
                customScoreOverride = it.getInt("custom_score", -1).takeIf { v -> v >= 0 }
            }
            if (it.contains("custom_confidence")) {
                customConfidenceOverride = it.getInt("custom_confidence", -1).takeIf { v -> v >= 0 }
            }
            if (it.contains("custom_size")) {
                customSizeOverride = it.getFloat("custom_size", -1f).toDouble().takeIf { v -> v > 0 }
            }
        }
    }
    
    private fun saveToPrefs() {
        prefs?.edit()?.apply {
            putString("mode", currentMode.name)
            customScoreOverride?.let { putInt("custom_score", it) } ?: remove("custom_score")
            customConfidenceOverride?.let { putInt("custom_confidence", it) } ?: remove("custom_confidence")
            customSizeOverride?.let { putFloat("custom_size", it.toFloat()) } ?: remove("custom_size")
            apply()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // GETTERS - Used by V3 decision engine
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getCurrentMode(): ConfidenceMode = currentMode
    
    fun getMultipliers(): ThresholdMultipliers {
        return modeMultipliers[currentMode] ?: modeMultipliers[ConfidenceMode.STANDARD]!!
    }
    
    /**
     * Get adjusted minimum score for EXECUTE band.
     * Base: 50, adjusted by mode multiplier.
     */
    fun getMinScoreForExecute(baseScore: Int = 50): Int {
        customScoreOverride?.let { return it }
        return (baseScore * getMultipliers().scoreMultiplier).toInt()
    }
    
    /**
     * Get adjusted minimum confidence for EXECUTE band.
     * Base: 45, adjusted by mode multiplier.
     */
    fun getMinConfidenceForExecute(baseConfidence: Int = 45): Int {
        customConfidenceOverride?.let { return it }
        return (baseConfidence * getMultipliers().confidenceMultiplier).toInt()
    }
    
    /**
     * Get size multiplier for position sizing.
     */
    fun getSizeMultiplier(): Double {
        customSizeOverride?.let { return it }
        return getMultipliers().sizeMultiplier
    }
    
    /**
     * Get description of current mode.
     */
    fun getModeDescription(): String {
        return getMultipliers().description
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SETTERS - For UI
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun setMode(mode: ConfidenceMode) {
        currentMode = mode
        // Clear custom overrides when switching modes
        customScoreOverride = null
        customConfidenceOverride = null
        customSizeOverride = null
        saveToPrefs()
        ErrorLogger.info(TAG, "Mode changed to: $mode - ${getModeDescription()}")
    }
    
    fun setCustomOverrides(
        minScore: Int? = null,
        minConfidence: Int? = null,
        sizeMultiplier: Double? = null
    ) {
        customScoreOverride = minScore?.coerceIn(20, 80)
        customConfidenceOverride = minConfidence?.coerceIn(20, 80)
        customSizeOverride = sizeMultiplier?.coerceIn(0.5, 2.0)
        saveToPrefs()
        ErrorLogger.info(TAG, "Custom overrides set: score=$customScoreOverride, conf=$customConfidenceOverride, size=$customSizeOverride")
    }
    
    fun clearCustomOverrides() {
        customScoreOverride = null
        customConfidenceOverride = null
        customSizeOverride = null
        saveToPrefs()
    }
    
    fun hasCustomOverrides(): Boolean {
        return customScoreOverride != null || customConfidenceOverride != null || customSizeOverride != null
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // UI HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getStatusSummary(): String {
        val mode = currentMode.name
        val multi = getMultipliers()
        return buildString {
            append("V3 Mode: $mode")
            if (hasCustomOverrides()) {
                append(" (CUSTOM)")
            }
            append("\n")
            append("Score threshold: ${getMinScoreForExecute()}\n")
            append("Confidence threshold: ${getMinConfidenceForExecute()}\n")
            append("Size multiplier: ${"%.2f".format(getSizeMultiplier())}x")
        }
    }
    
    fun getAllModes(): List<ConfidenceMode> = ConfidenceMode.values().toList()
}

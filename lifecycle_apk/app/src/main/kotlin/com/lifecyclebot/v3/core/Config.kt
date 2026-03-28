package com.lifecyclebot.v3.core

/**
 * V3 Trading Configuration
 * All thresholds in one place
 */
data class TradingConfigV3(
    // Eligibility
    val minLiquidityUsd: Double = 1000.0,
    val maxTokenAgeMinutes: Double = 30.0,
    
    // Decision thresholds
    val watchScoreMin: Int = 20,
    val executeSmallMin: Int = 35,
    val executeStandardMin: Int = 50,
    val executeAggressiveMin: Int = 65,
    
    // Risk
    val fatalRugThreshold: Int = 90,
    val candidateTtlMinutes: Long = 20,
    val shadowTrackNearMissMin: Int = 15,
    
    // Sizing
    val reserveSol: Double = 0.05,
    val maxSmallSizePct: Double = 0.04,
    val maxStandardSizePct: Double = 0.07,
    val maxAggressiveSizePct: Double = 0.12,
    val paperLearningSizeMult: Double = 0.50
)

data class TradingContext(
    val config: TradingConfigV3,
    val mode: V3BotMode,
    val marketRegime: String = "NEUTRAL",
    val apiHealthy: Boolean = true,
    val priceFeedsHealthy: Boolean = true,
    val clockMs: Long = System.currentTimeMillis(),
    val extra: Map<String, Any?> = emptyMap()
) {
    // Helper accessors for extra fields (consistent with CandidateSnapshot)
    fun extraBoolean(key: String): Boolean = extra[key] as? Boolean ?: false
    fun extraInt(key: String): Int = extra[key] as? Int ?: 0
    fun extraDouble(key: String): Double = extra[key] as? Double ?: 0.0
    fun extraString(key: String): String = extra[key] as? String ?: ""
}

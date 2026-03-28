package com.lifecyclebot.v3.scanner

/**
 * V3 Source Types
 */
enum class SourceType {
    DEX_BOOSTED,
    RAYDIUM_NEW_POOL,
    PUMP_FUN_GRADUATE,
    DEX_TRENDING
}

/**
 * V3 Candidate Snapshot
 * Immutable snapshot of token state at discovery
 */
data class CandidateSnapshot(
    val mint: String,
    val symbol: String,
    val source: SourceType,
    val discoveredAtMs: Long,
    val ageMinutes: Double,
    val liquidityUsd: Double,
    val marketCapUsd: Double,
    val buyPressurePct: Double,
    val volume1mUsd: Double,
    val volume5mUsd: Double,
    val holders: Int? = null,
    val topHolderPct: Double? = null,
    val bundledPct: Double? = null,
    val hasIdentitySignals: Boolean = false,
    val isSellable: Boolean? = null,
    val rawRiskScore: Int? = null,
    val extra: Map<String, Any?> = emptyMap()
) {
    // Helper accessors for extra fields
    fun extraBoolean(key: String): Boolean = extra[key] as? Boolean ?: false
    fun extraInt(key: String): Int = extra[key] as? Int ?: 0
    fun extraDouble(key: String): Double = extra[key] as? Double ?: 0.0
    fun extraString(key: String): String = extra[key] as? String ?: ""
}

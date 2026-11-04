package com.lifecyclebot.v3.scanner

/**
 * V3 Source Types
 */
/** Whether a numeric age is exact, only a witnessed lower bound, or unavailable. */
enum class AgeEvidenceKind8527 { KNOWN, PROVEN_OLDER_THAN, UNKNOWN }

enum class SourceType {
    DEX_BOOSTED,
    RAYDIUM_NEW_POOL,
    PUMP_FUN_NEW,
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
    /** Exact observed age only. -1 means absent; it is never the lower bound or a fabricated birth. */
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
    val extra: Map<String, Any?> = emptyMap(),
    /** A witnessed existence floor, not the exact age. Null preserves older constructor call sites. */
    val minimumAgeMinutes8527: Double? = null,
) {
    val exactAgeMinutes8527: Double? get() = ageMinutes.takeIf { it.isFinite() && it >= 0.0 }
    val provenMinimumAgeMinutes8527: Double? get() = exactAgeMinutes8527
        ?: minimumAgeMinutes8527?.takeIf { it.isFinite() && it >= 0.0 }
    val ageKind8527: AgeEvidenceKind8527 get() = when {
        exactAgeMinutes8527 != null -> AgeEvidenceKind8527.KNOWN
        provenMinimumAgeMinutes8527 != null -> AgeEvidenceKind8527.PROVEN_OLDER_THAN
        else -> AgeEvidenceKind8527.UNKNOWN
    }

    /** A maximum-age claim needs exact birth; an existence floor proves only minimum age. */
    fun ageAtMost8527(minutes: Double): Boolean = exactAgeMinutes8527?.let { it <= minutes } == true
    fun ageBelow8527(minutes: Double): Boolean = exactAgeMinutes8527?.let { it < minutes } == true
    fun ageAtLeast8527(minutes: Double): Boolean = provenMinimumAgeMinutes8527?.let { it >= minutes } == true

    val hasScorableAge8527: Boolean get() = exactAgeMinutes8527 != null ||
        establishedEvidence8527(provenMinimumAgeMinutes8527, liquidityUsd)

    companion object {
        const val ESTABLISHED_MINIMUM_MINUTES_8527 = 6.0 * 60.0

        /** Same established scope as the V3 trunk; no unknown-age or shallow-pool exemption. */
        internal fun establishedEvidence8527(minimumMinutes: Double?, liquidityUsd: Double): Boolean =
            minimumMinutes != null && minimumMinutes.isFinite() && minimumMinutes >= ESTABLISHED_MINIMUM_MINUTES_8527 &&
                liquidityUsd.isFinite() &&
                liquidityUsd >= com.lifecyclebot.engine.LaneAgeLiquidityScope8140.ESTABLISHED_SCOPE_MIN_LIQ_USD_8140
    }

    // Helper accessors for extra fields
    fun extraBoolean(key: String): Boolean = extra[key] as? Boolean ?: false
    fun extraInt(key: String): Int = extra[key] as? Int ?: 0
    fun extraDouble(key: String): Double = extra[key] as? Double ?: 0.0
    fun extraString(key: String): String = extra[key] as? String ?: ""
}

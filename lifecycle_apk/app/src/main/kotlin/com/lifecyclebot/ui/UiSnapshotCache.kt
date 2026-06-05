package com.lifecyclebot.ui

/**
 * V5.9.1332 — main-thread relief for heavy UI status reads.
 *
 * Operator snapshot (build 5.0.3321) showed ANR storms in updateUi():
 *   - EducationSubLayerAI.getLayerLevelProgress → 1259ms freeze
 *   - TradeHistoryStore.rollingWinRatePct → 1004ms freeze (via WrRecoveryPartial.shortBadge)
 *
 * Both are called from updateUi() every render tick — they each iterate over
 * the full trade journal / per-layer history. We never need fresher than ~2s
 * for these status badges; cache them and return the cached value within TTL.
 *
 * Train-First doctrine: this does NOT affect any trading decision. It only
 * caps how often heavy aggregations run on Dispatchers.Main.
 */
object UiSnapshotCache {

    private const val WR_BADGE_TTL_MS: Long = 2_500L
    private const val EDU_MATURITY_TTL_MS: Long = 2_500L

    @Volatile private var wrBadgeCache: String = ""
    @Volatile private var wrBadgeStampMs: Long = 0L

    @Volatile private var eduMaturityCache: Map<String, com.lifecyclebot.v3.scoring.EducationSubLayerAI.LayerMaturity>? = null
    @Volatile private var eduMaturityStampMs: Long = 0L

    /** Cached WrRecoveryPartial.shortBadge() (heavy: scans TradeHistoryStore). */
    fun wrShortBadge(): String {
        val now = System.currentTimeMillis()
        if (now - wrBadgeStampMs < WR_BADGE_TTL_MS) return wrBadgeCache
        return try {
            val v = com.lifecyclebot.engine.WrRecoveryPartial.shortBadge()
            wrBadgeCache = v
            wrBadgeStampMs = now
            v
        } catch (_: Throwable) {
            wrBadgeCache
        }
    }

    /** Cached EducationSubLayerAI.getAllLayerMaturity() (heavy: iterates per-layer histories). */
    fun eduAllLayerMaturity(): Map<String, com.lifecyclebot.v3.scoring.EducationSubLayerAI.LayerMaturity> {
        val now = System.currentTimeMillis()
        val cached = eduMaturityCache
        if (cached != null && now - eduMaturityStampMs < EDU_MATURITY_TTL_MS) return cached
        return try {
            val v = com.lifecyclebot.v3.scoring.EducationSubLayerAI.getAllLayerMaturity()
            eduMaturityCache = v
            eduMaturityStampMs = now
            v
        } catch (_: Throwable) {
            cached ?: emptyMap()
        }
    }
}

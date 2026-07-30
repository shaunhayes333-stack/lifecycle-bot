package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6395 — POSITION VIEW MODEL (UI TRUTH).
 *
 * All panels that show the same mint MUST consume the same canonical
 * PositionView. The Open Positions card and Moonshot card can NOT show
 * +823% and 0% simultaneously — both bind to the identical PositionView
 * for the canonicalId.
 *
 * Fields (contract):
 *   - displayMarkPnlPct  (mark, informational only)
 *   - executablePnlPct   (authoritative)
 *   - executableExitSol
 *   - quoteAgeMs, quoteFractionPct, priceImpactPct
 *   - proofStatus (EXECUTABLE / UNVERIFIED / EXPIRED / MARK_ONLY /
 *                  NON_EXECUTABLE_MARK_SPIKE)
 *   - pairAddressShort
 *   - canonicalPositionId
 *   - laneOwner
 *
 * UI code must NEVER show "lock +814%" unless proofStatus == EXECUTABLE.
 */
object PositionViewModelStore6395 {

    data class PositionView(
        val canonicalPositionId: String,
        val mint: String,
        val symbol: String,
        val laneOwner: String,
        val entryBasisSol: Double,
        val quantityRaw: java.math.BigInteger,
        val tokenDecimals: Int,
        val displayMarkPnlPct: Double,
        val executablePnlPct: Double,
        val executableExitSol: Double,
        val quoteAgeMs: Long,
        val quoteFractionPct: Double,
        val priceImpactPct: Double,
        val proofStatus: String,
        val pairAddressShort: String,
        val updatedAtMs: Long = System.currentTimeMillis(),
    ) {
        /** UI safety guard — never render "lock +N%" unless executable proof. */
        fun canShowLockedPercent(): Boolean = proofStatus == "EXECUTABLE"
    }

    private val byCanonicalId = ConcurrentHashMap<String, PositionView>()

    /** Idempotent update. All lane cards should call this via the same canonicalId. */
    fun upsert(view: PositionView) {
        byCanonicalId[view.canonicalPositionId] = view
    }

    fun get(canonicalId: String): PositionView? = byCanonicalId[canonicalId]

    fun getByMint(mint: String): PositionView? =
        byCanonicalId.values.firstOrNull { it.mint == mint }

    fun allViews(): List<PositionView> = byCanonicalId.values.toList()

    fun remove(canonicalId: String) { byCanonicalId.remove(canonicalId) }

    internal fun clearAllForTest() { byCanonicalId.clear() }
}

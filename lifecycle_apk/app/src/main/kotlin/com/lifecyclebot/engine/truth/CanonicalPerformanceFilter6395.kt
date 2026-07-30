package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6395 — CANONICAL PERFORMANCE FILTER.
 *
 * Excludes rows containing any of the following from win-rate, profit-factor,
 * expectancy, governor, tactic switcher, personality tuning and wallet
 * compounding:
 *   - QTY_DECIMAL_SKEW
 *   - MARK_EXECUTABLE_DIVERGENCE above tolerance
 *   - pair identity mismatch
 *   - duplicate lane exit
 *   - missing finalized signature
 *   - missing parsed raw deltas
 *   - inconsistent cost basis
 *   - unverified wallet quantity
 *
 * Callers query `isCanonicalEligible(rowId)` before adding a row to any
 * learning surface. Quarantine counts are exposed for the health panel.
 */
object CanonicalPerformanceFilter6395 {

    enum class QuarantineReason {
        QTY_DECIMAL_SKEW,
        MARK_EXECUTABLE_DIVERGENCE,
        PAIR_IDENTITY_MISMATCH,
        DUPLICATE_LANE_EXIT,
        MISSING_FINALIZED_SIGNATURE,
        MISSING_PARSED_RAW_DELTAS,
        INCONSISTENT_COST_BASIS,
        UNVERIFIED_WALLET_QUANTITY,
    }

    private val quarantined = ConcurrentHashMap<String, MutableSet<QuarantineReason>>()
    val quarantinedCount = AtomicLong(0L)

    fun quarantine(rowId: String, reason: QuarantineReason) {
        val first = quarantined.compute(rowId) { _, existing ->
            val set = existing ?: java.util.Collections.newSetFromMap(ConcurrentHashMap())
            set.add(reason); set
        }
        if (first?.size == 1) {
            quarantinedCount.incrementAndGet()
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CANON_QUARANTINED_${reason.name}_6395") } catch (_: Throwable) {}
        }
    }

    fun isCanonicalEligible(rowId: String): Boolean = !quarantined.containsKey(rowId)

    fun reasons(rowId: String): Set<QuarantineReason> = quarantined[rowId]?.toSet().orEmpty()

    fun totalQuarantined(): Long = quarantinedCount.get()

    internal fun clearAllForTest() { quarantined.clear(); quarantinedCount.set(0L) }
}

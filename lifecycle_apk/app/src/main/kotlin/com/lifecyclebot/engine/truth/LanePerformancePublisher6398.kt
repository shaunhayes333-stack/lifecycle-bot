package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6398 — LANE PERFORMANCE PUBLISHER (wire-up).
 *
 * Feeds CanonicalPerformanceFilter6395 clean rows into
 * AdaptiveFloorBrain6397.postLaneStat so demonstrably positive lanes
 * get their relaxation and demonstrably weak lanes tighten.
 *
 * Rows tainted by QTY_DECIMAL_SKEW, MARK_EXECUTABLE_DIVERGENCE,
 * PAIR_IDENTITY_MISMATCH, DUPLICATE_LANE_EXIT, MISSING_FINALIZED_SIGNATURE,
 * MISSING_PARSED_RAW_DELTAS, INCONSISTENT_COST_BASIS or
 * UNVERIFIED_WALLET_QUANTITY are EXCLUDED by design — this publisher
 * checks `CanonicalPerformanceFilter6395.isCanonicalEligible(rowId)`
 * before ingesting.
 */
object LanePerformancePublisher6398 {

    /** Per-lane rolling aggregate of clean canonical trades. */
    private data class LaneAgg(
        var trades: Int = 0,
        var wins: Int = 0,
        var pnlSumSol: Double = 0.0,
    )

    private val agg = ConcurrentHashMap<String, LaneAgg>()
    val rowsIngested = AtomicLong(0L)
    val rowsRejectedTainted = AtomicLong(0L)

    /**
     * Ingest one canonical trade result. Called once per finalized close
     * with the row id (positionId or fillId), lane, whether the trade
     * was a win, and its SOL pnl.
     */
    @Synchronized
    fun ingest(rowId: String, lane: String, wasWin: Boolean, pnlSol: Double) {
        // Guard: tainted rows never enter learning.
        if (!CanonicalPerformanceFilter6395.isCanonicalEligible(rowId)) {
            rowsRejectedTainted.incrementAndGet()
            return
        }
        rowsIngested.incrementAndGet()
        val a = agg.getOrPut(lane.uppercase()) { LaneAgg() }
        a.trades += 1
        if (wasWin) a.wins += 1
        a.pnlSumSol += pnlSol
        publish(lane)
    }

    /**
     * Publish the aggregated stat for the given lane. Automatically
     * called after every ingest — external callers rarely need this.
     */
    fun publish(lane: String) {
        val a = agg[lane.uppercase()] ?: return
        val wr = if (a.trades == 0) 0.0 else a.wins * 100.0 / a.trades
        val ev = if (a.trades == 0) 0.0 else a.pnlSumSol / a.trades
        AdaptiveFloorBrain6397.postLaneStat(
            AdaptiveFloorBrain6397.LaneStat(
                lane = lane.uppercase(), trades = a.trades,
                winRatePct = wr, expectancySol = ev,
            )
        )
    }

    fun snapshot(lane: String): Triple<Int, Double, Double>? {
        val a = agg[lane.uppercase()] ?: return null
        val wr = if (a.trades == 0) 0.0 else a.wins * 100.0 / a.trades
        val ev = if (a.trades == 0) 0.0 else a.pnlSumSol / a.trades
        return Triple(a.trades, wr, ev)
    }

    internal fun clearAllForTest() {
        agg.clear(); rowsIngested.set(0L); rowsRejectedTainted.set(0L)
    }
}

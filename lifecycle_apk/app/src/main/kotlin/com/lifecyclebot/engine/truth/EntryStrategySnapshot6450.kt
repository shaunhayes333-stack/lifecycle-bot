package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6450 §P0 — IMMUTABLE ENTRY STRATEGY SNAPSHOT.
 *
 * OPERATOR MANDATE:
 *   "A position must not change strategy identity after entry.
 *    Examples requiring investigation:
 *      COPYTRADE/MENTUM_SWING BUY -> MOONSHOT SELL
 *      RESALE_SNIPE BUY -> MOONSHOT SELL
 *    Do not infer exit lane later from current scanner lane, token
 *    classification, latest tactic, source, symbol, or current watchlist
 *    ownership. Lane reassignment after purchase is forbidden unless an
 *    explicit canonical migration event exists."
 *
 * DESIGN
 * ──────
 * Keyed by canonical positionId. Snapshot is set exactly once on BUY.
 * Any subsequent write is REJECTED and logged (except explicit
 * `migrate()` which requires a reason). Exit paths call `snapshot()` and
 * MUST use its lane/pid/tactic — never the current scanner state.
 */
object EntryStrategySnapshot6450 {

    data class Snapshot(
        val positionId: String,
        val mint: String,
        val entryLane: String,
        val entryStrategyPid: String,
        val entryTactic: String,
        val entryRiskProfile: String,
        val entryExitProfile: String,
        val entrySource: String,
        val entryScore: Int,
        val entryLiquiditySol: Double,
        val entryMarketCapUsd: Double,
        val entryTimestampMs: Long,
        val entryThresholdSnapshot: String,
    )

    private val snapshots = ConcurrentHashMap<String, Snapshot>() // positionId -> Snapshot
    private val writes = AtomicLong(0L)
    private val rejects = AtomicLong(0L)
    private val migrations = AtomicLong(0L)
    private val laneChangeAttempts = AtomicLong(0L)

    fun setEntry(snap: Snapshot): Boolean {
        if (snap.positionId.isBlank()) { rejects.incrementAndGet(); return false }
        val prior = snapshots.putIfAbsent(snap.positionId, snap)
        if (prior != null) {
            rejects.incrementAndGet()
            val laneChanged = prior.entryLane != snap.entryLane
            if (laneChanged) {
                laneChangeAttempts.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "ENTRY_STRATEGY_LANE_REASSIGNMENT_REJECTED_6450",
                        "positionId=${snap.positionId.take(12)} priorLane=${prior.entryLane} attemptedLane=${snap.entryLane} mint=${snap.mint.take(10)}",
                    )
                    PipelineHealthCollector.labelInc("ENTRY_STRATEGY_LANE_REASSIGNMENT_REJECTED_6450")
                } catch (_: Throwable) {}
            }
            return false
        }
        writes.incrementAndGet()
        return true
    }

    fun snapshot(positionId: String): Snapshot? = snapshots[positionId]

    /** Explicit canonical migration event. Rare; only used when the
     *  operator confirms a legitimate re-classification via a canonical
     *  migration flag on the position. */
    fun migrate(positionId: String, newLane: String, reason: String): Snapshot? {
        val cur = snapshots[positionId] ?: return null
        val next = cur.copy(entryLane = newLane)
        snapshots[positionId] = next
        migrations.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "ENTRY_STRATEGY_MIGRATED_6450",
                "positionId=${positionId.take(12)} priorLane=${cur.entryLane} newLane=$newLane reason=${reason.take(40)}",
            )
            PipelineHealthCollector.labelInc("ENTRY_STRATEGY_MIGRATED_6450")
        } catch (_: Throwable) {}
        return next
    }

    /** Convenience: caller resolves exit lane strictly from snapshot; if
     *  no snapshot is registered (legacy position), caller falls back to
     *  the current runtime lane and we count it as unresolved. */
    fun resolveExitLane(positionId: String, fallbackLane: String): String {
        val s = snapshots[positionId]
        return if (s != null) {
            s.entryLane
        } else {
            try { PipelineHealthCollector.labelInc("ENTRY_STRATEGY_SNAPSHOT_MISS_6450") } catch (_: Throwable) {}
            fallbackLane
        }
    }

    fun statusLine(): String = "positions=${snapshots.size} writes=${writes.get()} " +
        "rejects=${rejects.get()} laneReassignAttempts=${laneChangeAttempts.get()} migrations=${migrations.get()}"
}

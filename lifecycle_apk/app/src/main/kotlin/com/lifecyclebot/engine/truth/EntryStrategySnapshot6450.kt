package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.LearningPersistence
import org.json.JSONObject
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
        val entryMarketRegime: String = "",
    )

    private val snapshots = ConcurrentHashMap<String, Snapshot>() // positionId -> Snapshot
    private val writes = AtomicLong(0L)
    private val rejects = AtomicLong(0L)
    private val migrations = AtomicLong(0L)
    private val laneChangeAttempts = AtomicLong(0L)

    fun setEntry(snap: Snapshot): Boolean {
        if (snap.positionId.isBlank()) { rejects.incrementAndGet(); return false }
        val restoredPrior6567 = snapshot(snap.positionId)
        val prior = restoredPrior6567 ?: snapshots.putIfAbsent(snap.positionId, snap)
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
        persist6567(snap)
        return true
    }

    private fun persistenceKey6567(positionId: String) = "entry_strategy_6450_$positionId"
    private fun persist6567(snap: Snapshot) {
        try {
            val j = JSONObject()
                .put("positionId", snap.positionId).put("mint", snap.mint)
                .put("lane", snap.entryLane).put("pid", snap.entryStrategyPid)
                .put("tactic", snap.entryTactic).put("risk", snap.entryRiskProfile)
                .put("exit", snap.entryExitProfile).put("source", snap.entrySource)
                .put("score", snap.entryScore).put("liq", snap.entryLiquiditySol)
                .put("mcap", snap.entryMarketCapUsd).put("at", snap.entryTimestampMs)
                .put("threshold", snap.entryThresholdSnapshot).put("regime", snap.entryMarketRegime)
            LearningPersistence.save(persistenceKey6567(snap.positionId), j.toString())
        } catch (_: Throwable) {}
    }
    private fun restore6567(positionId: String): Snapshot? {
        return try {
        val raw = LearningPersistence.load(persistenceKey6567(positionId)) ?: return null
        val j = JSONObject(raw)
        Snapshot(
            positionId = j.optString("positionId", positionId), mint = j.optString("mint", ""),
            entryLane = j.optString("lane", ""), entryStrategyPid = j.optString("pid", ""),
            entryTactic = j.optString("tactic", ""), entryRiskProfile = j.optString("risk", ""),
            entryExitProfile = j.optString("exit", ""), entrySource = j.optString("source", ""),
            entryScore = j.optInt("score", 0), entryLiquiditySol = j.optDouble("liq", 0.0),
            entryMarketCapUsd = j.optDouble("mcap", 0.0), entryTimestampMs = j.optLong("at", 0L),
            entryThresholdSnapshot = j.optString("threshold", ""), entryMarketRegime = j.optString("regime", ""),
        ).also { snapshots.putIfAbsent(positionId, it) }
    } catch (_: Throwable) { null }
    }

    fun snapshot(positionId: String): Snapshot? = snapshots[positionId] ?: restore6567(positionId)

    /** Explicit canonical migration event. Rare; only used when the
     *  operator confirms a legitimate re-classification via a canonical
     *  migration flag on the position. */
    fun migrate(positionId: String, newLane: String, reason: String): Snapshot? {
        val cur = snapshot(positionId) ?: return null
        val next = cur.copy(entryLane = newLane)
        snapshots[positionId] = next
        persist6567(next)
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
        val s = snapshot(positionId)
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

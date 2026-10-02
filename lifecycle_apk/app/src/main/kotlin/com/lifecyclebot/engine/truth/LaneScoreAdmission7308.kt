package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.7308 §ONE ADMISSION, ONE SCORE.
 *
 * FinalDecisionGate 7292 admits a specialist on its OWN lane score (80-90)
 * when the generic V3 score (0-15) cannot clear the floor. The execution
 * ticket still carried the V3 entry score, so Executor's pre-lease floor
 * (LiveMinimumScoreFloor7239, 30) refused the very trade FDG had admitted:
 * LIVE_BUY_REFUSED_PRELEASE_SCORE_7256 = 91 on 5.0.7305 against 8 FDG allows.
 * The routable-minimum lift then refused the same candidates again as
 * "pending proof".
 *
 * FDG records every lane-score admission here; the executor reads it for the
 * same mint within the TTL and judges the trade on the score it was admitted
 * on. Nothing is admitted here — this only carries FDG's verdict forward.
 *
 * It also holds the single live EXPLORATION slot: a lane not yet proven
 * (journal or shadow) may still take ONE live position at a time, so it can
 * earn real closes instead of waiting on proof it has no way to gather.
 */
object LaneScoreAdmission7308 {
    private const val TTL_MS = 10 * 60_000L
    private const val EXPLORATION_SPACING_MS = 5 * 60_000L

    data class Admission(val lane: String, val score: Double, val exploration: Boolean, val atMs: Long)

    private val byMint = ConcurrentHashMap<String, Admission>()
    @Volatile private var lastExplorationMs = 0L

    fun record(mint: String, lane: String, score: Double, exploration: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank() || !score.isFinite()) return
        byMint[mint] = Admission(lane.trim().uppercase(), score, exploration, nowMs)
        if (exploration) {
            lastExplorationMs = nowMs
            lastExplorationByLane7323[lane.trim().uppercase()] = nowMs
        }
        try {
            PipelineHealthCollector.labelInc(if (exploration) "LANE_EXPLORATION_ADMITTED_7308" else "LANE_SCORE_ADMISSION_RECORDED_7308")
        } catch (_: Throwable) {}
    }

    fun forMint(mint: String, nowMs: Long = System.currentTimeMillis()): Admission? {
        val a = byMint[mint] ?: return null
        if (nowMs - a.atMs > TTL_MS) { byMint.remove(mint); return null }
        return a
    }

    /** Pure: is the exploration slot free? */
    fun explorationSlotFree(openUnprovenLivePositions: Int, lastExplorationAtMs: Long, nowMs: Long): Boolean =
        openUnprovenLivePositions == 0 && (lastExplorationAtMs <= 0L || nowMs - lastExplorationAtMs >= EXPLORATION_SPACING_MS)

    /**
     * V5.0.7323 — runner lanes get their own exploration slot. The single
     * global slot was held by any unproven live position in any lane, so one
     * slow QUALITY/BLUECHIP exploration locked MOONSHOT out for its whole hold
     * and fresh launches died at FDG as CANONICAL_V3_SCORE_FLOOR_7243 (90).
     * MOONSHOT may hold 2 unproven live probes, other runner lanes 1, each
     * spaced 5 min per lane. The global slot is unchanged for everyone else.
     */
    private val lastExplorationByLane7323 = ConcurrentHashMap<String, Long>()

    /** Pure: is a runner lane's own exploration slot free? */
    fun runnerSlotFree(lane: String, openInLane: Int, lastAtMs: Long, nowMs: Long): Boolean {
        val cap = if (lane.contains("MOONSHOT")) 2 else 1
        return com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane) && openInLane < cap &&
            (lastAtMs <= 0L || nowMs - lastAtMs >= EXPLORATION_SPACING_MS)
    }

    /**
     * V5.0.7722 — every lane has its own exploration slot, not only the runner
     * lanes. 5.0.7720: the global slot (one unproven live position in ANY lane)
     * was held by the two open MOONSHOT/EXPRESS probes for their whole hold, so
     * TREASURY (laneScore=70, floor=16.4), BLUECHIP, QUALITY and CASHGEN died at
     * FDG as CANONICAL_V3_SCORE_FLOOR_7243 (622) with the generic V3 score 0..11
     * and never produced a close to learn from — the mirror image of the 7323
     * defect. One open unproven position per lane, spaced 5 min per lane; the
     * total book stays bounded by LiveConcentrationDoctrine7697.slots() (20),
     * which is the only authority on how many positions the wallet carries.
     */
    fun laneSlotFree7722(lane: String, openInLane: Int, lastAtMs: Long, nowMs: Long): Boolean =
        openInLane < 1 && (lastAtMs <= 0L || nowMs - lastAtMs >= EXPLORATION_SPACING_MS)

    fun runnerSlotFreeNow(lane: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val l = lane.trim().uppercase()
        val open = try {
            CanonicalPositionAuthority6441.openPositions().count {
                it.mode.equals("LIVE", ignoreCase = true) && it.lane.trim().uppercase() == l
            }
        } catch (_: Throwable) { return false }
        val lastAt = lastExplorationByLane7323[l] ?: 0L
        val runnerFree = runnerSlotFree(l, open, lastAt, nowMs)
        if (runnerFree) try { PipelineHealthCollector.labelInc("LANE_EXPLORATION_RUNNER_SLOT_7323_$l") } catch (_: Throwable) {}
        if (runnerFree) return true
        val laneFree7722 = !com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(l) && laneSlotFree7722(l, open, lastAt, nowMs)
        if (laneFree7722) try { PipelineHealthCollector.labelInc("LANE_EXPLORATION_LANE_SLOT_7722_$l") } catch (_: Throwable) {}
        return laneFree7722
    }

    fun explorationSlotFreeNow(isProven: (String) -> Boolean, nowMs: Long = System.currentTimeMillis()): Boolean {
        val openUnproven = try {
            CanonicalPositionAuthority6441.openPositions().count {
                it.mode.equals("LIVE", ignoreCase = true) && !isProven(it.lane.trim().uppercase())
            }
        } catch (_: Throwable) { return false }
        return explorationSlotFree(openUnproven, lastExplorationMs, nowMs)
    }
}

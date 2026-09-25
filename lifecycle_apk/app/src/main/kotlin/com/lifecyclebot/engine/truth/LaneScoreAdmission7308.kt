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
        if (exploration) lastExplorationMs = nowMs
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

    fun explorationSlotFreeNow(isProven: (String) -> Boolean, nowMs: Long = System.currentTimeMillis()): Boolean {
        val openUnproven = try {
            CanonicalPositionAuthority6441.openPositions().count {
                it.mode.equals("LIVE", ignoreCase = true) && !isProven(it.lane.trim().uppercase())
            }
        } catch (_: Throwable) { return false }
        return explorationSlotFree(openUnproven, lastExplorationMs, nowMs)
    }
}

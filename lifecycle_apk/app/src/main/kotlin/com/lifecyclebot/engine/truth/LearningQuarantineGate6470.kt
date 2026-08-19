package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6470 §P1 — LEARNING QUARANTINE GATE.
 *
 * OPERATOR MANDATE (verbatim):
 *
 *   "While canonical/registry divergence, lot invariant violation,
 *    capital conservation breach, unknown/orphan lifecycle generation
 *    or finalized-event duplication/divergence is true, DO NOT feed
 *    that trade into adaptive learning. The bot may continue PAPER
 *    execution/forensics, but corrupted economic outcomes must be
 *    quarantined. Do not tune lane performance using invalid paper
 *    accounting."
 *
 * DESIGN
 * ──────
 * Positions and mints marked "quarantined" here are barred from
 * learning fanout. `FinalizedBusConsumerBridge6465.deliver` must
 * consult this gate before dispatching to Governor, TacticSwitcher,
 * GrowthRewardShaper, CapitalCreed, EVEstimator, LosingStreakReflex
 * and LearnerRewardBridge.
 *
 * TRUTH: canonical execution/forensic paths continue. Only LEARNING
 * consumers are held back.
 *
 * A quarantine is permanent for the position id/mint within a run
 * (learning can never trust a corrupted lot after the fact). New
 * distinct positionIds/mints are unaffected.
 */
object LearningQuarantineGate6470 {

    private val quarantinedPositionIds = ConcurrentHashMap<String, String>()
    private val quarantinedMints = ConcurrentHashMap<String, String>()
    private val quarantines = AtomicLong(0L)
    private val learningDrops = AtomicLong(0L)

    fun quarantinePositionId(positionId: String, reason: String) {
        if (positionId.isBlank()) return
        val prev = quarantinedPositionIds.putIfAbsent(positionId, reason)
        if (prev == null) {
            quarantines.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "LEARNING_QUARANTINE_POSITION_6470",
                    "positionId=${positionId.take(24)} reason=$reason",
                )
                PipelineHealthCollector.labelInc("LEARNING_QUARANTINE_POSITION_6470")
            } catch (_: Throwable) {}
        }
    }

    fun quarantineMint(mint: String, reason: String) {
        if (mint.isBlank()) return
        val prev = quarantinedMints.putIfAbsent(mint, reason)
        if (prev == null) {
            quarantines.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "LEARNING_QUARANTINE_MINT_6470",
                    "mint=${mint.take(10)} reason=$reason",
                )
                PipelineHealthCollector.labelInc("LEARNING_QUARANTINE_MINT_6470")
            } catch (_: Throwable) {}
        }
    }

    fun isQuarantined(positionId: String?, mint: String?): Boolean {
        val byPid = positionId?.let { quarantinedPositionIds.containsKey(it) } ?: false
        val byMint = mint?.let { quarantinedMints.containsKey(it) } ?: false
        return byPid || byMint
    }

    /** Called by learning delivery paths. Returns true if delivery must be dropped. */
    fun shouldDropForLearning(positionId: String?, mint: String?): Boolean {
        val drop = isQuarantined(positionId, mint)
        if (drop) {
            learningDrops.incrementAndGet()
            try { PipelineHealthCollector.labelInc("LEARNING_QUARANTINE_DROPPED_6470") } catch (_: Throwable) {}
        }
        return drop
    }

    fun quarantineReason(positionId: String?, mint: String?): String? {
        positionId?.let { quarantinedPositionIds[it]?.let { r -> return r } }
        mint?.let { quarantinedMints[it]?.let { r -> return r } }
        return null
    }

    fun statusLine(): String =
        "quarantinedPositions=${quarantinedPositionIds.size} " +
            "quarantinedMints=${quarantinedMints.size} " +
            "totalQuarantines=${quarantines.get()} " +
            "learningDrops=${learningDrops.get()}"

    internal fun resetForTest() {
        quarantinedPositionIds.clear()
        quarantinedMints.clear()
        quarantines.set(0L)
        learningDrops.set(0L)
    }
}

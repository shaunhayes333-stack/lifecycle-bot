package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6621 §MEME_SOURCE_LEVEL_EXECUTION_PROVENANCE §5 (operator
 * directive Feb 2026):
 *
 *   "Make BUY execution transactional. Change specialist wrappers to
 *    return a definitive result:
 *      sealed interface BuyResult {
 *        data class Opened(val positionId, val fillPrice, val filledSol)
 *        data class Rejected(val reason)
 *        data class Failed(val reason)
 *      }
 *    NO specialist state may be committed until BuyResult.Opened."
 *
 * Slice 2 delivers the CONTRACT + AUTHORITY receiver so downstream
 * specialist wrappers (Slice 3 rollout) route their outcomes through
 * this single record. Existing paperBuy/liveBuy signatures stay
 * `Unit` for API compatibility during the slice-2-to-3 transition; the
 * transactional outcome is journaled here at every open/reject/fail
 * boundary so the operator can prove no specialist state committed
 * before Opened.
 */
object BuyResult6621 {

    sealed interface Outcome {
        val attemptId: String
        data class Opened(
            override val attemptId: String,
            val positionId: String,
            val fillPrice: Double,
            val filledSol: Double,
            val lane: String,
        ) : Outcome
        data class Rejected(
            override val attemptId: String,
            val lane: String,
            val reason: String,
        ) : Outcome
        data class Failed(
            override val attemptId: String,
            val lane: String,
            val reason: String,
        ) : Outcome
    }

    private val outcomes = ConcurrentHashMap<String, Outcome>()
    private val opened = AtomicLong(0L)
    private val rejected = AtomicLong(0L)
    private val failed = AtomicLong(0L)
    private val prematureCommitAttempts = AtomicLong(0L)

    /**
     * Called by the executor at the moment it knows the outcome —
     * BEFORE it invokes any specialist state commit. Idempotent per
     * attemptId (second call is dropped so exit paths that observe
     * their own attempt via a callback don't double-count).
     */
    fun record6621(o: Outcome) {
        val existing = outcomes.putIfAbsent(o.attemptId, o) ?: run {
            when (o) {
                is Outcome.Opened -> {
                    opened.incrementAndGet()
                    try {
                        PipelineHealthCollector.labelInc("BUY_RESULT_OPENED_6621")
                        PipelineHealthCollector.labelInc("BUY_RESULT_OPENED_${o.lane.uppercase()}_6621")
                        ForensicLogger.lifecycle(
                            "BUY_RESULT_OPENED_6621",
                            "attemptId=${o.attemptId} lane=${o.lane} " +
                                "positionId=${o.positionId.take(18)} " +
                                "fillPrice=${"%.6g".format(o.fillPrice)} " +
                                "filledSol=${"%.4f".format(o.filledSol)}",
                        )
                    } catch (_: Throwable) {}
                }
                is Outcome.Rejected -> {
                    rejected.incrementAndGet()
                    try {
                        PipelineHealthCollector.labelInc("BUY_RESULT_REJECTED_6621")
                        PipelineHealthCollector.labelInc("BUY_RESULT_REJECTED_${o.lane.uppercase()}_6621")
                    } catch (_: Throwable) {}
                }
                is Outcome.Failed -> {
                    failed.incrementAndGet()
                    try {
                        PipelineHealthCollector.labelInc("BUY_RESULT_FAILED_6621")
                        PipelineHealthCollector.labelInc("BUY_RESULT_FAILED_${o.lane.uppercase()}_6621")
                    } catch (_: Throwable) {}
                }
            }
            o
        }
        // NOTE: `existing` may be non-null on idempotent second call;
        // that's harmless — outcome sealed once.
        @Suppress("UNUSED_VARIABLE") val ignoredExisting6621 = existing
    }

    /**
     * V5.0.6621 §5 invariant probe. Specialist entry sites (addPosition,
     * onPositionOpened, learning-arm, exposure register) call this
     * BEFORE mutating state — if the outcome for their attemptId is
     * not Opened, they must NOT commit and this counter records the
     * near-miss. Slice 3 will convert these near-misses into hard
     * refusals; Slice 2 telemetry-only.
     */
    fun assertOpenedOrCountPremature6621(
        attemptId: String,
        lane: String,
        commitSite: String,
    ): Boolean {
        val outcome = outcomes[attemptId]
        if (outcome is Outcome.Opened) return true
        prematureCommitAttempts.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("PREMATURE_SPECIALIST_STATE_COMMIT_6621")
            PipelineHealthCollector.labelInc("PREMATURE_SPECIALIST_STATE_COMMIT_${lane.uppercase()}_6621")
            ForensicLogger.lifecycle(
                "PREMATURE_SPECIALIST_STATE_COMMIT_6621",
                "attemptId=$attemptId lane=$lane commitSite=${commitSite.take(60)} " +
                    "outcome=${outcome?.javaClass?.simpleName ?: "NULL"}",
            )
        } catch (_: Throwable) {}
        return false
    }

    fun byAttempt6621(attemptId: String): Outcome? = outcomes[attemptId]

    fun statusLine(): String =
        "opened=${opened.get()} rejected=${rejected.get()} failed=${failed.get()} " +
            "prematureCommitAttempts=${prematureCommitAttempts.get()} " +
            "liveOutcomes=${outcomes.size}"

    internal fun resetForTest() {
        outcomes.clear()
        opened.set(0L); rejected.set(0L); failed.set(0L)
        prematureCommitAttempts.set(0L)
    }
}

/**
 * V5.0.6621 §MEME_SOURCE_LEVEL_EXECUTION_PROVENANCE §3 (operator
 * directive Feb 2026):
 *
 *   "A position needs two different concepts.
 *      IMMUTABLE  entryLane: MemeLane  (specialist that opened it)
 *      MUTABLE    activeLane / strategyMode / tacticMode / moonshotStage
 *    Do NOT overwrite historical entry provenance to implement a mode
 *    transition. entryLane = PROJECT_SNIPER, activeLane = MOONSHOT is
 *    valid. Changing the original entry lane to something invented
 *    by UnifiedModeOrchestrator is not."
 *
 * Because Position.tradingMode is already mutable and read widely, we
 * do NOT modify the Position data class — we introduce a companion
 * registry that seals the ENTRY lane at position creation and refuses
 * to allow mutation thereafter. Anyone can read the immutable entry
 * lane via entryLane(positionId); if a mode transition needs to
 * advertise a different active lane, they mutate Position.tradingMode
 * freely — but the ENTRY provenance stays sealed.
 */
object PositionEntryLaneRegistry6621 {

    private val entryLanes = ConcurrentHashMap<String, String>()
    private val seals = AtomicLong(0L)
    private val mutationAttempts = AtomicLong(0L)

    /**
     * Seal a position's ENTRY lane. Idempotent — a second call with
     * the SAME lane is silently accepted; a call with a DIFFERENT lane
     * emits POSITION_ENTRY_LANE_MUTATED_ATTEMPT_6621 and REFUSES the
     * rewrite. Returns the sealed lane (which may differ from the
     * caller's value if a prior seal exists).
     */
    fun seal6621(positionId: String, lane: String): String {
        if (positionId.isBlank()) return lane
        val laneCanonical = MemeExecutionIntent6621.canonicaliseLane6621(lane)
        val existing = entryLanes.putIfAbsent(positionId, laneCanonical)
        if (existing == null) {
            seals.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("POSITION_ENTRY_LANE_SEALED_6621")
                PipelineHealthCollector.labelInc("POSITION_ENTRY_LANE_SEALED_${laneCanonical}_6621")
            } catch (_: Throwable) {}
            return laneCanonical
        }
        if (existing != laneCanonical) {
            mutationAttempts.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("POSITION_ENTRY_LANE_MUTATED_ATTEMPT_6621")
                ForensicLogger.lifecycle(
                    "POSITION_ENTRY_LANE_MUTATED_ATTEMPT_6621",
                    "positionId=${positionId.take(18)} sealed=$existing " +
                        "callerAttemptedRewrite=$laneCanonical action=refuse_rewrite",
                )
            } catch (_: Throwable) {}
        }
        return existing
    }

    fun entryLane6621(positionId: String): String? = entryLanes[positionId]

    fun statusLine(): String =
        "sealed=${seals.get()} mutationAttempts=${mutationAttempts.get()} live=${entryLanes.size}"

    internal fun resetForTest() {
        entryLanes.clear(); seals.set(0L); mutationAttempts.set(0L)
    }
}

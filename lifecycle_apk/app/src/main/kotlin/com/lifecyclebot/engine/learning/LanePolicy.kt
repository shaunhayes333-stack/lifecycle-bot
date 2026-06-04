package com.lifecyclebot.engine.learning

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.LearningPersistence
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1321 — Train-First Learning Policy (Base44/Emergent operator directive).
 *
 * Lane / score-bucket policy state machine. Replaces the implicit
 * "hard block on losing bucket" with explicit routing states.
 *
 *   TRAINABILITY ≠ EXECUTABILITY
 *
 *   - Bad lanes/buckets should be demoted, downsized, sandboxed,
 *     or paper-only — NOT deleted from the learning universe.
 *   - Only genuinely unsafe / invalid states should hard-block.
 *
 * This module owns the per-lane / per-bucket policy state and the
 * dynamic learning vs execution weights. Other modules (FDG, Executor,
 * sub-traders, hypothesis engine) consume these states to route a
 * candidate to its correct training/execution path.
 *
 * Persistence: each lane's state is written to LearningPersistence as
 * a JSON blob keyed by "lane_policy_${LANE}". The state is loaded on
 * first read per lane and re-saved on every mutation.
 */
object LanePolicy {

    /**
     * Per-lane / per-bucket policy state. Order matters: the higher
     * the ordinal, the more "executable" the state.
     */
    enum class State {
        INVALID_UNTRADEABLE,
        TRAIN_ONLY_NO_OPEN,
        SHADOW_TRACK_ONLY,
        RETRAINING,
        DEMOTION_CANDIDATE,
        PAPER_MICRO_EXECUTION,
        REDUCED_SIZE_EXECUTION,
        PROMOTION_CANDIDATE,
        NORMAL_EXECUTION,
    }

    data class LaneState(
        val lane: String,
        val policy: State,
        val learningWeight: Double,
        val executionWeight: Double,
        val lastImprovedAt: Long,
        val retrainingSampleCount: Int,
        val recoveryCandidate: Boolean,
    )

    private data class Cell(
        val policy: AtomicInteger,
        val learningWeight: AtomicLong,
        val executionWeight: AtomicLong,
        val lastImprovedAt: AtomicLong,
        val retrainingSampleCount: AtomicInteger,
        val recoveryCandidate: AtomicLong,
    )

    private val lanes = ConcurrentHashMap<String, Cell>()
    private val buckets = ConcurrentHashMap<String, Cell>()

    private const val WEIGHT_SCALE = 1000.0

    private fun newCell(initialPolicy: State, learningW: Double, executionW: Double): Cell = Cell(
        policy           = AtomicInteger(initialPolicy.ordinal),
        learningWeight   = AtomicLong((learningW * WEIGHT_SCALE).toLong()),
        executionWeight  = AtomicLong((executionW * WEIGHT_SCALE).toLong()),
        lastImprovedAt   = AtomicLong(System.currentTimeMillis()),
        retrainingSampleCount = AtomicInteger(0),
        recoveryCandidate = AtomicLong(0L),
    )

    private fun defaultPolicyFor(lane: String): State {
        val key = lane.uppercase()
        return when {
            key.startsWith("UNKNOWN")     -> State.PAPER_MICRO_EXECUTION  // V5.9.1325: never stop trading — micro-probe unknown lanes
            key.contains("SHITCOIN")      -> State.PAPER_MICRO_EXECUTION
            key.contains("MANIPULATED")   -> State.PAPER_MICRO_EXECUTION
            key.contains("MOONSHOT")      -> State.REDUCED_SIZE_EXECUTION
            key.contains("TREASURY")      -> State.NORMAL_EXECUTION
            key.contains("QUALITY")       -> State.NORMAL_EXECUTION
            key.contains("BLUECHIP")      -> State.NORMAL_EXECUTION
            key.contains("WHALE")         -> State.REDUCED_SIZE_EXECUTION
            key.contains("PRESALE")       -> State.PAPER_MICRO_EXECUTION
            key.contains("COPY")          -> State.REDUCED_SIZE_EXECUTION
            else                          -> State.REDUCED_SIZE_EXECUTION
        }
    }

    private fun defaultLearningWeight(state: State): Double = when (state) {
        State.INVALID_UNTRADEABLE     -> 0.10
        State.TRAIN_ONLY_NO_OPEN      -> 1.00
        State.SHADOW_TRACK_ONLY       -> 1.00
        State.RETRAINING              -> 1.00
        State.DEMOTION_CANDIDATE      -> 0.80
        State.PAPER_MICRO_EXECUTION   -> 1.00
        State.REDUCED_SIZE_EXECUTION  -> 1.00
        State.PROMOTION_CANDIDATE     -> 1.00
        State.NORMAL_EXECUTION        -> 1.00
    }

    private fun defaultExecutionWeight(state: State): Double = when (state) {
        State.INVALID_UNTRADEABLE     -> 0.00
        State.TRAIN_ONLY_NO_OPEN      -> 0.00
        State.SHADOW_TRACK_ONLY       -> 0.00
        State.RETRAINING              -> 0.20
        State.DEMOTION_CANDIDATE      -> 0.40
        State.PAPER_MICRO_EXECUTION   -> 0.10
        State.REDUCED_SIZE_EXECUTION  -> 0.60
        State.PROMOTION_CANDIDATE     -> 0.80
        State.NORMAL_EXECUTION        -> 1.00
    }

    private fun laneKey(lane: String): String = lane.uppercase().take(32)
    private fun bucketKey(lane: String, scoreBand: String): String = "${laneKey(lane)}|${scoreBand.uppercase()}"

    private fun getOrCreateLaneCell(lane: String): Cell {
        val k = laneKey(lane)
        return lanes.computeIfAbsent(k) {
            val def = defaultPolicyFor(k)
            newCell(def, defaultLearningWeight(def), defaultExecutionWeight(def)).also {
                loadFromPersistenceIfAny("lane", k, it)
            }
        }
    }

    private fun getOrCreateBucketCell(lane: String, scoreBand: String): Cell {
        val k = bucketKey(lane, scoreBand)
        return buckets.computeIfAbsent(k) {
            val laneDef = defaultPolicyFor(lane)
            newCell(laneDef, defaultLearningWeight(laneDef), defaultExecutionWeight(laneDef)).also {
                loadFromPersistenceIfAny("bucket", k, it)
            }
        }
    }

    fun stateForLane(lane: String): State = State.values()[getOrCreateLaneCell(lane).policy.get()]

    fun stateForBucket(lane: String, scoreBand: String): State =
        State.values()[getOrCreateBucketCell(lane, scoreBand).policy.get()]

    fun effectiveState(lane: String, scoreBand: String): State {
        val laneState = stateForLane(lane)
        val bucketState = stateForBucket(lane, scoreBand)
        return if (laneState.ordinal <= bucketState.ordinal) laneState else bucketState
    }

    fun learningWeightForLane(lane: String): Double = getOrCreateLaneCell(lane).learningWeight.get() / WEIGHT_SCALE
    fun learningWeightForBucket(lane: String, scoreBand: String): Double = getOrCreateBucketCell(lane, scoreBand).learningWeight.get() / WEIGHT_SCALE

    fun executionWeightForLane(lane: String): Double = getOrCreateLaneCell(lane).executionWeight.get() / WEIGHT_SCALE
    fun executionWeightForBucket(lane: String, scoreBand: String): Double = getOrCreateBucketCell(lane, scoreBand).executionWeight.get() / WEIGHT_SCALE

    fun effectiveExecutionWeight(lane: String, scoreBand: String): Double {
        val laneW = executionWeightForLane(lane)
        val bucketW = executionWeightForBucket(lane, scoreBand)
        return (laneW * bucketW).coerceIn(0.0, 1.0)
    }

    fun setLaneState(lane: String, state: State, reason: String = "") {
        val cell = getOrCreateLaneCell(lane)
        val prev = cell.policy.getAndSet(state.ordinal)
        if (prev != state.ordinal) {
            cell.executionWeight.set((defaultExecutionWeight(state) * WEIGHT_SCALE).toLong())
            cell.learningWeight.set((defaultLearningWeight(state) * WEIGHT_SCALE).toLong())
            persist("lane", laneKey(lane), cell)
            ErrorLogger.info("LanePolicy", "🧭 Lane ${laneKey(lane)} ${State.values()[prev]} → ${state} (${reason.take(80)})")
        }
    }

    fun setBucketState(lane: String, scoreBand: String, state: State, reason: String = "") {
        val cell = getOrCreateBucketCell(lane, scoreBand)
        val prev = cell.policy.getAndSet(state.ordinal)
        if (prev != state.ordinal) {
            cell.executionWeight.set((defaultExecutionWeight(state) * WEIGHT_SCALE).toLong())
            cell.learningWeight.set((defaultLearningWeight(state) * WEIGHT_SCALE).toLong())
            persist("bucket", bucketKey(lane, scoreBand), cell)
            ErrorLogger.info("LanePolicy", "🧭 Bucket ${bucketKey(lane, scoreBand)} ${State.values()[prev]} → ${state} (${reason.take(80)})")
        }
    }

    fun noteRetrainingSample(lane: String, scoreBand: String) {
        getOrCreateLaneCell(lane).retrainingSampleCount.incrementAndGet()
        getOrCreateBucketCell(lane, scoreBand).retrainingSampleCount.incrementAndGet()
    }

    fun noteImprovement(lane: String, scoreBand: String) {
        val now = System.currentTimeMillis()
        getOrCreateLaneCell(lane).also {
            it.lastImprovedAt.set(now)
            it.recoveryCandidate.set(1L)
        }
        getOrCreateBucketCell(lane, scoreBand).also {
            it.lastImprovedAt.set(now)
            it.recoveryCandidate.set(1L)
        }
    }

    fun snapshotForLane(lane: String): LaneState {
        val cell = getOrCreateLaneCell(lane)
        return LaneState(
            lane = laneKey(lane),
            policy = State.values()[cell.policy.get()],
            learningWeight = cell.learningWeight.get() / WEIGHT_SCALE,
            executionWeight = cell.executionWeight.get() / WEIGHT_SCALE,
            lastImprovedAt = cell.lastImprovedAt.get(),
            retrainingSampleCount = cell.retrainingSampleCount.get(),
            recoveryCandidate = cell.recoveryCandidate.get() == 1L,
        )
    }

    fun allLanes(): List<LaneState> = lanes.keys.map { snapshotForLane(it) }

    fun snapshotForBucket(lane: String, scoreBand: String): LaneState {
        val cell = getOrCreateBucketCell(lane, scoreBand)
        return LaneState(
            lane = bucketKey(lane, scoreBand),
            policy = State.values()[cell.policy.get()],
            learningWeight = cell.learningWeight.get() / WEIGHT_SCALE,
            executionWeight = cell.executionWeight.get() / WEIGHT_SCALE,
            lastImprovedAt = cell.lastImprovedAt.get(),
            retrainingSampleCount = cell.retrainingSampleCount.get(),
            recoveryCandidate = cell.recoveryCandidate.get() == 1L,
        )
    }

    fun allBuckets(): List<LaneState> = buckets.keys.map { k ->
        val parts = k.split("|")
        if (parts.size >= 2) snapshotForBucket(parts[0], parts[1]) else snapshotForBucket(k, "")
    }

    private fun persist(kind: String, key: String, cell: Cell) {
        try {
            val json = """{"policy":${cell.policy.get()},"lw":${cell.learningWeight.get()},"ew":${cell.executionWeight.get()},"last":${cell.lastImprovedAt.get()},"rsc":${cell.retrainingSampleCount.get()},"rc":${cell.recoveryCandidate.get()}}"""
            LearningPersistence.save("${kind}_policy_${key}", json)
        } catch (_: Throwable) {}
    }

    private fun loadFromPersistenceIfAny(kind: String, key: String, cell: Cell) {
        try {
            val raw = LearningPersistence.load("${kind}_policy_${key}") ?: return
            val policy = Regex(""""policy":(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val lw     = Regex(""""lw":(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()
            val ew     = Regex(""""ew":(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()
            val last   = Regex(""""last":(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()
            val rsc    = Regex(""""rsc":(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val rc     = Regex(""""rc":(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()
            if (policy != null && policy in State.values().indices) cell.policy.set(policy)
            if (lw != null) cell.learningWeight.set(lw)
            if (ew != null) cell.executionWeight.set(ew)
            if (last != null) cell.lastImprovedAt.set(last)
            if (rsc != null) cell.retrainingSampleCount.set(rsc)
            if (rc != null) cell.recoveryCandidate.set(rc)
        } catch (_: Throwable) {}
    }
}

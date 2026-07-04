package com.lifecyclebot.engine.learning

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.RegimeDetector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
        // V5.9.1460 — rolling outcome window that DRIVES auto demote/promote.
        // This is the missing learning edge: before 1460 no outcome ever moved
        // a lane's policy State, so the entry gate (FdgRouteVerdict) read a state
        // that could only ratchet UP. A bleeding lane kept full execution weight
        // forever → WR could never climb 25%→50% no matter how many trades.
        val winWindow: AtomicInteger = AtomicInteger(0),
        val lossWindow: AtomicInteger = AtomicInteger(0),
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
            key.startsWith("UNKNOWN")     -> State.RETRAINING  // V5.0.6107: observe/retrain, no paid micro execution
            // V5.0.4526 — restore AATE core execution semantics. These are LIVE
            // meme strategy lanes, not permanent paper-micro lanes. Toxic buckets
            // should be pivoted by AgenticStyleRouter/LaneToxicityGuard/LiveStylePivotRouter,
            // not bought the same way at dust size forever.
            key.contains("SHITCOIN")      -> State.REDUCED_SIZE_EXECUTION
            key.contains("MANIPULATED")   -> State.REDUCED_SIZE_EXECUTION
            key.contains("MOONSHOT")      -> State.REDUCED_SIZE_EXECUTION
            key.contains("TREASURY")      -> State.NORMAL_EXECUTION
            key.contains("QUALITY")       -> State.NORMAL_EXECUTION
            key.contains("BLUECHIP")      -> State.NORMAL_EXECUTION
            key.contains("WHALE")         -> State.REDUCED_SIZE_EXECUTION
            key.contains("PRESALE")       -> State.RETRAINING
            // V5.0.6094 — new lanes are first-class policy citizens, not anonymous
            // fall-throughs. Paper samples them normally; live authority is still
            // controlled by FDG route verdict/quarantine and lab-proof release.
            key.contains("DIAMOND")       -> State.REDUCED_SIZE_EXECUTION
            key.contains("INSIDER")       -> State.REDUCED_SIZE_EXECUTION
            key.contains("SHARK")         -> State.REDUCED_SIZE_EXECUTION
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
        State.RETRAINING              -> 0.00
        State.DEMOTION_CANDIDATE      -> 0.40
        State.PAPER_MICRO_EXECUTION   -> 0.00
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

    // V5.9.1460 — public setters so RetrainingDecay's per-loss decay actually
    // lands on the LIVE cell the entry gate reads. Before this, RetrainingDecay
    // wrote only to a LearningPersistence key that LanePolicy never read back, so
    // the decay was a no-op (write-only loop). Now it mutates the cell + persists.
    fun setExecutionWeightLaneCell(lane: String, weight: Double) {
        val cell = getOrCreateLaneCell(lane)
        cell.executionWeight.set((weight.coerceIn(0.0, 1.0) * WEIGHT_SCALE).toLong())
        persist("lane", laneKey(lane), cell)
    }
    fun setExecutionWeightBucketCell(lane: String, scoreBand: String, weight: Double) {
        val cell = getOrCreateBucketCell(lane, scoreBand)
        cell.executionWeight.set((weight.coerceIn(0.0, 1.0) * WEIGHT_SCALE).toLong())
        persist("bucket", bucketKey(lane, scoreBand), cell)
    }

    fun effectiveExecutionWeight(lane: String, scoreBand: String): Double {
        // V5.9.1461 — FLUID, NON-DOUBLE-COUNTED blend (operator: "keep good
        // throughput, adjust in a fluid live state, consistent improvement").
        // The bucket (lane × score-band) is the PRIMARY, most-specific signal —
        // it's what actually predicts this trade's edge. The lane-level weight is
        // a softer, slower-moving CEILING (a whole lane bleeding pulls everything
        // down a bit). The previous raw product (laneW * bucketW) squared the
        // decay — both fall to the 0.15 floor on a loss streak → 0.15×0.15 ≈ 2%
        // size, over-suppressing a RECOVERING bucket and starving the samples it
        // needs to climb back. Instead: bucket is the signal, lane is a gentle
        // 70/30 ceiling blend. Worst case floors at ~0.15 (not 0.02), so a
        // demoted lane keeps enough size to generate a real recovery signal while
        // still risking far less capital. This is the fluid-progression knob.
        val laneW = executionWeightForLane(lane)
        val bucketW = executionWeightForBucket(lane, scoreBand)
        val blended = (0.70 * bucketW) + (0.30 * (bucketW * laneW))
        return blended.coerceIn(0.0, 1.0)
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

    // V5.9.1460 — THE CLOSED LEARNING LOOP. Called from the MEME close fanout
    // (V3JournalRecorder.recordClose) for every settled trade. Accumulates a
    // rolling win/loss window per lane AND per (lane,band) bucket, then moves the
    // policy State so the entry gate actually responds to results:
    //   • bleeding bucket  → step DOWN one executable rung (e.g. NORMAL →
    //     REDUCED_SIZE → DEMOTION_CANDIDATE → RETRAINING). V5.0.6107:
    //     train-first means observe + LLM Lab re-strategize, not paying for
    //     paper/live micro execution of proven-toxic setups.
    //   • recovering bucket → step UP one rung toward NORMAL_EXECUTION.
    // Window resets after each transition so the next regime is judged fresh.
    private const val OUTCOME_WINDOW_MIN_SAMPLES = 12   // need a real sample before moving
    private const val DEMOTE_WR = 0.18                  // < 18% WR over the window = bleeding
    private const val PROMOTE_WR = 0.42                 // > 42% WR over the window = recovering

    // V5.9.1464 — public rolling-WR reader for lane-level executable size shaping.
    // Returns the lane window WR in [0,1], or null until enough samples exist
    // (caller treats null as "no opinion yet — use the spec's default cap").
    fun rollingWr(lane: String): Double? {
        if (lane.isBlank()) return null
        val cell = getOrCreateLaneCell(lane)
        val w = cell.winWindow.get(); val l = cell.lossWindow.get(); val n = w + l
        if (n < OUTCOME_WINDOW_MIN_SAMPLES) return null
        return w.toDouble() / n
    }

    fun rollingWrForBucket(lane: String, scoreBand: String): Double? {
        if (lane.isBlank()) return null
        val cell = getOrCreateBucketCell(lane, scoreBand)
        val w = cell.winWindow.get(); val l = cell.lossWindow.get(); val n = w + l
        if (n < OUTCOME_WINDOW_MIN_SAMPLES) return null
        return w.toDouble() / n
    }

    /**
     * V5.0.3804 — persistent-bleed auto-pivot cap.
     *
     * Root bug pattern: RetrainingDecay/LanePolicy could correctly learn that a lane
     * or bucket was bleeding, but FdgRouteVerdict.ALLOW_NORMAL later floored size
     * back to 85%, undoing the pivot. This returns a soft execution cap for proven
     * low-WR windows. It never vetoes a valid candidate and never changes FDG score
     * floors; it only keeps learned decay authoritative at the route-size source.
     */
    fun bleedExecutionCap(lane: String, scoreBand: String): Double? {
        val laneWr = rollingWr(lane)
        val bucketWr = rollingWrForBucket(lane, scoreBand)
        val wr = listOfNotNull(laneWr, bucketWr).minOrNull() ?: return null
        val regime = try { RegimeDetector.currentRegime() } catch (_: Throwable) { RegimeDetector.Regime.NORMAL }
        val cap = when {
            regime == RegimeDetector.Regime.DEAD && wr < 0.25 -> 0.08
            regime == RegimeDetector.Regime.DUMP && wr < 0.15 -> 0.10
            regime == RegimeDetector.Regime.DUMP && wr < 0.20 -> 0.18
            wr < DEMOTE_WR -> 0.22
            wr < 0.25 -> 0.35
            else -> null
        }
        if (cap != null) {
            try { PipelineHealthCollector.labelInc("LANE_BLEED_EXECUTION_CAP|${laneKey(lane)}") } catch (_: Throwable) {}
        }
        return cap
    }

    fun recordOutcome(lane: String, scoreBand: String, isWin: Boolean, isLoss: Boolean) {
        if (lane.isBlank()) return
        if (!isWin && !isLoss) return  // scratch/breakeven — neutral, doesn't move policy
        // Drive both the lane-level and the bucket-level windows.
        evalCellOutcome(getOrCreateLaneCell(lane), isWin, "lane", laneKey(lane), lane, scoreBand)
        evalCellOutcome(getOrCreateBucketCell(lane, scoreBand), isWin, "bucket", bucketKey(lane, scoreBand), lane, scoreBand)
    }

    private fun evalCellOutcome(cell: Cell, isWin: Boolean, kind: String, key: String, lane: String, scoreBand: String) {
        if (isWin) cell.winWindow.incrementAndGet() else cell.lossWindow.incrementAndGet()
        val w = cell.winWindow.get(); val l = cell.lossWindow.get(); val n = w + l
        if (n < OUTCOME_WINDOW_MIN_SAMPLES) return
        val wr = w.toDouble() / n
        val cur = State.values()[cell.policy.get()]
        val next = when {
            wr < DEMOTE_WR  -> demoteOneRung(cur)
            wr > PROMOTE_WR -> promoteOneRung(cur)
            else            -> cur
        }
        if (next != cur) {
            cell.policy.set(next.ordinal)
            cell.executionWeight.set((defaultExecutionWeight(next) * WEIGHT_SCALE).toLong())
            cell.learningWeight.set((defaultLearningWeight(next) * WEIGHT_SCALE).toLong())
            persist(kind, key, cell)
            if (next.ordinal > cur.ordinal) noteImprovement(lane, scoreBand)
            ErrorLogger.info("LanePolicy",
                "🧭 ${kind.uppercase()} $key $cur → $next (wr=${"%.0f".format(wr*100)}% n=$n) [AUTO-${if (next.ordinal<cur.ordinal) "DEMOTE" else "PROMOTE"}]")
        }
        // reset the window after enough samples so the next regime is judged fresh
        if (n >= OUTCOME_WINDOW_MIN_SAMPLES * 2) { cell.winWindow.set(0); cell.lossWindow.set(0) }
    }

    // Step DOWN exactly one executable rung. FLOOR at RETRAINING so a valid lane
    // remains trainable through NoTradeObservation + LLM Lab strategy search, but
    // does not keep paying for toxic paper/live micro execution.
    private fun demoteOneRung(s: State): State = when (s) {
        State.NORMAL_EXECUTION       -> State.REDUCED_SIZE_EXECUTION
        State.PROMOTION_CANDIDATE    -> State.REDUCED_SIZE_EXECUTION
        State.REDUCED_SIZE_EXECUTION -> State.DEMOTION_CANDIDATE
        State.DEMOTION_CANDIDATE     -> State.RETRAINING
        else                         -> s   // already at/below retraining → hold (keep observing)
    }

    // Step UP exactly one rung toward NORMAL_EXECUTION.
    private fun promoteOneRung(s: State): State = when (s) {
        State.PAPER_MICRO_EXECUTION  -> State.DEMOTION_CANDIDATE
        State.RETRAINING             -> State.DEMOTION_CANDIDATE
        State.DEMOTION_CANDIDATE     -> State.REDUCED_SIZE_EXECUTION
        State.REDUCED_SIZE_EXECUTION -> State.PROMOTION_CANDIDATE
        State.PROMOTION_CANDIDATE    -> State.NORMAL_EXECUTION
        else                         -> s
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

    // V5.9.1462 — ASYNC + COALESCED persistence. Was synchronous: LearningPersistence
    // .save() does two execSQL writes on the CALLER thread, and 1460 calls persist()
    // up to 6× per trade close (lane+bucket × recordOutcome/RetrainingDecay/setWeight).
    // At 500-1000 closes/day in bursts that serialized SQLite I/O on the exit pipeline
    // → "parked again" + ANR. Now: stage the newest JSON per key (only latest matters,
    // idempotent) and flush on Dispatchers.IO. Durable, but zero pipeline blocking.
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingWrites = ConcurrentHashMap<String, String>()

    private val drainInFlight = ConcurrentHashMap<String, Boolean>()

    private fun persist(kind: String, key: String, cell: Cell) {
        try {
            val storeKey = "${kind}_policy_${key}"
            val json = """{"policy":${cell.policy.get()},"lw":${cell.learningWeight.get()},"ew":${cell.executionWeight.get()},"last":${cell.lastImprovedAt.get()},"rsc":${cell.retrainingSampleCount.get()},"rc":${cell.recoveryCandidate.get()}}"""
            // Always stage the newest value (coalesces repeat writes; only latest matters).
            pendingWrites[storeKey] = json
            // Ensure exactly ONE drain coroutine per key. The drain loops until the
            // staged value is fully flushed, so no final state is ever dropped even
            // if more writes arrive mid-flush (reliable persistence, never blocks caller).
            if (drainInFlight.putIfAbsent(storeKey, true) == null) {
                persistScope.launch {
                    try {
                        while (true) {
                            val latest = pendingWrites.remove(storeKey) ?: break
                            try { LearningPersistence.save(storeKey, latest) } catch (_: Throwable) {}
                        }
                    } finally {
                        drainInFlight.remove(storeKey)
                        // A write may have staged between the last remove() and the flag clear.
                        if (pendingWrites.containsKey(storeKey) && drainInFlight.putIfAbsent(storeKey, true) == null) {
                            persistScope.launch {
                                try {
                                    while (true) {
                                        val latest2 = pendingWrites.remove(storeKey) ?: break
                                        try { LearningPersistence.save(storeKey, latest2) } catch (_: Throwable) {}
                                    }
                                } finally { drainInFlight.remove(storeKey) }
                            }
                        }
                    }
                }
            }
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

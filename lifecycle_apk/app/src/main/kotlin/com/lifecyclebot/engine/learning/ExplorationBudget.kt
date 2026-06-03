package com.lifecyclebot.engine.learning

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1323 — Exploration Budgets + Retraining Decay (Build C).
 *
 * Operator directive §4 + §6:
 *   - per-lane controlled exploration, not gambling, not silence
 *   - decay applied to bad policies; recovery when evidence improves
 *   - bad lanes never permanently dead unless operator-disabled
 */
object ExplorationBudget {

    data class LaneBudget(
        val lane: String,
        val maxPaperMicroTradesPerHour: Int,
        val maxShadowSignalsPerHour: Int,
        val maxRetrainingSamplesPerBucket: Int,
        val minSamplesBeforePromotion: Int,
        val minSamplesBeforeRetirement: Int,
        val maxCapitalAtRiskForRetrainingPct: Double,  // % of session budget
        val liveExplorationAllowed: Boolean,
    )

    // Defaults per operator's §4. Lanes not listed inherit DEFAULT_LANE_BUDGET.
    private val laneBudgets = ConcurrentHashMap<String, LaneBudget>().also {
        it["SHITCOIN"]    = LaneBudget("SHITCOIN",    maxPaperMicroTradesPerHour = 30, maxShadowSignalsPerHour = 120, maxRetrainingSamplesPerBucket = 200, minSamplesBeforePromotion = 50, minSamplesBeforeRetirement = 200, maxCapitalAtRiskForRetrainingPct = 0.10, liveExplorationAllowed = false)
        it["UNKNOWN"]     = LaneBudget("UNKNOWN",     maxPaperMicroTradesPerHour = 10, maxShadowSignalsPerHour = 200, maxRetrainingSamplesPerBucket = 300, minSamplesBeforePromotion = 80, minSamplesBeforeRetirement = 300, maxCapitalAtRiskForRetrainingPct = 0.05, liveExplorationAllowed = false)
        it["MANIPULATED"] = LaneBudget("MANIPULATED", maxPaperMicroTradesPerHour = 20, maxShadowSignalsPerHour = 100, maxRetrainingSamplesPerBucket = 200, minSamplesBeforePromotion = 50, minSamplesBeforeRetirement = 200, maxCapitalAtRiskForRetrainingPct = 0.10, liveExplorationAllowed = false)
        it["MOONSHOT"]    = LaneBudget("MOONSHOT",    maxPaperMicroTradesPerHour = 60, maxShadowSignalsPerHour = 80,  maxRetrainingSamplesPerBucket = 200, minSamplesBeforePromotion = 50, minSamplesBeforeRetirement = 250, maxCapitalAtRiskForRetrainingPct = 0.20, liveExplorationAllowed = true)
        it["QUALITY"]     = LaneBudget("QUALITY",     maxPaperMicroTradesPerHour = 40, maxShadowSignalsPerHour = 60,  maxRetrainingSamplesPerBucket = 150, minSamplesBeforePromotion = 40, minSamplesBeforeRetirement = 250, maxCapitalAtRiskForRetrainingPct = 0.30, liveExplorationAllowed = true)
        it["BLUECHIP"]    = LaneBudget("BLUECHIP",    maxPaperMicroTradesPerHour = 40, maxShadowSignalsPerHour = 60,  maxRetrainingSamplesPerBucket = 150, minSamplesBeforePromotion = 40, minSamplesBeforeRetirement = 250, maxCapitalAtRiskForRetrainingPct = 0.30, liveExplorationAllowed = true)
        it["TREASURY"]    = LaneBudget("TREASURY",    maxPaperMicroTradesPerHour = 30, maxShadowSignalsPerHour = 40,  maxRetrainingSamplesPerBucket = 100, minSamplesBeforePromotion = 30, minSamplesBeforeRetirement = 200, maxCapitalAtRiskForRetrainingPct = 0.20, liveExplorationAllowed = true)
    }

    private val DEFAULT_LANE_BUDGET = LaneBudget(
        lane = "DEFAULT",
        maxPaperMicroTradesPerHour = 25,
        maxShadowSignalsPerHour = 100,
        maxRetrainingSamplesPerBucket = 150,
        minSamplesBeforePromotion = 50,
        minSamplesBeforeRetirement = 200,
        maxCapitalAtRiskForRetrainingPct = 0.15,
        liveExplorationAllowed = false,
    )

    private data class HourlyCounter(val startMs: AtomicLong, val count: AtomicInteger)
    private val microHourly  = ConcurrentHashMap<String, HourlyCounter>()
    private val shadowHourly = ConcurrentHashMap<String, HourlyCounter>()
    private const val HOUR_MS = 3_600_000L

    fun budgetFor(lane: String): LaneBudget = laneBudgets[lane.uppercase()] ?: DEFAULT_LANE_BUDGET

    private fun bumpHourly(map: ConcurrentHashMap<String, HourlyCounter>, lane: String): Int {
        val now = System.currentTimeMillis()
        val k = lane.uppercase().take(24)
        val cell = map.computeIfAbsent(k) { HourlyCounter(AtomicLong(now), AtomicInteger(0)) }
        val startedMs = cell.startMs.get()
        if (now - startedMs > HOUR_MS) {
            cell.startMs.set(now)
            cell.count.set(0)
        }
        return cell.count.incrementAndGet()
    }

    private fun peekHourly(map: ConcurrentHashMap<String, HourlyCounter>, lane: String): Int {
        val now = System.currentTimeMillis()
        val k = lane.uppercase().take(24)
        val cell = map[k] ?: return 0
        return if (now - cell.startMs.get() > HOUR_MS) 0 else cell.count.get()
    }

    /** Returns true if a paper-micro trade is allowed under the hourly budget. */
    fun allowPaperMicroTrade(lane: String): Boolean {
        val budget = budgetFor(lane)
        val taken = bumpHourly(microHourly, lane)
        val ok = taken <= budget.maxPaperMicroTradesPerHour
        if (!ok) {
            try { PipelineHealthCollector.labelInc("EXPLORATION_BUDGET_EXCEEDED_PAPER_MICRO|${lane.uppercase().take(24)}") } catch (_: Throwable) {}
        }
        return ok
    }

    fun allowShadowSignal(lane: String): Boolean {
        val budget = budgetFor(lane)
        val taken = bumpHourly(shadowHourly, lane)
        val ok = taken <= budget.maxShadowSignalsPerHour
        if (!ok) {
            try { PipelineHealthCollector.labelInc("EXPLORATION_BUDGET_EXCEEDED_SHADOW|${lane.uppercase().take(24)}") } catch (_: Throwable) {}
        }
        return ok
    }

    /** Current hourly counters for snapshot dump. */
    fun snapshot(): Map<String, Pair<Int, Int>> {
        val laneNames = (microHourly.keys + shadowHourly.keys).toSet()
        return laneNames.associateWith { lane ->
            Pair(peekHourly(microHourly, lane), peekHourly(shadowHourly, lane))
        }
    }
}

/**
 * V5.9.1323 — Retraining Decay (operator §6).
 *
 * Adjusts LanePolicy.executionWeight downward based on recent outcomes
 * and upward when improvement evidence arrives. The hypothesis engine
 * (Build D) is the primary state-mutator; this object is the decay
 * accumulator that prevents stale bad-lane weights from dominating.
 */
object RetrainingDecay {

    private const val DECAY_PER_LOSS = 0.97   // each recent loss multiplies weight by 0.97
    private const val DECAY_FLOOR = 0.15      // never below 15%
    private const val RECOVERY_PER_WIN = 1.05 // each recent win nudges weight up by 5%
    private const val RECOVERY_CEILING = 1.0  // capped at the policy default ceiling

    /**
     * Called from the close-side learning fanout for every settled meme trade.
     * Adjusts the lane's executionWeight smoothly — never zeros out, so no lane
     * is permanently dead unless explicitly set INVALID by the operator/runtime.
     */
    fun noteOutcome(lane: String, scoreBand: String, isWin: Boolean, isLoss: Boolean) {
        if (lane.isBlank()) return
        val currentLane = LanePolicy.executionWeightForLane(lane)
        val currentBucket = LanePolicy.executionWeightForBucket(lane, scoreBand)
        when {
            isWin -> {
                val next = (currentLane * RECOVERY_PER_WIN).coerceAtMost(RECOVERY_CEILING)
                setExecutionWeightLane(lane, next)
                val nextB = (currentBucket * RECOVERY_PER_WIN).coerceAtMost(RECOVERY_CEILING)
                setExecutionWeightBucket(lane, scoreBand, nextB)
                LanePolicy.noteImprovement(lane, scoreBand)
                try { PipelineHealthCollector.labelInc("RETRAINING_DECAY_WIN|${lane.uppercase().take(24)}") } catch (_: Throwable) {}
            }
            isLoss -> {
                val next = (currentLane * DECAY_PER_LOSS).coerceAtLeast(DECAY_FLOOR)
                setExecutionWeightLane(lane, next)
                val nextB = (currentBucket * DECAY_PER_LOSS).coerceAtLeast(DECAY_FLOOR)
                setExecutionWeightBucket(lane, scoreBand, nextB)
                try { PipelineHealthCollector.labelInc("RETRAINING_DECAY_LOSS|${lane.uppercase().take(24)}") } catch (_: Throwable) {}
            }
            else -> {
                // scratch — neutral; no decay nudge.
            }
        }
    }

    /**
     * Direct setter — writes the executionWeight cell without changing
     * the policy state itself. The hypothesis engine uses this to apply
     * Thompson-sampling-style adjustments.
     *
     * Uses LearningPersistence so the value survives reboots.
     */
    private fun setExecutionWeightLane(lane: String, weight: Double) {
        val clamped = weight.coerceIn(0.0, 1.0)
        try {
            LearningPersistence.save(
                "lane_policy_execweight_${lane.uppercase().take(32)}",
                """{"ew":${(clamped * 1000).toLong()},"t":${System.currentTimeMillis()}}"""
            )
        } catch (_: Throwable) {}
    }

    private fun setExecutionWeightBucket(lane: String, scoreBand: String, weight: Double) {
        val clamped = weight.coerceIn(0.0, 1.0)
        try {
            LearningPersistence.save(
                "bucket_policy_execweight_${lane.uppercase().take(24)}|${scoreBand.uppercase().take(12)}",
                """{"ew":${(clamped * 1000).toLong()},"t":${System.currentTimeMillis()}}"""
            )
        } catch (_: Throwable) {}
    }
}

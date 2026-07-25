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

    // V5.0.6368 — MAGNITUDE-AWARE BUDGET TIGHTENING (source-of-creation).
    // Before: a lane that took a -95% catastrophic loss got the same
    // budget-consumption as one that took a -0.5% scratch. Now every trade
    // close feeds `onLaneOutcome(lane, pnlPct)` which stores a decaying
    // magnitude multiplier per lane (0.25..1.0). `allowPaperMicroTrade`
    // reads the multiplier so a lane bleeding hard sees its microTrade
    // ceiling collapse to a quarter of its default without touching
    // LanePolicy state at all. Recovery is automatic: after HOUR_MS the
    // multiplier resets (see peekLaneMagnitudeMult).
    private data class LaneMagCell(val startMs: AtomicLong, val mult: java.util.concurrent.atomic.AtomicReference<Double>)
    private val laneMagnitude = ConcurrentHashMap<String, LaneMagCell>()
    private const val MAG_MULT_MIN = 0.25
    private const val MAG_MULT_MAX = 1.0

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
        // V5.0.6368 — apply magnitude-aware multiplier at check time so a
        // bleeding lane's ceiling collapses to a quarter of its default
        // without any call-site change or LanePolicy mutation.
        val mult = peekLaneMagnitudeMult(lane)
        val ceiling = (budget.maxPaperMicroTradesPerHour * mult).toInt().coerceAtLeast(1)
        val ok = taken <= ceiling
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

    // V5.0.6368 — SOURCE-OF-CREATION magnitude gate. Fed by V3JournalRecorder
    // close-side fanout with the real pnlPct. Rules:
    //   |pnl| >= 50%  → hard tighten: mult *= 0.35 (catastrophic → collapse ceiling)
    //   |pnl| >= 20%  → firm tighten: mult *= 0.60
    //   |pnl| >=  5%  → soft tighten: mult *= 0.85
    //     pnl >   5%  → recovery:     mult += 0.10 (up to MAG_MULT_MAX)
    // Multipliers persist for HOUR_MS then reset to MAG_MULT_MAX. This is
    // the downstream sibling of TacticSwitcher/LanePolicy magnitude triggers
    // added in V5.0.6367 — same event now shapes exploration budget too.
    fun onLaneOutcome(lane: String, pnlPct: Double) {
        if (lane.isBlank() || pnlPct.isNaN() || pnlPct.isInfinite()) return
        val now = System.currentTimeMillis()
        val k = lane.uppercase().take(24)
        val cell = laneMagnitude.computeIfAbsent(k) { LaneMagCell(AtomicLong(now), java.util.concurrent.atomic.AtomicReference(MAG_MULT_MAX)) }
        val startedMs = cell.startMs.get()
        val current = if (now - startedMs > HOUR_MS) {
            cell.startMs.set(now); cell.mult.set(MAG_MULT_MAX); MAG_MULT_MAX
        } else cell.mult.get()
        val mag = Math.abs(pnlPct)
        val next = when {
            pnlPct < 0.0 && mag >= 50.0 -> (current * 0.35).coerceAtLeast(MAG_MULT_MIN)
            pnlPct < 0.0 && mag >= 20.0 -> (current * 0.60).coerceAtLeast(MAG_MULT_MIN)
            pnlPct < 0.0 && mag >=  5.0 -> (current * 0.85).coerceAtLeast(MAG_MULT_MIN)
            pnlPct >  5.0                -> (current + 0.10).coerceAtMost(MAG_MULT_MAX)
            else -> current // scratch — leave unchanged
        }
        cell.mult.set(next)
        try {
            val band = when {
                pnlPct < 0.0 && mag >= 50.0 -> "CATASTROPHIC"
                pnlPct < 0.0 && mag >= 20.0 -> "BIG_LOSS"
                pnlPct < 0.0 && mag >=  5.0 -> "SMALL_LOSS"
                pnlPct >  5.0                -> "RECOVERY"
                else -> "SCRATCH"
            }
            PipelineHealthCollector.labelInc("EXPLORATION_BUDGET_MAGNITUDE_${band}|$k")
        } catch (_: Throwable) {}
    }

    private fun peekLaneMagnitudeMult(lane: String): Double {
        val k = lane.uppercase().take(24)
        val cell = laneMagnitude[k] ?: return MAG_MULT_MAX
        val now = System.currentTimeMillis()
        return if (now - cell.startMs.get() > HOUR_MS) MAG_MULT_MAX else cell.mult.get()
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
     * V5.0.6368 — Legacy 4-arg entry (kept for Golden Tape / older callers).
     * Delegates to the magnitude-aware overload with pnlPct=0.0, which reproduces
     * the historical binary decay behaviour.
     */
    fun noteOutcome(lane: String, scoreBand: String, isWin: Boolean, isLoss: Boolean) {
        noteOutcome(lane, scoreBand, isWin, isLoss, 0.0)
    }

    /**
     * V5.0.6368 — MAGNITUDE-AWARE decay. A -95% catastrophic close should
     * hammer the execution-weight harder than a -1% scratch. Rules:
     *   |pnl| >= 50%  → 4× decay steps  (catastrophic)
     *   |pnl| >= 20%  → 3× decay steps
     *   |pnl| >=  5%  → 2× decay steps
     *      else       → 1× decay step   (small loss / no data)
     * Wins get 2× recovery when pnlPct >= 5% (small nudge if scratch-win).
     * Called from the close-side learning fanout for every settled meme trade.
     * Adjusts the lane's executionWeight smoothly — never zeros out, so no lane
     * is permanently dead unless explicitly set INVALID by the operator/runtime.
     */
    fun noteOutcome(lane: String, scoreBand: String, isWin: Boolean, isLoss: Boolean, pnlPct: Double) {
        if (lane.isBlank()) return
        val currentLane = LanePolicy.executionWeightForLane(lane)
        val currentBucket = LanePolicy.executionWeightForBucket(lane, scoreBand)
        val safePnl = if (pnlPct.isNaN() || pnlPct.isInfinite()) 0.0 else pnlPct
        val mag = Math.abs(safePnl)
        // Magnitude steps: how many times to compound the decay/recovery.
        val lossSteps = when {
            mag >= 50.0 -> 4
            mag >= 20.0 -> 3
            mag >=  5.0 -> 2
            else -> 1
        }
        val winSteps = if (mag >= 5.0) 2 else 1
        when {
            isWin -> {
                var next = currentLane
                var nextB = currentBucket
                repeat(winSteps) {
                    next = (next * RECOVERY_PER_WIN).coerceAtMost(RECOVERY_CEILING)
                    nextB = (nextB * RECOVERY_PER_WIN).coerceAtMost(RECOVERY_CEILING)
                }
                setExecutionWeightLane(lane, next)
                setExecutionWeightBucket(lane, scoreBand, nextB)
                LanePolicy.noteImprovement(lane, scoreBand)
                try { PipelineHealthCollector.labelInc("RETRAINING_DECAY_WIN|${lane.uppercase().take(24)}") } catch (_: Throwable) {}
                if (winSteps > 1) {
                    try { PipelineHealthCollector.labelInc("RETRAINING_DECAY_WIN_MAG_${winSteps}X|${lane.uppercase().take(24)}") } catch (_: Throwable) {}
                }
            }
            isLoss -> {
                var next = currentLane
                var nextB = currentBucket
                repeat(lossSteps) {
                    next = (next * DECAY_PER_LOSS).coerceAtLeast(DECAY_FLOOR)
                    nextB = (nextB * DECAY_PER_LOSS).coerceAtLeast(DECAY_FLOOR)
                }
                setExecutionWeightLane(lane, next)
                setExecutionWeightBucket(lane, scoreBand, nextB)
                try { PipelineHealthCollector.labelInc("RETRAINING_DECAY_LOSS|${lane.uppercase().take(24)}") } catch (_: Throwable) {}
                if (lossSteps > 1) {
                    try { PipelineHealthCollector.labelInc("RETRAINING_DECAY_LOSS_MAG_${lossSteps}X|${lane.uppercase().take(24)}") } catch (_: Throwable) {}
                }
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
    // V5.9.1460 — write the LIVE LanePolicy cell (which IS persisted inside
    // LanePolicy.persist) instead of a dead key LanePolicy never read back. This
    // is what makes the per-loss decay actually reduce the lane's execution weight
    // that FdgRouteVerdict consumes at entry.
    private fun setExecutionWeightLane(lane: String, weight: Double) {
        try { LanePolicy.setExecutionWeightLaneCell(lane, weight.coerceIn(0.0, 1.0)) } catch (_: Throwable) {}
    }

    private fun setExecutionWeightBucket(lane: String, scoreBand: String, weight: Double) {
        try { LanePolicy.setExecutionWeightBucketCell(lane, scoreBand, weight.coerceIn(0.0, 1.0)) } catch (_: Throwable) {}
    }
}

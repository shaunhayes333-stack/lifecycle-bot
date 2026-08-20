package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6472 §Adaptive Lane Damping — replaces LANE_AUTO_PAUSED_SHITCOIN
 * (and any other lane-level hard-disable) with bounded adaptive
 * damping. Losing tactics must never equal permanent lane death.
 *
 * OPERATOR MANDATE (verbatim, tail of the 6471 dump):
 *
 *   "Replace LANE_AUTO_PAUSED_SHITCOIN hard FDG block with bounded
 *    adaptive damping:
 *      tighter size
 *      higher score requirement
 *      reduced admission cadence
 *      tactic rotation
 *      tiny information probes after cooldown
 *
 *    Hard block remains only for safety/rug/integrity conditions.
 *    Do not make a losing tactic equivalent to permanent lane death."
 *
 * DESIGN
 * ──────
 * `Damping` returned per lane:
 *   • sizeMultiplier      — 1.0 = full, < 1.0 = tighter (never 0).
 *   • scoreFloorBoost     — additive floor bump for admission.
 *   • cadenceThrottleMs   — minimum interval between admissions.
 *   • allowProbe          — true when a small information probe is
 *                            allowed at the current cadence.
 *
 * Damping level is DERIVED from recent lane performance:
 *   evPct ∈ [ -100 .. +∞ )   (recent expected value in %)
 *
 *   evPct >= -5   → Damping(1.0, 0,  0)      (no damping)
 *   evPct >= -20  → Damping(0.75, +5, 30_000)
 *   evPct >= -35  → Damping(0.50, +10, 60_000)
 *   evPct >= -60  → Damping(0.25, +15, 180_000)   ← SHITCOIN at -57%
 *   evPct <  -60  → Damping(0.10, +20, 300_000)   ← still not disabled
 *
 * Even the deepest damping keeps a 10% size + 5-minute-cadence probe
 * path open, so the lane can escape.
 *
 * Hard block is a SEPARATE authority (safety/rug) — this module never
 * emits it.
 */
object LaneAdaptiveDamping6472 {

    data class Damping(
        val sizeMultiplier: Double,
        val scoreFloorBoost: Int,
        val cadenceThrottleMs: Long,
        val allowProbe: Boolean,
        val level: Int,
    )

    private val laneEvPct = ConcurrentHashMap<String, Double>()
    private val lastAdmissionMs = ConcurrentHashMap<String, Long>()
    private val dampReads = AtomicLong(0L)
    private val probesAllowed = AtomicLong(0L)
    private val probesThrottled = AtomicLong(0L)

    /** Called by GrowthRewardShaper/lane analytics with recent EV %. */
    fun recordLaneEvPct(lane: String, evPct: Double) {
        if (lane.isBlank() || !evPct.isFinite()) return
        laneEvPct[lane] = evPct
    }

    /** Query damping for a lane. Includes cadence-aware `allowProbe`. */
    fun damping(lane: String): Damping {
        dampReads.incrementAndGet()
        val evPct = laneEvPct[lane] ?: 0.0
        val (sizeMult, scoreBoost, cadenceMs, level) = when {
            evPct >= -5.0 -> Quad(1.00, 0, 0L, 0)
            evPct >= -20.0 -> Quad(0.75, 5, 30_000L, 1)
            evPct >= -35.0 -> Quad(0.50, 10, 60_000L, 2)
            evPct >= -60.0 -> Quad(0.25, 15, 180_000L, 3)
            else -> Quad(0.10, 20, 300_000L, 4)
        }
        val now = System.currentTimeMillis()
        val lastMs = lastAdmissionMs[lane] ?: 0L
        val allowProbe = cadenceMs == 0L || (now - lastMs) >= cadenceMs
        return Damping(
            sizeMultiplier = sizeMult,
            scoreFloorBoost = scoreBoost,
            cadenceThrottleMs = cadenceMs,
            allowProbe = allowProbe,
            level = level,
        )
    }

    /** Called when the lane admits an entry so cadence-throttle can reset. */
    fun onLaneAdmission(lane: String) {
        if (lane.isBlank()) return
        val prevMs = lastAdmissionMs.put(lane, System.currentTimeMillis())
        if (prevMs != null) {
            probesAllowed.incrementAndGet()
            try { PipelineHealthCollector.labelInc("LANE_ADAPTIVE_DAMP_ADMIT_6472_$lane".take(60)) } catch (_: Throwable) {}
        }
    }

    /** Called when a candidate is deferred by damping cadence. */
    fun onLaneThrottled(lane: String, reason: String) {
        probesThrottled.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "LANE_ADAPTIVE_DAMP_THROTTLED_6472",
                "lane=$lane reason=$reason ev=${laneEvPct[lane]}",
            )
            PipelineHealthCollector.labelInc("LANE_ADAPTIVE_DAMP_THROTTLED_6472_$lane".take(60))
        } catch (_: Throwable) {}
    }

    fun statusLine(): String {
        val laneCount = laneEvPct.size
        val worst = laneEvPct.entries.minByOrNull { it.value }
        return "lanes=$laneCount worst=${worst?.let { "${it.key}(${it.value})" } ?: "-"} " +
            "reads=${dampReads.get()} admits=${probesAllowed.get()} " +
            "throttled=${probesThrottled.get()}"
    }

    internal fun resetForTest() {
        laneEvPct.clear(); lastAdmissionMs.clear()
        dampReads.set(0L); probesAllowed.set(0L); probesThrottled.set(0L)
    }

    private data class Quad(val a: Double, val b: Int, val c: Long, val d: Int)
}

package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6829 §SELECTION_QUALITY — operator diagnosis item #1 for build 5.0.6828:
 *   "68 measured trades, 11W/57L, 16.2% WR, PF 1.22, longest losing streak 23.
 *    QUALITY 0% WR (n=14), EXPRESS 6.9% WR (n=29), PROJECT_SNIPER 40% (n=20).
 *    The bot is no longer 'not trading'; it is trading too much low-quality
 *    inventory. The current failure is authority coherence and selection
 *    quality, not gate relaxation."
 *
 * DESIGN — adaptive per-lane score-floor delta driven by rolling WR.
 *   • `recordTerminal(lane, wonBool)` updates the rolling win record
 *     for a lane (bounded window per lane).
 *   • `scoreFloorDelta(lane)` returns a POSITIVE additive delta the
 *     caller applies to their normal score floor:
 *       - Rolling WR >= 40% → 0.0 (no adjustment; the lane is healthy)
 *       - Rolling WR in [25%, 40%) → +5.0
 *       - Rolling WR in [15%, 25%) → +10.0
 *       - Rolling WR < 15%          → +15.0
 *   • Also exposes `laneSelectivityMultiplier(lane)` in [0.35, 1.00]
 *     for size-based dampers that want to shrink weak lanes rather
 *     than block them outright.
 *   • Fail-open: any exception returns 0.0 / 1.0.
 */
object SelectionQualityAuthority6829 {

    private const val WINDOW_PER_LANE = 40
    private const val MIN_SAMPLES = 6

    private data class Ring(val wins: ArrayDeque<Boolean> = ArrayDeque()) {
        @Synchronized fun add(won: Boolean) {
            wins.addLast(won)
            while (wins.size > WINDOW_PER_LANE) wins.removeFirst()
        }
        @Synchronized fun wr(): Double {
            if (wins.size < MIN_SAMPLES) return -1.0
            val w = wins.count { it }
            return w.toDouble() * 100.0 / wins.size.toDouble()
        }
        @Synchronized fun size(): Int = wins.size
    }

    private val rings = ConcurrentHashMap<String, Ring>()
    private val recorded = AtomicLong(0L)
    private val queries = AtomicLong(0L)

    fun recordTerminal(lane: String, won: Boolean) {
        if (lane.isBlank()) return
        try {
            val key = lane.trim().uppercase()
            rings.computeIfAbsent(key) { Ring() }.add(won)
            recorded.incrementAndGet()
        } catch (_: Throwable) {}
    }

    /** Returns rolling WR% for a lane, or -1.0 if under-sampled. */
    fun rollingWr(lane: String): Double {
        if (lane.isBlank()) return -1.0
        return rings[lane.trim().uppercase()]?.wr() ?: -1.0
    }

    fun scoreFloorDelta(lane: String): Double {
        queries.incrementAndGet()
        val wr = rollingWr(lane)
        if (wr < 0.0) return 0.0
        val delta = when {
            wr >= 40.0 -> 0.0
            wr >= 25.0 -> 5.0
            wr >= 15.0 -> 10.0
            else -> 15.0
        }
        if (delta > 0.0) {
            try {
                PipelineHealthCollector.labelInc("SELECTION_QUALITY_FLOOR_APPLIED_6829")
                PipelineHealthCollector.labelInc(
                    "SELECTION_QUALITY_FLOOR_APPLIED_6829_${lane.uppercase().take(24)}"
                )
            } catch (_: Throwable) {}
        }
        return delta
    }

    fun laneSelectivityMultiplier(lane: String): Double {
        val wr = rollingWr(lane)
        if (wr < 0.0) return 1.0
        return when {
            wr >= 40.0 -> 1.0
            wr >= 25.0 -> 0.75
            wr >= 15.0 -> 0.50
            else -> 0.35
        }
    }

    fun statusLine(): String {
        val lines = rings.entries.joinToString(",") { (k, v) ->
            val wr = v.wr()
            "$k=" + (if (wr < 0.0) "u/${v.size()}" else "%.1f".format(wr) + "/${v.size()}")
        }
        return "recorded=${recorded.get()} queries=${queries.get()} lanes=[$lines]"
    }

    internal fun clearForTest() {
        rings.clear(); recorded.set(0L); queries.set(0L)
    }
}

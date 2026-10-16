package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * V5.0.8144 — cheap, bounded reads for the UI and the main-thread frame watcher. Pure: no Android.
 *
 * Device evidence (P1-D): main-thread samples landing in ConcurrentLinkedDeque.iterator, ANR
 * hints, and heap pressure while telemetry is active. Three read paths caused it:
 *
 *  1. PipelineHealthCollector's Choreographer callback — which runs ON the main thread — asked
 *     "did the watchdog keep ticking through this long frame?" by walking the stackRing deque
 *     (`stackRing.count { ... }`, twice). Every long frame paid an iterator walk over a deque
 *     another thread was mutating, on the very thread whose stall it was measuring.
 *     [RuntimeTickRing8144] answers the same question from a primitive array: no iterator, no
 *     node traversal, no allocation.
 *  2. The dashboard's Pipeline tile and the Pipeline Health screen built a full
 *     PipelineHealthCollector.snapshot() — every counter map copied, both rings copied, the
 *     canonical count authority captured — to print two or three numbers. [RuntimePipelineBadges8144]
 *     is the immutable handful of numbers those screens actually draw, read by key.
 *  3. Repetitive main-thread forensic lines (one per long frame). [RuntimeRateGate8144] lets a
 *     line through at most once per interval; the counters beside it stay exact.
 */

/**
 * Fixed-size ring of tick timestamps. Written by ONE thread (the ANR watchdog), read by any.
 * A read may see a slot mid-overwrite with a newer timestamp; that only ever adds a tick that
 * really happened, never invents one.
 */
class RuntimeTickRing8144(capacity: Int = 64) {
    private val slots = AtomicLongArray(capacity.coerceAtLeast(1))
    private val written = AtomicLong(0L)

    val capacity: Int get() = slots.length()

    fun record(tsMs: Long) {
        val n = written.getAndIncrement()
        slots.set((n % slots.length()).toInt(), tsMs)
    }

    /** Ticks with timestamp in [fromMs, toMs]. O(capacity), allocation-free. */
    fun countInWindow(fromMs: Long, toMs: Long): Int {
        val filled = written.get().coerceAtMost(slots.length().toLong()).toInt()
        var c = 0
        for (i in 0 until filled) {
            val ts = slots.get(i)
            if (ts in fromMs..toMs) c++
        }
        return c
    }

    fun size(): Int = written.get().coerceAtMost(slots.length().toLong()).toInt()
}

/** Lets an event through at most once per [intervalMs]. Thread-safe, allocation-free. */
class RuntimeRateGate8144(private val intervalMs: Long) {
    private val lastMs = AtomicLong(Long.MIN_VALUE)
    private val suppressed = AtomicLong(0L)

    /** True when the caller should emit now; false (and counted) when inside the interval. */
    fun admit(nowMs: Long): Boolean {
        while (true) {
            val last = lastMs.get()
            if (last != Long.MIN_VALUE && nowMs - last < intervalMs) {
                suppressed.incrementAndGet()
                return false
            }
            if (lastMs.compareAndSet(last, nowMs)) return true
        }
    }

    /** Events suppressed since the last admitted one; resets on read. */
    fun takeSuppressed(): Long = suppressed.getAndSet(0L)
}

/** The numbers the Pipeline tile and Pipeline Health header draw — nothing else. */
data class RuntimePipelineBadges8144(
    val loop: Long,
    val exec: Long,
    val journal: Long,
    val anrHints: Int,
    val maxFrameGapMs: Long,
    val funnel: LongArray,
) {
    override fun equals(other: Any?): Boolean =
        other is RuntimePipelineBadges8144 && loop == other.loop && exec == other.exec &&
            journal == other.journal && anrHints == other.anrHints &&
            maxFrameGapMs == other.maxFrameGapMs && funnel.contentEquals(other.funnel)

    override fun hashCode(): Int =
        ((((loop.hashCode() * 31 + exec.hashCode()) * 31 + journal.hashCode()) * 31 + anrHints) * 31 +
            maxFrameGapMs.hashCode()) * 31 + funnel.contentHashCode()

    companion object {
        /** Funnel stages, in draw order (PipelineHealthActivity's FunnelView7021). */
        val FUNNEL_PHASES: List<String> = listOf("INTAKE", "SAFETY", "V3", "LANE_EVAL", "FDG", "EXEC")

        /** Build from point lookups; [phase] and [label] read one counter each. */
        fun read8144(
            phase: (String) -> Long,
            label: (String) -> Long,
            anrHints: Int,
            maxFrameGapMs: Long,
        ): RuntimePipelineBadges8144 {
            val scan = phase("SCAN_CB")
            return RuntimePipelineBadges8144(
                loop = if (scan > 0L) scan else label("BOT_LOOP_TICK"),
                exec = phase("EXEC"),
                journal = label("TRADEJRNL_REC"),
                anrHints = anrHints,
                maxFrameGapMs = maxFrameGapMs,
                funnel = LongArray(FUNNEL_PHASES.size) { phase(FUNNEL_PHASES[it]) },
            )
        }

        /**
         * The tightest funnel step: index of the stage with the lowest pass-through from the stage
         * above, and that percentage. (-1, 101.0) when no stage has a non-zero predecessor.
         */
        fun tightest8144(counts: LongArray): Pair<Int, Double> {
            var worstI = -1
            var worstPct = 101.0
            for (i in 1 until counts.size) {
                val above = counts[i - 1]
                if (above <= 0L) continue
                val pct = counts[i] * 100.0 / above
                if (pct < worstPct) { worstPct = pct; worstI = i }
            }
            return worstI to worstPct
        }
    }
}

/*
 * V5.9.793 — CycleTimingTracker (operator audit Item 6 — observability).
 *
 * Operator-stated target: "Cycle avg < 12s and max < 30s."
 *
 * This tracker records the wall-clock duration of every full scan
 * cycle in SolanaMarketScanner. It exposes a rolling window
 * (last 64 cycles) so the Universe Health screen can render
 * avg / p95 / max / last and the operator can see at a glance
 * whether the scanner pipeline is meeting the target.
 *
 * Read-only / lock-free on the read side — Universe Health polls
 * snapshot() every 3s and never blocks the scanner coroutine.
 */
package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

object CycleTimingTracker {
    private const val ROLLING_WINDOW = 64

    private val samples = ConcurrentLinkedDeque<Long>()
    private val totalCycles = AtomicLong(0)
    private val overTargetCycles = AtomicLong(0)     // ms > 12_000
    private val overHardLimitCycles = AtomicLong(0)  // ms > 30_000
    private var lastCycleMs: Long = 0L

    private const val TARGET_MS = 12_000L
    private const val HARD_LIMIT_MS = 30_000L

    fun recordCycle(durationMs: Long) {
        if (durationMs < 0) return
        samples.addFirst(durationMs)
        while (samples.size > ROLLING_WINDOW) samples.pollLast()
        totalCycles.incrementAndGet()
        if (durationMs > TARGET_MS) overTargetCycles.incrementAndGet()
        if (durationMs > HARD_LIMIT_MS) {
            overHardLimitCycles.incrementAndGet()
            // V5.0.6251 — surface hard-limit cycles into the operational
            // report. Snapshot 6249 had avg=13.2s max=215.6s but the only
            // clue was an aggregate counter buried in the timing panel.
            // Emit a lifecycle event so ReportingHub / ForensicLogger dump
            // shows WHICH cycles hemorrhaged time, letting the operator
            // triage watchlist bloat vs API hangs vs pipeline stalls.
            try {
                ForensicLogger.lifecycle(
                    "CYCLE_OVER_HARD_LIMIT_6251",
                    "durationMs=$durationMs limitMs=$HARD_LIMIT_MS overCount=${overHardLimitCycles.get()} totalCycles=${totalCycles.get()}",
                )
                PipelineHealthCollector.labelInc("CYCLE_OVER_HARD_LIMIT_6251")
            } catch (_: Throwable) {}
        }
        lastCycleMs = durationMs
    }

    data class Snapshot(
        val lastMs: Long,
        val avgMs: Long,
        val p95Ms: Long,
        val maxMs: Long,
        val totalCycles: Long,
        val overTargetCycles: Long,
        val overHardLimitCycles: Long,
        val targetMs: Long,
        val hardLimitMs: Long,
        val windowSize: Int,
    )

    fun snapshot(): Snapshot {
        val current = samples.toList()
        val avg = if (current.isNotEmpty()) current.sum() / current.size else 0L
        val sorted = current.sorted()
        val p95 = if (sorted.isNotEmpty()) sorted[((sorted.size - 1) * 95) / 100] else 0L
        val max = sorted.maxOrNull() ?: 0L
        return Snapshot(
            lastMs = lastCycleMs,
            avgMs = avg,
            p95Ms = p95,
            maxMs = max,
            totalCycles = totalCycles.get(),
            overTargetCycles = overTargetCycles.get(),
            overHardLimitCycles = overHardLimitCycles.get(),
            targetMs = TARGET_MS,
            hardLimitMs = HARD_LIMIT_MS,
            windowSize = current.size,
        )
    }
}

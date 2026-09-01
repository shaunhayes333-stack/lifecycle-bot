package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6626 §RUNTIME_LOOP_UNCHOKE §2 — ADAPTIVE PAPER TICKET TTL.
 *
 * OPERATOR EMERGENCY (V5.0.6308 dump):
 *   cycles avgMs=31_295 maxMs=373_256 recent=242s,238s,249s,219s,255s...
 *   FDG=8_708 → EXEC=374 (4.3% pass-through)
 *   EXEC_GATE/EXPIRED_TICKET_ECONOMIC_REJECT_6614 = 235
 *
 * ROOT CAUSE
 * ──────────
 *   PAPER_EXECUTION_TICKET_TTL_MS is a hard 180_000L constant
 *   (ExecutableOpenGate.kt:233). Runtime cycles are 30-373s under
 *   load, so a buy intent that would execute NEXT cycle is instead
 *   economic-reject-expired before the executor even sees it.
 *
 * FIX
 * ───
 *   Bind TTL to the rolling average cycle time so the ticket funnel
 *   survives the multi-cycle window while the log-storm remediation
 *   is running. TTL = min(TTL_CEILING_MS, max(TTL_FLOOR_MS,
 *   3 × rollingAvgCycleMs)). The floor is the operator's original
 *   180s so behaviour is unchanged when cycles are healthy.
 *
 * BLAST RADIUS
 * ────────────
 *   The extended TTL only KEEPS a ticket alive longer during
 *   revalidation — every re-seal path still re-checks decision
 *   authority, position occupancy, sealed size and refreshed mark
 *   (ExecutableOpenGate.revalidateAndResealExpired6613). Staleness
 *   is caught by those gates, not by the TTL alone. The TTL only
 *   picks the moment when the ticket is FORCED to prove itself
 *   again. Extending it just gives the FDG→EXEC path more real
 *   attempts per intent, not weaker checks.
 */
object AdaptiveTicketTtl6626 {

    /** Operator-mandated floor. Matches the pre-6626 hard constant. */
    const val TTL_FLOOR_MS_6626 = 180_000L

    /** 10-minute ceiling so a runaway cycle-time regression cannot
     *  effectively disable expiry (would defeat the mandate). */
    const val TTL_CEILING_MS_6626 = 600_000L

    /** V5.0.6626 counter — how many times a caller received a TTL
     *  above the floor because rolling cycle time demanded it. */
    private val extendedAllocations = AtomicLong(0L)
    private val flooredAllocations = AtomicLong(0L)
    private val lastGranted = AtomicLong(TTL_FLOOR_MS_6626)

    /**
     * Returns the adaptive TTL to apply to a new PAPER execution
     * ticket. Reads rolling cycle telemetry from
     * PipelineHealthCollector. Fails safe to the floor on any error.
     */
    fun paperTicketTtlMs6626(): Long {
        val avgMs = try {
            PipelineHealthCollector.rollingAvgCycleMs6626()
        } catch (_: Throwable) { 0L }
        val computed = if (avgMs > 0L) 3L * avgMs else TTL_FLOOR_MS_6626
        val bounded = computed.coerceIn(TTL_FLOOR_MS_6626, TTL_CEILING_MS_6626)
        lastGranted.set(bounded)
        if (bounded > TTL_FLOOR_MS_6626) {
            extendedAllocations.incrementAndGet()
            try { HotLabelCoalescer6626.inc6626("PAPER_TICKET_TTL_EXTENDED_6626") } catch (_: Throwable) {}
        } else {
            flooredAllocations.incrementAndGet()
            try { HotLabelCoalescer6626.inc6626("PAPER_TICKET_TTL_FLOORED_6626") } catch (_: Throwable) {}
        }
        return bounded
    }

    fun statusLine6626(): String {
        val avg = try { PipelineHealthCollector.rollingAvgCycleMs6626() } catch (_: Throwable) { 0L }
        return "avgCycleMs=$avg floor=$TTL_FLOOR_MS_6626 ceiling=$TTL_CEILING_MS_6626 " +
            "lastGranted=${lastGranted.get()} extended=${extendedAllocations.get()} floored=${flooredAllocations.get()}"
    }

    /** V5.0.6626 test-only reset. */
    fun resetForTest() {
        extendedAllocations.set(0L)
        flooredAllocations.set(0L)
        lastGranted.set(TTL_FLOOR_MS_6626)
    }
}

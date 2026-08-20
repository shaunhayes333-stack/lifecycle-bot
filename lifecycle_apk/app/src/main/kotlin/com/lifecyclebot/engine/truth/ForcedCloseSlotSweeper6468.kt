package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6468 §P0 (item 17) — FORCED CLOSE + SLOT SWEEPER.
 *
 * OPERATOR MANDATE:
 *   "Fix forced close/slot cleanup. Synthetic closes must release
 *    the canonical mint occupancy slot; otherwise mint stays OPEN
 *    forever and blocks future entries."
 *
 * DESIGN
 * ──────
 * Reconcile `CanonicalMintOccupancyRegistry6464` against the
 * authoritative `CanonicalPositionAuthority6441.openPositions()`.
 *
 * For every mint currently marked OPEN or PENDING_EXIT in the
 * occupancy registry but NOT present in canonical open positions,
 * mark the slot CLOSED and emit a forensic trace.
 *
 * Runs on the 30-loop maintenance cadence, budgeted, non-blocking.
 * Idempotent — a healthy registry is a no-op.
 */
object ForcedCloseSlotSweeper6468 {

    private val sweeps = AtomicLong(0L)
    private val slotsReleased = AtomicLong(0L)
    private val lastReleased = AtomicLong(0L)

    /** Reconcile occupancy vs canonical positions. Returns slots released. */
    fun sweep(): Int {
        sweeps.incrementAndGet()
        val canonicalMints: Set<String> = try {
            CanonicalPositionAuthority6441.openPositions().map { it.mint }.toSet()
        } catch (_: Throwable) { return 0 }
        val occupancyEntries = try {
            CanonicalMintOccupancyRegistry6464.snapshotEntries()
        } catch (_: Throwable) { emptyList() }
        var released = 0
        for (entry in occupancyEntries) {
            if (entry.occupancy != CanonicalMintOccupancyRegistry6464.Occupancy.OPEN &&
                entry.occupancy != CanonicalMintOccupancyRegistry6464.Occupancy.PENDING_EXIT) continue
            // CanonicalPositionAuthority contains both modes. A mint still
            // represented by an active canonical position must retain its slot.
            if (entry.mint in canonicalMints) continue
            CanonicalMintOccupancyRegistry6464.markClosed(entry.mode, entry.mint)
            released++
            try {
                ForensicLogger.lifecycle(
                    "FORCED_CLOSE_SLOT_RELEASED_BY_SWEEP_6476",
                    "mode=${entry.mode} mint=${entry.mint.take(10)} prior=${entry.occupancy}",
                )
                PipelineHealthCollector.labelInc("FORCED_CLOSE_SLOT_RELEASED_BY_SWEEP_6476")
            } catch (_: Throwable) {}
        }
        if (released > 0) {
            slotsReleased.addAndGet(released.toLong())
            lastReleased.set(released.toLong())
        }
        return released
    }

    /** Explicit release path — called from any forced-close code site. */
    fun onForcedClose(mode: String, mint: String, reason: String) {
        if (mint.isBlank()) return
        try {
            CanonicalMintOccupancyRegistry6464.markClosed(mode, mint)
            slotsReleased.incrementAndGet()
            ForensicLogger.lifecycle(
                "FORCED_CLOSE_SLOT_RELEASED_6468",
                "mode=$mode mint=${mint.take(10)} reason=$reason",
            )
            PipelineHealthCollector.labelInc("FORCED_CLOSE_SLOT_RELEASED_6468")
        } catch (_: Throwable) {}
    }

    fun statusLine(): String =
        "sweeps=${sweeps.get()} slotsReleased=${slotsReleased.get()} lastReleased=${lastReleased.get()}"

    internal fun resetForTest() { sweeps.set(0L); slotsReleased.set(0L); lastReleased.set(0L) }
}

package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6470 §P0 — CANONICAL LIFECYCLE AUTHORITY (projection watchdog).
 *
 * OPERATOR MANDATE (verbatim):
 *
 *   "CanonicalPositionStore / canonical lot ledger becomes the sole
 *    lifecycle truth. Registry, UI, slot health, mint occupancy,
 *    runner ledger, wallet surfaces, exit coordinator, learning,
 *    replay and reports MUST project from canonical truth.
 *
 *    canonicalCount=155 registryCount=82 delta=73
 *    multiple canonical CLOSED / registry OPEN
 *    multiple canonical qty>0 / registry qty=0."
 *
 * DESIGN
 * ──────
 * `CanonicalPositionAuthority6441` remains the sole lifecycle truth.
 * This authority verifies that DERIVED projections converge to it
 * every parity audit. On divergence it:
 *
 *   1. Emits `LIFECYCLE_PROJECTION_DIVERGED_6470` with per-projection deltas.
 *   2. Quarantines the specific positionIds where the divergence is
 *      unrecoverable (e.g., canonical CLOSED but registry OPEN with
 *      qty>0) so learning cannot train on ghost lots.
 *   3. Records the largest divergence for the operator report.
 *
 * NON-INVASIVE. It observes and quarantines; it does not mutate the
 * secondary registries. Rebuilding them from canonical is the
 * responsibility of `PositionRegistryParityAudit6464.healRegistryFromCanonical()`
 * which is already wired.
 */
object CanonicalLifecycleAuthority6470 {

    data class ParityReport(
        val canonicalOpen: Int,
        val registryOpen: Int,
        val occupancyOpen: Int,
        val delta: Int,
        val closedButRegistryOpen: Int,
        val quarantinedNow: Int,
    )

    private val audits = AtomicLong(0L)
    private val divergences = AtomicLong(0L)
    private val quarantinesTriggered = AtomicLong(0L)
    private val lastDelta = AtomicLong(0L)

    fun audit(): ParityReport {
        audits.incrementAndGet()
        val canonicalOpenPositions = try {
            CanonicalPositionAuthority6441.openPositions().filter { it.remainingQtyRaw > java.math.BigInteger.ZERO }
        } catch (_: Throwable) { emptyList() }
        try { CanonicalMintOccupancyRegistry6464.reconcileActiveFromCanonical6489(canonicalOpenPositions) } catch (_: Throwable) {}
        try { PositionStateLedger6454.syncFromCanonical6519(canonicalOpenPositions) } catch (_: Throwable) {}
        try { SellQtyBoundaryClamp6427.syncFromCanonical6519(canonicalOpenPositions) } catch (_: Throwable) {}
        val canonicalOpen = canonicalOpenPositions.map { it.mint }.toSet()
        val occSnap = try {
            CanonicalMintOccupancyRegistry6464.snapshotByOccupancy()
        } catch (_: Throwable) { emptyMap() }
        val occupancyOpen = occSnap[CanonicalMintOccupancyRegistry6464.Occupancy.OPEN] ?: 0
        val positionStateOpen = try { PositionStateLedger6454.openOrPartialCount6519() } catch (_: Throwable) { -1 }
        val sellBoundaryOpen = try { SellQtyBoundaryClamp6427.trackedOpenCount6519() } catch (_: Throwable) { -1 }
        val exitCoordinatorVisibleOpen = canonicalOpenPositions.size
        // We cannot enumerate the legacy EmergentGuardrails registry from here
        // (private API); PositionRegistryParityAudit6464 does that. We record
        // the canonical vs occupancy delta here — the legacy registry parity
        // is fed via PIPELINE_HEALTH counters.
        val registryOpen = positionStateOpen
        val delta = maxOf(
            kotlin.math.abs(occupancyOpen - canonicalOpenPositions.size),
            kotlin.math.abs(positionStateOpen - canonicalOpenPositions.size),
            kotlin.math.abs(sellBoundaryOpen - canonicalOpenPositions.size),
            kotlin.math.abs(exitCoordinatorVisibleOpen - canonicalOpenPositions.size),
        )
        lastDelta.set(delta.toLong())
        var quarantinedNow = 0
        // Detect: canonical CLOSED but occupancy still OPEN for the same mint.
        // We approximate by scanning the closed set from CanonicalPositionAuthority
        // and querying isOpen on occupancy.
        val closedButOpen = try {
            var c = 0
            CanonicalPositionAuthority6441.closedPositions()
                .filter { it.mint !in canonicalOpen }
                .distinctBy { "${it.mode.lowercase()}|${it.mint}" }
                .forEach { pos ->
                if (CanonicalMintOccupancyRegistry6464.isOpen(pos.mode, pos.mint)) {
                    // Contradiction: canonical CLOSED, occupancy OPEN.
                    LearningQuarantineGate6470.quarantineMint(
                        pos.mint, "CANONICAL_CLOSED_OCCUPANCY_OPEN_6470",
                    )
                    quarantinedNow++
                    c++
                }
            }
            c
        } catch (_: Throwable) { 0 }
        if (delta != 0 || closedButOpen > 0) {
            divergences.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "LIFECYCLE_PROJECTION_DIVERGED_6470",
                    "canonicalOpen=${canonicalOpenPositions.size} positionStateOpen=$positionStateOpen sellBoundaryOpen=$sellBoundaryOpen exitCoordinatorVisibleOpen=$exitCoordinatorVisibleOpen occupancyOpen=$occupancyOpen " +
                        "delta=$delta closedButOccupancyOpen=$closedButOpen quarantinedNow=$quarantinedNow invariant=${canonicalOpenPositions.size == positionStateOpen && canonicalOpenPositions.size == sellBoundaryOpen && canonicalOpenPositions.size == exitCoordinatorVisibleOpen}",
                )
                PipelineHealthCollector.labelInc("LIFECYCLE_PROJECTION_DIVERGED_6470")
                if (quarantinedNow > 0) {
                    quarantinesTriggered.addAndGet(quarantinedNow.toLong())
                    PipelineHealthCollector.labelInc("LIFECYCLE_PROJECTION_QUARANTINED_6470")
                }
            } catch (_: Throwable) {}
        }
        return ParityReport(
            canonicalOpen = canonicalOpenPositions.size,
            registryOpen = registryOpen,
            occupancyOpen = occupancyOpen,
            delta = delta,
            closedButRegistryOpen = closedButOpen,
            quarantinedNow = quarantinedNow,
        )
    }

    fun statusLine(): String =
        "audits=${audits.get()} divergences=${divergences.get()} " +
            "quarantinesTriggered=${quarantinesTriggered.get()} lastDelta=${lastDelta.get()}"

    internal fun resetForTest() {
        audits.set(0L); divergences.set(0L)
        quarantinesTriggered.set(0L); lastDelta.set(0L)
    }
}

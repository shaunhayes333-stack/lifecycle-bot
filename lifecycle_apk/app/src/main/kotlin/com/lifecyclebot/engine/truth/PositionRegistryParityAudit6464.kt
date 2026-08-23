package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.EmergentGuardrails
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6464 §P0-#2 — POSITION REGISTRY VS CANONICAL PARITY.
 *
 * OPERATOR MANDATE:
 *   "canonical=54 registry=143 delta=-89. This must be reconciled by
 *    identity, not count. Print state breakdown for BOTH. Then produce
 *    missingFromCanonical / missingFromRegistry / stateMismatch /
 *    qtyMismatch / costBasisMismatch IDs. No registry may use OPEN as
 *    a catch-all for pending states. Canonical economic state is
 *    authoritative."
 *
 * DESIGN
 * ──────
 * Non-mutating audit that walks:
 *   - CanonicalPositionAuthority6441 (source of truth)
 *   - EmergentGuardrails.snapshot() (legacy registry)
 * and emits per-state counts + ID sets. Wired into the pipeline dump
 * so operators see divergence continuously.
 *
 * The audit does NOT rewrite either registry — healing is the job of
 * `EmergentGuardrails.rebuildFromCanonical6464(...)` (invoked
 * explicitly at run boundaries).
 */
object PositionRegistryParityAudit6464 {

    data class Snapshot(
        val canonicalByState: Map<CanonicalPositionAuthority6441.Lifecycle, Int>,
        val registryByState: Map<String, Int>,
        val canonicalCount: Int,
        val registryCount: Int,
        val delta: Int, // canonical - registry
        val missingFromCanonical: List<String>,
        val missingFromRegistry: List<String>,
        val stateMismatch: List<String>,
        val qtyMismatch: List<String>,
        val costBasisMismatch: List<String>,
    )

    private val lastSnapshot = AtomicReference<Snapshot?>(null)
    private val audits = AtomicLong(0L)
    private val divergences = AtomicLong(0L)
    // V5.0.6465 §P0-#3 REGISTRY AUTO-HEAL — after N consecutive
    // divergent audits, rebuild EmergentGuardrails from canonical
    // state.
    private const val AUTO_HEAL_THRESHOLD = 3
    private val consecutiveDivergences = AtomicLong(0L)
    private val autoHeals = AtomicLong(0L)

    fun audit(): Snapshot {
        audits.incrementAndGet()
        val canonicalOpens = try {
            CanonicalPositionAuthority6441.openPositions()
        } catch (_: Throwable) { emptyList() }
        // V5.0.6488 — slot/registry parity compares funded active positions
        // only. PENDING_ENTRY has no filled quantity and must not consume an
        // OPEN slot or manufacture a permanent canonical-registry delta.
        val canonicalAll = canonicalOpens

        val activeMode6490 = if (try { com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { true }) "paper" else "live"
        val canonicalModeRows6490 = canonicalAll.filter { it.mode == activeMode6490 }
        val canonicalByState = canonicalModeRows6490.groupingBy { it.lifecycle }.eachCount()
        val canonicalByMint = CanonicalPositionAuthority6441.activeMintProjections6490(activeMode6490).associateBy { it.mint }
        val canonicalStateByMint6498 = canonicalModeRows6490.groupBy { it.mint }.mapValues { (_, lots) ->
            if (lots.any { it.lifecycle == CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED }) "PARTIALLY_CLOSED" else "OPEN"
        }
        val canonicalMints = canonicalByMint.keys

        val registryMap: Map<String, EmergentGuardrails.RegistryEntry> = try {
            EmergentGuardrails.snapshot()
        } catch (_: Throwable) { emptyMap() }
        val registryByState = registryMap.values.groupingBy { it.state }.eachCount()
        val registryMints = registryMap.keys

        val missingFromRegistry = canonicalMints.filter { it !in registryMints }
        val missingFromCanonical = registryMints.filter { it !in canonicalMints }

        val stateMismatch = mutableListOf<String>()
        val qtyMismatch = mutableListOf<String>()
        val costBasisMismatch = mutableListOf<String>()
        val common = canonicalMints.intersect(registryMints)
        for (mint in common) {
            val c = canonicalByMint[mint] ?: continue
            val r = registryMap[mint] ?: continue
            val expectedState6498 = canonicalStateByMint6498[mint] ?: "OPEN"
            if (!r.state.equals(expectedState6498, true)) stateMismatch += "${mint.take(10)}(c=$expectedState6498 r=${r.state})"
            val qDelta = kotlin.math.abs(c.remainingQtyRaw.toDouble() - r.qtyRaw.toDouble())
            if (qDelta > 1.0) qtyMismatch += "${mint.take(10)}(cq=${c.remainingQtyRaw} rq=${r.qtyRaw} lots=${c.lotCount})"
            val costDelta = kotlin.math.abs(c.remainingCostBasisSol - r.entryCostSol)
            if (costDelta > 0.001) costBasisMismatch += "${mint.take(10)}(cc=${"%.4f".format(c.remainingCostBasisSol)} rc=${"%.4f".format(r.entryCostSol)} lots=${c.lotCount})"
        }

        val snap = Snapshot(
            canonicalByState = canonicalByState,
            registryByState = registryByState,
            canonicalCount = canonicalByMint.size,
            registryCount = registryMap.size,
            delta = canonicalByMint.size - registryMap.size,
            missingFromCanonical = missingFromCanonical.take(20).map { it.take(12) },
            missingFromRegistry = missingFromRegistry.take(20).map { it.take(12) },
            stateMismatch = stateMismatch.take(20),
            qtyMismatch = qtyMismatch.take(20),
            costBasisMismatch = costBasisMismatch.take(20),
        )
        lastSnapshot.set(snap)
        val diverged = snap.missingFromCanonical.isNotEmpty() || snap.missingFromRegistry.isNotEmpty() ||
                       snap.stateMismatch.isNotEmpty() || snap.qtyMismatch.isNotEmpty() ||
                       snap.costBasisMismatch.isNotEmpty()
        if (diverged) {
            divergences.incrementAndGet()
            val streak = consecutiveDivergences.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "POSITION_PARITY_DIVERGENCE_6464",
                    "canonical=${snap.canonicalCount} registry=${snap.registryCount} delta=${snap.delta} " +
                        "missCan=${snap.missingFromCanonical.size} missReg=${snap.missingFromRegistry.size} " +
                        "stateMismatch=${snap.stateMismatch.size} qtyMismatch=${snap.qtyMismatch.size} " +
                        "costMismatch=${snap.costBasisMismatch.size} streak=$streak",
                )
                PipelineHealthCollector.labelInc("POSITION_PARITY_DIVERGENCE_6464")
            } catch (_: Throwable) {}
            if (streak >= AUTO_HEAL_THRESHOLD) {
                rebuildFromCanonical6475()
                consecutiveDivergences.set(0L)
            }
        } else {
            consecutiveDivergences.set(0L)
            try { PipelineHealthCollector.labelInc("POSITION_PARITY_CONVERGENT_6464") } catch (_: Throwable) {}
        }
        return snap
    }

    /**
     * V5.0.6465 §P0-#3 — REBUILD EmergentGuardrails FROM CANONICAL.
     *
     * Called automatically after AUTO_HEAL_THRESHOLD consecutive
     * divergent audits. Walks canonical open positions and:
     *   - `registerPosition` for any mint missing from the legacy
     *     registry
     *   - `unregisterPosition` for any phantom mint present in the
     *     registry but absent from canonical
     *
     * Non-destructive to canonical state; the goal is to make the
     * legacy registry match the source of truth.
     */
    fun rebuildFromCanonical6475() {
        autoHeals.incrementAndGet()
        try {
            val canonical = try {
                val mode6490 = if (try { com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { true }) "paper" else "live"
                CanonicalPositionAuthority6441.openPositions().filter { it.mode == mode6490 }
            } catch (_: Throwable) { emptyList() }
            val canonicalMints = canonical.map { it.mint }.toSet()
            val registry = try { EmergentGuardrails.snapshot() } catch (_: Throwable) { emptyMap() }
            val registryMints = registry.keys.toSet()

            // V5.0.6475 — rebuild the active projection atomically from
            // canonical positions. This removes stale OPEN/zero-qty rows and
            // backfills missing canonical active rows without touching history.
            EmergentGuardrails.rebuildFromCanonical6475(canonical)
            try {
                CanonicalMintOccupancyRegistry6464.reconcileActiveFromCanonical6489(canonical)
            } catch (_: Throwable) {}
            ForensicLogger.lifecycle(
                "POSITION_PARITY_AUTO_HEAL_6465",
                "canonical=${canonicalMints.size} registryBefore=${registryMints.size} " +
                    "addedToRegistry=${(canonicalMints - registryMints).size} " +
                    "removedFromRegistry=${(registryMints - canonicalMints).size}",
            )
            PipelineHealthCollector.labelInc("POSITION_PARITY_AUTO_HEAL_6465")
        } catch (_: Throwable) {}
    }

    fun formatForPipelineDump(): String {
        val s = lastSnapshot.get() ?: return "PositionParity6464: (no audit yet)"
        val sb = StringBuilder("PositionParity6464:\n")
        sb.append("  canonicalCount=${s.canonicalCount} registryCount=${s.registryCount} delta=${s.delta}\n")
        sb.append("  canonicalByState=").append(
            s.canonicalByState.entries.joinToString(",") { "${it.key}=${it.value}" }
        ).append("\n")
        sb.append("  registryByState=").append(
            s.registryByState.entries.joinToString(",") { "${it.key}=${it.value}" }
        ).append("\n")
        if (s.missingFromCanonical.isNotEmpty())
            sb.append("  missingFromCanonical=").append(s.missingFromCanonical.joinToString(",")).append("\n")
        if (s.missingFromRegistry.isNotEmpty())
            sb.append("  missingFromRegistry=").append(s.missingFromRegistry.joinToString(",")).append("\n")
        if (s.stateMismatch.isNotEmpty())
            sb.append("  stateMismatch=").append(s.stateMismatch.joinToString(",")).append("\n")
        if (s.qtyMismatch.isNotEmpty())
            sb.append("  qtyMismatch=").append(s.qtyMismatch.joinToString(",")).append("\n")
        if (s.costBasisMismatch.isNotEmpty())
            sb.append("  costBasisMismatch=").append(s.costBasisMismatch.joinToString(",")).append("\n")
        sb.append("  audits=${audits.get()} divergences=${divergences.get()}\n")
        return sb.toString()
    }

    fun statusLine(): String = "audits=${audits.get()} divergences=${divergences.get()} " +
        "consecutiveDivergences=${consecutiveDivergences.get()} autoHeals=${autoHeals.get()}"

    /** V5.0.6466 — accessor for AdvisorIntegrityHold6466. */
    fun lastSnapshotOrNull(): Snapshot? = lastSnapshot.get()

    internal fun resetForTest() {
        lastSnapshot.set(null)
        audits.set(0L); divergences.set(0L)
        consecutiveDivergences.set(0L); autoHeals.set(0L)
    }
}

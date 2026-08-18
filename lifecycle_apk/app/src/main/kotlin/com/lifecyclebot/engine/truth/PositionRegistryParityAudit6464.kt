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

    fun audit(): Snapshot {
        audits.incrementAndGet()
        val canonicalOpens = try {
            CanonicalPositionAuthority6441.openPositions()
        } catch (_: Throwable) { emptyList() }
        val canonicalPending = try {
            CanonicalPositionAuthority6441.pendingEntryPositions6461()
        } catch (_: Throwable) { emptyList() }
        val canonicalClosed = try {
            CanonicalPositionAuthority6441.closedPositions()
        } catch (_: Throwable) { emptyList() }
        val canonicalAll = canonicalOpens + canonicalPending + canonicalClosed

        val canonicalByState = canonicalAll.groupingBy { it.lifecycle }.eachCount()
        val canonicalByMint = canonicalAll.associateBy { it.mint }
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
            val expectedRegState = when (c.lifecycle) {
                CanonicalPositionAuthority6441.Lifecycle.OPEN,
                CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED -> "OPEN"
                CanonicalPositionAuthority6441.Lifecycle.PENDING_ENTRY -> "PENDING_ENTRY"
                CanonicalPositionAuthority6441.Lifecycle.PENDING_EXIT -> "PENDING_EXIT"
                CanonicalPositionAuthority6441.Lifecycle.CLOSED -> "CLOSED"
                CanonicalPositionAuthority6441.Lifecycle.CANCELLED_ENTRY -> "CANCELLED_ENTRY"
                CanonicalPositionAuthority6441.Lifecycle.QUARANTINED -> "QUARANTINED"
                else -> "OPEN"
            }
            if (r.state != expectedRegState) stateMismatch += "${mint.take(10)}(c=${c.lifecycle} r=${r.state})"
            val qDelta = kotlin.math.abs(c.openedQtyRaw.toDouble() - r.qtyRaw.toDouble())
            if (qDelta > 1.0) qtyMismatch += "${mint.take(10)}(cq=${c.openedQtyRaw} rq=${r.qtyRaw})"
            val costDelta = kotlin.math.abs(c.entryCostSol - r.entryCostSol)
            if (costDelta > 0.001) costBasisMismatch += "${mint.take(10)}(cc=${"%.4f".format(c.entryCostSol)} rc=${"%.4f".format(r.entryCostSol)})"
        }

        val snap = Snapshot(
            canonicalByState = canonicalByState,
            registryByState = registryByState,
            canonicalCount = canonicalAll.size,
            registryCount = registryMap.size,
            delta = canonicalAll.size - registryMap.size,
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
            try {
                ForensicLogger.lifecycle(
                    "POSITION_PARITY_DIVERGENCE_6464",
                    "canonical=${snap.canonicalCount} registry=${snap.registryCount} delta=${snap.delta} " +
                        "missCan=${snap.missingFromCanonical.size} missReg=${snap.missingFromRegistry.size} " +
                        "stateMismatch=${snap.stateMismatch.size} qtyMismatch=${snap.qtyMismatch.size} " +
                        "costMismatch=${snap.costBasisMismatch.size}",
                )
                PipelineHealthCollector.labelInc("POSITION_PARITY_DIVERGENCE_6464")
            } catch (_: Throwable) {}
        } else {
            try { PipelineHealthCollector.labelInc("POSITION_PARITY_CONVERGENT_6464") } catch (_: Throwable) {}
        }
        return snap
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

    fun statusLine(): String = "audits=${audits.get()} divergences=${divergences.get()}"

    internal fun resetForTest() {
        lastSnapshot.set(null)
        audits.set(0L); divergences.set(0L)
    }
}

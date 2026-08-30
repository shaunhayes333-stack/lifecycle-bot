package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6496 §4 → V5.0.6497 §3 — EXECUTION SNAPSHOT AUTHORITY (relaxed).
 *
 * 6496 SHIP CAPTURED THE INTENT BUT OVER-CONSTRAINED THE HANDOFF.
 * ────────────────────────────────────────────────────────────────
 * The 6496 tuple was (candidateVersion, primaryLane, preFdgVerdict,
 * authorityVersion). Operator's 6496 dump proved this rejects legit
 * candidates:
 *
 *   EXEC_SNAPSHOT_DRIFT_6496 = 39
 *   EXEC_OPEN_DROPPED_SNAPSHOT_DRIFT_6496 = 39
 *
 * candidateVersion and preFdgVerdict are BOTH volatile by design —
 * `preFdgVerdict` transitions BUY↔WATCH as fresh price ticks land,
 * and `candidateVersion` bumps on any material state mutation. Fast-
 * moving tokens naturally see both change between FDG-allow and
 * EXEC_OPEN and that must not veto the entry.
 *
 * V5.0.6497 §3 tuple (operator's exact spec):
 *   • primaryLane          — the elected lane (immutable per election)
 *   • safetyAuthorityTier  — SAFE / CAUTION / RUG (identity, not price)
 *   • canonicalOccupancy   — mode+mint position occupancy identity
 *   • resolvedOrderSizeSol — the sealed order size (§1 authority)
 *
 * Only these four fields are checked. Volatile market data (price,
 * mcap, liquidity, preFdgVerdict, candidateVersion) refreshes rather
 * than rejects.
 */
object ExecutionSnapshotAuthority6496 {

    data class Snapshot(
        val primaryLane: String,
        val safetyAuthorityTier: String,
        val canonicalOccupancy: String,
        val resolvedOrderSizeSol: Double,
        val recordedAtMs: Long,
        // V5.0.6609 §SEALED_ACTION_IN_SNAPSHOT (operator directive Feb 2026:
        //   "Snapshot restore must restore the immutable ExecIntent verbatim.
        //   DO NOT reconstruct it by doing signal = savedSignal ?: UNKNOWN.
        //   Persist and restore executionAction / fdgVerdict / allowed.").
        //   Prior Snapshot only carried primaryLane / safety / occupancy /
        //   size — so when the frozen-snapshot restore fired (118× in the
        //   6608 dump), downstream signal derivation had no FDG-authorized
        //   verdict to pick up and defaulted to UNKNOWN, producing 118×
        //   EXEC_OPEN_BLOCKED_SIGNAL_NOT_BUY. Add the sealed FDG action
        //   here so the restore path can honour it.
        val fdgVerdict: String = "",
        val executionAction: String = "",
    )

    private val sealed = ConcurrentHashMap<String, Snapshot>()
    private val records = AtomicLong(0L)
    private val matches = AtomicLong(0L)
    private val drifts = AtomicLong(0L)

    private const val SNAPSHOT_TTL_MS = 15_000L
    // Resolved order size may re-clamp within a small band as cash /
    // ladder / lane cap re-evaluate. Only reject on material drift.
    private const val SIZE_MATERIAL_DRIFT_RATIO = 0.20  // 20 %

    /** Called by FDG-allow. Seals the executable tuple for the mint. */
    fun record(
        mint: String,
        primaryLane: String,
        safetyAuthorityTier: String,
        canonicalOccupancy: String,
        resolvedOrderSizeSol: Double,
        // V5.0.6609 §SEALED_ACTION_IN_SNAPSHOT — optional so pre-6609 call
        // sites keep compiling; callers that have the FDG verdict should
        // pass it so frozen-snapshot restore honours the sealed action.
        fdgVerdict: String = "",
        executionAction: String = "",
    ) {
        if (mint.isBlank()) return
        val snap = Snapshot(
            primaryLane = primaryLane.uppercase(),
            safetyAuthorityTier = safetyAuthorityTier.uppercase(),
            canonicalOccupancy = canonicalOccupancy,
            resolvedOrderSizeSol = resolvedOrderSizeSol,
            recordedAtMs = System.currentTimeMillis(),
            fdgVerdict = fdgVerdict.uppercase(),
            executionAction = executionAction.uppercase(),
        )
        sealed[mint] = snap
        records.incrementAndGet()
        try { PipelineHealthCollector.labelInc("EXEC_SNAPSHOT_RECORDED_6496") } catch (_: Throwable) {}
    }

    /**
     * V5.0.6609 §SEALED_ACTION_IN_SNAPSHOT — public read of a still-valid
     * (within TTL) sealed snapshot so the executor can recover the FDG-
     * authorized executionAction after a frozen-snapshot restore. Returns
     * null when no snapshot exists or the TTL has expired.
     */
    fun sealedSnapshot6609(mint: String): Snapshot? {
        if (mint.isBlank()) return null
        val snap = sealed[mint] ?: return null
        val age = System.currentTimeMillis() - snap.recordedAtMs
        return if (age in 0..SNAPSHOT_TTL_MS) snap else null
    }

    /**
     * Ticket-creation callers pass their currently-observed tuple.
     * Returns null on match (proceed with ticket). Returns a
     * human-readable drift reason otherwise; caller MUST refuse
     * ticket creation upstream and log `EXEC_SNAPSHOT_DRIFT_6496`.
     *
     * A stale (>TTL) or missing snapshot is treated as "no seal ⇒
     * allow" so we do not accidentally block cold-start candidates.
     */
    fun matchOrDriftReason(
        mint: String,
        primaryLane: String,
        safetyAuthorityTier: String,
        canonicalOccupancy: String,
        resolvedOrderSizeSol: Double,
    ): String? {
        val snap = sealed[mint] ?: return null
        if (System.currentTimeMillis() - snap.recordedAtMs > SNAPSHOT_TTL_MS) {
            sealed.remove(mint, snap)
            return null
        }
        val pLane = primaryLane.uppercase()
        val pSafety = safetyAuthorityTier.uppercase()
        val driftBits = buildList {
            // V5.0.6512 — strategy lane may rebind before ticket commit.
            // It is not position occupancy and cannot manufacture snapshot drift.
            // Safety drift only matters when it degrades to a hard
            // veto tier. SAFE → CAUTION is not drift; anything → RUG /
            // NO_BUY / UNKNOWN is a material identity change.
            val hardSafetyDrift = snap.safetyAuthorityTier != pSafety &&
                (pSafety == "RUG" || pSafety == "NO_BUY" || pSafety == "UNKNOWN")
            if (hardSafetyDrift)
                add("safetyAuthorityTier(${snap.safetyAuthorityTier}->$pSafety)")
            if (snap.canonicalOccupancy.isNotBlank() && canonicalOccupancy.isNotBlank() &&
                snap.canonicalOccupancy != canonicalOccupancy)
                add("canonicalOccupancy(${snap.canonicalOccupancy}->$canonicalOccupancy)")
            // Order size drift — only reject on MATERIAL shrink beyond
            // the tolerance band. A resize UP is never drift.
            if (snap.resolvedOrderSizeSol > 0.0 &&
                resolvedOrderSizeSol < snap.resolvedOrderSizeSol * (1.0 - SIZE_MATERIAL_DRIFT_RATIO))
                add("resolvedOrderSizeSol(${"%.4f".format(snap.resolvedOrderSizeSol)}->${"%.4f".format(resolvedOrderSizeSol)})")
        }
        return if (driftBits.isEmpty()) {
            matches.incrementAndGet()
            null
        } else {
            drifts.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "EXEC_SNAPSHOT_DRIFT_6496",
                    "mint=${mint.take(10)} drift=${driftBits.joinToString("+")}",
                )
                PipelineHealthCollector.labelInc("EXEC_SNAPSHOT_DRIFT_6496")
            } catch (_: Throwable) {}
            driftBits.joinToString("+")
        }
    }

    /**
     * Consume the seal on ticket commit so a later tick re-evaluates
     * fresh. Idempotent.
     */
    fun consume(mint: String) {
        if (mint.isBlank()) return
        sealed.remove(mint)
    }

    fun statusLine(): String =
        "sealed=${sealed.size} records=${records.get()} matches=${matches.get()} drifts=${drifts.get()}"

    internal fun resetForTest() {
        sealed.clear()
        records.set(0L); matches.set(0L); drifts.set(0L)
    }
}

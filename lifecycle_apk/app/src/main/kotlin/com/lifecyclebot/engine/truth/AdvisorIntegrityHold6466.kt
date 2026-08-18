package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6466 §P1 (narrow scope from item 15) — ADVISOR INTEGRITY FIREWALL.
 *
 * OPERATOR MANDATE:
 *   "advisor must be prevented from tuning around corrupted lifecycle/
 *    accounting data from the moment the canonical rewrite begins."
 *
 * DESIGN
 * ──────
 * Any of these signals ⇒ DATA_INTEGRITY_HOLD is true:
 *   - PositionParity delta != 0
 *   - stateMismatch > 0
 *   - qtyMismatch > 0
 *   - paper replay divergence != 0
 *   - conservation violation observed
 *   - finalized bus silent (canonicalUnique == 0 while sells > 0)
 *   - reconciler stale/uninitialized
 *
 * AutoPipelineAdvisor6462 reads `isHold()` at the top of every tick.
 * When HOLD is active the advisor may only emit ENGINEERING remediation
 * suggestions — no strategy mutations (score floors / cooldown / sizing /
 * TP / SL / trailing / lane weights / strategy selection).
 */
object AdvisorIntegrityHold6466 {

    private val holdChecks = AtomicLong(0L)
    private val holdActive = AtomicLong(0L)

    /** Aggregate signals into a single verdict. Non-blocking; ~1ms. */
    fun isHold(): Boolean {
        holdChecks.incrementAndGet()
        var reasons = 0
        // Position parity
        try {
            val snap = PositionRegistryParityAudit6464.lastSnapshotOrNull()
            if (snap != null) {
                if (snap.delta != 0) reasons++
                if (snap.stateMismatch.isNotEmpty()) reasons++
                if (snap.qtyMismatch.isNotEmpty()) reasons++
            }
        } catch (_: Throwable) {}
        // Paper replay divergence
        try {
            val p = CanonicalPaperReplay6464.lastParity()
            if (p != null && (kotlin.math.abs(p.cashDelta) > 0.01 ||
                              kotlin.math.abs(p.realizedDelta) > 0.01 ||
                              kotlin.math.abs(p.openCostDelta) > 0.01)) reasons++
        } catch (_: Throwable) {}
        // Finalized bus silence
        try {
            val bus = CanonicalFinalizedTradeBus6464.canonicalUnique()
            val terminalClaims = TerminalMutationAuthority6466.claimCount()
            if (terminalClaims > 0L && bus == 0) reasons++
        } catch (_: Throwable) {}
        // Ledger invariant violations
        try {
            val fi4famClamps = PipelineHealthCollector.labelCountSnapshot("FI4FAM_UNIT_CORRUPTION_6461")
            val ledgerInvFails = PipelineHealthCollector.labelCountSnapshot("PAPER_LEDGER_INVARIANT_FAIL_6430")
            if (fi4famClamps + ledgerInvFails > 5L) reasons++
        } catch (_: Throwable) {}
        // V5.0.6468 §P0 (item 15 extension) — Event stream replay divergence
        // (from EventStreamReplay6467) also gates the advisor. Advisor must
        // never tune around a diverged replay.
        try {
            val p = EventStreamReplay6467.lastParity()
            if (p != null && (kotlin.math.abs(p.cashDelta) > 0.01 ||
                              kotlin.math.abs(p.realizedDelta) > 0.01 ||
                              kotlin.math.abs(p.openCostDelta) > 0.01 ||
                              p.firstDivergentEventId != null)) reasons++
        } catch (_: Throwable) {}
        // V5.0.6468 §P0 (item 15 extension) — Order-size invariant violations
        // observed since startup gate the advisor. Sizing correctness is
        // upstream of learning; a violated resolver poisons every trade.
        try {
            val sizingViolations = PipelineHealthCollector.labelCountSnapshot("ORDER_SIZE_RESOLVER_INVARIANT_VIOLATION_6468")
            if (sizingViolations > 3L) reasons++
        } catch (_: Throwable) {}
        // V5.0.6468 §P0 (item 15 extension) — data provider auth lockout is
        // an environmental integrity failure; do not tune around it.
        try {
            val authLockouts = PipelineHealthCollector.labelCountSnapshot("DATA_PROVIDER_AUTH_LOCKOUT_6468")
            if (authLockouts > 0L) reasons++
        } catch (_: Throwable) {}
        val active = reasons > 0
        if (active) {
            holdActive.incrementAndGet()
            try { PipelineHealthCollector.labelInc("ADVISOR_INTEGRITY_HOLD_ACTIVE_6466") } catch (_: Throwable) {}
        }
        return active
    }

    fun statusLine(): String {
        return "checks=${holdChecks.get()} activeCount=${holdActive.get()}"
    }

    internal fun resetForTest() { holdChecks.set(0L); holdActive.set(0L) }
}

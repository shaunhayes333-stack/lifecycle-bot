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

    /**
     * V5.0.6505 — HOLDS DISABLED, DIAGNOSTIC KEPT.
     *
     * Operator mandate (this session): "I want all enforced holds
     * removed. I just want the bot to go back to trading correctly.
     * Full data integrity and super intelligent autonomous trading."
     *
     * The correctness surface has moved to the SOURCE: FillLotLedger6504
     * is the immutable lot truth, PaperAccountLedger6430
     * .overrideRealizedFromFillLots6504 rebuilds realized PnL from
     * finalized fills, EconomicPurityGate6504 quarantines contaminated
     * mints from analytics — the bot no longer needs to be strangled
     * to protect learners. Data integrity is enforced at write time,
     * not by refusing to trade.
     *
     * `isHold()` therefore returns FALSE unconditionally so the
     * AutoPipelineAdvisor6462 tick and every other consumer runs.
     * The multi-signal diagnostic is preserved via
     * `diagnosticActive()` for reporting only — dashboards can still
     * surface the underlying integrity signal without gating flow.
     */
    fun isHold(): Boolean {
        holdChecks.incrementAndGet()
        // Advisor + trading flow no longer gated. FillLotLedger6504 is
        // now the source-of-truth correctness surface.
        return false
    }

    /**
     * V5.0.6505 — DIAGNOSTIC ONLY. Returns the same multi-signal
     * verdict the pre-6505 `isHold()` returned so dashboards can
     * report on integrity signals without enforcing a hold.
     */
    fun diagnosticActive(): Boolean {
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
        try {
            val p = EventStreamReplay6467.lastParity()
            if (p != null && (kotlin.math.abs(p.cashDelta) > 0.01 ||
                              kotlin.math.abs(p.realizedDelta) > 0.01 ||
                              kotlin.math.abs(p.openCostDelta) > 0.01 ||
                              p.firstDivergentEventId != null)) reasons++
        } catch (_: Throwable) {}
        try {
            val sizingViolations = PipelineHealthCollector.labelCountSnapshot("ORDER_SIZE_RESOLVER_INVARIANT_VIOLATION_6468")
            if (sizingViolations > 3L) reasons++
        } catch (_: Throwable) {}
        try {
            val authLockouts = PipelineHealthCollector.labelCountSnapshot("DATA_PROVIDER_AUTH_LOCKOUT_6468")
            if (authLockouts > 0L) reasons++
        } catch (_: Throwable) {}
        val active = reasons > 0
        if (active) {
            holdActive.incrementAndGet()
            try { PipelineHealthCollector.labelInc("ADVISOR_INTEGRITY_DIAGNOSTIC_ACTIVE_6505") } catch (_: Throwable) {}
        }
        return active
    }

    fun statusLine(): String {
        return "checks=${holdChecks.get()} diagnosticActiveCount=${holdActive.get()} enforcementDisabled=true_6505"
    }

    internal fun resetForTest() { holdChecks.set(0L); holdActive.set(0L) }
}

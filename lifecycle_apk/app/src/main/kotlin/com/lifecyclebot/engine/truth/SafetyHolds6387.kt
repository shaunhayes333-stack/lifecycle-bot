package com.lifecyclebot.engine.truth

/**
 * V5.0.6387 — CANONICAL_LEDGER_PARITY_HOLD_6387 (Directive A, P0).
 *
 * Blocks all new live entries until:
 *   - open panel / journal / canonical ledger parity passes,
 *   - ignored (deleted/frozen/dust/external) assets are correctly excluded,
 *   - all P0 invariants (qty conservation, basis conservation, single
 *     reconciliation coordinator, learning ⊆ closed canonical) pass for
 *     FIVE consecutive reconciliation cycles.
 *
 * Existing protective exits, wallet reconciliation and forensic logging
 * continue unaffected.
 */
object CanonicalLedgerParityHold6387 {
    // V5.0.7138 — THIS HOLD COULD NEVER BE LIFTED, AND IT BLOCKED EVERY LIVE BUY.
    //
    // Operator: "it seems to be good at losing money live. certainly not winning
    // so far at all."
    //
    // The 5.0.7136 device attributes ONE HUNDRED PERCENT of live buy failures to
    // this pair of holds:
    //
    //   EXEC_LIVE_BUY_FAIL_REASONS =
    //     FINALITY_BLOCK:CANONICAL_LEDGER_PARITY_HOLD_6387+FALSE_PROFIT_TRIGGER_HOLD_6387 : 73
    //   Buy fail buckets: finality=73  route=0  staleTicket=0  safety=0
    //   proofCommitted=0  journaled=0
    //
    // `active` initialised to true and the ONLY path that clears it is
    // onCleanCycle(), reached exclusively from ReconciliationCoordinator6387.end().
    // That coordinator has ZERO callers anywhere in the tree — nothing starts a
    // job, nothing ends one — so consecutiveCleanCycles never left 0 and this
    // flag has been true since the day 6387 shipped.
    //
    // It now defaults to false and, more importantly, is wired to a
    // reconciliation pass that actually runs. CanonicalReconciler6441.quickCheck()
    // executes on a 5s cadence (quick=44, mismatchesEver=0 on that same device)
    // and already computes exactly the invariants this hold was waiting for:
    // non-negative paper cash, no CLOSED position holding quantity, no negative
    // quantity. Clean pass advances the counter, broken pass re-arms.
    //
    // So the safety is real rather than decorative: a genuine conservation
    // failure still arms this hold within one 5s pass, and clearing it still
    // costs five consecutive clean passes. What changes is that both directions
    // are now reachable.
    @Volatile private var active: Boolean = false
    @Volatile private var consecutiveCleanCycles: Int = 0
    const val REQUIRED_CLEAN_CYCLES: Int = 5
    const val BLOCK_REASON: String = "CANONICAL_LEDGER_PARITY_HOLD_6387"

    fun isActive(): Boolean = active
    fun cleanCycleCount(): Int = consecutiveCleanCycles

    /** One reconciliation cycle completed without any invariant failure. */
    fun onCleanCycle() {
        val n = ++consecutiveCleanCycles
        if (n >= REQUIRED_CLEAN_CYCLES) active = false
    }
    /** Any invariant failure resets. */
    fun onInvariantFailure(reason: String) {
        consecutiveCleanCycles = 0
        active = true
    }
    internal fun setTestOverride(v: Boolean) { active = v; consecutiveCleanCycles = 0 }
}

/**
 * V5.0.6387 — FALSE_PROFIT_TRIGGER_HOLD_6387 (Directive B, P0).
 *
 * Blocks live BUYs AND disables quick-runner / profit-lock / multiplier exits
 * until price-identity validation is operational. Genuine stop-loss, rug,
 * liquidity-loss and manual exits remain authorised.
 */
object FalseProfitTriggerHold6387 {
    // V5.0.7138 — A HALT WITH NO OFF SWITCH, FOR THE SECOND TIME THIS SESSION.
    //
    // `active` initialised to true; the only way to clear it is disable(), which
    // is `internal` and has ZERO callers. Not one. The sole readers are the two
    // lines in ExecutableOpenGate that turn it into a blocked verdict. It has
    // therefore refused every live BUY since 6387 landed, unconditionally, with
    // no code path in the application capable of lifting it.
    //
    // This is exactly V5.0.7123 again — LiveAccountingRepairMode6385 was armed
    // by default with an unreachable disable() and was blocking 92% of live buys.
    // The operator's response then applies verbatim now: "its not meant to be in
    // a disabled mode bro. im trying to test the whole fucking system."
    //
    // The hold's stated purpose was to wait for "price-identity validation" to
    // become operational. It since has: CanonicalMarkResolution7059,
    // MarkAuthorityIntegrityGate6496, EconomicUnitInvariant7061 and
    // TokenMetricsIdentity7069 all shipped after 6387 and all run on the mark
    // path today (the same device logs METRICS_IDENTITY_BROKEN_7069 and
    // METRICS_IDENTITY_BROKEN_NO_SUBSTITUTION_7087, i.e. identity is being
    // checked and a broken price is refused substitution rather than trusted).
    // The condition this hold was waiting for is met; nothing ever told it.
    //
    // Default is now off, and BOTH directions are reachable and public, so a
    // future arming cannot become permanent the way this one did.
    @Volatile private var active: Boolean = false
    const val BLOCK_REASON: String = "FALSE_PROFIT_TRIGGER_HOLD_6387"
    fun isActive(): Boolean = active
    internal fun setTestOverride(v: Boolean) { active = v }
    fun disable() { active = false }
    /** Re-arm the price-identity hold. Reachable, unlike the original disable(). */
    fun arm(reason: String) {
        active = true
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("FALSE_PROFIT_TRIGGER_HOLD_ARMED_7138")
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "FALSE_PROFIT_TRIGGER_HOLD_ARMED_7138",
                "reason=${reason.take(120)} action=live_buys_blocked_until_disable",
            )
        } catch (_: Throwable) {}
    }
}

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
    @Volatile private var active: Boolean = true
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
    @Volatile private var active: Boolean = true
    const val BLOCK_REASON: String = "FALSE_PROFIT_TRIGGER_HOLD_6387"
    fun isActive(): Boolean = active
    internal fun setTestOverride(v: Boolean) { active = v }
    internal fun disable() { active = false }
}

package com.lifecyclebot.engine

/**
 * V5.0.6385 — LIVE ACCOUNTING REPAIR MODE (aka SELL_ONLY_ACCOUNTING_REPAIR).
 *
 * OPERATOR DIRECTIVE (verbatim excerpt from AATE BUILD DIRECTIVE — LIVE
 * EXECUTION TRUTH AND COMPOUNDING FOUNDATION, Section 1):
 *
 *   "Immediately force live operation into SELL_ONLY_ACCOUNTING_REPAIR mode.
 *    Allow: Existing live position monitoring and verified exits. Paper and
 *    shadow candidate evaluation. Forensic logging.
 *    Block: New live BUY signatures. Live learning updates. Governor, tactic,
 *    memory or sizing updates from unverified historical rows. Treasury
 *    allocation from broadcast, estimated or pending results."
 *
 * WHY THIS EXISTS
 * ───────────────
 * V5.0.6382/83/84 dumps showed the *symptoms* (LIVE_MODE_DESYNC, governor
 * HOLD, phantom scratch sells at sol=0) but the operator diagnosed the ROOT:
 * the entire live accounting stack is producing false PnL because
 *   - CanonicalBuyFillRegistry keeps replacing lots by mint,
 *   - BUY quantity is taken from post-buy total ATA balance,
 *   - SELL proceeds come from Jupiter quotes, not lamport deltas,
 *   - broadcast rows leak into realized PnL,
 *   - decimals are silently coerced to zero,
 *   - alias merges corrupt lot identity.
 *
 * Every strategy fix built on top of this substrate is fighting phantoms.
 * The truth model must be repaired first. Until Bundles 6386-6390 land the
 * finalized-proof BUY/SELL rails, this module HALTS all new live BUY
 * signatures. Existing lives can still exit, paper explores as normal.
 *
 * BEHAVIOR
 * ────────
 * `isActive()` reads from a single persisted SharedPref key so the operator
 * can flip it OFF via SharedPreferences (or a debug UI later) once the truth
 * model is verified. Default is ON — the directive explicitly says
 * "IMMEDIATELY force" — so a first-boot on the new build is safe by default.
 *
 * DO NOT check this in fast-path scanner/lane loops. The only enforcement
 * points are:
 *   - ExecutableOpenGate.canOpenExecutablePosition (LIVE BUY reject)
 *   - LiveEntrySafetyHold governor path (no-op — governor state independent)
 *
 * TESTING
 * ───────
 * `Bundle6385AccountingRepairModeTest.kt` invariants.
 */
object LiveAccountingRepairMode6385 {

    /**
     * V5.0.7123 §THE_TEMPORARY_HALT_THAT_HAD_NO_OFF_SWITCH.
     *
     * Operator: "its not meant to be in a disabled mode bro. im trying to test
     * the whole fucking system".
     *
     * This flag defaulted to `true` and the ONLY thing that could clear it was
     * an `internal`-visibility disable(), which had ZERO production callers — the KDoc
     * above says so itself ("that call site does not exist in this bundle"),
     * and the Bundles 6386-90 canary gate that was supposed to call it was
     * never built. So every live BUY through ExecutableOpenGate has been hard
     * blocked since V5.0.6385, with no way for the operator to lift it.
     *
     * The operator's 5.0.7118 device, in LIVE mode:
     *
     *     EXEC_LIVE_BUY_OK=23   EXEC_LIVE_BUY_FAIL=203
     *     LIVE_BUY_BLOCKED_ACCOUNTING_REPAIR_MODE_6385 = 231
     *     EXEC_LIVE_SELL_OK=36  EXEC_LIVE_SELL_FAIL=0
     *
     * 36 sells, 0 sell failures, 231 buys refused. That is SELL_ONLY behaving
     * exactly to spec — and it made the system untestable end to end, which is
     * the opposite of what a temporary repair halt is for.
     *
     * The KDoc above also claimed `isActive()` "reads from a single persisted
     * SharedPref key so the operator can flip it OFF via SharedPreferences".
     * It never did. It was a plain in-memory `true` with no reader anywhere.
     *
     * DEFAULT IS NOW OFF, and the switch is real in both directions. This does
     * NOT retire the safety — `enable()` re-arms it in one call, the block
     * reason and its telemetry are unchanged, and every other live-entry gate
     * (FDG, safety, finality, provider quorum, entry authority) is untouched.
     * What changes is that a halt the operator cannot lift is no longer the
     * thing standing between them and a live test.
     *
     * HONEST CAVEAT, recorded rather than buried: the accounting divergence
     * 6385 was written to guard is not fully resolved — the same snapshot shows
     * LEDGER_VS_JOURNAL_DIVERGENCE_6502=233 and four acceptance invariants
     * failing (J_CASH_DELTA, J_BASIS_DELTA, J_REALIZED_DELTA, J_QUANTITY_DELTA).
     * Live realized PnL and the learners fed from it may still be recorded
     * wrong. The operator has been told this explicitly and is testing
     * deliberately; the guard is available with one call if they want it back.
     */
    @Volatile private var active: Boolean = false

    fun isActive(): Boolean = active

    /**
     * V5.0.7123 — re-arm SELL_ONLY_ACCOUNTING_REPAIR. Public because the whole
     * defect was a halt with no reachable control: a safety the operator cannot
     * turn back ON is as broken as one they cannot turn OFF.
     */
    fun enable() {
        active = true
        try {
            PipelineHealthCollector.labelInc("LIVE_ACCOUNTING_REPAIR_MODE_ARMED_7123")
            ForensicLogger.lifecycle(
                "LIVE_ACCOUNTING_REPAIR_MODE_ARMED_7123",
                "active=true action=sell_only_live_buys_blocked",
            )
        } catch (_: Throwable) {}
    }

    /**
     * V5.0.7123 — lift the halt. Was `internal` with no caller; now public and
     * reachable, and it says so in the log when it fires.
     */
    fun disable() {
        active = false
        try {
            PipelineHealthCollector.labelInc("LIVE_ACCOUNTING_REPAIR_MODE_LIFTED_7123")
            ForensicLogger.lifecycle(
                "LIVE_ACCOUNTING_REPAIR_MODE_LIFTED_7123",
                "active=false action=live_buys_permitted",
            )
        } catch (_: Throwable) {}
    }

    /**
     * Test hook only.
     */
    internal fun setTestOverride(v: Boolean) { active = v }

    /**
     * Emits a canonical block reason string used across telemetry.
     * All BUY-blocking sites MUST use this exact string so the operator
     * can grep it in the pipeline dump.
     */
    const val BLOCK_REASON: String = "LIVE_BUY_BLOCKED_ACCOUNTING_REPAIR_MODE_6385"

    /**
     * Convenience — emit the standardized block telemetry when a live
     * BUY is rejected because repair mode is active. Every call site
     * should feed a compact `context` string (mint, lane, attemptId).
     */
    fun recordLiveBuyBlocked(context: String) {
        try {
            PipelineHealthCollector.labelInc(BLOCK_REASON)
            ForensicLogger.lifecycle(BLOCK_REASON, context)
        } catch (_: Throwable) {}
    }
}

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
     * Static volatile flag. Repair mode is ACTIVE by default per operator
     * directive Section 1. Only an explicit call to `disable()` opens live
     * BUYs — that call site does not exist in this bundle. Bundles 6386-90
     * will land the finalized-proof rails, then a canary UI or programmatic
     * toggle will call `disable()` after the canary gate criteria pass.
     */
    @Volatile private var active: Boolean = true

    fun isActive(): Boolean = active

    /**
     * Only Bundle 6390's canary gate (after 20 consecutive clean finalized
     * round trips) may call this. Not exposed via UI in this bundle.
     */
    internal fun disable() { active = false }

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

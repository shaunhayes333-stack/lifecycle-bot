package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6312 — LIVE CAPITAL PROTECTION KIT.
 *
 * Central authority governing whether a candidate is allowed to execute
 * with LIVE funds. Sits between the existing lane/FDG/EXEC pipeline and
 * the actual `doBuy` execution — every live BUY must pass through
 * `assessLiveEntry()` and receive a `LiveEntryAssessment.ALLOW` result.
 * Any other result routes the candidate to PAPER / SHADOW instead of
 * silently dropping it (`LIVE_PROBE_REDIRECTED_TO_SHADOW`).
 *
 * The three governors implemented here address the failure evidence in
 * the operator hotfix brief:
 *
 * 1. **LIVE_ENTRY_SAFETY_HOLD** — arms when any critical execution or
 *    accounting invariant is unhealthy (unresolved decimals, wallet
 *    balance authority down, mint-level duplicate ownership, accounting
 *    quarantine active, etc). While armed:
 *      - blocks new LIVE buys
 *      - continues live exit monitoring, stop-loss, take-profit,
 *        reconciliation, paper/shadow eval, scanner, telemetry, learning
 *        from CANONICAL FINALISED results
 *      - never auto-liquidates
 *
 * 2. **Live Confidence Governor** — replaces gate-relaxer for the live
 *    lane. If recent finalised n < 10 → strict baseline (no relaxation).
 *    If n ≥ 10 and any of {WR<40, PF<1.0, exp<0} → tighten (floor+, size-,
 *    cooldown+). If any of {WR<25, PF<0.7, catastrophic exits, invariant
 *    failures} → arm the SAFETY_HOLD.
 *
 * 3. **Bypass ban** — a hard denylist of exploration/probe labels that
 *    must never authorize a LIVE buy. These are allowed in paper/shadow/
 *    probation but never with real SOL:
 *      - LIVE_EXPECTANCY_REJECT_BYPASSED
 *      - LIVE_LAST_MILE_FLOOR_LIFTED
 *      - LANE_WAIT_OVERRIDE_DUST_PROBE
 *      - LANE_WAIT_OVERRIDE_ZERO_SIGNAL_DUST_PROBE_4164
 *      - SMART_SIZER_V3_DUST_PROMOTED_6271
 *      - PRETRADE_PENDING_PROOF_PENALTY_ALLOW
 *      - PROVIDER_PROOF_HOLDER_CASCADE_BLIND
 *      - BRAIN_RUGCHECK_FLOOR
 *      - INTAKE_PROBATION_ONLY
 *      - NO_PAIR_NO_FALLBACK
 *      - TOKEN_MAP_PENDING
 *      - PROBE_ONLY
 *
 * All checks fail-CLOSED for live (candidate redirected to shadow), and
 * fail-OPEN for paper/shadow (candidate proceeds so the learning brain
 * can observe outcomes). This matches the design rule: "Live execution
 * must fail closed. Paper/shadow evaluation may fail open for learning."
 */
object LiveEntrySafetyHold {

    // ----- SafetyHold state -----------------------------------------

    private val armed = AtomicBoolean(false)
    private val armedAtMs = AtomicLong(0L)
    private val armedReasons = ConcurrentHashMap<String, Long>()
    @Volatile private var lastHealthCheckMs: Long = 0L
    @Volatile private var lastHealthCheckReasons: List<String> = emptyList()

    // ----- Configurable thresholds ----------------------------------

    /** Minimum candidate score to execute a LIVE buy. Below this the
     *  candidate is redirected to shadow. Does NOT auto-relax on low WR. */
    @Volatile var minLiveCandidateScore: Double = 55.0
        private set

    /** Minimum finalised sample before ANY performance-based relaxation
     *  applies to the live path (governor stays in baseline until then). */
    private const val GOVERNOR_MIN_SAMPLE: Int = 10

    /** Governor tighten thresholds (n ≥ GOVERNOR_MIN_SAMPLE). */
    private const val TIGHTEN_WR_PCT: Double = 40.0
    private const val TIGHTEN_PF: Double = 1.0
    private const val TIGHTEN_EXPECTANCY_SOL: Double = 0.0

    /** Governor severe thresholds → arm SAFETY_HOLD. */
    private const val SEVERE_WR_PCT: Double = 25.0
    private const val SEVERE_PF: Double = 0.70

    // ─── V5.0.6324 GOVERNOR FLUIDITY ────────────────────────────────
    // Operator directive: N=5, WR=20%, PF=0.05, exp=-0.0027 SOL MUST
    // materially shape live execution — not sit at BASELINE. The 6318
    // auto-snooze grace (N>=20) blocked all response to real bleed.
    // 6324 keeps the WADDLE-era exclusion in place (session window) but
    // opens up CAUTION and SOFT_TIGHT as small-N-friendly states that
    // reduce size / raise floor / require probe-first WITHOUT disabling
    // any lane. HOLD is preserved but reserved for execution-integrity /
    // wallet-safety failures (invariant-driven, not strategy bleed).

    private const val CAUTION_MIN_N: Int = 4
    private const val CAUTION_PF: Double = 0.80
    private const val CAUTION_EXP_SOL: Double = 0.0
    private const val CAUTION_WR_PCT: Double = 35.0

    private const val SOFT_TIGHT_MIN_N: Int = 4
    private const val SOFT_TIGHT_PF: Double = 0.35
    private const val SOFT_TIGHT_EXP_SOL: Double = -0.0015
    private const val SOFT_TIGHT_WR_PCT: Double = 25.0

    /** Deterministic size / floor shaping per state (P5). Callers read
     *  these via [currentSizeMultiplier] and [currentFloorAdjustment]. */
    private const val SIZE_MULTIPLIER_BASELINE: Double = 1.00
    private const val SIZE_MULTIPLIER_CAUTION: Double = 0.70
    private const val SIZE_MULTIPLIER_SOFT_TIGHT: Double = 0.40
    private const val SIZE_MULTIPLIER_RECOVERY: Double = 0.60
    private const val FLOOR_ADJUSTMENT_BASELINE: Double = 0.0
    private const val FLOOR_ADJUSTMENT_CAUTION: Double = 3.0
    private const val FLOOR_ADJUSTMENT_SOFT_TIGHT: Double = 8.0
    private const val FLOOR_ADJUSTMENT_RECOVERY: Double = 5.0

    @Volatile private var lastGovernorState: GovernorState = GovernorState.BASELINE
    @Volatile private var lastGovernorSizeMultiplier: Double = SIZE_MULTIPLIER_BASELINE
    @Volatile private var lastGovernorFloorAdjustment: Double = FLOOR_ADJUSTMENT_BASELINE

    fun currentSizeMultiplier(): Double = lastGovernorSizeMultiplier
    fun currentFloorAdjustment(): Double = lastGovernorFloorAdjustment
    fun currentGovernorState(): GovernorState = lastGovernorState

    // ----- Bypass ban denylist --------------------------------------

    private val LIVE_BYPASS_DENYLIST: Set<String> = setOf(
        "LIVE_EXPECTANCY_REJECT_BYPASSED",
        "LIVE_LAST_MILE_FLOOR_LIFTED",
        "LANE_WAIT_OVERRIDE_DUST_PROBE",
        "LANE_WAIT_OVERRIDE_ZERO_SIGNAL_DUST_PROBE_4164",
        "SMART_SIZER_V3_DUST_PROMOTED_6271",
        "PRETRADE_PENDING_PROOF_PENALTY_ALLOW",
        "PROVIDER_PROOF_HOLDER_CASCADE_BLIND",
        "BRAIN_RUGCHECK_FLOOR",
        "INTAKE_PROBATION_ONLY",
        "NO_PAIR_NO_FALLBACK",
        "TOKEN_MAP_PENDING",
        "PROBE_ONLY",
    )

    // ----- Public API ----------------------------------------------

    data class LiveEntryAssessment(
        val allow: Boolean,
        val redirectToShadow: Boolean,
        val reason: String,
        val failedInvariants: List<String>,
    )

    /**
     * Single-entry decision point for LIVE buy authorization. Returns
     * ALLOW only when every critical invariant is healthy AND no bypass
     * label is present AND the candidate meets the minimum score floor.
     * Any other outcome is a redirect to SHADOW (or hard block for
     * catastrophic failures).
     */
    fun assessLiveEntry(
        mint: String,
        symbol: String,
        candidateScore: Double,
        entryReasons: Collection<String>,
        lane: String,
    ): LiveEntryAssessment {
        val failed = mutableListOf<String>()

        // 1) SAFETY_HOLD gate — hard block, do not even redirect.
        if (armed.get()) {
            val reasonSummary = armedReasons.keys.take(5).joinToString(",")
            // V5.0.6324 — classify the block. Distinguish security /
            // wallet-integrity blocks (hard) from policy redirects
            // (intentional shadow route) so the health report no longer
            // shows 411 "provider failures" that were really policy calls.
            val securityBlocked = armedReasons.keys.any {
                it.contains("WALLET", true) || it.contains("SECURITY", true) ||
                it.contains("ACCOUNTING_QUARANTINE", true) || it.contains("SUPERVISOR", true) ||
                it.contains("DUPLICATE_FINALITY", true) || it.contains("DECIMAL_SKEW", true)
            }
            try {
                ForensicLogger.lifecycle(
                    "LIVE_ENTRY_SAFETY_HOLD_BUY_BLOCKED",
                    "mint=${mint.take(10)} sym=$symbol lane=$lane score=${candidateScore.toInt()} reasons=$reasonSummary securityBlocked=$securityBlocked",
                )
                PipelineHealthCollector.labelInc("LIVE_ENTRY_SAFETY_HOLD_BUY_BLOCKED")
                if (securityBlocked) {
                    PipelineHealthCollector.labelInc("BUY_SECURITY_BLOCKED_6324")
                } else {
                    PipelineHealthCollector.labelInc("BUY_POLICY_REDIRECTED_SHADOW_6324")
                }
            } catch (_: Throwable) {}
            return LiveEntryAssessment(
                allow = false,
                redirectToShadow = true,
                reason = "SAFETY_HOLD_ARMED",
                failedInvariants = armedReasons.keys.toList(),
            )
        }

        // 2) Bypass ban — any denylisted label means the candidate isn't
        //    genuinely qualified for live execution. Fail closed, redirect.
        val bypassHit = entryReasons.firstOrNull { r ->
            LIVE_BYPASS_DENYLIST.any { deny -> r.contains(deny, ignoreCase = true) }
        }
        if (bypassHit != null) {
            failed += "LIVE_BYPASS_DENYLISTED:$bypassHit"
        }

        // 3) Live floor — score below floor routes to shadow (never
        //    auto-relax on poor WR; that's the whole point of the
        //    governor). V5.0.6324 — apply governor-state floor uplift so
        //    CAUTION / SOFT_TIGHT genuinely raise the bar for live entry.
        val effectiveFloor = minLiveCandidateScore + lastGovernorFloorAdjustment
        if (candidateScore < effectiveFloor) {
            failed += "SCORE_BELOW_LIVE_FLOOR:score=${candidateScore.toInt()}/min=${effectiveFloor.toInt()}"
        }

        // 4) Confidence Governor — degraded live performance downgrades
        //    fresh candidates to shadow so learning can continue on
        //    paper without spending real SOL.
        val governorStatus = evaluateConfidenceGovernor()
        if (governorStatus == GovernorState.HOLD) {
            // Governor severity arms the hold via evaluateConfidenceGovernor;
            // reaching HOLD here means we should also block this candidate.
            failed += "CONFIDENCE_GOVERNOR_HOLD"
        }

        if (failed.isNotEmpty()) {
            try {
                ForensicLogger.lifecycle(
                    "LIVE_PROBE_REDIRECTED_TO_SHADOW",
                    "mint=${mint.take(10)} sym=$symbol lane=$lane score=${candidateScore.toInt()} floor=${effectiveFloor.toInt()} state=$governorStatus failed=${failed.joinToString(";").take(220)}",
                )
                PipelineHealthCollector.labelInc("LIVE_PROBE_REDIRECTED_TO_SHADOW")
                PipelineHealthCollector.labelInc("BUY_POLICY_REDIRECTED_SHADOW_6324")
            } catch (_: Throwable) {}
            return LiveEntryAssessment(
                allow = false,
                redirectToShadow = true,
                reason = failed.first(),
                failedInvariants = failed,
            )
        }

        try {
            PipelineHealthCollector.labelInc("LIVE_ENTRY_AUTHORITY_ALLOWED_6312")
            PipelineHealthCollector.labelInc("BUY_LIVE_AUTHORIZED_6324")
        } catch (_: Throwable) {}
        return LiveEntryAssessment(
            allow = true,
            redirectToShadow = false,
            reason = "ALLOWED",
            failedInvariants = emptyList(),
        )
    }

    /**
     * Periodic health check. Called from the bot's main cycle every
     * few seconds. Arms / clears LIVE_ENTRY_SAFETY_HOLD based on the
     * critical invariant set.
     */
    fun runHealthCheck(
        walletAuthorityHealthy: Boolean,
        scannerCriticalDegradedWithoutFallback: Boolean,
        supervisorSaturated: Boolean,
        accountingQuarantineActive: Boolean,
        pendingReconcileFailures: Int,
        recentDecimalSkewEvents: Int,
        recentDuplicateFinalityEvents: Int,
        recentSellClampEvents: Int,
    ) {
        val now = System.currentTimeMillis()
        lastHealthCheckMs = now
        val failed = mutableListOf<String>()
        if (!walletAuthorityHealthy) failed += "WALLET_AUTHORITY_DOWN"
        if (scannerCriticalDegradedWithoutFallback) failed += "SCANNER_CRITICAL_DEGRADED_NO_FALLBACK"
        if (supervisorSaturated) failed += "SUPERVISOR_SATURATED"
        if (accountingQuarantineActive) failed += "ACCOUNTING_QUARANTINE_ACTIVE"
        if (pendingReconcileFailures > 0) failed += "PENDING_RECONCILE_FAILURES=$pendingReconcileFailures"
        // Decimal skew or duplicate finality bursts mean the accounting
        // foundation is unhealthy — arm the hold until events stop rolling in.
        if (recentDecimalSkewEvents > 3) failed += "DECIMAL_SKEW_BURST=$recentDecimalSkewEvents"
        if (recentDuplicateFinalityEvents > 0) failed += "DUPLICATE_FINALITY=$recentDuplicateFinalityEvents"
        if (recentSellClampEvents > 5) failed += "SELL_CLAMP_BURST=$recentSellClampEvents"

        lastHealthCheckReasons = failed
        try {
            ForensicLogger.lifecycle(
                "LIVE_ENTRY_SAFETY_HOLD_HEALTH_CHECK",
                "armed=${armed.get()} failed=${failed.joinToString(",").take(220)}",
            )
            PipelineHealthCollector.labelInc("LIVE_ENTRY_SAFETY_HOLD_HEALTH_CHECK")
        } catch (_: Throwable) {}

        if (failed.isNotEmpty()) {
            armInternal(failed)
        } else if (armed.get()) {
            // V5.0.6318 — flip-flop guard. Do NOT auto-clear a hold that
            // was armed by CONFIDENCE_GOVERNOR_SEVERE — that arming path
            // has its own re-evaluation cycle. Health-check clears are
            // limited to invariant-owned reasons; otherwise the operator
            // saw ARMED=33 / CLEARED=32 flapping every bot tick.
            val currentReasons = armedReasons.keys.toSet()
            val invariantReasonsOnly = currentReasons.all { r ->
                !r.startsWith("CONFIDENCE_GOVERNOR_")
            }
            if (invariantReasonsOnly) {
                clearInternal("ALL_INVARIANTS_HEALTHY")
            }
        }
    }

    /** Manual arm/clear controls (operator-triggered or governor-triggered). */
    fun arm(reason: String) = armInternal(listOf(reason))
    fun clear(reason: String) = clearInternal(reason)

    fun isArmed(): Boolean = armed.get()
    fun armedReasonsSnapshot(): Map<String, Long> = armedReasons.toMap()
    fun lastHealthCheckSnapshot(): Pair<Long, List<String>> = lastHealthCheckMs to lastHealthCheckReasons

    // ----- Governor session window ---------------------------------

    /**
     * V5.0.6318 — SESSION-SCOPED GOVERNOR WINDOW.
     *
     * Set once per process boot to the wall-clock at module load. Rows
     * journaled BEFORE this timestamp represent historical outcomes and
     * MUST NOT influence the live confidence governor's automatic HOLD
     * arming. Only fresh post-boot canonical rows count.
     *
     * Rationale: the operator saw 19 pre-hotfix losing rows continue to
     * hold the governor in SEVERE state after V5.0.6317's HOTFIX_EPOCH
     * filter (calendar-date cutoff didn't fire because rows were dated
     * after Feb 24 2026). A session-scoped window solves this without
     * requiring persistence — each fresh boot gives the fixed pipeline
     * a clean slate to prove itself on. Operator manual reset also
     * available via [resetGovernorWindow].
     *
     * The AUTO SNOOZE GRACE: even after `canonicalN >= GOVERNOR_MIN_SAMPLE`,
     * the governor stays BASELINE until `canonicalN >= AUTO_SNOOZE_GRACE`
     * (20 canonical fresh trades). This prevents a bad opening streak
     * of 10 trades from arming HOLD before the tactic switcher has had
     * a chance to rotate through its playbook.
     */
    @Volatile private var governorWindowStartMs: Long = System.currentTimeMillis()

    private const val AUTO_SNOOZE_GRACE: Int = 20

    fun resetGovernorWindow(reason: String) {
        governorWindowStartMs = System.currentTimeMillis()
        try {
            ForensicLogger.lifecycle(
                "LIVE_CONFIDENCE_GOVERNOR_WINDOW_RESET_6318",
                "reason=$reason startMs=$governorWindowStartMs",
            )
            PipelineHealthCollector.labelInc("LIVE_CONFIDENCE_GOVERNOR_WINDOW_RESET_6318")
        } catch (_: Throwable) {}
    }

    fun governorWindowStart(): Long = governorWindowStartMs

    enum class GovernorState { BASELINE, CAUTION, SOFT_TIGHT, TIGHTENED, RECOVERY, HOLD }

    /**
     * Reads canonical finalised live stats from TradeHistoryStore.
     * Never touches broadcast/pending rows.
     *
     * V5.0.6324 — small-N-friendly CAUTION / SOFT_TIGHT states. The
     * governor now responds materially to real bleed at N>=4 without
     * disabling any lane. HOLD remains reserved for invariant / wallet
     * safety failures (armed via [runHealthCheck] or SEVERE bleed).
     */
    fun evaluateConfidenceGovernor(): GovernorState {
        val stats = try {
            LiveConfidenceStats.load()
        } catch (_: Throwable) { return applyGovernorState(GovernorState.BASELINE) }

        // Not enough data yet — behave normally so the fixed pipeline
        // gets a chance to show real signal.
        if (stats.canonicalN < CAUTION_MIN_N) {
            try { PipelineHealthCollector.labelInc("LIVE_CONFIDENCE_GOVERNOR_BASELINE") } catch (_: Throwable) {}
            return applyGovernorState(GovernorState.BASELINE)
        }

        // ── HOLD (invariant/wallet-safety severe) ────────────────────
        val severe =
            stats.canonicalN >= GOVERNOR_MIN_SAMPLE &&
            (stats.winRatePct < SEVERE_WR_PCT || stats.profitFactor < SEVERE_PF)
        if (severe) {
            try {
                ForensicLogger.lifecycle(
                    "LIVE_CONFIDENCE_GOVERNOR_HOLD",
                    "n=${stats.canonicalN} wr=${"%.1f".format(stats.winRatePct)}% pf=${"%.2f".format(stats.profitFactor)} exp=${"%.4f".format(stats.expectancySol)}",
                )
                PipelineHealthCollector.labelInc("LIVE_CONFIDENCE_GOVERNOR_HOLD")
            } catch (_: Throwable) {}
            armInternal(listOf("CONFIDENCE_GOVERNOR_SEVERE:wr=${stats.winRatePct.toInt()}/pf=${"%.2f".format(stats.profitFactor)}/n=${stats.canonicalN}"))
            return applyGovernorState(GovernorState.HOLD)
        }

        // ── SOFT_TIGHT (small-N decisive bleed) ──────────────────────
        val softTight =
            stats.canonicalN >= SOFT_TIGHT_MIN_N &&
            (stats.profitFactor < SOFT_TIGHT_PF ||
             stats.expectancySol <= SOFT_TIGHT_EXP_SOL ||
             (stats.canonicalN >= 5 && stats.winRatePct <= SOFT_TIGHT_WR_PCT))
        if (softTight) {
            try {
                ForensicLogger.lifecycle(
                    "LIVE_CONFIDENCE_GOVERNOR_SOFT_TIGHT_6324",
                    "n=${stats.canonicalN} wr=${"%.1f".format(stats.winRatePct)}% pf=${"%.2f".format(stats.profitFactor)} exp=${"%.4f".format(stats.expectancySol)}",
                )
                PipelineHealthCollector.labelInc("LIVE_CONFIDENCE_GOVERNOR_SOFT_TIGHT_6324")
            } catch (_: Throwable) {}
            return applyGovernorState(GovernorState.SOFT_TIGHT)
        }

        // ── CAUTION (early bleed) ────────────────────────────────────
        val caution =
            stats.canonicalN >= CAUTION_MIN_N &&
            (stats.profitFactor < CAUTION_PF ||
             stats.expectancySol < CAUTION_EXP_SOL ||
             (stats.canonicalN >= 5 && stats.winRatePct < CAUTION_WR_PCT))
        if (caution) {
            try {
                ForensicLogger.lifecycle(
                    "LIVE_CONFIDENCE_GOVERNOR_CAUTION_6324",
                    "n=${stats.canonicalN} wr=${"%.1f".format(stats.winRatePct)}% pf=${"%.2f".format(stats.profitFactor)} exp=${"%.4f".format(stats.expectancySol)}",
                )
                PipelineHealthCollector.labelInc("LIVE_CONFIDENCE_GOVERNOR_CAUTION_6324")
            } catch (_: Throwable) {}
            return applyGovernorState(GovernorState.CAUTION)
        }

        // ── Legacy TIGHTENED (retained for N>=GOVERNOR_MIN_SAMPLE) ────
        val tighten =
            stats.canonicalN >= GOVERNOR_MIN_SAMPLE &&
            (stats.winRatePct < TIGHTEN_WR_PCT ||
             stats.profitFactor < TIGHTEN_PF ||
             stats.expectancySol < TIGHTEN_EXPECTANCY_SOL)
        return if (tighten) {
            try { PipelineHealthCollector.labelInc("LIVE_CONFIDENCE_GOVERNOR_TIGHTENED") } catch (_: Throwable) {}
            applyGovernorState(GovernorState.TIGHTENED)
        } else {
            try { PipelineHealthCollector.labelInc("LIVE_CONFIDENCE_GOVERNOR_BASELINE") } catch (_: Throwable) {}
            applyGovernorState(GovernorState.BASELINE)
        }
    }

    /**
     * V5.0.6324 — persist state + emit transition event whenever the
     * governor materially changes. Callers read [currentSizeMultiplier]
     * and [currentFloorAdjustment] to shape sizing / floors without
     * needing to recompute the state.
     */
    private fun applyGovernorState(state: GovernorState): GovernorState {
        val prev = lastGovernorState
        val (mult, floorAdj) = when (state) {
            GovernorState.BASELINE -> SIZE_MULTIPLIER_BASELINE to FLOOR_ADJUSTMENT_BASELINE
            GovernorState.CAUTION -> SIZE_MULTIPLIER_CAUTION to FLOOR_ADJUSTMENT_CAUTION
            GovernorState.SOFT_TIGHT -> SIZE_MULTIPLIER_SOFT_TIGHT to FLOOR_ADJUSTMENT_SOFT_TIGHT
            GovernorState.TIGHTENED -> SIZE_MULTIPLIER_SOFT_TIGHT to FLOOR_ADJUSTMENT_SOFT_TIGHT
            GovernorState.RECOVERY -> SIZE_MULTIPLIER_RECOVERY to FLOOR_ADJUSTMENT_RECOVERY
            GovernorState.HOLD -> 0.0 to FLOOR_ADJUSTMENT_SOFT_TIGHT
        }
        lastGovernorSizeMultiplier = mult
        lastGovernorFloorAdjustment = floorAdj
        lastGovernorState = state
        if (prev != state) {
            try {
                ForensicLogger.lifecycle(
                    "LIVE_GOVERNOR_STATE_CHANGED_6324",
                    "oldState=$prev newState=$state sizeMult=${"%.2f".format(mult)} floorAdj=${"%.1f".format(floorAdj)}",
                )
                PipelineHealthCollector.labelInc("LIVE_GOVERNOR_STATE_CHANGED_6324")
                PipelineHealthCollector.labelInc("LIVE_GOVERNOR_STATE_${state.name}_6324")
            } catch (_: Throwable) {}
        }
        return state
    }

    /**
     * Canonical live-only stats derived from TradeHistoryStore. Only
     * SELL rows with proof=LIVE_FINALIZED (or reconciled) count. Broadcast
     * / pending / paper / quarantined rows are excluded.
     *
     * V5.0.6317 — HOTFIX EPOCH FILTER. Pre-V5.0.6310 rows contain
     * decimal-skew qty inflation, fake 10× "runner" labels on losing PnL,
     * contradictory exit reasons, and broadcast-vs-finalised double-counting.
     * The operator hotfix brief §18 forbids these corrupted rows from
     * training the confidence governor. Excluding them lets the governor
     * measure post-hotfix behaviour cleanly and re-open live entry once
     * the fixed pipeline demonstrates real edge.
     */
    data class LiveConfidenceStats(
        val canonicalN: Int,
        val wins: Int,
        val losses: Int,
        val winRatePct: Double,
        val profitFactor: Double,
        val expectancySol: Double,
    ) {
        companion object {
            /**
             * V5.0.6318 — session-scoped window replaces V5.0.6317's
             * hard-coded HOTFIX_EPOCH_MS calendar cutoff, which failed
             * to fire when trades were journaled after Feb 24 2026 but
             * still represented pre-hotfix corrupted state. The session
             * window is set at process boot in LiveEntrySafetyHold's
             * companion init and can be reset by the operator via
             * [resetGovernorWindow]. Only rows with ts >= window start
             * count toward the canonical governor sample.
             */
            fun load(): LiveConfidenceStats {
                val trades = try {
                    TradeHistoryStore.getRecentValidTrades(200)
                } catch (_: Throwable) { return LiveConfidenceStats(0, 0, 0, 0.0, 0.0, 0.0) }

                var wins = 0
                var losses = 0
                var grossWinSol = 0.0
                var grossLossSol = 0.0
                var netSol = 0.0
                var n = 0
                var preHotfixSkipped = 0
                val windowStart = governorWindowStart()

                for (t in trades) {
                    if (!t.side.equals("SELL", true) && !t.side.equals("PARTIAL_SELL", true)) continue
                    if (!t.mode.equals("live", true)) continue
                    val proof = t.proofState
                    if (!(proof.equals("LIVE_FINALIZED", true) || proof.equals("LIVE_RECONCILED", true))) {
                        // V5.0.6324 — surface broadcast-only rows that would
                        // otherwise silently escape canonical governor stats.
                        // The filter itself is unchanged (they were already
                        // excluded); the label makes the operator visible.
                        if (proof.equals("LIVE_BROADCAST", true)) {
                            try { PipelineHealthCollector.labelInc("BROADCAST_ACCOUNTING_SUPPRESSED_6324") } catch (_: Throwable) {}
                        }
                        continue
                    }

                    // Session-window cutoff: only fresh post-boot rows count.
                    if (t.ts > 0L && t.ts < windowStart) {
                        preHotfixSkipped += 1
                        continue
                    }
                    // Skip rows still bearing the EXIT_REASON_INVARIANT
                    // rewrite marker (contradictory-profit outcomes).
                    if (t.reason.startsWith("FALLBACK_AFTER_FAILED_PROFIT_EXIT_6312", ignoreCase = true)) {
                        preHotfixSkipped += 1
                        continue
                    }

                    val pnl = if (t.netPnlSol != 0.0) t.netPnlSol else t.pnlSol
                    n += 1
                    netSol += pnl
                    if (pnl > 0.0) { wins += 1; grossWinSol += pnl }
                    else if (pnl < 0.0) { losses += 1; grossLossSol += -pnl }
                }
                if (preHotfixSkipped > 0) {
                    try {
                        PipelineHealthCollector.labelInc("LIVE_CONFIDENCE_GOVERNOR_PRE_HOTFIX_ROWS_EXCLUDED_6317")
                    } catch (_: Throwable) {}
                }
                val wr = if (n > 0) wins.toDouble() * 100.0 / n else 0.0
                val pf = if (grossLossSol > 0.0) grossWinSol / grossLossSol else if (grossWinSol > 0.0) 999.0 else 0.0
                val exp = if (n > 0) netSol / n else 0.0
                return LiveConfidenceStats(n, wins, losses, wr, pf, exp)
            }
        }
    }

    // ----- Internal helpers ----------------------------------------

    private fun armInternal(reasons: List<String>) {
        val wasArmed = armed.getAndSet(true)
        val now = System.currentTimeMillis()
        armedAtMs.set(now)
        reasons.forEach { armedReasons.compute(it) { _, v -> (v ?: 0L) + 1L } }
        if (!wasArmed) {
            try {
                ForensicLogger.lifecycle(
                    "LIVE_ENTRY_SAFETY_HOLD_ARMED",
                    "reasons=${reasons.joinToString(",").take(220)}",
                )
                PipelineHealthCollector.labelInc("LIVE_ENTRY_SAFETY_HOLD_ARMED")
            } catch (_: Throwable) {}
        }
    }

    private fun clearInternal(reason: String) {
        val wasArmed = armed.getAndSet(false)
        armedReasons.clear()
        if (wasArmed) {
            try {
                ForensicLogger.lifecycle("LIVE_ENTRY_SAFETY_HOLD_CLEARED", "reason=$reason")
                PipelineHealthCollector.labelInc("LIVE_ENTRY_SAFETY_HOLD_CLEARED")
            } catch (_: Throwable) {}
        }
    }
}

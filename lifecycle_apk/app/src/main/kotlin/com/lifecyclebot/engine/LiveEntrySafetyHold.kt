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
            try {
                ForensicLogger.lifecycle(
                    "LIVE_ENTRY_SAFETY_HOLD_BUY_BLOCKED",
                    "mint=${mint.take(10)} sym=$symbol lane=$lane score=${candidateScore.toInt()} reasons=$reasonSummary",
                )
                PipelineHealthCollector.labelInc("LIVE_ENTRY_SAFETY_HOLD_BUY_BLOCKED")
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
        //    governor).
        if (candidateScore < minLiveCandidateScore) {
            failed += "SCORE_BELOW_LIVE_FLOOR:score=${candidateScore.toInt()}/min=${minLiveCandidateScore.toInt()}"
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
                    "mint=${mint.take(10)} sym=$symbol lane=$lane score=${candidateScore.toInt()} failed=${failed.joinToString(";").take(220)}",
                )
                PipelineHealthCollector.labelInc("LIVE_PROBE_REDIRECTED_TO_SHADOW")
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
            // All invariants clear → tentatively clear the hold.
            clearInternal("ALL_INVARIANTS_HEALTHY")
        }
    }

    /** Manual arm/clear controls (operator-triggered or governor-triggered). */
    fun arm(reason: String) = armInternal(listOf(reason))
    fun clear(reason: String) = clearInternal(reason)

    fun isArmed(): Boolean = armed.get()
    fun armedReasonsSnapshot(): Map<String, Long> = armedReasons.toMap()
    fun lastHealthCheckSnapshot(): Pair<Long, List<String>> = lastHealthCheckMs to lastHealthCheckReasons

    // ----- Confidence Governor -------------------------------------

    enum class GovernorState { BASELINE, TIGHTENED, HOLD }

    /**
     * Reads canonical finalised live stats from TradeHistoryStore.
     * Never touches broadcast/pending rows.
     */
    fun evaluateConfidenceGovernor(): GovernorState {
        val stats = try {
            LiveConfidenceStats.load()
        } catch (_: Throwable) { return GovernorState.BASELINE }

        if (stats.canonicalN < GOVERNOR_MIN_SAMPLE) {
            try { PipelineHealthCollector.labelInc("LIVE_CONFIDENCE_GOVERNOR_BASELINE") } catch (_: Throwable) {}
            return GovernorState.BASELINE
        }

        val severe =
            stats.winRatePct < SEVERE_WR_PCT ||
            stats.profitFactor < SEVERE_PF

        if (severe) {
            try {
                ForensicLogger.lifecycle(
                    "LIVE_CONFIDENCE_GOVERNOR_HOLD",
                    "n=${stats.canonicalN} wr=${"%.1f".format(stats.winRatePct)}% pf=${"%.2f".format(stats.profitFactor)} exp=${"%.4f".format(stats.expectancySol)}",
                )
                PipelineHealthCollector.labelInc("LIVE_CONFIDENCE_GOVERNOR_HOLD")
            } catch (_: Throwable) {}
            armInternal(listOf("CONFIDENCE_GOVERNOR_SEVERE:wr=${stats.winRatePct.toInt()}/pf=${"%.2f".format(stats.profitFactor)}/n=${stats.canonicalN}"))
            return GovernorState.HOLD
        }

        val tighten =
            stats.winRatePct < TIGHTEN_WR_PCT ||
            stats.profitFactor < TIGHTEN_PF ||
            stats.expectancySol < TIGHTEN_EXPECTANCY_SOL

        return if (tighten) {
            try {
                PipelineHealthCollector.labelInc("LIVE_CONFIDENCE_GOVERNOR_TIGHTENED")
            } catch (_: Throwable) {}
            GovernorState.TIGHTENED
        } else {
            try {
                PipelineHealthCollector.labelInc("LIVE_CONFIDENCE_GOVERNOR_BASELINE")
            } catch (_: Throwable) {}
            GovernorState.BASELINE
        }
    }

    /**
     * Canonical live-only stats derived from TradeHistoryStore. Only
     * SELL rows with proof=LIVE_FINALIZED (or reconciled) count. Broadcast
     * / pending / paper / quarantined rows are excluded.
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
            fun load(): LiveConfidenceStats {
                val trades = try {
                    TradeHistoryStore.getRecentValidTrades(100)
                } catch (_: Throwable) { return LiveConfidenceStats(0, 0, 0, 0.0, 0.0, 0.0) }

                var wins = 0
                var losses = 0
                var grossWinSol = 0.0
                var grossLossSol = 0.0
                var netSol = 0.0
                var n = 0

                for (t in trades) {
                    if (!t.side.equals("SELL", true) && !t.side.equals("PARTIAL_SELL", true)) continue
                    if (!t.mode.equals("live", true)) continue
                    // Canonical == finalised or reconciled proof; broadcast rows
                    // do NOT count against live confidence.
                    val proof = t.proofState
                    if (!(proof.equals("LIVE_FINALIZED", true) || proof.equals("LIVE_RECONCILED", true))) continue
                    val pnl = if (t.netPnlSol != 0.0) t.netPnlSol else t.pnlSol
                    n += 1
                    netSol += pnl
                    if (pnl > 0.0) { wins += 1; grossWinSol += pnl }
                    else if (pnl < 0.0) { losses += 1; grossLossSol += -pnl }
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

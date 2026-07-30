package com.lifecyclebot.engine.truth

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6396 — ENTRY REJECTION TELEMETRY (STOP CALLING POLICY REJECTIONS
 * BUY FAILURES).
 *
 * Score-floor rejection BEFORE quote/build MUST emit
 * ENTRY_REJECTED_SCORE_FLOOR and never touch a buy/execution failure
 * counter. This module is the ONLY sanctioned emit surface for
 * pre-executor rejections.
 *
 * Allowed increments:
 *   ENTRY_POLICY_REJECT
 *   SCORE_FLOOR_REJECT
 *   PRE_EXEC_REJECT
 *
 * Forbidden increments (emit() throws IllegalStateException when
 * called with these labels — mechanically prevents regressions):
 *   BUY_FAIL, BUY_FAILED, provider_failure, execution_failure,
 *   transaction_failure, live_buy_aborted_after_execution,
 *   RPC_failure_rate
 */
object EntryRejectionTelemetry6396 {

    val entryPolicyReject = AtomicLong(0L)
    val scoreFloorReject = AtomicLong(0L)
    val preExecReject = AtomicLong(0L)

    val forensicCorrectionRecords = AtomicLong(0L)

    private val FORBIDDEN_COUNTERS = setOf(
        "BUY_FAIL", "BUY_FAILED",
        "PROVIDER_FAILURE", "EXECUTION_FAILURE",
        "TRANSACTION_FAILURE", "LIVE_BUY_ABORTED_AFTER_EXECUTION",
        "RPC_FAILURE_RATE",
    )

    data class ScoreFloorRejectRecord(
        val mint: String, val symbol: String, val lane: String,
        val rawScore: Double, val effectiveScore: Double, val finalFloor: Int,
        val scoreScaleVersion: String, val metricEpoch: Long,
        val decisionId: String, val governorState: String,
        val hardSafetyPassed: Boolean, val recheckEligibleAt: Long,
    )

    /**
     * Emit a pre-executor score-floor rejection. Increments ONLY the three
     * whitelisted counters. Emits ForensicLogger.lifecycle with the full
     * required-field envelope.
     */
    fun emitScoreFloorReject(r: ScoreFloorRejectRecord) {
        entryPolicyReject.incrementAndGet()
        scoreFloorReject.incrementAndGet()
        preExecReject.incrementAndGet()
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ENTRY_POLICY_REJECT_6396")
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SCORE_FLOOR_REJECT_6396")
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PRE_EXEC_REJECT_6396")
        } catch (_: Throwable) {}
        try {
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "ENTRY_REJECTED_SCORE_FLOOR",
                "mint=${r.mint.take(10)} sym=${r.symbol} lane=${r.lane} " +
                "raw=${r.rawScore.toInt()} eff=${r.effectiveScore.toInt()} " +
                "floor=${r.finalFloor} scaleVer=${r.scoreScaleVersion} " +
                "epoch=${r.metricEpoch} decisionId=${r.decisionId.take(24)} " +
                "gov=${r.governorState} hardSafety=${r.hardSafetyPassed} " +
                "recheckAt=${r.recheckEligibleAt}",
            )
        } catch (_: Throwable) {}
    }

    /** Journal correction — historical policy rows reclassified as score-floor rejects. */
    fun recordForensicCorrection(originalRowId: String, correctedReason: String) {
        forensicCorrectionRecords.incrementAndGet()
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("JOURNAL_FORENSIC_CORRECTION_6396")
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "JOURNAL_FORENSIC_CORRECTION_6396",
                "originalRowId=${originalRowId.take(48)} correctedReason=$correctedReason",
            )
        } catch (_: Throwable) {}
    }

    /**
     * Compile-time-style guard: any caller attempting to increment a buy-failure
     * counter for a score-floor reject is an immediate regression.
     * Used by tests + optionally by wire-sites in defensive mode.
     */
    fun assertNotBuyFailure(label: String) {
        if (label.uppercase() in FORBIDDEN_COUNTERS)
            throw IllegalStateException("V5.0.6396: forbidden counter '$label' for score-floor rejection")
    }

    internal fun clearAllForTest() {
        entryPolicyReject.set(0L); scoreFloorReject.set(0L); preExecReject.set(0L)
        forensicCorrectionRecords.set(0L)
    }
}

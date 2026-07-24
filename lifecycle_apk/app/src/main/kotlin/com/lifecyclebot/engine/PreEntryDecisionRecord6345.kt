package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6345 — FIRST-TRADE FOUNDATION POLICY (operator P0-5 spec).
 *
 * OPERATOR DIRECTIVE (verbatim excerpts):
 *   "Make AATE trade competently from the first live trade using a
 *    deterministic, preloaded trading policy and evidence available
 *    before entry. Do not use early live losses as training material
 *    required to discover basic trading behaviour."
 *
 *   "Before every entry a PRE_ENTRY_DECISION_RECORD must be emitted
 *    containing all deterministic evidence the decision was based on:
 *    lane, expected R, stop distance, hydration state, sample size,
 *    executable spread, foundation-policy verdict. This record is the
 *    receipt the operator inspects when a trade misbehaves."
 *
 * PURPOSE
 *   Emits an immutable pre-entry evidence receipt for every live buy so
 *   the operator can audit exactly what deterministic facts were on the
 *   table at the moment the entry was authorised. This ships alongside
 *   the existing lane-entry gates — it observes; it does not veto.
 *
 * FUTURE V5.0.6346+ EXTENSION
 *   A follow-up push will let the foundation policy VETO the entry when
 *   the record is missing a mandatory field (e.g. no stop distance, no
 *   executable spread). This initial push runs in observation mode so
 *   the operator can see the raw evidence stream before we tighten it.
 */
object PreEntryDecisionRecord6345 {

    /** Immutable record. Every field is a deterministic fact known at the
     *  moment the entry ticket is being formed. Serialisable to CSV via
     *  toCompactLine() for forensic replay. */
    data class Record(
        val emittedAtMs: Long,
        val mint: String,
        val symbol: String,
        val laneRequested: String,
        val laneCanonical: String,
        val expectedRMultiple: Double,
        val stopDistancePct: Double,
        val executableSpreadBps: Int,
        val liquidityUsd: Double,
        val hydrationState: String,       // LIVE_READY / HYDRATING / PROBATION / SHADOW
        val sampleSizeCanonical: Int,
        val governorState: String,
        val foundationPolicyVerdict: String,
        val foundationReasons: List<String>,
    ) {
        fun toCompactLine(): String = buildString {
            append("mint=").append(mint.take(10)).append(' ')
            append("sym=").append(symbol).append(' ')
            append("laneReq=").append(laneRequested).append(' ')
            append("laneCanon=").append(laneCanonical).append(' ')
            append("expR=").append("%.2f".format(expectedRMultiple)).append(' ')
            append("stopPct=").append("%.2f".format(stopDistancePct)).append(' ')
            append("spreadBps=").append(executableSpreadBps).append(' ')
            append("liqUsd=").append("%.0f".format(liquidityUsd)).append(' ')
            append("hyd=").append(hydrationState).append(' ')
            append("nCanon=").append(sampleSizeCanonical).append(' ')
            append("gov=").append(governorState).append(' ')
            append("policy=").append(foundationPolicyVerdict).append(' ')
            append("policyReasons=").append(foundationReasons.joinToString("|").ifBlank { "none" })
        }
    }

    enum class Verdict { PASS, WARN, VETO }

    private val recent = ConcurrentHashMap<String, Record>()

    /**
     * Emit the pre-entry decision receipt. Called from the live entry
     * ticket assembler right before the buy lease is acquired.
     * Returns the classification; PASS is authoritative for this push.
     */
    fun emit(
        mint: String,
        symbol: String,
        laneRequested: String,
        laneCanonical: String,
        expectedRMultiple: Double,
        stopDistancePct: Double,
        executableSpreadBps: Int,
        liquidityUsd: Double,
        hydrationState: String,
        sampleSizeCanonical: Int,
        governorState: String,
    ): Record {
        val reasons = mutableListOf<String>()
        // Deterministic guardrails the operator says must be true BEFORE
        // a live trade is competent to fire. Failures are logged but not
        // enforced in this push (observation mode).
        if (expectedRMultiple <= 0.0) reasons += "EXP_R_NON_POSITIVE"
        if (stopDistancePct <= 0.0) reasons += "STOP_DISTANCE_UNKNOWN"
        if (executableSpreadBps <= 0) reasons += "SPREAD_UNKNOWN"
        if (liquidityUsd <= 0.0) reasons += "LIQUIDITY_UNKNOWN"
        if (hydrationState.isBlank()) reasons += "HYDRATION_STATE_UNKNOWN"

        val verdict = when {
            reasons.isEmpty() -> Verdict.PASS
            // Hydration or spread unknown is a soft warn — we've observed
            // enough hot pump.fun mints to know sometimes the quote lags.
            reasons.size <= 1 && (reasons.first() == "SPREAD_UNKNOWN" ||
                reasons.first() == "HYDRATION_STATE_UNKNOWN") -> Verdict.WARN
            else -> Verdict.VETO
        }
        val record = Record(
            emittedAtMs = System.currentTimeMillis(),
            mint = mint,
            symbol = symbol,
            laneRequested = laneRequested,
            laneCanonical = laneCanonical,
            expectedRMultiple = expectedRMultiple,
            stopDistancePct = stopDistancePct,
            executableSpreadBps = executableSpreadBps,
            liquidityUsd = liquidityUsd,
            hydrationState = hydrationState,
            sampleSizeCanonical = sampleSizeCanonical,
            governorState = governorState,
            foundationPolicyVerdict = verdict.name,
            foundationReasons = reasons,
        )
        recent[mint] = record
        try {
            ForensicLogger.lifecycle("PRE_ENTRY_DECISION_RECORD_6345", record.toCompactLine())
            when (verdict) {
                Verdict.PASS -> PipelineHealthCollector.labelInc("PRE_ENTRY_DECISION_PASS_6345")
                Verdict.WARN -> PipelineHealthCollector.labelInc("PRE_ENTRY_DECISION_WARN_6345")
                Verdict.VETO -> PipelineHealthCollector.labelInc("PRE_ENTRY_DECISION_VETO_6345")
            }
        } catch (_: Throwable) {}
        return record
    }

    /** Latest record for a mint (forensic replay surface). Returns null
     *  when no entry has been assessed since the process started. */
    fun latest(mint: String): Record? = recent[mint]

    fun activeCount(): Int = recent.size

    internal fun resetForTest() { recent.clear() }
}

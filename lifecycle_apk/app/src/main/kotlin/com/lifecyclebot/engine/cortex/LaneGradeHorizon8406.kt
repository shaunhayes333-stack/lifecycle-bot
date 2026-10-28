package com.lifecyclebot.engine.cortex

import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.truth.FieldManual7715

/**
 * V5.0.8406 §GRADE_THE_STRATEGY_AT_ITS_OWN_HORIZON.
 *
 * 5.0.8404 (547 s): Cortex graded 97 of 5,579 decisions, every lane on the 5-minute read ("THE read" in
 * ForwardReturnLabeler7731) net of a 6.7% round trip; information coefficient CORE -0.22, SHITCOIN -0.37, EXPRESS -0.35;
 * setups that MATCHED did worse than no setup (SHITCOIN -32.5% vs -12.6%). RETIRED_8383 had retired the accumulation
 * family (QUIET_ACCUMULATION, PRE_IGNITION_BASE, LAUNCH_CONTINUATION, BUYERS_DOMINATING, LOW_RUNUP_BASE ...) on the same
 * 5-minute evidence and kept the chase family (IGNITION_5M, NEW_HIGH_PUSH, BREAKOUT_RUNNER). A +50% runner mandate or a
 * +20% trend-pullback mandate does not resolve in 5 minutes; a chase entry sometimes pops in 5 minutes. The yardstick
 * retired the patient strategies and kept the losing ones.
 *
 * The rule, from the lane's own FieldManual7715 mandate: a lane whose expected gross move is at least
 * [HOUR_GRADED_MANDATE_PCT_8406] is graded on the hour slot (the 8337 hour revision owns its Cortex grade, setup books,
 * cells and miner; the 5-minute read stays the provisional/observed value), and a retirement measured on the 5-minute
 * read is not binding evidence against its accumulation/early setups. Range-reversion, yield and derivatives mandates
 * (TREASURY 12%, CRYPTO 12%, PERPS, CARRY) keep the 5-minute read. Hard safety, exits and sizing untouched.
 */
object LaneGradeHorizon8406 {
    const val HOUR_GRADED_MANDATE_PCT_8406 = 20.0

    /** Setups whose 5-minute retirement is suspended on an hour-graded lane: patience cannot be measured in 5 minutes. */
    val PATIENT_FAMILY_8406: Set<String> = setOf(
        "QUIET_ACCUMULATION", "BUYERS_DOMINATING", "PRE_IGNITION_BASE", "LAUNCH_CONTINUATION", "LOW_RUNUP_BASE",
        "VERIFIED_LAUNCH", "HIGHER_LOW_PULLBACK", "HIGHER_LOW_CONTINUATION", "FLAG_CONTINUATION", "MICRO_FLAG",
        "LIVESTREAM_LAUNCH", "GRADUATION_APPROACH",
    )

    /** Pure: the mandate's expected gross move decides the grading horizon. */
    fun hourGradedByMandate8406(expectedGrossPct: Double): Boolean =
        expectedGrossPct.isFinite() && expectedGrossPct >= HOUR_GRADED_MANDATE_PCT_8406

    fun hourGraded8406(lane: String?): Boolean {
        val l = lane?.trim()?.uppercase().orEmpty()
        if (l.isBlank()) return false
        val m = try { FieldManual7715.mandateFor(l) } catch (_: Throwable) { return false }
        return hourGradedByMandate8406(m.expectedGrossPct)
    }

    /** A 5-minute retirement of a patient setup on an hour-graded lane is suspended (counted per lane). */
    fun retirementSuspended8406(lane: String, setup: String): Boolean {
        if (setup !in PATIENT_FAMILY_8406 || !hourGraded8406(lane)) return false
        try { PipelineHealthCollector.labelInc("RETIREMENT_SUSPENDED_WRONG_HORIZON_8406_${lane.trim().uppercase()}") } catch (_: Throwable) {}
        return true
    }
}

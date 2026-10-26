package com.lifecyclebot.engine.cortex

/**
 * V5.0.8395 (R4) — ONE predictive-proof status shared by the positive door, C3 / C6 negatives and C5 slot saving.
 *
 * The positive STRONG door already required the walk-forward test with matured/missing/coverage (CortexAuthority8366).
 * The negatives and slot saving read raw scoreboard cohorts with no coverage at all: an incomplete, partly-unresolved
 * record could reserve the last slot for STRONG reads that were never executable. Incomplete or mixed evidence is
 * UNKNOWN: it never authorises a BUY and never reserves capital; a complete-scope negative still binds.
 */
object CortexPredictiveProof8395 {
    enum class Status { VALID, UNKNOWN }

    data class Proof(
        val lane: String,
        val generation: Long,
        val oosN: Int,
        val matured: Int,
        val missing: Int,
        val strongCoverage: Double,
        val restCoverage: Double,
        val incomplete: Boolean,
    ) {
        val status: Status get() = status(this)
    }

    fun of(lane: String, generation: Long, r: CortexAuthority8366.Result8366): Proof =
        Proof(lane, generation, r.oosN, r.matured8392, r.missing8392, r.strongCoverage8392, r.restCoverage8392,
            r.verdict == CortexAuthority8366.Verdict8366.INCOMPLETE_OUTCOMES)

    /** Pure: complete coverage on both sides, no INCOMPLETE verdict, and at least one matured out-of-sample row. */
    fun status(p: Proof): Status = if (!p.incomplete && p.oosN > 0 && p.matured > 0 &&
        p.strongCoverage.isFinite() && p.restCoverage.isFinite() &&
        minOf(p.strongCoverage, p.restCoverage) >= CortexWalkForward8144.MIN_RESOLVED_SHARE_8144) Status.VALID else Status.UNKNOWN

    /** Pure: a slot may be saved for STRONG only on a VALID proof AND an eligible, executable STRONG read in the lane now. */
    fun slotReservable(proof: Status, eligibleStrongNow: Boolean): Boolean = proof == Status.VALID && eligibleStrongNow

}

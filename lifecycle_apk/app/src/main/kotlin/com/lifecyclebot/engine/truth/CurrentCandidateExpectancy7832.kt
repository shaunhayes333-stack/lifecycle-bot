package com.lifecyclebot.engine.truth

/**
 * V5.0.7832 — current-candidate positive-expectancy fallback.
 *
 * Used when the predictive oracle has no numeric admission authority AND there
 * is no resolved forward/aggregate cohort. Missing history is never silently
 * converted to EV=0 and then used to deny the entire live universe.
 *
 * This is not a replacement oracle. It reads this candidate only and requires
 * positive net expectancy after learned execution slippage. Explicit WAIT /
 * REJECT / weak C-D-F setup quality can never be promoted by this fallback.
 */
object CurrentCandidateExpectancy7832 {
    data class Estimate(
        val pWin: Double,
        val grossEdgePct: Double,
        val executionCostPct: Double,
        val netExpectancyPct: Double,
        val positive: Boolean,
        val reason: String,
    )

    // An advisory verdict cannot acquire a veto simply by exporting its number.
    // Candidate-specific measured losses still bind, even before oracle proof.
    fun oracleNumericAuthority7838(
        available: Boolean, degenerate: Boolean, proven: Boolean, evidencedRefuse: Boolean,
    ): Boolean = available && !degenerate && (proven || evidencedRefuse)

    /** An excluded oracle cannot regain positive or negative authority through the fallback's probability input. */
    internal fun oracleProbability8871(numericAuthority: Boolean, pWin: Double?): Double? =
        pWin?.takeIf { numericAuthority && it.isFinite() && it in 0.0..1.0 }

    internal enum class Basis8871 { AUTHORITATIVE_ORACLE, TERMINAL_FORWARD, CURRENT_CANDIDATE_PRIOR, UNAVAILABLE }
    internal data class AdmissionEstimate8871(val basis: Basis8871, val netPct: Double?)

    /** The existing precedence, with its evidence source retained rather than calling every refusal a measured loss. */
    internal fun admissionEstimate8871(oracleNumeric: Boolean, oracleNetPct: Double?,
                                      forwardResolved: Boolean, forwardNetPct: Double?,
                                      current: Estimate?): AdmissionEstimate8871 = when {
        oracleNumeric -> AdmissionEstimate8871(Basis8871.AUTHORITATIVE_ORACLE, oracleNetPct)
        forwardResolved -> AdmissionEstimate8871(Basis8871.TERMINAL_FORWARD, forwardNetPct)
        current != null -> AdmissionEstimate8871(Basis8871.CURRENT_CANDIDATE_PRIOR, admissionNetPct7838(current))
        else -> AdmissionEstimate8871(Basis8871.UNAVAILABLE, null)
    }

    // Preserve the structural verdict as well as the arithmetic sign. A strong
    // score cannot turn an explicit WAIT or weak setup into live permission.
    fun admissionNetPct7838(estimate: Estimate?): Double? = estimate?.let {
        if (it.positive && it.netExpectancyPct.isFinite()) it.netExpectancyPct else Double.NaN
    }

    fun estimate(
        score: Int,
        candidateConfidence: Double,
        quality: String,
        edgePhase: String,
        oraclePWin: Double?,
        expectedSlipPct: Double,
    ): Estimate {
        val q = quality.trim().uppercase()
        val phase = edgePhase.trim().uppercase()
        val weakQuality = q in setOf("C", "D", "F")
        val noEntryPhase = listOf("WAIT", "REJECT", "NO_BUY", "BLOCK").any { phase.contains(it) }
        val qualityP = when (q) {
            "A+" -> 0.90
            "A" -> 0.82
            "B+" -> 0.74
            "B" -> 0.66
            "C" -> 0.42
            "D", "F" -> 0.20
            else -> 0.50
        }
        val scoreP = score.coerceIn(0, 100) / 100.0
        val confP = candidateConfidence.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.50
        val oracleP = oraclePWin?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: 0.50
        val pWin = (scoreP * 0.45 + confP * 0.30 + qualityP * 0.15 + oracleP * 0.10).coerceIn(0.0, 1.0)
        val gross = ((pWin - 0.50) * 20.0).coerceIn(-10.0, 10.0)
        val cost = expectedSlipPct.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(0.0, 10.0) ?: 0.0
        val net = gross - cost
        val positive = !weakQuality && !noEntryPhase && net > 0.0
        val reason = when {
            weakQuality -> "WEAK_SETUP_QUALITY_$q"
            noEntryPhase -> "NON_ENTRY_PHASE_${phase.take(24)}"
            net <= 0.0 -> "CURRENT_CANDIDATE_EV_NOT_POSITIVE"
            else -> "CURRENT_CANDIDATE_POSITIVE_EV"
        }
        return Estimate(pWin, gross, cost, net, positive, reason)
    }
}

package com.lifecyclebot.engine.truth

/**
 * V5.0.8418 — spot-scale leverage on the sealed ticket.
 *
 * MarketsLiveExecutor opens a leveraged NON-crypto (tokenized stock / metal / forex /
 * commodity) trade as a SPOT buy of margin × leverage, and records that principal on the
 * canonical position. The ticket used to carry only the margin, so requested (ticket),
 * submitted (the swap) and recorded (canonical principal) differed by the leverage factor.
 * The seal now records the factor ([EVIDENCE_KEY] → ExecutionIntent.spotScaleLeverage8418),
 * and the executor checks ticket notional == submitted == recorded before any SOL moves.
 */
object SpotScale8418 {
    const val EVIDENCE_KEY = "spotScaleLeverage8418"

    /** The SPOT multiple the executor will apply: the leverage for non-crypto leveraged trades, else 1. */
    fun factor(isCrypto: Boolean, leverage: Double): Double =
        if (!isCrypto && leverage.isFinite() && leverage > 1.0) leverage else 1.0

    /** The factor a seal reads from candidate evidence; absent or invalid = 1 (no scaling). */
    fun fromEvidence(evidence: Map<String, String>): Double {
        val v = evidence[EVIDENCE_KEY]?.toDoubleOrNull() ?: return 1.0
        return if (v.isFinite() && v >= 1.0) v else 1.0
    }

    /** The evidence entry a caller adds to its candidate. */
    fun evidence(isCrypto: Boolean, leverage: Double): Pair<String, String> =
        EVIDENCE_KEY to factor(isCrypto, leverage).toString()
}

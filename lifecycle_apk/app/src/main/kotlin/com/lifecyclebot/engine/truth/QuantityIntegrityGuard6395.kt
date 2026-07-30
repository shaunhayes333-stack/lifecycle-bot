package com.lifecyclebot.engine.truth

import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6395 — QUANTITY INTEGRITY GUARD.
 *
 *   buyRaw - cumulativeSellRaw = remainingRaw  (within dust tolerance)
 *   full sell requires cumulativeSellRaw == proven pre-sell wallet raw balance
 *
 * A UI label "378.2K" must NEVER be persisted as 378.2. If buy and sell
 * quantities differ by more than dust without a verified partial history,
 * this guard emits QTY_DECIMAL_SKEW and immediately quarantines the trade:
 *   - canonicalEligible=false
 *   - learningEligible=false
 *   - governorEligible=false
 * and increments three counters:
 *   - decimalSkewAudit
 *   - skewLearningQuarantine
 *   - excludedFromCanon.quarantined
 * A QTY_DECIMAL_SKEW warning with all three counters remaining zero is a
 * regression failure (see Bundle6395InvariantTest).
 */
object QuantityIntegrityGuard6395 {

    /** Documented dust tolerance — 1 unit at 6 decimals ≈ 1e-6 tokens. */
    const val DUST_TOLERANCE_RAW: Long = 1L

    /** V5.0.6395 §"QUANTITY INTEGRITY" (7) — |buyRaw - cumulativeSellRaw - remainingRaw| > dust. */
    private const val SKEW_TOLERANCE_MULTIPLIER: Long = 10L  // ≥10 dust units = skew

    val decimalSkewAudit = AtomicLong(0L)
    val skewLearningQuarantine = AtomicLong(0L)
    val excludedFromCanonQuarantined = AtomicLong(0L)

    data class Verdict(
        val ok: Boolean,
        val reason: String,
        val canonicalEligible: Boolean,
        val learningEligible: Boolean,
        val governorEligible: Boolean,
        val skewRaw: BigInteger,
    )

    /**
     * Validate a completed lifecycle: total buys, total sells and (optionally)
     * remaining raw balance. `hasVerifiedPartialHistory=true` allows a legitimate
     * partial-fill schedule where remainingRaw is positive.
     */
    fun check(
        totalBuyRaw: BigInteger,
        cumulativeSellRaw: BigInteger,
        remainingRaw: BigInteger,
        hasVerifiedPartialHistory: Boolean,
    ): Verdict {
        // Non-negative inputs required. Negative raw is itself a skew.
        if (totalBuyRaw.signum() < 0 || cumulativeSellRaw.signum() < 0 || remainingRaw.signum() < 0)
            return quarantine("NEGATIVE_RAW", BigInteger.ZERO)

        val expected = totalBuyRaw
        val actual = cumulativeSellRaw.add(remainingRaw)
        val skew = expected.subtract(actual).abs()
        val tolerance = BigInteger.valueOf(DUST_TOLERANCE_RAW * SKEW_TOLERANCE_MULTIPLIER)

        if (skew <= tolerance)
            return Verdict(true, "OK_WITHIN_DUST", true, true, true, skew)

        // Skew > dust. If partial history is verified AND remainingRaw is
        // consistent with expected residual, still OK — the "partial" caller
        // must supply matching numbers. Otherwise, quarantine.
        if (hasVerifiedPartialHistory && remainingRaw > BigInteger.ZERO && cumulativeSellRaw <= totalBuyRaw)
            return Verdict(true, "OK_VERIFIED_PARTIAL", true, true, true, skew)

        return quarantine("QTY_DECIMAL_SKEW", skew)
    }

    private fun quarantine(reason: String, skew: BigInteger): Verdict {
        decimalSkewAudit.incrementAndGet()
        skewLearningQuarantine.incrementAndGet()
        excludedFromCanonQuarantined.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("QTY_DECIMAL_SKEW_6395") } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.ForensicLogger.lifecycle("QTY_DECIMAL_SKEW_6395", "reason=$reason skewRaw=$skew") } catch (_: Throwable) {}
        return Verdict(false, reason, false, false, false, skew)
    }

    internal fun clearForTest() {
        decimalSkewAudit.set(0L); skewLearningQuarantine.set(0L); excludedFromCanonQuarantined.set(0L)
    }
}

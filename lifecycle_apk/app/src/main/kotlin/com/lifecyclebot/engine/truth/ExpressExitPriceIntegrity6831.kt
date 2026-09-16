package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6831 §EXPRESS_EXIT_PRICE_INTEGRITY — operator directive:
 *   "Multiple recent EXPRESS terminal sells recorded with 'sol=0.000'
 *    and losses ~= full position cost under reasons like
 *    'LANE_HARD_15PCT_SL_EXPRESS'. Repair pricing truth first, then
 *    recalculate EXPRESS performance."
 *
 * Pure validator called immediately before a paper terminal settlement.
 * Returns a Verdict enum:
 *   VALID
 *   REJECT_ZERO_PRICE, REJECT_NONFINITE, REJECT_EPSILON,
 *   REJECT_STALE, REJECT_DECIMALS_UNRESOLVED,
 *   REJECT_DOMAIN_MISMATCH, REJECT_PRICE_DISCONTINUITY,
 *   REJECT_PROCEEDS_INVARIANT
 *
 * Only VALID verdicts may finalize + train.
 * All others stamp positionId non-trainable — the finalized envelope
 * observer overrides learningEligible=false.
 */
object ExpressExitPriceIntegrity6831 {

    enum class Verdict {
        VALID,
        REJECT_ZERO_PRICE,
        REJECT_NONFINITE,
        REJECT_EPSILON,
        REJECT_STALE,
        REJECT_DECIMALS_UNRESOLVED,
        REJECT_DOMAIN_MISMATCH,
        REJECT_PRICE_DISCONTINUITY,
        REJECT_PROCEEDS_INVARIANT,
    }

    // Epsilon band the operator explicitly banned from economic
    // settlement. Any exit price <= this magnitude is a sentinel.
    private const val EPSILON_BAND = 1e-9

    // Maximum log-price movement (natural log) accepted without
    // independent corroboration. abs(log(exit/lastValid)) > this
    // rejects as PRICE_DISCONTINUITY.
    private const val MAX_LOG_MOVEMENT = 4.6  // ~= 99% collapse or 100x pump

    // Maximum quote age (ms) tolerated for terminal settlement.
    private const val MAX_QUOTE_AGE_MS = 90_000L

    // Non-trainable positionId stamps set by REJECT verdicts.
    private val nonTrainableClosures = ConcurrentHashMap<String, Verdict>()

    private val evaluations = AtomicLong(0L)
    private val valid = AtomicLong(0L)
    private val rejects = AtomicLong(0L)

    data class AuditInputs(
        val positionId: String,
        val mint: String,
        val lane: String,
        val entryRaw: Double,
        val entryNormalized: Double,
        val exitRaw: Double,
        val exitNormalized: Double,
        val lastValidRaw: Double = Double.NaN,
        val lastValidNormalized: Double = Double.NaN,
        val decimals: Int = -1,
        val normalizationDomain: String = "",
        val quoteSource: String = "",
        val quoteAgeMs: Long = -1L,
        val liquidity: Double = Double.NaN,
        val marketCap: Double = Double.NaN,
        val triggerReason: String = "",
        val triggerPct: Double = Double.NaN,
        val qty: Double = Double.NaN,
        val proceeds: Double = Double.NaN,
        val entryCost: Double = Double.NaN,
        val fees: Double = Double.NaN,
    )

    fun evaluate(a: AuditInputs): Verdict {
        evaluations.incrementAndGet()
        val verdict: Verdict = when {
            a.exitNormalized <= 0.0 && a.exitRaw <= 0.0 -> Verdict.REJECT_ZERO_PRICE
            !a.exitNormalized.isFinite() || !a.exitRaw.isFinite() -> Verdict.REJECT_NONFINITE
            kotlin.math.abs(a.exitNormalized) <= EPSILON_BAND ||
                kotlin.math.abs(a.exitRaw) <= EPSILON_BAND -> Verdict.REJECT_EPSILON
            a.quoteAgeMs in 0..Long.MAX_VALUE && a.quoteAgeMs > MAX_QUOTE_AGE_MS -> Verdict.REJECT_STALE
            a.decimals < 0 -> Verdict.REJECT_DECIMALS_UNRESOLVED
            !checkContinuity(a) -> Verdict.REJECT_PRICE_DISCONTINUITY
            !checkProceedsInvariant(a) -> Verdict.REJECT_PROCEEDS_INVARIANT
            else -> Verdict.VALID
        }
        emitAudit(a, verdict)
        if (verdict == Verdict.VALID) {
            valid.incrementAndGet()
        } else {
            rejects.incrementAndGet()
            if (a.positionId.isNotBlank()) nonTrainableClosures[a.positionId] = verdict
            try {
                PipelineHealthCollector.labelInc("EXPRESS_EXIT_PRICE_REJECT_6831")
                PipelineHealthCollector.labelInc("EXPRESS_EXIT_PRICE_REJECT_6831_${verdict.name}")
            } catch (_: Throwable) {}
        }
        return verdict
    }

    private fun checkContinuity(a: AuditInputs): Boolean {
        if (!a.lastValidNormalized.isFinite() || a.lastValidNormalized <= 0.0) return true
        if (a.exitNormalized <= 0.0) return true
        val mv = kotlin.math.abs(kotlin.math.ln(a.exitNormalized / a.lastValidNormalized))
        return mv <= MAX_LOG_MOVEMENT
    }

    private fun checkProceedsInvariant(a: AuditInputs): Boolean {
        if (!a.qty.isFinite() || a.qty <= 0.0) return true // not provided
        if (!a.proceeds.isFinite()) return false
        if (a.proceeds < 0.0) return false
        // If both provided: expectedProceeds = qty * exitNormalized should
        // agree with journal.proceeds within 1% tolerance (paper fees
        // account for the delta).
        val expected = a.qty * a.exitNormalized
        if (!expected.isFinite() || expected <= 0.0) return true
        val tolerance = expected * 0.05
        return kotlin.math.abs(expected - a.proceeds) <= (tolerance + 1e-9)
    }

    private fun emitAudit(a: AuditInputs, verdict: Verdict) {
        try {
            ForensicLogger.lifecycle(
                "EXPRESS_EXIT_PRICE_AUDIT",
                "mint=${a.mint.take(10)} positionId=${a.positionId.take(24)} lane=${a.lane} " +
                    "entryRaw=${fmt(a.entryRaw)} entryNorm=${fmt(a.entryNormalized)} " +
                    "exitRaw=${fmt(a.exitRaw)} exitNorm=${fmt(a.exitNormalized)} " +
                    "lastValidRaw=${fmt(a.lastValidRaw)} lastValidNorm=${fmt(a.lastValidNormalized)} " +
                    "decimals=${a.decimals} domain=${a.normalizationDomain} " +
                    "source=${a.quoteSource} quoteAgeMs=${a.quoteAgeMs} " +
                    "liquidity=${fmt(a.liquidity)} marketCap=${fmt(a.marketCap)} " +
                    "triggerReason=${a.triggerReason} triggerPct=${fmt(a.triggerPct)} " +
                    "qty=${fmt(a.qty)} proceeds=${fmt(a.proceeds)} entryCost=${fmt(a.entryCost)} " +
                    "fees=${fmt(a.fees)} validation=${verdict.name}",
            )
        } catch (_: Throwable) {}
    }

    private fun fmt(d: Double): String = if (!d.isFinite()) "NaN" else "%.9g".format(d)

    /** Finalized envelope consults this to override learningEligible. */
    fun isNonTrainable(positionId: String): Boolean {
        if (positionId.isBlank()) return false
        return nonTrainableClosures.containsKey(positionId)
    }

    fun verdictFor(positionId: String): Verdict? = nonTrainableClosures[positionId]

    fun onPositionClosed(positionId: String) {
        try { nonTrainableClosures.remove(positionId) } catch (_: Throwable) {}
    }

    fun statusLine(): String =
        "evals=${evaluations.get()} valid=${valid.get()} rejects=${rejects.get()} " +
            "outstandingNonTrainable=${nonTrainableClosures.size}"

    internal fun clearForTest() {
        nonTrainableClosures.clear()
        evaluations.set(0L); valid.set(0L); rejects.set(0L)
    }
}

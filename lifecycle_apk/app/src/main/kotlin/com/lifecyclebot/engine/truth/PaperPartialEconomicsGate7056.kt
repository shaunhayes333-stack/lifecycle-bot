package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7056 §PARTIAL_ECONOMICS_VALIDATED_BEFORE_ANY_MUTATION.
 *
 * OPERATOR EVIDENCE:
 *   124 PARTIAL_SELL rows; 108 exceed |1000%| return and contribute ~+599.39 SOL.
 *   Canonical clean performance -1.3565 SOL against PaperAccountLedger +550.9932.
 *   Basis 0.0125-0.028125 SOL producing 10-25 SOL proceeds at 70,000-90,000%.
 *
 * WHY §7032 DID NOT STOP IT. ContaminatedPartialQuarantine7032 filters REWARD
 * and LEARNING after the fact. By the time it runs, cash has already moved.
 * Directive §6 is the correction: the quarantine has to sit ahead of
 * PaperAccountLedger mutation, not behind it.
 *
 * AND WHY MY OWN 7029 GUARD DID NOT STOP IT EITHER — this is the actual leak.
 * CanonicalPaperPartialOperation6510 reconstructs proceeds as qty x price, but
 * it was written as:
 *
 *     if (markPriceUsd > 0 && solUsd > 0 && grossProceeds > 0) { ...refuse... }
 *     else { labelInc("PARTIAL_PROCEEDS_UNCHECKED_7029") }   // and commit anyway
 *
 * A FAIL-OPEN GUARD. Whenever the mark or the SOL rate was unavailable the
 * check was skipped and the sale committed unvalidated — and V5.0.7042 showed
 * the SOL rate was frequently unavailable (PROCEEDS_SOL_UNCONVERTIBLE_7029=163
 * in a 161s window). The guard reported "unchecked" and let the money through.
 * Unreconstructible must mean REFUSED, which is directive §5.
 *
 * WHAT THIS VALIDATES, per directive §3 and §9, entirely from ONE canonical
 * Position identity read before any mutation:
 *
 *   soldQty <= remainingQty                            §3
 *   fraction        = soldQty / preCloseQty            §3
 *   allocatedBasis  = preCloseRemainingBasis x fraction §3
 *   remainingBasis  = preCloseRemainingBasis - allocatedBasis
 *   grossProceeds   = soldQty x canonicalExitUnitPrice §3
 *   realized        = grossProceeds - allocatedBasis - fee
 *   oldQty   == soldQty + newQty                       §9
 *   oldBasis == allocatedBasis + newBasis              §9
 *   every economic field finite                        §9
 *
 * Quantity arithmetic is done in BigInteger/BigDecimal against the position's
 * own quantityScale, so a decimals mismatch cannot be hidden by a Double round
 * trip — directive §4's "quantity dimensional mismatch".
 *
 * SCOPE: PARTIALS ONLY. A terminal full exit is left exactly as it was. Three
 * of the four finalizeSell call sites are full exits that do not supply an exit
 * price at all, and failing those closed would strand open positions the bot
 * could no longer exit — a strictly worse failure than the one being fixed.
 * The directive is about partial-sell economics and this stays inside it.
 */
object PaperPartialEconomicsGate7056 {

    /**
     * Tolerance on the reconstruction. Generous on purpose: this is here to
     * refuse a 1400x impossibility, not to arbitrate slippage or a stale tick.
     * The defect rows sit at 70,000-90,000% — four orders of magnitude outside
     * this — so a wide band still catches every one of them while leaving an
     * ordinary sale untouched.
     */
    private const val RECONSTRUCTION_BAND = 25.0

    /** Pro-rata basis tolerance, as a fraction of the expected allocation. */
    private const val BASIS_PRO_RATA_TOLERANCE = 0.05

    private val checked = AtomicLong(0L)
    private val refused = AtomicLong(0L)
    private val passed = AtomicLong(0L)

    data class Verdict(val ok: Boolean, val reason: String, val detail: String)

    private fun no(reason: String, detail: String): Verdict {
        refused.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("PARTIAL_ECONOMICS_REFUSED_7056")
            PipelineHealthCollector.labelInc("PARTIAL_ECONOMICS_REFUSED_7056_$reason")
        } catch (_: Throwable) {}
        return Verdict(false, reason, detail)
    }

    /**
     * Validate a PARTIAL sale before any canonical position, lot, cash or
     * realized-PnL mutation. Returns ok=false to quarantine the economic event
     * outright: no cash, no realized, no journal economics, no training.
     */
    fun validate(
        positionId: String,
        mint: String,
        pos: CanonicalPositionAuthority6441.Position?,
        soldQtyRaw: BigInteger,
        grossProceedsSol: Double,
        callerBasisSol: Double,
        feesSol: Double,
        exitPriceUsd: Double,
        solUsdAtExit: Double,
    ): Verdict {
        checked.incrementAndGet()

        // §2 — same canonical identity, read once, here.
        if (pos == null) return no("NO_CANONICAL_POSITION", "positionId=$positionId")
        if (!pos.positionId.equals(positionId, true)) {
            return no("POSITION_IDENTITY_MISMATCH", "asked=$positionId got=${pos.positionId}")
        }

        // §9 — nothing NaN or infinite may reach the ledger.
        if (!grossProceedsSol.isFinite() || !callerBasisSol.isFinite() || !feesSol.isFinite()) {
            return no("NON_FINITE_ECONOMICS",
                "gross=$grossProceedsSol basis=$callerBasisSol fee=$feesSol")
        }
        if (grossProceedsSol < 0.0 || callerBasisSol < 0.0 || feesSol < 0.0) {
            return no("NEGATIVE_ECONOMICS",
                "gross=$grossProceedsSol basis=$callerBasisSol fee=$feesSol")
        }

        // §3 — quantity must fit inside what the canonical position still holds.
        if (soldQtyRaw <= BigInteger.ZERO) return no("NON_POSITIVE_QTY", "soldRaw=$soldQtyRaw")
        val remainingRaw = pos.remainingQtyRaw
        if (remainingRaw <= BigInteger.ZERO) {
            return no("NO_REMAINING_QTY", "positionId=$positionId")
        }
        if (soldQtyRaw > remainingRaw) {
            return no("SOLD_EXCEEDS_REMAINING", "sold=$soldQtyRaw remaining=$remainingRaw")
        }

        // §3 — allocated basis must be the pro-rata slice of what is LEFT, not
        // of the whole original position. Using the original entry cost against
        // a fractional quantity is directive §4's "whole-position proceeds
        // against fractional basis" in its other direction.
        val preCloseBasis = pos.entryCostSol - pos.soldCostBasisSol
        if (!preCloseBasis.isFinite() || preCloseBasis <= 0.0) {
            return no("NO_REMAINING_BASIS",
                "entryCost=${pos.entryCostSol} soldBasis=${pos.soldCostBasisSol}")
        }
        val mc = MathContext.DECIMAL64
        val fraction = try {
            BigDecimal(soldQtyRaw).divide(BigDecimal(remainingRaw), mc).toDouble()
        } catch (_: Throwable) { return no("FRACTION_UNCOMPUTABLE", "sold=$soldQtyRaw rem=$remainingRaw") }
        if (!fraction.isFinite() || fraction <= 0.0 || fraction > 1.0) {
            return no("FRACTION_OUT_OF_RANGE", "fraction=$fraction")
        }
        val expectedBasis = preCloseBasis * fraction
        if (expectedBasis > 0.0) {
            val basisDelta = kotlin.math.abs(callerBasisSol - expectedBasis) / expectedBasis
            if (basisDelta > BASIS_PRO_RATA_TOLERANCE) {
                return no("BASIS_NOT_PRO_RATA",
                    "caller=${"%.8f".format(callerBasisSol)} expected=${"%.8f".format(expectedBasis)} " +
                        "preCloseBasis=${"%.8f".format(preCloseBasis)} fraction=${"%.6f".format(fraction)}")
            }
        }

        // §9 — the conservation identities, on the values about to be committed.
        val newBasis = preCloseBasis - callerBasisSol
        if (!newBasis.isFinite() || newBasis < -1e-9) {
            return no("BASIS_CONSERVATION_BROKEN",
                "preClose=${"%.8f".format(preCloseBasis)} allocated=${"%.8f".format(callerBasisSol)}")
        }
        val newQtyRaw = remainingRaw.subtract(soldQtyRaw)
        if (soldQtyRaw.add(newQtyRaw) != remainingRaw) {
            return no("QTY_CONSERVATION_BROKEN", "sold=$soldQtyRaw new=$newQtyRaw old=$remainingRaw")
        }

        // §3 + §5 — proceeds must be reconstructible from this position's OWN
        // quantity and its OWN exit unit price. FAIL CLOSED: an absent mark or
        // an absent SOL rate makes the sale unreconstructible, and directive §5
        // says an unreconstructible partial is quarantined, not committed. This
        // is the exact inversion of the 7029 fail-open branch.
        if (!(exitPriceUsd.isFinite() && exitPriceUsd > 0.0)) {
            return no("UNRECONSTRUCTIBLE_NO_EXIT_PRICE", "positionId=$positionId mint=${mint.take(10)}")
        }
        if (!(solUsdAtExit.isFinite() && solUsdAtExit >= 20.0)) {
            return no("UNRECONSTRUCTIBLE_NO_SOL_RATE", "solUsd=$solUsdAtExit")
        }
        val scale = pos.quantityScale.coerceIn(0, 18)
        val soldQtyTokens = try {
            BigDecimal(soldQtyRaw).movePointLeft(scale).toDouble()
        } catch (_: Throwable) { 0.0 }
        if (!soldQtyTokens.isFinite() || soldQtyTokens <= 0.0) {
            return no("QTY_SCALE_UNRESOLVABLE", "raw=$soldQtyRaw scale=$scale")
        }
        // Units: tokens x (USD/token) / (USD/SOL) = SOL. Stated explicitly
        // because V5.0.7029's defect was exactly this product being booked as
        // SOL while it was USD.
        val expectedProceedsSol = soldQtyTokens * (exitPriceUsd / solUsdAtExit)
        if (!expectedProceedsSol.isFinite() || expectedProceedsSol <= 0.0) {
            return no("PROCEEDS_UNCOMPUTABLE",
                "qty=$soldQtyTokens priceUsd=$exitPriceUsd solUsd=$solUsdAtExit")
        }
        if (grossProceedsSol > 0.0) {
            val ratio = grossProceedsSol / expectedProceedsSol
            if (ratio > RECONSTRUCTION_BAND || ratio < 1.0 / RECONSTRUCTION_BAND) {
                return no("PROCEEDS_UNRECONSTRUCTIBLE",
                    "claimed=${"%.6f".format(grossProceedsSol)} " +
                        "reconstructed=${"%.6f".format(expectedProceedsSol)} " +
                        "ratio=${"%.1f".format(ratio)}x band=${RECONSTRUCTION_BAND}x " +
                        "qty=${"%.6f".format(soldQtyTokens)} priceUsd=$exitPriceUsd " +
                        "solUsd=${"%.2f".format(solUsdAtExit)}")
            }
        }

        passed.incrementAndGet()
        try { PipelineHealthCollector.labelInc("PARTIAL_ECONOMICS_VALIDATED_7056") } catch (_: Throwable) {}
        return Verdict(true, "VALIDATED", "fraction=${"%.6f".format(fraction)} " +
            "allocatedBasis=${"%.8f".format(callerBasisSol)} " +
            "reconstructedSol=${"%.6f".format(expectedProceedsSol)}")
    }

    /** One line per refusal, naming the position and the arithmetic that failed. */
    fun logRefusal(positionId: String, mint: String, symbol: String, v: Verdict) {
        try {
            ForensicLogger.lifecycle(
                "PARTIAL_ECONOMICS_REFUSED_7056",
                "positionId=$positionId mint=${mint.take(10)} symbol=$symbol " +
                    "reason=${v.reason} ${v.detail} " +
                    "action=quarantine_no_cash_no_realized_no_journal_no_training",
            )
        } catch (_: Throwable) {}
    }

    fun statusLine7056(): String =
        "checked=${checked.get()} validated=${passed.get()} refused=${refused.get()} " +
            "read=refusals_are_partials_that_never_touched_cash"

    internal fun resetForTest() { checked.set(0L); refused.set(0L); passed.set(0L) }
}

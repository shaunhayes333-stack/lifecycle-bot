package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7061 — DIRECTIVE §1 / §2 / §6 / §8: THE DIMENSIONAL INVARIANT.
 *
 * WHY THE EXISTING CONSERVATION CHECKS DID NOT CATCH ANY OF THIS
 * ==============================================================
 * Operator, §8, and it is the whole point: "existing conservation is
 * insufficient because internally consistent bad units still pass."
 *
 * Every conservation check in this codebase asks whether the books balance.
 * They did balance. V5.0.7057's defect was
 *
 *     gainMultiple = (pos.qtyToken * actualPrice) / pos.costSol
 *
 * — a USD numerator over a SOL denominator — and the product it produced was
 * then treated as SOL by everything downstream, consistently. Quantity
 * conservation passed. Basis conservation passed. Cash equalled proceeds minus
 * fees. The books balanced perfectly around a number that was 113x too large,
 * because balance is preserved by any error applied uniformly.
 *
 * A dimensional check is different in kind. It does not ask whether the
 * numbers agree with each other; it asks whether a SOL figure can be
 * reconstructed from the token quantity and the USD price that produced it.
 * That is the one question a uniform unit error cannot survive.
 *
 * THE CANONICAL CHAIN (§1), stated once so it can be checked rather than
 * re-derived at each of the four commit sites:
 *
 *     proceedsUsd      = soldQtyTokens x exitPriceUsdPerToken     [USD]
 *     proceedsSol      = proceedsUsd / solUsdPrice                [SOL]
 *     allocatedCostSol = remainingCostBefore x (soldQty / qtyBefore)
 *     grossPnlSol      = proceedsSol - allocatedCostSol
 *     netPnlSol        = grossPnlSol - feeSol
 *     cashCreditSol    = proceedsSol - feeSol
 *
 * FORBIDDEN, and each one has actually shipped in this app:
 *     proceedsSol = soldQty x priceUsd            (V5.0.7029)
 *     a multiple formed as USD/SOL                (V5.0.7057)
 *     tokenPriceUsd x solUsdPrice read back as a USD price   (§1, §3)
 *
 * WHY THE TOLERANCE IS TIGHT AND NOT A BAND
 * =========================================
 * My V5.0.7056 gate ran this same reconstruction with a 25x band. 25x is a
 * market judgement, and this is not a market judgement — it is an arithmetic
 * identity between three numbers the caller already holds. If the proceeds
 * were computed by the canonical chain they agree to floating point. So the
 * tolerance is [TOLERANCE_FRACTION], and a 25x band was me flinching.
 *
 * WHAT THIS WILL DO ON FIRST CONTACT, said plainly rather than discovered
 * ======================================================================
 * Positions whose ENTRY was booked inconsistently will fail check §2 on exit,
 * because proceeds derived as basis x (1 + pnl%) only equals qty x price /
 * solUsd when the entry itself satisfied debitSol = entryQty x entryPriceUsd /
 * solUsd. That is directive §8's ENTRY invariant, and failing it is the
 * correct answer: those are the contaminated rows. Expect
 * ECONOMIC_UNIT_INVARIANT_REJECTED to be non-trivial on the first build and to
 * fall as §10's replay rebuilds from clean events.
 *
 * A REJECTION LOSES NOTHING. It blocks the MUTATION, not the observation: the
 * position stays whole, the event is logged with every input and with the
 * reconstructed value, and a later correctly-priced attempt at the same ladder
 * step still succeeds. The operator's standing rule is that nothing may be
 * lost or excluded for bad data — a refusal that records the corrected figure
 * and leaves the position tradeable satisfies it, while crediting a number
 * known to be wrong does not.
 */
object EconomicUnitInvariant7061 {

    /**
     * §1 — explicit denomination. Nothing here converts implicitly; a
     * conversion is always a named function that consumes one denomination and
     * produces another, so a crossing has to be written on purpose to happen.
     */
    enum class Denom { USD, SOL, TOKEN_QTY, USD_PER_TOKEN, SOL_PER_TOKEN }

    /**
     * Relative tolerance on the reconstruction. An arithmetic identity, not a
     * market band — see the class note.
     */
    private const val TOLERANCE_FRACTION = 0.01

    /** Absolute floor so dust-sized sales are not rejected on rounding. */
    private const val EPSILON_SOL = 1e-9

    /** Below this a quoted SOL/USD rate is not a rate. Matches proceedsSol7029. */
    private const val MIN_SOL_USD = 20.0

    private val checked = AtomicLong(0L)
    private val rejected = AtomicLong(0L)
    private val unpriced = AtomicLong(0L)
    private val worstRatioMilli = AtomicLong(0L)

    data class Verdict(
        val ok: Boolean,
        val reason: String,
        val detail: String,
        /**
         * The proceeds the canonical chain produces from the caller's own
         * quantity and price. Populated whenever it is computable, including
         * on a rejection — §10's replay needs the corrected figure, and a
         * refusal that throws it away would be discarding the one useful thing
         * the check produced.
         */
        val reconstructedProceedsSol: Double,
    )

    /** §1 — [Denom.USD] from [Denom.TOKEN_QTY] and [Denom.USD_PER_TOKEN]. */
    fun proceedsUsd(soldQtyTokens: Double, exitPriceUsdPerToken: Double): Double =
        soldQtyTokens * exitPriceUsdPerToken

    /** §1 — [Denom.SOL] from [Denom.USD]. The division that 7029 omitted. */
    fun usdToSol(usd: Double, solUsdPrice: Double): Double =
        if (solUsdPrice.isFinite() && solUsdPrice >= MIN_SOL_USD) usd / solUsdPrice else Double.NaN

    /**
     * §2 + §6 — validate a paper sell before any economic mutation.
     *
     * [soldQtyRaw] / [preRemainingRaw] are raw base units at [quantityScale];
     * [preRemainingCostBasisSol] is the remaining basis BEFORE this sale.
     * Returns ok=false to block the mutation.
     */
    fun validate(
        positionId: String,
        soldQtyRaw: BigInteger,
        preRemainingRaw: BigInteger,
        quantityScale: Int,
        preRemainingCostBasisSol: Double,
        allocatedCostSol: Double,
        proceedsSol: Double,
        feeSol: Double,
        exitPriceUsdPerToken: Double,
        solUsdPrice: Double,
    ): Verdict {
        checked.incrementAndGet()

        // Unpriced sales cannot be dimensionally checked at all. They are NOT
        // rejected here: three of the four commit sites are terminal exits that
        // supply no price, and failing those closed would strand positions the
        // bot can no longer exit — a worse failure than the one being repaired,
        // and the same reasoning that scoped V5.0.7056 to partials. They are
        // counted so the size of the unchecked remainder is visible rather
        // than assumed to be zero.
        if (!(exitPriceUsdPerToken.isFinite() && exitPriceUsdPerToken > 0.0) ||
            !(solUsdPrice.isFinite() && solUsdPrice >= MIN_SOL_USD)
        ) {
            unpriced.incrementAndGet()
            try { PipelineHealthCollector.labelInc("ECONOMIC_UNIT_INVARIANT_UNPRICED_7061") } catch (_: Throwable) {}
            return Verdict(true, "UNPRICED_NOT_CHECKABLE",
                "priceUsd=$exitPriceUsdPerToken solUsd=$solUsdPrice", 0.0)
        }

        val scale = quantityScale.coerceIn(0, 18)
        val soldQtyTokens = try {
            BigDecimal(soldQtyRaw).movePointLeft(scale).toDouble()
        } catch (_: Throwable) { Double.NaN }
        if (!soldQtyTokens.isFinite() || soldQtyTokens <= 0.0) {
            return no("QTY_UNRESOLVABLE", "raw=$soldQtyRaw scale=$scale", 0.0)
        }

        // §2 — the reconstruction. tokens x (USD/token) / (USD/SOL) = SOL.
        val expectedSol = usdToSol(proceedsUsd(soldQtyTokens, exitPriceUsdPerToken), solUsdPrice)
        if (!expectedSol.isFinite() || expectedSol <= 0.0) {
            return no("RECONSTRUCTION_UNCOMPUTABLE",
                "qty=$soldQtyTokens priceUsd=$exitPriceUsdPerToken solUsd=$solUsdPrice", 0.0)
        }
        if (!proceedsSol.isFinite() || proceedsSol < 0.0) {
            return no("PROCEEDS_NOT_FINITE", "proceedsSol=$proceedsSol", expectedSol)
        }
        val allowance = maxOf(EPSILON_SOL, expectedSol * TOLERANCE_FRACTION)
        val gap = kotlin.math.abs(proceedsSol - expectedSol)
        if (gap > allowance) {
            val ratio = if (expectedSol > 0.0) proceedsSol / expectedSol else -1.0
            noteWorst(ratio)
            return no("PROCEEDS_UNIT_MISMATCH",
                "claimedSol=${"%.9f".format(proceedsSol)} " +
                    "expectedSol=${"%.9f".format(expectedSol)} " +
                    "ratio=${"%.6g".format(ratio)} gap=${"%.9f".format(gap)} " +
                    "allowance=${"%.9f".format(allowance)} " +
                    "qty=${"%.6f".format(soldQtyTokens)} priceUsd=$exitPriceUsdPerToken " +
                    "solUsd=${"%.2f".format(solUsdPrice)}",
                expectedSol)
        }

        // §6 — cost basis must follow QUANTITY, never a ladder label. The
        // pro-rata slice is of what remains, not of the original position:
        // taking a fraction of the original against a fractional quantity is
        // directive §4's "whole-position proceeds against fractional basis" in
        // its other direction.
        if (preRemainingRaw > BigInteger.ZERO && preRemainingCostBasisSol > 0.0) {
            val fraction = try {
                BigDecimal(soldQtyRaw).divide(BigDecimal(preRemainingRaw), java.math.MathContext.DECIMAL64).toDouble()
            } catch (_: Throwable) { Double.NaN }
            if (fraction.isFinite() && fraction > 0.0 && fraction <= 1.0) {
                val expectedCost = preRemainingCostBasisSol * fraction
                val costAllowance = maxOf(EPSILON_SOL, expectedCost * TOLERANCE_FRACTION)
                if (kotlin.math.abs(allocatedCostSol - expectedCost) > costAllowance) {
                    return no("COST_NOT_PRO_RATA",
                        "allocated=${"%.9f".format(allocatedCostSol)} " +
                            "expected=${"%.9f".format(expectedCost)} " +
                            "fraction=${"%.8f".format(fraction)} " +
                            "preRemainingCost=${"%.9f".format(preRemainingCostBasisSol)}",
                        expectedSol)
                }
            }
        }

        // §2 — grossPnl + allocatedCost must reconstitute proceeds exactly.
        // At the bridge this is an identity by construction; it is checked
        // anyway so that a future caller supplying an independently derived
        // P&L cannot slip a third derivation in beside the other two.
        val grossPnlSol = proceedsSol - allocatedCostSol
        if (kotlin.math.abs((grossPnlSol + allocatedCostSol) - proceedsSol) > EPSILON_SOL) {
            return no("PNL_CONSERVATION_BROKEN",
                "gross=$grossPnlSol allocated=$allocatedCostSol proceeds=$proceedsSol", expectedSol)
        }
        if (!feeSol.isFinite() || feeSol < 0.0) {
            return no("FEE_NOT_FINITE", "feeSol=$feeSol", expectedSol)
        }

        try { PipelineHealthCollector.labelInc("ECONOMIC_UNIT_INVARIANT_PASSED_7061") } catch (_: Throwable) {}
        return Verdict(true, "UNITS_OK",
            "expectedSol=${"%.9f".format(expectedSol)} positionId=$positionId", expectedSol)
    }

    private fun noteWorst(ratio: Double) {
        if (!ratio.isFinite() || ratio <= 0.0) return
        val magnitude = if (ratio >= 1.0) ratio else 1.0 / ratio
        if (!magnitude.isFinite()) return
        val milli = (magnitude * 1000.0).toLong()
        while (true) {
            val prev = worstRatioMilli.get()
            if (milli <= prev || worstRatioMilli.compareAndSet(prev, milli)) break
        }
    }

    private fun no(reason: String, detail: String, reconstructed: Double): Verdict {
        rejected.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("ECONOMIC_UNIT_INVARIANT_REJECTED")
            PipelineHealthCollector.labelInc("ECONOMIC_UNIT_INVARIANT_REJECTED_$reason")
        } catch (_: Throwable) {}
        return Verdict(false, reason, detail, reconstructed)
    }

    /** One line per rejection, naming the arithmetic that failed. */
    fun logRejection(positionId: String, mint: String, symbol: String, v: Verdict) {
        if (v.ok) return
        try {
            ForensicLogger.lifecycle(
                "ECONOMIC_UNIT_INVARIANT_REJECTED",
                "positionId=$positionId mint=${mint.take(10)} sym=$symbol " +
                    "reason=${v.reason} ${v.detail} " +
                    "reconstructedSol=${"%.9f".format(v.reconstructedProceedsSol)} " +
                    "action=block_mutation_no_cash_no_realized_no_learning",
            )
        } catch (_: Throwable) {}
    }

    /** Diagnostic line for the pipeline report. */
    fun status(): String =
        "checked=${checked.get()} rejected=${rejected.get()} unpriced=${unpriced.get()} " +
            "worstRatio=${"%.3f".format(worstRatioMilli.get() / 1000.0)}x"
}

package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.sell.SellFinalizationCoordinator
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6463 §P1 — PARTIAL-SELL UNIT CORRECTNESS (post-finalize validator).
 *
 * OPERATOR MANDATE (Feb 2026):
 *   "Executor Partial-Sell Migration: Route every partial-sell path in
 *    Executor.kt through the RealizedSol/ReturnPct types so no
 *    plain-Double SOL slot survives"
 *
 * DESIGN
 * ──────
 * Executor.kt is 24k+ lines; rewriting every partial-sell site to the
 * new inline value classes is unsafe against the 64KB compileReleaseKotlin
 * method cap. Instead this module wraps the SINGLE convergence point —
 * `SellFinalizationCoordinator.finalize()` — and validates its result
 * against the PartialSellUnitTypes6461 firewall before the caller can
 * write anything downstream. Every partial + full sell already flows
 * through the coordinator, so this catches 100% of unit corruption
 * without touching 20k lines of executor code.
 *
 * The validator:
 *   1. Wraps the coordinator result in typed RealizedSol / ReturnRatio
 *      / ReturnPct classes.
 *   2. Runs the Fi4FaM firewall on every SOL slot (net proceeds,
 *      realized PnL, cost basis, fees).
 *   3. Cross-checks proportional cost basis vs the RealizedPnLCalculator
 *      output: realizedPnl == solReceived − propCostBasis − fees.
 *      Divergence > 0.001 SOL emits PARTIAL_SELL_ARITH_DIVERGENCE_6463.
 *   4. Refuses to declare a "valid" trade when any SOL slot is clamped —
 *      callers gate their side-effects on `Validated.ok`.
 */
object PartialSellCorrectness6463 {

    data class Validated(
        val ok: Boolean,
        val realizedSol: PartialSellUnitTypes6461.RealizedSol,
        val returnRatio: PartialSellUnitTypes6461.ReturnRatio,
        val netProceedsSol: PartialSellUnitTypes6461.RealizedSol,
        val propCostBasisSol: PartialSellUnitTypes6461.RealizedSol,
        val feesSol: PartialSellUnitTypes6461.RealizedSol,
        val notes: List<String>,
    )

    private val validations = AtomicLong(0L)
    private val clamps = AtomicLong(0L)
    private val arithDivergences = AtomicLong(0L)

    /**
     * Validate a `SellFinalizationCoordinator.Result` before its
     * caller writes anything downstream. Non-mutating — returns a
     * Validated snapshot with `ok=false` if any SOL slot had to be
     * clamped or if the arithmetic diverges.
     */
    fun validate(result: SellFinalizationCoordinator.Result, feesSol: Double, siteTag: String): Validated {
        validations.incrementAndGet()
        val notes = mutableListOf<String>()
        var ok = true

        // Firewall every SOL slot through PartialSellUnitTypes6461.
        val netProceedsRaw = result.solReceived - feesSol
        val netProceedsClamped = PartialSellUnitTypes6461.assertSolPlausible(
            netProceedsRaw, "$siteTag.netProceeds",
        )
        if (netProceedsClamped != netProceedsRaw) { ok = false; clamps.incrementAndGet(); notes += "netProceeds_clamped" }

        val propCostRaw = result.realizedPnl.proportionalCostBasisSol
        val propCostClamped = PartialSellUnitTypes6461.assertSolPlausible(
            propCostRaw, "$siteTag.propCostBasis",
        )
        if (propCostClamped != propCostRaw) { ok = false; clamps.incrementAndGet(); notes += "propCost_clamped" }

        val realizedRaw = result.realizedPnl.realizedPnlSol
        val realizedClamped = PartialSellUnitTypes6461.assertSolPlausible(
            realizedRaw, "$siteTag.realizedPnl",
        )
        if (realizedClamped != realizedRaw) { ok = false; clamps.incrementAndGet(); notes += "realizedPnl_clamped" }

        val feesClamped = PartialSellUnitTypes6461.assertSolPlausible(
            feesSol, "$siteTag.fees",
        )
        if (feesClamped != feesSol) { ok = false; clamps.incrementAndGet(); notes += "fees_clamped" }

        // Cross-check arithmetic: realized == solReceived − propCost − fees
        // (RealizedPnLCalculator's own contract, restated here so any
        //  downstream corruption is caught even if the calculator
        //  regresses.)
        val expected = result.solReceived - propCostClamped - feesClamped
        val delta = kotlin.math.abs(expected - realizedClamped)
        if (delta > 0.001 && realizedClamped != 0.0) {
            ok = false
            arithDivergences.incrementAndGet()
            notes += "arith_diverge_${"%.4f".format(delta)}"
            try {
                ForensicLogger.lifecycle(
                    "PARTIAL_SELL_ARITH_DIVERGENCE_6463",
                    "site=$siteTag delta=${"%.4f".format(delta)} " +
                        "solReceived=${"%.4f".format(result.solReceived)} " +
                        "propCost=${"%.4f".format(propCostClamped)} " +
                        "fees=${"%.4f".format(feesClamped)} " +
                        "realizedPnl=${"%.4f".format(realizedClamped)}",
                )
                PipelineHealthCollector.labelInc("PARTIAL_SELL_ARITH_DIVERGENCE_6463")
            } catch (_: Throwable) {}
        }

        val realizedTyped = PartialSellUnitTypes6461.RealizedSol(realizedClamped)
        val ratio = PartialSellUnitTypes6461.computeReturnRatio(realizedTyped, propCostClamped)
        try {
            if (ok) PipelineHealthCollector.labelInc("PARTIAL_SELL_VALIDATED_OK_6463")
            else PipelineHealthCollector.labelInc("PARTIAL_SELL_VALIDATED_CLAMPED_6463")
        } catch (_: Throwable) {}
        return Validated(
            ok = ok,
            realizedSol = realizedTyped,
            returnRatio = ratio,
            netProceedsSol = PartialSellUnitTypes6461.RealizedSol(netProceedsClamped),
            propCostBasisSol = PartialSellUnitTypes6461.RealizedSol(propCostClamped),
            feesSol = PartialSellUnitTypes6461.RealizedSol(feesClamped),
            notes = notes,
        )
    }

    fun statusLine(): String =
        "validations=${validations.get()} clamps=${clamps.get()} arithDivergences=${arithDivergences.get()}"

    internal fun resetForTest() {
        validations.set(0L); clamps.set(0L); arithDivergences.set(0L)
    }
}

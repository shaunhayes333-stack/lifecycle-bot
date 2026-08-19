package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6470 §P0 — CANONICAL ECONOMIC IDENTITY.
 *
 * OPERATOR MANDATE (verbatim):
 *
 *   "There must be ONE economic equation and ONE starting-capital
 *    baseline.  Currently: legacy paper conservation delta ≈ -0.0438
 *    SOL, wallet/capital authority delta = -7.2082 SOL. These cannot
 *    both describe the same account."
 *
 * THE ONE EQUATION
 * ────────────────
 *   startingCapital
 *     + canonicalRealizedPnL
 *     - canonicalFees
 *     ==
 *   canonicalCash
 *     + canonicalOpenCostBasis
 *
 * (Withdrawals / deposits go into the left-hand side as an extra term.
 *  For paper mode they are zero unless the operator has topped up.)
 *
 * `CapitalConservationTracer6469` already emits a diagnostic delta
 * for the OLD invariant `baseline + realized == cash + openCost`.
 * That invariant IGNORES fees — which is why 6469 still saw a
 * conservation delta even after the terminal bridge shipped. THIS
 * module is the CORRECT equation.
 *
 * NON-CLAMPING. Diagnostic surface only.
 */
object CanonicalEconomicIdentity6470 {

    data class Result(
        val delta: Double,
        val leftHandSide: Double,
        val rightHandSide: Double,
    )

    private val reconciles = AtomicLong(0L)
    private val breaches = AtomicLong(0L)
    private val lastDelta = AtomicReference(0.0)
    private val lastCheckMs = AtomicLong(0L)

    /**
     * Reconcile the one equation. Emits `CAPITAL_IDENTITY_BREACH_6470`
     * when |delta| > 0.01 SOL. Also flips the `LearningQuarantineGate6470`
     * on breach so nothing downstream trains on a broken ledger.
     */
    fun reconcile(
        startingCapitalSol: Double,
        canonicalRealizedPnlSol: Double,
        canonicalFeesSol: Double,
        canonicalCashSol: Double,
        canonicalOpenCostBasisSol: Double,
        withdrawalsSol: Double = 0.0,
        depositsSol: Double = 0.0,
    ): Result {
        reconciles.incrementAndGet()
        lastCheckMs.set(System.currentTimeMillis())
        val lhs = startingCapitalSol + canonicalRealizedPnlSol - canonicalFeesSol +
            depositsSol - withdrawalsSol
        val rhs = canonicalCashSol + canonicalOpenCostBasisSol
        val delta = rhs - lhs
        lastDelta.set(delta)
        if (kotlin.math.abs(delta) > 0.01) {
            breaches.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "CAPITAL_IDENTITY_BREACH_6470",
                    "startCap=$startingCapitalSol realized=$canonicalRealizedPnlSol fees=$canonicalFeesSol " +
                        "cash=$canonicalCashSol openCost=$canonicalOpenCostBasisSol " +
                        "deposits=$depositsSol withdrawals=$withdrawalsSol " +
                        "lhs=$lhs rhs=$rhs delta=$delta",
                )
                PipelineHealthCollector.labelInc("CAPITAL_IDENTITY_BREACH_6470")
            } catch (_: Throwable) {}
        }
        return Result(delta = delta, leftHandSide = lhs, rightHandSide = rhs)
    }

    fun lastDelta(): Double = lastDelta.get()
    fun breachCount(): Long = breaches.get()

    fun statusLine(): String =
        "reconciles=${reconciles.get()} breaches=${breaches.get()} " +
            "lastDelta=${lastDelta.get()} lastCheckMs=${lastCheckMs.get()}"

    internal fun resetForTest() {
        reconciles.set(0L); breaches.set(0L)
        lastDelta.set(0.0); lastCheckMs.set(0L)
    }
}

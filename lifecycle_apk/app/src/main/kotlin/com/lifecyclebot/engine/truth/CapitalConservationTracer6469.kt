package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6469 §P0 — CAPITAL CONSERVATION TRACER.
 *
 * OPERATOR MANDATE (verbatim, 6468 evidence):
 *
 *   "cash=25.7324 openMV=6.6422 equity=32.3746 conservationDelta=-1.534918
 *    violations=65.
 *    Trace the delta to the exact mutation sequence.
 *    Do not clamp/reset the number."
 *
 * The invariant we're tracing is:
 *
 *   baselineSol + realizedPnl = cash + openMV_atCost
 *
 * Equivalently: SOL should be conserved across BUY / SELL / PARTIAL /
 * FEE mutations if cost basis + PnL are booked correctly.
 *
 * This tracer records per-terminal-event contributions to (cash,
 * realized, openCost) and derives a running conservation delta.
 * A non-zero drift means one of the mutation legs is off by an
 * exact amount, which points at the specific mutation site.
 *
 * NON-CLAMPING. The tracer never adjusts the ledger — it names the
 * bug. Callers act on the diagnosis, not on the number.
 */
object CapitalConservationTracer6469 {

    private val onBuyEvents = AtomicLong(0L)
    private val onSellEvents = AtomicLong(0L)
    private val onPartialEvents = AtomicLong(0L)
    private val onFeeEvents = AtomicLong(0L)
    private val cumulativeGross = java.util.concurrent.atomic.AtomicReference(0.0)
    private val cumulativeCostBasisSold = java.util.concurrent.atomic.AtomicReference(0.0)
    private val cumulativeFees = java.util.concurrent.atomic.AtomicReference(0.0)
    private val cumulativeRealized = java.util.concurrent.atomic.AtomicReference(0.0)
    private val lastMutation = AtomicReference<String?>(null)
    private val lastConservationDelta = java.util.concurrent.atomic.AtomicReference(0.0)
    private val violations = AtomicLong(0L)

    /** Called by CanonicalPaperTerminalBridge6469 on every finalize. */
    fun onSell(
        positionId: String, mint: String,
        grossProceedsSol: Double, soldCostBasisSol: Double, feesSol: Double,
        terminal: Boolean,
    ) {
        if (terminal) onSellEvents.incrementAndGet() else onPartialEvents.incrementAndGet()
        if (feesSol > 0.0) onFeeEvents.incrementAndGet()
        cumulativeGross.set(cumulativeGross.get() + grossProceedsSol)
        cumulativeCostBasisSold.set(cumulativeCostBasisSold.get() + soldCostBasisSol)
        cumulativeFees.set(cumulativeFees.get() + feesSol)
        val realizedThisEvent = grossProceedsSol - soldCostBasisSol - feesSol
        cumulativeRealized.set(cumulativeRealized.get() + realizedThisEvent)
        lastMutation.set("SELL($positionId, ${mint.take(10)}, gross=$grossProceedsSol, cost=$soldCostBasisSol, fee=$feesSol, terminal=$terminal)")
        // Publish the ONE conservation delta metric operators care about.
        try {
            PipelineHealthCollector.labelInc(
                if (terminal) "CAP_CONSERVATION_TRACER_TERMINAL_6469"
                else "CAP_CONSERVATION_TRACER_PARTIAL_6469"
            )
        } catch (_: Throwable) {}
    }

    /**
     * Reconcile against the canonical capital + paper ledger. Called from
     * the 30-loop parity audit (BotService.botLoop). Emits a live
     * `CAPITAL_CONSERVATION_DELTA` telemetry so the operator sees where
     * we are on every audit tick.
     */
    fun reconcile(
        baselineSol: Double,
        cashSol: Double,
        openCostBasisSol: Double,
        realizedFromLedger: Double,
        feesFromLedger: Double = 0.0,
    ): Double {
        // realizedFromLedger is gross; fees are a separate canonical line.
        // baseline + grossRealized - fees == cash + openCost.
        // We use realizedFromLedger (authoritative), not our own cumulative
        // sum, because the ledger is authority. Our cumulative sum is a
        // sanity check — mismatch between it and the ledger indicates a
        // ledger call was missed.
        val identity = (cashSol + openCostBasisSol) - (baselineSol + realizedFromLedger - feesFromLedger)
        lastConservationDelta.set(identity)
        if (kotlin.math.abs(identity) > 0.01) {
            violations.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "CAPITAL_CONSERVATION_DELTA_6469",
                    "baseline=$baselineSol cash=$cashSol openCost=$openCostBasisSol " +
                        "realizedLedger=$realizedFromLedger feesLedger=$feesFromLedger delta=$identity " +
                        "tracerRealized=${cumulativeRealized.get()} tracerGross=${cumulativeGross.get()} " +
                        "tracerCost=${cumulativeCostBasisSold.get()} tracerFees=${cumulativeFees.get()} " +
                        "lastMutation=${lastMutation.get() ?: "-"}"
                )
                PipelineHealthCollector.labelInc("CAPITAL_CONSERVATION_DELTA")
            } catch (_: Throwable) {}
        }
        return identity
    }

    fun lastDelta(): Double = lastConservationDelta.get()
    fun violationCount(): Long = violations.get()

    fun statusLine(): String =
        "sells=${onSellEvents.get()} partials=${onPartialEvents.get()} " +
            "fees=${onFeeEvents.get()} lastDelta=${lastConservationDelta.get()} " +
            "violations=${violations.get()}"

    internal fun resetForTest() {
        onBuyEvents.set(0L); onSellEvents.set(0L); onPartialEvents.set(0L); onFeeEvents.set(0L)
        cumulativeGross.set(0.0); cumulativeCostBasisSold.set(0.0)
        cumulativeFees.set(0.0); cumulativeRealized.set(0.0)
        lastMutation.set(null); lastConservationDelta.set(0.0)
        violations.set(0L)
    }
}

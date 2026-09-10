package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.6727 — §EXIT_THROUGHPUT_BACK_PRESSURE.
 *
 * Runtime symptom (from 6726 diagnostic dump): 200 active positions,
 * cash 0.0040 SOL (0.004% of equity), size-resolver returning
 * `final=0.00000 reason=CAPITAL_BELOW_MIN_EXECUTABLE_6490`, slot health
 * `forced=194 exitPending=true`, and 19 minutes of paper-execution
 * silence while SCAN and FDG continued. Cascading counters:
 *   EXEC_DEFERRED_SLOT_HEALTH   = 2586
 *   MEME_TURNOVER_PRESSURE_DEFER = 2586
 *   SIZE_ZERO_UNPRICED_INTAKE   =  349
 *   BELOW_MIN_NOTIONAL          =  200
 *
 * Root cause: the buy-admission path has NO hard back-pressure on
 * capital-saturation state. It relies on the size resolver to return
 * zero, which happens too late — by then the pipeline has already
 * churned SCAN → INTAKE → FDG → EXEC_GATE 2500+ times per cycle and
 * spammed the block counters. The admission surface needs to KNOW
 * about the saturation state and short-circuit before the churn.
 *
 * This authority owns the single-source-of-truth answer for
 * "should admission be paused right now?" It reads from
 * PaperCapitalAuthority6577 (paper) or CanonicalCapitalAuthority6450
 * (live) plus CanonicalPositionAuthority6441 and returns a verdict
 * consumers can act on WITHOUT re-implementing the pressure math.
 *
 * Deliberately DOES NOT block exits — this is admission-side only.
 * Exit-throughput is already gated by the exit coordinator; adding
 * an admission clamp lets exits drain the inventory without new
 * opens re-saturating.
 */
object ExitThroughputAuthority6727 {

    /** Fraction of equity below which cash is considered starved. */
    private const val CASH_STARVE_RATIO = 0.05        // 5% of equity
    /** Position-count threshold above which the guard engages. */
    private const val POSITION_CAP_HINT = 100
    /** Extreme threshold — at this open count admission blocks regardless of cash ratio. */
    private const val POSITION_HARD_CAP = 180

    data class Verdict(
        val allow: Boolean,
        val reason: String,
        val openPositions: Int,
        val cashSol: Double,
        val equitySol: Double,
        val cashRatio: Double,
    )

    /**
     * Query the current back-pressure state for buy admission.
     * @param mode "paper" or "live" (case-insensitive).
     * @return Verdict with .allow = true when admission is unblocked;
     *         .allow = false when the guard is engaged. Reason is one of:
     *         "OK", "CASH_STARVED_EXIT_THROUGHPUT_6727",
     *         "POSITION_HARD_CAP_EXIT_THROUGHPUT_6727".
     */
    fun evaluate(mode: String): Verdict {
        val m = mode.trim().lowercase()
        val cash: Double
        val open: Double
        try {
            if (m == "live") {
                val cap = CanonicalCapitalAuthority6450.snapshot()
                cash = cap.cashSol
                open = cap.openMarketValueSol
            } else {
                val cap = PaperCapitalAuthority6577.snapshot()
                cash = cap.availableCashSol
                open = cap.openMarketValueSol
            }
        } catch (_: Throwable) {
            return Verdict(true, "OK_FAIL_OPEN", 0, 0.0, 0.0, 1.0)
        }
        val equity = cash + open
        val openCount = try {
            CanonicalPositionAuthority6441.openPositions().count { it.mode == m }
        } catch (_: Throwable) { 0 }
        val cashRatio = if (equity > 0.0) cash / equity else 1.0

        // Hard cap: absolute open count exceeds sanity ceiling regardless
        // of cash. Prevents runaway inventory even when a fresh deposit
        // temporarily lifts cashRatio.
        if (openCount >= POSITION_HARD_CAP) {
            try { PipelineHealthCollector.labelInc("EXIT_THROUGHPUT_BLOCKED_POSITION_HARD_CAP_6727") } catch (_: Throwable) {}
            return Verdict(false, "POSITION_HARD_CAP_EXIT_THROUGHPUT_6727", openCount, cash, equity, cashRatio)
        }

        // Compound guard: cash starved AND we're already carrying real
        // inventory. Either condition alone can be recovered; the
        // combination is the saturation state we saw in the dump.
        if (cashRatio < CASH_STARVE_RATIO && openCount >= POSITION_CAP_HINT) {
            try { PipelineHealthCollector.labelInc("EXIT_THROUGHPUT_BLOCKED_CASH_STARVED_6727") } catch (_: Throwable) {}
            return Verdict(false, "CASH_STARVED_EXIT_THROUGHPUT_6727", openCount, cash, equity, cashRatio)
        }

        return Verdict(true, "OK", openCount, cash, equity, cashRatio)
    }
}

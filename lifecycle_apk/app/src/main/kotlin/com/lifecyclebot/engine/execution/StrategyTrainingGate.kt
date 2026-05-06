package com.lifecyclebot.engine.execution

/**
 * V5.9.495z20 — Strategy training gate.
 *
 * Operator spec rule: do NOT train EntryAI / Momentum / Narrative as a bad
 * target-token trade when the execution outcome was an
 * INTERMEDIATE_ASSET_HELD / FAILED_OUTPUT_MISMATCH / RECOVERING. Those are
 * route/bridge/execution failures, not strategy failures — punishing
 * strategy layers for them poisons their priors with phantom losses on
 * tokens we never actually bought.
 *
 * Pattern at every learning site:
 *
 *     if (StrategyTrainingGate.shouldTrainStrategy(executionStatus)) {
 *         entryAI.train(...)
 *         momentumAI.train(...)
 *     }
 *     // Route/Bridge/Execution layers always learn:
 *     routeLayer.train(executionStatus, ...)
 *
 * `recordedOutcome()` returns the canonical strategy result classifier so
 * the journal can store StrategyResult.UNKNOWN for partial bridges.
 */
object StrategyTrainingGate {

    enum class StrategyResult {
        WIN,
        LOSS,
        BREAKEVEN,
        UNKNOWN,            // partial bridge / output mismatch / recovery in progress
    }

    /**
     * True when the strategy layers (EntryAI / Momentum / Narrative / ChartPattern)
     * should be trained against this execution outcome. False for
     * route/bridge/execution failures that didn't actually deliver the target.
     */
    fun shouldTrainStrategy(status: ExecutionStatus): Boolean = when (status) {
        ExecutionStatus.FINAL_TOKEN_VERIFIED,
        ExecutionStatus.CONFIRMED,
        ExecutionStatus.CLOSED                 -> true   // genuine outcome
        ExecutionStatus.INTERMEDIATE_ASSET_HELD,
        ExecutionStatus.CONTINUATION_REQUIRED,
        ExecutionStatus.FAILED_OUTPUT_MISMATCH,
        ExecutionStatus.RECOVERING,
        ExecutionStatus.FAILED_NO_ROUTE,
        ExecutionStatus.FAILED_ORDER           -> false  // never train strategy on these
        ExecutionStatus.FAILED_TX_CONFIRMED    -> false  // tx error before delivery
        ExecutionStatus.NEW,
        ExecutionStatus.ROUTE_SELECTED,
        ExecutionStatus.ORDER_REQUESTED,
        ExecutionStatus.ORDER_RECEIVED,
        ExecutionStatus.SIGNED,
        ExecutionStatus.BROADCAST              -> false  // not yet settled
    }

    /**
     * Route / Bridge / Execution layers ALWAYS learn — they're the ones whose
     * job is exactly to handle these failure modes.
     */
    fun shouldTrainRouteLayer(status: ExecutionStatus): Boolean = when (status) {
        ExecutionStatus.NEW,
        ExecutionStatus.ROUTE_SELECTED,
        ExecutionStatus.ORDER_REQUESTED,
        ExecutionStatus.ORDER_RECEIVED         -> false  // not yet executed
        else                                    -> true
    }

    /** Map an execution outcome to the canonical strategy result classifier. */
    fun strategyResult(status: ExecutionStatus, pnlSol: Double = 0.0): StrategyResult = when {
        !shouldTrainStrategy(status)  -> StrategyResult.UNKNOWN
        pnlSol >  0.0                  -> StrategyResult.WIN
        pnlSol <  0.0                  -> StrategyResult.LOSS
        else                            -> StrategyResult.BREAKEVEN
    }
}

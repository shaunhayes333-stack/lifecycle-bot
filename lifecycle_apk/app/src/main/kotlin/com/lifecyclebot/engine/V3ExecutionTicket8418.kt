package com.lifecyclebot.engine

/**
 * V5.0.8418 §ROUTE_FAILED_LIVE_WAS_V3_ASKING_WITHOUT_A_TICKET.
 *
 * 5.0.8416: ROUTE_FAILED_LIVE x1,331 while EXEC (executor invocations) = 1 and FDG allowed 0 of 9,286. No route was
 * tried. V3's own orchestrator (BotOrchestrator.handleExecute -> TradeExecutor -> V3EngineManager's callback ->
 * BotService.runV3Execution) calls executor.doBuy with no attempt id, and since 5.0.7835 doBuy executes only the exact
 * order a FinalDecisionGate approval sealed (SealedExecutionSize7835: no ticket -> EXECUTION_TICKET_MISSING_7835). Every
 * one of those calls was therefore certain to return without an open, and runV3Execution booked each as a route failure
 * (it labels any not-opened doBuy ROUTE_FAILED_<mode>). The FDG path — which carries the ticket — owns entries.
 *
 * runV3Execution now asks the same authority doBuy would ask, before calling it, and returns that typed refusal
 * (V3_EXEC_NO_FDG_TICKET_8418) instead of a route failure. Nothing is loosened: the ticket rule is exactly 7835's.
 */
internal object V3ExecutionTicket8418 {
    /** The attempt id runV3Execution hands executor.doBuy (doBuy's default): V3's own executor carries no FDG attempt. */
    const val V3_ATTEMPT_ID_8418 = ""

    /** Pure: the sealed-order refusal doBuy will reach for this request (null = a live FDG ticket carries it). */
    fun refusal8418(ticket: ExecutableOpenGate.ExecutionIntent?, mint: String, mode: String, sizeSol: Double): String? =
        SealedExecutionSize7835.refusal(ticket, mint, mode, ticket?.canonicalLane ?: "V3", sizeSol)
}

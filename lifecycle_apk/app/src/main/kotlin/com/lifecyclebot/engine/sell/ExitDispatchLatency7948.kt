package com.lifecyclebot.engine.sell

/**
 * V5.0.7948 — EXIT DISPATCH LATENCY (5.0.7947 diag section 4, P0).
 *
 * 5.0.7947 measured TRAILING_STOP trigger -> broadcast at 220.5 s with the
 * queue stage (trigger -> first SELL_START) at up to 204,037 ms, alongside
 * 355 supervisor worker timeouts, 131 hot exit units replaced as stuck and
 * 285 sell redispatches. Two dispatch faults sat under those numbers:
 *
 *  1. processTokenCycle sells inline on a supervisor worker (thread
 *     "AATE-Entry-6647") whose 15 s budget ends in Thread.interrupt
 *     (runInterruptible). A live sell — quote ladder, broadcast, verifySell
 *     (60 s), wallet polling — routinely outlives that budget, so it was
 *     interrupted mid-flight and redispatched. Those sells now run on
 *     [supervisorSellExecutor]; the worker waits at most
 *     [SUPERVISOR_SELL_WAIT_MS_7948] and then answers "retry next tick"
 *     while the sell itself finishes, verifies and books.
 *  2. A fixed 500 ms sign -> broadcast sleep (SecurityGuard.enforceSignDelay)
 *     sat inside every protective and trailing sell. [skipsSignDelay] lets
 *     ProtectiveExitClass7807 rank 1-4 exits skip it.
 *
 * Nothing here weakens double-sell protection: CloseLease, the per-mint sell
 * lock and SellExecutionLocks still single-flight every sell per mint, and
 * accounting / verification run unchanged on whichever thread runs the sell.
 */
object ExitDispatchLatency7948 {

    /** Thread-name prefix of BotService's supervisor (processTokenCycle) workers. */
    const val SUPERVISOR_THREAD_PREFIX_7948 = "AATE-Entry-6647"

    /** Longest a supervisor worker waits on a sell it requested (budget is 15 s). */
    const val SUPERVISOR_SELL_WAIT_MS_7948 = 10_000L

    /** True when a sell requested on [threadName] must run off that thread. */
    fun runsOffCallerThread(threadName: String?): Boolean =
        threadName.orEmpty().startsWith(SUPERVISOR_THREAD_PREFIX_7948)

    /**
     * True when [reason] skips the fixed sign -> broadcast pause: capital
     * preservation, structural emergencies, hard stops, and profit protection
     * (trailing stops, profit locks, breakeven, take-profit). Ordinary exits
     * (rank NONE) keep the pause.
     */
    fun skipsSignDelay(reason: String?): Boolean =
        ProtectiveExitClass7807.of(reason).rank <= ProtectiveExitClass7807.Priority.PROFIT_PROTECTION.rank

    private val supervisorSellExecutor7948: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "AATE-SupervisorSell-7948").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 }
        }

    /** Pool for sells handed off from supervisor workers (never interrupted by their budget). */
    val supervisorSellExecutor: java.util.concurrent.ExecutorService get() = supervisorSellExecutor7948
}

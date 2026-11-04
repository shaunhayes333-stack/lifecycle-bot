package com.lifecyclebot.network

/**
 * One monotonic budget for unsigned quote fallbacks, never submission or finality.
 *
 * V5.0.8079 §NO_SINGLE_HOP_STARVATION. The 3.5 s budget was handed whole to the first HTTP hop, so one slow v6
 * host spent it and every later fallback (the v6 route ladder, both Ultra binding orders) threw "Exit quote budget
 * exhausted" without sending a request: a sell saw "Jupiter has no route" when Jupiter was never asked twice
 * (HIRE, 8078: three quote rounds, zero second hops, then Raydium/PumpPortal which cannot route it). Each hop is now
 * capped at [HOP_CAP_MS] inside a [TOTAL_MS] budget, so a stalled host leaves time for a different endpoint.
 */
internal object ExitQuoteBudget7863 {
    internal const val TOTAL_MS = 5_000L
    internal const val HOP_CAP_MS = 2_000L
    private val deadline = ThreadLocal<Long?>()
    fun remainingMs(nowNanos: Long = System.nanoTime()): Long? {
        val end = deadline.get() ?: return null
        val left = (end - nowNanos) / 1_000_000L
        if (left <= 0L) throw java.net.SocketTimeoutException("Exit quote budget exhausted")
        return left.coerceAtMost(HOP_CAP_MS)
    }
    /**
     * Scope for imperative callers with early returns (the live sell quote ladder).
     * Nested callers may shorten, never restart or extend, the current thread's deadline.
     * Only unsigned quote reads consume this scope; close before simulation or submission.
     */
    fun begin(nowNanos: Long = System.nanoTime(), budgetMs: Long = TOTAL_MS): AutoCloseable {
        require(budgetMs in 1L..60_000L)
        val previous = deadline.get()
        deadline.set(minOf(previous ?: Long.MAX_VALUE, nowNanos + budgetMs * 1_000_000L))
        return object : AutoCloseable {
            private var closed = false
            override fun close() {
                if (closed) return
                closed = true
                if (previous == null) deadline.remove() else deadline.set(previous)
            }
        }
    }

    fun <T> run(nowNanos: Long = System.nanoTime(), block: () -> T): T {
        val scope = begin(nowNanos)
        try { return block() } finally { scope.close() }
    }
}

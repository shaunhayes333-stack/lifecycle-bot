package com.lifecyclebot.network

/** One monotonic budget for unsigned quote fallbacks, never submission or finality. */
internal object ExitQuoteBudget7863 {
    private val deadline = ThreadLocal<Long?>()
    fun remainingMs(nowNanos: Long = System.nanoTime()): Long? {
        val end = deadline.get() ?: return null
        val left = (end - nowNanos) / 1_000_000L
        if (left <= 0L) throw java.net.SocketTimeoutException("Exit quote budget exhausted")
        return left.coerceAtMost(3_500L)
    }
    fun <T> run(nowNanos: Long = System.nanoTime(), block: () -> T): T {
        val previous = deadline.get()
        deadline.set(minOf(previous ?: Long.MAX_VALUE, nowNanos + 3_500_000_000L))
        try { return block() } finally { if (previous == null) deadline.remove() else deadline.set(previous) }
    }
}

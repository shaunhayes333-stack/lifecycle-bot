package com.lifecyclebot.network

/**
 * A curve reserve read is observation, never transaction submission.
 *
 * 8359 moved public RPCs ahead of Helius, but 7809's two-transport-failure
 * break still stopped the walk after those two public nodes. Bound the whole
 * read by the existing mark fan-out's four-second window instead. Background
 * probes retain keyless-first ordering and reserve half the window for a
 * configured authenticated fallback; a held/approved read uses it first.
 *
 * The callback receives the remaining call timeout. It must apply that timeout
 * to its HTTP call. An interrupt ends the walk; a timeout is unknown data,
 * never zero reserves, an absent account, or a token-quality verdict.
 */
internal object CurveReadBudget8527 {
    const val TOTAL_MS = 4_000L
    private const val AUTHENTICATED_RESERVE_MS = 2_000L
    private const val PUBLIC_HOP_MS = 1_000L
    private const val MIN_HOP_MS = 250L

    fun order(ladder: List<String>, latencyCritical: Boolean): List<String> {
        val clean = ladder.filter { it.isNotBlank() }.distinct()
        if (!latencyCritical) return clean
        val (keyless, authenticated) = clean.partition { KeylessRpc8359.isKeyless8359(it) }
        return authenticated + keyless
    }

    fun hopMs(leftMs: Long, keyless: Boolean, authenticatedStillAhead: Boolean): Long {
        val reserve = if (keyless && authenticatedStillAhead) AUTHENTICATED_RESERVE_MS else 0L
        val available = (leftMs - reserve).coerceAtLeast(0L)
        val hop = if (keyless && authenticatedStillAhead) minOf(PUBLIC_HOP_MS, available) else available
        return if (hop >= MIN_HOP_MS) hop else 0L
    }

    /**
     * Walk the actual read ladder. false from [visit] means this rung supplied
     * no complete answer and the next eligible rung may be asked. [admit] is
     * the caller's unchanged lockout/rate-governor check.
     */
    fun walk(
        ladder: List<String>,
        latencyCritical: Boolean,
        admit: (String) -> Boolean,
        clockNanos: () -> Long = System::nanoTime,
        interrupted: () -> Boolean = { Thread.currentThread().isInterrupted },
        visit: (url: String, index: Int, callTimeoutMs: Long) -> Boolean,
    ) {
        val ordered = order(ladder, latencyCritical)
        val deadline = clockNanos() + TOTAL_MS * 1_000_000L
        for ((index, url) in ordered.withIndex()) {
            if (interrupted()) return
            val left = ((deadline - clockNanos()) / 1_000_000L).coerceAtLeast(0L)
            if (left < MIN_HOP_MS) return
            val authenticatedAhead = ordered.drop(index + 1).any { !KeylessRpc8359.isKeyless8359(it) }
            val timeout = hopMs(left, KeylessRpc8359.isKeyless8359(url), authenticatedAhead)
            if (timeout <= 0L || !admit(url)) continue
            if (visit(url, index, timeout)) return
        }
    }
}

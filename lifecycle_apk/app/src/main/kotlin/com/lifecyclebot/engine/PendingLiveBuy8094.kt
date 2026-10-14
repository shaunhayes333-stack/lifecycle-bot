package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8094 §A_DEFERRED_LIVE_BUY_IS_RETRIED_NEXT_CYCLE.
 *
 * 5.0.8093: SHITCOIN live fills landed a median 48 s after the FDG seal (EntryChase7961 lat=48s) and several were
 * stopped out within a minute — the move was over. A sealed live buy is often deferred once, non-terminally (token
 * map still resolving, entry snapshot missing, wallet mutex busy), and the retry then waited for the mint's next
 * round-robin turn: at least 15 s for a socket-active pump.fun token (DataOrchestrator WS gate) plus the loop period.
 * A mint whose live buy was just deferred is now re-evaluated on the next cycle (front of the cycle, past the WS
 * gate) for [TTL_MS]. No gate changes: the retry faces every check the first attempt did.
 */
object PendingLiveBuy8094 {
    // A deferral must survive one full cycle (poll 5 s + cycle work) or the next-cycle retry never happens and the mint
    // waits its 15 s socket turn (8 s did exactly that). Chasing is refused at the fill (Executor 8094 moved-since-decision
    // / chasing-at-fill), not by expiring the retry.
    internal const val TTL_MS = 20_000L
    private const val MAX_PER_CYCLE = 8
    private val pending = ConcurrentHashMap<String, Long>()

    /** ExecutionAttemptLease.releaseNonTerminal (BUY): this mint's live buy was deferred, not refused. */
    fun note8094(mint: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        pending[mint] = nowMs
        if (pending.size > 200) pending.entries.removeIf { nowMs - it.value > TTL_MS }
        try { PipelineHealthCollector.labelInc("LIVE_BUY_DEFERRED_RETRY_NEXT_CYCLE_8094") } catch (_: Throwable) {}
    }

    fun isPending8094(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        pending[mint]?.let { nowMs - it in 0L..TTL_MS } == true

    /** The mints to put at the front of the next cycle, oldest deferral first. */
    fun pendingMints8094(nowMs: Long = System.currentTimeMillis()): List<String> = pendingFrom8094(pending, nowMs, TTL_MS, MAX_PER_CYCLE)

    /** Pure: live entries of [stamps] within [ttlMs] of [nowMs], oldest first, at most [max]. */
    internal fun pendingFrom8094(stamps: Map<String, Long>, nowMs: Long, ttlMs: Long, max: Int): List<String> =
        stamps.entries.filter { nowMs - it.value in 0L..ttlMs }.sortedBy { it.value }.take(max).map { it.key }
}

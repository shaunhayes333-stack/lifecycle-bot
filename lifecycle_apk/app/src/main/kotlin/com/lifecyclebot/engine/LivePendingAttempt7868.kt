package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.7868 — the sealed attempt (and owning lane) behind a live buy that is
 * waiting for balance proof, by mint.
 *
 * 5.0.7866: two live buys confirmed; one was promoted to OPEN by wallet recovery
 * (LIVE_PENDING_ENTRY_PROMOTED_FROM_WALLET_7133), a path that never stamped the
 * specialist funnel's EXEC/OPEN, so the lane read ticket>0 exec=0 for a buy that
 * landed. The live pending reservation is keyed by mint (positionIdOf(mint,false));
 * this binds that reservation to the immutable attempt that produced it so the
 * promotion path stamps EXEC/OPEN on the same causal record as the TICKET.
 */
internal object LivePendingAttempt7868 {
    data class Binding(
        val attemptId: String,
        val lane: String,
        val atMs: Long,
        // V5.0.7871 — the entry evidence frozen at broadcast, written as the
        // immutable §6450 snapshot when wallet recovery promotes the reservation.
        val entry7871: com.lifecyclebot.engine.truth.EntryStrategySnapshot6450.Snapshot? = null,
    )

    private const val TTL_MS = 30L * 60_000L
    private val byMint = ConcurrentHashMap<String, Binding>()

    fun bind(
        mint: String,
        attemptId: String,
        lane: String,
        nowMs: Long = System.currentTimeMillis(),
        entry7871: com.lifecyclebot.engine.truth.EntryStrategySnapshot6450.Snapshot? = null,
    ) {
        if (mint.isBlank() || attemptId.isBlank() || lane.isBlank()) return
        byMint[mint] = Binding(attemptId, lane, nowMs, entry7871)
        if (byMint.size > 500) byMint.entries.removeIf { nowMs - it.value.atMs > TTL_MS }
    }

    /** The live binding for [mint], consumed once (null when absent or expired). */
    fun take(mint: String, nowMs: Long = System.currentTimeMillis()): Binding? {
        val b = byMint.remove(mint) ?: return null
        return b.takeIf { nowMs - it.atMs <= TTL_MS }
    }
}

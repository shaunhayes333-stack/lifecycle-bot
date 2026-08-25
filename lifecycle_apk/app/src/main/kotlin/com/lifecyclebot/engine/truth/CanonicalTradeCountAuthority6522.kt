package com.lifecyclebot.engine.truth

/** Immutable trade-count truth. Journal rows are intentionally absent. */
data class CanonicalTradeCounts6522(
    val revision: Long,
    val executionAttempts: Long,
    val buys: Int,
    val partialExits: Int,
    val sessionCompletedTrades: Int,
    val lifetimeCompletedTrades: Int,
    val openTrades: Int,
)

object CanonicalTradeCountAuthority6522 {
    fun capture(sessionStartedAtMs: Long, revision: Long, executionAttempts: Long): CanonicalTradeCounts6522 {
        val positions = CanonicalPositionAuthority6441.openPositions() + CanonicalPositionAuthority6441.closedPositions()
        val unique = positions.distinctBy { "${it.mode.lowercase()}|${it.positionId}|${it.openedAtMs}" }
        val events = EconomicEventSchema6464.snapshot()
        val buys = events.filterIsInstance<EconomicEventSchema6464.Buy>()
            .distinctBy { "${it.mode}|${it.positionId}" }.size
        val partials = events.filterIsInstance<EconomicEventSchema6464.Sell>()
            .filter { it.partial }.distinctBy { it.idempotencyKey }.size
        val closed = unique.filter { it.lifecycle == CanonicalPositionAuthority6441.Lifecycle.CLOSED }
        return CanonicalTradeCounts6522(
            revision = revision,
            executionAttempts = executionAttempts,
            buys = buys,
            partialExits = partials,
            sessionCompletedTrades = closed.count { it.lastMutationMs >= sessionStartedAtMs },
            lifetimeCompletedTrades = closed.size,
            openTrades = unique.count { it.lifecycle == CanonicalPositionAuthority6441.Lifecycle.OPEN || it.lifecycle == CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED },
        )
    }
}

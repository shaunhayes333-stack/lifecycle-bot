package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8136 §ONE_TERMINAL_PER_POSITION — the finalized bus (CanonicalFinalizedTradeBus6464) deduplicated on
 * tradeId only. Both producers stamp tradeId = positionId today, but nothing enforced it: a second terminal for
 * the same position under any other id (a repair, a reconciler row) would fan out to the owner strategy, the
 * Cortex, the risk policy and the hypothesis engine a second time. One whole-position terminal result per
 * positionId: the first tradeId wins; the same tradeId again is the ordinary redelivery (allowed); a different
 * tradeId for an already-finalized position is refused.
 */
class PositionTerminalOnce8136 {
    private val firstTradeByPosition = ConcurrentHashMap<String, String>()

    /** True when [tradeId] may stand as [positionId]'s terminal (first, or the same id again). Blank ids pass. */
    fun admit(positionId: String, tradeId: String): Boolean {
        if (positionId.isBlank() || tradeId.isBlank()) return true
        val prior = firstTradeByPosition.putIfAbsent(positionId, tradeId) ?: return true
        return prior == tradeId
    }

    fun size(): Int = firstTradeByPosition.size

    fun clear() = firstTradeByPosition.clear()
}

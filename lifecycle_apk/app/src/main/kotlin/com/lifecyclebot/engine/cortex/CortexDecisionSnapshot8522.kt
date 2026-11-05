package com.lifecyclebot.engine.cortex

import com.lifecyclebot.data.TokenState

/**
 * The native committee opens several lane observations for one token at one decision time. They must see one
 * detached input, not twelve shallow copies which both follow the live chart and defeat the lane-free voter memo.
 * This cache owns no prediction: assessment/read timestamps are still taken when the worker actually reads voters.
 */
internal class CortexDecisionSnapshot8522(
    private val capacity: Int = 128,
    private val retentionMs: Long = 60_000L,
) {
    private data class Key(val mint: String, val decisionAtMs: Long)
    private class Frozen(val source: TokenState, val token: TokenState, val createdAtMs: Long)
    private val snapshots = LinkedHashMap<Key, Frozen>()

    @Synchronized
    fun capture(ts: TokenState, decisionAtMs: Long, nowMs: Long = System.currentTimeMillis()): TokenState {
        val key = Key(ts.mint, decisionAtMs)
        snapshots[key]?.takeIf { it.source === ts && nowMs - it.createdAtMs in 0L..retentionMs }?.let { return it.token }
        trim(nowMs)
        val frozen = detach(ts)
        if (capacity > 0) {
            while (snapshots.size >= capacity && !snapshots.containsKey(key)) snapshots.remove(snapshots.keys.first())
            snapshots[key] = Frozen(ts, frozen, nowMs)
        }
        return frozen
    }

    @Synchronized
    fun trim(nowMs: Long): Int {
        val before = snapshots.size
        snapshots.entries.removeIf { nowMs - it.value.createdAtMs !in 0L..retentionMs }
        return before - snapshots.size
    }

    @Synchronized
    internal fun size(): Int = snapshots.size

    companion object {
        /**
         * Candle, strategy and supply records are immutable; their mutable containers and route/position records are not.
         * TokenState's four observation properties live outside its primary constructor, so generated copy() resets them.
         * A failed detachment must propagate to capture ownership cleanup, never fall back to the live mutable token.
         */
        internal fun detach(ts: TokenState): TokenState = ts.copy(
            history = synchronized(ts.history) { ArrayDeque(ts.history.toList()) },
            history5m = synchronized(ts.history5m) { ArrayDeque(ts.history5m.toList()) },
            history15m = synchronized(ts.history15m) { ArrayDeque(ts.history15m.toList()) },
            history1h = synchronized(ts.history1h) { ArrayDeque(ts.history1h.toList()) },
            history4h = synchronized(ts.history4h) { ArrayDeque(ts.history4h.toList()) },
            position = ts.position.copy(),
            trades = synchronized(ts.trades) { ts.trades.map { it.copy() }.toMutableList() },
            recentEntryTimes = synchronized(ts.recentEntryTimes) { ts.recentEntryTimes.toMutableList() },
            laneAffinity = ts.laneAffinity.toMutableSet(),
            toolAffinity = ts.toolAffinity.toMutableSet(),
            tokenMap = ts.tokenMap.let { route -> route.copy(
                providerTimestamps = synchronized(route.providerTimestamps) { route.providerTimestamps.toMutableMap() },
                hydrationFailureReasons = synchronized(route.hydrationFailureReasons) { route.hydrationFailureReasons.toMutableList() },
            ) },
            safety = ts.safety.let { it.copy(hardBlockReasons = it.hardBlockReasons.toList(), softPenalties = it.softPenalties.toList(),
                lpOwnership8522 = it.lpOwnership8522.copy(pools = it.lpOwnership8522.pools.toList())) },
            lastConsensusObjections = ts.lastConsensusObjections.toList(),
        ).also { frozen ->
            frozen.mintSupply8418 = ts.mintSupply8418
            frozen.buyPressureObservedAtMs8084 = ts.buyPressureObservedAtMs8084
            frozen.priceChangeObservedAtMs8084 = ts.priceChangeObservedAtMs8084
            frozen.hourChangeObservedAtMs8506 = ts.hourChangeObservedAtMs8506
            // V5.0.8554 D10 — the decision is graded on the facts it saw: source, rank and observation time travel with the copy
            com.lifecyclebot.engine.truth.TokenFacts.freezeInto(frozen, ts)
        }
    }
}

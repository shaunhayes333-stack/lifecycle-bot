package com.lifecyclebot.engine.execution

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.495z21 — Side-channel registry that stamps the most recent
 * `ExecutionStatus` per mint so downstream learning hooks can gate
 * strategy-layer training via `StrategyTrainingGate`.
 *
 * Why a side-channel?
 *   The legacy learning hooks (EducationSubLayerAI, SentienceHooks,
 *   FluidLearningAI, etc.) fan out from a `TradeOutcomeData` / `pnlPct`
 *   signature that has no awareness of canonical `ExecutionStatus`.
 *   Refactoring every signature would ripple through hundreds of call
 *   sites. Instead we stamp the status at the few places where we
 *   actually know it (UniversalBridgeEngine, IntermediateAssetRecovery,
 *   future UniversalRouteEngine callers) and let the learning sites
 *   query by mint.
 *
 * Default behaviour: if no status has been stamped for a mint within
 * `TTL_MS`, the gate helper returns `true` (i.e. "legacy path — go
 * ahead and train"). That's safe because:
 *   • After z19's atomic-swap fix, successful buys stamp FINAL_TOKEN_VERIFIED.
 *   • Failed buys either never create a trade/position OR stamp a
 *     failure status.
 *   • Pre-existing positions from before the fix lack a stamp → treated
 *     as legacy/trusted (same as before this gate existed).
 *
 * Thread-safe. Bounded size (8k entries) with LRU-ish pruning on write.
 */
object ExecutionStatusRegistry {

    private const val TTL_MS = 24L * 3600L * 1000L         // 24 hours
    private const val MAX_ENTRIES = 8_192
    private const val PRUNE_CHUNK = 512

    private data class Stamp(val status: ExecutionStatus, val atMs: Long)

    private val store = ConcurrentHashMap<String, Stamp>()

    /** Stamp a status for a given mint. Most recent wins. */
    fun stamp(mint: String, status: ExecutionStatus) {
        if (mint.isBlank()) return
        store[mint] = Stamp(status, System.currentTimeMillis())
        if (store.size > MAX_ENTRIES) pruneOldest()
    }

    fun get(mint: String): ExecutionStatus? {
        val s = store[mint] ?: return null
        if (System.currentTimeMillis() - s.atMs > TTL_MS) {
            store.remove(mint)
            return null
        }
        return s.status
    }

    /**
     * Gate helper for strategy-layer training. Returns true when:
     *   • no status stamped (legacy / trusted path), OR
     *   • stamped status is a "genuine outcome" per StrategyTrainingGate.
     * Returns false for INTERMEDIATE_ASSET_HELD / OUTPUT_MISMATCH / RECOVERING /
     * FAILED_* — the strategy layers must not be trained on these.
     */
    fun shouldTrainStrategy(mint: String): Boolean {
        val status = get(mint) ?: return true
        return StrategyTrainingGate.shouldTrainStrategy(status)
    }

    fun clear() = store.clear()
    fun size(): Int = store.size

    private fun pruneOldest() {
        val sorted = store.entries.sortedBy { it.value.atMs }
        val toRemove = sorted.take(PRUNE_CHUNK)
        for (e in toRemove) store.remove(e.key, e.value)
    }
}

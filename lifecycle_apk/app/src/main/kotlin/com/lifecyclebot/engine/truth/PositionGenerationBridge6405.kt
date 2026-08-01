package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6405 §5 — POSITION GENERATION BRIDGE.
 *
 * Executor's per-mint sell path (`resolveSellUnitsForMint`) does not
 * receive a `TokenState`, so it cannot read `pos.entryTime` to key the
 * lifetime raw-qty ledger in [DecimalIntegrityAuthority6405]. This tiny
 * bridge is stamped by the buy-verified path (or by any caller that has
 * `ts`) so the sell path can look up the current position generation
 * (`entryTime`) by mint alone.
 *
 * Generation semantics:
 *   • generation == 0L → no active position stamped (invariant becomes
 *     advisory; DecimalIntegrityAuthority6405 will skip the clamp)
 *   • generation > 0L  → active position; sums are tracked against it
 *
 * We store the LATEST generation per mint. When a position closes the
 * caller should [clear] the entry so a subsequent re-buy starts a fresh
 * ledger.
 */
object PositionGenerationBridge6405 {
    private val currentGeneration = ConcurrentHashMap<String, Long>()

    fun set(mint: String, positionGeneration: Long) {
        if (mint.isBlank() || positionGeneration <= 0L) return
        currentGeneration[mint] = positionGeneration
    }

    fun get(mint: String): Long = currentGeneration[mint] ?: 0L

    fun clear(mint: String) { currentGeneration.remove(mint) }

    internal fun clearForTest() { currentGeneration.clear() }
}

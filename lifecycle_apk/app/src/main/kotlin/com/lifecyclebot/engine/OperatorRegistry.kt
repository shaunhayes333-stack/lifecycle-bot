package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.357 — Operator/Creator Registry
 *
 * Tiny shared map of `mint -> creator/dev wallet` populated by upstream
 * data feeds (DataOrchestrator's PumpFun / Helius hooks already discover
 * dev wallets per new token) and consumed by trade-close hooks that need
 * to feed OperatorFingerprintAI.recordOutcome(creator, won).
 *
 * Lives in `engine/` so it does NOT touch the scanner or watchlist flow.
 * It is a passive side-channel: setters are best-effort, getters are
 * null-safe. Bounded at 5,000 entries with simple LRU-ish eviction so the
 * map can't grow without bound across long sessions.
 */
object OperatorRegistry {

    private const val MAX_ENTRIES = 5_000

    private val map = ConcurrentHashMap<String, String>()
    // Insertion-ordered list for cheap eviction without dragging in a real
    // LRU. We just trim the oldest entries when we hit the cap.
    private val insertOrder = java.util.concurrent.ConcurrentLinkedDeque<String>()

    fun set(mint: String, creatorWallet: String?) {
        if (mint.isBlank() || creatorWallet.isNullOrBlank()) return
        if (map.put(mint, creatorWallet) == null) {
            insertOrder.addLast(mint)
            // Evict oldest if over cap
            while (map.size > MAX_ENTRIES) {
                val oldest = insertOrder.pollFirst() ?: break
                map.remove(oldest)
            }
        }
    }

    fun getDevWallet(mint: String): String? = map[mint]

    /** Test/maintenance only. */
    fun clear() {
        map.clear()
        insertOrder.clear()
    }

    fun size(): Int = map.size
}

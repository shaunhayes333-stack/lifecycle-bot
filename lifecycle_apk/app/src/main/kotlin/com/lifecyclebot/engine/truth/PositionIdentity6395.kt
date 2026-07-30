package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6395 — POSITION IDENTITY (one mint, one canonical position).
 *
 * canonicalId = wallet + network + mint. Every lane alias
 * (SHITCOIN_mint_ts, MOONSHOT_mint_ts, ...) resolves to the same
 * canonicalId so Treasury + Moonshot + Bluechip advising the same
 * mint see one lifecycle, one sell intent, one journal row.
 *
 * Rules enforced (V5.0.6395 §"ONE MINT, ONE CANONICAL POSITION"):
 *  - exactly one canonical position per (wallet, network, mint)
 *  - lane ownership is metadata via `laneAliases`
 *  - repeated exit signals merge into one active execution
 *  - separate lots require genuinely separate confirmed buys
 *    (aliasBuy() returns the same canonical id — buys extend, not fork)
 */
object PositionIdentity6395 {
    const val DEFAULT_NETWORK = "SOL_MAINNET"

    /** canonicalId = wallet + network + mint. Stable across lanes. */
    fun canonicalId(wallet: String, mint: String, network: String = DEFAULT_NETWORK): String {
        val w = wallet.ifBlank { "UNKNOWN_WALLET" }
        val m = mint.ifBlank { "UNKNOWN_MINT" }
        return "POS_${network}_${w.take(8)}_${m}"
    }

    private data class Entry(
        val canonicalId: String,
        val wallet: String,
        val network: String,
        val mint: String,
        val laneAliases: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap()),
        @Volatile var laneOwner: String = "UNKNOWN",
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * Register (or return existing) canonical id for this (wallet, mint) and
     * attach a lane alias. All lanes advising the same mint end up with the
     * same canonicalId — the second call is a no-op registration.
     */
    @Synchronized
    fun register(wallet: String, mint: String, lane: String, network: String = DEFAULT_NETWORK): String {
        val id = canonicalId(wallet, mint, network)
        val entry = entries.getOrPut(id) {
            Entry(id, wallet.ifBlank { "UNKNOWN_WALLET" }, network, mint)
        }
        entry.laneAliases.add(lane.uppercase())
        // First-writer-wins lane owner unless a hierarchy is defined externally.
        if (entry.laneOwner == "UNKNOWN") entry.laneOwner = lane.uppercase()
        return id
    }

    fun laneAliases(canonicalId: String): Set<String> =
        entries[canonicalId]?.laneAliases?.toSet().orEmpty()

    fun laneOwner(canonicalId: String): String =
        entries[canonicalId]?.laneOwner ?: "UNKNOWN"

    fun mintFor(canonicalId: String): String? = entries[canonicalId]?.mint
    fun walletFor(canonicalId: String): String? = entries[canonicalId]?.wallet

    /** Directive §7: secondary lane exits update the SAME canonical sell intent. */
    private val activeExitIntents = ConcurrentHashMap<String, String>()

    @Synchronized
    fun openOrGetExitIntent(canonicalId: String, requestedByLane: String): String {
        return activeExitIntents.getOrPut(canonicalId) {
            "EI_${canonicalId}_${System.currentTimeMillis()}"
        }
    }

    fun closeExitIntent(canonicalId: String) { activeExitIntents.remove(canonicalId) }

    fun activeExitIntent(canonicalId: String): String? = activeExitIntents[canonicalId]

    internal fun clearAllForTest() { entries.clear(); activeExitIntents.clear() }
}

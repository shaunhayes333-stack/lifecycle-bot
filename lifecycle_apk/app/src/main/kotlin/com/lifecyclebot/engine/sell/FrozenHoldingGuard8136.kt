package com.lifecyclebot.engine.sell

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8136 — a frozen token account is a holding, not a zero balance.
 *
 * The wallet read drops every mint whose parsed token account reads `state=frozen` from the inventory map
 * (WALLET_FROZEN_TOKEN_IGNORED_7253). 8122 handed a frozen coin's sell to the balance-proof poller, and the poller read
 * "absent from a complete snapshot" twice as ZERO_BALANCE_CONFIRMED and closed the position as if the wallet were
 * empty: a ghost exit for tokens that are still in the wallet (ALrNcQ). A frozen account cannot be closed, burned or
 * transferred by the owner, so absence caused by the freeze proves nothing about the balance.
 *
 * While the mint's account is frozen the poller holds the wait (no zero read, no close, no sell route) and re-checks
 * on a slow clock; a thaw seen by any wallet read returns the mint to the normal proof path.
 */
object FrozenHoldingGuard8136 {

    const val FROZEN_RECHECK_MS_8136 = 120_000L
    private const val CAP_8136 = 2_000

    /** Pure: may a zero/absent read finalise the position as zero-balance? Never while its token account is frozen. */
    fun zeroFinalityAllowed8136(frozen: Boolean): Boolean = !frozen

    /** Live read of the parsed token-account state (fed by every wallet token read). */
    fun isFrozen8136(mint: String): Boolean =
        try { com.lifecyclebot.engine.truth.WalletTokenAccountStateAuthority7253.isFrozen(mint) } catch (_: Throwable) { false }

    private val heldSince = ConcurrentHashMap<String, Long>()

    /** Returns true the first time [mint] is held in this wait (so the caller logs once, not every re-check). */
    fun noteHeld8136(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (mint.isBlank()) return false
        if (heldSince.size > CAP_8136) heldSince.clear()
        return heldSince.putIfAbsent(mint, nowMs) == null
    }

    fun clear8136(mint: String) { heldSince.remove(mint) }

    fun isHeld8136(mint: String): Boolean = heldSince.containsKey(mint)

    fun heldCount8136(): Int = heldSince.size

    internal fun clearForTest8136() = heldSince.clear()
}

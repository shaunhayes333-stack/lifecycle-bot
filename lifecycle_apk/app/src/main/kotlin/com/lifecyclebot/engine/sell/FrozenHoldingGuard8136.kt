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
        ensureLoaded8185()
        if (heldSince.size > CAP_8136) heldSince.clear()
        val first = heldSince.putIfAbsent(mint, nowMs) == null
        if (first) save8185()  // V5.0.8185
        return first
    }

    fun clear8136(mint: String) {
        ensureLoaded8185()
        if (heldSince.remove(mint) != null) save8185()  // V5.0.8185
    }

    fun isHeld8136(mint: String): Boolean { ensureLoaded8185(); return heldSince.containsKey(mint) }

    fun heldCount8136(): Int { ensureLoaded8185(); return heldSince.size }

    internal fun clearForTest8136() = heldSince.clear()

    // ── V5.0.8185 §FROZEN_HOLDS_SURVIVE_A_RESTART ──
    // The frozen hold lived only in memory: after a restart the poller forgot which waits it held for a freeze (it logged
    // and re-learned each one), and nothing re-checked a thaw for a holding whose account no read had listed yet (the
    // frozen-state authority starts empty, so "not frozen" proved nothing — FROZEN_THAW_UNPROVEN_AFTER_RESTART_8144 for
    // ever). The holds are now persisted beside the frozen-holdings sub-ledger (same atomic file pattern), restored on the
    // first read, and the thaw sweep (BalanceProofPoller, every 30 s) proves a thaw from a parsed account read since boot
    // that listed the account NOT frozen ([thawObserved8185]). Never a zero balance, never a close.
    private const val FILE_8185 = "frozen_holding_guard_8185.json"
    @Volatile private var loaded8185 = false

    /** A parsed token-account read in this process listed [mint]'s account and it was not frozen: a chain-proven thaw. */
    fun thawObserved8185(mint: String): Boolean =
        try { com.lifecyclebot.engine.truth.WalletTokenAccountStateAuthority7253.observedUnfrozen8185(mint) } catch (_: Throwable) { false }

    /** Pure: the held set as text. */
    internal fun encode8185(held: Map<String, Long>): String {
        val o = org.json.JSONObject()
        for ((m, t) in held) if (m.isNotBlank()) o.put(m, t)
        return org.json.JSONObject().put("v", 1).put("held", o).toString()
    }

    /** Pure: [encode8185] text back to mint -> heldSince (bad rows dropped, at most the guard's cap). */
    internal fun decode8185(text: String?): Map<String, Long> {
        if (text.isNullOrBlank()) return emptyMap()
        return try {
            val o = org.json.JSONObject(text).optJSONObject("held") ?: return emptyMap()
            val out = LinkedHashMap<String, Long>()
            for (k in o.keys()) {
                val t = o.optLong(k, 0L)
                if (k.isNotBlank() && t > 0L && out.size < CAP_8136) out[k] = t
            }
            out
        } catch (_: Throwable) { emptyMap() }
    }

    private fun file8185(): java.io.File? = com.lifecyclebot.AATEApp.appContextOrNull()?.let { java.io.File(it.filesDir, FILE_8185) }

    private fun ensureLoaded8185() {
        if (loaded8185) return
        // No store yet (early boot / JVM test): latching now would let the next save replace the file with empty state.
        val f = try { file8185() } catch (_: Throwable) { null } ?: return
        synchronized(this) {
            if (loaded8185) return
            loaded8185 = true
            try { if (f.exists()) for ((m, t) in decode8185(f.readText())) heldSince.putIfAbsent(m, t) } catch (_: Throwable) {}
        }
    }

    private fun save8185() {
        try {
            if (!loaded8185) return
            val f = file8185() ?: return
            val text = encode8185(HashMap(heldSince))
            val tmp = java.io.File(f.parentFile, f.name + ".tmp8185")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() } }
        } catch (_: Throwable) {}
    }
}

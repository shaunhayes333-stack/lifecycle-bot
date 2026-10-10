package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8019 — a coin the bot just sold is not bought again by another lane a minute later.
 *
 * 5.0.8018 live: CRYPTO_ALT sold HXHZT27p at 00:11:18 (trail), QUALITY bought it back at 00:12:58.
 * The per-mint cooldown is 30 s and lives in the meme executor; the crypto lane and the specialist
 * lanes each read their own. One lockout, fed by the canonical close bus, read by every live entry:
 * [LOCK_MS] after any whole-position close of a mint, no lane opens it again — except through the
 * confirmed-run re-entry ticket (RunnerPlay8018), which exists for exactly the coin that keeps
 * running after the bot sold it, and a manual trade.
 */
object RebuyLockout8019 {
    const val LOCK_MS_8019 = 10L * 60_000L
    private val closedAt = ConcurrentHashMap<String, Long>()
    private val refused = AtomicLong(0)

    /** Pure: is a mint closed [sinceCloseMs] ago still locked (no re-entry ticket)? */
    fun locked8019(sinceCloseMs: Long, ticket: Boolean): Boolean = !ticket && sinceCloseMs in 0L until LOCK_MS_8019

    /** CanonicalFinalizedTradeBus6464: a whole-position close. */
    fun onClose8019(mint: String, atMs: Long) {
        if (mint.isBlank()) return
        if (closedAt.size > 4_000) closedAt.entries.removeIf { atMs - it.value > LOCK_MS_8019 }
        closedAt[mint] = atMs
    }

    /** Live entry gates: the refusal label when [mint] was closed inside the lockout, else null. */
    fun refusal8019(mint: String, nowMs: Long = System.currentTimeMillis()): String? {
        val at = closedAt[mint] ?: return null
        val ticket = try { RunnerPlay8018.ticketActive8018(mint, nowMs) } catch (_: Throwable) { false }
        if (!locked8019(nowMs - at, ticket)) return null
        refused.incrementAndGet()
        try { PipelineHealthCollector.labelInc("REBUY_LOCKOUT_REFUSED_8019") } catch (_: Throwable) {}
        return "REBUY_LOCKOUT_8019"
    }

    fun statusLine(): String = "locked=${closedAt.values.count { System.currentTimeMillis() - it < LOCK_MS_8019 }} refused=${refused.get()} window=${LOCK_MS_8019 / 60_000}m"
}

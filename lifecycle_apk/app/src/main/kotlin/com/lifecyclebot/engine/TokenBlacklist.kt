package com.lifecyclebot.engine

import android.content.Context

/**
 * TokenBlacklist
 *
 * Persistent set of token mints that should never be traded this session.
 * A token is added when:
 *   • Safety checker hard-blocks it
 *   • Stop loss fires (token failed — don't re-enter)
 *   • Manual user block
 *   • Rug pattern detected
 *
 * Survives across bot stop/start within the same app session.
 * Cleared on app restart (intentional — fresh start each day).
 *
 * The blacklist short-circuits ALL other checks — if a mint is here,
 * entry score is immediately set to 0 and no buy is possible.
 */
object TokenBlacklist {

    private val blocked = mutableSetOf<String>()
    private val reasons = mutableMapOf<String, String>()
    private val timestamps = mutableMapOf<String, Long>()

    data class BlockedToken(
        val mint: String,
        val reason: String,
        val blockedAt: Long,
    )

    fun block(mint: String, reason: String) {
        blocked.add(mint)
        reasons[mint]    = reason
        timestamps[mint] = System.currentTimeMillis()
    }

    private fun isFalseSafetyBlacklist(reason: String): Boolean {
        val r = reason.uppercase()
        return r.contains("RUGCHECK PENDING") ||
            r.contains("RUGCHECK API TIMEOUT") ||
            r.contains("PENDING_REVIEW") ||
            r.contains("SAFETY_RUN_FAILED") ||
            r.contains("PARTIAL_DATA") ||
            (r.contains("LIQUIDITY") && !r.contains("NO VIABLE") && !r.contains("ZERO")) ||
            r.contains("LOW_LIQUIDITY")
    }

    fun isBlocked(mint: String): Boolean {
        if (!blocked.contains(mint)) return false
        val reason = reasons[mint] ?: ""
        // V5.9.1561 — rehabilitate false hard blockers created by older builds.
        // Pending/timeout/partial safety and low-but-nonzero liquidity are penalties
        // or size reducers, not persistent BLACKLISTED_TOKEN causes.
        if (isFalseSafetyBlacklist(reason)) {
            unblock(mint)
            try { ForensicLogger.lifecycle("FALSE_BLACKLIST_REHABILITATED", "mint=${mint.take(10)} reason=${reason.take(120)}") } catch (_: Throwable) {}
            return false
        }
        return true
    }

    fun getBlockReason(mint: String): String = reasons[mint] ?: "Unknown"

    fun unblock(mint: String) {
        blocked.remove(mint)
        reasons.remove(mint)
        timestamps.remove(mint)
    }

    fun getAll(): List<BlockedToken> = blocked.map { mint ->
        BlockedToken(mint, reasons[mint] ?: "", timestamps[mint] ?: 0L)
    }.sortedByDescending { it.blockedAt }

    fun clear() {
        blocked.clear()
        reasons.clear()
        timestamps.clear()
    }

    val count: Int get() = blocked.size
    
    fun getBlacklistSize(): Int = blocked.size
}

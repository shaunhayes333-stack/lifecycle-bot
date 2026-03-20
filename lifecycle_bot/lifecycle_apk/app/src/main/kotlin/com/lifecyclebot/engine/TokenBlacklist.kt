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

    fun isBlocked(mint: String): Boolean = blocked.contains(mint)

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
}

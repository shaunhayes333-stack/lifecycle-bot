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
 *
 * V5.3: Added TTL-based expiry. Rug-detection blocks expire after 24h.
 *       Entries with 0% price drop (false positives from old bug) are
 *       auto-removed immediately on isBlocked() check.
 */
object TokenBlacklist {

    // V5.3: How long rug-detection blocks persist. Safety/manual blocks are permanent.
    private const val RUG_BLOCK_TTL_MS = 24 * 3_600_000L  // 24 hours

    private val blocked    = mutableSetOf<String>()
    private val reasons    = mutableMapOf<String, String>()
    private val timestamps = mutableMapOf<String, Long>()

    data class BlockedToken(
        val mint: String,
        val reason: String,
        val blockedAt: Long,
    )

    fun block(mint: String, reason: String) {
        // V5.3: Guard against false-positive rug entries (price drop <= 0%)
        if (reason.contains("Rug detected") && isFalsePositiveRug(reason)) {
            ErrorLogger.warn("TokenBlacklist", "⚠️ Skipping false-positive rug block for $mint: $reason")
            return
        }
        blocked.add(mint)
        reasons[mint]    = reason
        timestamps[mint] = System.currentTimeMillis()
    }

    /**
     * V5.3: True if a rug-detection reason looks like a false positive.
     * "price -0%" or price drop below 5% cannot be a real rug.
     */
    private fun isFalsePositiveRug(reason: String): Boolean {
        // Match patterns like "price -0%", "price -1%", "price -2%", ... "price -4%"
        val match = Regex("price -(\\d+)%").find(reason)
        if (match != null) {
            val dropPct = match.groupValues[1].toIntOrNull() ?: return false
            return dropPct < 5
        }
        return false
    }

    /**
     * V5.3: Check with auto-expiry for rug-detection entries.
     * Safety blocks and manual blocks are permanent (no TTL).
     */
    fun isBlocked(mint: String): Boolean {
        if (!blocked.contains(mint)) return false

        val reason = reasons[mint] ?: ""
        val blockedAt = timestamps[mint] ?: return true

        // V5.3: Auto-expire rug detection blocks after 24h
        if (reason.contains("Rug detected")) {
            val elapsed = System.currentTimeMillis() - blockedAt
            if (elapsed >= RUG_BLOCK_TTL_MS || isFalsePositiveRug(reason)) {
                // Auto-unblock: entry has expired or was a false positive
                ErrorLogger.info("TokenBlacklist", "♻️ Auto-unblocking $mint (reason: $reason, age: ${elapsed / 3_600_000}h)")
                unblock(mint)
                return false
            }
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

    /**
     * V5.3: Remove all false-positive rug entries (price drop < 5%).
     * Call once at bot startup to clear legacy bad entries.
     */
    fun clearFalsePositiveRugs() {
        val toRemove = blocked.filter { mint ->
            val reason = reasons[mint] ?: ""
            reason.contains("Rug detected") && isFalsePositiveRug(reason)
        }
        toRemove.forEach { mint ->
            ErrorLogger.info("TokenBlacklist", "🧹 Clearing false-positive rug block: $mint (${reasons[mint]})")
            unblock(mint)
        }
        if (toRemove.isNotEmpty()) {
            ErrorLogger.info("TokenBlacklist", "✅ Cleared ${toRemove.size} false-positive rug entries")
        }
    }

    fun clear() {
        blocked.clear()
        reasons.clear()
        timestamps.clear()
    }

    val count: Int get() = blocked.size

    fun getBlacklistSize(): Int = blocked.size
}

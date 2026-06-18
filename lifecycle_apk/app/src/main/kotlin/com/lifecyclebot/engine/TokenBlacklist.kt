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
        // V5.0.3844 — blacklist is only for true mint-level malicious/fatal
        // conditions. Learning/history/symbol-loss reasons are PENALTY_ONLY and
        // must never become BLACKLIST_SHADOW / hard live-route blocks.
        if (!isTrueBlacklistReason(reason)) {
            try {
                ForensicLogger.lifecycle(
                    "BUY_GATE_DECISION",
                    "mint=${mint.take(10)} symbol=? decision=PENALTY_ONLY reason=${reason.take(140)} source=TokenBlacklist liveEligible=true",
                )
            } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("BUY_GATE_PENALTY_ONLY_TOKEN_BLACKLIST_SOFT_REASON") } catch (_: Throwable) {}
            return
        }
        blocked.add(mint)
        reasons[mint]    = reason
        timestamps[mint] = System.currentTimeMillis()
    }

    fun isTrueBlacklistReason(reason: String): Boolean {
        val r = reason.uppercase()
        if (isSoftPenaltyOnlyReason(reason)) return false
        return r.contains("KNOWN MALICIOUS") ||
            r.contains("VERIFIED BLACKLIST") ||
            r.contains("MINT BLACKLIST") ||
            r.contains("CONFIRMED RUG") ||
            r.contains("CONFIRMED_RUG_COLLAPSE") ||
            r.contains("RUGCHECK_CONFIRMED") ||
            r.contains("RUGGED") ||
            r.contains("HONEYPOT") ||
            r.contains("CANNOT SELL") ||
            r.contains("NO SELL ROUTE") ||
            r.contains("SELL QUOTE IMPOSSIBLE") ||
            r.contains("NO EXECUTABLE ROUTE") ||
            r.contains("SELL SIMULATION FAIL") ||
            r.contains("MALICIOUS CONTRACT") ||
            r.contains("FREEZE AUTHORITY ACTIVE") ||
            r.contains("MINT AUTHORITY ACTIVE") ||
            r.contains("LP 0%") ||
            r.contains("LP UNLOCKED") ||
            r.contains("UNLOCKED LIQUIDITY") ||
            r.contains("ZERO LIQUIDITY") ||
            r.contains("TOP HOLDER") ||
            r.contains("HOLDER CONCENTRATION") ||
            r.contains("BASE_OR_QUOTE_MINT_AS_TARGET")
    }

    fun isSoftPenaltyOnlyReason(reason: String): Boolean {
        val r = reason.uppercase()
        return r.contains("2+ LOSSES") ||
            r.contains("REPEATED LOSSES") ||
            r.contains("PRIOR LOSSES") ||
            r.contains("SAME SYMBOL") ||
            r.contains("DUPLICATE SYMBOL") ||
            r.contains("SYMBOL LOSS") ||
            r.contains("PREVIOUS BAD TRADE") ||
            r.contains("LOW SCORE") ||
            r.contains("LOW CONFIDENCE") ||
            r.contains("LEARNING") ||
            r.contains("NEGATIVE SIGNAL") ||
            r.contains("NARRATIVE") ||
            r.contains("UNCERTAINTY") ||
            r.contains("RUGCHECK PENDING") ||
            r.contains("RUGCHECK API TIMEOUT") ||
            (r.contains("RUG DETECTED") && !r.contains("CONFIRMED_RUG_COLLAPSE")) ||
            r.contains("UNCONFIRMED_PRICE_COLLAPSE") ||
            r.contains("PENDING_REVIEW") ||
            r.contains("SAFETY_RUN_FAILED") ||
            r.contains("PARTIAL_DATA") ||
            r.contains("LIQUIDITY DEPTH") ||
            r.contains("LIQUIDITY_BELOW") ||
            r.contains("BELOW_LIVE_MIN") ||
            (r.contains("LIQUIDITY") && !r.contains("ZERO") && !r.contains("LP 0%") && !r.contains("UNLOCKED")) ||
            r.contains("LOW_LIQUIDITY")
    }

    private fun isFalseSafetyBlacklist(reason: String): Boolean {
        return isSoftPenaltyOnlyReason(reason)
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
        try {
            ForensicLogger.lifecycle(
                "BUY_GATE_DECISION",
                "mint=${mint.take(10)} symbol=? decision=HARD_BLOCK reason=${reason.take(140)} source=TokenBlacklist liveEligible=false",
            )
        } catch (_: Throwable) {}
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

package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.execution.MintIntegrityGate

/**
 * V5.0.3692 — final live pre-broadcast rug defense.
 *
 * This is the layer that was missing from the earlier audit: LiveBuyAdmissionGate
 * only proved safety was present/fresh/not HARD_BLOCK. It did NOT hard-stop
 * live buys when RugCheck was still pending/unknown, holder data was absent,
 * mint/freeze authority was unknown/active, exact mint was quarantined, or a
 * holder-concentration rug shape was visible.
 *
 * This gate is intentionally LIVE-only and immediately-before-broadcast. Paper
 * training can keep sampling; real SOL cannot.
 */
object PreTradeHardGate {
    data class Verdict(val allowed: Boolean, val reason: String, val detail: String = "")

    private const val MIN_LIVE_LIQ_USD = 1_500.0
    private const val TOP_HOLDER_FATAL_PCT = 35.0
    private const val SAFETY_FRESH_MS = 120_000L

    private fun block(ts: TokenState, reason: String, detail: String): Verdict {
        try {
            ForensicLogger.lifecycle(
                "PRETRADE_HARD_BLOCK",
                "mint=${ts.mint.take(10)} symbol=${ts.symbol} reason=$reason detail=${detail.take(180)}"
            )
            PipelineHealthCollector.labelInc("PRETRADE_HARD_BLOCK_$reason")
        } catch (_: Throwable) {}
        return Verdict(false, reason, detail)
    }

    fun requireLiveBuyAllowed(ts: TokenState, callSite: String): Verdict {
        val mint = ts.mint
        val safety = ts.safety
        val now = System.currentTimeMillis()

        if (mint.isBlank() || mint.length < 30) return block(ts, "INVALID_EXACT_MINT", "mint=${mint.take(12)} site=$callSite")
        if (MintIntegrityGate.isSystemOrStablecoinMint(mint)) return block(ts, "BASE_OR_QUOTE_MINT_AS_TARGET", mint)
        if (TokenBlacklist.isBlocked(mint)) return block(ts, "EXACT_MINT_BLACKLISTED", TokenBlacklist.getBlockReason(mint))
        if (QuarantineStore.isQuarantined(mint)) return block(ts, "EXACT_MINT_QUARANTINED", mint.take(12))

        val safetyAt = maxOf(ts.lastSafetyCheck, safety.checkedAt)
        val safetyAge = now - safetyAt
        if (safetyAt <= 0L || safetyAge > SAFETY_FRESH_MS) {
            return block(ts, "SAFETY_PROOF_STALE_OR_MISSING", "checkedAt=$safetyAt ageMs=$safetyAge")
        }
        if (safety.tier == SafetyTier.HARD_BLOCK || safety.isBlocked) {
            return block(ts, "SAFETY_HARD_BLOCK", safety.hardBlockReasons.joinToString("|").ifBlank { safety.summary })
        }

        val rcStatus = safety.rugcheckStatus.uppercase()
        if (rcStatus.isBlank() || rcStatus in setOf("UNKNOWN", "TIMEOUT", "PENDING", "PENDING_REVIEW", "ERROR")) {
            return block(ts, "RUGCHECK_PENDING_OR_UNKNOWN", "status=$rcStatus")
        }
        // RugModel/RugCheck convention in this codebase: 0 = confirmed rug/fatal,
        // 1 = pending/needs-data and should already be caught above when status is pending.
        if (safety.rugcheckScore == 0) return block(ts, "RUGCHECK_CONFIRMED_RUG", "score=0")

        if (safety.mintAuthorityDisabled != true) {
            return block(ts, "MINT_AUTHORITY_ACTIVE_OR_UNKNOWN", "mintAuthorityDisabled=${safety.mintAuthorityDisabled}")
        }
        if (safety.freezeAuthorityDisabled != true) {
            return block(ts, "FREEZE_AUTHORITY_ACTIVE_OR_UNKNOWN", "freezeAuthorityDisabled=${safety.freezeAuthorityDisabled}")
        }

        if (!safety.liqConfirmed && ts.lastLiquidityUsd < MIN_LIVE_LIQ_USD) {
            return block(ts, "LIQUIDITY_PROOF_INCOMPLETE", "liq=${ts.lastLiquidityUsd} liqConfirmed=${safety.liqConfirmed}")
        }
        if (ts.lastLiquidityUsd > 0.0 && ts.lastLiquidityUsd < MIN_LIVE_LIQ_USD) {
            return block(ts, "LIQUIDITY_BELOW_LIVE_MIN", "liq=${ts.lastLiquidityUsd} min=$MIN_LIVE_LIQ_USD")
        }

        if (!ts.holderDataResolved) return block(ts, "HOLDER_DATA_PENDING", "holderDataResolved=false")
        val topHolder = listOfNotNull(ts.topHolderPct, safety.topHolderPct.takeIf { it >= 0.0 }).maxOrNull() ?: -1.0
        if (topHolder < 0.0) return block(ts, "HOLDER_DATA_UNKNOWN", "topHolderPct missing")
        if (topHolder >= TOP_HOLDER_FATAL_PCT) return block(ts, "TOP_HOLDER_FATAL_CONCENTRATION", "topHolderPct=$topHolder")

        val text = buildString {
            append(safety.hardBlockReasons.joinToString("|")); append('|')
            append(safety.softPenalties.joinToString("|") { it.first }); append('|')
            append(safety.summary); append('|'); append(ts.lastError)
        }.uppercase()
        // V5.0.3706 — wallet-style risk text backstop. Phantom/Jupiter can show
        // warnings such as "Single holder ownership", "Unverified token", and
        // "top 10 users hold more than 50%" even when our numeric holder fields
        // are still hydrating. LIVE must treat those as pre-broadcast fatal.
        val fatalNeedles = listOf(
            "HONEYPOT", "CANNOT_SELL", "CONFIRMED_RUG", "KNOWN_RUGGER", "BLACKLIST", "BANNED", "FROZEN_AUTHORITY",
            "SINGLE HOLDER", "SINGLE_HOLDER", "SINGLE_HOLDER_OWNERSHIP", "UNVERIFIED TOKEN", "UNVERIFIED_TOKEN",
            "HIGH HOLDER CONCENTRATION", "HOLDER CONCENTRATION", "TOP10", "TOP 10", "TOP HOLDERS", "TOP_HOLDERS",
        )
        val fatal = fatalNeedles.firstOrNull { text.contains(it) }
        if (fatal != null) return block(ts, "FATAL_SAFETY_TEXT", fatal)

        return Verdict(true, "ALLOW", "site=$callSite")
    }
}

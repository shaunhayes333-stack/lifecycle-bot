package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.6525 §PRE_V3_RETURN_TELEMETRY — canonical helper to stamp every
 * pre-V3 return in processTokenCycle with a single, greppable marker.
 *
 * Operator source-level audit (Feb 2026):
 *   > "Instrument every pre-V3 Meme return before altering strategy.
 *   >  Add exactly one helper. Then stamp every return from SCAN_CB
 *   >  entry through ExecutableEntryAuthority6450.gate(). Also wire
 *   >  PHASE.SAFETY properly. One new snapshot will then tell us
 *   >  exactly whether Meme is dying at NO_PAIR, CANONICAL_MARK,
 *   >  HARD_RUG, DISTRIBUTION, BLACKLIST, etc., instead of guessing."
 *
 * Contract:
 *   - Every early return inside processTokenCycle that happens BEFORE
 *     ExecutableEntryAuthority6450.gate() MUST call this helper with a
 *     canonical reason token.
 *   - Reason tokens are UPPER_SNAKE_CASE, stable across builds, so the
 *     operator can grep `PRE_V3_RETURN_<REASON>` in the pipeline funnel
 *     dump and see the exact distribution of pre-V3 exits.
 *
 * Canonical reason tokens (keep this list sorted alphabetically):
 *   BLACKLIST                    TokenBlacklist.isBlocked(mint)
 *   CANONICAL_MARK_REJECTED      current mark failed canonical acceptance
 *   CANONICAL_OCCUPANCY          canonical registry occupancy denies entry
 *   DECIMAL_JUMP                 token decimals mismatch (bad pair)
 *   DISTRIBUTION_FADE            DistributionFadeAvoider hard-blocked
 *   HARD_RUG                     HardRugPreFilter.decide() returned block
 *   NO_PAIR                      dexscreener/fallback pair unavailable
 *   POSITION_ALREADY_OPEN        position map already has this mint
 *   SAFETY                       TokenSafetyChecker hard-blocked
 *   STALE_PRICE                  price cache expired past acceptable ttl
 *   TOKEN_MAP_MISSING            token map lookup failed
 *
 * Add a new token here BEFORE using it. Do not free-form strings — the
 * grep-then-count workflow depends on the tokens being an enum in
 * effect.
 */
object PreV3ReturnTelemetry6525 {

    fun stamp(ts: TokenState, reason: String) {
        try {
            PipelineHealthCollector.labelInc("PRE_V3_RETURN_$reason")
        } catch (_: Throwable) {}
        try {
            ForensicLogger.lifecycle(
                "PRE_V3_RETURN",
                "mint=${ts.mint.take(10)} symbol=${ts.symbol} reason=$reason",
            )
        } catch (_: Throwable) {}
    }

    /** Convenience overload for call-sites that only have the mint string. */
    fun stampMint(mint: String, symbol: String, reason: String) {
        try {
            PipelineHealthCollector.labelInc("PRE_V3_RETURN_$reason")
        } catch (_: Throwable) {}
        try {
            ForensicLogger.lifecycle(
                "PRE_V3_RETURN",
                "mint=${mint.take(10)} symbol=$symbol reason=$reason",
            )
        } catch (_: Throwable) {}
    }
}

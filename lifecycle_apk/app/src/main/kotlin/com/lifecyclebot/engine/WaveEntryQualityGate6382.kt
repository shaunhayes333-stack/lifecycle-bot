package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * V5.0.6382 — WAVE ENTRY QUALITY GATE.
 *
 * Operator directive (verbatim, run 5.0.6381):
 *   "the bot buys in the wrong waves of the chart... it needs to use the full
 *    aate stack to find profitable trades live asap"
 *
 * Root cause: FDG/EXEC finality gates already validate SAFETY / ROUTE / RUG /
 * LIQ / lane authority — but none of them look at the token's PRICE POSITION
 * within its own recent wave. A candidate that has already spiked +200% in the
 * last hour with parabolic 1m acceleration is by definition the WORST possible
 * entry: retail top-tick. This gate rejects the "already-blown-off-the-top"
 * entry pattern with narrow, evidence-backed thresholds.
 *
 * DOCTRINE COMPLIANCE:
 *   - Never disables a lane. Rejects a SINGLE candidate; the lane keeps
 *     scanning other tokens.
 *   - Self-clearing: as soon as the token's 1h change or 1m acceleration
 *     falls out of parabolic-top territory, entry re-qualifies naturally.
 *   - Fail-open: any exception returns `null` (allow), so a data outage
 *     cannot stop trading. The FDG hard vetoes still fire regardless.
 *   - Score-band aware: LOW-CONVICTION entries (score < 40) have a tighter
 *     top-tick veto because the chase risk on a weak signal is enormous;
 *     HIGH-CONVICTION entries (score >= 60) get more headroom because the
 *     full AATE stack has already validated the setup.
 *
 * Gate returns:
 *   - null  => allow (default, safe)
 *   - String reason => veto with this canonical reason
 */
object WaveEntryQualityGate6382 {

    // 1h change above this = "already extended". Score-band adjusted.
    private const val EXT_1H_LOW_SCORE = 80.0     // score < 40 : veto at +80% 1h
    private const val EXT_1H_MID_SCORE = 140.0    // score 40-59 : veto at +140% 1h
    private const val EXT_1H_HIGH_SCORE = 220.0   // score >= 60 : veto at +220% 1h

    // 1m parabolic acceleration: last 3 candles cumulative gain over this = top-tick.
    private const val PARABOLIC_3M_PCT = 45.0
    // Any single 1m candle over this AND already extended = ejection candle.
    private const val EJECTION_1M_PCT = 25.0

    // Only evaluate when we have a real recent price basis; the gate MUST be
    // fail-open if we don't have data (never block on missing history).
    private const val MIN_HISTORY_CANDLES = 3

    fun evaluate(ts: TokenState, entryScore: Int): String? {
        return try {
            val change1hPct = ts.lastPriceChange1h
            // Score-band ceiling.
            val extCeiling = when {
                entryScore >= 60 -> EXT_1H_HIGH_SCORE
                entryScore >= 40 -> EXT_1H_MID_SCORE
                else -> EXT_1H_LOW_SCORE
            }
            val extended1h = change1hPct.isFinite() && change1hPct >= extCeiling

            val hist = try { ts.history.toList() } catch (_: Throwable) { emptyList() }
            if (hist.size < MIN_HISTORY_CANDLES) {
                // No 1m acceleration data. Fall back to 1h-only extended check —
                // and only if the extension is SEVERE (2× the score-band ceiling)
                // do we veto; anything below that with no 1m context is allowed.
                if (extended1h && change1hPct >= extCeiling * 2.0) {
                    return "WAVE_TOO_LATE_1H_SEVERE change1h=${"%.0f".format(change1hPct)}% score=$entryScore ceiling=${"%.0f".format(extCeiling)}%"
                }
                return null
            }

            // Compute cumulative return over last 3 completed 1m candles.
            val last3 = hist.takeLast(MIN_HISTORY_CANDLES)
            val first = last3.first().priceUsd
            val latest = last3.last().priceUsd
            if (first <= 0.0 || latest <= 0.0 || !first.isFinite() || !latest.isFinite()) return null
            val cum3mPct = ((latest / first) - 1.0) * 100.0
            val parabolic = cum3mPct >= PARABOLIC_3M_PCT

            // Single ejection candle: any of the last 3 1m candles ≥ +25%.
            val ejection = last3.any { c ->
                val prev = if (c.openUsd > 0.0) c.openUsd else 0.0
                if (prev <= 0.0 || !prev.isFinite() || !c.priceUsd.isFinite()) false
                else ((c.priceUsd / prev) - 1.0) * 100.0 >= EJECTION_1M_PCT
            }

            when {
                // Parabolic + already extended = classic retail top-tick.
                extended1h && parabolic ->
                    "WAVE_TOO_LATE_PARABOLIC 1h=${"%.0f".format(change1hPct)}% 3m=${"%.0f".format(cum3mPct)}% score=$entryScore"
                // Even without severe 1h, a hot ejection candle right now is a chase.
                ejection && change1hPct >= (extCeiling * 0.5) ->
                    "WAVE_TOO_LATE_EJECTION 1h=${"%.0f".format(change1hPct)}% 3m=${"%.0f".format(cum3mPct)}% score=$entryScore"
                extended1h && cum3mPct >= (PARABOLIC_3M_PCT * 0.6) ->
                    "WAVE_TOO_LATE_EXTENDED 1h=${"%.0f".format(change1hPct)}% 3m=${"%.0f".format(cum3mPct)}% score=$entryScore ceiling=${"%.0f".format(extCeiling)}%"
                else -> null
            }
        } catch (_: Throwable) { null }
    }

    // Public test hook (deterministic) — invariant tests can drive this without
    // constructing a full TokenState.
    internal fun evaluateForTests(
        change1hPct: Double,
        recent1mCandles: List<Pair<Double, Double>>, // (open, close)
        entryScore: Int,
    ): String? {
        val extCeiling = when {
            entryScore >= 60 -> EXT_1H_HIGH_SCORE
            entryScore >= 40 -> EXT_1H_MID_SCORE
            else -> EXT_1H_LOW_SCORE
        }
        val extended1h = change1hPct.isFinite() && change1hPct >= extCeiling
        if (recent1mCandles.size < MIN_HISTORY_CANDLES) {
            if (extended1h && change1hPct >= extCeiling * 2.0) {
                return "WAVE_TOO_LATE_1H_SEVERE change1h=${"%.0f".format(change1hPct)}% score=$entryScore ceiling=${"%.0f".format(extCeiling)}%"
            }
            return null
        }
        val last3 = recent1mCandles.takeLast(MIN_HISTORY_CANDLES)
        val first = last3.first().second
        val latest = last3.last().second
        if (first <= 0.0 || latest <= 0.0) return null
        val cum3mPct = ((latest / first) - 1.0) * 100.0
        val parabolic = cum3mPct >= PARABOLIC_3M_PCT
        val ejection = last3.any { (o, c) ->
            o > 0.0 && ((c / o) - 1.0) * 100.0 >= EJECTION_1M_PCT
        }
        return when {
            extended1h && parabolic ->
                "WAVE_TOO_LATE_PARABOLIC 1h=${"%.0f".format(change1hPct)}% 3m=${"%.0f".format(cum3mPct)}% score=$entryScore"
            ejection && change1hPct >= (extCeiling * 0.5) ->
                "WAVE_TOO_LATE_EJECTION 1h=${"%.0f".format(change1hPct)}% 3m=${"%.0f".format(cum3mPct)}% score=$entryScore"
            extended1h && cum3mPct >= (PARABOLIC_3M_PCT * 0.6) ->
                "WAVE_TOO_LATE_EXTENDED 1h=${"%.0f".format(change1hPct)}% 3m=${"%.0f".format(cum3mPct)}% score=$entryScore ceiling=${"%.0f".format(extCeiling)}%"
            else -> null
        }
    }
}

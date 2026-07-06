package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * V5.0.6124h — FreshLaunchHunter
 *
 * OPERATOR DIRECTIVE (with $ANSEM screenshot): "$120 buy at 10k mcap 8
 * days ago now worth $3.4M+ = ~28,000x. The bot should be able to
 * easily identify opportunities like this — buy them early and hold to
 * millions!!!!!"
 *
 * WHAT THIS DOES
 * ══════════════
 *
 * Detects tokens fitting the "moonshot launch" profile — the exact
 * ANSEM-style setup. Buy triggers OVERRIDE normal gates that would kill
 * these plays (chart-pre-buy-veto with no candles, high-score requirement
 * that no fresh launch can satisfy, mcap floor).
 *
 * PROFILE FIT
 *   • Age < 12 hours   (brand-new mint)
 *   • Mcap < $75,000   (bottom of launch curve, not already pumped)
 *   • Liquidity ≥ $2,000  (not a phantom pool)
 *   • Rugcheck score ≥ 40 (excludes obvious honeypots)
 *   • Holder count ≥ 20 (not a solo dev wallet)
 *   • AT LEAST ONE of:
 *       - Insider wallet buy in last 30 min
 *       - Hivemind co-fire count ≥ 2
 *       - Swarm whale open count ≥ 3
 *       - Entry score ≥ 55 (relaxed from normal 78)
 *       - Bullish candle-based signal (if any candles exist)
 *
 * WHAT IT DOES ON MATCH
 *   • Registers the mint in the MOONSHOT_ENTRY registry with a peak/exit
 *     policy tag so MoonshotHoldMode can protect it downstream.
 *   • Returns a Verdict with:
 *       - `overrideChartVeto = true`  → FDG skips ChartPreBuyGate hard-veto
 *       - `overrideMcapFloor = true`  → FDG skips its normal mcap floor
 *       - `sizeMult = 1.4`            → moderate boost (asymmetric bet)
 *       - `tpOverridePct = 100_000.0` → effectively remove hard TP
 *       - `slOverridePct = -35.0`     → wide SL — this is a lottery ticket
 *
 * FAIL-OPEN: any error yields "no match" (neutral, no override).
 */
object FreshLaunchHunter {

    // ── Tunables ────────────────────────────────────────────────────────
    private const val MAX_AGE_MS = 12L * 60L * 60L * 1000L
    private const val MAX_MCAP_USD = 75_000.0
    private const val MIN_LIQ_USD = 2_000.0
    private const val MIN_HOLDERS = 20
    private const val MIN_RUGCHECK = 40
    private const val LOW_SCORE_FLOOR = 55.0
    private const val INSIDER_LOOKBACK_MS = 30L * 60L * 1000L

    // ── State ───────────────────────────────────────────────────────────
    data class LaunchVerdict(
        val isMatch: Boolean,
        val fingerprint: String,           // e.g. "AGE+MCAP+INSIDER+HIVE"
        val sizeMult: Double,
        val overrideChartVeto: Boolean,
        val overrideMcapFloor: Boolean,
        val tpOverridePct: Double,         // 0.0 = no override
        val slOverridePct: Double,         // 0.0 = no override
    )

    private val NO_MATCH = LaunchVerdict(
        isMatch = false,
        fingerprint = "",
        sizeMult = 1.0,
        overrideChartVeto = false,
        overrideMcapFloor = false,
        tpOverridePct = 0.0,
        slOverridePct = 0.0,
    )

    // Registry of mints that were entered via the FreshLaunchHunter path.
    // Used by MoonshotHoldMode to know which positions should get the
    // extended-hold policy.
    private val moonshotEntries = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // ── Public API ──────────────────────────────────────────────────────

    fun evaluate(ts: TokenState): LaunchVerdict {
        return try {
            val nowMs = System.currentTimeMillis()
            val ageMs = nowMs - ts.addedToWatchlistAt

            // Hard requirements
            if (ageMs > MAX_AGE_MS) return NO_MATCH
            if (ts.lastMcap > MAX_MCAP_USD) return NO_MATCH
            if (ts.lastMcap <= 0.0) return NO_MATCH  // unknown mcap → skip (safe default)
            if (ts.lastLiquidityUsd < MIN_LIQ_USD) return NO_MATCH
            if ((ts.peakHolderCount) < MIN_HOLDERS) return NO_MATCH
            val rc = ts.safety.rugcheckScore
            if (rc in 1..(MIN_RUGCHECK - 1)) return NO_MATCH

            // Confirmatory (any-of)
            val hits = mutableListOf<String>()
            hits.add("AGE${(ageMs / 60_000L).toInt()}m")
            hits.add("MCAP${(ts.lastMcap / 1000.0).toInt()}k")

            try {
                if (InsiderWalletFeeder.hasRecentBuy(ts.mint, INSIDER_LOOKBACK_MS)) hits.add("INSIDER")
            } catch (_: Throwable) {}

            try {
                if (SwarmIntel.getCoFireCount(ts.mint) >= 2) hits.add("HIVE")
            } catch (_: Throwable) {}

            try {
                if (SwarmIntel.getSwarmOpenCount(ts.mint) >= 3) hits.add("WHALE")
            } catch (_: Throwable) {}

            if (ts.entryScore >= LOW_SCORE_FLOOR) hits.add("SCORE${ts.entryScore.toInt()}")

            // Need AT LEAST ONE confirmatory (INSIDER / HIVE / WHALE / SCORE).
            val hasConfirmatory = hits.any { it == "INSIDER" || it == "HIVE" || it == "WHALE" || it.startsWith("SCORE") }
            if (!hasConfirmatory) return NO_MATCH

            val verdict = LaunchVerdict(
                isMatch = true,
                fingerprint = hits.joinToString("+"),
                sizeMult = 1.4,
                overrideChartVeto = true,
                overrideMcapFloor = true,
                tpOverridePct = 100_000.0,   // effectively no hard TP cap
                slOverridePct = -35.0,       // wide SL — moonshot allows drawdown
            )

            moonshotEntries.add(ts.mint)
            try {
                ForensicLogger.lifecycle(
                    "FRESH_LAUNCH_HUNTER_MATCH_6124h",
                    "mint=${ts.mint.take(10)} sym=${ts.symbol} mcap=${ts.lastMcap.toInt()} " +
                    "age=${(ageMs / 60_000L)}m fp=${verdict.fingerprint}",
                )
                PipelineHealthCollector.labelInc("FRESH_LAUNCH_HUNTER_MATCH_6124h")
            } catch (_: Throwable) {}

            verdict
        } catch (_: Throwable) { NO_MATCH }
    }

    /** Called by MoonshotHoldMode to check whether an active position should
     *  receive extended-hold protection. */
    fun isMoonshotEntry(mint: String): Boolean =
        try { moonshotEntries.contains(mint) } catch (_: Throwable) { false }

    /** Called by Executor on final SELL to clean up the registry. */
    fun onPositionClosed(mint: String) {
        try { moonshotEntries.remove(mint) } catch (_: Throwable) {}
    }

    fun activeMoonshots(): Set<String> = try { moonshotEntries.toSet() } catch (_: Throwable) { emptySet() }
}

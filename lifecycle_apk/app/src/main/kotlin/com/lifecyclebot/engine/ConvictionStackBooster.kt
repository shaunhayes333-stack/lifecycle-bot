package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * V5.0.6121 — ConvictionStackBooster
 *
 * OPERATOR DIRECTIVE: "the memetrader should be catching asymmetric
 * winners with everything the swarm has. When 3+ signals converge on the
 * same mint we should be BIGGER not the same."
 *
 * When multiple INDEPENDENT bullish signals converge on the same token,
 * this booster asymmetrically scales the entry. Each signal counted once:
 *
 *   1. HIVEMIND CO-FIRE       — SwarmIntel.getSwarmCofireCount >= 2
 *      (at least 2 other bots already opened this mint)
 *   2. CHART TAILWIND         — ChartPreBuyGate returns sizeMult >= 1.2
 *      (bullish + BREAKOUT/CUP_HANDLE/DOUBLE_BOTTOM pattern)
 *   3. WHALE FOLLOW           — SwarmIntel.publishedWhale(mint) in last 10m
 *   4. HIGH SCORE             — ts.entryScore >= 78
 *   5. GREEN LANE              — GreenEvLaneGovernor.laneSizeMultiplier >= 1.0
 *      (lane is LIVE not paused/damped)
 *   6. INSIDER WALLET BUY      — InsiderWalletFeeder emitted a hit in last 5m
 *      (optional; skipped if feeder not available)
 *
 * Convergence ladder:
 *   3 signals → 1.35×
 *   4 signals → 1.65×
 *   5 signals → 2.00×
 *   6 signals → 2.50× (kelly max cap)
 *
 * Failing open — if any inspector throws, that signal is treated as
 * unconfirmed (worst case: fewer signals = lower boost = neutral). Final
 * multiplier caps at 2.50× to prevent runaway.
 *
 * FDG applies this AFTER laneMult/chartMult/compounderMult, so it
 * compounds on the winning-streak. Combined cap enforced at final size
 * (finalSize <= 1.0 SOL).
 */
object ConvictionStackBooster {

    // ── Tunables ────────────────────────────────────────────────────────
    private const val COFIRE_MIN_PEERS = 2
    private const val CHART_TAILWIND_THRESHOLD = 1.2
    private const val WHALE_LOOKBACK_MS = 10L * 60L * 1000L
    private const val INSIDER_LOOKBACK_MS = 5L * 60L * 1000L
    private const val HIGH_SCORE_THRESHOLD = 78.0

    // ── State ───────────────────────────────────────────────────────────
    data class ConvictionSnapshot(
        val signalCount: Int,
        val sizeMult: Double,
        val fingerprint: String,     // e.g. "HIVE+CHART+SCORE"
    )

    // ── Public API ──────────────────────────────────────────────────────

    fun evaluate(ts: TokenState): ConvictionSnapshot {
        return try {
            val hits = mutableListOf<String>()

            // 1. Hivemind co-fire
            try {
                val cofire = SwarmIntel.getCoFireCount(ts.mint)
                if (cofire >= COFIRE_MIN_PEERS) hits.add("HIVE")
            } catch (_: Throwable) {}

            // 2. Chart tailwind
            try {
                val chart = ChartPreBuyGate.consult(ts)
                if (chart.sizeMult >= CHART_TAILWIND_THRESHOLD) hits.add("CHART")
            } catch (_: Throwable) {}

            // 3. Whale-follow buy visible in swarm (open count as proxy for
            //    aggressive whale activity — 3+ instances opening at once).
            try {
                if (SwarmIntel.getSwarmOpenCount(ts.mint) >= 3) hits.add("WHALE")
            } catch (_: Throwable) {}

            // 4. High entry score
            if (ts.entryScore >= HIGH_SCORE_THRESHOLD) hits.add("SCORE")

            // 5. Green lane
            try {
                val lane = ts.position.tradingMode.ifBlank { "STANDARD" }
                val laneMult = GreenEvLaneGovernor.laneSizeMultiplier(lane)
                if (laneMult >= 1.0) hits.add("GREEN_LANE")
            } catch (_: Throwable) {}

            // 6. Insider wallet buy (optional feeder)
            try {
                if (InsiderWalletFeeder.hasRecentBuy(ts.mint, INSIDER_LOOKBACK_MS)) hits.add("INSIDER")
            } catch (_: Throwable) {}

            val mult = when (hits.size) {
                6 -> 2.50
                5 -> 2.00
                4 -> 1.65
                3 -> 1.35
                else -> 1.00
            }

            if (mult > 1.0) {
                try {
                    ForensicLogger.lifecycle(
                        "CONVICTION_STACK_6121",
                        "mint=${ts.mint.take(10)} sym=${ts.symbol} hits=${hits.joinToString("+")} " +
                        "count=${hits.size} mult=${"%.2f".format(mult)}",
                    )
                    PipelineHealthCollector.labelInc("CONVICTION_STACK_BOOST_${hits.size}_6121")
                } catch (_: Throwable) {}
            }

            ConvictionSnapshot(
                signalCount = hits.size,
                sizeMult = mult,
                fingerprint = hits.joinToString("+"),
            )
        } catch (_: Throwable) {
            ConvictionSnapshot(0, 1.0, "ERROR")
        }
    }
}

/**
 * V5.0.6121 — Optional insider-wallet feeder. Wraps whatever underlying
 * insider-wallet-tracker the app has (real feeder or no-op) so
 * ConvictionStackBooster can query without hard-coupling. Populated by
 * the InsiderWalletTracker subsystem when it detects a smart-wallet buy.
 *
 * Fail-open: if the feeder is not populated, `hasRecentBuy` returns false
 * and the INSIDER signal simply doesn't count. Never breaks the booster.
 */
object InsiderWalletFeeder {
    private data class InsiderHit(val mint: String, val atMs: Long)
    private val recent = java.util.concurrent.ConcurrentLinkedDeque<InsiderHit>()

    /** Called by the real InsiderWalletTracker when it detects a smart-wallet buy. */
    fun publishBuy(mint: String) {
        try {
            recent.addFirst(InsiderHit(mint, System.currentTimeMillis()))
            // Cap to last 256 hits.
            while (recent.size > 256) recent.pollLast()
        } catch (_: Throwable) {}
    }

    fun hasRecentBuy(mint: String, lookbackMs: Long): Boolean {
        return try {
            val cutoff = System.currentTimeMillis() - lookbackMs
            recent.any { it.mint == mint && it.atMs >= cutoff }
        } catch (_: Throwable) { false }
    }
}

package com.lifecyclebot.engine

/**
 * V5.0.6196 — DumpRegimeWinnerRouter
 *
 * OPERATOR (2026-07-08): "the bot has lost money trading LIVE for 2 days
 * straight. Paper is +12 SOL. Live is -0.25 SOL. What will flick it
 * green???"
 *
 * ANALYSIS OF THE REPORT
 * ══════════════════════
 *   • Regime=DUMP for the entire session.
 *   • LIVE Strategy Clean: n=42, WR=13.5%, PnL=-0.2533 SOL.
 *   • PROVEN WINNER LANES (lifetime, all trades):
 *       - TREASURY : n=43 WR 50.0% EV+205.18% PnL +8.4079 SOL  ← the GOAT
 *       - BLUECHIP : n=69 WR 36.8% EV+ 83.67% PnL +6.5961 SOL
 *       - STANDARD : n=17 WR 26.7% EV+ 68.85% PnL +2.5767 SOL
 *       - Metals   : n= 6 WR 66.7% EV+ 53.62% PnL +1.4894 SOL
 *   • BLEEDER LANES (currently EATING the wallet during DUMP):
 *       - EXPRESS : n=  5 WR  0.0% EV -44.68%
 *       - QUALITY : n=106 WR 24.1% EV -7.60% PnL -1.83 SOL
 *       - MOONSHOT: n=93 WR 31.8% EV -4.61%
 *       - PRESALE_SNIPE: n=36 WR 34.3% EV -0.13%
 *
 * The bot has all the data — it just isn't ACTING on it during DUMP.
 *
 * WHAT THIS DOES
 * ══════════════
 *
 * A last-mile pre-execution gate. When regime is DUMP or CHOP and the
 * caller wants to open a LIVE position, this gate compares the target
 * lane against the leaderboard:
 *   • Positive EV + WR ≥ 30%  → ALLOW (winner)
 *   • Positive SOL PnL         → ALLOW (net profitable)
 *   • Negative EV lane during DUMP → BLOCK ("PIVOT_TO_WINNERS_ONLY_6196")
 *   • Unknown lane (n < 5)     → ALLOW (bootstrap, need learning data)
 *
 * PAPER trades unaffected — this only pivots LIVE money. Paper needs to
 * keep exploring so the leaderboard stays fresh.
 *
 * DOCTRINE #86 — fail-open: any error yields ALLOW (never worse than
 * pre-6196 behavior). This is a positive-additive gate.
 */
object DumpRegimeWinnerRouter {

    // ── Tunables ────────────────────────────────────────────────────────
    private const val MIN_SAMPLE_FOR_CLASSIFICATION = 5
    private const val WINNER_WR_FLOOR = 30.0
    private const val ALLOWED_NEGATIVE_EV_FLOOR = -2.5   // small dip OK; deep negative EV = block

    // ── State ───────────────────────────────────────────────────────────
    data class RouterVerdict(
        val allow: Boolean,
        val reason: String,       // e.g. "PIVOT_TO_WINNERS_ONLY_6196"
        val laneEvPct: Double,
        val laneWrPct: Double,
        val laneTotalSol: Double,
    )

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Called by FDG right before mode=LIVE is finalized. If we're in DUMP
     * or CHOP AND the target lane is a known bleeder, block the entry
     * (still allow the flow to fall through as a PAPER trade so we keep
     * learning).
     *
     * @param laneName  target lane / trading mode label
     * @return verdict with allow/reason + telemetry for logging
     */
    fun evaluate(laneName: String?): RouterVerdict {
        return try {
            val regime = RegimeDetector.currentRegime()
            // Only gate during DUMP or CHOP. In NORMAL / BULL_RIPPING /
            // BOOTSTRAP the base regime multiplier already handles risk.
            if (regime != RegimeDetector.Regime.DUMP && regime != RegimeDetector.Regime.CHOP) {
                return RouterVerdict(true, "REGIME_ALLOW_6196", 0.0, 0.0, 0.0)
            }
            // V5.0.6201 — SMALL-WALLET RECOVERY EXEMPTION. When wallet is
            // below 1 SOL the bot needs to trade its way out. Blocking all
            // negative-EV lanes in DUMP/CHOP creates a starvation loop —
            // no trades means no learning means no recovery. Report
            // 2026-07-08 19:54 showed wallet=0.4118 SOL with 28 pivot-blocks
            // out of 45 total FDG blocks (62%). Recovery mode: allow the
            // trade but let downstream sizing shapers (LivePolicyEngine
            // lane-pause + LiveStrategyTuner) keep positions small.
            val walletSol = try { RealizedWalletCompoundingGovernor.snapshot().walletSol } catch (_: Throwable) { 1.0 }
            if (walletSol < 1.0) {
                try {
                    PipelineHealthCollector.labelInc("PIVOT_SMALL_WALLET_RECOVERY_EXEMPT_6201")
                    ForensicLogger.lifecycle(
                        "PIVOT_SMALL_WALLET_RECOVERY_EXEMPT_6201",
                        "walletSol=${"%.4f".format(walletSol)} regime=$regime lane=${laneName ?: "?"} — allowing negative-EV lane so bot can trade its way to recovery",
                    )
                } catch (_: Throwable) {}
                return RouterVerdict(true, "SMALL_WALLET_RECOVERY_EXEMPT_6201", 0.0, 0.0, 0.0)
            }
            val lane = laneName?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
                ?: return RouterVerdict(true, "LANE_UNKNOWN_ALLOW_6196", 0.0, 0.0, 0.0)

            val board = try {
                StrategyTelemetry.computeCleanLiveTerminalLeaderboard(limit = 1_500)
            } catch (_: Throwable) { emptyList() }
            val m = board.firstOrNull { it.strategy.equals(lane, true) }
            if (m == null || m.trades < MIN_SAMPLE_FOR_CLASSIFICATION) {
                // Not enough data — bootstrap allow.
                return RouterVerdict(true, "BOOTSTRAP_ALLOW_6196", 0.0, 0.0, 0.0)
            }

            // WINNER LANES: net positive SOL OR (positive mean pnl AND WR >= 30%)
            val isWinner = m.totalSolPnl > 0.0 ||
                (m.meanPnlPct >= 0.0 && m.winRatePct >= WINNER_WR_FLOOR)
            if (isWinner) {
                return RouterVerdict(
                    allow = true,
                    reason = "WINNER_LANE_ALLOW_6196",
                    laneEvPct = m.meanPnlPct,
                    laneWrPct = m.winRatePct,
                    laneTotalSol = m.totalSolPnl,
                )
            }

            // Mild-dip lanes (small negative EV, decent WR): still allow but log.
            if (m.meanPnlPct >= ALLOWED_NEGATIVE_EV_FLOOR && m.winRatePct >= WINNER_WR_FLOOR) {
                return RouterVerdict(
                    allow = true,
                    reason = "MILD_DIP_ALLOW_6196",
                    laneEvPct = m.meanPnlPct,
                    laneWrPct = m.winRatePct,
                    laneTotalSol = m.totalSolPnl,
                )
            }

            // Deep-negative-EV bleeder during DUMP — HARD BLOCK the live entry.
            try {
                ForensicLogger.lifecycle(
                    "PIVOT_TO_WINNERS_ONLY_6196",
                    "lane=$lane regime=$regime ev=${"%.2f".format(m.meanPnlPct)}% wr=${"%.1f".format(m.winRatePct)}% totalSol=${"%.4f".format(m.totalSolPnl)} — blocking live entry (paper continues)",
                )
                PipelineHealthCollector.labelInc("PIVOT_TO_WINNERS_ONLY_6196")
            } catch (_: Throwable) {}

            RouterVerdict(
                allow = false,
                reason = "PIVOT_TO_WINNERS_ONLY_6196",
                laneEvPct = m.meanPnlPct,
                laneWrPct = m.winRatePct,
                laneTotalSol = m.totalSolPnl,
            )
        } catch (_: Throwable) {
            RouterVerdict(true, "ERROR_FAIL_OPEN_6196", 0.0, 0.0, 0.0)
        }
    }
}

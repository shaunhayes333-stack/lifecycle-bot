package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * V5.9.806 — Market regime state machine.
 *
 * Classifies the current market into one of 5 regimes so the rest of the
 * intelligence stack (sub-trader thresholds, WrRecoveryPartial, brain
 * consensus, losing-pattern memory) can adapt their behaviour without
 * each module having to compute its own ad-hoc regime view.
 *
 * Read-only consumer of existing state — never mutates trading data:
 *   • Recent realised PnL from TradeHistoryStore (last 100 closed trades)
 *   • V3 score distribution from WrRecoveryPartial (already collected)
 *   • SOL/SOL-USD slope is OUT OF SCOPE here (kept regime detector pure
 *     to the bot's own performance; SOL price is a noisy proxy anyway).
 *
 * Regimes
 * -------
 *   BULL_RIPPING  — last-100 WR >= 45% AND mean PnL >= +3% AND V3 median >= 35
 *   NORMAL        — WR in 25-45%, PnL near break-even, V3 median 20-40
 *   CHOP          — WR 15-25%, PnL slightly negative
 *   DUMP          — WR < 15% AND mean PnL <= -3%   (current state on the
 *                   operator forensic snapshot of V5.9.805)
 *   DEAD          — fewer than 10 closes in the last hour (no signal)
 *
 * No background task. Regime is recomputed on demand with a 30s memoised
 * cache so frequent reads from sub-traders don't bog down the journal.
 */
object RegimeDetector {

    enum class Regime { BULL_RIPPING, NORMAL, CHOP, DUMP, DEAD, BOOTSTRAP }

    data class RegimeSnapshot(
        val regime: Regime,
        val recentWrPct: Double,
        val recentMeanPnlPct: Double,
        val v3Median: Int,
        val sampleSize: Int,
        val computedAtMs: Long,
    )

    private val cached = AtomicReference<RegimeSnapshot?>(null)
    private const val CACHE_TTL_MS = 30_000L

    fun current(): RegimeSnapshot {
        val now = System.currentTimeMillis()
        val c = cached.get()
        if (c != null && (now - c.computedAtMs) < CACHE_TTL_MS) return c
        val fresh = recompute(now)
        cached.set(fresh)
        return fresh
    }

    fun currentRegime(): Regime = current().regime

    /**
     * V5.9.1070 — Force-expire regime cache. Called by SelfHealingDiagnostics
     * on CRITICAL/EMERGENCY so regime is recomputed from fresh trade data
     * rather than remaining stuck on DUMP for hours after recovery starts.
     */
    fun bustCache() {
        cached.set(null)  // V5.9.1070 — AtomicReference cleared; next call recomputes from fresh trade data
    }

    private fun recompute(now: Long): RegimeSnapshot {
        val recentSells = try {
            TradeHistoryStore.getRecentValidClosedTrades(limit = 100, includePartials = false)
        } catch (_: Throwable) { emptyList() }

        val v3Median = try {
            WrRecoveryPartial.v3DistSnapshot().median
        } catch (_: Throwable) { -1 }

        if (recentSells.size < 10) {
            // V5.0.4081 — NO BOOTSTRAP IN LIVE (operator P0). Real money
            // pipeline does NOT get a free pass for being early. When sample
            // is thin we report NORMAL semantics (sizeMult=1.0, scoreFloor
            // delta=0) — quality drives sizing, not newness. The lane-level
            // tuners (LiveStrategyTuner, LaneExpectancyDamper, LaneExitTuner)
            // already adapt per-lane on their own sample timelines.
            return RegimeSnapshot(Regime.NORMAL, 0.0, 0.0, v3Median, recentSells.size, now)
        }

        val wins = recentSells.count { it.pnlPct > 1.0 }
        val losses = recentSells.count { it.pnlPct < -1.0 }
        val wlDenom = wins + losses
        val wr = if (wlDenom > 0) (wins.toDouble() / wlDenom) * 100.0 else 0.0
        val meanPnl = recentSells.sumOf { it.pnlPct } / recentSells.size

        val regime = when {
            wr >= 45.0 && meanPnl >= 3.0 && (v3Median < 0 || v3Median >= 35) -> Regime.BULL_RIPPING
            wr in 25.0..44.99 && abs(meanPnl) < 3.0                         -> Regime.NORMAL
            // V5.0.4085 — ASYMMETRIC-RUNNER EXEMPTION (operator P0: meme/MOONSHOT
            // profiles run sub-25% WR by design — frequent small losses paid by
            // fat-tail winners. Pre-V5.0.4085 the CHOP gate fired on WR<25 alone
            // and crushed sizing by ×0.35 even when the book was net-positive in
            // SOL terms. WR-only is the wrong signal for runner regimes; require
            // a non-positive realized mean before declaring CHOP. Likewise DUMP
            // already gates on meanPnl <= -3.0, so the new CHOP gate just adds
            // the symmetric "mean must be losing" requirement.
            wr in 15.0..24.99 && meanPnl < 0.0                              -> Regime.CHOP
            wr < 15.0 && meanPnl <= -3.0                                     -> Regime.DUMP
            else                                                              -> Regime.NORMAL
        }

        return RegimeSnapshot(regime, wr, meanPnl, v3Median, recentSells.size, now)
    }

    /**
     * Score-floor delta the rest of the stack should add to its band base.
     * BULL_RIPPING relaxes (more permissive — the bot is winning, let it run).
     * DUMP tightens hard (the bot is bleeding — quality only).
     */
    // V5.0.4528 — DUMP is no longer a blanket volume choke. Recent live reports
    // showed healthy scanner/executor mechanics but DUMP publishing +20/+0.10,
    // while AgenticStyleRouter already pivots DUMP/CHOP into defensive/reclaim/
    // liquidity-depth routes. Keep DUMP cautious, but do not starve all live flow.
    fun scoreFloorDelta(): Int = when (currentRegime()) {
        Regime.BULL_RIPPING -> -10
        Regime.NORMAL       ->   0
        Regime.CHOP         -> +10
        Regime.DUMP         -> +10  // recovery-quality only, not +20 global starvation
        Regime.DEAD         ->   0
        Regime.BOOTSTRAP    ->   0  // V5.0.4081 — retained for binary compat; no longer emitted by detect()
    }

    /**
     * Size multiplier the rest of the stack should compose with its own
     * dampener. Conservative — in DUMP we halve, in BULL we let it ride
     * at 1.0 (sub-trader sizing already handles upside on its own).
     */
    // V5.0.4528 — hostile-regime recovery sizing. DUMP remains reduced, but
    // no longer micro-sizes every live route to 0.10 before lane/style pivots can
    // express a better tactic. Toxicity/safety gates still own true hard rejects.
    fun sizeMultiplier(): Double = when (currentRegime()) {
        Regime.BULL_RIPPING -> 1.0
        Regime.NORMAL       -> 1.0
        Regime.CHOP         -> 0.35
        Regime.DUMP         -> 0.35   // recovery trade size; not live dust tuition
        Regime.DEAD         -> 0.50   // was 0.70 — no signal, stay tiny
        Regime.BOOTSTRAP    -> 1.0    // V5.0.4078 — cold-start at full size; we MUST trade to earn samples
    }

    // V5.0.6068 — LANE-AWARE REGIME SIZING (operator: "why the good lanes are
    // dampened instead of expanded. there has to be multiple inverted scores
    // still. find all"). The global sizeMultiplier() applies the DUMP/CHOP
    // dampener UNIFORMLY to every lane — proven winners AND bleeders alike.
    // That's an inversion: a lane earning +974% EV/trade should NOT be sized
    // at 35% just because the aggregate market is in DUMP. Aggregate DUMP is
    // exactly what proven-winner lanes are supposed to profit FROM (they
    // survived the last dump).
    //
    // laneAwareSizeMultiplier(): use this from the entry-size stack. If the
    // lane is a proven winner (WR>=35% or mean>=20% or positive SOL with
    // enough samples), the regime dampener is neutralized to 0.80 (still a
    // mild caution but not a 65% cut). Bleeders and unknown lanes get the
    // full regime dampener as before. Priority lanes (MOONSHOT/STANDARD/
    // SHITCOIN) with any sample history get at least 0.70.
    fun laneAwareSizeMultiplier(rawLane: String?): Double {
        val base = sizeMultiplier()
        if (base >= 1.0) return base  // BULL/NORMAL/BOOTSTRAP: nothing to soften
        val lane = rawLane?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: return base
        return try {
            val board = StrategyTelemetry.computeCleanLiveTerminalLeaderboard(limit = 1_500)
            val m = board.firstOrNull { it.strategy.equals(lane, true) }
            if (m == null) return base
            // V5.0.6075 — DUMP/CHOP dampening is PER-LANE: a lane that is
            // individually net-positive in SOL is not in drawdown and gets
            // the full 1.0 (global regime mult does not apply to it at all).
            if (m.trades >= 5 && m.totalSolPnl > 0.0) return 1.0
            // V5.0.6195 — WINNER PRESS: if a lane has a *high WR* even with
            // small negative aggregate (small sample noise) OR proven-strategy
            // signal from LiveStrategyTuner, treat as a winner and press
            // 1.10x instead of damping. This is the "pivot to winners"
            // operator directive — don't strangle a lane with 50%+ WR just
            // because n<10 aggregate SOL isn't positive yet.
            if (m.trades >= 5 && m.winRatePct >= 50.0) return 1.10
            val provenWinner = m.trades >= 8 &&
                (m.winRatePct >= 35.0 || m.meanPnlPct >= 20.0 ||
                    m.avgWinPct >= 50.0 || m.totalSolPnl > 0.0)
            if (provenWinner) {
                // Neutralize DUMP/CHOP dampener for proven winners.
                base.coerceAtLeast(0.80)
            } else if (lane in setOf("MOONSHOT", "STANDARD", "SHITCOIN") && m.trades >= 5) {
                // Priority-lane floor even when unproven — enough to compound.
                // V5.0.6195: lifted 0.70 -> 0.85 so priority lanes still
                // compound during DUMP instead of hard-neutered scalp size.
                base.coerceAtLeast(0.85)
            } else base
        } catch (_: Throwable) { base }
    }

    fun formatForPipelineDump(): String {
        val s = current()
        val ageSec = ((System.currentTimeMillis() - s.computedAtMs) / 1000L).coerceAtLeast(0)
        return "\n===== Regime detector (V5.9.806) =====\n" +
               "  regime=${s.regime}  wr=${"%.1f".format(s.recentWrPct)}%  meanPnl=${"%+.2f".format(s.recentMeanPnlPct)}%  v3Median=${s.v3Median}  n=${s.sampleSize}  age=${ageSec}s\n" +
               "  → scoreFloorDelta=${scoreFloorDelta()}  sizeMult=${"%.2f".format(sizeMultiplier())}\n"
    }
}

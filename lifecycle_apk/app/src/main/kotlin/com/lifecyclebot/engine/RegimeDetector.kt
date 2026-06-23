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
            wr in 15.0..24.99                                                -> Regime.CHOP
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
    // V5.0.4067 — operator recovery directive: tighten DUMP/CHOP harder.
    // DUMP +20 (was +15), CHOP +10 (was +5) to force quality-only entries.
    fun scoreFloorDelta(): Int = when (currentRegime()) {
        Regime.BULL_RIPPING -> -10
        Regime.NORMAL       ->   0
        Regime.CHOP         -> +10  // was +5 — CHOP is where most bleed happens
        Regime.DUMP         -> +20  // was +15 — bleeding hard, quality only
        Regime.DEAD         ->   0
        Regime.BOOTSTRAP    ->   0  // V5.0.4081 — retained for binary compat; no longer emitted by detect()
    }

    /**
     * Size multiplier the rest of the stack should compose with its own
     * dampener. Conservative — in DUMP we halve, in BULL we let it ride
     * at 1.0 (sub-trader sizing already handles upside on its own).
     */
    // V5.0.4067 — operator recovery directive: DUMP 0.10 (was 0.40),
    // CHOP 0.35 (was 0.65). Near-minimum bets in hostile regimes.
    fun sizeMultiplier(): Double = when (currentRegime()) {
        Regime.BULL_RIPPING -> 1.0
        Regime.NORMAL       -> 1.0
        Regime.CHOP         -> 0.35   // was 0.65 — most bleed happens here
        Regime.DUMP         -> 0.10   // was 0.40 — bleeding hard, minimum bets only
        Regime.DEAD         -> 0.50   // was 0.70 — no signal, stay tiny
        Regime.BOOTSTRAP    -> 1.0    // V5.0.4078 — cold-start at full size; we MUST trade to earn samples
    }

    fun formatForPipelineDump(): String {
        val s = current()
        val ageSec = ((System.currentTimeMillis() - s.computedAtMs) / 1000L).coerceAtLeast(0)
        return "\n===== Regime detector (V5.9.806) =====\n" +
               "  regime=${s.regime}  wr=${"%.1f".format(s.recentWrPct)}%  meanPnl=${"%+.2f".format(s.recentMeanPnlPct)}%  v3Median=${s.v3Median}  n=${s.sampleSize}  age=${ageSec}s\n" +
               "  → scoreFloorDelta=${scoreFloorDelta()}  sizeMult=${"%.2f".format(sizeMultiplier())}\n"
    }
}

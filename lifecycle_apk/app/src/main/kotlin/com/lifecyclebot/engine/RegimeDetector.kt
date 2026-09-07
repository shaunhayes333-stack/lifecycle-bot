package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.DeskPerformanceAuthority6648
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * V5.9.806 — Market regime state machine.
 *
 * Classifies the current market into one of 5 regimes so the rest of the
 * intelligence stack can adapt without each module computing an ad-hoc view.
 *
 * V5.0.6679 — CURRENT-MODE TRUTH REPAIR.
 * Regime and lane-aware regime exemptions are now calculated from the current
 * runtime environment only. PAPER consumes PAPER closes and clean PAPER lane
 * truth; LIVE consumes LIVE closes and clean LIVE lane truth. The old source
 * mixed historical environments in recompute() and then consulted LIVE-only
 * lane truth in laneAwareSizeMultiplier(), which made PAPER simultaneously
 * enter DUMP from paper losses yet unable to recognise its own paper winners.
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
    @Volatile private var cachedMode6679: String = ""
    private const val CACHE_TTL_MS = 30_000L

    private fun currentMode6679(): String =
        try { if (RuntimeModeAuthority.isPaper()) "paper" else "live" } catch (_: Throwable) { "live" }

    fun current(): RegimeSnapshot {
        val now = System.currentTimeMillis()
        val mode6679 = currentMode6679()
        val c = cached.get()
        if (c != null && cachedMode6679 == mode6679 && (now - c.computedAtMs) < CACHE_TTL_MS) return c
        val fresh = recompute(now, mode6679)
        cachedMode6679 = mode6679
        cached.set(fresh)
        return fresh
    }

    fun currentRegime(): Regime = current().regime

    fun bustCache() {
        cachedMode6679 = ""
        cached.set(null)
    }

    private fun recompute(now: Long, mode6679: String = currentMode6679()): RegimeSnapshot {
        val recentSells = try {
            TradeHistoryStore.getRecentValidClosedTrades(limit = 2_000, includePartials = false)
                .filter { it.mode.equals(mode6679, ignoreCase = true) }
                .filter { DeskPerformanceAuthority6648.classify(it) == DeskPerformanceAuthority6648.Book.MEME }
                .takeLast(100)
        } catch (_: Throwable) { emptyList() }

        val v3Median = try {
            WrRecoveryPartial.v3DistSnapshot().median
        } catch (_: Throwable) { -1 }

        if (recentSells.size < 10) {
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
            wr in 15.0..24.99 && meanPnl < 0.0                              -> Regime.CHOP
            wr < 15.0 && meanPnl <= -3.0                                    -> Regime.DUMP
            else                                                             -> Regime.NORMAL
        }

        return RegimeSnapshot(regime, wr, meanPnl, v3Median, recentSells.size, now)
    }

    fun scoreFloorDelta(): Int = when (currentRegime()) {
        Regime.BULL_RIPPING -> -10
        Regime.NORMAL       ->   0
        Regime.CHOP         -> +10
        Regime.DUMP         -> +10
        Regime.DEAD         ->   0
        Regime.BOOTSTRAP    ->   0
    }

    fun sizeMultiplier(): Double = when (currentRegime()) {
        Regime.BULL_RIPPING -> 1.0
        Regime.NORMAL       -> 1.0
        Regime.CHOP         -> 0.35
        Regime.DUMP         -> 0.35
        Regime.DEAD         -> 0.50
        Regime.BOOTSTRAP    -> 1.0
    }

    /**
     * Lane-aware hostile-regime sizing. Same-mode clean terminal truth is the
     * only authority: a PAPER winner can escape a PAPER DUMP haircut, but it can
     * never authorize LIVE sizing; a LIVE winner does the symmetric live-only job.
     */
    fun laneAwareSizeMultiplier(rawLane: String?): Double {
        val base = sizeMultiplier()
        if (base >= 1.0) return base
        val lane = rawLane?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: return base
        return try {
            val paper6679 = try { RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false }
            val board = if (paper6679) {
                StrategyTelemetry.computeCleanPaperTerminalLeaderboard(limit = 1_500)
            } else {
                StrategyTelemetry.computeCleanLiveTerminalLeaderboard(limit = 1_500)
            }
            val m = board.firstOrNull { it.strategy.equals(lane, true) }
            if (m == null) return base
            if (m.trades >= 5 && m.totalSolPnl > 0.0) return 1.0
            val provenWinner = m.trades >= 8 &&
                (m.winRatePct >= 35.0 || m.meanPnlPct >= 20.0 ||
                    m.avgWinPct >= 50.0 || m.totalSolPnl > 0.0)
            if (provenWinner) {
                base.coerceAtLeast(0.80)
            } else if (lane in setOf("MOONSHOT", "STANDARD", "SHITCOIN") && m.trades >= 5) {
                base.coerceAtLeast(0.70)
            } else base
        } catch (_: Throwable) { base }
    }

    fun formatForPipelineDump(): String {
        val s = current()
        val ageSec = ((System.currentTimeMillis() - s.computedAtMs) / 1000L).coerceAtLeast(0)
        val mode = currentMode6679().uppercase()
        return "\n===== Regime detector (V5.9.806) =====\n" +
               "  mode=$mode regime=${s.regime}  wr=${"%.1f".format(s.recentWrPct)}%  meanPnl=${"%+.2f".format(s.recentMeanPnlPct)}%  v3Median=${s.v3Median}  n=${s.sampleSize}  age=${ageSec}s\n" +
               "  → scoreFloorDelta=${scoreFloorDelta()}  sizeMult=${"%.2f".format(sizeMultiplier())}\n"
    }
}

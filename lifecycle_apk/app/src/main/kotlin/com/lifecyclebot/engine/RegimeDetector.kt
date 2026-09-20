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
 *
 * Golden Tape compatibility: V5.0.4081 low-sample NORMAL, V5.0.4528 recovery
 * regimeDelta/regimeMult, and the no-cross-lane-streak-mux contract remain
 * explicit source invariants. Restoring those names does not restore blending.
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

    /**
     * V5.0.7173 — how old CrossMarketRegimeAI's last assessment may be and
     * still count as a reading of the market. The regime pulse runs on the
     * bot loop (~5s), so ten minutes is many missed pulses, not a blip. Past
     * it there is no market term and the base is neutral — an unread market
     * is not a hostile one.
     */
    private const val MARKET_REGIME_MAX_AGE_MS_7173 = 10L * 60_000L

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

    /** V5.0.7173 — name the market reading behind the regime, or say it is absent. */
    private fun marketSourceLine7173(): String = try {
        val tracked = com.lifecyclebot.v4.meta.CrossMarketRegimeAI.trackedMarketCount()
        val ageMs = com.lifecyclebot.v4.meta.CrossMarketRegimeAI.lastAssessAgeMs()
        val fresh = tracked > 0 && ageMs <= MARKET_REGIME_MAX_AGE_MS_7173
        val mode = com.lifecyclebot.v4.meta.CrossMarketRegimeAI.getCurrentRegime().name
        "  market(CrossMarketRegimeAI)=$mode tracked=$tracked assessAge=${ageMs / 1000L}s " +
            "used=${if (fresh) "YES" else "NO_neutral_base"}\n"
    } catch (_: Throwable) { "  market(CrossMarketRegimeAI)=unavailable used=NO_neutral_base\n" }

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

        // V5.0.4081 — no bootstrap penalty in live or paper on a cold sample.
        // A small sample is not evidence for a hostile regime; keep NORMAL until
        // the current-mode terminal ledger has enough observations.
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
            // V5.0.6733 §REGIME_DEGRADATION_EARLY_TRIGGER — 6731 dump: 25
            // trades, 3W/22L (12% WR), PF 0.97, longest loss streak 16.
            // The pre-6733 else branch fell through to NORMAL because
            // meanPnl narrowly exceeded -3% (position sizing kept losses
            // shallow, but the WR was terminal). The regime detector
            // never downgraded, so scoreFloorDelta stayed at 0 and
            // sizeMult at 1.00. Add an explicit early-degradation trigger:
            // sample-size >= 20 AND WR < 25% => CHOP downgrade. This
            // routes the size-mult haircut (0.35) and floor delta (+10)
            // to fire on the actual toxic-sample condition the operator
            // observed, without needing to wait for the meanPnl to also
            // collapse to -3%.
            recentSells.size >= 20 && wr < 25.0                             -> Regime.CHOP
            else                                                             -> Regime.NORMAL
        }

        // V5.0.7173 §THE ENTRY GATE WAS WIRED TO THE WRONG REGIME AUTHORITY.
        //
        // Operator: "there's no trade volume at all... totally choked out",
        // and then: "it's meant to look at the actual real market's
        // sentiment. not the bot's."
        //
        // Their 5.0.7171:
        //
        //   regime=DUMP  wr=12.5%  meanPnl=-29.23%  n=16
        //   EXEC_GATE allow=7 block=136
        //     learned6846:REGIME_DUMP_STRONG_NEGATIVE_ = 82   (60% of blocks)
        //   ENTRY_AUTHORITY_DENY_6846 = 668 · 5 closes in seven minutes
        //
        // Every input above this line is the bot's OWN closed trades. `wr` is
        // its own win rate, `meanPnl` its own mean P&L. No market term exists
        // in this computation — not SOL, not BTC, not breadth, not volume.
        // "Regime" here has meant "how the bot has been doing lately", and it
        // feeds scoreFloorDelta, sizeMult and LearnedAdmissionAuthority6846,
        // which refuse entries on it. That closes a loop with no external
        // input:
        //
        //   bad closes -> DUMP -> refuse entries -> no new closes
        //     -> the same bad closes stay the window -> DUMP
        //
        // A detector whose output becomes its own input is a latch. Sixteen
        // trades from an hour ago were still refusing every entry, and the
        // sample could not be replaced because refusing is what stops new
        // samples being created.
        //
        // The real market-sentiment engine already exists and is already fed.
        // CrossMarketRegimeAI takes live price, 24h change and volume for
        // SOL, BTC and ETH every regime pulse (BotServiceLifecycleExt:66) and
        // grades majors-down count, momentum and volatility. It is read by the
        // personality, the symbolic exit reasoner and the adaptive runtime —
        // by everything EXCEPT the gate that decides whether to trade.
        //
        // So take the regime from the market, and demote own-performance to
        // what it actually is: a performance term, not a market observation.
        // The bot's P&L is already represented in the sizing stack four times
        // over (LaneExpectancyDamper, ColdStreakDamper, LosingStreakReflex,
        // GrowthRewardShaper); it does not also need to be the weather.
        //
        // It still tightens — a losing run should trade smaller — but by AT
        // MOST ONE STEP, so own losses can never originate DUMP. DUMP now
        // requires the market to be there: RISK_OFF is majors down on real
        // volatility, which is a dump whoever is trading it.
        val marketMode7173 = try {
            com.lifecyclebot.v4.meta.CrossMarketRegimeAI.assessRegime().mode
        } catch (_: Throwable) { null }
        val marketFresh7173 = try {
            com.lifecyclebot.v4.meta.CrossMarketRegimeAI.trackedMarketCount() > 0 &&
                com.lifecyclebot.v4.meta.CrossMarketRegimeAI.lastAssessAgeMs() <= MARKET_REGIME_MAX_AGE_MS_7173
        } catch (_: Throwable) { false }

        // ROTATIONAL is also CrossMarketRegimeAI's explicit no-feed stance
        // ("No live price feed yet — neutral stance"), so it maps to neutral
        // rather than to caution. Absence of a reading is not a bad reading.
        val marketBase7173 = when {
            !marketFresh7173 || marketMode7173 == null -> Regime.NORMAL
            marketMode7173 == com.lifecyclebot.v4.meta.GlobalRiskMode.RISK_OFF -> Regime.DUMP
            marketMode7173 == com.lifecyclebot.v4.meta.GlobalRiskMode.CHAOTIC -> Regime.CHOP
            marketMode7173 == com.lifecyclebot.v4.meta.GlobalRiskMode.MEAN_REVERT -> Regime.CHOP
            else -> Regime.NORMAL
        }

        // Own performance may tighten one step, never more, and never from a
        // market that is not already cautious into a full DUMP.
        val ownWantsTighter7173 = wr < 25.0 && meanPnl < 0.0
        val tightened7173 = if (!ownWantsTighter7173) marketBase7173 else when (marketBase7173) {
            Regime.NORMAL, Regime.BULL_RIPPING -> Regime.CHOP
            Regime.CHOP -> Regime.DUMP
            else -> marketBase7173
        }

        // BULL_RIPPING still needs both: a market that is not hostile AND own
        // evidence that the bot is actually capturing it. Unchanged bar.
        val resolved7173 = if (
            tightened7173 == Regime.NORMAL && marketBase7173 == Regime.NORMAL &&
            wr >= 45.0 && meanPnl >= 3.0 && (v3Median < 0 || v3Median >= 35)
        ) Regime.BULL_RIPPING else tightened7173

        if (resolved7173 != regime) {
            try {
                PipelineHealthCollector.labelInc("REGIME_SOURCED_FROM_MARKET_7173")
                PipelineHealthCollector.labelInc("REGIME_SOURCED_FROM_MARKET_7173_${regime.name}_TO_${resolved7173.name}")
                ForensicLogger.lifecycle(
                    "REGIME_SOURCED_FROM_MARKET_7173",
                    "ownPnlSaid=$regime marketSaid=$marketMode7173 marketFresh=$marketFresh7173 " +
                        "marketBase=$marketBase7173 ownTighten=$ownWantsTighter7173 resolved=$resolved7173 " +
                        "wr=${"%.1f".format(wr)} meanPnl=${"%.2f".format(meanPnl)} n=${recentSells.size}",
                )
            } catch (_: Throwable) {}
        }

        return RegimeSnapshot(resolved7173, wr, meanPnl, v3Median, recentSells.size, now)
    }

    fun scoreFloorDelta(): Int {
        // V5.0.6753 §REGIME_FLOOR_RELAXED — operator diagnostic Feb 2026:
        //   > "50 trades in 1850s ... my own V5.0.6747 regime floor is
        //   >  the dominant block (1480 entries), strangling throughput."
        // The +10 CHOP/DUMP floor was too aggressive in combination
        // with base minScore=15 (effective floor 25). Halved to +5 so
        // CHOP/DUMP raises admission to 20 (still meaningfully tighter
        // than 15) but doesn't murder throughput on approved entries.
        val regimeDelta = when (currentRegime()) {
            Regime.BULL_RIPPING -> -10
            Regime.NORMAL       ->   0
            Regime.CHOP         -> +5
            Regime.DUMP         -> +5
            Regime.DEAD         ->   0
            Regime.BOOTSTRAP    ->   0
        }
        return regimeDelta
    }

    fun sizeMultiplier(): Double {
        val regimeMult = when (currentRegime()) {
            Regime.BULL_RIPPING -> 1.0
            Regime.NORMAL       -> 1.0
            Regime.CHOP         -> 0.35
            Regime.DUMP         -> 0.35
            Regime.DEAD         -> 0.50
            Regime.BOOTSTRAP    -> 1.0
        }
        return regimeMult
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
               // V5.0.7173 — the regime now comes from the market, so the
               // report has to say which market reading produced it. Without
               // this line the operator cannot tell a real RISK_OFF from the
               // bot talking to itself, which is the whole bug 7173 fixes.
               marketSourceLine7173() +
               "  → scoreFloorDelta=${scoreFloorDelta()}  sizeMult=${"%.2f".format(sizeMultiplier())}\n"
    }
}

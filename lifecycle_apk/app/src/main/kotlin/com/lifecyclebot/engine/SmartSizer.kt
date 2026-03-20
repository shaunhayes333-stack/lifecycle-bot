package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig

/**
 * SmartSizer — wallet-aware dynamic position sizing
 * ═══════════════════════════════════════════════════════════════
 *
 * The core principle: position size should be a function of
 * wallet balance, not a hardcoded SOL amount. As the wallet
 * compounds, sizes scale up automatically. As it drawdowns,
 * sizes scale back down to protect capital.
 *
 * SIZING TIERS (based on tradeable balance):
 * ─────────────────────────────────────────
 *   Tier 1 — Micro    (<0.5 SOL):  base = 5% of wallet
 *   Tier 2 — Small    (0.5–2 SOL): base = 6% of wallet
 *   Tier 3 — Medium   (2–10 SOL):  base = 7% of wallet
 *   Tier 4 — Large    (10–50 SOL): base = 6% of wallet  ← slight reduction for risk control
 *   Tier 5 — Whale    (50+ SOL):   base = 5% of wallet  ← protect the big stack
 *
 * CONVICTION MULTIPLIER (entry score):
 * ─────────────────────────────────────
 *   Score 42–54:  1.0× (standard)
 *   Score 55–64:  1.25× (high conviction)
 *   Score 65–79:  1.50× (very high conviction)
 *   Score 80+:    1.75× (exceptional — rare, requires multiple signals)
 *
 * PERFORMANCE MULTIPLIER (recent win rate):
 * ──────────────────────────────────────────
 *   Win rate ≥70% last 10 trades: +20% size (hot streak — press the edge)
 *   Win rate ≥55%:                 no change
 *   Win rate 40–54%:              −20% size (cooling off)
 *   Win rate <40%:                −40% size (struggling — cut size)
 *   Win streak ≥3:                +10% (momentum bonus)
 *   Loss streak ≥3:               −30% (danger signal — pull back hard)
 *
 * DRAWDOWN PROTECTION:
 * ─────────────────────
 *   If current balance < 80% of session peak: −25% size
 *   If current balance < 60% of session peak: −50% size (half-size mode)
 *   If current balance < 40% of session peak: PAUSE new entries (circuit breaker)
 *
 * HARD LIMITS (always enforced, regardless of multipliers):
 * ──────────────────────────────────────────────────────────
 *   Min position:  0.005 SOL (dust prevention)
 *   Max position:  20% of tradeable wallet per trade
 *   Max exposure:  70% of tradeable wallet across all open positions
 *   Liquidity cap: never own >4% of the token's pool (avoids becoming exit liquidity)
 *   Reserve:       always keep walletReserveSol untouched
 *
 * CONCURRENT POSITION SCALING:
 * ──────────────────────────────
 *   1 open position:  100% of calculated size
 *   2 open positions:  75% of calculated size
 *   3 open positions:  60% of calculated size
 *   4+ open positions: 50% of calculated size
 */
object SmartSizer {

    data class SizeResult(
        val solAmount: Double,       // final position size in SOL
        val tier: String,            // wallet tier label
        val basePct: Double,         // base wallet % used
        val convictionMult: Double,  // multiplier from entry score
        val performanceMult: Double, // multiplier from win rate / streak
        val drawdownMult: Double,    // multiplier from drawdown protection
        val concurrentMult: Double,  // multiplier from concurrent positions
        val cappedBy: String,        // what limited the final size ("none", "maxPct", "hardCap", etc)
        val explanation: String,     // human-readable breakdown for decision log
    )

    data class PerformanceContext(
        val recentWinRate: Double,   // win rate over last N trades (0-100)
        val winStreak: Int,          // consecutive wins
        val lossStreak: Int,         // consecutive losses
        val sessionPeakSol: Double,  // highest wallet balance this session
        val totalTrades: Int,
    )

    fun calculate(
        walletSol: Double,
        entryScore: Double,
        perf: PerformanceContext,
        cfg: BotConfig,
        openPositionCount: Int = 0,
        currentTotalExposure: Double = 0.0,
        liquidityUsd: Double = 0.0,
        solPriceUsd: Double = 0.0,
        mcapUsd: Double = 0.0,           // market cap for ScalingMode tier selection
    ): SizeResult {

        // ── Tradeable balance (reserve + treasury excluded) ──────────
        // treasurySol is the milestone-locked profit — never risked in trades.
        // walletReserveSol is the minimum operating reserve (gas + safety floor).
        val treasuryFloor = TreasuryManager.treasurySol
        val tradeable = (walletSol - cfg.walletReserveSol - treasuryFloor).coerceAtLeast(0.0)
        if (tradeable < 0.005) {
            return SizeResult(0.0, "insufficient", 0.0, 1.0, 1.0, 1.0, 1.0, "reserve",
                "Wallet below reserve floor — no trades (treasury: ${treasuryFloor.fmt(4)}◎ locked)")
        }

        // ── Tier + base percentage ────────────────────────────────────
        // Tier percentages — higher base % means more capital deployed per trade
        // as wallet grows. Drawdown protection still kicks in if things go wrong.
        val (tier, basePct) = when {
            tradeable < 0.5  -> "micro"  to 0.08   // 8% — small wallets need meaningful trades
            tradeable < 2.0  -> "small"  to 0.10   // 10%
            tradeable < 10.0 -> "medium" to 0.10   // 10%
            tradeable < 50.0 -> "large"  to 0.08   // 8% — diversify more at scale
            else             -> "whale"  to 0.06   // 6% — large positions, wider spread
        }
        var size = tradeable * basePct

        // ── Conviction multiplier ─────────────────────────────────────
        val convMult = when {
            entryScore >= 80 -> if (cfg.convictionSizingEnabled) 1.75 else 1.0
            entryScore >= 65 -> if (cfg.convictionSizingEnabled) cfg.convictionMult2 else 1.0
            entryScore >= 55 -> if (cfg.convictionSizingEnabled) cfg.convictionMult1 else 1.0
            else             -> 1.0
        }
        size *= convMult

        // ── Performance multiplier ────────────────────────────────────
        val perfMult = when {
            perf.lossStreak >= 3                       -> 0.70  // loss streak — cut back
            perf.recentWinRate >= 70 && perf.totalTrades >= 5 -> 1.20  // hot streak
            perf.recentWinRate < 40  && perf.totalTrades >= 5 -> 0.60  // struggling
            perf.recentWinRate < 55  && perf.totalTrades >= 5 -> 0.80  // cooling
            perf.winStreak >= 3                        -> 1.10  // win streak bonus
            else                                       -> 1.0
        }
        size *= perfMult

        // ── Drawdown protection ───────────────────────────────────────
        val drawdownMult = if (perf.sessionPeakSol > 0) {
            val recovery = walletSol / perf.sessionPeakSol
            when {
                recovery < 0.40 -> 0.0   // circuit breaker — no new entries
                recovery < 0.60 -> 0.50  // half size
                recovery < 0.80 -> 0.75  // reduced
                else            -> 1.0
            }
        } else 1.0

        if (drawdownMult == 0.0) {
            return SizeResult(0.0, tier, basePct, convMult, perfMult, 0.0, 1.0,
                "drawdown_circuit_breaker",
                "Wallet down 60%+ from peak — entries paused to protect capital")
        }
        size *= drawdownMult

        // ── Concurrent position scaling ────────────────────────────────
        // We WANT many positions simultaneously — that's how we maximise opportunities.
        // Each position gets a wallet-percentage slice, so more positions = more
        // total deployment, not smaller per-position sizes.
        //
        // The total exposure cap (35% of tradeable) is the real guard here.
        // Per-position size stays constant — the cap naturally limits how many
        // positions can be open simultaneously based on wallet size.
        //
        // No penalty for concurrent positions. We scale per-position size UP
        // when wallet grows (via tier system) not down when we have more positions.
        val concMult = 1.0  // no penalty — wallet % allocation handles exposure

        // ── Hard limits ───────────────────────────────────────────────
        var cappedBy = "none"

        // Max per-trade: 20% of tradeable (raised — larger wallet = bigger individual positions)
        val maxPerTrade = tradeable * 0.20
        if (size > maxPerTrade) { size = maxPerTrade; cappedBy = "maxPct_20" }

        // Total exposure cap: 70% of tradeable deployed simultaneously
        // 30% stays as dry powder for new opportunities and gas fees.
        // SmartSizer drawdown protection further reduces this when losing.
        val exposureRoom = (tradeable * 0.70) - currentTotalExposure
        if (size > exposureRoom) { size = exposureRoom.coerceAtLeast(0.0); cappedBy = "exposureCap" }

        // ── Liquidity ownership cap (ScalingMode tier-aware) ─────────
        // Ownership % scales down as treasury grows and pools get larger:
        //   MICRO/STANDARD: 4%  |  GROWTH: 3%  |  SCALED: 2%  |  INSTITUTIONAL: 1%
        val trsUsdCap = TreasuryManager.treasurySol * solPriceUsd
        val (capTier, tierMaxSol) = ScalingMode.maxPositionForToken(
            liquidityUsd = liquidityUsd,
            mcapUsd      = mcapUsd,
            treasuryUsd  = trsUsdCap,
            solPriceUsd  = solPriceUsd,
        )
        if (liquidityUsd > 0.0 && solPriceUsd > 0.0) {
            if (size > tierMaxSol) {
                size     = tierMaxSol
                cappedBy = "liqOwnership_${(capTier.ownershipCapPct*100).toInt()}pct_${capTier.label}"
            }
        } else if (liquidityUsd <= 0.0) {
            if (size > 20.0) { size = 20.0; cappedBy = "liqUnknown_20sol" }
        }

        // Dust floor
        size = size.coerceAtLeast(0.0)
        if (size < 0.005) {
            return SizeResult(0.0, tier, basePct, convMult, perfMult, drawdownMult, concMult,
                "dust", "Calculated size below dust floor")
        }

        // Round to 4dp
        size = (size * 10000).toLong() / 10000.0

        val explanation = buildString {
            append("Tier=$tier(${(basePct*100).toInt()}%)  ")
            append("base=${(tradeable*basePct).fmt()}◎  ")
            append("×conv=${convMult.fmt1}  ")
            if (perfMult != 1.0) append("×perf=${perfMult.fmt1}  ")
            if (drawdownMult != 1.0) append("×dd=${drawdownMult.fmt1}  ")
            if (concMult != 1.0) append("×conc=${concMult.fmt1}  ")
            append("→${size.fmt()}◎")
            if (cappedBy != "none") append("  [capped:$cappedBy]")
        }

        return SizeResult(size, tier, basePct, convMult, perfMult,
                          drawdownMult, concMult, cappedBy, explanation)
    }

    // ── Session peak tracker ──────────────────────────────────────────
    // Maintained by BotService, passed into calculate() each tick
    @Volatile private var _sessionPeak = 0.0
    fun updateSessionPeak(walletSol: Double) {
        // Synchronized: called from bot loop thread, read from UI thread
        if (walletSol > _sessionPeak) _sessionPeak = walletSol
    }
    fun getSessionPeak() = _sessionPeak
    fun resetSessionPeak() { _sessionPeak = 0.0 }

    // ── Recent performance tracker ────────────────────────────────────
    // Lightweight ring buffer of last 10 trade outcomes
    private val recentTrades = ArrayDeque<Boolean>(10)  // true=win, false=loss
    @Volatile private var winStreak = 0
    @Volatile private var lossStreak = 0

    fun recordTrade(isWin: Boolean) {
        if (recentTrades.size >= 10) recentTrades.removeFirst()
        recentTrades.addLast(isWin)
        if (isWin) { winStreak++; lossStreak = 0 }
        else       { lossStreak++; winStreak = 0 }
    }

    fun getPerformanceContext(walletSol: Double, totalTrades: Int): PerformanceContext {
        val winRate = if (recentTrades.isNotEmpty())
            recentTrades.count { it }.toDouble() / recentTrades.size * 100.0
        else 50.0
        return PerformanceContext(
            recentWinRate  = winRate,
            winStreak      = winStreak,
            lossStreak     = lossStreak,
            sessionPeakSol = getSessionPeak().coerceAtLeast(walletSol),
            totalTrades    = totalTrades,
        )
    }

    /** Restore streak counts from a persisted session (avoids replay side-effects) */
    fun restoreStreaks(wins: Int, losses: Int) {
        winStreak  = wins.coerceAtLeast(0)
        lossStreak = losses.coerceAtLeast(0)
    }

    fun resetSession() {
        recentTrades.clear()
        winStreak = 0; lossStreak = 0
        resetSessionPeak()
    }
}

private fun Double.fmt() = "%.4f".format(this)
private val Double.fmt1 get() = "%.2f".format(this)

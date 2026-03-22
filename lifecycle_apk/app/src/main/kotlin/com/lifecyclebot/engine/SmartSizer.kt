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
        mcapUsd: Double = 0.0,
        // NEW: AI-driven parameters
        aiConfidence: Double = 50.0,      // 0-100 from EdgeOptimizer
        phase: String = "unknown",
        source: String = "unknown",
        brain: BotBrain? = null,
    ): SizeResult {

        // ── HARD MCAP FLOOR — never trade dust tokens ──────────────────
        val HARD_MIN_MCAP = 2_000.0  // lowered from $5K - let AI decide
        if (mcapUsd > 0 && mcapUsd < HARD_MIN_MCAP) {
            return SizeResult(0.0, "blocked", 0.0, 1.0, 1.0, 1.0, 1.0, "mcap_too_low",
                "Market cap \$${(mcapUsd/1000).toInt()}K below \$2K minimum")
        }

        // ── Tradeable balance (reserve + treasury excluded) ──────────
        val treasuryFloor = TreasuryManager.treasurySol
        val tradeable = (walletSol - cfg.walletReserveSol - treasuryFloor).coerceAtLeast(0.0)
        if (tradeable < 0.005) {
            return SizeResult(0.0, "insufficient", 0.0, 1.0, 1.0, 1.0, 1.0, "reserve",
                "Wallet below reserve floor — no trades (treasury: ${treasuryFloor.fmt(4)}◎ locked)")
        }

        // ══════════════════════════════════════════════════════════════
        // AI-DRIVEN SIZING: Let the brain decide base size
        // ══════════════════════════════════════════════════════════════
        
        // Base percentage determined by AI confidence, not just wallet tier
        val aiBasePct = when {
            aiConfidence >= 85 -> 0.15  // 15% - very high confidence
            aiConfidence >= 75 -> 0.12  // 12% - high confidence
            aiConfidence >= 65 -> 0.10  // 10% - good confidence
            aiConfidence >= 55 -> 0.08  // 8% - moderate confidence
            aiConfidence >= 45 -> 0.06  // 6% - low confidence
            else -> 0.04                 // 4% - very low confidence
        }
        
        // Wallet tier still affects maximum, not base
        val (tier, tierMaxPct) = when {
            tradeable < 0.5  -> "micro"  to 0.15
            tradeable < 2.0  -> "small"  to 0.15
            tradeable < 10.0 -> "medium" to 0.12
            tradeable < 50.0 -> "large"  to 0.10
            else             -> "whale"  to 0.08
        }
        
        // Use AI base, capped by tier max
        val basePct = minOf(aiBasePct, tierMaxPct)
        var size = tradeable * basePct

        // ── AI Entry Score Multiplier (replaces conviction) ───────────
        val aiScoreMult = when {
            entryScore >= 80 -> 1.50  // High entry score = bigger position
            entryScore >= 65 -> 1.30
            entryScore >= 50 -> 1.15
            entryScore >= 35 -> 1.00
            entryScore >= 20 -> 0.85
            else             -> 0.70
        }
        size *= aiScoreMult

        // ── BotBrain Source/Phase Adjustment ─────────────────────────
        var brainMult = 1.0
        brain?.let { b ->
            // Source boost/penalty from learned win rates
            val sourceBoost = b.getSourceBoost(source)
            when {
                sourceBoost >= 15 -> brainMult *= 1.30  // This source wins a lot
                sourceBoost >= 10 -> brainMult *= 1.20
                sourceBoost >= 5  -> brainMult *= 1.10
                sourceBoost <= -15 -> brainMult *= 0.60 // This source loses a lot
                sourceBoost <= -10 -> brainMult *= 0.70
                sourceBoost <= -5  -> brainMult *= 0.85
            }
            
            // Phase boost from learned patterns
            val phaseBoost = b.getPhaseBoost(phase)
            when {
                phaseBoost >= 10 -> brainMult *= 1.20
                phaseBoost >= 5  -> brainMult *= 1.10
                phaseBoost <= -10 -> brainMult *= 0.70
                phaseBoost <= -5  -> brainMult *= 0.85
            }
            
            // Overall regime adjustment
            brainMult *= b.regimeBullMult
        }
        size *= brainMult

        // ── TradingMemory Pattern Check ─────────────────────────────
        val memoryMult = run {
            val patternPenalty = TradingMemory.getPatternPenalty(phase, "UNKNOWN", source)
            when {
                patternPenalty >= 20 -> 0.50  // Known bad pattern - half size
                patternPenalty >= 10 -> 0.70
                patternPenalty >= 5  -> 0.85
                else -> 1.0
            }
        }
        size *= memoryMult

        // ── Performance multiplier ────────────────────────────────────
        val perfMult = when {
            perf.lossStreak >= 4                       -> 0.50  // bad streak — cut back hard
            perf.lossStreak >= 3                       -> 0.70
            perf.recentWinRate >= 70 && perf.totalTrades >= 5 -> 1.30  // hot streak - go bigger
            perf.recentWinRate >= 60 && perf.totalTrades >= 5 -> 1.15
            perf.recentWinRate < 40  && perf.totalTrades >= 5 -> 0.60
            perf.recentWinRate < 50  && perf.totalTrades >= 5 -> 0.80
            perf.winStreak >= 3                        -> 1.20  // win streak bonus
            else                                       -> 1.0
        }
        size *= perfMult

        // ── Drawdown protection ───────────────────────────────────────
        val drawdownMult = if (perf.sessionPeakSol > 0) {
            val recovery = walletSol / perf.sessionPeakSol
            when {
                recovery < 0.40 -> 0.0   // circuit breaker
                recovery < 0.60 -> 0.50
                recovery < 0.80 -> 0.75
                else            -> 1.0
            }
        } else 1.0

        if (drawdownMult == 0.0) {
            return SizeResult(0.0, tier, basePct, aiScoreMult, perfMult, 0.0, 1.0,
                "drawdown_circuit_breaker",
                "Wallet down 60%+ from peak — entries paused")
        }
        size *= drawdownMult

        // ── Concurrent position scaling ────────────────────────────────
        val concMult = 1.0  // no penalty

        // ── Hard limits ───────────────────────────────────────────────
        var cappedBy = "none"

        // Max per-trade: 20% of tradeable
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
            return SizeResult(0.0, tier, basePct, aiScoreMult, perfMult, drawdownMult, concMult,
                "dust", "Calculated size below dust floor")
        }

        // Round to 4dp
        size = (size * 10000).toLong() / 10000.0

        val explanation = buildString {
            append("AI conf=${aiConfidence.toInt()} ")
            append("base=${(basePct*100).toInt()}% ")
            append("×score=${aiScoreMult.fmt1} ")
            if (brainMult != 1.0) append("×brain=${brainMult.fmt1} ")
            if (memoryMult != 1.0) append("×mem=${memoryMult.fmt1} ")
            if (perfMult != 1.0) append("×perf=${perfMult.fmt1} ")
            if (drawdownMult != 1.0) append("×dd=${drawdownMult.fmt1} ")
            append("→${size.fmt()}◎")
            if (cappedBy != "none") append(" [cap:$cappedBy]")
        }

        return SizeResult(size, tier, basePct, aiScoreMult, perfMult,
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

private fun Double.fmt(d: Int = 4) = "%.${d}f".format(this)
private val Double.fmt1 get() = "%.2f".format(this)

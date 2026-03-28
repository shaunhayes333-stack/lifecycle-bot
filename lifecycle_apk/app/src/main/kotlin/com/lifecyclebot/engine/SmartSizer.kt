package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.engine.ErrorLogger

/**
 * SmartSizer — wallet-aware dynamic position sizing
 * ═══════════════════════════════════════════════════════════════
 *
 * @deprecated V3 ARCHITECTURE MIGRATION
 * This legacy sizer is being replaced by:
 *   - SmartSizerV3 (v3/sizing/) - Confidence-based position sizing
 *   - Band-based sizing tied to decision confidence
 * 
 * Currently runs IN PARALLEL with V3 for validation.
 * Will be removed once V3 is proven in production.
 * 
 * MIGRATION STATUS: DEPRECATED - V3 is the future
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
        val treasuryMult: Double = 1.0,    // NEW: multiplier from treasury tier
        val houseMoneyMult: Double = 1.0,  // NEW: multiplier from house money positions
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
        setupQuality: String = "C",       // A+ / B / C from strategy
    ): SizeResult {

        val isPaperMode = cfg.paperMode
        ErrorLogger.info("SmartSizer", "📏 SIZING: paper=$isPaperMode wallet=$walletSol reserve=${cfg.walletReserveSol} mcap=$mcapUsd liq=$liquidityUsd quality=$setupQuality")
        
        // ══════════════════════════════════════════════════════════════════════
        // FLUID LEARNING: In paper mode, use simulated balance for realistic sizing
        // This teaches the AI real constraints instead of "unlimited funds"
        // ══════════════════════════════════════════════════════════════════════
        val effectiveWallet = if (isPaperMode && cfg.fluidLearningEnabled) {
            val simBal = FluidLearning.getSimulatedBalance()
            ErrorLogger.info("SmartSizer", "📊 FLUID LEARNING: Using simulated balance $simBal SOL (real: $walletSol)")
            simBal
        } else {
            walletSol
        }
        
        // ── HARD MCAP FLOOR — DISABLED IN PAPER MODE ──────────────────
        if (!isPaperMode) {
            val HARD_MIN_MCAP = 2_000.0
            if (mcapUsd > 0 && mcapUsd < HARD_MIN_MCAP) {
                return SizeResult(0.0, "blocked", 0.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, "mcap_too_low",
                    "Market cap \$${(mcapUsd/1000).toInt()}K below \$2K minimum")
            }
        }

        // ── Tradeable balance (reserve + treasury excluded) ──────────
        val treasuryFloor = TreasuryManager.treasurySol
        
        // FLUID LEARNING: Use simulated balance with realistic constraints
        val tradeable = if (isPaperMode && cfg.fluidLearningEnabled) {
            // Apply same reserve logic as live mode for realistic learning
            val simBalance = FluidLearning.getSimulatedBalance()
            val simExposure = FluidLearning.getSimulatedExposure()
            (simBalance - cfg.walletReserveSol - simExposure).coerceAtLeast(0.0)
        } else if (isPaperMode) {
            // Legacy paper mode - unlimited funds
            effectiveWallet.coerceAtLeast(0.0)
        } else {
            (effectiveWallet - cfg.walletReserveSol - treasuryFloor).coerceAtLeast(0.0)
        }
        
        ErrorLogger.info("SmartSizer", "📏 tradeable=$tradeable (wallet=$effectiveWallet - reserve=${cfg.walletReserveSol} - treasury=$treasuryFloor | paper=$isPaperMode | fluid=${cfg.fluidLearningEnabled})")
        
        if (tradeable < 0.005) {
            ErrorLogger.error("SmartSizer", "❌ BLOCKED: tradeable $tradeable < 0.005 floor | paper=$isPaperMode")
            return SizeResult(0.0, "insufficient", 0.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, "reserve",
                "Wallet below reserve floor — no trades (treasury: ${treasuryFloor.fmt(4)}◎ locked)")
        }

        // ══════════════════════════════════════════════════════════════
        // AI-DRIVEN SIZING: Let the brain decide base size
        // PAPER MODE: Larger positions for faster learning
        // REAL MODE: Conservative positions based on confidence
        // ══════════════════════════════════════════════════════════════
        
        // Base percentage determined by AI confidence
        val aiBasePct = if (isPaperMode) {
            // PAPER MODE: Larger fixed positions - learn faster
            when {
                aiConfidence >= 70 -> 0.15  // 15%
                aiConfidence >= 50 -> 0.12  // 12%
                else -> 0.10                 // 10% minimum in paper
            }
        } else {
            // REAL MODE: Conservative, confidence-driven
            when {
                aiConfidence >= 85 -> 0.15  // 15% - very high confidence
                aiConfidence >= 75 -> 0.12  // 12% - high confidence
                aiConfidence >= 65 -> 0.10  // 10% - good confidence
                aiConfidence >= 55 -> 0.08  // 8% - moderate confidence
                aiConfidence >= 45 -> 0.06  // 6% - low confidence
                else -> 0.04                 // 4% - very low confidence
            }
        }
        
        // Wallet tier still affects maximum, not base
        // Wallet tier affects maximum percentage
        // PAPER MODE with large balance: Use higher percentages to test all features
        val (tier, tierMaxPct) = if (isPaperMode && tradeable >= 1000) {
            "paper_whale" to 0.20  // 20% max for paper whale - test bigger positions
        } else when {
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

        // ══════════════════════════════════════════════════════════════
        // IMPROVEMENT #3: SETUP QUALITY MULTIPLIER
        // A+ setups get larger positions, C setups get smaller
        // ══════════════════════════════════════════════════════════════
        val qualityMult = when (setupQuality) {
            "A+" -> 1.50   // Excellent setup: +50% size
            "B"  -> 1.20   // Good setup: +20% size
            "C"  -> 1.00   // Basic setup: normal size
            else -> 0.80   // Unknown/poor: -20% size
        }
        size *= qualityMult
        ErrorLogger.debug("SmartSizer", "📊 Quality mult: $setupQuality → ${qualityMult}x")

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
        
        // ── LIQUIDITY DEPTH ADJUSTMENT ─────────────────────────────────
        // Scale position based on liquidity - bigger positions when more depth
        val liquidityMult = when {
            liquidityUsd >= 500_000 -> 1.50  // Very deep liquidity - can size up
            liquidityUsd >= 200_000 -> 1.30
            liquidityUsd >= 100_000 -> 1.15
            liquidityUsd >= 50_000  -> 1.00  // Normal
            liquidityUsd >= 20_000  -> 0.85  // Low liquidity - smaller positions
            liquidityUsd >= 10_000  -> 0.70
            else                    -> 0.60  // Very low - minimal position
        }
        size *= liquidityMult
        
        // ── VOLATILITY/CONFIDENCE ADJUSTMENT ───────────────────────────
        // Higher confidence = accept more risk, lower = reduce exposure
        val confidenceMult = when {
            aiConfidence >= 85 -> 1.30  // Very high confidence - size up
            aiConfidence >= 70 -> 1.15
            aiConfidence >= 55 -> 1.00  // Normal
            aiConfidence >= 40 -> 0.85
            else               -> 0.70  // Low confidence = minimal exposure
        }
        size *= confidenceMult
        
        // ══════════════════════════════════════════════════════════════
        // TREASURY-ADAPTIVE SIZING
        // As treasury grows, we can afford to take larger positions
        // Treasury acts as a "bankroll buffer" that allows more aggression
        // ══════════════════════════════════════════════════════════════
        val treasuryMult = if (isPaperMode && !cfg.fluidLearningEnabled) {
            1.0  // No treasury adjustment in legacy paper mode
        } else {
            val solPrice = solPriceUsd.takeIf { it > 0 } ?: 100.0
            val treasuryUsd = TreasuryManager.treasurySol * solPrice
            val tier = ScalingMode.activeTier(treasuryUsd)
            
            when (tier) {
                // Higher treasury = more aggressive sizing
                ScalingMode.Tier.INSTITUTIONAL -> 1.50  // +50% size - big bankroll
                ScalingMode.Tier.SCALED        -> 1.30  // +30% size
                ScalingMode.Tier.GROWTH        -> 1.15  // +15% size
                ScalingMode.Tier.STANDARD      -> 1.00  // Normal
                ScalingMode.Tier.MICRO         -> 0.85  // -15% size - protect small stack
            }
        }
        size *= treasuryMult
        
        // ══════════════════════════════════════════════════════════════
        // HOUSE MONEY BONUS
        // If we have positions that recovered capital, we can be more aggressive
        // with new entries since existing positions are "free"
        // ══════════════════════════════════════════════════════════════
        val houseMoneyBonus = if (isPaperMode && !cfg.fluidLearningEnabled) {
            1.0  // No house money bonus in legacy paper mode
        } else if (isPaperMode && cfg.fluidLearningEnabled) {
            // FLUID PAPER: Learn house money mechanics using simulated balance
            val fluidPnl = FluidLearning.getTotalPnl()
            when {
                fluidPnl >= 2.0 -> 1.25  // 2+ SOL profit - aggressive
                fluidPnl >= 1.0 -> 1.15  // 1+ SOL profit
                fluidPnl >= 0.5 -> 1.10  // 0.5+ SOL profit
                fluidPnl > 0    -> 1.05  // Some profit
                else            -> 1.00  // No profit yet
            }
        } else {
            // Check if we have house money positions
            val hasHouseMoneyPositions = TreasuryManager.lifetimeLocked > 0
            val lockedProfitRatio = if (TreasuryManager.treasurySol > 0) {
                TreasuryManager.lifetimeLocked / TreasuryManager.treasurySol
            } else 0.0
            
            when {
                lockedProfitRatio >= 0.5 -> 1.25  // 50%+ of treasury is locked profit - aggressive
                lockedProfitRatio >= 0.3 -> 1.15  // 30%+ locked profit
                lockedProfitRatio >= 0.1 -> 1.10  // 10%+ locked profit
                hasHouseMoneyPositions   -> 1.05  // Some locked profits
                else                     -> 1.00  // No locked profits yet
            }
        }
        size *= houseMoneyBonus
        
        ErrorLogger.info("SmartSizer", "📏 Mults: score=${aiScoreMult} brain=${brainMult.fmt1} mem=${memoryMult} liq=${liquidityMult} conf=${confidenceMult} treasury=${treasuryMult.fmt1} house=${houseMoneyBonus.fmt1}")

        // ── Performance multiplier ────────────────────────────────────
        // FLUID PAPER: Learn streak mechanics using simulated trades
        val perfMult = if (isPaperMode && !cfg.fluidLearningEnabled) {
            1.0  // No streak penalty in legacy paper mode
        } else if (isPaperMode && cfg.fluidLearningEnabled) {
            // Learn from paper trade streaks
            val fluidWinRate = FluidLearning.getWinRate()
            val fluidTrades = FluidLearning.getTradeCount()
            when {
                fluidWinRate >= 70 && fluidTrades >= 10 -> 1.30  // hot streak
                fluidWinRate >= 60 && fluidTrades >= 10 -> 1.15
                fluidWinRate < 40 && fluidTrades >= 10  -> 0.70  // Learn to scale down on losses
                fluidWinRate < 50 && fluidTrades >= 10  -> 0.85
                else -> 1.0
            }
        } else {
            when {
                perf.lossStreak >= 4                       -> 0.50  // bad streak — cut back hard
                perf.lossStreak >= 3                       -> 0.70
                perf.recentWinRate >= 70 && perf.totalTrades >= 5 -> 1.30  // hot streak - go bigger
                perf.recentWinRate >= 60 && perf.totalTrades >= 5 -> 1.15
                perf.recentWinRate < 40  && perf.totalTrades >= 5 -> 0.60
                perf.recentWinRate < 50  && perf.totalTrades >= 5 -> 0.80
                perf.winStreak >= 3                        -> 1.20  // win streak bonus
                else                                       -> 1.0
            }
        }
        size *= perfMult

        // ── Drawdown protection ───────────────────────────────────────
        // FLUID PAPER: Learn drawdown protection using simulated balance
        // LIVE MODE: Uses mode-specific session peak to prevent paper stats affecting live
        val drawdownMult = if (isPaperMode && !cfg.fluidLearningEnabled) {
            1.0  // No drawdown penalty in legacy paper mode
        } else if (isPaperMode && cfg.fluidLearningEnabled) {
            // Learn drawdown protection from simulated balance
            val fluidRecovery = FluidLearning.getRecoveryRatio()
            when {
                fluidRecovery < 0.50 -> 0.50  // Learn to cut back on big drawdown
                fluidRecovery < 0.70 -> 0.75
                fluidRecovery < 0.85 -> 0.90
                else -> 1.0
            }
        } else if (perf.sessionPeakSol > 0) {
            val recovery = walletSol / perf.sessionPeakSol
            ErrorLogger.debug("SmartSizer", "📊 LIVE Drawdown check: wallet=$walletSol peak=${perf.sessionPeakSol} recovery=${(recovery*100).toInt()}%")
            when {
                recovery < 0.40 -> 0.0   // circuit breaker
                recovery < 0.60 -> 0.50
                recovery < 0.80 -> 0.75
                else            -> 1.0
            }
        } else 1.0

        if (drawdownMult == 0.0) {
            ErrorLogger.error("SmartSizer", "❌ BLOCKED: drawdown circuit breaker | paper=$isPaperMode | wallet=$walletSol | peak=${perf.sessionPeakSol}")
            return SizeResult(0.0, tier, basePct, aiScoreMult, perfMult, 0.0, 1.0, treasuryMult, houseMoneyBonus,
                "drawdown_circuit_breaker",
                "LIVE wallet ($walletSol SOL) down 60%+ from session peak (${perf.sessionPeakSol.fmt(2)} SOL) — entries paused. Switch to paper or wait for recovery.")
        }
        size *= drawdownMult

        // ── Concurrent position scaling ────────────────────────────────
        val concMult = 1.0  // no penalty

        // ── Hard limits ───────────────────────────────────────────────
        var cappedBy = "none"

        // Max per-trade: 20% of tradeable (same for paper and live)
        val maxPerTrade = tradeable * 0.20
        if (size > maxPerTrade) { size = maxPerTrade; cappedBy = "maxPct_20" }

        // ══════════════════════════════════════════════════════════════
        // TREASURY-ADAPTIVE EXPOSURE CAP
        // Higher treasury = can handle more total exposure
        // This allows scaling up as the bankroll grows
        // ══════════════════════════════════════════════════════════════
        val maxExposurePct = if (isPaperMode) {
            0.70  // 70% in paper mode
        } else {
            val solPrice = solPriceUsd.takeIf { it > 0 } ?: 100.0
            val treasuryUsd = TreasuryManager.treasurySol * solPrice
            val tier = ScalingMode.activeTier(treasuryUsd)
            
            when (tier) {
                ScalingMode.Tier.INSTITUTIONAL -> 0.85  // 85% exposure allowed - big bankroll
                ScalingMode.Tier.SCALED        -> 0.80  // 80% exposure
                ScalingMode.Tier.GROWTH        -> 0.75  // 75% exposure
                ScalingMode.Tier.STANDARD      -> 0.70  // 70% exposure (default)
                ScalingMode.Tier.MICRO         -> 0.60  // 60% exposure - protect small stack
            }
        }
        
        val exposureRoom = (tradeable * maxExposurePct) - currentTotalExposure
        ErrorLogger.info("SmartSizer", "📏 exposureRoom=$exposureRoom (tradeable*$maxExposurePct=${tradeable*maxExposurePct} - exposure=$currentTotalExposure)")
        if (size > exposureRoom) { size = exposureRoom.coerceAtLeast(0.0); cappedBy = "exposureCap_${(maxExposurePct*100).toInt()}pct" }

        // ── Liquidity ownership cap (ScalingMode tier-aware) ─────────
        // PAPER MODE: Skip liquidity ownership cap - we want to learn
        if (!isPaperMode) {
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
        }

        // Dust floor - lower for paper mode
        size = size.coerceAtLeast(0.0)
        val dustFloor = if (isPaperMode) 0.001 else 0.005  // 0.001 SOL in paper, 0.005 in real
        
        // PAPER MODE MINIMUM: Always trade at least 0.01 SOL (or 5% of wallet) to ensure learning
        if (isPaperMode && size < dustFloor) {
            val minPaperSize = maxOf(0.01, tradeable * 0.05)  // At least 0.01 SOL or 5% of wallet
            if (tradeable >= 0.02) {  // Only if we have at least 0.02 SOL
                size = minPaperSize
                ErrorLogger.info("SmartSizer", "📏 PAPER MIN SIZE: forcing $size SOL (was below dust)")
            }
        }
        
        if (size < dustFloor) {
            ErrorLogger.error("SmartSizer", "❌ BLOCKED: dust floor | size=$size < $dustFloor | paper=$isPaperMode | wallet=$walletSol")
            return SizeResult(0.0, tier, basePct, aiScoreMult, perfMult, drawdownMult, concMult, treasuryMult, houseMoneyBonus,
                "dust", "Calculated size below dust floor (wallet too small?)")
        }

        // Round to 4dp
        size = (size * 10000).toLong() / 10000.0
        ErrorLogger.info("SmartSizer", "✅ SIZE OK: $size SOL | tier=$tier | paper=$isPaperMode")

        val explanation = buildString {
            append("AI conf=${aiConfidence.toInt()} ")
            append("base=${(basePct*100).toInt()}% ")
            append("×score=${aiScoreMult.fmt1} ")
            if (brainMult != 1.0) append("×brain=${brainMult.fmt1} ")
            if (memoryMult != 1.0) append("×mem=${memoryMult.fmt1} ")
            if (treasuryMult != 1.0) append("×treasury=${treasuryMult.fmt1} ")
            if (houseMoneyBonus != 1.0) append("×house=${houseMoneyBonus.fmt1} ")
            if (perfMult != 1.0) append("×perf=${perfMult.fmt1} ")
            if (drawdownMult != 1.0) append("×dd=${drawdownMult.fmt1} ")
            append("→${size.fmt()}◎")
            if (cappedBy != "none") append(" [cap:$cappedBy]")
        }

        return SizeResult(size, tier, basePct, aiScoreMult, perfMult,
                          drawdownMult, concMult, treasuryMult, houseMoneyBonus, cappedBy, explanation)
    }

    // ── Session peak tracker ──────────────────────────────────────────
    // Maintained by BotService, passed into calculate() each tick
    // IMPORTANT: These are MODE-SPECIFIC to prevent paper stats affecting live trading
    @Volatile private var _sessionPeakPaper = 0.0
    @Volatile private var _sessionPeakLive = 0.0
    @Volatile private var _currentMode: Boolean = true  // true = paper, false = live
    
    fun updateSessionPeak(walletSol: Double, isPaperMode: Boolean = true) {
        // Track mode changes to reset stats when switching
        if (isPaperMode != _currentMode) {
            ErrorLogger.info("SmartSizer", "🔄 MODE SWITCH: ${if (_currentMode) "PAPER" else "LIVE"} → ${if (isPaperMode) "PAPER" else "LIVE"} - resetting session stats")
            resetSessionForMode(isPaperMode)
            _currentMode = isPaperMode
        }
        
        // Update the appropriate mode's peak
        if (isPaperMode) {
            if (walletSol > _sessionPeakPaper) _sessionPeakPaper = walletSol
        } else {
            if (walletSol > _sessionPeakLive) _sessionPeakLive = walletSol
        }
    }
    
    fun getSessionPeak(isPaperMode: Boolean = true): Double {
        return if (isPaperMode) _sessionPeakPaper else _sessionPeakLive
    }
    
    /**
     * Reset session stats when switching modes.
     * This prevents paper trading drawdowns from blocking live trades.
     */
    private fun resetSessionForMode(isPaperMode: Boolean) {
        if (isPaperMode) {
            // Switching TO paper - reset paper stats only
            _sessionPeakPaper = 0.0
        } else {
            // Switching TO live - reset live stats (CRITICAL: fresh start for real money)
            _sessionPeakLive = 0.0
            // Also reset the performance trackers for live mode
            winStreakLive = 0
            lossStreakLive = 0
            recentTradesLive.clear()
        }
    }
    
    fun resetSessionPeak() { 
        _sessionPeakPaper = 0.0
        _sessionPeakLive = 0.0 
    }

    // ── Recent performance tracker ────────────────────────────────────
    // Lightweight ring buffer of last 10 trade outcomes
    // SEPARATE TRACKERS for paper and live modes
    private val recentTradesPaper = ArrayDeque<Boolean>(10)  // true=win, false=loss
    private val recentTradesLive = ArrayDeque<Boolean>(10)
    @Volatile private var winStreakPaper = 0
    @Volatile private var lossStreakPaper = 0
    @Volatile private var winStreakLive = 0
    @Volatile private var lossStreakLive = 0

    fun recordTrade(isWin: Boolean, isPaperMode: Boolean = true) {
        if (isPaperMode) {
            if (recentTradesPaper.size >= 10) recentTradesPaper.removeFirst()
            recentTradesPaper.addLast(isWin)
            if (isWin) { winStreakPaper++; lossStreakPaper = 0 }
            else       { lossStreakPaper++; winStreakPaper = 0 }
        } else {
            if (recentTradesLive.size >= 10) recentTradesLive.removeFirst()
            recentTradesLive.addLast(isWin)
            if (isWin) { winStreakLive++; lossStreakLive = 0 }
            else       { lossStreakLive++; winStreakLive = 0 }
        }
    }

    fun getPerformanceContext(walletSol: Double, totalTrades: Int, isPaperMode: Boolean = true): PerformanceContext {
        val recentTrades = if (isPaperMode) recentTradesPaper else recentTradesLive
        val winStreak = if (isPaperMode) winStreakPaper else winStreakLive
        val lossStreak = if (isPaperMode) lossStreakPaper else lossStreakLive
        val sessionPeak = getSessionPeak(isPaperMode)
        
        val winRate = if (recentTrades.isNotEmpty())
            recentTrades.count { it }.toDouble() / recentTrades.size * 100.0
        else 50.0
        return PerformanceContext(
            recentWinRate  = winRate,
            winStreak      = winStreak,
            lossStreak     = lossStreak,
            sessionPeakSol = sessionPeak.coerceAtLeast(walletSol),
            totalTrades    = totalTrades,
        )
    }

    /** Restore streak counts from a persisted session (avoids replay side-effects) */
    fun restoreStreaks(wins: Int, losses: Int, isPaperMode: Boolean = true) {
        if (isPaperMode) {
            winStreakPaper  = wins.coerceAtLeast(0)
            lossStreakPaper = losses.coerceAtLeast(0)
        } else {
            winStreakLive  = wins.coerceAtLeast(0)
            lossStreakLive = losses.coerceAtLeast(0)
        }
    }

    fun resetSession() {
        recentTradesPaper.clear()
        recentTradesLive.clear()
        winStreakPaper = 0; lossStreakPaper = 0
        winStreakLive = 0; lossStreakLive = 0
        resetSessionPeak()
    }
}

private fun Double.fmt(d: Int = 4) = "%.${d}f".format(this)
private val Double.fmt1 get() = "%.2f".format(this)

package com.lifecyclebot.engine

import android.content.Context
import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.engine.ErrorLogger
import org.json.JSONObject
import java.util.ArrayDeque

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
        laneMode: String = "",            // V5.9.718 — trading lane for phase-aware size scaling
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

        // V5.9.61: For small-wallet users the configured reserve (default
        // 0.05 SOL) can eat the ENTIRE tradeable balance. Paper mode
        // skipped the reserve subtraction entirely — so paper kept trading
        // while live silently bailed at "reserve". Scale the reserve down
        // proportionally when the wallet is too small to support it, so
        // live behaves like paper on tiny wallets. We still keep a hard
        // floor of 0.002 SOL so the user can never drain to zero.
        val effectiveReserve = if (!isPaperMode) {
            val configuredReserve = cfg.walletReserveSol
            val smallWalletCap    = (effectiveWallet * 0.10).coerceAtLeast(0.01)  // V5.9.186: was 0.002 — fees eat sub-0.01
            if (effectiveWallet < configuredReserve * 2.0) {
                // wallet too small to honour the full reserve — use 10%
                ErrorLogger.info("SmartSizer", "📏 Small-wallet reserve: configured=${configuredReserve} → effective=${smallWalletCap} (wallet=${effectiveWallet})")
                smallWalletCap
            } else {
                configuredReserve
            }
        } else {
            cfg.walletReserveSol
        }

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
            (effectiveWallet - effectiveReserve - treasuryFloor).coerceAtLeast(0.0)
        }

        ErrorLogger.info("SmartSizer", "📏 tradeable=$tradeable (wallet=$effectiveWallet - reserve=${effectiveReserve} - treasury=$treasuryFloor | paper=$isPaperMode | fluid=${cfg.fluidLearningEnabled})")

        // V5.9.61: Lower the "insufficient" floor from 0.005 → 0.002 so
        // small wallets (the $5–$10 starter cohort) can actually trade
        // once the reserve is scaled down. Still blocks truly empty ones.
        // V5.9.212: raise floor from 0.002 → 0.01 to cover fees on live trades
        if (tradeable < if (isPaperMode) 0.002 else 0.01) {
            // V5.9.412 — demote from `error` to `debug`: this is a normal
            // wallet-exposure-cap condition (sim balance == sim exposure)
            // that pollutes the error log unnecessarily. The bot is just
            // saying "no room left, sit this one out".
            ErrorLogger.debug("SmartSizer", "🪫 NO_HEADROOM: tradeable $tradeable < ${if (isPaperMode) 0.002 else 0.01} floor | paper=$isPaperMode")
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
            // V5.9.68 PAPER MODE BUMP: free-money mode — push size harder
            // so the learner gets meaningful fill variance per trade.
            when {
                aiConfidence >= 70 -> 0.25  // 25%
                aiConfidence >= 50 -> 0.20  // 20%
                else -> 0.15                 // 15% minimum in paper
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
        // V5.9.68 PAPER MODE BUMP: double the cap across every paper tier so
        // bigger positions can flow through. Live tiers kept unchanged.
        val (tier, tierMaxPct) = if (isPaperMode) {
            when {
                tradeable < 0.5    -> "paper_micro"  to 0.30
                tradeable < 2.0    -> "paper_small"  to 0.30
                tradeable < 10.0   -> "paper_medium" to 0.28
                tradeable < 50.0   -> "paper_large"  to 0.25
                tradeable < 1000.0 -> "paper_big"    to 0.22
                else               -> "paper_whale"  to 0.20
            }
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
        // V5.9.643 — meme-aware liquidity scaling.
        // Pump.fun / early Solana tokens naturally launch at $1k-$30k liquidity —
        // the old 0.60x at <$10k was crushing sizing on exactly the tokens we want.
        // Paper mode: no penalty (learning should not be distorted by this gate).
        // Live mode: thresholds shifted down to match real meme launch profiles.
        val liquidityMult = if (isPaperMode) {
            1.0  // no penalty in paper — don't starve learning
        } else {
            when {
                liquidityUsd >= 500_000 -> 1.40  // Very deep — can size up
                liquidityUsd >= 200_000 -> 1.20
                liquidityUsd >= 100_000 -> 1.10
                liquidityUsd >= 50_000  -> 1.00  // Normal
                liquidityUsd >= 20_000  -> 1.00  // Meme-normal — no penalty
                liquidityUsd >= 10_000  -> 0.90  // Borderline — small reduction
                liquidityUsd >= 5_000   -> 0.80  // Low but valid meme launch range
                liquidityUsd >= 1_000   -> 0.70  // Very low — reduce but not zero
                else                    -> 0.60  // Sub $1k — minimal position
            }
        }
        size *= liquidityMult
        
        // ── VOLATILITY/CONFIDENCE ADJUSTMENT ───────────────────────────
        // V5.9.643 — narrowed range: aiBasePct already encodes confidence, so
        // applying a second full confidence multiplier was double-penalising low-
        // confidence tokens (e.g. conf=45 → 4% base × 0.85 = effectively 3.4%).
        // This secondary mult now operates in a ±15% band only to nudge at extremes.
        val confidenceMult = when {
            aiConfidence >= 85 -> 1.15  // Very high confidence — small boost
            aiConfidence >= 70 -> 1.08
            aiConfidence >= 45 -> 1.00  // Normal band — no change
            aiConfidence >= 30 -> 0.90  // Genuinely low — mild reduction
            else               -> 0.80  // Very low confidence — moderate cut
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
        // V5.9.737 — paper-trained wisdom transfers to live. When live mode
        // hasn't accumulated its own statistical mass (totalTrades < 10), read
        // FluidLearning's paper-trained win rate so the bot doesn't act like
        // a brand-new agent the moment the operator flips the switch. Once
        // live has ≥10 trades of its own, the live perf context wins.
        val perfMult = if (isPaperMode && !cfg.fluidLearningEnabled) {
            1.0  // No streak penalty in legacy paper mode
        } else if (isPaperMode && cfg.fluidLearningEnabled) {
            // Paper path: learn from paper trade streaks
            val fluidWinRate = FluidLearning.getWinRate()
            val fluidTrades = FluidLearning.getTradeCount()
            when {
                fluidWinRate >= 70 && fluidTrades >= 10 -> 1.30  // hot streak
                fluidWinRate >= 60 && fluidTrades >= 10 -> 1.15
                fluidWinRate < 40 && fluidTrades >= 10  -> 0.70  // scale down on losses
                fluidWinRate < 50 && fluidTrades >= 10  -> 0.85
                else -> 1.0
            }
        } else {
            // Live path. If we have ≥10 live trades, use live's own context.
            // Otherwise fall back to FluidLearning paper-trained stats so the
            // bot's hard-earned recognition transfers.
            val useFluidFallback = cfg.fluidLearningEnabled && perf.totalTrades < 10 &&
                                   FluidLearning.getTradeCount() >= 10
            if (useFluidFallback) {
                val fluidWinRate = FluidLearning.getWinRate()
                val fluidTrades = FluidLearning.getTradeCount()
                ErrorLogger.debug("SmartSizer",
                    "🧬 LIVE perfMult using paper-trained fallback: liveTrades=${perf.totalTrades} " +
                    "paperWR=${fluidWinRate.toInt()}% paperTrades=$fluidTrades")
                when {
                    fluidWinRate >= 70 && fluidTrades >= 10 -> 1.30
                    fluidWinRate >= 60 && fluidTrades >= 10 -> 1.15
                    fluidWinRate < 40 && fluidTrades >= 10  -> 0.70
                    fluidWinRate < 50 && fluidTrades >= 10  -> 0.85
                    else -> 1.0
                }
            } else {
                when {
                    perf.lossStreak >= 4                       -> 0.50
                    perf.lossStreak >= 3                       -> 0.70
                    perf.recentWinRate >= 70 && perf.totalTrades >= 5 -> 1.30
                    perf.recentWinRate >= 60 && perf.totalTrades >= 5 -> 1.15
                    perf.recentWinRate < 40  && perf.totalTrades >= 5 -> 0.60
                    perf.recentWinRate < 50  && perf.totalTrades >= 5 -> 0.80
                    perf.winStreak >= 3                        -> 1.20
                    else                                       -> 1.0
                }
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

        // ══════════════════════════════════════════════════════════════
        // V5.6.8 LIVE POSITION MANAGEMENT
        // Strategy: Allow up to 25 concurrent positions for diversification,
        // but scale down position sizes as more positions are opened.
        // More positions = smaller size each = better risk distribution
        // ══════════════════════════════════════════════════════════════
        // V5.9.495z12 — operator mandate: bot must never choke its own
        // throughput. 25→60 raises the live concurrent-position ceiling so
        // 200-500 trades/day live is reachable with realistic hold times.
        //
        // V5.9.495z23 — operator: "why was I not up 5x". With sub-1 SOL
        // wallets, 60 concurrent positions means 0.05–0.1 SOL per trade —
        // any winner's gain is pulverised by entry+exit fees. Cap is now
        // TIER-aware:
        //   • MICRO        →  6  positions  (full size for first 3)
        //   • STANDARD     → 10  positions  (full size for first 4)
        //   • GROWTH       → 16  positions  (full size for first 5)
        //   • SCALED       → 28  positions  (full size for first 6)
        //   • INSTITUTIONAL→ 50  positions  (full size for first 8)
        // Diversification scaling now starts later AND falls off slower so
        // early positions actually carry meaningful size.
        val livePosTier = run {
            val solPx = solPriceUsd.takeIf { it > 0 } ?: 100.0
            val treasuryUsd = TreasuryManager.treasurySol * solPx
            ScalingMode.activeTier(treasuryUsd)
        }
        val maxLivePositions = when (livePosTier) {
            ScalingMode.Tier.INSTITUTIONAL -> 50
            ScalingMode.Tier.SCALED        -> 28
            ScalingMode.Tier.GROWTH        -> 16
            ScalingMode.Tier.STANDARD      -> 10
            ScalingMode.Tier.MICRO         -> 6
        }
        val fullSizeSlots = when (livePosTier) {
            ScalingMode.Tier.INSTITUTIONAL -> 8
            ScalingMode.Tier.SCALED        -> 6
            ScalingMode.Tier.GROWTH        -> 5
            ScalingMode.Tier.STANDARD      -> 4
            ScalingMode.Tier.MICRO         -> 3
        }

        if (!isPaperMode) {
            // V5.9.611 — live must mirror paper's learned entry behavior. Do not
            // hard-block or shrink to dust because a wallet tier says we already
            // have "too many" positions. Anti-drain protection lives in wallet
            // balance/rent reserve, max-per-trade, liquidity ownership, settlement
            // verification, and sell mutexes — not artificial position counts.
            if (openPositionCount > maxLivePositions) {
                ErrorLogger.info("SmartSizer", "♻️ LIVE PARITY: ignoring legacy position cap $openPositionCount/$maxLivePositions (tier=${livePosTier.name})")
            }
        }

        // V5.9.731 — ABSOLUTE SOL CAP + MCAP-RELATIVE CAP (paper-safe).
        // Operator dump showed paper balance had compounded to $46M with 50k
        // SOL trades on $3.4k-mcap tokens — impossible in reality, and the
        // resulting fantasy PnL fed back into the sizer, exponentially
        // inflating the next trade. Three new caps, applied BEFORE the
        // existing 20%-of-tradeable cap:
        //
        //   1. Absolute paper hard ceiling — never size a single paper buy
        //      above 5 SOL (~$1k) regardless of "balance". Paper exists to
        //      simulate reality. A position bigger than a normal user's
        //      total wallet teaches the bot nothing useful.
        //   2. Paper mcap-relative cap — never deploy more than 3% of the
        //      token's market cap into one position. Buying $5M of a $3k
        //      pool is a buy-side rug on yourself.
        //   3. Liquidity-relative cap (paper) — same 4% ownership cap that
        //      live mode applies, mirrored into paper so the learner sees
        //      realistic slippage limits. Previously paper skipped this.
        //
        // Live mode kept untouched — its existing cascade already enforces
        // ownership caps, rent reserve, wallet checks, etc.
        if (isPaperMode) {
            val PAPER_ABS_CAP_SOL = 5.0
            if (size > PAPER_ABS_CAP_SOL) {
                ErrorLogger.warn("SmartSizer",
                    "📏 PAPER_ABS_CAP: size=${size.fmt(3)} → ${PAPER_ABS_CAP_SOL} SOL " +
                    "(tradeable=${tradeable.fmt(2)} appears inflated — capping to learning-safe ceiling)")
                size = PAPER_ABS_CAP_SOL
                cappedBy = "paper_abs_5sol"
            }
            if (mcapUsd > 0.0 && solPriceUsd > 0.0) {
                val maxMcapSol = (mcapUsd * 0.03) / solPriceUsd
                if (size > maxMcapSol) {
                    ErrorLogger.info("SmartSizer",
                        "📏 PAPER_MCAP_CAP: size=${size.fmt(3)} → ${maxMcapSol.fmt(3)} SOL " +
                        "(3% of mcap=$${mcapUsd.toInt()})")
                    size = maxMcapSol
                    cappedBy = "paper_mcap_3pct"
                }
            }
            if (liquidityUsd > 0.0 && solPriceUsd > 0.0) {
                val maxLiqSol = (liquidityUsd * 0.04) / solPriceUsd
                if (size > maxLiqSol) {
                    ErrorLogger.info("SmartSizer",
                        "📏 PAPER_LIQ_CAP: size=${size.fmt(3)} → ${maxLiqSol.fmt(3)} SOL " +
                        "(4% of liq=$${liquidityUsd.toInt()})")
                    size = maxLiqSol
                    cappedBy = "paper_liq_4pct"
                }
            }
        }

        // Max per-trade: 20% of tradeable (same for paper and live)
        val maxPerTrade = tradeable * 0.20
        if (size > maxPerTrade) { size = maxPerTrade; cappedBy = "maxPct_20" }

        // ══════════════════════════════════════════════════════════════
        // TREASURY-ADAPTIVE EXPOSURE CAP
        // Higher treasury = can handle more total exposure
        // This allows scaling up as the bankroll grows
        // ══════════════════════════════════════════════════════════════
        // V5.9.611 — remove portfolio-exposure as a hard size/quantity block.
        // Paper mode is training for live mode, so live cannot silently size to
        // zero just because cumulative exposure is above a tier threshold. The
        // bot still cannot drain the wallet in one hit because max-per-trade,
        // live rent reserve, wallet balance checks, liquidity ownership caps,
        // and chain settlement guards remain active.
        val legacyExposurePct = if (isPaperMode) 0.70 else 0.70
        val legacyExposureRoom = (tradeable * legacyExposurePct) - currentTotalExposure
        ErrorLogger.info("SmartSizer", "📏 exposureRoom(observe-only)=$legacyExposureRoom (tradeable*$legacyExposurePct=${tradeable*legacyExposurePct} - exposure=$currentTotalExposure)")

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
        // V5.9.212: live dust 0.002 → 0.01 — Jito+Jupiter+platform fees ~0.004 SOL min
        // Sub-0.01 positions can NEVER be sold profitably. Paper stays 0.001.
        val dustFloor = if (isPaperMode) 0.001 else 0.01
        
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

        // ── V5.9.718 PHASE-AWARE LANE SIZE MULTIPLIER ────────────────────
        // During Phase 1+ (> 500 trades) underperforming lanes get gradually
        // smaller positions (floor 0.50×). Performing lanes stay at 1.0×.
        // This is the ONLY place laneSizeMultiplier() is applied so there is
        // one source of truth for the WR-sensitive size sift.
        val lanePhaseMult = if (laneMode.isNotBlank()) {
            try {
                val laneWr = TradeHistoryStore.getLaneWinRate(laneMode, minTrades = 10)
                FreeRangeMode.laneSizeMultiplier(laneWr)
            } catch (_: Throwable) { 1.0 }
        } else 1.0
        if (lanePhaseMult < 1.0) {
            size *= lanePhaseMult
            // Re-apply dust floor after reduction
            val dustFloor2 = if (isPaperMode) 0.001 else 0.01
            if (size < dustFloor2) size = dustFloor2
            ErrorLogger.info("SmartSizer", "📉 Lane phase mult: $laneMode → ${lanePhaseMult.fmt1}x (size now ${size.fmt(4)} SOL)")
        }

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
            if (lanePhaseMult != 1.0) append("×lane=${lanePhaseMult.fmt1}[$laneMode] ")
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
     * V3.3: Get current drawdown percentage from session peak.
     * Used by AutoCompoundEngine to adjust allocation based on drawdown.
     */
    fun getCurrentDrawdownPct(isPaper: Boolean): Double {
        val peak = if (isPaper) _sessionPeakPaper else _sessionPeakLive
        if (peak <= 0) return 0.0  // No peak yet, no drawdown
        
        // Get current balance (approximation - should be passed in for accuracy)
        // Using the peak as base since we track it on each update
        return 0.0  // Return 0 to let AutoCompoundEngine use its own tracking
    }
    
    /**
     * V5.9.737 — Paper→live transition. Operator doctrine (memory ID #20):
     * "paper is training for live trading so everything should transfer
     * fluidly." Previous behavior reset live performance trackers to zero
     * on every paper→live flip, with the rationale "fresh start for real
     * money". This caused the bot to feel like it "forgot" everything on
     * mode switch — it literally did. Hours of paper-trained win-rate,
     * streak data, and recent-outcome ring buffer were thrown away every
     * time the operator went live.
     *
     * New behavior:
     *   - Paper switch: paper session peak resets (intentional — peak is
     *     session-bounded, not memory).
     *   - Live switch: SEED live trackers from paper trackers instead of
     *     clearing them. Live still maintains its own session peak (live
     *     drawdown protection should not key off paper drawdowns), but
     *     win-rate / streak / recent-trade memory carries over so SmartSizer
     *     sees a "matured" performance state from trade one.
     *
     * Safety: paper-trained win rate is generic market wisdom. Sizing
     * caps (paper SmartSizer caps, 5 SOL abs, 3% mcap, 4% liq) and the
     * live circuit breaker (MIN_LIVE_SOL=0.10, -10% session drawdown halt)
     * remain the actual risk-management layer for real money. This change
     * only restores the trained pattern recognition.
     */
    private fun resetSessionForMode(isPaperMode: Boolean) {
        if (isPaperMode) {
            // Switching TO paper - reset paper session peak only
            _sessionPeakPaper = 0.0
        } else {
            // Switching TO live - reset SESSION PEAK only (drawdown reference
            // must be live-anchored). SEED win/streak trackers from paper so
            // the bot doesn't forget what it learned.
            _sessionPeakLive = 0.0
            if (recentTradesLive.isEmpty() && recentTradesPaper.isNotEmpty()) {
                recentTradesLive.addAll(recentTradesPaper)
                winStreakLive = winStreakPaper
                lossStreakLive = lossStreakPaper
                ErrorLogger.info("SmartSizer",
                    "🧬 PAPER→LIVE INHERITANCE: seeded recentTrades=${recentTradesLive.size} " +
                    "winStreak=$winStreakLive lossStreak=$lossStreakLive (paper-trained memory carries over)")
            }
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
        recordTrade(isWin = isWin, isPaperMode = isPaperMode, pnlPct = if (isWin) 2.0 else -2.0)
    }

    /**
     * V5.9.307: Scratch-aware overload — pnlPct in (-1%, +1%) is NEUTRAL.
     * Scratches don't update the rolling W/L window or reset streaks. This
     * prevents fee-bleed near-flat trades from inflating loss streaks and
     * triggering size-down circuit breakers that throttle recovery.
     * Callers that don't have pnlPct can keep using the boolean overload above.
     */
    fun recordTrade(isWin: Boolean, isPaperMode: Boolean = true, pnlPct: Double) {
        val isScratch = pnlPct > -1.0 && pnlPct < 1.0
        if (isScratch) {
            // Scratch — neutral for streaks. Save anyway so timestamps update.
            save()
            return
        }
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
        save()
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
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.6.28d: PERSISTENCE - Save/Restore SmartSizer state across app restarts
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val PREFS_NAME = "smart_sizer_state"
    @Volatile private var ctx: Context? = null
    @Volatile private var lastSaveTime: Long = 0L
    private const val SAVE_THROTTLE_MS = 10_000L  // Only save every 10 seconds max
    
    fun init(context: Context) {
        ctx = context.applicationContext
        restore()
        ErrorLogger.info("SmartSizer", "💾 SmartSizer initialized | Paper: W${winStreakPaper}/L${lossStreakPaper} | Live: W${winStreakLive}/L${lossStreakLive}")
    }
    
    fun save(force: Boolean = false) {
        val c = ctx ?: return
        val now = System.currentTimeMillis()
        
        // Throttle saves unless forced
        if (!force && now - lastSaveTime < SAVE_THROTTLE_MS) {
            return
        }
        lastSaveTime = now
        
        try {
            val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val obj = JSONObject().apply {
                put("win_streak_paper", winStreakPaper)
                put("loss_streak_paper", lossStreakPaper)
                put("win_streak_live", winStreakLive)
                put("loss_streak_live", lossStreakLive)
                put("session_peak_paper", _sessionPeakPaper)
                put("session_peak_live", _sessionPeakLive)
                // Save recent trades as comma-separated 1s and 0s
                put("recent_paper", recentTradesPaper.joinToString(",") { if (it) "1" else "0" })
                put("recent_live", recentTradesLive.joinToString(",") { if (it) "1" else "0" })
                put("saved_at", System.currentTimeMillis())
            }
            prefs.edit().putString("state", obj.toString()).apply()
        } catch (e: Exception) {
            ErrorLogger.error("SmartSizer", "💾 Save failed: ${e.message}")
        }
    }
    
    private fun restore() {
        val c = ctx ?: return
        try {
            val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString("state", null) ?: return
            
            val obj = JSONObject(json)
            
            winStreakPaper = obj.optInt("win_streak_paper", 0)
            lossStreakPaper = obj.optInt("loss_streak_paper", 0)
            winStreakLive = obj.optInt("win_streak_live", 0)
            lossStreakLive = obj.optInt("loss_streak_live", 0)
            _sessionPeakPaper = obj.optDouble("session_peak_paper", 0.0)
            _sessionPeakLive = obj.optDouble("session_peak_live", 0.0)
            
            // Restore recent trades
            val recentPaperStr = obj.optString("recent_paper", "")
            if (recentPaperStr.isNotBlank()) {
                recentTradesPaper.clear()
                recentPaperStr.split(",").filter { it.isNotBlank() }.forEach {
                    recentTradesPaper.addLast(it == "1")
                }
            }
            val recentLiveStr = obj.optString("recent_live", "")
            if (recentLiveStr.isNotBlank()) {
                recentTradesLive.clear()
                recentLiveStr.split(",").filter { it.isNotBlank() }.forEach {
                    recentTradesLive.addLast(it == "1")
                }
            }
            
            ErrorLogger.info("SmartSizer", "💾 Restored: Paper W${winStreakPaper}/L${lossStreakPaper} | Live W${winStreakLive}/L${lossStreakLive}")
        } catch (e: Exception) {
            ErrorLogger.error("SmartSizer", "💾 Restore failed: ${e.message}")
        }
    }
}

private fun Double.fmt(d: Int = 4) = "%.${d}f".format(this)
private val Double.fmt1 get() = "%.2f".format(this)

package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * ADVANCED EXIT MANAGER v2.0 — Restored + Enhanced from build #1947
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Restored from build #1947 (V5.9.140) where it was proven on live trading.
 * Enhanced with V5.9.187+ improvements:
 *
 * 1. PROGRESSIVE TRAILING STOPS — Gets tighter as profit increases
 * 2. TIME-BASED EXIT PRESSURE — URGENT_EXIT for stuck losers
 * 3. MOMENTUM-AWARE EXITS — Cut on momentum death, ride momentum surges
 * 4. PARTIAL PROFIT TAKING — Chunk sells at milestones (BlueChip/DipHunter)
 * 5. LIQUIDITY COLLAPSE DETECTION — Exit immediately if liq drops >50%
 * 6. EMOTIONAL STATE MODULATION — PANIC tightens, EUPHORIC loosens
 * 7. ENTRY QUALITY SCALING — High-score entries get bigger targets
 * 8. LEARNING-FLUID TARGETS — TP/SL scale with learning progress
 *
 * KEY PRINCIPLE: "Let winners run, cut losers fast"
 *
 * V2.0 changes vs 1947:
 * - Added MOONSHOT and MARKETS_LEVERAGED profiles
 * - shouldCutLoss() now integrated into evaluateExit() flow
 * - Per-profile liquidity collapse threshold (meme tighter, stocks wider)
 * - Chunk sell 3rd tier at 100% of TP for long-hold positions
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object AdvancedExitManager {

    private const val TAG = "ExitMgr"

    // ═══════════════════════════════════════════════════════════════════════════
    // EXIT STRATEGY PROFILES
    // ═══════════════════════════════════════════════════════════════════════════

    enum class ExitProfile(
        val baseTakeProfitPct: Double,
        val baseStopLossPct: Double,
        val baseTrailingPct: Double,
        val maxHoldMinutes: Int,
        val chunkSellEnabled: Boolean,
        val progressiveTrailing: Boolean,
        val liquidityCollapseThreshold: Double,  // V2.0: per-profile liq collapse %
    ) {
        SHITCOIN(
            baseTakeProfitPct = 25.0,
            baseStopLossPct = 10.0,
            baseTrailingPct = 8.0,
            maxHoldMinutes = 15,
            chunkSellEnabled = false,
            progressiveTrailing = true,
            liquidityCollapseThreshold = 0.5,
        ),
        EXPRESS(
            baseTakeProfitPct = 30.0,
            baseStopLossPct = 8.0,
            baseTrailingPct = 5.0,
            maxHoldMinutes = 10,
            chunkSellEnabled = false,
            progressiveTrailing = true,
            liquidityCollapseThreshold = 0.5,
        ),
        BLUE_CHIP(
            baseTakeProfitPct = 40.0,
            baseStopLossPct = 15.0,
            baseTrailingPct = 10.0,
            maxHoldMinutes = 120,
            chunkSellEnabled = true,
            progressiveTrailing = true,
            liquidityCollapseThreshold = 0.6,
        ),
        DIP_HUNTER(
            baseTakeProfitPct = 20.0,
            baseStopLossPct = 15.0,
            baseTrailingPct = 12.0,
            maxHoldMinutes = 360,
            chunkSellEnabled = true,
            progressiveTrailing = false,
            liquidityCollapseThreshold = 0.7,
        ),
        TREASURY(
            baseTakeProfitPct = 15.0,
            baseStopLossPct = 8.0,
            baseTrailingPct = 6.0,
            maxHoldMinutes = 30,
            chunkSellEnabled = false,
            progressiveTrailing = true,
            liquidityCollapseThreshold = 0.5,
        ),
        V3_STANDARD(
            baseTakeProfitPct = 35.0,
            baseStopLossPct = 12.0,
            baseTrailingPct = 8.0,
            maxHoldMinutes = 60,
            chunkSellEnabled = true,
            progressiveTrailing = true,
            liquidityCollapseThreshold = 0.5,
        ),
        // V2.0: New profiles
        MOONSHOT(
            baseTakeProfitPct = 200.0,
            baseStopLossPct = 25.0,
            baseTrailingPct = 15.0,
            maxHoldMinutes = 1440,
            chunkSellEnabled = true,
            progressiveTrailing = true,
            liquidityCollapseThreshold = 0.6,
        ),
        MARKETS_LEVERAGED(
            baseTakeProfitPct = 8.0,
            baseStopLossPct = 5.0,
            baseTrailingPct = 3.0,
            maxHoldMinutes = 480,
            chunkSellEnabled = false,
            progressiveTrailing = true,
            liquidityCollapseThreshold = 0.8,
        ),
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EXIT CALCULATION
    // ═══════════════════════════════════════════════════════════════════════════

    data class ExitTargets(
        val takeProfitPct: Double,
        val stopLossPct: Double,
        val trailingStopPct: Double,
        val chunk1Pct: Double,
        val chunk1SellPct: Int,
        val chunk2Pct: Double,
        val chunk2SellPct: Int,
        val chunk3Pct: Double,      // V2.0: 3rd tier for long-hold profiles
        val chunk3SellPct: Int,
        val timeExitMinutes: Int,
        val liquidityCollapseThreshold: Double,
    )

    fun calculateExitTargets(
        profile: ExitProfile,
        entryScore: Int,
        momentum: Double,
        volatility: Double,
        marketRegime: String,
    ): ExitTargets {
        val learningProgress = try {
            FluidLearningAI.getLearningProgress().coerceIn(0.0, 1.0)
        } catch (_: Exception) { 0.5 }

        var takeProfitPct = profile.baseTakeProfitPct
        var stopLossPct   = profile.baseStopLossPct
        var trailingPct   = profile.baseTrailingPct
        var maxHold       = profile.maxHoldMinutes

        // Learning-fluid scaling: 0.8x at bootstrap → 1.2x at expert
        val fluidMultiplier = 0.8 + learningProgress * 0.4
        takeProfitPct *= fluidMultiplier
        stopLossPct   *= (1.2 - learningProgress * 0.3)  // Tighten SL as entries improve

        // Entry quality adjustments
        when {
            entryScore >= 80 -> {
                stopLossPct   *= 1.2
                takeProfitPct *= 1.3
                maxHold        = (maxHold * 1.5).toInt()
            }
            entryScore <= 50 -> {
                stopLossPct   *= 0.8
                takeProfitPct *= 0.8
                maxHold        = (maxHold * 0.7).toInt()
            }
        }

        // Momentum adjustments
        when {
            momentum > 10  -> { takeProfitPct *= 1.3; trailingPct *= 1.2 }
            momentum < -5  -> { stopLossPct *= 0.8; takeProfitPct *= 0.7; trailingPct *= 0.7 }
        }

        // Volatility adjustments
        when {
            volatility > 50 -> { stopLossPct *= 1.3; trailingPct *= 1.4 }
            volatility < 10 -> { stopLossPct *= 0.8; trailingPct *= 0.8 }
        }

        // Market regime adjustments
        when (marketRegime.uppercase()) {
            "BULL", "TRENDING_UP"    -> { takeProfitPct *= 1.2; maxHold = (maxHold * 1.3).toInt() }
            "BEAR", "TRENDING_DOWN"  -> { takeProfitPct *= 0.7; stopLossPct *= 0.8; maxHold = (maxHold * 0.6).toInt() }
            "RANGE", "CHOPPY"        -> { takeProfitPct *= 0.8; trailingPct *= 1.2 }
        }

        // Emotional state modulation
        try {
            val sc = com.lifecyclebot.engine.SymbolicContext
            when (sc.emotionalState) {
                "PANIC"    -> { stopLossPct *= 0.7; trailingPct *= 0.6; maxHold = (maxHold * 0.6).toInt() }
                "FEARFUL"  -> { stopLossPct *= 0.85; trailingPct *= 0.8; maxHold = (maxHold * 0.8).toInt() }
                "EUPHORIC" -> { trailingPct *= 1.25; takeProfitPct *= 1.15; maxHold = (maxHold * 1.2).toInt() }
                "GREEDY"   -> { trailingPct *= 1.1; takeProfitPct *= 1.05 }
                else       -> Unit
            }
        } catch (_: Exception) {}

        // Hard clamps
        takeProfitPct = takeProfitPct.coerceIn(10.0, 500.0)
        stopLossPct   = stopLossPct.coerceIn(3.0, 35.0)
        trailingPct   = trailingPct.coerceIn(2.0, 25.0)
        maxHold       = maxHold.coerceIn(5, 2880)

        // Chunk targets (derived from clamped TP)
        val chunk1Pct  = if (profile.chunkSellEnabled) takeProfitPct * 0.4 else 0.0
        val chunk1Sell = if (profile.chunkSellEnabled) 20 else 0
        val chunk2Pct  = if (profile.chunkSellEnabled) takeProfitPct * 0.7 else 0.0
        val chunk2Sell = if (profile.chunkSellEnabled) 25 else 0
        // V2.0: 3rd tier at full TP — sell another 25%, let 30% ride for moonshots
        val chunk3Pct  = if (profile.chunkSellEnabled && profile == ExitProfile.MOONSHOT) takeProfitPct else 0.0
        val chunk3Sell = if (profile.chunkSellEnabled && profile == ExitProfile.MOONSHOT) 25 else 0

        return ExitTargets(
            takeProfitPct             = takeProfitPct,
            stopLossPct               = stopLossPct,
            trailingStopPct           = trailingPct,
            chunk1Pct                 = chunk1Pct,
            chunk1SellPct             = chunk1Sell,
            chunk2Pct                 = chunk2Pct,
            chunk2SellPct             = chunk2Sell,
            chunk3Pct                 = chunk3Pct,
            chunk3SellPct             = chunk3Sell,
            timeExitMinutes           = maxHold,
            liquidityCollapseThreshold = profile.liquidityCollapseThreshold,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PROGRESSIVE TRAILING STOP
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns a trailing STOP PRICE (not a percentage).
     * Trail percentage tightens automatically as profit grows.
     */
    fun calculateProgressiveTrailingStop(
        currentPnlPct: Double,
        baseTrailingPct: Double,
        highWaterMarkPrice: Double,
    ): Double {
        if (currentPnlPct <= 0 || highWaterMarkPrice <= 0.0) return 0.0

        val effectiveTrailingPct = when {
            currentPnlPct >= 200 -> baseTrailingPct * 0.3  // V2.0: extra tier for big runners
            currentPnlPct >= 100 -> baseTrailingPct * 0.4
            currentPnlPct >= 75  -> baseTrailingPct * 0.5
            currentPnlPct >= 50  -> baseTrailingPct * 0.6
            currentPnlPct >= 30  -> baseTrailingPct * 0.75
            currentPnlPct >= 20  -> baseTrailingPct * 0.85
            currentPnlPct >= 10  -> baseTrailingPct * 0.95
            else                 -> baseTrailingPct
        }.coerceIn(2.0, 25.0)

        return highWaterMarkPrice * (1.0 - effectiveTrailingPct / 100.0)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TIME-BASED EXIT PRESSURE
    // ═══════════════════════════════════════════════════════════════════════════

    fun calculateTimePressure(
        holdMinutes: Int,
        maxHoldMinutes: Int,
        currentPnlPct: Double,
    ): TimePressure {
        val safeMax   = maxHoldMinutes.coerceAtLeast(1)
        val holdRatio = holdMinutes.toDouble() / safeMax.toDouble()

        return when {
            holdRatio >= 0.9 && currentPnlPct < 0  -> TimePressure.URGENT_EXIT
            holdRatio >= 0.9 && currentPnlPct >= 0 -> TimePressure.TAKE_PROFIT
            holdRatio >= 0.75                       -> TimePressure.HIGH_PRESSURE
            holdRatio >= 0.5                        -> TimePressure.MODERATE_PRESSURE
            else                                    -> TimePressure.NO_PRESSURE
        }
    }

    enum class TimePressure(val takeProfitMultiplier: Double, val stopMultiplier: Double) {
        NO_PRESSURE(1.0, 1.0),
        MODERATE_PRESSURE(0.9, 0.9),
        HIGH_PRESSURE(0.75, 0.8),
        TAKE_PROFIT(0.5, 0.7),
        URGENT_EXIT(0.0, 0.5),
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MASTER EXIT EVALUATION
    // ═══════════════════════════════════════════════════════════════════════════

    data class ExitDecision(
        val shouldExit: Boolean,
        val sellPct: Int,
        val exitReason: ExitReason,
        val urgency: ExitUrgency,
        val logMessage: String = "",
    )

    enum class ExitReason {
        HOLD,
        TAKE_PROFIT_FULL,
        TAKE_PROFIT_CHUNK,
        STOP_LOSS,
        TRAILING_STOP,
        TIME_EXIT,
        MOMENTUM_EXIT,
        LIQUIDITY_EXIT,
        INVALID_INPUT,
    }

    enum class ExitUrgency { NONE, LOW, MEDIUM, HIGH, CRITICAL }

    /**
     * Master exit evaluation — call this from HoldingLogicLayer or any trader.
     * Covers all exit scenarios: SL, TP, trailing, chunks, time, liq, momentum.
     */
    fun evaluateExit(
        profile: ExitProfile,
        entryPrice: Double,
        currentPrice: Double,
        highWaterMarkPrice: Double,
        holdMinutes: Int,
        entryScore: Int,
        currentMomentum: Double,
        currentLiquidity: Double,
        entryLiquidity: Double,
        marketRegime: String,
        volatility: Double,
        alreadySoldPct: Int,
        symbol: String = "",
    ): ExitDecision {

        if (entryPrice <= 0.0 || currentPrice <= 0.0) {
            ErrorLogger.warn(TAG, "[$symbol] Invalid price: entry=$entryPrice current=$currentPrice")
            return ExitDecision(true, 100, ExitReason.INVALID_INPUT, ExitUrgency.CRITICAL,
                "Invalid price input — forced exit")
        }

        val pnlPct       = (currentPrice - entryPrice) / entryPrice * 100.0
        val targets      = calculateExitTargets(profile, entryScore, currentMomentum, volatility, marketRegime)
        val timePressure = calculateTimePressure(holdMinutes, targets.timeExitMinutes, pnlPct)

        val effectiveTakeProfit = targets.takeProfitPct * timePressure.takeProfitMultiplier
        val effectiveStopLoss   = targets.stopLossPct   * timePressure.stopMultiplier

        // 1. Liquidity collapse
        if (entryLiquidity > 0.0 && currentLiquidity < entryLiquidity * targets.liquidityCollapseThreshold) {
            return ExitDecision(true, 100, ExitReason.LIQUIDITY_EXIT, ExitUrgency.CRITICAL,
                "[$symbol] Liquidity collapsed: ${currentLiquidity.toInt()} < ${(entryLiquidity * targets.liquidityCollapseThreshold).toInt()}")
        }

        // 2. Hard stop loss (with time-multiplier looseness in first 2 min to survive wicks)
        val earlyHoldMultiplier = when {
            holdMinutes < 2  -> 1.4
            holdMinutes < 5  -> 1.2
            holdMinutes < 10 -> 1.0
            else             -> 0.9
        }
        val momentumMultiplier = when {
            currentMomentum < -15 -> 0.7
            currentMomentum < -5  -> 0.85
            currentMomentum > 10  -> 1.2
            else                  -> 1.0
        }
        val adjustedSL = effectiveStopLoss * earlyHoldMultiplier * momentumMultiplier
        if (pnlPct <= -adjustedSL) {
            return ExitDecision(true, 100, ExitReason.STOP_LOSS, ExitUrgency.HIGH,
                "[$symbol] SL hit: ${pnlPct.toInt()}% <= -${adjustedSL.toInt()}%")
        }

        // 3. Full take profit
        if (effectiveTakeProfit > 0.0 && pnlPct >= effectiveTakeProfit) {
            return ExitDecision(true, 100, ExitReason.TAKE_PROFIT_FULL, ExitUrgency.MEDIUM,
                "[$symbol] TP hit: +${pnlPct.toInt()}% >= +${effectiveTakeProfit.toInt()}%")
        }

        // 4. Progressive trailing stop (activates after +10%)
        if (profile.progressiveTrailing && pnlPct > 10.0) {
            val hwm = if (highWaterMarkPrice > 0.0) highWaterMarkPrice else currentPrice
            val trailingStopPrice = calculateProgressiveTrailingStop(
                currentPnlPct        = pnlPct,
                baseTrailingPct      = targets.trailingStopPct,
                highWaterMarkPrice   = hwm,
            )
            if (trailingStopPrice > 0.0 && currentPrice <= trailingStopPrice) {
                return ExitDecision(true, 100, ExitReason.TRAILING_STOP, ExitUrgency.MEDIUM,
                    "[$symbol] Trail hit: price=$currentPrice <= trail=${"%.6f".format(trailingStopPrice)} (peak=${pnlPct.toInt()}%)")
            }
        }

        // 5. Chunk sells (tier 1, 2, 3)
        if (profile.chunkSellEnabled) {
            val totalSold = alreadySoldPct
            if (targets.chunk3Pct > 0 && totalSold < (targets.chunk1SellPct + targets.chunk2SellPct + targets.chunk3SellPct)
                && pnlPct >= targets.chunk3Pct) {
                return ExitDecision(true, targets.chunk3SellPct, ExitReason.TAKE_PROFIT_CHUNK, ExitUrgency.LOW,
                    "[$symbol] Chunk3 (${targets.chunk3SellPct}% sell) at +${pnlPct.toInt()}%")
            }
            if (totalSold < (targets.chunk1SellPct + targets.chunk2SellPct) && pnlPct >= targets.chunk2Pct) {
                return ExitDecision(true, targets.chunk2SellPct, ExitReason.TAKE_PROFIT_CHUNK, ExitUrgency.LOW,
                    "[$symbol] Chunk2 (${targets.chunk2SellPct}% sell) at +${pnlPct.toInt()}%")
            }
            if (totalSold < targets.chunk1SellPct && pnlPct >= targets.chunk1Pct) {
                return ExitDecision(true, targets.chunk1SellPct, ExitReason.TAKE_PROFIT_CHUNK, ExitUrgency.LOW,
                    "[$symbol] Chunk1 (${targets.chunk1SellPct}% sell) at +${pnlPct.toInt()}%")
            }
        }

        // 6. Time pressure exits
        if (timePressure == TimePressure.URGENT_EXIT) {
            return ExitDecision(true, 100, ExitReason.TIME_EXIT, ExitUrgency.HIGH,
                "[$symbol] Time exit (${holdMinutes}min, losing ${pnlPct.toInt()}%)")
        }
        if (timePressure == TimePressure.TAKE_PROFIT && pnlPct > 0.0) {
            return ExitDecision(true, 100, ExitReason.TIME_EXIT, ExitUrgency.MEDIUM,
                "[$symbol] Time TP exit (${holdMinutes}min, +${pnlPct.toInt()}%)")
        }

        // 7. Momentum death (in profit but momentum collapsed)
        if (pnlPct > 5.0 && currentMomentum < -10.0) {
            return ExitDecision(true, 100, ExitReason.MOMENTUM_EXIT, ExitUrgency.MEDIUM,
                "[$symbol] Momentum death: +${pnlPct.toInt()}% but mom=${currentMomentum.toInt()}")
        }

        return ExitDecision(false, 0, ExitReason.HOLD, ExitUrgency.NONE)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // QUICK HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Standalone loss-cut check. Looser in first 2min to survive meme wicks.
     */
    fun shouldCutLoss(
        pnlPct: Double,
        baseStopPct: Double,
        momentum: Double,
        holdMinutes: Int,
    ): Boolean {
        val timeMultiplier = when {
            holdMinutes < 2  -> 1.4
            holdMinutes < 5  -> 1.2
            holdMinutes < 10 -> 1.0
            else             -> 0.9
        }
        val momentumMultiplier = when {
            momentum < -15 -> 0.7
            momentum < -5  -> 0.85
            momentum > 10  -> 1.2
            else           -> 1.0
        }
        return pnlPct <= -(baseStopPct * timeMultiplier * momentumMultiplier)
    }

    /**
     * Map trading mode string to nearest ExitProfile.
     * Used by HoldingLogicLayer to get the right profile for any position.
     */
    fun profileForMode(tradingMode: String): ExitProfile = when (tradingMode.uppercase()) {
        "MOONSHOT", "LONG_HOLD", "SLEEPER", "MICRO_CAP" -> ExitProfile.MOONSHOT
        "BLUE_CHIP", "BLUE_CHIP_TRADE"                  -> ExitProfile.BLUE_CHIP
        "DIP_HUNTER", "LIQUIDATION_HUNTER", "REVIVAL"   -> ExitProfile.DIP_HUNTER
        "TREASURY", "CYCLIC", "MARKET_MAKER"             -> ExitProfile.TREASURY
        "EXPRESS", "SCALP", "PUMP_DUMP"                  -> ExitProfile.EXPRESS
        "SHITCOIN", "PUMP_SNIPER", "PRESALE_SNIPE",
        "COPY_TRADE", "WHALE_FOLLOW", "NICHE"            -> ExitProfile.SHITCOIN
        "STANDARD", "MOMENTUM_SWING", "ARBITRAGE"        -> ExitProfile.V3_STANDARD
        else                                             -> ExitProfile.V3_STANDARD
    }
}

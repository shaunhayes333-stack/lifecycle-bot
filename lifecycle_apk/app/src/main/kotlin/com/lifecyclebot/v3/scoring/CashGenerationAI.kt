package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.AutoCompoundEngine
import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * CASH GENERATION AI - "TREASURY MODE" v2.0
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * A conservative, profit-focused trading mode designed for consistent daily cash flow.
 * User-configured for ACTIVE scalping with ULTRA-CONSERVATIVE loss limits.
 *
 * USER REQUIREMENTS (Dec 2024):
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. Daily Loss Limit: $100 (ULTRA-CONSERVATIVE) - pause after hitting
 * 2. Position Sizing: DYNAMIC - shrinks as daily target approaches
 * 3. Trade Frequency: ACTIVE (100+ trades/day, quick scalps)
 * 4. Profit Taking: QUICK SCALPS (5-10% profit, fast exit)
 * 5. SEPARATE Paper/Live Treasury Balances - switch display with mode
 *
 * PHILOSOPHY:
 * - Many small wins (5-10%) through quick scalping
 * - Cut losses IMMEDIATELY (max -2% per trade, $50/day total)
 * - Only C+ grade confidence setups
 * - Feed the treasury, never drain it
 * - "2nd shadow mode" that runs CONCURRENTLY with other trading
 *
 * GOALS:
 * - Target: $500-$1000 daily profit
 * - Max drawdown: $100/day
 * - Win rate target: 70%+
 * - Trade count: 100+ per day
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object CashGenerationAI {

    private const val TAG = "CashGenAI"

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════

    private const val DAILY_MAX_LOSS_SOL = 1.0

    // Position sizing
    private const val BASE_POSITION_SOL = 0.10
    private const val MAX_POSITION_SOL = 0.25
    private const val MIN_POSITION_SOL = 0.03
    private const val POSITION_SCALE_FACTOR = 1.15

    // Exit strategy
    private const val TAKE_PROFIT_PCT_PAPER = 3.5
    private const val TAKE_PROFIT_PCT_LIVE = 2.5
    private const val TAKE_PROFIT_MIN_PCT = 3.0
    private const val TAKE_PROFIT_PCT = 3.5
    private const val TAKE_PROFIT_MAX_PCT = 4.0
    private const val STOP_LOSS_PCT = -4.0
    private const val TRAILING_STOP_PCT = 1.5
    private const val MAX_HOLD_MINUTES = 30
    private const val REENTRY_COOLDOWN_MS = 5_000L

    private const val MIN_PROFIT_FOR_LIVE = 2.5

    // Trade frequency
    private const val MIN_TRADES_PER_DAY = 100
    private const val MAX_CONCURRENT_POSITIONS = 6

    // ═══════════════════════════════════════════════════════════════════════════
    // COMPOUNDING & IMMEDIATE TRADING
    // ═══════════════════════════════════════════════════════════════════════════

    private const val COMPOUNDING_ENABLED = true
    private const val COMPOUNDING_RATIO = 0.5
    private const val IMMEDIATE_TRADING = true
    private const val MIN_WARMUP_TOKENS = 0

    @Volatile
    private var isWarmedUp = true

    @Volatile
    private var tokensSeenSinceStart = 0

    // ═══════════════════════════════════════════════════════════════════════════
    // STATE TRACKING
    // ═══════════════════════════════════════════════════════════════════════════

    private val paperDailyPnlSolBps = AtomicLong(0)
    private val paperDailyWins = AtomicInteger(0)
    private val paperDailyLosses = AtomicInteger(0)
    private val paperDailyTradeCount = AtomicInteger(0)
    private val paperTreasuryBalanceBps = AtomicLong(600)

    private val liveDailyPnlSolBps = AtomicLong(0)
    private val liveDailyWins = AtomicInteger(0)
    private val liveDailyLosses = AtomicInteger(0)
    private val liveDailyTradeCount = AtomicInteger(0)
    private val liveTreasuryBalanceBps = AtomicLong(0)

    @Volatile
    private var isPaperMode: Boolean = true

    private var lastResetDay = 0

    private val paperPositions = mutableMapOf<String, TreasuryPosition>()
    private val livePositions = mutableMapOf<String, TreasuryPosition>()

    private val recentExits = mutableMapOf<String, Long>()

    private val activePositions: MutableMap<String, TreasuryPosition>
        get() = if (isPaperMode) paperPositions else livePositions

    private val dailyPnlSolBps: AtomicLong
        get() = if (isPaperMode) paperDailyPnlSolBps else liveDailyPnlSolBps

    private val dailyWins: AtomicInteger
        get() = if (isPaperMode) paperDailyWins else liveDailyWins

    private val dailyLosses: AtomicInteger
        get() = if (isPaperMode) paperDailyLosses else liveDailyLosses

    private val dailyTradeCount: AtomicInteger
        get() = if (isPaperMode) paperDailyTradeCount else liveDailyTradeCount

    private val treasuryBalanceBps: AtomicLong
        get() = if (isPaperMode) paperTreasuryBalanceBps else liveTreasuryBalanceBps

    private val currentPrices = ConcurrentHashMap<String, Double>()
    private val lastPriceUpdate = ConcurrentHashMap<String, Long>()

    data class TreasuryPosition(
        val mint: String,
        val symbol: String,
        val entryPrice: Double,
        val entrySol: Double,
        val entryTime: Long,
        val targetPrice: Double,
        val stopPrice: Double,
        var highWaterMark: Double,
        var trailingStop: Double,
        val isPaper: Boolean,
    )

    data class TreasurySignal(
        val shouldEnter: Boolean,
        val positionSizeSol: Double,
        val takeProfitPct: Double,
        val stopLossPct: Double,
        val confidence: Int,
        val reason: String,
        val mode: TreasuryMode,
        val isPaperMode: Boolean,
    )

    enum class TreasuryMode {
        HUNT,
        CRUISE,
        DEFENSIVE,
        PAUSED,
        AGGRESSIVE,
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MODE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    fun setTradingMode(isPaper: Boolean) {
        if (isPaperMode != isPaper) {
            ErrorLogger.info(
                TAG,
                "💰 TREASURY MODE SWITCH: ${if (isPaper) "PAPER" else "LIVE"} | " +
                    "Balance: ${getTreasuryBalance(isPaper).fmt(4)} SOL",
            )
        }
        isPaperMode = isPaper
    }

    fun getTreasuryBalance(isPaper: Boolean): Double {
        return if (isPaper) {
            paperTreasuryBalanceBps.get() / 100.0
        } else {
            liveTreasuryBalanceBps.get() / 100.0
        }
    }

    fun getCurrentTreasuryBalance(): Double = treasuryBalanceBps.get() / 100.0

    fun addToTreasury(profitSol: Double, isPaper: Boolean) {
        if (profitSol <= 0) return
        val bps = (profitSol * 100).toLong()
        if (isPaper) {
            paperTreasuryBalanceBps.addAndGet(bps)
        } else {
            liveTreasuryBalanceBps.addAndGet(bps)
        }
        ErrorLogger.info(
            TAG,
            "💰 TREASURY +${profitSol.fmt(4)} SOL | " +
                "Total ${if (isPaper) "PAPER" else "LIVE"}: ${getTreasuryBalance(isPaper).fmt(4)} SOL",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FLUID LEARNING
    // ═══════════════════════════════════════════════════════════════════════════

    fun getCurrentConfidenceThreshold(): Int = FluidLearningAI.getTreasuryConfidenceThreshold()

    fun getCurrentScoreThreshold(): Int = FluidLearningAI.getMinScoreThreshold()

    // ═══════════════════════════════════════════════════════════════════════════
    // CORE EVALUATION
    // ═══════════════════════════════════════════════════════════════════════════

    fun evaluate(
        mint: String,
        symbol: String,
        currentPrice: Double,
        liquidityUsd: Double,
        topHolderPct: Double,
        buyPressurePct: Double,
        v3Score: Int,
        v3Confidence: Int,
        momentum: Double,
        volatility: Double,
    ): TreasurySignal {
        val mode = getCurrentMode()

        if (mode == TreasuryMode.PAUSED) {
            return TreasurySignal(
                shouldEnter = false,
                positionSizeSol = 0.0,
                takeProfitPct = 0.0,
                stopLossPct = 0.0,
                confidence = 0,
                reason = "PAUSED: Daily loss limit (\$50) reached - waiting for reset",
                mode = mode,
                isPaperMode = isPaperMode,
            )
        }

        var treasuryScore = 0
        val scoreReasons = mutableListOf<String>()

        val liqScore = when {
            liquidityUsd >= 50_000 -> 25
            liquidityUsd >= 20_000 -> 20
            liquidityUsd >= 10_000 -> 15
            liquidityUsd >= 5_000 -> 10
            liquidityUsd >= 2_000 -> 5
            else -> 0
        }
        treasuryScore += liqScore
        if (liqScore > 0) scoreReasons.add("liq+$liqScore")

        val buyScore = when {
            buyPressurePct >= 70 -> 30
            buyPressurePct >= 60 -> 25
            buyPressurePct >= 55 -> 20
            buyPressurePct >= 50 -> 15
            buyPressurePct >= 45 -> 10
            buyPressurePct >= 40 -> 5
            else -> 0
        }
        treasuryScore += buyScore
        if (buyScore > 0) scoreReasons.add("buy+$buyScore")

        val momentumScore = when {
            momentum >= 10 -> 20
            momentum >= 5 -> 15
            momentum >= 2 -> 10
            momentum >= 0 -> 5
            momentum >= -3 -> 0
            momentum >= -5 -> -5
            else -> -10
        }
        treasuryScore += momentumScore
        if (momentumScore != 0) {
            scoreReasons.add("mom${if (momentumScore > 0) "+" else ""}$momentumScore")
        }

        val volScore = when {
            volatility in 20.0..60.0 -> 15
            volatility in 10.0..70.0 -> 10
            volatility in 5.0..80.0 -> 5
            else -> 0
        }
        treasuryScore += volScore
        if (volScore > 0) scoreReasons.add("vol+$volScore")

        val holderPenalty = when {
            topHolderPct <= 10 -> 0
            topHolderPct <= 20 -> -2
            topHolderPct <= 30 -> -5
            topHolderPct <= 40 -> -10
            else -> -20
        }
        treasuryScore += holderPenalty
        if (holderPenalty < 0) scoreReasons.add("holder$holderPenalty")

        val modeBonus = when (mode) {
            TreasuryMode.AGGRESSIVE -> 10
            TreasuryMode.HUNT -> 5
            TreasuryMode.CRUISE -> 0
            TreasuryMode.DEFENSIVE -> -5
            TreasuryMode.PAUSED -> 0
        }
        treasuryScore += modeBonus
        if (modeBonus != 0) {
            scoreReasons.add("mode${if (modeBonus > 0) "+" else ""}$modeBonus")
        }

        val treasuryConfidence = (
            (if (liquidityUsd > 10_000) 25 else if (liquidityUsd > 5_000) 15 else 5) +
                (if (buyPressurePct > 55) 25 else if (buyPressurePct > 45) 15 else 5) +
                (if (momentum > 2) 25 else if (momentum > -2) 15 else 5) +
                (if (topHolderPct < 20) 25 else if (topHolderPct < 30) 15 else 5)
            ).coerceIn(0, 100)

        val learningProgress = FluidLearningAI.getLearningProgress()

        val minTreasuryScore = FluidLearningAI.getTreasuryScoreThreshold()
        val minTreasuryConf = FluidLearningAI.getTreasuryConfidenceThreshold()
        val minLiquidity = FluidLearningAI.getTreasuryMinLiquidity()
        val maxTopHolder = FluidLearningAI.getTreasuryMaxTopHolder()
        val minBuyPressure = FluidLearningAI.getTreasuryMinBuyPressure()

        ErrorLogger.debug(
            TAG,
            "💰 TREASURY SCORE: $symbol | " +
                "score=$treasuryScore (need≥$minTreasuryScore) | " +
                "conf=$treasuryConfidence% (need≥$minTreasuryConf%) | " +
                scoreReasons.joinToString(","),
        )

        val rejectionReasons = mutableListOf<String>()

        if (treasuryScore < minTreasuryScore) {
            rejectionReasons.add("score=$treasuryScore<$minTreasuryScore")
        }
        if (treasuryConfidence < minTreasuryConf) {
            rejectionReasons.add("conf=$treasuryConfidence<$minTreasuryConf")
        }
        if (liquidityUsd < minLiquidity) {
            rejectionReasons.add("liq=$${liquidityUsd.toInt()}<$${minLiquidity.toInt()}")
        }
        if (topHolderPct > maxTopHolder) {
            rejectionReasons.add("holder=${topHolderPct.toInt()}%>${maxTopHolder.toInt()}%")
        }
        if (buyPressurePct < minBuyPressure) {
            rejectionReasons.add("buy=${buyPressurePct.toInt()}%<${minBuyPressure.toInt()}%")
        }
        if (momentum < -5) {
            rejectionReasons.add("momentum=${"%.1f".format(momentum)}<-5")
        }
        if (activePositions.containsKey(mint)) {
            rejectionReasons.add("already_in_position")
        }

        val lastExitTime = recentExits[mint] ?: 0L
        val timeSinceExit = System.currentTimeMillis() - lastExitTime
        if (lastExitTime > 0 && timeSinceExit < REENTRY_COOLDOWN_MS) {
            val remaining = (REENTRY_COOLDOWN_MS - timeSinceExit) / 1000
            rejectionReasons.add("reentry_cooldown (${remaining}s)")
        }

        if (activePositions.size >= MAX_CONCURRENT_POSITIONS) {
            rejectionReasons.add("max_positions_reached ($MAX_CONCURRENT_POSITIONS)")
        }

        if (rejectionReasons.isNotEmpty()) {
            ErrorLogger.info(TAG, "💰 TREASURY SKIP: $symbol | ${rejectionReasons.joinToString(", ")}")
            return TreasurySignal(
                shouldEnter = false,
                positionSizeSol = 0.0,
                takeProfitPct = 0.0,
                stopLossPct = 0.0,
                confidence = treasuryConfidence,
                reason = "REJECTED: ${rejectionReasons.joinToString(", ")}",
                mode = mode,
                isPaperMode = isPaperMode,
            )
        }

        val dailyPnl = dailyPnlSolBps.get() / 100.0

        var positionSol = BASE_POSITION_SOL

        if (COMPOUNDING_ENABLED) {
            val treasuryBalance = getTreasuryBalance(isPaperMode)
            if (treasuryBalance > 0) {
                val compoundingBonus = treasuryBalance * COMPOUNDING_RATIO
                val confidenceScale = treasuryConfidence / 100.0
                positionSol += compoundingBonus * confidenceScale

                ErrorLogger.debug(
                    TAG,
                    "💰 COMPOUNDING: +${compoundingBonus.fmt(3)}SOL from treasury " +
                        "(balance=${treasuryBalance.fmt(3)}SOL, conf=${treasuryConfidence}%)",
                )
            }

            if (dailyPnl > 0) {
                val dailyCompound = dailyPnl * COMPOUNDING_RATIO * (treasuryConfidence / 100.0)
                positionSol += dailyCompound
                ErrorLogger.debug(TAG, "💰 DAILY COMPOUND: +${dailyCompound.fmt(3)}SOL from today's profits")
            }
        }

        if (treasuryConfidence >= 75 && treasuryScore >= 50) {
            positionSol *= POSITION_SCALE_FACTOR
        }

        positionSol *= when (mode) {
            TreasuryMode.DEFENSIVE -> 0.4
            TreasuryMode.CRUISE -> 0.7
            TreasuryMode.AGGRESSIVE -> 1.15
            TreasuryMode.HUNT -> 1.0
            TreasuryMode.PAUSED -> 0.0
        }

        val maxWithCompounding = MAX_POSITION_SOL * (1 + COMPOUNDING_RATIO)
        positionSol = positionSol.coerceIn(MIN_POSITION_SOL, maxWithCompounding)

        val globalMultiplier = AutoCompoundEngine.getSizeMultiplier()
        if (globalMultiplier > 1.0) {
            positionSol *= globalMultiplier
            positionSol = positionSol.coerceAtMost(maxWithCompounding * 1.5)
            ErrorLogger.debug(
                TAG,
                "💰 GLOBAL COMPOUND BOOST: ${globalMultiplier.fmt(2)}x → pos=${positionSol.fmt(3)}SOL",
            )
        }

        val baseTakeProfitPct = when (mode) {
            TreasuryMode.DEFENSIVE -> TAKE_PROFIT_MIN_PCT
            TreasuryMode.CRUISE -> TAKE_PROFIT_PCT
            TreasuryMode.AGGRESSIVE -> TAKE_PROFIT_MAX_PCT
            TreasuryMode.HUNT -> TAKE_PROFIT_PCT
            TreasuryMode.PAUSED -> TAKE_PROFIT_PCT
        }
        val baseStopLossPct = kotlin.math.abs(STOP_LOSS_PCT)

        val takeProfitPct = FluidLearningAI.getFluidTakeProfit(baseTakeProfitPct)
        val stopLossPct = -FluidLearningAI.getFluidStopLoss(baseStopLossPct)

        ErrorLogger.info(
            TAG,
            "💰 TREASURY ENTRY: $symbol | " +
                "score=$treasuryScore conf=${treasuryConfidence}% | " +
                "size=${positionSol.fmt(3)} SOL (dailyPnl=${dailyPnl.fmt(2)}◎) | " +
                "TP=${takeProfitPct.fmt(1)}% SL=${stopLossPct.fmt(1)}% | mode=$mode | ${if (isPaperMode) "PAPER" else "LIVE"}",
        )

        return TreasurySignal(
            shouldEnter = true,
            positionSizeSol = positionSol,
            takeProfitPct = takeProfitPct,
            stopLossPct = stopLossPct,
            confidence = treasuryConfidence,
            reason = "TREASURY_ENTRY: score=$treasuryScore (${scoreReasons.joinToString(",")})",
            mode = mode,
            isPaperMode = isPaperMode,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    fun openPosition(
        mint: String,
        symbol: String,
        entryPrice: Double,
        positionSol: Double,
        takeProfitPct: Double,
        stopLossPct: Double,
    ) {
        val targetPrice = entryPrice * (1 + takeProfitPct / 100)
        val stopPrice = entryPrice * (1 + stopLossPct / 100)

        ErrorLogger.info(
            TAG,
            "💰 TREASURY TP CALC: $symbol | " +
                "entry=$entryPrice | tpPct=$takeProfitPct% | " +
                "targetPrice=$targetPrice | (entry × ${1 + takeProfitPct / 100} = $targetPrice)",
        )

        val position = TreasuryPosition(
            mint = mint,
            symbol = symbol,
            entryPrice = entryPrice,
            entrySol = positionSol,
            entryTime = System.currentTimeMillis(),
            targetPrice = targetPrice,
            stopPrice = stopPrice,
            highWaterMark = entryPrice,
            trailingStop = stopPrice,
            isPaper = isPaperMode,
        )

        synchronized(activePositions) {
            activePositions[mint] = position
            ErrorLogger.info(
                TAG,
                "💰 TREASURY MAP: Added $symbol | activePositions.size=${activePositions.size} | " +
                    "mints=${activePositions.keys.map { it.take(8) }}",
            )
        }

        dailyTradeCount.incrementAndGet()

        ErrorLogger.info(
            TAG,
            "💰 TREASURY OPENED: $symbol | " +
                "entry=$entryPrice | TP=$targetPrice (+$takeProfitPct%) | SL=$stopPrice ($stopLossPct%) | " +
                "size=$positionSol SOL | ${if (isPaperMode) "PAPER" else "LIVE"}",
        )
    }

    fun hasPosition(mint: String): Boolean {
        return synchronized(activePositions) { activePositions.containsKey(mint) }
    }

    fun getActivePosition(mint: String): TreasuryPosition? {
        return synchronized(activePositions) { activePositions[mint] }
    }

    fun updatePrice(mint: String, price: Double) {
        if (price > 0) {
            currentPrices[mint] = price
            lastPriceUpdate[mint] = System.currentTimeMillis()
        }
    }

    fun getTrackedPrice(mint: String): Double? {
        return currentPrices[mint]
    }

    fun clearAllPositions() {
        synchronized(activePositions) {
            val count = activePositions.size
            activePositions.clear()
            currentPrices.clear()
            lastPriceUpdate.clear()
            ErrorLogger.info(TAG, "💰 CLEARED $count Treasury positions on shutdown")
        }
    }

    fun checkAllPositionsForExit(): List<Pair<String, ExitSignal>> {
        val exits = mutableListOf<Pair<String, ExitSignal>>()

        synchronized(activePositions) {
            for ((mint, pos) in activePositions) {
                val currentPrice = currentPrices[mint] ?: continue
                val signal = checkExitInternal(pos, currentPrice)
                if (signal != ExitSignal.HOLD) {
                    exits.add(mint to signal)
                    ErrorLogger.info(
                        TAG,
                        "💰 TREASURY EXIT SIGNAL: ${pos.symbol} | $signal | " +
                            "price=$currentPrice entry=${pos.entryPrice} pnl=${((currentPrice - pos.entryPrice) / pos.entryPrice * 100).toInt()}%",
                    )
                }
            }
        }

        return exits
    }

    private fun checkExitInternal(pos: TreasuryPosition, currentPrice: Double): ExitSignal {
        val pnlPct = (currentPrice - pos.entryPrice) / pos.entryPrice * 100
        val holdMinutes = (System.currentTimeMillis() - pos.entryTime) / 60_000

        if (currentPrice > pos.highWaterMark) {
            pos.highWaterMark = currentPrice
            pos.trailingStop = currentPrice * (1 - TRAILING_STOP_PCT / 100)
        }

        // Paper/live TP floor: live exits at 2.5% (lock real profit sooner),
        // paper at 3.5% (let paper trades breathe for learning data).
        // These constants were defined but previously unused — now applied.
        val tpFloor = if (pos.isPaper) TAKE_PROFIT_PCT_PAPER else TAKE_PROFIT_PCT_LIVE
        if (pnlPct >= tpFloor) {
            val holdSeconds = (System.currentTimeMillis() - pos.entryTime) / 1000
            val modeLabel = if (pos.isPaper) "PAPER" else "LIVE"
            ErrorLogger.info(TAG, "💰 TREASURY TP HIT [$modeLabel]: ${pos.symbol} | +${pnlPct.fmt(1)}% >= $tpFloor% in ${holdSeconds}s | SELLING!")
            return ExitSignal.TAKE_PROFIT
        }

        if (pnlPct >= TAKE_PROFIT_MAX_PCT) {
            return ExitSignal.TAKE_PROFIT
        }

        if (currentPrice <= pos.stopPrice) {
            return ExitSignal.STOP_LOSS
        }

        if (pnlPct > 5.0 && currentPrice <= pos.trailingStop) {
            return ExitSignal.TRAILING_STOP
        }

        if (holdMinutes >= MAX_HOLD_MINUTES && pnlPct < 0) {
            return ExitSignal.TIME_EXIT
        }

        return ExitSignal.HOLD
    }

    fun checkExit(mint: String, currentPrice: Double): ExitSignal {
        updatePrice(mint, currentPrice)

        val pos = synchronized(activePositions) { activePositions[mint] }

        if (pos == null) {
            ErrorLogger.warn(
                TAG,
                "💰 TREASURY CHECK: Position NOT FOUND for ${mint.take(8)}... | activePositions.size=${activePositions.size}",
            )
            return ExitSignal.HOLD
        }

        val pnlPct = (currentPrice - pos.entryPrice) / pos.entryPrice * 100
        val holdMinutes = (System.currentTimeMillis() - pos.entryTime) / 60_000
        val isAboveTarget = currentPrice >= pos.targetPrice

        ErrorLogger.info(
            TAG,
            "💰 TREASURY TP CHECK: ${pos.symbol} | " +
                "price=$currentPrice | entry=${pos.entryPrice} | target=${pos.targetPrice} | " +
                "pnl=${pnlPct.fmt(1)}% | isAboveTarget=$isAboveTarget",
        )

        if (currentPrice > pos.highWaterMark) {
            pos.highWaterMark = currentPrice
            pos.trailingStop = currentPrice * (1 - TRAILING_STOP_PCT / 100)
        }

        val holdTimeMs = System.currentTimeMillis() - pos.entryTime
        val minTreasuryHoldMs = 15_000L
        val isInMinHoldPeriod = holdTimeMs < minTreasuryHoldMs

        val hardFloorStopPct = -10.0
        val catastrophicLossPct = -15.0

        if (pnlPct <= catastrophicLossPct) {
            ErrorLogger.warn(TAG, "💰🛑 TREASURY CATASTROPHIC: ${pos.symbol} | ${pnlPct.toInt()}% - EMERGENCY EXIT!")
            return ExitSignal.STOP_LOSS
        }

        if (pnlPct <= hardFloorStopPct && !isInMinHoldPeriod) {
            ErrorLogger.warn(TAG, "💰🛑 TREASURY HARD FLOOR: ${pos.symbol} | ${pnlPct.toInt()}% - EXIT!")
            return ExitSignal.STOP_LOSS
        }

        if (isInMinHoldPeriod && pnlPct < 0) {
            ErrorLogger.debug(
                TAG,
                "💰⏳ TREASURY MIN_HOLD: ${pos.symbol} | ${pnlPct.toInt()}% | " +
                    "hold=${holdTimeMs / 1000}s/${minTreasuryHoldMs / 1000}s - waiting...",
            )
            return ExitSignal.HOLD
        }

        if (isAboveTarget) {
            ErrorLogger.info(
                TAG,
                "💰 TREASURY TP HIT: ${pos.symbol} | +${pnlPct.fmt(1)}% | " +
                    "price=$currentPrice >= target=${pos.targetPrice} | SELLING & PROMOTING!",
            )
            return ExitSignal.TAKE_PROFIT
        }

        if (pnlPct >= TAKE_PROFIT_MAX_PCT) {
            ErrorLogger.info(TAG, "💰 TREASURY MAX TP: ${pos.symbol} | +${pnlPct.fmt(1)}% (hit ${TAKE_PROFIT_MAX_PCT}% cap)")
            return ExitSignal.TAKE_PROFIT
        }

        if (currentPrice <= pos.stopPrice) {
            ErrorLogger.info(
                TAG,
                "💰 TREASURY SL HIT: ${pos.symbol} | ${pnlPct.fmt(1)}% | price=$currentPrice <= stop=${pos.stopPrice}",
            )
            return ExitSignal.STOP_LOSS
        }

        if (pnlPct > 5.0 && currentPrice <= pos.trailingStop) {
            ErrorLogger.info(TAG, "💰 TREASURY TRAIL HIT: ${pos.symbol} | +${pnlPct.fmt(1)}%")
            return ExitSignal.TRAILING_STOP
        }

        if (holdMinutes >= MAX_HOLD_MINUTES && pnlPct < 0) {
            ErrorLogger.info(TAG, "💰 TREASURY TIME EXIT: ${pos.symbol} | ${pnlPct.fmt(1)}% after ${holdMinutes}min")
            return ExitSignal.TIME_EXIT
        }

        return ExitSignal.HOLD
    }

    enum class ExitSignal {
        HOLD,
        TAKE_PROFIT,
        STOP_LOSS,
        TRAILING_STOP,
        TIME_EXIT,
    }

    fun closePosition(mint: String, exitPrice: Double, exitReason: ExitSignal) {
        val pos = synchronized(activePositions) { activePositions.remove(mint) } ?: return

        try {
            com.lifecyclebot.engine.TradeAuthorizer.releasePosition(
                mint = mint,
                reason = "TREASURY_${exitReason.name}",
                book = com.lifecyclebot.engine.TradeAuthorizer.ExecutionBook.TREASURY,
            )
            ErrorLogger.debug(TAG, "💰🔓 TREASURY LOCK RELEASED: ${pos.symbol} | reason=$exitReason")
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "💰⚠️ Failed to release Treasury lock for ${pos.symbol}: ${e.message}")
        }

        val pnlPct = (exitPrice - pos.entryPrice) / pos.entryPrice * 100
        val pnlSol = pos.entrySol * pnlPct / 100

        recentExits[mint] = System.currentTimeMillis()
        val oneMinuteAgo = System.currentTimeMillis() - 60_000
        recentExits.entries.removeIf { it.value < oneMinuteAgo }

        val pnlBps = (pnlSol * 100).toLong()
        dailyPnlSolBps.addAndGet(pnlBps)

        if (pnlSol > 0) {
            dailyWins.incrementAndGet()
            addToTreasury(pnlSol, pos.isPaper)
        } else {
            dailyLosses.incrementAndGet()
        }

        try {
            val isWin = pnlPct > 0
            if (pos.isPaper) {
                FluidLearningAI.recordPaperTrade(isWin)
            } else {
                FluidLearningAI.recordLiveTrade(isWin)
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "FluidLearning update failed: ${e.message}")
        }

        val dailyPnl = dailyPnlSolBps.get() / 100.0
        val winRate = if (dailyTradeCount.get() > 0) {
            dailyWins.get().toDouble() / dailyTradeCount.get() * 100
        } else {
            0.0
        }

        val cycleLabel = if (pnlPct > 0) " [CYCLE OK in ${REENTRY_COOLDOWN_MS / 1000}s]" else ""
        val modeLabel = if (pos.isPaper) "PAPER" else "LIVE"

        ErrorLogger.info(
            TAG,
            "💰 TREASURY CLOSED [$modeLabel]: ${pos.symbol} | " +
                "P&L: ${if (pnlSol >= 0) "+" else ""}${pnlSol.fmt(4)} SOL (${pnlPct.fmt(1)}%) | " +
                "reason=$exitReason$cycleLabel | " +
                "Daily: ${dailyPnl.fmt(4)} SOL | " +
                "Win rate: ${winRate.fmt(0)}% | " +
                "Treasury: ${getCurrentTreasuryBalance().fmt(4)} SOL",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MODE DETERMINATION
    // ═══════════════════════════════════════════════════════════════════════════

    fun getCurrentMode(): TreasuryMode {
        val dailyPnl = dailyPnlSolBps.get() / 100.0

        return when {
            dailyPnl <= -DAILY_MAX_LOSS_SOL -> TreasuryMode.PAUSED
            dailyPnl < 0 -> TreasuryMode.AGGRESSIVE
            dailyPnl > 0.5 -> TreasuryMode.CRUISE
            else -> TreasuryMode.HUNT
        }
    }

    fun getStats(): TreasuryStats {
        val dailyPnl = dailyPnlSolBps.get() / 100.0
        val learningProgress = FluidLearningAI.getLearningProgress()

        return TreasuryStats(
            dailyPnlSol = dailyPnl,
            dailyMaxLossSol = DAILY_MAX_LOSS_SOL,
            dailyWins = dailyWins.get(),
            dailyLosses = dailyLosses.get(),
            dailyTradeCount = dailyTradeCount.get(),
            winRate = if (dailyTradeCount.get() > 0) {
                dailyWins.get().toDouble() / dailyTradeCount.get() * 100
            } else {
                0.0
            },
            activePositions = activePositions.size,
            mode = getCurrentMode(),
            treasuryBalanceSol = getCurrentTreasuryBalance(),
            isPaperMode = isPaperMode,
            learningProgressPct = (learningProgress * 100).toInt(),
            currentConfThreshold = getCurrentConfidenceThreshold(),
            currentScoreThreshold = getCurrentScoreThreshold(),
        )
    }

    data class TreasuryStats(
        val dailyPnlSol: Double,
        val dailyMaxLossSol: Double,
        val dailyWins: Int,
        val dailyLosses: Int,
        val dailyTradeCount: Int,
        val winRate: Double,
        val activePositions: Int,
        val mode: TreasuryMode,
        val treasuryBalanceSol: Double,
        val isPaperMode: Boolean,
        val learningProgressPct: Int = 0,
        val currentConfThreshold: Int = 30,
        val currentScoreThreshold: Int = 15,
    ) {
        fun summary(): String = buildString {
            val modeEmoji = when (mode) {
                TreasuryMode.HUNT -> "🎯"
                TreasuryMode.CRUISE -> "🚢"
                TreasuryMode.DEFENSIVE -> "🛡️"
                TreasuryMode.PAUSED -> "⏸️"
                TreasuryMode.AGGRESSIVE -> "⚡"
            }
            append("$modeEmoji ${mode.name} | ")
            append("${if (dailyPnlSol >= 0) "+" else ""}${dailyPnlSol.fmt(3)}◎ ")
            append("(UNLIMITED) | ")
            append("$dailyWins W / $dailyLosses L | ")
            append("Treasury: ${treasuryBalanceSol.fmt(3)}◎ ")
            append("[${if (isPaperMode) "PAPER" else "LIVE"}] | ")
            append("AI: ${learningProgressPct}% → conf≥$currentConfThreshold")
        }
    }

    fun resetDaily() {
        dailyPnlSolBps.set(0)
        dailyWins.set(0)
        dailyLosses.set(0)
        dailyTradeCount.set(0)
        ErrorLogger.info(TAG, "💰 TREASURY: Daily stats reset for ${if (isPaperMode) "PAPER" else "LIVE"} mode")
    }

    fun resetAll() {
        paperDailyPnlSolBps.set(0)
        paperDailyWins.set(0)
        paperDailyLosses.set(0)
        paperDailyTradeCount.set(0)

        liveDailyPnlSolBps.set(0)
        liveDailyWins.set(0)
        liveDailyLosses.set(0)
        liveDailyTradeCount.set(0)

        ErrorLogger.info(TAG, "💰 TREASURY: All daily stats reset (treasury balances preserved)")
    }

    fun isEnabled(): Boolean {
        return true
    }

    fun getActivePositions(): List<TreasuryPosition> {
        return synchronized(activePositions) {
            activePositions.values.toList()
        }
    }

    fun getPositionsForMode(isPaper: Boolean): List<TreasuryPosition> {
        val positions = if (isPaper) paperPositions else livePositions
        return synchronized(positions) {
            positions.values.toList()
        }
    }

    fun getDailyPnlSol(): Double {
        return dailyPnlSolBps.get() / 100.0
    }

    private fun Double.fmt(decimals: Int): String = String.format("%.${decimals}f", this)
}

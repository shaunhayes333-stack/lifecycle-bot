package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * ☠️ MANIPULATED TRADER AI - "RIDE THE PUMP" v1.0
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Buys tokens showing CLEAR manipulation signals — bundle concentration,
 * wash trading, whale pump patterns — and rides the INITIAL pump (first 3-5
 * minutes) with tight risk control BEFORE the manipulators dump.
 *
 * WHERE OTHER LAYERS BLOCK — THIS LAYER ENTERS.
 *
 * PHILOSOPHY:
 * - Manipulation is detectable. Bundled buy pressure and extreme one-sided
 *   volume indicate coordinated pumping.
 * - Enter early (< 8 min token age), exit fast (≤ 4 min hold).
 * - TP 25%, SL -5%. If neither hits, FORCE EXIT at 4 minutes — no exceptions.
 * - These manipulators DO NOT WAIT for retail to catch up.
 *
 * MANIPULATION SCORE (0-100):
 * ─────────────────────────────────────────────────────────────────────────────
 * +30  bundlePct >= 60%  (mass bundled buy = coordinated pump)
 * +20  bundlePct >= 40%
 * +20  buyPressurePct >= 80%  (extreme one-sided buying)
 * +15  buyPressurePct >= 70%
 * +20  momentum >= 15  (token already ripping)
 * +15  momentum >= 10
 * +10  source is PUMP_FUN or PUMP_FUN_GRADUATE
 * +10  ageMinutes < 3  (very fresh = still in pump phase)
 * -20  rugcheckScore < 1  (known rug — skip even here)
 *
 * Entry trigger: ManipulationScore >= 60
 * Position sizing: lerp(0.015, 0.05) SOL via FluidLearningAI
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object ManipulatedTraderAI {

    private const val TAG = "☠️ManipAI"

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION - V5.6.8: Added fluid thresholds for learning progression
    // ═══════════════════════════════════════════════════════════════════════════

    // Entry score thresholds (fluid - VERY permissive for manipulation plays)
    // Realistic scores: bundle 40%=20 + bp 70%=15 + pump.fun=10 = 45 max typical
    // Many tokens will score 20-35, so we need LOW thresholds
    private const val MIN_MANIP_SCORE_BOOTSTRAP = 15        // V5.6.8: VERY low - catch all manipulation signals
    private const val MIN_MANIP_SCORE_MATURE = 28           // V5.6.8: Still very permissive when experienced
    
    // Market cap range
    private const val MIN_MARKET_CAP_USD = 5_000.0          // $5K minimum
    private const val MAX_MARKET_CAP_USD = 300_000.0        // $300K maximum
    
    // Liquidity thresholds (fluid) - LOW for manipulation plays
    private const val MIN_LIQUIDITY_BOOTSTRAP = 1_500.0     // V5.6.8: $1.5K during bootstrap
    private const val MIN_LIQUIDITY_MATURE = 3_000.0        // V5.6.8: $3K when mature
    
    // Token age (fluid - tighter when experienced)
    private const val MAX_AGE_MINUTES_BOOTSTRAP = 12.0      // V5.6.8: 12 min during bootstrap
    private const val MAX_AGE_MINUTES_MATURE = 8.0          // V5.6.8: 8 min when mature

    // Position sizing — bootstrap→mature via FluidLearningAI
    private const val POSITION_SOL_BOOTSTRAP = 0.015
    private const val POSITION_SOL_MATURE = 0.05

    // Take profit / Stop loss (fixed — no fluid adaption on risk for this layer)
    private const val TAKE_PROFIT_PCT = 25.0                   // Fast pump target
    private const val STOP_LOSS_PCT = -5.0                     // Tight — dumps are fast
    private const val FORCE_EXIT_MINUTES = 4.0                 // Hard 4-minute time exit
    private const val TRAILING_STOP_ACTIVATION_PCT = 10.0      // Activate trailing at +10%
    private const val TRAILING_STOP_FROM_HWM_PCT = 15.0        // 15% trail from high-water mark

    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════

    data class ManipulatedPosition(
        val mint: String,
        val symbol: String,
        val entryPrice: Double,
        val entrySol: Double,
        val entryTime: Long,
        val takeProfitPct: Double,
        val stopLossPct: Double,
        val manipScore: Int,
        val bundlePct: Double,
        val buyPressure: Double,
        val isPaper: Boolean,
        var highWaterMark: Double = entryPrice,
        // V5.9.168 — shared laddered profit-lock
        var peakPnlPct: Double = 0.0,
        var partialRungsTaken: Int = 0,
    )

    data class ManipSignal(
        val shouldEnter: Boolean,
        val positionSizeSol: Double,
        val manipScore: Int,
        val reason: String,
    )

    data class ManipStats(
        val dailyWins: Int,
        val dailyLosses: Int,
        val dailyPnlSol: Double,
        val totalManipCaught: Int,
        val activeCount: Int,
    )

    enum class ManipExitSignal {
        HOLD,
        TAKE_PROFIT,
        STOP_LOSS,
        TIME_EXIT,
        RUG_EXIT,
        TRAILING_STOP,
        PARTIAL_TAKE,  // V5.9.168: laddered partial-sell signal
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════

    @Volatile var isPaperMode: Boolean = true
    @Volatile private var initialized = false

    val activePositions = ConcurrentHashMap<String, ManipulatedPosition>()

    private val _dailyWins = AtomicInteger(0)
    private val _dailyLosses = AtomicInteger(0)
    private val _dailyPnlSolBps = AtomicLong(0)    // stored as (sol * 10000) for precision
    private val _totalManipCaught = AtomicInteger(0)

    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════

    fun init(isPaper: Boolean) {
        if (initialized) {
            ErrorLogger.warn(TAG, "☠️ init() called again — BLOCKED (already initialized)")
            return
        }
        isPaperMode = isPaper
        initialized = true
        ErrorLogger.info(TAG, "☠️ ManipulatedTraderAI initialized | " +
            "mode=${if (isPaper) "PAPER" else "LIVE"} | " +
            "TP=${TAKE_PROFIT_PCT}% SL=${STOP_LOSS_PCT}% | " +
            "forceExit=${FORCE_EXIT_MINUTES}min | " +
            "size=${POSITION_SOL_BOOTSTRAP}->${POSITION_SOL_MATURE} SOL")
    }

    fun isEnabled(): Boolean = true

    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION SIZING — fluid scaling via FluidLearningAI
    // ═══════════════════════════════════════════════════════════════════════════

    private fun lerp(bootstrap: Double, mature: Double): Double {
        val progress = FluidLearningAI.getLearningProgress().coerceIn(0.0, 1.0)
        return bootstrap + (mature - bootstrap) * progress
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MANIPULATION SCORE
    // ═══════════════════════════════════════════════════════════════════════════

    fun calcManipScore(
        bundlePct: Double,
        buyPressurePct: Double,
        momentum: Double,
        source: String,
        ageMinutes: Double,
        rugcheckScore: Int,
    ): Int {
        var score = 0

        // Bundle concentration — coordinated pump signal
        when {
            bundlePct >= 60.0 -> score += 30
            bundlePct >= 40.0 -> score += 20
        }

        // Extreme one-sided buying
        when {
            buyPressurePct >= 80.0 -> score += 20
            buyPressurePct >= 70.0 -> score += 15
        }

        // Momentum — token already ripping
        when {
            momentum >= 15.0 -> score += 20
            momentum >= 10.0 -> score += 15
        }

        // Common manipulation venue
        val srcUpper = source.uppercase()
        if (srcUpper.contains("PUMP_FUN") || srcUpper.contains("PUMP.FUN") ||
            srcUpper.contains("PUMPFUN") || srcUpper.contains("PUMP_FUN_GRADUATE")) {
            score += 10
        }

        // Very fresh token — still in pump phase
        if (ageMinutes < 3.0) score += 10

        // Known rug penalty
        if (rugcheckScore < 1) score -= 20

        return score.coerceIn(0, 100)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EVALUATE — decide whether to enter a position
    // ═══════════════════════════════════════════════════════════════════════════

    fun evaluate(
        mint: String,
        symbol: String,
        currentPrice: Double,
        marketCapUsd: Double,
        liquidityUsd: Double,
        momentum: Double,
        buyPressurePct: Double,
        bundlePct: Double,
        source: String,
        ageMinutes: Double,
        rugcheckScore: Int,
        isPaper: Boolean,
    ): ManipSignal {
        fun noEnter(reason: String) = ManipSignal(
            shouldEnter = false, positionSizeSol = 0.0, manipScore = 0, reason = reason
        )

        // Already in a position on this token
        if (activePositions.containsKey(mint)) return noEnter("ALREADY_POSITIONED")

        // Market cap filter
        if (marketCapUsd < MIN_MARKET_CAP_USD) return noEnter("MCAP_TOO_LOW")
        if (marketCapUsd > MAX_MARKET_CAP_USD) return noEnter("MCAP_TOO_HIGH")

        // V5.6.8: Fluid liquidity filter
        val minLiq = getFluidMinLiquidity()
        if (liquidityUsd < minLiq) return noEnter("LOW_LIQUIDITY_${liquidityUsd.toInt()}<${minLiq.toInt()}")

        // V5.6.8: Fluid age filter — entry is everything on manipulation plays
        val maxAge = getFluidMaxAge()
        if (ageMinutes > maxAge) return noEnter("TOKEN_TOO_OLD_${ageMinutes.toInt()}m>${maxAge.toInt()}m")

        // V5.6.8: REMOVED rugcheck block — Manipulated layer INTENTIONALLY trades risky tokens
        // The whole point is to ride manipulator pumps that other layers reject
        // TradeAuthorizer.ExecutionBook.MANIPULATED bypasses rugcheck checks

        // Calculate manipulation score
        val score = calcManipScore(bundlePct, buyPressurePct, momentum, source, ageMinutes, rugcheckScore)

        // V5.6.8: Fluid score gate
        val minScore = getFluidScoreThreshold()
        if (score < minScore) return noEnter("SCORE_TOO_LOW_${score}<${minScore}")

        val positionSizeSol = lerp(POSITION_SOL_BOOTSTRAP, POSITION_SOL_MATURE)
        val learningPct = (FluidLearningAI.getLearningProgress() * 100).toInt()

        ErrorLogger.info(TAG, "☠️ MANIP SIGNAL: $symbol | score=$score (min=$minScore) | " +
            "bundle=${bundlePct.toInt()}% | bp=${buyPressurePct.toInt()}% | " +
            "mom=${momentum.toInt()}% | age=${ageMinutes.toInt()}min | " +
            "size=${String.format("%.4f", positionSizeSol)} SOL | learning=$learningPct%")

        return ManipSignal(
            shouldEnter = true,
            positionSizeSol = positionSizeSol,
            manipScore = score,
            reason = "MANIP_$score",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    fun addPosition(pos: ManipulatedPosition) {
        activePositions[pos.mint] = pos
        _totalManipCaught.incrementAndGet()
        ErrorLogger.info(TAG, "☠️ POSITION ADDED: ${pos.symbol} | " +
            "score=${pos.manipScore} | bundle=${pos.bundlePct.toInt()}% | " +
            "bp=${pos.buyPressure.toInt()}% | " +
            "entry=${String.format("%.8f", pos.entryPrice)} | " +
            "size=${String.format("%.4f", pos.entrySol)} SOL | " +
            "${if (pos.isPaper) "PAPER" else "LIVE"}")
    }

    fun hasPosition(mint: String): Boolean = activePositions.containsKey(mint)

    fun getActivePositions(): List<ManipulatedPosition> {
        return synchronized(activePositions) {
            activePositions.values.toList()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EXIT CHECK — called every tick for open positions
    // ═══════════════════════════════════════════════════════════════════════════

    fun checkExit(mint: String, currentPrice: Double): ManipExitSignal {
        val pos = activePositions[mint] ?: return ManipExitSignal.HOLD

        val pnlPct = if (pos.entryPrice > 0) {
            (currentPrice - pos.entryPrice) / pos.entryPrice * 100.0
        } else 0.0

        val holdMinutes = (System.currentTimeMillis() - pos.entryTime) / 60_000.0

        // Update peak + HWM
        if (pnlPct > pos.peakPnlPct) pos.peakPnlPct = pnlPct
        if (currentPrice > pos.highWaterMark) pos.highWaterMark = currentPrice

        // V5.9.168 — SHARED LADDERED PROFIT-LOCK
        val rungs = doubleArrayOf(20.0, 50.0, 100.0, 300.0, 1000.0, 3000.0, 10000.0)
        if (pos.partialRungsTaken < rungs.size && pnlPct >= rungs[pos.partialRungsTaken]) {
            pos.partialRungsTaken += 1
            return ManipExitSignal.PARTIAL_TAKE
        }
        // V5.9.169 — continuous fluid profit floor (shared engine).
        val profitFloor = com.lifecyclebot.v3.scoring.FluidLearningAI.fluidProfitFloor(pos.peakPnlPct)
        if (pnlPct < profitFloor) return ManipExitSignal.TRAILING_STOP

        // 1. Take profit
        if (pnlPct >= pos.takeProfitPct) return ManipExitSignal.TAKE_PROFIT

        // 2. Stop loss
        if (pnlPct <= pos.stopLossPct) return ManipExitSignal.STOP_LOSS

        // 3. Hard time exit — manipulators have already left
        if (holdMinutes >= FORCE_EXIT_MINUTES) return ManipExitSignal.TIME_EXIT

        // 4. Trailing stop — only activates once we've hit +10% pnl
        if (pnlPct >= TRAILING_STOP_ACTIVATION_PCT) {
            val trailLevel = pos.highWaterMark * (1.0 - TRAILING_STOP_FROM_HWM_PCT / 100.0)
            if (currentPrice <= trailLevel) return ManipExitSignal.TRAILING_STOP
        }

        return ManipExitSignal.HOLD
    }

    fun closePosition(mint: String, exitPrice: Double, reason: ManipExitSignal) {
        val pos = activePositions.remove(mint) ?: return

        val pnlPct = if (pos.entryPrice > 0) {
            (exitPrice - pos.entryPrice) / pos.entryPrice * 100.0
        } else 0.0

        val pnlSol = pos.entrySol * (pnlPct / 100.0)
        val pnlBps = (pnlSol * 10_000).toLong()
        _dailyPnlSolBps.addAndGet(pnlBps)
        val _isWin = pnlPct >= 0
        try { com.lifecyclebot.engine.SmartSizer.recordTrade(_isWin, isPaperMode = pos.isPaper) } catch (_: Exception) {}
        if (pos.isPaper) try { com.lifecyclebot.engine.FluidLearning.recordPaperSell(pos.symbol, pos.entrySol, pnlSol, reason.name, "MANIP") } catch (_: Exception) {}
        // V5.9.8: Sync paper P&L to shared wallet
        if (pos.isPaper) {
            com.lifecyclebot.engine.BotService.status.paperWalletSol =
                (com.lifecyclebot.engine.BotService.status.paperWalletSol + pnlSol).coerceAtLeast(0.0)
        }

        if (pnlPct >= 0) _dailyWins.incrementAndGet() else _dailyLosses.incrementAndGet()

        ErrorLogger.info(TAG, "☠️ POSITION CLOSED: ${pos.symbol} | " +
            "reason=${reason.name} | " +
            "pnl=${String.format("%+.2f", pnlPct)}% | " +
            "pnlSol=${String.format("%+.4f", pnlSol)} | " +
            "${if (pos.isPaper) "PAPER" else "LIVE"}")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATS
    // ═══════════════════════════════════════════════════════════════════════════

    val dailyWins: Int get() = _dailyWins.get()
    val dailyLosses: Int get() = _dailyLosses.get()
    val dailyPnlSolBps: Long get() = _dailyPnlSolBps.get()
    val totalManipCaught: Int get() = _totalManipCaught.get()

    fun getDailyPnlSol(): Double = _dailyPnlSolBps.get() / 10_000.0

    fun getStats(): ManipStats = ManipStats(
        dailyWins = _dailyWins.get(),
        dailyLosses = _dailyLosses.get(),
        dailyPnlSol = getDailyPnlSol(),
        totalManipCaught = _totalManipCaught.get(),
        activeCount = activePositions.size,
    )

    fun resetDaily() {
        _dailyWins.set(0)
        _dailyLosses.set(0)
        _dailyPnlSolBps.set(0)
        ErrorLogger.info(TAG, "☠️ ManipulatedTraderAI daily stats reset")
    }

    fun clearAll() {
        activePositions.clear()
        ErrorLogger.info(TAG, "☠️ ManipulatedTraderAI — all positions cleared")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.6.8: FLUID THRESHOLD FUNCTIONS
    // Thresholds relax during bootstrap (learning), tighten when mature (experienced)
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getFluidScoreThreshold(): Int {
        val progress = FluidLearningAI.getLearningProgress().coerceIn(0.0, 1.0)
        return (MIN_MANIP_SCORE_BOOTSTRAP + (MIN_MANIP_SCORE_MATURE - MIN_MANIP_SCORE_BOOTSTRAP) * progress).toInt()
    }
    
    fun getFluidMinLiquidity(): Double {
        val progress = FluidLearningAI.getLearningProgress().coerceIn(0.0, 1.0)
        return MIN_LIQUIDITY_BOOTSTRAP + (MIN_LIQUIDITY_MATURE - MIN_LIQUIDITY_BOOTSTRAP) * progress
    }
    
    fun getFluidMaxAge(): Double {
        val progress = FluidLearningAI.getLearningProgress().coerceIn(0.0, 1.0)
        // Age gets TIGHTER as we mature (faster entries required)
        return MAX_AGE_MINUTES_BOOTSTRAP + (MAX_AGE_MINUTES_MATURE - MAX_AGE_MINUTES_BOOTSTRAP) * progress
    }
    
    fun getFluidPositionSize(): Double = lerp(POSITION_SOL_BOOTSTRAP, POSITION_SOL_MATURE)
}

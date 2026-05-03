package com.lifecyclebot.v3.scoring

import android.content.Context
import com.lifecyclebot.engine.AutoCompoundEngine
import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

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

    // Position sizing - V5.6.6: AGGRESSIVE DYNAMIC scaling
    // Treasury is the money printer - it should scale with success!
    private const val BASE_POSITION_SOL = 0.15        // V5.6.6: Raised from 0.10 - minimum viable scalp
    private const val MAX_POSITION_SOL = 2.0          // V5.6.6: Raised from 0.50 - allow real size with big wallet
    private const val MIN_POSITION_SOL = 0.05         // V5.6.6: Raised from 0.03 - below this isn't worth fees
    private const val POSITION_SCALE_FACTOR = 1.25    // V5.6.6: Raised from 1.15 - confidence bonus
    private const val WALLET_SCALE_FACTOR = 0.08      // V5.6.6: Raised from 0.03 - 8% of balance per trade
    private const val WIN_STREAK_SCALE = 0.15         // V5.6.6: NEW - 15% bonus per consecutive win (up to 5)

    // Exit strategy - V5.6.6: Dynamic TP/SL that Treasury controls
    private const val TAKE_PROFIT_PCT_PAPER = 4.0     // V5.6.6: Raised from 3.5 - bigger targets in paper
    private const val TAKE_PROFIT_PCT_LIVE = 3.0      // V5.6.6: Raised from 2.5
    private const val TAKE_PROFIT_MIN_PCT = 2.5       // V5.6.6: Floor for defensive mode
    private const val TAKE_PROFIT_PCT = 4.0           // V5.6.6: Raised from 3.5 - default target
    private const val TAKE_PROFIT_MAX_PCT = 20.0      // V5.9.200: 8→20 — don't cap genuine runners at 8%
    private const val STOP_LOSS_PCT = -5.0            // V5.6.6: Slightly wider from -4.0
    private const val TRAILING_STOP_PCT = 2.0         // V5.6.6: Wider trailing from 1.5
    private const val MAX_HOLD_MINUTES = 45           // V5.6.6: Extended from 30 - let winners run
    private const val REENTRY_COOLDOWN_MS = 5_000L

    private const val MIN_PROFIT_FOR_LIVE = 2.5

    // Trade frequency - V5.2.12: Raised max positions for more learning volume
    private const val MIN_TRADES_PER_DAY = 100
    private const val MAX_CONCURRENT_POSITIONS = 6   // V5.9.218: 15→6 — disciplined sizing

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
    private val paperTreasuryBalanceBps = AtomicLong(588)  // ~$500 USD starting treasury (at ~$85 SOL)

    private val liveDailyPnlSolBps = AtomicLong(0)
    private val liveDailyWins = AtomicInteger(0)
    private val liveDailyLosses = AtomicInteger(0)
    private val liveDailyTradeCount = AtomicInteger(0)
    private val liveTreasuryBalanceBps = AtomicLong(0)
    
    // V5.6.6: Win streak tracking for position scaling
    private val paperWinStreak = AtomicInteger(0)
    private val liveWinStreak = AtomicInteger(0)
    private val winStreak: AtomicInteger
        get() = if (isPaperMode) paperWinStreak else liveWinStreak
    
    // V5.6.6: Track actual wallet balance for proper scaling
    @Volatile
    private var lastKnownWalletBalance: Double = 1.0  // SOL

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
        // V5.9.270: expose current price for LLM context PnL computation
        var currentPrice: Double = 0.0,
        // V5.9.436 — entry treasury score for outcome attribution.
        val entryScore: Int = 0,
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
        // V5.9.436 — entry treasury score so caller can stash on Position.
        val entryScore: Int = 0,
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
            // V5.6.11: When switching from PAPER to LIVE, transfer learned stats
            if (!isPaper && isPaperMode) {
                transferPaperLearningToLive()
            }
            ErrorLogger.info(
                TAG,
                "💰 TREASURY MODE SWITCH: ${if (isPaper) "PAPER" else "LIVE"} | " +
                    "Balance: ${getTreasuryBalance(isPaper).fmt(4)} SOL",
            )
        }
        isPaperMode = isPaper
    }
    
    /**
     * V5.6.11: Transfer paper learning stats to live mode
     * This ensures LIVE mode starts with all the patterns learned in PAPER mode
     */
    private fun transferPaperLearningToLive() {
        try {
            // Transfer win streak if paper had a positive streak
            val paperStreak = paperWinStreak.get()
            if (paperStreak > liveWinStreak.get()) {
                liveWinStreak.set(paperStreak)
                ErrorLogger.info(TAG, "💰 TRANSFER: Win streak $paperStreak from PAPER to LIVE")
            }
            
            // Transfer daily PnL if paper was profitable today
            val paperPnl = paperDailyPnlSolBps.get()
            if (paperPnl > 0 && liveDailyPnlSolBps.get() == 0L) {
                // Only transfer if live hasn't started trading yet today
                liveDailyPnlSolBps.set(paperPnl)
                ErrorLogger.info(TAG, "💰 TRANSFER: Daily PnL ${paperPnl/100.0} SOL from PAPER to LIVE")
            }
            
            // Transfer daily wins/losses counts
            if (liveDailyWins.get() == 0 && liveDailyLosses.get() == 0) {
                liveDailyWins.set(paperDailyWins.get())
                liveDailyLosses.set(paperDailyLosses.get())
                liveDailyTradeCount.set(paperDailyTradeCount.get())
                ErrorLogger.info(TAG, "💰 TRANSFER: Daily stats W=${paperDailyWins.get()} L=${paperDailyLosses.get()} from PAPER to LIVE")
            }
            
            // V5.9.306: BUG FIX — DO NOT TRANSFER TREASURY BALANCE FROM PAPER → LIVE.
            // Treasury balance represents REAL locked SOL; copying paper balance into
            // live made the UI show 14,107 SOL (~$1.2M) phantom balance the moment a
            // user switched modes. Live treasury must ONLY grow from real on-chain
            // trade profits via addToTreasury(isPaper=false).
            //
            // We DO transfer the LEARNED stats above (daily wins/losses/trade counts),
            // because those are knowledge metrics, not real money. The compounded
            // balance is real money and stays separate.
            val paperBalanceForLog = paperTreasuryBalanceBps.get() / 100.0
            ErrorLogger.info(TAG, "💰 TRANSFER: paper treasury ${paperBalanceForLog.fmt(4)} SOL retained in PAPER only (NOT copied to LIVE)")
            
            ErrorLogger.info(TAG, "💰 PAPER→LIVE TRANSFER COMPLETE: stats transferred, live balance untouched")
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "💰 TRANSFER ERROR: ${e.message}")
        }
    }
    
    // V5.6.6: Update wallet balance for proper position scaling
    fun updateWalletBalance(balanceSol: Double) {
        // Do NOT clamp to 0.1 — masking a tiny wallet allows sizing to exceed real capacity.
        // The SmartSizer dust floor (0.005 SOL) handles the "too small to trade" case downstream.
        lastKnownWalletBalance = balanceSol.coerceAtLeast(0.0)
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
        // V5.9.284: Per-call sanity guard — single trade profit cannot exceed 50 SOL for live.
        // Prevents oracle spikes or fee miscalculations from pumping the live treasury counter.
        val sanitizedProfit = if (!isPaper) profitSol.coerceAtMost(50.0) else profitSol
        if (sanitizedProfit != profitSol) {
            ErrorLogger.warn(TAG, "💰 TREASURY addToTreasury CLAMPED: ${profitSol.fmt(4)} → ${sanitizedProfit.fmt(4)} SOL (live 50 SOL/call cap)")
        }
        val bps = (sanitizedProfit * 100).toLong()

        // Treasury total cap: live=1000 SOL max, paper=100k SOL max
        val MAX_TREASURY_BPS = if (!isPaper) 100_000L else 10_000_000_00L
        
        if (isPaper) {
            val newBalance = paperTreasuryBalanceBps.addAndGet(bps)
            if (newBalance > MAX_TREASURY_BPS) {
                paperTreasuryBalanceBps.set(MAX_TREASURY_BPS)
                ErrorLogger.warn(TAG, "💰 PAPER TREASURY CAPPED at ${MAX_TREASURY_BPS/100.0} SOL (was ${newBalance/100.0})")
            }
        } else {
            val newBalance = liveTreasuryBalanceBps.addAndGet(bps)
            if (newBalance > MAX_TREASURY_BPS) {
                liveTreasuryBalanceBps.set(MAX_TREASURY_BPS)
                // V5.9.284: Hard cap at 1000 SOL for live treasury to prevent phantom inflation
                ErrorLogger.warn(TAG, "💰 LIVE TREASURY CAPPED at ${MAX_TREASURY_BPS/100.0} SOL (was ${newBalance/100.0}) — possible accumulation artifact")
            }
        }
        ErrorLogger.info(
            TAG,
            "💰 TREASURY +${profitSol.fmt(4)} SOL | " +
                "Total ${if (isPaper) "PAPER" else "LIVE"}: ${getTreasuryBalance(isPaper).fmt(4)} SOL",
        )
        // V5.6.28: Auto-save after treasury balance change
        save()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.6.28: PERSISTENCE - Save/Restore treasury state across app restarts
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val PREFS_NAME = "cash_generation_ai_state"
    @Volatile private var ctx: Context? = null
    @Volatile private var lastSaveTime: Long = 0L
    private const val SAVE_THROTTLE_MS = 10_000L  // Only save every 10 seconds max
    
    /**
     * Initialize CashGenerationAI with context and restore persisted state.
     * Call this from BotService.onCreate() BEFORE any trading starts.
     */
    fun init(context: Context) {
        ctx = context.applicationContext
        restore()
        ErrorLogger.info(TAG, "💰 CashGenerationAI initialized | Paper: ${getTreasuryBalance(true).fmt(4)} SOL | Live: ${getTreasuryBalance(false).fmt(4)} SOL")
    }
    
    /**
     * Save treasury state to SharedPreferences.
     * Throttled to max once per 10 seconds to prevent excessive I/O.
     */
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
                put("paper_treasury_bps", paperTreasuryBalanceBps.get())
                put("live_treasury_bps", liveTreasuryBalanceBps.get())
                put("paper_win_streak", paperWinStreak.get())
                put("live_win_streak", liveWinStreak.get())
                put("paper_daily_pnl_bps", paperDailyPnlSolBps.get())
                put("paper_daily_wins", paperDailyWins.get())
                put("paper_daily_losses", paperDailyLosses.get())
                put("paper_daily_trades", paperDailyTradeCount.get())
                put("live_daily_pnl_bps", liveDailyPnlSolBps.get())
                put("live_daily_wins", liveDailyWins.get())
                put("live_daily_losses", liveDailyLosses.get())
                put("live_daily_trades", liveDailyTradeCount.get())
                put("last_reset_day", lastResetDay)
                put("saved_at", System.currentTimeMillis())
            }
            prefs.edit().putString("state", obj.toString()).apply()
            ErrorLogger.debug(TAG, "💾 Saved CashGenerationAI state: Paper=${paperTreasuryBalanceBps.get()/100.0} SOL")
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "💾 Save failed: ${e.message}")
        }
    }
    
    /**
     * Restore treasury state from SharedPreferences.
     */
    private fun restore() {
        val c = ctx ?: return
        try {
            val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString("state", null) ?: return
            
            val obj = JSONObject(json)
            
            // Restore paper treasury balance
            // Cap at 100,000 SOL (10,000,000 bps) — rejects corrupted values from
            // price-scale anomalies while preserving any legitimate accumulated balance.
            val savedPaperBps = obj.optLong("paper_treasury_bps", 600L)
            if (savedPaperBps > 0) {
                val cappedPaperBps = savedPaperBps.coerceAtMost(10_000_000L)
                if (savedPaperBps != cappedPaperBps) {
                    ErrorLogger.warn(TAG, "💾 TREASURY CORRUPTED: paper=${savedPaperBps/100.0} SOL — clamped to ${cappedPaperBps/100.0} SOL")
                }
                paperTreasuryBalanceBps.set(cappedPaperBps)
            }

            // Restore live treasury balance
            // V5.9.284: Hard sanity cap at 1000 SOL on restore — values above this are
            // accumulation artifacts (auto-compound routing errors, fee tracking overflow,
            // etc). A real live session starting from <1 SOL wallet cannot legitimately
            // accumulate >1000 SOL in treasury. This prevents the 14107 SOL phantom balance
            // from inflating position sizing via SIZE CALC.
            val LIVE_TREASURY_SANITY_CAP_BPS = 100_000L  // 1000 SOL
            val savedLiveBps = obj.optLong("live_treasury_bps", 0L)
            if (savedLiveBps > 0) {
                val cappedLiveBps = savedLiveBps.coerceAtMost(LIVE_TREASURY_SANITY_CAP_BPS)
                if (savedLiveBps != cappedLiveBps) {
                    ErrorLogger.warn(TAG, "💾 TREASURY CORRUPTED: live=${savedLiveBps/100.0} SOL > 1000 SOL sanity cap — reset to ${cappedLiveBps/100.0} SOL")
                }
                liveTreasuryBalanceBps.set(cappedLiveBps)
            }
            
            // Restore win streaks
            paperWinStreak.set(obj.optInt("paper_win_streak", 0))
            liveWinStreak.set(obj.optInt("live_win_streak", 0))
            
            // Restore daily stats (only if same day)
            val savedDay = obj.optInt("last_reset_day", 0)
            val currentDay = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
            
            if (savedDay == currentDay) {
                paperDailyPnlSolBps.set(obj.optLong("paper_daily_pnl_bps", 0L))
                paperDailyWins.set(obj.optInt("paper_daily_wins", 0))
                paperDailyLosses.set(obj.optInt("paper_daily_losses", 0))
                paperDailyTradeCount.set(obj.optInt("paper_daily_trades", 0))
                liveDailyPnlSolBps.set(obj.optLong("live_daily_pnl_bps", 0L))
                liveDailyWins.set(obj.optInt("live_daily_wins", 0))
                liveDailyLosses.set(obj.optInt("live_daily_losses", 0))
                liveDailyTradeCount.set(obj.optInt("live_daily_trades", 0))
                lastResetDay = savedDay
                ErrorLogger.info(TAG, "💾 Restored daily stats (same day)")
            } else {
                // New day - reset daily stats but keep treasury balance
                lastResetDay = currentDay
                ErrorLogger.info(TAG, "💾 New day detected - daily stats reset, treasury preserved")
            }
            
            ErrorLogger.info(TAG, "💾 Restored CashGenerationAI: Paper=${paperTreasuryBalanceBps.get()/100.0} SOL | Live=${liveTreasuryBalanceBps.get()/100.0} SOL")
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "💾 Restore failed: ${e.message}")
        }
    }
    
    /**
     * V5.6.28c: MANUAL RESTORATION - Allows user to restore paper treasury balance
     * that was lost due to missing persistence in earlier versions.
     * 
     * @param treasurySol The paper treasury balance to restore (in SOL)
     */
    fun manualRestorePaperTreasury(treasurySol: Double) {
        val bps = (treasurySol * 100).toLong()
        paperTreasuryBalanceBps.set(bps)
        save()
        ErrorLogger.info(TAG, "🔧 MANUAL RESTORE: Paper treasury set to ${treasurySol.fmt(4)} SOL")
    }
    
    /**
     * V5.6.28c: MANUAL RESTORATION - Allows user to restore live treasury balance.
     * 
     * @param treasurySol The live treasury balance to restore (in SOL)
     */
    fun manualRestoreLiveTreasury(treasurySol: Double) {
        val bps = (treasurySol * 100).toLong()
        liveTreasuryBalanceBps.set(bps)
        save()
        ErrorLogger.info(TAG, "🔧 MANUAL RESTORE: Live treasury set to ${treasurySol.fmt(4)} SOL")
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

        // V5.9.193: bypassed during bootstrap for data gathering
        val cashBootstrap = FluidLearningAI.getLearningProgress() < 0.40
        if (!cashBootstrap && activePositions.size >= MAX_CONCURRENT_POSITIONS) {
            rejectionReasons.add("max_positions_reached ($MAX_CONCURRENT_POSITIONS)")
        }

        // V5.9.436 — SCORE-EXPECTANCY SOFT GATE (per-layer).
        // Even if all hard gates pass, skip when this score bucket has
        // been net-losing over the last 25+ closed treasury trades.
        if (com.lifecyclebot.engine.ScoreExpectancyTracker.shouldReject("CASHGEN", treasuryScore)) {
            val mean = com.lifecyclebot.engine.ScoreExpectancyTracker.bucketMean("CASHGEN", treasuryScore)
            val n = com.lifecyclebot.engine.ScoreExpectancyTracker.bucketSamples("CASHGEN", treasuryScore)
            rejectionReasons.add("expectancy_reject_score_${treasuryScore}_μ_${"%+.1f".format(mean ?: 0.0)}%_n_${n}")
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

        // ═══════════════════════════════════════════════════════════════════
        // V5.6.6: AGGRESSIVE DYNAMIC POSITION SIZING
        // 
        // Treasury is the MONEY PRINTER - it needs to scale with success!
        // 
        // Scaling factors:
        //   1. Wallet balance (8% of wallet per trade)
        //   2. Treasury internal balance (compounding profits)
        //   3. Win streak (15% bonus per consecutive win, up to 5)
        //   4. Confidence (high confidence = bigger size)
        //   5. Mode (aggressive = 1.5x, defensive = 0.5x)
        // 
        // Examples with 10 SOL wallet:
        //   Base: 10 × 8% = 0.80 SOL
        //   + Win streak 3: × 1.45 = 1.16 SOL
        //   + High confidence: × 1.25 = 1.45 SOL
        //   + Aggressive mode: × 1.5 = 2.0 SOL (capped)
        // ═══════════════════════════════════════════════════════════════════
        
        val dailyPnl = dailyPnlSolBps.get() / 100.0
        val treasuryBalance = getTreasuryBalance(isPaperMode)

        // In LIVE mode the actual wallet IS the truth — treasury is a virtual accounting
        // ledger that can show thousands of SOL while the wallet holds pennies.
        // Using maxOf(wallet, treasury) in live mode caused 20 SOL position sizes on
        // a 0.07 SOL wallet (treasury=2169 SOL → effectiveBalance=2169 → size=20 SOL capped).
        // In paper mode compounding from the virtual treasury is fine and intentional.
        val effectiveBalance = if (isPaperMode) {
            maxOf(lastKnownWalletBalance, treasuryBalance)
        } else {
            lastKnownWalletBalance  // Live mode: real wallet only — never phantom treasury
        }
        val walletBasedSize = effectiveBalance * WALLET_SCALE_FACTOR
        var positionSol = maxOf(BASE_POSITION_SOL, walletBasedSize)
        
        ErrorLogger.debug(TAG, "💰 SIZE CALC: wallet=${lastKnownWalletBalance.fmt(2)}◎ treasury=${treasuryBalance.fmt(2)}◎ → base=${positionSol.fmt(3)}◎")

        // V5.6.6: WIN STREAK BONUS - hot hand gets bigger size
        val currentStreak = winStreak.get().coerceIn(0, 5)
        if (currentStreak > 0) {
            val streakBonus = 1.0 + (currentStreak * WIN_STREAK_SCALE)
            positionSol *= streakBonus
            ErrorLogger.debug(TAG, "💰 WIN STREAK: $currentStreak → ${((streakBonus - 1) * 100).toInt()}% bonus")
        }

        if (COMPOUNDING_ENABLED) {
            // V5.9 FIX: Compound TODAY'S PROFITS only — not total treasury balance.
            // Old code added treasuryBalance * 0.5 * conf per trade: with 6 SOL treasury
            // this inflated every trade by 2+ SOL (38% of wallet). Runaway sizing.
            // Now only incremental profits of the day feed back into sizing.
            if (dailyPnl > 0) {
                val dailyCompound = dailyPnl * COMPOUNDING_RATIO * (treasuryConfidence / 100.0)
                positionSol += dailyCompound
                ErrorLogger.debug(TAG, "💰 DAILY COMPOUND: +${dailyCompound.fmt(3)}SOL from today's profits")
            }
        }

        if (treasuryConfidence >= 75 && treasuryScore >= 50) {
            positionSol *= POSITION_SCALE_FACTOR
        }

        // V5.6.6: More aggressive mode multipliers
        positionSol *= when (mode) {
            TreasuryMode.DEFENSIVE -> 0.5   // Still trades, just smaller
            TreasuryMode.CRUISE -> 0.8      // Normal operation
            TreasuryMode.AGGRESSIVE -> 1.5  // V5.6.6: Raised from 1.15 - GO BIG
            TreasuryMode.HUNT -> 1.2        // V5.6.6: Raised from 1.0 - hunting = aggressive
            TreasuryMode.PAUSED -> 0.0
        }

        // V5.6.6: Dynamic cap scales with WALLET (not just treasury)
        // Bigger wallet = can afford bigger positions
        val walletScaleFactor = (1 + effectiveBalance * 0.05).coerceIn(1.0, 10.0)
        val maxWithCompounding = MAX_POSITION_SOL * walletScaleFactor
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

        // V5.9.101 CRITICAL LIVE MONEY-LEAK FIX:
        // Previously Treasury could request a 0.15-0.18 SOL entry while the
        // live wallet held only 0.01 SOL. The live-buy path then silently
        // clamped size to (wallet - rent reserve) ≈ 0.007 SOL, submitted
        // the swap anyway, ate the tx fees + slippage, and the Treasury
        // virtual-accounting ledger drifted out of sync with the real
        // wallet. Rinse/repeat drained a user from 1.0 SOL -> 0.01 SOL
        // while showing no visible positions in the UI.
        // In LIVE mode, cap the requested size at (actual wallet minus a
        // safety reserve for rent + fees). If the cap would drop the size
        // below MIN_POSITION_SOL, return 0.0 so the caller skips the trade
        // entirely instead of firing an under-sized swap that burns fees.
        if (!isPaperMode) {
            val liveSafetyReserveSol = 0.01   // rent-exempt + tx fees + fee-wallet split
            val walletCeiling = (lastKnownWalletBalance - liveSafetyReserveSol).coerceAtLeast(0.0)
            if (positionSol > walletCeiling) {
                if (walletCeiling < MIN_POSITION_SOL) {
                    ErrorLogger.warn(
                        TAG,
                        "💰 LIVE SIZE SKIP: requested=${positionSol.fmt(3)}◎ but wallet=${lastKnownWalletBalance.fmt(4)}◎ leaves only ${walletCeiling.fmt(4)}◎ after reserve — below MIN_POSITION_SOL=${MIN_POSITION_SOL}; skip trade"
                    )
                    return TreasurySignal(
                        shouldEnter     = false,
                        positionSizeSol = 0.0,
                        takeProfitPct   = 0.0,
                        stopLossPct     = 0.0,
                        confidence      = treasuryConfidence,
                        reason          = "REJECTED: LIVE_WALLET_TOO_SMALL (${lastKnownWalletBalance.fmt(4)}◎)",
                        mode            = mode,
                        isPaperMode     = isPaperMode,
                    )
                }
                ErrorLogger.info(
                    TAG,
                    "💰 LIVE SIZE CAP: ${positionSol.fmt(3)}◎ -> ${walletCeiling.fmt(4)}◎ (wallet=${lastKnownWalletBalance.fmt(4)}◎ minus ${liveSafetyReserveSol}◎ reserve)"
                )
                positionSol = walletCeiling
            }
        }

        ErrorLogger.info(TAG, "💰 FINAL SIZE: ${positionSol.fmt(3)}◎ | wallet=${effectiveBalance.fmt(2)}◎ streak=$currentStreak mode=$mode")

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
            // V5.9.90: renamed from "TREASURY ENTRY" — this log fires when
            // CashGen APPROVES the signal; actual entry is still gated by
            // BotService cooldown / duplicates / safety checks downstream.
            "💰 TREASURY SIGNAL: $symbol | " +
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
            entryScore = treasuryScore,
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
        entryScore: Int = 0,  // V5.9.436 — for outcome attribution
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
            entryScore = entryScore,
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

    /**
     * V5.9.268 — public snapshot of all live treasury scalps.
     * Used by the SentientPersonality LLM context-builder so 'Morgan
     * Freeman' actually sees treasury positions in the live ledger.
     */
    fun getActivePositionsSnapshot(): List<TreasuryPosition> {
        return synchronized(activePositions) { activePositions.values.toList() }
    }

    /**
     * V5.9.268 — paper- and live-aware snapshot. Returns the positions
     * for whichever wallet matches `isPaper`.
     */
    fun getActivePositionsForMode(isPaper: Boolean): List<TreasuryPosition> {
        val source = if (isPaper) paperPositions else livePositions
        return synchronized(source) { source.values.toList() }
    }

    fun updatePrice(mint: String, price: Double) {
        if (price > 0) {
            currentPrices[mint] = price
            lastPriceUpdate[mint] = System.currentTimeMillis()
            // V5.9.270: stamp onto position object so getActivePositions() returns live PnL
            synchronized(activePositions) { activePositions[mint]?.currentPrice = price }
        }
    }

    fun getTrackedPrice(mint: String): Double? {
        return currentPrices[mint]
    }

    /**
     * V5.9.415 — return the wall-clock ms of the last successful
     * updatePrice() call for this mint, or null if we have no record.
     * Used by the UI to label rows as 'stale' when no tick has arrived
     * recently, even if the last tracked price happens to equal entry.
     */
    fun getLastPriceUpdateMs(mint: String): Long? = lastPriceUpdate[mint]

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

        // V5.9.204: was pnlPct < 0 — flat 0% positions held FOREVER. Now exit if below TP floor too.
        if (holdMinutes >= MAX_HOLD_MINUTES && pnlPct < TAKE_PROFIT_PCT_PAPER) {
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

        // Derive the TP% from the stored target price (matches what UI displays).
        // Also check pnlPct directly — mathematically equivalent to isAboveTarget
        // but survives any floating-point or stale-price edge cases.
        val tpPct = if (pos.entryPrice > 0 && pos.targetPrice > pos.entryPrice) {
            (pos.targetPrice - pos.entryPrice) / pos.entryPrice * 100.0
        } else if (pos.isPaper) TAKE_PROFIT_PCT_PAPER.toDouble() else TAKE_PROFIT_PCT_LIVE.toDouble()

        if (isAboveTarget || pnlPct >= tpPct) {
            val modeLabel = if (pos.isPaper) "PAPER" else "LIVE"
            ErrorLogger.info(
                TAG,
                "💰 TREASURY TP HIT [$modeLabel]: ${pos.symbol} | +${pnlPct.fmt(1)}% >= ${"%.1f".format(tpPct)}% | " +
                    "price=$currentPrice target=${pos.targetPrice} | SELLING & PROMOTING!",
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

        // V5.9.204: was pnlPct < 0 — flat 0% positions held FOREVER. Exit if below TP floor.
        if (holdMinutes >= MAX_HOLD_MINUTES && pnlPct < TAKE_PROFIT_PCT_LIVE) {
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
        val holdMinutesLong = (System.currentTimeMillis() - pos.entryTime) / 60_000L

        // V5.9.434 — journal every V3 sub-trader close so the persistent
        // Trade Journal reflects ALL trades across the universe (was only
        // showing ~300 of 4791 because V3 sub-traders bypassed Executor).
        // V5.9.436 — recorder also feeds outcome-attribution trackers.
        try {
            com.lifecyclebot.engine.V3JournalRecorder.recordClose(
                symbol = pos.symbol, mint = pos.mint,
                entryPrice = pos.entryPrice, exitPrice = exitPrice,
                sizeSol = pos.entrySol, pnlPct = pnlPct, pnlSol = pnlSol,
                isPaper = pos.isPaper, layer = "CASHGEN",
                exitReason = exitReason.name,
                entryScore = pos.entryScore,
                holdMinutes = holdMinutesLong,
            )
        } catch (_: Exception) {}

        recentExits[mint] = System.currentTimeMillis()
        val oneMinuteAgo = System.currentTimeMillis() - 60_000
        recentExits.entries.removeIf { it.value < oneMinuteAgo }

        val pnlBps = (pnlSol * 100).toLong()
        dailyPnlSolBps.addAndGet(pnlBps)

        if (pnlSol > 0) {
            dailyWins.incrementAndGet()
            // V5.6.7 FIX: DON'T add to treasury here - Executor.paperSell() handles
            // profit distribution via AutoCompoundEngine (50/50 split to wallet/treasury)
            // Adding here caused DOUBLE-COUNTING: treasury got profit twice!
            // Treasury allocation is now handled in: Executor.kt line ~4137
            // addToTreasury(pnlSol, pos.isPaper) // REMOVED - caused double counting
            
            // V5.6.6: Increment win streak on profitable exit
            winStreak.incrementAndGet()
            ErrorLogger.debug(TAG, "💰🔥 WIN STREAK: ${winStreak.get()} consecutive wins!")
        } else {
            dailyLosses.incrementAndGet()
            // V5.6.6: Reset win streak on loss
            val previousStreak = winStreak.getAndSet(0)
            if (previousStreak > 0) {
                ErrorLogger.debug(TAG, "💰❄️ WIN STREAK BROKEN: was $previousStreak, now 0")
            }
        }

        try {
            val isWin = pnlPct > 0.0  // V5.9.408: restored pre-225 win-threshold
            if (pos.isPaper) {
                FluidLearningAI.recordSubTraderTrade(isWin)
            } else {
                FluidLearningAI.recordSubTraderTrade(isWin)
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "FluidLearning update failed: ${e.message}")
        }

        val dailyPnl = dailyPnlSolBps.get() / 100.0
        val winRate = if (dailyWins.get() + dailyLosses.get() > 0) {
            dailyWins.get().toDouble() / (dailyWins.get() + dailyLosses.get()) * 100
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
            winRate = if (dailyWins.get() + dailyLosses.get() > 0) {
                dailyWins.get().toDouble() / (dailyWins.get() + dailyLosses.get()) * 100
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

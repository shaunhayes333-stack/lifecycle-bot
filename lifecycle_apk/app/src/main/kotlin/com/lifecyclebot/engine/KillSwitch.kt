package com.lifecyclebot.engine

import android.content.Context

/**
 * KillSwitch — Account Protection System
 * ═══════════════════════════════════════════════════════════════════════
 * 
 * CRITICAL safety mechanisms:
 * 1. Max Daily Loss - Stop trading after X% daily loss
 * 2. Max Drawdown - Stop trading after X% drawdown from peak
 * 3. Max Consecutive Losses - Stop after N losses in a row
 * 4. Auto Shutdown - Completely halt bot when triggered
 * 
 * Without this, one bad cycle = account wiped
 */
object KillSwitch {

    private const val PREFS_NAME = "kill_switch"
    
    // Default limits
    private const val DEFAULT_MAX_DAILY_LOSS_PCT = 15.0      // Stop after 15% daily loss
    private const val DEFAULT_MAX_DRAWDOWN_PCT = 25.0        // Stop after 25% drawdown from peak
    private const val DEFAULT_MAX_CONSECUTIVE_LOSSES = 5     // Stop after 5 losses in a row
    private const val DEFAULT_MAX_TRADES_PER_HOUR = 10       // Rate limit
    
    // V5.7.8: Paper mode — no limits, let it learn freely
    private const val PAPER_MAX_DAILY_LOSS_PCT = 999.0
    private const val PAPER_MAX_DRAWDOWN_PCT = 999.0
    private const val PAPER_MAX_CONSECUTIVE_LOSSES = 999
    private const val PAPER_MAX_TRADES_PER_HOUR = 999
    
    // Paper mode flag
    var isPaperMode: Boolean = false
    
    // State tracking
    private var peakBalance: Double = 0.0
    private var dailyStartBalance: Double = 0.0
    private var dailyStartDate: Long = 0
    private var consecutiveLosses: Int = 0
    private var tradesThisHour: Int = 0
    private var hourStart: Long = 0
    private var isKilled: Boolean = false
    private var killReason: String = ""
    private var killTime: Long = 0
    
    // Callbacks
    var onKillTriggered: ((String) -> Unit)? = null
    var onWarning: ((String) -> Unit)? = null
    
    data class KillSwitchState(
        val isKilled: Boolean,
        val killReason: String,
        val dailyPnlPct: Double,
        val drawdownPct: Double,
        val consecutiveLosses: Int,
        val tradesThisHour: Int,
        val warningLevel: WarningLevel,
    )
    
    enum class WarningLevel {
        NONE,
        CAUTION,    // 50% of limit
        WARNING,    // 75% of limit
        CRITICAL,   // 90% of limit
        KILLED,     // Limit hit
    }
    
    /**
     * Initialize with current balance
     */
    fun init(context: Context, currentBalance: Double) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Load persisted state
        peakBalance = prefs.getFloat("peak_balance", currentBalance.toFloat()).toDouble()
        dailyStartBalance = prefs.getFloat("daily_start_balance", currentBalance.toFloat()).toDouble()
        dailyStartDate = prefs.getLong("daily_start_date", System.currentTimeMillis())
        consecutiveLosses = prefs.getInt("consecutive_losses", 0)
        isKilled = prefs.getBoolean("is_killed", false)
        killReason = prefs.getString("kill_reason", "") ?: ""
        killTime = prefs.getLong("kill_time", 0)
        
        // Update peak if current balance is higher
        if (currentBalance > peakBalance) {
            peakBalance = currentBalance
        }
        
        // Reset daily tracking if new day
        val now = System.currentTimeMillis()
        if (!isSameDay(dailyStartDate, now)) {
            dailyStartBalance = currentBalance
            dailyStartDate = now
            // Don't auto-reset kill switch - require manual reset
        }
        
        // Reset hourly tracking
        if (!isSameHour(hourStart, now)) {
            tradesThisHour = 0
            hourStart = now
        }
        
        save(context)
        
        ErrorLogger.info("KillSwitch", "Initialized: peak=$${peakBalance.toInt()} daily=$${dailyStartBalance.toInt()} losses=$consecutiveLosses killed=$isKilled")
    }
    
    /**
     * Record a trade result
     * @return true if trading should continue, false if killed
     */
    fun recordTrade(
        context: Context,
        pnlPct: Double,
        currentBalance: Double,
        maxDailyLossPct: Double = DEFAULT_MAX_DAILY_LOSS_PCT,
        maxDrawdownPct: Double = DEFAULT_MAX_DRAWDOWN_PCT,
        maxConsecutiveLosses: Int = DEFAULT_MAX_CONSECUTIVE_LOSSES,
    ): Boolean {
        
        // V5.7.8: Paper mode — never kill, always continue
        if (isPaperMode) {
            tradesThisHour++
            return true
        }
        
        if (isKilled) {
            ErrorLogger.warn("KillSwitch", "Already killed: $killReason")
            return false
        }
        
        val now = System.currentTimeMillis()
        
        // Update peak balance
        if (currentBalance > peakBalance) {
            peakBalance = currentBalance
        }
        
        // Track trades per hour
        if (!isSameHour(hourStart, now)) {
            tradesThisHour = 0
            hourStart = now
        }
        tradesThisHour++
        
        // Track consecutive losses
        if (pnlPct < 0) {
            consecutiveLosses++
        } else {
            consecutiveLosses = 0
        }
        
        // Reset daily if new day
        if (!isSameDay(dailyStartDate, now)) {
            dailyStartBalance = currentBalance
            dailyStartDate = now
        }
        
        // ════════════════════════════════════════════════════════════════
        // CHECK KILL CONDITIONS
        // ════════════════════════════════════════════════════════════════
        
        // 1. Max Daily Loss
        val dailyPnlPct = if (dailyStartBalance > 0) {
            ((currentBalance - dailyStartBalance) / dailyStartBalance) * 100
        } else 0.0
        
        if (dailyPnlPct <= -maxDailyLossPct) {
            triggerKill(context, "MAX_DAILY_LOSS", 
                "Daily loss ${dailyPnlPct.toInt()}% exceeded limit -${maxDailyLossPct.toInt()}%")
            return false
        }
        
        // 2. Max Drawdown
        val drawdownPct = if (peakBalance > 0) {
            ((peakBalance - currentBalance) / peakBalance) * 100
        } else 0.0
        
        if (drawdownPct >= maxDrawdownPct) {
            triggerKill(context, "MAX_DRAWDOWN",
                "Drawdown ${drawdownPct.toInt()}% exceeded limit ${maxDrawdownPct.toInt()}%")
            return false
        }
        
        // 3. Max Consecutive Losses
        if (consecutiveLosses >= maxConsecutiveLosses) {
            triggerKill(context, "MAX_CONSECUTIVE_LOSSES",
                "$consecutiveLosses consecutive losses exceeded limit $maxConsecutiveLosses")
            return false
        }
        
        // ════════════════════════════════════════════════════════════════
        // WARNINGS (not kills)
        // ════════════════════════════════════════════════════════════════
        
        val warningLevel = getWarningLevel(dailyPnlPct, drawdownPct, consecutiveLosses,
            maxDailyLossPct, maxDrawdownPct, maxConsecutiveLosses)
        
        if (warningLevel >= WarningLevel.WARNING) {
            val warning = buildWarningMessage(dailyPnlPct, drawdownPct, consecutiveLosses,
                maxDailyLossPct, maxDrawdownPct, maxConsecutiveLosses)
            onWarning?.invoke(warning)
            ErrorLogger.warn("KillSwitch", "⚠️ $warning")
        }
        
        save(context)
        return true
    }
    
    /**
     * Check if trading is allowed (without recording a trade)
     */
    fun canTrade(
        currentBalance: Double,
        maxDailyLossPct: Double = DEFAULT_MAX_DAILY_LOSS_PCT,
        maxDrawdownPct: Double = DEFAULT_MAX_DRAWDOWN_PCT,
        maxConsecutiveLosses: Int = DEFAULT_MAX_CONSECUTIVE_LOSSES,
        maxTradesPerHour: Int = DEFAULT_MAX_TRADES_PER_HOUR,
    ): Pair<Boolean, String> {
        
        // V5.7.8: Paper mode — always allow trading, no limits
        if (isPaperMode) {
            return Pair(true, "PAPER_MODE: no limits")
        }
        
        if (isKilled) {
            return Pair(false, "KILLED: $killReason")
        }
        
        // Check hourly rate limit
        val now = System.currentTimeMillis()
        if (isSameHour(hourStart, now) && tradesThisHour >= maxTradesPerHour) {
            return Pair(false, "RATE_LIMITED: $tradesThisHour trades this hour (max $maxTradesPerHour)")
        }
        
        // Check daily loss
        val dailyPnlPct = if (dailyStartBalance > 0) {
            ((currentBalance - dailyStartBalance) / dailyStartBalance) * 100
        } else 0.0
        
        if (dailyPnlPct <= -maxDailyLossPct * 0.9) {  // 90% of limit = block new trades
            return Pair(false, "DAILY_LOSS_NEAR_LIMIT: ${dailyPnlPct.toInt()}% (limit -${maxDailyLossPct.toInt()}%)")
        }
        
        // Check drawdown
        val drawdownPct = if (peakBalance > 0) {
            ((peakBalance - currentBalance) / peakBalance) * 100
        } else 0.0
        
        if (drawdownPct >= maxDrawdownPct * 0.9) {  // 90% of limit
            return Pair(false, "DRAWDOWN_NEAR_LIMIT: ${drawdownPct.toInt()}% (limit ${maxDrawdownPct.toInt()}%)")
        }
        
        // Check consecutive losses
        if (consecutiveLosses >= maxConsecutiveLosses - 1) {  // One away from limit
            return Pair(false, "LOSSES_NEAR_LIMIT: $consecutiveLosses consecutive (limit $maxConsecutiveLosses)")
        }
        
        return Pair(true, "OK")
    }
    
    /**
     * Get current state
     */
    fun getState(
        currentBalance: Double,
        maxDailyLossPct: Double = DEFAULT_MAX_DAILY_LOSS_PCT,
        maxDrawdownPct: Double = DEFAULT_MAX_DRAWDOWN_PCT,
        maxConsecutiveLosses: Int = DEFAULT_MAX_CONSECUTIVE_LOSSES,
    ): KillSwitchState {
        
        val dailyPnlPct = if (dailyStartBalance > 0) {
            ((currentBalance - dailyStartBalance) / dailyStartBalance) * 100
        } else 0.0
        
        val drawdownPct = if (peakBalance > 0) {
            ((peakBalance - currentBalance) / peakBalance) * 100
        } else 0.0
        
        val warningLevel = if (isKilled) WarningLevel.KILLED else {
            getWarningLevel(dailyPnlPct, drawdownPct, consecutiveLosses,
                maxDailyLossPct, maxDrawdownPct, maxConsecutiveLosses)
        }
        
        return KillSwitchState(
            isKilled = isKilled,
            killReason = killReason,
            dailyPnlPct = dailyPnlPct,
            drawdownPct = drawdownPct,
            consecutiveLosses = consecutiveLosses,
            tradesThisHour = tradesThisHour,
            warningLevel = warningLevel,
        )
    }
    
    /**
     * Reset kill switch (manual reset required)
     */
    fun reset(context: Context, newBalance: Double) {
        isKilled = false
        killReason = ""
        killTime = 0
        consecutiveLosses = 0
        peakBalance = newBalance
        dailyStartBalance = newBalance
        dailyStartDate = System.currentTimeMillis()
        tradesThisHour = 0
        hourStart = System.currentTimeMillis()
        
        save(context)
        ErrorLogger.info("KillSwitch", "Reset with balance $${newBalance.toInt()}")
    }
    
    // ════════════════════════════════════════════════════════════════
    // Private helpers
    // ════════════════════════════════════════════════════════════════
    
    private fun triggerKill(context: Context, reason: String, details: String) {
        isKilled = true
        killReason = "$reason: $details"
        killTime = System.currentTimeMillis()
        
        save(context)
        
        ErrorLogger.error("KillSwitch", "🛑 KILL SWITCH TRIGGERED: $killReason")
        onKillTriggered?.invoke(killReason)
    }
    
    private fun getWarningLevel(
        dailyPnlPct: Double,
        drawdownPct: Double,
        consecutiveLosses: Int,
        maxDailyLossPct: Double,
        maxDrawdownPct: Double,
        maxConsecutiveLosses: Int,
    ): WarningLevel {
        
        val dailyRatio = if (dailyPnlPct < 0) (-dailyPnlPct / maxDailyLossPct) else 0.0
        val drawdownRatio = drawdownPct / maxDrawdownPct
        val lossRatio = consecutiveLosses.toDouble() / maxConsecutiveLosses
        
        val maxRatio = maxOf(dailyRatio, drawdownRatio, lossRatio)
        
        return when {
            maxRatio >= 1.0 -> WarningLevel.KILLED
            maxRatio >= 0.9 -> WarningLevel.CRITICAL
            maxRatio >= 0.75 -> WarningLevel.WARNING
            maxRatio >= 0.5 -> WarningLevel.CAUTION
            else -> WarningLevel.NONE
        }
    }
    
    private fun buildWarningMessage(
        dailyPnlPct: Double,
        drawdownPct: Double,
        consecutiveLosses: Int,
        maxDailyLossPct: Double,
        maxDrawdownPct: Double,
        maxConsecutiveLosses: Int,
    ): String {
        val parts = mutableListOf<String>()
        
        if (dailyPnlPct < 0 && -dailyPnlPct >= maxDailyLossPct * 0.5) {
            parts.add("Daily: ${dailyPnlPct.toInt()}%/${-maxDailyLossPct.toInt()}%")
        }
        if (drawdownPct >= maxDrawdownPct * 0.5) {
            parts.add("DD: ${drawdownPct.toInt()}%/${maxDrawdownPct.toInt()}%")
        }
        if (consecutiveLosses >= maxConsecutiveLosses / 2) {
            parts.add("Losses: $consecutiveLosses/$maxConsecutiveLosses")
        }
        
        return parts.joinToString(" | ")
    }
    
    private fun isSameDay(ts1: Long, ts2: Long): Boolean {
        val day1 = ts1 / (24 * 60 * 60 * 1000)
        val day2 = ts2 / (24 * 60 * 60 * 1000)
        return day1 == day2
    }
    
    private fun isSameHour(ts1: Long, ts2: Long): Boolean {
        val hour1 = ts1 / (60 * 60 * 1000)
        val hour2 = ts2 / (60 * 60 * 1000)
        return hour1 == hour2
    }
    
    private fun save(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putFloat("peak_balance", peakBalance.toFloat())
            putFloat("daily_start_balance", dailyStartBalance.toFloat())
            putLong("daily_start_date", dailyStartDate)
            putInt("consecutive_losses", consecutiveLosses)
            putBoolean("is_killed", isKilled)
            putString("kill_reason", killReason)
            putLong("kill_time", killTime)
            apply()
        }
    }
}

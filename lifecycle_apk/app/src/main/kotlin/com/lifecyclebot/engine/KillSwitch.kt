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
    // V5.9.495z12 — operator mandate: "200-500 trades/day minimum live when
    // learnt". Pre-fix `DEFAULT_MAX_TRADES_PER_HOUR = 10` capped live at
    // ~240/day in absolute best case and was the direct cause of the
    // observed "3 trades an hour if I'm lucky" choke in live mode. Bumped
    // to 100/hour so the bot can sustain 200–500/day with normal pacing,
    // bursts to 1000+ during volatile sessions, while the daily-loss /
    // drawdown / consecutive-loss circuit breakers still protect capital.
    private const val DEFAULT_MAX_TRADES_PER_HOUR = 100      // Rate limit (live)
    
    // V5.7.8: Paper mode — no limits, let it learn freely
    private const val PAPER_MAX_DAILY_LOSS_PCT = 999.0
    private const val PAPER_MAX_DRAWDOWN_PCT = 999.0
    private const val PAPER_MAX_CONSECUTIVE_LOSSES = 999
    private const val PAPER_MAX_TRADES_PER_HOUR = 999
    
    // Paper mode flag
    var isPaperMode: Boolean
        get() = RuntimeModeAuthority.isPaper()
        set(@Suppress("UNUSED_PARAMETER") value) { /* RuntimeModeAuthority owns this fact. */ }
    private var context7835: Context? = null
    private var initializedLive7835 = false
    private var config7835 = com.lifecyclebot.data.BotConfig()
    private val canonicalOutcomes7835 = mutableSetOf<String>()
    private var outcomeBaselineAt7837 = 0L

    internal fun outcomeIsCurrent7837(atMs: Long, baselineMs: Long, nowMs: Long): Boolean =
        atMs >= baselineMs && atMs <= nowMs && atMs > 0L

    /** Repair only a legacy computed latch whose own baseline was reset after
     * it fired and whose current equity has fully recovered that baseline. */
    internal fun staleBaselineLatch7837(reason: String, killedAt: Long, baselineAt: Long,
        peak: Double, daily: Double, equity: Double): Boolean =
        (reason.startsWith("MAX_DRAWDOWN:") || reason.startsWith("MAX_DAILY_LOSS:")) &&
            killedAt > 0L && baselineAt > killedAt && equity.isFinite() && equity > 0.0 &&
            peak.isFinite() && daily.isFinite() && peak > 0.0 && daily > 0.0 &&
            equity + 1e-8 >= peak && equity + 1e-8 >= daily

    /**
     * V5.0.7843 — persisted wallet peaks are account-history, not necessarily
     * trading P&L. A withdrawal, funding move, or an old larger wallet must not
     * become a synthetic 25%+ bot drawdown on the next build. One schema
     * migration starts the live risk window from the actual realised live
     * equity; canonical LIVE closes rebuild the loss/streak authority from
     * there. Hard/manual kills are not silently cleared.
     */
    internal fun shouldRebaseLiveBaseline7843(storedSchema: Int, equity: Double): Boolean =
        storedSchema < 7843 && equity.isFinite() && equity > 0.0

    /**
     * V5.0.7857 — a persisted daily-loss latch from the shared PAPER/LIVE
     * baseline is not live loss evidence. Retire only that computed latch on
     * migration and anchor the new daily window to observed LIVE equity.
     * Manual and drawdown kills remain latched.
     */
    internal fun shouldRebasePaperContaminatedDailyLatch7857(
        storedSchema: Int, reason: String, equity: Double,
    ): Boolean = storedSchema < 7857 && reason.startsWith("MAX_DAILY_LOSS:") &&
        equity.isFinite() && equity > 0.0

    /**
     * V5.0.7864 — operator doctrine "never pause trading; size down instead"
     * (FIELD_MANUAL §risk: drawdown cuts size, it does not stop the desk).
     * 5.0.7863 live proved the computed latch halting every lane
     * (KILL_SWITCH_7835 on MOONSHOT/BLUECHIP/CASHGEN/TREASURY/SHITCOIN) on a
     * "40% drawdown" while LiveRiskPolicy7807's own rolling peak read ~18%.
     * Drawdown, daily loss and loss streaks are already priced into size by
     * LiveRiskPolicy7807 (drawdownMultiplier → 0.35x floor, lane daily-loss
     * cap → 0.3x, expectancy shrink). Here they are warnings only; manual /
     * explicit kills, the hourly rate limit and missing equity stay hard.
     */
    internal fun computedLimitLatch7864(reason: String): Boolean =
        reason.startsWith("MAX_DRAWDOWN:") || reason.startsWith("MAX_DAILY_LOSS:") ||
            reason.startsWith("MAX_CONSECUTIVE_LOSSES:")

    fun initConfigured7835(context: Context, config: com.lifecyclebot.data.BotConfig) {
        config7835 = config
        init(context, com.lifecyclebot.engine.truth.LiveRiskPolicy7807.liveEquitySol(BotService.status.walletSol))
    }

    @Synchronized
    fun checkEntry7835(paper: Boolean, config: com.lifecyclebot.data.BotConfig? = null): String? {
        if (paper) return null
        if (RuntimeModeAuthority.isPaper()) return "LIVE_ENTRY_WHILE_RUNTIME_PAPER_7835"
        if (config != null) config7835 = config
        val equity = com.lifecyclebot.engine.truth.LiveRiskPolicy7807.liveEquitySol(BotService.status.walletSol)
        if (!equity.isFinite() || equity <= 0.0) return "KILL_SWITCH_EQUITY_UNAVAILABLE_7835"
        if (!initializedLive7835) context7835?.let { init(it, equity) }
        val now = System.currentTimeMillis()
        if (!isSameDay(dailyStartDate, now)) { dailyStartBalance = equity; dailyStartDate = now }
        if (isKilled && killReason.startsWith("MAX_CONSECUTIVE_LOSSES") &&
            now - killTime >= config7835.circuitBreakerPauseMin.coerceAtLeast(1) * 60_000L) {
            isKilled = false; killReason = ""; consecutiveLosses = 0
            context7835?.let { save(it) }
        }
        val verdict = canTrade(equity, maxDailyLossPct = config7835.maxDailyLossPct,
            maxConsecutiveLosses = config7835.circuitBreakerLosses,
            maxTradesPerHour = config7835.maxTradesPerHour)
        return if (verdict.first) null else "KILL_SWITCH_7835:${verdict.second}"
    }

    /** Same limits as entry, without changing the risk state during report generation. */
    @Synchronized
    fun preflight7863(): Pair<Boolean, String> {
        if (RuntimeModeAuthority.isPaper()) return true to "PAPER_MODE"
        if (!initializedLive7835) return false to "RISK_STATE_NOT_INITIALIZED"
        val equity = com.lifecyclebot.engine.truth.LiveRiskPolicy7807.liveEquitySol(BotService.status.walletSol)
        if (!equity.isFinite() || equity <= 0.0) return false to "EQUITY_UNAVAILABLE"
        return canTrade(equity, maxDailyLossPct = config7835.maxDailyLossPct,
            maxConsecutiveLosses = config7835.circuitBreakerLosses,
            maxTradesPerHour = config7835.maxTradesPerHour)
    }

    @Synchronized
    fun recordCanonical7835(env: com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        if (!env.mode.equals("LIVE", true) || !env.terminal) return true
        val ctx = context7835 ?: return false
        val equity = com.lifecyclebot.engine.truth.LiveRiskPolicy7807.liveEquitySol(BotService.status.walletSol)
        if (!equity.isFinite() || equity <= 0.0) return false
        if (!initializedLive7835) initLive7835(ctx, equity)
        val key = env.economicEventId.ifBlank { env.tradeId }
        if (key.isBlank()) return false
        if (key in canonicalOutcomes7835) return true
        // Durable bus replay teaches learners, but is not a new loss streak or
        // an hourly trade in today's risk window. Equity checks run at entry.
        if (!outcomeIsCurrent7837(env.atMs, outcomeBaselineAt7837, System.currentTimeMillis())) {
            PipelineHealthCollector.labelInc("KILL_SWITCH_HISTORICAL_REPLAY_EXCLUDED_7837")
            return true
        }
        if (!env.realizedReturnPct.isFinite()) return false
        recordTrade(ctx, env.realizedReturnPct, equity,
            maxDailyLossPct = config7835.maxDailyLossPct,
            maxConsecutiveLosses = config7835.circuitBreakerLosses,
            paperOutcome7835 = false)
        canonicalOutcomes7835.add(key)
        save(ctx)
        return true
    }
    
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
    @Synchronized
    fun init(context: Context, currentBalance: Double) {
        context7835 = context.applicationContext
        if (RuntimeModeAuthority.isPaper()) return
        initLive7835(context, currentBalance)
    }

    @Synchronized
    private fun initLive7835(context: Context, currentBalance: Double) {
        if (initializedLive7835 || !currentBalance.isFinite() || currentBalance <= 0.0) return
        initializedLive7835 = true
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        canonicalOutcomes7835.addAll(prefs.getStringSet("canonical_outcomes_7835", emptySet()).orEmpty())
        outcomeBaselineAt7837 = prefs.getLong("outcome_baseline_at_7837", System.currentTimeMillis())
        val storedSchema7843 = prefs.getInt("environment_schema", 0)
        // Load persisted state
        peakBalance = prefs.getFloat("peak_balance", currentBalance.toFloat()).toDouble()
        dailyStartBalance = prefs.getFloat("daily_start_balance", currentBalance.toFloat()).toDouble()
        dailyStartDate = prefs.getLong("daily_start_date", System.currentTimeMillis())
        consecutiveLosses = prefs.getInt("consecutive_losses", 0)
        isKilled = prefs.getBoolean("is_killed", false)
        killReason = prefs.getString("kill_reason", "") ?: ""
        killTime = prefs.getLong("kill_time", 0)
        if (storedSchema7843 < 7835) {
            // Prior baselines could contain PAPER cash. Preserve an explicit kill,
            // retire unattributable balances/streaks before live admission.
            peakBalance = currentBalance; dailyStartBalance = currentBalance
            dailyStartDate = System.currentTimeMillis(); consecutiveLosses = 0
        }

        // V5.0.7843 — 7842 runtime proved the persisted peak was still acting
        // as live P&L authority: wallet 0.2016 SOL was refused as a 39% drawdown
        // before a single canonical live close existed. Rebase once on schema
        // migration. A prior drawdown/daily-loss computed latch belongs to that
        // retired baseline; manual and other explicit kill reasons remain hard.
        if (shouldRebaseLiveBaseline7843(storedSchema7843, currentBalance)) {
            val priorPeak7843 = peakBalance
            val priorDaily7843 = dailyStartBalance
            val priorReason7843 = killReason
            peakBalance = currentBalance
            dailyStartBalance = currentBalance
            dailyStartDate = System.currentTimeMillis()
            consecutiveLosses = 0
            outcomeBaselineAt7837 = dailyStartDate
            if (isKilled && (killReason.startsWith("MAX_DRAWDOWN:") || killReason.startsWith("MAX_DAILY_LOSS:"))) {
                isKilled = false
                killReason = ""
                killTime = 0L
            }
            try {
                PipelineHealthCollector.labelInc("KILL_SWITCH_ACCOUNT_BASELINE_REBASED_7843")
                ForensicLogger.lifecycle(
                    "KILL_SWITCH_ACCOUNT_BASELINE_REBASED_7843",
                    "schema=$storedSchema7843 equity=$currentBalance priorPeak=$priorPeak7843 priorDaily=$priorDaily7843 " +
                        "priorReason=${priorReason7843.take(80)} action=retire_external_account_delta_as_trading_drawdown",
                )
            } catch (_: Throwable) {}
        }

        if (isKilled && computedLimitLatch7864(killReason)) {
            val priorReason7864 = killReason
            isKilled = false; killReason = ""; killTime = 0L
            peakBalance = currentBalance
            try {
                PipelineHealthCollector.labelInc("KILL_SWITCH_COMPUTED_LATCH_RETIRED_SIZE_DOWN_7864")
                ForensicLogger.lifecycle(
                    "KILL_SWITCH_COMPUTED_LATCH_RETIRED_SIZE_DOWN_7864",
                    "schema=$storedSchema7843 equity=$currentBalance priorReason=${priorReason7864.take(80)} " +
                        "action=size_down_via_LiveRiskPolicy7807_not_halt",
                )
            } catch (_: Throwable) {}
        }

        if (shouldRebasePaperContaminatedDailyLatch7857(storedSchema7843, killReason, currentBalance)) {
            val priorDaily7857 = dailyStartBalance
            val priorReason7857 = killReason
            dailyStartBalance = currentBalance
            dailyStartDate = System.currentTimeMillis()
            outcomeBaselineAt7837 = dailyStartDate
            isKilled = false
            killReason = ""
            killTime = 0L
            try {
                PipelineHealthCollector.labelInc("KILL_SWITCH_PAPER_DAILY_LATCH_REBASED_7857")
                ForensicLogger.lifecycle(
                    "KILL_SWITCH_PAPER_DAILY_LATCH_REBASED_7857",
                    "schema=$storedSchema7843 liveEquity=$currentBalance priorDaily=$priorDaily7857 " +
                        "priorReason=${priorReason7857.take(80)} action=rebase_daily_loss_to_live_equity",
                )
            } catch (_: Throwable) {}
        }
        
        if (storedSchema7843 < 7837 && isKilled &&
            staleBaselineLatch7837(killReason, killTime, dailyStartDate, peakBalance, dailyStartBalance, currentBalance)) {
            PipelineHealthCollector.labelInc("KILL_SWITCH_STALE_BASELINE_LATCH_REPAIRED_7837")
            ErrorLogger.info("KillSwitch", "Retired legacy latch against a replaced baseline: $killReason")
            isKilled = false; killReason = ""; killTime = 0L
        }

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
    @Synchronized
    fun recordTrade(
        context: Context,
        pnlPct: Double,
        currentBalance: Double,
        maxDailyLossPct: Double = DEFAULT_MAX_DAILY_LOSS_PCT,
        maxDrawdownPct: Double = DEFAULT_MAX_DRAWDOWN_PCT,
        maxConsecutiveLosses: Int = DEFAULT_MAX_CONSECUTIVE_LOSSES,
        paperOutcome7835: Boolean = isPaperMode,
    ): Boolean {
        
        // V5.7.8: Paper mode — never kill, always continue
        if (paperOutcome7835) {
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
        // V5.9.729 — asymmetric scratch band: only real losses count.
        // Fee-drag scratches (-2% < pnl < +0.5%) don't bump the streak,
        // wins reset it to zero. Previously every -0.04 SOL stale-price
        // rug-escape was incrementing the "20 consec losses" badge,
        // freaking the operator out and (more importantly) tripping
        // the consec-loss kill switch on what was actually fee noise.
        when {
            pnlPct <= -2.0 -> consecutiveLosses++
            pnlPct >= 0.5  -> consecutiveLosses = 0
            else           -> { /* scratch — streak unchanged */ }
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
            sizeDownNotice7864("MAX_DAILY_LOSS",
                "Daily loss ${dailyPnlPct.toInt()}% exceeded limit -${maxDailyLossPct.toInt()}%")
        }
        
        // 2. Max Drawdown
        val drawdownPct = if (peakBalance > 0) {
            ((peakBalance - currentBalance) / peakBalance) * 100
        } else 0.0
        
        if (drawdownPct >= maxDrawdownPct) {
            sizeDownNotice7864("MAX_DRAWDOWN",
                "Drawdown ${drawdownPct.toInt()}% exceeded limit ${maxDrawdownPct.toInt()}%")
        }
        
        // 3. Max Consecutive Losses
        if (consecutiveLosses >= maxConsecutiveLosses) {
            sizeDownNotice7864("MAX_CONSECUTIVE_LOSSES",
                "$consecutiveLosses consecutive losses exceeded limit $maxConsecutiveLosses")
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
        
        if (isKilled && !computedLimitLatch7864(killReason)) {
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
        
        val notes7864 = ArrayList<String>(3)
        if (dailyPnlPct <= -maxDailyLossPct * 0.9) {
            notes7864 += "DAILY_LOSS ${dailyPnlPct.toInt()}%/-${maxDailyLossPct.toInt()}%"
        }
        
        // Check drawdown
        val drawdownPct = if (peakBalance > 0) {
            ((peakBalance - currentBalance) / peakBalance) * 100
        } else 0.0
        
        if (drawdownPct >= maxDrawdownPct * 0.9) {
            notes7864 += "DD ${drawdownPct.toInt()}%/${maxDrawdownPct.toInt()}%"
        }
        
        // Check consecutive losses
        if (consecutiveLosses >= maxConsecutiveLosses - 1) {
            notes7864 += "LOSSES $consecutiveLosses/$maxConsecutiveLosses"
        }
        
        // Limits reached are priced into size by LiveRiskPolicy7807; never a halt.
        return if (notes7864.isEmpty()) Pair(true, "OK")
        else Pair(true, "SIZE_DOWN_NOT_HALT_7864: ${notes7864.joinToString(" | ")}")
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
        outcomeBaselineAt7837 = hourStart
        
        save(context)
        ErrorLogger.info("KillSwitch", "Reset with balance $${newBalance.toInt()}")
    }
    
    // ════════════════════════════════════════════════════════════════
    // Private helpers
    // ════════════════════════════════════════════════════════════════
    
    private fun sizeDownNotice7864(reason: String, details: String) {
        try { PipelineHealthCollector.labelInc("KILL_SWITCH_LIMIT_SIZE_DOWN_7864:$reason") } catch (_: Throwable) {}
        ErrorLogger.warn("KillSwitch", "⚠️ $reason: $details — sizing down (LiveRiskPolicy7807), not halting")
        onWarning?.invoke("$reason: $details (size down)")
    }

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
            putInt("environment_schema", 7864)
            putLong("outcome_baseline_at_7837", outcomeBaselineAt7837)
            putStringSet("canonical_outcomes_7835", canonicalOutcomes7835.toSet())
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

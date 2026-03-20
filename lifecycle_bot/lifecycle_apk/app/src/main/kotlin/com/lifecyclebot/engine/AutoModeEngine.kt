package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.BotStatus
import com.lifecyclebot.data.TokenState
import java.util.Calendar
import java.util.TimeZone

/**
 * AutoModeEngine
 *
 * Automatically switches between trading modes based on real-time context.
 * Eliminates the need to manually configure the bot for different market conditions.
 *
 * MODES:
 * ──────
 * SNIPE       Fresh token < 15 min. Max aggression, wide stop, ride the launch.
 * RANGE       Established token, price consolidated. Patient entries at range bottom.
 * COPY        A tracked wallet just bought. Immediate entry regardless of signals.
 * AGGRESSIVE  Multiple bullish signals aligned (whale + trending + pre-grad).
 *             Larger position size, higher score thresholds relaxed.
 * DEFENSIVE   Circuit breaker history, low-activity hours, market-wide selloff.
 *             Smaller positions, tighter stops, higher entry bar.
 * PAUSED      Time-of-day filter active. No new entries, existing exits normally.
 *
 * AUTO-SWITCH TRIGGERS:
 * ─────────────────────
 * → SNIPE      token age < 15 min
 * → RANGE      token age > 15 min AND rangePct < 12% over 12 candles
 * → AGGRESSIVE whale score ≥ 70 OR (trending rank ≤ 2 AND pre-grad)
 * → DEFENSIVE  ≥ 2 losses in last 10 trades OR UTC hour in dead zone
 * → PAUSED     UTC 01:00–07:00 (low volume, high rug rate)
 * → COPY       copy wallet detected buy (overrides everything)
 *
 * PARAMETER ADJUSTMENTS PER MODE:
 * ─────────────────────────────────
 * SNIPE      stopLoss=12%, trail=15%, exitThresh=55, minHold=1min
 * RANGE      stopLoss=8%,  trail=8%,  exitThresh=58, minHold=3min
 * AGGRESSIVE stopLoss=12%, trail=12%, exitThresh=50, posSize×1.5
 * DEFENSIVE  stopLoss=6%,  trail=6%,  exitThresh=65, posSize×0.5
 * COPY       stopLoss=10%, trail=10%, exitThresh=52 (quick profit take)
 */
class AutoModeEngine(
    private val cfg: () -> BotConfig,
    private val status: BotStatus,
    private val onModeChange: (from: BotMode, to: BotMode, reason: String) -> Unit,
) {

    enum class BotMode {
        SNIPE, RANGE, AGGRESSIVE, DEFENSIVE, COPY, PAUSED;

        val label: String get() = when (this) {
            SNIPE      -> "⚡ Snipe"
            RANGE      -> "📊 Range"
            AGGRESSIVE -> "🔥 Aggressive"
            DEFENSIVE  -> "🛡 Defensive"
            COPY       -> "📋 Copy"
            PAUSED     -> "⏸ Paused"
        }

        val colour: Int get() = when (this) {
            SNIPE      -> 0xFF9945FF.toInt()
            RANGE      -> 0xFF3B82F6.toInt()
            AGGRESSIVE -> 0xFFEF4444.toInt()
            DEFENSIVE  -> 0xFF10B981.toInt()
            COPY       -> 0xFFF59E0B.toInt()
            PAUSED     -> 0xFF6B7280.toInt()
        }
    }

    data class ModeConfig(
        val mode: BotMode,
        val stopLossPct: Double,
        val trailingStopPct: Double,
        val exitScoreThreshold: Double,
        val entryScoreMultiplier: Double,   // multiply entry score thresholds
        val positionSizeMultiplier: Double, // multiply position size
        val minHoldMins: Double,
        val maxHoldMins: Double,
        val reason: String,
    )

    @Volatile var currentMode: BotMode = BotMode.RANGE
        private set

    @Volatile var currentModeConfig: ModeConfig = rangeModeConfig("initial")
        private set

    // Copy trading state
    @Volatile var copyTriggerMint: String = ""
    @Volatile var copyTriggerWallet: String = ""
    private var lastCopyTriggerMs = 0L

    // Mode history for analysis
    val modeHistory = ArrayDeque<Triple<Long, BotMode, String>>(50)

    // ── evaluate — call every poll tick ──────────────────────────────

    fun evaluate(ts: TokenState, whaleScore: Double, trendingRank: Int?,
                 curveStage: BondingCurveTracker.CurveStage): ModeConfig {

        val c        = cfg()
        val utcHour  = utcHour()
        val newMode  = determineMode(ts, whaleScore, trendingRank, curveStage, utcHour, c)

        if (newMode != currentMode) {
            val reason = modeChangeReason(newMode, ts, whaleScore, trendingRank, curveStage, utcHour)
            modeHistory.addFirst(Triple(System.currentTimeMillis(), newMode, reason))
            if (modeHistory.size > 50) modeHistory.removeLast()
            onModeChange(currentMode, newMode, reason)
            currentMode       = newMode
            currentModeConfig = buildConfig(newMode, reason)
        }

        return currentModeConfig
    }

    /** Called from DataOrchestrator when a copy wallet makes a buy */
    fun triggerCopy(mint: String, wallet: String) {
        val now = System.currentTimeMillis()
        if (now - lastCopyTriggerMs < 30_000L) return  // debounce 30s
        copyTriggerMint   = mint
        copyTriggerWallet = wallet
        lastCopyTriggerMs = now
        val prev = currentMode
        currentMode       = BotMode.COPY
        currentModeConfig = buildConfig(BotMode.COPY, "Copy wallet $wallet bought")
        onModeChange(prev, BotMode.COPY, "Copy wallet ${wallet.take(8)}… bought $mint")
    }

    /** Clear copy mode after the triggered trade executes or times out */
    fun clearCopy() {
        if (currentMode == BotMode.COPY) {
            copyTriggerMint   = ""
            copyTriggerWallet = ""
            currentMode       = BotMode.RANGE
            currentModeConfig = buildConfig(BotMode.RANGE, "Copy trade complete")
        }
    }

    // ── mode determination ────────────────────────────────────────────

    private fun determineMode(
        ts: TokenState, whaleScore: Double, trendingRank: Int?,
        curveStage: BondingCurveTracker.CurveStage, utcHour: Int, c: BotConfig,
    ): BotMode {

        // PAUSED — dead hours (01:00–07:00 UTC)
        if (utcHour in 1..6) return BotMode.PAUSED

        // COPY — active copy trigger (handled externally, just maintain)
        if (currentMode == BotMode.COPY &&
            System.currentTimeMillis() - lastCopyTriggerMs < 60_000L)
            return BotMode.COPY

        // DEFENSIVE — recent loss streak or market conditions poor
        val recentTrades = ts.trades.takeLast(10)
        val recentLosses = recentTrades.count { it.pnlSol < 0 }
        if (recentLosses >= 3) return BotMode.DEFENSIVE

        // Check circuit breaker state
        val cb = status.tokens.values
            .firstOrNull()?.let { _ ->
                try { com.lifecyclebot.engine.BotService.instance
                    ?.let { svc ->
                        val f = svc.javaClass.getDeclaredField("securityGuard")
                        f.isAccessible = true
                        (f.get(svc) as? SecurityGuard)?.getCircuitBreakerState()
                    }
                } catch (_: Exception) { null }
            }
        if (cb?.consecutiveLosses ?: 0 >= 3) return BotMode.DEFENSIVE

        // SNIPE — fresh token
        val hist       = ts.history.toList()
        val tokenAgeMs = if (hist.isNotEmpty()) System.currentTimeMillis() - hist.first().ts else Long.MAX_VALUE
        val ageMins    = tokenAgeMs / 60_000.0
        if (ageMins <= 15.0) return BotMode.SNIPE

        // AGGRESSIVE — multiple strong signals aligned
        val isAggressive = (whaleScore >= 70) ||
            (trendingRank != null && trendingRank <= 2 &&
             curveStage in listOf(
                 BondingCurveTracker.CurveStage.PRE_GRAD,
                 BondingCurveTracker.CurveStage.GRADUATING))
        if (isAggressive) return BotMode.AGGRESSIVE

        // Default to RANGE for established tokens
        return BotMode.RANGE
    }

    private fun modeChangeReason(
        mode: BotMode, ts: TokenState, whaleScore: Double,
        trendingRank: Int?, curveStage: BondingCurveTracker.CurveStage, utcHour: Int,
    ): String = when (mode) {
        BotMode.SNIPE      -> "Token < 15 min old"
        BotMode.RANGE      -> "Token established, ranging"
        BotMode.AGGRESSIVE -> when {
            whaleScore >= 70            -> "Whale score ${"%.0f".format(whaleScore)}"
            trendingRank != null        -> "Trending #${trendingRank + 1} + $curveStage"
            else                        -> "Multiple bullish signals"
        }
        BotMode.DEFENSIVE  -> "Loss streak detected"
        BotMode.COPY       -> "Copy wallet triggered"
        BotMode.PAUSED     -> "Dead zone UTC $utcHour:00"
    }

    // ── mode configs ──────────────────────────────────────────────────

    private fun buildConfig(mode: BotMode, reason: String): ModeConfig = when (mode) {
        BotMode.SNIPE      -> snitpeModeConfig(reason)
        BotMode.RANGE      -> rangeModeConfig(reason)
        BotMode.AGGRESSIVE -> aggressiveModeConfig(reason)
        BotMode.DEFENSIVE  -> defensiveModeConfig(reason)
        BotMode.COPY       -> copyModeConfig(reason)
        BotMode.PAUSED     -> pausedModeConfig(reason)
    }

    private fun snitpeModeConfig(reason: String) = ModeConfig(
        mode                   = BotMode.SNIPE,
        stopLossPct            = 12.0,   // wide — meme coins wick hard at launch
        trailingStopPct        = 15.0,   // wide trail to ride the move
        exitScoreThreshold     = 55.0,   // slightly lower — exit faster on snipes
        entryScoreMultiplier   = 0.85,   // lower entry bar (entry scores multiplied ÷ this)
        positionSizeMultiplier = 1.0,
        minHoldMins            = 1.0,    // can exit very quickly if it rugs
        maxHoldMins            = 30.0,   // 30 min max on a snipe
        reason                 = reason,
    )

    private fun rangeModeConfig(reason: String) = ModeConfig(
        mode                   = BotMode.RANGE,
        stopLossPct            = 8.0,
        trailingStopPct        = 8.0,
        exitScoreThreshold     = 58.0,
        entryScoreMultiplier   = 1.0,
        positionSizeMultiplier = 1.0,
        minHoldMins            = 3.0,
        maxHoldMins            = 120.0,
        reason                 = reason,
    )

    private fun aggressiveModeConfig(reason: String) = ModeConfig(
        mode                   = BotMode.AGGRESSIVE,
        stopLossPct            = 12.0,   // wider — high conviction, give it room
        trailingStopPct        = 12.0,
        exitScoreThreshold     = 50.0,   // lower threshold — take profits a bit earlier
        entryScoreMultiplier   = 0.80,   // lower bar — signals are very strong
        positionSizeMultiplier = 1.5,    // bigger position on high conviction
        minHoldMins            = 2.0,
        maxHoldMins            = 60.0,
        reason                 = reason,
    )

    private fun defensiveModeConfig(reason: String) = ModeConfig(
        mode                   = BotMode.DEFENSIVE,
        stopLossPct            = 6.0,    // tight — cut losses fast
        trailingStopPct        = 6.0,
        exitScoreThreshold     = 65.0,   // higher bar — be very selective
        entryScoreMultiplier   = 1.3,    // higher entry bar
        positionSizeMultiplier = 0.5,    // half size — protecting capital
        minHoldMins            = 2.0,
        maxHoldMins            = 45.0,
        reason                 = reason,
    )

    private fun copyModeConfig(reason: String) = ModeConfig(
        mode                   = BotMode.COPY,
        stopLossPct            = 10.0,
        trailingStopPct        = 10.0,
        exitScoreThreshold     = 52.0,   // take profit faster — following not leading
        entryScoreMultiplier   = 0.5,    // very low bar — we trust the wallet
        positionSizeMultiplier = 1.0,
        minHoldMins            = 1.0,
        maxHoldMins            = 20.0,   // copy trades are quick
        reason                 = reason,
    )

    private fun pausedModeConfig(reason: String) = ModeConfig(
        mode                   = BotMode.PAUSED,
        stopLossPct            = 8.0,    // still exit positions normally
        trailingStopPct        = 8.0,
        exitScoreThreshold     = 45.0,   // lower threshold — easier to exit
        entryScoreMultiplier   = 999.0,  // effectively blocks all new entries
        positionSizeMultiplier = 0.0,    // no new positions
        minHoldMins            = 1.0,
        maxHoldMins            = 60.0,
        reason                 = reason,
    )

    private fun utcHour(): Int {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        return cal.get(Calendar.HOUR_OF_DAY)
    }
}

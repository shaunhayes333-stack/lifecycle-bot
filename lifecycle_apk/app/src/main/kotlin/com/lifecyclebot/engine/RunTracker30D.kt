package com.lifecyclebot.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * RunTracker30D — 30-Day Proof Tracking Module
 * ══════════════════════════════════════════════════════════════════════════════
 * 
 * AATe EMERGENT PATCH PACKAGE - SECTION 2
 * 
 * Tracks a complete 30-day trading run with:
 *   - Balance progression (equity curve)
 *   - Trade outcomes (wins/losses/scratches)
 *   - Peak/drawdown tracking
 *   - Intelligence metrics progression
 *   - System integrity scoring
 * 
 * Produces investor-grade outputs for verification.
 * 
 * CONSTRAINTS:
 *   - DO_NOT_MODIFY_CORE_LOGIC: true
 *   - DO_NOT_CHANGE_THRESHOLDS: true
 *   - ADD_ONLY: true
 */
object RunTracker30D {
    
    private const val TAG = "RunTracker30D"
    private const val PREFS_NAME = "run_tracker_30d"
    
    // ═══════════════════════════════════════════════════════════════════════
    // RUN STATE
    // ═══════════════════════════════════════════════════════════════════════
    
    @Volatile var startTime: Long = 0L
    @Volatile var startBalance: Double = 0.0
    @Volatile var currentBalance: Double = 0.0
    
    @Volatile var totalTrades: Int = 0
    @Volatile var wins: Int = 0
    @Volatile var losses: Int = 0
    @Volatile var scratches: Int = 0

    // V5.9.369 — per-asset-class trade counters. RunTracker30D was global,
    // so Markets-layer trades (50 stocks @ 0% WR) were dragging the
    // Meme Live Readiness WR down to 35%. Now each trader passes its
    // assetClass tag and we increment the matching bucket; existing
    // global counters are preserved for back-compat (lifetime run-level
    // metrics still aggregate across all assets).
    data class AssetBucket(
        @Volatile var trades: Int = 0,
        @Volatile var wins: Int = 0,
        @Volatile var losses: Int = 0,
        @Volatile var scratches: Int = 0,
        @Volatile var pnlSol: Double = 0.0,
    ) {
        fun winRate(): Double {
            val decisive = wins + losses
            return if (decisive > 0) wins * 100.0 / decisive else 0.0
        }
    }
    val memeBucket   = AssetBucket()
    val altsBucket   = AssetBucket()
    val perpsBucket  = AssetBucket()
    val stocksBucket = AssetBucket()
    val forexBucket  = AssetBucket()
    val metalsBucket = AssetBucket()
    val commodBucket = AssetBucket()

    /**
     * V5.9.432 — unified per-lane stats accessor so UI (Alt Trader card,
     * Live Readiness tab, 30D tracker) can all read from ONE source instead
     * of drifting apart across three stores.
     *   keys: "MEME" | "ALT"/"ALTS"/"CRYPTO_ALT" | "PERPS" | "STOCKS"
     *         "FOREX" | "METALS" | "COMMODITIES"
     */
    fun getLaneStats(lane: String): Map<String, Any> {
        val b = bucketFor(lane)
        val decisive = b.wins + b.losses
        return mapOf(
            "trades"    to b.trades,
            "wins"      to b.wins,
            "losses"    to b.losses,
            "scratches" to b.scratches,
            "winRate"   to b.winRate(),
            "pnlSol"    to b.pnlSol,
            "decisive"  to decisive,
        )
    }
    
    @Volatile var peakBalance: Double = 0.0
    @Volatile var maxDrawdown: Double = 0.0  // Stored as negative percentage
    
    // V5.6.15: Track actual realized P&L in SOL, not accumulated percentages
    @Volatile var totalRealizedPnlSol: Double = 0.0
    @Volatile var bestTradePnlPct: Double = 0.0
    @Volatile var worstTradePnlPct: Double = 0.0
    
    // Equity curve: List of (timestamp, balance) pairs
    private val equityCurve = mutableListOf<Pair<Long, Double>>()
    
    // Daily summaries
    private val dailySummaries = mutableListOf<DailySummary>()
    
    // Trade log
    private val tradeLog = mutableListOf<TradeEntry>()
    
    // Intelligence metrics
    val metrics = IntelligenceMetrics()
    
    // Context for file operations
    private var ctx: Context? = null
    
    // ═══════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════
    
    data class DailySummary(
        val date: String,
        val startBalance: Double,
        val endBalance: Double,
        val trades: Int,
        val wins: Int,
        val losses: Int,
        val pnlPct: Double,
        val maxDrawdown: Double,
    )
    
    data class TradeEntry(
        val timestamp: Long,
        val symbol: String,
        val mint: String,
        val entryPrice: Double,
        val exitPrice: Double,
        val sizeSol: Double,
        val pnlPct: Double,
        val classification: String,  // WIN, LOSS, SCRATCH
        val holdTimeSec: Long,
        val mode: String,
        val score: Int,
        val confidence: Int,
        val decision: String,
    )
    
    // ═══════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Initialize the run tracker with context.
     * Call at app start.
     */
    fun init(context: Context) {
        ctx = context.applicationContext
        load()
        ErrorLogger.info(TAG, "📊 RunTracker30D initialized: day ${getCurrentDay()} of 30")
    }
    
    /**
     * Start a new 30-day run.
     * Call when user initiates a tracked run.
     */
    fun startRun(initialBalance: Double) {
        if (startTime == 0L) {
            startTime = System.currentTimeMillis()
            startBalance = initialBalance
            currentBalance = initialBalance
            peakBalance = initialBalance
            
            // Record initial equity point
            equityCurve.add(startTime to initialBalance)
            
            save()
            ErrorLogger.info(TAG, "🚀 30-Day Run Started | Balance: \$${initialBalance.toInt()}")
        }
    }
    
    /**
     * Check if a run is active.
     */
    fun isRunActive(): Boolean = startTime > 0L
    
    /**
     * Get current day of the run (1-30).
     */
    fun getCurrentDay(): Int {
        if (startTime == 0L) return 0
        val elapsedMs = System.currentTimeMillis() - startTime
        val elapsedDays = (elapsedMs / (24 * 60 * 60 * 1000L)).toInt() + 1
        return elapsedDays.coerceIn(1, 30)
    }
    
    /**
     * Check if run is complete (30 days elapsed).
     */
    fun isRunComplete(): Boolean {
        if (startTime == 0L) return false
        return getCurrentDay() > 30
    }
    
    /**
     * V5.6.15: Sync balance with actual paper wallet balance.
     * Call this periodically to ensure RunTracker stays in sync.
     */
    fun syncBalance(actualBalance: Double) {
        if (!isRunActive()) return
        
        // Only sync if there's a significant difference (>5%)
        val diff = kotlin.math.abs(currentBalance - actualBalance)
        val pctDiff = if (currentBalance > 0) diff / currentBalance * 100 else 100.0
        
        if (pctDiff > 5.0) {
            ErrorLogger.info(TAG, "📊 Balance sync: ${formatSol(currentBalance)} → ${formatSol(actualBalance)} (${pctDiff.toInt()}% drift)")
            currentBalance = actualBalance
            updateDrawdown(currentBalance)
            save()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // TRADE TRACKING
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * FIX_4 — CLASSIFICATION STANDARD (+/- 5.0%)
     * Classify trade outcome based on P&L percentage.
     */
    fun classifyTrade(pnlPct: Double): String {
        return when {
            // V5.9.729 — align with TradeJournal/TradeHistoryStore asymmetric
            // bands. Previous ±0.1% over-corrected from the 1% scratch flood
            // by going so narrow that every fee-drag exit (-1.9% from
            // STALE_LIVE_PRICE_RUG_ESCAPE, -0.2% from CASHGEN flat closes,
            // -0.5% from BLUECHIP_STOP_LOSS hair-trigger) was bucketed as a
            // real loss. Operator screenshot: 30-Day Proof Run showing
            // W/L/S = 2 / 20 / 0 — zero scratches across 22 trades is
            // impossible for a paper bot running through volatile memes.
            //
            // Asymmetric bands matching every other classifier in the codebase:
            //   WIN     >= +0.5%   (meaningful gain after fees + slippage)
            //   LOSS    <= -2.0%   (meaningful drawdown beyond round-trip cost)
            //   SCRATCH everything in between
            pnlPct >= 0.5 -> "WIN"
            pnlPct <= -2.0 -> "LOSS"
            else -> "SCRATCH"
        }
    }
    
    /**
     * Record a completed trade.
     */
    fun recordTrade(
        symbol: String,
        mint: String,
        entryPrice: Double,
        exitPrice: Double,
        sizeSol: Double,
        pnlPct: Double,
        holdTimeSec: Long,
        mode: String,
        score: Int,
        confidence: Int,
        decision: String,
        assetClass: String? = null,   // V5.9.369: "MEME"|"ALT"|"PERP"|"STOCK"|"FOREX"|"METAL"|"COMMODITY"; auto-inferred from mode if null
    ) {
        // FIX_1 — HOLD TIME SANITY
        var sanitizedHoldTime = holdTimeSec.coerceAtLeast(0)
        if (sanitizedHoldTime > 86400) {
            ErrorLogger.warn(TAG, "HOLD_TIME_SANITY_FAIL: $sanitizedHoldTime sec → reset=0")
            sanitizedHoldTime = 0
        }
        
        val classification = classifyTrade(pnlPct)
        
        // Update counters
        totalTrades++
        when (classification) {
            "WIN" -> wins++
            "LOSS" -> losses++
            "SCRATCH" -> scratches++
        }

        // V5.9.369 — increment per-asset-class bucket so MEME readiness
        // doesn't pull stock losses into its WR calc.
        try {
            val bucket = bucketFor(assetClass ?: inferAssetClassFromMode(mode))
            bucket.trades++
            when (classification) {
                "WIN" -> bucket.wins++
                "LOSS" -> bucket.losses++
                "SCRATCH" -> bucket.scratches++
            }
        } catch (_: Exception) {}
        
        // V5.6.15: Track realized P&L in SOL (not accumulated percentages)
        // V5.9.56: SANITY CAP — without this, a single glitchy trade carrying a
        // huge pnlPct (e.g. micro-cap decimal-shift feeding 9,999,999 %) would
        // nuke the accumulator. User saw "+161,980,464 SOL realized" on the
        // 30-Day Proof Run due to exactly this. Same cap we already apply to
        // bestTradePnlPct / worstTradePnlPct is now applied to the value that
        // feeds the accumulator.
        val sanitizedPnlPct = pnlPct.coerceIn(-100.0, 10000.0)
        val realizedPnlSol = sizeSol * (sanitizedPnlPct / 100.0)
        totalRealizedPnlSol += realizedPnlSol

        // V5.9.369 — also write realized PnL to the matching asset bucket
        try {
            bucketFor(assetClass ?: inferAssetClassFromMode(mode)).pnlSol += realizedPnlSol
        } catch (_: Exception) {}
        
        // Track best/worst trade percentages (capped for sanity)
        val cappedPnlPct = pnlPct.coerceIn(-100.0, 10000.0)  // Cap at -100% to +10000%
        if (cappedPnlPct > bestTradePnlPct) bestTradePnlPct = cappedPnlPct
        if (cappedPnlPct < worstTradePnlPct) worstTradePnlPct = cappedPnlPct
        
        // V5.6.15: Update balance properly - add realized P&L
        currentBalance += realizedPnlSol
        
        // Sanity check: balance should never go negative
        if (currentBalance < 0) {
            ErrorLogger.warn(TAG, "Balance went negative ($currentBalance), resetting to 0")
            currentBalance = 0.0
        }
        
        // Update peak and drawdown
        updateDrawdown(currentBalance)
        
        // Record equity point (limit to 1000 points to prevent memory bloat)
        if (equityCurve.size < 1000) {
            equityCurve.add(System.currentTimeMillis() to currentBalance)
        } else if (totalTrades % 10 == 0) {
            // Sample every 10th trade after 1000
            equityCurve.add(System.currentTimeMillis() to currentBalance)
        }
        
        // Log trade (limit to last 500 trades to prevent memory bloat)
        val entry = TradeEntry(
            timestamp = System.currentTimeMillis(),
            symbol = symbol,
            mint = mint,
            entryPrice = entryPrice,
            exitPrice = exitPrice,
            sizeSol = sizeSol,
            pnlPct = cappedPnlPct,
            classification = classification,
            holdTimeSec = sanitizedHoldTime,
            mode = mode,
            score = score,
            confidence = confidence,
            decision = decision,
        )
        tradeLog.add(entry)
        if (tradeLog.size > 500) {
            tradeLog.removeAt(0)  // Remove oldest
        }
        
        // Update metrics
        metrics.updateFromTrade(cappedPnlPct, classification, confidence.toDouble())
        
        // Save state
        save()
        
        val emoji = when (classification) {
            "WIN" -> "✅"
            "LOSS" -> "❌"
            else -> "➖"
        }
        ErrorLogger.info(TAG, "$emoji Trade #$totalTrades: $symbol ${cappedPnlPct.toInt()}% → $classification | " +
            "Total: W=$wins L=$losses S=$scratches | Balance: ${formatSol(currentBalance)}")
    }
    
    /**
     * Update drawdown tracking.
     * V5.7.8: Disabled — drawdown tracking removed from 30-day proof run.
     * Peak balance still tracked for reference but maxDrawdown is not updated.
     */
    private fun updateDrawdown(balance: Double) {
        // Update peak only — drawdown calculation disabled
        peakBalance = maxOf(peakBalance, balance)
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // REPORTING
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Generate daily report string.
     */
    fun dailyReport(): String {
        // V5.6.16: Win rate excludes scratches (only meaningful trades count)
        val meaningfulTrades = wins + losses
        val winRate = if (meaningfulTrades > 0) {
            (wins * 100 / meaningfulTrades)
        } else 0
        
        val returnPct = if (startBalance > 0) {
            ((currentBalance - startBalance) / startBalance) * 100
        } else 0.0
        
        return """
═══════════════════════════════════════════════════════════════
                    AATe 30-DAY RUN - DAY ${getCurrentDay()}
═══════════════════════════════════════════════════════════════

BALANCE
  Start:   ${formatSol(startBalance)}
  Current: ${formatSol(currentBalance)}
  Return:  ${if (returnPct >= 0) "+" else ""}${String.format("%.2f", returnPct)}%

TRADES
  Total:     $totalTrades
  Wins:      $wins
  Losses:    $losses
  Scratches: $scratches (excluded from win rate)
  Win Rate:  $winRate% (of ${meaningfulTrades} meaningful trades)

RISK
  Peak Balance:  ${formatSol(peakBalance)}
  Best Trade:    ${if (bestTradePnlPct >= 0) "+" else ""}${String.format("%.1f", bestTradePnlPct)}%
  Worst Trade:   ${String.format("%.1f", worstTradePnlPct)}%

INTELLIGENCE
  Learning:    ${String.format("%.1f", metrics.learning)}%
  Confidence:  ${String.format("%.1f", metrics.confidence)}%
  Accuracy:    ${String.format("%.1f", metrics.decisionAccuracy)}%

SYSTEM
  Integrity:   ${integrityScore()} / 100
  Uptime:      ${getUptimeString()}

═══════════════════════════════════════════════════════════════
        """.trimIndent()
    }
    
    /**
     * Generate final report as Map (for JSON export).
     */
    fun finalReport(): Map<String, Any> {
        val returnPct = if (startBalance > 0) {
            ((currentBalance - startBalance) / startBalance) * 100
        } else 0.0
        
        // V5.6.16: Win rate excludes scratches
        val meaningfulTrades = wins + losses
        val winRate = if (meaningfulTrades > 0) {
            wins.toDouble() / meaningfulTrades
        } else 0.0
        
        return mapOf(
            "runStartTime" to startTime,
            "runEndTime" to System.currentTimeMillis(),
            "totalDays" to getCurrentDay(),
            "startBalance" to startBalance,
            "endBalance" to currentBalance,
            "returnPct" to returnPct,
            "totalTrades" to totalTrades,
            "meaningfulTrades" to meaningfulTrades,
            "wins" to wins,
            "losses" to losses,
            "scratches" to scratches,
            "winRate" to winRate,
            "maxDrawdown" to maxDrawdown,
            "peakBalance" to peakBalance,
            "bestTradePnl" to bestTradePnlPct,
            "worstTradePnl" to worstTradePnlPct,
            "totalRealizedPnlSol" to totalRealizedPnlSol,
            "avgPnlPerTrade" to (if (meaningfulTrades > 0) totalRealizedPnlSol / meaningfulTrades else 0.0),
            "integrityScore" to integrityScore(),
            "learningProgress" to metrics.learning,
            "confidenceLevel" to metrics.confidence,
            "decisionAccuracy" to metrics.decisionAccuracy,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // SYSTEM INTEGRITY SCORE (SECTION 5)
    // ═══════════════════════════════════════════════════════════════════════
    
    @Volatile var executionFailures: Int = 0
    @Volatile var missedTrades: Int = 0
    
    /**
     * Calculate system integrity score (0-100).
     */
    fun integrityScore(): Int {
        var score = 100
        
        // Deduct for execution failures
        score -= executionFailures * 5
        
        // Deduct for missed trades
        score -= missedTrades * 2
        
        // Add learning progress bonus
        score += metrics.learning.toInt()
        
        // Add confidence bonus
        score += (metrics.confidence / 10).toInt()
        
        return score.coerceIn(0, 100)
    }
    
    /**
     * Record an execution failure.
     */
    fun recordExecutionFailure() {
        executionFailures++
        save()
    }
    
    /**
     * Record a missed trade opportunity.
     */
    fun recordMissedTrade() {
        missedTrades++
        save()
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // UI HELPERS
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Get header string for UI.
     */
    fun getHeaderString(): String {
        return if (isRunActive()) {
            "AATe — 30 DAY RUN (DAY ${getCurrentDay()})\nAutonomous Mode"
        } else {
            "AATe — Autonomous Trading Engine"
        }
    }
    
    /**
     * Get system score string for UI.
     */
    fun getSystemScoreString(): String {
        return "System Integrity: ${integrityScore()} / 100"
    }
    
    /**
     * Get learning string for UI.
     */
    fun getLearningString(): String {
        return "Learning: ${String.format("%.1f", metrics.learning)}%"
    }
    
    /**
     * Get equity label for UI.
     */
    fun getEquityLabel(): String {
        val returnPct = if (startBalance > 0) {
            ((currentBalance - startBalance) / startBalance) * 100
        } else 0.0
        val sign = if (returnPct >= 0) "+" else ""
        return "Balance Trend: $sign${String.format("%.1f", returnPct)}%"
    }
    
    /**
     * Get filter stats for UI.
     */
    fun getFilterStats(): String {
        val filtered = missedTrades + executionFailures
        return "Filtered: $filtered | Executed: $totalTrades"
    }
    
    /**
     * Get trade trace for detail view.
     */
    fun getTradeTrace(entry: TradeEntry): String {
        return "Score: ${entry.score} | Confidence: ${entry.confidence} | Decision: ${entry.decision}"
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // FILE OUTPUT (SECTION 9)
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Export daily report to file.
     */
    fun exportDailyReport() {
        val c = ctx ?: return
        try {
            val dir = File(c.filesDir, "reports")
            if (!dir.exists()) dir.mkdirs()
            
            val file = File(dir, "day.txt")
            file.writeText(dailyReport())
            
            ErrorLogger.info(TAG, "📄 Daily report exported to ${file.absolutePath}")
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Export daily report failed: ${e.message}", e)
        }
    }
    
    /**
     * Export final report to JSON.
     */
    fun exportFinalReport() {
        val c = ctx ?: return
        try {
            val dir = File(c.filesDir, "reports")
            if (!dir.exists()) dir.mkdirs()
            
            val report = finalReport()
            val json = JSONObject(report).toString(2)
            
            val file = File(dir, "final.json")
            file.writeText(json)
            
            ErrorLogger.info(TAG, "📄 Final report exported to ${file.absolutePath}")
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Export final report failed: ${e.message}", e)
        }
    }
    
    /**
     * Export equity curve to CSV.
     */
    fun exportEquityCurve() {
        val c = ctx ?: return
        try {
            val dir = File(c.filesDir, "reports")
            if (!dir.exists()) dir.mkdirs()
            
            val sb = StringBuilder()
            sb.appendLine("timestamp,balance")
            equityCurve.forEach { (ts, balance) ->
                sb.appendLine("$ts,$balance")
            }
            
            val file = File(dir, "equity.csv")
            file.writeText(sb.toString())
            
            ErrorLogger.info(TAG, "📄 Equity curve exported to ${file.absolutePath}")
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Export equity curve failed: ${e.message}", e)
        }
    }
    
    /**
     * Export trade log to CSV.
     */
    fun exportTradeLog() {
        val c = ctx ?: return
        try {
            val dir = File(c.filesDir, "reports")
            if (!dir.exists()) dir.mkdirs()
            
            val sb = StringBuilder()
            sb.appendLine("timestamp,symbol,mint,entryPrice,exitPrice,sizeSol,pnlPct,classification,holdTimeSec,mode,score,confidence,decision")
            tradeLog.forEach { t ->
                sb.appendLine("${t.timestamp},${t.symbol},${t.mint},${t.entryPrice},${t.exitPrice},${t.sizeSol},${t.pnlPct},${t.classification},${t.holdTimeSec},${t.mode},${t.score},${t.confidence},${t.decision}")
            }
            
            val file = File(dir, "trades.csv")
            file.writeText(sb.toString())
            
            ErrorLogger.info(TAG, "📄 Trade log exported to ${file.absolutePath}")
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Export trade log failed: ${e.message}", e)
        }
    }
    
    /**
     * Export all reports.
     */
    fun exportAllReports() {
        exportDailyReport()
        exportFinalReport()
        exportEquityCurve()
        exportTradeLog()
        ErrorLogger.info(TAG, "📦 All reports exported")
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun formatSol(sol: Double): String {
        return "${String.format("%.4f", sol)} SOL"
    }
    
    private fun getUptimeString(): String {
        if (startTime == 0L) return "Not started"
        val elapsedMs = System.currentTimeMillis() - startTime
        val days = elapsedMs / (24 * 60 * 60 * 1000L)
        val hours = (elapsedMs / (60 * 60 * 1000L)) % 24
        val minutes = (elapsedMs / (60 * 1000L)) % 60
        return "${days}d ${hours}h ${minutes}m"
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun save() {
        val c = ctx ?: return
        try {
            val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                putLong("startTime", startTime)
                putFloat("startBalance", startBalance.toFloat())
                putFloat("currentBalance", currentBalance.toFloat())
                putInt("totalTrades", totalTrades)
                putInt("wins", wins)
                putInt("losses", losses)
                putInt("scratches", scratches)
                putFloat("peakBalance", peakBalance.toFloat())
                putFloat("maxDrawdown", maxDrawdown.toFloat())
                putFloat("totalRealizedPnlSol", totalRealizedPnlSol.toFloat())
                putFloat("bestTradePnlPct", bestTradePnlPct.toFloat())
                putFloat("worstTradePnlPct", worstTradePnlPct.toFloat())
                putInt("executionFailures", executionFailures)
                putInt("missedTrades", missedTrades)
                putString("equityCurve", equityCurveToJson())
                putString("metrics", metrics.toJson().toString())
                apply()
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "save error: ${e.message}")
        }
    }
    
    private fun load() {
        val c = ctx ?: return
        try {
            val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            startTime = prefs.getLong("startTime", 0L)
            startBalance = prefs.getFloat("startBalance", 0f).toDouble()
            currentBalance = prefs.getFloat("currentBalance", 0f).toDouble()
            totalTrades = prefs.getInt("totalTrades", 0)
            wins = prefs.getInt("wins", 0)
            losses = prefs.getInt("losses", 0)
            scratches = prefs.getInt("scratches", 0)
            peakBalance = prefs.getFloat("peakBalance", 0f).toDouble()
            maxDrawdown = prefs.getFloat("maxDrawdown", 0f).toDouble()
            totalRealizedPnlSol = prefs.getFloat("totalRealizedPnlSol", 0f).toDouble()
            bestTradePnlPct = prefs.getFloat("bestTradePnlPct", 0f).toDouble()
            worstTradePnlPct = prefs.getFloat("worstTradePnlPct", 0f).toDouble()
            executionFailures = prefs.getInt("executionFailures", 0)
            missedTrades = prefs.getInt("missedTrades", 0)
            
            // Load equity curve
            val curveJson = prefs.getString("equityCurve", null)
            if (curveJson != null) {
                equityCurveFromJson(curveJson)
            }
            
            // Load metrics
            val metricsJson = prefs.getString("metrics", null)
            if (metricsJson != null) {
                metrics.fromJson(JSONObject(metricsJson))
            }
            
            ErrorLogger.info(TAG, "📂 Loaded: day=${getCurrentDay()} trades=$totalTrades W=$wins L=$losses")
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "load error: ${e.message}")
        }
    }
    
    private fun equityCurveToJson(): String {
        val arr = JSONArray()
        // Only keep last 1000 points to avoid memory issues
        equityCurve.takeLast(1000).forEach { (ts, bal) ->
            val obj = JSONObject()
            obj.put("t", ts)
            obj.put("b", bal)
            arr.put(obj)
        }
        return arr.toString()
    }
    
    private fun equityCurveFromJson(json: String) {
        try {
            equityCurve.clear()
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                equityCurve.add(obj.getLong("t") to obj.getDouble("b"))
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "equityCurveFromJson error: ${e.message}")
        }
    }
    
    /**
     * Reset the tracker (for new run).
     */
    fun reset() {
        startTime = 0L
        startBalance = 0.0
        currentBalance = 0.0
        totalTrades = 0
        wins = 0
        losses = 0
        scratches = 0
        peakBalance = 0.0
        maxDrawdown = 0.0
        totalRealizedPnlSol = 0.0
        bestTradePnlPct = 0.0
        worstTradePnlPct = 0.0
        executionFailures = 0
        missedTrades = 0
        equityCurve.clear()
        dailySummaries.clear()
        tradeLog.clear()
        metrics.reset()
        save()
        ErrorLogger.info(TAG, "🧹 RunTracker30D reset")
    }

    /**
     * V5.9.635 — Soft reset for "Clear Journal" UX.
     *
     * Zeros every TRADE-derived counter (totalTrades, wins/losses/scratches,
     * realized PnL, best/worst trade, all 7 AssetBuckets) so the main-screen
     * pills, lane breakdown M/A/P/S/FX/MT/CD, and Live Readiness gauges
     * align with an empty TradeHistoryStore.
     *
     * Preserves the proof-run identity (startTime, startBalance,
     * currentBalance, peakBalance, maxDrawdown, equityCurve, dailySummaries)
     * so the 30-Day Proof Run card keeps running on the same Day-X timeline
     * — operator just rebases their counters, not their run.
     */
    fun resetTradeStatsForJournalClear() {
        totalTrades = 0
        wins = 0
        losses = 0
        scratches = 0
        totalRealizedPnlSol = 0.0
        bestTradePnlPct = 0.0
        worstTradePnlPct = 0.0
        executionFailures = 0
        missedTrades = 0
        tradeLog.clear()
        // Per-lane buckets the M/A/P/S/FX/MT/CD breakdown reads from.
        memeBucket.trades = 0;   memeBucket.wins = 0;   memeBucket.losses = 0;   memeBucket.scratches = 0;   memeBucket.pnlSol = 0.0
        altsBucket.trades = 0;   altsBucket.wins = 0;   altsBucket.losses = 0;   altsBucket.scratches = 0;   altsBucket.pnlSol = 0.0
        perpsBucket.trades = 0;  perpsBucket.wins = 0;  perpsBucket.losses = 0;  perpsBucket.scratches = 0;  perpsBucket.pnlSol = 0.0
        stocksBucket.trades = 0; stocksBucket.wins = 0; stocksBucket.losses = 0; stocksBucket.scratches = 0; stocksBucket.pnlSol = 0.0
        forexBucket.trades = 0;  forexBucket.wins = 0;  forexBucket.losses = 0;  forexBucket.scratches = 0;  forexBucket.pnlSol = 0.0
        metalsBucket.trades = 0; metalsBucket.wins = 0; metalsBucket.losses = 0; metalsBucket.scratches = 0; metalsBucket.pnlSol = 0.0
        commodBucket.trades = 0; commodBucket.wins = 0; commodBucket.losses = 0; commodBucket.scratches = 0; commodBucket.pnlSol = 0.0
        save()
        ErrorLogger.info(TAG, "🧹 RunTracker30D trade stats reset (proof-run timeline preserved)")
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // V5.6.28f: SYNC FUNCTION - Align stats across different tracking systems
    // ═══════════════════════════════════════════════════════════════════════
    fun syncStatsFromTradeHistory() {
        try {
            val stats = TradeHistoryStore.getStats()
            
            // V5.9.56: Always recompute totalRealizedPnlSol from the canonical
            // per-trade pnlSol records. This fixes the "+161,980,464 SOL
            // realized" bug caused by legacy bad-data accumulation. Each
            // individual pnlSol is sanity-clamped so one glitch can't
            // explode the total.
            val recomputedPnl = try {
                TradeHistoryStore.getAllSells()
                    .sumOf { it.pnlSol.coerceIn(-1_000.0, 10_000.0) }
            } catch (_: Exception) {
                totalRealizedPnlSol
            }
            val driftSol = recomputedPnl - totalRealizedPnlSol
            if (kotlin.math.abs(driftSol) > 0.01) {
                ErrorLogger.info(TAG,
                    "📊 PnL RECOMPUTE: ${"%.4f".format(totalRealizedPnlSol)} → " +
                    "${"%.4f".format(recomputedPnl)} SOL (drift=${"%.4f".format(driftSol)})")
                totalRealizedPnlSol = recomputedPnl
                // currentBalance follows: recompute from the run's start.
                currentBalance = (startBalance + recomputedPnl).coerceAtLeast(0.0)
            }

            // Update RunTracker30D to match TradeHistoryStore totals
            // Only sync if TradeHistoryStore has more trades (it's the source of truth)
            if (stats.totalTrades > totalTrades) {
                ErrorLogger.info(TAG, "📊 SYNC: Aligning stats from TradeHistoryStore")
                ErrorLogger.info(TAG, "   Before: total=$totalTrades W=$wins L=$losses S=$scratches")
                
                totalTrades = stats.totalTrades
                wins = stats.totalWins
                losses = stats.totalLosses
                scratches = stats.totalScratches
                
                ErrorLogger.info(TAG, "   After:  total=$totalTrades W=$wins L=$losses S=$scratches")
            }
            save()
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Sync error: ${e.message}")
        }
    }

    // V5.9.369 — asset-class bucket helpers.
    private fun bucketFor(assetClass: String): AssetBucket = when (assetClass.uppercase()) {
        "MEME"      -> memeBucket
        "ALT", "ALTS", "CRYPTOALT", "CRYPTO_ALT" -> altsBucket
        "PERP", "PERPS"                           -> perpsBucket
        "STOCK", "STOCKS"                         -> stocksBucket
        "FOREX", "FX"                             -> forexBucket
        "METAL", "METALS"                         -> metalsBucket
        "COMMODITY", "COMMOD", "COMMODITIES"      -> commodBucket
        else                                       -> memeBucket   // legacy default
    }

    /**
     * Best-effort inference of asset class from the legacy `mode` string
     * (e.g. "Stocks_5x", "AltSpot", "PERPS_LONG", "TREASURY", "SHITCOIN").
     * Used when the trader hasn't been updated to pass assetClass yet.
     */
    private fun inferAssetClassFromMode(mode: String): String {
        val m = mode.uppercase()
        return when {
            m.startsWith("STOCK") || m.contains("STOCKS_") -> "STOCK"
            m.startsWith("FOREX") || m.contains("FOREX_")  -> "FOREX"
            m.startsWith("METAL") || m.contains("METALS_") -> "METAL"
            m.startsWith("COMMOD") || m.contains("COMMODITIES_") -> "COMMODITY"
            m.startsWith("PERP")  || m.contains("PERPS_")  -> "PERP"
            m.startsWith("ALT")   || m.contains("ALTSPOT") || m.contains("CRYPTO_ALT") -> "ALT"
            else                                             -> "MEME"
        }
    }
}


/**
 * IntelligenceMetrics — AI Learning Metrics (SECTION 3)
 * 
 * Tracks the progression of AI learning over time.
 */
class IntelligenceMetrics {
    @Volatile var learning: Double = 0.0        // Learning progress %
    @Volatile var confidence: Double = 0.0      // Confidence level %
    @Volatile var decisionAccuracy: Double = 0.0 // Decision accuracy %
    
    private var correctDecisions: Int = 0
    private var totalDecisions: Int = 0
    
    /**
     * Update metrics based on a trade outcome.
     */
    fun updateFromTrade(pnlPct: Double, classification: String, tradeConfidence: Double) {
        totalDecisions++
        
        // A "correct" decision is a WIN or a SCRATCH that was entered with high confidence
        // or a LOSS that was entered with low confidence (correctly uncertain)
        val isCorrect = when (classification) {
            "WIN" -> true
            "SCRATCH" -> tradeConfidence >= 50.0
            "LOSS" -> tradeConfidence < 50.0  // Low confidence entry that lost = correctly uncertain
            else -> false
        }
        
        if (isCorrect) correctDecisions++
        
        // Update accuracy
        decisionAccuracy = if (totalDecisions > 0) {
            (correctDecisions.toDouble() / totalDecisions) * 100
        } else 0.0
        
        // Update learning (logarithmic growth based on total trades)
        learning = minOf(100.0, kotlin.math.log10(totalDecisions.toDouble() + 1) * 30)
        
        // Update confidence (rolling average of trade confidences)
        confidence = (confidence * 0.9 + tradeConfidence * 0.1).coerceIn(0.0, 100.0)
    }
    
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("learning", learning)
            put("confidence", confidence)
            put("decisionAccuracy", decisionAccuracy)
            put("correctDecisions", correctDecisions)
            put("totalDecisions", totalDecisions)
        }
    }
    
    fun fromJson(json: JSONObject) {
        learning = json.optDouble("learning", 0.0)
        confidence = json.optDouble("confidence", 0.0)
        decisionAccuracy = json.optDouble("decisionAccuracy", 0.0)
        correctDecisions = json.optInt("correctDecisions", 0)
        totalDecisions = json.optInt("totalDecisions", 0)
    }
    
    fun reset() {
        learning = 0.0
        confidence = 0.0
        decisionAccuracy = 0.0
        correctDecisions = 0
        totalDecisions = 0
    }

    // -------------------------------------------------------------------------
    // V5.9.783 — CANONICAL BUS ADAPTER (operator audit item B)
    // -------------------------------------------------------------------------

    /**
     * Real adapter for CanonicalOutcomeBus. Subscribed via CanonicalSubscribers.
     *
     * Records every settled canonical outcome (rich OR incomplete) — RunTracker30D
     * is a LEDGER, not a strategy learner. The 30-day proof run needs to see every
     * settled trade regardless of feature richness so total realized PnL, WR, and
     * asset-class buckets reflect the full execution truth. featuresIncomplete only
     * blocks STRATEGY learners (BehaviorLearning patterns, AdaptiveLearningEngine).
     *
     * Dedup: recordTrade has no built-in dedup, so we use a small LRU here.
     */
    private val rt30CanonSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun onCanonicalOutcome(outcome: com.lifecyclebot.engine.CanonicalTradeOutcome) {
        try {
            if (outcome.result != com.lifecyclebot.engine.TradeResult.WIN &&
                outcome.result != com.lifecyclebot.engine.TradeResult.LOSS &&
                outcome.result != com.lifecyclebot.engine.TradeResult.BREAKEVEN) return
            // Dedup by tradeId.
            if (rt30CanonSeen.putIfAbsent(outcome.tradeId, System.currentTimeMillis()) != null) return
            if (rt30CanonSeen.size > 2_000) {
                val cutoff = System.currentTimeMillis() - 300_000L
                rt30CanonSeen.entries.removeIf { it.value < cutoff }
            }
            val pnlPct = outcome.realizedPnlPct ?: 0.0
            val sizeSol = outcome.entrySol ?: 0.0
            val holdSec = outcome.holdSeconds ?: 0L
            val assetClass = outcome.candidate?.assetClass?.ifBlank { null } ?: outcome.assetClass.name
            recordTrade(
                symbol = outcome.symbol.ifBlank { outcome.mint.take(6) },
                mint = outcome.mint,
                entryPrice = outcome.entryPrice ?: 0.0,
                exitPrice = outcome.exitPrice ?: 0.0,
                sizeSol = sizeSol,
                pnlPct = pnlPct,
                holdTimeSec = holdSec,
                mode = outcome.mode.name,
                score = 0,
                confidence = 0,
                decision = outcome.executionResult.name,
                assetClass = assetClass,
            )
        } catch (_: Throwable) {}
    }
}

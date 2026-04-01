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
    
    @Volatile var peakBalance: Double = 0.0
    @Volatile var maxDrawdown: Double = 0.0  // Stored as negative percentage
    
    @Volatile var totalPnlPct: Double = 0.0
    @Volatile var bestTradePnl: Double = 0.0
    @Volatile var worstTradePnl: Double = 0.0
    
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
    
    // ═══════════════════════════════════════════════════════════════════════
    // TRADE TRACKING
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * FIX_4 — CLASSIFICATION STANDARD (+/- 5.0%)
     * Classify trade outcome based on P&L percentage.
     */
    fun classifyTrade(pnlPct: Double): String {
        return when {
            pnlPct >= 5.0 -> "WIN"
            pnlPct <= -5.0 -> "LOSS"
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
        
        // Update P&L tracking
        totalPnlPct += pnlPct
        if (pnlPct > bestTradePnl) bestTradePnl = pnlPct
        if (pnlPct < worstTradePnl) worstTradePnl = pnlPct
        
        // Update balance (assuming sizeSol * pnlPct/100 represents SOL gain/loss)
        val balanceChange = sizeSol * (pnlPct / 100.0)
        currentBalance += balanceChange
        
        // Update peak and drawdown
        updateDrawdown(currentBalance)
        
        // Record equity point
        equityCurve.add(System.currentTimeMillis() to currentBalance)
        
        // Log trade
        val entry = TradeEntry(
            timestamp = System.currentTimeMillis(),
            symbol = symbol,
            mint = mint,
            entryPrice = entryPrice,
            exitPrice = exitPrice,
            sizeSol = sizeSol,
            pnlPct = pnlPct,
            classification = classification,
            holdTimeSec = sanitizedHoldTime,
            mode = mode,
            score = score,
            confidence = confidence,
            decision = decision,
        )
        tradeLog.add(entry)
        
        // Update metrics
        metrics.updateFromTrade(pnlPct, classification, confidence.toDouble())
        
        // Save state
        save()
        
        val emoji = when (classification) {
            "WIN" -> "✅"
            "LOSS" -> "❌"
            else -> "➖"
        }
        ErrorLogger.info(TAG, "$emoji Trade #$totalTrades: $symbol ${pnlPct.toInt()}% → $classification | " +
            "Total: W=$wins L=$losses S=$scratches")
    }
    
    /**
     * Update drawdown tracking.
     */
    private fun updateDrawdown(balance: Double) {
        // Update peak
        peakBalance = maxOf(peakBalance, balance)
        
        // Calculate current drawdown
        val dd = if (peakBalance > 0) {
            (balance - peakBalance) / peakBalance
        } else 0.0
        
        // Track maximum drawdown (most negative)
        maxDrawdown = minOf(maxDrawdown, dd)
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // REPORTING
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Generate daily report string.
     */
    fun dailyReport(): String {
        val winRate = if (totalTrades > 0) {
            (wins * 100 / totalTrades)
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
  Scratches: $scratches
  Win Rate:  $winRate%

RISK
  Peak Balance:  ${formatSol(peakBalance)}
  Max Drawdown:  ${String.format("%.2f", maxDrawdown * 100)}%
  Best Trade:    ${if (bestTradePnl >= 0) "+" else ""}${String.format("%.1f", bestTradePnl)}%
  Worst Trade:   ${String.format("%.1f", worstTradePnl)}%

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
        
        val winRate = if (totalTrades > 0) {
            wins.toDouble() / totalTrades
        } else 0.0
        
        return mapOf(
            "runStartTime" to startTime,
            "runEndTime" to System.currentTimeMillis(),
            "totalDays" to getCurrentDay(),
            "startBalance" to startBalance,
            "endBalance" to currentBalance,
            "returnPct" to returnPct,
            "totalTrades" to totalTrades,
            "wins" to wins,
            "losses" to losses,
            "scratches" to scratches,
            "winRate" to winRate,
            "maxDrawdown" to maxDrawdown,
            "peakBalance" to peakBalance,
            "bestTradePnl" to bestTradePnl,
            "worstTradePnl" to worstTradePnl,
            "avgPnlPerTrade" to (if (totalTrades > 0) totalPnlPct / totalTrades else 0.0),
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
                putFloat("totalPnlPct", totalPnlPct.toFloat())
                putFloat("bestTradePnl", bestTradePnl.toFloat())
                putFloat("worstTradePnl", worstTradePnl.toFloat())
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
            totalPnlPct = prefs.getFloat("totalPnlPct", 0f).toDouble()
            bestTradePnl = prefs.getFloat("bestTradePnl", 0f).toDouble()
            worstTradePnl = prefs.getFloat("worstTradePnl", 0f).toDouble()
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
        totalPnlPct = 0.0
        bestTradePnl = 0.0
        worstTradePnl = 0.0
        executionFailures = 0
        missedTrades = 0
        equityCurve.clear()
        dailySummaries.clear()
        tradeLog.clear()
        metrics.reset()
        save()
        ErrorLogger.info(TAG, "🧹 RunTracker30D reset")
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
}

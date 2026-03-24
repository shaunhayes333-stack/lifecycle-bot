package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * EdgeLearning - Adaptive threshold learning for Edge optimizer
 * 
 * This system learns from trade outcomes to adjust Edge's thresholds:
 * - If Edge vetoed but outcome would have been profitable → loosen thresholds
 * - If Edge approved but outcome was a loss → tighten thresholds
 * 
 * Thresholds are adjusted gradually (±1-2 per learning event) to prevent
 * overfitting to recent trades. The system tracks a rolling window of
 * outcomes to make data-driven adjustments.
 */
object EdgeLearning {
    
    // ═══════════════════════════════════════════════════════════════════════
    // ADAPTIVE THRESHOLDS (start with defaults, learn over time)
    // ═══════════════════════════════════════════════════════════════════════
    
    data class AdaptiveThresholds(
        var paperBuyPctMin: Double = 35.0,      // Paper: min buy% to trade (lowered for more learning)
        var paperVolumeMin: Double = 8.0,       // Paper: min volume score (lowered)
        var liveBuyPctMin: Double = 50.0,       // Live: min buy% to trade
        var liveVolumeMin: Double = 15.0,       // Live: min volume score
        var vetoStickyMinutes: Int = 3,         // How long vetoes stick (reduced for paper)
    ) {
        // Bounds to prevent extreme values
        fun clamp() {
            paperBuyPctMin = paperBuyPctMin.coerceIn(25.0, 55.0)  // Lower bound reduced
            paperVolumeMin = paperVolumeMin.coerceIn(3.0, 20.0)   // Lower bound reduced
            liveBuyPctMin = liveBuyPctMin.coerceIn(45.0, 65.0)
            liveVolumeMin = liveVolumeMin.coerceIn(10.0, 25.0)
            vetoStickyMinutes = vetoStickyMinutes.coerceIn(1, 15)  // Lower bound reduced
        }
    }
    
    // Current learned thresholds
    var thresholds = AdaptiveThresholds()
        private set
    
    // Callback for persistence (set by BotService)
    var onThresholdsChanged: (() -> Unit)? = null
    
    // ═══════════════════════════════════════════════════════════════════════
    // TRADE TRACKING - Record Edge analysis at entry for later comparison
    // ═══════════════════════════════════════════════════════════════════════
    
    data class EdgeSnapshot(
        val mint: String,
        val symbol: String,
        val timestamp: Long,
        val buyPct: Double,
        val volumeScore: Double,
        val phase: String,
        val edgeQuality: String,        // A/B/C/SKIP
        val wasVetoed: Boolean,
        val vetoReason: String?,
        val entryPrice: Double,
        val isPaperMode: Boolean,
    )
    
    private val entrySnapshots = ConcurrentHashMap<String, EdgeSnapshot>()
    
    // Rolling outcome stats for learning
    data class OutcomeStats(
        var totalTrades: Int = 0,
        var vetoedWouldWin: Int = 0,     // Vetoed but would have won
        var vetoedWouldLose: Int = 0,    // Vetoed and would have lost (correct)
        var approvedWon: Int = 0,         // Approved and won (correct)
        var approvedLost: Int = 0,        // Approved and lost (incorrect)
    ) {
        val vetoAccuracy: Double get() = 
            if (vetoedWouldWin + vetoedWouldLose > 0) 
                vetoedWouldLose.toDouble() / (vetoedWouldWin + vetoedWouldLose) 
            else 0.5
            
        val approvalAccuracy: Double get() = 
            if (approvedWon + approvedLost > 0) 
                approvedWon.toDouble() / (approvedWon + approvedLost) 
            else 0.5
    }
    
    private val stats = OutcomeStats()
    
    // ═══════════════════════════════════════════════════════════════════════
    // API: Record Edge analysis at trade entry
    // ═══════════════════════════════════════════════════════════════════════
    
    fun recordEntry(
        mint: String,
        symbol: String,
        buyPct: Double,
        volumeScore: Double,
        phase: String,
        edgeQuality: String,
        wasVetoed: Boolean,
        vetoReason: String?,
        entryPrice: Double,
        isPaperMode: Boolean,
    ) {
        entrySnapshots[mint] = EdgeSnapshot(
            mint = mint,
            symbol = symbol,
            timestamp = System.currentTimeMillis(),
            buyPct = buyPct,
            volumeScore = volumeScore,
            phase = phase,
            edgeQuality = edgeQuality,
            wasVetoed = wasVetoed,
            vetoReason = vetoReason,
            entryPrice = entryPrice,
            isPaperMode = isPaperMode,
        )
        ErrorLogger.info("EdgeLearning", "📝 ENTRY: $symbol | buy%=${buyPct.toInt()} vol=${volumeScore.toInt()} | phase=$phase quality=$edgeQuality")
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // API: Learn from trade outcome
    // ═══════════════════════════════════════════════════════════════════════
    
    fun learnFromOutcome(
        mint: String,
        exitPrice: Double,
        pnlPercent: Double,
        wasExecuted: Boolean,
    ) {
        val snapshot = entrySnapshots.remove(mint) ?: return
        
        val isWin = pnlPercent >= 5.0      // Significant win
        val isLoss = pnlPercent <= -5.0    // Significant loss
        val isScratch = !isWin && !isLoss  // Insignificant outcome
        
        // Skip scratch trades for learning (no clear signal)
        if (isScratch) {
            ErrorLogger.debug("EdgeLearning", 
                "${snapshot.symbol}: Scratch trade (${pnlPercent.toInt()}%) - no learning")
            return
        }
        
        stats.totalTrades++
        
        if (snapshot.wasVetoed) {
            // Trade was vetoed by Edge
            if (isWin) {
                stats.vetoedWouldWin++
                // Edge was WRONG to veto - loosen thresholds
                adjustThresholds(snapshot, wasCorrect = false, wasVeto = true)
                ErrorLogger.info("EdgeLearning", 
                    "📈 ${snapshot.symbol}: Veto was WRONG (+${pnlPercent.toInt()}%) → loosening thresholds")
            } else {
                stats.vetoedWouldLose++
                // Edge was RIGHT to veto - reinforce
                adjustThresholds(snapshot, wasCorrect = true, wasVeto = true)
                ErrorLogger.info("EdgeLearning", 
                    "✅ ${snapshot.symbol}: Veto was CORRECT (${pnlPercent.toInt()}%) → reinforcing")
            }
        } else if (wasExecuted) {
            // Trade was approved and executed
            if (isWin) {
                stats.approvedWon++
                // Edge was RIGHT to approve - reinforce
                adjustThresholds(snapshot, wasCorrect = true, wasVeto = false)
                ErrorLogger.info("EdgeLearning", 
                    "✅ ${snapshot.symbol}: Approval was CORRECT (+${pnlPercent.toInt()}%) → reinforcing")
            } else {
                stats.approvedLost++
                // Edge was WRONG to approve - tighten thresholds
                adjustThresholds(snapshot, wasCorrect = false, wasVeto = false)
                ErrorLogger.info("EdgeLearning", 
                    "📉 ${snapshot.symbol}: Approval was WRONG (${pnlPercent.toInt()}%) → tightening thresholds")
            }
        }
        
        // Log current stats periodically and trigger persistence callback
        if (stats.totalTrades % 10 == 0) {
            logStats()
            // Trigger save callback for persistence
            onThresholdsChanged?.invoke()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // THRESHOLD ADJUSTMENT LOGIC
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun adjustThresholds(snapshot: EdgeSnapshot, wasCorrect: Boolean, wasVeto: Boolean) {
        val adjustAmount = 1.0  // Small incremental adjustments
        
        if (snapshot.isPaperMode) {
            // Adjust paper thresholds
            if (wasVeto && !wasCorrect) {
                // Veto was wrong (would have won) → LOOSEN
                thresholds.paperBuyPctMin -= adjustAmount
                thresholds.paperVolumeMin -= adjustAmount * 0.5
            } else if (wasVeto && wasCorrect) {
                // Veto was right (would have lost) → slight tighten
                thresholds.paperBuyPctMin += adjustAmount * 0.3
            } else if (!wasVeto && !wasCorrect) {
                // Approval was wrong (lost money) → TIGHTEN
                thresholds.paperBuyPctMin += adjustAmount
                thresholds.paperVolumeMin += adjustAmount * 0.5
            } else if (!wasVeto && wasCorrect) {
                // Approval was right (won money) → slight loosen
                thresholds.paperBuyPctMin -= adjustAmount * 0.3
            }
        } else {
            // Adjust live thresholds (more conservative adjustments)
            if (wasVeto && !wasCorrect) {
                thresholds.liveBuyPctMin -= adjustAmount * 0.5
                thresholds.liveVolumeMin -= adjustAmount * 0.3
            } else if (!wasVeto && !wasCorrect) {
                thresholds.liveBuyPctMin += adjustAmount
                thresholds.liveVolumeMin += adjustAmount * 0.5
            }
        }
        
        // Keep within bounds
        thresholds.clamp()
        
        ErrorLogger.debug("EdgeLearning", 
            "Thresholds: paper(buy=${thresholds.paperBuyPctMin.toInt()}% vol=${thresholds.paperVolumeMin.toInt()}) " +
            "live(buy=${thresholds.liveBuyPctMin.toInt()}% vol=${thresholds.liveVolumeMin.toInt()})")
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // SHADOW LEARNING - Learn from trades we DIDN'T take
    // ═══════════════════════════════════════════════════════════════════════
    
    fun learnFromShadowTrade(
        mint: String,
        symbol: String,
        buyPct: Double,
        volumeScore: Double,
        phase: String,
        vetoReason: String?,
        entryPrice: Double,
        exitPrice: Double,
        isPaperMode: Boolean,
    ) {
        val pnlPercent = if (entryPrice > 0) ((exitPrice - entryPrice) / entryPrice) * 100 else 0.0
        
        val isWin = pnlPercent >= 5.0
        val isLoss = pnlPercent <= -5.0
        
        if (isWin || isLoss) {
            // Create a snapshot for the shadow trade
            val snapshot = EdgeSnapshot(
                mint = mint,
                symbol = symbol,
                timestamp = System.currentTimeMillis(),
                buyPct = buyPct,
                volumeScore = volumeScore,
                phase = phase,
                edgeQuality = "SKIP",
                wasVetoed = true,
                vetoReason = vetoReason,
                entryPrice = entryPrice,
                isPaperMode = isPaperMode,
            )
            
            stats.totalTrades++
            
            if (isWin) {
                stats.vetoedWouldWin++
                adjustThresholds(snapshot, wasCorrect = false, wasVeto = true)
                ErrorLogger.info("EdgeLearning", 
                    "👻 SHADOW ${symbol}: Veto MISSED +${pnlPercent.toInt()}% → loosening thresholds")
            } else {
                stats.vetoedWouldLose++
                adjustThresholds(snapshot, wasCorrect = true, wasVeto = true)
                ErrorLogger.info("EdgeLearning", 
                    "👻 SHADOW ${symbol}: Veto SAVED ${pnlPercent.toInt()}% loss → reinforcing")
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // PERSISTENCE - Save/load learned thresholds
    // ═══════════════════════════════════════════════════════════════════════
    
    fun saveToPrefs(prefs: android.content.SharedPreferences) {
        prefs.edit()
            .putFloat("edge_paper_buy_pct", thresholds.paperBuyPctMin.toFloat())
            .putFloat("edge_paper_volume", thresholds.paperVolumeMin.toFloat())
            .putFloat("edge_live_buy_pct", thresholds.liveBuyPctMin.toFloat())
            .putFloat("edge_live_volume", thresholds.liveVolumeMin.toFloat())
            .putInt("edge_veto_minutes", thresholds.vetoStickyMinutes)
            .putInt("edge_total_trades", stats.totalTrades)
            .putInt("edge_vetoed_would_win", stats.vetoedWouldWin)
            .putInt("edge_vetoed_would_lose", stats.vetoedWouldLose)
            .putInt("edge_approved_won", stats.approvedWon)
            .putInt("edge_approved_lost", stats.approvedLost)
            .apply()
        
        ErrorLogger.info("EdgeLearning", "💾 Saved learned thresholds")
    }
    
    fun loadFromPrefs(prefs: android.content.SharedPreferences) {
        thresholds = AdaptiveThresholds(
            paperBuyPctMin = prefs.getFloat("edge_paper_buy_pct", 40f).toDouble(),
            paperVolumeMin = prefs.getFloat("edge_paper_volume", 10f).toDouble(),
            liveBuyPctMin = prefs.getFloat("edge_live_buy_pct", 50f).toDouble(),
            liveVolumeMin = prefs.getFloat("edge_live_volume", 15f).toDouble(),
            vetoStickyMinutes = prefs.getInt("edge_veto_minutes", 5),
        )
        
        stats.totalTrades = prefs.getInt("edge_total_trades", 0)
        stats.vetoedWouldWin = prefs.getInt("edge_vetoed_would_win", 0)
        stats.vetoedWouldLose = prefs.getInt("edge_vetoed_would_lose", 0)
        stats.approvedWon = prefs.getInt("edge_approved_won", 0)
        stats.approvedLost = prefs.getInt("edge_approved_lost", 0)
        
        ErrorLogger.info("EdgeLearning", 
            "📂 Loaded thresholds: paper(buy=${thresholds.paperBuyPctMin.toInt()}%) " +
            "live(buy=${thresholds.liveBuyPctMin.toInt()}%) | trades=${stats.totalTrades}")
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // API: Get current thresholds for Edge to use
    // ═══════════════════════════════════════════════════════════════════════
    
    fun getPaperBuyPctMin(): Double = thresholds.paperBuyPctMin
    fun getPaperVolumeMin(): Double = thresholds.paperVolumeMin
    fun getLiveBuyPctMin(): Double = thresholds.liveBuyPctMin
    fun getLiveVolumeMin(): Double = thresholds.liveVolumeMin
    fun getVetoStickyMinutes(): Int = thresholds.vetoStickyMinutes
    
    fun logStats() {
        ErrorLogger.info("EdgeLearning", """
            |📊 EDGE LEARNING STATS
            |   Total trades: ${stats.totalTrades}
            |   Veto accuracy: ${(stats.vetoAccuracy * 100).toInt()}% (${stats.vetoedWouldLose}/${stats.vetoedWouldWin + stats.vetoedWouldLose} correct)
            |   Approval accuracy: ${(stats.approvalAccuracy * 100).toInt()}% (${stats.approvedWon}/${stats.approvedWon + stats.approvedLost} correct)
            |   Thresholds: paper(buy=${thresholds.paperBuyPctMin.toInt()}% vol=${thresholds.paperVolumeMin.toInt()}) live(buy=${thresholds.liveBuyPctMin.toInt()}% vol=${thresholds.liveVolumeMin.toInt()})
        """.trimMargin())
    }
    
    fun reset() {
        thresholds = AdaptiveThresholds()
        stats.totalTrades = 0
        stats.vetoedWouldWin = 0
        stats.vetoedWouldLose = 0
        stats.approvedWon = 0
        stats.approvedLost = 0
        entrySnapshots.clear()
        ErrorLogger.info("EdgeLearning", "🔄 Reset to default thresholds")
    }
}

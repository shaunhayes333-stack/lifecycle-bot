package com.lifecyclebot.engine

import android.content.Context
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * SelfHealingDiagnostics — Automatic performance monitoring and correction.
 * 
 * Runs every few hours to:
 * - Check win rate and detect bad learning patterns
 * - Auto-clear poisoned persistent memory if needed
 * - Adjust thresholds if too lenient
 * - Reset modes that are underperforming
 * 
 * DEFENSIVE DESIGN:
 * - Runs in background, non-blocking
 * - All operations wrapped in try/catch
 * - Conservative corrections (won't make things worse)
 */
object SelfHealingDiagnostics {
    
    private const val TAG = "SelfHeal"
    
    // ═══════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════
    
    private const val CHECK_INTERVAL_MS = 3 * 60 * 60 * 1000L  // 3 hours
    private const val MIN_TRADES_FOR_DIAGNOSIS = 20            // Need at least 20 trades to judge
    private const val CRITICAL_WIN_RATE = 15.0                 // Below this = something is very wrong
    private const val WARNING_WIN_RATE = 25.0                  // Below this = needs attention
    private const val HEALTHY_WIN_RATE = 40.0                  // Above this = doing OK
    
    // ═══════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════
    
    @Volatile private var lastCheckMs: Long = 0
    @Volatile private var lastClearMs: Long = 0
    @Volatile private var checksPerformed: Int = 0
    @Volatile private var correctionsApplied: Int = 0
    @Volatile private var memoryClearsApplied: Int = 0
    
    private val isRunning = AtomicBoolean(false)
    private var diagnosticJob: Job? = null
    
    // ═══════════════════════════════════════════════════════════════════
    // DIAGNOSIS RESULT
    // ═══════════════════════════════════════════════════════════════════
    
    data class DiagnosisResult(
        val timestamp: Long = System.currentTimeMillis(),
        val tradeCount: Int = 0,
        val winRate: Double = 0.0,
        val avgPnl: Double = 0.0,
        val status: HealthStatus = HealthStatus.UNKNOWN,
        val issues: List<String> = emptyList(),
        val corrections: List<String> = emptyList(),
        val memoryCleared: Boolean = false,
    )
    
    enum class HealthStatus {
        HEALTHY,      // Win rate >= 40%
        WARNING,      // Win rate 25-40%
        CRITICAL,     // Win rate 15-25%
        EMERGENCY,    // Win rate < 15%
        UNKNOWN,      // Not enough data
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // MAIN DIAGNOSTIC LOOP
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Start the self-healing diagnostic loop.
     * Should be called once when BotService starts.
     */
    fun start(scope: CoroutineScope) {
        if (isRunning.getAndSet(true)) {
            ErrorLogger.debug(TAG, "Already running")
            return
        }
        
        diagnosticJob = scope.launch(Dispatchers.IO) {
            ErrorLogger.info(TAG, "🔬 Self-healing diagnostics started (${CHECK_INTERVAL_MS / 3600000}h interval)")
            
            while (isActive) {
                try {
                    // Wait for interval
                    delay(CHECK_INTERVAL_MS)
                    
                    // Run diagnosis
                    val result = runDiagnosis()
                    checksPerformed++
                    lastCheckMs = System.currentTimeMillis()
                    
                    // Log result
                    ErrorLogger.info(TAG, "🔬 Diagnosis #$checksPerformed: ${result.status} | " +
                        "win=${result.winRate.toInt()}% | trades=${result.tradeCount} | " +
                        "issues=${result.issues.size} | corrections=${result.corrections.size}")
                    
                    // Apply corrections if needed
                    if (result.corrections.isNotEmpty()) {
                        correctionsApplied += result.corrections.size
                    }
                    if (result.memoryCleared) {
                        memoryClearsApplied++
                    }
                    
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ErrorLogger.warn(TAG, "Diagnosis error: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Stop the diagnostic loop.
     */
    fun stop() {
        diagnosticJob?.cancel()
        diagnosticJob = null
        isRunning.set(false)
        ErrorLogger.info(TAG, "Self-healing diagnostics stopped")
    }
    
    /**
     * Run an immediate diagnosis (can be called manually).
     */
    fun runDiagnosis(context: Context? = null): DiagnosisResult {
        return try {
            val issues = mutableListOf<String>()
            val corrections = mutableListOf<String>()
            var memoryCleared = false
            
            // ═══════════════════════════════════════════════════════════════
            // COLLECT METRICS
            // ═══════════════════════════════════════════════════════════════
            
            // Get win rate from FluidLearning (paper trading stats)
            val fluidWinRate = try {
                FluidLearning.getWinRate()
            } catch (e: Exception) { 0.0 }
            
            val fluidTrades = try {
                FluidLearning.getTradeCount()
            } catch (e: Exception) { 0 }
            
            // Get win rate from EntryIntelligence
            val entryAiWinRate = try {
                EntryIntelligence.getWinRate()
            } catch (e: Exception) { 0.0 }
            
            val entryAiTrades = try {
                EntryIntelligence.getTradeCount()
            } catch (e: Exception) { 0 }
            
            // Get mode stats
            val modeStats = try {
                UnifiedModeOrchestrator.getAllStatsSorted()
            } catch (e: Exception) { emptyList() }
            
            // Use the most reliable metric
            val primaryWinRate = if (fluidTrades > 10) fluidWinRate else entryAiWinRate
            val primaryTrades = maxOf(fluidTrades, entryAiTrades)
            
            // ═══════════════════════════════════════════════════════════════
            // DETERMINE HEALTH STATUS
            // ═══════════════════════════════════════════════════════════════
            
            val status = when {
                primaryTrades < MIN_TRADES_FOR_DIAGNOSIS -> HealthStatus.UNKNOWN
                primaryWinRate < CRITICAL_WIN_RATE -> HealthStatus.EMERGENCY
                primaryWinRate < WARNING_WIN_RATE -> HealthStatus.CRITICAL
                primaryWinRate < HEALTHY_WIN_RATE -> HealthStatus.WARNING
                else -> HealthStatus.HEALTHY
            }
            
            // ═══════════════════════════════════════════════════════════════
            // DETECT ISSUES
            // ═══════════════════════════════════════════════════════════════
            
            // Issue 1: Extremely low win rate
            if (primaryWinRate < CRITICAL_WIN_RATE && primaryTrades >= MIN_TRADES_FOR_DIAGNOSIS) {
                issues.add("CRITICAL: Win rate ${primaryWinRate.toInt()}% < ${CRITICAL_WIN_RATE.toInt()}% after $primaryTrades trades")
            }
            
            // Issue 2: Entry AI has learned bad patterns
            if (entryAiWinRate < 10.0 && entryAiTrades > 50) {
                issues.add("Entry AI poisoned: ${entryAiWinRate.toInt()}% win rate over $entryAiTrades trades")
            }
            
            // Issue 3: All modes deactivated
            val activeModes = modeStats.count { it.isActive }
            if (activeModes == 0 && modeStats.isNotEmpty()) {
                issues.add("All trading modes deactivated due to poor performance")
            }
            
            // Issue 4: Negative avg PnL
            val avgPnl = try {
                modeStats.filter { it.trades > 5 }.map { it.avgPnlPct }.average()
            } catch (e: Exception) { 0.0 }
            
            if (avgPnl < -10.0) {
                issues.add("Average PnL is ${avgPnl.toInt()}% (heavily negative)")
            }
            
            // ═══════════════════════════════════════════════════════════════
            // APPLY CORRECTIONS
            // ═══════════════════════════════════════════════════════════════
            
            // Correction 1: Clear poisoned memory if win rate is emergency-level
            if (status == HealthStatus.EMERGENCY && primaryTrades >= MIN_TRADES_FOR_DIAGNOSIS) {
                // Only clear if we haven't cleared recently (prevent loops)
                val timeSinceLastClear = System.currentTimeMillis() - lastClearMs
                if (timeSinceLastClear > 24 * 60 * 60 * 1000L) {  // Once per 24h max
                    ErrorLogger.warn(TAG, "🧹 EMERGENCY: Clearing poisoned learning data...")
                    clearPoisonedMemory(context)
                    memoryCleared = true
                    lastClearMs = System.currentTimeMillis()
                    corrections.add("Cleared poisoned learning data (win rate was ${primaryWinRate.toInt()}%)")
                }
            }
            
            // Correction 2: Reset deactivated modes if all are off
            if (activeModes == 0 && modeStats.isNotEmpty()) {
                ErrorLogger.warn(TAG, "🔄 Reactivating all trading modes...")
                UnifiedModeOrchestrator.ExtendedMode.values().forEach { mode ->
                    UnifiedModeOrchestrator.activateMode(mode)
                }
                corrections.add("Reactivated all trading modes")
            }
            
            // Correction 3: Tighten thresholds if win rate is critical
            if (status == HealthStatus.CRITICAL) {
                ErrorLogger.warn(TAG, "⚠️ Win rate critical - thresholds may need tightening")
                corrections.add("Flagged for threshold review (manual)")
            }
            
            DiagnosisResult(
                tradeCount = primaryTrades,
                winRate = primaryWinRate,
                avgPnl = avgPnl,
                status = status,
                issues = issues,
                corrections = corrections,
                memoryCleared = memoryCleared,
            )
            
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "runDiagnosis error: ${e.message}")
            DiagnosisResult(status = HealthStatus.UNKNOWN, issues = listOf("Diagnosis failed: ${e.message}"))
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // MEMORY CLEARING
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Clear all poisoned learning data.
     * This is a nuclear option when win rate is critically low.
     */
    fun clearPoisonedMemory(context: Context? = null) {
        try {
            ErrorLogger.warn(TAG, "🧹 Clearing ALL learning data...")
            
            // 1. Clear EntryIntelligence
            try {
                EntryIntelligence.clear()
                ErrorLogger.info(TAG, "✓ Cleared EntryIntelligence")
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "Failed to clear EntryIntelligence: ${e.message}")
            }
            
            // 2. Clear ExitIntelligence
            try {
                ExitIntelligence.clear()
                ErrorLogger.info(TAG, "✓ Cleared ExitIntelligence")
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "Failed to clear ExitIntelligence: ${e.message}")
            }
            
            // 3. Clear FluidLearning
            try {
                FluidLearning.reset()
                ErrorLogger.info(TAG, "✓ Cleared FluidLearning")
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "Failed to clear FluidLearning: ${e.message}")
            }
            
            // 4. Clear EdgeLearning
            try {
                EdgeLearning.clear()
                ErrorLogger.info(TAG, "✓ Cleared EdgeLearning")
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "Failed to clear EdgeLearning: ${e.message}")
            }
            
            // 5. Clear ScannerLearning
            try {
                ScannerLearning.clear()
                ErrorLogger.info(TAG, "✓ Cleared ScannerLearning")
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "Failed to clear ScannerLearning: ${e.message}")
            }
            
            // 6. Clear AdaptiveLearningEngine
            try {
                AdaptiveLearningEngine.clear()
                ErrorLogger.info(TAG, "✓ Cleared AdaptiveLearningEngine")
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "Failed to clear AdaptiveLearningEngine: ${e.message}")
            }
            
            // 7. Clear SuperBrainEnhancements
            try {
                SuperBrainEnhancements.clear()
                ErrorLogger.info(TAG, "✓ Cleared SuperBrainEnhancements")
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "Failed to clear SuperBrainEnhancements: ${e.message}")
            }
            
            // 7.5. Clear BehaviorLearning (Good/Bad layers)
            try {
                BehaviorLearning.clear()
                ErrorLogger.info(TAG, "✓ Cleared BehaviorLearning")
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "Failed to clear BehaviorLearning: ${e.message}")
            }
            
            // 8. Reset UnifiedModeOrchestrator stats
            try {
                UnifiedModeOrchestrator.ExtendedMode.values().forEach { mode ->
                    UnifiedModeOrchestrator.activateMode(mode)
                }
                ErrorLogger.info(TAG, "✓ Reset UnifiedModeOrchestrator")
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "Failed to reset ModeOrchestrator: ${e.message}")
            }
            
            // 9. Clear BotBrain recent memory
            try {
                BotService.instance?.executor?.brain?.clearRecentMemory()
                ErrorLogger.info(TAG, "✓ Cleared BotBrain recent memory")
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "Failed to clear BotBrain: ${e.message}")
            }
            
            // 10. Clear persistent storage files if context available
            if (context != null) {
                try {
                    PersistentLearning.clearAll(context)
                    ErrorLogger.info(TAG, "✓ Cleared persistent storage")
                } catch (e: Exception) {
                    ErrorLogger.warn(TAG, "Failed to clear persistent storage: ${e.message}")
                }
            }
            
            ErrorLogger.warn(TAG, "🧹 Memory clear complete - bot will relearn from scratch")
            
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "clearPoisonedMemory failed: ${e.message}")
        }
    }
    
    /**
     * Manual trigger to clear memory (can be called from UI).
     */
    fun manualClearMemory(context: Context?) {
        clearPoisonedMemory(context)
        lastClearMs = System.currentTimeMillis()
        memoryClearsApplied++
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // STATUS
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Get diagnostic stats for display.
     */
    fun getStats(): DiagnosticStats {
        return DiagnosticStats(
            lastCheckMs = lastCheckMs,
            checksPerformed = checksPerformed,
            correctionsApplied = correctionsApplied,
            memoryClearsApplied = memoryClearsApplied,
            isRunning = isRunning.get(),
        )
    }
    
    data class DiagnosticStats(
        val lastCheckMs: Long = 0,
        val checksPerformed: Int = 0,
        val correctionsApplied: Int = 0,
        val memoryClearsApplied: Int = 0,
        val isRunning: Boolean = false,
    )
    
    /**
     * Get status emoji for display.
     */
    fun getStatusEmoji(status: HealthStatus): String {
        return when (status) {
            HealthStatus.HEALTHY -> "🟢"
            HealthStatus.WARNING -> "🟡"
            HealthStatus.CRITICAL -> "🟠"
            HealthStatus.EMERGENCY -> "🔴"
            HealthStatus.UNKNOWN -> "⚪"
        }
    }
}

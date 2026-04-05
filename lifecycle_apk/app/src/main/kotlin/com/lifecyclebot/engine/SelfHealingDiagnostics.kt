package com.lifecyclebot.engine

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

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

    private const val CHECK_INTERVAL_MS = 3L * 60L * 60L * 1000L  // 3 hours
    private const val MIN_TRADES_FOR_DIAGNOSIS = 20
    private const val CRITICAL_WIN_RATE = 15.0
    private const val WARNING_WIN_RATE = 25.0
    private const val HEALTHY_WIN_RATE = 40.0
    private const val MIN_TRADES_FOR_AVG_PNL = 5
    private const val MEMORY_CLEAR_COOLDOWN_MS = 24L * 60L * 60L * 1000L

    // ═══════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════

    @Volatile
    private var lastCheckMs: Long = 0L

    @Volatile
    private var lastClearMs: Long = 0L

    @Volatile
    private var checksPerformed: Int = 0

    @Volatile
    private var correctionsApplied: Int = 0

    @Volatile
    private var memoryClearsApplied: Int = 0

    private val isRunning = AtomicBoolean(false)

    @Volatile
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
        HEALTHY,
        WARNING,
        CRITICAL,
        EMERGENCY,
        UNKNOWN,
    }

    // ═══════════════════════════════════════════════════════════════════
    // MAIN DIAGNOSTIC LOOP
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Start the self-healing diagnostic loop.
     * Should be called once when BotService starts.
     */
    fun start(scope: CoroutineScope) {
        if (!isRunning.compareAndSet(false, true)) {
            ErrorLogger.debug(TAG, "Already running")
            return
        }

        diagnosticJob?.cancel()

        diagnosticJob = scope.launch(Dispatchers.IO) {
            ErrorLogger.info(
                TAG,
                "🔬 Self-healing diagnostics started (${CHECK_INTERVAL_MS / 3_600_000L}h interval)"
            )

            while (isActive) {
                try {
                    delay(CHECK_INTERVAL_MS)

                    val result = runDiagnosis()
                    checksPerformed += 1
                    lastCheckMs = System.currentTimeMillis()

                    ErrorLogger.info(
                        TAG,
                        "🔬 Diagnosis #$checksPerformed: ${result.status} | " +
                            "win=${result.winRate.toInt()}% | " +
                            "trades=${result.tradeCount} | " +
                            "issues=${result.issues.size} | " +
                            "corrections=${result.corrections.size}"
                    )

                    if (result.corrections.isNotEmpty()) {
                        correctionsApplied += result.corrections.size
                    }

                    if (result.memoryCleared) {
                        memoryClearsApplied += 1
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
        val job = diagnosticJob
        diagnosticJob = null
        job?.cancel()
        isRunning.set(false)
        ErrorLogger.info(TAG, "Self-healing diagnostics stopped")
    }

    /**
     * Run an immediate diagnosis (can be called manually).
     */
    fun runDiagnosis(context: Context? = null): DiagnosisResult {
        return try {
            val issues = ArrayList<String>()
            val corrections = ArrayList<String>()
            var memoryCleared = false

            // ═══════════════════════════════════════════════════════════════
            // COLLECT METRICS
            // ═══════════════════════════════════════════════════════════════

            val fluidWinRate = safeGetDouble { FluidLearning.getWinRate() }
            val fluidTrades = safeGetInt { FluidLearning.getTradeCount() }

            val entryAiWinRate = safeGetDouble { EntryIntelligence.getWinRate() }
            val entryAiTrades = safeGetInt { EntryIntelligence.getTradeCount() }

            val modeStats = safeGetModeStats()
            val behaviorHealth = safeGetBehaviorHealth()

            val primaryWinRate = if (fluidTrades > 10) fluidWinRate else entryAiWinRate
            val primaryTrades = if (fluidTrades >= entryAiTrades) fluidTrades else entryAiTrades

            val avgPnl = computeAveragePnl(modeStats)
            val activeModes = countActiveModes(modeStats)

            // ═══════════════════════════════════════════════════════════════
            // DETERMINE HEALTH STATUS
            // ═══════════════════════════════════════════════════════════════

            val status = determineHealthStatus(
                tradeCount = primaryTrades,
                winRate = primaryWinRate,
            )

            // ═══════════════════════════════════════════════════════════════
            // DETECT ISSUES
            // ═══════════════════════════════════════════════════════════════

            if (primaryTrades >= MIN_TRADES_FOR_DIAGNOSIS && primaryWinRate < CRITICAL_WIN_RATE) {
                issues.add(
                    "CRITICAL: Win rate ${primaryWinRate.toInt()}% < ${CRITICAL_WIN_RATE.toInt()}% after $primaryTrades trades"
                )
            }

            if (entryAiTrades > 50 && entryAiWinRate < 10.0) {
                issues.add(
                    "Entry AI poisoned: ${entryAiWinRate.toInt()}% win rate over $entryAiTrades trades"
                )
            }

            if (modeStats.isNotEmpty() && activeModes == 0) {
                issues.add("All trading modes deactivated due to poor performance")
            }

            if (avgPnl < -10.0) {
                issues.add("Average PnL is ${avgPnl.toInt()}% (heavily negative)")
            }

            if (behaviorHealth != null) {
                val statusValue = behaviorHealth.status
                if (statusValue == "CRITICAL") {
                    issues.add(
                        "BehaviorLearning CRITICAL: Bad ratio ${formatOneDecimal(behaviorHealth.badRatio)}x " +
                            "(${behaviorHealth.badCount} bad vs ${behaviorHealth.goodCount} good)"
                    )
                } else if (statusValue == "WARNING") {
                    issues.add(
                        "BehaviorLearning WARNING: Elevated bad patterns " +
                            "(ratio ${formatOneDecimal(behaviorHealth.badRatio)}x)"
                    )
                }
            }

            // ═══════════════════════════════════════════════════════════════
            // APPLY CORRECTIONS
            // ═══════════════════════════════════════════════════════════════

            if (status == HealthStatus.EMERGENCY && primaryTrades >= MIN_TRADES_FOR_DIAGNOSIS) {
                val now = System.currentTimeMillis()
                val timeSinceLastClear = now - lastClearMs

                if (timeSinceLastClear > MEMORY_CLEAR_COOLDOWN_MS) {
                    ErrorLogger.warn(TAG, "🧹 EMERGENCY: Clearing poisoned learning data...")
                    clearPoisonedMemory(context)
                    memoryCleared = true
                    lastClearMs = now
                    corrections.add(
                        "Cleared poisoned learning data (win rate was ${primaryWinRate.toInt()}%)"
                    )
                } else {
                    corrections.add("Skipped memory clear due to cooldown")
                }
            }

            if (modeStats.isNotEmpty() && activeModes == 0) {
                ErrorLogger.warn(TAG, "🔄 Reactivating all trading modes...")
                try {
                    for (mode in UnifiedModeOrchestrator.ExtendedMode.values()) {
                        UnifiedModeOrchestrator.activateMode(mode)
                    }
                    corrections.add("Reactivated all trading modes")
                } catch (e: Exception) {
                    ErrorLogger.warn(TAG, "Failed reactivating modes: ${e.message}")
                }
            }

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
            DiagnosisResult(
                status = HealthStatus.UNKNOWN,
                issues = listOf("Diagnosis failed: ${e.message}")
            )
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

            safeClear("EntryIntelligence") { EntryIntelligence.clear() }
            safeClear("ExitIntelligence") { ExitIntelligence.clear() }
            safeClear("FluidLearning") { FluidLearning.reset() }
            safeClear("EdgeLearning") { EdgeLearning.clear() }
            safeClear("ScannerLearning") { ScannerLearning.clear() }
            safeClear("AdaptiveLearningEngine") { AdaptiveLearningEngine.clear() }
            safeClear("SuperBrainEnhancements") { SuperBrainEnhancements.clear() }
            safeClear("BehaviorLearning") { BehaviorLearning.clear() }
            safeClear("ModeLearning") { ModeLearning.clear() }

            safeClear("UnifiedModeOrchestrator") {
                for (mode in UnifiedModeOrchestrator.ExtendedMode.values()) {
                    UnifiedModeOrchestrator.activateMode(mode)
                }
            }

            safeClear("BotBrain recent memory") {
                BotService.instance?.executor?.brain?.clearRecentMemory()
            }

            if (context != null) {
                safeClear("persistent storage") {
                    PersistentLearning.clearAll(context)
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
        memoryClearsApplied += 1
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
        val lastCheckMs: Long = 0L,
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

    // ═══════════════════════════════════════════════════════════════════
    // INTERNAL HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private inline fun safeGetDouble(block: () -> Double): Double {
        return try {
            block()
        } catch (_: Exception) {
            0.0
        }
    }

    private inline fun safeGetInt(block: () -> Int): Int {
        return try {
            block()
        } catch (_: Exception) {
            0
        }
    }

    private fun safeGetModeStats(): List<UnifiedModeOrchestrator.ModeStats> {
        return try {
            UnifiedModeOrchestrator.getAllStatsSorted()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun safeGetBehaviorHealth(): BehaviorLearning.HealthStatus? {
        return try {
            BehaviorLearning.getHealthStatus()
        } catch (_: Exception) {
            null
        }
    }

    private fun computeAveragePnl(modeStats: List<UnifiedModeOrchestrator.ModeStats>): Double {
        var total = 0.0
        var count = 0

        for (stat in modeStats) {
            if (stat.trades > MIN_TRADES_FOR_AVG_PNL) {
                total += stat.avgPnlPct
                count += 1
            }
        }

        return if (count > 0) total / count else 0.0
    }

    private fun countActiveModes(modeStats: List<UnifiedModeOrchestrator.ModeStats>): Int {
        var active = 0
        for (stat in modeStats) {
            if (stat.isActive) {
                active += 1
            }
        }
        return active
    }

    private fun determineHealthStatus(
        tradeCount: Int,
        winRate: Double,
    ): HealthStatus {
        return when {
            tradeCount < MIN_TRADES_FOR_DIAGNOSIS -> HealthStatus.UNKNOWN
            winRate < CRITICAL_WIN_RATE -> HealthStatus.EMERGENCY
            winRate < WARNING_WIN_RATE -> HealthStatus.CRITICAL
            winRate < HEALTHY_WIN_RATE -> HealthStatus.WARNING
            else -> HealthStatus.HEALTHY
        }
    }

    private fun formatOneDecimal(value: Double): String {
        return String.format("%.1f", value)
    }

    private inline fun safeClear(name: String, block: () -> Unit) {
        try {
            block()
            ErrorLogger.info(TAG, "✓ Cleared $name")
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Failed to clear $name: ${e.message}")
        }
    }
}
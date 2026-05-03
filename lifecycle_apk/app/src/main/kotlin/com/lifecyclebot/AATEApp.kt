package com.lifecyclebot

import android.app.Application
import android.content.Intent
import com.lifecyclebot.engine.BotService
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.ServiceWatchdog

/**
 * AATEApp — Application class
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Initializes error logging BEFORE any Activity or Service starts.
 * Sets up a global uncaught exception handler to catch ALL crashes.
 * 
 * RESILIENCE STRATEGY:
 * - Log all crashes to ErrorLogger database
 * - For non-fatal exceptions (ConcurrentModification, NullPointer in background),
 *   attempt to continue without crashing
 * - Schedule service restart if bot was running
 * - WorkManager watchdog for reliable service monitoring
 */
class AATEApp : Application() {

    companion object {
        // V5.9.104: global app context accessor for static utilities
        // (MarketsLiveExecutor, etc.) that need to load BotConfig.
        @Volatile private var _appCtx: android.content.Context? = null
        fun appContextOrNull(): android.content.Context? = _appCtx
    }

    override fun onCreate() {
        super.onCreate()
        _appCtx = applicationContext
        
        // Initialize ErrorLogger FIRST - before anything else
        try {
            ErrorLogger.init(this)
            ErrorLogger.info("App", "Application onCreate - ErrorLogger initialized")
        } catch (e: Exception) {
            android.util.Log.e("AATEApp", "Failed to init ErrorLogger: ${e.message}", e)
        }

        // V5.9.431 — Initialize TradeHistoryStore EARLY so the journal starts
        // logging immediately regardless of whether the bot is running or the
        // user opens JournalActivity before MainActivity. Prior to this, the
        // SQLite store only initialized inside BotService.startBot() (line
        // 389) and MainActivity.onCreate, which meant any trade recorded
        // before those ran was silently dropped by the null ioHandler, and a
        // user opening Journal while the bot was OFF saw an empty list.
        try {
            com.lifecyclebot.engine.TradeHistoryStore.init(this)
            ErrorLogger.info("App", "TradeHistoryStore initialized — journal persistence active")
        } catch (e: Exception) {
            ErrorLogger.error("App", "TradeHistoryStore init failed: ${e.message}", e)
        }

        // V5.9.433 — restore TreasuryManager here too, so the 70/30 splits
        // and 100% scalp deposits can auto-persist (cachedCtx is seeded on
        // restore). Previously only BotService.startBot did this, which
        // meant contributions before startBot would land in memory-only and
        // get wiped on process kill. Restore is idempotent.
        try {
            com.lifecyclebot.engine.TreasuryManager.restore(this)
            ErrorLogger.info("App", "TreasuryManager restored — treasury persistence active")
        } catch (e: Exception) {
            ErrorLogger.error("App", "TreasuryManager restore failed: ${e.message}", e)
        }

        // V5.9.14: Initialize SymbolicContext — loads persisted mood/edge state
        try {
            com.lifecyclebot.engine.SymbolicContext.init(this)
            ErrorLogger.info("App", "SymbolicContext restored: ${com.lifecyclebot.engine.SymbolicContext.getDiagnostics()}")
        } catch (e: Exception) {
            ErrorLogger.error("App", "SymbolicContext init failed: ${e.message}", e)
        }

        // V5.9.19: AUTO-RECOVERY from paper-wallet inflation bug (V5.9.18 regression).
        // If a user's persisted paper wallet is absurdly high (> 10000 SOL) we reset
        // it to the default 11.76 SOL on boot. Without this, the inflated state
        // triggers hundreds of oversized positions and long startup hangs/black
        // screens on relaunch. Learning state is preserved; only cash resets.
        try {
            val paperPrefs = getSharedPreferences("bot_paper_wallet", MODE_PRIVATE)
            val persistedSol = paperPrefs.getFloat("paper_wallet_sol", 11.7647f).toDouble()
            if (persistedSol > 10_000.0) {
                val freshSol = 11.7647f
                paperPrefs.edit().putFloat("paper_wallet_sol", freshSol).apply()
                ErrorLogger.warn("App",
                    "🧯 Paper wallet auto-reset: was ${"%.2f".format(persistedSol)} SOL " +
                    "(inflated by pre-V5.9.18 bug) → reset to $freshSol SOL. Learning preserved.")
                // Also wipe the fluid learning simulated balance so it doesn't re-inflate.
                try { getSharedPreferences("fluid_learning", MODE_PRIVATE)
                    .edit().remove("simulatedBalanceSol").remove("simulatedPeakSol").apply() } catch (_: Exception) {}
                // And clear any persisted positions (they reference the inflated wallet).
                try { getSharedPreferences("position_persistence_v1", MODE_PRIVATE)
                    .edit().clear().apply() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            ErrorLogger.error("App", "Paper wallet recovery check failed: ${e.message}", e)
        }
        
        // Set up global uncaught exception handler
        setupCrashHandler()
        
        // Initialize ServiceWatchdog if bot was supposed to be running
        // This ensures the watchdog is scheduled even after app restart
        try {
            val prefs = getSharedPreferences("bot_runtime", MODE_PRIVATE)
            val wasRunning = prefs.getBoolean("was_running_before_shutdown", false)
            if (wasRunning) {
                ServiceWatchdog.schedule(this)
                ErrorLogger.info("App", "ServiceWatchdog scheduled (bot was running)")
            }
        } catch (e: Exception) {
            ErrorLogger.error("App", "Failed to schedule watchdog: ${e.message}", e)
        }
        
        ErrorLogger.info("App", "AATE Application started successfully")
    }
    
    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Log the crash to our database
                ErrorLogger.crash(
                    "CRASH",
                    "UNCAUGHT EXCEPTION in thread '${thread.name}': ${throwable.javaClass.simpleName}: ${throwable.message}",
                    throwable
                )
                
                // Also log to Android's logcat
                android.util.Log.e("AATEApp", "=== UNCAUGHT CRASH ===", throwable)
                android.util.Log.e("AATEApp", "Thread: ${thread.name}")
                android.util.Log.e("AATEApp", "Exception: ${throwable.javaClass.name}")
                android.util.Log.e("AATEApp", "Message: ${throwable.message}")
                android.util.Log.e("AATEApp", "Stack trace:")
                throwable.printStackTrace()
                
                // Log the cause chain
                var cause = throwable.cause
                var depth = 0
                while (cause != null && depth < 5) {
                    ErrorLogger.error(
                        "CRASH",
                        "Caused by [${depth}]: ${cause.javaClass.simpleName}: ${cause.message}",
                        cause
                    )
                    android.util.Log.e("AATEApp", "Caused by: ${cause.javaClass.name}: ${cause.message}")
                    cause = cause.cause
                    depth++
                }
                
                // Check if this is a recoverable exception in a background thread
                val isBackgroundThread = thread.name.contains("Dispatcher") || 
                                        thread.name.contains("worker") ||
                                        thread.name.contains("pool")
                val isRecoverable = isRecoverableException(throwable)
                
                if (isBackgroundThread && isRecoverable) {
                    ErrorLogger.warn("CRASH", "Recoverable exception in background thread - attempting to continue")
                    android.util.Log.w("AATEApp", "Recoverable exception - NOT crashing app")
                    
                    // Schedule service restart if bot was supposed to be running
                    scheduleServiceRestart()
                    
                    // Don't call default handler - swallow the exception
                    return@setDefaultUncaughtExceptionHandler
                }
                
            } catch (e: Exception) {
                // If even logging fails, at least print to logcat
                android.util.Log.e("AATEApp", "Failed to log crash: ${e.message}")
                android.util.Log.e("AATEApp", "Original crash: ${throwable.message}", throwable)
            }
            
            // Call the default handler to show the crash dialog / terminate
            defaultHandler?.uncaughtException(thread, throwable)
        }
        
        ErrorLogger.info("App", "Global crash handler installed with recovery support")
    }
    
    /**
     * Determines if an exception is recoverable (shouldn't crash the app)
     */
    private fun isRecoverableException(throwable: Throwable): Boolean {
        val exceptionName = throwable.javaClass.simpleName
        val message = throwable.message ?: ""
        
        // List of exceptions that are recoverable in background threads
        val recoverableExceptions = listOf(
            "ConcurrentModificationException",
            "IndexOutOfBoundsException",
            "NoSuchElementException",
            "IllegalStateException",
            "NullPointerException",
            "NumberFormatException",
            "JSONException",
            "SocketTimeoutException",
            "UnknownHostException",
            "IOException",
            "SSLException",
        )
        
        // Check the exception and its cause chain
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < 5) {
            if (recoverableExceptions.any { current!!.javaClass.simpleName.contains(it) }) {
                return true
            }
            current = current.cause
            depth++
        }
        
        return false
    }
    
    /**
     * Schedules a service restart if the bot was running
     */
    private fun scheduleServiceRestart() {
        try {
            val prefs = getSharedPreferences("bot_runtime", MODE_PRIVATE)
            val wasRunning = prefs.getBoolean("was_running_before_shutdown", false)
            
            if (wasRunning) {
                ErrorLogger.info("App", "Bot was running - scheduling restart in 3 seconds")
                
                val restartIntent = Intent(this, BotService::class.java).apply {
                    action = BotService.ACTION_START
                }
                val pi = android.app.PendingIntent.getService(
                    this, 999, restartIntent,
                    android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                val am = getSystemService(android.app.AlarmManager::class.java)
                am?.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 3_000,
                    pi
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("AATEApp", "Failed to schedule restart: ${e.message}")
        }
    }
}

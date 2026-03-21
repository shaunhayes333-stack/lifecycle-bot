package com.lifecyclebot

import android.app.Application
import android.content.Intent
import com.lifecyclebot.engine.BotService
import com.lifecyclebot.engine.ErrorLogger

/**
 * LifecycleBotApp — Application class
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
 */
class LifecycleBotApp : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Initialize ErrorLogger FIRST - before anything else
        try {
            ErrorLogger.init(this)
            ErrorLogger.info("App", "Application onCreate - ErrorLogger initialized")
        } catch (e: Exception) {
            android.util.Log.e("LifecycleBotApp", "Failed to init ErrorLogger: ${e.message}", e)
        }
        
        // Set up global uncaught exception handler
        setupCrashHandler()
        
        ErrorLogger.info("App", "Application started successfully")
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
                android.util.Log.e("LifecycleBotApp", "=== UNCAUGHT CRASH ===", throwable)
                android.util.Log.e("LifecycleBotApp", "Thread: ${thread.name}")
                android.util.Log.e("LifecycleBotApp", "Exception: ${throwable.javaClass.name}")
                android.util.Log.e("LifecycleBotApp", "Message: ${throwable.message}")
                android.util.Log.e("LifecycleBotApp", "Stack trace:")
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
                    android.util.Log.e("LifecycleBotApp", "Caused by: ${cause.javaClass.name}: ${cause.message}")
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
                    android.util.Log.w("LifecycleBotApp", "Recoverable exception - NOT crashing app")
                    
                    // Schedule service restart if bot was supposed to be running
                    scheduleServiceRestart()
                    
                    // Don't call default handler - swallow the exception
                    return@setDefaultUncaughtExceptionHandler
                }
                
            } catch (e: Exception) {
                // If even logging fails, at least print to logcat
                android.util.Log.e("LifecycleBotApp", "Failed to log crash: ${e.message}")
                android.util.Log.e("LifecycleBotApp", "Original crash: ${throwable.message}", throwable)
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
            android.util.Log.e("LifecycleBotApp", "Failed to schedule restart: ${e.message}")
        }
    }
}

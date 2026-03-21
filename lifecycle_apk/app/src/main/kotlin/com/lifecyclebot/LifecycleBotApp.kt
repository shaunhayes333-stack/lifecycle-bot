package com.lifecyclebot

import android.app.Application
import com.lifecyclebot.engine.ErrorLogger

/**
 * LifecycleBotApp — Application class
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Initializes error logging BEFORE any Activity or Service starts.
 * Sets up a global uncaught exception handler to catch ALL crashes.
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
                
            } catch (e: Exception) {
                // If even logging fails, at least print to logcat
                android.util.Log.e("LifecycleBotApp", "Failed to log crash: ${e.message}")
                android.util.Log.e("LifecycleBotApp", "Original crash: ${throwable.message}", throwable)
            }
            
            // Call the default handler to show the crash dialog / terminate
            defaultHandler?.uncaughtException(thread, throwable)
        }
        
        ErrorLogger.info("App", "Global crash handler installed")
    }
}

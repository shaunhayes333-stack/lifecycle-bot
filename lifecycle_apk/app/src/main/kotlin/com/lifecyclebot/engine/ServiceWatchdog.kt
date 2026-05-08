package com.lifecyclebot.engine

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * ServiceWatchdog — ensures the BotService stays running.
 * 
 * Uses WorkManager (more reliable than AlarmManager on newer Android versions)
 * to periodically check if the service should be running and restart it if needed.
 * 
 * Key features:
 * - Runs every 15 minutes (minimum for periodic work)
 * - Survives app kills, Doze mode, and battery optimization
 * - Only restarts if user had the bot running (checks SharedPreferences)
 * - Tracks restart count for debugging
 */
object ServiceWatchdog {
    
    private const val WORK_TAG = "service_watchdog"
    private const val PREFS_NAME = "service_watchdog_prefs"
    private const val KEY_RESTART_COUNT = "restart_count"
    private const val KEY_LAST_RESTART_TIME = "last_restart_time"
    private const val KEY_LAST_HEALTH_CHECK = "last_health_check"
    
    /**
     * Schedule the watchdog worker to run periodically.
     * Call this once from Application.onCreate() or MainActivity.
     */
    fun schedule(context: Context) {
        // No network constraint — watchdog just restarts the service;
        // BotService handles its own network reconnection on start.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()
        
        val workRequest = PeriodicWorkRequestBuilder<WatchdogWorker>(
            15, TimeUnit.MINUTES  // Minimum interval for periodic work
        )
            .setConstraints(constraints)
            .addTag(WORK_TAG)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()
        
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                WORK_TAG,
                ExistingPeriodicWorkPolicy.KEEP,  // Don't replace if already scheduled
                workRequest
            )
        
        ErrorLogger.info("ServiceWatchdog", "Watchdog scheduled (15-minute interval)")
    }
    
    /**
     * Cancel the watchdog if user explicitly stops the bot.
     */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_TAG)
        ErrorLogger.info("ServiceWatchdog", "Watchdog cancelled")
    }
    
    /**
     * Record that the service was healthy (called periodically by BotService).
     */
    fun recordHealthy(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_HEALTH_CHECK, System.currentTimeMillis())
            .apply()
    }
    
    /**
     * Get restart statistics for debugging.
     */
    fun getStats(context: Context): WatchdogStats {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return WatchdogStats(
            restartCount = prefs.getInt(KEY_RESTART_COUNT, 0),
            lastRestartTime = prefs.getLong(KEY_LAST_RESTART_TIME, 0),
            lastHealthCheck = prefs.getLong(KEY_LAST_HEALTH_CHECK, 0),
        )
    }
    
    data class WatchdogStats(
        val restartCount: Int,
        val lastRestartTime: Long,
        val lastHealthCheck: Long,
    ) {
        fun format(): String {
            val restartAgo = if (lastRestartTime > 0) {
                val mins = (System.currentTimeMillis() - lastRestartTime) / 60_000
                if (mins < 60) "${mins}m ago" else "${mins / 60}h ago"
            } else "never"
            
            val healthAgo = if (lastHealthCheck > 0) {
                val mins = (System.currentTimeMillis() - lastHealthCheck) / 60_000
                if (mins < 60) "${mins}m ago" else "${mins / 60}h ago"
            } else "never"
            
            return "restarts=$restartCount (last: $restartAgo) | health: $healthAgo"
        }
    }
    
    /**
     * WorkManager worker that checks and restarts the service.
     */
    class WatchdogWorker(
        appContext: Context,
        workerParams: WorkerParameters
    ) : Worker(appContext, workerParams) {
        
        override fun doWork(): Result {
            val context = applicationContext
            
            try {
                // Check if bot was supposed to be running
                val runtimePrefs = context.getSharedPreferences(BotService.RUNTIME_PREFS, Context.MODE_PRIVATE)
                val wasRunning = runtimePrefs.getBoolean(BotService.KEY_WAS_RUNNING_BEFORE_SHUTDOWN, false)
                val manualStop = runtimePrefs.getBoolean(BotService.KEY_MANUAL_STOP_REQUESTED, false)
                
                if (!wasRunning || manualStop) {
                    ErrorLogger.debug("ServiceWatchdog", "Bot was not intended to run, skipping restart (wasRunning=$wasRunning manualStop=$manualStop)")
                    return Result.success()
                }
                
                // Check if service is actually running
                val isRunning = BotService.status.running
                
                if (!isRunning) {
                    ErrorLogger.warn("ServiceWatchdog", "Bot should be running but isn't - RESTARTING")
                    
                    // Record restart
                    val watchdogPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val currentCount = watchdogPrefs.getInt(KEY_RESTART_COUNT, 0)
                    watchdogPrefs.edit()
                        .putInt(KEY_RESTART_COUNT, currentCount + 1)
                        .putLong(KEY_LAST_RESTART_TIME, System.currentTimeMillis())
                        .apply()
                    
                    // Start the service
                    val intent = Intent(context, BotService::class.java).apply {
                        action = BotService.ACTION_START
                    }
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    
                    ErrorLogger.info("ServiceWatchdog", "Restart #${currentCount + 1} initiated")
                } else {
                    // Service is healthy, record it
                    recordHealthy(context)
                    ErrorLogger.debug("ServiceWatchdog", "Service healthy, recording check")
                }
                
                return Result.success()
            } catch (e: Exception) {
                ErrorLogger.error("ServiceWatchdog", "Watchdog error: ${e.message}", e)
                return Result.retry()
            }
        }
    }
}


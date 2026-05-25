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
        
        // V5.9.999b — SharedPreferences PREWARM.
        //
        // Operator ANR dump V5.9.999 showed 758 ms freezes on
        // CurrencyManager.selectedCurrency and similar SP reads from the
        // UI thread. SharedPreferencesImpl loads its XML file lazily via
        // awaitLoadedLocked() on first read — that load is what was
        // blocking. Trigger every hot-path SP file on a background thread
        // NOW so the disk parse is done before any Activity reads them.
        // Each getSharedPreferences().all forces the load to finish.
        Thread({
            val files = listOf(
                "currency_prefs",
                "bot_paper_wallet",
                "fluid_learning",
                "position_persistence_v1",
                BotService.RUNTIME_PREFS,
            )
            for (f in files) {
                try { getSharedPreferences(f, MODE_PRIVATE).all }
                catch (_: Throwable) {}
            }
        }, "SP-prewarm").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()

        // Set up global uncaught exception handler
        setupCrashHandler()

        // V5.9.1153 — 24/7 runtime contract guard.
        // Activity navigation/backgrounding must never imply bot stop. If all
        // AATE activities stop while the bot was intended to run, renew watchdog
        // alarms and re-kick BotService if the runtime is inactive.
        installBackgroundRuntimeGuard()
        
        // V5.9.913 — RESURRECTION-IN-APPLICATION-ONCREATE.
        //
        // Previous code only scheduled the 15-minute periodic WorkManager
        // watchdog when the app process re-launched after a kill. That
        // meant after an OS kill, the bot stayed dead for up to 15 MINUTES
        // even though the app process is already alive at this moment —
        // we should restart the service NOW.
        //
        // Additionally, Application.onCreate is one of the few places we
        // CAN start a foreground service from a background context with
        // confidence — it runs on the system's app-launch path, which is
        // exempt from background FGS restrictions.
        //
        // Strategy:
        //   1. Check wasRunning && !manualStop (existing condition).
        //   2. Schedule the WorkManager watchdog (existing).
        //   3. ALSO immediately start the service via startForegroundService.
        //   4. If the direct start throws, fall back to a 1s setAlarmClock
        //      (same trick used by WatchdogWorker — bypasses bg restrictions).
        try {
            val prefs = getSharedPreferences(BotService.RUNTIME_PREFS, MODE_PRIVATE)
            val wasRunning = prefs.getBoolean(BotService.KEY_WAS_RUNNING_BEFORE_SHUTDOWN, false)
            val manualStop = prefs.getBoolean(BotService.KEY_MANUAL_STOP_REQUESTED, false)
            if (wasRunning && !manualStop) {
                ServiceWatchdog.schedule(this)
                ErrorLogger.info("App", "ServiceWatchdog scheduled (bot was running)")

                // V5.9.913 — immediately resurrect the BotService rather than
                // waiting 15 minutes for the first WorkManager check.
                val startIntent = android.content.Intent(this, BotService::class.java).apply {
                    action = BotService.ACTION_START
                }
                var directOk = false
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(startIntent)
                    } else {
                        startService(startIntent)
                    }
                    directOk = true
                    ErrorLogger.info("App", "BotService resurrected via direct startForegroundService")
                } catch (fgsBlocked: Throwable) {
                    ErrorLogger.warn(
                        "App",
                        "Direct FGS start from onCreate failed (${fgsBlocked.javaClass.simpleName}: ${fgsBlocked.message}) — falling through to AlarmClock"
                    )
                }
                if (!directOk) {
                    try {
                        val am = getSystemService(android.app.AlarmManager::class.java)
                        if (am != null) {
                            val pi = android.app.PendingIntent.getService(
                                this, 9902, startIntent,
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                            )
                            val showPi = android.app.PendingIntent.getActivity(
                                this, 9903,
                                android.content.Intent(this, com.lifecyclebot.ui.MainActivity::class.java),
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                            )
                            am.setAlarmClock(
                                android.app.AlarmManager.AlarmClockInfo(
                                    System.currentTimeMillis() + 1_000L, showPi
                                ),
                                pi
                            )
                            ErrorLogger.info("App", "BotService resurrection scheduled via 1s AlarmClock fallback")
                        }
                    } catch (e: Throwable) {
                        ErrorLogger.error("App", "Resurrection AlarmClock fallback failed: ${e.message}", e)
                    }
                }
            }
        } catch (e: Exception) {
            ErrorLogger.error("App", "Failed to schedule watchdog: ${e.message}", e)
        }
        
        ErrorLogger.info("App", "AATE Application started successfully")
    }
    
    private fun installBackgroundRuntimeGuard() {
        try {
            registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
                private val activeActivities = java.util.concurrent.atomic.AtomicInteger(0)
                private val bgHandler = android.os.Handler(android.os.Looper.getMainLooper())
                private val backgroundCheck = Runnable { verifyRuntimeAfterBackground("activity_background") }

                override fun onActivityStarted(activity: android.app.Activity) {
                    activeActivities.incrementAndGet()
                    bgHandler.removeCallbacks(backgroundCheck)
                }

                override fun onActivityStopped(activity: android.app.Activity) {
                    val remaining = activeActivities.decrementAndGet().coerceAtLeast(0)
                    if (remaining == 0) {
                        // Delay one beat so in-app Activity transitions (Main -> Pipeline,
                        // Main -> Journal, etc.) can start the next Activity without being
                        // misclassified as app background.
                        bgHandler.removeCallbacks(backgroundCheck)
                        bgHandler.postDelayed(backgroundCheck, 2_500L)
                    }
                }

                override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
                override fun onActivityResumed(activity: android.app.Activity) {}
                override fun onActivityPaused(activity: android.app.Activity) {}
                override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
                override fun onActivityDestroyed(activity: android.app.Activity) {}
            })
            ErrorLogger.info("App", "Background runtime guard installed")
        } catch (e: Throwable) {
            ErrorLogger.warn("App", "Background runtime guard install failed: ${e.message}")
        }
    }

    private fun verifyRuntimeAfterBackground(reason: String) {
        try {
            val prefs = getSharedPreferences(BotService.RUNTIME_PREFS, MODE_PRIVATE)
            val wasRunning = prefs.getBoolean(BotService.KEY_WAS_RUNNING_BEFORE_SHUTDOWN, false)
            val manualStop = prefs.getBoolean(BotService.KEY_MANUAL_STOP_REQUESTED, false)
            val runtimeActive = try { BotService.isRuntimeActive() } catch (_: Throwable) { false }
            val intendedToRun = wasRunning || runtimeActive
            if (!intendedToRun || manualStop) return

            if (runtimeActive && !wasRunning) {
                // Repair stale intent state. Runtime truth says the bot is active,
                // therefore future OS kills/background restarts must treat it as
                // intended-to-run unless the user explicitly stops it.
                try {
                    prefs.edit()
                        .putBoolean(BotService.KEY_WAS_RUNNING_BEFORE_SHUTDOWN, true)
                        .putBoolean(BotService.KEY_MANUAL_STOP_REQUESTED, false)
                        .apply()
                } catch (_: Throwable) {}
            }

            // Renew both watchdog layers while the app goes background. This is a
            // preserve-only path: it never sends ACTION_STOP and never mutates the
            // manual-stop latch.
            try { ServiceWatchdog.schedule(this) } catch (_: Throwable) {}
            try { ServiceWatchdog.scheduleAlarm(this) } catch (_: Throwable) {}

            if (runtimeActive) {
                ErrorLogger.info("App", "Background guard: runtime active; keepalive renewed ($reason)")
                return
            }

            ErrorLogger.warn("App", "Background guard: bot intended to run but runtime inactive; re-kicking BotService ($reason)")
            val startIntent = Intent(this, BotService::class.java).apply {
                action = BotService.ACTION_START
                putExtra(BotService.EXTRA_USER_REQUESTED, false)
            }
            var directOk = false
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(startIntent)
                else startService(startIntent)
                directOk = true
                ErrorLogger.info("App", "Background guard: BotService startForegroundService issued")
            } catch (fgsBlocked: Throwable) {
                ErrorLogger.warn("App", "Background guard direct FGS start failed (${fgsBlocked.javaClass.simpleName}: ${fgsBlocked.message}) — scheduling AlarmClock")
            }
            if (!directOk) scheduleStartAlarm(startIntent, requestCode = 9913, showCode = 9914, delayMs = 1_000L, label = "background_guard")
        } catch (e: Throwable) {
            ErrorLogger.warn("App", "Background runtime verification failed: ${e.message}")
        }
    }

    private fun scheduleStartAlarm(
        startIntent: Intent,
        requestCode: Int,
        showCode: Int,
        delayMs: Long,
        label: String,
    ) {
        try {
            val am = getSystemService(android.app.AlarmManager::class.java) ?: return
            val pi = android.app.PendingIntent.getService(
                this, requestCode, startIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val showPi = android.app.PendingIntent.getActivity(
                this, showCode,
                Intent(this, com.lifecyclebot.ui.MainActivity::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(
                android.app.AlarmManager.AlarmClockInfo(System.currentTimeMillis() + delayMs, showPi),
                pi
            )
            ErrorLogger.info("App", "BotService ACTION_START scheduled via AlarmClock ($label delayMs=$delayMs)")
        } catch (e: Throwable) {
            ErrorLogger.error("App", "AlarmClock start scheduling failed ($label): ${e.message}", e)
        }
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
                    scheduleServiceRestart(force = false)
                    
                    // Don't call default handler - swallow the exception
                    return@setDefaultUncaughtExceptionHandler
                }
                
            } catch (e: Exception) {
                // If even logging fails, at least print to logcat
                android.util.Log.e("AATEApp", "Failed to log crash: ${e.message}")
                android.util.Log.e("AATEApp", "Original crash: ${throwable.message}", throwable)
            }
            
            // Fatal process crash: if the bot was supposed to be running, schedule
            // resurrection BEFORE delegating to Android's default crash handler. A main
            // thread UI crash kills the whole process, so a live loop right now is not
            // enough — it will be gone in milliseconds.
            try { scheduleServiceRestart(force = true) } catch (_: Throwable) {}

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
    private fun scheduleServiceRestart(force: Boolean = false) {
        try {
            val prefs = getSharedPreferences(BotService.RUNTIME_PREFS, MODE_PRIVATE)
            val wasRunning = prefs.getBoolean(BotService.KEY_WAS_RUNNING_BEFORE_SHUTDOWN, false)
            val manualStop = prefs.getBoolean(BotService.KEY_MANUAL_STOP_REQUESTED, false)

            // V5.9.1074 — fatal UI/process crashes must schedule resurrection even
            // if BotService.isRuntimeActive() is true at crash time, because the
            // default handler is about to terminate the process and kill that live
            // loop. Non-fatal recoverable background exceptions still use the old
            // random-restart guard and schedule only if runtime is actually inactive.
            val isRunningNow = try { BotService.isRuntimeActive() } catch (_: Throwable) { false }
            if (wasRunning && !manualStop && (force || !isRunningNow)) {
                ErrorLogger.info("App", "Bot was running - scheduling restart in 3 seconds force=$force runningNow=$isRunningNow")
                
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

package com.lifecyclebot.perps

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🔔 PERPS NOTIFICATION MANAGER - V5.7.5
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Push notifications for important trading events.
 * 
 * NOTIFICATION TYPES:
 * ─────────────────────────────────────────────────────────────────────────────
 *   🚨 CRITICAL  - Liquidation warning, large loss
 *   🔔 HIGH      - Position opened/closed, big win
 *   📊 NORMAL    - Signal detected, insider alert
 *   📌 LOW       - Learning updates, stats
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object PerpsNotificationManager {
    
    private const val TAG = "🔔Notifications"
    
    // Channel IDs
    private const val CHANNEL_CRITICAL = "perps_critical"
    private const val CHANNEL_TRADING = "perps_trading"
    private const val CHANNEL_SIGNALS = "perps_signals"
    private const val CHANNEL_LEARNING = "perps_learning"
    
    // Notification IDs
    private val notificationIdCounter = AtomicInteger(1000)
    
    // Settings
    private val isEnabled = AtomicBoolean(true)
    private val soundEnabled = AtomicBoolean(true)
    private val vibrationEnabled = AtomicBoolean(true)
    
    // Context reference (set from MainActivity)
    private var appContext: Context? = null
    private var notificationManager: NotificationManager? = null
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Initialize notification system from MainActivity
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        createNotificationChannels()
        
        ErrorLogger.info(TAG, "🔔 Notification Manager initialized")
    }
    
    /**
     * Create notification channels (Android 8.0+)
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = notificationManager ?: return
            
            // Critical channel (liquidation, large losses)
            val criticalChannel = NotificationChannel(
                CHANNEL_CRITICAL,
                "Critical Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Liquidation warnings and large losses"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
            }
            
            // Trading channel (position updates)
            val tradingChannel = NotificationChannel(
                CHANNEL_TRADING,
                "Trading Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Position opened/closed, P&L updates"
            }
            
            // Signals channel (insider alerts, opportunities)
            val signalsChannel = NotificationChannel(
                CHANNEL_SIGNALS,
                "Trading Signals",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Insider alerts and trading signals"
            }
            
            // Learning channel (low priority)
            val learningChannel = NotificationChannel(
                CHANNEL_LEARNING,
                "Learning Updates",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "AI learning progress and insights"
            }
            
            manager.createNotificationChannel(criticalChannel)
            manager.createNotificationChannel(tradingChannel)
            manager.createNotificationChannel(signalsChannel)
            manager.createNotificationChannel(learningChannel)
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // NOTIFICATION TYPES
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class NotificationType {
        CRITICAL,
        TRADING,
        SIGNAL,
        LEARNING,
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TRADING NOTIFICATIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Notify when a position is opened
     */
    fun notifyPositionOpened(
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
        leverage: Int,
        entryPrice: Double,
    ) {
        if (!isEnabled.get()) return
        
        val title = "${direction.symbol} ${market.symbol} Position Opened"
        val message = "Entry: $${"%.2f".format(entryPrice)} | Size: ${"%.3f".format(sizeSol)} SOL | ${leverage}x"
        
        sendNotification(
            type = NotificationType.TRADING,
            title = title,
            message = message,
            emoji = if (direction == PerpsDirection.LONG) "📈" else "📉",
        )
    }
    
    /**
     * Notify when a position is closed
     */
    fun notifyPositionClosed(
        market: PerpsMarket,
        direction: PerpsDirection,
        pnlSol: Double,
        pnlPct: Double,
        reason: String,
    ) {
        if (!isEnabled.get()) return
        
        val isWin = pnlSol > 0
        val emoji = if (isWin) "💰" else "📉"
        val pnlSign = if (pnlSol >= 0) "+" else ""
        
        val title = "$emoji ${market.symbol} Closed: $pnlSign${"%.2f".format(pnlPct)}%"
        val message = "P&L: $pnlSign${"%.4f".format(pnlSol)} SOL | $reason"
        
        // Use critical channel for large losses
        val type = when {
            pnlPct <= -15 -> NotificationType.CRITICAL
            pnlPct >= 20 -> NotificationType.TRADING  // Big wins too
            else -> NotificationType.TRADING
        }
        
        sendNotification(
            type = type,
            title = title,
            message = message,
            emoji = emoji,
        )
    }
    
    /**
     * Notify liquidation warning
     */
    fun notifyLiquidationWarning(
        market: PerpsMarket,
        currentPnlPct: Double,
        liquidationPrice: Double,
        currentPrice: Double,
    ) {
        if (!isEnabled.get()) return
        
        val distanceToLiq = kotlin.math.abs((liquidationPrice - currentPrice) / currentPrice * 100)
        
        val title = "🚨 LIQUIDATION WARNING: ${market.symbol}"
        val message = "Current P&L: ${"%.1f".format(currentPnlPct)}% | Distance to liq: ${"%.1f".format(distanceToLiq)}%"
        
        sendNotification(
            type = NotificationType.CRITICAL,
            title = title,
            message = message,
            emoji = "🚨",
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SIGNAL NOTIFICATIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Notify insider signal detected
     */
    fun notifyInsiderSignal(
        walletLabel: String,
        signalType: String,
        tokenSymbol: String?,
        confidence: Int,
    ) {
        if (!isEnabled.get()) return
        
        val title = "🔍 Insider Alert: $walletLabel"
        val message = "$signalType | ${tokenSymbol ?: "Unknown"} | Conf: $confidence%"
        
        sendNotification(
            type = NotificationType.SIGNAL,
            title = title,
            message = message,
            emoji = "🔍",
        )
    }
    
    /**
     * Notify strong trading signal
     */
    fun notifyStrongSignal(
        market: PerpsMarket,
        direction: PerpsDirection,
        confidence: Int,
        reason: String,
    ) {
        if (!isEnabled.get()) return
        
        val emoji = if (direction == PerpsDirection.LONG) "🟢" else "🔴"
        val title = "$emoji Strong ${direction.name}: ${market.symbol}"
        val message = "Confidence: $confidence% | $reason"
        
        sendNotification(
            type = NotificationType.SIGNAL,
            title = title,
            message = message,
            emoji = emoji,
        )
    }
    
    /**
     * Notify MTF alignment
     */
    fun notifyMTFAlignment(
        market: PerpsMarket,
        direction: String,
        alignedTimeframes: Int,
    ) {
        if (!isEnabled.get() || alignedTimeframes < 5) return
        
        val title = "⏱️ MTF Alignment: ${market.symbol}"
        val message = "$direction on $alignedTimeframes/6 timeframes"
        
        sendNotification(
            type = NotificationType.SIGNAL,
            title = title,
            message = message,
            emoji = "⏱️",
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LEARNING NOTIFICATIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Notify learning milestone
     */
    fun notifyLearningMilestone(
        totalTrades: Int,
        winRate: Double,
        message: String,
    ) {
        if (!isEnabled.get()) return
        
        val title = "🧠 Learning Milestone: $totalTrades Trades"
        val body = "Win Rate: ${"%.1f".format(winRate)}% | $message"
        
        sendNotification(
            type = NotificationType.LEARNING,
            title = title,
            message = body,
            emoji = "🧠",
        )
    }
    
    /**
     * Notify pattern discovered
     */
    fun notifyPatternDiscovered(
        patternType: String,
        market: PerpsMarket,
        accuracy: Double,
    ) {
        if (!isEnabled.get()) return
        
        val title = "🎯 Pattern Found: $patternType"
        val message = "${market.symbol} | Accuracy: ${"%.1f".format(accuracy)}%"
        
        sendNotification(
            type = NotificationType.LEARNING,
            title = title,
            message = message,
            emoji = "🎯",
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CORE NOTIFICATION SENDING
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun sendNotification(
        type: NotificationType,
        title: String,
        message: String,
        emoji: String,
    ) {
        val context = appContext ?: return
        val manager = notificationManager ?: return
        
        val channelId = when (type) {
            NotificationType.CRITICAL -> CHANNEL_CRITICAL
            NotificationType.TRADING -> CHANNEL_TRADING
            NotificationType.SIGNAL -> CHANNEL_SIGNALS
            NotificationType.LEARNING -> CHANNEL_LEARNING
        }
        
        val priority = when (type) {
            NotificationType.CRITICAL -> NotificationCompat.PRIORITY_HIGH
            NotificationType.TRADING -> NotificationCompat.PRIORITY_DEFAULT
            NotificationType.SIGNAL -> NotificationCompat.PRIORITY_DEFAULT
            NotificationType.LEARNING -> NotificationCompat.PRIORITY_LOW
        }
        
        // Create pending intent to open app
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = if (intent != null) {
            PendingIntent.getActivity(
                context, 
                0, 
                intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null
        
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$emoji $title")
            .setContentText(message)
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .apply {
                if (type == NotificationType.CRITICAL && vibrationEnabled.get()) {
                    setVibrate(longArrayOf(0, 500, 200, 500))
                }
            }
            .build()
        
        val notificationId = notificationIdCounter.incrementAndGet()
        
        try {
            manager.notify(notificationId, notification)
            ErrorLogger.debug(TAG, "🔔 Notification sent: $title")
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to send notification: ${e.message}", e)
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SETTINGS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun setEnabled(enabled: Boolean) {
        isEnabled.set(enabled)
        ErrorLogger.info(TAG, "🔔 Notifications ${if (enabled) "enabled" else "disabled"}")
    }
    
    fun isEnabled(): Boolean = isEnabled.get()
    
    fun setSoundEnabled(enabled: Boolean) {
        soundEnabled.set(enabled)
    }
    
    fun setVibrationEnabled(enabled: Boolean) {
        vibrationEnabled.set(enabled)
    }
    
    fun getStats(): Map<String, Any> = mapOf(
        "enabled" to isEnabled.get(),
        "sound" to soundEnabled.get(),
        "vibration" to vibrationEnabled.get(),
        "total_sent" to (notificationIdCounter.get() - 1000),
    )
}

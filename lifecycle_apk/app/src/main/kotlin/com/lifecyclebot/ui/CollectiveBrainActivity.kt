package com.lifecyclebot.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lifecyclebot.R
import com.lifecyclebot.collective.CollectiveLearning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CollectiveBrainActivity - Animated brain visualization of collective intelligence
 * 
 * Features:
 * - Animated brain that pulses with each trade
 * - Progress ring showing trades toward 1M milestone
 * - Live stats from Turso collective database
 * - Mode performance breakdown
 * - Pattern intelligence summary
 */
class CollectiveBrainActivity : AppCompatActivity() {
    
    private lateinit var brainView: AnimatedBrainView
    private lateinit var tvTotalTrades: TextView
    private lateinit var tvTotalProfit: TextView
    private lateinit var tvTotalLoss: TextView
    private lateinit var tvActiveInstances: TextView
    private lateinit var tvPatternsLearned: TextView
    private lateinit var tvBlacklistedTokens: TextView
    private lateinit var tvTopMode: TextView
    private lateinit var tvWorstMode: TextView
    private lateinit var tvWinRate: TextView
    private lateinit var tvAvgHold: TextView
    private lateinit var llModeStats: LinearLayout
    private lateinit var tvDataSource: TextView
    private lateinit var tvSyncStatus: TextView
    private lateinit var btnForceSync: TextView
    
    private val purple = 0xFF9945FF.toInt()
    private val green = 0xFF10B981.toInt()
    private val red = 0xFFEF4444.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val muted = 0xFF6B7280.toInt()
    private val surface = 0xFF111118.toInt()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_collective_brain)
        supportActionBar?.hide()
        
        // Make it fullscreen
        window.statusBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        
        bindViews()
        
        findViewById<View>(R.id.btnBrainBack).setOnClickListener { finish() }
        
        // V3.2: Send heartbeat when screen opens (in case bot isn't running)
        lifecycleScope.launch {
            sendHeartbeatIfEnabled()
        }
        
        // Start polling for updates
        lifecycleScope.launch {
            while (true) {
                try {
                    refreshStats()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Expected when user leaves screen - not an error
                    break
                } catch (e: Exception) {
                    com.lifecyclebot.engine.ErrorLogger.error("CollectiveBrain", "Refresh error: ${e.message}")
                }
                delay(5000) // Refresh every 5 seconds
            }
        }
    }
    
    /**
     * Send a heartbeat to Turso when screen opens (ensures this instance is counted).
     */
    private suspend fun sendHeartbeatIfEnabled() {
        if (!CollectiveLearning.isEnabled()) {
            // Try to initialize if not yet done
            try {
                CollectiveLearning.init(applicationContext)
            } catch (_: Exception) { return }
        }
        
        if (!CollectiveLearning.isEnabled()) return
        
        try {
            val prefs = getSharedPreferences("bot_service", android.content.Context.MODE_PRIVATE)
            val instanceId = prefs.getString("instance_id", null) 
                ?: java.util.UUID.randomUUID().toString().also { 
                    prefs.edit().putString("instance_id", it).apply() 
                }
            
            val config = com.lifecyclebot.data.ConfigStore.load(applicationContext)
            val localStats = com.lifecyclebot.engine.TradeHistoryStore.getStats()
            val pnl24hPct = if (localStats.trades24h > 0) {
                (localStats.pnl24hSol / (localStats.trades24h * 0.1).coerceAtLeast(0.01)) * 100
            } else 0.0
            
            CollectiveLearning.uploadHeartbeat(
                instanceId = instanceId,
                appVersion = com.lifecyclebot.BuildConfig.VERSION_NAME,
                paperMode = config.paperMode,
                trades24h = localStats.trades24h,
                pnl24hPct = pnl24hPct
            )
            
            com.lifecyclebot.engine.ErrorLogger.debug("CollectiveBrain", "💓 Heartbeat sent on screen open")
        } catch (e: Exception) {
            com.lifecyclebot.engine.ErrorLogger.debug("CollectiveBrain", "Heartbeat error: ${e.message}")
        }
    }
    
    private fun bindViews() {
        brainView = findViewById(R.id.brainView)
        tvTotalTrades = findViewById(R.id.tvBrainTotalTrades)
        tvTotalProfit = findViewById(R.id.tvBrainTotalProfit)
        tvTotalLoss = findViewById(R.id.tvBrainTotalLoss)
        tvActiveInstances = findViewById(R.id.tvBrainActiveInstances)
        tvPatternsLearned = findViewById(R.id.tvBrainPatternsLearned)
        tvBlacklistedTokens = findViewById(R.id.tvBrainBlacklistedTokens)
        tvTopMode = findViewById(R.id.tvBrainTopMode)
        tvWorstMode = findViewById(R.id.tvBrainWorstMode)
        llModeStats = findViewById(R.id.llBrainModeStats)
        tvDataSource = findViewById(R.id.tvBrainDataSource)
        tvSyncStatus = findViewById(R.id.tvSyncStatus)
        tvWinRate = try { findViewById(R.id.tvBrainWinRate) } catch (_: Exception) { TextView(this) }
        tvAvgHold = try { findViewById(R.id.tvBrainAvgHold) } catch (_: Exception) { TextView(this) }
        btnForceSync = findViewById(R.id.btnForceSync)
        
        // V4.20: Force sync button
        btnForceSync.setOnClickListener {
            lifecycleScope.launch {
                tvSyncStatus.text = "🔄 Syncing..."
                btnForceSync.isEnabled = false
                btnForceSync.alpha = 0.5f
                
                try {
                    val result = withContext(Dispatchers.IO) {
                        CollectiveLearning.forceSyncNow()
                    }
                    
                    // Show result in a toast
                    android.widget.Toast.makeText(this@CollectiveBrainActivity, result, android.widget.Toast.LENGTH_LONG).show()
                    
                    // Refresh the UI
                    refreshStats()
                    updateSyncStatus()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this@CollectiveBrainActivity, "Sync failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
                
                btnForceSync.isEnabled = true
                btnForceSync.alpha = 1.0f
            }
        }
        
        // V5.2: Long-press for diagnostics
        btnForceSync.setOnLongClickListener {
            lifecycleScope.launch {
                tvSyncStatus.text = "🔬 Running diagnostics..."
                
                try {
                    val result = withContext(Dispatchers.IO) {
                        CollectiveLearning.runDiagnostics()
                    }
                    
                    // Show diagnostics in a dialog
                    android.app.AlertDialog.Builder(this@CollectiveBrainActivity, R.style.Theme_AATE_Dialog)
                        .setTitle("🔬 Collective Diagnostics")
                        .setMessage(result)
                        .setPositiveButton("Close") { d, _ -> d.dismiss() }
                        .show()
                        
                    updateSyncStatus()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this@CollectiveBrainActivity, "Diagnostics failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            true
        }
        
        // Initial sync status
        updateSyncStatus()
        
        // ═══════════════════════════════════════════════════════════════
        // V5.7.8: BOOST BUTTON — Scan/Backtest Collective Learning (24h cooldown)
        // Uses XML btnBoost — removed duplicate programmatic button that was inserting into top bar
        // ═══════════════════════════════════════════════════════════════
        val boostPrefs = getSharedPreferences("collective_boost", android.content.Context.MODE_PRIVATE)
        
        // Use the XML-defined boost button (in layout below the brain view)
        val btnBoost = findViewById<android.widget.TextView>(R.id.btnBoost)
        
        // Check cooldown
        fun updateBoostState() {
            val lastBoost = boostPrefs.getLong("last_boost_time", 0L)
            val elapsed = System.currentTimeMillis() - lastBoost
            val cooldownMs = 24 * 60 * 60 * 1000L
            if (elapsed < cooldownMs) {
                val hoursLeft = ((cooldownMs - elapsed) / (60 * 60 * 1000.0)).toInt()
                val minsLeft = (((cooldownMs - elapsed) % (60 * 60 * 1000L)) / (60 * 1000.0)).toInt()
                btnBoost.text = "⚡ BOOST — ${hoursLeft}h ${minsLeft}m cooldown"
                btnBoost.setBackgroundResource(R.drawable.pill_bg)
                btnBoost.setTextColor(0xFF888888.toInt())
                btnBoost.isEnabled = false
                btnBoost.alpha = 0.6f
            } else {
                btnBoost.text = "⚡ BOOST — Scan & Backtest"
                btnBoost.setBackgroundResource(R.drawable.pill_bg_yellow)
                btnBoost.setTextColor(0xFF000000.toInt())
                btnBoost.isEnabled = true
                btnBoost.alpha = 1.0f
            }
        }
        updateBoostState()
        
        btnBoost.setOnClickListener {
            val lastBoost = boostPrefs.getLong("last_boost_time", 0L)
            val elapsed = System.currentTimeMillis() - lastBoost
            if (elapsed < 24 * 60 * 60 * 1000L) {
                android.widget.Toast.makeText(this, "Boost available in ${((24 * 60 * 60 * 1000L - elapsed) / (60 * 60 * 1000.0)).toInt()}h", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            btnBoost.text = "⚡ BOOSTING..."
            btnBoost.setBackgroundResource(R.drawable.pill_bg)
            btnBoost.isEnabled = false
            btnBoost.alpha = 0.5f
            
            lifecycleScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        runCollectiveBoost()
                    }
                    
                    // Record boost time
                    boostPrefs.edit().putLong("last_boost_time", System.currentTimeMillis()).apply()
                    
                    android.app.AlertDialog.Builder(this@CollectiveBrainActivity, R.style.Theme_AATE_Dialog)
                        .setTitle("⚡ Boost Complete")
                        .setMessage(result)
                        .setPositiveButton("Nice") { d, _ -> d.dismiss() }
                        .show()
                    
                    refreshStats()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this@CollectiveBrainActivity, "Boost failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
                updateBoostState()
            }
        }
    }
    
    private fun updateSyncStatus() {
        val status = CollectiveLearning.getSyncStatus()
        tvSyncStatus.text = status.statusMessage
        tvSyncStatus.setTextColor(if (status.isConnected) green else muted)
    }
    
    private suspend fun refreshStats() {
        // ═══════════════════════════════════════════════════════════════════════════
        // V4.0: USE REAL COLLECTIVE STATS FROM THE HIVE MIND
        // No more mixing local + collective - show TRUE network data when connected
        // ═══════════════════════════════════════════════════════════════════════════
        
        val isTursoEnabled = CollectiveLearning.isEnabled()
        
        // V4.0: Get REAL collective stats from the network
        val collectiveStats = if (isTursoEnabled) {
            try {
                CollectiveLearning.getCollectiveStats()
            } catch (_: Exception) { null }
        } else null
        
        // V4.0: Get top performing modes from collective
        val topModes = if (isTursoEnabled) {
            try {
                CollectiveLearning.getTopModes(5)
            } catch (_: Exception) { emptyList() }
        } else emptyList()
        
        // V4.0: Get modes to avoid
        val avoidModes = if (isTursoEnabled) {
            try {
                CollectiveLearning.getModesToAvoid(3)
            } catch (_: Exception) { emptyList() }
        } else emptyList()
        
        // V4.0: Get hot tokens from network
        val hotTokens = if (isTursoEnabled) {
            try {
                CollectiveLearning.getHotTokens(6, 5)
            } catch (_: Exception) { emptyList() }
        } else emptyList()
        
        // V4.0: Get network signals (broadcasts from other bots)
        val networkSignals = if (isTursoEnabled) {
            try {
                CollectiveLearning.getNetworkSignals(10)
            } catch (_: Exception) { emptyList() }
        } else emptyList()
        
        // Get basic stats for fallback
        val basicStats = CollectiveLearning.getStats()
        val collectivePatterns = basicStats["patterns"] as? Int ?: 0
        val collectiveBlacklisted = basicStats["blacklistedTokens"] as? Int ?: 0
        
        // Get local stats for comparison
        val localStats = com.lifecyclebot.engine.TradeHistoryStore.getStats()
        val localWinRate = localStats.winRate  // Already a percentage (0-100)
        
        // Determine data source and display values
        val hasCollectiveData = collectiveStats != null && collectiveStats.totalTrades > 0
        
        // V4.0 FIX: Better status detection
        // Check if we're actually connected vs just initialized with empty data
        val isActuallyConnected = isTursoEnabled && collectiveStats != null
        val hasNetworkData = hasCollectiveData
        
        // Extract values based on connection state
        val dataSourceLabel: String
        val displayTrades: Int
        val displayWinRate: Double
        val displayAvgPnl: Double
        val activeUsers: Int
        
        if (hasNetworkData) {
            val stats = collectiveStats!!
            dataSourceLabel = "🌐 HIVE MIND"
            displayTrades = stats.totalTrades
            displayWinRate = stats.winRate
            displayAvgPnl = stats.avgPnlPct
            activeUsers = stats.activeUsers24h
        } else if (isActuallyConnected) {
            // Connected but collective stats shows 0 trades - could be sync issue
            // Show local trades but indicate we're connected
            val localTradeCount = localStats.totalStoredTrades
            if (localTradeCount > 0) {
                dataSourceLabel = "🌐 SYNCING..."
                displayTrades = localTradeCount
                displayWinRate = localWinRate
                displayAvgPnl = 0.0
                activeUsers = collectiveStats?.activeUsers24h ?: 1
            } else {
                dataSourceLabel = "🌐 CONNECTED (No trades yet)"
                displayTrades = 0
                displayWinRate = 0.0
                displayAvgPnl = 0.0
                activeUsers = collectiveStats?.activeUsers24h ?: 1
            }
        } else if (isTursoEnabled) {
            // Turso enabled but connection may be failing
            dataSourceLabel = "⚠️ CONNECTION ISSUE"
            displayTrades = localStats.totalStoredTrades
            displayWinRate = localWinRate
            displayAvgPnl = 0.0
            activeUsers = 1
        } else {
            dataSourceLabel = "📱 LOCAL ONLY"
            displayTrades = localStats.totalStoredTrades
            displayWinRate = localWinRate
            displayAvgPnl = 0.0
            activeUsers = 1
        }
        
        withContext(Dispatchers.Main) {
            // Update data source label
            tvDataSource.text = dataSourceLabel
            tvDataSource.setTextColor(when {
                hasNetworkData -> green
                isActuallyConnected -> 0xFF10B981.toInt()  // Green for connected
                isTursoEnabled -> 0xFFF59E0B.toInt()       // Amber for connection issue
                else -> purple
            })
            
            // Update brain animation
            val progress = (displayTrades.toFloat() / 1_000_000f).coerceIn(0f, 1f)
            brainView.setProgress(progress)
            brainView.setTradeCount(displayTrades)
            
            // Update trade count
            tvTotalTrades.text = formatNumber(displayTrades)
            
            // V4.0: Show COLLECTIVE win rate and avg PnL (not local!)
            if (hasCollectiveData && collectiveStats != null) {
                // Show win rate as profit indicator
                val wrText = "${collectiveStats.winRate.toInt()}% WR"
                val avgText = "${if (collectiveStats.avgPnlPct >= 0) "+" else ""}${String.format("%.1f", collectiveStats.avgPnlPct)}%"
                tvTotalProfit.text = "$wrText | $avgText avg"
                tvTotalProfit.setTextColor(if (collectiveStats.winRate >= 50) green else red)
                
                // Show total trades breakdown
                tvTotalLoss.text = "${collectiveStats.totalWins}W / ${collectiveStats.totalLosses}L"
                tvTotalLoss.setTextColor(muted)
            } else {
                // Fallback to local
                tvTotalProfit.text = "${displayWinRate.toInt()}% WR"
                tvTotalProfit.setTextColor(if (displayWinRate >= 50) green else muted)
                tvTotalLoss.text = "Local data"
                tvTotalLoss.setTextColor(muted)
            }
            
            // Active users
            tvActiveInstances.text = "$activeUsers"
            tvActiveInstances.setTextColor(if (activeUsers > 1) green else white)
            
            // Patterns and blacklist
            tvPatternsLearned.text = "$collectivePatterns"
            tvBlacklistedTokens.text = "$collectiveBlacklisted"
            
            // WIN RATE and AVG HOLD — use collective data when available, local otherwise
            val displayWrFinal = if (hasCollectiveData && collectiveStats != null)
                collectiveStats.winRate else displayWinRate
            tvWinRate.text = if (displayWrFinal > 0) "${displayWrFinal.toInt()}%" else "--"
            tvWinRate.setTextColor(when {
                displayWrFinal >= 60 -> green
                displayWrFinal >= 50 -> 0xFF10B981.toInt()
                displayWrFinal > 0  -> 0xFFF59E0B.toInt()
                else -> muted
            })
            
            // AVG HOLD — derive from local trade history (avgHoldMins field)
            val avgHoldMins = try {
                val stats = com.lifecyclebot.engine.TradeHistoryStore.getStats()
                stats.avgHoldMins
            } catch (_: Exception) { 0.0 }
            tvAvgHold.text = when {
                avgHoldMins >= 60 -> "${(avgHoldMins / 60).toInt()}h"
                avgHoldMins > 0  -> "${avgHoldMins.toInt()}m"
                else -> "--"
            }
            tvAvgHold.setTextColor(white)

            // V4.0: Show top mode from collective ranking
            if (topModes.isNotEmpty()) {
                val best = topModes.first()
                tvTopMode.text = "${best.modeName.take(12)} ${best.winRate.toInt()}%"
                tvTopMode.setTextColor(green)
            } else {
                tvTopMode.text = "—"
            }
            
            // V4.0: Show worst mode to avoid
            if (avoidModes.isNotEmpty()) {
                val worst = avoidModes.first()
                tvWorstMode.text = "${worst.modeName.take(12)} ${worst.avgPnlPct.toInt()}%"
                tvWorstMode.setTextColor(red)
            } else {
                tvWorstMode.text = "—"
            }
            
            // V4.0: Update mode stats panel with TOP MODES from collective
            updateModeStatsV4(topModes, avoidModes, hotTokens, networkSignals)
            
            // Pulse the brain if trades increased
            if (displayTrades > brainView.lastTradeCount) {
                brainView.pulse()
            }
        }
    }
    
    /**
     * V4.0: Update mode stats with REAL collective data
     */
    private fun updateModeStatsV4(
        topModes: List<CollectiveLearning.ModeRanking>,
        avoidModes: List<CollectiveLearning.ModeRanking>,
        hotTokens: List<CollectiveLearning.HotToken>,
        networkSignals: List<CollectiveLearning.NetworkSignal>
    ) {
        llModeStats.removeAllViews()
        
        // Section: Top Modes
        if (topModes.isNotEmpty()) {
            addSectionHeader("🏆 TOP MODES")
            topModes.take(3).forEachIndexed { index, mode ->
                val emoji = when (index) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "•" }
                addModeRow(emoji, mode.modeName, mode.winRate, mode.avgPnlPct, true)
            }
        }
        
        // Section: Hot Tokens from Network
        if (networkSignals.isNotEmpty()) {
            addSectionHeader("📡 NETWORK SIGNALS")
            networkSignals.filter { it.signalType in listOf("MEGA_WINNER", "HOT_TOKEN") }.take(3).forEach { signal ->
                val emoji = if (signal.signalType == "MEGA_WINNER") "🔥" else "🌐"
                addTokenRow(emoji, signal.symbol, signal.pnlPct, signal.ackCount)
            }
        } else if (hotTokens.isNotEmpty()) {
            addSectionHeader("🔥 HOT TOKENS (6h)")
            hotTokens.take(3).forEach { token ->
                addTokenRow("💎", token.symbol, token.avgPnlPct, token.botsTrading)
            }
        }
        
        // Section: Avoid
        if (avoidModes.isNotEmpty() && avoidModes.first().avgPnlPct < -5) {
            addSectionHeader("⚠️ AVOID")
            avoidModes.take(2).forEach { mode ->
                addModeRow("❌", mode.modeName, mode.winRate, mode.avgPnlPct, false)
            }
        }
    }
    
    private fun addSectionHeader(title: String) {
        llModeStats.addView(android.widget.TextView(this).apply {
            text = title
            textSize = 11f
            setTextColor(muted)
            setPadding(0, dp(8), 0, dp(4))
        })
    }
    
    private fun addModeRow(emoji: String, name: String, winRate: Double, avgPnl: Double, isPositive: Boolean) {
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
        }
        
        row.addView(android.widget.TextView(this).apply {
            text = "$emoji ${name.take(14)}"
            textSize = 12f
            setTextColor(white)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        
        row.addView(android.widget.TextView(this).apply {
            val pnlStr = if (avgPnl >= 0) "+${avgPnl.toInt()}%" else "${avgPnl.toInt()}%"
            text = "${winRate.toInt()}% | $pnlStr"
            textSize = 12f
            setTextColor(if (isPositive) green else red)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        })
        
        llModeStats.addView(row)
    }
    
    private fun addTokenRow(emoji: String, symbol: String, pnlPct: Double, count: Int) {
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
        }
        
        row.addView(android.widget.TextView(this).apply {
            text = "$emoji $symbol"
            textSize = 12f
            setTextColor(white)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        
        row.addView(android.widget.TextView(this).apply {
            text = "+${pnlPct.toInt()}% (${count}🤖)"
            textSize = 12f
            setTextColor(green)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        })
        
        llModeStats.addView(row)
    }
    
    private fun formatNumber(n: Int): String {
        return when {
            n >= 1_000_000 -> "${String.format("%.1f", n / 1_000_000.0)}M"
            n >= 1_000 -> "${String.format("%.1f", n / 1_000.0)}K"
            else -> "$n"
        }
    }
    
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

/**
 * Custom view that renders an animated brain with progress ring
 * V4.0: Enhanced with continuous pulse animation and better scaling
 */
class AnimatedBrainView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    private var progress = 0f
    private var pulseScale = 1f
    private var breatheScale = 1f  // V4.0: Continuous breathing animation
    private var tradeCount = 0
    var lastTradeCount = 0
        private set
    
    // V4.0: Track if we're in "live" mode (receiving continuous data)
    private var isLiveMode = false
    private var lastUpdateTime = 0L
    
    // Paints
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A0A0F")
    }
    
    private val ringBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1F2937")
        style = Paint.Style.STROKE
        strokeWidth = 24f  // V4.0: Thicker ring
        strokeCap = Paint.Cap.ROUND
    }
    
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9945FF")
        style = Paint.Style.STROKE
        strokeWidth = 24f  // V4.0: Thicker ring
        strokeCap = Paint.Cap.ROUND
    }
    
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9945FF")
        maskFilter = BlurMaskFilter(40f, BlurMaskFilter.Blur.OUTER)  // V4.0: Larger glow
    }
    
    private val brainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9945FF")
        textSize = 160f  // V4.0: Larger brain emoji
        textAlign = Paint.Align.CENTER
    }
    
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 56f  // V4.0: Larger text
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    
    private val subtextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B7280")
        textSize = 28f  // V4.0: Larger subtext
        textAlign = Paint.Align.CENTER
    }
    
    private val ringRect = RectF()
    
    // Pulse animation
    private var pulseAnimator: ValueAnimator? = null
    
    // V4.0: Continuous breathing animation for "live" state
    private var breatheAnimator: ValueAnimator? = null
    
    init {
        // Start the breathing animation
        startBreathing()
    }
    
    private fun startBreathing() {
        breatheAnimator?.cancel()
        breatheAnimator = ValueAnimator.ofFloat(1f, 1.03f, 1f).apply {
            duration = 2500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                breatheScale = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
    
    fun setProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        invalidate()
    }
    
    fun setTradeCount(count: Int) {
        lastTradeCount = tradeCount
        tradeCount = count
        
        // V4.0: Auto-pulse when trade count increases
        if (count > lastTradeCount) {
            pulse()
            lastUpdateTime = System.currentTimeMillis()
            
            // Enable live mode if getting frequent updates
            if (!isLiveMode) {
                isLiveMode = true
                setLiveMode(true)
            }
        }
        
        invalidate()
    }
    
    // V4.0: Set live mode for more dynamic animation
    fun setLiveMode(live: Boolean) {
        isLiveMode = live
        if (live) {
            // Speed up breathing when live
            breatheAnimator?.duration = 1500
        } else {
            breatheAnimator?.duration = 2500
        }
    }
    
    fun pulse() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.2f, 1f).apply {
            duration = 500
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                pulseScale = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val cx = width / 2f
        val cy = height / 2f
        // V4.0: Better scaling - use more of the available space
        val radius = (minOf(width, height) / 2f - 30f) * 0.92f
        
        // Background
        canvas.drawColor(Color.parseColor("#000000"))
        
        // V4.0: Combined scale from pulse + breathing
        val combinedScale = pulseScale * breatheScale
        
        // Glow behind brain (when progress > 0 or live mode)
        if (progress > 0 || isLiveMode) {
            val glowRadius = radius * 0.65f * combinedScale
            val glowAlpha = if (isLiveMode) 80 else (progress * 100).toInt().coerceIn(0, 100)
            glowPaint.alpha = glowAlpha
            canvas.drawCircle(cx, cy, glowRadius, glowPaint)
        }
        
        // Progress ring background
        ringRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(ringRect, -90f, 360f, false, ringBgPaint)
        
        // Progress ring
        val sweepAngle = progress * 360f
        canvas.drawArc(ringRect, -90f, sweepAngle, false, ringPaint)
        
        // Brain emoji (scaled with pulse + breathing)
        canvas.save()
        canvas.scale(combinedScale, combinedScale, cx, cy)
        canvas.drawText("🧠", cx, cy - 10f, brainPaint)
        canvas.restore()
        
        // Trade count
        val countStr = when {
            tradeCount >= 1_000_000 -> "${String.format("%.1f", tradeCount / 1_000_000.0)}M"
            tradeCount >= 1_000 -> "${String.format("%.1f", tradeCount / 1_000.0)}K"
            else -> "$tradeCount"
        }
        canvas.drawText(countStr, cx, cy + 100f, textPaint)
        
        // Label
        val label = if (isLiveMode) "live trades" else "collective trades"
        canvas.drawText(label, cx, cy + 140f, subtextPaint)
        
        // Progress percentage at bottom
        val pctStr = "${String.format("%.2f", progress * 100)}% to 1M"
        canvas.drawText(pctStr, cx, cy + radius - 10f, subtextPaint)
    }
}

    // ═══════════════════════════════════════════════════════════════════════
    // V5.7.8: COLLECTIVE BOOST — Scan & Backtest using collective data
    // ═══════════════════════════════════════════════════════════════════════
    
    private suspend fun runCollectiveBoost(): String {
        val sb = StringBuilder()
        sb.appendLine("⚡ COLLECTIVE BOOST RESULTS")
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        
        // 1. Sync latest collective data
        try {
            val syncResult = CollectiveLearning.forceSyncNow()
            sb.appendLine("📡 Sync: $syncResult")
        } catch (e: Exception) {
            sb.appendLine("📡 Sync: Using cached data (${e.message})")
        }
        
        // 2. Get collective stats for backtesting
        val stats = try { CollectiveLearning.getCollectiveStats() } catch (_: Exception) { null }
        val topModes = try { CollectiveLearning.getTopModes(10) } catch (_: Exception) { emptyList() }
        val avoidModes = try { CollectiveLearning.getModesToAvoid(5) } catch (_: Exception) { emptyList() }
        
        sb.appendLine()
        sb.appendLine("📊 COLLECTIVE INTELLIGENCE SCAN")
        if (stats != null) {
            sb.appendLine("Network Trades: ${stats.totalTrades}")
            sb.appendLine("Network WR: ${String.format("%.1f", stats.winRate)}%")
            sb.appendLine("Active Users 24h: ${stats.activeUsers24h}")
            sb.appendLine("Avg PnL: ${String.format("%.2f", stats.avgPnlPct)}%")
        }
        
        // 3. Backtest top modes against collective data
        sb.appendLine()
        sb.appendLine("🏆 TOP MODE PERFORMANCE")
        topModes.take(5).forEachIndexed { i, ranking ->
            sb.appendLine("${i + 1}. ${ranking.modeName}: WR=${String.format("%.1f", ranking.winRate)}% | Trades=${ranking.trades} | PnL=${String.format("%.2f", ranking.avgPnlPct)}%")
        }
        
        // 4. Modes to suppress
        if (avoidModes.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("SUPPRESS THESE MODES")
            avoidModes.forEach { ranking ->
                sb.appendLine("X ${ranking.modeName}: WR=${String.format("%.1f", ranking.winRate)}% (${ranking.trades} trades)")
            }
        }
        
        // 5. Feed collective insights into V4 StrategyTrustAI
        try {
            topModes.forEach { ranking ->
                val lesson = com.lifecyclebot.v4.meta.TradeLesson(
                    id = "BOOST_${ranking.modeName}_${System.currentTimeMillis()}",
                    strategy = ranking.modeName,
                    market = "MEME",
                    symbol = "COLLECTIVE",
                    entryRegime = com.lifecyclebot.v4.meta.GlobalRiskMode.RISK_ON,
                    entrySession = com.lifecyclebot.v4.meta.SessionContext.OFF_HOURS,
                    trustScore = ranking.winRate / 100.0,
                    fragilityScore = 0.3,
                    narrativeHeat = 0.5,
                    portfolioHeat = 0.3,
                    leverageUsed = 1.0,
                    executionConfidence = 0.8,
                    leadSource = null,
                    expectedDelaySec = null,
                    outcomePct = ranking.avgPnlPct,
                    mfePct = ranking.avgPnlPct * 1.5,
                    maePct = -(100 - ranking.winRate) / 10.0,
                    holdSec = 300,
                    exitReason = if (ranking.avgPnlPct > 0) "TAKE_PROFIT" else "STOP_LOSS",
                    expectedFillPrice = 1.0,
                    actualFillPrice = 1.0,
                    slippagePct = 0.5,
                    executionRoute = "JUPITER_V6"
                )
                com.lifecyclebot.v4.meta.StrategyTrustAI.recordTrade(lesson)
            }
            sb.appendLine()
            sb.appendLine("🧠 V4 META UPDATE")
            sb.appendLine("Fed ${topModes.size} mode insights to StrategyTrustAI")
            
            // Fuse snapshot
            val snapshot = com.lifecyclebot.v4.meta.CrossTalkFusionEngine.fuse()
            sb.appendLine("Regime: ${snapshot.globalRiskMode.name}")
            sb.appendLine("Leverage Cap: ${snapshot.leverageCap.toInt()}x")
            sb.appendLine("Portfolio Heat: ${(snapshot.portfolioHeat * 100).toInt()}%")
            
            val trust = com.lifecyclebot.v4.meta.StrategyTrustAI.getAllTrustScores()
            if (trust.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("📈 STRATEGY TRUST SCORES")
                trust.entries.sortedByDescending { it.value.trustScore }.forEach { (name, record) ->
                    sb.appendLine("${record.trustLevel.name.take(4).padEnd(4)} ${name}: ${(record.trustScore * 100).toInt()}%")
                }
            }
        } catch (e: Exception) {
            sb.appendLine("V4 update: ${e.message}")
        }
        
        sb.appendLine()
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine("Next boost available in 24 hours")
        
        return sb.toString()
    }

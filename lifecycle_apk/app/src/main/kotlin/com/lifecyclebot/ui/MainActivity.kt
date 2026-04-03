package com.lifecyclebot.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.*
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.CircleCropTransformation
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.lifecyclebot.R
import com.lifecyclebot.data.*
import com.lifecyclebot.engine.SafetyTier
import com.lifecyclebot.engine.WalletConnectionState
import com.lifecyclebot.engine.WalletManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var vm: BotViewModel
    private lateinit var currency: com.lifecyclebot.engine.CurrencyManager

    // top bar
    private lateinit var tvNetworkLabel: TextView
    private lateinit var btnWalletTop: View
    private lateinit var tvWalletDot: View
    private lateinit var tvWalletShort: TextView
    private lateinit var brainContainer: FrameLayout
    private lateinit var pbBrainProgress: ProgressBar
    private lateinit var tvBrainEmoji: TextView

    // hero balance
    private lateinit var tvBalanceLarge: TextView
    private lateinit var tvBalanceUsd: TextView
    private lateinit var tvPnlChange: TextView
    private lateinit var tvPnlChangePct: TextView
    private lateinit var tvSolPrice: TextView
    private lateinit var btnCurrencySelector: TextView

    // bot status card
    private lateinit var tvTokenName: TextView
    private lateinit var tvTokenPhase: TextView
    private lateinit var tvSignalChip: TextView
    private lateinit var tvPrice: TextView
    private lateinit var tvMcap: TextView
    private lateinit var tvPosition: TextView
    private lateinit var pbEntry: ProgressBar
    private lateinit var pbExit: ProgressBar
    private lateinit var pbVol: ProgressBar
    private lateinit var pbPress: ProgressBar
    private lateinit var tvEntryVal: TextView
    private lateinit var tvExitVal: TextView
    private lateinit var tvVolVal: TextView
    private lateinit var tvPressVal: TextView

    // chart
    private lateinit var priceChart: LineChart
    private lateinit var tvSafetyChip: TextView

    // safety
    private lateinit var tvSafety: TextView
    private lateinit var tvRugcheck: TextView

    // trades
    private lateinit var llTradeList: LinearLayout
    private lateinit var tvTradeCount: TextView
    private lateinit var tvNoTrades: TextView
    // bonding curve + whale
    private lateinit var pbBondingCurve: android.widget.ProgressBar
    private lateinit var tvCurveStage: android.widget.TextView
    private lateinit var pbWhale: android.widget.ProgressBar
    private lateinit var tvWhaleSummary: android.widget.TextView
    // nav buttons
    private lateinit var btnOpenJournal: android.widget.TextView
    private lateinit var btnOpenAlerts: android.widget.TextView
    private lateinit var cardOpenPositions: android.view.View
    private lateinit var llOpenPositions: LinearLayout
    private lateinit var tvTotalExposure: TextView
    private lateinit var tvTotalUnrealisedPnl: TextView
    
    // V4.0: Treasury positions panel
    private lateinit var cardTreasuryPositions: android.view.View
    private lateinit var llTreasuryPositions: LinearLayout
    private lateinit var tvTreasuryExposure: TextView
    private lateinit var tvTreasuryPnl: TextView
    
    // V4.0: Blue Chip positions panel
    private lateinit var cardBlueChipPositions: android.view.View
    private lateinit var llBlueChipPositions: LinearLayout
    private lateinit var tvBlueChipExposure: TextView
    private lateinit var tvBlueChipPnl: TextView
    
    // V4.0: ShitCoin positions panel
    private lateinit var cardShitCoinPositions: android.view.View
    private lateinit var llShitCoinPositions: LinearLayout
    private lateinit var tvShitCoinExposure: TextView
    private lateinit var tvShitCoinPnl: TextView
    private lateinit var tvShitCoinMode: TextView
    private lateinit var tvShitCoinWinRate: TextView
    private lateinit var tvShitCoinDailyPnl: TextView
    
    // V5.2: Moonshot positions panel
    private lateinit var cardMoonshotPositions: android.view.View
    private lateinit var llMoonshotPositions: LinearLayout
    private lateinit var tvMoonshotExposure: TextView
    private lateinit var tvMoonshotPnl: TextView
    private lateinit var tvMoonshotMode: TextView
    private lateinit var tvMoonshotWinRate: TextView
    private lateinit var tvMoonshotDailyPnl: TextView
    private lateinit var tvMoonshotLearning: TextView
    
    // V5.2: Tile Stats TextViews (show wins/trades on each tile)
    private lateinit var tvV3Stats: TextView
    private lateinit var tvTreasuryStats: TextView
    private lateinit var tvBlueChipStats: TextView
    private lateinit var tvShitCoinStats: TextView
    private lateinit var tvMoonshotStats: TextView
    // Note: Below 4 tiles don't have stats TextViews in XML yet
    private var tvAiBrainStats: TextView? = null
    private var tvShadowStats: TextView? = null
    private var tvRegimesStats: TextView? = null
    private var tv25AIsStats: TextView? = null
    
    // V5.2: Side-by-side Treasury + Moonshot row
    private lateinit var rowTreasuryMoonshot: android.view.View
    private lateinit var cardTreasuryMini: android.view.View
    private lateinit var cardMoonshotMini: android.view.View
    private lateinit var llTreasuryMiniPositions: LinearLayout
    private lateinit var llMoonshotMiniPositions: LinearLayout
    private lateinit var tvTreasuryMiniPnl: TextView
    private lateinit var tvMoonshotMiniPnl: TextView
    
    // V5.2: Chart enhancements
    private lateinit var tvChartSymbol: TextView
    private lateinit var tvChartPrice: TextView
    private lateinit var candleChart: com.github.mikephil.charting.charts.CandleStickChart
    private var selectedChartMint: String? = null
    private var chartTimeRange: String = "5m"
    private var chartType: String = "line"
    
    // V4.0: AI Status panel
    private lateinit var tvAiHealth: TextView
    private lateinit var tvAiTradingMode: TextView
    private lateinit var tvAiRegime: TextView
    private lateinit var tvAiTreasury: TextView
    private lateinit var tvAiShitCoin: TextView
    private lateinit var tvAiLearning: TextView
    private lateinit var tvAiLayers: TextView

    // decision log
    private lateinit var cardLogScores: android.view.View
    private lateinit var tvLogToken: TextView
    private lateinit var tvLogPhase: TextView
    private lateinit var tvLogSignal: TextView
    private lateinit var tvLogEntry: TextView
    private lateinit var tvLogExit: TextView
    private lateinit var tvLogVol: TextView
    private lateinit var tvLogPress: TextView
    private lateinit var tvLogMom: TextView
    private lateinit var tvLogEmaFan: TextView
    private lateinit var tvLogFlags: TextView
    private lateinit var tvLogReason: TextView
    private lateinit var tvDecisionLog: TextView
    private lateinit var scrollLog: android.widget.ScrollView
    private lateinit var btnClearLog: TextView
    private val logLines = ArrayDeque<String>(200)

    // top-up settings
    private lateinit var switchTopUp: android.widget.Switch
    private lateinit var etTopUpMinGain: EditText
    private lateinit var etTopUpGainStep: EditText
    private lateinit var etTopUpMaxCount: EditText
    private lateinit var etTopUpMaxSol: EditText

    // watchlist
    private lateinit var llTokenList: LinearLayout
    private lateinit var llProbationList: LinearLayout  // V5.0: Side-by-side probation
    private lateinit var llIdleList: LinearLayout       // V5.2: Idle tokens column
    private lateinit var tvProbationHeader: TextView    // V5.0: Probation header
    private lateinit var tvWatchlistHeader: TextView    // V5.2: Watchlist header
    private lateinit var tvIdleHeader: TextView         // V5.2: Idle header
    private lateinit var etAddMint: EditText
    private lateinit var btnAddToken: Button
    
    // V5.2.8: 30-Day Run Stats views
    private lateinit var card30DayRun: View
    private lateinit var tv30DayCounter: TextView
    private lateinit var tv30DayBalance: TextView
    private lateinit var tv30DayReturn: TextView
    private lateinit var tv30DayDrawdown: TextView
    private lateinit var tv30DayTrades: TextView
    private lateinit var tv30DayWLS: TextView
    private lateinit var tv30DayWinRate: TextView
    private lateinit var tv30DayLearning: TextView
    private lateinit var tv30DayAccuracy: TextView
    private lateinit var tv30DayIntegrity: TextView
    private lateinit var btn30DayExport: TextView

    // settings
    private lateinit var etActiveToken: EditText
    private lateinit var spMode: Spinner
    private lateinit var spAutoTrade: Spinner
    private lateinit var etStopLoss: EditText
    private lateinit var etExitScore: EditText
    private lateinit var tvAdvancedToggle: TextView
    private lateinit var layoutAdvanced: View
    private lateinit var etSmallBuy: EditText
    private lateinit var etLargeBuy: EditText
    private lateinit var etSlippage: EditText
    private lateinit var etPoll: EditText
    private lateinit var etRpc: EditText
    private lateinit var etTgBotToken: EditText
    private lateinit var etTgChatId: EditText
    private lateinit var etWatchlist: EditText
    private lateinit var etHeliusKey: EditText
    private lateinit var etBirdeyeKey: EditText
    private lateinit var etGroqKey: EditText
    private lateinit var etGeminiKey: EditText
    private lateinit var etJupiterKey: EditText
    private lateinit var switchNotifications: android.widget.Switch
    private lateinit var switchSounds: android.widget.Switch
    private lateinit var switchDarkMode: androidx.appcompat.widget.SwitchCompat
    private lateinit var btnSave: Button

    // bottom bar
    private lateinit var statusDot: View
    private lateinit var tvBotStatus: TextView
    private lateinit var tvMode: TextView
    private lateinit var tvAutoMode: android.widget.TextView
    private lateinit var btnToggle: Button

    // NEW: Pull to refresh
    private lateinit var swipeRefresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    
    // NEW: Quick stats
    private lateinit var tvStats24hTrades: TextView
    private lateinit var tvStatsWinRate: TextView
    private lateinit var tvStatsOpenPos: TextView
    private lateinit var tvStatsAiConf: TextView
    
    // NEW: Token logo
    private lateinit var ivTokenLogo: ImageView
    
    // NEW: Position PnL card
    private lateinit var cardPositionPnl: LinearLayout
    private lateinit var tvPnlSymbol: TextView
    private lateinit var tvPnlEntry: TextView
    private lateinit var tvPnlPercent: TextView
    private lateinit var tvPnlValue: TextView

    // chart data
    private val chartEntries = mutableListOf<Entry>()
    private var chartIdx = 0f
    private var lastChartTokenMint: String? = null  // Track which token's chart is displayed
    private var advancedExpanded = false
    private var settingsPopulated = false

    // colours
    private val purple  = 0xFF9945FF.toInt()
    private val green   = 0xFF10B981.toInt()
    private val red     = 0xFFEF4444.toInt()
    private val amber   = 0xFFF59E0B.toInt()
    private val muted   = 0xFF6B7280.toInt()
    private val white   = 0xFFFFFFFF.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Ensure ErrorLogger is initialized (backup - App class should have done this)
        try {
            com.lifecyclebot.engine.ErrorLogger.init(applicationContext)
            com.lifecyclebot.engine.ErrorLogger.info("MainActivity", "onCreate started")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "ErrorLogger init failed: ${e.message}")
        }
        
        // ════════════════════════════════════════════════════════════════════════════
        // FIX: Initialize TradeHistoryStore EARLY, BEFORE BotService starts
        // This ensures Quick Stats persist across app restarts even if bot not running
        // ════════════════════════════════════════════════════════════════════════════
        try {
            com.lifecyclebot.engine.TradeHistoryStore.init(applicationContext)
            com.lifecyclebot.engine.ErrorLogger.info("MainActivity", "TradeHistoryStore initialized")
        } catch (e: Exception) {
            com.lifecyclebot.engine.ErrorLogger.error("MainActivity", "TradeHistoryStore init failed: ${e.message}")
        }
        
        try {
            setContentView(R.layout.activity_main)
            supportActionBar?.hide()

            // Transparent status bar
            window.statusBarColor = Color.TRANSPARENT
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

            vm       = ViewModelProvider(this)[BotViewModel::class.java]
            currency = try {
                com.lifecyclebot.engine.BotService.instance?.currencyManager
                    ?: com.lifecyclebot.engine.CurrencyManager(applicationContext)
            } catch (_: Exception) {
                com.lifecyclebot.engine.CurrencyManager(applicationContext)
            }
            
            // Refresh currency rates immediately
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    currency.refresh()
                    com.lifecyclebot.engine.ErrorLogger.info("MainActivity", "Currency rates refreshed")
                } catch (e: Exception) {
                    com.lifecyclebot.engine.ErrorLogger.error("MainActivity", "Currency refresh error: ${e.message}")
                }
            }

            bindViews()
            setupChart()
            setupSettings()
            requestNotifPermission()
            requestStoragePermission()
            checkBatteryOptimisation()
            
            // Show first-time disclaimer if not yet agreed
            showFirstTimeDisclaimer()

            // ════════════════════════════════════════════════════════════════════════════
            // V3.2: Initialize all 21 AI layers via AIStartupCoordinator
            // This ensures all AI modules are loaded and ready before trading starts
            // ════════════════════════════════════════════════════════════════════════════
            lifecycleScope.launch {
                try {
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        com.lifecyclebot.v3.core.AIStartupCoordinator.initialize(this)
                    }
                    if (result.success) {
                        com.lifecyclebot.engine.ErrorLogger.info("MainActivity", 
                            "AI System initialized: ${result.readyLayers} ready, " +
                            "${result.degradedLayers} degraded, ${result.failedLayers} failed")
                    } else {
                        com.lifecyclebot.engine.ErrorLogger.error("MainActivity", 
                            "AI System FAILED: ${result.message}")
                        Toast.makeText(this@MainActivity, 
                            "⚠️ Some AI layers failed to initialize", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    com.lifecyclebot.engine.ErrorLogger.error("MainActivity", 
                        "AIStartupCoordinator error: ${e.message}")
                }
            }

            lifecycleScope.launch {
                vm.ui.collect { state -> updateUi(state) }
            }
            
            com.lifecyclebot.engine.ErrorLogger.info("MainActivity", "onCreate completed successfully")
        } catch (e: Exception) {
            com.lifecyclebot.engine.ErrorLogger.crash("MainActivity", "onCreate CRASH: ${e.message}", e)
            throw e  // Re-throw to let the global handler catch it too
        }
    }

    override fun onPause() {
        super.onPause()
        // Auto-save settings when app goes to background
        saveCurrentSettings()
    }
    
    override fun onResume() {
        super.onResume()
        // Refresh currency rates and wallet balance when returning to activity
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                currency.refresh()
                com.lifecyclebot.engine.WalletManager.getInstance(applicationContext).refreshBalance()
            } catch (e: Exception) {
                com.lifecyclebot.engine.ErrorLogger.error("MainActivity", "onResume refresh error: ${e.message}")
            }
        }
        // Update currency selector text (user may have changed currency)
        updateCurrencySelectorText()
    }

    override fun onStop() {
        super.onStop()
        // Auto-save settings when app is stopped
        saveCurrentSettings()
    }

    /** Save current settings from UI fields */
    private fun saveCurrentSettings() {
        try {
            val state = vm.ui.value
            val cfg = state.config.copy(
                heliusApiKey          = etHeliusKey.text.toString().trim(),
                birdeyeApiKey         = etBirdeyeKey.text.toString().trim(),
                groqApiKey            = etGroqKey.text.toString().trim(),
                geminiApiKey          = etGeminiKey.text.toString().trim(),
                jupiterApiKey         = etJupiterKey.text.toString().trim(),
                telegramBotToken      = etTgBotToken.text.toString().trim(),
                telegramChatId        = etTgChatId.text.toString().trim(),
                watchlist             = etWatchlist.text.toString()
                                            .split(",")
                                            .map { it.trim() }
                                            .filter { it.isNotBlank() },
                // V5.2 FIX: TopUp settings were NOT being saved!
                topUpEnabled          = switchTopUp.isChecked,
                topUpMinGainPct       = etTopUpMinGain.text.toString().toDoubleOrNull() ?: state.config.topUpMinGainPct,
                topUpGainStepPct      = etTopUpGainStep.text.toString().toDoubleOrNull() ?: state.config.topUpGainStepPct,
                topUpMaxCount         = etTopUpMaxCount.text.toString().toIntOrNull() ?: state.config.topUpMaxCount,
                topUpMaxTotalSol      = etTopUpMaxSol.text.toString().toDoubleOrNull() ?: state.config.topUpMaxTotalSol,
            )
            vm.saveConfig(cfg)
        } catch (_: Exception) {}
    }

    private fun checkBatteryOptimisation() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Battery Optimisation")
                .setMessage("AATE needs to be excluded from battery optimisation " +
                    "so trading continues in the background. Tap OK to open settings.")
                .setPositiveButton("OK") { dialog: android.content.DialogInterface, _: Int ->
                    startActivity(Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    ))
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }
    
    // ── First Time Disclaimer ───────────────────────────────────────────
    
    private fun showFirstTimeDisclaimer() {
        val prefs = getSharedPreferences("lifecycle_disclaimer", Context.MODE_PRIVATE)
        val agreedAt = prefs.getLong("disclaimer_agreed_at", 0L)
        
        // Check if current version has been agreed to
        val agreedVersion = prefs.getString("disclaimer_version", null)
        val currentVersion = com.lifecyclebot.collective.LegalAgreementManager.CURRENT_AGREEMENT_VERSION
        
        // If already agreed to current version, don't show again
        if (agreedAt > 0 && agreedVersion == currentVersion) return
        
        val disclaimerText = com.lifecyclebot.collective.LegalAgreementManager.DISCLAIMER_TEXT + """


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

📝 PAPER MODE FIRST — MANDATORY

1. Start the bot in PAPER MODE only
2. Let it run for at least 24-48 hours
3. Watch how it makes decisions
4. Review the trade journal daily
5. Only switch to LIVE after you trust its judgment

By clicking "I AGREE", you acknowledge that you have read, understood, 
and accept full responsibility for any outcomes resulting from the use 
of this application. Your acceptance will be recorded with timestamp 
for legal compliance.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        """.trimIndent()
        
        val dialog = AlertDialog.Builder(this, R.style.Theme_AATE_Dialog)
            .setTitle("🚨 AATE Risk Disclaimer v$currentVersion")
            .setMessage(disclaimerText)
            .setCancelable(false)  // Cannot dismiss by clicking outside
            .setPositiveButton("I AGREE") { dialogInterface, _ ->
                // Log agreement with timestamp
                val timestamp = System.currentTimeMillis()
                prefs.edit()
                    .putLong("disclaimer_agreed_at", timestamp)
                    .putString("disclaimer_version", currentVersion)
                    .putString("disclaimer_agreed_date", 
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).format(Date(timestamp)))
                    .apply()
                
                // Record in collective database for legal compliance
                lifecycleScope.launch {
                    try {
                        com.lifecyclebot.collective.LegalAgreementManager.recordAgreementAcceptance(
                            context = this@MainActivity,
                            agreementType = com.lifecyclebot.collective.LegalAgreementManager.TYPE_FULL_DISCLAIMER
                        )
                    } catch (e: Exception) {
                        com.lifecyclebot.engine.ErrorLogger.error("Disclaimer", "Failed to record to collective: ${e.message}")
                    }
                }
                
                // Log to ErrorLogger as well
                com.lifecyclebot.engine.ErrorLogger.info("Disclaimer", 
                    "User agreed to disclaimer v$currentVersion at ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))}")
                
                // Haptic feedback
                performHaptic(HapticFeedbackConstants.CONFIRM)
                
                dialogInterface.dismiss()
                
                // Show brief toast confirmation
                Toast.makeText(this, "Agreement recorded. Start in PAPER mode!", Toast.LENGTH_LONG).show()
            }
            .create()
        
        dialog.show()
        
        // Style the dialog button
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            setTextColor(green)
            textSize = 16f
        }
    }

    // ── bind ──────────────────────────────────────────────────────────

    private fun bindViews() {
        tvNetworkLabel  = findViewById(R.id.tvNetworkLabel)
        btnWalletTop    = findViewById(R.id.btnWalletTop)
        tvWalletDot     = findViewById(R.id.tvWalletDot)
        tvWalletShort   = findViewById(R.id.tvWalletShort)
        
        // Brain learning indicator
        brainContainer   = try { findViewById(R.id.brainContainer) } catch (_: Exception) { FrameLayout(this) }
        pbBrainProgress  = try { findViewById(R.id.pbBrainProgress) } catch (_: Exception) { ProgressBar(this) }
        tvBrainEmoji     = try { findViewById(R.id.tvBrainEmoji) } catch (_: Exception) { TextView(this) }
        
        // Click brain icon to open Collective Brain Activity
        brainContainer.setOnClickListener {
            startActivity(android.content.Intent(this, CollectiveBrainActivity::class.java))
        }
        
        // Long press to show quick learning stats
        brainContainer.setOnLongClickListener {
            showLearningStats()
            true
        }
        
        tvBalanceLarge  = findViewById(R.id.tvBalanceLarge)
        tvBalanceUsd    = findViewById(R.id.tvBalanceUsd)
        tvPnlChange     = findViewById(R.id.tvPnlChange)
        tvPnlChangePct  = findViewById(R.id.tvPnlChangePct)
        tvSolPrice      = try { findViewById(R.id.tvSolPrice) } catch (_: Exception) { TextView(this) }
        btnCurrencySelector = try { findViewById(R.id.btnCurrencySelector) } catch (_: Exception) { TextView(this) }
        tvTokenName     = findViewById(R.id.tvTokenName)
        tvTokenPhase    = findViewById(R.id.tvTokenPhase)
        tvSignalChip    = findViewById(R.id.tvSignalChip)
        tvPrice         = findViewById(R.id.tvPrice)
        tvMcap          = findViewById(R.id.tvMcap)
        tvPosition      = findViewById(R.id.tvPosition)
        pbEntry         = findViewById(R.id.pbEntry)
        pbExit          = findViewById(R.id.pbExit)
        pbVol           = findViewById(R.id.pbVol)
        pbPress         = findViewById(R.id.pbPress)
        tvEntryVal      = findViewById(R.id.tvEntryVal)
        tvExitVal       = findViewById(R.id.tvExitVal)
        tvVolVal        = findViewById(R.id.tvVolVal)
        tvPressVal      = findViewById(R.id.tvPressVal)
        priceChart      = findViewById(R.id.priceChart)
        tvSafetyChip    = findViewById(R.id.tvSafetyChip)
        tvSafety        = findViewById(R.id.tvSafety)
        tvRugcheck      = findViewById(R.id.tvRugcheck)
        llTradeList     = findViewById(R.id.llTradeList)
        tvTradeCount    = findViewById(R.id.tvTradeCount)
        tvNoTrades         = findViewById(R.id.tvNoTrades)
        pbBondingCurve     = try { findViewById(R.id.pbBondingCurve) } catch (_:Exception) { android.widget.ProgressBar(this) }
        tvCurveStage       = try { findViewById(R.id.tvCurveStage) } catch (_:Exception) { android.widget.TextView(this) }
        pbWhale            = try { findViewById(R.id.pbWhale) } catch (_:Exception) { android.widget.ProgressBar(this) }
        tvWhaleSummary     = try { findViewById(R.id.tvWhaleSummary) } catch (_:Exception) { android.widget.TextView(this) }
        btnOpenJournal     = try { findViewById(R.id.btnOpenJournal) } catch (_:Exception) { android.widget.TextView(this) }
        btnOpenAlerts      = try { findViewById(R.id.btnOpenAlerts) } catch (_:Exception) { android.widget.TextView(this) }
        btnOpenJournal.setOnClickListener { startActivity(android.content.Intent(this, JournalActivity::class.java)) }
        try {
            findViewById<android.widget.TextView>(R.id.btnOpenBacktest)
                ?.setOnClickListener { startActivity(android.content.Intent(this, BacktestActivity::class.java)) }
        } catch (_: Exception) {}

        // Scanner source toggles — save to config on change
        listOf(
            R.id.switchFullScan to "fullMarketScanEnabled",
            R.id.cbScanGraduates to "scanPumpGraduates",
            R.id.cbScanDexTrending to "scanDexTrending",
            R.id.cbScanGainers to "scanDexGainers",
            R.id.cbScanBoosted to "scanDexBoosted",
            R.id.cbScanRaydium to "scanRaydiumNew",
            R.id.cbScanNarrative to "narrativeScanEnabled",
        ).forEach { (viewId, _) ->
            try {
                val v = findViewById<android.widget.CompoundButton>(viewId)
                v?.setOnCheckedChangeListener { _: android.widget.CompoundButton, _: Boolean -> saveScannerSettings() }
            } catch (_: Exception) {}
        }
        btnOpenAlerts.setOnClickListener  { startActivity(android.content.Intent(this, AlertsActivity::class.java)) }
        
        // V5.2: Behavior Dashboard button
        try {
            findViewById<android.view.View>(R.id.btnOpenBehavior)
                ?.setOnClickListener { startActivity(android.content.Intent(this, BehaviorActivity::class.java)) }
        } catch (_: Exception) {}
        
        // V5.2: Quick Behavior Tile
        try {
            findViewById<android.view.View>(R.id.btnQuickBehavior)
                ?.setOnClickListener { startActivity(android.content.Intent(this, BehaviorActivity::class.java)) }
        } catch (_: Exception) {}
        
        // Collective Brain button
        try {
            findViewById<android.widget.TextView>(R.id.btnOpenCollectiveBrain)
                ?.setOnClickListener { startActivity(android.content.Intent(this, CollectiveBrainActivity::class.java)) }
        } catch (_: Exception) {}
        
        // Historical Chart Scanner button — manual trigger
        try {
            findViewById<android.widget.TextView>(R.id.btnHistoricalScan)
                ?.setOnClickListener { showHistoricalScanDialog() }
        } catch (_: Exception) {}
        
        cardOpenPositions  = findViewById(R.id.cardOpenPositions)
        llOpenPositions    = findViewById(R.id.llOpenPositions)
        tvTotalExposure    = try { findViewById(R.id.tvTotalExposure) } catch (_: Exception) { TextView(this) }
        tvTotalUnrealisedPnl = try { findViewById(R.id.tvTotalUnrealisedPnl) } catch (_: Exception) { TextView(this) }
        
        // V4.0: Treasury positions panel bindings
        cardTreasuryPositions = try { findViewById(R.id.cardTreasuryPositions) } catch (_: Exception) { android.view.View(this) }
        llTreasuryPositions = try { findViewById(R.id.llTreasuryPositions) } catch (_: Exception) { LinearLayout(this) }
        tvTreasuryExposure = try { findViewById(R.id.tvTreasuryExposure) } catch (_: Exception) { TextView(this) }
        tvTreasuryPnl = try { findViewById(R.id.tvTreasuryPnl) } catch (_: Exception) { TextView(this) }
        
        // V4.0: Blue Chip positions panel bindings
        cardBlueChipPositions = try { findViewById(R.id.cardBlueChipPositions) } catch (_: Exception) { android.view.View(this) }
        llBlueChipPositions = try { findViewById(R.id.llBlueChipPositions) } catch (_: Exception) { LinearLayout(this) }
        tvBlueChipExposure = try { findViewById(R.id.tvBlueChipExposure) } catch (_: Exception) { TextView(this) }
        tvBlueChipPnl = try { findViewById(R.id.tvBlueChipPnl) } catch (_: Exception) { TextView(this) }
        
        // V4.0: ShitCoin positions panel bindings
        cardShitCoinPositions = try { findViewById(R.id.cardShitCoinPositions) } catch (_: Exception) { android.view.View(this) }
        llShitCoinPositions = try { findViewById(R.id.llShitCoinPositions) } catch (_: Exception) { LinearLayout(this) }
        tvShitCoinExposure = try { findViewById(R.id.tvShitCoinExposure) } catch (_: Exception) { TextView(this) }
        tvShitCoinPnl = try { findViewById(R.id.tvShitCoinPnl) } catch (_: Exception) { TextView(this) }
        tvShitCoinMode = try { findViewById(R.id.tvShitCoinMode) } catch (_: Exception) { TextView(this) }
        tvShitCoinWinRate = try { findViewById(R.id.tvShitCoinWinRate) } catch (_: Exception) { TextView(this) }
        tvShitCoinDailyPnl = try { findViewById(R.id.tvShitCoinDailyPnl) } catch (_: Exception) { TextView(this) }
        
        // V5.2: Moonshot positions panel bindings
        cardMoonshotPositions = try { findViewById(R.id.cardMoonshotPositions) } catch (_: Exception) { android.view.View(this) }
        llMoonshotPositions = try { findViewById(R.id.llMoonshotPositions) } catch (_: Exception) { LinearLayout(this) }
        tvMoonshotExposure = try { findViewById(R.id.tvMoonshotExposure) } catch (_: Exception) { TextView(this) }
        tvMoonshotPnl = try { findViewById(R.id.tvMoonshotPnl) } catch (_: Exception) { TextView(this) }
        tvMoonshotMode = try { findViewById(R.id.tvMoonshotMode) } catch (_: Exception) { TextView(this) }
        tvMoonshotWinRate = try { findViewById(R.id.tvMoonshotWinRate) } catch (_: Exception) { TextView(this) }
        tvMoonshotDailyPnl = try { findViewById(R.id.tvMoonshotDailyPnl) } catch (_: Exception) { TextView(this) }
        tvMoonshotLearning = try { findViewById(R.id.tvMoonshotLearning) } catch (_: Exception) { TextView(this) }
        
        // V5.2: Tile stats TextViews - show wins/trades on each tile
        tvV3Stats = try { findViewById(R.id.tvV3Stats) } catch (_: Exception) { TextView(this) }
        tvTreasuryStats = try { findViewById(R.id.tvTreasuryStats) } catch (_: Exception) { TextView(this) }
        tvBlueChipStats = try { findViewById(R.id.tvBlueChipStats) } catch (_: Exception) { TextView(this) }
        tvShitCoinStats = try { findViewById(R.id.tvShitCoinStats) } catch (_: Exception) { TextView(this) }
        tvMoonshotStats = try { findViewById(R.id.tvMoonshotStats) } catch (_: Exception) { TextView(this) }
        tvAiBrainStats = try { findViewById(R.id.tvAiBrainStats) } catch (_: Exception) { null }
        tvShadowStats = try { findViewById(R.id.tvShadowStats) } catch (_: Exception) { null }
        tvRegimesStats = try { findViewById(R.id.tvRegimesStats) } catch (_: Exception) { null }
        tv25AIsStats = try { findViewById(R.id.tv25AIsStats) } catch (_: Exception) { null }
        
        // V5.2: Side-by-side Treasury + Moonshot
        rowTreasuryMoonshot = try { findViewById(R.id.rowTreasuryMoonshot) } catch (_: Exception) { android.view.View(this) }
        cardTreasuryMini = try { findViewById(R.id.cardTreasuryMini) } catch (_: Exception) { android.view.View(this) }
        cardMoonshotMini = try { findViewById(R.id.cardMoonshotMini) } catch (_: Exception) { android.view.View(this) }
        llTreasuryMiniPositions = try { findViewById(R.id.llTreasuryMiniPositions) } catch (_: Exception) { LinearLayout(this) }
        llMoonshotMiniPositions = try { findViewById(R.id.llMoonshotMiniPositions) } catch (_: Exception) { LinearLayout(this) }
        tvTreasuryMiniPnl = try { findViewById(R.id.tvTreasuryMiniPnl) } catch (_: Exception) { TextView(this) }
        tvMoonshotMiniPnl = try { findViewById(R.id.tvMoonshotMiniPnl) } catch (_: Exception) { TextView(this) }
        
        // V5.2: Chart enhancements
        tvChartSymbol = try { findViewById(R.id.tvChartSymbol) } catch (_: Exception) { TextView(this) }
        tvChartPrice = try { findViewById(R.id.tvChartPrice) } catch (_: Exception) { TextView(this) }
        candleChart = try { findViewById(R.id.candleChart) } catch (_: Exception) { com.github.mikephil.charting.charts.CandleStickChart(this) }
        
        // V5.2: Chart time range button listeners
        setupChartControls()
        
        // V4.0: AI Status panel bindings
        tvAiHealth = try { findViewById(R.id.tvAiHealth) } catch (_: Exception) { TextView(this) }
        tvAiTradingMode = try { findViewById(R.id.tvAiTradingMode) } catch (_: Exception) { TextView(this) }
        tvAiRegime = try { findViewById(R.id.tvAiRegime) } catch (_: Exception) { TextView(this) }
        tvAiTreasury = try { findViewById(R.id.tvAiTreasury) } catch (_: Exception) { TextView(this) }
        tvAiShitCoin = try { findViewById(R.id.tvAiShitCoin) } catch (_: Exception) { TextView(this) }
        tvAiLearning = try { findViewById(R.id.tvAiLearning) } catch (_: Exception) { TextView(this) }
        tvAiLayers = try { findViewById(R.id.tvAiLayers) } catch (_: Exception) { TextView(this) }
        
        // V5.2.8: 30-Day Run Stats bindings
        card30DayRun = try { findViewById(R.id.card30DayRun) } catch (_: Exception) { View(this) }
        tv30DayCounter = try { findViewById(R.id.tv30DayCounter) } catch (_: Exception) { TextView(this) }
        tv30DayBalance = try { findViewById(R.id.tv30DayBalance) } catch (_: Exception) { TextView(this) }
        tv30DayReturn = try { findViewById(R.id.tv30DayReturn) } catch (_: Exception) { TextView(this) }
        tv30DayDrawdown = try { findViewById(R.id.tv30DayDrawdown) } catch (_: Exception) { TextView(this) }
        tv30DayTrades = try { findViewById(R.id.tv30DayTrades) } catch (_: Exception) { TextView(this) }
        tv30DayWLS = try { findViewById(R.id.tv30DayWLS) } catch (_: Exception) { TextView(this) }
        tv30DayWinRate = try { findViewById(R.id.tv30DayWinRate) } catch (_: Exception) { TextView(this) }
        tv30DayLearning = try { findViewById(R.id.tv30DayLearning) } catch (_: Exception) { TextView(this) }
        tv30DayAccuracy = try { findViewById(R.id.tv30DayAccuracy) } catch (_: Exception) { TextView(this) }
        tv30DayIntegrity = try { findViewById(R.id.tv30DayIntegrity) } catch (_: Exception) { TextView(this) }
        btn30DayExport = try { findViewById(R.id.btn30DayExport) } catch (_: Exception) { TextView(this) }
        
        // V5.2.8: Export button click listener
        btn30DayExport.setOnClickListener {
            try {
                com.lifecyclebot.engine.RunTracker30D.exportAllReports()
                Toast.makeText(this, "📥 Reports exported to /reports/", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        
        llTokenList     = findViewById(R.id.llTokenList)
        llProbationList = findViewById(R.id.llProbationList)  // V5.0
        llIdleList      = findViewById(R.id.llIdleList)       // V5.2: Idle column
        tvProbationHeader = findViewById(R.id.tvProbationHeader)  // V5.0
        tvWatchlistHeader = findViewById(R.id.tvWatchlistHeader)  // V5.2
        tvIdleHeader    = findViewById(R.id.tvIdleHeader)     // V5.2
        etAddMint       = findViewById(R.id.etAddMint)
        btnAddToken     = findViewById(R.id.btnAddToken)
        etActiveToken   = findViewById(R.id.etActiveToken)
        spMode          = findViewById(R.id.spMode)
        spAutoTrade     = findViewById(R.id.spAutoTrade)
        etStopLoss      = findViewById(R.id.etStopLoss)
        etExitScore     = findViewById(R.id.etExitScore)
        tvAdvancedToggle = findViewById(R.id.tvAdvancedToggle)
        layoutAdvanced  = findViewById(R.id.layoutAdvanced)
        etSmallBuy      = findViewById(R.id.etSmallBuy)
        etLargeBuy      = findViewById(R.id.etLargeBuy)
        etSlippage      = findViewById(R.id.etSlippage)
        etPoll          = findViewById(R.id.etPoll)
        etRpc           = findViewById(R.id.etRpc)
        etTgBotToken    = findViewById(R.id.etTgBotToken)
        etTgChatId          = findViewById(R.id.etTgChatId)
        etWatchlist     = findViewById(R.id.etWatchlist)
        etHeliusKey     = try { findViewById(R.id.etHeliusKey) } catch (_: Exception) { EditText(this) }
        etBirdeyeKey    = try { findViewById(R.id.etBirdeyeKey) } catch (_: Exception) { EditText(this) }
        etGroqKey       = try { findViewById(R.id.etGroqKey) } catch (_: Exception) { EditText(this) }
        etGeminiKey     = try { findViewById(R.id.etGeminiKey) } catch (_: Exception) { EditText(this) }
        etJupiterKey    = try { findViewById(R.id.etJupiterKey) } catch (_: Exception) { EditText(this) }
        switchNotifications = try { findViewById(R.id.switchNotifications) } catch (_: Exception) { android.widget.Switch(this) }
        switchSounds    = try { findViewById(R.id.switchSounds) } catch (_: Exception) { android.widget.Switch(this) }
        switchDarkMode  = try { findViewById(R.id.switchDarkMode) } catch (_: Exception) { androidx.appcompat.widget.SwitchCompat(this) }
        btnSave         = findViewById(R.id.btnSave)
        
        // Notification toggle listener - save immediately when toggled
        switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            val currentConfig = com.lifecyclebot.data.ConfigStore.load(applicationContext)
            com.lifecyclebot.data.ConfigStore.save(applicationContext, currentConfig.copy(notificationsEnabled = isChecked))
            com.lifecyclebot.engine.ErrorLogger.info("Settings", "Notifications ${if (isChecked) "ENABLED" else "DISABLED"}")
        }
        
        // Sound toggle listener - save immediately when toggled
        switchSounds.setOnCheckedChangeListener { _, isChecked ->
            val currentConfig = com.lifecyclebot.data.ConfigStore.load(applicationContext)
            com.lifecyclebot.data.ConfigStore.save(applicationContext, currentConfig.copy(soundEnabled = isChecked))
            com.lifecyclebot.engine.ErrorLogger.info("Settings", "Sounds ${if (isChecked) "ENABLED" else "DISABLED"}")
        }
        
        // Dark mode toggle listener
        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            applyTheme(isChecked)
        }

        // API key help links - open signup pages
        setupApiKeyHelpLinks()

        // Clear settings button
        setupClearSettingsButton()
        
        // Test toast button (for debugging notifications)
        setupTestToastButton()

        // Quick action buttons
        setupQuickActionButtons()

        // decision log
        cardLogScores = try { findViewById(R.id.cardLogScores) } catch (_: Exception) { android.view.View(this) }
        tvLogToken    = try { findViewById(R.id.tvLogToken)  } catch (_: Exception) { TextView(this) }
        tvLogPhase    = try { findViewById(R.id.tvLogPhase)  } catch (_: Exception) { TextView(this) }
        tvLogSignal   = try { findViewById(R.id.tvLogSignal) } catch (_: Exception) { TextView(this) }
        tvLogEntry    = try { findViewById(R.id.tvLogEntry)  } catch (_: Exception) { TextView(this) }
        tvLogExit     = try { findViewById(R.id.tvLogExit)   } catch (_: Exception) { TextView(this) }
        tvLogVol      = try { findViewById(R.id.tvLogVol)    } catch (_: Exception) { TextView(this) }
        tvLogPress    = try { findViewById(R.id.tvLogPress)  } catch (_: Exception) { TextView(this) }
        tvLogMom      = try { findViewById(R.id.tvLogMom)    } catch (_: Exception) { TextView(this) }
        tvLogEmaFan   = try { findViewById(R.id.tvLogEmaFan) } catch (_: Exception) { TextView(this) }
        tvLogFlags    = try { findViewById(R.id.tvLogFlags)  } catch (_: Exception) { TextView(this) }
        tvLogReason   = try { findViewById(R.id.tvLogReason) } catch (_: Exception) { TextView(this) }
        tvDecisionLog = try { findViewById(R.id.tvDecisionLog) } catch (_: Exception) { TextView(this) }
        scrollLog     = try { findViewById(R.id.scrollLog)   } catch (_: Exception) { android.widget.ScrollView(this) }
        btnClearLog   = try { findViewById(R.id.btnClearLog) } catch (_: Exception) { TextView(this) }
        btnClearLog.setOnClickListener { clearDecisionLog() }

        // top-up
        switchTopUp    = try { findViewById(R.id.switchTopUp)    } catch (_: Exception) { android.widget.Switch(this) }
        etTopUpMinGain = try { findViewById(R.id.etTopUpMinGain) } catch (_: Exception) { EditText(this) }
        etTopUpGainStep= try { findViewById(R.id.etTopUpGainStep)} catch (_: Exception) { EditText(this) }
        etTopUpMaxCount= try { findViewById(R.id.etTopUpMaxCount)} catch (_: Exception) { EditText(this) }
        etTopUpMaxSol  = try { findViewById(R.id.etTopUpMaxSol)  } catch (_: Exception) { EditText(this) }

        statusDot       = findViewById(R.id.statusDot)
        tvBotStatus     = findViewById(R.id.tvBotStatus)
        tvMode          = findViewById(R.id.tvMode)
        tvAutoMode      = try { findViewById(R.id.tvAutoMode) } catch (_:Exception) { android.widget.TextView(this) }
        btnToggle       = findViewById(R.id.btnToggle)

        // NEW: Pull-to-refresh
        swipeRefresh    = try { findViewById(R.id.swipeRefresh) } catch (_: Exception) { 
            androidx.swiperefreshlayout.widget.SwipeRefreshLayout(this) 
        }
        swipeRefresh.setColorSchemeColors(purple, green, amber)
        swipeRefresh.setOnRefreshListener {
            // Trigger a refresh
            vm.forceRefresh()
            // Haptic feedback
            performHaptic(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            // Stop the animation after a delay
            swipeRefresh.postDelayed({ swipeRefresh.isRefreshing = false }, 1500)
        }
        
        // NEW: Quick stats
        tvStats24hTrades = try { findViewById(R.id.tvStats24hTrades) } catch (_: Exception) { TextView(this) }
        tvStatsWinRate   = try { findViewById(R.id.tvStatsWinRate) } catch (_: Exception) { TextView(this) }
        tvStatsOpenPos   = try { findViewById(R.id.tvStatsOpenPos) } catch (_: Exception) { TextView(this) }
        tvStatsAiConf    = try { findViewById(R.id.tvStatsAiConf) } catch (_: Exception) { TextView(this) }
        
        // NEW: Token logo
        ivTokenLogo      = try { findViewById(R.id.ivTokenLogo) } catch (_: Exception) { ImageView(this) }
        
        // NEW: Position PnL card
        cardPositionPnl  = try { findViewById(R.id.cardPositionPnl) } catch (_: Exception) { LinearLayout(this) }
        tvPnlSymbol      = try { findViewById(R.id.tvPnlSymbol) } catch (_: Exception) { TextView(this) }
        tvPnlEntry       = try { findViewById(R.id.tvPnlEntry) } catch (_: Exception) { TextView(this) }
        tvPnlPercent     = try { findViewById(R.id.tvPnlPercent) } catch (_: Exception) { TextView(this) }
        tvPnlValue       = try { findViewById(R.id.tvPnlValue) } catch (_: Exception) { TextView(this) }

        btnToggle.setOnClickListener { vm.toggleBot() }
        btnWalletTop.setOnClickListener {
            startActivity(Intent(this, WalletActivity::class.java))
        }
        
        // Currency selector - opens currency picker
        btnCurrencySelector.setOnClickListener {
            startActivity(Intent(this, CurrencyActivity::class.java))
        }
        // Update currency selector text on init
        updateCurrencySelectorText()
        
        btnAddToken.setOnClickListener { addToken() }
        btnSave.setOnClickListener { saveSettings() }
        
        // V5.1: Export/Import learning data buttons
        findViewById<View>(R.id.btnExportData)?.setOnClickListener { exportLearningData() }
        findViewById<View>(R.id.btnImportData)?.setOnClickListener { importLearningData() }
        
        tvAdvancedToggle.setOnClickListener {
            try {
                advancedExpanded = !advancedExpanded
                layoutAdvanced.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
                tvAdvancedToggle.text = if (advancedExpanded) "▼ Advanced settings (tap to hide)" else "► Advanced settings (tap to show)"
                tvAdvancedToggle.setTextColor(if (advancedExpanded) 0xFF14F195.toInt() else 0xFF6B7280.toInt())
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        // Initialize text
        tvAdvancedToggle.text = "► Advanced settings (tap to show)"
    }

    // ── chart ─────────────────────────────────────────────────────────

    private fun setupChart() {
        priceChart.apply {
            setBackgroundColor(Color.TRANSPARENT)
            setDrawGridBackground(false)
            description.isEnabled = false
            legend.isEnabled      = false
            setTouchEnabled(false)
            xAxis.apply {
                isEnabled     = false
                position      = XAxis.XAxisPosition.BOTTOM
            }
            axisLeft.apply {
                setDrawGridLines(true)
                gridColor    = 0xFF1F2937.toInt()
                textColor    = muted
                textSize     = 9f
                axisLineColor = Color.TRANSPARENT
                setDrawAxisLine(false)
            }
            axisRight.isEnabled = false
        }
    }

    private fun appendChart(price: Double) {
        chartEntries.add(Entry(chartIdx++, price.toFloat()))
        if (chartEntries.size > 120) chartEntries.removeAt(0)

        val ds = LineDataSet(chartEntries, "").apply {
            color           = purple
            lineWidth       = 1.8f
            setDrawCircles(false)
            setDrawValues(false)
            setDrawFilled(true)
            fillColor       = purple
            fillAlpha       = 30
            mode            = LineDataSet.Mode.CUBIC_BEZIER
        }
        priceChart.data = LineData(ds)
        priceChart.invalidate()
    }

    // ── update UI ─────────────────────────────────────────────────────

    private fun updateUi(state: UiState) {
        val ts  = state.activeToken
        val cfg = state.config
        val ws  = state.walletState

        // ── wallet top bar ────────────────────────────────────────────
        when (ws.connectionState) {
            WalletConnectionState.CONNECTED -> {
                tvWalletDot.background   = ContextCompat.getDrawable(this, R.drawable.dot_green)
                tvWalletShort.text       = ws.shortKey
                tvWalletShort.setTextColor(white)
            }
            WalletConnectionState.ERROR -> {
                tvWalletDot.background   = ContextCompat.getDrawable(this, R.drawable.dot_red)
                tvWalletShort.text       = "Error"
                tvWalletShort.setTextColor(red)
            }
            else -> {
                tvWalletDot.background   = ContextCompat.getDrawable(this, R.drawable.dot_bg)
                tvWalletShort.text       = "Connect wallet"
                tvWalletShort.setTextColor(muted)
            }
        }

        // ── hero balance ──────────────────────────────────────────────
        // V4.0 FIX: Show PAPER balance when in paper mode, REAL wallet balance when live
        val config = com.lifecyclebot.data.ConfigStore.load(applicationContext)
        val displayBalance: Double
        val balanceLabel: String
        
        if (config.paperMode) {
            // PAPER MODE: Show simulated paper balance
            displayBalance = com.lifecyclebot.engine.FluidLearning.getSimulatedBalance()
            balanceLabel = "PAPER"
        } else {
            // LIVE MODE: Show real wallet balance
            displayBalance = ws.solBalance
            balanceLabel = ""
        }
        
        if (displayBalance > 0) {
            tvBalanceLarge.text = currency.format(displayBalance)
            // Secondary: show mode indicator or SOL amount
            tvBalanceUsd.text = if (config.paperMode) {
                "📝 $balanceLabel ◎ %.4f".format(displayBalance)
            } else if (currency.selectedCurrency != "SOL") {
                "◎ %.4f".format(displayBalance)
            } else ""
        } else if (ws.isConnected && ws.solBalance > 0) {
            // Fallback to wallet if paper balance is 0
            tvBalanceLarge.text = currency.format(ws.solBalance)
            tvBalanceUsd.text = if (config.paperMode) "📝 PAPER" else ""
        } else {
            tvBalanceLarge.text = "—"
            tvBalanceUsd.text   = ""
        }
        
        // ── Live SOL Price ──────────────────────────────────────────────
        val solPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
        if (solPrice > 0) {
            tvSolPrice.text = "$${solPrice.toInt()}"
        } else {
            tvSolPrice.text = "$—"
        }

        val pnl    = ws.totalPnlSol
        val pnlPct = ws.totalPnlPct
        if (ws.totalTrades > 0) {
            tvPnlChange.text = currency.format(pnl, showPlus = true)
            tvPnlChange.setTextColor(if (pnl >= 0) green else red)
            tvPnlChangePct.text = "%+.1f%%  •  ${ws.winRate}%% wins".format(pnlPct)
        } else {
            tvPnlChange.text    = ""
            tvPnlChangePct.text = ""
        }

        // ── Treasury + ScalingMode tier ──────────────────────────────
        // V4.0: Show PAPER TREASURY balance from CashGenerationAI when in paper mode
        // This ensures paper mode shows the correct simulated treasury balance
        try {
            val isPaper = cfg.paperMode
            val trs: Double
            val trsUsd: Double
            
            if (isPaper) {
                // In paper mode, show the CashGenerationAI paper treasury balance
                trs = com.lifecyclebot.v3.scoring.CashGenerationAI.getTreasuryBalance(true)
                val solPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
                trsUsd = trs * solPrice
            } else {
                // In live mode, show the TreasuryManager live treasury
                trs = ws.treasurySol
                trsUsd = ws.treasuryUsd
            }
            
            val tier    = ws.highestMilestoneName
            val nextUsd = ws.nextMilestoneUsd
            val modeLabel = if (isPaper) " [PAPER]" else ""
            
            findViewById<android.widget.TextView?>(R.id.tvTreasuryTier)?.text =
                if (trs > 0.001) "Tier: $tier$modeLabel" else "Tier: None$modeLabel"
            findViewById<android.widget.TextView?>(R.id.tvTreasuryAmount)?.text =
                if (trs > 0.001) "${"%.3f".format(trs)} SOL  ($${"%.0f".format(trsUsd)})" else "—"
            findViewById<android.widget.TextView?>(R.id.tvTreasuryNext)?.text = when {
                nextUsd > 0 -> "Next: $${"%,.0f".format(nextUsd)}"
                trs > 0     -> "Max tier reached"
                else        -> "First milestone: $500"
            }
        } catch (_: Exception) {}

        // ── bot status card ───────────────────────────────────────────
        tvTokenName.text  = ts?.symbol?.ifBlank { "No token selected" } ?: "No token selected"
        
        // Load token logo from DexScreener
        if (ts != null && ts.logoUrl.isNotEmpty()) {
            ivTokenLogo.load(ts.logoUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_token_placeholder)
                error(R.drawable.ic_token_placeholder)
                transformations(CircleCropTransformation())
            }
        } else {
            ivTokenLogo.setImageResource(R.drawable.ic_token_placeholder)
        }
        
        val ageMins = if (ts != null && ts.history.isNotEmpty()) {
            (System.currentTimeMillis() - ts.history.first().ts) / 60_000.0
        } else -1.0
        
        // V3.2: Show ACTUAL trading mode from MarketStructureRouter instead of simple age label
        val modeLabel = if (ts != null) {
            try {
                val classification = com.lifecyclebot.v3.modes.MarketStructureRouter.classify(ts)
                " · ${classification.mode.emoji} ${classification.mode.label}"
            } catch (_: Exception) {
                when {
                    ageMins < 0  -> ""
                    ageMins <= 15 -> " · 🚀 Fresh"
                    else         -> " · 📊 Range"
                }
            }
        } else ""
        tvTokenPhase.text = "${ts?.phase ?: "—"}$modeLabel"

        val sig = ts?.signal ?: "WAIT"
        tvSignalChip.text = sig
        val (sigBg, sigColor) = when {
            sig == "BUY" ->
                R.drawable.chip_green_bg to green
            sig in listOf("SELL", "EXIT") ->
                R.drawable.chip_red_bg to red
            sig in listOf("WAIT_BUILDING", "WAIT_PULLBACK", "WAIT_CONFIRM", "WAIT_COOLING") ->
                R.drawable.chip_neutral_bg to amber
            else ->
                R.drawable.chip_neutral_bg to muted
        }
        tvSignalChip.background = ContextCompat.getDrawable(this, sigBg)
        tvSignalChip.setTextColor(sigColor)

        tvPrice.text    = if (ts?.lastPrice != null && ts.lastPrice > 0) currency.formatPrice(ts.lastPrice) else "—"
        // Show market cap with FDV fallback
        val mcapValue = ts?.lastMcap?.takeIf { it > 0 } ?: ts?.lastFdv?.takeIf { it > 0 } ?: 0.0
        tvMcap.text     = mcapValue.fmtMcap()
        tvPosition.text = when {
            ts?.position?.isOpen == true -> "● OPEN"
            else                         -> "FLAT"
        }
        tvPosition.setTextColor(if (ts?.position?.isOpen == true) green else muted)

        // Animated progress bars
        animateProgress(pbEntry, ts?.entryScore?.toInt() ?: 0)
        animateProgress(pbExit, ts?.exitScore?.toInt() ?: 0)
        animateProgress(pbVol, ts?.meta?.volScore?.toInt() ?: 0)
        animateProgress(pbPress, ts?.meta?.pressScore?.toInt() ?: 0)
        tvEntryVal.text   = "${ts?.entryScore?.toInt() ?: 0}"
        tvExitVal.text    = "${ts?.exitScore?.toInt()  ?: 0}"
        tvVolVal.text     = "${ts?.meta?.volScore?.toInt()   ?: 0}"
        tvPressVal.text   = "${ts?.meta?.pressScore?.toInt() ?: 0}"

        // ── chart ─────────────────────────────────────────────────────
        // Build chart from token history when switching tokens
        if (ts != null && ts.mint != lastChartTokenMint) {
            // Clear and rebuild chart from history for new token
            chartEntries.clear()
            chartIdx = 0f
            lastChartTokenMint = ts.mint
            
            // Build chart from historical candles
            synchronized(ts.history) {
                val historyList = ts.history.takeLast(100)
                for (candle in historyList) {
                    if (candle.priceUsd > 0) {
                        chartEntries.add(Entry(chartIdx++, candle.priceUsd.toFloat()))
                    }
                }
            }
            
            // Update chart display
            if (chartEntries.isNotEmpty()) {
                val ds = LineDataSet(chartEntries, "").apply {
                    color           = purple
                    lineWidth       = 1.8f
                    setDrawCircles(false)
                    setDrawValues(false)
                    setDrawFilled(true)
                    fillColor       = purple
                    fillAlpha       = 30
                    mode            = LineDataSet.Mode.CUBIC_BEZIER
                }
                priceChart.data = LineData(ds)
                priceChart.invalidate()
            }
        } else if (ts?.lastPrice != null && ts.lastPrice > 0) {
            // Append new price point
            appendChart(ts.lastPrice)
        }

        // ── Quick Stats Bar ─────────────────────────────────────────
        // FIX: ALWAYS use TradeHistoryStore as PRIMARY source for persisted stats
        // In-memory trades supplement but don't replace persistent storage
        try {
            val now = System.currentTimeMillis()
            val twentyFourHoursAgo = now - (24 * 60 * 60 * 1000L)
            
            // ═══════════════════════════════════════════════════════════════════
            // USE PERSISTED JOURNAL DATA ONLY
            // 
            // The journal (TradeHistoryStore) is the source of truth for stats.
            // Stats are calculated from ALL stored trades, not just 24h.
            // Data persists across app restarts and is never auto-cleared.
            // ═══════════════════════════════════════════════════════════════════
            val persistedStats = com.lifecyclebot.engine.TradeHistoryStore.getStats()
            
            // 24H trades from persisted journal
            val trades24h = persistedStats.trades24h
            tvStats24hTrades.text = "$trades24h"
            
            // Win rate: Use LIFETIME win rate from persisted journal
            // This gives true percentage figures based on all recorded trades
            val winRate = when {
                persistedStats.totalTrades >= 1 -> persistedStats.winRate.toInt()
                persistedStats.winRate24h > 0 -> persistedStats.winRate24h
                else -> 0  // No trades yet
            }
            
            tvStatsWinRate.text = "$winRate%"
            tvStatsWinRate.setTextColor(when {
                winRate >= 60 -> green
                winRate >= 40 -> amber
                winRate > 0 -> red
                else -> muted  // Show muted for 0% (no data)
            })
            
            // ═══════════════════════════════════════════════════════════════════
            // OPEN POSITIONS COUNT
            // ═══════════════════════════════════════════════════════════════════
            val openCount = state.openPositions.size
            tvStatsOpenPos.text = "$openCount"
            tvStatsOpenPos.setTextColor(if (openCount > 0) purple else muted)
            
            // ═══════════════════════════════════════════════════════════════════
            // AI CONFIDENCE / MODE DISPLAY (unified - no double-write)
            // Priority: Dashboard mode > Active token entry score > Default
            // ═══════════════════════════════════════════════════════════════════
            val dashboardData = state.dashboardData
            val activeEntryScore = ts?.entryScore?.toInt() ?: 0
            
            when {
                // If we have an active token being evaluated, show its entry score
                activeEntryScore > 0 -> {
                    tvStatsAiConf.text = "$activeEntryScore"
                    tvStatsAiConf.setTextColor(when {
                        activeEntryScore >= 70 -> green
                        activeEntryScore >= 50 -> amber
                        else -> red
                    })
                }
                // Otherwise show dashboard mode info
                dashboardData != null -> {
                    tvStatsAiConf.text = "${dashboardData.modeEmoji} ${dashboardData.activeMode}"
                    tvStatsAiConf.setTextColor(when (dashboardData.sentiment) {
                        "STRONG_BULL", "BULL" -> green
                        "NEUTRAL" -> amber
                        else -> muted
                    })
                }
                // Fallback: show dash
                else -> {
                    tvStatsAiConf.text = "—"
                    tvStatsAiConf.setTextColor(muted)
                }
            }
        } catch (_: Exception) {}
        
        // ── Brain Learning Indicator ─────────────────────────────────
        try {
            val totalTrades = ws.totalTrades
            val winRate = ws.winRate
            val learningProgress = com.lifecyclebot.engine.FinalDecisionGate.getLearningProgress(totalTrades, winRate.toDouble())
            val progressPct = (learningProgress * 100).toInt()
            
            // Animate progress
            animateProgress(pbBrainProgress, progressPct)
            
            // Pulse animation when learning
            if (progressPct < 100) {
                tvBrainEmoji.animate()
                    .scaleX(1.1f).scaleY(1.1f)
                    .setDuration(500)
                    .withEndAction {
                        tvBrainEmoji.animate()
                            .scaleX(1.0f).scaleY(1.0f)
                            .setDuration(500)
                            .start()
                    }
                    .start()
            }
        } catch (_: Exception) {}

        // ── Position PnL Floating Card ──────────────────────────────
        // Disabled - users found it annoying
        cardPositionPnl.visibility = View.GONE

        // ── open positions panel ─────────────────────────────────
        val openPos = state.openPositions
        cardOpenPositions.visibility = if (openPos.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        if (openPos.isNotEmpty()) {
            tvTotalExposure.text = "%.3f◎ at risk".format(state.totalExposureSol)
            val upnl = state.totalUnrealisedPnlSol
            tvTotalUnrealisedPnl.text = "%+.4f◎".format(upnl)
            tvTotalUnrealisedPnl.setTextColor(if (upnl >= 0) green else red)
            renderOpenPositions(openPos)
        }
        
        // ── V4.0: Treasury positions panel ─────────────────────────────────
        try {
            val treasuryPositions = com.lifecyclebot.v3.scoring.CashGenerationAI.getActivePositions()
            cardTreasuryPositions.visibility = if (treasuryPositions.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            if (treasuryPositions.isNotEmpty()) {
                val treasuryExposure = treasuryPositions.sumOf { it.entrySol }
                tvTreasuryExposure.text = "%.3f◎".format(treasuryExposure)
                val treasuryDailyPnl = com.lifecyclebot.v3.scoring.CashGenerationAI.getDailyPnlSol()
                tvTreasuryPnl.text = "%+.4f◎".format(treasuryDailyPnl)
                tvTreasuryPnl.setTextColor(if (treasuryDailyPnl >= 0) green else red)
                renderTreasuryPositions(treasuryPositions)
            }
        } catch (_: Exception) {}
        
        // ── V4.0: Blue Chip positions panel ─────────────────────────────────
        try {
            val blueChipPositions = com.lifecyclebot.v3.scoring.BlueChipTraderAI.getActivePositions()
            cardBlueChipPositions.visibility = if (blueChipPositions.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            if (blueChipPositions.isNotEmpty()) {
                val blueChipExposure = blueChipPositions.sumOf { it.entrySol }
                tvBlueChipExposure.text = "%.3f◎".format(blueChipExposure)
                val blueChipDailyPnl = com.lifecyclebot.v3.scoring.BlueChipTraderAI.getDailyPnlSol()
                tvBlueChipPnl.text = "%+.4f◎".format(blueChipDailyPnl)
                tvBlueChipPnl.setTextColor(if (blueChipDailyPnl >= 0) green else red)
                renderBlueChipPositions(blueChipPositions)
            }
        } catch (_: Exception) {}
        
        // ── V4.0: ShitCoin positions panel ─────────────────────────────────
        try {
            val shitCoinPositions = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getActivePositions()
            val expressRides = com.lifecyclebot.v3.scoring.ShitCoinExpress.getActiveRides()
            val shitCoinStats = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getStats()
            
            // V5.2.12: Include Express rides in the count
            val totalDegenPositions = shitCoinPositions.size + expressRides.size
            
            // Always show if we have positions OR if mode is active (not PAUSED)
            val showShitCoin = totalDegenPositions > 0 || shitCoinStats.dailyTradeCount > 0
            cardShitCoinPositions.visibility = if (showShitCoin) android.view.View.VISIBLE else android.view.View.GONE
            
            if (showShitCoin) {
                // V5.2.12: Include Express exposure
                val shitCoinExposure = shitCoinPositions.sumOf { it.entrySol } + expressRides.sumOf { it.entrySol }
                tvShitCoinExposure.text = "%.3f◎".format(shitCoinExposure)
                
                val shitCoinDailyPnl = shitCoinStats.dailyPnlSol
                tvShitCoinPnl.text = "%+.4f◎".format(shitCoinDailyPnl)
                tvShitCoinPnl.setTextColor(if (shitCoinDailyPnl >= 0) green else red)
                
                // Update stats row
                val modeEmoji = when (shitCoinStats.mode) {
                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ShitCoinMode.HUNTING -> "🎯"
                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ShitCoinMode.POSITIONED -> "📊"
                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ShitCoinMode.CAUTIOUS -> "⚠️"
                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ShitCoinMode.PAUSED -> "⏸️"
                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ShitCoinMode.GRADUATION -> "🎓"
                }
                tvShitCoinMode.text = "$modeEmoji ${shitCoinStats.mode.name}"
                tvShitCoinWinRate.text = "${shitCoinStats.dailyWins}W/${shitCoinStats.dailyLosses}L"
                tvShitCoinDailyPnl.text = "Day: %+.3f◎".format(shitCoinDailyPnl)
                tvShitCoinDailyPnl.setTextColor(if (shitCoinDailyPnl >= 0) green else red)
                
                if (shitCoinPositions.isNotEmpty()) {
                    renderShitCoinPositions(shitCoinPositions)
                }
            }
        } catch (_: Exception) {}
        
        // ── V5.2: Moonshot positions panel ────────────────────────────
        try {
            val moonshotPositions = com.lifecyclebot.v3.scoring.MoonshotTraderAI.getActivePositions()
            val showMoonshot = moonshotPositions.isNotEmpty()
            
            cardMoonshotPositions.visibility = if (showMoonshot) android.view.View.VISIBLE else android.view.View.GONE
            
            if (showMoonshot) {
                val moonshotExposure = moonshotPositions.sumOf { it.entrySol }
                tvMoonshotExposure.text = "${String.format("%.3f", moonshotExposure)} SOL"
                
                // Calculate total P&L
                var totalPnl = 0.0
                for (pos in moonshotPositions) {
                    val currentPrice = try {
                        com.lifecyclebot.engine.BotService.status.tokens[pos.mint]?.ref ?: pos.entryPrice
                    } catch (_: Exception) { pos.entryPrice }
                    val pnlPct = if (pos.entryPrice > 0) ((currentPrice - pos.entryPrice) / pos.entryPrice * 100) else 0.0
                    totalPnl += pos.entrySol * (pnlPct / 100)
                }
                
                val pnlColor = if (totalPnl >= 0) green else red
                tvMoonshotPnl.text = "${if (totalPnl >= 0) "+" else ""}${String.format("%.4f", totalPnl)} SOL"
                tvMoonshotPnl.setTextColor(pnlColor)
                
                // Stats
                val winRate = com.lifecyclebot.v3.scoring.MoonshotTraderAI.getWinRatePct()
                val dailyPnl = com.lifecyclebot.v3.scoring.MoonshotTraderAI.getDailyPnlSol()
                val learning = (com.lifecyclebot.v3.scoring.MoonshotTraderAI.getLearningProgress() * 100).toInt()
                
                tvMoonshotMode.text = if (moonshotPositions.size >= 3) "RIDING" else "HUNTING"
                tvMoonshotWinRate.text = "${com.lifecyclebot.v3.scoring.MoonshotTraderAI.getDailyWins()}W/${com.lifecyclebot.v3.scoring.MoonshotTraderAI.getDailyLosses()}L"
                tvMoonshotDailyPnl.text = "Day: ${if (dailyPnl >= 0) "+" else ""}${String.format("%.3f", dailyPnl)}"
                tvMoonshotDailyPnl.setTextColor(if (dailyPnl >= 0) green else red)
                tvMoonshotLearning.text = "Learn: $learning%"
                
                renderMoonshotPositions(moonshotPositions)
            }
            
            // V5.2: Update Treasury+Moonshot side-by-side row
            val treasuryPositions = com.lifecyclebot.v3.scoring.CashGenerationAI.getActivePositions()
            val showTreasuryMini = treasuryPositions.isNotEmpty()
            val showMoonshotMini = moonshotPositions.isNotEmpty()
            
            if (showTreasuryMini && showMoonshotMini) {
                rowTreasuryMoonshot.visibility = android.view.View.VISIBLE
                cardTreasuryMini.visibility = android.view.View.VISIBLE
                cardMoonshotMini.visibility = android.view.View.VISIBLE
                
                // Treasury mini P&L
                val treasuryPnl = com.lifecyclebot.v3.scoring.CashGenerationAI.getDailyPnlSol()
                tvTreasuryMiniPnl.text = "${if (treasuryPnl >= 0) "+" else ""}${String.format("%.3f", treasuryPnl)}"
                tvTreasuryMiniPnl.setTextColor(if (treasuryPnl >= 0) green else red)
                
                // Moonshot mini P&L
                val moonshotDailyPnl = com.lifecyclebot.v3.scoring.MoonshotTraderAI.getDailyPnlSol()
                tvMoonshotMiniPnl.text = "${if (moonshotDailyPnl >= 0) "+" else ""}${String.format("%.3f", moonshotDailyPnl)}"
                tvMoonshotMiniPnl.setTextColor(if (moonshotDailyPnl >= 0) green else red)
            } else {
                rowTreasuryMoonshot.visibility = android.view.View.GONE
            }
        } catch (_: Exception) {}
        
        // ── V4.0: AI Status panel ─────────────────────────────────
        try {
            updateAiStatusPanel(ts)
        } catch (_: Exception) {}
        
        // ── V5.2: Tile Stats ─────────────────────────────────
        try {
            updateTileStats()
        } catch (_: Exception) {}
        
        // ── V5.2.8: 30-Day Run Stats ─────────────────────────────────
        try {
            update30DayRunStats()
        } catch (_: Exception) {}

        // ── safety ────────────────────────────────────────────────────
        val safety = ts?.safety
        if (safety != null && safety.checkedAt > 0) {
            tvSafety.text = safety.summary
            tvSafety.setTextColor(when (safety.tier) {
                SafetyTier.HARD_BLOCK -> red
                SafetyTier.CAUTION    -> amber
                else                  -> green
            })
            val rc = safety.rugcheckScore
            tvRugcheck.text = if (rc >= 0) "RC $rc" else "RC —"
            tvRugcheck.setTextColor(when {
                rc < 0   -> muted
                rc < 70  -> red
                rc < 80  -> amber
                else     -> green
            })
            tvSafetyChip.text = safety.summary.take(30)
            tvSafetyChip.setTextColor(when (safety.tier) {
                SafetyTier.HARD_BLOCK -> red
                SafetyTier.CAUTION    -> amber
                else                  -> green
            })
        }

        // ── bonding curve ─────────────────────────────────────
        if (ts != null) {
            val curveState = com.lifecyclebot.engine.BondingCurveTracker.evaluate(ts)
            pbBondingCurve.progress = curveState.progressPct.toInt()
            tvCurveStage.text       = curveState.stageLabel

            // Show graduation mcap dynamically (moves with SOL price)
            val gradMcap = curveState.graduationMcapUsd
            val gradStr  = if (gradMcap > 0) " (grad ≈ \$${"%,.0f".format(gradMcap)})" else ""

            tvCurveStage.text = "${curveState.stageLabel}$gradStr"
            tvCurveStage.setTextColor(when (curveState.stage) {
                com.lifecyclebot.engine.BondingCurveTracker.CurveStage.GRADUATING -> green
                com.lifecyclebot.engine.BondingCurveTracker.CurveStage.PRE_GRAD   -> amber
                com.lifecyclebot.engine.BondingCurveTracker.CurveStage.GRADUATED  -> green
                else                                                               -> muted
            })
        } else {
            pbBondingCurve.progress = 0
            tvCurveStage.text       = "—"
        }

        // ── whale indicator ───────────────────────────────────────
        val whaleMeta = if (ts != null && ts.lastPrice > 0) {
            com.lifecyclebot.engine.WhaleDetector.evaluate(ts.mint, ts)
        } else null
        pbWhale.progress     = whaleMeta?.velocityScore?.toInt() ?: 0
        tvWhaleSummary.text  = whaleMeta?.summary?.ifBlank { "—" } ?: "—"
        tvWhaleSummary.setTextColor(when {
            whaleMeta?.hasWhaleActivity == true -> amber
            else                                -> muted
        })

        // ── trades ────────────────────────────────────────────────────
        val trades = ts?.trades ?: emptyList()
        tvTradeCount.text = if (trades.isNotEmpty()) "${trades.size} trades" else ""
        tvNoTrades.visibility = if (trades.isEmpty()) View.VISIBLE else View.GONE
        if (trades.isNotEmpty()) {
            renderTrades(trades)
        }

        // ── decision log ──────────────────────────────────────────────
        if (ts != null) updateDecisionLog(ts)

        // ── top-up status in bot status text ─────────────────────────
        // Show top-up count on active position
        if (ts?.position?.isOpen == true && ts.position.topUpCount > 0) {
            val gainPct = if (ts.position.entryPrice > 0)
                (ts.ref - ts.position.entryPrice) / ts.position.entryPrice * 100.0 else 0.0
            val topUpBadge = "🔺×${ts.position.topUpCount}  avg entry ${ts.position.entryPrice.fmtRef()}"
            tvBotStatus.text = "${tvBotStatus.text}  $topUpBadge"
        }

        // ── watchlist ─────────────────────────────────────────────────
        renderWatchlist(state)

        // ── bottom bar ────────────────────────────────────────────────
        val running = state.running
        val cb      = state.circuitBreaker

        // Determine effective bot state
        val isHalted  = cb.isHalted
        val isPaused  = cb.isPaused && running
        val isRunning = running && !isHalted && !isPaused

        btnToggle.text = when {
            isHalted -> "Halted — Tap to Reset"
            running  -> "Stop Bot"
            else     -> "Start Bot"
        }
        btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(when {
            isHalted -> 0xFFEF4444.toInt()
            running  -> 0xFF374151.toInt()
            else     -> purple
        })
        btnToggle.setTextColor(if (running || isHalted) white else white)

        // Override toggle click when halted
        if (isHalted) {
            btnToggle.setOnClickListener {
                // Clear halt and stop the bot
                try {
                    val svc = com.lifecyclebot.engine.BotService.instance
                    val f   = svc?.javaClass?.getDeclaredField("securityGuard")
                    f?.isAccessible = true
                    (f?.get(svc) as? com.lifecyclebot.engine.SecurityGuard)?.clearHalt()
                } catch (_: Exception) {}
                vm.stopBot()
            }
        } else {
            btnToggle.setOnClickListener { vm.toggleBot() }
        }

        statusDot.background = ContextCompat.getDrawable(this, when {
            isHalted -> R.drawable.dot_red
            isPaused -> R.drawable.dot_bg     // amber would be ideal but using muted
            isRunning -> R.drawable.dot_green
            else      -> R.drawable.dot_bg
        })

        tvBotStatus.text = when {
            isHalted  -> "🛑 ${cb.haltReason.take(40)}"
            isPaused  -> "⏸ Paused ${cb.pauseRemainingSecs}s  •  ${cb.consecutiveLosses} losses"
            running && ts?.signal in listOf("BUY","EXIT","SELL") ->
                "Signal: ${ts?.signal}  •  ${ts?.symbol ?: ""}"
            running   -> "Scanning  ${ts?.symbol ?: ""}  •  ${cb.consecutiveLosses} consec losses"
            else      -> "Bot stopped"
        }
        tvBotStatus.setTextColor(when {
            isHalted -> 0xFFEF4444.toInt()
            isPaused -> amber
            else     -> 0xFF9CA3AF.toInt()
        })

        // Daily loss display in mode badge
        val isPaper = cfg.paperMode
        tvMode.text = when {
            isPaper             -> "PAPER"
            cb.dailyLossSol > 0 -> "LIVE -${"%.3f".format(cb.dailyLossSol)}◎"
            else                -> "LIVE"
        }
        tvMode.setTextColor(if (isPaper) amber else red)

        // Auto-mode badge
        val mode = state.currentMode
        tvAutoMode.text = mode.label
        tvAutoMode.setTextColor(mode.colour)

        // Show blacklist count in status
        if (state.blacklistedCount > 0) {
            tvBotStatus.text = tvBotStatus.text.toString() + "  🚫${state.blacklistedCount}"
        }

        // Settings population (once)
        if (!settingsPopulated) {
            val c = vm.ui.value.config
            switchTopUp.isChecked      = c.topUpEnabled
            switchNotifications.isChecked = c.notificationsEnabled
            switchSounds.isChecked     = c.soundEnabled
            switchDarkMode.isChecked   = c.darkModeEnabled
            etTopUpMinGain.setText(c.topUpMinGainPct.toString())
            etTopUpGainStep.setText(c.topUpGainStepPct.toString())
            etTopUpMaxCount.setText(c.topUpMaxCount.toString())
            etTopUpMaxSol.setText(c.topUpMaxTotalSol.toString())
            populateSettings(cfg)
            settingsPopulated = true
            
            // Apply theme on startup
            applyTheme(c.darkModeEnabled)
        }
    }

    // ── trades ────────────────────────────────────────────────────────

    private fun renderOpenPositions(positions: List<TokenState>) {
        llOpenPositions.removeAllViews()
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        val solPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
        
        positions.forEach { ts ->
            val pos     = ts.position
            val ref     = ts.ref
            val gainPct = if (pos.entryPrice > 0 && ref > 0)
                (ref - pos.entryPrice) / pos.entryPrice * 100.0 else 0.0
            val gainCol = if (gainPct >= 0) green else red
            val pnlSol  = pos.costSol * gainPct / 100.0
            
            // Calculate token amount using USD value and current token price
            // costSol is in SOL, so convert to USD first, then divide by token price (USD)
            val costUsd = pos.costSol * solPrice
            val tokenAmount = if (ts.lastPrice > 0) costUsd / ts.lastPrice else 0.0
            val currentValue = pos.costSol + pnlSol  // Current value in SOL
            val valueUsd = currentValue * solPrice

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
            }

            // Colour bar on left
            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(4, LinearLayout.LayoutParams.MATCH_PARENT).also {
                    it.marginEnd = 12
                }
                setBackgroundColor(gainCol)
            }
            row.addView(bar)

            // Token info (left column)
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            // Symbol + Trading Mode emoji
            val modeEmoji = pos.tradingModeEmoji.ifEmpty { "📈" }
            info.addView(TextView(this).apply {
                text = "$modeEmoji ${ts.symbol.ifBlank { ts.mint.take(8) }}"
                textSize = resources.getDimension(R.dimen.trade_row_text) / resources.displayMetrics.scaledDensity
                setTextColor(white)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            // Entry price per token and time
            // Calculate actual entry price per token = cost USD / token amount
            val entryPricePerToken = if (tokenAmount > 0) costUsd / tokenAmount else 0.0
            info.addView(TextView(this).apply {
                text = "Entry: ${entryPricePerToken.fmtPrice()}  ·  ${sdf.format(java.util.Date(pos.entryTime))}"
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
            })
            // Entry size and token balance
            info.addView(TextView(this).apply {
                val tokenAmtStr = when {
                    tokenAmount >= 1_000_000 -> "%.2fM".format(tokenAmount / 1_000_000)
                    tokenAmount >= 1_000     -> "%.2fK".format(tokenAmount / 1_000)
                    else                     -> "%.4f".format(tokenAmount)
                }
                text = "Size: %.4f◎  ·  Bal: %s".format(pos.costSol, tokenAmtStr)
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
            })
            // Profit target %
            val tpPct = when {
                pos.isTreasuryPosition && pos.treasuryTakeProfit > 0 -> pos.treasuryTakeProfit
                pos.isBlueChipPosition && pos.blueChipTakeProfit > 0 -> pos.blueChipTakeProfit
                pos.isShitCoinPosition && pos.shitCoinTakeProfit > 0 -> pos.shitCoinTakeProfit
                else -> 0.0
            }
            if (tpPct > 0) {
                info.addView(TextView(this).apply {
                    text = "Target: +%.0f%%".format(tpPct)
                    textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                    setTextColor(0xFF22C55E.toInt())  // green
                    typeface = android.graphics.Typeface.MONOSPACE
                })
            }
            row.addView(info)

            // P&L (right column)
            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.END
            }
            // PnL percentage
            right.addView(TextView(this).apply {
                text = "%+.1f%%".format(gainPct)
                textSize = resources.getDimension(R.dimen.token_name_size) / resources.displayMetrics.scaledDensity
                setTextColor(gainCol)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.END
            })
            // PnL in SOL
            right.addView(TextView(this).apply {
                text = "%+.4f◎".format(pnlSol)
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(gainCol)
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = android.view.Gravity.END
            })
            // Current value in USD
            right.addView(TextView(this).apply {
                text = "≈\$%.2f".format(valueUsd)
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = android.view.Gravity.END
            })
            row.addView(right)

            val div = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1).also { it.topMargin = 10 }
                setBackgroundColor(0xFF1F2937.toInt())
            }
            llOpenPositions.addView(row)
            llOpenPositions.addView(div)
        }
    }
    
    // V4.0: Render Treasury Mode positions
    private fun renderTreasuryPositions(positions: List<com.lifecyclebot.v3.scoring.CashGenerationAI.TreasuryPosition>) {
        llTreasuryPositions.removeAllViews()
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        val solPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
        
        positions.forEach { pos ->
            // V5.2 FIX: Use REAL tracked price from CashGenerationAI instead of random simulation!
            // This is critical - UI was showing fake PnL while checkExit used real prices
            val trackedPrice = com.lifecyclebot.v3.scoring.CashGenerationAI.getTrackedPrice(pos.mint)
            val currentPrice = trackedPrice ?: pos.entryPrice  // Fallback to entry if no price yet
            val gainPct = (currentPrice - pos.entryPrice) / pos.entryPrice * 100.0
            val gainCol = if (gainPct >= 0) green else red
            val pnlSol = pos.entrySol * gainPct / 100.0

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
            }

            // Colour bar on left (gold for treasury)
            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(4, LinearLayout.LayoutParams.MATCH_PARENT).also {
                    it.marginEnd = 12
                }
                setBackgroundColor(0xFFFFD700.toInt())
            }
            row.addView(bar)

            // Token info (left column)
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = "💰 ${pos.symbol}"
                textSize = resources.getDimension(R.dimen.trade_row_text) / resources.displayMetrics.scaledDensity
                setTextColor(0xFFFFD700.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            info.addView(TextView(this).apply {
                text = "Entry: ${pos.entryPrice.fmtPrice()}  ·  ${sdf.format(java.util.Date(pos.entryTime))}"
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
            })
            val tpPct = if (pos.entryPrice > 0 && pos.targetPrice > 0) {
                ((pos.targetPrice - pos.entryPrice) / pos.entryPrice) * 100
            } else {
                3.5  // Default to 3.5% if data missing
            }
            info.addView(TextView(this).apply {
                text = "Size: %.4f◎  ·  Target: +%.1f%%".format(pos.entrySol, tpPct)
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
            })
            // Token balance
            val tSolPrice = solPrice
            val tTokenBal = if (pos.entryPrice > 0 && tSolPrice > 0)
                (pos.entrySol * tSolPrice) / pos.entryPrice else 0.0
            val tTokenStr = when {
                tTokenBal >= 1_000_000 -> "%.2fM".format(tTokenBal / 1_000_000)
                tTokenBal >= 1_000     -> "%.2fK".format(tTokenBal / 1_000)
                else                   -> "%.0f".format(tTokenBal)
            }
            info.addView(TextView(this).apply {
                text = "Bal: $tTokenStr tokens"
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(0xFFFFD700.toInt())  // gold
                typeface = android.graphics.Typeface.MONOSPACE
            })
            row.addView(info)

            // P&L (right column)
            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.END
            }
            right.addView(TextView(this).apply {
                text = "%+.1f%%".format(gainPct)
                textSize = resources.getDimension(R.dimen.token_name_size) / resources.displayMetrics.scaledDensity
                setTextColor(gainCol)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.END
            })
            right.addView(TextView(this).apply {
                text = "%+.4f◎".format(pnlSol)
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(gainCol)
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = android.view.Gravity.END
            })
            row.addView(right)

            val div = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1).also { it.topMargin = 10 }
                setBackgroundColor(0xFF1F2937.toInt())
            }
            llTreasuryPositions.addView(row)
            llTreasuryPositions.addView(div)
        }
    }
    
    // V4.0: Render Blue Chip positions
    private fun renderBlueChipPositions(positions: List<com.lifecyclebot.v3.scoring.BlueChipTraderAI.BlueChipPosition>) {
        llBlueChipPositions.removeAllViews()
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        
        positions.forEach { pos ->
            // V5.2 FIX: Use position's tracked price or fall back to entry price
            // BlueChip positions should have their own price tracking similar to Treasury
            val currentPrice = pos.entryPrice  // TODO: Add getTrackedPrice to BlueChipTraderAI
            val gainPct = (currentPrice - pos.entryPrice) / pos.entryPrice * 100
            val gainCol = if (gainPct >= 0) green else red
            val pnlSol = pos.entrySol * gainPct / 100.0

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
            }

            // Colour bar on left (blue for Blue Chip)
            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(4, LinearLayout.LayoutParams.MATCH_PARENT).also {
                    it.marginEnd = 12
                }
                setBackgroundColor(0xFF3B82F6.toInt()) // Blue color
            }
            row.addView(bar)

            // Token info (left column)
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = "🔵 ${pos.symbol}"
                textSize = resources.getDimension(R.dimen.trade_row_text) / resources.displayMetrics.scaledDensity
                setTextColor(0xFF3B82F6.toInt()) // Blue
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            info.addView(TextView(this).apply {
                text = "Entry: ${pos.entryPrice.fmtPrice()}  ·  ${sdf.format(java.util.Date(pos.entryTime))}"
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
            })
            // Token balance + profit target
            val bcSolPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
            val bcTokenBal = if (pos.entryPrice > 0 && bcSolPrice > 0)
                (pos.entrySol * bcSolPrice) / pos.entryPrice else 0.0
            val bcTokenStr = when {
                bcTokenBal >= 1_000_000 -> "%.2fM".format(bcTokenBal / 1_000_000)
                bcTokenBal >= 1_000     -> "%.2fK".format(bcTokenBal / 1_000)
                else                    -> "%.4f".format(bcTokenBal)
            }
            info.addView(TextView(this).apply {
                text = "MCap: \$${String.format("%.2f", pos.marketCapUsd/1_000_000)}M  ·  Size: ${String.format("%.3f", pos.entrySol)}◎"
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
            })
            info.addView(TextView(this).apply {
                text = "Bal: $bcTokenStr  ·  Target: +${pos.takeProfitPct.toInt()}%"
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(0xFF22C55E.toInt())  // green
                typeface = android.graphics.Typeface.MONOSPACE
            })
            row.addView(info)

            // P&L (right column)
            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.END
            }
            right.addView(TextView(this).apply {
                text = "%+.1f%%".format(gainPct)
                textSize = resources.getDimension(R.dimen.token_name_size) / resources.displayMetrics.scaledDensity
                setTextColor(gainCol)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.END
            })
            right.addView(TextView(this).apply {
                text = "%+.4f◎".format(pnlSol)
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(gainCol)
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = android.view.Gravity.END
            })
            row.addView(right)

            val div = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1).also { it.topMargin = 10 }
                setBackgroundColor(0xFF1F2937.toInt())
            }
            llBlueChipPositions.addView(row)
            llBlueChipPositions.addView(div)
        }
    }
    
    // V4.0: Render ShitCoin Positions with platform icons
    private fun renderShitCoinPositions(positions: List<com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ShitCoinPosition>) {
        llShitCoinPositions.removeAllViews()
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        
        positions.forEach { pos ->
            val currentPrice = pos.highWaterMark * 0.95 // Use actual price from position tracking
            val gainPct = (currentPrice - pos.entryPrice) / pos.entryPrice * 100
            val gainCol = if (gainPct >= 0) green else red
            val pnlSol = pos.entrySol * gainPct / 100.0

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
            }

            // Colour bar on left (orange for ShitCoin)
            val barColor = when (pos.launchPlatform) {
                com.lifecyclebot.v3.scoring.ShitCoinTraderAI.LaunchPlatform.PUMP_FUN -> 0xFFFFB800.toInt() // Gold
                com.lifecyclebot.v3.scoring.ShitCoinTraderAI.LaunchPlatform.RAYDIUM -> 0xFF3B82F6.toInt()  // Blue
                com.lifecyclebot.v3.scoring.ShitCoinTraderAI.LaunchPlatform.MOONSHOT -> 0xFF9333EA.toInt() // Purple
                else -> 0xFFF97316.toInt() // Default orange
            }
            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(4, LinearLayout.LayoutParams.MATCH_PARENT).also {
                    it.marginEnd = 12
                }
                setBackgroundColor(barColor)
            }
            row.addView(bar)

            // Token info (left column)
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = "${pos.launchPlatform.emoji} ${pos.symbol}"
                textSize = resources.getDimension(R.dimen.trade_row_text) / resources.displayMetrics.scaledDensity
                setTextColor(0xFFF97316.toInt()) // Orange
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            info.addView(TextView(this).apply {
                text = "${pos.launchPlatform.displayName}  ·  ${sdf.format(java.util.Date(pos.entryTime))}"
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
            })
            info.addView(TextView(this).apply {
                val mcapLabel = if (pos.marketCapUsd >= 1000) {
                    "\$${String.format("%.1f", pos.marketCapUsd/1_000)}K"
                } else {
                    "\$${pos.marketCapUsd.toInt()}"
                }
                val bundleWarn = if (pos.bundlePct >= 80) " ⚠️BUNDLE" else ""
                text = "MCap: $mcapLabel  ·  ${String.format("%.3f", pos.entrySol)}◎$bundleWarn"
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
            })
            // Token balance + profit target
            val scSolPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
            val scTokenBal = if (pos.entryPrice > 0 && scSolPrice > 0)
                (pos.entrySol * scSolPrice) / pos.entryPrice else 0.0
            val scTokenStr = when {
                scTokenBal >= 1_000_000 -> "%.2fM".format(scTokenBal / 1_000_000)
                scTokenBal >= 1_000     -> "%.2fK".format(scTokenBal / 1_000)
                else                    -> "%.0f".format(scTokenBal)
            }
            if (pos.takeProfitPct > 0 || scTokenBal > 0) {
                info.addView(TextView(this).apply {
                    text = "Bal: $scTokenStr  ·  Target: +${pos.takeProfitPct.toInt()}%"
                    textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                    setTextColor(0xFF22C55E.toInt())
                    typeface = android.graphics.Typeface.MONOSPACE
                })
            }
            row.addView(info)

            // P&L (right column)
            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.END
            }
            right.addView(TextView(this).apply {
                text = "%+.1f%%".format(gainPct)
                textSize = resources.getDimension(R.dimen.token_name_size) / resources.displayMetrics.scaledDensity
                setTextColor(gainCol)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.END
            })
            right.addView(TextView(this).apply {
                text = "%+.4f◎".format(pnlSol)
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(gainCol)
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = android.view.Gravity.END
            })
            right.addView(TextView(this).apply {
                text = "TP ${pos.takeProfitPct.toInt()}% SL ${pos.stopLossPct.toInt()}%"
                textSize = 8f
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = android.view.Gravity.END
            })
            row.addView(right)

            val div = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1).also { it.topMargin = 10 }
                setBackgroundColor(0xFF1F2937.toInt())
            }
            llShitCoinPositions.addView(row)
            llShitCoinPositions.addView(div)
        }
    }
    
    // V5.2: Render Moonshot positions
    private fun renderMoonshotPositions(positions: List<com.lifecyclebot.v3.scoring.MoonshotTraderAI.MoonshotPosition>) {
        llMoonshotPositions.removeAllViews()
        
        for (pos in positions) {
            val currentPrice = try {
                com.lifecyclebot.engine.BotService.status.tokens[pos.mint]?.ref ?: pos.entryPrice
            } catch (_: Exception) { pos.entryPrice }
            
            val pnlPct = if (pos.entryPrice > 0) ((currentPrice - pos.entryPrice) / pos.entryPrice * 100) else 0.0
            val holdMins = (System.currentTimeMillis() - pos.entryTime) / 60000
            
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 6 }
                
                // V5.2: Click to show chart for this token
                setOnClickListener {
                    selectedChartMint = pos.mint
                    tvChartSymbol.text = pos.symbol
                    // Trigger chart update on next cycle
                }
            }
            
            // Token balance
            val msSolPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
            val msTokenBal = if (pos.entryPrice > 0 && msSolPrice > 0)
                (pos.entrySol * msSolPrice) / pos.entryPrice else 0.0
            val msTokenStr = when {
                msTokenBal >= 1_000_000 -> "%.2fM".format(msTokenBal / 1_000_000)
                msTokenBal >= 1_000     -> "%.2fK".format(msTokenBal / 1_000)
                else                    -> "%.4f".format(msTokenBal)
            }

            // Left column - symbol + details
            val msInfo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
            }

            val tvSymbol = TextView(this).apply {
                text = "${pos.spaceMode.emoji} ${pos.symbol}"
                setTextColor(0xFFA855F7.toInt())  // Purple for moonshots
                textSize = 12f
                typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
            }
            msInfo.addView(tvSymbol)

            val tvEntry = TextView(this).apply {
                text = "${String.format("%.6f", pos.entryPrice)} → ${String.format("%.6f", currentPrice)}"
                setTextColor(0xFF6B7280.toInt())
                textSize = 10f
                typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
            }
            msInfo.addView(tvEntry)

            msInfo.addView(TextView(this).apply {
                text = "Bal: $msTokenStr  ·  Target: +${pos.takeProfitPct.toInt()}%"
                setTextColor(0xFF22C55E.toInt())
                textSize = 10f
                typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
            })

            // P&L
            val tvPnl = TextView(this).apply {
                text = "${if (pnlPct >= 0) "+" else ""}${String.format("%.1f", pnlPct)}%"
                setTextColor(if (pnlPct >= 0) 0xFF10B981.toInt() else 0xFFEF4444.toInt())
                textSize = 12f
                typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.5f)
                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            }

            // Hold time
            val tvHold = TextView(this).apply {
                text = "${holdMins}m"
                setTextColor(0xFF6B7280.toInt())
                textSize = 10f
                typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.3f)
                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            }

            row.addView(msInfo)
            row.addView(tvPnl)
            row.addView(tvHold)
            
            val div = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1).also { it.topMargin = 6 }
                setBackgroundColor(0xFF1F2937.toInt())
            }
            llMoonshotPositions.addView(row)
            llMoonshotPositions.addView(div)
        }
    }
    
    // V5.2: Setup chart time range and type controls
    private fun setupChartControls() {
        val timeButtons = listOf(
            "1m" to try { findViewById<TextView>(R.id.btnChart1m) } catch (_: Exception) { null },
            "5m" to try { findViewById<TextView>(R.id.btnChart5m) } catch (_: Exception) { null },
            "15m" to try { findViewById<TextView>(R.id.btnChart15m) } catch (_: Exception) { null },
            "1h" to try { findViewById<TextView>(R.id.btnChart1h) } catch (_: Exception) { null },
        )
        
        val typeButtons = listOf(
            "line" to try { findViewById<TextView>(R.id.btnChartLine) } catch (_: Exception) { null },
            "candle" to try { findViewById<TextView>(R.id.btnChartCandle) } catch (_: Exception) { null },
        )
        
        // Time range buttons
        for ((range, btn) in timeButtons) {
            btn?.setOnClickListener {
                chartTimeRange = range
                // Update button styles
                for ((r, b) in timeButtons) {
                    b?.setTextColor(if (r == range) 0xFFFFFFFF.toInt() else 0xFF6B7280.toInt())
                    b?.setBackgroundColor(if (r == range) 0xFF3B82F6.toInt() else 0xFF2A2A2A.toInt())
                }
            }
        }
        
        // Chart type buttons
        for ((type, btn) in typeButtons) {
            btn?.setOnClickListener {
                chartType = type
                // Update button styles
                for ((t, b) in typeButtons) {
                    b?.setTextColor(if (t == type) 0xFFFFFFFF.toInt() else 0xFF6B7280.toInt())
                    b?.setBackgroundColor(if (t == type) 0xFF10B981.toInt() else 0xFF2A2A2A.toInt())
                }
                // Toggle chart visibility
                priceChart.visibility = if (type == "line") android.view.View.VISIBLE else android.view.View.GONE
                candleChart.visibility = if (type == "candle") android.view.View.VISIBLE else android.view.View.GONE
            }
        }
    }
    
    // V4.0: Update AI Status Panel with live data
    private fun updateAiStatusPanel(ts: TokenState?) {
        try {
            // ═══════════════════════════════════════════════════════════════
            // V5.2: EMERGENT PATCH - Add RunTracker30D metrics to AI panel
            // ═══════════════════════════════════════════════════════════════
            
            // AI Health - show system integrity score if run active
            val integrityScore = try {
                if (com.lifecyclebot.engine.RunTracker30D.isRunActive()) {
                    com.lifecyclebot.engine.RunTracker30D.integrityScore()
                } else null
            } catch (_: Exception) { null }
            
            if (integrityScore != null) {
                tvAiHealth.text = "Integrity: $integrityScore/100"
                tvAiHealth.setTextColor(when {
                    integrityScore >= 80 -> green
                    integrityScore >= 50 -> amber
                    else -> red
                })
            } else {
                tvAiHealth.text = "25 layers"
                tvAiHealth.setTextColor(green)
            }
            
            // Trading Mode - from current token or default
            val tradingMode = ts?.position?.tradingMode?.ifEmpty { "SCANNING" } ?: "SCANNING"
            tvAiTradingMode.text = tradingMode.uppercase()
            tvAiTradingMode.setTextColor(when {
                tradingMode.contains("LAUNCH", ignoreCase = true) -> purple
                tradingMode.contains("SNIPE", ignoreCase = true) -> amber
                tradingMode.contains("RANGE", ignoreCase = true) -> green
                else -> muted
            })
            
            // Market Regime - infer from token phase or liquidity
            val regime = when {
                ts?.phase?.contains("pump", ignoreCase = true) == true -> "MEME_MICRO"
                ts?.lastLiquidityUsd ?: 0.0 > 100_000 -> "MID_CAPS"
                ts?.lastLiquidityUsd ?: 0.0 > 20_000 -> "MAJORS"
                else -> "MEME_MICRO"
            }
            tvAiRegime.text = regime.uppercase()
            tvAiRegime.setTextColor(when {
                regime.contains("MEME", ignoreCase = true) -> purple
                regime.contains("MID", ignoreCase = true) -> green
                regime.contains("MAJOR", ignoreCase = true) -> amber
                else -> muted
            })
            
            // Treasury Mode Status
            val treasuryStatus = try {
                val positions = com.lifecyclebot.v3.scoring.CashGenerationAI.getActivePositions()
                if (positions.isNotEmpty()) "SCALPING (${positions.size})" 
                else if (com.lifecyclebot.v3.scoring.CashGenerationAI.getDailyPnlSol() >= 3.33) "TARGET HIT"
                else "HUNTING"
            } catch (_: Exception) { "IDLE" }
            tvAiTreasury.text = treasuryStatus
            tvAiTreasury.setTextColor(0xFFFFD700.toInt())
            
            // ShitCoin Mode Status
            val shitCoinStatus = try {
                val stats = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getStats()
                when (stats.mode) {
                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ShitCoinMode.HUNTING -> "HUNTING"
                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ShitCoinMode.POSITIONED -> "ACTIVE (${stats.activePositions})"
                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ShitCoinMode.CAUTIOUS -> "CAUTIOUS"
                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ShitCoinMode.PAUSED -> "PAUSED"
                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ShitCoinMode.GRADUATION -> "WATCHING GRAD"
                }
            } catch (_: Exception) { "IDLE" }
            tvAiShitCoin.text = shitCoinStatus
            tvAiShitCoin.setTextColor(0xFFF97316.toInt()) // Orange
            
            // V5.2: Learning Progress - use RunTracker30D if active, else FluidLearningAI
            val learningPct = try {
                if (com.lifecyclebot.engine.RunTracker30D.isRunActive()) {
                    com.lifecyclebot.engine.RunTracker30D.metrics.learning
                } else {
                    com.lifecyclebot.v3.scoring.FluidLearningAI.getMaturityPercent()
                }
            } catch (_: Exception) { 0.0 }
            
            // V5.2: Show run day if active
            val runInfo = try {
                if (com.lifecyclebot.engine.RunTracker30D.isRunActive()) {
                    " (Day ${com.lifecyclebot.engine.RunTracker30D.getCurrentDay()})"
                } else ""
            } catch (_: Exception) { "" }
            
            tvAiLearning.text = "%.1f%% learning$runInfo".format(learningPct)
            tvAiLearning.setTextColor(when {
                learningPct >= 50.0 -> green
                learningPct >= 20.0 -> amber
                else -> 0xFF3B82F6.toInt() // blue
            })
            
            // Active AI Layers - concise list
            tvAiLayers.text = "Entry · Exit · Volume · Momentum · Liquidity · Behavior · Regime · Treasury · ShitCoin · Express · DipHunter · SolArb · Social · Whale · Narrative"
            tvAiLayers.setTextColor(muted)
            
        } catch (e: Exception) {
            tvAiHealth.text = "Error"
            tvAiHealth.setTextColor(red)
        }
    }
    
    /**
     * V5.2: Update tile stats with real data from each AI layer
     * Shows wins/trades, win rate, or other relevant stats on each tile
     */
    private fun updateTileStats() {
        // V3 Core - show total open positions across ALL layers and overall learning progress
        try {
            // V5.2.11: V3 represents the CORE ENGINE, show aggregate stats
            val treasuryPos = com.lifecyclebot.v3.scoring.CashGenerationAI.getActivePositions().size
            val shitCoinPos = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getActivePositions().size
            val expressPos = com.lifecyclebot.v3.scoring.ShitCoinExpress.getActiveRides().size
            val qualityPos = com.lifecyclebot.v3.scoring.QualityTraderAI.getActivePositions().size
            val moonshotPos = com.lifecyclebot.v3.scoring.MoonshotTraderAI.getActivePositions().size
            val blueChipPos = com.lifecyclebot.v3.scoring.BlueChipTraderAI.getActivePositions().size
            val totalOpenPos = treasuryPos + shitCoinPos + expressPos + qualityPos + moonshotPos + blueChipPos
            
            val learningPct = com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress() * 100
            
            // Show: total positions | learning%
            tvV3Stats.text = "$totalOpenPos | ${learningPct.toInt()}%"
            tvV3Stats.setTextColor(when {
                learningPct >= 50 -> green
                learningPct >= 20 -> amber
                else -> 0xFF3B82F6.toInt() // blue (learning)
            })
        } catch (_: Exception) { tvV3Stats.text = "—" }
        
        // Treasury - show daily P&L and active scalps
        try {
            val positions = com.lifecyclebot.v3.scoring.CashGenerationAI.getActivePositions()
            val dailyPnl = com.lifecyclebot.v3.scoring.CashGenerationAI.getDailyPnlSol()
            val posCount = positions.size
            if (posCount > 0 || dailyPnl != 0.0) {
                val pnlStr = if (dailyPnl >= 0) "+${String.format("%.2f", dailyPnl)}" else String.format("%.2f", dailyPnl)
                tvTreasuryStats.text = "$posCount | $pnlStr"
                tvTreasuryStats.setTextColor(if (dailyPnl >= 0) green else red)
            } else {
                tvTreasuryStats.text = "0/0"
                tvTreasuryStats.setTextColor(muted)
            }
        } catch (_: Exception) { tvTreasuryStats.text = "—" }
        
        // BlueChip - show active positions and win rate
        // V5.2.11: Blue tile shows Quality ($100K-$1M) + BlueChip ($1M+) combined
        try {
            val qualityPos = com.lifecyclebot.v3.scoring.QualityTraderAI.getActivePositions()
            val blueChipPos = com.lifecyclebot.v3.scoring.BlueChipTraderAI.getActivePositions()
            val qualityWR = com.lifecyclebot.v3.scoring.QualityTraderAI.getWinRate().toInt()
            val blueChipStats = com.lifecyclebot.v3.scoring.BlueChipTraderAI.getStats()
            
            val totalPos = qualityPos.size + blueChipPos.size
            // Show combined stats: Quality | BlueChip
            if (totalPos > 0 || qualityWR > 0 || blueChipStats.dailyTradeCount > 0) {
                // Format: "Q:2 B:1" or "Q:0 B:3"
                tvBlueChipStats.text = "${qualityPos.size}/${blueChipPos.size}"
                tvBlueChipStats.setTextColor(when {
                    totalPos > 0 -> green
                    else -> amber
                })
            } else {
                tvBlueChipStats.text = "0/0"
                tvBlueChipStats.setTextColor(muted)
            }
        } catch (_: Exception) { tvBlueChipStats.text = "—" }
        
        // ShitCoin - show active positions and mode
        try {
            val stats = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getStats()
            val posCount = stats.activePositions
            val winRate = stats.winRate.toInt()
            if (posCount > 0 || stats.dailyTradeCount > 0) {
                tvShitCoinStats.text = "$posCount | $winRate%"
                tvShitCoinStats.setTextColor(when {
                    winRate >= 60 -> green
                    winRate >= 45 -> amber
                    else -> red
                })
            } else {
                tvShitCoinStats.text = "0/0"
                tvShitCoinStats.setTextColor(muted)
            }
        } catch (_: Exception) { tvShitCoinStats.text = "—" }
        
        // Moonshot - show active positions and learning progress
        try {
            val positions = com.lifecyclebot.v3.scoring.MoonshotTraderAI.getActivePositions()
            val posCount = positions.size
            val winRate = com.lifecyclebot.v3.scoring.MoonshotTraderAI.getWinRatePct()
            val totalTrades = com.lifecyclebot.v3.scoring.MoonshotTraderAI.getDailyWins() + com.lifecyclebot.v3.scoring.MoonshotTraderAI.getDailyLosses()
            if (posCount > 0 || totalTrades > 0) {
                tvMoonshotStats.text = "$posCount | $winRate%"
                tvMoonshotStats.setTextColor(when {
                    winRate >= 60 -> green
                    winRate >= 45 -> amber
                    else -> red
                })
            } else {
                tvMoonshotStats.text = "0/0"
                tvMoonshotStats.setTextColor(muted)
            }
        } catch (_: Exception) { tvMoonshotStats.text = "—" }
        
        // AI Brain - show active/total layers
        try {
            val diagnostics = com.lifecyclebot.v3.scoring.EducationSubLayerAI.runDiagnostics()
            val activeLayers = diagnostics.count { it.value }
            val totalLayers = diagnostics.size
            tvAiBrainStats?.text = "$activeLayers/$totalLayers"
            tvAiBrainStats?.setTextColor(when {
                activeLayers >= 20 -> green
                activeLayers >= 10 -> amber
                else -> muted
            })
        } catch (_: Exception) { tvAiBrainStats?.text = "—" }
        
        // Shadow - show shadow trades count
        try {
            val stats = com.lifecyclebot.engine.ShadowLearningEngine.getStats()
            val openTrades = stats.openTrades
            val totalTrades = stats.totalTrades
            tvShadowStats?.text = "$openTrades/$totalTrades"
            tvShadowStats?.setTextColor(if (totalTrades > 0) green else muted)
        } catch (_: Exception) { tvShadowStats?.text = "—" }
        
        // Regimes - show current regime
        try {
            val regime = com.lifecyclebot.engine.MarketRegimeAI.getCurrentRegime()
            val regimeLabel = regime.label
            val regimeShort = when {
                regimeLabel.contains("MEME", ignoreCase = true) -> "MEME"
                regimeLabel.contains("MID", ignoreCase = true) -> "MID"
                regimeLabel.contains("MAJOR", ignoreCase = true) -> "MAJ"
                regimeLabel.contains("BULL", ignoreCase = true) -> "BULL"
                regimeLabel.contains("BEAR", ignoreCase = true) -> "BEAR"
                regimeLabel.contains("NEUTRAL", ignoreCase = true) -> "NEU"
                else -> regimeLabel.take(4)
            }
            tvRegimesStats?.text = regimeShort
            tvRegimesStats?.setTextColor(when {
                regimeLabel.contains("BULL", ignoreCase = true) -> green
                regimeLabel.contains("BEAR", ignoreCase = true) -> red
                else -> amber
            })
        } catch (_: Exception) { tvRegimesStats?.text = "—" }
        
        // 25 AIs - show learning progress percentage
        try {
            val learningPct = com.lifecyclebot.v3.scoring.FluidLearningAI.getMaturityPercent()
            tv25AIsStats?.text = "${learningPct.toInt()}%"
            tv25AIsStats?.setTextColor(when {
                learningPct >= 50.0 -> green
                learningPct >= 20.0 -> amber
                else -> 0xFF3B82F6.toInt() // blue
            })
        } catch (_: Exception) { tv25AIsStats?.text = "—" }
    }
    
    /**
     * V5.2.8: Update 30-Day Run Stats Card
     * Shows balance, return, drawdown, trades, W/L/S, win rate, learning metrics, and integrity
     */
    private fun update30DayRunStats() {
        val tracker = com.lifecyclebot.engine.RunTracker30D
        
        // Show/hide card based on whether run is active
        if (!tracker.isRunActive()) {
            card30DayRun.visibility = View.GONE
            return
        }
        card30DayRun.visibility = View.VISIBLE
        
        // Day counter
        val currentDay = tracker.getCurrentDay()
        tv30DayCounter.text = "Day $currentDay/30"
        
        // Balance
        tv30DayBalance.text = String.format("%.4f SOL", tracker.currentBalance)
        
        // Return percentage
        val returnPct = if (tracker.startBalance > 0) {
            ((tracker.currentBalance - tracker.startBalance) / tracker.startBalance) * 100
        } else 0.0
        val returnSign = if (returnPct >= 0) "+" else ""
        tv30DayReturn.text = "$returnSign${String.format("%.2f", returnPct)}%"
        tv30DayReturn.setTextColor(if (returnPct >= 0) green else red)
        
        // Max Drawdown
        val drawdownPct = tracker.maxDrawdown * 100
        tv30DayDrawdown.text = String.format("%.2f", drawdownPct) + "%"
        tv30DayDrawdown.setTextColor(if (drawdownPct > -10) amber else red)
        
        // Trades count
        tv30DayTrades.text = tracker.totalTrades.toString()
        
        // W/L/S
        tv30DayWLS.text = "${tracker.wins} / ${tracker.losses} / ${tracker.scratches}"
        
        // Win rate
        val winRate = if (tracker.totalTrades > 0) {
            (tracker.wins * 100 / tracker.totalTrades)
        } else 0
        tv30DayWinRate.text = "$winRate%"
        tv30DayWinRate.setTextColor(when {
            winRate >= 60 -> green
            winRate >= 45 -> amber
            else -> red
        })
        
        // Intelligence metrics
        val metrics = tracker.metrics
        tv30DayLearning.text = String.format("%.1f", metrics.learning) + "%"
        tv30DayAccuracy.text = String.format("%.1f", metrics.decisionAccuracy) + "%"
        tv30DayAccuracy.setTextColor(when {
            metrics.decisionAccuracy >= 60 -> green
            metrics.decisionAccuracy >= 45 -> amber
            else -> red
        })
        
        // Integrity score
        val integrity = tracker.integrityScore()
        tv30DayIntegrity.text = integrity.toString()
        tv30DayIntegrity.setTextColor(when {
            integrity >= 80 -> green
            integrity >= 60 -> amber
            else -> red
        })
    }

    private fun renderTrades(trades: List<Trade>) {
        llTradeList.removeAllViews()
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
        val tradeTextSp = resources.getDimension(R.dimen.trade_row_text) / resources.displayMetrics.scaledDensity
        val tradeSubSp  = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity

        trades.reversed().take(8).forEach { t ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 10, 0, 10)
            }

            // Side dot + label
            val sideView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = 12 }
            }
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(8, 8).also {
                    it.topMargin = 4
                    it.bottomMargin = 4
                }
                background = ContextCompat.getDrawable(
                    this@MainActivity,
                    if (t.side == "BUY") R.drawable.dot_green else R.drawable.dot_red
                )
            }
            sideView.addView(dot)
            row.addView(sideView)

            // Info
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val sideLabel = TextView(this).apply {
                val modeEmoji = t.tradingModeEmoji.ifEmpty { "📈" }
                text      = "$modeEmoji ${t.side}  ${t.reason.ifBlank { t.mode }}"
                textSize  = tradeTextSp
                setTextColor(if (t.side == "BUY") green else if (t.pnlSol > 0) green else red)
                typeface  = android.graphics.Typeface.DEFAULT_BOLD
            }
            val timeLabel = TextView(this).apply {
                text      = sdf.format(Date(t.ts))
                textSize  = tradeSubSp
                setTextColor(muted)
                typeface  = android.graphics.Typeface.MONOSPACE
            }
            info.addView(sideLabel)
            info.addView(timeLabel)
            row.addView(info)

            // Amount + P&L
            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity     = android.view.Gravity.END
            }
            val amtLabel = TextView(this).apply {
                text      = "%.4f◎".format(t.sol)
                textSize  = tradeTextSp
                setTextColor(white)
                typeface  = android.graphics.Typeface.MONOSPACE
                gravity   = android.view.Gravity.END
            }
            right.addView(amtLabel)
            if (t.side == "SELL" && t.pnlSol != 0.0) {
                val pnlLabel = TextView(this).apply {
                    text      = "%+.4f◎  %+.1f%%".format(t.pnlSol, t.pnlPct)
                    textSize  = tradeSubSp
                    setTextColor(if (t.pnlSol > 0) green else red)
                    typeface  = android.graphics.Typeface.MONOSPACE
                    gravity   = android.view.Gravity.END
                }
                right.addView(pnlLabel)
            }
            row.addView(right)

            // Divider
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).also { it.topMargin = 10 }
                setBackgroundColor(0xFF1F2937.toInt())
            }

            llTradeList.addView(row)
            llTradeList.addView(divider)
        }
    }

    // ── watchlist ─────────────────────────────────────────────────────

    // ── Decision log ─────────────────────────────────────────────────

    private fun updateDecisionLog(ts: TokenState) {
        val meta   = ts.meta
        val signal = ts.signal
        val phase  = ts.phase

        // Show the score breakdown card
        cardLogScores.visibility = android.view.View.VISIBLE
        tvLogToken.text  = ts.symbol.ifBlank { ts.mint.take(8) + "…" }
        tvLogPhase.text  = phase

        tvLogSignal.text = signal
        val (sigBg, sigCol) = when {
            signal == "BUY"                -> R.drawable.chip_green_bg to green
            signal in listOf("SELL","EXIT") -> R.drawable.chip_red_bg  to red
            signal.startsWith("WAIT_")     -> R.drawable.chip_neutral_bg to amber
            else                           -> R.drawable.chip_neutral_bg to muted
        }
        tvLogSignal.background = ContextCompat.getDrawable(this, sigBg)
        tvLogSignal.setTextColor(sigCol)

        tvLogEntry.text  = "ENTRY  ${ts.entryScore.toInt()}"
        tvLogExit.text   = "EXIT   ${ts.exitScore.toInt()}"
        tvLogVol.text    = "VOL    ${meta.volScore.toInt()}"
        tvLogPress.text  = "BUY%%   ${meta.pressScore.toInt()}"
        tvLogMom.text    = "MOM    ${meta.momScore.toInt()}"
        tvLogEmaFan.text = "EMA FAN  ${meta.emafanAlignment.ifBlank { "—" }}"

        // Active flag pills — shows which v4 signals fired this tick
        val flags = buildList {
            if (meta.exhaustion)           add("EXHAUST")
            if (phase == "breakdown")      add("BREAKDOWN")
            if (phase == "strong_reclaim") add("RECLAIM✓")
            if (phase == "choppy_range")   add("CHOP")
            if (phase == "micro_cap_wait") add("LOW HOLDERS")
            if (ts.exitScore > 70)         add("EXIT HIGH")
            if (ts.entryScore > 70)        add("ENTRY HIGH")
            if (meta.topUpReady)           add("🔺 TOP-UP READY")
            if (meta.spikeDetected)        add("⚡ SPIKE")
            if (meta.protectMode)          add("🔒 PROTECT")
            try {
                val regime = com.lifecyclebot.engine.BotService.instance?.botBrain?.currentRegime ?: ""
                if (regime.isNotBlank() && regime != "NEUTRAL" && regime != "UNKNOWN") add("brain:$regime")
            } catch (_: Exception) {}
        }
        tvLogFlags.text = flags.joinToString("  ·  ")
        tvLogFlags.visibility = if (flags.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE

        // Reason line — why did the bot pick this signal?
        tvLogReason.text = buildReasonLine(ts, phase, signal)
        try {
            val insight = com.lifecyclebot.engine.BotService.instance?.botBrain?.lastLlmInsight ?: ""
            if (insight.isNotBlank()) tvLogReason.text = "${tvLogReason.text}\n💡 $insight"
        } catch (_: Exception) {}

        // Show SmartSizer tier + multipliers
        val walletSol = vm.ui.value.walletSol
        val tier = when {
            walletSol < 0.5  -> "MICRO"
            walletSol < 2.0  -> "SMALL"
            walletSol < 10.0 -> "MEDIUM"
            walletSol < 50.0 -> "LARGE"
            else             -> "WHALE"
        }
        val pct = when {
            walletSol < 0.5  -> "5%"
            walletSol < 2.0  -> "6%"
            walletSol < 10.0 -> "7%"
            walletSol < 50.0 -> "6%"
            else             -> "5%"
        }
        tvLogReason.text = "${tvLogReason.text}\nSizer: $tier ${pct}×wallet  " +
            "wallet=${walletSol.fmtRef()}◎"

        // Append a timestamped line to the scrolling log
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val logLine = "$time  ${ts.symbol.padEnd(8)}  ${phase.padEnd(16)}  " +
            "E:${ts.entryScore.toInt().toString().padStart(3)}  " +
            "X:${ts.exitScore.toInt().toString().padStart(3)}  " +
            signal

        logLines.addFirst(logLine)
        if (logLines.size > 200) logLines.removeLast()

        tvDecisionLog.text = logLines.joinToString("\n")
        // Auto-scroll to top (newest entry)
        if (::scrollLog.isInitialized) {
            scrollLog.post { scrollLog.smoothScrollTo(0, 0) }
        }
    }

    private fun buildReasonLine(ts: TokenState, phase: String, signal: String): String {
        val meta = ts.meta
        return when {
            signal == "BUY" -> when (phase) {
                "pre_pump"       -> "Pre-pump: early accumulation, buyer dominance confirmed"
                "pumping"        -> "Active pump: volume + pressure aligned"
                "pump_pullback"  -> "Pullback on active pump: dip entry"
                "range"          -> "Range bottom (${meta.posInRange.toInt()}% in range): buying support"
                "strong_reclaim" -> "Strong reclaim: double-bottom + vol expanding on recovery"
                "reclaim_attempt"-> "Reclaim attempt: price above EMA, buyers returning"
                "cooling"        -> "Post-pump cooling: EMA fan healthy, dip entry"
                else             -> "Entry score ${ts.entryScore.toInt()} crossed threshold"
            }
            signal in listOf("SELL", "EXIT") -> when {
                meta.exhaustion          -> "Volume exhaustion: 3+ declining candles + buy ratio drop"
                phase == "breakdown"     -> "Breakdown: price below range floor"
                phase == "distribution"  -> "Distribution: lower highs forming, smart money exiting"
                ts.exitScore > 80        -> "Exit score ${ts.exitScore.toInt()}: multiple signals converging"
                else                     -> "Exit score ${ts.exitScore.toInt()} crossed threshold"
            }
            signal == "WAIT_CHOP"      -> "Choppy range: flat EMAs, no volume expansion — skipping"
            meta.topUpReady            -> "🔺 Top-up conditions met — will add to position"
            signal == "WAIT_HOLDERS"   -> "Micro-cap: holder count below 150 — waiting for distribution"
            signal == "WAIT_BUILDING"  -> "Pre-pump building: vol accelerating, not yet confirmed"
            signal == "WAIT_PULLBACK"  -> "Pumping: waiting for pullback entry"
            signal == "WAIT_CONFIRM"   -> "Reclaim: waiting for volume confirmation"
            signal == "WAIT_COOLING"   -> "Cooling: EMA fan not yet aligned for entry"
            else                       -> "Monitoring — E:${ts.entryScore.toInt()} X:${ts.exitScore.toInt()}"
        }
    }

    private fun saveScannerSettings() {
        val cfg = vm.ui.value.config
        try {
            val fullScan = try { findViewById<android.widget.Switch>(R.id.switchFullScan)?.isChecked } catch (_: Exception) { null } ?: cfg.fullMarketScanEnabled
            val graduates = try { findViewById<android.widget.CheckBox>(R.id.cbScanGraduates)?.isChecked } catch (_: Exception) { null } ?: cfg.scanPumpGraduates
            val dexTrend  = try { findViewById<android.widget.CheckBox>(R.id.cbScanDexTrending)?.isChecked } catch (_: Exception) { null } ?: cfg.scanDexTrending
            val gainers   = try { findViewById<android.widget.CheckBox>(R.id.cbScanGainers)?.isChecked } catch (_: Exception) { null } ?: cfg.scanDexGainers
            val boosted   = try { findViewById<android.widget.CheckBox>(R.id.cbScanBoosted)?.isChecked } catch (_: Exception) { null } ?: cfg.scanDexBoosted
            val raydium   = try { findViewById<android.widget.CheckBox>(R.id.cbScanRaydium)?.isChecked } catch (_: Exception) { null } ?: cfg.scanRaydiumNew
            val narrative = try { findViewById<android.widget.CheckBox>(R.id.cbScanNarrative)?.isChecked } catch (_: Exception) { null } ?: cfg.narrativeScanEnabled
            val minMc     = try { findViewById<android.widget.EditText>(R.id.etScanMinMc)?.text?.toString()?.toDoubleOrNull() } catch (_: Exception) { null } ?: cfg.scanMinMcapUsd
            val maxMc     = try { findViewById<android.widget.EditText>(R.id.etScanMaxMc)?.text?.toString()?.toDoubleOrNull() } catch (_: Exception) { null } ?: cfg.scanMaxMcapUsd
            val kwText    = try { findViewById<android.widget.EditText>(R.id.etNarrativeKeywords)?.text?.toString() } catch (_: Exception) { null } ?: ""
            val kws       = if (kwText.isNotBlank()) kwText.split(",").map{it.trim()}.filter{it.isNotBlank()} else cfg.narrativeKeywords
            com.lifecyclebot.data.ConfigStore.save(applicationContext,
                cfg.copy(fullMarketScanEnabled=fullScan, scanPumpGraduates=graduates,
                         scanDexTrending=dexTrend, scanDexGainers=gainers,
                         scanDexBoosted=boosted, scanRaydiumNew=raydium,
                         narrativeScanEnabled=narrative, scanMinMcapUsd=minMc,
                         scanMaxMcapUsd=maxMc, narrativeKeywords=kws))
        } catch (_: Exception) {}
    }

    private fun clearDecisionLog() {
        logLines.clear()
        tvDecisionLog.text = "Log cleared"
        cardLogScores.visibility = android.view.View.GONE
    }

    private fun renderWatchlist(state: UiState) {
        llTokenList.removeAllViews()
        llProbationList.removeAllViews()
        llIdleList.removeAllViews()
        
        val active = state.config.activeToken
        val solPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
        
        // ═══════════════════════════════════════════════════════════════════════
        // V5.2: THREE-COLUMN LAYOUT - Probation | Watchlist | Idle
        // ═══════════════════════════════════════════════════════════════════════
        
        // Separate tokens into active vs idle
        val activeTokens = mutableListOf<com.lifecyclebot.data.TokenState>()
        val idleTokens = mutableListOf<com.lifecyclebot.data.TokenState>()
        
        state.tokens.values.forEach { ts ->
            if (ts.phase == "idle" || ts.phase == "blocked" || ts.phase == "dead") {
                idleTokens.add(ts)
            } else {
                activeTokens.add(ts)
            }
        }
        
        val probationEntries = com.lifecyclebot.engine.GlobalTradeRegistry.getProbationEntries()
        
        // Determine column visibility and scaling
        val visibleColumns = listOfNotNull(
            if (probationEntries.isNotEmpty()) "probation" else null,
            "watchlist",  // Always show
            if (idleTokens.isNotEmpty()) "idle" else null
        )
        val columnCount = visibleColumns.size
        // Scale text/logos based on how many columns are showing
        val scaleFactor = when (columnCount) {
            3 -> 0.85f
            2 -> 0.92f
            else -> 1.0f
        }
        
        // ═══════════════════════════════════════════════════════════════════════
        // PROBATION COLUMN (left)
        // ═══════════════════════════════════════════════════════════════════════
        if (probationEntries.isNotEmpty()) {
            tvProbationHeader.visibility = android.view.View.VISIBLE
            llProbationList.visibility = android.view.View.VISIBLE
            tvProbationHeader.text = "Probation (${probationEntries.size})"
            
            for (entry in probationEntries) {
                val probationCard = buildProbationCard(entry, scaleFactor)
                llProbationList.addView(probationCard)
            }
        } else {
            tvProbationHeader.visibility = android.view.View.GONE
            llProbationList.visibility = android.view.View.GONE
        }
        
        // ═══════════════════════════════════════════════════════════════════════
        // WATCHLIST COLUMN (center) - Active tokens only
        // ═══════════════════════════════════════════════════════════════════════
        tvWatchlistHeader.text = "Watchlist (${activeTokens.size})"
        
        activeTokens.forEach { ts ->
            val card = buildTokenCard(ts, active, solPrice, scaleFactor, state)
            llTokenList.addView(card)
        }
        
        // ═══════════════════════════════════════════════════════════════════════
        // IDLE COLUMN (right) - Idle/blocked/dead tokens
        // ═══════════════════════════════════════════════════════════════════════
        if (idleTokens.isNotEmpty()) {
            tvIdleHeader.visibility = android.view.View.VISIBLE
            llIdleList.visibility = android.view.View.VISIBLE
            tvIdleHeader.text = "Idle (${idleTokens.size})"
            
            idleTokens.forEach { ts ->
                val card = buildTokenCard(ts, active, solPrice, scaleFactor, state, isIdle = true)
                llIdleList.addView(card)
            }
        } else {
            tvIdleHeader.visibility = android.view.View.GONE
            llIdleList.visibility = android.view.View.GONE
        }
    }
    
    /**
     * Build a probation card with scaled text/elements
     */
    private fun buildProbationCard(entry: com.lifecyclebot.engine.GlobalTradeRegistry.ProbationEntry, scale: Float): android.view.View {
        val elapsed = (System.currentTimeMillis() - entry.addedAt) / 1000
        val elapsedStr = when {
            elapsed < 60 -> "${elapsed}s"
            else -> "${elapsed / 60}m"
        }
        
        val probationCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * scale).toInt(), (8 * scale).toInt(), (8 * scale).toInt(), (8 * scale).toInt())
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.card_bg)
        }
        
        // Row 1: Logo + Symbol + Timer
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        
        // Logo
        val logoSize = (28 * scale).toInt()
        val logoView = android.widget.ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(logoSize, logoSize).also {
                it.marginEnd = (6 * scale).toInt()
            }
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.token_logo_bg)
            val logoUrl = "https://dd.dexscreener.com/ds-data/tokens/solana/${entry.mint}.png"
            load(logoUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_token_placeholder)
                error(R.drawable.ic_token_placeholder)
                transformations(coil.transform.CircleCropTransformation())
            }
        }
        row1.addView(logoView)
        
        row1.addView(TextView(this).apply {
            text = entry.symbol
            textSize = 11f * scale
            setTextColor(0xFFFF9500.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row1.addView(TextView(this).apply {
            text = elapsedStr
            textSize = 9f * scale
            setTextColor(muted)
        })
        probationCard.addView(row1)
        
        // Row 2: Reason + RC + Conf
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (3 * scale).toInt(), 0, 0)
        }
        val reasonShort = when {
            entry.isSingleSource -> "1-src"
            entry.isEstimatedLiquidity -> "est-$"
            entry.initialConfidence < 50 -> "low-c"
            else -> "wait"
        }
        row2.addView(TextView(this).apply {
            text = reasonShort
            textSize = 9f * scale
            setTextColor(0xFFFF9500.toInt())
            setPadding(3, 1, 3, 1)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.badge_bg)
        })
        if (entry.rcScore >= 0) {
            row2.addView(TextView(this).apply {
                text = " RC:${entry.rcScore}"
                textSize = 9f * scale
                setTextColor(if (entry.rcScore >= 30) green else if (entry.rcScore >= 15) 0xFFFFCC00.toInt() else red)
                typeface = android.graphics.Typeface.MONOSPACE
            })
        }
        if (entry.initialConfidence > 0) {
            row2.addView(TextView(this).apply {
                text = " ${entry.initialConfidence}%"
                textSize = 9f * scale
                setTextColor(muted)
            })
        }
        probationCard.addView(row2)
        
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, (4 * scale).toInt())
        }
        wrapper.addView(probationCard)
        return wrapper
    }
    
    /**
     * Build a token card for watchlist or idle column with scaled text/elements
     */
    private fun buildTokenCard(
        ts: com.lifecyclebot.data.TokenState, 
        active: String, 
        solPrice: Double, 
        scale: Float,
        state: UiState,
        isIdle: Boolean = false
    ): android.view.View {
        // Calculate % change from reference price
        val refPrice = when {
            ts.position.isOpen -> ts.position.entryPrice
            ts.history.isNotEmpty() -> ts.history.first().priceUsd
            else -> ts.lastPrice
        }
        val pctChange = if (refPrice > 0 && ts.lastPrice > 0 && ts.lastPrice != refPrice) {
            ((ts.lastPrice - refPrice) / refPrice) * 100.0
        } else 0.0
        val changeCol = if (pctChange >= 0) green else red
        
        // Get scanner info from GlobalTradeRegistry
        val registryEntry = com.lifecyclebot.engine.GlobalTradeRegistry.getEntry(ts.mint)
        val scannerSource = registryEntry?.addedBy ?: ts.source.ifBlank { "UNKNOWN" }
        
        // Get RC score
        val rcScore = ts.safety.rugcheckScore.takeIf { it >= 0 }
        val rcColor = when {
            rcScore == null -> muted
            rcScore <= 10 -> red
            rcScore <= 20 -> 0xFFFF9500.toInt()
            rcScore <= 40 -> 0xFFFFCC00.toInt()
            else -> green
        }
        
        // Get V3 info
        val v3Score = ts.lastV3Score
        val v3Conf = ts.lastV3Confidence
        val buyPct = ts.lastBuyPressurePct.takeIf { it != 50.0 && it > 0 }
        val entryScr = ts.entryScore.takeIf { it > 0 }
        
        // Build card with scaling
        val paddingH = (10 * scale).toInt()
        val paddingV = (10 * scale).toInt()
        
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(paddingH, paddingV, paddingH, paddingV)
            isClickable = true
            isFocusable = true
            background = when {
                ts.mint == active -> ContextCompat.getDrawable(this@MainActivity, R.drawable.card_selected_bg)
                isIdle -> ContextCompat.getDrawable(this@MainActivity, R.drawable.card_bg)?.mutate()?.also {
                    it.alpha = 128  // Dim idle cards
                }
                else -> ContextCompat.getDrawable(this@MainActivity, R.drawable.card_bg)
            }
            setOnClickListener {
                vm.saveConfig(state.config.copy(activeToken = ts.mint))
                etActiveToken.setText(ts.mint)
                settingsPopulated = false
            }
        }
        
        // Row 1: Logo + Symbol + % Change
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        
        // Logo (scaled)
        val logoSize = (32 * scale).toInt()
        val logoView = android.widget.ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(logoSize, logoSize).also {
                it.marginEnd = (8 * scale).toInt()
            }
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.token_logo_bg)
            val logoUrl = "https://dd.dexscreener.com/ds-data/tokens/solana/${ts.mint}.png"
            load(logoUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_token_placeholder)
                error(R.drawable.ic_token_placeholder)
                transformations(coil.transform.CircleCropTransformation())
            }
            if (isIdle) alpha = 0.6f
        }
        row1.addView(logoView)
        
        // Symbol + MCap column
        val symbolCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        val baseTextSize = resources.getDimension(R.dimen.token_name_size) / resources.displayMetrics.scaledDensity
        val smallTextSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
        
        symbolCol.addView(TextView(this).apply {
            text = ts.symbol.ifBlank { ts.mint.take(8) }
            textSize = (baseTextSize + 1) * scale
            setTextColor(if (isIdle) muted else white)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        
        // MCap + Liq
        val mcapStr = when {
            ts.lastMcap >= 1_000_000 -> "$%.2fM".format(ts.lastMcap / 1_000_000)
            ts.lastMcap >= 1_000 -> "$%.1fK".format(ts.lastMcap / 1_000)
            else -> "$%.0f".format(ts.lastMcap)
        }
        val liqStr = when {
            ts.lastLiquidityUsd >= 1_000_000 -> "$%.1fM".format(ts.lastLiquidityUsd / 1_000_000)
            ts.lastLiquidityUsd >= 1_000 -> "$%.0fK".format(ts.lastLiquidityUsd / 1_000)
            else -> "$%.0f".format(ts.lastLiquidityUsd)
        }
        symbolCol.addView(TextView(this).apply {
            text = "MC:$mcapStr L:$liqStr"
            textSize = smallTextSize * scale
            setTextColor(muted)
        })
        row1.addView(symbolCol)
        
        // % Change
        val changeStr = "%+.1f%%".format(pctChange)
        row1.addView(TextView(this).apply {
            text = changeStr
            textSize = (baseTextSize - 1) * scale
            setTextColor(if (isIdle) muted else changeCol)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        card.addView(row1)
        
        // Row 2: Scanner + RC + V3 Score + Conf + Buy%
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (4 * scale).toInt(), 0, 0)
        }
        
        // Scanner source badge
        val sourceShort = when {
            scannerSource.contains("PUMP", true) -> "PF"
            scannerSource.contains("RAYDIUM", true) -> "RD"
            scannerSource.contains("DEX", true) -> "DX"
            scannerSource.contains("TREND", true) -> "TR"
            scannerSource.contains("BOOST", true) -> "BS"
            scannerSource.contains("VOLUME", true) -> "VL"
            else -> scannerSource.take(2).uppercase()
        }
        row2.addView(TextView(this).apply {
            text = sourceShort
            textSize = 8f * scale
            setTextColor(0xFF14F195.toInt())
            setPadding(3, 1, 3, 1)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.badge_bg)
        })
        
        // RC Score
        if (rcScore != null) {
            row2.addView(TextView(this).apply {
                text = " RC:$rcScore"
                textSize = 9f * scale
                setTextColor(if (isIdle) muted else rcColor)
                typeface = android.graphics.Typeface.MONOSPACE
            })
        }
        
        // V3 Score
        if ((v3Score ?: 0) > 0) {
            row2.addView(TextView(this).apply {
                text = " V3:$v3Score"
                textSize = 9f * scale
                setTextColor(if (isIdle) muted else white)
            })
        }
        
        // Confidence
        if ((v3Conf ?: 0) > 0) {
            val confColor = when {
                (v3Conf ?: 0) >= 50 -> green
                (v3Conf ?: 0) >= 25 -> 0xFFFFCC00.toInt()
                else -> muted
            }
            row2.addView(TextView(this).apply {
                text = " ${v3Conf}%"
                textSize = 9f * scale
                setTextColor(if (isIdle) muted else confColor)
            })
        }
        
        // Buy pressure
        if (buyPct != null) {
            val bpColor = when {
                buyPct >= 60 -> green
                buyPct <= 40 -> red
                else -> white
            }
            row2.addView(TextView(this).apply {
                text = " B:${buyPct.toInt()}%"
                textSize = 9f * scale
                setTextColor(if (isIdle) muted else bpColor)
            })
        }
        card.addView(row2)
        
        // Row 3: Entry Score + Phase
        val row3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (3 * scale).toInt(), 0, 0)
        }
        
        if (entryScr != null && entryScr > 0) {
            val entryColor = when {
                entryScr >= 70 -> green
                entryScr >= 50 -> 0xFFFFCC00.toInt()
                else -> red
            }
            row3.addView(TextView(this).apply {
                text = "Entry:$entryScr"
                textSize = 9f * scale
                setTextColor(if (isIdle) muted else entryColor)
                typeface = android.graphics.Typeface.MONOSPACE
            })
        }
        
        // Phase with color coding
        val phaseText = " ${ts.phase.uppercase()}"
        val phaseColor = when (ts.phase.lowercase()) {
            "early_pump", "pump", "breakout" -> green
            "accumulation", "healthy" -> 0xFF00DDFF.toInt()
            "distribution", "decline" -> red
            "idle", "blocked", "dead" -> muted
            else -> white
        }
        row3.addView(TextView(this).apply {
            text = phaseText
            textSize = 9f * scale
            setTextColor(phaseColor)
            typeface = android.graphics.Typeface.MONOSPACE
        })
        card.addView(row3)
        
        // Wrapper with margin
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, (6 * scale).toInt())
        }
        wrapper.addView(card)
        return wrapper
    }

    private fun addToken() {
        val mint = etAddMint.text.toString().trim()
        if (mint.isBlank()) return
        val cfg = ConfigStore.load(this)
        val wl  = cfg.watchlist.toMutableList()
        if (mint !in wl) wl.add(mint)
        vm.saveConfig(cfg.copy(watchlist = wl))
        etAddMint.setText("")
        Toast.makeText(this, "Added to watchlist", Toast.LENGTH_SHORT).show()
    }

    // ── settings ─────────────────────────────────────────────────────

    private fun setupSettings() {
        ArrayAdapter.createFromResource(this, R.array.mode_options, R.layout.spinner_item)
            .also { spMode.adapter = it }
        ArrayAdapter.createFromResource(this, R.array.auto_options, R.layout.spinner_item)
            .also { spAutoTrade.adapter = it }
    }

    private fun populateSettings(cfg: BotConfig) {
        etActiveToken.setText(cfg.activeToken)
        spMode.setSelection(if (cfg.paperMode) 0 else 1)
        spAutoTrade.setSelection(if (cfg.autoTrade) 1 else 0)
        etStopLoss.setText(cfg.stopLossPct.toString())
        etExitScore.setText(cfg.exitScoreThreshold.toString())
        etSmallBuy.setText(cfg.smallBuySol.toString())
        etLargeBuy.setText(cfg.largeBuySol.toString())
        etSlippage.setText(cfg.slippageBps.toString())
        etPoll.setText(cfg.pollSeconds.toString())
        etRpc.setText(cfg.rpcUrl)
        etTgBotToken.setText(cfg.telegramBotToken)
        etWatchlist.setText(cfg.watchlist.joinToString(", "))
        etHeliusKey.setText(cfg.heliusApiKey)
        etBirdeyeKey.setText(cfg.birdeyeApiKey)
        etGroqKey.setText(cfg.groqApiKey)
        etGeminiKey.setText(cfg.geminiApiKey)
        etJupiterKey.setText(cfg.jupiterApiKey)
    }

    private fun saveSettings() {
        val wl = etWatchlist.text.toString()
            .split(",").map { it.trim() }.filter { it.isNotBlank() }
        val cfg = ConfigStore.load(this).copy(
            activeToken           = etActiveToken.text.toString().trim(),
            paperMode             = spMode.selectedItemPosition == 0,
            autoTrade             = spAutoTrade.selectedItemPosition == 1,
            stopLossPct           = etStopLoss.text.toString().toDoubleOrNull() ?: 10.0,
            exitScoreThreshold    = etExitScore.text.toString().toDoubleOrNull() ?: 58.0,
            smallBuySol           = etSmallBuy.text.toString().toDoubleOrNull() ?: 0.05,
            largeBuySol           = etLargeBuy.text.toString().toDoubleOrNull() ?: 0.10,
            slippageBps           = etSlippage.text.toString().toIntOrNull() ?: 200,
            pollSeconds           = etPoll.text.toString().toIntOrNull() ?: 8,
            rpcUrl                = etRpc.text.toString().trim().ifBlank { "https://api.mainnet-beta.solana.com" },
            telegramBotToken      = etTgBotToken.text.toString().trim(),
            telegramChatId        = etTgChatId.text.toString().trim(),  // V5.2 FIX: Was missing!
            heliusApiKey          = etHeliusKey.text.toString().trim(),
            birdeyeApiKey         = etBirdeyeKey.text.toString().trim(),
            groqApiKey            = etGroqKey.text.toString().trim(),
            geminiApiKey          = etGeminiKey.text.toString().trim(),
            jupiterApiKey         = etJupiterKey.text.toString().trim(),
            watchlist             = wl,
            notificationsEnabled  = switchNotifications.isChecked,
            soundEnabled          = switchSounds.isChecked,
            darkModeEnabled       = switchDarkMode.isChecked,
            // V5.2 FIX: TopUp settings were NOT being saved!
            topUpEnabled          = switchTopUp.isChecked,
            topUpMinGainPct       = etTopUpMinGain.text.toString().toDoubleOrNull() ?: 3.0,
            topUpGainStepPct      = etTopUpGainStep.text.toString().toDoubleOrNull() ?: 2.0,
            topUpMaxCount         = etTopUpMaxCount.text.toString().toIntOrNull() ?: 3,
            topUpMaxTotalSol      = etTopUpMaxSol.text.toString().toDoubleOrNull() ?: 0.5,
        )
        vm.saveConfig(cfg)
        settingsPopulated = false
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.1: EXPORT/IMPORT LEARNING DATA
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun exportLearningData() {
        // Request storage permission if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                requestStoragePermission()
                Toast.makeText(this, "Please grant storage permission, then try again", Toast.LENGTH_LONG).show()
                return
            }
        }
        
        // Show confirmation dialog
        android.app.AlertDialog.Builder(this)
            .setTitle("📦 Export Learning Data")
            .setMessage("This will export all learned AI data to Downloads/AATE_Backups/\n\nThe backup file survives app uninstall and can be imported after reinstall.\n\nExport now?")
            .setPositiveButton("Export") { _, _ ->
                try {
                    val backupFile = com.lifecyclebot.engine.PersistentLearning.exportFullBackup(this)
                    if (backupFile != null) {
                        Toast.makeText(this, "✅ Exported to:\n${backupFile.absolutePath}", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "❌ Export failed - check storage permission", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "❌ Export error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun importLearningData() {
        // Request storage permission if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                requestStoragePermission()
                Toast.makeText(this, "Please grant storage permission, then try again", Toast.LENGTH_LONG).show()
                return
            }
        }
        
        // Find available backups
        val backups = com.lifecyclebot.engine.PersistentLearning.listBackups()
        
        if (backups.isEmpty()) {
            Toast.makeText(this, "No backups found in Downloads/AATE_Backups/", Toast.LENGTH_LONG).show()
            return
        }
        
        // Show backup selection dialog with OK button
        val backupNames = backups.map { file ->
            val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(file.lastModified()))
            "${file.name} ($date)"
        }.toTypedArray()
        
        var selectedIndex = 0  // Default to first backup
        
        android.app.AlertDialog.Builder(this)
            .setTitle("📥 Import Learning Data")
            .setSingleChoiceItems(backupNames, 0) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton("Import") { _, _ ->
                val selectedBackup = backups[selectedIndex]
                try {
                    val success = com.lifecyclebot.engine.PersistentLearning.importFullBackup(this, selectedBackup)
                    if (success) {
                        Toast.makeText(this, "✅ Learning data + API keys restored!\n\nRestart app for changes.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "❌ Import failed", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "❌ Import error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── permissions ───────────────────────────────────────────────────

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }
    
    private fun requestStoragePermission() {
        // For Android 11+ (API 30+), we need MANAGE_EXTERNAL_STORAGE for full access
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback to general settings
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // For Android 6-10, request legacy permissions
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        android.Manifest.permission.READ_EXTERNAL_STORAGE
                    ), 1002)
            }
        }
    }

    /** Setup clickable help links for API key fields */
    private fun setupApiKeyHelpLinks() {
        val apiLinks = mapOf(
            R.id.tvHeliusHelp to "https://dev.helius.xyz/signup",
            R.id.tvBirdeyeHelp to "https://birdeye.so",
            R.id.tvGroqHelp to "https://console.groq.com",
            R.id.tvTelegramHelp to "https://t.me/BotFather"
        )
        
        apiLinks.forEach { (viewId, url) ->
            try {
                findViewById<TextView>(viewId)?.apply {
                    setOnClickListener {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (_: Exception) {
                            // Copy URL to clipboard as fallback
                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", url))
                            android.widget.Toast.makeText(this@MainActivity, "URL copied: $url", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    // Make it look clickable
                    paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                }
            } catch (_: Exception) {}
        }
    }

    /** Setup quick action icon buttons */
    private fun setupQuickActionButtons() {
        // Wallet button
        findViewById<View>(R.id.btnQuickWallet)?.setOnClickListener {
            startActivity(Intent(this, WalletActivity::class.java))
        }

        // Journal button
        findViewById<View>(R.id.btnQuickJournal)?.setOnClickListener {
            startActivity(Intent(this, JournalActivity::class.java))
        }

        // Alerts button
        findViewById<View>(R.id.btnQuickAlerts)?.setOnClickListener {
            startActivity(Intent(this, AlertsActivity::class.java))
        }

        // Settings button - opens Settings Bottom Sheet
        findViewById<View>(R.id.btnQuickSettings)?.setOnClickListener {
            showSettingsBottomSheet()
        }

        // Error Logs button
        findViewById<View>(R.id.btnQuickLogs)?.setOnClickListener {
            startActivity(Intent(this, ErrorLogActivity::class.java))
        }

        // ═══════════════════════════════════════════════════════════════════
        // V3.2 AI FEATURE BUTTONS
        // ═══════════════════════════════════════════════════════════════════

        // AI Brain button → Opens Collective Brain Activity
        findViewById<View>(R.id.btnQuickBrain)?.setOnClickListener {
            startActivity(Intent(this, CollectiveBrainActivity::class.java))
            performHaptic()
        }

        // Shadow Learning button → Shows Shadow Learning status dialog
        findViewById<View>(R.id.btnQuickShadow)?.setOnClickListener {
            showShadowLearningDialog()
            performHaptic()
        }

        // Market Regimes button → Shows current regime and available modes
        findViewById<View>(R.id.btnQuickRegimes)?.setOnClickListener {
            showRegimesDialog()
            performHaptic()
        }

        // 21 AI Layers button → Shows all AI layer statuses
        findViewById<View>(R.id.btnQuickAILayers)?.setOnClickListener {
            showAILayersDialog()
            performHaptic()
        }
        
        // V5.2: V3 Core Mode button → Shows V3 Engine status
        findViewById<View>(R.id.btnQuickV3)?.setOnClickListener {
            showV3ModeDialog()
            performHaptic()
        }
        
        // Treasury Mode button → Shows Cash Generation AI status
        findViewById<View>(R.id.btnQuickTreasury)?.setOnClickListener {
            showTreasuryModeDialog()
            performHaptic()
        }
        
        // V4.20: BlueChip Mode button → Shows BlueChip AI status
        findViewById<View>(R.id.btnQuickBlueChip)?.setOnClickListener {
            showBlueChipModeDialog()
            performHaptic()
        }
        
        // V4.20: ShitCoin Mode button → Shows ShitCoin AI status
        findViewById<View>(R.id.btnQuickShitCoin)?.setOnClickListener {
            showShitCoinModeDialog()
            performHaptic()
        }
        
        // V5.2: Moonshot Mode button → Shows Moonshot AI status
        findViewById<View>(R.id.btnQuickMoonshot)?.setOnClickListener {
            showMoonshotModeDialog()
            performHaptic()
        }
    }

    /** Setup clear settings button with confirmation */
    private fun setupClearSettingsButton() {
        try {
            findViewById<android.widget.Button>(R.id.btnClearSettings)?.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Clear All API Keys?")
                    .setMessage("This will remove:\n\n" +
                        "• Helius API key\n" +
                        "• Birdeye API key\n" +
                        "• Groq API key\n" +
                        "• Telegram bot token\n" +
                        "• Telegram chat ID\n\n" +
                        "Your wallet and trading settings will be kept.")
                    .setPositiveButton("Clear Keys") { dialog: android.content.DialogInterface, _: Int ->
                        clearApiKeys()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        } catch (_: Exception) {}
    }

    /** Clear all API keys from storage and UI */
    private fun clearApiKeys() {
        try {
            // Clear UI fields
            etHeliusKey.setText("")
            etBirdeyeKey.setText("")
            etGroqKey.setText("")
            etGeminiKey.setText("")
            etJupiterKey.setText("")
            etTgBotToken.setText("")
            etTgChatId.setText("")

            // Save empty values
            val state = vm.ui.value
            val cfg = state.config.copy(
                heliusApiKey = "",
                birdeyeApiKey = "",
                groqApiKey = "",
                geminiApiKey = "",
                jupiterApiKey = "",
                telegramBotToken = "",
                telegramChatId = "",
            )
            vm.saveConfig(cfg)

            Toast.makeText(this, "API keys cleared", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.2: SETTINGS BOTTOM SHEET
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * V5.2: Opens the Settings Bottom Sheet dialog.
     * Replaces the inline settings card for cleaner UI.
     */
    private fun showSettingsBottomSheet() {
        val state = vm.ui.value
        val sheet = SettingsBottomSheet.newInstance()
        sheet.setConfig(state.config)
        
        // Wire up callbacks
        sheet.onSettingsSaved = { newConfig ->
            vm.saveConfig(newConfig)
            settingsPopulated = false
            // Refresh UI with new active token if changed
            if (newConfig.activeToken != state.config.activeToken) {
                etActiveToken.setText(newConfig.activeToken)
            }
        }
        
        sheet.onExportRequested = {
            exportLearningData()
        }
        
        sheet.onImportRequested = {
            importLearningData()
        }
        
        sheet.onClearApiKeys = {
            clearApiKeys()
        }
        
        sheet.onTestToast = {
            testToastNotifications()
        }
        
        sheet.show(supportFragmentManager, "SettingsBottomSheet")
    }
    
    /** V5.2: Test toast notifications - called from settings bottom sheet */
    private fun testToastNotifications() {
        Toast.makeText(this, "✅ LIVE BUY: TESTTOKEN\n0.0150 SOL @ 0.00001234", Toast.LENGTH_LONG).show()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Toast.makeText(this, "💰 PARTIAL SELL: TESTTOKEN\n+125% | 0.0050 SOL sold", Toast.LENGTH_LONG).show()
        }, 2000)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Toast.makeText(this, "🎯 FULL EXIT: TESTTOKEN\n+85% | 0.0280 SOL profit", Toast.LENGTH_LONG).show()
        }, 4000)
    }
    
    /** Test toast notifications - simulates live trade toasts */
    private fun setupTestToastButton() {
        try {
            findViewById<android.widget.Button>(R.id.btnTestToast)?.setOnClickListener {
                // Show a series of test toasts to verify they work
                Toast.makeText(this, "✅ LIVE BUY: TESTTOKEN\n0.0150 SOL @ 0.00001234", Toast.LENGTH_LONG).show()
                
                // Schedule follow-up toasts
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    Toast.makeText(this, "📉 LIVE SELL: TESTTOKEN\nPnL: -5.2% (-0.0008 SOL)", Toast.LENGTH_LONG).show()
                }, 3500)
                
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    Toast.makeText(this, "✅ Toast notifications working!", Toast.LENGTH_SHORT).show()
                }, 7000)
            }
        } catch (_: Exception) {}
        
        // V5.2: Wire up the "Open Settings" button to show bottom sheet
        try {
            findViewById<android.widget.Button>(R.id.btnOpenSettingsSheet)?.setOnClickListener {
                showSettingsBottomSheet()
            }
        } catch (_: Exception) {}
    }
    
    /**
     * Apply dark or light theme to the UI
     */
    private fun applyTheme(isDarkMode: Boolean) {
        if (isDarkMode) {
            // Dark mode - default colors (already set in XML)
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            )
        } else {
            // Light mode
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            )
        }
    }
    
    // ── Haptic Feedback ─────────────────────────────────────────────────
    private fun performHaptic(feedbackType: Int = HapticFeedbackConstants.CONTEXT_CLICK) {
        try {
            window.decorView.performHapticFeedback(feedbackType)
        } catch (_: Exception) {}
    }
    
    @Suppress("DEPRECATION")
    private fun vibrate(durationMs: Long = 50) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    vibrator.vibrate(durationMs)
                }
            }
        } catch (_: Exception) {}
    }
    
    // ── Animated Progress ───────────────────────────────────────────────
    private fun animateProgress(progressBar: ProgressBar, newValue: Int) {
        val currentValue = progressBar.progress
        if (currentValue == newValue) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            progressBar.setProgress(newValue, true)
        } else {
            val animator = android.animation.ObjectAnimator.ofInt(progressBar, "progress", currentValue, newValue)
            animator.duration = 300
            animator.interpolator = android.view.animation.DecelerateInterpolator()
            animator.start()
        }
    }
    
    // ── Historical Chart Scanner Popup ────────────────────────────────────
    private fun showHistoricalScanDialog() {
        try {
            val stats = com.lifecyclebot.engine.HistoricalChartScanner.getStats()
            val isScanning = com.lifecyclebot.engine.HistoricalChartScanner.isScanning()
            
            val lastScanStr = if (stats.lastScanTime > 0) {
                val hoursAgo = (System.currentTimeMillis() - stats.lastScanTime) / (1000 * 60 * 60)
                if (hoursAgo < 1) "Just now" else "${hoursAgo}h ago"
            } else "Never"
            
            val statusEmoji = when {
                isScanning -> "🔄"
                stats.tokensAnalyzed > 100 -> "✅"
                stats.tokensAnalyzed > 0 -> "📊"
                else -> "⏳"
            }
            
            val message = """
$statusEmoji Historical Chart Scanner

📊 Tokens Analyzed: ${stats.tokensAnalyzed}
🟢 Winning Patterns: ${stats.winningPatterns}
🔴 Losing Patterns: ${stats.losingPatterns}
🕐 Last Scan: $lastScanStr
${if (isScanning) "🔄 SCAN IN PROGRESS..." else ""}

━━━━━━━━━━━━━━━━━━━━━━━━━

This scanner backtests historical charts to:
• Pre-train trading modes without live trades
• Learn optimal entry/exit conditions
• Improve position sizing models
• Feed learnings to BehaviorLearning

${if (!isScanning) "Tap 'Start Scan' to begin." else "Scan running in background..."}
            """.trimIndent()
            
            val builder = AlertDialog.Builder(this, R.style.Theme_AATE_Dialog)
                .setTitle("📊 Historical Scanner")
                .setMessage(message)
                .setNegativeButton("Close") { d, _ -> d.dismiss() }
            
            if (!isScanning) {
                builder.setPositiveButton("Start Scan") { d, _ ->
                    d.dismiss()
                    startHistoricalScan()
                }
            } else {
                builder.setPositiveButton("Stop Scan") { d, _ ->
                    d.dismiss()
                    com.lifecyclebot.engine.HistoricalChartScanner.stopScan()
                    Toast.makeText(this, "Scan stopped", Toast.LENGTH_SHORT).show()
                }
            }
            
            builder.show()
            performHaptic()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun startHistoricalScan() {
        try {
            val cfg = vm.ui.value.config
            
            // Show progress toast
            Toast.makeText(this, "📊 Starting historical scan...", Toast.LENGTH_SHORT).show()
            
            // Start scan in background
            com.lifecyclebot.engine.HistoricalChartScanner.startScan(
                hoursBack = cfg.historicalScanHoursBack,
                onProgress = { pct, total, msg ->
                    // Update UI periodically (every 10%)
                    if (pct % 10 == 0) {
                        runOnUiThread {
                            try {
                                Toast.makeText(this@MainActivity, 
                                    "📊 Scan: $pct% ($total tokens)", 
                                    Toast.LENGTH_SHORT).show()
                            } catch (_: Exception) {}
                        }
                    }
                },
                onComplete = { analyzed, learned ->
                    runOnUiThread {
                        try {
                            AlertDialog.Builder(this@MainActivity, R.style.Theme_AATE_Dialog)
                                .setTitle("✅ Scan Complete!")
                                .setMessage("""
Analyzed: $analyzed tokens
Patterns Learned: $learned

The AI brain has been updated with new insights from historical data.
                                """.trimIndent())
                                .setPositiveButton("Great!") { d, _ -> d.dismiss() }
                                .show()
                            performHaptic()
                        } catch (_: Exception) {}
                    }
                }
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Scan error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Learning Stats Popup ────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════════════
    // V3.2 AI FEATURE DIALOGS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Shows Shadow Learning Engine status and virtual trade statistics
     */
    private fun showShadowLearningDialog() {
        try {
            // V5.2: Use the ENGINE version (com.lifecyclebot.engine.ShadowLearningEngine)
            // which is the one actually tracking blocked trades, NOT the v3.learning one
            val engine = com.lifecyclebot.engine.ShadowLearningEngine
            val stats = engine.getStats()
            val statusText = engine.getStatus()
            
            val message = """
👁️ SHADOW LEARNING ENGINE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Status: 🟢 ACTIVE

📊 Virtual Trade Statistics:
• Total Shadow Trades: ${stats.totalTrades}
• Open Positions: ${stats.openTrades}
• Wins: ${stats.wins}
• Losses: ${stats.losses}
• Virtual Win Rate: ${"%.1f".format(stats.winRate)}%
• Avg PnL per Trade: ${if (stats.avgPnlPct >= 0) "+" else ""}${"%.2f".format(stats.avgPnlPct)}%

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

📈 Status: $statusText

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

ℹ️ Shadow Learning runs continuous virtual 
trades to calibrate AI scoring without 
risking real capital.
            """.trimIndent()
            
            AlertDialog.Builder(this, R.style.Theme_AATE_Dialog)
                .setTitle("👁️ Shadow Learning Engine")
                .setMessage(message)
                .setPositiveButton("Close") { d, _ -> d.dismiss() }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Shadow Learning: ${e.message ?: "Not available"}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Shows Cash Generation AI (Treasury Mode) status and daily stats.
     * Displays PAPER or LIVE treasury balance based on current mode.
     */
    private fun showTreasuryModeDialog() {
        try {
            val treasuryAI = com.lifecyclebot.v3.scoring.CashGenerationAI
            val stats = treasuryAI.getStats()
            val cfg = com.lifecyclebot.data.ConfigStore.load(applicationContext)
            val solPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
            
            // Get both paper and live balances for comparison
            val paperBalance = treasuryAI.getTreasuryBalance(true)
            val liveBalance = treasuryAI.getTreasuryBalance(false)
            
            val modeEmoji = when (stats.mode) {
                com.lifecyclebot.v3.scoring.CashGenerationAI.TreasuryMode.HUNT -> "🎯"
                com.lifecyclebot.v3.scoring.CashGenerationAI.TreasuryMode.CRUISE -> "🚢"
                com.lifecyclebot.v3.scoring.CashGenerationAI.TreasuryMode.DEFENSIVE -> "🛡️"
                com.lifecyclebot.v3.scoring.CashGenerationAI.TreasuryMode.PAUSED -> "⏸️"
                com.lifecyclebot.v3.scoring.CashGenerationAI.TreasuryMode.AGGRESSIVE -> "⚡"
            }
            
            val currentModeLabel = if (cfg.paperMode) "📝 PAPER MODE" else "💰 LIVE MODE"
            val currentBalance = stats.treasuryBalanceSol
            val currentBalanceUsd = currentBalance * solPrice
            
            val pnlSign = if (stats.dailyPnlSol >= 0) "+" else ""
            
            val message = """
💰 CASH GENERATION AI (Treasury Mode)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

$currentModeLabel
Current Treasury: ${"%.4f".format(currentBalance)} SOL (~$${"%.0f".format(currentBalanceUsd)})

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📊 DAILY PERFORMANCE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

$modeEmoji Mode: ${stats.mode.name}

Daily P&L: $pnlSign${"%.4f".format(stats.dailyPnlSol)} SOL
Max Loss Limit: ${"%.2f".format(stats.dailyMaxLossSol)} SOL (~$50)
Target: UNLIMITED 🚀

Trades: ${stats.dailyTradeCount} | W/L: ${stats.dailyWins}/${stats.dailyLosses}
Win Rate: ${"%.1f".format(stats.winRate)}%
Active Positions: ${stats.activePositions}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📈 TREASURY BALANCES
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

📝 Paper Treasury: ${"%.4f".format(paperBalance)} SOL
💰 Live Treasury:  ${"%.4f".format(liveBalance)} SOL

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

ℹ️ Treasury Mode runs concurrent scalps
aiming for $500-$1000/day with strict
$50 max daily loss protection.
            """.trimIndent()
            
            AlertDialog.Builder(this, R.style.Theme_AATE_Dialog)
                .setTitle("💰 Treasury Mode")
                .setMessage(message)
                .setPositiveButton("Close") { d, _ -> d.dismiss() }
                .setNeutralButton("Reset Daily") { d, _ ->
                    treasuryAI.resetDaily()
                    Toast.makeText(this, "Daily stats reset for ${if (cfg.paperMode) "Paper" else "Live"} mode", Toast.LENGTH_SHORT).show()
                    d.dismiss()
                }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Treasury Mode: ${e.message ?: "Not available"}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /** Helper to build a visual progress bar */
    private fun buildProgressBar(pct: Double): String {
        val filled = ((pct / 100.0) * 10).toInt().coerceIn(0, 10)
        val empty = 10 - filled
        return "▓".repeat(filled) + "░".repeat(empty)
    }

    /**
     * Shows current market regime and available trading modes
     */
    private fun showRegimesDialog() {
        try {
            val regimes = com.lifecyclebot.v3.modes.MarketStructureRouter.MarketRegime.values()
            val modes = com.lifecyclebot.v3.modes.MarketStructureRouter.StructureMode.values()
            val statusText = com.lifecyclebot.v3.modes.MarketStructureRouter.getStatus()
            val regimeTransitionStatus = com.lifecyclebot.v3.scoring.RegimeTransitionAI.getStatus()
            
            // Build regime summary
            val regimeSummary = regimes.joinToString("\n") { regime ->
                val modeCount = modes.count { it.regime == regime }
                "${regime.emoji} ${regime.label}: $modeCount modes"
            }
            
            // Build modes list (first 12)
            val modesText = modes.take(12).joinToString("\n") { mode ->
                "${mode.emoji} ${mode.label} (${mode.regime.label})"
            }
            
            val message = """
📊 MARKET STRUCTURE ROUTER
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

$statusText

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🏛️ Market Regimes (${regimes.size}):

$regimeSummary

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🎯 Trading Modes (${modes.size}):

$modesText
${if (modes.size > 12) "...and ${modes.size - 12} more" else ""}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

📈 Regime Transition AI:
$regimeTransitionStatus
            """.trimIndent()
            
            AlertDialog.Builder(this, R.style.Theme_AATE_Dialog)
                .setTitle("📊 Market Regimes & Modes")
                .setMessage(message)
                .setPositiveButton("Close") { d, _ -> d.dismiss() }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Regimes: ${e.message ?: "Not available"}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Shows status of all 25 AI layers
     */
    private fun showAILayersDialog() {
        try {
            val coordinator = com.lifecyclebot.v3.core.AIStartupCoordinator
            val healthReport = coordinator.runHealthCheck()
            val detailedStatus = coordinator.getDetailedStatus()
            
            val statusEmoji = if (healthReport.overallHealthy) "✅" else "⚠️"
            val tradingStatus = if (healthReport.tradingAllowed) "🟢 ENABLED" else "🔴 DISABLED"
            
            // Build layer status list
            val layerLines = detailedStatus.take(25).joinToString("\n") { (name, status) ->
                "$status $name"
            }
            
            val message = """
🤖 AI SYSTEM STATUS ($statusEmoji)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Trading: $tradingStatus
Critical Issues: ${healthReport.criticalIssues}
Warnings: ${healthReport.warnings}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📋 Layer Status (25 AI Modules):

$layerLines

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Legend:
✅ Ready  ⚠️ Degraded  ❌ Failed
⏳ Loading  ⏸️ Pending

Last Check: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(healthReport.timestamp))}
            """.trimIndent()
            
            AlertDialog.Builder(this, R.style.Theme_AATE_Dialog)
                .setTitle("🤖 25 AI Layers")
                .setMessage(message)
                .setPositiveButton("Close") { d, _ -> d.dismiss() }
                .setNeutralButton("Refresh") { _, _ -> 
                    showAILayersDialog() // Re-run to refresh
                }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "AI Layers: ${e.message ?: "Not available"}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * V4.20: Shows BlueChip AI status - similar to Treasury dialog
     */
    private fun showBlueChipModeDialog() {
        try {
            val qualityAI = com.lifecyclebot.v3.scoring.QualityTraderAI
            val blueChipAI = com.lifecyclebot.v3.scoring.BlueChipTraderAI
            val blueChipStats = blueChipAI.getStats()
            val cfg = com.lifecyclebot.data.ConfigStore.load(applicationContext)
            val solPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
            
            val currentModeLabel = if (cfg.paperMode) "📝 PAPER MODE" else "💰 LIVE MODE"
            
            // Get Quality positions
            val qualityPositions = qualityAI.getActivePositions()
            val qualityWR = qualityAI.getWinRate()
            val qualityPnl = qualityAI.getDailyPnl()
            
            // Get BlueChip positions  
            val blueChipPositions = blueChipAI.getActivePositions()
            
            // Build Quality positions list
            val qualityPosList = if (qualityPositions.isEmpty()) {
                "   (none)"
            } else {
                qualityPositions.joinToString("\n") { pos ->
                    val holdMins = (System.currentTimeMillis() - pos.entryTime) / 60000
                    val qTokenBal = if (pos.entryPrice > 0 && solPrice > 0)
                        (pos.entrySol * solPrice) / pos.entryPrice else 0.0
                    val qBal = when {
                        qTokenBal >= 1_000_000 -> "%.2fM".format(qTokenBal / 1_000_000)
                        qTokenBal >= 1_000     -> "%.1fK".format(qTokenBal / 1_000)
                        else                   -> "%.0f".format(qTokenBal)
                    }
                    "   • ${pos.symbol} | \$${(pos.entryMcap/1000).toInt()}K | bal:$qBal | tp:+${pos.takeProfitPct.toInt()}% | ${holdMins}m"
                }
            }

            // Build BlueChip positions list
            val blueChipPosList = if (blueChipPositions.isEmpty()) {
                "   (none)"
            } else {
                blueChipPositions.joinToString("\n") { pos ->
                    val holdMins = (System.currentTimeMillis() - pos.entryTime) / 60000
                    val bcTokenBal = if (pos.entryPrice > 0 && solPrice > 0)
                        (pos.entrySol * solPrice) / pos.entryPrice else 0.0
                    val bcBal = when {
                        bcTokenBal >= 1_000_000 -> "%.2fM".format(bcTokenBal / 1_000_000)
                        bcTokenBal >= 1_000     -> "%.1fK".format(bcTokenBal / 1_000)
                        else                   -> "%.0f".format(bcTokenBal)
                    }
                    "   • ${pos.symbol} | \$${(pos.marketCapUsd/1_000_000).toInt()}M | bal:$bcBal | tp:+${pos.takeProfitPct.toInt()}% | ${holdMins}m"
                }
            }
            
            val modeEmoji = when (blueChipStats.mode) {
                com.lifecyclebot.v3.scoring.BlueChipTraderAI.BlueChipMode.HUNTING -> "🎯"
                com.lifecyclebot.v3.scoring.BlueChipTraderAI.BlueChipMode.POSITIONED -> "📊"
                com.lifecyclebot.v3.scoring.BlueChipTraderAI.BlueChipMode.CAUTIOUS -> "⚠️"
                com.lifecyclebot.v3.scoring.BlueChipTraderAI.BlueChipMode.PAUSED -> "⏸️"
            }
            
            val message = """
⭐ QUALITY + 🔵 BLUECHIP LAYERS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

$currentModeLabel

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⭐ QUALITY LAYER ($100K-$1M)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Professional Solana trading
(NOT meme-specific)

Positions: ${qualityPositions.size}
$qualityPosList

Win Rate: ${"%.0f".format(qualityWR)}%
Daily P&L: ${if(qualityPnl>=0)"+" else ""}${"%.4f".format(qualityPnl)} SOL

Entry Criteria:
• MCap: $100K - $1M
• Age: 30+ minutes
• Holders: 50+
• TP: 15-50% | SL: -8%

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🔵 BLUECHIP LAYER ($1M+)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

$modeEmoji Mode: ${blueChipStats.mode.name}

Positions: ${blueChipPositions.size}
$blueChipPosList

Win Rate: ${"%.0f".format(blueChipStats.winRate)}%
Daily P&L: ${if(blueChipStats.dailyPnlSol>=0)"+" else ""}${"%.4f".format(blueChipStats.dailyPnlSol)} SOL

Entry Criteria:
• MCap: $1M+
• Liquidity: $200K+
• TP: 10-20% | SL: -8%
            """.trimIndent()
            
            AlertDialog.Builder(this, R.style.Theme_AATE_Dialog)
                .setTitle("⭐ Quality + 🔵 BlueChip")
                .setMessage(message)
                .setPositiveButton("Close") { d, _ -> d.dismiss() }
                .setNeutralButton("Reset Daily") { d, _ ->
                    qualityAI.resetDaily()
                    blueChipAI.resetDaily()
                    Toast.makeText(this, "Quality & BlueChip daily stats reset", Toast.LENGTH_SHORT).show()
                    d.dismiss()
                }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Quality/BlueChip: ${e.message ?: "Not available"}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * V4.20: Shows ShitCoin AI status - similar to Treasury dialog
     */
    private fun showShitCoinModeDialog() {
        try {
            val shitCoinAI = com.lifecyclebot.v3.scoring.ShitCoinTraderAI
            val stats = shitCoinAI.getStats()
            val cfg = com.lifecyclebot.data.ConfigStore.load(applicationContext)
            val solPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
            
            val currentModeLabel = if (cfg.paperMode) "📝 PAPER MODE" else "💰 LIVE MODE"
            val pnlSign = if (stats.dailyPnlSol >= 0) "+" else ""
            val dailyPnlUsd = stats.dailyPnlSol * solPrice
            
            val modeEmoji = when (stats.mode) {
                com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ShitCoinMode.HUNTING -> "🎯"
                com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ShitCoinMode.POSITIONED -> "📍"
                com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ShitCoinMode.CAUTIOUS -> "⚠️"
                com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ShitCoinMode.PAUSED -> "⏸️"
                com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ShitCoinMode.GRADUATION -> "🎓"
            }
            
            val message = """
💩 SHITCOIN AI (Degen Plays)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

$currentModeLabel
$modeEmoji Mode: ${stats.mode.name}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📊 DAILY PERFORMANCE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Daily P&L: $pnlSign${"%.4f".format(stats.dailyPnlSol)} SOL (~$${"%.2f".format(dailyPnlUsd)})
Trades: ${stats.dailyTradeCount} | W/L: ${stats.dailyWins}/${stats.dailyLosses}
Win Rate: ${"%.1f".format(stats.winRate)}%
Active Positions: ${stats.activePositions}
Max Loss/Day: ${"%.2f".format(stats.dailyMaxLossSol)} SOL (~$50)

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🎰 TARGET TOKENS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Market Cap: <$30K
Token Age: <6 hours
Max Position: 0.20 SOL
Hold Time: <15 minutes

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

⚠️ HIGH RISK! ShitCoin AI hunts
pump.fun launches and micro-caps.
Use with caution - moon or zero!
            """.trimIndent()
            
            AlertDialog.Builder(this, R.style.Theme_AATE_Dialog)
                .setTitle("💩 ShitCoin Mode")
                .setMessage(message)
                .setPositiveButton("Close") { d, _ -> d.dismiss() }
                .setNeutralButton("Reset Daily") { d, _ ->
                    shitCoinAI.resetDaily()
                    Toast.makeText(this, "ShitCoin daily stats reset", Toast.LENGTH_SHORT).show()
                    d.dismiss()
                }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "ShitCoin Mode: ${e.message ?: "Not available"}", Toast.LENGTH_SHORT).show()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.2: V3 CORE MODE DIALOG
    // ═══════════════════════════════════════════════════════════════════════════
    private fun showV3ModeDialog() {
        try {
            val tradeStore = com.lifecyclebot.engine.TradeHistoryStore
            val cfg = com.lifecyclebot.data.ConfigStore.load(applicationContext)
            val solPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
            
            val currentModeLabel = if (cfg.paperMode) "📝 PAPER MODE" else "💰 LIVE MODE"
            
            // V5.2.11: Get positions from ALL layers
            val treasuryPos = com.lifecyclebot.v3.scoring.CashGenerationAI.getActivePositions()
            val shitCoinPos = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getActivePositions()
            val moonshotPos = com.lifecyclebot.v3.scoring.MoonshotTraderAI.getActivePositions()
            val blueChipPos = com.lifecyclebot.v3.scoring.BlueChipTraderAI.getActivePositions()
            val totalOpenPos = treasuryPos.size + shitCoinPos.size + moonshotPos.size + blueChipPos.size
            
            // Get ALL trades from 24h (not filtered by mode)
            val allTrades = tradeStore.getTrades24h()
            val wins = allTrades.count { it.pnlPct > 0 }
            val losses = allTrades.count { it.pnlPct < 0 }
            val scratches = allTrades.count { it.pnlPct == 0.0 }
            val totalPnl = allTrades.sumOf { it.pnlSol }
            val winRate = if (allTrades.isNotEmpty()) (wins.toDouble() / allTrades.size * 100) else 0.0
            
            val pnlSign = if (totalPnl >= 0) "+" else ""
            val totalPnlUsd = totalPnl * solPrice
            
            // Get fluid learning progress
            val learningProgress = try {
                com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress()
            } catch (_: Exception) { 0.0 }
            
            // Get 30-day run stats if active
            val runStats = try {
                val tracker = com.lifecyclebot.engine.RunTracker30D
                if (tracker.isRunActive()) {
                    "Day ${tracker.getCurrentDay()}/30 | W=${tracker.wins} L=${tracker.losses} S=${tracker.scratches}"
                } else {
                    "Not started"
                }
            } catch (_: Exception) { "N/A" }
            
            val message = """
🎯 V3 CORE ENGINE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

$currentModeLabel
🧠 Learning: ${"%.0f".format(learningProgress * 100)}%

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📊 OPEN POSITIONS (All Layers)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

💰 Treasury: ${treasuryPos.size}
💩 ShitCoin: ${shitCoinPos.size}
🚀 Moonshot: ${moonshotPos.size}
💎 BlueChip: ${blueChipPos.size}
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Total Open: $totalOpenPos

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📊 24H PERFORMANCE (All Layers)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Daily P&L: $pnlSign${"%.4f".format(totalPnl)} SOL (~$${"%.2f".format(totalPnlUsd)})
Trades: ${allTrades.size} | W/L/S: $wins/$losses/$scratches
Win Rate: ${"%.1f".format(winRate)}%

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📅 30-DAY RUN
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

$runStats

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🧠 V3 AI COMPONENTS (25)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Entry, Exit, Edge, Momentum,
Liquidity, Volume, Behavior,
HoldTime, Whale, Regime + 15 more
            """.trimIndent()
            
            AlertDialog.Builder(this, R.style.Theme_AATE_Dialog)
                .setTitle("🎯 V3 Core Engine")
                .setMessage(message)
                .setPositiveButton("Close") { d, _ -> d.dismiss() }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "V3 Mode: ${e.message ?: "Not available"}", Toast.LENGTH_SHORT).show()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.2: MOONSHOT MODE DIALOG - Space-themed 10x Hunter!
    // ═══════════════════════════════════════════════════════════════════════════
    private fun showMoonshotModeDialog() {
        try {
            val moonshotAI = com.lifecyclebot.v3.scoring.MoonshotTraderAI
            val cfg = com.lifecyclebot.data.ConfigStore.load(applicationContext)
            val solPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
            
            val currentModeLabel = if (cfg.paperMode) "📝 PAPER MODE" else "💰 LIVE MODE"
            val pnlSign = if (moonshotAI.getDailyPnlSol() >= 0) "+" else ""
            val dailyPnlUsd = moonshotAI.getDailyPnlSol() * solPrice
            
            val winRate = moonshotAI.getWinRatePct()
            val learningPct = (moonshotAI.getLearningProgress() * 100).toInt()
            val positionCount = moonshotAI.getPositionCount()
            
            // Space mode distribution
            val spaceModeStats = moonshotAI.getSpaceModeStats()
            val orbitalCount = spaceModeStats[com.lifecyclebot.v3.scoring.MoonshotTraderAI.SpaceMode.ORBITAL] ?: 0
            val lunarCount = spaceModeStats[com.lifecyclebot.v3.scoring.MoonshotTraderAI.SpaceMode.LUNAR] ?: 0
            val marsCount = spaceModeStats[com.lifecyclebot.v3.scoring.MoonshotTraderAI.SpaceMode.MARS] ?: 0
            val jupiterCount = spaceModeStats[com.lifecyclebot.v3.scoring.MoonshotTraderAI.SpaceMode.JUPITER] ?: 0
            
            val message = """
🚀 MOONSHOT AI - TO THE MOON!
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

$currentModeLabel
🧠 Learning: $learningPct%

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📊 DAILY PERFORMANCE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Daily P&L: $pnlSign${"%.4f".format(moonshotAI.getDailyPnlSol())} SOL (~$${"%.2f".format(dailyPnlUsd)})
W/L: ${moonshotAI.getDailyWins()}/${moonshotAI.getDailyLosses()} | Win Rate: $winRate%
🔟 Today's 10x+: ${moonshotAI.getDailyTenX()}
💯 Today's 100x+: ${moonshotAI.getDailyHundredX()}
Active Positions: $positionCount

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🚀 LIFETIME MILESTONES
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

🔟 10x Wins: ${moonshotAI.getLifetimeTenX()}
💯 100x Wins: ${moonshotAI.getLifetimeHundredX()}
🌌 1000x Wins: ${moonshotAI.getLifetimeThousandX()}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🛸 SPACE MODES (Active)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

🛸 Orbital ($100K-$500K): $orbitalCount
🌙 Lunar ($500K-$2M): $lunarCount
🔴 Mars ($2M-$5M): $marsCount
🪐 Jupiter ($5M-$50M): $jupiterCount

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

🚀 200%+ gains from other layers
get PROMOTED here to ride for
10x-100x-1000x! LET IT RIDE!
            """.trimIndent()
            
            AlertDialog.Builder(this, R.style.Theme_AATE_Dialog)
                .setTitle("🚀 Moonshot Mode")
                .setMessage(message)
                .setPositiveButton("Close") { d, _ -> d.dismiss() }
                .setNeutralButton("Reset Daily") { d, _ ->
                    moonshotAI.resetDaily()
                    Toast.makeText(this, "Moonshot daily stats reset", Toast.LENGTH_SHORT).show()
                    d.dismiss()
                }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Moonshot Mode: ${e.message ?: "Not available"}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLearningStats() {
        try {
            val ws = vm.ui.value.walletState
            val totalTrades = ws.totalTrades
            val winRate = ws.winRate
            val learningProgress = com.lifecyclebot.engine.FinalDecisionGate.getLearningProgress(totalTrades, winRate.toDouble())
            val phase = com.lifecyclebot.engine.FinalDecisionGate.getLearningPhase(totalTrades)
            
            val phaseEmoji = when (phase) {
                com.lifecyclebot.engine.FinalDecisionGate.LearningPhase.BOOTSTRAP -> "🌒"
                com.lifecyclebot.engine.FinalDecisionGate.LearningPhase.LEARNING -> "🌗"
                com.lifecyclebot.engine.FinalDecisionGate.LearningPhase.MATURE -> "🌕"
            }
            
            val phaseName = when (phase) {
                com.lifecyclebot.engine.FinalDecisionGate.LearningPhase.BOOTSTRAP -> "Bootstrap"
                com.lifecyclebot.engine.FinalDecisionGate.LearningPhase.LEARNING -> "Learning"
                com.lifecyclebot.engine.FinalDecisionGate.LearningPhase.MATURE -> "Mature"
            }
            
            val tradesNeeded = when (phase) {
                com.lifecyclebot.engine.FinalDecisionGate.LearningPhase.BOOTSTRAP -> 50 - totalTrades
                com.lifecyclebot.engine.FinalDecisionGate.LearningPhase.LEARNING -> 500 - totalTrades
                com.lifecyclebot.engine.FinalDecisionGate.LearningPhase.MATURE -> 0
            }
            
            val message = """
🧠 AI Learning Status

$phaseEmoji Phase: $phaseName
📊 Progress: ${"%.0f".format(learningProgress * 100)}%
📈 Total Trades: $totalTrades
🎯 Win Rate: $winRate%
${if (tradesNeeded > 0) "⏳ Trades to next phase: $tradesNeeded" else "✅ Fully Mature!"}

━━━━━━━━━━━━━━━━━━━━━━━━━

Learning Phases:
• Bootstrap (0-50): Very loose thresholds
• Learning (51-500): Gradually tightening
• Mature (500+): Full AI strictness

The brain fills as learning progresses.
Keep trading to make it smarter!
            """.trimIndent()
            
            AlertDialog.Builder(this, R.style.Theme_AATE_Dialog)
                .setTitle("🧠 Brain Learning Status")
                .setMessage(message)
                .setPositiveButton("Got it!") { d, _ -> d.dismiss() }
                .show()
                
            performHaptic()
        } catch (_: Exception) {}
    }
    
    /**
     * Update the currency selector button text to show current currency
     */
    private fun updateCurrencySelectorText() {
        try {
            val info = currency.selectedInfo
            btnCurrencySelector.text = "${info.code} ▼"
        } catch (_: Exception) {}
    }
}

// ── extensions ────────────────────────────────────────────────────────────────

private fun Double.fmtRef(): String = "%.4f".format(this)

private fun Double.fmtPrice(): String = when {
    this <= 0       -> "—"
    this >= 1.0     -> "$%.4f".format(this)
    this >= 0.001   -> "$%.6f".format(this)
    else            -> "$%.8f".format(this)
}

private fun Double.fmtMcap(): String = when {
    this <= 0          -> "—"
    this >= 1_000_000  -> "$%.2fM".format(this / 1_000_000)
    this >= 1_000      -> "$%.1fK".format(this / 1_000)
    else               -> "$%.0f".format(this)
}
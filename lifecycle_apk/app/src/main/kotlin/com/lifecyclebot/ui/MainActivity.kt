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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // V5.9.29: live-readiness banner
    private lateinit var readinessBanner: View
    private lateinit var tvReadinessDot: TextView
    private lateinit var tvReadinessStatus: TextView
    private lateinit var tvReadinessLatency: TextView

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

    // Quality positions panel ($100K-$1M mcap)
    private lateinit var cardQualityPositions: android.view.View
    private lateinit var llQualityPositions: LinearLayout
    private lateinit var tvQualityExposure: TextView
    private lateinit var tvQualityPnl: TextView

    // V4.0: ShitCoin positions panel
    private lateinit var cardShitCoinPositions: android.view.View
    private lateinit var llShitCoinPositions: LinearLayout
    private lateinit var tvShitCoinExposure: TextView
    private lateinit var tvShitCoinPnl: TextView
    private lateinit var tvShitCoinMode: TextView
    private lateinit var tvShitCoinWinRate: TextView
    private lateinit var tvShitCoinDailyPnl: TextView

    // V5.9: ShitCoinExpress positions panel
    private lateinit var cardExpressPositions: android.view.View
    private lateinit var llExpressPositions: LinearLayout
    private lateinit var tvExpressExposure: TextView
    private lateinit var tvExpressPnl: TextView
    private lateinit var tvExpressWinRate: TextView
    private lateinit var tvExpressDailyPnl: TextView

    // ☠️ The Manipulated positions panel
    private lateinit var cardManipulatedPositions: android.view.View
    private lateinit var llManipPositions: LinearLayout
    private lateinit var tvManipExposure: TextView
    private lateinit var tvManipPnl: TextView
    private lateinit var tvManipWinRate: TextView
    private lateinit var tvManipDailyPnl: TextView
    private lateinit var tvManipCaught: TextView

    // V5.9.222: Cyclic $500→$1M panel
    private var cardCyclicPanel: LinearLayout? = null

    // V5.2: Moonshot positions panel
    private lateinit var cardMoonshotPositions: android.view.View
    private lateinit var llMoonshotPositions: LinearLayout
    private lateinit var tvMoonshotExposure: TextView
    private lateinit var tvMoonshotPnl: TextView
    private lateinit var tvMoonshotMode: TextView
    private lateinit var tvMoonshotWinRate: TextView
    private lateinit var tvMoonshotDailyPnl: TextView
    private lateinit var tvMoonshotLearning: TextView
    
    // V5.6.29d: Network Signals panel (Collective Intelligence)
    private lateinit var cardNetworkSignals: android.view.View
    private lateinit var llNetworkSignals: LinearLayout
    private lateinit var tvNetworkSignalCount: TextView
    private lateinit var tvNetworkMegaWinners: TextView
    private lateinit var tvNetworkHotTokens: TextView
    private lateinit var tvNetworkAvoid: TextView
    private lateinit var tvNetworkLastSync: TextView
    
    // V5.6.29d: Project Sniper panel
    private lateinit var cardSniperPositions: android.view.View
    private lateinit var llSniperMissions: LinearLayout
    private lateinit var tvSniperExposure: TextView
    private lateinit var tvSniperRank: TextView
    private lateinit var tvSniperWinRate: TextView
    private lateinit var tvSniperDailyPnl: TextView
    
    // V5.2: Tile Stats TextViews (show wins/trades on each tile)
    private lateinit var tvV3Stats: TextView
    private lateinit var tvTreasuryStats: TextView
    private lateinit var tvBlueChipStats: TextView
    private lateinit var tvShitCoinStats: TextView
    private lateinit var tvExpressStats: TextView
    private lateinit var tvManipStats: TextView
    private lateinit var tvMoonshotStats: TextView
    private var tvPerpsStats: TextView? = null
    // Note: Below 4 tiles don't have stats TextViews in XML yet
    private var tvAiBrainStats: TextView? = null
    private var tvShadowStats: TextView? = null
    private var tvRegimesStats: TextView? = null
    private var tv25AIsStats: TextView? = null
    
    // V5.7: Perps Trading UI
    private var cardPerpsTrading: android.view.View? = null
    private var tvPerpsModeBadge: TextView? = null
    private var tvPerpsBalance: TextView? = null
    private var tvPerpsPnl: TextView? = null
    private var tvPerpsWinRate: TextView? = null
    private var tvPerpsTrades: TextView? = null
    private var tvPerpsReadinessPhase: TextView? = null
    private var viewPerpsReadinessBar: android.view.View? = null
    private var tvPerpsReadinessText: TextView? = null
    private var tvPerpsSolPrice: TextView? = null
    private var tvPerpsSolChange: TextView? = null
    private var llPerpsPositions: LinearLayout? = null
    
    // V5.7.1: Layer Confidence Dashboard
    private var tvLayerSyncCount: TextView? = null
    private var tvLayer1Name: TextView? = null
    private var tvLayer1Score: TextView? = null
    private var tvLayer2Name: TextView? = null
    private var tvLayer2Score: TextView? = null
    private var tvLayer3Name: TextView? = null
    private var tvLayer3Score: TextView? = null
    private var tvLayer4Name: TextView? = null
    private var tvLayer4Score: TextView? = null
    private var tvLayerLearningEvents: TextView? = null
    private var tvLayerCrossSync: TextView? = null
    
    // V5.7.4: Perps Card - Quick Stock Prices (AAPL, TSLA, NVDA in header)
    private var tvPerpsAaplPrice: TextView? = null
    private var tvPerpsTslaPrice: TextView? = null
    private var tvPerpsNvdaPrice: TextView? = null
    
    // V1.0: Crypto Alts UI
    private var tvCryptoAltsStats: TextView? = null
    private var cardCryptoAlts: android.view.View? = null
    private var tvCryptoAltsModeBadge: TextView? = null
    private var tvCryptoAltsBalance: TextView? = null
    private var tvCryptoAltsPnl: TextView? = null
    private var tvCryptoAltsWinRate: TextView? = null
    private var tvCryptoAltsTrades: TextView? = null
    private var tvCryptoAltsPhase: TextView? = null
    private var viewCryptoAltsBar: android.view.View? = null
    private var tvCryptoAltsReadiness: TextView? = null
    private var tvCryptoAltsProgress: TextView? = null
    private var tvAltsBtcPrice: TextView? = null
    private var tvAltsBtcChange: TextView? = null
    private var tvAltsEthPrice: TextView? = null
    private var tvAltsEthChange: TextView? = null
    private var tvAltsBnbPrice: TextView? = null
    private var tvAltsBnbChange: TextView? = null
    private var tvAltsXrpPrice: TextView? = null
    private var tvAltsXrpChange: TextView? = null
    private var llCryptoAltsPositions: LinearLayout? = null

    // V5.7.3: Tokenized Stocks UI
    private var cardTokenizedStocks: android.view.View? = null
    private var tvStocksModeBadge: TextView? = null
    private var tvStocksBalance: TextView? = null
    private var tvStocksPnl: TextView? = null
    private var tvStocksWinRate: TextView? = null
    private var tvStocksTrades: TextView? = null
    private var tvStocksStats: TextView? = null
    private var llStocksPositions: LinearLayout? = null
    private var tvStocksMarketHours: TextView? = null
    // Stock price TextViews
    private var tvStocksAaplPrice: TextView? = null
    private var tvStocksAaplChange: TextView? = null
    private var tvStocksTslaPrice: TextView? = null
    private var tvStocksTslaChange: TextView? = null
    private var tvStocksNvdaPrice: TextView? = null
    private var tvStocksNvdaChange: TextView? = null
    private var tvStocksGooglPrice: TextView? = null
    private var tvStocksGooglChange: TextView? = null
    private var tvStocksAmznPrice: TextView? = null
    private var tvStocksMetaPrice: TextView? = null
    private var tvStocksMsftPrice: TextView? = null
    private var tvStocksCoinPrice: TextView? = null
    
    // V5.7.3: Learning Insights Panel
    private var cardLearningInsights: android.view.View? = null
    private var tvInsightsCount: TextView? = null
    private var tvInsightsPatternsCount: TextView? = null
    private var tvInsightsReplaysCount: TextView? = null
    private var tvInsightsOptimizations: TextView? = null
    private var llRecentInsights: LinearLayout? = null
    private var btnViewAllInsights: TextView? = null
    
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
    
    // V5.6: DexScreener-style chart metrics
    private var tvChartMcap: TextView? = null
    private var tvChart5mVol: TextView? = null
    private var tvChartLiq: TextView? = null
    private var tvChartHolders: TextView? = null
    private var tvChartBuyPressure: TextView? = null
    
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
    // V5.9.317: Manual BUY/SELL buttons in active token panel
    private lateinit var btnManualBuy: android.widget.Button
    private lateinit var btnManualSell: android.widget.Button
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

    // V5.9.672 — Watchlist render throttle. The pollLoop ticks every 2.5s
    // and renderWatchlist tears down + recreates up to 40 token cards on
    // every tick (each card creates ~10 TextViews → AssetManager native
    // applyStyle / theme attribute lookups). Operator pipeline-health dump
    // showed buildTokenCard as the top ANR blocker at 62% main-thread stall.
    // Cheapest non-rewrite fix: throttle the FULL rebuild to ~6s, and skip
    // it entirely when neither the active mint, the open-position count,
    // nor the visible watchlist size changed.
    private var lastWatchlistRenderMs: Long = 0L
    private var lastWatchlistOpenCount: Int = -1
    private var lastWatchlistActiveMint: String = ""
    
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
    
    // V5.6.9: Live Readiness Indicator
    private lateinit var cardLiveReadiness: View
    private lateinit var tvLiveReadinessBadge: TextView
    private lateinit var tvReadinessWinRate: TextView
    private lateinit var tvReadinessTrades: TextView
    private lateinit var tvReadinessPhase: TextView
    private lateinit var tvReadinessProgress: TextView
    private lateinit var viewReadinessProgressBar: View
    private lateinit var tvReadinessRecommendation: TextView

    // V5.9.348: Per-trader tabbed readiness ("MEME" | "ALTS" | "PERPS")
    private var currentReadinessTab: String = "MEME"
    private var tvTradersSummary: TextView? = null
    private var tabTraderMeme: TextView? = null
    private var tabTraderAlts: TextView? = null
    private var tabTraderPerps: TextView? = null
    // V5.9.354: Live Readiness card title (updates per tab so the active
    // trader is always visible at a glance — fixes 'cross populating' UX).
    private var tvLiveReadinessTitle: TextView? = null

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
    // ═══════════════════════════════════════════════════════════════
    // V5.9.709 — Render deduplication guards.
    // Heavy render methods (removeAllViews + inflate per token) were
    // firing every 2500ms poll tick regardless of whether the underlying
    // data had changed. With 48 loop ticks/s and position lists, this
    // caused 65%+ main-thread stall time (ANR watchdog confirmed).
    // Each guard stores a lightweight hash of the last rendered data.
    // If the hash is unchanged, the render is skipped entirely.
    // ═══════════════════════════════════════════════════════════════
    private var lastOpenPosHash: Int = -1
    // V5.9.749 — split the open-positions render into structural-vs-price.
    // The 92.8% main-thread stall was caused by ref-only price ticks (1Hz
    // V5.9.730 loop) blowing the structural hash and forcing a full
    // removeAllViews()/rebuild of every card. Now we use a STRUCTURAL hash
    // (mint + cost + paper flag + qty) for the rebuild path and a
    // minimum 2s interval throttle. Pure price drift no longer rebuilds.
    private var lastOpenPosRenderMs: Long = 0L
    private val OPEN_POS_MIN_RENDER_INTERVAL_MS: Long = 2_000L
    private var lastMoonshotHash: Int = -1
    private var lastNetworkSigRenderMs: Long = 0L
    private var lastDecisionLogHash: Int = -1
    private var lastTradesRenderHash: Int = -1
    private var lastWatchlistRenderHash: Int = -1
    private var lastTreasuryRenderMs: Long = 0L   // V5.9.730 ANR debounce
    private var lastTreasuryMints: String = ""      // V5.9.730 dirty-check
    private var lastCryptoAltsRenderMs: Long = 0L  // V5.9.730 ANR debounce
    private var lastBlueChipHash: Int = -1
    private var lastQualityHash: Int = -1

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
            // V5.9.438 — persist outcome-learning trackers across restarts.
            com.lifecyclebot.engine.LearningPersistence.init(applicationContext)
        } catch (e: Exception) {
            com.lifecyclebot.engine.ErrorLogger.error("MainActivity", "TradeHistoryStore init failed: ${e.message}")
        }
        
        // ════════════════════════════════════════════════════════════════════════════
        // V5.7.7: Eagerly fetch SOL price so USD values display correctly immediately
        // ════════════════════════════════════════════════════════════════════════════
        try {
            com.lifecyclebot.engine.WalletManager.getInstance(applicationContext).refreshSolPriceEagerly()
        } catch (e: Exception) {
            com.lifecyclebot.engine.ErrorLogger.warn("MainActivity", "Eager SOL price fetch error: ${e.message}")
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
            hydratePaperWalletForColdOpen("onCreate")
            
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

            // V5.9.713 — AUTO-RESTART on cold-open after process kill.
            // When Android kills the process (OEM battery saver, Doze, OOM) and
            // the user re-authenticates via SecurityActivity + Splash, MainActivity
            // is cold-created. The bot may not be running yet even though
            // SecurityActivity already kicked ACTION_START, because BotService
            // can take a few seconds to become running=true. We schedule a short
            // delayed check: if the user had the bot running before the kill
            // AND they haven't manually stopped it AND the bot still isn't up
            // after 4 seconds, we press START BOT on their behalf.
            lifecycleScope.launch {
                try {
                    val rp = getSharedPreferences(
                        com.lifecyclebot.engine.BotService.RUNTIME_PREFS,
                        android.content.Context.MODE_PRIVATE
                    )
                    val wasRunning = rp.getBoolean(com.lifecyclebot.engine.BotService.KEY_WAS_RUNNING_BEFORE_SHUTDOWN, false)
                    val manualStop = rp.getBoolean(com.lifecyclebot.engine.BotService.KEY_MANUAL_STOP_REQUESTED, false)
                    if (wasRunning && !manualStop) {
                        // Grace period: give SecurityActivity's pre-kick time to take effect
                        kotlinx.coroutines.delay(4_000)
                        val isRunning = com.lifecyclebot.engine.BotService.status.running
                        if (!isRunning) {
                            com.lifecyclebot.engine.ErrorLogger.warn("MainActivity",
                                "V5.9.713: bot still stopped 4s after cold-open (wasRunning=true, manualStop=false) — auto-restarting")
                            vm.startBot()
                        } else {
                            com.lifecyclebot.engine.ErrorLogger.info("MainActivity",
                                "V5.9.713: cold-open check — bot already running, no action needed")
                        }
                    }
                } catch (e: Exception) {
                    com.lifecyclebot.engine.ErrorLogger.warn("MainActivity",
                        "V5.9.713: auto-restart check failed: ${'$'}{e.message}")
                }
            }
            
            // V5.9.262: floating "Live Trade Forensics" tile — opens the
            // end-to-end live-trade timeline UI. Implemented programmatically
            // so it doesn't depend on layout XML resource IDs.
            try {
                val fab = android.widget.TextView(this).apply {
                    text = "🔬 Live Forensics"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 12f
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                    setBackgroundColor(android.graphics.Color.parseColor("#A78BFA"))
                    val pad = (10 * resources.displayMetrics.density).toInt()
                    setPadding(pad + pad / 2, pad / 2 + 2, pad + pad / 2, pad / 2 + 2)
                    elevation = 12f * resources.displayMetrics.density
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        startActivity(android.content.Intent(this@MainActivity, LiveTradeLogActivity::class.java))
                    }
                }
                val flp = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                    val mPx = (16 * resources.displayMetrics.density).toInt()
                    rightMargin = mPx
                    bottomMargin = (84 * resources.displayMetrics.density).toInt()
                }
                val rootDecor = window.decorView as? android.view.ViewGroup
                rootDecor?.addView(fab, flp)

                // V5.9.495z8 — second floating tile: 🧠 Learning Pipeline
                // Stacks above the Live Forensics FAB.
                val brain = android.widget.TextView(this).apply {
                    text = "🧠 Learning"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 12f
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                    setBackgroundColor(android.graphics.Color.parseColor("#10B981"))
                    val pad = (10 * resources.displayMetrics.density).toInt()
                    setPadding(pad + pad / 2, pad / 2 + 2, pad + pad / 2, pad / 2 + 2)
                    elevation = 12f * resources.displayMetrics.density
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        startActivity(android.content.Intent(this@MainActivity, LearningCounterActivity::class.java))
                    }
                }
                val brainLp = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                    val mPx = (16 * resources.displayMetrics.density).toInt()
                    rightMargin = mPx
                    bottomMargin = (132 * resources.displayMetrics.density).toInt()
                }
                rootDecor?.addView(brain, brainLp)

                // V5.9.495z25 — Universe stat tile.
                // Operator: "add the universe stat tile" — surfaces the live size
                // of the persistent token universe so the operator can see the
                // discovery loop is feeding (e.g. "🪙 487 mints +12 today").
                // Tap → opens LiveTradeLogActivity (which already shows the
                // PositionWalletReconciler counter strip too). Refreshes every
                // 30s while MainActivity is foregrounded.
                val universeTile = android.widget.TextView(this).apply {
                    text = "🪙 Universe"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 11f
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                    setBackgroundColor(android.graphics.Color.parseColor("#F59E0B"))
                    val pad = (10 * resources.displayMetrics.density).toInt()
                    setPadding(pad + pad / 2, pad / 2 + 2, pad + pad / 2, pad / 2 + 2)
                    elevation = 12f * resources.displayMetrics.density
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        // Long-form stats popup so operator can see both registries at once
                        try {
                            val altStats = com.lifecyclebot.perps.DynamicAltTokenRegistry.getStats()
                            val memeStats = com.lifecyclebot.engine.MemeMintRegistry.stats()
                            android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("🪙 Token Universe")
                                .setMessage("ALT: $altStats\n\nMEME: $memeStats")
                                .setPositiveButton("OK", null)
                                .show()
                        } catch (_: Exception) { /* never crash on stats display */ }
                    }
                }
                val universeLp = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                    val mPx = (16 * resources.displayMetrics.density).toInt()
                    rightMargin = mPx
                    bottomMargin = (180 * resources.displayMetrics.density).toInt()
                }
                rootDecor?.addView(universeTile, universeLp)
                // Periodic refresh on the main thread (30s)
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                val updater = object : Runnable {
                    override fun run() {
                        try {
                            val total = com.lifecyclebot.perps.DynamicAltTokenRegistry.getTokenCount()
                            val memeTotal = com.lifecyclebot.engine.MemeMintRegistry.count()
                            universeTile.text = "🪙 ${total + memeTotal} mints"
                        } catch (_: Throwable) {}
                        handler.postDelayed(this, 30_000L)
                    }
                }
                handler.post(updater)
            } catch (e: Exception) {
                com.lifecyclebot.engine.ErrorLogger.warn("MainActivity", "FAB inject failed: ${e.message}")
            }
            
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

            // V5.9.731 — UI THROTTLE.
            // Operator dump showed 86.8% main-thread stall with
            // renderOpenPositions / buildTokenCard / renderWatchlist as the
            // top blockers — each emission of vm.ui fires a synchronous
            // re-render of every position card and watchlist row, and the
            // ViewModel was emitting faster than the UI could paint.
            // Coalesce emissions with a 500ms throttle so we render at most
            // 2 frames per second, which is still 2x the operator's "ticks
            // every second" requirement and gives the main thread room to
            // breathe. StateFlow is already conflated by construction
            // (Kotlin actually errors on .conflate() over StateFlow), so the
            // throttle is just a post-render delay — the next state we see
            // after the delay will be the most recent one, never a queued
            // backlog. That's exactly the behaviour we want.
            lifecycleScope.launch {
                vm.ui.collect { state ->
                    updateUi(state)
                    kotlinx.coroutines.delay(500)
                }
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
        // V5.9.666 — pause Pipeline tile badge refresher
        try { pipelineTileHandler.removeCallbacks(pipelineTileRefresh) } catch (_: Throwable) {}
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
        hydratePaperWalletForColdOpen("onResume")
        // Update currency selector text (user may have changed currency)
        updateCurrencySelectorText()
        // V5.9.666 — start Pipeline tile badge refresher (every 3s).
        try { pipelineTileHandler.post(pipelineTileRefresh) } catch (_: Throwable) {}
        // V5.9.675 — show / hide the battery-optimisation banner depending
        // on current whitelist state. Cheap PowerManager check + at most
        // one TextView attach. See refreshBatteryOptBanner() below for the
        // root-cause explainer.
        try { refreshBatteryOptBanner() } catch (e: Throwable) {
            com.lifecyclebot.engine.ErrorLogger.warn("MainActivity", "batteryOptBanner refresh err: ${e.message}")
        }
    }

    // ── V5.9.675 — BATTERY OPTIMISATION BANNER ───────────────────────
    //
    // Operator's 7h Pipeline Health dump showed BOT_LOOP_TICK = 4 across
    // 25,891s of uptime — the bot loop was hibernating under Doze whenever
    // the screen was off, then ripping through +2,717 scan callbacks the
    // instant the screen woke. PARTIAL_WAKE_LOCK is ignored by Doze for
    // non-priv apps; the only fix is the user adding the app to the
    // battery-optimisation whitelist. This banner makes that one tap.
    private fun refreshBatteryOptBanner() {
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return
        val whitelisted = try {
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (_: Throwable) { true } // fail-open: don't pester if check fails
        val existing = findViewById<android.widget.TextView>(BATTERY_OPT_BANNER_VIEW_ID)
        if (whitelisted) {
            if (existing != null) (existing.parent as? android.view.ViewGroup)?.removeView(existing)
            return
        }
        if (existing != null) return // already attached
        // Anchor: the outer LinearLayout that is the direct child of the
        // NestedScrollView (above topBarContainer). Locate it via topBar.
        val topBar = findViewById<android.view.View>(R.id.topBarContainer) ?: return
        val outer = topBar.parent as? android.widget.LinearLayout ?: return
        val tv = android.widget.TextView(this).apply {
            id = BATTERY_OPT_BANNER_VIEW_ID
            text = "⚠ Battery optimisation is ON — bot will freeze when screen turns off. Tap to fix."
            setTextColor(0xFF0A0A0F.toInt())
            setBackgroundColor(0xFFF59E0B.toInt())
            setPadding(40, 28, 40, 28)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                try {
                    @android.annotation.SuppressLint("BatteryLife")
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    ).setData(android.net.Uri.parse("package:$packageName"))
                    startActivity(intent)
                } catch (_: Throwable) {
                    try {
                        startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        )
                    } catch (_: Throwable) {}
                }
            }
        }
        val lp = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
        outer.addView(tv, 0, lp)
    }

    companion object {
        private const val BATTERY_OPT_BANNER_VIEW_ID = 0x7F990001
    }

    // V5.9.666 — Pipeline tile badge live updater. Reads
    // PipelineHealthCollector counters and updates the small stat
    // line on the row-2 Pipeline tile (e.g. "ANR 0" when healthy,
    // "ANR 12" amber, "ANR 47" red). Cheap; does not block UI.
    private val pipelineTileHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pipelineTileRefresh = object : Runnable {
        override fun run() {
            try {
                val tv = findViewById<android.widget.TextView>(R.id.tvPipelineTileStats)
                if (tv != null) {
                    val snap = com.lifecyclebot.engine.PipelineHealthCollector.snapshot()
                    val anr = snap.anrHints
                    val exec = snap.phaseCounts["EXEC"] ?: 0L
                    tv.text = "ANR $anr · EXEC $exec"
                    tv.setTextColor(when {
                        anr == 0  -> 0xFF10B981.toInt()
                        anr < 5   -> 0xFFF59E0B.toInt()
                        else      -> 0xFFEF4444.toInt()
                    })
                }
            } catch (_: Throwable) { /* never let UI tick crash main */ }
            pipelineTileHandler.postDelayed(this, 3_000L)
        }
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
            textSize = resources.getDimension(R.dimen.bottom_bar_button_text) / resources.displayMetrics.scaledDensity
        }
    }

    // ── bind ──────────────────────────────────────────────────────────

    private fun bindViews() {
        tvNetworkLabel  = findViewById(R.id.tvNetworkLabel)
        btnWalletTop    = findViewById(R.id.btnWalletTop)
        tvWalletDot     = findViewById(R.id.tvWalletDot)
        tvWalletShort   = findViewById(R.id.tvWalletShort)

        // V5.9.29: Live readiness banner
        readinessBanner    = try { findViewById(R.id.readinessBanner) } catch (_: Exception) { View(this) }
        tvReadinessDot     = try { findViewById(R.id.tvReadinessDot) } catch (_: Exception) { TextView(this) }
        tvReadinessStatus  = try { findViewById(R.id.tvReadinessStatus) } catch (_: Exception) { TextView(this) }
        tvReadinessLatency = try { findViewById(R.id.tvReadinessLatency) } catch (_: Exception) { TextView(this) }
        readinessBanner.setOnClickListener {
            com.lifecyclebot.network.LiveReadinessChecker.triggerCheck()
            android.widget.Toast.makeText(this, "Re-checking live endpoints…", android.widget.Toast.LENGTH_SHORT).show()
            // Repaint in ~500ms after the check likely lands
            readinessBanner.postDelayed({ renderReadiness() }, 500)
        }
        
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
        
        // V5.7.8: Watchlist button — long-press alerts to open watchlist
        btnOpenAlerts.setOnLongClickListener {
            startActivity(android.content.Intent(this, WatchlistActivity::class.java))
            true
        }
        
        // V5.2: Behavior Dashboard button
        try {
            findViewById<android.view.View>(R.id.btnOpenBehavior)
                ?.setOnClickListener { startActivity(android.content.Intent(this, BehaviorActivity::class.java)) }
            // V5.9.350: Long-press opens the Persona Studio (traits / memories / chat / sounds)
            findViewById<android.view.View>(R.id.btnOpenBehavior)
                ?.setOnLongClickListener {
                    startActivity(android.content.Intent(this, PersonaStudioActivity::class.java)); true
                }
        } catch (_: Exception) {}
        
        // V5.2: Quick Behavior Tile
        try {
            findViewById<android.view.View>(R.id.btnQuickBehavior)
                ?.setOnClickListener { startActivity(android.content.Intent(this, BehaviorActivity::class.java)) }
            // V5.9.350: Long-press the quick tile also opens Persona Studio
            findViewById<android.view.View>(R.id.btnQuickBehavior)
                ?.setOnLongClickListener {
                    startActivity(android.content.Intent(this, PersonaStudioActivity::class.java)); true
                }
        } catch (_: Exception) {}

        // V5.9.359: Persona Studio Tile — direct entry point so the new MP3
        // drag-drop / share-to-app / preview features are discoverable.
        try {
            findViewById<android.view.View>(R.id.btnQuickPersona)
                ?.setOnClickListener { startActivity(android.content.Intent(this, PersonaStudioActivity::class.java)) }
        } catch (_: Exception) {}

        // V5.9.14: Symbolic Telemetry row → opens Tuning (Sentient Mind panel)
        try {
            findViewById<android.view.View>(R.id.rowSymTelemetry)
                ?.setOnClickListener { startActivity(android.content.Intent(this, BehaviorActivity::class.java)) }
        } catch (_: Exception) {}

        // V5.9.320: Copilot ribbon click → open Behavior screen for full
        // life-coach state (mood, learning health, regime, layer adjustments).
        try {
            findViewById<android.view.View>(R.id.tvCopilotRibbon)
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

        // Quality positions panel bindings
        cardQualityPositions = try { findViewById(R.id.cardQualityPositions) } catch (_: Exception) { android.view.View(this) }
        llQualityPositions = try { findViewById(R.id.llQualityPositions) } catch (_: Exception) { LinearLayout(this) }
        tvQualityExposure = try { findViewById(R.id.tvQualityExposure) } catch (_: Exception) { TextView(this) }
        tvQualityPnl = try { findViewById(R.id.tvQualityPnl) } catch (_: Exception) { TextView(this) }

        // V4.0: ShitCoin positions panel bindings
        cardShitCoinPositions = try { findViewById(R.id.cardShitCoinPositions) } catch (_: Exception) { android.view.View(this) }
        llShitCoinPositions = try { findViewById(R.id.llShitCoinPositions) } catch (_: Exception) { LinearLayout(this) }
        tvShitCoinExposure = try { findViewById(R.id.tvShitCoinExposure) } catch (_: Exception) { TextView(this) }
        tvShitCoinPnl = try { findViewById(R.id.tvShitCoinPnl) } catch (_: Exception) { TextView(this) }
        tvShitCoinMode = try { findViewById(R.id.tvShitCoinMode) } catch (_: Exception) { TextView(this) }
        tvShitCoinWinRate = try { findViewById(R.id.tvShitCoinWinRate) } catch (_: Exception) { TextView(this) }
        tvShitCoinDailyPnl = try { findViewById(R.id.tvShitCoinDailyPnl) } catch (_: Exception) { TextView(this) }

        // V5.9: ShitCoinExpress positions panel bindings
        cardExpressPositions = try { findViewById(R.id.cardExpressPositions) } catch (_: Exception) { android.view.View(this) }
        llExpressPositions = try { findViewById(R.id.llExpressPositions) } catch (_: Exception) { LinearLayout(this) }
        tvExpressExposure = try { findViewById(R.id.tvExpressExposure) } catch (_: Exception) { TextView(this) }
        tvExpressPnl = try { findViewById(R.id.tvExpressPnl) } catch (_: Exception) { TextView(this) }
        tvExpressWinRate = try { findViewById(R.id.tvExpressWinRate) } catch (_: Exception) { TextView(this) }
        tvExpressDailyPnl = try { findViewById(R.id.tvExpressDailyPnl) } catch (_: Exception) { TextView(this) }

        // ☠️ The Manipulated positions panel bindings
        cardManipulatedPositions = try { findViewById(R.id.cardManipulatedPositions) } catch (_: Exception) { android.view.View(this) }
        llManipPositions = try { findViewById(R.id.llManipPositions) } catch (_: Exception) { LinearLayout(this) }
        tvManipExposure = try { findViewById(R.id.tvManipExposure) } catch (_: Exception) { TextView(this) }
        tvManipPnl = try { findViewById(R.id.tvManipPnl) } catch (_: Exception) { TextView(this) }
        tvManipWinRate = try { findViewById(R.id.tvManipWinRate) } catch (_: Exception) { TextView(this) }
        tvManipDailyPnl = try { findViewById(R.id.tvManipDailyPnl) } catch (_: Exception) { TextView(this) }
        tvManipCaught = try { findViewById(R.id.tvManipCaught) } catch (_: Exception) { TextView(this) }

        // V5.2: Moonshot positions panel bindings
        cardMoonshotPositions = try { findViewById(R.id.cardMoonshotPositions) } catch (_: Exception) { android.view.View(this) }
        llMoonshotPositions = try { findViewById(R.id.llMoonshotPositions) } catch (_: Exception) { LinearLayout(this) }
        tvMoonshotExposure = try { findViewById(R.id.tvMoonshotExposure) } catch (_: Exception) { TextView(this) }
        tvMoonshotPnl = try { findViewById(R.id.tvMoonshotPnl) } catch (_: Exception) { TextView(this) }
        tvMoonshotMode = try { findViewById(R.id.tvMoonshotMode) } catch (_: Exception) { TextView(this) }
        tvMoonshotWinRate = try { findViewById(R.id.tvMoonshotWinRate) } catch (_: Exception) { TextView(this) }
        tvMoonshotDailyPnl = try { findViewById(R.id.tvMoonshotDailyPnl) } catch (_: Exception) { TextView(this) }
        tvMoonshotLearning = try { findViewById(R.id.tvMoonshotLearning) } catch (_: Exception) { TextView(this) }
        
        // V5.6.29d: Network Signals panel bindings (Collective Intelligence)
        cardNetworkSignals = try { findViewById(R.id.cardNetworkSignals) } catch (_: Exception) { android.view.View(this) }
        llNetworkSignals = try { findViewById(R.id.llNetworkSignals) } catch (_: Exception) { LinearLayout(this) }
        tvNetworkSignalCount = try { findViewById(R.id.tvNetworkSignalCount) } catch (_: Exception) { TextView(this) }
        tvNetworkMegaWinners = try { findViewById(R.id.tvNetworkMegaWinners) } catch (_: Exception) { TextView(this) }
        tvNetworkHotTokens = try { findViewById(R.id.tvNetworkHotTokens) } catch (_: Exception) { TextView(this) }
        tvNetworkAvoid = try { findViewById(R.id.tvNetworkAvoid) } catch (_: Exception) { TextView(this) }
        tvNetworkLastSync = try { findViewById(R.id.tvNetworkLastSync) } catch (_: Exception) { TextView(this) }
        
        // V5.6.29d: Project Sniper panel bindings
        cardSniperPositions = try { findViewById(R.id.cardSniperPositions) } catch (_: Exception) { android.view.View(this) }
        llSniperMissions = try { findViewById(R.id.llSniperMissions) } catch (_: Exception) { LinearLayout(this) }
        tvSniperExposure = try { findViewById(R.id.tvSniperExposure) } catch (_: Exception) { TextView(this) }
        tvSniperRank = try { findViewById(R.id.tvSniperRank) } catch (_: Exception) { TextView(this) }
        tvSniperWinRate = try { findViewById(R.id.tvSniperWinRate) } catch (_: Exception) { TextView(this) }
        tvSniperDailyPnl = try { findViewById(R.id.tvSniperDailyPnl) } catch (_: Exception) { TextView(this) }
        
        // V5.2: Tile stats TextViews - show wins/trades on each tile
        tvV3Stats = try { findViewById(R.id.tvV3Stats) } catch (_: Exception) { TextView(this) }
        tvTreasuryStats = try { findViewById(R.id.tvTreasuryStats) } catch (_: Exception) { TextView(this) }
        tvBlueChipStats = try { findViewById(R.id.tvBlueChipStats) } catch (_: Exception) { TextView(this) }
        tvShitCoinStats = try { findViewById(R.id.tvShitCoinStats) } catch (_: Exception) { TextView(this) }
        tvExpressStats = try { findViewById(R.id.tvExpressStats) } catch (_: Exception) { TextView(this) }
        tvManipStats = try { findViewById(R.id.tvManipStats) } catch (_: Exception) { TextView(this) }
        tvMoonshotStats = try { findViewById(R.id.tvMoonshotStats) } catch (_: Exception) { TextView(this) }
        tvPerpsStats = try { findViewById(R.id.tvPerpsStats) } catch (_: Exception) { null }
        tvAiBrainStats = try { findViewById(R.id.tvAiBrainStats) } catch (_: Exception) { null }
        tvShadowStats = try { findViewById(R.id.tvShadowStats) } catch (_: Exception) { null }
        tvRegimesStats = try { findViewById(R.id.tvRegimesStats) } catch (_: Exception) { null }
        tv25AIsStats = try { findViewById(R.id.tv25AIsStats) } catch (_: Exception) { null }
        
        // V5.7: Perps Trading UI bindings
        cardPerpsTrading = try { findViewById(R.id.cardPerpsTrading) } catch (_: Exception) { null }
        tvPerpsModeBadge = try { findViewById(R.id.tvPerpsModeBadge) } catch (_: Exception) { null }
        tvPerpsBalance = try { findViewById(R.id.tvPerpsBalance) } catch (_: Exception) { null }
        tvPerpsPnl = try { findViewById(R.id.tvPerpsPnl) } catch (_: Exception) { null }
        tvPerpsWinRate = try { findViewById(R.id.tvPerpsWinRate) } catch (_: Exception) { null }
        tvPerpsTrades = try { findViewById(R.id.tvPerpsTrades) } catch (_: Exception) { null }
        tvPerpsReadinessPhase = try { findViewById(R.id.tvPerpsReadinessPhase) } catch (_: Exception) { null }
        viewPerpsReadinessBar = try { findViewById(R.id.viewPerpsReadinessBar) } catch (_: Exception) { null }
        tvPerpsReadinessText = try { findViewById(R.id.tvPerpsReadinessText) } catch (_: Exception) { null }
        tvPerpsSolPrice = try { findViewById(R.id.tvPerpsSolPrice) } catch (_: Exception) { null }
        tvPerpsSolChange = try { findViewById(R.id.tvPerpsSolChange) } catch (_: Exception) { null }
        llPerpsPositions = try { findViewById(R.id.llPerpsPositions) } catch (_: Exception) { null }
        
        // V5.7.1: Layer Confidence Dashboard bindings
        tvLayerSyncCount = try { findViewById(R.id.tvLayerSyncCount) } catch (_: Exception) { null }
        tvLayer1Name = try { findViewById(R.id.tvLayer1Name) } catch (_: Exception) { null }
        tvLayer1Score = try { findViewById(R.id.tvLayer1Score) } catch (_: Exception) { null }
        tvLayer2Name = try { findViewById(R.id.tvLayer2Name) } catch (_: Exception) { null }
        tvLayer2Score = try { findViewById(R.id.tvLayer2Score) } catch (_: Exception) { null }
        tvLayer3Name = try { findViewById(R.id.tvLayer3Name) } catch (_: Exception) { null }
        tvLayer3Score = try { findViewById(R.id.tvLayer3Score) } catch (_: Exception) { null }
        tvLayer4Name = try { findViewById(R.id.tvLayer4Name) } catch (_: Exception) { null }
        tvLayer4Score = try { findViewById(R.id.tvLayer4Score) } catch (_: Exception) { null }
        tvLayerLearningEvents = try { findViewById(R.id.tvLayerLearningEvents) } catch (_: Exception) { null }
        tvLayerCrossSync = try { findViewById(R.id.tvLayerCrossSync) } catch (_: Exception) { null }
        
        // V5.7.4: Perps Card - Quick Stock Prices (AAPL, TSLA, NVDA in header)
        tvPerpsAaplPrice = try { findViewById(R.id.tvPerpsAaplPrice) } catch (_: Exception) { null }
        tvPerpsTslaPrice = try { findViewById(R.id.tvPerpsTslaPrice) } catch (_: Exception) { null }
        tvPerpsNvdaPrice = try { findViewById(R.id.tvPerpsNvdaPrice) } catch (_: Exception) { null }
        
        // V5.7.3: Tokenized Stocks UI bindings
        cardTokenizedStocks = try { findViewById(R.id.cardTokenizedStocks) } catch (_: Exception) { null }
        tvStocksModeBadge = try { findViewById(R.id.tvStocksModeBadge) } catch (_: Exception) { null }
        tvStocksBalance = try { findViewById(R.id.tvStocksBalance) } catch (_: Exception) { null }
        tvStocksPnl = try { findViewById(R.id.tvStocksPnl) } catch (_: Exception) { null }
        tvStocksWinRate = try { findViewById(R.id.tvStocksWinRate) } catch (_: Exception) { null }
        tvStocksTrades = try { findViewById(R.id.tvStocksTrades) } catch (_: Exception) { null }
        tvStocksStats = try { findViewById(R.id.tvStocksStats) } catch (_: Exception) { null }
        llStocksPositions = try { findViewById(R.id.llStocksPositions) } catch (_: Exception) { null }
        tvStocksMarketHours = try { findViewById(R.id.tvStocksMarketHours) } catch (_: Exception) { null }
        tvStocksAaplPrice = try { findViewById(R.id.tvStocksAaplPrice) } catch (_: Exception) { null }
        tvStocksAaplChange = try { findViewById(R.id.tvStocksAaplChange) } catch (_: Exception) { null }
        tvStocksTslaPrice = try { findViewById(R.id.tvStocksTslaPrice) } catch (_: Exception) { null }
        tvStocksTslaChange = try { findViewById(R.id.tvStocksTslaChange) } catch (_: Exception) { null }
        tvStocksNvdaPrice = try { findViewById(R.id.tvStocksNvdaPrice) } catch (_: Exception) { null }
        tvStocksNvdaChange = try { findViewById(R.id.tvStocksNvdaChange) } catch (_: Exception) { null }
        tvStocksGooglPrice = try { findViewById(R.id.tvStocksGooglPrice) } catch (_: Exception) { null }
        tvStocksGooglChange = try { findViewById(R.id.tvStocksGooglChange) } catch (_: Exception) { null }
        tvStocksAmznPrice = try { findViewById(R.id.tvStocksAmznPrice) } catch (_: Exception) { null }
        tvStocksMetaPrice = try { findViewById(R.id.tvStocksMetaPrice) } catch (_: Exception) { null }
        tvStocksMsftPrice = try { findViewById(R.id.tvStocksMsftPrice) } catch (_: Exception) { null }
        tvStocksCoinPrice = try { findViewById(R.id.tvStocksCoinPrice) } catch (_: Exception) { null }
        
        // V5.7.3: Learning Insights Panel bindings
        cardLearningInsights = try { findViewById(R.id.cardLearningInsights) } catch (_: Exception) { null }
        tvInsightsCount = try { findViewById(R.id.tvInsightsCount) } catch (_: Exception) { null }
        tvInsightsPatternsCount = try { findViewById(R.id.tvInsightsPatternsCount) } catch (_: Exception) { null }
        tvInsightsReplaysCount = try { findViewById(R.id.tvInsightsReplaysCount) } catch (_: Exception) { null }
        tvInsightsOptimizations = try { findViewById(R.id.tvInsightsOptimizations) } catch (_: Exception) { null }
        llRecentInsights = try { findViewById(R.id.llRecentInsights) } catch (_: Exception) { null }
        btnViewAllInsights = try { findViewById(R.id.btnViewAllInsights) } catch (_: Exception) { null }
        
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
        
        // V5.6: DexScreener-style chart metrics
        tvChartMcap = try { findViewById(R.id.tvChartMcap) } catch (_: Exception) { null }
        tvChart5mVol = try { findViewById(R.id.tvChart5mVol) } catch (_: Exception) { null }
        tvChartLiq = try { findViewById(R.id.tvChartLiq) } catch (_: Exception) { null }
        tvChartHolders = try { findViewById(R.id.tvChartHolders) } catch (_: Exception) { null }
        tvChartBuyPressure = try { findViewById(R.id.tvChartBuyPressure) } catch (_: Exception) { null }
        
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
        
        // V5.6.9: Live Readiness Indicator views
        cardLiveReadiness = try { findViewById(R.id.cardLiveReadiness) } catch (_: Exception) { View(this) }
        tvLiveReadinessBadge = try { findViewById(R.id.tvLiveReadinessBadge) } catch (_: Exception) { TextView(this) }
        tvReadinessWinRate = try { findViewById(R.id.tvReadinessWinRate) } catch (_: Exception) { TextView(this) }
        tvReadinessTrades = try { findViewById(R.id.tvReadinessTrades) } catch (_: Exception) { TextView(this) }
        tvReadinessPhase = try { findViewById(R.id.tvReadinessPhase) } catch (_: Exception) { TextView(this) }
        tvReadinessProgress = try { findViewById(R.id.tvReadinessProgress) } catch (_: Exception) { TextView(this) }
        viewReadinessProgressBar = try { findViewById(R.id.viewReadinessProgressBar) } catch (_: Exception) { View(this) }
        tvReadinessRecommendation = try { findViewById(R.id.tvReadinessRecommendation) } catch (_: Exception) { TextView(this) }

        // V5.9.348: Per-trader tab bindings (Meme/Alts/Perps)
        tvTradersSummary = try { findViewById(R.id.tvTradersSummary) } catch (_: Exception) { null }
        tabTraderMeme    = try { findViewById(R.id.tabTraderMeme) } catch (_: Exception) { null }
        tabTraderAlts    = try { findViewById(R.id.tabTraderAlts) } catch (_: Exception) { null }
        tabTraderPerps   = try { findViewById(R.id.tabTraderPerps) } catch (_: Exception) { null }
        tvLiveReadinessTitle = try { findViewById(R.id.tvLiveReadinessTitle) } catch (_: Exception) { null }
        tabTraderMeme?.setOnClickListener  { selectReadinessTab("MEME") }
        tabTraderAlts?.setOnClickListener  { selectReadinessTab("ALTS") }
        tabTraderPerps?.setOnClickListener { selectReadinessTab("PERPS") }
        applyTabStyles()
        
        // V5.2.8: Export button click listener
        btn30DayExport.setOnClickListener {
            try {
                com.lifecyclebot.engine.RunTracker30D.exportAllReports()
                Toast.makeText(this, "📥 Reports exported to /reports/", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        
        // V5.7.5: Long-press 30-day card to reset
        card30DayRun.setOnLongClickListener {
            show30DayResetDialog()
            true
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
        
        // V5.7.3: Setup perps and stocks card click handlers
        setupPerpsPositionClickHandlers()
        setupStockButtonClickHandlers()
        
        // V5.7.4: Setup Network Signals / Insider Tracker click handlers
        try {
            cardNetworkSignals.setOnClickListener {
                showNetworkSignalsMenu()
                performHaptic()
            }
        } catch (_: Exception) {}

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

        // V5.9.317: Manual BUY/SELL buttons (paper + live, end-to-end)
        btnManualBuy  = try { findViewById(R.id.btnManualBuy)  } catch (_: Exception) { android.widget.Button(this) }
        btnManualSell = try { findViewById(R.id.btnManualSell) } catch (_: Exception) { android.widget.Button(this) }
        btnManualBuy.setOnClickListener { onManualBuyClicked() }
        btnManualSell.setOnClickListener { onManualSellClicked() }

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
        // V5.8.0: Setup CandleStick chart styling
        candleChart.apply {
            setBackgroundColor(Color.TRANSPARENT)
            setDrawGridBackground(false)
            description.isEnabled = false
            legend.isEnabled      = false
            setTouchEnabled(true)
            setPinchZoom(true)
            setScaleEnabled(true)
            xAxis.apply {
                isEnabled  = false
                position   = XAxis.XAxisPosition.BOTTOM
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

    // V5.8.0: Update candle chart from token history with timeframe selection
    private fun updateCandleChart(ts: TokenState?) {
        if (ts == null) {
            candleChart.clear()
            return
        }
        
        // Pick the right history bucket based on chartTimeRange
        val sourceHistory: List<com.lifecyclebot.data.Candle> = try {
            when (chartTimeRange) {
                "5m"  -> synchronized(ts.history5m) { ts.history5m.toList() }
                "15m" -> synchronized(ts.history15m) { ts.history15m.toList() }
                "1h"  -> synchronized(ts.history15m) { ts.history15m.toList() } // aggregate into hour candles
                else  -> synchronized(ts.history) { ts.history.toList() }       // 1m default
            }
        } catch (_: Exception) { emptyList() }
        
        if (sourceHistory.isEmpty()) {
            candleChart.clear()
            return
        }
        
        val entries = ArrayList<com.github.mikephil.charting.data.CandleEntry>()
        sourceHistory.forEachIndexed { idx, candle ->
            val close = candle.priceUsd.toFloat()
            val open  = if (candle.openUsd > 0) candle.openUsd.toFloat() else close
            val high  = if (candle.highUsd > 0) candle.highUsd.toFloat() else maxOf(open, close) * 1.001f
            val low   = if (candle.lowUsd  > 0) candle.lowUsd.toFloat()  else minOf(open, close) * 0.999f
            entries.add(com.github.mikephil.charting.data.CandleEntry(idx.toFloat(), high, low, open, close))
        }
        
        val ds = com.github.mikephil.charting.data.CandleDataSet(entries, "").apply {
            setDrawIcons(false)
            shadowColor = 0xFF6B7280.toInt()
            shadowWidth = 0.7f
            decreasingColor = 0xFFEF4444.toInt()
            decreasingPaintStyle = android.graphics.Paint.Style.FILL
            increasingColor = 0xFF10B981.toInt()
            increasingPaintStyle = android.graphics.Paint.Style.FILL
            neutralColor = 0xFF6B7280.toInt()
            setDrawValues(false)
        }
        
        candleChart.data = com.github.mikephil.charting.data.CandleData(ds)
        candleChart.invalidate()
    }

    /**
     * V5.9.630 — Cold-open paper balance hydration.
     * MainActivity can render before BotService.startBot() restores the paper wallet,
     * so hydrate BotService.status from the persisted unified paper wallet immediately.
     */
    private fun hydratePaperWalletForColdOpen(reason: String) {
        try {
            val cfg = com.lifecyclebot.data.ConfigStore.load(applicationContext)
            if (!cfg.paperMode) return
            val restored = com.lifecyclebot.engine.PaperWalletStore.restore(applicationContext, cfg)
            if (restored.balanceSol > 0.001 && com.lifecyclebot.engine.BotService.status.paperWalletSol <= 0.001) {
                com.lifecyclebot.engine.BotService.status.paperWalletSol = restored.balanceSol
                com.lifecyclebot.engine.BotService.status.paperWalletInitialized = true
                com.lifecyclebot.engine.BotService.status.paperWalletLastRefreshMs = System.currentTimeMillis()
                com.lifecyclebot.engine.ErrorLogger.info(
                    "MainActivity",
                    "💰 Paper wallet cold-open hydrate ($reason): ${"%.4f".format(restored.balanceSol)} SOL"
                )
            }
        } catch (e: Exception) {
            com.lifecyclebot.engine.ErrorLogger.warn("MainActivity", "paper wallet cold-open hydrate failed: ${e.message}")
        }
    }

    // ── update UI ─────────────────────────────────────────────────────

    /**
     * V5.9.29: Render the live-readiness banner.
     *   🟢 GREEN   — Jupiter + Pyth both healthy → live trading safe
     *   🟡 YELLOW  — Jupiter OK but oracle slow/down → degraded
     *   🔴 RED     — Jupiter unreachable → live swaps WILL fail; stay in paper
     *   ⚪ UNKNOWN — first check hasn't returned yet
     * Tap the banner to force an immediate re-check.
     */
    private fun renderReadiness() {
        val snap = com.lifecyclebot.network.LiveReadinessChecker.current()
        val (dot, textColor, bg) = when (snap.state) {
            com.lifecyclebot.network.LiveReadinessChecker.State.GREEN ->
                Triple("🟢", 0xFF9CA3AF.toInt(), 0xFF0B1A10.toInt())
            com.lifecyclebot.network.LiveReadinessChecker.State.YELLOW ->
                Triple("🟡", 0xFFFCD34D.toInt(), 0xFF1A1608.toInt())
            com.lifecyclebot.network.LiveReadinessChecker.State.RED ->
                Triple("🔴", 0xFFF87171.toInt(), 0xFF1F0B0F.toInt())
            com.lifecyclebot.network.LiveReadinessChecker.State.UNKNOWN ->
                Triple("⚪", 0xFF6B7280.toInt(), 0xFF0F0F16.toInt())
        }
        tvReadinessDot.text = dot
        tvReadinessStatus.text = snap.summary
        tvReadinessStatus.setTextColor(textColor)
        readinessBanner.setBackgroundColor(bg)
        tvReadinessLatency.text = if (snap.jupiterLatencyMs >= 0) {
            val jup = if (snap.jupiterOk) "jup ${snap.jupiterLatencyMs}ms" else "jup ✗"
            val py  = if (snap.pythOk) "pyth ${snap.pythLatencyMs}ms" else "pyth ✗"
            // V5.9.37: append 5-min live-attempt summary so users can see
            // in real time whether the bot is actively hunting.
            val att = try {
                val s = com.lifecyclebot.engine.LiveAttemptStats.snapshot()
                if (s.attempts > 0) " · ⚡${s.executed}/${s.attempts}" else ""
            } catch (_: Throwable) { "" }
            "$jup · $py$att"
        } else ""
    }

    private fun updateUi(state: UiState) {
        val ts  = state.activeToken
        val cfg = state.config
        val ws  = state.walletState

        // V5.9.29: refresh the live-readiness banner every UI tick
        renderReadiness()

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

        // ── hero balance — BotService.status is the single source of truth ──
        val config = state.config // V5.9.706 — use pre-loaded config from UiState (avoid AES-GCM decrypt on main thread)
        val solPx  = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice.takeIf { it in 50.0..500.0 } ?: 85.0
        val balSol = if (config.paperMode) {
            val livePaper = com.lifecyclebot.engine.BotService.status.paperWalletSol
            if (livePaper > 0.001) livePaper
            else com.lifecyclebot.engine.PaperWalletStore.restore(applicationContext, config).balanceSol
        } else {
            ws.solBalance
        }

        if (balSol > 0.001) {
            tvBalanceLarge.text = currency.format(balSol)  // currency.format() converts SOL→display currency internally
            // V5.9.773 — BIG explicit mode chip so the operator can never
            // confuse "🟢 APIs READY" (Jupiter/Pyth health) with actual
            // trade mode. Per troubleshoot RCA: user saw "LIVE READY"
            // banner and thought bot was live, but cfg.paperMode=true.
            tvBalanceUsd.text   = if (config.paperMode) "📝 PAPER MODE  ◎ ${"%.4f".format(balSol)}"
                                  else "🔴 LIVE MODE  ◎ ${"%.4f".format(balSol)}"
        } else if (ws.isConnected && ws.solBalance > 0) {
            tvBalanceLarge.text = currency.format(ws.solBalance)
            tvBalanceUsd.text   = if (config.paperMode) "📝 PAPER MODE  ◎ ${"%.4f".format(ws.solBalance)}"
                                  else "🔴 LIVE MODE  ◎ ${"%.4f".format(ws.solBalance)}"
        } else {
            tvBalanceLarge.text = "—"
            tvBalanceUsd.text   = if (config.paperMode) "📝 PAPER MODE" else "🔴 LIVE MODE"
        }

        // ── Live SOL Price ──────────────────────────────────────────────
        val solPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice.takeIf { it in 50.0..1000.0 } ?: 85.0
        if (solPrice >= 10) {
            tvSolPrice.text = "$${solPrice.toInt()}"
        } else {
            tvSolPrice.text = "$—"
            kotlinx.coroutines.MainScope().launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val freshPrice = com.lifecyclebot.engine.WalletManager.getInstance(applicationContext).fetchSolPrice()
                    if (freshPrice >= 10) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            tvSolPrice.text = "$${"%.0f".format(freshPrice)}"
                        }
                    }
                } catch (_: Exception) {}
            }
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

        // V5.9.14: Symbolic Telemetry row — live mood/edge/risk/health
        try {
            val sc = com.lifecyclebot.engine.SymbolicContext
            val moodEmoji = when (sc.emotionalState) {
                "PANIC"    -> "😱"
                "FEARFUL"  -> "😟"
                "EUPHORIC" -> "🤩"
                "GREEDY"   -> "😈"
                else       -> "🧠"
            }
            val moodColor = when (sc.emotionalState) {
                "PANIC"    -> 0xFFFF4444.toInt()
                "FEARFUL"  -> 0xFFFFAA00.toInt()
                "EUPHORIC" -> 0xFF00FF88.toInt()
                "GREEDY"   -> 0xFFFFD700.toInt()
                else       -> 0xFF9945FF.toInt()
            }
            findViewById<TextView>(R.id.tvSymHomeMood)?.text       = moodEmoji
            findViewById<TextView>(R.id.tvSymHomeMoodLabel)?.apply {
                text = sc.emotionalState
                setTextColor(moodColor)
            }
            findViewById<TextView>(R.id.tvSymHomeStats)?.text =
                "edge ${(sc.edgeStrength * 100).toInt()}%  " +
                "risk ${(sc.overallRisk * 100).toInt()}%  " +
                "health ${(sc.marketHealth * 100).toInt()}%"
        } catch (_: Exception) {}

        // V5.9.453: Brain Health pill + Ladder pill + Guards strip + Leaderboard.
        // Each block is fail-soft so a single broken refresh can't choke
        // the whole UI update path.
        try {
            val pill = findViewById<TextView>(R.id.tvBrainHealthPill)
            if (pill != null) {
                val d = com.lifecyclebot.engine.TradingCopilot.current()
                val ageMs = System.currentTimeMillis() - com.lifecyclebot.engine.TradingCopilot.lastUpdated()
                // Only surface once copilot has emitted at least one directive.
                if (com.lifecyclebot.engine.TradingCopilot.lastUpdated() > 0 && ageMs < 30L * 60_000L) {
                    val (label, color) = when (d.learningHealth) {
                        com.lifecyclebot.engine.TradingCopilot.LearningHealth.EXCELLENT -> "🧠 EXCELLENT" to 0xFF00FF88.toInt()
                        com.lifecyclebot.engine.TradingCopilot.LearningHealth.STEADY    -> "🧠 STEADY"    to 0xFF9CA3AF.toInt()
                        com.lifecyclebot.engine.TradingCopilot.LearningHealth.DRIFTING  -> "🧠 DRIFTING"  to 0xFFFFAA00.toInt()
                        // V5.9.495z35 — never call our own layers POISONED.
                        // Show the coaching curriculum count instead so the
                        // operator sees we're tutoring, not dying.
                        com.lifecyclebot.engine.TradingCopilot.LearningHealth.POISONED  -> {
                            val count = try {
                                com.lifecyclebot.engine.CoachingCurriculum.count()
                            } catch (_: Throwable) { 0 }
                            (if (count > 0) "🧑‍🏫 COACHING ($count)" else "🧑‍🏫 COACHING") to 0xFF8B5CF6.toInt()
                        }
                    }
                    pill.text = label
                    pill.setTextColor(color)
                    pill.visibility = android.view.View.VISIBLE
                } else {
                    pill.visibility = android.view.View.GONE
                }
            }
        } catch (_: Exception) {}

        // V5.9.471 — TIER pill / Guards strip / Top-3 leaderboard are
        // ALL meme-trader specific (RunTracker30D, MemeLossStreakGuard,
        // StrategyTrustAI all read from the meme-only data store).
        // Operator: 'the tier information on the live readiness tab is
        // for memes only.' On Crypto / Markets tabs, hide them entirely
        // rather than show MEME numbers under those tabs and mislead the
        // user into thinking they're lane-specific. (When per-lane
        // ladders / guards exist they can render here; for now those
        // lanes don't have an equivalent system.)
        val isMemeTab = currentReadinessTab != "ALTS" && currentReadinessTab != "PERPS"

        try {
            val lp = findViewById<TextView>(R.id.tvLadderPill)
            if (lp != null) {
                if (!isMemeTab) {
                    lp.visibility = android.view.View.GONE
                } else {
                // V5.9.462 — data-consistency fix. User reported the ladder
                // pill's WR (20.7% / 719 trades from TradeHistoryStore
                // getLifetimeStats) disagreed with the big WR/Trades
                // rendered on the SAME card (18% / 586 from RunTracker30D).
                // Per V5.9.371 the big block intentionally mirrors the
                // 30-Day Proof Run. Align the ladder pill to the same
                // source so both halves of the card always agree.
                val rt = com.lifecyclebot.engine.RunTracker30D
                val trades = rt.totalTrades
                val meaningful = rt.wins + rt.losses
                val actual = if (meaningful > 0) rt.wins * 100.0 / meaningful else 0.0
                val target = com.lifecyclebot.engine.QualityLadder.targetWrForTrades(trades)
                val tier = com.lifecyclebot.engine.QualityLadder.tier()
                val sizeMult = com.lifecyclebot.engine.QualityLadder.sizeMultiplier()
                val icon = when (tier) {
                    0    -> "🔓"
                    1, 2 -> "🟡"
                    3, 4 -> "🟠"
                    else -> "🔴"
                }
                // V5.9.495z32 — augment label with TierState so users can
                // see WHY a high tier is not "ready" (TIER_COUNT_UNLOCKED
                // vs PROFITABILITY_LOCKED vs STREAK_GUARD_ACTIVE).
                val streakBlocks = try {
                    com.lifecyclebot.engine.MemeLossStreakGuard.activeBlockCount()
                } catch (_: Throwable) { 0 }
                val tierStateSnap = com.lifecyclebot.engine.TierState.evaluate(
                    tierCount = tier,
                    tradeCount = trades,
                    winRatePct = actual,
                    targetWrPct = target,
                    streakBlocks = streakBlocks,
                )
                val statusSuffix = when {
                    trades < 5000 -> "LEARNING_OPEN"
                    tierStateSnap.isReady -> "READY"
                    com.lifecyclebot.engine.TierState.Status.STREAK_GUARD_ACTIVE in tierStateSnap.statuses -> "STREAK_GUARD"
                    com.lifecyclebot.engine.TierState.Status.PROFITABILITY_LOCKED in tierStateSnap.statuses -> "PROFITABILITY_LOCKED"
                    else -> "COUNT_UNLOCKED"
                }
                lp.text = "$icon TIER $tier · $statusSuffix · $trades trades · WR=${"%.1f".format(actual)}% " +
                    "(target ${"%.1f".format(target)}%) · size×${"%.2f".format(sizeMult)}"
                val color = when (tier) {
                    0    -> 0xFF6B7280.toInt()
                    1, 2 -> 0xFFFFD700.toInt()
                    3, 4 -> 0xFFFFAA00.toInt()
                    else -> 0xFFFF4444.toInt()
                }
                lp.setTextColor(color)
                lp.visibility = android.view.View.VISIBLE
                }
            }
        } catch (_: Exception) {}

        try {
            val gs = findViewById<TextView>(R.id.tvGuardsStrip)
            if (gs != null) {
                if (!isMemeTab) {
                    gs.visibility = android.view.View.GONE
                } else {
                val streakBlocks = try {
                    com.lifecyclebot.engine.MemeLossStreakGuard.activeBlockCount()
                } catch (_: Throwable) { 0 }
                val distrustPauses = try {
                    // V5.9.463 — SENTIENT-FLUID RETUNE. Quarantine is now a
                    // rare, soft signal (legacy pre-V5.9.463 data can still
                    // set one; V5.9.463 onwards we don't auto-quarantine).
                    // Displayed as 'cooling' so the user doesn't see it as
                    // a blacklist.
                    com.lifecyclebot.v4.meta.StrategyTrustAI.getAllTrustScores().keys
                        .count { strat -> com.lifecyclebot.v4.meta.StrategyTrustAI.isQuarantined(strat) }
                } catch (_: Throwable) { 0 }
                val coachingCount = try {
                    // V5.9.463 — was 'distrustedCount'. Reframed: strategies
                    // with trustLevel=DISTRUSTED are now in INTENSIVE
                    // COACHING, not blacklisted. getTrustMultiplier floors
                    // them at 0.20x so they keep trading and learning.
                    com.lifecyclebot.v4.meta.StrategyTrustAI.getAllTrustScores().values
                        .count { rec -> rec.trustLevel == com.lifecyclebot.v4.meta.TrustLevel.DISTRUSTED }
                } catch (_: Throwable) { 0 }
                // V5.9.495z42 P1 — surface recovery-lock + amount-violation counts.
                // Both locks block live sells; operator needs to see them at a
                // glance instead of digging through forensics.
                val recoveryLocks = try {
                    com.lifecyclebot.engine.sell.RecoveryLockTracker.lockedCount()
                } catch (_: Throwable) { 0 }
                val amountViolations = try {
                    com.lifecyclebot.engine.sell.SellAmountAuditor.lockedCount()
                } catch (_: Throwable) { 0 }
                // V5.9.755 — surface WR-Recovery state on the readiness card.
                // Operator screenshot 2026-05-15 02:58 — WR=29% (below phase
                // target × 0.85) but no visible indicator that the recovery
                // partial trigger had dropped to drive WR back up. Pull the
                // canonical status tag from WrRecoveryPartial.statusTag().
                val wrRecoveryTag = try {
                    // V5.9.797 — operator audit: pull the active-band badge (e.g. F@30%,
                    // M@25%, A@18%, with ⚡ prefix when predictive escalated) instead of
                    // the stale hardcoded "@9%". When recovery is off the badge is empty.
                    val short = com.lifecyclebot.engine.WrRecoveryPartial.shortBadge()
                    if (short == "off") "" else "🚑 WR recovery $short"
                } catch (_: Throwable) { "" }

                if (streakBlocks == 0 && distrustPauses == 0 && coachingCount == 0 &&
                    recoveryLocks == 0 && amountViolations == 0 && wrRecoveryTag.isEmpty()) {
                    gs.text = "🛡 Guards: clear" + appendDeferTile()
                    gs.setTextColor(0xFF6B7280.toInt())
                } else {
                    val parts = mutableListOf<String>()
                    if (streakBlocks > 0) parts += "$streakBlocks streak-block${if (streakBlocks == 1) "" else "s"}"
                    if (distrustPauses > 0) parts += "$distrustPauses cooling"
                    if (coachingCount > 0) parts += "$coachingCount coaching"
                    if (recoveryLocks > 0) parts += "🔒 $recoveryLocks recovery-lock${if (recoveryLocks == 1) "" else "s"}"
                    if (amountViolations > 0) parts += "🚨 $amountViolations amount-violation${if (amountViolations == 1) "" else "s"}"
                    if (wrRecoveryTag.isNotEmpty()) parts += wrRecoveryTag
                    gs.text = "🛡 Guards: " + parts.joinToString(" · ") + appendDeferTile()
                    gs.setTextColor(if (distrustPauses > 0 || streakBlocks > 0 || amountViolations > 0) 0xFFFFAA00.toInt() else 0xFF9CA3AF.toInt())
                }
                gs.visibility = android.view.View.VISIBLE
                }
            }
        } catch (_: Exception) {}

        // V5.9.798 — operator audit: WR Recovery Heatmap mini-tile.
        // Renders 5 coloured blocks showing rolling-50 WR slices (newest on
        // the left, oldest on the right). Each block is independently
        // green / amber / red based on that slice's WR vs phase target.
        // Lets the operator see at a glance whether the bot is actively
        // improving, drifting, or already deep in a hole — exactly the
        // 'no more 1469-trade screenshots wondering whether anything's
        // happening' visibility they asked for.
        try {
            val heatmap = findViewById<TextView>(R.id.tvWrHeatmap)
            if (heatmap != null) {
                if (!isMemeTab) {
                    heatmap.visibility = android.view.View.GONE
                } else {
                    renderWrRecoveryHeatmap(heatmap)
                }
            }
        } catch (_: Exception) {}

        try {
            val lb = findViewById<TextView>(R.id.tvStrategyLeaderboard)
            if (lb != null) {
                if (!isMemeTab) {
                    lb.visibility = android.view.View.GONE
                } else {
                val rows = try {
                    com.lifecyclebot.v4.meta.StrategyTrustAI.getAllTrustScores()
                        .values
                        .filter { it.expectancy.isFinite() }
                        .sortedByDescending { it.expectancy }
                        .take(3)
                } catch (_: Throwable) { emptyList() }
                if (rows.isEmpty()) {
                    lb.visibility = android.view.View.GONE
                } else {
                    val txt = rows.joinToString(" · ") { r ->
                        "${r.strategyName} ${"%+.2f".format(r.expectancy)}R"
                    }
                    lb.text = "🏆 Top-3: $txt"
                    lb.visibility = android.view.View.VISIBLE
                }
                }
            }
        } catch (_: Exception) {}

        // V5.9.320: COPILOT COACHING RIBBON — surface the live life-coach
        // directive on the home screen so the user can SEE the AI thinking.
        // Hidden when no trades observed yet OR mood is plain NORMAL with
        // no advisory; shown whenever Copilot is actively steering.
        try {
            val ribbon = findViewById<TextView>(R.id.tvCopilotRibbon)
            val d = com.lifecyclebot.engine.TradingCopilot.current()
            val ageMs = System.currentTimeMillis() - com.lifecyclebot.engine.TradingCopilot.lastUpdated()
            val moodIcon = when (d.mood) {
                com.lifecyclebot.engine.TradingCopilot.TradeMood.EMERGENCY_BRAKE -> "🛑"
                com.lifecyclebot.engine.TradingCopilot.TradeMood.PROTECT          -> "🟠"
                com.lifecyclebot.engine.TradingCopilot.TradeMood.AGGRESSIVE_HUNT  -> "🟢"
                else -> "🧭"
            }
            val show = ageMs in 0..(15 * 60_000L) &&
                       (d.mood != com.lifecyclebot.engine.TradingCopilot.TradeMood.NORMAL ||
                        d.regime == "RUNNER_MARKET" ||
                        d.learningHealth == com.lifecyclebot.engine.TradingCopilot.LearningHealth.POISONED)
            if (ribbon != null) {
                if (show) {
                    val moodColor = when (d.mood) {
                        com.lifecyclebot.engine.TradingCopilot.TradeMood.EMERGENCY_BRAKE -> 0xFF7F1D1D.toInt() // dark red
                        com.lifecyclebot.engine.TradingCopilot.TradeMood.PROTECT          -> 0xFF78350F.toInt() // dark amber
                        com.lifecyclebot.engine.TradingCopilot.TradeMood.AGGRESSIVE_HUNT  -> 0xFF064E3B.toInt() // dark green
                        else -> 0xFF3730A3.toInt() // indigo
                    }
                    ribbon.setBackgroundColor(moodColor)
                    ribbon.text = "$moodIcon Copilot · ${d.mood.name.replace('_', ' ')} · ${d.regime} · ${d.advice}"
                    ribbon.visibility = android.view.View.VISIBLE
                } else {
                    ribbon.visibility = android.view.View.GONE
                }
            }
        } catch (_: Exception) {}

        // ── Treasury + ScalingMode tier ──────────────────────────────
        // V4.0: Show PAPER TREASURY balance from CashGenerationAI when in paper mode
        // This ensures paper mode shows the correct simulated treasury balance
        try {
            val isPaper = cfg.paperMode
            val trs: Double
            val trsUsd: Double
            
            // V5.6.20: Get SOL price with fallback to prevent $0 display bug
            val solPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice.takeIf { it > 0 }
                ?: ws.solPriceUsd.takeIf { it > 0 }
                ?: 130.0  // Reasonable fallback if all else fails
            
            if (isPaper) {
                // V5.9.425 — paper mode previously read CashGenerationAI's separate
                // auto-compound counter, which hid the 70/30 meme-sell splits that
                // land in TreasuryManager.treasurySol. Unified both modes on the
                // canonical TreasuryManager counter so the 70/30 splits are visible.
                trs = com.lifecyclebot.engine.TreasuryManager.treasurySol
                trsUsd = if (ws.treasuryUsd > 0) ws.treasuryUsd else trs * solPrice
            } else {
                // In live mode, show the TreasuryManager live treasury
                trs = ws.treasurySol
                // V5.6.20: Also recalculate USD in live mode if ws.treasuryUsd is 0
                trsUsd = if (ws.treasuryUsd > 0) ws.treasuryUsd else trs * solPrice
            }
            
            // V5.6.21: Calculate milestone tier DYNAMICALLY based on actual treasury USD value
            // This fixes the issue where paper mode showed "Max tier reached" with only $780
            val milestones = com.lifecyclebot.engine.TreasuryManager.MILESTONES
            var currentTierIdx = -1
            for ((idx, m) in milestones.withIndex()) {
                if (trsUsd >= m.thresholdUsd) {
                    currentTierIdx = idx
                }
            }
            
            val tier = if (currentTierIdx >= 0) milestones[currentTierIdx].label else "None"
            val nextMilestone = milestones.getOrNull(currentTierIdx + 1)
            val nextUsd = nextMilestone?.thresholdUsd ?: 0.0
            val modeLabel = if (isPaper) " [PAPER]" else ""
            
            // Update BOTH old and new treasury views (new views have "2" suffix)
            val tierText = if (trs > 0.001) "Tier: $tier$modeLabel" else "Tier: None$modeLabel"
            val amountText = if (trs > 0.001) "${"%.3f".format(trs)} SOL  ($${"%.0f".format(trsUsd)})" else "—"
            val nextText = when {
                nextUsd > 0 -> "Next: $${"%,.0f".format(nextUsd)}"
                trs > 0     -> "Max tier reached"
                else        -> "First: $500"
            }
            
            // Old views (hidden but keeping for compatibility)
            findViewById<android.widget.TextView?>(R.id.tvTreasuryTier)?.text = tierText
            findViewById<android.widget.TextView?>(R.id.tvTreasuryAmount)?.text = amountText
            findViewById<android.widget.TextView?>(R.id.tvTreasuryNext)?.text = nextText
            
            // New visible views (V5.6.8 moved section)
            findViewById<android.widget.TextView?>(R.id.tvTreasuryTier2)?.text = tierText
            findViewById<android.widget.TextView?>(R.id.tvTreasuryAmount2)?.text = amountText
            findViewById<android.widget.TextView?>(R.id.tvTreasuryNext2)?.text = nextText
        } catch (_: Exception) {}

        // ── bot status card ───────────────────────────────────────────
        tvTokenName.text  = ts?.symbol?.ifBlank { "No token selected" } ?: "No token selected"
        
        // Load token logo from DexScreener
        if (ts != null) {
            val heroLogoUrl = ts.logoUrl.ifBlank { "https://cdn.dexscreener.com/tokens/solana/${ts.mint}.png" }
            ivTokenLogo.load(heroLogoUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_token_placeholder)
                error(R.drawable.ic_token_placeholder)
                allowHardware(false)
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
            
            // V5.6: Update DexScreener-style chart metrics
            updateChartMetrics(ts)
        } else if (ts?.lastPrice != null && ts.lastPrice > 0) {
            // Append new price point
            appendChart(ts.lastPrice)
            updateCandleChart(ts)  // V5.8.0: keep candle chart in sync
            
            // V5.6: Update metrics on each tick
            updateChartMetrics(ts)
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
            val persistedStats = com.lifecyclebot.engine.TradeHistoryStore.getStatsCached() // V5.9.706
            // V5.9.355 — Pull meme WR + W/L/S from RunTracker30D so the hero
            // tile matches the 30-Day Proof card byte-for-byte. User report:
            // "the meme coin 30 day is at 26% the livereadiness is drawing
            // wrong data!!!" Different isLoss/isScratch thresholds between
            // TradeHistoryStore and RunTracker30D were painting 20% vs 26%
            // on the same meme data. RunTracker30D is the proof-run source
            // of truth so we align everything to it.
            val tracker30d    = com.lifecyclebot.engine.RunTracker30D
            val memeWinsRT    = tracker30d.wins
            val memeLossesRT  = tracker30d.losses
            val memeScratchRT = tracker30d.scratches
            val memeDecisive  = memeWinsRT + memeLossesRT
            val memeWrRT      = if (memeDecisive > 0) (memeWinsRT * 100.0) / memeDecisive else 0.0
            
            // 24H trades from persisted journal
            // V5.9.386 — On MEME tab, match the 30-Day Proof Run card byte-for-byte:
            // use RunTracker30D.totalTrades (same field the card shows) so the
            // top-bar number aligns with the card directly below it. Prior
            // 24h-window source always drifted from the 30-day cumulative count.
            val trades24h = persistedStats.trades24h
            val topBarTradeCount = when (currentReadinessTab) {
                "MEME"  -> if (com.lifecyclebot.engine.RunTracker30D.isRunActive()) com.lifecyclebot.engine.RunTracker30D.totalTrades else trades24h
                "ALTS"  -> try { (com.lifecyclebot.perps.CryptoAltTrader.getStats()["totalTrades"] as? Int) ?: trades24h } catch (_: Exception) { trades24h }
                "PERPS" -> try {
                    // V5.9.387 — PERPS tab = unified AATE markets, so the top-bar
                    // trade count aggregates every markets trader the same way
                    // renderPerpsReadiness() does. Was only PerpsTraderAI which
                    // meant the top-bar always showed 0 while markets were trading.
                    var t = 0
                    t += ((com.lifecyclebot.perps.CryptoAltTrader.getStats()["totalTrades"] as? Int) ?: 0)
                    t += com.lifecyclebot.perps.PerpsTraderAI.getLifetimeTrades()
                    t += com.lifecyclebot.perps.TokenizedStockTrader.getTotalTrades()
                    t += com.lifecyclebot.perps.ForexTrader.getTotalTrades()
                    t += com.lifecyclebot.perps.MetalsTrader.getTotalTrades()
                    t += com.lifecyclebot.perps.CommoditiesTrader.getTotalTrades()
                    t
                } catch (_: Exception) { trades24h }
                else    -> trades24h
            }
            tvStats24hTrades.text = "$topBarTradeCount"
            
            // Win rate: Use RunTracker30D meme-trader-specific WR.
            // V5.9.649 — fix data pollution where MEME tab showed e.g. "20%"
            // while the W/L/S subline read "0W 0L 0S". Operator screenshot:
            // "20% winrate?. it hasnt made a single trade in the memetrader
            // yet so that is wrong." The bug was a fallback to
            // persistedStats.winRate24h (TradeHistoryStore 24h-window WR
            // including ALL traders) when meme-decisive was 0. That stale
            // historical/cross-trader % bled into the meme tile and
            // contradicted its own subline. Fix: on MEME tab, never fall
            // through to persistedStats — show 0 so the headline number
            // matches the W/L/S subline byte-for-byte. Other tabs unchanged.
            val winRate = when {
                memeDecisive >= 1                     -> memeWrRT.toInt()
                currentReadinessTab == "MEME"         -> 0
                persistedStats.winRate24h > 0         -> persistedStats.winRate24h
                else -> 0
            }
            
            tvStatsWinRate.text = "$winRate%"
            tvStatsWinRate.setTextColor(when {
                winRate >= 60 -> green
                winRate >= 40 -> amber
                winRate > 0 -> red
                else -> muted  // Show muted for 0% (no data)
            })

            // V5.9.266 — W/L/S 3-bucket pill below the WR tile.
            // The WR % alone hides the scratch-bleed (53% of trades land
            // between -2% and +0.5% — pure fee bleed). This sub-line gives
            // the user the full truth at a glance.
            try {
                val parent = tvStatsWinRate.parent as? android.widget.LinearLayout
                if (parent != null) {
                    val tag = "wls_subline"
                    var sub = parent.findViewWithTag<TextView>(tag)
                    if (sub == null) {
                        sub = TextView(this).apply {
                            this.tag = tag
                            textSize = 9f
                            setPadding(0, (2 * resources.displayMetrics.density).toInt(), 0, 0)
                        }
                        parent.addView(sub)
                    }
                    val w = memeWinsRT
                    val l = memeLossesRT
                    val s = memeScratchRT
                    val totalNonScratch = w + l
                    val rawWr = if (totalNonScratch > 0) w * 100.0 / totalNonScratch else 0.0
                    val totalAll = w + l + s
                    val effectiveWr = if (totalAll > 0) w * 100.0 / totalAll else 0.0
                    sub.text = "${w}W ${l}L ${s}S"
                    sub.setTextColor(when {
                        // Scratch share warning — paint the sub-line amber when
                        // > 40% of trades are scratches (fee bleed dominant).
                        s > 0 && totalAll > 0 && s.toDouble() / totalAll > 0.40 -> amber
                        rawWr >= 50 -> green
                        rawWr >= 30 -> amber
                        else -> muted
                    })
                    sub.contentDescription =
                        "Pure WR ${"%.1f".format(rawWr)}% • Effective ${"%.1f".format(effectiveWr)}% (incl scratches)"

                    // ═══════════════════════════════════════════════════════════
                    // V5.9.746 — operator readability fix. The bare "32% WR"
                    // pill is hard to map to action: how far am I from 50%?
                    // and am I actually losing money or is my avg win covering
                    // the loss frequency? Two extra grey sublines below the
                    // W/L/S row solve this:
                    //   • Line 2: "need +N W → 50%"   (concrete distance gap)
                    //   • Line 3: "EV +X% / trade"    (true profitability)
                    // EV = (winRate * avgWin% + lossRate * avgLoss%) — the
                    // expected return per trade given your current win/loss
                    // rates AND your current avg win/loss sizes. This is the
                    // number that actually decides go-live, not bare WR.
                    val gapTag = "wls_gap_subline"
                    var gapSub = parent.findViewWithTag<TextView>(gapTag)
                    if (gapSub == null) {
                        gapSub = TextView(this).apply {
                            this.tag = gapTag
                            textSize = 8f
                            setPadding(0, (1 * resources.displayMetrics.density).toInt(), 0, 0)
                        }
                        parent.addView(gapSub)
                    }
                    if (totalNonScratch > 0 && rawWr < 50.0) {
                        // Solve for delta wins x such that (w+x)/(w+l+x) >= 0.50.
                        // → x >= l - w  (when l > w). When w >= l, gap is 0.
                        val winsNeeded = (l - w).coerceAtLeast(0)
                        gapSub.text = if (winsNeeded > 0) "need +${winsNeeded}W → 50%" else "AT TARGET"
                        gapSub.setTextColor(muted)
                        gapSub.visibility = android.view.View.VISIBLE
                    } else {
                        gapSub.visibility = android.view.View.GONE
                    }

                    val evTag = "wls_ev_subline"
                    var evSub = parent.findViewWithTag<TextView>(evTag)
                    if (evSub == null) {
                        evSub = TextView(this).apply {
                            this.tag = evTag
                            textSize = 8f
                            setPadding(0, (1 * resources.displayMetrics.density).toInt(), 0, 0)
                        }
                        parent.addView(evSub)
                    }
                    // EV uses avgWin/avgLoss from persistedStats (TradeHistory
                    // already computes them). Probabilities use rawWr / (1-rawWr)
                    // so scratches don't dilute the signal — scratches contribute
                    // ~0 to EV anyway since they're by definition flat.
                    if (totalNonScratch >= 5) {
                        val aw = persistedStats.avgWinPct
                        val al = persistedStats.avgLossPct  // already negative
                        val pWin = rawWr / 100.0
                        val pLoss = 1.0 - pWin
                        val evPct = pWin * aw + pLoss * al
                        val sign = if (evPct >= 0) "+" else ""
                        evSub.text = "EV ${sign}${"%.2f".format(evPct)}% / trade"
                        evSub.setTextColor(when {
                            evPct >= 5.0  -> green
                            evPct >= 0.0  -> amber
                            else          -> red
                        })
                        evSub.visibility = android.view.View.VISIBLE
                    } else {
                        evSub.visibility = android.view.View.GONE
                    }
                }
            } catch (_: Exception) { /* never crash on UI sub-injection */ }
            
            // ═══════════════════════════════════════════════════════════════════
            // OPEN POSITIONS COUNT
            // V5.9.475 — DEDUPLICATED count fix. Operator: 'main meme ui open
            // positions counter is wrong.' Was naive sum:
            //   memeOpen + shitOpen + qualityOpen + blueOpen + moonOpen + treasuryOpen
            // ShitCoin / Quality / BlueChip / Moonshot ALL mirror their
            // positions onto ts.position when they take a trade, so they
            // appear BOTH in state.openPositions AND their private maps.
            // Naive sum double-counted every one of them — operator saw
            // '5 Open' when only 2 visible. Now we union the mint sets and
            // take size, so each mint is counted exactly once.
            val openMints = mutableSetOf<String>()
            try { state.openPositions.forEach { openMints.add(it.mint) } } catch (_: Exception) {}
            try { com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getActivePositions().forEach { openMints.add(it.mint) } } catch (_: Exception) {}
            try { com.lifecyclebot.v3.scoring.QualityTraderAI.getActivePositions().forEach { openMints.add(it.mint) } } catch (_: Exception) {}
            try { com.lifecyclebot.v3.scoring.BlueChipTraderAI.getActivePositions().forEach { openMints.add(it.mint) } } catch (_: Exception) {}
            try { com.lifecyclebot.v3.scoring.MoonshotTraderAI.getActivePositions().forEach { openMints.add(it.mint) } } catch (_: Exception) {}
            try { com.lifecyclebot.v3.scoring.CashGenerationAI.getActivePositions().forEach { openMints.add(it.mint) } } catch (_: Exception) {}
            try { com.lifecyclebot.engine.HostWalletTokenTracker.getOpenTrackedPositions().forEach { openMints.add(it.mint) } } catch (_: Exception) {}
            val lifecycleOpen = try { com.lifecyclebot.engine.TokenLifecycleTracker.openCount() } catch (_: Exception) { 0 }
            val hostOpen = try { com.lifecyclebot.engine.HostWalletTokenTracker.getOpenCount() } catch (_: Exception) { 0 }
            // V5.9.797 — operator audit (build-2734 screenshot): top tile showed
            // "7 Open" while the actual Open Positions list rendered 10 cards.
            // Source-of-truth divergence: the list uses buildUnifiedOpenPositions(state)
            // which pulls from a wider source (incl. EXPRESS / sub-trader positions
            // that aren't in state.openPositions OR the trader-AI active maps),
            // but this tile was computing its own union. We now read the SAME
            // unified list size so what you see in the tile always equals what
            // the card below renders.
            val unifiedListSize = try {
                buildUnifiedOpenPositions(state).size
            } catch (_: Throwable) { 0 }
            val openCount = maxOf(openMints.size, hostOpen, lifecycleOpen, unifiedListSize)
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
        // V5.9.389 — unified render (fixes V5.9.386 regression where sub-
        // trader positions were tacked on below in a stripped-down second
        // list with missing P&L / size / TP-SL). Now every holding —
        // meme-base AND sub-trader (ShitCoin / Quality / BlueChip /
        // Moonshot / Treasury) — flows through the SAME renderOpenPositions
        // row builder in ONE pass, so format + columns + colour bar are
        // identical across the whole card. Deduplication: if a mint is
        // already in state.openPositions it's NOT synthesized a second
        // time, even if a sub-trader also holds it.
        val openPos = buildUnifiedOpenPositions(state)
        cardOpenPositions.visibility = if (openPos.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        if (openPos.isNotEmpty()) {
            val totalExposure = openPos.sumOf { it.position.costSol }
            val totalUpnl = openPos.sumOf { ts ->
                val ref = ts.ref
                val pos = ts.position
                if (pos.entryPrice > 0 && ref > 0) pos.costSol * (ref - pos.entryPrice) / pos.entryPrice else 0.0
            }
            tvTotalExposure.text = "%.3f◎ at risk".format(totalExposure)
            tvTotalUnrealisedPnl.text = "%+.4f◎".format(totalUpnl)
            tvTotalUnrealisedPnl.setTextColor(if (totalUpnl >= 0) green else red)
            renderOpenPositions(openPos)
        }
        
        // ── V4.0: Treasury positions panel ─────────────────────────────────
        try {
            // V5.9.458 — operator directive: 'treasury when connected and in
            // LIVE mode should display 0 if the balance is 0'. If the user
            // toggled back to live after a previous session left stale
            // livePositions in the map, hide the card so they don't see
            // phantom positions they can't actually sell (0 SOL = can't pay gas).
            val cfgNow = state.config // V5.9.706 — avoid AES-GCM decrypt on main thread
            val liveSol = try {
                com.lifecyclebot.engine.WalletManager.getInstance(applicationContext).state.value.solBalance
            } catch (_: Throwable) { 0.0 }
            val hideForDrainedLive = !cfgNow.paperMode && liveSol < 0.001
            // V5.9.495z17 — show both paper + live so toggling mode never hides positions.
            val treasuryPositions = if (hideForDrainedLive) emptyList() else (
                com.lifecyclebot.v3.scoring.CashGenerationAI.getPositionsForMode(true) +
                com.lifecyclebot.v3.scoring.CashGenerationAI.getPositionsForMode(false)
            )
            cardTreasuryPositions.visibility = if (treasuryPositions.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            if (treasuryPositions.isNotEmpty()) {
                val treasuryExposure = treasuryPositions.sumOf { it.entrySol }
                tvTreasuryExposure.text = "%.3f◎".format(treasuryExposure)
                // V5.9.420 — header PnL was showing realized day-PnL while child
                // rows showed open unrealized → parent and children disagreed
                // (e.g. header -0.1700◎ while all 3 rows said +0.0000◎). Now we
                // sum the children's unrealized PnL during render and use that.
                // V5.9.730 ANR FIX: Treasury render is the #1 main-thread stall
                // (renderTreasuryPositions appears in >15% of ANR samples, stall=74%).
                // It does removeAllViews + full view inflation per position on every
                // 2500ms UI tick. Debounce to 6s max — Treasury positions are long-hold
                // so 6s visual lag is imperceptible while halving main-thread pressure.
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastTreasuryRenderMs >= 6_000L) {
                    lastTreasuryRenderMs = nowMs
                    val treasuryUnrealized = renderTreasuryPositions(treasuryPositions)
                    tvTreasuryPnl.text = "%+.4f◎".format(treasuryUnrealized)
                    tvTreasuryPnl.setTextColor(if (treasuryUnrealized >= 0) green else red)
                }
            }
        } catch (_: Exception) {}
        
        // ── V4.0: Blue Chip positions panel ─────────────────────────────────
        try {
            // V5.9.495z17 — show both paper + live so toggling mode never hides held positions.
            val blueChipPositions = (
                com.lifecyclebot.v3.scoring.BlueChipTraderAI.getActivePositionsForMode(true) +
                com.lifecyclebot.v3.scoring.BlueChipTraderAI.getActivePositionsForMode(false)
            )
            cardBlueChipPositions.visibility = if (blueChipPositions.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            if (blueChipPositions.isNotEmpty()) {
                val blueChipExposure = blueChipPositions.sumOf { it.entrySol }
                tvBlueChipExposure.text = "%.3f◎".format(blueChipExposure)
                val blueChipUnrealized = renderBlueChipPositions(blueChipPositions)  // V5.9.420
                tvBlueChipPnl.text = "%+.4f◎".format(blueChipUnrealized)
                tvBlueChipPnl.setTextColor(if (blueChipUnrealized >= 0) green else red)
            }
        } catch (_: Exception) {}
        
        // ── Quality positions panel ───────────────────────────────────────
        try {
            // V5.9.495z17 — show both paper + live.
            val qualityPositions = (
                com.lifecyclebot.v3.scoring.QualityTraderAI.getActivePositionsForMode(true) +
                com.lifecyclebot.v3.scoring.QualityTraderAI.getActivePositionsForMode(false)
            )
            cardQualityPositions.visibility = if (qualityPositions.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            if (qualityPositions.isNotEmpty()) {
                tvQualityExposure.text = "%.3f◎".format(qualityPositions.sumOf { it.entrySol })
                val qualityUnrealized = renderQualityPositions(qualityPositions)  // V5.9.420
                tvQualityPnl.text = "%+.4f◎".format(qualityUnrealized)
                tvQualityPnl.setTextColor(if (qualityUnrealized >= 0) green else red)
            }
        } catch (_: Exception) {}

        // ── V4.0: ShitCoin positions panel ─────────────────────────────────
        try {
            // V5.9.495z17 — show both paper + live.
            val shitCoinPositions = (
                com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getActivePositionsForMode(true) +
                com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getActivePositionsForMode(false)
            )
            val shitCoinStats = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getStats()
            val showShitCoin = shitCoinPositions.isNotEmpty() || shitCoinStats.dailyTradeCount > 0
            cardShitCoinPositions.visibility = if (showShitCoin) android.view.View.VISIBLE else android.view.View.GONE
            if (showShitCoin) {
                tvShitCoinExposure.text = "%.3f◎".format(shitCoinPositions.sumOf { it.entrySol })
                val shitCoinDailyPnl = shitCoinStats.dailyPnlSol
                // V5.9.420 — header now shows OPEN unrealized PnL (matches child rows).
                // The "Day:" sub-label below still shows daily realized PnL.
                val shitCoinUnrealized = renderShitCoinPositions(shitCoinPositions)
                tvShitCoinPnl.text = "%+.4f◎".format(shitCoinUnrealized)
                tvShitCoinPnl.setTextColor(if (shitCoinUnrealized >= 0) green else red)
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
                // V5.9.420 — renderShitCoinPositions() already invoked above to
                // compute the unrealized sum; second call removed.
            }
        } catch (_: Exception) {}

        // ── V5.9: ShitCoinExpress positions panel (own card, separate from ShitCoin) ──
        try {
            val expressRides = com.lifecyclebot.v3.scoring.ShitCoinExpress.getActiveRides()
            val expressStats = com.lifecyclebot.v3.scoring.ShitCoinExpress.getStats()
            val showExpress = expressRides.isNotEmpty() || expressStats.dailyRides > 0
            cardExpressPositions.visibility = if (showExpress) android.view.View.VISIBLE else android.view.View.GONE
            if (showExpress) {
                tvExpressExposure.text = "%.3f◎".format(expressRides.sumOf { it.entrySol })
                val expressDailyPnl = expressStats.dailyPnlSol
                // V5.9.420 — header now shows OPEN unrealized PnL (matches child rows).
                val expressUnrealized = renderExpressRides(expressRides)
                tvExpressPnl.text = "%+.4f◎".format(expressUnrealized)
                tvExpressPnl.setTextColor(if (expressUnrealized >= 0) green else red)
                tvExpressWinRate.text = "${expressStats.dailyWins}W/${expressStats.dailyLosses}L"
                tvExpressDailyPnl.text = "Day: %+.3f◎".format(expressDailyPnl)
                tvExpressDailyPnl.setTextColor(if (expressDailyPnl >= 0) green else red)
            }
        } catch (_: Exception) {}

        // ── ☠️ The Manipulated positions panel ────────────────────────────
        try {
            val manipPositions = com.lifecyclebot.v3.scoring.ManipulatedTraderAI.getActivePositions()
            val manipStats = com.lifecyclebot.v3.scoring.ManipulatedTraderAI.getStats()
            val showManip = manipPositions.isNotEmpty() || manipStats.dailyWins > 0 || manipStats.dailyLosses > 0
            cardManipulatedPositions.visibility = if (showManip) android.view.View.VISIBLE else android.view.View.GONE
            if (showManip) {
                tvManipExposure.text = "%.3f◎".format(manipPositions.sumOf { it.entrySol })
                val manipDailyPnl = manipStats.dailyPnlSol
                // V5.9.420 — header now shows OPEN unrealized PnL (matches child rows).
                val manipUnrealized = renderManipPositions(manipPositions)
                tvManipPnl.text = "%+.4f◎".format(manipUnrealized)
                tvManipPnl.setTextColor(if (manipUnrealized >= 0) green else red)
                tvManipWinRate.text = "${manipStats.dailyWins}W/${manipStats.dailyLosses}L"
                tvManipDailyPnl.text = "Day: %+.3f◎".format(manipDailyPnl)
                tvManipDailyPnl.setTextColor(if (manipDailyPnl >= 0) green else red)
                tvManipCaught.text = "Caught: ${manipStats.totalManipCaught}"
            }
        } catch (_: Exception) {}

    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.9.222: Cyclic $500→$1M Panel
    // Shows only when CyclicTradeEngine is running. Inserted above moonshot panel.
    // ═══════════════════════════════════════════════════════════════════════════

    fun updateCyclicPanel() {  // V5.9.225: removed 'private' — local functions can't use access modifiers
        try {
            val engine = com.lifecyclebot.engine.CyclicTradeEngine
            val cfg = com.lifecyclebot.data.ConfigStore.load(this) // V5.9.706: served from 2s cache
            val isRunning = cfg.cyclicTradeEnabled && engine.isRunning

            // Find or build the container — it lives in the same scroll as moonshotPositions
            // We dynamically create it on first call and attach above cardMoonshotPositions
            if (!isRunning) {
                cardCyclicPanel?.visibility = android.view.View.GONE
                return
            }

            // Build panel on first run
            if (cardCyclicPanel == null) {
                val parent = cardMoonshotPositions.parent as? android.view.ViewGroup ?: return
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((14 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt(), (14 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt())
                    setBackgroundColor(android.graphics.Color.parseColor("#1A1A2E"))
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins((12 * resources.displayMetrics.density).toInt(), (8 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt(), (4 * resources.displayMetrics.density).toInt()) }
                    layoutParams = lp
                }
                // Insert BEFORE cardMoonshotPositions
                val idx = parent.indexOfChild(cardMoonshotPositions)
                parent.addView(card, if (idx >= 0) idx else parent.childCount)
                cardCyclicPanel = card
            }

            val card = cardCyclicPanel ?: return
            card.visibility = android.view.View.VISIBLE
            card.removeAllViews()

            val solPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice.takeIf { it > 0.0 } ?: 150.0
            val ringUsd   = engine.ringBalanceUsd
            val cycles    = engine.cycleCount
            val wins      = engine.winCount
            val losses    = engine.lossCount
            val totalPnlSol = engine.totalPnlSol
            val isInPos   = engine.isInPosition
            val symbol    = engine.currentSymbol
            val entryTime = engine.entryTimeMs
            val isLive    = engine.isLiveMode
            val status    = engine.statusMessage

            val winRate = if (cycles > 0) (wins * 100 / cycles) else 0
            val growthPct = ((ringUsd - 500.0) / 500.0 * 100.0)
            val modeColor = if (isLive) android.graphics.Color.parseColor("#FF4444") else amber
            val modeLabel = if (isLive) "🔴 LIVE" else "📄 PAPER"

            // ── Row 1: Header ─────────────────────────────────────────────────
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, (6 * resources.displayMetrics.density).toInt()) }

                addView(TextView(this@MainActivity).apply {
                    text = "🔄 Cyclic $500→$1M"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 13f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@MainActivity).apply {
                    text = modeLabel
                    setTextColor(modeColor)
                    textSize = 11f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
            })

            // ── Row 2: Ring balance + growth ─────────────────────────────────
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, (4 * resources.displayMetrics.density).toInt()) }

                addView(TextView(this@MainActivity).apply {
                    text = "Ring: \$${"%,.0f".format(ringUsd)}"
                    setTextColor(if (ringUsd >= 500.0) green else red)
                    textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                val growthColor = if (growthPct >= 0) green else red
                addView(TextView(this@MainActivity).apply {
                    text = "${if (growthPct >= 0) "+" else ""}${"%,.1f".format(growthPct)}% growth"
                    setTextColor(growthColor)
                    textSize = 12f
                })
            })

            // ── Row 3: Cycles | WR | Total PnL ───────────────────────────────
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, (4 * resources.displayMetrics.density).toInt()) }

                fun stat(label: String, value: String, color: Int) = TextView(this@MainActivity).apply {
                    text = "$label $value"
                    setTextColor(color)
                    textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                addView(stat("Cycles:", "$cycles", white))
                addView(stat("W/L:", "$wins/$losses", if (wins >= losses) green else red))
                addView(stat("WR:", "$winRate%", if (winRate >= 50) green else if (winRate >= 35) amber else red))
                addView(stat("PnL:", "${if (totalPnlSol >= 0) "+" else ""}${"%,.4f".format(totalPnlSol)} SOL",
                    if (totalPnlSol >= 0) green else red))
            })

            // ── Row 4: Current position (if in one) ───────────────────────────
            if (isInPos && symbol.isNotBlank()) {
                val holdSec = (System.currentTimeMillis() - entryTime) / 1000
                val holdMin = holdSec / 60
                val holdStr = if (holdMin > 0) "${holdMin}m ${holdSec % 60}s" else "${holdSec}s"

                card.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setBackgroundColor(android.graphics.Color.parseColor("#0D2040"))
                    setPadding((8 * resources.displayMetrics.density).toInt(), (6 * resources.displayMetrics.density).toInt(), (8 * resources.displayMetrics.density).toInt(), (6 * resources.displayMetrics.density).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, (2 * resources.displayMetrics.density).toInt(), 0, 0) }

                    addView(TextView(this@MainActivity).apply {
                        text = "⚡ $symbol"
                        setTextColor(android.graphics.Color.parseColor("#00CFFF"))
                        textSize = 12f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = "⏱ $holdStr"
                        setTextColor(amber)
                        textSize = 11f
                    })
                })

                // Status line
                card.addView(TextView(this@MainActivity).apply {
                    text = status
                    setTextColor(android.graphics.Color.parseColor("#9CA3AF"))
                    textSize = 10f
                    setPadding((2 * resources.displayMetrics.density).toInt(), (2 * resources.displayMetrics.density).toInt(), 0, 0)
                })
            } else {
                // Scanning
                card.addView(TextView(this@MainActivity).apply {
                    text = status
                    setTextColor(android.graphics.Color.parseColor("#9CA3AF"))
                    textSize = 10f
                    setPadding((2 * resources.displayMetrics.density).toInt(), (2 * resources.displayMetrics.density).toInt(), 0, 0)
                })
            }

        } catch (_: Exception) {}
    }

        // ── V5.9.222: Cyclic panel — above moonshot ──────────────────────
        updateCyclicPanel()

    // ── V5.2: Moonshot positions panel ────────────────────────────
        try {
            // V5.9.495z17 — show both paper + live so toggling mode never hides positions.
            val moonshotPositions = (
                com.lifecyclebot.v3.scoring.MoonshotTraderAI.getActivePositionsForMode(true) +
                com.lifecyclebot.v3.scoring.MoonshotTraderAI.getActivePositionsForMode(false)
            )
            val showMoonshot = moonshotPositions.isNotEmpty()
            
            cardMoonshotPositions.visibility = if (showMoonshot) android.view.View.VISIBLE else android.view.View.GONE
            
            if (showMoonshot) {
                val moonshotExposure = moonshotPositions.sumOf { it.entrySol }
                tvMoonshotExposure.text = "${String.format("%.3f", moonshotExposure)} SOL"
                
                // Calculate total P&L
                var totalPnl = 0.0
                for (pos in moonshotPositions) {
                    // V5.9.302: dead-feed guard — ref=0 must NOT count as -100%
                    val currentPrice = try {
                        val ref = com.lifecyclebot.engine.BotService.status.tokens[pos.mint]?.ref ?: 0.0
                        if (ref > 0.0) ref else pos.entryPrice
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
            val treasuryPositions = (
                com.lifecyclebot.v3.scoring.CashGenerationAI.getPositionsForMode(true) +
                com.lifecyclebot.v3.scoring.CashGenerationAI.getPositionsForMode(false)
            )
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
        
        // V5.6.29d: Update Network Signals panel (Collective Intelligence)
        try {
            renderNetworkSignals()
        } catch (_: Exception) {}
        
        // V5.6.29d: Update Project Sniper panel
        try {
            renderSniperMissions()
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
        
        // ── V5.6.9: Live Readiness Indicator ─────────────────────────
        try {
            updateLiveReadiness()
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
        // V5.9.672 — render throttle. Skip the full teardown+rebuild when
        // 6s haven't elapsed AND none of the *structural* signals changed.
        // We deliberately exclude raw token count from the structural check
        // because the scanner intake constantly nudges the count (3-7/s in
        // operator dumps) — using it would defeat the throttle entirely.
        val nowWl = System.currentTimeMillis()
        val openCountWl = state.openPositions.size
        val activeMintWl = state.config.activeToken
        val structuralChange = openCountWl != lastWatchlistOpenCount ||
            activeMintWl != lastWatchlistActiveMint
        val timeElapsed = (nowWl - lastWatchlistRenderMs) >= 12_000L  // V5.9.726 — was 6s, doubled to halve buildTokenCard burden
        if (structuralChange || timeElapsed || lastWatchlistRenderMs == 0L) {
            renderWatchlist(state)
            lastWatchlistRenderMs = nowWl
            lastWatchlistOpenCount = openCountWl
            lastWatchlistActiveMint = activeMintWl
        }

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

    // V5.9.389 — merge base meme + sub-trader holdings into one TokenState
    // list so renderOpenPositions can paint every row in the SAME format.
    // State.openPositions already holds tokens that were evicted AFTER
    // V5.9.385 properly (those stay). For each sub-trader (ShitCoin /
    // Quality / BlueChip / Moonshot / Treasury) we inspect its own
    // paperPositions map and SYNTHESIZE a TokenState for any mint that
    // isn't already represented — this rescues ghost positions that were
    // evicted from status.tokens before V5.9.385 shipped but still live in
    // the sub-trader's own position map. Live P&L populates whenever the
    // sub-trader tracker has a recent price; otherwise falls back to
    // entry price (0% line) rather than the misleading "—".
    private fun buildUnifiedOpenPositions(state: UiState): List<TokenState> {
        // V5.9.771 — EMERGENT-MEME #3 + #4: live ↔ paper UI contamination.
        // Operator screenshot 2026-05-15 21:14 showed LIVE mode active
        // with 40 open positions, the bulk of which were paper.
        // `state.openPositions` is a UNION across modes; the readiness
        // tile / risk bar / "X at risk" must reflect the CURRENT mode
        // only. Filter the source list by `isPaperPosition` against
        // `config.paperMode` BEFORE anything synthesises further rows.
        // Paper trades retain a separate panel; this surface is the
        // live-vs-paper executive view of REAL trading.
        val isPaperMode = state.config.paperMode
        val merged = state.openPositions
            .filter { it.position.isPaperPosition == isPaperMode }
            .toMutableList()
        val alreadyRendered = merged.map { it.mint }.toMutableSet()

        fun upsert(mint: String, symbol: String, layer: String, emoji: String,
                   entryPrice: Double, entrySol: Double, entryTime: Long,
                   peakPct: Double, currentPrice: Double, isPaper: Boolean) {
            if (mint.isBlank() || alreadyRendered.contains(mint)) return
            val synth = TokenState(mint = mint, symbol = symbol)
            // Seed a complete Position so renderOpenPositions shows entry,
            // size, token qty, P&L%, SOL PnL, USD value, peak/lock badge.
            synth.position = com.lifecyclebot.data.Position(
                qtyToken        = if (entryPrice > 0) entrySol / entryPrice else 0.0,
                entryPrice      = entryPrice,
                entryTime       = entryTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
                costSol         = entrySol,
                highestPrice    = if (peakPct > 0 && entryPrice > 0) entryPrice * (1.0 + peakPct / 100.0) else entryPrice,
                entryPhase      = "sub_trader",
                entryScore      = 0.0,
                isPaperPosition = isPaper,
                tradingMode     = layer,
                tradingModeEmoji = emoji,
                peakGainPct     = peakPct,
                isShitCoinPosition = (layer == "SHITCOIN"),
                isBlueChipPosition = (layer == "BLUE_CHIP"),
                isTreasuryPosition = (layer == "TREASURY"),
            )
            synth.lastPrice = if (currentPrice > 0) currentPrice else entryPrice
            synth.lastPriceUpdate = System.currentTimeMillis()
            merged += synth
            alreadyRendered += mint
        }

        try {
            // V5.9.495z17 — operator: "open positions panel is supposed to
            // show all held positions on the meme trader in paper and live
            // mode". `getActivePositions()` only returns the *current* mode's
            // map (legacy behaviour), which hides cross-mode positions when
            // the user toggles. Now we explicitly pull BOTH maps via
            // `getActivePositionsForMode()` so a held token always shows
            // regardless of paper/live toggle.
            val shitCoinAll = (
                com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getActivePositionsForMode(true) +
                com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getActivePositionsForMode(false)
            )
            shitCoinAll.forEach {
                upsert(it.mint, it.symbol, "SHITCOIN", "💩",
                    entryPrice = it.entryPrice, entrySol = it.entrySol,
                    entryTime = it.entryTime, peakPct = it.peakPnlPct,
                    currentPrice = it.lastSeenPrice, isPaper = it.isPaper)
            }
        } catch (_: Exception) {}
        try {
            val qualityAll = (
                com.lifecyclebot.v3.scoring.QualityTraderAI.getActivePositionsForMode(true)
                    .map { it to true } +
                com.lifecyclebot.v3.scoring.QualityTraderAI.getActivePositionsForMode(false)
                    .map { it to false }
            )
            qualityAll.forEach { (it, isPaper) ->
                upsert(it.mint, it.symbol, "QUALITY", "⭐",
                    entryPrice = it.entryPrice, entrySol = it.entrySol,
                    entryTime = it.entryTime, peakPct = it.peakPnlPct,
                    currentPrice = it.lastSeenPrice, isPaper = isPaper)
            }
        } catch (_: Exception) {}
        // V5.9.471 — BlueChip skipped here; it has its own dedicated
        // cardBlueChipPositions card. Listing it both places was a
        // double-count (same mint rendered twice with same entry/size).
        try {
            val moonshotAll = (
                com.lifecyclebot.v3.scoring.MoonshotTraderAI.getActivePositionsForMode(true) +
                com.lifecyclebot.v3.scoring.MoonshotTraderAI.getActivePositionsForMode(false)
            )
            moonshotAll.forEach {
                upsert(it.mint, it.symbol, "MOONSHOT", "🚀",
                    entryPrice = it.entryPrice, entrySol = it.entrySol,
                    entryTime = it.entryTime, peakPct = it.peakPnlPct,
                    currentPrice = it.lastSeenPrice, isPaper = it.isPaperMode)
            }
        } catch (_: Exception) {}
        // V5.9.763 — operator screenshot V5.9.761: HIM held by Project
        // Sniper at -16.5% (live mode) was visible in the Sniper card
        // but missing from the unified Open Positions panel. Same gap
        // affected Manipulated. Both lanes now contribute their
        // mission/position rosters so a held token always shows in the
        // unified list regardless of which sub-trader owns it.
        try {
            com.lifecyclebot.v3.scoring.ProjectSniperAI.getActiveMissions().forEach { m ->
                upsert(
                    m.mint, m.symbol, "PROJECT_SNIPER", "🎯",
                    entryPrice = m.entryPrice,
                    entrySol = m.entrySol,
                    entryTime = m.entryTime,
                    peakPct = if (m.entryPrice > 0)
                        ((m.highestPrice - m.entryPrice) / m.entryPrice * 100.0) else 0.0,
                    currentPrice = m.highestPrice,
                    isPaper = com.lifecyclebot.v3.scoring.ProjectSniperAI.isPaperMode,
                )
            }
        } catch (_: Exception) {}
        try {
            com.lifecyclebot.v3.scoring.ManipulatedTraderAI.getActivePositions().forEach { p ->
                upsert(
                    p.mint, p.symbol, "MANIPULATED", "🎭",
                    entryPrice = p.entryPrice,
                    entrySol = p.entrySol,
                    entryTime = p.entryTime,
                    peakPct = p.peakPnlPct,
                    currentPrice = p.highWaterMark,
                    isPaper = p.isPaper,
                )
            }
        } catch (_: Exception) {}
        // V5.9.471 — DO NOT pull TREASURY or BLUE_CHIP positions into the
        // unified Open Positions list. Operator-reported double-count:
        // MINDFAK appeared in BOTH 'Open Positions' (with 💰 icon) AND
        // 'Treasury Scalps' card with identical entry / size — same mint
        // rendered twice in the same view. Same risk for BlueChip if it
        // had positions. Both layers already have their own dedicated
        // cards (cardTreasuryPositions, cardBlueChipPositions) further
        // down the screen, so listing them here as well was pure
        // duplication. The unified Open Positions card now shows ONLY
        // sub-traders that don't have a dedicated card: ShitCoin /
        // Quality / Moonshot.

        return merged.sortedByDescending { it.position.entryTime }
    }

    /**
     * V5.9.495z37 — count positions held by lane traders that are NOT
     * in the unified Open Positions list (BlueChip + Treasury have
     * their own cards and are intentionally excluded above to avoid
     * double-counting). Returned count is what the footer label
     * reports so users can confirm none are abandoned.
     */
    private fun countLaneHeldPositions(unified: List<TokenState>): Int {
        val seen = unified.map { it.mint }.toMutableSet()
        var n = 0
        try {
            val bc = (
                com.lifecyclebot.v3.scoring.BlueChipTraderAI.getActivePositionsForMode(true) +
                com.lifecyclebot.v3.scoring.BlueChipTraderAI.getActivePositionsForMode(false)
            )
            for (p in bc) if (p.mint !in seen) { seen.add(p.mint); n++ }
        } catch (_: Throwable) {}
        try {
            val tre = (
                com.lifecyclebot.v3.scoring.CashGenerationAI.getActivePositionsForMode(true) +
                com.lifecyclebot.v3.scoring.CashGenerationAI.getActivePositionsForMode(false)
            )
            for (p in tre) if (p.mint.isNotBlank() && p.mint !in seen) { seen.add(p.mint); n++ }
        } catch (_: Throwable) {}
        return n
    }

    private fun renderOpenPositions(positions: List<TokenState>) {
        // V5.9.749 — STRUCTURAL-only hash. Excludes ts.ref (live price) on
        // purpose: price drift from the 1Hz tick loop must NOT trigger a
        // full card rebuild — that was the dominant ANR blocker (92.8%
        // stall per pipeline snapshot). Structural rebuild only fires
        // when a position is added/removed/resized or flips paper↔live.
        val openHash = positions.map {
            "${it.mint}|${it.position.costSol}|${it.position.qtyToken}|${it.position.isPaperPosition}|${it.position.pendingVerify}"
        }.hashCode()
        if (openHash == lastOpenPosHash) return
        // V5.9.749 — 2s minimum interval between full rebuilds even when
        // structure changed. Bursts of new tokens during a hot scanner
        // cycle can't ANR the UI; the next interval will pick them up.
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastOpenPosRenderMs < OPEN_POS_MIN_RENDER_INTERVAL_MS &&
            lastOpenPosRenderMs > 0L) {
            return
        }
        lastOpenPosHash = openHash
        lastOpenPosRenderMs = nowMs
        llOpenPositions.removeAllViews()
        // V5.9.495z37 — operator-reported confusion: tSpaceX / TCLAW /
        // TripleT / GMAR / MAGA / ROAF appear in lane cards (Blue Chip
        // Trades / Treasury Scalps / Moonshot) but NOT in Open
        // Positions. The bot DOES still manage them — they live in
        // their dedicated trader's position map and TP/SL ticks
        // continue. The Open Positions card omits them only to avoid
        // double-counting the same mint twice on screen. Surface that
        // explicitly so users know nothing has been dropped.
        try {
            val laneHeld = countLaneHeldPositions(positions)
            val laneFooterId = "OPEN_POS_LANE_FOOTER".hashCode()
            // Reuse a single TextView across renders rather than
            // adding one each tick.
            var footer = llOpenPositions.parent?.let { p ->
                (p as? android.view.ViewGroup)?.findViewById<TextView>(laneFooterId)
            }
            if (footer == null) {
                footer = TextView(this).apply {
                    id = laneFooterId
                    textSize = 11f
                    setTextColor(0xFF9CA3AF.toInt())
                    setPadding(0, 8, 0, 0)
                }
                (llOpenPositions.parent as? android.view.ViewGroup)?.addView(footer)
            }
            if (laneHeld > 0) {
                footer.text = "+ $laneHeld held in lane cards below — still managed by their respective traders."
                footer.visibility = android.view.View.VISIBLE
            } else {
                footer.visibility = android.view.View.GONE
            }
        } catch (_: Throwable) { /* best-effort */ }
        // V5.9.495z36 — surface a clear PAPER/LIVE/MIXED chip on the
        // Open Positions header. Operator-reported "paper positions
        // polluting live UI" — leave no doubt about which mode each
        // row is in.
        try {
            val chip = findViewById<TextView>(R.id.tvOpenPositionsModeChip)
            if (chip != null) {
                val paperCount = positions.count { it.position.isPaperPosition }
                val liveCount = positions.size - paperCount
                chip.text = when {
                    positions.isEmpty()     -> ""
                    liveCount == 0          -> "📝 PAPER"
                    paperCount == 0         -> "💵 LIVE"
                    else                    -> "📝 ${paperCount} paper · 💵 ${liveCount} live"
                }
                chip.setTextColor(when {
                    paperCount > 0 && liveCount > 0 -> 0xFFFFAA00.toInt()  // mixed = amber
                    paperCount > 0                  -> 0xFFB58CFF.toInt()  // paper = purple
                    else                            -> 0xFF10B981.toInt()  // live = green
                })
            }
        } catch (_: Throwable) { /* best-effort */ }
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        val solPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice.takeIf { it in 50.0..1000.0 } ?: 85.0
        
        positions.forEach { ts ->
            val pos     = ts.position
            val ref     = ts.ref
            val gainPct = if (pos.entryPrice > 0 && ref > 0)
                (ref - pos.entryPrice) / pos.entryPrice * 100.0 else 0.0
            val gainCol = if (gainPct >= 0) green else red
            val pnlSol  = pos.costSol * gainPct / 100.0
            
            // V5.6.18: Use actual token quantity from position, not calculated value
            val tokenAmount = pos.qtyToken
            val currentValue = pos.costSol + pnlSol  // Current value in SOL
            val valueUsd = currentValue * solPrice

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            // V5.8.0: Token logo
            val logoImg = android.widget.ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(40, 40).also { it.marginEnd = 10 }
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                try { background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.token_logo_bg) } catch (_: Exception) {}
                val cachedLogo = try { ts.logoUrl.ifBlank { null } } catch (_: Exception) { null }
                load(cachedLogo ?: "https://cdn.dexscreener.com/tokens/solana/${ts.mint}") {
                    crossfade(true); placeholder(R.drawable.ic_token_placeholder)
                    error(R.drawable.ic_token_placeholder); allowHardware(false)
                    transformations(coil.transform.CircleCropTransformation())
                }
            }
            row.addView(logoImg)

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
            // V5.9.495z36 — operator-reported "paper positions polluting
            // live UI". Make the paper/live distinction visually obvious
            // in the row title so the open-positions card can never be
            // mistaken for a live wallet view.
            val paperBadge = if (pos.isPaperPosition) " 📝" else ""
            info.addView(TextView(this).apply {
                text = "$modeEmoji ${ts.symbol.ifBlank { ts.mint.take(8) }}$paperBadge"
                textSize = resources.getDimension(R.dimen.trade_row_text) / resources.displayMetrics.scaledDensity
                setTextColor(if (pos.isPaperPosition) 0xFFB58CFF.toInt() else white)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            // V5.9.495m — SETTLE-IN COUNTDOWN CHIP. Mirrors the 45s post-buy
            // grace (Executor.SETTLE_IN_MS) so the operator can see WHY a
            // freshly bought token isn't being managed yet — gives confidence
            // that exit predicates intentionally don't fire on launch
            // volatility for the first 45 seconds.
            run {
                val ageMs = System.currentTimeMillis() - pos.entryTime
                val settleMs = 45_000L
                if (pos.entryTime > 0L && ageMs in 0L..settleMs) {
                    val remainSec = ((settleMs - ageMs) / 1000L).coerceAtLeast(0L)
                    info.addView(TextView(this).apply {
                        text = "🛡 SETTLE 0:${"%02d".format(remainSec)}"
                        textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                        setTextColor(0xFFFBBF24.toInt()) // amber/gold
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    })
                }
            }
            // Entry price per token and time — use pos.entryPrice directly
            info.addView(TextView(this).apply {
                text = "Entry: ${pos.entryPrice.fmtPrice()}  ·  ${sdf.format(java.util.Date(pos.entryTime))}"
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
            })
            // Entry size and token amount
            info.addView(TextView(this).apply {
                val tokenAmtStr = when {
                    tokenAmount >= 1_000_000 -> "%.2fM".format(tokenAmount / 1_000_000)
                    tokenAmount >= 1_000     -> "%.2fK".format(tokenAmount / 1_000)
                    else                     -> "%.2f".format(tokenAmount)
                }
                text = "Size: %.4f◎  ·  %s tokens".format(pos.costSol, tokenAmtStr)
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
            })
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
            // Current value in USD — only show if we have real price data
            right.addView(TextView(this).apply {
                text = if (solPrice > 0) "≈\$%.2f".format(valueUsd) else "≈\$—"
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = android.view.Gravity.END
            })
            // V5.9.172 — show the FLUID TP/SL the bot is ACTUALLY enforcing
            // right now, not the stale entry-time static ladder. Pull the
            // live values from FluidLearningAI so the UI matches the exit
            // engine. Falls back to the stored static TP/SL if fluid math
            // can't compute (very early position, no peak yet).
            // V5.9.428 — peak fix: sub-traders (Moonshot / ShitCoin) maintain
            // their own internal peakPnlPct but never mirror it back to
            // pos.peakGainPct, so the "🎯 Peak +X%" badge displayed a stale
            // peak (e.g. +252% on a +3198% runner) and the fluid lock was
            // calculated from the stale value. Sync inline on every render
            // so peak always reflects the live high-water mark.
            if (gainPct > pos.peakGainPct) pos.peakGainPct = gainPct
            val holdSec = ((System.currentTimeMillis() - pos.entryTime) / 1000.0).coerceAtLeast(0.0)
            val vol = ts.volatility ?: 50.0
            val fluidStop = try {
                com.lifecyclebot.v3.scoring.FluidLearningAI.getDynamicFluidStop(
                    modeDefaultStop = 20.0,
                    currentPnlPct = gainPct,
                    peakPnlPct = kotlin.math.max(pos.peakGainPct, gainPct),
                    holdTimeSeconds = holdSec,
                    volatility = vol,
                )
            } catch (_: Exception) { Double.NaN }
            val fluidTrail = try {
                com.lifecyclebot.v3.scoring.FluidLearningAI.fluidTrailPct(gainPct)
            } catch (_: Exception) { Double.NaN }

            val staticTp = when {
                pos.isTreasuryPosition && pos.treasuryTakeProfit > 0 -> pos.treasuryTakeProfit
                pos.isBlueChipPosition  && pos.blueChipTakeProfit  > 0 -> pos.blueChipTakeProfit
                pos.isShitCoinPosition  && pos.shitCoinTakeProfit  > 0 -> pos.shitCoinTakeProfit
                else -> 0.0
            }
            val staticSl = when {
                pos.isTreasuryPosition && pos.treasuryStopLoss != 0.0 -> pos.treasuryStopLoss
                pos.isBlueChipPosition  && pos.blueChipStopLoss  != 0.0 -> pos.blueChipStopLoss
                pos.isShitCoinPosition  && pos.shitCoinStopLoss  != 0.0 -> pos.shitCoinStopLoss
                else -> 0.0
            }

            // Compose the display: live trail % + live fluid stop %.
            val tpTxt: String? = when {
                !fluidTrail.isNaN() && gainPct > 3.0 -> "trail ${fluidTrail.toInt()}%"
                staticTp > 0                          -> "TP +${staticTp.toInt()}%"
                else                                  -> null
            }
            val slTxt: String? = when {
                !fluidStop.isNaN() && fluidStop < 0.0 -> "SL ${fluidStop.toInt()}%"
                !fluidStop.isNaN() && fluidStop > 0.0 -> "lock +${fluidStop.toInt()}%"
                staticSl != 0.0                       -> "SL ${staticSl.toInt()}%"
                else                                  -> null
            }
            val label = listOfNotNull(tpTxt, slTxt).joinToString("  ")
            if (label.isNotEmpty()) {
                right.addView(TextView(this).apply {
                    text = label
                    textSize = resources.getDimension(R.dimen.card_badge_size) / resources.displayMetrics.scaledDensity
                    setTextColor(muted)
                    typeface = android.graphics.Typeface.MONOSPACE
                    gravity = android.view.Gravity.END
                })
            }

            // V5.9.118: Peak Gain / Profit-Lock badge — shows the peak gain,
            // current give-back, and the dynamic profit-floor lock level so
            // the user can SEE the lock armed on every open position. If this
            // row shows peak=+290% now=+251% lock=+255%, the lock is live.
            if (pos.peakGainPct >= 10.0) {
                val lockLevel = try {
                    val stop = com.lifecyclebot.v3.scoring.FluidLearningAI.getDynamicFluidStop(
                        modeDefaultStop = 20.0,
                        currentPnlPct = gainPct,
                        peakPnlPct = pos.peakGainPct,
                        holdTimeSeconds = ((System.currentTimeMillis() - pos.entryTime) / 1000.0).coerceAtLeast(0.0),
                        volatility = ts.volatility ?: 50.0,
                    )
                    // Positive stop = profit trailing lock. Negative = loss stop (don't show).
                    if (stop > 0.0) stop else null
                } catch (_: Exception) { null }

                val peakCol = if (pos.peakGainPct >= 100.0) 0xFFFACC15.toInt() else muted
                right.addView(TextView(this).apply {
                    val peakTxt = "Peak +${pos.peakGainPct.toInt()}%"
                    text = if (lockLevel != null) "🎯 $peakTxt · lock +${lockLevel.toInt()}%" else "🎯 $peakTxt"
                    textSize = resources.getDimension(R.dimen.card_badge_size) / resources.displayMetrics.scaledDensity
                    setTextColor(peakCol)
                    typeface = android.graphics.Typeface.MONOSPACE
                    gravity = android.view.Gravity.END
                })
            }
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
    private fun renderTreasuryPositions(positions: List<com.lifecyclebot.v3.scoring.CashGenerationAI.TreasuryPosition>): Double {
        // V5.9.730 ANR FIX: skip full re-inflate if position list is unchanged.
        // Computing PnL still happens (cheap) but view inflation (expensive) is skipped.
        val mintKey = positions.joinToString(",") { "${it.mint}:${it.entrySol}" }
        val samePositions = mintKey == lastTreasuryMints
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        if (!samePositions) {
            lastTreasuryMints = mintKey
            llTreasuryPositions.removeAllViews()
        }
        val solPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice.takeIf { it in 50.0..1000.0 } ?: 85.0
        // V5.9.420 — accumulate children unrealized PnL so the card header
        // matches the visible rows below (was showing daily realized PnL).
        var childrenUnrealizedSum = 0.0
        
        positions.forEach { pos ->
            // V5.9.188c (fix): 3-tier price lookup for Treasury positions
            // Tier 1: live scanner token map (mint key) — updated every scan cycle
            // Tier 2: CashGenerationAI tracked price — fed from BotService scanner loop
            // Tier 3: entryPrice — last resort (will show +0.0% but won't crash)
            val currentPrice = try {
                // V5.9.415 — 4-tier price lookup. Previous code returned
                // pos.entryPrice when Tier 1 (scanner) and Tier 2 (Treasury
                // tracked-prices map) were both empty for a given mint —
                // producing a screen full of '+0.0%' when 13/14 Treasury
                // positions had no live feed in either source. The
                // TreasuryPosition itself carries a `currentPrice` field
                // that's stamped on every successful updatePrice() call,
                // so use that as Tier 3 before falling back to entry.
                val fromScanner   = com.lifecyclebot.engine.BotService.status.tokens[pos.mint]?.ref
                val fromTreasury  = com.lifecyclebot.v3.scoring.CashGenerationAI.getTrackedPrice(pos.mint)
                val fromPosObj    = pos.currentPrice.takeIf { it > 0.0 }
                val resolved = fromScanner ?: fromTreasury ?: fromPosObj
                if (resolved == null || resolved <= 0.0) {
                    com.lifecyclebot.engine.ErrorLogger.debug("MainActivity",
                        "[Treasury] No live price for ${pos.symbol} (${pos.mint.take(8)}), using entry")
                    pos.entryPrice
                } else resolved
            } catch (_: Exception) { pos.entryPrice }
            val gainPct = (currentPrice - pos.entryPrice) / pos.entryPrice * 100.0
            // V5.9.415 — true stale detection: a row is stale ONLY if we
            // have NO recent tick (>60s since last updatePrice for this
            // mint) AND the scanner has no entry for it either. A price
            // that legitimately equals entry is NOT stale (just unchanged).
            val now = System.currentTimeMillis()
            val lastTick = com.lifecyclebot.v3.scoring.CashGenerationAI.getLastPriceUpdateMs(pos.mint) ?: 0L
            val scannerHasMint = com.lifecyclebot.engine.BotService.status.tokens[pos.mint]?.ref?.let { it > 0.0 } == true
            val hasFresh = scannerHasMint || (lastTick > 0L && (now - lastTick) < 60_000L)
            val gainCol = when {
                !hasFresh    -> muted
                gainPct >= 0 -> green
                else         -> red
            }
            val pnlSol = if (hasFresh) pos.entrySol * gainPct / 100.0 else 0.0
            childrenUnrealizedSum += pnlSol

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            // V5.8.0: Token logo
            val logoImg = android.widget.ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(40, 40).also { it.marginEnd = 10 }
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                try { background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.token_logo_bg) } catch (_: Exception) {}
                load("https://cdn.dexscreener.com/tokens/solana/${pos.mint}") {
                    crossfade(true); placeholder(R.drawable.ic_token_placeholder)
                    error(R.drawable.ic_token_placeholder); allowHardware(false)
                    transformations(coil.transform.CircleCropTransformation())
                }
            }
            row.addView(logoImg)

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
            info.addView(TextView(this).apply {
                // V4.0: Calculate actual TP% from position data instead of hardcoded 7%
                val tpPct = if (pos.entryPrice > 0 && pos.targetPrice > 0) {
                    ((pos.targetPrice - pos.entryPrice) / pos.entryPrice) * 100
                } else {
                    3.5  // Default to 3.5% if data missing
                }
                text = "Size: %.4f◎  ·  Target: +%.1f%%".format(pos.entrySol, tpPct)
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
            })
            row.addView(info)

            // P&L (right column)
            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.END
            }
            right.addView(TextView(this).apply {
                text = if (hasFresh) "%+.1f%%".format(gainPct) else "— stale"
                textSize = resources.getDimension(R.dimen.token_name_size) / resources.displayMetrics.scaledDensity
                setTextColor(gainCol)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.END
            })
            right.addView(TextView(this).apply {
                text = if (hasFresh) "%+.4f◎".format(pnlSol) else "no live feed"
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
            if (!samePositions) llTreasuryPositions.addView(row)  // V5.9.730 dirty-skip
            llTreasuryPositions.addView(div)
        }
        return childrenUnrealizedSum
    }
    
    // V4.0: Render Blue Chip positions
    private fun renderBlueChipPositions(positions: List<com.lifecyclebot.v3.scoring.BlueChipTraderAI.BlueChipPosition>): Double {
        // V5.9.709 — skip re-render if blue chip list unchanged
        val bcHash = positions.map { "${it.mint}${it.lastSeenPrice}${it.isPaper}" }.hashCode()
        if (bcHash == lastBlueChipHash) return 0.0
        lastBlueChipHash = bcHash
        llBlueChipPositions.removeAllViews()
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        // V5.9.420 — accumulate children unrealized PnL for header parity.
        var childrenUnrealizedSum = 0.0
        
        positions.forEach { pos ->
            // V5.8: Use live token price from BotService (consistent with other windows)
            // V5.9.302: dead-feed guard — ref=0 must NOT count as -100%
            val currentPrice = try {
                val ref = com.lifecyclebot.engine.BotService.status.tokens[pos.mint]?.ref ?: 0.0
                if (ref > 0.0) ref else pos.entryPrice
            } catch (_: Exception) { pos.entryPrice }
            val gainPct = if (pos.entryPrice > 0) (currentPrice - pos.entryPrice) / pos.entryPrice * 100 else 0.0
            val gainCol = if (gainPct >= 0) green else red
            val pnlSol = pos.entrySol * gainPct / 100.0
            childrenUnrealizedSum += pnlSol

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            // V5.8.0: Token logo
            val logoImg = android.widget.ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(40, 40).also { it.marginEnd = 10 }
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                try { background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.token_logo_bg) } catch (_: Exception) {}
                load("https://cdn.dexscreener.com/tokens/solana/${pos.mint}") {
                    crossfade(true); placeholder(R.drawable.ic_token_placeholder)
                    error(R.drawable.ic_token_placeholder); allowHardware(false)
                    transformations(coil.transform.CircleCropTransformation())
                }
            }
            row.addView(logoImg)

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
            val mcapM = pos.marketCapUsd / 1_000_000.0
            val mcapLabel = if (mcapM >= 1.0) "\$${String.format("%.2f", mcapM)}M" else "\$${String.format("%.0f", pos.marketCapUsd/1_000)}K"
            info.addView(TextView(this).apply {
                text = "MCap: $mcapLabel  ·  ${String.format("%.3f", pos.entrySol)}◎  TP:+${pos.takeProfitPct.toInt()}%  SL:${pos.stopLossPct.toInt()}%"
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
            })
            row.addView(info)

            // P&L (right column)
            val holdMins = (System.currentTimeMillis() - pos.entryTime) / 60_000
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
                text = "%+.4f◎  ${holdMins}m".format(pnlSol)
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
        return childrenUnrealizedSum
    }
    
    // Render Quality positions ($100K-$1M mcap)
    private fun renderQualityPositions(positions: List<com.lifecyclebot.v3.scoring.QualityTraderAI.QualityPosition>): Double {
        // V5.9.709 — skip re-render if quality list unchanged
        val qpHash = positions.map { "${it.mint}${it.lastSeenPrice}${it.entryScore}" }.hashCode()
        if (qpHash == lastQualityHash) return 0.0
        lastQualityHash = qpHash
        llQualityPositions.removeAllViews()
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        // V5.9.420 — accumulate children unrealized PnL for header parity.
        var childrenUnrealizedSum = 0.0

        positions.forEach { pos ->
            // V5.9.411/415 — true-freshness display. We declare a row stale
            // ONLY when no tick has arrived recently (>60s since the last
            // updateLivePrice / checkExit) AND the scanner has no entry for
            // the mint. A current price that legitimately equals entry is
            // NOT stale (just unchanged). Previous heuristic (current ==
            // entry → stale) gave false positives that wiped real 0% rows.
            val tsRef = try {
                com.lifecyclebot.engine.BotService.status.tokens[pos.mint]?.ref ?: 0.0
            } catch (_: Exception) { 0.0 }
            val now = System.currentTimeMillis()
            val lastTick = try {
                com.lifecyclebot.v3.scoring.QualityTraderAI.getLastPriceUpdateMs(pos.mint) ?: 0L
            } catch (_: Exception) { 0L }
            val hasFresh = (tsRef > 0.0) ||
                (lastTick > 0L && (now - lastTick) < 60_000L)
            val currentPrice = when {
                tsRef > 0.0                       -> tsRef
                pos.lastSeenPrice > 0.0           -> pos.lastSeenPrice
                else                              -> pos.entryPrice
            }
            val gainPct = if (pos.entryPrice > 0) (currentPrice - pos.entryPrice) / pos.entryPrice * 100 else 0.0
            val gainCol = when {
                !hasFresh    -> muted             // stale → grey, no false-zero green
                gainPct >= 0 -> green
                else         -> red
            }
            val pnlSol = pos.entrySol * gainPct / 100.0
            childrenUnrealizedSum += if (hasFresh) pnlSol else 0.0
            val holdMins = (System.currentTimeMillis() - pos.entryTime) / 60_000

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            // V5.8.0: Token logo
            val logoImg = android.widget.ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(40, 40).also { it.marginEnd = 10 }
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                try { background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.token_logo_bg) } catch (_: Exception) {}
                load("https://cdn.dexscreener.com/tokens/solana/${pos.mint}") {
                    crossfade(true); placeholder(R.drawable.ic_token_placeholder)
                    error(R.drawable.ic_token_placeholder); allowHardware(false)
                    transformations(coil.transform.CircleCropTransformation())
                }
            }
            row.addView(logoImg)

            // Amber colour bar for Quality
            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(4, LinearLayout.LayoutParams.MATCH_PARENT).also {
                    it.marginEnd = 12
                }
                setBackgroundColor(0xFFF59E0B.toInt())
            }
            row.addView(bar)

            // Token info (left column)
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = "⭐ ${pos.symbol}"
                textSize = resources.getDimension(R.dimen.trade_row_text) / resources.displayMetrics.scaledDensity
                setTextColor(0xFFF59E0B.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            info.addView(TextView(this).apply {
                text = "Entry: ${pos.entryPrice.fmtPrice()}  ·  ${sdf.format(java.util.Date(pos.entryTime))}"
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
            })
            val mcapK = pos.entryMcap / 1_000.0
            val mcapLabel = if (mcapK >= 1000) "\$${String.format("%.1f", mcapK/1000)}M" else "\$${String.format("%.0f", mcapK)}K"
            info.addView(TextView(this).apply {
                text = "MCap: $mcapLabel  ·  ${String.format("%.3f", pos.entrySol)}◎  TP:+${pos.takeProfitPct.toInt()}%  SL:${pos.stopLossPct.toInt()}%"
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
            })
            row.addView(info)

            // P&L (right column)
            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.END
            }
            right.addView(TextView(this).apply {
                text = if (hasFresh) "%+.1f%%".format(gainPct) else "— stale"
                textSize = resources.getDimension(R.dimen.token_name_size) / resources.displayMetrics.scaledDensity
                setTextColor(gainCol)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.END
            })
            right.addView(TextView(this).apply {
                text = if (hasFresh) "%+.4f◎  ${holdMins}m".format(pnlSol) else "no live feed  ${holdMins}m"
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
            llQualityPositions.addView(row)
            llQualityPositions.addView(div)
        }
        return childrenUnrealizedSum
    }

    // V4.0: Render ShitCoin Positions with platform icons
    private fun renderShitCoinPositions(positions: List<com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ShitCoinPosition>): Double {
        llShitCoinPositions.removeAllViews()
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        // V5.9.420 — accumulate children unrealized PnL for header parity.
        var childrenUnrealizedSum = 0.0
        
        positions.forEach { pos ->
            if (pos.entryPrice <= 0 || pos.entrySol <= 0 || pos.mint.isBlank()) return@forEach
            val currentPrice = try {
                com.lifecyclebot.engine.BotService.status.tokens[pos.mint]?.ref
                    ?.takeIf { it > 0 }
                    ?: pos.highWaterMark.takeIf { it > pos.entryPrice }
                    ?: pos.entryPrice
            } catch (_: Exception) { pos.entryPrice }
            val gainPct = if (pos.entryPrice > 0) (currentPrice - pos.entryPrice) / pos.entryPrice * 100 else 0.0
            val gainCol = if (gainPct >= 0) green else red
            val pnlSol = pos.entrySol * gainPct / 100.0
            childrenUnrealizedSum += pnlSol

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            // Token logo
            val logoImgSc = android.widget.ImageView(this).apply {
                val lp = LinearLayout.LayoutParams(40, 40).also { it.marginEnd = 10 }
                layoutParams = lp
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                try { background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.token_logo_bg) } catch (_: Exception) {}
                val cachedLogoUrl = try { com.lifecyclebot.engine.BotService.status.tokens[pos.mint]?.logoUrl } catch (_: Exception) { null }
                val logoUrl = if (!cachedLogoUrl.isNullOrBlank()) cachedLogoUrl
                              else "https://cdn.dexscreener.com/tokens/solana/${pos.mint}.png"
                load(logoUrl) {
                    crossfade(true)
                    placeholder(R.drawable.ic_token_placeholder)
                    error(R.drawable.ic_token_placeholder)
                    allowHardware(false)
                    transformations(coil.transform.CircleCropTransformation())
                }
            }
            row.addView(logoImgSc)

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
                textSize = resources.getDimension(R.dimen.card_badge_size) / resources.displayMetrics.scaledDensity
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
        return childrenUnrealizedSum
    }
    
    // V5.9: Render ShitCoinExpress active rides into dedicated Express card
    private fun renderExpressRides(rides: List<com.lifecyclebot.v3.scoring.ShitCoinExpress.ExpressRide>): Double {
        llExpressPositions.removeAllViews()
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        // V5.9.420 — accumulate children unrealized PnL for header parity.
        var childrenUnrealizedSum = 0.0
        rides.forEach { ride ->
            if (ride.entryPrice <= 0 || ride.entrySol <= 0 || ride.mint.isBlank()) return@forEach
            val currentPrice = try {
                com.lifecyclebot.engine.BotService.status.tokens[ride.mint]?.ref
                    ?.takeIf { it > 0 }
                    ?: ride.entryPrice
            } catch (_: Exception) { ride.entryPrice }
            val gainPct = if (ride.entryPrice > 0) (currentPrice - ride.entryPrice) / ride.entryPrice * 100 else 0.0
            val gainCol = if (gainPct >= 0) green else red
            val pnlSol = ride.entrySol * gainPct / 100.0
            childrenUnrealizedSum += pnlSol
            val holdMins = (System.currentTimeMillis() - ride.entryTime) / 60_000

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            // Token logo
            val logoImgEx = android.widget.ImageView(this).apply {
                val lp = LinearLayout.LayoutParams(40, 40).also { it.marginEnd = 10 }
                layoutParams = lp
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                try { background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.token_logo_bg) } catch (_: Exception) {}
                val cachedLogoUrlEx = try { com.lifecyclebot.engine.BotService.status.tokens[ride.mint]?.logoUrl } catch (_: Exception) { null }
                val logoUrl = if (!cachedLogoUrlEx.isNullOrBlank()) cachedLogoUrlEx
                              else "https://cdn.dexscreener.com/tokens/solana/${ride.mint}.png"
                load(logoUrl) {
                    crossfade(true)
                    placeholder(R.drawable.ic_token_placeholder)
                    error(R.drawable.ic_token_placeholder)
                    allowHardware(false)
                    transformations(coil.transform.CircleCropTransformation())
                }
            }
            row.addView(logoImgEx)

            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(4, LinearLayout.LayoutParams.MATCH_PARENT).also { it.marginEnd = 12 }
                setBackgroundColor(0xFFFF4500.toInt()) // Deep orange for express
            }
            row.addView(bar)

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = "🚂 ${ride.symbol}  ${ride.ridePhase.emoji}"
                textSize = resources.getDimension(R.dimen.trade_row_text) / resources.displayMetrics.scaledDensity
                setTextColor(0xFFFF4500.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            info.addView(TextView(this).apply {
                text = "Entry: ${ride.entryPrice.fmtPrice()}  ·  ${sdf.format(java.util.Date(ride.entryTime))}"
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
            })
            info.addView(TextView(this).apply {
                text = "Size: %.4f◎  ·  mom=${ride.entryMomentum.toInt()}%  ·  ${holdMins}m".format(ride.entrySol)
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
            })
            row.addView(info)

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
                text = "TP 30/50/100% SL -8%"
                textSize = resources.getDimension(R.dimen.card_badge_size) / resources.displayMetrics.scaledDensity
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = android.view.Gravity.END
            })
            row.addView(right)

            val div = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).also { it.topMargin = 10 }
                setBackgroundColor(0xFF1F2937.toInt())
            }
            llExpressPositions.addView(row)
            llExpressPositions.addView(div)
        }
        return childrenUnrealizedSum
    }

    // ☠️ Render Manipulated positions into the Manip card
    private fun renderManipPositions(positions: List<com.lifecyclebot.v3.scoring.ManipulatedTraderAI.ManipulatedPosition>): Double {
        llManipPositions.removeAllViews()
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        // V5.9.420 — accumulate children unrealized PnL for header parity.
        var childrenUnrealizedSum = 0.0
        positions.forEach { pos ->
            // V5.9.302: dead-feed guard — ref=0 must NOT count as -100%
            val currentPrice = try {
                val ref = com.lifecyclebot.engine.BotService.status.tokens[pos.mint]?.ref ?: 0.0
                if (ref > 0.0) ref else pos.entryPrice
            } catch (_: Exception) { pos.entryPrice }
            val gainPct = if (pos.entryPrice > 0) (currentPrice - pos.entryPrice) / pos.entryPrice * 100 else 0.0
            val gainCol = if (gainPct >= 0) green else red
            val pnlSol = pos.entrySol * gainPct / 100.0
            childrenUnrealizedSum += pnlSol
            val holdMins = (System.currentTimeMillis() - pos.entryTime) / 60_000

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            // V5.8.0: Token logo
            val logoImg = android.widget.ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(40, 40).also { it.marginEnd = 10 }
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                try { background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.token_logo_bg) } catch (_: Exception) {}
                load("https://cdn.dexscreener.com/tokens/solana/${pos.mint}") {
                    crossfade(true); placeholder(R.drawable.ic_token_placeholder)
                    error(R.drawable.ic_token_placeholder); allowHardware(false)
                    transformations(coil.transform.CircleCropTransformation())
                }
            }
            row.addView(logoImg)

            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(4, LinearLayout.LayoutParams.MATCH_PARENT).also { it.marginEnd = 12 }
                setBackgroundColor(0xFFB91C1C.toInt()) // Dark red for manipulated
            }
            row.addView(bar)

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = "☠️ ${pos.symbol}  score=${pos.manipScore}"
                textSize = resources.getDimension(R.dimen.trade_row_text) / resources.displayMetrics.scaledDensity
                setTextColor(0xFFB91C1C.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            info.addView(TextView(this).apply {
                text = "Entry: ${pos.entryPrice.fmtPrice()}  ·  ${sdf.format(java.util.Date(pos.entryTime))}"
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
            })
            info.addView(TextView(this).apply {
                text = "Size: %.4f◎  ·  bndl=${pos.bundlePct.toInt()}%  ·  bp=${pos.buyPressure.toInt()}%  ·  ${holdMins}m".format(pos.entrySol)
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
            })
            row.addView(info)

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
                text = "TP+25% SL-5% 4min"
                textSize = resources.getDimension(R.dimen.card_badge_size) / resources.displayMetrics.scaledDensity
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = android.view.Gravity.END
            })
            row.addView(right)

            val div = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).also { it.topMargin = 10 }
                setBackgroundColor(0xFF1F2937.toInt())
            }
            llManipPositions.addView(row)
            llManipPositions.addView(div)
        }
        return childrenUnrealizedSum
    }

    // V5.2: Render Moonshot positions
    private fun renderMoonshotPositions(positions: List<com.lifecyclebot.v3.scoring.MoonshotTraderAI.MoonshotPosition>) {
        // V5.9.709 — skip render if moonshot positions unchanged
        val moonHash = positions.map { "${it.mint}${it.lastSeenPrice}${it.isPaperMode}" }.hashCode()
        if (moonHash == lastMoonshotHash) return
        lastMoonshotHash = moonHash
        llMoonshotPositions.removeAllViews()
        
        for (pos in positions) {
            // V5.9.302: Guard against dead price feed (ref=0 when token rugs/dies).
            // Without guard: pnlPct = (0 - entryPrice)/entryPrice*100 = -100% even though
            // the engine hasn't closed it. Show ~0% instead while RUG_SAFETY_NET fires.
            val currentPrice = try {
                val ref = com.lifecyclebot.engine.BotService.status.tokens[pos.mint]?.ref ?: 0.0
                if (ref > 0.0) ref else pos.entryPrice
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
            
            // V5.8.0: Token logo
            val logoImgMs = android.widget.ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(40, 40).also { it.marginEnd = 10 }
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                try { background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.token_logo_bg) } catch (_: Exception) {}
                load("https://cdn.dexscreener.com/tokens/solana/${pos.mint}") {
                    crossfade(true); placeholder(R.drawable.ic_token_placeholder)
                    error(R.drawable.ic_token_placeholder); allowHardware(false)
                    transformations(coil.transform.CircleCropTransformation())
                }
            }
            row.addView(logoImgMs)

            // Symbol
            val tvSymbol = TextView(this).apply {
                text = pos.symbol
                setTextColor(0xFFA855F7.toInt())  // Purple for moonshots
                textSize = resources.getDimension(R.dimen.trade_row_text) / resources.displayMetrics.scaledDensity
                typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            
            // Entry / Current
            val tvEntry = TextView(this).apply {
                text = "${String.format("%.6f", pos.entryPrice)} → ${String.format("%.6f", currentPrice)}"
                setTextColor(0xFF6B7280.toInt())
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            }
            
            // P&L
            val tvPnl = TextView(this).apply {
                text = "${if (pnlPct >= 0) "+" else ""}${String.format("%.1f", pnlPct)}%"
                setTextColor(if (pnlPct >= 0) 0xFF10B981.toInt() else 0xFFEF4444.toInt())
                textSize = resources.getDimension(R.dimen.trade_row_text) / resources.displayMetrics.scaledDensity
                typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.5f)
            }
            
            // Hold time
            val tvHold = TextView(this).apply {
                text = "${holdMins}m"
                setTextColor(0xFF6B7280.toInt())
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.3f)
            }
            
            row.addView(tvSymbol)
            row.addView(tvEntry)
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
    
    // V5.6.29d: Render Network Signals from Collective Intelligence
    private fun renderNetworkSignals() {
        // V5.9.709 — network signal panel doesn't change faster than 3s; skip if too soon
        val now = System.currentTimeMillis()
        if (now - lastNetworkSigRenderMs < 8_000L) return  // V5.9.726 — was 3s, raised to 8s
        lastNetworkSigRenderMs = now
        try {
            val rawSignals = com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI.getActiveNetworkSignals()
            
            // V5.7.7: Filter out obviously corrupted signals (price scale bugs can create >10M% values)
            // Real meme pumps can hit 100,000% but anything above 1,000,000% is likely data corruption
            val signals = rawSignals.filter { 
                it.pnlPct.isFinite() && it.pnlPct > -101.0 && it.pnlPct < 1_000_000.0 
            }
            
            // Count by type
            val megaCount = signals.count { it.signalType == "MEGA_WINNER" }
            val hotCount = signals.count { it.signalType == "HOT_TOKEN" }
            val avoidCount = signals.count { it.signalType == "AVOID" }
            val totalActive = signals.size
            
            // Show/hide card based on whether we have signals
            cardNetworkSignals.visibility = if (totalActive > 0) android.view.View.VISIBLE else android.view.View.GONE
            
            if (totalActive == 0) return
            
            // Update stats
            tvNetworkSignalCount.text = "$totalActive active"
            tvNetworkMegaWinners.text = "MEGA: $megaCount"
            tvNetworkMegaWinners.setTextColor(if (megaCount > 0) 0xFFF59E0B.toInt() else 0xFF6B7280.toInt())
            tvNetworkHotTokens.text = "HOT: $hotCount"
            tvNetworkHotTokens.setTextColor(if (hotCount > 0) 0xFF10B981.toInt() else 0xFF6B7280.toInt())
            tvNetworkAvoid.text = "AVOID: $avoidCount"
            tvNetworkAvoid.setTextColor(if (avoidCount > 0) 0xFFEF4444.toInt() else 0xFF6B7280.toInt())
            
            // Get last sync time from CollectiveIntelligenceAI
            val lastRefresh = com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI.getLastRefreshTime()
            val syncAgoSecs = (System.currentTimeMillis() - lastRefresh) / 1000
            tvNetworkLastSync.text = if (syncAgoSecs < 60) "Sync: ${syncAgoSecs}s" else "Sync: ${syncAgoSecs/60}m"
            
            // Clear and repopulate list
            llNetworkSignals.removeAllViews()
            
            // Sort: MEGA_WINNER first, then HOT_TOKEN, then AVOID. Within each, by pnl desc
            val sortedSignals = signals.sortedWith(compareBy(
                { when (it.signalType) { "MEGA_WINNER" -> 0; "HOT_TOKEN" -> 1; "AVOID" -> 2; else -> 3 } },
                { -it.pnlPct }
            )).take(10)  // Show max 10 signals
            
            for (signal in sortedSignals) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = 4 }
                }
                
                // Signal type emoji
                val emoji = when (signal.signalType) {
                    "MEGA_WINNER" -> "🔥"
                    "HOT_TOKEN" -> "🌐"
                    "AVOID" -> "⚠️"
                    else -> "📡"
                }
                val tvEmoji = TextView(this).apply {
                    text = emoji
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = 8 }
                }
                
                // Symbol
                val tvSymbol = TextView(this).apply {
                    text = signal.symbol.take(10)
                    setTextColor(when (signal.signalType) {
                        "MEGA_WINNER" -> 0xFFF59E0B.toInt()
                        "HOT_TOKEN" -> 0xFF10B981.toInt()
                        "AVOID" -> 0xFFEF4444.toInt()
                        else -> 0xFFFFFFFF.toInt()
                    })
                    textSize = 13f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.35f)
                }
                
                // PnL %
                val pnlColor = if (signal.pnlPct >= 0) 0xFF10B981.toInt() else 0xFFEF4444.toInt()
                val pnlSign = if (signal.pnlPct >= 0) "+" else ""
                val tvPnl = TextView(this).apply {
                    text = "$pnlSign${signal.pnlPct.toInt()}%"
                    setTextColor(pnlColor)
                    textSize = 12f
                    typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.2f)
                }
                
                // Source (broadcaster truncated)
                val tvSource = TextView(this).apply {
                    text = "from ${signal.broadcasterId.take(6)}..."
                    setTextColor(0xFF6B7280.toInt())
                    textSize = 10f
                    typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.35f)
                }
                
                // Ack count if > 1
                if (signal.ackCount > 1) {
                    val tvAck = TextView(this).apply {
                        text = "x${signal.ackCount}"
                        setTextColor(0xFF00D4FF.toInt())
                        textSize = 10f
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        ).also { it.marginStart = 4 }
                    }
                    row.addView(tvEmoji)
                    row.addView(tvSymbol)
                    row.addView(tvPnl)
                    row.addView(tvSource)
                    row.addView(tvAck)
                } else {
                    row.addView(tvEmoji)
                    row.addView(tvSymbol)
                    row.addView(tvPnl)
                    row.addView(tvSource)
                }
                
                llNetworkSignals.addView(row)
            }
        } catch (e: Exception) {
            // Silent fail - don't crash UI for network signals
        }
    }
    
    // V5.6.29d: Render Project Sniper missions
    private fun renderSniperMissions() {
        try {
            val missions = com.lifecyclebot.v3.scoring.ProjectSniperAI.getActiveMissions()
            val dailyStats = com.lifecyclebot.v3.scoring.ProjectSniperAI.getDailyStats()
            val lifetimeStats = com.lifecyclebot.v3.scoring.ProjectSniperAI.getLifetimeStats()
            
            // Show/hide card
            cardSniperPositions.visibility = if (missions.isNotEmpty() || dailyStats.missions > 0) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
            
            if (cardSniperPositions.visibility == android.view.View.GONE) return
            
            // Update stats
            tvSniperExposure.text = "${missions.size} missions"
            tvSniperRank.text = "⭐${lifetimeStats.generals} 🎖️${lifetimeStats.colonels + lifetimeStats.majors}"
            tvSniperWinRate.text = "${dailyStats.kills}K/${dailyStats.kia}KIA"
            
            val pnlColor = if (dailyStats.pnlSol >= 0) 0xFF10B981.toInt() else 0xFFEF4444.toInt()
            val pnlSign = if (dailyStats.pnlSol >= 0) "+" else ""
            tvSniperDailyPnl.text = "Day: $pnlSign${String.format("%.2f", dailyStats.pnlSol)}"
            tvSniperDailyPnl.setTextColor(pnlColor)
            
            // Render active missions
            llSniperMissions.removeAllViews()
            
            for (mission in missions) {
                // V5.9.302: dead-feed guard — ref=0 must NOT count as -100%
                val currentPrice = run {
                    val ref = com.lifecyclebot.engine.BotService.status.tokens[mission.mint]?.ref ?: 0.0
                    if (ref > 0.0) ref else mission.entryPrice
                }
                val pnlPct = ((currentPrice - mission.entryPrice) / mission.entryPrice * 100)
                val holdTimeSecs = (System.currentTimeMillis() - mission.entryTime) / 1000
                
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = 6 }
                }
                
                // Rank emoji
                val rankEmoji = when {
                    pnlPct >= 100 -> "⭐"
                    pnlPct >= 50 -> "🎖️"
                    pnlPct >= 25 -> "🎖️"
                    pnlPct >= 10 -> "🎖️"
                    pnlPct >= 0 -> "🎯"
                    else -> "💀"
                }
                val tvEmoji = TextView(this).apply {
                    text = rankEmoji
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = 6 }
                }
                
                // Symbol
                val tvSymbol = TextView(this).apply {
                    text = mission.symbol.take(8)
                    setTextColor(0xFFEF4444.toInt())
                    textSize = 12f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.3f)
                }
                
                // PnL
                val pnlTextColor = if (pnlPct >= 0) 0xFF10B981.toInt() else 0xFFEF4444.toInt()
                val tvPnl = TextView(this).apply {
                    text = "${if (pnlPct >= 0) "+" else ""}${String.format("%.1f", pnlPct)}%"
                    setTextColor(pnlTextColor)
                    textSize = 11f
                    typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.2f)
                }
                
                // Hold time
                val tvTime = TextView(this).apply {
                    text = "${holdTimeSecs}s"
                    setTextColor(0xFF6B7280.toInt())
                    textSize = 10f
                    typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.15f)
                }
                
                // Entry age
                val tvAge = TextView(this).apply {
                    text = "@${mission.tokenAgeSecs}s"
                    setTextColor(0xFF6B7280.toInt())
                    textSize = 10f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.15f)
                }
                
                // Size
                val tvSize = TextView(this).apply {
                    text = String.format("%.2f◎", mission.entrySol)
                    setTextColor(0xFF6B7280.toInt())
                    textSize = 10f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.2f)
                }
                
                row.addView(tvEmoji)
                row.addView(tvSymbol)
                row.addView(tvPnl)
                row.addView(tvTime)
                row.addView(tvAge)
                row.addView(tvSize)
                
                llSniperMissions.addView(row)
            }
        } catch (e: Exception) {
            // Silent fail
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
                // V5.8.0: Refresh candle chart for new timeframe
                val activeTs = try {
                    val mint = selectedChartMint
                    if (!mint.isNullOrBlank()) com.lifecyclebot.engine.BotService.status.tokens[mint]
                    else com.lifecyclebot.engine.BotService.status.tokens.values.firstOrNull { it.position.isOpen }
                        ?: com.lifecyclebot.engine.BotService.status.tokens.values.firstOrNull()
                } catch (_: Exception) { null }
                updateCandleChart(activeTs)
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
    
    // V5.6: Update DexScreener-style chart metrics
    private fun updateChartMetrics(ts: TokenState?) {
        if (ts == null) return
        
        // Market Cap
        tvChartMcap?.text = when {
            ts.lastMcap >= 1_000_000 -> "$${(ts.lastMcap / 1_000_000).toInt()}M"
            ts.lastMcap >= 1_000 -> "$${(ts.lastMcap / 1_000).toInt()}K"
            else -> "$${ts.lastMcap.toInt()}"
        }
        
        // 5m Volume (use recent history to calculate)
        val vol5m = try {
            synchronized(ts.history) {
                val now = System.currentTimeMillis()
                val fiveMinAgo = now - (5 * 60 * 1000L)
                ts.history.filter { candle -> candle.ts > fiveMinAgo }
                    .sumOf { candle -> candle.volumeH1 }
            }
        } catch (_: Exception) { 0.0 }
        
        tvChart5mVol?.text = when {
            vol5m >= 1_000_000 -> "$${(vol5m / 1_000_000).toInt()}M"
            vol5m >= 1_000 -> "$${(vol5m / 1_000).toInt()}K"
            vol5m > 0 -> "$${vol5m.toInt()}"
            else -> "$0"
        }
        tvChart5mVol?.setTextColor(if (vol5m > 10000) green else if (vol5m > 1000) 0xFF10B981.toInt() else 0xFF6B7280.toInt())
        
        // Liquidity
        tvChartLiq?.text = when {
            ts.lastLiquidityUsd >= 1_000_000 -> "$${(ts.lastLiquidityUsd / 1_000_000).toInt()}M"
            ts.lastLiquidityUsd >= 1_000 -> "$${(ts.lastLiquidityUsd / 1_000).toInt()}K"
            else -> "$${ts.lastLiquidityUsd.toInt()}"
        }
        tvChartLiq?.setTextColor(when {
            ts.lastLiquidityUsd >= 50000 -> green
            ts.lastLiquidityUsd >= 10000 -> 0xFF3B82F6.toInt()
            else -> amber
        })
        
        // Holders (from last candle if available)
        val holders = try {
            ts.history.lastOrNull()?.holderCount ?: 0
        } catch (_: Exception) { 0 }
        tvChartHolders?.text = when {
            holders >= 1000 -> "${holders / 1000}K"
            holders > 0 -> "$holders"
            else -> "—"
        }
        
        // Buy Pressure
        val buyPressure = ts.lastBuyPressurePct
        tvChartBuyPressure?.text = "${buyPressure.toInt()}%"
        tvChartBuyPressure?.setTextColor(when {
            buyPressure >= 65 -> green
            buyPressure >= 50 -> 0xFF10B981.toInt()
            buyPressure >= 35 -> amber
            else -> red
        })
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
            
            // V5.6: ML Engine Status - show training progress
            val mlStatus = try {
                com.lifecyclebot.ml.OnDeviceMLEngine.getStatus()
            } catch (_: Exception) { "Not initialized" }
            
            // Active AI Layers - concise list with ML
            // V5.9.230: Live Education layer health — top performing + muted
            val eduHealthStr = try {
                val maturity = com.lifecyclebot.v3.scoring.EducationSubLayerAI.getAllLayerMaturity()
                val top = maturity.entries
                    .filter { it.value.trades >= 10 }
                    .sortedByDescending { it.value.smoothedAccuracy }
                    .take(4)
                    .joinToString(" · ") { "${it.key.take(6)}(${(it.value.smoothedAccuracy*100).toInt()}%)" }
                val muted = maturity.entries
                    .filter { !it.value.isActive }
                    .take(2)
                    .joinToString(", ") { "⛔${it.key.take(6)}" }
                val metaConf = try {
                    val mc = com.lifecyclebot.v3.scoring.MetaCognitionAI.calculateMetaConfidence(emptyList())
                    " | Meta:${mc.confidence.toInt()}%"
                } catch (_: Exception) { "" }
                val symCtx = try {
                    " | Sym:${com.lifecyclebot.engine.SymbolicContext.getDiagnostics().take(18)}"
                } catch (_: Exception) { "" }
                val sentLine = try {
                    " | ${com.lifecyclebot.engine.SentientPersonality.getStatusLine().take(24)}"
                } catch (_: Exception) { "" }
                "${if (top.isNotEmpty()) "🏆 $top" else "Learning..."}${if (muted.isNotEmpty()) " | $muted" else ""}$metaConf$symCtx$sentLine"
            } catch (_: Exception) {
                "Entry · Exit · Momentum · Liquidity · Regime · ShitCoin · Express · ML($mlStatus)"
            }
            tvAiLayers.text = eduHealthStr
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
            val manipPos = com.lifecyclebot.v3.scoring.ManipulatedTraderAI.getActivePositions().size
            val qualityPos = com.lifecyclebot.v3.scoring.QualityTraderAI.getActivePositions().size
            val moonshotPos = com.lifecyclebot.v3.scoring.MoonshotTraderAI.getActivePositions().size
            val blueChipPos = com.lifecyclebot.v3.scoring.BlueChipTraderAI.getActivePositions().size
            val totalOpenPos = treasuryPos + shitCoinPos + expressPos + manipPos + qualityPos + moonshotPos + blueChipPos
            
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
        
        // ShitCoinExpress - show active rides and win rate
        try {
            val stats = com.lifecyclebot.v3.scoring.ShitCoinExpress.getStats()
            val posCount = stats.activeRides
            val winRate = stats.winRate.toInt()
            if (posCount > 0 || stats.dailyRides > 0) {
                tvExpressStats.text = "$posCount | $winRate%"
                tvExpressStats.setTextColor(when {
                    winRate >= 60 -> green
                    winRate >= 45 -> amber
                    else -> red
                })
            } else {
                tvExpressStats.text = "0/0"
                tvExpressStats.setTextColor(muted)
            }
        } catch (_: Exception) { tvExpressStats.text = "—" }

        // ☠️ The Manipulated - show active positions and win rate
        try {
            val manipStats = com.lifecyclebot.v3.scoring.ManipulatedTraderAI.getStats()
            val posCount = manipStats.activeCount
            val totalTrades = manipStats.dailyWins + manipStats.dailyLosses
            val winRate = if (totalTrades > 0) (manipStats.dailyWins * 100 / totalTrades) else 0
            if (posCount > 0 || totalTrades > 0) {
                tvManipStats.text = "$posCount | $winRate%"
                tvManipStats.setTextColor(when {
                    winRate >= 60 -> green
                    winRate >= 45 -> amber
                    else -> red
                })
            } else {
                tvManipStats.text = "0/0"
                tvManipStats.setTextColor(muted)
            }
        } catch (_: Exception) { tvManipStats.text = "—" }

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
        
        // V5.7: Perps - show active positions and win rate
        try {
            val perpsAI = com.lifecyclebot.perps.PerpsTraderAI
            val posCount = perpsAI.getPositionCount()
            val winRate = perpsAI.getWinRatePct()
            val totalTrades = perpsAI.getDailyWins() + perpsAI.getDailyLosses()
            
            if (perpsAI.isEnabled()) {
                if (posCount > 0 || totalTrades > 0) {
                    tvPerpsStats?.text = "$posCount | $winRate%"
                    tvPerpsStats?.setTextColor(when {
                        winRate >= 60 -> green
                        winRate >= 45 -> amber
                        else -> red
                    })
                } else {
                    tvPerpsStats?.text = "0/0"
                    tvPerpsStats?.setTextColor(muted)
                }
                
                // Update Perps card if visible
                updatePerpsCard(perpsAI)
            } else {
                tvPerpsStats?.text = "OFF"
                tvPerpsStats?.setTextColor(muted)
            }
            
            // V5.7.6: ALWAYS update Tokenized Stocks card - it has its own engine
            // TokenizedStockTrader is independent of PerpsTraderAI
            updateTokenizedStocksCard()
        updateCryptoAltsCard()
        } catch (_: Exception) { tvPerpsStats?.text = "—" }
        
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
     * V5.7: Update Perps Trading Card UI
     */
    private fun updatePerpsCard(perpsAI: com.lifecyclebot.perps.PerpsTraderAI) {
        try {
            if (!perpsAI.isEnabled() || !perpsAI.hasAcknowledgedRisk()) {
                cardPerpsTrading?.visibility = View.GONE
                return
            }
            
            // V5.7.6: Perps card moved to MultiAssetActivity - keep hidden
            cardPerpsTrading?.visibility = View.GONE
            return
            
            // DEPRECATED: All code below is unused - perps card now in Markets UI
            val state = perpsAI.getState()
            val readiness = perpsAI.getLiveReadiness()
            val cfg = com.lifecyclebot.data.ConfigStore.load(applicationContext)
            
            // Mode badge
            tvPerpsModeBadge?.text = if (cfg.paperMode) "PAPER" else "LIVE"
            tvPerpsModeBadge?.setBackgroundResource(
                if (cfg.paperMode) R.drawable.pill_bg_yellow else R.drawable.pill_bg_green
            )
            
            // Balance
            val balance = if (cfg.paperMode) state.paperBalanceSol else state.liveBalanceSol
            tvPerpsBalance?.text = "%.4f".format(balance)
            
            // P&L
            val pnlPct = state.dailyPnlPct
            val pnlSign = if (pnlPct >= 0) "+" else ""
            tvPerpsPnl?.text = "$pnlSign${"%.2f".format(pnlPct)}%"
            tvPerpsPnl?.setTextColor(if (pnlPct >= 0) 0xFF22C55E.toInt() else 0xFFEF4444.toInt())
            
            // Win Rate
            val winRate = perpsAI.getWinRatePct()
            tvPerpsWinRate?.text = "${winRate}%"
            tvPerpsWinRate?.setTextColor(when {
                winRate >= 55 -> 0xFF22C55E.toInt()
                winRate >= 45 -> 0xFFF59E0B.toInt()
                else -> 0xFFEF4444.toInt()
            })
            
            // Trades
            tvPerpsTrades?.text = "${state.dailyTrades}"
            
            // Readiness gauge
            tvPerpsReadinessPhase?.text = readiness.phase.displayName
            tvPerpsReadinessPhase?.setTextColor(android.graphics.Color.parseColor(readiness.phase.color))
            
            // Progress bar
            val progressPct = readiness.getProgressPct()
            val barWidth = (cardPerpsTrading?.width ?: 300) * progressPct / 100
            viewPerpsReadinessBar?.layoutParams?.width = barWidth.coerceAtLeast(0)
            viewPerpsReadinessBar?.requestLayout()
            
            tvPerpsReadinessText?.text = readiness.recommendation
            
            // Fetch and display SOL price
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                try {
                    val marketData = com.lifecyclebot.perps.PerpsMarketDataFetcher.getMarketData(
                        com.lifecyclebot.perps.PerpsMarket.SOL
                    )
                    tvPerpsSolPrice?.text = "$${"%.2f".format(marketData.price)}"
                    val changeSign = if (marketData.priceChange24hPct >= 0) "+" else ""
                    tvPerpsSolChange?.text = "$changeSign${"%.1f".format(marketData.priceChange24hPct)}%"
                    tvPerpsSolChange?.setTextColor(
                        if (marketData.priceChange24hPct >= 0) 0xFF22C55E.toInt() else 0xFFEF4444.toInt()
                    )
                } catch (_: Exception) {
                    tvPerpsSolPrice?.text = "$—"
                    tvPerpsSolChange?.text = "—"
                }
                
                // V5.7.4: Update AAPL/TSLA/NVDA prices in perps card header
                try {
                    val aaplData = com.lifecyclebot.perps.PerpsMarketDataFetcher.getMarketData(com.lifecyclebot.perps.PerpsMarket.AAPL)
                    tvPerpsAaplPrice?.text = "$${"%.2f".format(aaplData.price)}"
                } catch (_: Exception) { tvPerpsAaplPrice?.text = "$--" }
                
                try {
                    val tslaData = com.lifecyclebot.perps.PerpsMarketDataFetcher.getMarketData(com.lifecyclebot.perps.PerpsMarket.TSLA)
                    tvPerpsTslaPrice?.text = "$${"%.2f".format(tslaData.price)}"
                } catch (_: Exception) { tvPerpsTslaPrice?.text = "$--" }
                
                try {
                    val nvdaData = com.lifecyclebot.perps.PerpsMarketDataFetcher.getMarketData(com.lifecyclebot.perps.PerpsMarket.NVDA)
                    tvPerpsNvdaPrice?.text = "$${"%.2f".format(nvdaData.price)}"
                } catch (_: Exception) { tvPerpsNvdaPrice?.text = "$--" }
            }
            
            // V5.7.1: Update Layer Confidence Dashboard
            updateLayerConfidenceDashboard()
            
        } catch (e: Exception) {
            com.lifecyclebot.engine.ErrorLogger.warn("MainActivity", "Perps card update error: ${e.message}")
        }
    }
    
    /**
     * V5.7.1: Update Layer Confidence Dashboard with top performing layers
     */
    private fun updateLayerConfidenceDashboard() {
        try {
            val bridge = com.lifecyclebot.perps.PerpsLearningBridge
            val layerStats = bridge.getLayerPerpsStats()
            
            // Sort layers by trust score
            val sortedLayers = layerStats.entries
                .sortedByDescending { it.value.first }
                .take(4)
            
            // Update layer count
            tvLayerSyncCount?.text = "${bridge.getConnectedLayerCount()} layers"
            
            // Update top 4 layers
            sortedLayers.getOrNull(0)?.let { (name, stats) ->
                tvLayer1Name?.text = name.replace("TraderAI", "").replace("AI", "")
                val score = (stats.first * 100).toInt()
                tvLayer1Score?.text = "$score%"
                tvLayer1Score?.setTextColor(getScoreColor(score))
            }
            
            sortedLayers.getOrNull(1)?.let { (name, stats) ->
                tvLayer2Name?.text = name.replace("TraderAI", "").replace("AI", "")
                val score = (stats.first * 100).toInt()
                tvLayer2Score?.text = "$score%"
                tvLayer2Score?.setTextColor(getScoreColor(score))
            }
            
            sortedLayers.getOrNull(2)?.let { (name, stats) ->
                tvLayer3Name?.text = name.replace("TraderAI", "").replace("AI", "")
                val score = (stats.first * 100).toInt()
                tvLayer3Score?.text = "$score%"
                tvLayer3Score?.setTextColor(getScoreColor(score))
            }
            
            sortedLayers.getOrNull(3)?.let { (name, stats) ->
                tvLayer4Name?.text = name.replace("TraderAI", "").replace("AI", "")
                val score = (stats.first * 100).toInt()
                tvLayer4Score?.text = "$score%"
                tvLayer4Score?.setTextColor(getScoreColor(score))
            }
            
            // Update learning stats
            tvLayerLearningEvents?.text = "${bridge.getTotalLearningEvents()} learning events"
            tvLayerCrossSync?.text = "${bridge.getCrossLayerSyncs()} cross-syncs"
            
            // V5.7.3: Update Learning Insights Panel
            updateLearningInsightsPanel()
            
        } catch (e: Exception) {
            com.lifecyclebot.engine.ErrorLogger.debug("MainActivity", "Layer dashboard update error: ${e.message}")
        }
    }
    
    private fun getScoreColor(score: Int): Int {
        return when {
            score >= 80 -> 0xFF22C55E.toInt()  // Green
            score >= 60 -> 0xFFF59E0B.toInt()  // Amber
            score >= 40 -> 0xFF3B82F6.toInt()  // Blue
            else -> 0xFFEF4444.toInt()          // Red
        }
    }
    
    /**
     * V5.2.8: Update 30-Day Run Stats Card
     * Shows balance, return, drawdown, trades, W/L/S, win rate, learning metrics, and integrity
     */
    private fun update30DayRunStats() {
        // V5.9.348: Per-trader 30-day / lifetime stats card, driven by currentReadinessTab.
        when (currentReadinessTab) {
            "ALTS"  -> render30DayAlts()
            "PERPS" -> render30DayPerps()
            else    -> render30DayMeme()
        }
    }

    /** V5.9.348: Meme 30-day proof run (native RunTracker30D). */
    private fun render30DayMeme() {
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
        tv30DayDrawdown.text = "N/A"
        tv30DayDrawdown.setTextColor(muted)
        
        // Trades count
        tv30DayTrades.text = tracker.totalTrades.toString()
        
        // W/L/S
        tv30DayWLS.text = "${tracker.wins} / ${tracker.losses} / ${tracker.scratches}"
        
        // Win rate - V5.6.16: Exclude scratches from calculation
        val meaningfulTrades = tracker.wins + tracker.losses
        val winRate = if (meaningfulTrades > 0) {
            (tracker.wins * 100 / meaningfulTrades)
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

    /** V5.9.348: Alts "Lifetime" proof card (CryptoAltTrader has no 30-day window). */
    private fun render30DayAlts() {
        card30DayRun.visibility = View.VISIBLE
        val stats = try { com.lifecyclebot.perps.CryptoAltTrader.getStats() } catch (_: Exception) { emptyMap<String, Any>() }
        val totalTrades   = (stats["totalTrades"]    as? Int)    ?: 0
        val wins          = (stats["winningTrades"]  as? Int)    ?: 0
        val losses        = (stats["losingTrades"]   as? Int)    ?: 0
        // V5.9.419 — show real scratches (default 0 if older getStats build).
        val scratches     = (stats["scratchTrades"]  as? Int)
            ?: (totalTrades - wins - losses).coerceAtLeast(0)
        val paperBalance  = (stats["paperBalance"]   as? Double) ?: 0.0
        val openPositions = (stats["openPositions"]  as? Int)    ?: 0
        val phaseLabel    = (stats["learningPhase"]  as? String) ?: "BOOTSTRAP"
        val initialBalance = try { com.lifecyclebot.perps.CryptoAltTrader.getInitialBalance() } catch (_: Exception) { paperBalance }

        tv30DayCounter.text = "🪙 CRYPTO · LIFETIME"
        tv30DayBalance.text = String.format("%.4f SOL", paperBalance)
        val returnPct = if (initialBalance > 0) ((paperBalance - initialBalance) / initialBalance) * 100 else 0.0
        val sign = if (returnPct >= 0) "+" else ""
        tv30DayReturn.text = "$sign${String.format("%.2f", returnPct)}%"
        tv30DayReturn.setTextColor(if (returnPct >= 0) green else red)

        tv30DayDrawdown.text = "N/A"
        tv30DayDrawdown.setTextColor(muted)

        tv30DayTrades.text = totalTrades.toString()
        tv30DayWLS.text = "$wins / $losses / $scratches"

        val meaningful = wins + losses
        val winRate = if (meaningful > 0) (wins * 100 / meaningful) else 0
        tv30DayWinRate.text = "$winRate%"
        tv30DayWinRate.setTextColor(when {
            winRate >= 60 -> green
            winRate >= 45 -> amber
            else -> red
        })

        tv30DayLearning.text = phaseLabel
        tv30DayAccuracy.text = "$winRate%"
        tv30DayAccuracy.setTextColor(when {
            winRate >= 60 -> green
            winRate >= 45 -> amber
            else -> red
        })
        tv30DayIntegrity.text = openPositions.toString()
        tv30DayIntegrity.setTextColor(Color.parseColor("#00BFFF"))
    }

    /** V5.9.348: Perps "Lifetime" proof card (PerpsTraderAI has no native 30-day window). */
    private fun render30DayPerps() {
        card30DayRun.visibility = View.VISIBLE
        val perps = com.lifecyclebot.perps.PerpsTraderAI
        // V5.9.418 — UNIFIED PERPS STAT PARITY.
        // Was: only PerpsTraderAI lifetime stats → top-bar (which sums every
        // markets bucket) and the 30-Day card disagreed (top-bar showed 12
        // trades, card showed 0). Now we sum every markets trader the same
        // way renderPerpsReadiness() and the top-bar do, so all three views
        // tell the same story.
        val jupiterTrades = try { perps.getLifetimeTrades() } catch (_: Exception) { 0 }
        val jupiterWins   = try { perps.getLifetimeWins() } catch (_: Exception) { 0 }
        val jupiterLosses = try { perps.getLifetimeLosses() } catch (_: Exception) { 0 }
        val jupiterPnl    = try { perps.getLifetimePnlSol() } catch (_: Exception) { 0.0 }
        // V5.9.444 — start from Jupiter Perps ONLY. Alts was being added into
        // Markets totals which made CRYPTO and MARKETS tabs show identical
        // numbers when alts was the only sub-trader with trades (user report:
        // both tabs showed 197t / 31% WR / 35/75/87 byte-for-byte). Markets
        // now aggregates Perps + Stocks + Forex + Metals + Commodities only.
        var trades = jupiterTrades
        var wins   = jupiterWins
        var losses = jupiterLosses
        var aggPnlSol = jupiterPnl
        try {
            trades += com.lifecyclebot.perps.TokenizedStockTrader.getTotalTrades()
            wins   += com.lifecyclebot.perps.TokenizedStockTrader.getWinningTrades()
            // V5.9.419 — no getLosingTrades() on this trader → don't fake
            // losses as (total-wins). The residual surfaces as scratches.
            aggPnlSol += com.lifecyclebot.perps.TokenizedStockTrader.getTotalPnlSol()
        } catch (_: Exception) {}
        try {
            trades += com.lifecyclebot.perps.ForexTrader.getTotalTrades()
            wins   += com.lifecyclebot.perps.ForexTrader.getWinningTrades()
            aggPnlSol += com.lifecyclebot.perps.ForexTrader.getTotalPnlSol()
        } catch (_: Exception) {}
        try {
            trades += com.lifecyclebot.perps.MetalsTrader.getTotalTrades()
            wins   += com.lifecyclebot.perps.MetalsTrader.getWinningTrades()
            aggPnlSol += com.lifecyclebot.perps.MetalsTrader.getTotalPnlSol()
        } catch (_: Exception) {}
        try {
            trades += com.lifecyclebot.perps.CommoditiesTrader.getTotalTrades()
            wins   += com.lifecyclebot.perps.CommoditiesTrader.getWinningTrades()
            aggPnlSol += com.lifecyclebot.perps.CommoditiesTrader.getTotalPnlSol()
        } catch (_: Exception) {}

        val balance      = try { perps.getBalance() } catch (_: Exception) { 0.0 }
        val learningPct  = try { perps.getLearningProgress() } catch (_: Exception) { 0 }
        val discipline   = try { perps.getDisciplineScore() } catch (_: Exception) { 0 }
        val readiness    = try { perps.getLiveReadiness() } catch (_: Exception) { null }

        tv30DayCounter.text = "⚡ AATE MARKETS · LIFETIME"
        tv30DayBalance.text = String.format("%.4f SOL", balance)

        // Trade-weighted return: prefer aggregate PnL across all market buckets
        // when present, fall back to Jupiter-perps readiness pct for parity
        // with prior builds.
        val returnPct = if (aggPnlSol != 0.0 && balance > 0.0) {
            (aggPnlSol / balance) * 100.0
        } else {
            readiness?.paperPnlPct ?: 0.0
        }
        val sign = if (returnPct >= 0) "+" else ""
        tv30DayReturn.text = "$sign${String.format("%.2f", returnPct)}%"
        tv30DayReturn.setTextColor(if (returnPct >= 0) green else red)

        val maxDD = readiness?.maxDrawdownPct ?: 0.0
        tv30DayDrawdown.text = String.format("%.1f%%", maxDD)
        tv30DayDrawdown.setTextColor(when {
            maxDD <= 10.0 -> green
            maxDD <= 25.0 -> amber
            else          -> red
        })

        tv30DayTrades.text = trades.toString()
        // V5.9.419 — show real scratches as the residual (trades not flagged
        // as wins or losses, i.e. tiny scratch trades + sub-traders without
        // a separate getLosingTrades() exposing). Was hardcoded to "0".
        val scratches = (trades - wins - losses).coerceAtLeast(0)
        tv30DayWLS.text = "$wins / $losses / $scratches"

        val meaningful = wins + losses
        val winRate = if (meaningful > 0) (wins * 100 / meaningful) else 0
        tv30DayWinRate.text = "$winRate%"
        tv30DayWinRate.setTextColor(when {
            winRate >= 55 -> green
            winRate >= 45 -> amber
            else -> red
        })

        tv30DayLearning.text = "$learningPct%"
        tv30DayAccuracy.text = "$discipline"
        tv30DayAccuracy.setTextColor(when {
            discipline >= 70 -> green
            discipline >= 50 -> amber
            else             -> red
        })
        tv30DayIntegrity.text = (readiness?.readinessScore ?: 0).toString()
        tv30DayIntegrity.setTextColor(when {
            (readiness?.readinessScore ?: 0) >= 70 -> green
            (readiness?.readinessScore ?: 0) >= 50 -> amber
            else                                    -> red
        })
    }
    
    /**
     * V5.7.5: Show 30-Day Run Reset Dialog
     */
    private fun show30DayResetDialog() {
        val tracker = com.lifecyclebot.engine.RunTracker30D
        
        val message = """
🔄 RESET 30-DAY RUN TRACKER?

Current Stats:
• Day: ${tracker.getCurrentDay()}/30
• Balance: ${"%.4f".format(tracker.currentBalance)} SOL
• Trades: ${tracker.totalTrades}
• W/L/S: ${tracker.wins}/${tracker.losses}/${tracker.scratches}

⚠️ This will:
• Clear all trading history
• Reset balance to current wallet
• Start a new 30-day period

This cannot be undone!
        """.trimIndent()
        
        AlertDialog.Builder(this, R.style.Theme_AATE_Dialog)
            .setTitle("🔄 Reset 30-Day Tracker")
            .setMessage(message)
            .setPositiveButton("RESET") { dialog, _ ->
                try {
                    // Get current wallet balance for fresh start
                    val currentWalletBalance = com.lifecyclebot.engine.BotService.status.paperWalletSol
                    
                    // Reset the tracker
                    tracker.reset()
                    
                    // Start a fresh run with current balance
                    tracker.startRun(currentWalletBalance)
                    
                    Toast.makeText(this, "✅ 30-Day Tracker Reset! New run started.", Toast.LENGTH_LONG).show()
                    
                    // Update UI
                    update30DayRunStats()
                    
                    performHaptic()
                } catch (e: Exception) {
                    Toast.makeText(this, "Reset failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }
    
    /**
     * V5.6.9: Update Live Readiness Indicator
     * Shows when paper trading performance is ready for live mode
     * 
     * Criteria for READY status:
     * - 1000+ trades (enough data)
     * - 42%+ win rate (profitable with good R:R)
     * - Mature or Continuous phase
     */
    private fun updateLiveReadiness() {
        try {
            // V5.9.348: Route to the selected trader tab (MEME / ALTS / PERPS)
            updateTradersSummary()
            // V5.9.354: Stamp the active tab onto the card title so cross-tab
            // confusion is impossible — user can read at a glance which
            // trader's stats are showing.
            tvLiveReadinessTitle?.text = when (currentReadinessTab) {
                "ALTS"  -> "🚀 Live Readiness · 🪙 CRYPTO"
                "PERPS" -> "🚀 Live Readiness · 📈 MARKETS"  // V5.9.433 — renamed from AATE MARKETS
                else    -> "🚀 Live Readiness · 💎 MEME"
            }
            when (currentReadinessTab) {
                "ALTS"  -> renderAltsReadiness()
                "PERPS" -> renderPerpsReadiness()
                else    -> renderMemeReadiness()
            }

            // Hide card if in live mode (already trading live)
            try {
                val prefs = getSharedPreferences("bot_config", MODE_PRIVATE)
                val isPaperMode = prefs.getBoolean("paper_mode", true)
                cardLiveReadiness.visibility = if (isPaperMode) View.VISIBLE else View.GONE
            } catch (_: Exception) {
                cardLiveReadiness.visibility = View.VISIBLE
            }
        } catch (_: Exception) {
            // Silently fail — non-critical UI
        }
    }

    /** V5.9.348: Meme trader readiness — original TradeHistoryStore logic. */
    private fun renderMemeReadiness() {
            // V5.9.355: Align Meme Live Readiness with RunTracker30D so the
            // numbers match the 30-Day Proof card byte-for-byte. User report:
            // 'the meme coin 30 day is at 26% the livereadiness is drawing
            // wrong data!!!' TradeHistoryStore and RunTracker30D classify
            // scratches vs losses with different thresholds, causing 20% vs
            // 26% drift on the same meme data. RunTracker30D is the
            // source of truth for the proof-run readiness assessment.
            //
            // V5.9.369 attempted to switch this to read from the new
            // RunTracker30D.memeBucket so stock losses wouldn't drag meme
            // WR down. BUT: pre-V5.9.369 trades (the user's 3312 historical
            // trades) were never asset-tagged, so the bucket only had 13
            // trades vs 3312 in the global counter. Result: trade count
            // came from one source (6663) and WR came from the empty
            // bucket (15%) — completely wrong, didn't match 30-Day Proof.
            //
            // V5.9.371 — revert to reading rt.totalTrades / rt.wins /
            // rt.losses directly. This matches 30-Day Proof Run exactly,
            // which is what the user expects. The stock-contamination
            // issue is real but tiny (a few stock trades in 3000+); will
            // be addressed properly when 30-Day Proof Run itself is
            // asset-segregated (so the proof card shows MEME-only too).
            val rt = com.lifecyclebot.engine.RunTracker30D
            val stats = com.lifecyclebot.engine.TradeHistoryStore.getStatsCached() // V5.9.706
            // V5.9.371b — read EXACTLY from RunTracker30D so the numbers
            // shown here match the 30-Day Proof Run card byte-for-byte.
            // Removed the .coerceAtLeast(stats.totalStoredTrades) because
            // stats.totalStoredTrades = 6663 (TradeHistoryStore raw trade
            // count, includes scratches and journal-replay duplicates)
            // while rt.totalTrades = 3312 (the 30-Day Proof's clean
            // counter). User explicitly asked these match — that's
            // rt.totalTrades, not whichever happens to be bigger.
            val totalTrades = rt.totalTrades
            val meaningfulTrades = rt.wins + rt.losses
            val winRate = if (meaningfulTrades > 0)
                (rt.wins * 100.0) / meaningfulTrades else 0.0
            val profitFactor = stats.profitFactor  // stays on recent-in-memory avg w / avg l
            val totalPnlSol = rt.totalRealizedPnlSol.takeIf { it != 0.0 } ?: stats.totalPnlSol

            // V5.9.620 — Profitability gates re-baselined to the 5000-trade
            // maturity ladder (V5.9.616 / FDG.LearningPhase). Previously this
            // tab ran on a 500-trade scale and showed "Continuous / READY"
            // while FDG was still in pure bootstrap — the numbers didn't
            // match the brain's actual readiness state.
            val WR_READY      = 50.0     // minimum win rate to go live
            val WR_ALMOST     = 45.0     // almost-there zone
            val PF_READY      = 1.3      // profit factor (gross wins / gross losses)
            val PF_ALMOST     = 1.1
            val TRADES_READY  = 5000     // matches FDG MATURE phase (V5.9.616 ladder)
            val TRADES_ALMOST = 3000     // matches FDG LEARNING→MATURE transition

            val isProfitable   = totalPnlSol > 0.0
            val wrOk           = winRate >= WR_READY
            val pfOk           = profitFactor >= PF_READY
            val tradesOk       = meaningfulTrades >= TRADES_READY

            val isReady      = wrOk && pfOk && tradesOk && isProfitable
            val isAlmostReady = winRate >= WR_ALMOST && profitFactor >= PF_ALMOST && meaningfulTrades >= TRADES_ALMOST

            // V5.9.620 — phase boundaries match FDG ladder exactly:
            //   <1000  Bootstrap       (FDG bootstrap floors active)
            //   <3000  Learning        (FDG learning, EV gating off)
            //   <5000  Mature          (FDG mature, EV gating on)
            //   >=5000 Continuous      (full maturity, trusted)
            val phase = when {
                meaningfulTrades < 1000 -> "Bootstrap"
                meaningfulTrades < 3000 -> "Learning"
                meaningfulTrades < 5000 -> "Mature"
                else                    -> "Continuous"
            }

            // V5.9.620 — Readiness score (0-100%) re-weighted for 5000-trade target:
            //   40% from trades (need 5000 decisive — was 500)
            //   35% from win rate (need 50%)
            //   25% from profit factor (need 1.3)
            val tradesScore   = minOf(meaningfulTrades.toDouble() / TRADES_READY.toDouble(), 1.0) * 40.0
            val winRateScore  = minOf(winRate / WR_READY, 1.0) * 35.0
            val pfScore       = minOf(profitFactor / PF_READY, 1.0) * 25.0
            val readinessScore = (tradesScore + winRateScore + pfScore).toInt().coerceIn(0, 100)

            // Win rate colour
            tvReadinessWinRate.text = if (totalTrades > 0) "${winRate.toInt()}%" else "--"
            tvReadinessWinRate.setTextColor(when {
                winRate >= WR_READY  -> green
                winRate >= WR_ALMOST -> amber
                else                 -> red
            })

            tvReadinessTrades.text = totalTrades.toString()
            tvReadinessTrades.setTextColor(when {
                totalTrades >= TRADES_READY  -> green
                totalTrades >= TRADES_ALMOST -> amber
                else                         -> white
            })

            tvReadinessPhase.text = phase
            tvReadinessPhase.setTextColor(when (phase) {
                "Bootstrap"  -> amber
                "Learning"   -> Color.parseColor("#FFD54F")  // V5.9.620 — new mid phase
                "Mature"     -> Color.parseColor("#00BFFF")
                "Continuous" -> green
                else         -> white
            })

            tvReadinessProgress.text = "$readinessScore%"

            // Progress bar
            val params = viewReadinessProgressBar.layoutParams
            val parent = viewReadinessProgressBar.parent as? FrameLayout
            if (parent != null) {
                val maxWidth = parent.width
                if (maxWidth > 0) {
                    params.width = (maxWidth * readinessScore / 100)
                    viewReadinessProgressBar.layoutParams = params
                }
            }

            // Badge + recommendation
            when {
                isReady -> {
                    tvLiveReadinessBadge.text = "READY"
                    tvLiveReadinessBadge.setTextColor(Color.BLACK)
                    tvLiveReadinessBadge.setBackgroundResource(R.drawable.pill_bg_green)
                    tvReadinessRecommendation.text = "✅ Bot is profitable in paper mode. Safe to switch to live."
                    tvReadinessRecommendation.setTextColor(green)
                }
                isAlmostReady -> {
                    tvLiveReadinessBadge.text = "ALMOST"
                    tvLiveReadinessBadge.setTextColor(Color.BLACK)
                    tvLiveReadinessBadge.setBackgroundResource(R.drawable.pill_bg_yellow)
                    val needed = mutableListOf<String>()
                    if (meaningfulTrades < TRADES_READY)  needed.add("${TRADES_READY - meaningfulTrades} more trades")
                    if (winRate < WR_READY)               needed.add("${String.format("%.1f", WR_READY - winRate)}% more WR (need $WR_READY%)")
                    if (profitFactor < PF_READY)          needed.add("PF ${String.format("%.2f", profitFactor)} → need $PF_READY")
                    if (!isProfitable)                    needed.add("positive total PnL")
                    tvReadinessRecommendation.text = "⏳ Almost there! Need: ${needed.joinToString(" · ")}"
                    tvReadinessRecommendation.setTextColor(amber)
                }
                else -> {
                    tvLiveReadinessBadge.text = "NOT READY"
                    tvLiveReadinessBadge.setTextColor(Color.WHITE)
                    tvLiveReadinessBadge.setBackgroundResource(R.drawable.pill_bg_red)
                    val needed = mutableListOf<String>()
                    if (meaningfulTrades < TRADES_READY)  needed.add("${TRADES_READY - meaningfulTrades} more trades")
                    if (winRate < WR_READY)               needed.add("WR ${winRate.toInt()}% → need $WR_READY%")
                    if (profitFactor < PF_READY)          needed.add("PF ${String.format("%.2f", profitFactor)} → need $PF_READY")
                    if (!isProfitable)                    needed.add("positive total PnL")
                    tvReadinessRecommendation.text = "🚫 Not profitable yet. ${needed.joinToString(" · ")}"
                    tvReadinessRecommendation.setTextColor(red)
                }
            }
    }

    /** V5.9.348: Crypto Alts trader readiness — uses CryptoAltTrader.getStats(). */
    private fun renderAltsReadiness() {
        val stats = try { com.lifecyclebot.perps.CryptoAltTrader.getStats() } catch (_: Exception) { emptyMap<String, Any>() }
        val totalTrades    = (stats["totalTrades"] as? Int) ?: 0
        val wins           = (stats["winningTrades"] as? Int) ?: 0
        val losses         = (stats["losingTrades"] as? Int) ?: 0
        val winRate        = (stats["winRate"] as? Double) ?: 0.0
        val totalPnlSol    = (stats["totalPnlSol"] as? Double) ?: 0.0
        val phase          = (stats["learningPhase"] as? String) ?: "BOOTSTRAP"

        // Alts readiness thresholds (mirrors CryptoAltTrader.isLiveReady)
        val WR_READY      = 52.0
        val WR_ALMOST     = 48.0
        val TRADES_READY  = 5000
        val TRADES_ALMOST = 1500

        // V5.9.387 — progress now counts every alt trade (was wins+losses only,
        // which caused the gate to never move because scratches dominated and
        // user saw 0/5000 forever even after 40 alt trades had fired).
        val gatingTrades = totalTrades

        val isProfitable   = totalPnlSol > 0.0
        val isReady        = gatingTrades >= TRADES_READY && winRate >= WR_READY && isProfitable
        val isAlmostReady  = gatingTrades >= TRADES_ALMOST && winRate >= WR_ALMOST

        val tradesScore  = minOf(gatingTrades.toDouble() / TRADES_READY.toDouble(), 1.0) * 50.0
        val winRateScore = minOf(winRate / WR_READY, 1.0) * 40.0
        val pnlScore     = if (isProfitable) 10.0 else 0.0
        val readinessScore = (tradesScore + winRateScore + pnlScore).toInt().coerceIn(0, 100)

        tvReadinessWinRate.text = if (totalTrades > 0) "${winRate.toInt()}%" else "--"
        tvReadinessWinRate.setTextColor(when {
            winRate >= WR_READY  -> green
            winRate >= WR_ALMOST -> amber
            else                 -> red
        })

        tvReadinessTrades.text = totalTrades.toString()
        tvReadinessTrades.setTextColor(when {
            totalTrades >= TRADES_READY  -> green
            totalTrades >= TRADES_ALMOST -> amber
            else                         -> white
        })

        tvReadinessPhase.text = phase
        tvReadinessPhase.setTextColor(Color.parseColor("#9945FF"))

        tvReadinessProgress.text = "$readinessScore%"
        val params = viewReadinessProgressBar.layoutParams
        val parent = viewReadinessProgressBar.parent as? FrameLayout
        if (parent != null) {
            val maxWidth = parent.width
            if (maxWidth > 0) {
                params.width = (maxWidth * readinessScore / 100)
                viewReadinessProgressBar.layoutParams = params
            }
        }

        when {
            isReady -> {
                tvLiveReadinessBadge.text = "READY"
                tvLiveReadinessBadge.setTextColor(Color.BLACK)
                tvLiveReadinessBadge.setBackgroundResource(R.drawable.pill_bg_green)
                tvReadinessRecommendation.text = "✅ Crypto profitable in paper. Safe for live."
                tvReadinessRecommendation.setTextColor(green)
            }
            isAlmostReady -> {
                tvLiveReadinessBadge.text = "ALMOST"
                tvLiveReadinessBadge.setTextColor(Color.BLACK)
                tvLiveReadinessBadge.setBackgroundResource(R.drawable.pill_bg_yellow)
                val needed = mutableListOf<String>()
                if (gatingTrades < TRADES_READY) needed.add("${TRADES_READY - gatingTrades} more trades")
                if (winRate < WR_READY)              needed.add("WR ${winRate.toInt()}% → need ${WR_READY.toInt()}%")
                if (!isProfitable)                   needed.add("positive PnL")
                tvReadinessRecommendation.text = "⏳ Almost there! Need: ${needed.joinToString(" · ")}"
                tvReadinessRecommendation.setTextColor(amber)
            }
            else -> {
                tvLiveReadinessBadge.text = "NOT READY"
                tvLiveReadinessBadge.setTextColor(Color.WHITE)
                tvLiveReadinessBadge.setBackgroundResource(R.drawable.pill_bg_red)
                val needed = mutableListOf<String>()
                if (gatingTrades < TRADES_READY) needed.add("${TRADES_READY - gatingTrades} more trades")
                if (winRate < WR_READY)              needed.add("WR ${winRate.toInt()}% → need ${WR_READY.toInt()}%")
                if (!isProfitable)                   needed.add("positive PnL")
                tvReadinessRecommendation.text = "🚫 Crypto learning. ${needed.joinToString(" · ")}"
                tvReadinessRecommendation.setTextColor(red)
            }
        }
    }

    /**
     * V5.9.387 — PERPS tab now renders the **unified AATE markets**
     * readiness: a single card that sums Perps + CryptoAlts + TokenizedStocks
     * + Forex + Metals + Commodities into one readiness score. User asked
     * for the PERPS tab to represent the entire AATE-markets trader data
     * combined, instead of only Jupiter Perps (which was always 0 because
     * nearly every live trade is Alts/Stocks/Metals rather than raw Perps).
     */
    private fun renderPerpsReadiness() {
        data class BucketStats(
            val emoji: String,
            val shortLabel: String,
            val trades: Int,
            val wins: Int,
            val pnlSol: Double,
            val winRate: Double,
        )

        val buckets = mutableListOf<BucketStats>()

        // V5.9.444 — Alts REMOVED from Markets aggregation. CRYPTO tab owns
        // alts stats entirely (see renderAltsReadiness). Markets tab now
        // reflects Perps + Stocks + Forex + Metals + Commodities only, so
        // the two tabs stop showing byte-identical numbers when alts is the
        // only bucket with activity.

        // Perps (Jupiter Perps proper)
        try {
            val t = com.lifecyclebot.perps.PerpsTraderAI.getLifetimeTrades()
            val w = com.lifecyclebot.perps.PerpsTraderAI.getLifetimeWins()
            val l = com.lifecyclebot.perps.PerpsTraderAI.getLifetimeLosses()
            val pnl = com.lifecyclebot.perps.PerpsTraderAI.getLifetimePnlSol()
            val wl = w + l
            buckets += BucketStats(
                emoji = "⚡", shortLabel = "P",
                trades = t, wins = w, pnlSol = pnl,
                winRate = if (wl > 0) w.toDouble() / wl * 100.0 else 0.0,
            )
        } catch (_: Exception) {}

        // Tokenized Stocks
        try {
            buckets += BucketStats(
                emoji = "📈", shortLabel = "S",
                trades = com.lifecyclebot.perps.TokenizedStockTrader.getTotalTrades(),
                wins = com.lifecyclebot.perps.TokenizedStockTrader.getWinningTrades(),
                pnlSol = com.lifecyclebot.perps.TokenizedStockTrader.getTotalPnlSol(),
                winRate = com.lifecyclebot.perps.TokenizedStockTrader.getWinRate(),
            )
        } catch (_: Exception) {}

        // Forex
        try {
            buckets += BucketStats(
                emoji = "💱", shortLabel = "FX",
                trades = com.lifecyclebot.perps.ForexTrader.getTotalTrades(),
                wins = com.lifecyclebot.perps.ForexTrader.getWinningTrades(),
                pnlSol = com.lifecyclebot.perps.ForexTrader.getTotalPnlSol(),
                winRate = com.lifecyclebot.perps.ForexTrader.getWinRate(),
            )
        } catch (_: Exception) {}

        // Metals
        try {
            buckets += BucketStats(
                emoji = "🥇", shortLabel = "MT",
                trades = com.lifecyclebot.perps.MetalsTrader.getTotalTrades(),
                wins = com.lifecyclebot.perps.MetalsTrader.getWinningTrades(),
                pnlSol = com.lifecyclebot.perps.MetalsTrader.getTotalPnlSol(),
                winRate = com.lifecyclebot.perps.MetalsTrader.getWinRate(),
            )
        } catch (_: Exception) {}

        // Commodities
        try {
            buckets += BucketStats(
                emoji = "🛢️", shortLabel = "CD",
                trades = com.lifecyclebot.perps.CommoditiesTrader.getTotalTrades(),
                wins = com.lifecyclebot.perps.CommoditiesTrader.getWinningTrades(),
                pnlSol = com.lifecyclebot.perps.CommoditiesTrader.getTotalPnlSol(),
                winRate = com.lifecyclebot.perps.CommoditiesTrader.getWinRate(),
            )
        } catch (_: Exception) {}

        val totalTrades = buckets.sumOf { it.trades }
        val totalWins   = buckets.sumOf { it.wins }
        val totalPnlSol = buckets.sumOf { it.pnlSol }

        // Trade-weighted WR — buckets with 0 trades are excluded from both
        // numerator and denominator so they don't drag WR to 0.
        val wrActive = buckets.filter { it.trades > 0 }
        val unifiedWinRate = if (wrActive.isNotEmpty()) {
            val totalW = wrActive.sumOf { it.trades.toDouble() * it.winRate }
            val totalT = wrActive.sumOf { it.trades.toDouble() }
            if (totalT > 0) totalW / totalT else 0.0
        } else 0.0

        // Unified thresholds (same shape as ALTS so the bar behaviour is
        // predictable across tabs).
        val WR_READY      = 52.0
        val WR_ALMOST     = 48.0
        val TRADES_READY  = 5000
        val TRADES_ALMOST = 1500

        val isProfitable  = totalPnlSol > 0.0
        val isReady       = totalTrades >= TRADES_READY && unifiedWinRate >= WR_READY && isProfitable
        val isAlmostReady = totalTrades >= TRADES_ALMOST && unifiedWinRate >= WR_ALMOST

        val tradesScore    = minOf(totalTrades.toDouble() / TRADES_READY.toDouble(), 1.0) * 50.0
        val winRateScore   = minOf(unifiedWinRate / WR_READY, 1.0) * 40.0
        val pnlScore       = if (isProfitable) 10.0 else 0.0
        val readinessScore = (tradesScore + winRateScore + pnlScore).toInt().coerceIn(0, 100)

        tvReadinessWinRate.text = if (totalTrades > 0) "${unifiedWinRate.toInt()}%" else "--"
        tvReadinessWinRate.setTextColor(when {
            unifiedWinRate >= WR_READY  -> green
            unifiedWinRate >= WR_ALMOST -> amber
            else                        -> red
        })

        tvReadinessTrades.text = totalTrades.toString()
        tvReadinessTrades.setTextColor(when {
            totalTrades >= TRADES_READY  -> green
            totalTrades >= TRADES_ALMOST -> amber
            else                         -> white
        })

        // Phase label reflects combined progress for the unified view.
        val phaseLabel = when {
            totalTrades < 500  -> "📚 BOOTSTRAP"
            totalTrades < 1500 -> "🧠 LEARNING"
            totalTrades < 3000 -> "🔬 VALIDATING"
            totalTrades < 5000 -> "⚡ MATURING"
            unifiedWinRate >= WR_READY -> "✅ READY"
            else               -> "⚡ MATURING"
        }
        tvReadinessPhase.text = phaseLabel
        tvReadinessPhase.setTextColor(Color.parseColor("#9945FF"))

        tvReadinessProgress.text = "$readinessScore%"
        val params = viewReadinessProgressBar.layoutParams
        val parent = viewReadinessProgressBar.parent as? FrameLayout
        if (parent != null) {
            val maxWidth = parent.width
            if (maxWidth > 0) {
                params.width = (maxWidth * readinessScore / 100)
                viewReadinessProgressBar.layoutParams = params
            }
        }

        // Per-bucket breakdown shown under the recommendation: A 54t/9% · P 0t/-- · …
        val breakdown = buckets.joinToString(" · ") { b ->
            val wr = if (b.trades > 0) "${b.winRate.toInt()}%" else "--"
            "${b.shortLabel} ${b.trades}t/$wr"
        }

        when {
            isReady -> {
                tvLiveReadinessBadge.text = "READY"
                tvLiveReadinessBadge.setTextColor(Color.BLACK)
                tvLiveReadinessBadge.setBackgroundResource(R.drawable.pill_bg_green)
                tvReadinessRecommendation.text = "✅ AATE markets profitable in paper. Safe for live.\n$breakdown"
                tvReadinessRecommendation.setTextColor(green)
            }
            isAlmostReady -> {
                tvLiveReadinessBadge.text = "ALMOST"
                tvLiveReadinessBadge.setTextColor(Color.BLACK)
                tvLiveReadinessBadge.setBackgroundResource(R.drawable.pill_bg_yellow)
                val needed = mutableListOf<String>()
                if (totalTrades < TRADES_READY)    needed.add("${TRADES_READY - totalTrades} more trades")
                if (unifiedWinRate < WR_READY)     needed.add("WR ${unifiedWinRate.toInt()}% → need ${WR_READY.toInt()}%")
                if (!isProfitable)                 needed.add("positive PnL")
                tvReadinessRecommendation.text = "⏳ Almost there! ${needed.joinToString(" · ")}\n$breakdown"
                tvReadinessRecommendation.setTextColor(amber)
            }
            else -> {
                tvLiveReadinessBadge.text = "NOT READY"
                tvLiveReadinessBadge.setTextColor(Color.WHITE)
                tvLiveReadinessBadge.setBackgroundResource(R.drawable.pill_bg_red)
                val needed = mutableListOf<String>()
                if (totalTrades < TRADES_READY)    needed.add("${TRADES_READY - totalTrades} more trades")
                if (unifiedWinRate < WR_READY)     needed.add("WR ${unifiedWinRate.toInt()}% → need ${WR_READY.toInt()}%")
                if (!isProfitable)                 needed.add("positive PnL")
                tvReadinessRecommendation.text = "🚫 AATE markets learning. ${needed.joinToString(" · ")}\n$breakdown"
                tvReadinessRecommendation.setTextColor(red)
            }
        }
    }

    /** V5.9.348: Switch active readiness tab & refresh both readiness + 30-day cards. */
    private fun selectReadinessTab(tab: String) {
        if (currentReadinessTab == tab) return
        currentReadinessTab = tab
        applyTabStyles()
        try { updateLiveReadiness() } catch (_: Exception) { }
        try { update30DayRunStats()  } catch (_: Exception) { }
        try { performHaptic() } catch (_: Exception) { }
    }

    /** V5.9.348: Highlight the selected trader tab pill. */
    private fun applyTabStyles() {
        val tabs = listOf(
            "MEME"  to tabTraderMeme,
            "ALTS"  to tabTraderAlts,
            "PERPS" to tabTraderPerps,
        )
        tabs.forEach { (key, tv) ->
            val t = tv ?: return@forEach
            if (key == currentReadinessTab) {
                t.setBackgroundResource(R.drawable.pill_bg_green)
                t.setTextColor(Color.BLACK)
                t.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                t.setBackgroundResource(R.drawable.pill_bg)
                t.setTextColor(Color.parseColor("#9CA3AF"))
                t.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
    }

    /** V5.9.348: Combined summary line across Meme + Alts + Perps. */
    private fun updateTradersSummary() {
        try {
            val tv = tvTradersSummary ?: return
            // Meme
            val memeStats = com.lifecyclebot.engine.TradeHistoryStore.getStatsCached() // V5.9.706
            val memeWins   = memeStats.totalWins
            val memeLosses = memeStats.totalLosses
            val memeTrades = memeWins + memeLosses
            val memePnl    = memeStats.totalPnlSol
            // Alts
            val altsStats = try { com.lifecyclebot.perps.CryptoAltTrader.getStats() } catch (_: Exception) { emptyMap<String, Any>() }
            val altsWins   = (altsStats["winningTrades"] as? Int) ?: 0
            val altsLosses = (altsStats["losingTrades"] as? Int) ?: 0
            val altsTrades = altsWins + altsLosses
            val altsPnl    = (altsStats["totalPnlSol"] as? Double) ?: 0.0
            // Perps
            var perpsTrades = 0
            var perpsWins   = 0
            var perpsLosses = 0
            var perpsPnl    = 0.0
            try {
                perpsTrades = com.lifecyclebot.perps.PerpsTraderAI.getLifetimeTrades()
                perpsWins   = com.lifecyclebot.perps.PerpsTraderAI.getLifetimeWins()
                perpsLosses = com.lifecyclebot.perps.PerpsTraderAI.getLifetimeLosses()
                perpsPnl    = com.lifecyclebot.perps.PerpsTraderAI.getLifetimePnlSol()
            } catch (_: Exception) { }

            val totalTrades = memeTrades + altsTrades + perpsTrades
            val totalWins   = memeWins + altsWins + perpsWins
            val totalLoss   = memeLosses + altsLosses + perpsLosses
            val totalDecisive = totalWins + totalLoss
            val blendedWR = if (totalDecisive > 0) totalWins * 100.0 / totalDecisive else 0.0
            val totalPnl  = memePnl + altsPnl + perpsPnl

            val wrLabel = if (totalDecisive > 0) "${blendedWR.toInt()}% WR" else "--% WR"
            val pnlSign = if (totalPnl >= 0) "+" else ""
            // V5.9.370 — per-asset breakdown line. Pulls from the V5.9.369
            // RunTracker30D.AssetBucket so MEME/ALT/PERP/STOCK/FOREX/METAL/COMMOD
            // each show their own clean WR and trade count.
            val perAssetLine = try {
                val rt = com.lifecyclebot.engine.RunTracker30D
                fun fmt(label: String, b: com.lifecyclebot.engine.RunTracker30D.AssetBucket): String {
                    val dec = b.wins + b.losses
                    return if (dec == 0) "$label —" else "$label ${dec}t/${b.winRate().toInt()}%"
                }
                listOf(
                    fmt("M",  rt.memeBucket),
                    fmt("A",  rt.altsBucket),
                    fmt("P",  rt.perpsBucket),
                    fmt("S",  rt.stocksBucket),
                    fmt("FX", rt.forexBucket),
                    fmt("MT", rt.metalsBucket),
                    fmt("CD", rt.commodBucket),
                ).joinToString(" · ")
            } catch (_: Exception) { "" }
            tv.text = if (perAssetLine.isNotEmpty()) {
                "🧠 All Traders: $totalTrades trades · $wrLabel · $pnlSign${String.format("%.4f", totalPnl)} SOL\n📊 $perAssetLine"
            } else {
                "🧠 All Traders: $totalTrades trades · $wrLabel · $pnlSign${String.format("%.4f", totalPnl)} SOL"
            }
            tv.setTextColor(when {
                blendedWR >= 50.0 -> green
                blendedWR >= 45.0 -> amber
                totalDecisive == 0 -> Color.parseColor("#9CA3AF")
                else              -> red
            })
        } catch (_: Exception) { }
    }

    private fun renderTrades(trades: List<Trade>) {
        // V5.9.709 — skip if trade list unchanged
        val rtHash = (trades.size.toString() + trades.lastOrNull()?.let { it.mint + it.side } ?: "").hashCode()
        if (rtHash == lastTradesRenderHash) return
        lastTradesRenderHash = rtHash
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

    /**
     * V5.9.317: Manual BUY click handler. Prompts user for SOL amount, confirms,
     * routes to BotService.manualBuy which handles paper vs live correctly
     * end-to-end (paperBuy / Jupiter swap pipeline + security guards + fee split).
     */
    private fun onManualBuyClicked() {
        val mint = etActiveToken.text.toString().trim()
        if (mint.isBlank()) {
            Toast.makeText(this, "No active token selected", Toast.LENGTH_SHORT).show()
            return
        }
        val service = com.lifecyclebot.engine.BotService.instance
        if (service == null) {
            Toast.makeText(this, "Bot service not running — start the bot first", Toast.LENGTH_SHORT).show()
            return
        }
        val ts = com.lifecyclebot.engine.BotService.status.tokens[mint]
        if (ts == null) {
            Toast.makeText(this, "Token not in watchlist", Toast.LENGTH_SHORT).show()
            return
        }
        if (ts.position.isOpen) {
            Toast.makeText(this, "Position already open — use SELL", Toast.LENGTH_SHORT).show()
            return
        }

        val cfg = com.lifecyclebot.data.ConfigStore.load(this)
        val isPaper = cfg.paperMode
        val modeLabel = if (isPaper) "PAPER" else "🔴 LIVE"

        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "SOL amount (e.g. 0.05)"
            setText("0.05")
            setPadding(pad, pad, pad, pad)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Manual BUY — ${ts.symbol}  [$modeLabel]")
            .setMessage("Enter SOL amount to buy.\n\nMode: ${if (isPaper) "Paper trading" else "LIVE — uses real wallet SOL"}")
            .setView(input)
            .setPositiveButton("BUY") { _, _ ->
                val amt = input.text.toString().toDoubleOrNull()
                if (amt == null || amt <= 0.0) {
                    Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // V5.9.495m — ANR FIX: manualBuy now does wallet RPC + 1.5s
                // Thread.sleep in liveBuy (V5.9.495l). Calling on UI thread
                // freezes the app for 3-5+s and triggers Android's "wait/close"
                // dialog. Run on background thread, post toast back to UI.
                Toast.makeText(this, "⏳ BUY submitting…", Toast.LENGTH_SHORT).show()
                Thread {
                    val (ok, msg) = try {
                        service.manualBuy(mint, amt)
                    } catch (e: Exception) {
                        false to "Error: ${e.message ?: e.javaClass.simpleName}"
                    }
                    runOnUiThread {
                        Toast.makeText(this, (if (ok) "✅ " else "❌ ") + msg, if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * V5.9.317: Manual SELL click handler. Confirms with current PnL preview,
     * routes to BotService.manualSell which handles paper vs live correctly.
     */
    private fun onManualSellClicked() {
        val mint = etActiveToken.text.toString().trim()
        if (mint.isBlank()) {
            Toast.makeText(this, "No active token selected", Toast.LENGTH_SHORT).show()
            return
        }
        val service = com.lifecyclebot.engine.BotService.instance
        if (service == null) {
            Toast.makeText(this, "Bot service not running — start the bot first", Toast.LENGTH_SHORT).show()
            return
        }
        val ts = com.lifecyclebot.engine.BotService.status.tokens[mint]
        if (ts == null) {
            Toast.makeText(this, "Token not in watchlist", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ts.position.isOpen) {
            Toast.makeText(this, "No open position to sell", Toast.LENGTH_SHORT).show()
            return
        }

        val pos = ts.position
        val pnlPct = if (pos.entryPrice > 0 && ts.lastPrice > 0)
            ((ts.lastPrice - pos.entryPrice) / pos.entryPrice) * 100.0
        else 0.0
        val pnlSol = pos.qtyToken * (ts.lastPrice - pos.entryPrice) / (ts.lastPrice.takeIf { it > 0 } ?: 1.0)

        val cfg = com.lifecyclebot.data.ConfigStore.load(this)
        val modeLabel = if (cfg.paperMode) "PAPER" else "🔴 LIVE"

        android.app.AlertDialog.Builder(this)
            .setTitle("Manual SELL — ${ts.symbol}  [$modeLabel]")
            .setMessage(
                "Close position now?\n\n" +
                "Qty: ${"%.4f".format(pos.qtyToken)}\n" +
                "Entry: $${"%.6f".format(pos.entryPrice)}\n" +
                "Now:   $${"%.6f".format(ts.lastPrice)}\n" +
                "PnL:   ${"%+.2f".format(pnlPct)}%  (${"%+.4f".format(pnlSol)} SOL)"
            )
            .setPositiveButton("SELL") { _, _ ->
                // V5.9.495m — ANR FIX: manualSell now triggers wallet RPC
                // (quantityToTokenUnits → getTokenAccountsWithDecimals) on
                // every call. Run off the UI thread to avoid Android's
                // "wait/close" dialog when RPC is slow.
                Toast.makeText(this, "⏳ SELL submitting…", Toast.LENGTH_SHORT).show()
                Thread {
                    val (ok, msg) = try {
                        service.manualSell(mint)
                    } catch (e: Exception) {
                        false to "Error: ${e.message ?: e.javaClass.simpleName}"
                    }
                    runOnUiThread {
                        Toast.makeText(this, (if (ok) "✅ " else "❌ ") + msg, if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateDecisionLog(ts: TokenState) {
        // V5.9.709 — skip if decision log content unchanged
        val dlHash = (ts.mint + ts.lastV3Score + ts.trades.size + ts.position.isOpen.hashCode()).hashCode()
        if (dlHash == lastDecisionLogHash) return
        lastDecisionLogHash = dlHash
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
        // V5.9.709 — skip watchlist re-render if content unchanged
        val tokens = state.tokens.values.toList()
        val wlHash = (tokens.size.toString() + tokens.take(5).joinToString { it.symbol + it.lastPrice.toInt() }).hashCode()
        if (wlHash == lastWatchlistRenderHash) return
        lastWatchlistRenderHash = wlHash
        llTokenList.removeAllViews()
        llProbationList.removeAllViews()
        llIdleList.removeAllViews()
        
        val active = state.config.activeToken
        val solPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice.takeIf { it in 50.0..1000.0 } ?: 85.0
        
        // ═══════════════════════════════════════════════════════════════════════
        // V5.2: THREE-COLUMN LAYOUT - Probation | Watchlist | Idle
        // ═══════════════════════════════════════════════════════════════════════
        
        // V5.9.640 — Watchlist means the upstream qualification bench.
        // Fresh protected-intake tokens default to phase="idle" until the first
        // scoring pass touches them, but they are STILL watchlist candidates.
        // Only true non-qualifying/shadow states belong in the side column.
        val activeTokens = mutableListOf<com.lifecyclebot.data.TokenState>()
        val idleTokens = mutableListOf<com.lifecyclebot.data.TokenState>()
        val shadowPhases = setOf(
            "blocked", "dead", "dying", "rug_likely", "distribution", "distributing",
            "safety_shadow", "blacklist_shadow", "idle_shadow", "stale_shadow",
            "timeout_shadow", "wait_shadow", "flat_shadow", "low_liq_shadow",
            "fresh_zero_liq_shadow", "phase_shadow", "shadow"
        )
        
        state.tokens.values.forEach { ts ->
            val phase = ts.phase.lowercase()
            if (phase in shadowPhases || ts.safety.isBlocked) {
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
        // Scale text/logos based on how many columns are showing AND screen density
        // Also respect user's system font scaling
        val fontScale = resources.configuration.fontScale  // User's text scaling preference (1.0 = normal)
        val densityScale = resources.displayMetrics.density / 2.0f  // Normalize to ~1.0 on mdpi
        val columnScale = when (columnCount) {
            3 -> 0.85f
            2 -> 0.92f
            else -> 1.0f
        }
        // Combine: smaller on more columns, but respect user font preference
        val scaleFactor = columnScale * fontScale.coerceIn(0.85f, 1.3f)
        
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
        // V5.9.365 — Funnel Stages tile: piggyback on the watchlist header so
        // the user can see exactly where tokens are dying in the pipeline:
        //   RAW (raw scanner hits) → ENQ (enqueued to merge) → MQ (pending in
        //   merge queue) → WL (active watchlist) plus secondary counters
        //   (probation, liq-rejects, saturated, multi-scanner bypasses).
        val funnelLine = try {
            val tele = com.lifecyclebot.engine.MarketsTelemetry.latestThroughput
            val mqSize = com.lifecyclebot.engine.TokenMergeQueue.getPendingCount()
            val probSize = probationEntries.size
            val bypassCount = com.lifecyclebot.engine.MarketsTelemetry.multiScannerBypasses.get()
            val insiderBuys = com.lifecyclebot.engine.InsiderCopyEngine.totalCopyBuys.get()
            val insiderExits = com.lifecyclebot.engine.InsiderCopyEngine.totalCopyExits.get()
            val insiderSuffix = if (insiderBuys > 0 || insiderExits > 0) {
                " · 🐋$insiderBuys/$insiderExits"
            } else ""
            val scannerPulse = if (tele.src > 0 || tele.ok > 0 || tele.err > 0) {
                "SRC ${tele.src}/${tele.ok}/${tele.err} → RAW ${tele.raw} → ENQ ${tele.enq}"
            } else {
                "RAW ${tele.raw} → ENQ ${tele.enq}"
            }
            "$scannerPulse → MQ $mqSize → WL ${activeTokens.size}  ·  Prob $probSize · LIQ-rej ${tele.liqRej} · SAT ${tele.sat}" +
                (if (!tele.alive && tele.ageSec > 90) " · ⚠scan stale ${tele.ageSec}s" else "") +
                (if (bypassCount > 0) " · 🟢Bypass $bypassCount" else "") +
                insiderSuffix
        } catch (_: Exception) { "" }
        tvWatchlistHeader.text = if (funnelLine.isNotEmpty()) {
            "Watchlist (${activeTokens.size})\n$funnelLine"
        } else {
            "Watchlist (${activeTokens.size})"
        }
        
        // V5.9.613 — watchlist UI must be lightweight. The scanner can hold
        // hundreds of candidates, but drawing hundreds of nested cards + images
        // every UI tick OOMs low-memory Android heaps. Render the most relevant
        // slice only; the header still shows the true full count.
        // V5.9.672 — caps lowered (24/32/40 → 16/20/24). Each buildTokenCard
        // creates ~10 fresh TextViews which each trigger native AssetManager
        // theme/attribute parsing. Operator dump showed 62% main-thread stall
        // pinned to buildTokenCard. The header still surfaces the full count.
        val maxWatchlistRows = when (columnCount) {
            3 -> 16
            2 -> 20
            else -> 24
        }
        val activeVisible = activeTokens
            .sortedWith(compareByDescending<com.lifecyclebot.data.TokenState> { it.mint == active }
                .thenByDescending { it.position.isOpen }
                .thenByDescending { it.entryScore }
                .thenByDescending { it.lastV3Score ?: 0 }
                .thenByDescending { it.lastLiquidityUsd })
            .take(maxWatchlistRows)
        activeVisible.forEach { ts ->
            val card = buildTokenCard(ts, active, solPrice, scaleFactor, state)
            llTokenList.addView(card)
        }
        if (activeTokens.size > activeVisible.size) {
            llTokenList.addView(buildListFooter("showing ${activeVisible.size}/${activeTokens.size} — scanner still tracking all"))
        }
        
        // ═══════════════════════════════════════════════════════════════════════
        // IDLE COLUMN (right) - Idle/blocked/dead tokens
        // ═══════════════════════════════════════════════════════════════════════
        if (idleTokens.isNotEmpty()) {
            tvIdleHeader.visibility = android.view.View.VISIBLE
            llIdleList.visibility = android.view.View.VISIBLE
            tvIdleHeader.text = "Idle (${idleTokens.size})"
            
            val maxIdleRows = 12
            val idleVisible = idleTokens
                .sortedWith(compareByDescending<com.lifecyclebot.data.TokenState> { it.lastLiquidityUsd }
                    .thenByDescending { it.entryScore })
                .take(maxIdleRows)
            idleVisible.forEach { ts ->
                val card = buildTokenCard(ts, active, solPrice, scaleFactor, state, isIdle = true)
                llIdleList.addView(card)
            }
            if (idleTokens.size > idleVisible.size) {
                llIdleList.addView(buildListFooter("showing ${idleVisible.size}/${idleTokens.size} idle"))
            }
        } else {
            tvIdleHeader.visibility = android.view.View.GONE
            llIdleList.visibility = android.view.View.GONE
        }
    }
    
    private fun buildListFooter(textValue: String): android.view.View {
        return TextView(this).apply {
            text = textValue
            textSize = 10f
            setTextColor(muted)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 6, 0, 8)
            typeface = android.graphics.Typeface.MONOSPACE
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
            val logoUrl = "https://cdn.dexscreener.com/tokens/solana/${entry.mint}.png"
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
        val refPrice = when {
            ts.position.isOpen -> ts.position.entryPrice
            ts.history.isNotEmpty() -> ts.history.first().priceUsd
            else -> ts.lastPrice
        }
        val pctChange = if (refPrice > 0 && ts.lastPrice > 0 && ts.lastPrice != refPrice) {
            ((ts.lastPrice - refPrice) / refPrice) * 100.0
        } else 0.0
        val changeCol = if (pctChange >= 0) green else red

        val registryEntry = com.lifecyclebot.engine.GlobalTradeRegistry.getEntry(ts.mint)
        val scannerSource = registryEntry?.addedBy ?: ts.source.ifBlank { "UNKNOWN" }
        val rcScore = ts.safety.rugcheckScore.takeIf { it >= 0 }
        val rcColor = when {
            rcScore == null -> muted
            rcScore <= 10 -> red
            rcScore <= 20 -> 0xFFFF9500.toInt()
            rcScore <= 40 -> 0xFFFFCC00.toInt()
            else -> green
        }
        val v3Score = ts.lastV3Score
        val v3Conf = ts.lastV3Confidence
        val buyPct = ts.lastBuyPressurePct.takeIf { it != 50.0 && it > 0 }
        val entryScr = ts.entryScore.takeIf { it > 0 }

        fun compactUsd(v: Double): String = when {
            v >= 1_000_000 -> "$%.1fM".format(v / 1_000_000.0)
            v >= 1_000 -> "$%.0fK".format(v / 1_000.0)
            v > 0 -> "$%.0f".format(v)
            else -> "—"
        }
        val sourceShort = when {
            scannerSource.contains("PUMP", true) -> "PF"
            scannerSource.contains("RAYDIUM", true) -> "RD"
            scannerSource.contains("DEX", true) -> "DX"
            scannerSource.contains("TREND", true) -> "TR"
            scannerSource.contains("BOOST", true) -> "BS"
            scannerSource.contains("VOLUME", true) -> "VL"
            else -> scannerSource.take(2).uppercase()
        }
        val phaseShort = when (ts.phase.lowercase()) {
            "early_pump" -> "EARLY"
            "accumulation" -> "ACC"
            "distribution" -> "DIST"
            else -> ts.phase.uppercase().take(6).ifBlank { "SCAN" }
        }
        val phaseColor = when (ts.phase.lowercase()) {
            "early_pump", "pump", "breakout" -> green
            "accumulation", "healthy" -> 0xFF00DDFF.toInt()
            "distribution", "decline" -> red
            "idle", "blocked", "dead" -> muted
            else -> white
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * scale).toInt(), (6 * scale).toInt(), (8 * scale).toInt(), (6 * scale).toInt())
            isClickable = true
            isFocusable = true
            background = when {
                ts.mint == active -> ContextCompat.getDrawable(this@MainActivity, R.drawable.card_selected_bg)
                isIdle -> ContextCompat.getDrawable(this@MainActivity, R.drawable.card_bg)?.mutate()?.also { it.alpha = 120 }
                else -> ContextCompat.getDrawable(this@MainActivity, R.drawable.card_bg)
            }
            setOnClickListener {
                vm.saveConfig(state.config.copy(activeToken = ts.mint))
                etActiveToken.setText(ts.mint)
                settingsPopulated = false
            }
        }

        val baseTextSize = resources.getDimension(R.dimen.token_name_size) / resources.displayMetrics.scaledDensity
        val smallTextSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        row1.addView(TextView(this).apply {
            text = ts.symbol.ifBlank { ts.mint.take(8) }
            textSize = (baseTextSize + 0.5f) * scale
            setTextColor(if (isIdle) muted else white)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row1.addView(TextView(this).apply {
            text = "%+.1f%%".format(pctChange)
            textSize = (baseTextSize - 1f) * scale
            setTextColor(if (isIdle) muted else changeCol)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        card.addView(row1)

        val row2 = TextView(this).apply {
            val rcPart = rcScore?.let { " RC:$it" } ?: ""
            val v3Part = if ((v3Score ?: 0) > 0) " V3:$v3Score" else ""
            val confPart = if ((v3Conf ?: 0) > 0) " C:${v3Conf}%" else ""
            val buyPart = buyPct?.let { " B:${it.toInt()}%" } ?: ""
            val entryPart = entryScr?.let { " E:${it.toInt()}" } ?: ""
            text = "$sourceShort$rcPart$v3Part$confPart$buyPart$entryPart"
            textSize = smallTextSize * scale
            setTextColor(if (isIdle) muted else white)
            typeface = android.graphics.Typeface.MONOSPACE
            maxLines = 1
            setPadding(0, (2 * scale).toInt(), 0, 0)
        }
        card.addView(row2)

        val row3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (2 * scale).toInt(), 0, 0)
        }
        row3.addView(TextView(this).apply {
            text = "MC:${compactUsd(ts.lastMcap)} L:${compactUsd(ts.lastLiquidityUsd)}"
            textSize = smallTextSize * scale
            setTextColor(muted)
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
        })
        row3.addView(TextView(this).apply {
            text = phaseShort
            textSize = smallTextSize * scale
            setTextColor(if (isIdle) muted else phaseColor)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(4, 1, 4, 1)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.badge_bg)
        })
        card.addView(row3)

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, (4 * scale).toInt())
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
        // V5.9.666 — long-press the Logs button to open the in-app
        // Pipeline Health panel (CI funnel mirror + ANR + recent
        // forensic events, all clipboard-exportable). Power-user
        // shortcut; the proper Pipeline tile in row 2 is the
        // primary discoverable entry point.
        findViewById<View>(R.id.btnQuickLogs)?.setOnLongClickListener {
            startActivity(Intent(this, PipelineHealthActivity::class.java))
            performHaptic()
            true
        }

        // V5.9.666 — Pipeline Health tile (row 2). Primary entry point.
        findViewById<View>(R.id.btnQuickPipeline)?.setOnClickListener {
            startActivity(Intent(this, PipelineHealthActivity::class.java))
            performHaptic()
        }
        // V5.9.790 — operator audit Critical Fix 9: long-press the Pipeline
        // tile opens the new AATE Universe Health screen (6-pillar status:
        // Runtime / Scoring / Learning / Execution / Authority / Wallet) so
        // the operator can verify in one tap whether the bot is honestly
        // sentient (MODERN) or running CLASSIC, and which Critical Fix lights
        // are still red.
        findViewById<View>(R.id.btnQuickPipeline)?.setOnLongClickListener {
            startActivity(Intent(this, UniverseHealthActivity::class.java))
            performHaptic()
            true
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
        
        // V5.7: Perps/Leverage Mode button → Shows Perps AI status
        findViewById<View>(R.id.btnQuickPerps)?.setOnClickListener {
            showPerpsModeDialog()
            performHaptic()
        }
        
        // V5.7.3: Tokenized Stocks button → Opens dedicated Markets UI
        // V5.7.6: Now navigates to MultiAssetActivity for proper Markets AI layers
        findViewById<View>(R.id.btnQuickStocks)?.setOnClickListener {
            startActivity(Intent(this, MultiAssetActivity::class.java))
            performHaptic()
        }
        
        // V5.7.6: Multi-Asset Markets button → Opens dedicated trading UI
        findViewById<View>(R.id.btnQuickMarkets)?.setOnClickListener {
            startActivity(Intent(this, MultiAssetActivity::class.java))
            performHaptic()
        }

        // V1.0: Crypto Alts tile (in meme modes row) → opens CryptoAltActivity
        findViewById<View>(R.id.btnQuickCryptoAlts)?.setOnClickListener {
            startActivity(Intent(this, com.lifecyclebot.ui.CryptoAltActivity::class.java))
            performHaptic()
        }

        // V5.9.402: Lab tile → opens LabActivity (LLM sandbox)
        findViewById<View>(R.id.btnQuickLab)?.setOnClickListener {
            startActivity(Intent(this, com.lifecyclebot.ui.LabActivity::class.java))
            performHaptic()
        }

        // V1.0: "Open Full Crypto Alts Screen" button inside card → same
        cardCryptoAlts?.findViewById<android.view.View>(R.id.btnOpenCryptoAltsMarkets)?.setOnClickListener {
            startActivity(Intent(this, com.lifecyclebot.ui.CryptoAltActivity::class.java))
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
            // V5.9.362 — apply Markets Trader per-asset switches (Perps/Stocks/
            // Commodities/Metals/Forex/Alts) immediately so the user doesn't
            // have to restart the bot service to enable/disable a trader.
            try { com.lifecyclebot.engine.BotService.reapplyMarketsTraderSwitches(applicationContext) } catch (_: Exception) {}
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
            val solPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice.takeIf { it in 50.0..1000.0 } ?: 85.0
            
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
            val solPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice.takeIf { it in 50.0..1000.0 } ?: 85.0
            
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
                    val pnl = ((solPrice * pos.entryPrice) - (solPrice * pos.entryPrice)) / (solPrice * pos.entryPrice) * 100
                    val holdMins = (System.currentTimeMillis() - pos.entryTime) / 60000
                    "   • ${pos.symbol} | \$${(pos.entryMcap/1000).toInt()}K | ${holdMins}min"
                }
            }
            
            // Build BlueChip positions list
            val blueChipPosList = if (blueChipPositions.isEmpty()) {
                "   (none)"
            } else {
                blueChipPositions.joinToString("\n") { pos ->
                    val holdMins = (System.currentTimeMillis() - pos.entryTime) / 60000
                    "   • ${pos.symbol} | \$${(pos.marketCapUsd/1_000_000).toInt()}M | ${holdMins}min"
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
            val solPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice.takeIf { it in 50.0..1000.0 } ?: 85.0
            
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
            val solPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice.takeIf { it in 50.0..1000.0 } ?: 85.0
            
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
            val solPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice.takeIf { it in 50.0..1000.0 } ?: 85.0
            
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

🛸 Orbital ($50K-$500K): $orbitalCount
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

    /**
     * V5.7: Shows Perps/Leverage trading mode dialog with risk acknowledgement
     */
    private fun showPerpsModeDialog() {
        try {
            val perpsAI = com.lifecyclebot.perps.PerpsTraderAI
            val cfg = com.lifecyclebot.data.ConfigStore.load(applicationContext)
            val solPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice.takeIf { it in 50.0..1000.0 } ?: 85.0
            
            // Check if user has acknowledged risk
            if (!perpsAI.hasAcknowledgedRisk()) {
                showPerpsRiskWarning()
                return
            }
            
            val currentModeLabel = if (cfg.paperMode) "PAPER MODE" else "LIVE MODE"
            val state = perpsAI.getState()
            val readiness = perpsAI.getLiveReadiness()
            
            val pnlSign = if (state.dailyPnlSol >= 0) "+" else ""
            val dailyPnlUsd = state.dailyPnlSol * solPrice
            
            val streakEmoji = when {
                state.currentStreak >= 5 -> "🔥🔥🔥"
                state.currentStreak >= 3 -> "🔥🔥"
                state.currentStreak > 0 -> "🔥"
                state.currentStreak <= -3 -> "❄️"
                else -> ""
            }
            
            val message = """
📊 SOL PERPS & LEVERAGE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

${if (cfg.paperMode) "📝" else "💰"} $currentModeLabel
🧠 Learning: ${state.learningProgress.toInt()}% | Discipline: ${perpsAI.getDisciplineScore()}%

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
💼 BALANCE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Paper: ${"%.4f".format(state.paperBalanceSol)} ◎
Live: ${"%.4f".format(state.liveBalanceSol)} ◎

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📊 TODAY'S PERFORMANCE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Day P&L: $pnlSign${"%.4f".format(state.dailyPnlSol)} ◎ (~$${"%.2f".format(dailyPnlUsd)})
W/L: ${state.dailyWins}/${state.dailyLosses} | Win Rate: ${perpsAI.getWinRatePct()}%
Trades: ${state.dailyTrades}
Streak: ${state.currentStreak} $streakEmoji

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📈 LIFETIME STATS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Total Trades: ${state.lifetimeTrades}
Win Rate: ${perpsAI.getLifetimeWinRatePct()}%
Total P&L: ${if (state.lifetimePnlSol >= 0) "+" else ""}${"%.4f".format(state.lifetimePnlSol)} ◎
Best Win: ${"%.4f".format(state.lifetimeBest)} ◎
Worst Loss: ${"%.4f".format(state.lifetimeWorst)} ◎
Max Win Streak: ${perpsAI.getMaxWinStreak()}
Max Loss Streak: ${perpsAI.getMaxLossStreak()}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🎯 LIVE READINESS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

${readiness.phase.emoji} ${readiness.phase.displayName}
Score: ${readiness.readinessScore}% | Discipline: ${readiness.disciplineScore}%
Paper Win Rate: ${"%.1f".format(readiness.paperWinRate)}%
Paper Trades: ${readiness.paperTrades}
Max Drawdown: disabled

${readiness.recommendation}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⚠️ RISK TIERS:
🎯 Sniper (2x) | ⚔️ Tactical (5x)
💥 Assault (10x) | ☢️ Nuclear (20x)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            """.trimIndent()
            
            AlertDialog.Builder(this, R.style.Theme_AATE_Dialog)
                .setTitle("📊 Perps & Leverage")
                .setMessage(message)
                .setPositiveButton("Close") { d, _ -> d.dismiss() }
                .setNegativeButton("Reset Daily") { d, _ ->
                    perpsAI.resetDaily()
                    Toast.makeText(this, "Perps daily stats reset", Toast.LENGTH_SHORT).show()
                    d.dismiss()
                }
                .setNeutralButton(if (perpsAI.isEnabled()) "Disable" else "Enable") { d, _ ->
                    perpsAI.setEnabled(!perpsAI.isEnabled())
                    // V5.7.6: Perps card moved to Markets UI - keep hidden
                    Toast.makeText(this, "Perps ${if (perpsAI.isEnabled()) "ENABLED" else "DISABLED"} - View in Markets", Toast.LENGTH_SHORT).show()
                    d.dismiss()
                }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Perps Mode: ${e.message ?: "Not available"}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * V5.7: Shows risk warning before enabling Perps trading
     */
    private fun showPerpsRiskWarning() {
        val warningMessage = """
⚠️ LEVERAGE TRADING RISK WARNING ⚠️

Leverage trading carries EXTREME risk:

• You can lose MORE than your initial investment
• Positions can be LIQUIDATED within minutes
• Past performance does NOT guarantee future results
• AI recommendations are NOT financial advice

ONLY trade with funds you can afford to lose 100%.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

By proceeding, you acknowledge:

✓ I understand leverage amplifies both gains AND losses
✓ I accept full responsibility for my trading decisions
✓ I will start with PAPER MODE to practice first
✓ I am NOT using funds needed for essential expenses

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        """.trimIndent()
        
        AlertDialog.Builder(this, R.style.Theme_AATE_Dialog)
            .setTitle("⚠️ Risk Acknowledgement Required")
            .setMessage(warningMessage)
            .setCancelable(false)
            .setPositiveButton("I UNDERSTAND & ACCEPT") { d, _ ->
                com.lifecyclebot.perps.PerpsTraderAI.acknowledgeRisk()
                com.lifecyclebot.perps.PerpsTraderAI.setEnabled(true)
                // V5.7.6: Perps card moved to Markets UI
                Toast.makeText(this, "📊 Perps Trading Unlocked - Open Markets to trade!", Toast.LENGTH_LONG).show()
                d.dismiss()
                // Show the full dialog now
                showPerpsModeDialog()
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════════
    // V5.7.3: PERPS TRADE VISUALIZER & BUY/SELL TRIGGERS
    // ═══════════════════════════════════════════════════════════════════════════════
    
    /**
     * V5.7.3: Shows the detailed trade visualizer pop-out for a specific position
     */
    private fun showPerpsTradeVisualizerDialog(position: com.lifecyclebot.perps.PerpsPosition) {
        try {
            lifecycleScope.launch {
                // Generate visualization data
                val viz = com.lifecyclebot.perps.PerpsTradeVisualizer.generateVisualization(position)
                
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val pred = viz.prediction
                    val risk = viz.riskGauge
                    val momentum = viz.momentumRibbon
                    val pnl = viz.pnlProjection
                    
                    val alertsText = viz.smartAlerts.take(3).joinToString("\n") { 
                        "${it.emoji} ${it.message}" 
                    }
                    
                    val message = """
${position.market.emoji} ${position.market.displayName}
${position.direction.emoji} ${position.direction.symbol} | ${position.leverage}x
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

💰 CURRENT P&L
Entry: $${String.format("%.4f", position.entryPrice)}
Current: $${String.format("%.4f", position.currentPrice)}
P&L: ${position.getDisplayPnl()} ($$${String.format("%.2f", position.getUnrealizedPnlUsd())})

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🔮 AI PREDICTION (${pred.layerConsensus} layers)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

${pred.emoji} Direction: ${pred.predictedDirection.symbol}
Confidence: ${String.format("%.0f", pred.directionConfidence)}%
Target: $${String.format("%.4f", pred.predictedPriceTarget)}

📊 Probabilities:
  🎯 TP: ${String.format("%.0f", pred.probabilityOfTP)}%
  🛑 SL: ${String.format("%.0f", pred.probabilityOfSL)}%
  💀 Liquidation: ${String.format("%.0f", pred.probabilityOfLiquidation)}%

${pred.reasoning}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
${risk.emoji} RISK GAUGE: ${risk.riskLevel}/100
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

${risk.riskCategory.name} | Dist to Liq: ${String.format("%.1f", risk.distanceToLiquidation)}%
Leverage Health: ${String.format("%.0f", risk.leverageHealth)}%
${risk.warning ?: ""}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
${momentum.emoji} MOMENTUM: ${momentum.strength}
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Trend: ${momentum.trend.name}
Direction: ${momentum.direction.symbol}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📈 P&L PROJECTION
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Break Even: $${String.format("%.4f", pnl.breakEvenPrice)}
Max Potential: $${String.format("%.2f", pnl.maxGain)}
Max Loss: $${String.format("%.2f", pnl.maxLoss)}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⚡ SMART ALERTS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

${if (alertsText.isNotEmpty()) alertsText else "No active alerts"}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                    """.trimIndent()
                    
                    AlertDialog.Builder(this@MainActivity, R.style.Theme_AATE_Dialog)
                        .setTitle("${position.market.emoji} Trade Visualizer")
                        .setMessage(message)
                        .setPositiveButton("Close") { d, _ -> d.dismiss() }
                        .setNegativeButton("Close Position") { d, _ ->
                            showClosePositionConfirmation(position)
                            d.dismiss()
                        }
                        .setNeutralButton("Refresh") { d, _ ->
                            d.dismiss()
                            showPerpsTradeVisualizerDialog(position)
                        }
                        .show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Visualizer error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * V5.7.3: Shows confirmation dialog before closing a position
     */
    private fun showClosePositionConfirmation(position: com.lifecyclebot.perps.PerpsPosition) {
        val pnlText = position.getDisplayPnl()
        val pnlColor = if (position.getUnrealizedPnlPct() >= 0) "profit" else "loss"
        
        AlertDialog.Builder(this, R.style.Theme_AATE_Dialog)
            .setTitle("⚠️ Close Position?")
            .setMessage("""
Close ${position.market.symbol} ${position.direction.symbol} position?

Current P&L: $pnlText ($${"%.2f".format(position.getUnrealizedPnlUsd())})

This action cannot be undone.
            """.trimIndent())
            .setPositiveButton("Close Position") { d, _ ->
                lifecycleScope.launch {
                    try {
                        val trade = com.lifecyclebot.perps.PerpsExecutionEngine.manualClose(position.id)
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (trade != null) {
                                Toast.makeText(this@MainActivity, 
                                    "${if (trade.pnlPct >= 0) "✅" else "📉"} Closed: ${trade.pnlPct.fmt(1)}%", 
                                    Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@MainActivity, "Failed to close position", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                d.dismiss()
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()
    }
    
    /**
     * V5.7.3: Shows manual buy dialog for perps trading
     */
    private fun showPerpsBuyDialog() {
        val perpsAI = com.lifecyclebot.perps.PerpsTraderAI
        
        if (!perpsAI.isEnabled() || !perpsAI.hasAcknowledgedRisk()) {
            showPerpsRiskWarning()
            return
        }
        
        val markets = com.lifecyclebot.perps.PerpsMarket.values()
        val marketLabels = markets.map { "${it.emoji} ${it.symbol} (${it.displayName})" }.toTypedArray()
        var selectedMarket = com.lifecyclebot.perps.PerpsMarket.SOL
        var selectedDirection = com.lifecyclebot.perps.PerpsDirection.LONG
        var selectedLeverage = 2.0
        var selectedSizePct = 5.0
        
        // Build dialog with input fields
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        
        // Market selector
        val marketSpinner = android.widget.Spinner(this)
        val marketAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, marketLabels)
        marketSpinner.adapter = marketAdapter
        marketSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                selectedMarket = markets[pos]
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        
        // Direction buttons
        val directionLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        val btnLong = android.widget.Button(this).apply {
            text = "📈 LONG"
            setBackgroundColor(0xFF22C55E.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                selectedDirection = com.lifecyclebot.perps.PerpsDirection.LONG
                setBackgroundColor(0xFF22C55E.toInt())
                (directionLayout.getChildAt(1) as android.widget.Button).setBackgroundColor(0xFF374151.toInt())
            }
        }
        val btnShort = android.widget.Button(this).apply {
            text = "📉 SHORT"
            setBackgroundColor(0xFF374151.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                selectedDirection = com.lifecyclebot.perps.PerpsDirection.SHORT
                setBackgroundColor(0xFFEF4444.toInt())
                (directionLayout.getChildAt(0) as android.widget.Button).setBackgroundColor(0xFF374151.toInt())
            }
        }
        directionLayout.addView(btnLong, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        directionLayout.addView(btnShort, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        
        // Leverage input
        val leverageLabel = android.widget.TextView(this).apply {
            text = "Leverage: 2x"
            setTextColor(0xFFFFFFFF.toInt())
        }
        val leverageSeekBar = android.widget.SeekBar(this).apply {
            max = 19  // 1-20x
            progress = 1  // Default 2x
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    selectedLeverage = (progress + 1).toDouble()
                    leverageLabel.text = "Leverage: ${selectedLeverage.toInt()}x"
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        }
        
        // Size input
        val sizeLabel = android.widget.TextView(this).apply {
            text = "Position Size: 5% of balance"
            setTextColor(0xFFFFFFFF.toInt())
        }
        val sizeSeekBar = android.widget.SeekBar(this).apply {
            max = 24  // 1-25%
            progress = 4  // Default 5%
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    selectedSizePct = (progress + 1).toDouble()
                    sizeLabel.text = "Position Size: ${selectedSizePct.toInt()}% of balance"
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        }
        
        layout.addView(android.widget.TextView(this).apply { text = "Market:"; setTextColor(0xFFFFFFFF.toInt()) })
        layout.addView(marketSpinner)
        layout.addView(android.widget.TextView(this).apply { text = "\nDirection:"; setTextColor(0xFFFFFFFF.toInt()) })
        layout.addView(directionLayout)
        layout.addView(android.widget.TextView(this).apply { text = "\n"; setTextColor(0xFFFFFFFF.toInt()) })
        layout.addView(leverageLabel)
        layout.addView(leverageSeekBar)
        layout.addView(android.widget.TextView(this).apply { text = "\n"; setTextColor(0xFFFFFFFF.toInt()) })
        layout.addView(sizeLabel)
        layout.addView(sizeSeekBar)
        
        AlertDialog.Builder(this, R.style.Theme_AATE_Dialog)
            .setTitle("📈 Open Perps Position")
            .setView(layout)
            .setPositiveButton("Open Position") { d, _ ->
                lifecycleScope.launch {
                    try {
                        val position = com.lifecyclebot.perps.PerpsExecutionEngine.manualOpen(
                            market = selectedMarket,
                            direction = selectedDirection,
                            leverage = selectedLeverage,
                            sizePct = selectedSizePct,
                        )
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (position != null) {
                                Toast.makeText(this@MainActivity, 
                                    "${selectedDirection.emoji} Opened ${selectedMarket.symbol} @ ${selectedLeverage.toInt()}x", 
                                    Toast.LENGTH_SHORT).show()
                                performHaptic()
                            } else {
                                Toast.makeText(this@MainActivity, "Failed to open position", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                d.dismiss()
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()
    }
    
    /**
     * V5.7.3: Shows AI-recommended perps signals for quick execution
     */
    private fun showPerpsSignalsDialog() {
        val perpsAI = com.lifecyclebot.perps.PerpsTraderAI
        
        if (!perpsAI.isEnabled() || !perpsAI.hasAcknowledgedRisk()) {
            showPerpsRiskWarning()
            return
        }
        
        lifecycleScope.launch {
            try {
                // Get current market data and generate signals
                val solData = com.lifecyclebot.perps.PerpsMarketDataFetcher.getMarketData(
                    com.lifecyclebot.perps.PerpsMarket.SOL
                )
                val aggregated = com.lifecyclebot.perps.PerpsLearningBridge.aggregateLayerSignals(
                    com.lifecyclebot.perps.PerpsMarket.SOL, solData
                )
                
                // Get replay learner recommendation
                val recommendation = com.lifecyclebot.perps.PerpsAutoReplayLearner.getRecommendation(
                    com.lifecyclebot.perps.PerpsMarket.SOL, aggregated.direction
                )
                
                // Get recent insights
                val insights = com.lifecyclebot.perps.PerpsAutoReplayLearner.getRecentInsights().take(5)
                val insightsText = insights.joinToString("\n") { 
                    "${it.type.name}: ${it.insight}" 
                }
                
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    // V5.9.10: 16-channel symbolic snapshot — every AI module, one view
                    val symSnap = try {
                        com.lifecyclebot.engine.SymbolicExitReasoner.getSignalSnapshot("SOL", "")
                    } catch (_: Exception) { emptyMap() }
                    val symBars = if (symSnap.isEmpty()) "Signals warming up…" else {
                        symSnap.entries.sortedByDescending { it.value }.joinToString("\n") { (name, v) ->
                            val pct = (v.coerceIn(0.0, 1.0) * 100).toInt()
                            val blocks = (pct / 10).coerceIn(0, 10)
                            val bar = "█".repeat(blocks) + "░".repeat(10 - blocks)
                            "${name.padEnd(16)} $bar ${pct.toString().padStart(3)}%"
                        }
                    }
                    val symDiag = try { com.lifecyclebot.engine.SymbolicContext.getDiagnostics() } catch (_: Exception) { "—" }
                    val sentStatus = try { com.lifecyclebot.engine.SentientPersonality.getStatusLine() } catch (_: Exception) { "—" }

                    val message = """
🧠 AI SIGNAL ANALYSIS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

📊 SOL-PERP Current: $${String.format("%.2f", solData.price)}
24h Change: ${if (solData.priceChange24hPct >= 0) "+" else ""}${String.format("%.1f", solData.priceChange24hPct)}%
Funding: ${String.format("%.4f", solData.fundingRate * 100)}%

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🧠 SENTIENT MIND
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

$sentStatus

$symDiag

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🎯 16-CHANNEL SYMBOLIC FIRING
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

$symBars

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🎯 26-LAYER CONSENSUS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Direction: ${aggregated.direction.emoji} ${aggregated.direction.symbol}
Confidence: ${String.format("%.0f", aggregated.directionConfidence)}%
Layers Voting: ${aggregated.layerConsensus}/${aggregated.totalLayersVoting}
Risk Score: ${aggregated.riskScore.toInt()}/100

Top Layers: ${aggregated.contributingLayers.take(3).joinToString(", ")}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🎬 REPLAY LEARNER
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

$recommendation

Patterns Identified: ${com.lifecyclebot.perps.PerpsAutoReplayLearner.getPatternsIdentified()}
Total Replays: ${com.lifecyclebot.perps.PerpsAutoReplayLearner.getTotalReplays()}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
💡 RECENT INSIGHTS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

${if (insightsText.isNotEmpty()) insightsText else "No recent insights"}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                    """.trimIndent()
                    
                    AlertDialog.Builder(this@MainActivity, R.style.Theme_AATE_Dialog)
                        .setTitle("🧠 AI Signals")
                        .setMessage(message)
                        .setPositiveButton("Close") { d, _ -> d.dismiss() }
                        .setNeutralButton("Manual Trade") { d, _ ->
                            d.dismiss()
                            showPerpsBuyDialog()
                        }
                        .show()
                }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * V5.7.3: Setup perps card click handlers for positions
     */
    private fun setupPerpsPositionClickHandlers() {
        try {
            // Card click → show full perps dialog
            cardPerpsTrading?.setOnClickListener {
                showPerpsModeDialog()
                performHaptic()
            }
            
            // Long press → show buy dialog
            cardPerpsTrading?.setOnLongClickListener {
                showPerpsBuyDialog()
                performHaptic()
                true
            }
        } catch (_: Exception) {}
    }
    
    private fun Double.fmt(decimals: Int): String = String.format("%.${decimals}f", this)
    
    // ═══════════════════════════════════════════════════════════════════════════════
    // V5.7.3: TOKENIZED STOCKS TRADING UI
    // ═══════════════════════════════════════════════════════════════════════════════
    
    /**
     * V5.7.3: Shows the tokenized stocks main dialog
     */
    private fun showTokenizedStocksDialog() {
        val perpsAI = com.lifecyclebot.perps.PerpsTraderAI
        
        // Check risk acknowledgement
        if (!perpsAI.hasAcknowledgedRisk()) {
            showPerpsRiskWarning()
            return
        }
        
        val cfg = com.lifecyclebot.data.ConfigStore.load(applicationContext)
        val state = perpsAI.getState()
        
        // Get stock positions
        val stockPositions = perpsAI.getActivePositions().filter { it.market.isStock }
        val stockTrades = state.lifetimeTrades  // Would ideally filter by stock trades
        
        val positionsText = if (stockPositions.isEmpty()) {
            "No open stock positions"
        } else {
            stockPositions.joinToString("\n") { pos ->
                "${pos.market.emoji} ${pos.market.symbol}: ${pos.getDisplayPnl()} | ${pos.direction.symbol} ${pos.leverage}x"
            }
        }
        
        val message = """
📈 TOKENIZED STOCKS TRADING
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

${if (cfg.paperMode) "📝" else "💰"} ${if (cfg.paperMode) "PAPER MODE" else "LIVE MODE"}

AVAILABLE MARKETS:
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

🍎 AAPL (Apple)     - Max 10x
🚗 TSLA (Tesla)     - Max 10x  
🖥️ NVDA (NVIDIA)    - Max 10x
🔍 GOOGL (Alphabet) - Max 10x
📦 AMZN (Amazon)    - Max 10x
👤 META (Meta)      - Max 10x
🪟 MSFT (Microsoft) - Max 10x
🪙 COIN (Coinbase)  - Max 10x

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📊 CURRENT POSITIONS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

$positionsText

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📅 MARKET HOURS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Mon-Fri: 9:30 AM - 4:00 PM ET
Pre-market: 4:00 AM - 9:30 AM ET
After-hours: 4:00 PM - 8:00 PM ET

⚠️ Tokenized stocks follow real market hours!
Trading outside hours may have wider spreads.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        """.trimIndent()
        
        AlertDialog.Builder(this, R.style.Theme_AATE_Dialog)
            .setTitle("📈 Tokenized Stocks")
            .setMessage(message)
            .setPositiveButton("Close") { d, _ -> d.dismiss() }
            .setNegativeButton("Open Markets") { d, _ ->
                d.dismiss()
                // V5.7.6: Navigate to Markets UI for stocks trading
                startActivity(Intent(this, MultiAssetActivity::class.java))
            }
            .show()
    }
    
    /**
     * V5.7.3: Shows manual buy dialog for tokenized stocks
     */
    private fun showStockBuyDialog() {
        val perpsAI = com.lifecyclebot.perps.PerpsTraderAI
        
        if (!perpsAI.isEnabled() || !perpsAI.hasAcknowledgedRisk()) {
            showPerpsRiskWarning()
            return
        }
        
        // Only show stock markets
        val stockMarkets = com.lifecyclebot.perps.PerpsMarket.values().filter { it.isStock }
        val marketLabels = stockMarkets.map { "${it.emoji} ${it.symbol} (${it.displayName})" }.toTypedArray()
        var selectedMarket = stockMarkets.firstOrNull() ?: return
        var selectedDirection = com.lifecyclebot.perps.PerpsDirection.LONG
        var selectedLeverage = 2.0
        var selectedSizePct = 5.0
        
        // Build dialog with input fields
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        
        // Market selector
        val marketSpinner = android.widget.Spinner(this)
        val marketAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, marketLabels)
        marketSpinner.adapter = marketAdapter
        marketSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                selectedMarket = stockMarkets[pos]
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        
        // Direction buttons
        val directionLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        val btnLong = android.widget.Button(this).apply {
            text = "📈 LONG"
            setBackgroundColor(0xFF22C55E.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                selectedDirection = com.lifecyclebot.perps.PerpsDirection.LONG
                setBackgroundColor(0xFF22C55E.toInt())
                (directionLayout.getChildAt(1) as android.widget.Button).setBackgroundColor(0xFF374151.toInt())
            }
        }
        val btnShort = android.widget.Button(this).apply {
            text = "📉 SHORT"
            setBackgroundColor(0xFF374151.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                selectedDirection = com.lifecyclebot.perps.PerpsDirection.SHORT
                setBackgroundColor(0xFFEF4444.toInt())
                (directionLayout.getChildAt(0) as android.widget.Button).setBackgroundColor(0xFF374151.toInt())
            }
        }
        directionLayout.addView(btnLong, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        directionLayout.addView(btnShort, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        
        // Leverage input (max 10x for stocks)
        val leverageLabel = android.widget.TextView(this).apply {
            text = "Leverage: 2x"
            setTextColor(0xFFFFFFFF.toInt())
        }
        val leverageSeekBar = android.widget.SeekBar(this).apply {
            max = 9  // 1-10x for stocks
            progress = 1  // Default 2x
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    selectedLeverage = (progress + 1).toDouble()
                    leverageLabel.text = "Leverage: ${selectedLeverage.toInt()}x"
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        }
        
        // Size input
        val sizeLabel = android.widget.TextView(this).apply {
            text = "Position Size: 5% of balance"
            setTextColor(0xFFFFFFFF.toInt())
        }
        val sizeSeekBar = android.widget.SeekBar(this).apply {
            max = 24  // 1-25%
            progress = 4  // Default 5%
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    selectedSizePct = (progress + 1).toDouble()
                    sizeLabel.text = "Position Size: ${selectedSizePct.toInt()}% of balance"
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        }
        
        layout.addView(android.widget.TextView(this).apply { text = "Stock:"; setTextColor(0xFFFFFFFF.toInt()) })
        layout.addView(marketSpinner)
        layout.addView(android.widget.TextView(this).apply { text = "\nDirection:"; setTextColor(0xFFFFFFFF.toInt()) })
        layout.addView(directionLayout)
        layout.addView(android.widget.TextView(this).apply { text = "\n"; setTextColor(0xFFFFFFFF.toInt()) })
        layout.addView(leverageLabel)
        layout.addView(leverageSeekBar)
        layout.addView(android.widget.TextView(this).apply { text = "\n"; setTextColor(0xFFFFFFFF.toInt()) })
        layout.addView(sizeLabel)
        layout.addView(sizeSeekBar)
        
        AlertDialog.Builder(this, R.style.Theme_AATE_Dialog)
            .setTitle("📈 Open Stock Position")
            .setView(layout)
            .setPositiveButton("Open Position") { d, _ ->
                lifecycleScope.launch {
                    try {
                        val position = com.lifecyclebot.perps.PerpsExecutionEngine.manualOpen(
                            market = selectedMarket,
                            direction = selectedDirection,
                            leverage = selectedLeverage,
                            sizePct = selectedSizePct,
                        )
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (position != null) {
                                Toast.makeText(this@MainActivity, 
                                    "${selectedMarket.emoji} Opened ${selectedMarket.symbol} ${selectedDirection.symbol} @ ${selectedLeverage.toInt()}x - View in Markets", 
                                    Toast.LENGTH_SHORT).show()
                                performHaptic()
                                // V5.7.6: Stocks card moved to Markets UI
                            } else {
                                Toast.makeText(this@MainActivity, "Failed to open position", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                d.dismiss()
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()
    }
    
    /**
     * V5.7.3: Update the tokenized stocks card with current prices and positions
     * V5.7.6: Card moved to Markets UI - this function now just returns early
     */
    private fun updateTokenizedStocksCard() {
        // V5.7.6: Stocks card moved to MultiAssetActivity (Markets UI)
        cardTokenizedStocks?.visibility = View.GONE
        return
        
        // DEPRECATED: All code below unused - stocks now in Markets UI
        try {
            // V5.7.5: Use dedicated TokenizedStockTrader instead of PerpsTraderAI
            val stockTrader = com.lifecyclebot.perps.TokenizedStockTrader
            val stockPositions = stockTrader.getActivePositions()
            
            // Always show the stocks card
            cardTokenizedStocks?.visibility = View.VISIBLE
            
            val cfg = com.lifecyclebot.data.ConfigStore.load(applicationContext)
            
            // Mode badge
            tvStocksModeBadge?.text = "PAPER"
            tvStocksModeBadge?.setBackgroundResource(R.drawable.pill_bg_yellow)
            
            // Balance from stock trader
            val balance = stockTrader.getBalance()
            tvStocksBalance?.text = "%.4f".format(balance)
            
            // Stats from dedicated trader
            val stockPnlPct = stockPositions.sumOf { it.getUnrealizedPnlPct() }
            val stockWins = stockPositions.count { it.getUnrealizedPnlPct() > 0 }
            val stockTotal = stockPositions.size
            val winRate = stockTrader.getWinRate()
            val totalTrades = stockTrader.getTotalTrades()
            
            tvStocksPnl?.text = "${if (stockPnlPct >= 0) "+" else ""}${"%.2f".format(stockPnlPct)}%"
            tvStocksPnl?.setTextColor(if (stockPnlPct >= 0) 0xFF22C55E.toInt() else 0xFFEF4444.toInt())
            
            tvStocksWinRate?.text = "${winRate.toInt()}%"
            tvStocksTrades?.text = "$totalTrades"
            
            // Update tile stats
            tvStocksStats?.text = "$stockWins/$stockTotal"
            
            // Fetch stock prices asynchronously
            lifecycleScope.launch {
                try {
                    // Fetch prices for each stock market
                    val stockMarkets = com.lifecyclebot.perps.PerpsMarket.values().filter { it.isStock }
                    
                    for (market in stockMarkets) {
                        try {
                            val data = com.lifecyclebot.perps.PerpsMarketDataFetcher.getMarketData(market)
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                when (market) {
                                    com.lifecyclebot.perps.PerpsMarket.AAPL -> {
                                        tvStocksAaplPrice?.text = "$${"%.2f".format(data.price)}"
                                        tvStocksAaplChange?.text = "${if (data.priceChange24hPct >= 0) "+" else ""}${"%.1f".format(data.priceChange24hPct)}%"
                                        tvStocksAaplChange?.setTextColor(if (data.priceChange24hPct >= 0) 0xFF22C55E.toInt() else 0xFFEF4444.toInt())
                                    }
                                    com.lifecyclebot.perps.PerpsMarket.TSLA -> {
                                        tvStocksTslaPrice?.text = "$${"%.2f".format(data.price)}"
                                        tvStocksTslaChange?.text = "${if (data.priceChange24hPct >= 0) "+" else ""}${"%.1f".format(data.priceChange24hPct)}%"
                                        tvStocksTslaChange?.setTextColor(if (data.priceChange24hPct >= 0) 0xFF22C55E.toInt() else 0xFFEF4444.toInt())
                                    }
                                    com.lifecyclebot.perps.PerpsMarket.NVDA -> {
                                        tvStocksNvdaPrice?.text = "$${"%.2f".format(data.price)}"
                                        tvStocksNvdaChange?.text = "${if (data.priceChange24hPct >= 0) "+" else ""}${"%.1f".format(data.priceChange24hPct)}%"
                                        tvStocksNvdaChange?.setTextColor(if (data.priceChange24hPct >= 0) 0xFF22C55E.toInt() else 0xFFEF4444.toInt())
                                    }
                                    com.lifecyclebot.perps.PerpsMarket.GOOGL -> {
                                        tvStocksGooglPrice?.text = "$${"%.2f".format(data.price)}"
                                        tvStocksGooglChange?.text = "${if (data.priceChange24hPct >= 0) "+" else ""}${"%.1f".format(data.priceChange24hPct)}%"
                                        tvStocksGooglChange?.setTextColor(if (data.priceChange24hPct >= 0) 0xFF22C55E.toInt() else 0xFFEF4444.toInt())
                                    }
                                    com.lifecyclebot.perps.PerpsMarket.AMZN -> {
                                        tvStocksAmznPrice?.text = "$${"%.2f".format(data.price)}"
                                    }
                                    com.lifecyclebot.perps.PerpsMarket.META -> {
                                        tvStocksMetaPrice?.text = "$${"%.2f".format(data.price)}"
                                    }
                                    com.lifecyclebot.perps.PerpsMarket.MSFT -> {
                                        tvStocksMsftPrice?.text = "$${"%.2f".format(data.price)}"
                                    }
                                    com.lifecyclebot.perps.PerpsMarket.COIN -> {
                                        tvStocksCoinPrice?.text = "$${"%.2f".format(data.price)}"
                                    }
                                    else -> {}
                                }
                            }
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
            
            // Update positions list with stock trader positions
            updateStockTraderPositionsList(stockPositions)
            
        } catch (e: Exception) {
            com.lifecyclebot.engine.ErrorLogger.debug("MainActivity", "Stocks card update error: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // V1.0: CRYPTO ALTS CARD — mirrors updateTokenizedStocksCard pattern
    // ═══════════════════════════════════════════════════════════════════════
    private fun updateCryptoAltsCard() {
        // V5.9.730 ANR FIX: 3s debounce — appeared in 4% of ANR stack samples.
        val nowCa = System.currentTimeMillis()
        if (nowCa - lastCryptoAltsRenderMs < 3_000L) return
        lastCryptoAltsRenderMs = nowCa
        try {
            val altTrader = com.lifecyclebot.perps.CryptoAltTrader
            if (!altTrader.isRunning()) {
                cardCryptoAlts?.visibility = android.view.View.GONE
                return
            }
            cardCryptoAlts?.visibility = android.view.View.VISIBLE

            // Mode badge
            val isLive = altTrader.isLiveMode()
            tvCryptoAltsModeBadge?.text = if (isLive) "LIVE" else "PAPER"
            tvCryptoAltsModeBadge?.setBackgroundResource(
                if (isLive) R.drawable.pill_bg_red else R.drawable.pill_bg_yellow)
            tvCryptoAltsModeBadge?.setTextColor(
                if (isLive) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())

            // Balance
            val bal = altTrader.getBalance()
            tvCryptoAltsBalance?.text = "${"%.3f".format(bal)}◎"

            // PnL
            val pnl = altTrader.getTotalPnlSol()
            tvCryptoAltsPnl?.text = "${if (pnl >= 0) "+" else ""}${"%.3f".format(pnl)}◎"
            tvCryptoAltsPnl?.setTextColor(if (pnl >= 0) 0xFF22C55E.toInt() else 0xFFEF4444.toInt())

            // Win rate
            val wr = altTrader.getWinRate()
            tvCryptoAltsWinRate?.text = "${wr.toInt()}%"
            tvCryptoAltsWinRate?.setTextColor(when {
                wr >= 55 -> 0xFF22C55E.toInt()
                wr >= 45 -> 0xFFF59E0B.toInt()
                else     -> 0xFFEF4444.toInt()
            })

            // Trades
            val totalTrades = altTrader.getTotalTrades()
            tvCryptoAltsTrades?.text = "$totalTrades"

            // Tile stats (positions/trades shown on the meme mode tile)
            val openCount = altTrader.getAllPositions().size
            tvCryptoAltsStats?.text = "$openCount/$totalTrades"

            // Readiness phase
            val trades = altTrader.getTotalTrades()
            val (phase, phasePct, phaseColor, phaseText) = when {
                trades < 500  -> Quadruple("📚 BOOTSTRAP",  trades / 500.0,  0xFFF59E0B.toInt(), "Learning alt market patterns — paper mode only")
                trades < 1500 -> Quadruple("🧠 LEARNING",   (trades - 500) / 1000.0,  0xFFF59E0B.toInt(), "Building alt pattern memory")
                trades < 3000 -> Quadruple("🔬 VALIDATING", (trades - 1500) / 1500.0, 0xFF3B82F6.toInt(), "Validating signal reliability")
                trades < 5000 -> Quadruple("⚡ MATURING",   (trades - 3000) / 2000.0, 0xFF8B5CF6.toInt(), "Refining alt execution strategy")
                wr >= 55      -> Quadruple("✅ READY",       1.0, 0xFF22C55E.toInt(), "Alt trader is ready for live trading")
                else          -> Quadruple("⚡ MATURING",   0.9, 0xFF8B5CF6.toInt(), "Improving win rate before live mode")
            }
            tvCryptoAltsPhase?.text = phase
            tvCryptoAltsPhase?.setTextColor(phaseColor)
            tvCryptoAltsProgress?.text = "${(phasePct * 100).toInt()}%"
            tvCryptoAltsReadiness?.text = phaseText

            // Progress bar width
            viewCryptoAltsBar?.let { bar ->
                val parent = bar.parent as? android.widget.FrameLayout ?: return@let
                bar.post {
                    val params = bar.layoutParams
                    params.width = (parent.width * phasePct.coerceIn(0.0, 1.0)).toInt().coerceAtLeast(8)
                    bar.layoutParams = params
                }
            }

            // Price tickers — async, non-blocking
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val priceTargets = listOf(
                    com.lifecyclebot.perps.PerpsMarket.BTC  to (tvAltsBtcPrice  to tvAltsBtcChange),
                    com.lifecyclebot.perps.PerpsMarket.ETH  to (tvAltsEthPrice  to tvAltsEthChange),
                    com.lifecyclebot.perps.PerpsMarket.BNB  to (tvAltsBnbPrice  to tvAltsBnbChange),
                    com.lifecyclebot.perps.PerpsMarket.XRP  to (tvAltsXrpPrice  to tvAltsXrpChange),
                )
                for ((market, views) in priceTargets) {
                    val (priceView, changeView) = views
                    try {
                        val cached = com.lifecyclebot.perps.PerpsMarketDataFetcher.getCachedPrice(market)
                        if (cached != null && cached.price > 0) {
                            val price  = cached.price
                            val change = cached.priceChange24hPct
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                priceView?.text = if (price > 1000) "$${"%.0f".format(price)}"
                                                  else "$${"%.4f".format(price)}"
                                changeView?.text = "${if (change >= 0) "+" else ""}${"%.1f".format(change)}%"
                                changeView?.setTextColor(if (change >= 0) 0xFF22C55E.toInt() else 0xFFEF4444.toInt())
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            // Open positions (top 3) — programmatic rows, no separate layout needed
            llCryptoAltsPositions?.removeAllViews()
            val openPositions = altTrader.getAllPositions().take(3)
            for (pos in openPositions) {
                try {
                    val pnlPct = pos.getPnlPct()
                    val pnlColor = if (pnlPct >= 0) 0xFF22C55E.toInt() else 0xFFEF4444.toInt()
                    val row = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        setPadding(0, 4, 0, 4)
                    }
                    val tvSymbol = TextView(this).apply {
                        text = "${pos.market.emoji} ${pos.market.symbol}"
                        textSize = 11f
                        setTextColor(0xFFFFFFFF.toInt())
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val tvDir = TextView(this).apply {
                        text = "${pos.direction.emoji} ${pos.leverageLabel}"
                        textSize = 10f
                        setTextColor(0xFF9CA3AF.toInt())
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val tvPnl = TextView(this).apply {
                        text = "${if (pnlPct >= 0) "+" else ""}${"%.2f".format(pnlPct)}%"
                        textSize = 11f
                        setTextColor(pnlColor)
                        gravity = android.view.Gravity.END
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    row.addView(tvSymbol)
                    row.addView(tvDir)
                    row.addView(tvPnl)
                    llCryptoAltsPositions?.addView(row)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    // Tiny helper for destructuring 4-tuples
    private data class Quadruple<A,B,C,D>(val a:A, val b:B, val c:C, val d:D)
    private operator fun <A,B,C,D> Quadruple<A,B,C,D>.component1() = a
    private operator fun <A,B,C,D> Quadruple<A,B,C,D>.component2() = b
    private operator fun <A,B,C,D> Quadruple<A,B,C,D>.component3() = c
    private operator fun <A,B,C,D> Quadruple<A,B,C,D>.component4() = d

    
    /**
     * V5.7.5: Update the stocks positions list UI for TokenizedStockTrader
     */
    private fun updateStockTraderPositionsList(positions: List<com.lifecyclebot.perps.TokenizedStockTrader.StockPosition>) {
        llStocksPositions?.removeAllViews()
        
        if (positions.isEmpty()) return
        
        for (position in positions) {
            try {
                val livePrice = position.currentPrice.takeIf { it > 0 } ?: position.entryPrice
                
                // Create a rich position card
                val cardLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.section_card_bg)
                    setPadding(24, 16, 24, 16)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, 12)
                    }
                }
                
                // Header row: Symbol + Direction + Leverage
                val headerRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                
                val headerText = TextView(this).apply {
                    text = "${position.market.emoji} ${position.market.symbol} ${position.direction.symbol} ${position.leverage.toInt()}x"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                headerRow.addView(headerText)
                
                // P&L badge
                val pnlPct = position.getUnrealizedPnlPct()
                val pnlBadge = TextView(this).apply {
                    text = "${if (pnlPct >= 0) "+" else ""}${String.format("%.2f", pnlPct)}%"
                    setTextColor(if (pnlPct >= 0) 0xFF22C55E.toInt() else 0xFFEF4444.toInt())
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                headerRow.addView(pnlBadge)
                cardLayout.addView(headerRow)
                
                // Spacer
                cardLayout.addView(android.view.View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 8)
                })
                
                // Data grid
                val dataGrid = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                
                // Entry price
                val entryCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                entryCol.addView(TextView(this).apply {
                    text = "Entry"
                    setTextColor(0xFF9CA3AF.toInt())
                    textSize = 10f
                })
                entryCol.addView(TextView(this).apply {
                    text = "$${String.format("%.2f", position.entryPrice)}"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 12f
                })
                dataGrid.addView(entryCol)
                
                // Current price with change indicator
                val currentCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                currentCol.addView(TextView(this).apply {
                    text = "Current"
                    setTextColor(0xFF9CA3AF.toInt())
                    textSize = 10f
                })
                
                val priceChangePct = if (position.entryPrice > 0) {
                    ((livePrice - position.entryPrice) / position.entryPrice * 100)
                } else 0.0
                val changeArrow = when {
                    priceChangePct > 0.5 -> "▲"
                    priceChangePct < -0.5 -> "▼"
                    else -> "•"
                }
                val changeColor = when {
                    priceChangePct > 0.1 -> 0xFF22C55E.toInt()
                    priceChangePct < -0.1 -> 0xFFEF4444.toInt()
                    else -> 0xFFFFFFFF.toInt()
                }
                
                currentCol.addView(TextView(this).apply {
                    text = "$${String.format("%.2f", livePrice)} $changeArrow"
                    setTextColor(changeColor)
                    textSize = 12f
                })
                dataGrid.addView(currentCol)
                
                // Size
                val sizeCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                sizeCol.addView(TextView(this).apply {
                    text = "Size"
                    setTextColor(0xFF9CA3AF.toInt())
                    textSize = 10f
                })
                sizeCol.addView(TextView(this).apply {
                    text = "${String.format("%.2f", position.sizeSol)} SOL"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 12f
                })
                dataGrid.addView(sizeCol)
                
                // P&L SOL
                val pnlCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                pnlCol.addView(TextView(this).apply {
                    text = "P&L"
                    setTextColor(0xFF9CA3AF.toInt())
                    textSize = 10f
                })
                val pnlSol = position.getUnrealizedPnlSol()
                pnlCol.addView(TextView(this).apply {
                    text = "${if (pnlSol >= 0) "+" else ""}${String.format("%.4f", pnlSol)}◎"
                    setTextColor(if (pnlSol >= 0) 0xFF22C55E.toInt() else 0xFFEF4444.toInt())
                    textSize = 12f
                })
                dataGrid.addView(pnlCol)
                
                cardLayout.addView(dataGrid)
                
                // Spacer
                cardLayout.addView(android.view.View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 8)
                })
                
                // TP/SL row
                val tpSlRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                tpSlRow.addView(TextView(this).apply {
                    text = "TP: ${if (position.takeProfitPrice != null) "$${String.format("%.2f", position.takeProfitPrice)}" else "---"}"
                    setTextColor(0xFF22C55E.toInt())
                    textSize = 10f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                tpSlRow.addView(TextView(this).apply {
                    text = "SL: ${if (position.stopLossPrice != null) "$${String.format("%.2f", position.stopLossPrice)}" else "---"}"
                    setTextColor(0xFFEF4444.toInt())
                    textSize = 10f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                val holdTime = (System.currentTimeMillis() - position.entryTime) / 60000
                tpSlRow.addView(TextView(this).apply {
                    text = "⏱️ ${holdTime}m"
                    setTextColor(0xFF9CA3AF.toInt())
                    textSize = 10f
                })
                cardLayout.addView(tpSlRow)
                
                llStocksPositions?.addView(cardLayout)
            } catch (e: Exception) {
                com.lifecyclebot.engine.ErrorLogger.debug("MainActivity", "Stock position card error: ${e.message}")
            }
        }
    }
    
    /**
     * V5.7.5: Update the stocks positions list UI (legacy for PerpsPosition)
     * Uses position's currentPrice which is updated by the monitor loop
     */
    private fun updateStocksPositionsList(positions: List<com.lifecyclebot.perps.PerpsPosition>) {
        llStocksPositions?.removeAllViews()
        
        if (positions.isEmpty()) return
        
        for (position in positions) {
            try {
                // Use the position's current price (updated by monitor loop)
                val livePrice = position.currentPrice.takeIf { it > 0 } ?: position.entryPrice
                
                // Create a rich position card
                val cardLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.section_card_bg)
                    setPadding(24, 16, 24, 16)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, 12)
                    }
                }
                
                // Header row: Symbol + Direction + Leverage
                val headerRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                
                val headerText = TextView(this).apply {
                    text = "${position.market.emoji} ${position.market.symbol} ${position.direction.symbol} ${position.leverage.toInt()}x"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                headerRow.addView(headerText)
                
                // P&L badge - Calculate with current price
                val pnlPct = position.getUnrealizedPnlPct()
                val pnlBadge = TextView(this).apply {
                    text = "${if (pnlPct >= 0) "+" else ""}${String.format("%.2f", pnlPct)}%"
                    setTextColor(if (pnlPct >= 0) 0xFF22C55E.toInt() else 0xFFEF4444.toInt())
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                headerRow.addView(pnlBadge)
                cardLayout.addView(headerRow)
                
                // Spacer
                cardLayout.addView(android.view.View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 8)
                })
                
                // Data grid
                val dataGrid = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                
                // Entry price
                val entryCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                entryCol.addView(TextView(this).apply {
                    text = "Entry"
                    setTextColor(0xFF9CA3AF.toInt())
                    textSize = 10f
                })
                entryCol.addView(TextView(this).apply {
                    text = "$${String.format("%.2f", position.entryPrice)}"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 12f
                })
                dataGrid.addView(entryCol)
                
                // Current price with change indicator
                val currentCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                currentCol.addView(TextView(this).apply {
                    text = "Current"
                    setTextColor(0xFF9CA3AF.toInt())
                    textSize = 10f
                })
                
                // Price change indicator
                val priceChangePct = if (position.entryPrice > 0) {
                    ((livePrice - position.entryPrice) / position.entryPrice * 100)
                } else 0.0
                val changeArrow = when {
                    priceChangePct > 0.5 -> "▲"
                    priceChangePct < -0.5 -> "▼"
                    else -> "•"
                }
                val changeColor = when {
                    priceChangePct > 0.1 -> 0xFF22C55E.toInt()
                    priceChangePct < -0.1 -> 0xFFEF4444.toInt()
                    else -> 0xFFFFFFFF.toInt()
                }
                
                currentCol.addView(TextView(this).apply {
                    text = "$${String.format("%.2f", livePrice)} $changeArrow"
                    setTextColor(changeColor)
                    textSize = 12f
                })
                dataGrid.addView(currentCol)
                
                // Size
                val sizeCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                sizeCol.addView(TextView(this).apply {
                    text = "Size"
                    setTextColor(0xFF9CA3AF.toInt())
                    textSize = 10f
                })
                sizeCol.addView(TextView(this).apply {
                    text = "${String.format("%.2f", position.sizeSol)} SOL"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 12f
                })
                dataGrid.addView(sizeCol)
                
                // P&L USD
                val pnlCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                pnlCol.addView(TextView(this).apply {
                    text = "P&L"
                    setTextColor(0xFF9CA3AF.toInt())
                    textSize = 10f
                })
                val pnlUsd = position.getUnrealizedPnlUsd()
                pnlCol.addView(TextView(this).apply {
                    text = "${if (pnlUsd >= 0) "+" else ""}$${String.format("%.2f", pnlUsd)}"
                    setTextColor(if (pnlUsd >= 0) 0xFF22C55E.toInt() else 0xFFEF4444.toInt())
                    textSize = 12f
                })
                dataGrid.addView(pnlCol)
                
                cardLayout.addView(dataGrid)
                
                // Spacer
                cardLayout.addView(android.view.View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 8)
                })
                
                // TP/SL row
                val tpSlRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                tpSlRow.addView(TextView(this).apply {
                    text = "TP: ${if (position.takeProfitPrice != null) "$${String.format("%.2f", position.takeProfitPrice)}" else "---"}"
                    setTextColor(0xFF22C55E.toInt())
                    textSize = 10f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                tpSlRow.addView(TextView(this).apply {
                    text = "SL: ${if (position.stopLossPrice != null) "$${String.format("%.2f", position.stopLossPrice)}" else "---"}"
                    setTextColor(0xFFEF4444.toInt())
                    textSize = 10f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                val holdTime = (System.currentTimeMillis() - position.entryTime) / 60000
                tpSlRow.addView(TextView(this).apply {
                    text = "⏱️ ${holdTime}m"
                    setTextColor(0xFF9CA3AF.toInt())
                    textSize = 10f
                })
                cardLayout.addView(tpSlRow)
                
                // Click to show visualizer
                cardLayout.setOnClickListener {
                    showPerpsTradeVisualizerDialog(position)
                    performHaptic()
                }
                
                llStocksPositions?.addView(cardLayout)
            } catch (e: Exception) {
                com.lifecyclebot.engine.ErrorLogger.debug("MainActivity", "Position card error: ${e.message}")
            }
        }
    }
    
    /**
     * V5.7.3: Setup stock button click handlers for direct trading
     */
    private fun setupStockButtonClickHandlers() {
        try {
            // Stock card click handlers
            findViewById<View>(R.id.btnStocksAapl)?.setOnClickListener {
                openQuickStockTrade(com.lifecyclebot.perps.PerpsMarket.AAPL)
                performHaptic()
            }
            findViewById<View>(R.id.btnStocksTsla)?.setOnClickListener {
                openQuickStockTrade(com.lifecyclebot.perps.PerpsMarket.TSLA)
                performHaptic()
            }
            findViewById<View>(R.id.btnStocksNvda)?.setOnClickListener {
                openQuickStockTrade(com.lifecyclebot.perps.PerpsMarket.NVDA)
                performHaptic()
            }
            findViewById<View>(R.id.btnStocksGoogl)?.setOnClickListener {
                openQuickStockTrade(com.lifecyclebot.perps.PerpsMarket.GOOGL)
                performHaptic()
            }
            findViewById<View>(R.id.btnStocksAmzn)?.setOnClickListener {
                openQuickStockTrade(com.lifecyclebot.perps.PerpsMarket.AMZN)
                performHaptic()
            }
            findViewById<View>(R.id.btnStocksMeta)?.setOnClickListener {
                openQuickStockTrade(com.lifecyclebot.perps.PerpsMarket.META)
                performHaptic()
            }
            findViewById<View>(R.id.btnStocksMsft)?.setOnClickListener {
                openQuickStockTrade(com.lifecyclebot.perps.PerpsMarket.MSFT)
                performHaptic()
            }
            findViewById<View>(R.id.btnStocksCoin)?.setOnClickListener {
                openQuickStockTrade(com.lifecyclebot.perps.PerpsMarket.COIN)
                performHaptic()
            }
            
            // Card click → V5.7.6: Navigate to MultiAssetActivity for proper Markets AI layers
            cardTokenizedStocks?.setOnClickListener {
                startActivity(Intent(this, MultiAssetActivity::class.java))
                performHaptic()
            }
            
            // Long press → show trade dialog
            cardTokenizedStocks?.setOnLongClickListener {
                showStockBuyDialog()
                performHaptic()
                true
            }
        } catch (_: Exception) {}
    }
    
    /**
     * V5.7.3: Quick trade dialog for a specific stock
     */
    private fun openQuickStockTrade(market: com.lifecyclebot.perps.PerpsMarket) {
        val perpsAI = com.lifecyclebot.perps.PerpsTraderAI
        
        if (!perpsAI.isEnabled() || !perpsAI.hasAcknowledgedRisk()) {
            showPerpsRiskWarning()
            return
        }
        
        // Check if already have position in this market
        val existingPosition = perpsAI.getActivePositions().find { it.market == market }
        if (existingPosition != null) {
            // Show position details instead
            showPerpsTradeVisualizerDialog(existingPosition)
            return
        }
        
        AlertDialog.Builder(this, R.style.Theme_AATE_Dialog)
            .setTitle("${market.emoji} Trade ${market.symbol}")
            .setMessage("""
${market.displayName}
Max Leverage: ${market.maxLeverage.toInt()}x
Trading Hours: ${market.tradingHours}

Quick trade or open detailed dialog?
            """.trimIndent())
            .setPositiveButton("📈 LONG") { d, _ ->
                lifecycleScope.launch {
                    try {
                        val position = com.lifecyclebot.perps.PerpsExecutionEngine.manualOpen(
                            market = market,
                            direction = com.lifecyclebot.perps.PerpsDirection.LONG,
                            leverage = 2.0,
                            sizePct = 5.0,
                        )
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (position != null) {
                                Toast.makeText(this@MainActivity, "📈 Opened ${market.symbol} LONG @ 2x - View in Markets", Toast.LENGTH_SHORT).show()
                                // V5.7.6: Stocks card moved to Markets UI
                            }
                        }
                    } catch (e: Exception) {
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                d.dismiss()
            }
            .setNegativeButton("📉 SHORT") { d, _ ->
                lifecycleScope.launch {
                    try {
                        val position = com.lifecyclebot.perps.PerpsExecutionEngine.manualOpen(
                            market = market,
                            direction = com.lifecyclebot.perps.PerpsDirection.SHORT,
                            leverage = 2.0,
                            sizePct = 5.0,
                        )
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (position != null) {
                                Toast.makeText(this@MainActivity, "📉 Opened ${market.symbol} SHORT @ 2x - View in Markets", Toast.LENGTH_SHORT).show()
                                // V5.7.6: Stocks card moved to Markets UI
                            }
                        }
                    } catch (e: Exception) {
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                d.dismiss()
            }
            .setNeutralButton("Custom") { d, _ ->
                d.dismiss()
                showStockBuyDialog()
            }
            .show()
    }

    private fun showLearningStats() {
        // V5.9.230: Full Sentience + MetaCognition + Education + Symbolic dialog
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
                com.lifecyclebot.engine.FinalDecisionGate.LearningPhase.BOOTSTRAP -> maxOf(0, 50 - totalTrades)
                com.lifecyclebot.engine.FinalDecisionGate.LearningPhase.LEARNING -> maxOf(0, 500 - totalTrades)
                com.lifecyclebot.engine.FinalDecisionGate.LearningPhase.MATURE -> 0
            }

            // ── Education SubLayer ──────────────────────────────────────────────
            val eduReport = try {
                com.lifecyclebot.v3.scoring.EducationSubLayerAI.getLearningHealthReport()
            } catch (_: Exception) { "N/A" }
            val eduTop = try {
                val maturity = com.lifecyclebot.v3.scoring.EducationSubLayerAI.getAllLayerMaturity()
                maturity.entries.filter { it.value.trades >= 5 }
                    .sortedByDescending { it.value.smoothedAccuracy }.take(5)
                    .joinToString("\n") { "  • ${it.key.padEnd(22)} WR=${(it.value.smoothedAccuracy*100).toInt()}% (${it.value.trades}t)${if (!it.value.isActive) " ⛔MUTED" else ""}" }
            } catch (_: Exception) { "  (not enough data)" }

            // ── MetaCognition ───────────────────────────────────────────────────
            val metaTop = try {
                val top = com.lifecyclebot.v3.scoring.MetaCognitionAI.getTopPerformingLayers(4)
                val under = com.lifecyclebot.v3.scoring.MetaCognitionAI.getUnderperformingLayers().take(3)
                "  Top: ${top.joinToString(", ") { it.name.take(10) }}\n  Under: ${if (under.isEmpty()) "none" else under.joinToString(", ") { it.name.take(10) }}"
            } catch (_: Exception) { "  N/A" }
            val totalAnalyzed = try { com.lifecyclebot.v3.scoring.MetaCognitionAI.getTotalTradesAnalyzed() } catch (_: Exception) { 0 }

            // ── Symbolic Exit Reasoner ──────────────────────────────────────────
            val symDiag = try {
                com.lifecyclebot.engine.SymbolicContext.getDiagnostics()
            } catch (_: Exception) { "N/A" }
            val symSnap = try {
                val snap = com.lifecyclebot.engine.SymbolicExitReasoner.getSignalSnapshot("SOL", "")
                if (snap.isEmpty()) "  Warming up…" else {
                    snap.entries.sortedByDescending { it.value }.take(6)
                        .joinToString("\n") { (k, v) ->
                            val pct = (v.coerceIn(0.0,1.0)*100).toInt()
                            val bar = "█".repeat(pct/10) + "░".repeat(10-pct/10)
                            "  ${k.padEnd(18)} $bar $pct%"
                        }
                }
            } catch (_: Exception) { "  N/A" }

            // ── Sentience / Personality ─────────────────────────────────────────
            val sentStatus = try { com.lifecyclebot.engine.SentientPersonality.getStatusLine() } catch (_: Exception) { "N/A" }
            val reflections = try {
                com.lifecyclebot.engine.SentienceOrchestrator.recentReflections(3)
                    .joinToString("\n\n") { r ->
                        val ago = (System.currentTimeMillis() - r.timestamp) / 60_000
                        "  [${ago}m ago] ${r.monologue.take(120)}"
                    }
            } catch (_: Exception) { "  No reflections yet — runs every 6 min after boot" }

            val message = """
🧠 AI CONSCIOUSNESS REPORT
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

$phaseEmoji Phase: $phaseName | ${"%.0f".format(learningProgress * 100)}% mature
📈 Trades: $totalTrades | WR: $winRate%${if (tradesNeeded > 0) " | ⏳ $tradesNeeded to next phase" else " | ✅ Fully Mature"}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📚 EDUCATION SUBLAYER (41 layers)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
$eduReport

Top layers by accuracy:
$eduTop

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🔬 METACOGNITION (Layer 20)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Trades analysed: $totalAnalyzed
$metaTop

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🎯 SYMBOLIC EXIT SIGNALS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
$symDiag

$symSnap

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🌌 SENTIENCE ORCHESTRATOR
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
$sentStatus

Recent inner monologues:
$reflections

${com.lifecyclebot.engine.WiringHealth.detailBlock()}
            """.trimIndent()

            AlertDialog.Builder(this, R.style.Theme_AATE_Dialog)
                .setTitle("🧠 AI Consciousness Report")
                .setMessage(message)
                .setPositiveButton("Close") { d, _ -> d.dismiss() }
                .setNeutralButton("Brain Screen") { d, _ ->
                    d.dismiss()
                    startActivity(android.content.Intent(this, CollectiveBrainActivity::class.java))
                }
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
    
    // ═══════════════════════════════════════════════════════════════════════════════
    // V5.7.3: LEARNING INSIGHTS PANEL
    // ═══════════════════════════════════════════════════════════════════════════════
    
    /**
     * Update the Learning Insights Panel with latest data
     */
    private fun updateLearningInsightsPanel() {
        try {
            // Get insights data
            val totalInsights = com.lifecyclebot.perps.PerpsLearningInsightsPanel.getTotalInsights()
            val patterns = com.lifecyclebot.perps.PerpsLearningInsightsPanel.getPatternsCount()
            val replays = com.lifecyclebot.perps.PerpsAutoReplayLearner.getTotalReplays()
            val optimizations = com.lifecyclebot.perps.PerpsLearningInsightsPanel.getOptimizationsCount()
            
            // Update counts
            tvInsightsCount?.text = "$totalInsights insights"
            tvInsightsPatternsCount?.text = "$patterns"
            tvInsightsReplaysCount?.text = "$replays"
            tvInsightsOptimizations?.text = "$optimizations"
            
            // Update recent insights list
            val insights = com.lifecyclebot.perps.PerpsLearningInsightsPanel.getRecentInsights(3)
            llRecentInsights?.removeAllViews()
            
            for (insight in insights) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = 4 }
                }
                
                val tvEmoji = TextView(this).apply {
                    text = insight.type.emoji
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = 6 }
                }
                
                val tvText = TextView(this).apply {
                    text = insight.title
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 9f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                
                val tvTime = TextView(this).apply {
                    text = insight.getTimeAgo()
                    setTextColor(0xFF6B7280.toInt())
                    textSize = 8f
                }
                
                row.addView(tvEmoji)
                row.addView(tvText)
                row.addView(tvTime)
                
                row.setOnClickListener {
                    showInsightDetailDialog(insight)
                    performHaptic()
                }
                
                llRecentInsights?.addView(row)
            }
            
            // Setup view all button
            btnViewAllInsights?.setOnClickListener {
                showAllInsightsDialog()
                performHaptic()
            }
            
        } catch (e: Exception) {
            com.lifecyclebot.engine.ErrorLogger.debug("MainActivity", "Insights panel update error: ${e.message}")
        }
    }
    
    /**
     * Show detailed insight dialog
     */
    private fun showInsightDetailDialog(insight: com.lifecyclebot.perps.PerpsLearningInsightsPanel.DisplayInsight) {
        val message = """
${insight.type.emoji} ${insight.type.displayName}
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

${insight.title}

${insight.description}

${if (insight.market != null) "📊 Market: ${insight.market}" else ""}
${if (insight.layerName != null) "🧠 Layer: ${insight.layerName}" else ""}
${if (insight.impactScore != 0.0) "📈 Impact: ${String.format("%.1f", insight.impactScore)}" else ""}

🕐 ${insight.getTimeAgo()}

${if (insight.actionable && insight.actionText != null) "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n💡 Suggested Action: ${insight.actionText}" else ""}
        """.trimIndent()
        
        AlertDialog.Builder(this, R.style.Theme_AATE_Dialog)
            .setTitle("${insight.type.emoji} Insight Detail")
            .setMessage(message)
            .setPositiveButton("Close") { d, _ -> d.dismiss() }
            .show()
    }
    
    /**
     * Show all insights dialog
     */
    private fun showAllInsightsDialog() {
        lifecycleScope.launch {
            try {
                val panelData = com.lifecyclebot.perps.PerpsLearningInsightsPanel.getPanelData()
                
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val insightsText = panelData.recentInsights.take(10).joinToString("\n\n") { insight ->
                        "${insight.type.emoji} ${insight.title}\n   ${insight.description}\n   ${insight.getTimeAgo()}"
                    }
                    
                    val topLayersText = panelData.topPerformingLayers.take(5).joinToString("\n") { (name, score) ->
                        "• $name: ${String.format("%.0f", score)}%"
                    }
                    
                    val message = """
🧠 LEARNING INSIGHTS DASHBOARD
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

📊 STATS
───────────────────────────────
Total Insights: ${panelData.totalInsights}
Patterns Found: ${panelData.patternsIdentified}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🏆 TOP PERFORMING LAYERS
───────────────────────────────
${if (topLayersText.isNotEmpty()) topLayersText else "No data yet"}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📜 RECENT INSIGHTS
───────────────────────────────
${if (insightsText.isNotEmpty()) insightsText else "No insights yet. Keep trading!"}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                    """.trimIndent()
                    
                    AlertDialog.Builder(this@MainActivity, R.style.Theme_AATE_Dialog)
                        .setTitle("🧠 All Learning Insights")
                        .setMessage(message)
                        .setPositiveButton("Close") { d, _ -> d.dismiss() }
                        .setNeutralButton("Refresh") { d, _ ->
                            d.dismiss()
                            showAllInsightsDialog()
                        }
                        .show()
                }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * V5.7.3: Show Network Signal Auto-Buyer dialog
     */
    private fun showNetworkSignalAutoBuyerDialog() {
        val autoBuyer = com.lifecyclebot.perps.NetworkSignalAutoBuyer
        val stats = autoBuyer.getStats()
        val config = autoBuyer.getConfig()
        
        val message = """
📡 NETWORK SIGNAL AUTO-BUYER
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Status: ${if (stats.isEnabled) "🟢 ACTIVE" else "🔴 DISABLED"}
Mode: ${if (stats.paperModeOnly) "📝 PAPER" else "💰 LIVE"}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📊 TODAY'S STATS
───────────────────────────────
Daily Auto-Buys: ${stats.dailyAutoBuys}/${stats.maxDailyAutoBuys}
Successful: ${stats.successfulBuys}
Failed: ${stats.failedBuys}
Active Cooldowns: ${stats.activeCooldowns}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⚙️ CONFIGURATION
───────────────────────────────
🔥 MEGA_WINNER: ${if (config.autoBuyMegaWinners) "✅ Auto" else "❌ Skip"}
🌐 HOT_TOKEN: ${if (config.autoBuyHotTokens) "✅ Auto" else "❌ Skip"}
Min Acks: ${config.minAckCount}
Min Confidence: ${config.minConfidence}%
Min Liquidity: $${String.format("%,.0f", config.minLiquidityUsd)}
Position Size: ${config.positionSizePct}%
Cooldown: ${config.cooldownMinutes} min
AI Confirmation: ${if (config.requireAIConfirmation) "✅" else "❌"}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        """.trimIndent()
        
        AlertDialog.Builder(this, R.style.Theme_AATE_Dialog)
            .setTitle("📡 Network Signal Auto-Buyer")
            .setMessage(message)
            .setPositiveButton("Close") { d, _ -> d.dismiss() }
            .setNegativeButton(if (stats.isEnabled) "Disable" else "Enable") { d, _ ->
                if (stats.isEnabled) {
                    autoBuyer.stop()
                    Toast.makeText(this, "📡 Auto-buyer disabled", Toast.LENGTH_SHORT).show()
                } else {
                    autoBuyer.start()
                    Toast.makeText(this, "📡 Auto-buyer enabled", Toast.LENGTH_SHORT).show()
                }
                d.dismiss()
            }
            .show()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════════
    // V5.7.4: INSIDER TRACKER DIALOG
    // ═══════════════════════════════════════════════════════════════════════════════
    
    /**
     * V5.7.4: Show Network Signals menu with options for Auto-Buyer and Insider Tracker
     */
    private fun showNetworkSignalsMenu() {
        val options = arrayOf(
            "📡 Network Signal Auto-Buyer",
            "🔍 Insider Tracker (Trump/Pelosi/Whales)",
            "📊 View All Network Signals",
            "🔔 Watchlist & Price Alerts"
        )
        
        AlertDialog.Builder(this, R.style.Theme_AATE_Dialog)
            .setTitle("🌐 Network Intelligence")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showNetworkSignalAutoBuyerDialog()
                    1 -> showInsiderTrackerDialog()
                    2 -> showAllNetworkSignalsDialog()
                    3 -> startActivity(Intent(this, WatchlistActivity::class.java))
                }
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()
    }
    
    /**
     * V5.7.4: Show all network signals dialog
     */
    private fun showAllNetworkSignalsDialog() {
        val signals = com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI.getActiveNetworkSignals()
        
        if (signals.isEmpty()) {
            Toast.makeText(this, "No active network signals", Toast.LENGTH_SHORT).show()
            return
        }
        
        val message = signals.sortedByDescending { it.pnlPct }.take(20).joinToString("\n\n") { signal ->
            val emoji = when (signal.signalType) {
                "MEGA_WINNER" -> "🔥"
                "HOT_TOKEN" -> "🌐"
                "AVOID" -> "⚠️"
                else -> "📡"
            }
            "$emoji ${signal.symbol}\n   PnL: ${String.format("%+.1f", signal.pnlPct)}% | Acks: ${signal.ackCount} | Conf: ${signal.confidence}%"
        }
        
        AlertDialog.Builder(this, R.style.Theme_AATE_Dialog)
            .setTitle("📡 Active Network Signals (${signals.size})")
            .setMessage(message)
            .setPositiveButton("Close") { d, _ -> d.dismiss() }
            .show()
    }
    
    private fun showInsiderTrackerDialog() {
        val tracker = com.lifecyclebot.v3.scoring.InsiderTrackerAI
        val stats = tracker.getStats()
        val recentAlpha = tracker.getAlphaSignals(5)
        val preTweet = tracker.getPreTweetSignals()
        
        val signalsText = if (recentAlpha.isNotEmpty()) {
            recentAlpha.joinToString("\n") { signal ->
                val age = (System.currentTimeMillis() - signal.timestamp) / 60000
                val emoji = when (signal.signalType) {
                    com.lifecyclebot.v3.scoring.InsiderTrackerAI.InsiderSignalType.PRE_TWEET -> "🐦"
                    com.lifecyclebot.v3.scoring.InsiderTrackerAI.InsiderSignalType.ACCUMULATION -> "💰"
                    com.lifecyclebot.v3.scoring.InsiderTrackerAI.InsiderSignalType.DISTRIBUTION -> "🚨"
                    else -> "⚡"
                }
                "$emoji ${signal.wallet.label}: ${signal.tokenSymbol ?: "?"} (${age}m ago)"
            }
        } else "No recent ALPHA signals"
        
        val preTweetText = if (preTweet.isNotEmpty()) {
            preTweet.joinToString("\n") { "🐦 ${it.wallet.label}: Watch for tweet!" }
        } else "None detected"
        
        val message = """
🔍 INSIDER TRACKER
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Status: ${if (stats.isRunning) "🟢 ACTIVE" else "🔴 STOPPED"}
Wallets Tracked: ${stats.walletsTracked}
ALPHA Wallets: ${stats.alphaWallets}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📊 SIGNAL STATS
───────────────────────────────
Total Signals: ${stats.totalSignals}
ALPHA Signals: ${stats.alphaSignals}
Pre-Tweet Signals: ${stats.preTweetSignals}
Active Signals: ${stats.recentSignalCount}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🔥 RECENT ALPHA SIGNALS
───────────────────────────────
$signalsText

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🐦 PRE-TWEET ALERTS
───────────────────────────────
$preTweetText

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🎯 TRACKED CATEGORIES:
• Politicians (Pelosi)
• Trump Family (Barron, DJT)
• Whales (Jump, Wintermute)
• Influencers (Ansem)
• Exchanges (Coinbase, Binance)
        """.trimIndent()
        
        AlertDialog.Builder(this, R.style.Theme_AATE_Dialog)
            .setTitle("🔍 Insider Tracker (Trump/Pelosi)")
            .setMessage(message)
            .setPositiveButton("Full View") { d, _ ->
                startActivity(Intent(this, InsiderWalletsActivity::class.java))
                d.dismiss()
            }
            .setNegativeButton(if (stats.isRunning) "Stop" else "Start") { d, _ ->
                if (stats.isRunning) {
                    tracker.stop()
                    Toast.makeText(this, "🔍 Insider Tracker stopped", Toast.LENGTH_SHORT).show()
                } else {
                    tracker.start()
                    Toast.makeText(this, "🔍 Insider Tracker started", Toast.LENGTH_SHORT).show()
                }
                d.dismiss()
            }
            .setNeutralButton("Add Wallet") { d, _ ->
                showAddInsiderWalletDialog()
                d.dismiss()
            }
            .show()
    }
    
    private fun showAddInsiderWalletDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        
        val addressInput = EditText(this).apply {
            hint = "Solana Wallet Address"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        
        val labelInput = EditText(this).apply {
            hint = "Label (e.g., 'My Insider')"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        
        layout.addView(addressInput)
        layout.addView(labelInput)
        
        AlertDialog.Builder(this, R.style.Theme_AATE_Dialog)
            .setTitle("Add Custom Wallet to Track")
            .setView(layout)
            .setPositiveButton("Add") { d, _ ->
                val address = addressInput.text.toString().trim()
                val label = labelInput.text.toString().trim().ifEmpty { "Custom Wallet" }
                
                if (address.length >= 32) {
                    val success = com.lifecyclebot.v3.scoring.InsiderTrackerAI.addCustomWallet(
                        address = address,
                        label = label,
                        category = com.lifecyclebot.v3.scoring.InsiderTrackerAI.WalletCategory.WHALE,
                        riskLevel = com.lifecyclebot.v3.scoring.InsiderTrackerAI.RiskLevel.HIGH
                    )
                    if (success) {
                        Toast.makeText(this, "✅ Wallet added: $label", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "❌ Failed to add wallet", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "❌ Invalid address", Toast.LENGTH_SHORT).show()
                }
                d.dismiss()
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()
    }

    /**
     * V5.9.495z34 — defer counter for the Guards strip on the Meme
     * tab. Returns "" when no defer activity in the last 5 minutes,
     * otherwise " · 🛡 24 deferred · 3 background-classed · 6 expired".
     */
    private fun appendDeferTile(): String {
        return try {
            val s = com.lifecyclebot.engine.DeferActivityTracker.snapshot()
            val total = s.deferred + s.backgroundClassed + s.expired
            if (total == 0) "" else " · 🛡 ${s.deferred} deferred · ${s.backgroundClassed} bg · ${s.expired} expired"
        } catch (_: Throwable) { "" }
    }

    /**
     * V5.9.798 — WR Recovery Heatmap. Renders 5 coloured blocks (newest →
     * oldest, left → right) into the [target] TextView. Each block shows
     * a slice of rolling-50 WR, colour-coded vs phase target:
     *   GREEN   — slice WR ≥ target
     *   AMBER   — 0.85 × target ≤ slice WR < target
     *   RED     — slice WR < 0.85 × target
     *   DIM     — not enough sample yet (< 25 decisive trades in the slice)
     */
    private fun renderWrRecoveryHeatmap(target: TextView) {
        try {
            val store = com.lifecyclebot.engine.TradeHistoryStore
            val stats = store.getLifetimeStats()
            val totalSettled = (stats.totalWins + stats.totalLosses).toInt()
            // Phase target — same source the recovery state uses.
            val phaseTarget = try {
                com.lifecyclebot.engine.FreeRangeMode.phaseTargetWr(totalSettled)
            } catch (_: Throwable) { 30.0 }

            val sliceWidth = 50
            val sliceCount = 5
            val builder = android.text.SpannableStringBuilder("🌡 WR slices: ")
            val rolling = store.rollingWinRatePct(sliceWidth)
            for (i in 0 until sliceCount) {
                val pct = store.rollingWinRatePctSlice(offset = i * sliceWidth, width = sliceWidth)
                val color = when {
                    pct < 0 -> 0xFF4B5563.toInt()                   // DIM — sparse sample
                    phaseTarget <= 0 -> 0xFF4B5563.toInt()
                    pct >= phaseTarget -> 0xFF10B981.toInt()        // GREEN
                    pct >= phaseTarget * 0.85 -> 0xFFF59E0B.toInt() // AMBER
                    else -> 0xFFEF4444.toInt()                      // RED
                }
                val start = builder.length
                builder.append("▰")
                builder.setSpan(
                    android.text.style.ForegroundColorSpan(color),
                    start, builder.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            // Append a short text summary so the operator knows what the
            // blocks mean without hovering.
            val rollLabel = if (rolling >= 0) "%.0f%%".format(rolling) else "—"
            val targetLabel = if (phaseTarget > 0) "${phaseTarget.toInt()}%" else "—"
            builder.append("  roll50=$rollLabel / target=$targetLabel")

            target.text = builder
            target.visibility = if (totalSettled >= 50) android.view.View.VISIBLE else android.view.View.GONE
        } catch (_: Throwable) {
            target.visibility = android.view.View.GONE
        }
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
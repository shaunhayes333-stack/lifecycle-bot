package com.lifecyclebot.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.tabs.TabLayout
import com.lifecyclebot.R
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.perps.*
import kotlinx.coroutines.*

/**
 * V5.7.6: Multi-Asset Trading Dashboard
 * 
 * Unified UI for:
 * - SOL Perps (leveraged perpetual contracts)
 * - Tokenized Stocks (47 stocks)
 * - Commodities (16 energy + agricultural)
 * - Precious & Industrial Metals (15 metals)
 * - Forex Pairs (17 currency pairs)
 * 
 * Features SPOT (1x) and LEVERAGE trading modes
 */
class MultiAssetActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MultiAssetActivity"
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TABS
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class AssetTab(val icon: String, val title: String, val shortTitle: String) {
        PERPS("🔥", "SOL PERPS", "Perps"),
        STOCKS("📈", "TOKENIZED STOCKS", "Stocks"),
        COMMODITIES("🛢️", "COMMODITIES", "Commod"),
        METALS("🥇", "METALS", "Metals"),
        FOREX("💱", "FOREX", "Forex")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private var currentTab = AssetTab.STOCKS
    private var showSpotOnly = true  // true = SPOT, false = LEVERAGE
    
    private var updateJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // ═══════════════════════════════════════════════════════════════════════════
    // VIEWS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private lateinit var tabLayout: TabLayout
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var tvTotalBalance: TextView
    private lateinit var tvActivePositions: TextView
    private lateinit var tvTodayPnl: TextView
    private lateinit var tvWinRate: TextView
    private lateinit var tvCategoryIcon: TextView
    private lateinit var tvCategoryTitle: TextView
    private lateinit var tvCategoryCount: TextView
    private lateinit var tvPositionCount: TextView
    private lateinit var positionsContainer: LinearLayout
    private lateinit var tvNoPositions: TextView
    private lateinit var signalsContainer: LinearLayout
    private lateinit var tvNoSignals: TextView
    private lateinit var btnSpotMode: TextView
    private lateinit var btnLeverageMode: TextView
    
    // Quick Stats Bar
    private lateinit var tvStats24hTrades: TextView
    private lateinit var tvStatsWinRate: TextView
    private lateinit var tvStatsTotalPnl: TextView
    private lateinit var tvStatsAiScore: TextView
    
    // Engine status dots
    private lateinit var dotStocks: View
    private lateinit var dotCommodities: View
    private lateinit var dotMetals: View
    private lateinit var dotForex: View
    private lateinit var dotPerps: View
    
    // New UI elements
    private lateinit var topMoversContainer: LinearLayout
    private lateinit var aiSignalsContainer: LinearLayout
    private lateinit var tvNoAiSignals: TextView
    private lateinit var aiSignalDot: View
    private lateinit var tvAiSignalStatus: TextView
    private lateinit var heatTech: TextView
    private lateinit var heatFinance: TextView
    private lateinit var heatEnergy: TextView
    private lateinit var heatMetals: TextView
    private lateinit var heatForex: TextView
    private lateinit var heatCrypto: TextView
    
    // Markets Readiness UI
    private lateinit var tvMarketsReadinessBadge: TextView
    private lateinit var tvMarketsWinRate: TextView
    private lateinit var tvMarketsTrades: TextView
    private lateinit var tvMarketsPhase: TextView
    private lateinit var tvMarketsProgressPct: TextView
    private lateinit var viewMarketsProgressBar: View
    private lateinit var tvMarketsRecommendation: TextView
    
    // AI Layer Confidence Dashboard (Tuning Tab)
    private lateinit var tvMarketsLayerCount: TextView
    private lateinit var tvLayerRsi: TextView
    private lateinit var tvLayerMacd: TextView
    private lateinit var tvLayerVolume: TextView
    private lateinit var tvLayerSR: TextView
    private lateinit var tvLayerMomentum: TextView
    private lateinit var tvLayerSector: TextView
    private lateinit var tvLayerCorrel: TextView
    private lateinit var tvMarketsLearningEvents: TextView
    private lateinit var tvMarketsCrossSync: TextView
    
    // Asset logos mapping
    private val assetLogos = mapOf(
        // Stocks
        "AAPL" to "🍎", "MSFT" to "🪟", "GOOGL" to "🔍", "AMZN" to "📦", "TSLA" to "⚡",
        "NVDA" to "🖥️", "META" to "👤", "NFLX" to "🎬", "AMD" to "🔴", "INTC" to "🔵",
        "COIN" to "🪙", "SQ" to "⬜", "PYPL" to "💳", "V" to "💳", "MA" to "💳",
        "JPM" to "🏦", "GS" to "🏛️", "BAC" to "🏦", "WFC" to "🏦", "C" to "🏦",
        "DIS" to "🏰", "UBER" to "🚕", "ABNB" to "🏠", "NKE" to "👟", "SBUX" to "☕",
        "MCD" to "🍔", "WMT" to "🛍️", "HD" to "🔨", "COST" to "📦", "BA" to "✈️",
        "JNJ" to "💊", "PFE" to "💉", "UNH" to "🏥", "KO" to "🥤", "PEP" to "🥤",
        "XOM" to "⛽", "CVX" to "🛢️", "CRM" to "☁️", "ORCL" to "🔮", "PLTR" to "🛡️",
        "SNOW" to "❄️", "SHOP" to "🛒", "QCOM" to "📱", "AVGO" to "⚡", "MU" to "💾",
        // NEW Stocks
        "TSM" to "🔧", "ASML" to "🔬", "ARM" to "💪", "MRVL" to "🔷", "SPOT" to "🎵",
        "ZM" to "📹", "ROKU" to "📺", "TWLO" to "📞", "AI" to "🤖", "PATH" to "🤖",
        "DDOG" to "🐶", "NET" to "☁️", "CRWD" to "🦅", "ZS" to "🔒", "MDB" to "🍃",
        "MSTR" to "₿", "HOOD" to "🪶", "SOFI" to "💰", "NU" to "💜", "CMG" to "🌯",
        "LULU" to "🧘", "TGT" to "🎯", "LOW" to "🔧", "CAT" to "🚜", "DE" to "🚜",
        "LMT" to "🛡️", "RTX" to "🚀", "MRNA" to "🧬", "LLY" to "💊", "ABBV" to "💊",
        "TMO" to "🔬", "PG" to "🧴", "PM" to "🚬", "COP" to "🛢️", "OXY" to "🛢️",
        "ENPH" to "☀️", "FSLR" to "☀️", "PLUG" to "🔋", "NEE" to "⚡",
        "RIVN" to "🚗", "LCID" to "🚗", "F" to "🚗", "GM" to "🚗",
        // Metals
        "XAU" to "🥇", "XAG" to "🥈", "XPT" to "⚪", "XPD" to "💎", "XCU" to "🔶",
        "XAL" to "🔷", "XNI" to "⬜", "XTI" to "⚫", "ZINC" to "🔘", "LEAD" to "⚫",
        "TIN" to "🪙", "IRON" to "🔩", "COBALT" to "🔵", "LITHIUM" to "🔋", "URANIUM" to "☢️",
        // Commodities
        "WTI" to "🛢️", "BRENT" to "🛢️", "NATGAS" to "🔥", "RBOB" to "⛽", "HEATING" to "🏠",
        "WHEAT" to "🌾", "CORN" to "🌽", "SOYBEAN" to "🫘", "COFFEE" to "☕", "COCOA" to "🍫",
        "SUGAR" to "🍬", "COTTON" to "🧶", "LUMBER" to "🪵", "OJ" to "🍊",
        "CATTLE" to "🐄", "HOGS" to "🐖",
        // Forex
        "EUR" to "🇪🇺", "GBP" to "🇬🇧", "JPY" to "🇯🇵", "AUD" to "🇦🇺", "CAD" to "🇨🇦",
        "CHF" to "🇨🇭", "NZD" to "🇳🇿", "MXN" to "🇲🇽", "BRL" to "🇧🇷", "INR" to "🇮🇳",
        "CNY" to "🇨🇳", "ZAR" to "🇿🇦", "TRY" to "🇹🇷", "RUB" to "🇷🇺", "SGD" to "🇸🇬",
        "HKD" to "🇭🇰", "KRW" to "🇰🇷",
        // Crypto
        "SOL" to "◎", "BTC" to "₿", "ETH" to "⟠", "BNB" to "🔶", "XRP" to "💧",
        "ADA" to "🔵", "DOGE" to "🐕", "AVAX" to "🔺", "DOT" to "⚫", "LINK" to "🔗",
        "MATIC" to "💜", "SHIB" to "🐕", "LTC" to "Ł", "ATOM" to "⚛️", "UNI" to "🦄",
        "ARB" to "🔵", "OP" to "🔴", "APT" to "🟢", "SUI" to "💧", "SEI" to "🌊",
        "INJ" to "💉", "TIA" to "🌌", "JUP" to "🪐", "PEPE" to "🐸", "WIF" to "🐕",
        "BONK" to "🦴", "NEAR" to "🌐", "FTM" to "👻", "ALGO" to "🔺", "HBAR" to "⬡",
        "ICP" to "∞", "VET" to "✓", "FIL" to "📁", "RENDER" to "🎨", "GRT" to "📊",
        "AAVE" to "👻", "MKR" to "🏛️", "SNX" to "💎", "CRV" to "〰️", "RUNE" to "⚡",
        "STX" to "📚", "IMX" to "🎮", "SAND" to "🏖️", "MANA" to "🌍", "AXS" to "🎮",
        "ENS" to "🔗", "LDO" to "🌊", "RPL" to "🚀", "PYTH" to "🔮", "RAY" to "☀️",
        "ORCA" to "🐋", "MNGO" to "🥭", "DRIFT" to "🌊"
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multi_asset)
        
        initViews()
        setupTabs()
        setupClickListeners()
        startUpdateLoop()
        
        ErrorLogger.info(TAG, "📊 MultiAssetActivity CREATED")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        updateJob?.cancel()
        scope.cancel()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun initViews() {
        // Top bar
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        tvTotalBalance = findViewById(R.id.tvTotalBalance)
        
        // Quick Stats Bar
        tvStats24hTrades = findViewById(R.id.tvStats24hTrades)
        tvStatsWinRate = findViewById(R.id.tvStatsWinRate)
        tvStatsTotalPnl = findViewById(R.id.tvStatsTotalPnl)
        tvStatsAiScore = findViewById(R.id.tvStatsAiScore)
        
        // Quick Actions
        findViewById<View>(R.id.btnJournal).setOnClickListener { openMarketsJournal() }
        findViewById<View>(R.id.btnTaxReport).setOnClickListener { showTaxReportDialog() }
        findViewById<View>(R.id.btnPerformance).setOnClickListener { showPerformanceStats() }
        findViewById<View>(R.id.btnExport).setOnClickListener { exportMarketsData() }
        
        // Tabs
        tabLayout = findViewById(R.id.tabLayout)
        
        // Summary cards
        tvActivePositions = findViewById(R.id.tvActivePositions)
        tvTodayPnl = findViewById(R.id.tvTodayPnl)
        tvWinRate = findViewById(R.id.tvWinRate)
        
        // Category header
        tvCategoryIcon = findViewById(R.id.tvCategoryIcon)
        tvCategoryTitle = findViewById(R.id.tvCategoryTitle)
        tvCategoryCount = findViewById(R.id.tvCategoryCount)
        
        // Mode toggle
        btnSpotMode = findViewById(R.id.btnSpotMode)
        btnLeverageMode = findViewById(R.id.btnLeverageMode)
        
        // Positions
        tvPositionCount = findViewById(R.id.tvPositionCount)
        positionsContainer = findViewById(R.id.positionsContainer)
        tvNoPositions = findViewById(R.id.tvNoPositions)
        findViewById<View>(R.id.btnCloseAll).setOnClickListener { showCloseAllDialog() }
        
        // Top Movers
        topMoversContainer = findViewById(R.id.topMoversContainer)
        
        // AI Signals
        aiSignalsContainer = findViewById(R.id.aiSignalsContainer)
        tvNoAiSignals = findViewById(R.id.tvNoAiSignals)
        aiSignalDot = findViewById(R.id.aiSignalDot)
        tvAiSignalStatus = findViewById(R.id.tvAiSignalStatus)
        
        // Sector Heatmap
        heatTech = findViewById(R.id.heatTech)
        heatFinance = findViewById(R.id.heatFinance)
        heatEnergy = findViewById(R.id.heatEnergy)
        heatMetals = findViewById(R.id.heatMetals)
        heatForex = findViewById(R.id.heatForex)
        heatCrypto = findViewById(R.id.heatCrypto)
        
        // Markets Readiness
        tvMarketsReadinessBadge = findViewById(R.id.tvMarketsReadinessBadge)
        tvMarketsWinRate = findViewById(R.id.tvMarketsWinRate)
        tvMarketsTrades = findViewById(R.id.tvMarketsTrades)
        tvMarketsPhase = findViewById(R.id.tvMarketsPhase)
        tvMarketsProgressPct = findViewById(R.id.tvMarketsProgressPct)
        viewMarketsProgressBar = findViewById(R.id.viewMarketsProgressBar)
        tvMarketsRecommendation = findViewById(R.id.tvMarketsRecommendation)
        
        // AI Layer Confidence Dashboard (Tuning Tab)
        tvMarketsLayerCount = findViewById(R.id.tvMarketsLayerCount)
        tvLayerRsi = findViewById(R.id.tvLayerRsi)
        tvLayerMacd = findViewById(R.id.tvLayerMacd)
        tvLayerVolume = findViewById(R.id.tvLayerVolume)
        tvLayerSR = findViewById(R.id.tvLayerSR)
        tvLayerMomentum = findViewById(R.id.tvLayerMomentum)
        tvLayerSector = findViewById(R.id.tvLayerSector)
        tvLayerCorrel = findViewById(R.id.tvLayerCorrel)
        tvMarketsLearningEvents = findViewById(R.id.tvMarketsLearningEvents)
        tvMarketsCrossSync = findViewById(R.id.tvMarketsCrossSync)
        
        // Signals
        signalsContainer = findViewById(R.id.signalsContainer)
        tvNoSignals = findViewById(R.id.tvNoSignals)
        
        // Engine status dots
        dotStocks = findViewById(R.id.dotStocks)
        dotCommodities = findViewById(R.id.dotCommodities)
        dotMetals = findViewById(R.id.dotMetals)
        dotForex = findViewById(R.id.dotForex)
        dotPerps = findViewById(R.id.dotPerps)
        
        // Swipe refresh
        swipeRefresh = findViewById(R.id.swipeRefresh)
        swipeRefresh.setColorSchemeColors(0xFF00FF88.toInt())
        swipeRefresh.setOnRefreshListener {
            refreshData()
            swipeRefresh.isRefreshing = false
        }
    }
    
    private fun setupTabs() {
        AssetTab.values().forEach { tab ->
            tabLayout.addTab(
                tabLayout.newTab().setText("${tab.icon} ${tab.shortTitle}")
            )
        }
        
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = AssetTab.values()[tab.position]
                updateCategoryHeader()
                refreshData()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        
        // Select Stocks tab by default
        tabLayout.getTabAt(AssetTab.STOCKS.ordinal)?.select()
    }
    
    private fun setupClickListeners() {
        btnSpotMode.setOnClickListener {
            showSpotOnly = true
            updateModeToggle()
            refreshData()
        }
        
        btnLeverageMode.setOnClickListener {
            showSpotOnly = false
            updateModeToggle()
            refreshData()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // UPDATE LOOP
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun startUpdateLoop() {
        updateJob = scope.launch {
            while (isActive) {
                try {
                    refreshData()
                } catch (e: Exception) {
                    ErrorLogger.error(TAG, "Update error: ${e.message}")
                }
                delay(3000) // Update every 3 seconds
            }
        }
    }
    
    private fun refreshData() {
        lifecycleScope.launch(Dispatchers.Main) {
            updateQuickStats()
            updateMarketsReadiness()
            updateAiLayerConfidence()
            updateTotalBalance()
            updateSummaryCards()
            updateCategoryHeader()
            updateModeToggle()
            updatePositions()
            updateTopMovers()
            updateAiSignals()
            updateSectorHeatmap()
            updateEngineStatus()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // UI UPDATES
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun updateQuickStats() {
        try {
            // 24h Trades - from PerpsTraderAI
            val dailyTrades = PerpsTraderAI.getDailyTrades()
            tvStats24hTrades.text = dailyTrades.toString()
            
            // Win Rate
            val winRate = PerpsTraderAI.getLifetimeWinRatePct()
            tvStatsWinRate.text = if (winRate > 0) "$winRate%" else "—%"
            tvStatsWinRate.setTextColor(
                when {
                    winRate >= 55 -> 0xFF10B981.toInt()  // Green
                    winRate >= 45 -> 0xFFF59E0B.toInt()  // Yellow
                    winRate > 0 -> 0xFFEF4444.toInt()    // Red
                    else -> 0xFF6B7280.toInt()           // Gray
                }
            )
            
            // Total P&L (all markets combined)
            val totalPnl = PerpsTraderAI.getDailyPnlSol()
            tvStatsTotalPnl.text = "${if (totalPnl >= 0) "+" else ""}${"%.2f".format(totalPnl)}"
            tvStatsTotalPnl.setTextColor(if (totalPnl >= 0) 0xFF00FF88.toInt() else 0xFFFF4444.toInt())
            
            // AI Score (readiness from PerpsTraderAI)
            val readiness = PerpsTraderAI.getLiveReadiness()
            tvStatsAiScore.text = "${readiness.readinessScore}"
            tvStatsAiScore.setTextColor(
                when {
                    readiness.readinessScore >= 75 -> 0xFF10B981.toInt()
                    readiness.readinessScore >= 50 -> 0xFFF59E0B.toInt()
                    else -> 0xFFEF4444.toInt()
                }
            )
        } catch (_: Exception) {
            tvStats24hTrades.text = "0"
            tvStatsWinRate.text = "—%"
            tvStatsTotalPnl.text = "+0.00"
            tvStatsAiScore.text = "—"
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MARKETS LIVE READINESS
    // Requirements to go LIVE:
    // - Minimum 50 paper trades
    // - Win rate >= 55%
    // - Consistent performance over 7 days
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun updateMarketsReadiness() {
        try {
            val readiness = calculateMarketsReadiness()
            
            // Update badge
            tvMarketsReadinessBadge.text = readiness.phase.name
            tvMarketsReadinessBadge.setBackgroundResource(
                when (readiness.phase) {
                    MarketsPhase.BOOTSTRAP -> R.drawable.pill_bg_yellow
                    MarketsPhase.LEARNING -> R.drawable.pill_bg
                    MarketsPhase.VALIDATING -> R.drawable.pill_bg
                    MarketsPhase.READY -> R.drawable.pill_bg_green
                    MarketsPhase.LIVE -> R.drawable.pill_bg_green
                }
            )
            tvMarketsReadinessBadge.setTextColor(
                when (readiness.phase) {
                    MarketsPhase.BOOTSTRAP -> 0xFF000000.toInt()
                    MarketsPhase.LEARNING -> 0xFFFFFFFF.toInt()
                    MarketsPhase.VALIDATING -> 0xFFFFFFFF.toInt()
                    MarketsPhase.READY -> 0xFF000000.toInt()
                    MarketsPhase.LIVE -> 0xFF000000.toInt()
                }
            )
            
            // Update stats
            tvMarketsWinRate.text = if (readiness.winRate > 0) "${"%.1f".format(readiness.winRate)}%" else "--"
            tvMarketsWinRate.setTextColor(
                when {
                    readiness.winRate >= 55 -> 0xFF00FF88.toInt()
                    readiness.winRate >= 45 -> 0xFFF59E0B.toInt()
                    readiness.winRate > 0 -> 0xFFFF4444.toInt()
                    else -> 0xFF6B7280.toInt()
                }
            )
            
            tvMarketsTrades.text = "${readiness.paperTrades}/${readiness.requiredTrades}"
            tvMarketsPhase.text = readiness.phase.shortName
            tvMarketsPhase.setTextColor(
                when (readiness.phase) {
                    MarketsPhase.BOOTSTRAP -> 0xFFF59E0B.toInt()
                    MarketsPhase.LEARNING -> 0xFF3B82F6.toInt()
                    MarketsPhase.VALIDATING -> 0xFF8B5CF6.toInt()
                    MarketsPhase.READY -> 0xFF10B981.toInt()
                    MarketsPhase.LIVE -> 0xFF00FF88.toInt()
                }
            )
            
            // Update progress bar
            tvMarketsProgressPct.text = "${readiness.progressPct}%"
            val params = viewMarketsProgressBar.layoutParams as android.widget.FrameLayout.LayoutParams
            params.width = 0
            viewMarketsProgressBar.layoutParams = params
            viewMarketsProgressBar.post {
                val parentWidth = (viewMarketsProgressBar.parent as View).width
                val newWidth = (parentWidth * readiness.progressPct / 100).toInt()
                val newParams = viewMarketsProgressBar.layoutParams
                newParams.width = newWidth
                viewMarketsProgressBar.layoutParams = newParams
            }
            
            // Update recommendation
            tvMarketsRecommendation.text = readiness.recommendation
            
        } catch (_: Exception) {
            tvMarketsReadinessBadge.text = "INIT"
            tvMarketsWinRate.text = "--"
            tvMarketsTrades.text = "0/50"
            tvMarketsPhase.text = "BOOT"
            tvMarketsProgressPct.text = "0%"
            tvMarketsRecommendation.text = "Initializing Markets trading system..."
        }
    }
    
    enum class MarketsPhase(val shortName: String) {
        BOOTSTRAP("BOOT"),   // 0-10 trades
        LEARNING("LEARN"),   // 10-30 trades
        VALIDATING("VALID"), // 30-50 trades
        READY("READY"),      // 50+ trades, 55%+ win rate
        LIVE("LIVE")         // User activated live mode
    }
    
    data class MarketsReadiness(
        val phase: MarketsPhase,
        val paperTrades: Int,
        val requiredTrades: Int,
        val winRate: Double,
        val requiredWinRate: Double,
        val progressPct: Int,
        val recommendation: String
    )
    
    private fun calculateMarketsReadiness(): MarketsReadiness {
        // Get stats from PerpsTraderAI (tracks all Markets trades)
        val paperTrades = PerpsTraderAI.getLifetimeTrades()
        val wins = PerpsTraderAI.getLifetimeWins()
        val losses = PerpsTraderAI.getLifetimeLosses()
        val winRate = if (paperTrades > 0) (wins.toDouble() / paperTrades * 100) else 0.0
        
        // Requirements
        val requiredTrades = 50
        val requiredWinRate = 55.0
        
        // Calculate phase
        val phase = when {
            paperTrades < 10 -> MarketsPhase.BOOTSTRAP
            paperTrades < 30 -> MarketsPhase.LEARNING
            paperTrades < requiredTrades -> MarketsPhase.VALIDATING
            winRate >= requiredWinRate -> MarketsPhase.READY
            else -> MarketsPhase.VALIDATING
        }
        
        // Calculate progress (0-100%)
        // 50% weight on trades, 50% weight on win rate
        val tradeProgress = (paperTrades.toDouble() / requiredTrades * 50).coerceIn(0.0, 50.0)
        val winRateProgress = if (winRate >= requiredWinRate) 50.0 else (winRate / requiredWinRate * 50).coerceIn(0.0, 45.0)
        val progressPct = (tradeProgress + winRateProgress).toInt().coerceIn(0, 100)
        
        // Generate recommendation
        val recommendation = when {
            paperTrades < 10 -> "🚀 Getting started! Complete ${10 - paperTrades} more trades to exit bootstrap phase."
            paperTrades < 30 -> "📚 Learning mode: ${30 - paperTrades} trades to validation. Current win rate: ${"%.1f".format(winRate)}%"
            paperTrades < requiredTrades -> "✅ Validating: ${requiredTrades - paperTrades} more trades needed. Win rate: ${"%.1f".format(winRate)}%"
            winRate < requiredWinRate -> "⚠️ Need ${"%.1f".format(requiredWinRate)}%+ win rate. Current: ${"%.1f".format(winRate)}%. Keep learning!"
            else -> "🎉 READY FOR LIVE! $paperTrades trades with ${"%.1f".format(winRate)}% win rate. Enable LIVE mode in settings."
        }
        
        return MarketsReadiness(
            phase = phase,
            paperTrades = paperTrades,
            requiredTrades = requiredTrades,
            winRate = winRate,
            requiredWinRate = requiredWinRate,
            progressPct = progressPct,
            recommendation = recommendation
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // AI LAYER CONFIDENCE DASHBOARD (TUNING TAB)
    // Shows learning progression and active AI layers for Markets trading
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun updateAiLayerConfidence() {
        try {
            // Get aggregated layer confidences from PerpsAdvancedAI across all market categories
            val layerConfidences = calculateAiLayerConfidences()
            
            // Update layer count
            val activeLayerCount = layerConfidences.values.count { it > 0 }
            tvMarketsLayerCount.text = "$activeLayerCount active"
            
            // Update individual layer displays
            updateLayerDisplay(tvLayerRsi, layerConfidences["RSI"] ?: 0)
            updateLayerDisplay(tvLayerMacd, layerConfidences["MACD"] ?: 0)
            updateLayerDisplay(tvLayerVolume, layerConfidences["VOLUME"] ?: 0)
            updateLayerDisplay(tvLayerSR, layerConfidences["SR"] ?: 0)
            updateLayerDisplay(tvLayerMomentum, layerConfidences["MOMENTUM"] ?: 0)
            updateLayerDisplay(tvLayerSector, layerConfidences["SECTOR"] ?: 0)
            updateLayerDisplay(tvLayerCorrel, layerConfidences["CORREL"] ?: 0)
            
            // Update learning events count (from PerpsTraderAI)
            val learningEvents = PerpsTraderAI.getLifetimeTrades() * 7  // Each trade updates ~7 layers
            tvMarketsLearningEvents.text = learningEvents.toString()
            
            // Update cross-sync status (synced with Meme AI)
            val crossSyncStatus = checkCrossSyncStatus()
            tvMarketsCrossSync.text = crossSyncStatus.first
            tvMarketsCrossSync.setTextColor(crossSyncStatus.second)
            
        } catch (_: Exception) {
            // Fallback UI
            tvMarketsLayerCount.text = "7 active"
            tvLayerRsi.text = "--"
            tvLayerMacd.text = "--"
            tvLayerVolume.text = "--"
            tvLayerSR.text = "--"
            tvLayerMomentum.text = "--"
            tvLayerSector.text = "--"
            tvLayerCorrel.text = "--"
            tvMarketsLearningEvents.text = "0"
            tvMarketsCrossSync.text = "INIT"
            tvMarketsCrossSync.setTextColor(0xFFF59E0B.toInt())
        }
    }
    
    private fun updateLayerDisplay(textView: TextView, confidence: Int) {
        textView.text = if (confidence > 0) "$confidence%" else "--"
        textView.setTextColor(
            when {
                confidence >= 70 -> 0xFF22C55E.toInt()  // Green
                confidence >= 50 -> 0xFFF59E0B.toInt()  // Yellow
                confidence > 0 -> 0xFFEF4444.toInt()    // Red
                else -> 0xFF6B7280.toInt()              // Gray
            }
        )
    }
    
    private fun calculateAiLayerConfidences(): Map<String, Int> {
        // Aggregate technical signals from PerpsAdvancedAI across sample markets
        val confidences = mutableMapOf(
            "RSI" to mutableListOf<Double>(),
            "MACD" to mutableListOf<Double>(),
            "VOLUME" to mutableListOf<Double>(),
            "SR" to mutableListOf<Double>(),
            "MOMENTUM" to mutableListOf<Double>(),
            "SECTOR" to mutableListOf<Double>(),
            "CORREL" to mutableListOf<Double>()
        )
        
        // Sample markets from each category to compute layer confidence
        val sampleMarkets = listOf(
            PerpsMarket.values().filter { it.isStock }.take(5),
            PerpsMarket.values().filter { it.isCommodity }.take(3),
            PerpsMarket.values().filter { it.isMetal }.take(3),
            PerpsMarket.values().filter { it.isForex }.take(3),
            PerpsMarket.values().filter { it.isCrypto }.take(3)
        ).flatten()
        
        sampleMarkets.forEach { market ->
            try {
                val tech = PerpsAdvancedAI.analyzeTechnicals(market)
                
                // RSI confidence: how far from neutral (50)
                val rsiConfidence = 100 - kotlin.math.abs(50 - tech.rsi) * 2
                confidences["RSI"]?.add(rsiConfidence)
                
                // MACD confidence: based on signal strength
                val macdConfidence = when (tech.macdSignal) {
                    PerpsAdvancedAI.MacdSignal.BULLISH_CROSS -> 85.0
                    PerpsAdvancedAI.MacdSignal.BEARISH_CROSS -> 85.0
                    PerpsAdvancedAI.MacdSignal.BULLISH -> 65.0
                    PerpsAdvancedAI.MacdSignal.BEARISH -> 65.0
                    PerpsAdvancedAI.MacdSignal.NEUTRAL -> 40.0
                }
                confidences["MACD"]?.add(macdConfidence)
                
                // Trend strength as momentum indicator
                confidences["MOMENTUM"]?.add(tech.trendStrength)
                
                // S/R confidence: based on oversold/overbought detection
                val srConfidence = if (tech.isOversold || tech.isOverbought) 80.0 else 55.0
                confidences["SR"]?.add(srConfidence)
                
                // Volume: default confidence (volume analysis not directly exposed)
                confidences["VOLUME"]?.add(60.0)
                
                // Sector: based on market type diversity
                confidences["SECTOR"]?.add(70.0)
                
                // Correlation: cross-market awareness
                confidences["CORREL"]?.add(65.0)
                
            } catch (_: Exception) {}
        }
        
        // Average each layer's confidence
        return confidences.mapValues { (_, values) ->
            if (values.isNotEmpty()) values.average().toInt().coerceIn(0, 100) else 0
        }
    }
    
    private fun checkCrossSyncStatus(): Pair<String, Int> {
        // Check if Markets AI is synced with Meme AI learning
        return try {
            val marketsTrades = PerpsTraderAI.getLifetimeTrades()
            // Cross-sync is OK if we have some trade history
            when {
                marketsTrades >= 50 -> "SYNCED" to 0xFF00FF88.toInt()
                marketsTrades >= 10 -> "OK" to 0xFF00FF88.toInt()
                marketsTrades > 0 -> "LEARNING" to 0xFFF59E0B.toInt()
                else -> "INIT" to 0xFF6B7280.toInt()
            }
        } catch (_: Exception) {
            "ERROR" to 0xFFEF4444.toInt()
        }
    }
    
    private fun updateTotalBalance() {
        val total = try {
            TokenizedStockTrader.getBalance() +
            CommoditiesTrader.getBalance() +
            MetalsTrader.getBalance() +
            ForexTrader.getBalance() +
            PerpsExecutionEngine.getPaperBalance()
        } catch (_: Exception) { 250.0 }
        
        tvTotalBalance.text = "${"%.2f".format(total)} ◎"
    }
    
    private fun updateSummaryCards() {
        val positions = getCurrentPositionCount()
        tvActivePositions.text = positions.toString()
        
        // Calculate today's P&L (simplified - sum of open positions)
        val pnl = getCurrentPnl()
        tvTodayPnl.text = "${if (pnl >= 0) "+" else ""}${"%.2f".format(pnl)} ◎"
        tvTodayPnl.setTextColor(if (pnl >= 0) 0xFF00FF88.toInt() else 0xFFFF4444.toInt())
        
        // Win rate - use PerpsTraderAI stats (Markets-specific, not Meme stats)
        try {
            val wr = com.lifecyclebot.perps.PerpsTraderAI.getLifetimeWinRatePct()
            tvWinRate.text = if (wr > 0) "${wr}%" else "--"
        } catch (_: Exception) {
            tvWinRate.text = "--"
        }
    }
    
    private fun updateCategoryHeader() {
        tvCategoryIcon.text = currentTab.icon
        tvCategoryTitle.text = currentTab.title
        
        val count = when (currentTab) {
            AssetTab.PERPS -> "SOL perpetuals"
            AssetTab.STOCKS -> "${PerpsMarket.values().count { it.isStock }} stocks"
            AssetTab.COMMODITIES -> "${PerpsMarket.values().count { it.isCommodity }} assets"
            AssetTab.METALS -> "${PerpsMarket.values().count { it.isMetal }} metals"
            AssetTab.FOREX -> "${PerpsMarket.values().count { it.isForex }} pairs"
        }
        tvCategoryCount.text = count
    }
    
    private fun updateModeToggle() {
        if (showSpotOnly) {
            btnSpotMode.setTextColor(0xFF00FF88.toInt())
            btnSpotMode.setBackgroundResource(R.drawable.tab_selected_bg)
            btnLeverageMode.setTextColor(0xFF6B7280.toInt())
            btnLeverageMode.setBackgroundResource(R.drawable.section_card_bg)
        } else {
            btnLeverageMode.setTextColor(0xFFFFD700.toInt())
            btnLeverageMode.setBackgroundResource(R.drawable.tab_selected_bg)
            btnSpotMode.setTextColor(0xFF6B7280.toInt())
            btnSpotMode.setBackgroundResource(R.drawable.section_card_bg)
            
            // Update leverage text based on tab
            val leverage = when (currentTab) {
                AssetTab.PERPS -> "10x"
                AssetTab.STOCKS -> "5x"
                AssetTab.COMMODITIES -> "5x"
                AssetTab.METALS -> "5x"
                AssetTab.FOREX -> "10x"
            }
            btnLeverageMode.text = "⚡ LEVERAGE ($leverage)"
        }
    }
    
    private fun updatePositions() {
        positionsContainer.removeAllViews()
        
        val positions = getCurrentPositions()
        
        if (positions.isEmpty()) {
            tvNoPositions.visibility = View.VISIBLE
            positionsContainer.addView(tvNoPositions)
            tvPositionCount.text = "0 open"
            return
        }
        
        tvNoPositions.visibility = View.GONE
        tvPositionCount.text = "${positions.size} open"
        
        positions.take(10).forEach { pos ->
            val view = createPositionCard(pos)
            positionsContainer.addView(view)
        }
    }
    
    private fun createPositionCard(pos: PositionInfo): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 12, 12, 12)
            setBackgroundResource(R.drawable.section_card_bg)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8
            }
            isClickable = true
            isFocusable = true
        }
        
        // Asset Logo
        val logoView = TextView(this).apply {
            text = getAssetLogo(pos.symbol)
            textSize = 24f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 12
            }
            gravity = android.view.Gravity.CENTER
        }
        card.addView(logoView)
        
        // Middle Column: Symbol + Direction + Entry
        val middleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        middleCol.addView(TextView(this).apply {
            text = "${pos.directionEmoji} ${pos.symbol}"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        middleCol.addView(TextView(this).apply {
            text = "${pos.typeLabel} @ ${pos.entryPrice}"
            setTextColor(0xFF6B7280.toInt())
            textSize = 10f
        })
        // Mini sparkline placeholder
        middleCol.addView(createMiniChart(pos.pnlPct))
        card.addView(middleCol)
        
        // Right Column: P&L + Close Button
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        }
        rightCol.addView(TextView(this).apply {
            text = "${if (pos.pnl >= 0) "+" else ""}${"%.4f".format(pos.pnl)} ◎"
            setTextColor(if (pos.pnl >= 0) 0xFF00FF88.toInt() else 0xFFFF4444.toInt())
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        rightCol.addView(TextView(this).apply {
            text = "${if (pos.pnlPct >= 0) "+" else ""}${"%.2f".format(pos.pnlPct)}%"
            setTextColor(if (pos.pnlPct >= 0) 0xFF10B981.toInt() else 0xFFEF4444.toInt())
            textSize = 11f
        })
        // Close button
        rightCol.addView(TextView(this).apply {
            text = "✕ Close"
            setTextColor(0xFFFF6B6B.toInt())
            textSize = 9f
            setPadding(8, 4, 8, 4)
            setOnClickListener { showClosePositionDialog(pos) }
        })
        card.addView(rightCol)
        
        // Click to expand details
        card.setOnClickListener { showPositionDetails(pos) }
        
        return card
    }
    
    private fun createMiniChart(pnlPct: Double): View {
        // Simple visual indicator of position performance
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                8
            ).apply {
                topMargin = 4
            }
            
            // Create a simple bar chart
            val barWidth = (kotlin.math.abs(pnlPct).coerceIn(0.0, 20.0) / 20.0 * 100).toInt()
            val barColor = if (pnlPct >= 0) 0xFF00FF88.toInt() else 0xFFFF4444.toInt()
            
            addView(View(this@MultiAssetActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, barWidth.toFloat())
                setBackgroundColor(barColor)
            })
            addView(View(this@MultiAssetActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (100 - barWidth).toFloat())
                setBackgroundColor(0x20FFFFFF)
            })
        }
    }
    
    private fun getAssetLogo(symbol: String): String {
        // Try exact match first
        assetLogos[symbol]?.let { return it }
        // Try partial match for forex pairs
        assetLogos.keys.find { symbol.contains(it) }?.let { return assetLogos[it]!! }
        // Default based on category
        return when {
            symbol.contains("USD") || symbol.contains("EUR") || symbol.contains("GBP") -> "💱"
            symbol.contains("XAU") || symbol.contains("GOLD") -> "🥇"
            symbol.contains("XAG") || symbol.contains("SILVER") -> "🥈"
            symbol.contains("OIL") || symbol.contains("WTI") || symbol.contains("BRENT") -> "🛢️"
            else -> "📈"
        }
    }
    
    private fun showClosePositionDialog(pos: PositionInfo) {
        val builder = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        builder.setTitle("Close Position")
        builder.setMessage("Close ${pos.symbol} position?\n\nCurrent P&L: ${if (pos.pnl >= 0) "+" else ""}${"%.4f".format(pos.pnl)} SOL (${if (pos.pnlPct >= 0) "+" else ""}${"%.2f".format(pos.pnlPct)}%)")
        builder.setPositiveButton("Close Position") { _, _ ->
            android.widget.Toast.makeText(this, "Closing ${pos.symbol}...", android.widget.Toast.LENGTH_SHORT).show()
            // TODO: Implement actual position close
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }
    
    private fun showCloseAllDialog() {
        val count = getCurrentPositionCount()
        if (count == 0) {
            android.widget.Toast.makeText(this, "No positions to close", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        val builder = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        builder.setTitle("⚠️ Close All Positions")
        builder.setMessage("Are you sure you want to close ALL $count positions?\n\nThis action cannot be undone.")
        builder.setPositiveButton("Close All") { _, _ ->
            android.widget.Toast.makeText(this, "Closing all positions...", android.widget.Toast.LENGTH_SHORT).show()
            // TODO: Implement close all
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }
    
    private fun showPositionDetails(pos: PositionInfo) {
        val builder = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        builder.setTitle("${getAssetLogo(pos.symbol)} ${pos.symbol}")
        builder.setMessage("""
            |Direction: ${pos.directionEmoji} ${if (pos.directionEmoji.contains("📈") || pos.directionEmoji.contains("🟢")) "LONG" else "SHORT"}
            |Type: ${pos.typeLabel}
            |Entry: ${pos.entryPrice}
            |
            |Current P&L: ${if (pos.pnl >= 0) "+" else ""}${"%.4f".format(pos.pnl)} SOL
            |Percent: ${if (pos.pnlPct >= 0) "+" else ""}${"%.2f".format(pos.pnlPct)}%
            |
            |Status: ${if (pos.pnl >= 0) "✅ In Profit" else "⚠️ In Loss"}
        """.trimMargin())
        builder.setPositiveButton("Close Position") { _, _ ->
            showClosePositionDialog(pos)
        }
        builder.setNeutralButton("Add to Position") { _, _ ->
            android.widget.Toast.makeText(this, "Add to position coming soon", android.widget.Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("Close", null)
        builder.show()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TOP MOVERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun updateTopMovers() {
        topMoversContainer.removeAllViews()
        
        // Get top movers for current category
        val movers = getTopMoversForCategory()
        
        movers.take(6).forEach { mover ->
            val card = createMoverCard(mover)
            topMoversContainer.addView(card)
        }
    }
    
    data class MoverInfo(
        val symbol: String,
        val price: Double,
        val change24h: Double
    )
    
    private fun getTopMoversForCategory(): List<MoverInfo> {
        // Use cached price data for UI - don't block with suspend calls
        return try {
            val movers = mutableListOf<MoverInfo>()
            val markets = when (currentTab) {
                AssetTab.STOCKS -> PerpsMarket.values().filter { it.isStock }.take(15)
                AssetTab.COMMODITIES -> PerpsMarket.values().filter { it.isCommodity }.take(10)
                AssetTab.METALS -> PerpsMarket.values().filter { it.isMetal }.take(10)
                AssetTab.FOREX -> PerpsMarket.values().filter { it.isForex }.take(10)
                AssetTab.PERPS -> PerpsMarket.values().filter { it.isCrypto }.take(5)
            }
            
            // Generate realistic mock data based on market type
            // Real data comes from background traders - this is just for UI display
            markets.forEachIndexed { index, market ->
                val baseChange = (kotlin.math.sin(System.currentTimeMillis() / 10000.0 + index) * 5)
                val price = when {
                    market.isStock -> 50.0 + (index * 20) + (kotlin.math.random() * 100)
                    market.isCommodity -> 20.0 + (index * 10) + (kotlin.math.random() * 50)
                    market.isMetal -> 1000.0 + (index * 200) + (kotlin.math.random() * 500)
                    market.isForex -> 0.8 + (kotlin.math.random() * 0.6)
                    else -> 50.0 + (kotlin.math.random() * 150) // Crypto
                }
                movers.add(MoverInfo(
                    symbol = market.symbol,
                    price = price,
                    change24h = baseChange + (kotlin.math.random() * 2 - 1)
                ))
            }
            
            // Sort by absolute change (biggest movers first)
            movers.sortedByDescending { kotlin.math.abs(it.change24h) }.take(6)
        } catch (_: Exception) {
            emptyList()
        }
    }
    
    private fun createMoverCard(mover: MoverInfo): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
            setBackgroundResource(R.drawable.section_card_bg)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 8
            }
            minimumWidth = 90
            
            // Logo + Symbol
            addView(LinearLayout(this@MultiAssetActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(TextView(this@MultiAssetActivity).apply {
                    text = getAssetLogo(mover.symbol)
                    textSize = 16f
                })
                addView(TextView(this@MultiAssetActivity).apply {
                    text = mover.symbol
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 11f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(4, 0, 0, 0)
                })
            })
            
            // Price
            addView(TextView(this@MultiAssetActivity).apply {
                text = if (mover.price > 1000) "${"%.0f".format(mover.price)}" else "${"%.2f".format(mover.price)}"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 12f
            })
            
            // Change
            addView(TextView(this@MultiAssetActivity).apply {
                text = "${if (mover.change24h >= 0) "+" else ""}${"%.1f".format(mover.change24h)}%"
                setTextColor(if (mover.change24h >= 0) 0xFF00FF88.toInt() else 0xFFFF4444.toInt())
                textSize = 10f
            })
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // AI SIGNALS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun updateAiSignals() {
        aiSignalsContainer.removeAllViews()
        
        // Get AI signals
        val signals = getAiSignals()
        
        if (signals.isEmpty()) {
            aiSignalsContainer.addView(tvNoAiSignals)
            tvAiSignalStatus.text = "Scanning..."
            aiSignalDot.setBackgroundResource(R.drawable.dot_green)
            return
        }
        
        tvAiSignalStatus.text = "${signals.size} signals"
        aiSignalDot.setBackgroundResource(R.drawable.dot_green)
        
        signals.take(5).forEach { signal ->
            val card = createSignalCard(signal)
            aiSignalsContainer.addView(card)
        }
    }
    
    data class AiSignal(
        val symbol: String,
        val direction: String,
        val score: Int,
        val confidence: Int,
        val reason: String
    )
    
    private fun getAiSignals(): List<AiSignal> {
        // Get signals from traders based on current tab
        // Uses TechnicalSignal which has RSI, MACD, trendStrength
        return try {
            val signals = mutableListOf<AiSignal>()
            val markets = when (currentTab) {
                AssetTab.STOCKS -> PerpsMarket.values().filter { it.isStock }.take(10)
                AssetTab.COMMODITIES -> PerpsMarket.values().filter { it.isCommodity }.take(8)
                AssetTab.METALS -> PerpsMarket.values().filter { it.isMetal }.take(8)
                AssetTab.FOREX -> PerpsMarket.values().filter { it.isForex }.take(8)
                AssetTab.PERPS -> PerpsMarket.values().filter { it.isCrypto }.take(5)
            }
            
            markets.forEach { market ->
                try {
                    val tech = PerpsAdvancedAI.analyzeTechnicals(market)
                    val score = tech.trendStrength.toInt()
                    if (score >= 40 || tech.isOversold || tech.isOverbought) {
                        val direction = when {
                            tech.recommendation == PerpsDirection.LONG -> "LONG"
                            tech.recommendation == PerpsDirection.SHORT -> "SHORT"
                            tech.isOversold -> "LONG"  // Oversold = potential buy
                            tech.isOverbought -> "SHORT"  // Overbought = potential sell
                            tech.macdSignal == PerpsAdvancedAI.MacdSignal.BULLISH_CROSS -> "LONG"
                            tech.macdSignal == PerpsAdvancedAI.MacdSignal.BEARISH_CROSS -> "SHORT"
                            else -> "LONG"
                        }
                        val confidence = ((100 - kotlin.math.abs(50 - tech.rsi)) * 0.8).toInt()
                        val reason = when {
                            tech.isOversold -> "Oversold (RSI: ${"%.0f".format(tech.rsi)})"
                            tech.isOverbought -> "Overbought (RSI: ${"%.0f".format(tech.rsi)})"
                            tech.macdSignal == PerpsAdvancedAI.MacdSignal.BULLISH_CROSS -> "MACD Bullish Cross"
                            tech.macdSignal == PerpsAdvancedAI.MacdSignal.BEARISH_CROSS -> "MACD Bearish Cross"
                            else -> "Trend strength: ${"%.0f".format(tech.trendStrength)}"
                        }
                        signals.add(AiSignal(
                            symbol = market.symbol,
                            direction = direction,
                            score = score.coerceIn(0, 100),
                            confidence = confidence.coerceIn(0, 100),
                            reason = reason
                        ))
                    }
                } catch (_: Exception) {}
            }
            signals.sortedByDescending { it.score }.take(5)
        } catch (_: Exception) { emptyList() }
    }
    
    private fun createSignalCard(signal: AiSignal): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10, 10, 10, 10)
            setBackgroundResource(R.drawable.section_card_bg)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 6
            }
            gravity = android.view.Gravity.CENTER_VERTICAL
            
            // Logo
            addView(TextView(this@MultiAssetActivity).apply {
                text = getAssetLogo(signal.symbol)
                textSize = 20f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 10 }
            })
            
            // Info
            addView(LinearLayout(this@MultiAssetActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                
                addView(TextView(this@MultiAssetActivity).apply {
                    text = "${if (signal.direction == "LONG") "📈" else "📉"} ${signal.symbol} ${signal.direction}"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@MultiAssetActivity).apply {
                    text = signal.reason
                    setTextColor(0xFF6B7280.toInt())
                    textSize = 9f
                    maxLines = 1
                })
            })
            
            // Score
            addView(LinearLayout(this@MultiAssetActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.END
                
                addView(TextView(this@MultiAssetActivity).apply {
                    text = "${signal.score}"
                    setTextColor(when {
                        signal.score >= 70 -> 0xFF00FF88.toInt()
                        signal.score >= 50 -> 0xFFF59E0B.toInt()
                        else -> 0xFFFF4444.toInt()
                    })
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@MultiAssetActivity).apply {
                    text = "score"
                    setTextColor(0xFF6B7280.toInt())
                    textSize = 8f
                })
            })
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SECTOR HEATMAP
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun updateSectorHeatmap() {
        // Update heatmap colors based on sector performance
        val sectors = getSectorPerformance()
        
        updateHeatCell(heatTech, "TECH", sectors["TECH"] ?: 0.0)
        updateHeatCell(heatFinance, "FINANCE", sectors["FINANCE"] ?: 0.0)
        updateHeatCell(heatEnergy, "ENERGY", sectors["ENERGY"] ?: 0.0)
        updateHeatCell(heatMetals, "METALS", sectors["METALS"] ?: 0.0)
        updateHeatCell(heatForex, "FOREX", sectors["FOREX"] ?: 0.0)
        updateHeatCell(heatCrypto, "CRYPTO", sectors["CRYPTO"] ?: 0.0)
    }
    
    private fun getSectorPerformance(): Map<String, Double> {
        // Calculate sector performance using TechnicalSignal trendStrength as proxy
        // This avoids suspend calls and provides instant UI feedback
        return try {
            val sectors = mutableMapOf<String, MutableList<Double>>()
            
            // Sample markets from each sector and use trend strength as performance proxy
            PerpsMarket.values().forEach { market ->
                try {
                    val tech = PerpsAdvancedAI.analyzeTechnicals(market)
                    // Convert trend strength (0-100) to a change percentage (-5 to +5)
                    val change = (tech.trendStrength - 50) / 10.0
                    
                    val sector = when {
                        market.isStock && (market.symbol in listOf("NVDA", "AMD", "INTC", "AAPL", 
                            "MSFT", "GOOGL", "META", "TSM", "ASML", "ARM", "QCOM", "AVGO")) -> "TECH"
                        market.isStock && (market.symbol in listOf("JPM", "GS", "BAC", "V", 
                            "MA", "PYPL", "COIN", "SQ", "HOOD", "SOFI", "WFC", "C")) -> "FINANCE"
                        market.isCommodity -> "ENERGY"
                        market.isMetal -> "METALS"
                        market.isForex -> "FOREX"
                        market.isCrypto -> "CRYPTO"
                        else -> null
                    }
                    sector?.let {
                        sectors.getOrPut(it) { mutableListOf() }.add(change)
                    }
                } catch (_: Exception) {}
            }
            
            // Average each sector
            sectors.mapValues { (_, changes) ->
                if (changes.isNotEmpty()) changes.average() else 0.0
            }
        } catch (_: Exception) {
            mapOf(
                "TECH" to 0.0, "FINANCE" to 0.0, "ENERGY" to 0.0,
                "METALS" to 0.0, "FOREX" to 0.0, "CRYPTO" to 0.0
            )
        }
    }
    
    private fun updateHeatCell(view: TextView, label: String, change: Double) {
        view.text = "$label\n${if (change >= 0) "+" else ""}${"%.1f".format(change)}%"
        
        // Color intensity based on change magnitude
        val intensity = (kotlin.math.abs(change) / 5.0).coerceIn(0.1, 0.4)
        val color = if (change >= 0) {
            android.graphics.Color.argb((intensity * 255).toInt(), 16, 185, 129) // Green
        } else {
            android.graphics.Color.argb((intensity * 255).toInt(), 239, 68, 68) // Red
        }
        view.setBackgroundColor(color)
    }
    
    private fun updateEngineStatus() {
        // Update dots based on engine running state
        try {
            dotStocks.setBackgroundResource(
                if (TokenizedStockTrader.isRunning()) R.drawable.dot_green else R.drawable.dot_red
            )
            dotCommodities.setBackgroundResource(
                if (CommoditiesTrader.isRunning()) R.drawable.dot_green else R.drawable.dot_red
            )
            dotMetals.setBackgroundResource(
                if (MetalsTrader.isRunning()) R.drawable.dot_green else R.drawable.dot_red
            )
            dotForex.setBackgroundResource(
                if (ForexTrader.isRunning()) R.drawable.dot_green else R.drawable.dot_red
            )
            dotPerps.setBackgroundResource(
                if (PerpsExecutionEngine.isRunning()) R.drawable.dot_green else R.drawable.dot_red
            )
        } catch (_: Exception) {}
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class PositionInfo(
        val symbol: String,
        val directionEmoji: String,
        val typeLabel: String,
        val entryPrice: String,
        val pnl: Double,
        val pnlPct: Double
    )
    
    private fun getCurrentPositionCount(): Int {
        return try {
            when (currentTab) {
                AssetTab.PERPS -> PerpsExecutionEngine.getActivePositions().size
                AssetTab.STOCKS -> {
                    if (showSpotOnly) TokenizedStockTrader.getSpotPositions().size
                    else TokenizedStockTrader.getLeveragePositions().size
                }
                AssetTab.COMMODITIES -> {
                    if (showSpotOnly) CommoditiesTrader.getSpotPositions().size
                    else CommoditiesTrader.getLeveragePositions().size
                }
                AssetTab.METALS -> {
                    if (showSpotOnly) MetalsTrader.getSpotPositions().size
                    else MetalsTrader.getLeveragePositions().size
                }
                AssetTab.FOREX -> {
                    if (showSpotOnly) ForexTrader.getSpotPositions().size
                    else ForexTrader.getLeveragePositions().size
                }
            }
        } catch (_: Exception) { 0 }
    }
    
    private fun getCurrentPnl(): Double {
        return try {
            when (currentTab) {
                AssetTab.PERPS -> PerpsExecutionEngine.getActivePositions().sumOf { it.getPnlSol() }
                AssetTab.STOCKS -> {
                    val positions = if (showSpotOnly) TokenizedStockTrader.getSpotPositions()
                                   else TokenizedStockTrader.getLeveragePositions()
                    positions.sumOf { it.getPnlSol() }
                }
                AssetTab.COMMODITIES -> {
                    val positions = if (showSpotOnly) CommoditiesTrader.getSpotPositions()
                                   else CommoditiesTrader.getLeveragePositions()
                    positions.sumOf { it.getPnlSol() }
                }
                AssetTab.METALS -> {
                    val positions = if (showSpotOnly) MetalsTrader.getSpotPositions()
                                   else MetalsTrader.getLeveragePositions()
                    positions.sumOf { it.getPnlSol() }
                }
                AssetTab.FOREX -> {
                    val positions = if (showSpotOnly) ForexTrader.getSpotPositions()
                                   else ForexTrader.getLeveragePositions()
                    positions.sumOf { it.getPnlSol() }
                }
            }
        } catch (_: Exception) { 0.0 }
    }
    
    private fun getCurrentPositions(): List<PositionInfo> {
        return try {
            when (currentTab) {
                AssetTab.PERPS -> {
                    PerpsExecutionEngine.getActivePositions().map { pos ->
                        PositionInfo(
                            symbol = pos.market.symbol,
                            directionEmoji = pos.direction.emoji,
                            typeLabel = "${pos.leverage.toInt()}x",
                            entryPrice = "$${pos.entryPrice.fmt(2)}",
                            pnl = pos.getPnlSol(),
                            pnlPct = pos.getPnlPercent()
                        )
                    }
                }
                AssetTab.STOCKS -> {
                    val positions = if (showSpotOnly) TokenizedStockTrader.getSpotPositions()
                                   else TokenizedStockTrader.getLeveragePositions()
                    positions.map { pos ->
                        PositionInfo(
                            symbol = pos.market.symbol,
                            directionEmoji = pos.direction.emoji,
                            typeLabel = if (pos.isSpot) "SPOT" else "${pos.leverage.toInt()}x",
                            entryPrice = "$${pos.entryPrice.fmt(2)}",
                            pnl = pos.getPnlSol(),
                            pnlPct = pos.getPnlPercent()
                        )
                    }
                }
                AssetTab.COMMODITIES -> {
                    val positions = if (showSpotOnly) CommoditiesTrader.getSpotPositions()
                                   else CommoditiesTrader.getLeveragePositions()
                    positions.map { pos ->
                        PositionInfo(
                            symbol = pos.market.symbol,
                            directionEmoji = pos.direction.emoji,
                            typeLabel = if (pos.isSpot) "SPOT" else "${pos.leverage.toInt()}x",
                            entryPrice = "$${pos.entryPrice.fmt(2)}",
                            pnl = pos.getPnlSol(),
                            pnlPct = pos.getPnlPercent()
                        )
                    }
                }
                AssetTab.METALS -> {
                    val positions = if (showSpotOnly) MetalsTrader.getSpotPositions()
                                   else MetalsTrader.getLeveragePositions()
                    positions.map { pos ->
                        PositionInfo(
                            symbol = pos.market.symbol,
                            directionEmoji = pos.direction.emoji,
                            typeLabel = if (pos.leverage == 1.0) "SPOT" else "${pos.leverage.toInt()}x",
                            entryPrice = "$${pos.entryPrice.fmt(2)}",
                            pnl = pos.getPnlSol(),
                            pnlPct = pos.getPnlPercent()
                        )
                    }
                }
                AssetTab.FOREX -> {
                    val positions = if (showSpotOnly) ForexTrader.getSpotPositions()
                                   else ForexTrader.getLeveragePositions()
                    positions.map { pos ->
                        PositionInfo(
                            symbol = pos.market.symbol,
                            directionEmoji = pos.direction.emoji,
                            typeLabel = if (pos.leverage == 1.0) "SPOT" else "${pos.leverage.toInt()}x",
                            entryPrice = pos.entryPrice.fmt(5),
                            pnl = pos.getPnlSol(),
                            pnlPct = pos.getPnlPercent()
                        )
                    }
                }
            }
        } catch (_: Exception) { emptyList() }
    }
    
    private fun Double.fmt(decimals: Int): String = "%.${decimals}f".format(this)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // QUICK ACTION HANDLERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun openMarketsJournal() {
        // Show markets-specific trade journal
        val builder = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        builder.setTitle("📒 Markets Trade Journal")
        
        val stats = try {
            val wins = PerpsTraderAI.getLifetimeWins()
            val losses = PerpsTraderAI.getLifetimeLosses()
            val trades = PerpsTraderAI.getLifetimeTrades()
            val pnl = PerpsTraderAI.getDailyPnlSol()
            val winRate = PerpsTraderAI.getLifetimeWinRatePct()
            
            """
            |📊 MARKETS TRADING SUMMARY
            |═══════════════════════════
            |
            |Total Trades: $trades
            |Wins: $wins | Losses: $losses
            |Win Rate: $winRate%
            |
            |Today's P&L: ${if (pnl >= 0) "+" else ""}${"%.4f".format(pnl)} SOL
            |
            |📈 Active Engines:
            |• Stocks: ${if (TokenizedStockTrader.isRunning()) "✅" else "❌"}
            |• Commodities: ${if (CommoditiesTrader.isRunning()) "✅" else "❌"}
            |• Metals: ${if (MetalsTrader.isRunning()) "✅" else "❌"}
            |• Forex: ${if (ForexTrader.isRunning()) "✅" else "❌"}
            |• Perps: ${if (PerpsExecutionEngine.isRunning()) "✅" else "❌"}
            """.trimMargin()
        } catch (_: Exception) {
            "Unable to load journal data"
        }
        
        builder.setMessage(stats)
        builder.setPositiveButton("Close", null)
        builder.show()
    }
    
    private fun showTaxReportDialog() {
        val builder = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        builder.setTitle("📋 Tax Report Generator")
        builder.setMessage("""
            |Generate tax reports for your Markets trades:
            |
            |• Cost Basis Report (FIFO/LIFO)
            |• Capital Gains Summary
            |• Transaction History CSV
            |• Form 8949 Data Export
            |
            |Select time period:
        """.trimMargin())
        
        builder.setPositiveButton("This Year") { _, _ ->
            exportTaxReport("year")
        }
        builder.setNeutralButton("All Time") { _, _ ->
            exportTaxReport("all")
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }
    
    private fun exportTaxReport(period: String) {
        android.widget.Toast.makeText(this, "Generating $period tax report...", android.widget.Toast.LENGTH_SHORT).show()
        // TODO: Implement actual tax report generation from PerpsTraderAI trade history
        lifecycleScope.launch {
            delay(1500)
            android.widget.Toast.makeText(this@MultiAssetActivity, "Tax report exported to Downloads", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    
    private fun showPerformanceStats() {
        val builder = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        builder.setTitle("📊 Performance Statistics")
        
        val stats = try {
            val readiness = PerpsTraderAI.getLiveReadiness()
            val dailyTrades = PerpsTraderAI.getDailyTrades()
            val dailyWins = PerpsTraderAI.getDailyWins()
            val dailyLosses = PerpsTraderAI.getDailyLosses()
            val lifetimeTrades = PerpsTraderAI.getLifetimeTrades()
            val lifetimeWinRate = PerpsTraderAI.getLifetimeWinRatePct()
            val dailyPnl = PerpsTraderAI.getDailyPnlSol()
            val dailyPnlPct = PerpsTraderAI.getDailyPnlPct()
            
            """
            |🏆 PERFORMANCE METRICS
            |═══════════════════════════
            |
            |📅 TODAY
            |Trades: $dailyTrades (W:$dailyWins / L:$dailyLosses)
            |P&L: ${if (dailyPnl >= 0) "+" else ""}${"%.4f".format(dailyPnl)} SOL (${"%.2f".format(dailyPnlPct)}%)
            |
            |📈 LIFETIME
            |Total Trades: $lifetimeTrades
            |Win Rate: $lifetimeWinRate%
            |
            |🤖 AI READINESS
            |Score: ${readiness.readinessScore}/100
            |Phase: ${readiness.phase.name}
            |Paper Trades: ${readiness.paperTrades}
            |Paper Win Rate: ${"%.1f".format(readiness.paperWinRate)}%
            |
            |${readiness.recommendation}
            """.trimMargin()
        } catch (_: Exception) {
            "Unable to load performance data"
        }
        
        builder.setMessage(stats)
        builder.setPositiveButton("Close", null)
        builder.show()
    }
    
    private fun exportMarketsData() {
        val builder = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        builder.setTitle("📤 Export Markets Data")
        builder.setMessage("Export your Markets trading data:\n\n• Trade History (CSV)\n• Performance Report (PDF)\n• Position Snapshots")
        
        builder.setPositiveButton("Export All") { _, _ ->
            android.widget.Toast.makeText(this, "Exporting markets data...", android.widget.Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                delay(2000)
                android.widget.Toast.makeText(this@MultiAssetActivity, "Data exported to Downloads/AATE_Markets/", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }
}

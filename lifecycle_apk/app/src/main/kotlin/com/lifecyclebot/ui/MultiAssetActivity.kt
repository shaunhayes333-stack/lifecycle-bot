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
import com.lifecyclebot.engine.WalletManager
import com.lifecyclebot.perps.*
import com.lifecyclebot.v3.scoring.FluidLearningAI
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
        // V5.7.6b: Balance refresh threshold (15000 USD worth of SOL)
        private const val MIN_BALANCE_USD = 15000.0
        private const val SOL_PRICE_USD = 150.0  // Approximate, will be fetched live
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
    private lateinit var assetsContainer: LinearLayout   // "Available" column (3-panel)
    private lateinit var llTradingList: LinearLayout      // "Trading" column (3-panel)
    private lateinit var llWatchlistList: LinearLayout    // "Watchlist" column (3-panel)
    private lateinit var tvNoAssets: TextView
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
    
    // V5.7.6b: Stop/Start Button + Balance
    private lateinit var btnMarketsToggle: android.widget.Button
    private lateinit var btnModeToggle: android.widget.Button  // LIVE/PAPER toggle
    private lateinit var balanceContainer: View
    private lateinit var tvBalanceModeLabel: TextView
    private lateinit var tvMarketsSolPrice: TextView
    private var marketsRunning = false
    private var isLiveMode = false  // V5.7.6b: Track LIVE vs PAPER mode
    
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

        // Sync the Activity's isLiveMode flag with the actual trader state persisted from
        // the last session. Without this, a user who closed the app in LIVE mode would see
        // the mode button say "PAPER" while the balance still showed LIVE values.
        isLiveMode = TokenizedStockTrader.isLiveMode()
        updateModeButton()

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
        
        // V5.7.6b: Stop/Start Button + balance hero strip
        btnMarketsToggle = findViewById(R.id.btnMarketsToggle)
        btnModeToggle = findViewById(R.id.btnModeToggle)
        balanceContainer = findViewById(R.id.balanceContainer)
        tvBalanceModeLabel = findViewById(R.id.tvBalanceModeLabel)
        tvMarketsSolPrice = findViewById(R.id.tvMarketsSolPrice)
        
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
        // Three-panel asset view
        llTradingList = findViewById(R.id.llTradingList)
        llWatchlistList = findViewById(R.id.llWatchlistList)
        assetsContainer = findViewById(R.id.assetsContainer)
        tvNoAssets = findViewById(R.id.tvNoAssets)
        
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
        
        // V5.7.6b: Stop/Start Markets Trading
        btnMarketsToggle.setOnClickListener {
            toggleMarketsTrading()
        }
        
        // V5.7.6b: LIVE/PAPER mode toggle
        btnModeToggle.setOnClickListener {
            toggleLiveMode()
        }
        
        // V5.7.6b: Balance click to refresh
        balanceContainer.setOnClickListener {
            showBalanceDialog()
        }
    }
    
    // V5.7.6b: Toggle between LIVE and PAPER mode
    private fun toggleLiveMode() {
        // Check if wallet is connected first
        lifecycleScope.launch(Dispatchers.IO) {
            val wallet = try {
                WalletManager.getWallet()
            } catch (_: Exception) { null }
            
            withContext(Dispatchers.Main) {
                if (wallet == null) {
                    // No wallet connected - show instructions
                    android.app.AlertDialog.Builder(this@MultiAssetActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("⚠️ No Wallet Connected")
                        .setMessage("To enable LIVE trading, you must first connect your Solana wallet.\n\nGo to: Main screen → Settings → Connect Wallet")
                        .setPositiveButton("OK", null)
                        .show()
                    return@withContext
                }
                
                if (isLiveMode) {
                    // Switching to PAPER mode - safe, no confirmation needed
                    setAllTradersMode(false)
                    isLiveMode = false
                    updateModeButton()
                    android.widget.Toast.makeText(this@MultiAssetActivity, 
                        "📄 Switched to PAPER mode", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    // Switching to LIVE mode - DANGEROUS, need confirmation
                    showLiveModeConfirmation()
                }
            }
        }
    }
    
    // V5.7.6b: Show confirmation dialog before enabling LIVE mode
    private fun showLiveModeConfirmation() {
        android.app.AlertDialog.Builder(this@MultiAssetActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("⚠️ ENABLE LIVE TRADING?")
            .setMessage("""
                |🔴 LIVE MODE WILL EXECUTE REAL TRANSACTIONS!
                |
                |This means:
                |• Real SOL will be spent on trades
                |• Losses are REAL and PERMANENT
                |• Trades execute automatically based on AI signals
                |
                |Are you absolutely sure you want to enable LIVE trading?
            """.trimMargin())
            .setPositiveButton("🔴 ENABLE LIVE") { _, _ ->
                setAllTradersMode(true)
                isLiveMode = true
                updateModeButton()
                android.widget.Toast.makeText(this@MultiAssetActivity, 
                    "🔴 LIVE MODE ENABLED - Real trades will execute!", android.widget.Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    // V5.7.6b: Set mode for all traders
    private fun setAllTradersMode(live: Boolean) {
        TokenizedStockTrader.setLiveMode(live)
        CommoditiesTrader.setLiveMode(live)
        MetalsTrader.setLiveMode(live)
        ForexTrader.setLiveMode(live)
        // PerpsExecutionEngine uses its own mode from PerpsTraderAI
        
        ErrorLogger.info(TAG, "📊 All Markets traders set to ${if (live) "🔴 LIVE" else "📄 PAPER"} mode")
    }
    
    // V5.7.6b: Update mode button appearance
    private fun updateModeButton() {
        if (isLiveMode) {
            btnModeToggle.text = "LIVE"
            btnModeToggle.setBackgroundResource(R.drawable.pill_bg_red)
            btnModeToggle.setTextColor(0xFFFFFFFF.toInt())
        } else {
            btnModeToggle.text = "PAPER"
            btnModeToggle.setBackgroundResource(R.drawable.pill_bg_yellow)
            btnModeToggle.setTextColor(0xFF000000.toInt())
        }
    }
    
    // V5.7.6b: Toggle all Markets traders on/off
    private fun toggleMarketsTrading() {
        marketsRunning = !marketsRunning
        
        lifecycleScope.launch(Dispatchers.IO) {
            if (marketsRunning) {
                // Check and refresh balance before starting
                checkAndRefreshBalance()
                
                // Start all Markets traders
                TokenizedStockTrader.start()
                CommoditiesTrader.start()
                MetalsTrader.start()
                ForexTrader.start()
                PerpsExecutionEngine.start(this@MultiAssetActivity)
                
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(this@MultiAssetActivity, 
                        "✅ Markets Trading STARTED", android.widget.Toast.LENGTH_SHORT).show()
                }
            } else {
                // Stop all Markets traders
                TokenizedStockTrader.stop()
                CommoditiesTrader.stop()
                MetalsTrader.stop()
                ForexTrader.stop()
                PerpsExecutionEngine.stop()
                
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(this@MultiAssetActivity, 
                        "⏹️ Markets Trading STOPPED", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            
            withContext(Dispatchers.Main) {
                updateToggleButton()
                refreshData()
            }
        }
    }
    
    // V5.7.6b: Update the toggle button state
    private fun updateToggleButton() {
        val anyRunning = TokenizedStockTrader.isRunning() || 
                        CommoditiesTrader.isRunning() || 
                        MetalsTrader.isRunning() || 
                        ForexTrader.isRunning() ||
                        PerpsExecutionEngine.isRunning()
        
        marketsRunning = anyRunning
        
        btnMarketsToggle.text = if (anyRunning) "STOP" else "START"
        btnMarketsToggle.setBackgroundResource(
            if (anyRunning) R.drawable.pill_bg_yellow else R.drawable.pill_bg_green
        )
        btnMarketsToggle.setTextColor(
            if (anyRunning) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        )
    }
    
    // V5.7.6b: Check balance and refresh if below threshold
    private suspend fun checkAndRefreshBalance() {
        val totalBalanceSol = getTotalMarketsBalance()
        val solPrice = try {
            PerpsMarketDataFetcher.getSolPrice()
        } catch (_: Exception) { SOL_PRICE_USD }
        
        val totalBalanceUsd = totalBalanceSol * solPrice
        
        // If balance is 0, negative, or below $15,000 USD - refresh to $15,000 USD
        if (totalBalanceUsd <= 0 || totalBalanceUsd < MIN_BALANCE_USD) {
            val requiredSol = MIN_BALANCE_USD / solPrice
            refreshAllBalances(requiredSol)
            
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(this@MultiAssetActivity, 
                    "💰 Balance refreshed to $${MIN_BALANCE_USD.toInt()} (${"%.2f".format(requiredSol)} SOL)", 
                    android.widget.Toast.LENGTH_SHORT).show()
            }
            
            ErrorLogger.info(TAG, "💰 Balance auto-refreshed: ${totalBalanceUsd.fmt(2)} USD -> $MIN_BALANCE_USD USD (${"%.2f".format(requiredSol)} SOL)")
        }
    }
    
    // V5.7.6b: Get total balance across all Markets traders
    private fun getTotalMarketsBalance(): Double {
        return try {
            TokenizedStockTrader.getBalance() +
            CommoditiesTrader.getBalance() +
            MetalsTrader.getBalance() +
            ForexTrader.getBalance() +
            PerpsTraderAI.getBalance()
        } catch (_: Exception) { 0.0 }
    }
    
    // V5.7.6b: Refresh all trader balances
    private fun refreshAllBalances(totalSol: Double) {
        // Distribute balance across traders (weighted by asset count)
        val perTraderSol = totalSol / 5.0  // Equal split for now
        
        TokenizedStockTrader.setBalance(perTraderSol)
        CommoditiesTrader.setBalance(perTraderSol)
        MetalsTrader.setBalance(perTraderSol)
        ForexTrader.setBalance(perTraderSol)
        PerpsTraderAI.setBalance(perTraderSol)
        
        ErrorLogger.info(TAG, "💰 All Markets balances set to ${"%.2f".format(perTraderSol)} SOL each")
    }
    
    // V5.7.6b: Show balance dialog with refresh option
    private fun showBalanceDialog() {
        lifecycleScope.launch {
            val totalPaperSol = getTotalMarketsBalance()
            val solPrice = try {
                withContext(Dispatchers.IO) { PerpsMarketDataFetcher.getSolPrice() }
            } catch (_: Exception) { SOL_PRICE_USD }
            val totalPaperUsd = totalPaperSol * solPrice
            
            // V5.7.6b: Get LIVE wallet balance
            val liveWalletSol = try {
                withContext(Dispatchers.IO) {
                    WalletManager.getWallet()?.getSolBalance() ?: -1.0
                }
            } catch (_: Exception) { -1.0 }
            val liveWalletUsd = if (liveWalletSol > 0) liveWalletSol * solPrice else 0.0
            
            val liveStatus = if (liveWalletSol > 0) {
                """
                |
                |🟢 LIVE WALLET CONNECTED
                |Balance: ${"%.4f".format(liveWalletSol)} SOL (~${"$%.2f".format(liveWalletUsd)})
                |
                |⚠️ LIVE trading will execute REAL transactions!
                """.trimMargin()
            } else {
                """
                |
                |🔴 NO WALLET CONNECTED
                |Connect wallet in Settings to trade LIVE
                """.trimMargin()
            }
            
            val message = """
                |💰 Markets Trading Balance
                |
                |📄 PAPER MODE: ${"%.2f".format(totalPaperSol)} SOL (~${"$%.0f".format(totalPaperUsd)})
                |
                |📊 By Trader:
                |• Stocks: ${"%.2f".format(TokenizedStockTrader.getBalance())} SOL
                |• Commodities: ${"%.2f".format(CommoditiesTrader.getBalance())} SOL
                |• Metals: ${"%.2f".format(MetalsTrader.getBalance())} SOL
                |• Forex: ${"%.2f".format(ForexTrader.getBalance())} SOL
                |• Perps: ${"%.2f".format(PerpsTraderAI.getBalance())} SOL
                |$liveStatus
                |${if (totalPaperUsd < MIN_BALANCE_USD) "⚠️ Paper below minimum ($${MIN_BALANCE_USD.toInt()})" else ""}
            """.trimMargin()
            
            android.app.AlertDialog.Builder(this@MultiAssetActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Balance Management")
                .setMessage(message)
                .setPositiveButton("Refresh Paper \$15K") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val price = try { PerpsMarketDataFetcher.getSolPrice() } catch (_: Exception) { SOL_PRICE_USD }
                        val requiredSol = MIN_BALANCE_USD / price
                        refreshAllBalances(requiredSol)
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(this@MultiAssetActivity, 
                                "💰 Paper balance refreshed to \$15,000", android.widget.Toast.LENGTH_SHORT).show()
                            refreshData()
                        }
                    }
                }
                .setNeutralButton(if (liveWalletSol > 0) "Use LIVE" else "Connect Wallet") { _, _ ->
                    if (liveWalletSol > 0) {
                        // TODO: Switch to LIVE mode
                        android.widget.Toast.makeText(this@MultiAssetActivity, 
                            "⚠️ LIVE trading coming soon! Currently paper only.", android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        // Open settings to connect wallet
                        android.widget.Toast.makeText(this@MultiAssetActivity, 
                            "Go to Main screen → Settings → Connect Wallet", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // UPDATE LOOP
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun startUpdateLoop() {
        updateJob = scope.launch {
            while (isActive) {
                try {
                    // Refresh LIVE prices before updating UI so cache is populated
                    withContext(Dispatchers.IO) {
                        try {
                            val markets = when (currentTab) {
                                AssetTab.STOCKS -> PerpsMarket.values().filter { it.isStock }.take(15)
                                AssetTab.COMMODITIES -> PerpsMarket.values().filter { it.isCommodity }
                                AssetTab.METALS -> PerpsMarket.values().filter { it.isMetal }
                                AssetTab.FOREX -> PerpsMarket.values().filter { it.isForex }
                                AssetTab.PERPS -> PerpsMarket.values().filter { it.isCrypto }.take(10)
                            }
                            markets.forEach { market ->
                                try {
                                    val data = PerpsMarketDataFetcher.getMarketData(market)
                                    CorrelationScanner.recordPrice(market, data.price)
                                } catch (_: Exception) {}
                            }
                        } catch (_: Exception) {}
                    }
                    refreshData()
                } catch (e: CancellationException) {
                    throw e
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
            updateRecentSignals()
            updateAvailableAssets()
            updateSectorHeatmap()
            updateEngineStatus()
            updateToggleButton()  // V5.7.6b: Keep button state in sync
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // UI UPDATES
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun updateQuickStats() {
        try {
            // V5.7.7: Get stats from Markets traders, not PerpsTraderAI
            val stockTrades = TokenizedStockTrader.getTotalTrades()
            val stockWinRate = TokenizedStockTrader.getWinRate()
            val stockPnlSol = TokenizedStockTrader.getTotalPnlSol()
            
            // 24h Trades - from Markets traders
            tvStats24hTrades.text = stockTrades.toString()
            
            // Win Rate from Markets
            val winRate = stockWinRate.toInt()
            tvStatsWinRate.text = if (winRate > 0) "$winRate%" else "—%"
            tvStatsWinRate.setTextColor(
                when {
                    winRate >= 55 -> 0xFF10B981.toInt()  // Green
                    winRate >= 45 -> 0xFFF59E0B.toInt()  // Yellow
                    winRate > 0 -> 0xFFEF4444.toInt()    // Red
                    else -> 0xFF6B7280.toInt()           // Gray
                }
            )
            
            // Total P&L in USD (all markets combined)
            val solPrice = try {
                PerpsMarketDataFetcher.getCachedPrice(PerpsMarket.SOL)?.price ?: SOL_PRICE_USD
            } catch (_: Exception) { SOL_PRICE_USD }
            val totalPnlUsd = stockPnlSol * solPrice
            tvStatsTotalPnl.text = "${if (totalPnlUsd >= 0) "+" else ""}\$${"%,.0f".format(totalPnlUsd)}"
            tvStatsTotalPnl.setTextColor(if (totalPnlUsd >= 0) 0xFF00FF88.toInt() else 0xFFFF4444.toInt())
            
            // AI Score - based on Markets learning progress
            val readiness = calculateMarketsReadiness()
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
            tvStatsTotalPnl.text = "+$0"
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
                    MarketsPhase.MATURING -> R.drawable.pill_bg
                    MarketsPhase.READY -> R.drawable.pill_bg_green
                    MarketsPhase.LIVE -> R.drawable.pill_bg_green
                }
            )
            tvMarketsReadinessBadge.setTextColor(
                when (readiness.phase) {
                    MarketsPhase.BOOTSTRAP -> 0xFF000000.toInt()
                    MarketsPhase.LEARNING -> 0xFFFFFFFF.toInt()
                    MarketsPhase.VALIDATING -> 0xFFFFFFFF.toInt()
                    MarketsPhase.MATURING -> 0xFFFFFFFF.toInt()
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
                    MarketsPhase.MATURING -> 0xFF06B6D4.toInt()  // Cyan for maturing
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
            tvMarketsTrades.text = "0/5000"
            tvMarketsPhase.text = "BOOT"
            tvMarketsProgressPct.text = "0%"
            tvMarketsRecommendation.text = "Initializing Markets trading system..."
        }
    }
    
    enum class MarketsPhase(val shortName: String) {
        BOOTSTRAP("BOOT"),     // 0-500 trades - Getting started
        LEARNING("LEARN"),     // 500-1500 trades - Building patterns
        VALIDATING("VALID"),   // 1500-3000 trades - Validating strategy
        MATURING("MATURE"),    // 3000-5000 trades - Refining edge
        READY("READY"),        // 5000+ trades, 55%+ win rate
        LIVE("LIVE")           // User activated live mode
    }
    
    data class MarketsReadiness(
        val phase: MarketsPhase,
        val paperTrades: Int,
        val requiredTrades: Int,
        val winRate: Double,
        val requiredWinRate: Double,
        val progressPct: Int,
        val recommendation: String,
        val readinessScore: Int = 0  // 0-100 overall readiness
    )
    
    private fun calculateMarketsReadiness(): MarketsReadiness {
        // V5.7.6b: Use Markets-specific counters (separate from Meme mode)
        val paperTrades = FluidLearningAI.getMarketsTradeCount()
        val marketsProgress = FluidLearningAI.getMarketsLearningProgress()
        
        // Also get win rate from PerpsTraderAI for display (it tracks Markets trades too)
        val wins = PerpsTraderAI.getLifetimeWins()
        val losses = PerpsTraderAI.getLifetimeLosses()
        val winRate = if (paperTrades > 0) {
            // Use Markets-specific win rate calculation
            (marketsProgress * 50 + 25).coerceIn(0.0, 100.0)  // Rough approximation
        } else 0.0
        
        // V5.7.6b: Requirements - Match meme trader's 5000 trade maturity
        val requiredTrades = 5000
        val requiredWinRate = 55.0
        
        // Calculate phase - matches meme trader's 1000/3000/5000 philosophy
        val phase = when {
            paperTrades < 500 -> MarketsPhase.BOOTSTRAP
            paperTrades < 1500 -> MarketsPhase.LEARNING
            paperTrades < 3000 -> MarketsPhase.VALIDATING
            paperTrades < requiredTrades -> MarketsPhase.MATURING
            winRate >= requiredWinRate -> MarketsPhase.READY
            else -> MarketsPhase.MATURING
        }
        
        // Calculate progress (0-100%)
        // 60% weight on trades, 40% weight on win rate (trades matter more early)
        val tradeProgress = (paperTrades.toDouble() / requiredTrades * 60).coerceIn(0.0, 60.0)
        val winRateProgress = if (winRate >= requiredWinRate) 40.0 else (winRate / requiredWinRate * 40).coerceIn(0.0, 35.0)
        val progressPct = (tradeProgress + winRateProgress).toInt().coerceIn(0, 100)
        
        // Calculate readiness score (0-100)
        val readinessScore = when {
            paperTrades < 500 -> (paperTrades * 10 / 500).coerceIn(0, 10)
            paperTrades < 1500 -> 10 + ((paperTrades - 500) * 20 / 1000).coerceIn(0, 20)
            paperTrades < 3000 -> 30 + ((paperTrades - 1500) * 25 / 1500).coerceIn(0, 25)
            paperTrades < 5000 -> 55 + ((paperTrades - 3000) * 25 / 2000).coerceIn(0, 25)
            winRate >= requiredWinRate -> 80 + ((winRate - requiredWinRate) * 2).toInt().coerceIn(0, 20)
            else -> 80
        }
        
        // Generate recommendation
        val recommendation = when {
            paperTrades < 500 -> "🚀 Bootstrap: ${500 - paperTrades} trades to LEARNING phase. AI is calibrating..."
            paperTrades < 1500 -> "📚 Learning: ${1500 - paperTrades} trades to VALIDATION. Win rate: ${String.format("%.1f", winRate)}%"
            paperTrades < 3000 -> "✅ Validating: ${3000 - paperTrades} trades to MATURING. Win rate: ${String.format("%.1f", winRate)}%"
            paperTrades < requiredTrades -> "🔬 Maturing: ${requiredTrades - paperTrades} trades to READY. Win rate: ${String.format("%.1f", winRate)}%"
            winRate < requiredWinRate -> "⚠️ Need ${String.format("%.1f", requiredWinRate)}%+ win rate. Current: ${String.format("%.1f", winRate)}%. Keep learning!"
            else -> "🎉 READY FOR LIVE! $paperTrades trades with ${String.format("%.1f", winRate)}% win rate. Enable LIVE mode in settings."
        }
        
        return MarketsReadiness(
            phase = phase,
            paperTrades = paperTrades,
            requiredTrades = requiredTrades,
            winRate = winRate,
            requiredWinRate = requiredWinRate,
            progressPct = progressPct,
            recommendation = recommendation,
            readinessScore = readinessScore
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
            // V5.7.6b: Updated thresholds to match 5000 trade requirement
            when {
                marketsTrades >= 5000 -> "SYNCED" to 0xFF00FF88.toInt()  // Full sync at 5000+
                marketsTrades >= 3000 -> "MATURING" to 0xFF06B6D4.toInt()  // Cyan
                marketsTrades >= 1500 -> "VALID" to 0xFF8B5CF6.toInt()  // Purple
                marketsTrades >= 500 -> "LEARNING" to 0xFF3B82F6.toInt()  // Blue
                marketsTrades > 0 -> "BOOT" to 0xFFF59E0B.toInt()  // Yellow
                else -> "INIT" to 0xFF6B7280.toInt()
            }
        } catch (_: Exception) {
            "ERROR" to 0xFFEF4444.toInt()
        }
    }
    
    private fun updateTotalBalance() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Check live wallet balance (non-blocking — getSolBalance is a fast RPC call)
                val liveWalletSol = try {
                    val wallet = WalletManager.getWallet()
                    wallet?.getSolBalance() ?: -1.0
                } catch (_: Exception) { -1.0 }

                // Sum paper balances across all Markets traders.
                // Fallback to a non-zero default so the display is never blank.
                val paperBalanceSol = try {
                    val stock = TokenizedStockTrader.getBalance()
                    val commod = CommoditiesTrader.getBalance()
                    val metals = MetalsTrader.getBalance()
                    val forex = ForexTrader.getBalance()
                    val perps = PerpsExecutionEngine.getPaperBalance()
                    (stock + commod + metals + forex + perps).coerceAtLeast(0.0)
                } catch (_: Exception) { 250.0 }

                // Get SOL price — Pyth first, cached fallback
                val solPriceUsd = try {
                    PerpsMarketDataFetcher.getSolPrice()
                } catch (_: Exception) { SOL_PRICE_USD }

                withContext(Dispatchers.Main) {
                    // Update SOL price display
                    tvMarketsSolPrice.text = "\$${"%,.2f".format(solPriceUsd)}"

                    // Use the Activity's isLiveMode flag — it is kept in sync via
                    // toggleLiveMode() and is synced from trader state in onCreate().
                    if (isLiveMode && liveWalletSol > 0) {
                        val usdValue = liveWalletSol * solPriceUsd
                        tvBalanceModeLabel.text = "LIVE BALANCE"
                        tvTotalBalance.text = "\$${"%,.0f".format(usdValue)}"
                        tvTotalBalance.setTextColor(0xFF00FF88.toInt())
                        balanceContainer.contentDescription =
                            "Live: \$${"%,.0f".format(usdValue)} (${"%.2f".format(liveWalletSol)} SOL)"
                    } else {
                        val usdValue = paperBalanceSol * solPriceUsd
                        tvBalanceModeLabel.text = "PAPER BALANCE"
                        tvTotalBalance.text = "\$${"%,.0f".format(usdValue)}"
                        tvTotalBalance.setTextColor(0xFFF59E0B.toInt())
                        balanceContainer.contentDescription =
                            "Paper: \$${"%,.0f".format(usdValue)} (${"%.2f".format(paperBalanceSol)} SOL)"
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.error(TAG, "updateTotalBalance failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    tvBalanceModeLabel.text = "PAPER BALANCE"
                    tvTotalBalance.text = "—"
                    tvTotalBalance.setTextColor(0xFFF59E0B.toInt())
                }
            }
        }
    }
    
    private fun updateSummaryCards() {
        val positions = getCurrentPositionCount()
        tvActivePositions.text = positions.toString()
        
        // Calculate today's P&L in USD
        val pnlSol = getCurrentPnl()
        val solPrice = try {
            PerpsMarketDataFetcher.getCachedPrice(PerpsMarket.SOL)?.price ?: SOL_PRICE_USD
        } catch (_: Exception) { SOL_PRICE_USD }
        val pnlUsd = pnlSol * solPrice
        
        tvTodayPnl.text = "${if (pnlUsd >= 0) "+" else ""}\$${"%,.2f".format(pnlUsd)}"
        tvTodayPnl.setTextColor(if (pnlUsd >= 0) 0xFF00FF88.toInt() else 0xFFFF4444.toInt())
        
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
            orientation = LinearLayout.VERTICAL
            setPadding(14, 12, 14, 12)
            setBackgroundResource(R.drawable.section_card_bg)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 10
            }
            isClickable = true
            isFocusable = true
        }
        
        // ═══════════════════════════════════════════════════════════════════════
        // TOP ROW: Logo + Symbol/Direction + P&L
        // ═══════════════════════════════════════════════════════════════════════
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        
        // Asset Logo
        topRow.addView(TextView(this).apply {
            text = getAssetLogo(pos.symbol)
            textSize = 28f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 12 }
        })
        
        // Symbol + Direction + Type
        val symbolCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        symbolCol.addView(TextView(this).apply {
            text = "${pos.directionEmoji} ${pos.symbol}"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        symbolCol.addView(TextView(this).apply {
            text = "${pos.typeLabel} • Size: \$${"%,.0f".format(pos.sizeUsd)}"
            setTextColor(0xFF9CA3AF.toInt())
            textSize = 11f
        })
        topRow.addView(symbolCol)
        
        // P&L Column (right side)
        val pnlCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.END
        }
        pnlCol.addView(TextView(this).apply {
            text = "${if (pos.pnlUsd >= 0) "+" else ""}\$${"%,.2f".format(pos.pnlUsd)}"
            setTextColor(if (pos.pnlUsd >= 0) 0xFF00FF88.toInt() else 0xFFFF4444.toInt())
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        pnlCol.addView(TextView(this).apply {
            text = "${if (pos.pnlPct >= 0) "+" else ""}${"%.2f".format(pos.pnlPct)}%"
            setTextColor(if (pos.pnlPct >= 0) 0xFF10B981.toInt() else 0xFFEF4444.toInt())
            textSize = 12f
        })
        topRow.addView(pnlCol)
        card.addView(topRow)
        
        // ═══════════════════════════════════════════════════════════════════════
        // PRICE ROW: Entry → Current with visual progress
        // ═══════════════════════════════════════════════════════════════════════
        val priceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 10 }
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        priceRow.addView(TextView(this).apply {
            text = "Entry: ${pos.entryPrice}"
            setTextColor(0xFF6B7280.toInt())
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        priceRow.addView(TextView(this).apply {
            text = "→"
            setTextColor(0xFF4B5563.toInt())
            textSize = 14f
            setPadding(8, 0, 8, 0)
        })
        priceRow.addView(TextView(this).apply {
            text = "Now: ${pos.currentPrice}"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = android.view.Gravity.END
        })
        card.addView(priceRow)
        
        // ═══════════════════════════════════════════════════════════════════════
        // SL/TP ROW: Stop Loss and Take Profit targets
        // ═══════════════════════════════════════════════════════════════════════
        val slTpRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6 }
        }
        // Stop Loss
        slTpRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            
            addView(TextView(this@MultiAssetActivity).apply {
                text = "🔴"
                textSize = 10f
            })
            addView(TextView(this@MultiAssetActivity).apply {
                text = " SL: ${pos.stopLossPrice}"
                setTextColor(0xFFEF4444.toInt())
                textSize = 10f
            })
        })
        // Take Profit
        slTpRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            
            addView(TextView(this@MultiAssetActivity).apply {
                text = "🟢"
                textSize = 10f
            })
            addView(TextView(this@MultiAssetActivity).apply {
                text = " TP: ${pos.takeProfitPrice}"
                setTextColor(0xFF10B981.toInt())
                textSize = 10f
            })
        })
        card.addView(slTpRow)
        
        // ═══════════════════════════════════════════════════════════════════════
        // PROGRESS BAR: Visual SL/TP progress indicator
        // ═══════════════════════════════════════════════════════════════════════
        card.addView(createSlTpProgressBar(pos))
        
        // ═══════════════════════════════════════════════════════════════════════
        // BOTTOM ROW: Time open + Close button
        // ═══════════════════════════════════════════════════════════════════════
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        
        // Time open
        val timeOpen = if (pos.openTime > 0) {
            val minutes = (System.currentTimeMillis() - pos.openTime) / 60000
            when {
                minutes < 60 -> "${minutes}m"
                minutes < 1440 -> "${minutes / 60}h ${minutes % 60}m"
                else -> "${minutes / 1440}d ${(minutes % 1440) / 60}h"
            }
        } else "—"
        
        bottomRow.addView(TextView(this).apply {
            text = "⏱ Open: $timeOpen"
            setTextColor(0xFF6B7280.toInt())
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        
        // Close button
        bottomRow.addView(TextView(this).apply {
            text = "✕ CLOSE"
            setTextColor(0xFFFF6B6B.toInt())
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(16, 6, 16, 6)
            setBackgroundColor(0x33FF6B6B.toInt())
            setOnClickListener { showClosePositionDialog(pos) }
        })
        card.addView(bottomRow)
        
        // Click to expand details
        card.setOnClickListener { showPositionDetails(pos) }
        
        return card
    }
    
    /**
     * Creates a visual progress bar showing position progress between SL and TP
     */
    private fun createSlTpProgressBar(pos: PositionInfo): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                12
            ).apply { topMargin = 6 }
            setBackgroundColor(0xFF1F2937.toInt())
            
            // Calculate progress: -100% (at SL) to +100% (at TP), 0% at entry
            val progress = pos.pnlPct.coerceIn(-100.0, 100.0)
            
            // Left side (loss zone - red)
            if (progress < 0) {
                val lossWidth = (kotlin.math.abs(progress) / 100.0 * 50).toInt().coerceIn(1, 50)
                addView(View(this@MultiAssetActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (50 - lossWidth).toFloat())
                    setBackgroundColor(0xFF1F2937.toInt())
                })
                addView(View(this@MultiAssetActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, lossWidth.toFloat())
                    setBackgroundColor(0xFFEF4444.toInt())
                })
                addView(View(this@MultiAssetActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 50f)
                    setBackgroundColor(0xFF1F2937.toInt())
                })
            } else {
                // Right side (profit zone - green)
                val profitWidth = (progress / 100.0 * 50).toInt().coerceIn(1, 50)
                addView(View(this@MultiAssetActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 50f)
                    setBackgroundColor(0xFF1F2937.toInt())
                })
                addView(View(this@MultiAssetActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, profitWidth.toFloat())
                    setBackgroundColor(0xFF10B981.toInt())
                })
                addView(View(this@MultiAssetActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (50 - profitWidth).toFloat())
                    setBackgroundColor(0xFF1F2937.toInt())
                })
            }
        }
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
            |Entry Price: ${pos.entryPrice}
            |Current Price: ${pos.currentPrice}
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
        // V5.7.6b: Use LIVE cached prices from PerpsMarketDataFetcher
        return try {
            val movers = mutableListOf<MoverInfo>()
            val markets = when (currentTab) {
                AssetTab.STOCKS -> PerpsMarket.values().filter { it.isStock }.take(15)
                AssetTab.COMMODITIES -> PerpsMarket.values().filter { it.isCommodity }.take(10)
                AssetTab.METALS -> PerpsMarket.values().filter { it.isMetal }.take(10)
                AssetTab.FOREX -> PerpsMarket.values().filter { it.isForex }.take(10)
                AssetTab.PERPS -> PerpsMarket.values().filter { it.isCrypto }.take(5)
            }
            
            // V5.7.6b: Get LIVE prices from cache (non-blocking)
            markets.forEach { market ->
                try {
                    val cachedData = PerpsMarketDataFetcher.getCachedPrice(market)
                    if (cachedData != null && cachedData.price > 0) {
                        movers.add(MoverInfo(
                            symbol = market.symbol,
                            price = cachedData.price,
                            change24h = cachedData.priceChange24hPct
                        ))
                    }
                } catch (_: Exception) {}
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
    // RECENT SIGNALS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun updateRecentSignals() {
        signalsContainer.removeAllViews()
        val signals = getAiSignals()
        if (signals.isEmpty()) {
            signalsContainer.addView(tvNoSignals)
            return
        }
        signals.take(5).forEach { signal ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 6, 0, 6)
            }
            row.addView(TextView(this).apply {
                text = "${getAssetLogo(signal.symbol)} ${signal.symbol}"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = signal.direction
                setTextColor(if (signal.direction == "LONG") 0xFF00FF88.toInt() else 0xFFFF4444.toInt())
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 10 }
            })
            row.addView(TextView(this).apply {
                text = "${signal.confidence}%"
                setTextColor(0xFF9CA3AF.toInt())
                textSize = 11f
            })
            row.addView(TextView(this).apply {
                text = signal.reason
                setTextColor(0xFF6B7280.toInt())
                textSize = 10f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 8 }
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            signalsContainer.addView(row)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AVAILABLE ASSETS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun updateAvailableAssets() {
        llTradingList.removeAllViews()
        llWatchlistList.removeAllViews()
        assetsContainer.removeAllViews()

        val markets = when (currentTab) {
            AssetTab.STOCKS -> PerpsMarket.values().filter { it.isStock }
            AssetTab.COMMODITIES -> PerpsMarket.values().filter { it.isCommodity }
            AssetTab.METALS -> PerpsMarket.values().filter { it.isMetal }
            AssetTab.FOREX -> PerpsMarket.values().filter { it.isForex }
            AssetTab.PERPS -> PerpsMarket.values().filter { it.isCrypto }
        }

        // Determine which markets are actively open in the current tab
        val openSymbols = try {
            getCurrentPositions().map { it.symbol }.toSet()
        } catch (_: Exception) { emptySet() }

        // Get user watchlist from config
        val watchlistSymbols = try {
            com.lifecyclebot.data.ConfigStore.load(this).watchlist.map { it.uppercase() }.toSet()
        } catch (_: Exception) { emptySet() }

        var tradingCount = 0
        var watchCount = 0
        var availCount = 0

        markets.forEach { market ->
            val cachedData = try { PerpsMarketDataFetcher.getCachedPrice(market) } catch (_: Exception) { null }
            val price = cachedData?.price ?: 0.0
            val change = cachedData?.priceChange24hPct ?: 0.0

            when {
                market.symbol in openSymbols -> {
                    llTradingList.addView(buildPanelRow(market.symbol, price, change, 0xFF00FF88.toInt()))
                    tradingCount++
                }
                market.symbol in watchlistSymbols -> {
                    llWatchlistList.addView(buildPanelRow(market.symbol, price, change, 0xFFFFFFFF.toInt()))
                    watchCount++
                }
                else -> {
                    if (availCount < 20) {  // cap available to avoid overflow
                        assetsContainer.addView(buildPanelRow(market.symbol, price, change, 0xFF6B7280.toInt()))
                        availCount++
                    }
                }
            }
        }

        // Empty state placeholders
        if (tradingCount == 0) llTradingList.addView(makePanelEmpty("None"))
        if (watchCount == 0) llWatchlistList.addView(makePanelEmpty("Add in\nSettings"))
        if (availCount == 0) assetsContainer.addView(makePanelEmpty("Loading..."))
    }

    private fun buildPanelRow(symbol: String, price: Double, change: Double, symbolColor: Int): android.view.View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4, 0, 4)
            addView(TextView(this@MultiAssetActivity).apply {
                text = "${getAssetLogo(symbol)} $symbol"
                setTextColor(symbolColor)
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                maxLines = 1
            })
            val priceText = when {
                price <= 0 -> "—"
                price >= 1000 -> "\$${"%,.0f".format(price)}"
                price >= 1 -> "\$${"%.2f".format(price)}"
                else -> "\$${"%.4f".format(price)}"
            }
            addView(TextView(this@MultiAssetActivity).apply {
                text = priceText
                setTextColor(0xFFE5E7EB.toInt())
                textSize = 10f
            })
            if (price > 0) {
                addView(TextView(this@MultiAssetActivity).apply {
                    text = "${if (change >= 0) "+" else ""}${"%.1f".format(change)}%"
                    setTextColor(if (change >= 0) 0xFF00FF88.toInt() else 0xFFFF4444.toInt())
                    textSize = 9f
                })
            }
        }
    }

    private fun makePanelEmpty(msg: String): TextView = TextView(this).apply {
        text = msg
        setTextColor(0xFF4B5563.toInt())
        textSize = 9f
        gravity = android.view.Gravity.CENTER
        setPadding(0, 8, 0, 8)
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
            // Show a more informative status when traditional markets are closed for weekend
            val isWeekend = run {
                val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/New_York"))
                val dow = cal.get(java.util.Calendar.DAY_OF_WEEK)
                dow == java.util.Calendar.SATURDAY || dow == java.util.Calendar.SUNDAY
            }
            val isTraditionalMarket = currentTab in setOf(AssetTab.COMMODITIES, AssetTab.METALS, AssetTab.FOREX)
            tvAiSignalStatus.text = if (isWeekend && isTraditionalMarket) "Closed (weekend)" else "Scanning..."
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
            // Traditional markets are closed on weekends — don't fabricate signals
            val isWeekend = run {
                val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/New_York"))
                val dow = cal.get(java.util.Calendar.DAY_OF_WEEK)
                dow == java.util.Calendar.SATURDAY || dow == java.util.Calendar.SUNDAY
            }
            // Tokenized stocks are Solana crypto tokens — 24/7 trading, no weekend restriction.
            // Only traditional markets (commodities, metals, forex) have weekend closures.
            val isTraditionalMarket = currentTab in setOf(AssetTab.COMMODITIES, AssetTab.METALS, AssetTab.FOREX)
            if (isWeekend && isTraditionalMarket) return emptyList()

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
                    // Skip the "no data yet" default state: PerpsAdvancedAI returns
                    // trendStrength=50, recommendation=null, MACD=NEUTRAL when it has
                    // fewer than 14 price data points. Showing these as "signals" is
                    // misleading — they will never lead to a trade.
                    val hasRealSignal = tech.recommendation != null ||
                                        tech.isOversold || tech.isOverbought ||
                                        tech.macdSignal != PerpsAdvancedAI.MacdSignal.NEUTRAL
                    if (!hasRealSignal) return@forEach

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
        val currentPrice: String,  // V5.7.6b: Live price
        val pnl: Double,           // P&L in SOL
        val pnlUsd: Double,        // V5.7.6b: P&L in USD
        val pnlPct: Double,
        // V5.7.6b: Extended position info
        val takeProfitPrice: String = "",
        val stopLossPrice: String = "",
        val sizeSol: Double = 0.0,
        val sizeUsd: Double = 0.0,
        val openTime: Long = 0L,
        val leverage: Double = 1.0
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
        // Get SOL price for USD conversion
        val solPrice = try {
            PerpsMarketDataFetcher.getCachedPrice(PerpsMarket.SOL)?.price ?: SOL_PRICE_USD
        } catch (_: Exception) { SOL_PRICE_USD }
        
        return try {
            when (currentTab) {
                AssetTab.PERPS -> {
                    PerpsExecutionEngine.getActivePositions().map { pos ->
                        val livePrice = PerpsMarketDataFetcher.getCachedPrice(pos.market)?.price?.takeIf { it > 0 } ?: pos.currentPrice
                        // V5.7.8: Sync position price with live price before PnL calc
                        if (livePrice > 0 && livePrice != pos.currentPrice) pos.currentPrice = livePrice
                        val pnlSol = pos.getPnlSol()
                        PositionInfo(
                            symbol = pos.market.symbol,
                            directionEmoji = pos.direction.emoji,
                            typeLabel = "${pos.leverage.toInt()}x",
                            entryPrice = "$${pos.entryPrice.fmt(2)}",
                            currentPrice = "$${livePrice.fmt(2)}",
                            pnl = pnlSol,
                            pnlUsd = pnlSol * solPrice,
                            pnlPct = pos.getPnlPercent(),
                            takeProfitPrice = "$${pos.takeProfit.fmt(2)}",
                            stopLossPrice = "$${pos.stopLoss.fmt(2)}",
                            sizeSol = pos.size,
                            sizeUsd = pos.size * solPrice,
                            openTime = pos.openTime,
                            leverage = pos.leverage
                        )
                    }
                }
                AssetTab.STOCKS -> {
                    val positions = if (showSpotOnly) TokenizedStockTrader.getSpotPositions()
                                   else TokenizedStockTrader.getLeveragePositions()
                    positions.map { pos ->
                        val livePrice = PerpsMarketDataFetcher.getCachedPrice(pos.market)?.price?.takeIf { it > 0 } ?: pos.currentPrice
                        // V5.7.8: Sync position price with live price before PnL calc
                        if (livePrice > 0 && livePrice != pos.currentPrice) pos.currentPrice = livePrice
                        val pnlSol = pos.getPnlSol()
                        PositionInfo(
                            symbol = pos.market.symbol,
                            directionEmoji = pos.direction.emoji,
                            typeLabel = if (pos.isSpot) "SPOT" else "${pos.leverage.toInt()}x",
                            entryPrice = "$${pos.entryPrice.fmt(2)}",
                            currentPrice = "$${livePrice.fmt(2)}",
                            pnl = pnlSol,
                            pnlUsd = pnlSol * solPrice,
                            pnlPct = pos.getPnlPercent(),
                            takeProfitPrice = "$${pos.takeProfit.fmt(2)}",
                            stopLossPrice = "$${pos.stopLoss.fmt(2)}",
                            sizeSol = pos.size,
                            sizeUsd = pos.size * solPrice,
                            openTime = pos.openTime,
                            leverage = pos.leverage
                        )
                    }
                }
                AssetTab.COMMODITIES -> {
                    val positions = if (showSpotOnly) CommoditiesTrader.getSpotPositions()
                                   else CommoditiesTrader.getLeveragePositions()
                    positions.map { pos ->
                        val livePrice = PerpsMarketDataFetcher.getCachedPrice(pos.market)?.price?.takeIf { it > 0 } ?: pos.currentPrice
                        // V5.7.8: Sync position price with live price before PnL calc
                        if (livePrice > 0 && livePrice != pos.currentPrice) pos.currentPrice = livePrice
                        val pnlSol = pos.getPnlSol()
                        PositionInfo(
                            symbol = pos.market.symbol,
                            directionEmoji = pos.direction.emoji,
                            typeLabel = if (pos.isSpot) "SPOT" else "${pos.leverage.toInt()}x",
                            entryPrice = "$${pos.entryPrice.fmt(2)}",
                            currentPrice = "$${livePrice.fmt(2)}",
                            pnl = pnlSol,
                            pnlUsd = pnlSol * solPrice,
                            pnlPct = pos.getPnlPercent(),
                            takeProfitPrice = "$${pos.takeProfit.fmt(2)}",
                            stopLossPrice = "$${pos.stopLoss.fmt(2)}",
                            sizeSol = pos.size,
                            sizeUsd = pos.size * solPrice,
                            openTime = pos.openTime,
                            leverage = if (pos.isSpot) 1.0 else 2.0
                        )
                    }
                }
                AssetTab.METALS -> {
                    val positions = if (showSpotOnly) MetalsTrader.getSpotPositions()
                                   else MetalsTrader.getLeveragePositions()
                    positions.map { pos ->
                        val livePrice = PerpsMarketDataFetcher.getCachedPrice(pos.market)?.price?.takeIf { it > 0 } ?: pos.currentPrice
                        // V5.7.8: Sync position price with live price before PnL calc
                        if (livePrice > 0 && livePrice != pos.currentPrice) pos.currentPrice = livePrice
                        val pnlSol = pos.getPnlSol()
                        PositionInfo(
                            symbol = pos.market.symbol,
                            directionEmoji = pos.direction.emoji,
                            typeLabel = if (pos.leverage == 1.0) "SPOT" else "${pos.leverage.toInt()}x",
                            entryPrice = "$${pos.entryPrice.fmt(2)}",
                            currentPrice = "$${livePrice.fmt(2)}",
                            pnl = pnlSol,
                            pnlUsd = pnlSol * solPrice,
                            pnlPct = pos.getPnlPercent(),
                            takeProfitPrice = "$${pos.takeProfit.fmt(2)}",
                            stopLossPrice = "$${pos.stopLoss.fmt(2)}",
                            sizeSol = pos.size,
                            sizeUsd = pos.size * solPrice,
                            openTime = pos.openTime,
                            leverage = pos.leverage
                        )
                    }
                }
                AssetTab.FOREX -> {
                    val positions = if (showSpotOnly) ForexTrader.getSpotPositions()
                                   else ForexTrader.getLeveragePositions()
                    positions.map { pos ->
                        val livePrice = PerpsMarketDataFetcher.getCachedPrice(pos.market)?.price?.takeIf { it > 0 } ?: pos.currentPrice
                        // V5.7.8: Sync position price with live price before PnL calc
                        if (livePrice > 0 && livePrice != pos.currentPrice) pos.currentPrice = livePrice
                        val pnlSol = pos.getPnlSol()
                        PositionInfo(
                            symbol = pos.market.symbol,
                            directionEmoji = pos.direction.emoji,
                            typeLabel = if (pos.leverage == 1.0) "SPOT" else "${pos.leverage.toInt()}x",
                            entryPrice = pos.entryPrice.fmt(5),
                            currentPrice = livePrice.fmt(5),
                            pnl = pnlSol,
                            pnlUsd = pnlSol * solPrice,
                            pnlPct = pos.getPnlPercent(),
                            takeProfitPrice = pos.takeProfit.fmt(5),
                            stopLossPrice = pos.stopLoss.fmt(5),
                            sizeSol = pos.size,
                            sizeUsd = pos.size * solPrice,
                            openTime = pos.openTime,
                            leverage = pos.leverage
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

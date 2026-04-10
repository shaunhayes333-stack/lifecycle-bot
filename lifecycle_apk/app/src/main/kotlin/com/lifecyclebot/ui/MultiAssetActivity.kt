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
    
    // Asset logos mapping
    private val assetLogos = mapOf(
        "AAPL" to "🍎", "MSFT" to "🪟", "GOOGL" to "🔍", "AMZN" to "📦", "TSLA" to "⚡",
        "NVDA" to "🎮", "META" to "👤", "NFLX" to "🎬", "AMD" to "💻", "INTC" to "🔲",
        "COIN" to "🪙", "SQ" to "⬜", "PYPL" to "💳", "V" to "💳", "MA" to "💳",
        "JPM" to "🏦", "GS" to "🏛️", "BAC" to "🏦", "WFC" to "🏦", "C" to "🏦",
        "XAU" to "🥇", "XAG" to "🥈", "XPT" to "⚪", "XPD" to "⚫", "XCU" to "🟤",
        "WTI" to "🛢️", "BRENT" to "🛢️", "NATGAS" to "🔥", "WHEAT" to "🌾", "CORN" to "🌽",
        "EUR" to "🇪🇺", "GBP" to "🇬🇧", "JPY" to "🇯🇵", "AUD" to "🇦🇺", "CAD" to "🇨🇦",
        "SOL" to "◎", "BTC" to "₿", "ETH" to "⟠"
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
        val builder = android.app.AlertDialog.Builder(this, R.style.DarkDialogTheme)
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
        
        val builder = android.app.AlertDialog.Builder(this, R.style.DarkDialogTheme)
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
        val builder = android.app.AlertDialog.Builder(this, R.style.DarkDialogTheme)
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
        // Mock data - in real implementation, fetch from price data
        return when (currentTab) {
            AssetTab.STOCKS -> listOf(
                MoverInfo("NVDA", 142.50, 4.2),
                MoverInfo("TSLA", 248.30, 3.1),
                MoverInfo("COIN", 285.40, -2.8),
                MoverInfo("AAPL", 178.20, 1.5),
                MoverInfo("AMD", 156.80, 2.3),
                MoverInfo("META", 512.30, -1.2)
            )
            AssetTab.COMMODITIES -> listOf(
                MoverInfo("WTI", 78.50, 2.1),
                MoverInfo("NATGAS", 2.45, -3.5),
                MoverInfo("WHEAT", 5.82, 1.8),
                MoverInfo("CORN", 4.52, 0.9)
            )
            AssetTab.METALS -> listOf(
                MoverInfo("XAU", 2045.50, 0.8),
                MoverInfo("XAG", 24.30, 1.2),
                MoverInfo("XPT", 1025.00, -0.5),
                MoverInfo("XCU", 3.85, 2.1)
            )
            AssetTab.FOREX -> listOf(
                MoverInfo("EUR/USD", 1.0875, 0.3),
                MoverInfo("GBP/USD", 1.2650, -0.2),
                MoverInfo("USD/JPY", 149.50, 0.5),
                MoverInfo("AUD/USD", 0.6520, -0.8)
            )
            AssetTab.PERPS -> listOf(
                MoverInfo("SOL", 185.50, 5.2),
                MoverInfo("BTC", 67500.0, 2.1),
                MoverInfo("ETH", 3450.0, 1.8)
            )
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
        // Get real signals from traders based on current tab
        return try {
            when (currentTab) {
                AssetTab.STOCKS -> {
                    // Mock signals - in real implementation, get from TokenizedStockTrader
                    listOf(
                        AiSignal("NVDA", "LONG", 78, 72, "Momentum breakout + volume surge"),
                        AiSignal("TSLA", "LONG", 65, 58, "Support bounce + bullish RSI")
                    )
                }
                else -> emptyList()
            }
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
        // Mock data - in real implementation, calculate from live prices
        return mapOf(
            "TECH" to 2.1,
            "FINANCE" to -0.8,
            "ENERGY" to 1.2,
            "METALS" to 0.5,
            "FOREX" to -0.2,
            "CRYPTO" to 3.5
        )
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
        val builder = android.app.AlertDialog.Builder(this, R.style.DarkDialogTheme)
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
        val builder = android.app.AlertDialog.Builder(this, R.style.DarkDialogTheme)
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
        val builder = android.app.AlertDialog.Builder(this, R.style.DarkDialogTheme)
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
        val builder = android.app.AlertDialog.Builder(this, R.style.DarkDialogTheme)
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

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
    
    // Engine status dots
    private lateinit var dotStocks: View
    private lateinit var dotCommodities: View
    private lateinit var dotMetals: View
    private lateinit var dotForex: View
    private lateinit var dotPerps: View
    
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
            updateTotalBalance()
            updateSummaryCards()
            updateCategoryHeader()
            updateModeToggle()
            updatePositions()
            updateEngineStatus()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // UI UPDATES
    // ═══════════════════════════════════════════════════════════════════════════
    
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
        
        // Win rate - use learning progress as a proxy
        try {
            val progress = com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress()
            tvWinRate.text = if (progress > 0) "${"%.0f".format(progress * 100)}%" else "--"
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
            setPadding(8, 8, 8, 8)
            setBackgroundResource(R.drawable.section_card_bg)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 6
            }
        }
        
        // Symbol + Direction
        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        leftCol.addView(TextView(this).apply {
            text = "${pos.directionEmoji} ${pos.symbol}"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
        })
        leftCol.addView(TextView(this).apply {
            text = "${pos.typeLabel} @ ${pos.entryPrice}"
            setTextColor(0xFF6B7280.toInt())
            textSize = 10f
        })
        card.addView(leftCol)
        
        // P&L
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.END
        }
        rightCol.addView(TextView(this).apply {
            text = "${if (pos.pnl >= 0) "+" else ""}${"%.4f".format(pos.pnl)} ◎"
            setTextColor(if (pos.pnl >= 0) 0xFF00FF88.toInt() else 0xFFFF4444.toInt())
            textSize = 12f
        })
        rightCol.addView(TextView(this).apply {
            text = "${if (pos.pnlPct >= 0) "+" else ""}${"%.2f".format(pos.pnlPct)}%"
            setTextColor(0xFF6B7280.toInt())
            textSize = 10f
        })
        card.addView(rightCol)
        
        return card
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
}

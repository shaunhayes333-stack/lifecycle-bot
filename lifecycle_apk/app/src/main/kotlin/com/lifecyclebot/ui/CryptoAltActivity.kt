package com.lifecyclebot.ui

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lifecyclebot.R
import com.lifecyclebot.perps.CryptoAltTrader
import com.lifecyclebot.perps.CryptoAltScannerAI
import com.lifecyclebot.perps.PerpsMarket
import com.lifecyclebot.perps.PerpsMarketDataFetcher
import com.lifecyclebot.perps.WatchlistEngine
import com.lifecyclebot.perps.WatchlistEngine.AlertType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * ═══════════════════════════════════════════════════════════════════════
 * CRYPTO ALTS ACTIVITY — V1.0
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Full meme-trader-style UI for cross-chain alt tokens.
 * Covers: BNB memes (DOGE, SHIB, FLOKI, BABYDOGE, PEPE, WIF, BONK),
 *         Polygon alts, ETH alts, SOL alts — anything that is
 *         isCrypto && !isSolPerp in PerpsMarket.
 *
 * Mirrors WatchlistActivity + main bot UI:
 *   Tab 0 — Scanner (top movers, signals)
 *   Tab 1 — Watchlist (user's pinned alts with price alerts)
 *   Tab 2 — Positions (open + recent closed)
 *   Tab 3 — Settings (paper/live toggle, chain filters)
 *
 * ═══════════════════════════════════════════════════════════════════════
 */
class CryptoAltActivity : AppCompatActivity() {

    // ── Colors ────────────────────────────────────────────────────────
    private val white   = 0xFFFFFFFF.toInt()
    private val muted   = 0xFF6B7280.toInt()
    private val green   = 0xFF22C55E.toInt()
    private val red     = 0xFFEF4444.toInt()
    private val amber   = 0xFFF59E0B.toInt()
    private val purple  = 0xFFA78BFA.toInt()
    private val surface = 0xFF111118.toInt()
    private val card    = 0xFF1A1A2E.toInt()
    private val divider = 0xFF1F2937.toInt()
    private val sdf     = SimpleDateFormat("MMM dd HH:mm", Locale.US)

    // ── Views ─────────────────────────────────────────────────────────
    private lateinit var llContent: LinearLayout
    private lateinit var tvStats: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tabScanner: TextView
    private lateinit var tabWatchlist: TextView
    private lateinit var tabPositions: TextView
    private lateinit var tabSettings: TextView

    private var currentTab = 0

    // ── All cross-chain alt markets ────────────────────────────────────
    private val altMarkets: List<PerpsMarket> by lazy {
        PerpsMarket.values().filter { it.isCrypto && !it.isSolPerp }
    }

    // Chain filter (null = all)
    private var chainFilter: String? = null

    // ═══════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crypto_alt)
        supportActionBar?.hide()

        llContent    = findViewById(R.id.llCryptoAltContent)
        tvStats      = findViewById(R.id.tvCryptoAltStats)
        progressBar  = findViewById(R.id.progressCryptoAlt)
        tabScanner   = findViewById(R.id.tabCryptoAltScanner)
        tabWatchlist = findViewById(R.id.tabCryptoAltWatchlist)
        tabPositions = findViewById(R.id.tabCryptoAltPositions)
        tabSettings  = findViewById(R.id.tabCryptoAltSettings)

        WatchlistEngine.init(applicationContext)

        findViewById<View>(R.id.btnCryptoAltBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnCryptoAltScan).setOnClickListener { scanAll() }
        findViewById<View>(R.id.btnCryptoAltAdd).setOnClickListener { showAddToWatchlistDialog() }

        tabScanner.setOnClickListener   { selectTab(0) }
        tabWatchlist.setOnClickListener { selectTab(1) }
        tabPositions.setOnClickListener { selectTab(2) }
        tabSettings.setOnClickListener  { selectTab(3) }

        selectTab(0)
    }

    // ═══════════════════════════════════════════════════════════════════
    // TAB NAVIGATION
    // ═══════════════════════════════════════════════════════════════════

    private fun selectTab(tab: Int) {
        currentTab = tab
        val tabs   = listOf(tabScanner, tabWatchlist, tabPositions, tabSettings)
        val colors = listOf(purple, green, amber, 0xFF9CA3AF.toInt())
        val bgs    = listOf(0xFF1A1528.toInt(), 0xFF1A2E1A.toInt(), 0xFF2E2A1A.toInt(), 0xFF1F2937.toInt())
        tabs.forEachIndexed { i, tv ->
            if (i == tab) { tv.setTextColor(colors[i]); tv.setBackgroundColor(bgs[i]) }
            else          { tv.setTextColor(muted);     tv.setBackgroundColor(0) }
        }
        buildContent()
    }

    // ═══════════════════════════════════════════════════════════════════
    // CONTENT BUILDER
    // ═══════════════════════════════════════════════════════════════════

    private fun buildContent() {
        when (currentTab) {
            0 -> buildScannerTab()
            1 -> buildWatchlistTab()
            2 -> buildPositionsTab()
            3 -> buildSettingsTab()
        }
    }

    // ── SCANNER TAB ───────────────────────────────────────────────────

    private fun buildScannerTab() {
        llContent.removeAllViews()

        // Chain filter chips
        addChainFilterRow()

        // Header
        addSectionHeader("🔍 Top Alt Signals", purple)

        val markets = getFilteredMarkets()
        if (markets.isEmpty()) {
            addEmptyState("No alt markets match filter")
            return
        }

        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val results = mutableListOf<Pair<PerpsMarket, com.lifecyclebot.perps.MarketData>>()
            for (market in markets) {
                try {
                    val data = PerpsMarketDataFetcher.getMarketData(market)
                    results.add(market to data)
                } catch (_: Exception) {}
            }
            // Sort by 24h change abs value (strongest movers first)
            results.sortByDescending { kotlin.math.abs(it.second.priceChange24hPct) }

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                llContent.removeAllViews()
                addChainFilterRow()
                addSectionHeader("🔥 Top Movers — ${results.size} alts", purple)

                for ((market, data) in results.take(30)) {
                    addMarketRow(market, data)
                }

                tvStats.text = "${results.size} alts scanned"
            }
        }
    }

    private fun addChainFilterRow() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 8, 16, 8)
        }
        val chains = listOf(null to "All", "BNB" to "BNB", "ETH" to "ETH", "SOL" to "SOL", "MATIC" to "Polygon")
        for ((chain, label) in chains) {
            val btn = TextView(this).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(16, 6, 16, 6)
                setTextColor(if (chainFilter == chain) white else muted)
                setBackgroundColor(if (chainFilter == chain) purple else divider)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 4
                }
                setOnClickListener {
                    chainFilter = chain
                    buildContent()
                }
            }
            row.addView(btn)
        }
        llContent.addView(row)
    }

    private fun getFilteredMarkets(): List<PerpsMarket> {
        return if (chainFilter == null) altMarkets
        else altMarkets.filter { market ->
            when (chainFilter) {
                "BNB"   -> market.symbol in setOf("DOGE","SHIB","FLOKI","BABYDOGE","PEPE","WIF","BONK","CAKE","BNB","ONE","TWT")
                "ETH"   -> market.symbol in setOf("ETH","LINK","UNI","AAVE","MKR","SNX","COMP","ENS","GRT","LDO","IMX","ARB","OP")
                "SOL"   -> market.symbol in setOf("SOL","RAY","ORCA","JUP","PYTH","DRIFT","MNGO","BONK","WIF","BOME","MEW")
                "MATIC" -> market.symbol in setOf("MATIC","QUICK","GHST","SAND","MANA","AXS","AAVE")
                else    -> true
            }
        }
    }

    private fun addMarketRow(market: PerpsMarket, data: com.lifecyclebot.perps.MarketData) {
        val change = data.priceChange24hPct
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 10, 16, 10)
            setBackgroundColor(card)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 3 }
        }

        // Symbol + chain
        val tvSym = TextView(this).apply {
            text = "${market.emoji} ${market.symbol}"
            textSize = 13f
            setTextColor(white)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        }

        // Price
        val tvPrice = TextView(this).apply {
            text = if (data.price > 1) "$${"%.2f".format(data.price)}" else "$${"%.6f".format(data.price)}"
            textSize = 12f
            setTextColor(white)
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        }

        // Change
        val changeColor = if (change >= 0) green else red
        val tvChange = TextView(this).apply {
            text = "${if (change >= 0) "+" else ""}${"%.1f".format(change)}%"
            textSize = 12f
            setTextColor(changeColor)
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Add to watchlist button
        val tvAdd = TextView(this).apply {
            text = "+"
            textSize = 16f
            setTextColor(purple)
            setPadding(16, 0, 8, 0)
            setOnClickListener { addSymbolToWatchlist(market.symbol) }
        }

        row.addView(tvSym)
        row.addView(tvPrice)
        row.addView(tvChange)
        row.addView(tvAdd)

        // Tap row → show quick trade dialog
        row.setOnClickListener { showQuickTradeDialog(market, data) }
        llContent.addView(row)
        addDivider()
    }

    // ── WATCHLIST TAB ─────────────────────────────────────────────────

    private fun buildWatchlistTab() {
        llContent.removeAllViews()
        addSectionHeader("⭐ Alt Watchlist", green)

        val items = WatchlistEngine.getWatchlist()
            .filter { item -> altMarkets.any { it.symbol == item.symbol } }

        if (items.isEmpty()) {
            addEmptyState("No alts in watchlist yet.
Tap + in the Scanner to add.")
            return
        }

        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val enriched = items.map { item ->
                val market = altMarkets.firstOrNull { it.symbol == item.symbol }
                val data = market?.let {
                    try { PerpsMarketDataFetcher.getMarketData(it) } catch (_: Exception) { null }
                }
                Triple(item, market, data)
            }
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                llContent.removeAllViews()
                addSectionHeader("⭐ Alt Watchlist (${items.size})", green)
                for ((item, market, data) in enriched) {
                    addWatchlistRow(item, market, data)
                }
                tvStats.text = "${items.size} alts watched"
            }
        }
    }

    private fun addWatchlistRow(
        item: WatchlistEngine.WatchlistItem,
        market: PerpsMarket?,
        data: com.lifecyclebot.perps.MarketData?
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(card)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 3 }
        }

        val tvSym = TextView(this).apply {
            text = "${market?.emoji ?: "🪙"} ${item.symbol}"
            textSize = 13f; setTextColor(white)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        }
        val price = data?.price ?: item.lastPrice
        val change = data?.priceChange24hPct ?: item.change24hPct
        val tvPrice = TextView(this).apply {
            text = if (price > 1) "$${"%.2f".format(price)}" else "$${"%.6f".format(price)}"
            textSize = 12f; setTextColor(white)
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        }
        val tvChange = TextView(this).apply {
            text = "${if (change >= 0) "+" else ""}${"%.1f".format(change)}%"
            textSize = 12f
            setTextColor(if (change >= 0) green else red)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvDel = TextView(this).apply {
            text = "✕"; textSize = 14f; setTextColor(red); setPadding(16, 0, 8, 0)
            setOnClickListener { WatchlistEngine.removeFromWatchlist(item.symbol); buildWatchlistTab() }
        }
        row.addView(tvSym); row.addView(tvPrice); row.addView(tvChange); row.addView(tvDel)
        if (market != null && data != null) row.setOnClickListener { showQuickTradeDialog(market, data) }
        llContent.addView(row); addDivider()
    }

    // ── POSITIONS TAB ─────────────────────────────────────────────────

    private fun buildPositionsTab() {
        llContent.removeAllViews()
        val positions = CryptoAltTrader.getAllPositions()
        val openPos   = positions.filter { it.isOpen }
        val closedPos = positions.filter { !it.isOpen }.take(20)

        addSectionHeader("📂 Open Positions (${openPos.size})", amber)

        if (openPos.isEmpty()) {
            addEmptyState("No open positions")
        } else {
            for (pos in openPos) addPositionRow(pos, isOpen = true)
        }

        addSectionHeader("📜 Recent Closed (${closedPos.size})", muted)
        if (closedPos.isEmpty()) {
            addEmptyState("No closed positions yet")
        } else {
            for (pos in closedPos) addPositionRow(pos, isOpen = false)
        }

        val totalPnl = positions.sumOf { it.getPnlSol() }
        tvStats.text = "${openPos.size} open | PnL: ${if (totalPnl >= 0) "+" else ""}${"%.4f".format(totalPnl)}◎"
    }

    private fun addPositionRow(pos: CryptoAltTrader.AltPosition, isOpen: Boolean) {
        val pnl    = pos.getPnlSol()
        val pnlPct = pos.getPnlPct()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(card)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 3 }
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "${pos.market.emoji} ${pos.market.symbol}  ${pos.direction.emoji} ${pos.leverageLabel}"
            textSize = 13f; setTextColor(white); typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = "${if (pnlPct >= 0) "+" else ""}${"%.2f".format(pnlPct)}%"
            textSize = 13f; setTextColor(if (pnlPct >= 0) green else red)
            typeface = android.graphics.Typeface.MONOSPACE
        })
        row.addView(header)
        row.addView(TextView(this).apply {
            text = "Entry: ${"%.6f".format(pos.entryPrice)}  Now: ${"%.6f".format(pos.currentPrice)}  Size: ${"%.4f".format(pos.sizeSol)}◎"
            textSize = 10f; setTextColor(muted); typeface = android.graphics.Typeface.MONOSPACE
        })
        if (isOpen) {
            row.setOnClickListener { showPositionDialog(pos) }
        }
        llContent.addView(row); addDivider()
    }

    // ── SETTINGS TAB ──────────────────────────────────────────────────

    private fun buildSettingsTab() {
        llContent.removeAllViews()
        addSectionHeader("⚙️ Crypto Alts Settings", 0xFF9CA3AF.toInt())

        val isRunning = CryptoAltTrader.isRunning()
        val isLive    = CryptoAltTrader.isLiveMode()

        // Bot on/off
        addToggleRow("🤖 Alt Trader Running", isRunning) { on ->
            if (on) CryptoAltTrader.start(applicationContext)
            else    CryptoAltTrader.stop()
            buildSettingsTab()
        }

        // Paper/live toggle
        addToggleRow("💰 Live Mode (real money)", isLive) { on ->
            if (on) {
                AlertDialog.Builder(this)
                    .setTitle("⚠️ Enable Live Trading?")
                    .setMessage("This will use REAL SOL to execute trades on-chain. Only enable when the trader has reached READY phase (55%+ win rate).")
                    .setPositiveButton("Enable Live") { _, _ -> CryptoAltTrader.setLiveMode(true); buildSettingsTab() }
                    .setNegativeButton("Cancel") { _, _ -> buildSettingsTab() }
                    .show()
            } else {
                CryptoAltTrader.setLiveMode(false)
                buildSettingsTab()
            }
        }

        // Stats summary
        addSectionHeader("📊 Performance", purple)
        addInfoRow("Balance",  "${"%.4f".format(CryptoAltTrader.getBalance())}◎")
        addInfoRow("Total PnL","${if (CryptoAltTrader.getTotalPnlSol() >= 0) "+" else ""}${"%.4f".format(CryptoAltTrader.getTotalPnlSol())}◎")
        addInfoRow("Win Rate", "${CryptoAltTrader.getWinRate().toInt()}%")
        addInfoRow("Trades",   "${CryptoAltTrader.getTotalTrades()}")

        // Chain coverage
        addSectionHeader("🌐 Chain Coverage", purple)
        addInfoRow("BNB Memes", "${PerpsMarket.values().count { it.isCrypto && !it.isSolPerp && it.symbol in setOf("DOGE","SHIB","FLOKI","BABYDOGE","PEPE","WIF","BONK","CAKE") }} tokens")
        addInfoRow("ETH Alts",  "${PerpsMarket.values().count { it.isCrypto && !it.isSolPerp && it.symbol in setOf("ETH","LINK","UNI","AAVE","ARB","OP","GRT","IMX","LDO","ENS","SNX","COMP") }} tokens")
        addInfoRow("SOL Alts",  "${PerpsMarket.values().count { it.isCrypto && !it.isSolPerp && it.symbol in setOf("SOL","RAY","JUP","ORCA","PYTH","DRIFT","BONK","WIF","BOME","MEW") }} tokens")
        addInfoRow("Total",     "${PerpsMarket.values().count { it.isCrypto && !it.isSolPerp }} alts tracked")
    }

    // ═══════════════════════════════════════════════════════════════════
    // DIALOGS
    // ═══════════════════════════════════════════════════════════════════

    private fun showQuickTradeDialog(market: PerpsMarket, data: com.lifecyclebot.perps.MarketData) {
        val price = data.price
        val change = data.priceChange24hPct
        val signal = if (change > 5) "🟢 BULLISH" else if (change < -5) "🔴 BEARISH" else "🟡 NEUTRAL"

        AlertDialog.Builder(this)
            .setTitle("${market.emoji} ${market.symbol}")
            .setMessage(
                "Price: ${if (price > 1) "$${"%.2f".format(price)}" else "$${"%.6f".format(price)}"}
" +
                "24h: ${if (change >= 0) "+" else ""}${"%.1f".format(change)}%
" +
                "Signal: $signal

" +
                "Volume 24h: ${"%.0f".format(data.volume24h / 1_000_000)}M
" +
                "Market Cap: ${if (data.marketCapUsd > 0) "$" + "%.0f".format(data.marketCapUsd / 1_000_000) + "M" else "—"}"
            )
            .setPositiveButton("⭐ Watchlist") { _, _ -> addSymbolToWatchlist(market.symbol) }
            .setNeutralButton("📊 Chain") { _, _ -> }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showPositionDialog(pos: CryptoAltTrader.AltPosition) {
        val pnlPct = pos.getPnlPct()
        AlertDialog.Builder(this)
            .setTitle("${pos.market.emoji} ${pos.market.symbol} — ${pos.direction.emoji} ${pos.leverageLabel}")
            .setMessage(
                "Size: ${"%.4f".format(pos.sizeSol)}◎
" +
                "Entry: ${"%.6f".format(pos.entryPrice)}
" +
                "Current: ${"%.6f".format(pos.currentPrice)}
" +
                "P&L: ${if (pnlPct >= 0) "+" else ""}${"%.2f".format(pnlPct)}%
" +
                "Opened: ${sdf.format(Date(pos.openedAt))}"
            )
            .setPositiveButton("Close Position") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    CryptoAltTrader.closePosition(pos.id)
                    withContext(Dispatchers.Main) { buildPositionsTab() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddToWatchlistDialog() {
        val markets = altMarkets.map { "${it.emoji} ${it.symbol}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Add Alt to Watchlist")
            .setItems(markets) { _, which ->
                addSymbolToWatchlist(altMarkets[which].symbol)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addSymbolToWatchlist(symbol: String) {
        val added = WatchlistEngine.addToWatchlist(symbol)
        Toast.makeText(this, if (added) "✅ $symbol added to watchlist" else "$symbol already in watchlist", Toast.LENGTH_SHORT).show()
        if (currentTab == 1) buildWatchlistTab()
    }

    // ═══════════════════════════════════════════════════════════════════
    // SCAN ALL
    // ═══════════════════════════════════════════════════════════════════

    private fun scanAll() {
        selectTab(0)
        buildScannerTab()
    }

    // ═══════════════════════════════════════════════════════════════════
    // UI HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private fun addSectionHeader(text: String, color: Int) {
        llContent.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(color)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(16, 14, 16, 6)
        })
    }

    private fun addEmptyState(text: String) {
        llContent.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(16, 32, 16, 32)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
    }

    private fun addDivider() {
        llContent.addView(View(this).apply {
            setBackgroundColor(divider)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })
    }

    private fun addToggleRow(label: String, current: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(card)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 3 }
        }
        row.addView(TextView(this).apply {
            text = label; textSize = 13f; setTextColor(white)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sw = Switch(this).apply { isChecked = current; setOnCheckedChangeListener { _, v -> onChange(v) } }
        row.addView(sw)
        llContent.addView(row); addDivider()
    }

    private fun addInfoRow(label: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 10, 16, 10)
            setBackgroundColor(card)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 3 }
        }
        row.addView(TextView(this).apply { text = label; textSize = 12f; setTextColor(muted); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        row.addView(TextView(this).apply { text = value; textSize = 12f; setTextColor(white); typeface = android.graphics.Typeface.MONOSPACE })
        llContent.addView(row); addDivider()
    }
}

package com.lifecyclebot.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lifecyclebot.R
import com.lifecyclebot.perps.CryptoAltTrader
import com.lifecyclebot.perps.PerpsMarket
import com.lifecyclebot.perps.PerpsMarketDataFetcher
import com.lifecyclebot.perps.PerpsMarketData
import com.lifecyclebot.perps.WatchlistEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * ═══════════════════════════════════════════════════════════════════════
 * CRYPTO ALTS ACTIVITY — V1.0
 * ═══════════════════════════════════════════════════════════════════════
 * Meme-trader-style UI for cross-chain alt tokens.
 * BNB memes, Polygon alts, ETH alts, SOL alts.
 * 4 tabs: Scanner | Watchlist | Positions | Settings
 */
class CryptoAltActivity : AppCompatActivity() {

    private val white   = 0xFFFFFFFF.toInt()
    private val muted   = 0xFF6B7280.toInt()
    private val green   = 0xFF22C55E.toInt()
    private val red     = 0xFFEF4444.toInt()
    private val amber   = 0xFFF59E0B.toInt()
    private val purple  = 0xFFA78BFA.toInt()
    private val card    = 0xFF1A1A2E.toInt()
    private val divider = 0xFF1F2937.toInt()
    private val sdf     = SimpleDateFormat("MMM dd HH:mm", Locale.US)

    private lateinit var llContent: LinearLayout
    private lateinit var tvStats: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tabScanner: TextView
    private lateinit var tabWatchlist: TextView
    private lateinit var tabPositions: TextView
    private lateinit var tabSettings: TextView

    private var currentTab = 0
    private var chainFilter: String? = null   // null = all

    // All cross-chain alt markets (not SOL perps native)
    private val altMarkets: List<PerpsMarket> by lazy {
        PerpsMarket.values().filter { it.isCrypto && !it.isSolPerp }
    }

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
        findViewById<View>(R.id.btnCryptoAltScan).setOnClickListener { selectTab(0) }
        findViewById<View>(R.id.btnCryptoAltAdd).setOnClickListener { showAddToWatchlistDialog() }

        tabScanner.setOnClickListener   { selectTab(0) }
        tabWatchlist.setOnClickListener { selectTab(1) }
        tabPositions.setOnClickListener { selectTab(2) }
        tabSettings.setOnClickListener  { selectTab(3) }

        selectTab(0)
    }

    // ═══════════════════════════════════════════════════════════════════
    // TABS
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

    private fun buildContent() {
        when (currentTab) {
            0 -> buildScannerTab()
            1 -> buildWatchlistTab()
            2 -> buildPositionsTab()
            3 -> buildSettingsTab()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // SCANNER TAB
    // ═══════════════════════════════════════════════════════════════════

    private fun buildScannerTab() {
        llContent.removeAllViews()
        addChainFilterRow()
        addSectionHeader("🔍 Top Alt Signals", purple)

        val markets = getFilteredMarkets()
        if (markets.isEmpty()) { addEmptyState("No alt markets match filter"); return }

        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val results = mutableListOf<Pair<PerpsMarket, PerpsMarketData>>()
            for (market in markets.take(40)) {
                try { results.add(market to PerpsMarketDataFetcher.getMarketData(market)) } catch (_: Exception) {}
            }
            results.sortByDescending { kotlin.math.abs(it.second.priceChange24hPct) }
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                llContent.removeAllViews()
                addChainFilterRow()
                addSectionHeader("🔥 Top Movers — ${results.size} alts", purple)
                for ((market, data) in results) addMarketRow(market, data)
                tvStats.text = "${results.size} alts scanned"
            }
        }
    }

    private fun addChainFilterRow() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 8, 16, 8)
        }
        val chains = listOf<Pair<String?, String>>(null to "All", "BNB" to "BNB", "ETH" to "ETH", "SOL" to "SOL", "MATIC" to "Polygon")
        for ((chain, label) in chains) {
            val btn = TextView(this).apply {
                text = label; textSize = 11f; gravity = Gravity.CENTER; setPadding(16, 6, 16, 6)
                setTextColor(if (chainFilter == chain) white else muted)
                setBackgroundColor(if (chainFilter == chain) purple else divider)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 4 }
                setOnClickListener { chainFilter = chain; buildContent() }
            }
            row.addView(btn)
        }
        llContent.addView(row)
    }

    private fun getFilteredMarkets(): List<PerpsMarket> {
        val bnbSymbols   = setOf("DOGE","SHIB","FLOKI","BABYDOGE","PEPE","WIF","BONK","CAKE","BNB","ONE","TWT")
        val ethSymbols   = setOf("ETH","LINK","UNI","AAVE","MKR","SNX","COMP","ENS","GRT","LDO","IMX","ARB","OP")
        val solSymbols   = setOf("SOL","RAY","ORCA","JUP","PYTH","DRIFT","MNGO","BONK","WIF","BOME","MEW")
        val maticSymbols = setOf("MATIC","QUICK","GHST","SAND","MANA","AXS","AAVE")
        return when (chainFilter) {
            "BNB"   -> altMarkets.filter { it.symbol in bnbSymbols }
            "ETH"   -> altMarkets.filter { it.symbol in ethSymbols }
            "SOL"   -> altMarkets.filter { it.symbol in solSymbols }
            "MATIC" -> altMarkets.filter { it.symbol in maticSymbols }
            else    -> altMarkets
        }
    }

    private fun addMarketRow(market: PerpsMarket, data: PerpsMarketData) {
        val change = data.priceChange24hPct
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 10, 16, 10); setBackgroundColor(card)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 3 }
        }
        row.addView(TextView(this).apply {
            text = "${market.emoji} ${market.symbol}"; textSize = 13f; setTextColor(white)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        })
        row.addView(TextView(this).apply {
            text = if (data.price > 1) "$%.2f".format(data.price) else "$%.6f".format(data.price)
            textSize = 12f; setTextColor(white); typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        })
        row.addView(TextView(this).apply {
            text = "${if (change >= 0) "+" else ""}%.1f%%".format(change)
            textSize = 12f; setTextColor(if (change >= 0) green else red)
            typeface = android.graphics.Typeface.MONOSPACE; gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = "+"; textSize = 16f; setTextColor(purple); setPadding(16, 0, 8, 0)
            setOnClickListener { addSymbolToWatchlist(market.symbol) }
        })
        row.setOnClickListener { showQuickTradeDialog(market, data) }
        llContent.addView(row); addDivider()
    }

    // ═══════════════════════════════════════════════════════════════════
    // WATCHLIST TAB
    // ═══════════════════════════════════════════════════════════════════

    private fun buildWatchlistTab() {
        llContent.removeAllViews()
        addSectionHeader("⭐ Alt Watchlist", green)
        val items = WatchlistEngine.getWatchlist().filter { item -> altMarkets.any { it.symbol == item.symbol } }
        if (items.isEmpty()) { addEmptyState("No alts in watchlist yet. Tap + to add."); return }

        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val enriched = items.map { item ->
                val market = altMarkets.firstOrNull { it.symbol == item.symbol }
                val data = market?.let { try { PerpsMarketDataFetcher.getMarketData(it) } catch (_: Exception) { null } }
                Triple(item, market, data)
            }
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                llContent.removeAllViews()
                addSectionHeader("⭐ Alt Watchlist (${items.size})", green)
                for ((item, market, data) in enriched) addWatchlistRow(item, market, data)
                tvStats.text = "${items.size} alts watched"
            }
        }
    }

    private fun addWatchlistRow(item: WatchlistEngine.WatchlistItem, market: PerpsMarket?, data: PerpsMarketData?) {
        val price  = data?.price  ?: item.lastPrice
        val change = data?.priceChange24hPct ?: item.change24hPct
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 12); setBackgroundColor(card)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 3 }
        }
        row.addView(TextView(this).apply {
            text = "${market?.emoji ?: "🪙"} ${item.symbol}"; textSize = 13f; setTextColor(white)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        })
        row.addView(TextView(this).apply {
            text = if (price > 1) "$%.2f".format(price) else "$%.6f".format(price)
            textSize = 12f; setTextColor(white); typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        })
        row.addView(TextView(this).apply {
            text = "${if (change >= 0) "+" else ""}%.1f%%".format(change)
            textSize = 12f; setTextColor(if (change >= 0) green else red); gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = "✕"; textSize = 14f; setTextColor(red); setPadding(16, 0, 8, 0)
            setOnClickListener { WatchlistEngine.removeFromWatchlist(item.symbol); buildWatchlistTab() }
        })
        if (market != null && data != null) row.setOnClickListener { showQuickTradeDialog(market, data) }
        llContent.addView(row); addDivider()
    }

    // ═══════════════════════════════════════════════════════════════════
    // POSITIONS TAB
    // ═══════════════════════════════════════════════════════════════════

    private fun buildPositionsTab() {
        llContent.removeAllViews()
        val allPos   = CryptoAltTrader.getAllPositions()
        val openPos  = allPos.filter { it.closeTime == null }
        val closedPos= allPos.filter { it.closeTime != null }.take(20)

        addSectionHeader("📂 Open Positions (${openPos.size})", amber)
        if (openPos.isEmpty()) addEmptyState("No open positions")
        else openPos.forEach { addPositionRow(it, isOpen = true) }

        addSectionHeader("📜 Recent Closed (${closedPos.size})", muted)
        if (closedPos.isEmpty()) addEmptyState("No closed positions yet")
        else closedPos.forEach { addPositionRow(it, isOpen = false) }

        val totalPnl = allPos.sumOf { it.getPnlSol() }
        tvStats.text = "${openPos.size} open | PnL: ${if (totalPnl >= 0) "+" else ""}%.4f◎".format(totalPnl)
    }

    private fun addPositionRow(pos: CryptoAltTrader.AltPosition, isOpen: Boolean) {
        val pnlPct = pos.getPnlPct()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(16, 12, 16, 12); setBackgroundColor(card)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 3 }
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "${pos.market.emoji} ${pos.market.symbol}  ${pos.direction.emoji} ${pos.leverageLabel}"
            textSize = 13f; setTextColor(white); typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = "${if (pnlPct >= 0) "+" else ""}%.2f%%".format(pnlPct)
            textSize = 13f; setTextColor(if (pnlPct >= 0) green else red); typeface = android.graphics.Typeface.MONOSPACE
        })
        row.addView(header)
        row.addView(TextView(this).apply {
            text = "Entry: %.6f  Now: %.6f  Size: %.4f◎".format(pos.entryPrice, pos.currentPrice, pos.sizeSol)
            textSize = 10f; setTextColor(muted); typeface = android.graphics.Typeface.MONOSPACE
        })
        if (isOpen) row.setOnClickListener { showPositionDialog(pos) }
        llContent.addView(row); addDivider()
    }

    // ═══════════════════════════════════════════════════════════════════
    // SETTINGS TAB
    // ═══════════════════════════════════════════════════════════════════

    private fun buildSettingsTab() {
        llContent.removeAllViews()
        addSectionHeader("⚙️ Crypto Alts Settings", 0xFF9CA3AF.toInt())

        addToggleRow("🤖 Alt Trader Running", CryptoAltTrader.isRunning()) { on ->
            if (on) CryptoAltTrader.start() else CryptoAltTrader.stop()
            buildSettingsTab()
        }
        addToggleRow("💰 Live Mode (real money)", CryptoAltTrader.isLiveMode()) { on ->
            if (on) {
                AlertDialog.Builder(this)
                    .setTitle("⚠️ Enable Live Trading?")
                    .setMessage("This will use REAL SOL. Only enable when win rate > 55%.")
                    .setPositiveButton("Enable Live") { _, _ -> CryptoAltTrader.setLiveMode(true); buildSettingsTab() }
                    .setNegativeButton("Cancel")      { _, _ -> buildSettingsTab() }
                    .show()
            } else { CryptoAltTrader.setLiveMode(false); buildSettingsTab() }
        }

        addSectionHeader("📊 Performance", purple)
        addInfoRow("Balance",  "%.4f◎".format(CryptoAltTrader.getBalance()))
        addInfoRow("Total PnL","${if (CryptoAltTrader.getTotalPnlSol() >= 0) "+" else ""}%.4f◎".format(CryptoAltTrader.getTotalPnlSol()))
        addInfoRow("Win Rate", "${CryptoAltTrader.getWinRate().toInt()}%")
        addInfoRow("Trades",   "${CryptoAltTrader.getTotalTrades()}")

        addSectionHeader("🌐 Chain Coverage", purple)
        addInfoRow("Total alts", "${altMarkets.size} tokens")
    }

    // ═══════════════════════════════════════════════════════════════════
    // DIALOGS
    // ═══════════════════════════════════════════════════════════════════

    private fun showQuickTradeDialog(market: PerpsMarket, data: PerpsMarketData) {
        val price  = data.price
        val change = data.priceChange24hPct
        val signal = when {
            change > 5  -> "🟢 BULLISH"
            change < -5 -> "🔴 BEARISH"
            else        -> "🟡 NEUTRAL"
        }
        val priceStr = if (price > 1) "$%.2f".format(price) else "$%.6f".format(price)
        val changeStr = "${if (change >= 0) "+" else ""}%.1f%%".format(change)
        val volStr  = "%.0fM".format(data.volume24h / 1_000_000)
        AlertDialog.Builder(this)
            .setTitle("${market.emoji} ${market.symbol}")
            .setMessage("Price: $priceStr\n24h: $changeStr\nSignal: $signal\n\nVolume 24h: $$volStr")
            .setPositiveButton("⭐ Watchlist") { _, _ -> addSymbolToWatchlist(market.symbol) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showPositionDialog(pos: CryptoAltTrader.AltPosition) {
        val pnlPct   = pos.getPnlPct()
        val pnlStr   = "${if (pnlPct >= 0) "+" else ""}%.2f%%".format(pnlPct)
        val openedAt = sdf.format(Date(pos.openTime))
        AlertDialog.Builder(this)
            .setTitle("${pos.market.emoji} ${pos.market.symbol} — ${pos.direction.emoji} ${pos.leverageLabel}")
            .setMessage("Size: %.4f◎\nEntry: %.6f\nCurrent: %.6f\nP&L: $pnlStr\nOpened: $openedAt".format(
                pos.sizeSol, pos.entryPrice, pos.currentPrice))
            .setPositiveButton("Close Position") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    CryptoAltTrader.requestClose(pos.id)
                    withContext(Dispatchers.Main) { buildPositionsTab() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddToWatchlistDialog() {
        val labels   = altMarkets.map { "${it.emoji} ${it.symbol}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Add Alt to Watchlist")
            .setItems(labels) { _, which -> addSymbolToWatchlist(altMarkets[which].symbol) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addSymbolToWatchlist(symbol: String) {
        val added = WatchlistEngine.addToWatchlist(symbol)
        Toast.makeText(this, if (added) "✅ $symbol added" else "$symbol already in watchlist", Toast.LENGTH_SHORT).show()
        if (currentTab == 1) buildWatchlistTab()
    }

    // ═══════════════════════════════════════════════════════════════════
    // UI HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private fun addSectionHeader(text: String, color: Int) {
        llContent.addView(TextView(this).apply {
            this.text = text; textSize = 12f; setTextColor(color)
            typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(16, 14, 16, 6)
        })
    }

    private fun addEmptyState(msg: String) {
        llContent.addView(TextView(this).apply {
            text = msg; textSize = 13f; setTextColor(muted); gravity = Gravity.CENTER
            setPadding(16, 32, 16, 32)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
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
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 12); setBackgroundColor(card)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 3 }
        }
        row.addView(TextView(this).apply {
            text = label; textSize = 13f; setTextColor(white)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Switch(this).apply { isChecked = current; setOnCheckedChangeListener { _, v -> onChange(v) } })
        llContent.addView(row); addDivider()
    }

    private fun addInfoRow(label: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 10, 16, 10); setBackgroundColor(card)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 3 }
        }
        row.addView(TextView(this).apply { text = label; textSize = 12f; setTextColor(muted); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        row.addView(TextView(this).apply { text = value; textSize = 12f; setTextColor(white); typeface = android.graphics.Typeface.MONOSPACE })
        llContent.addView(row); addDivider()
    }
}

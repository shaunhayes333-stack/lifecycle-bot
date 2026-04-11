package com.lifecyclebot.ui

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lifecyclebot.R
import com.lifecyclebot.perps.PerpsMarket
import com.lifecyclebot.perps.WatchlistEngine
import com.lifecyclebot.perps.WatchlistEngine.AlertType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class WatchlistActivity : AppCompatActivity() {

    private lateinit var llContent: LinearLayout
    private lateinit var tvStats: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tabWatchlist: TextView
    private lateinit var tabAlerts: TextView
    private lateinit var tabTriggered: TextView

    private var currentTab = 0 // 0=watchlist, 1=alerts, 2=triggered

    private val white   = 0xFFFFFFFF.toInt()
    private val muted   = 0xFF6B7280.toInt()
    private val green   = 0xFF14F195.toInt()
    private val red     = 0xFFEF4444.toInt()
    private val amber   = 0xFFF59E0B.toInt()
    private val purple  = 0xFF9945FF.toInt()
    private val surface = 0xFF111118.toInt()
    private val divider = 0xFF1F2937.toInt()
    private val sdf     = SimpleDateFormat("MMM dd HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watchlist)
        supportActionBar?.hide()

        llContent = findViewById(R.id.llWatchlistContent)
        tvStats = findViewById(R.id.tvWatchlistStats)
        progressBar = findViewById(R.id.progressWatchlist)
        tabWatchlist = findViewById(R.id.tabWatchlist)
        tabAlerts = findViewById(R.id.tabActiveAlerts)
        tabTriggered = findViewById(R.id.tabTriggered)

        WatchlistEngine.init(applicationContext)

        findViewById<View>(R.id.btnWatchlistBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAddSymbol).setOnClickListener { showAddSymbolDialog() }
        findViewById<View>(R.id.btnRefreshWatchlist).setOnClickListener { scanAll() }

        tabWatchlist.setOnClickListener { selectTab(0) }
        tabAlerts.setOnClickListener { selectTab(1) }
        tabTriggered.setOnClickListener { selectTab(2) }

        buildContent()
    }

    private fun selectTab(tab: Int) {
        currentTab = tab
        val tabs = listOf(tabWatchlist, tabAlerts, tabTriggered)
        val colors = listOf(green, amber, red)
        val bgs = listOf(0xFF1A2E1A.toInt(), 0xFF2E2A1A.toInt(), 0xFF2E1A1A.toInt())
        tabs.forEachIndexed { i, tv ->
            if (i == tab) { tv.setTextColor(colors[i]); tv.setBackgroundColor(bgs[i]) }
            else { tv.setTextColor(muted); tv.setBackgroundColor(0x00000000) }
        }
        buildContent()
    }

    private fun buildContent() {
        llContent.removeAllViews()
        val stats = WatchlistEngine.getStats()
        tvStats.text = "${stats["watchlist_size"]} items | ${stats["active_alerts"]} alerts | ${stats["triggered_alerts"]} triggered"

        when (currentTab) {
            0 -> buildWatchlistTab()
            1 -> buildAlertsTab()
            2 -> buildTriggeredTab()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WATCHLIST TAB
    // ═══════════════════════════════════════════════════════════════════════

    private fun buildWatchlistTab() {
        val items = WatchlistEngine.getWatchlist()
        if (items.isEmpty()) {
            addEmpty("No watchlist items\nTap + Add to start tracking symbols")
            return
        }

        // Quick add buttons
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        listOf("Top Gainers" to green, "Tech" to purple, "Crypto" to amber, "Miners" to 0xFFD4A017.toInt()).forEach { (label, color) ->
            quickRow.addView(TextView(this).apply {
                text = label
                textSize = 10f
                setTextColor(color)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setBackgroundColor(0xFF1A1A2E.toInt())
                setOnClickListener {
                    when (label) {
                        "Top Gainers" -> WatchlistEngine.addTopGainersToWatchlist(5)
                        "Tech" -> listOf("NVDA","AAPL","MSFT","GOOGL","META","AMZN","TSLA").forEach { WatchlistEngine.addToWatchlist(it) }
                        "Crypto" -> listOf("SOL","BTC","ETH","BNB","XRP").forEach { WatchlistEngine.addToWatchlist(it) }
                        "Miners" -> listOf("NEM","GOLD","AEM","WPM","FNV","AG").forEach { WatchlistEngine.addToWatchlist(it) }
                    }
                    buildContent()
                    Toast.makeText(this@WatchlistActivity, "$label added to watchlist", Toast.LENGTH_SHORT).show()
                }
            })
            quickRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 0) })
        }
        llContent.addView(quickRow)
        addDivider()

        items.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setBackgroundColor(surface)
            }

            // Row 1: Symbol + Price + Change
            val row1 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row1.addView(TextView(this).apply {
                text = item.symbol
                textSize = 16f
                setTextColor(white)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            row1.addView(TextView(this).apply {
                text = if (item.lastPrice > 0) "$${"%,.2f".format(item.lastPrice)}" else "---"
                textSize = 14f
                setTextColor(white)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val changeColor = if (item.change24hPct >= 0) green else red
            row1.addView(TextView(this).apply {
                text = "${if (item.change24hPct >= 0) "+" else ""}${"%.2f".format(item.change24hPct)}%"
                textSize = 14f
                setTextColor(changeColor)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.END
            })
            card.addView(row1)

            // Row 2: Actions
            val row2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            row2.addView(TextView(this).apply {
                text = "Set Alert"
                textSize = 11f
                setTextColor(amber)
                setPadding(0, dp(2), dp(16), dp(2))
                setOnClickListener { showSetAlertDialog(item.symbol, item.lastPrice) }
            })
            row2.addView(TextView(this).apply {
                text = "Remove"
                textSize = 11f
                setTextColor(red)
                setPadding(0, dp(2), 0, dp(2))
                setOnClickListener {
                    WatchlistEngine.removeFromWatchlist(item.symbol)
                    buildContent()
                }
            })
            val alertCount = WatchlistEngine.getAlertsForSymbol(item.symbol).count { it.isActive }
            if (alertCount > 0) {
                row2.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })
                row2.addView(TextView(this).apply {
                    text = "$alertCount alert${if (alertCount > 1) "s" else ""}"
                    textSize = 10f
                    setTextColor(amber)
                })
            }
            card.addView(row2)

            llContent.addView(card)
            addDivider()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ALERTS TAB
    // ═══════════════════════════════════════════════════════════════════════

    private fun buildAlertsTab() {
        val alerts = WatchlistEngine.getActiveAlerts()
        if (alerts.isEmpty()) {
            addEmpty("No active alerts\nAdd alerts from the Watchlist tab")
            return
        }

        alerts.forEach { alert ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setBackgroundColor(surface)
            }

            val typeStr = when (alert.type) {
                AlertType.PRICE_ABOVE -> "Above"
                AlertType.PRICE_BELOW -> "Below"
                AlertType.CHANGE_ABOVE -> "Change >"
                AlertType.CHANGE_BELOW -> "Change <"
                AlertType.VOLUME_SPIKE -> "Vol >"
            }
            val valueStr = when (alert.type) {
                AlertType.PRICE_ABOVE, AlertType.PRICE_BELOW -> "$${"%,.2f".format(alert.targetValue)}"
                AlertType.CHANGE_ABOVE, AlertType.CHANGE_BELOW -> "${"%.1f".format(alert.targetValue)}%"
                AlertType.VOLUME_SPIKE -> "$${"%,.0f".format(alert.targetValue)}"
            }

            row.addView(TextView(this).apply {
                text = alert.symbol
                textSize = 14f
                setTextColor(white)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            row.addView(TextView(this).apply {
                text = "$typeStr $valueStr"
                textSize = 12f
                setTextColor(amber)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = "X"
                textSize = 14f
                setTextColor(red)
                setPadding(dp(12), dp(4), dp(4), dp(4))
                setOnClickListener {
                    WatchlistEngine.removeAlert(alert.id)
                    buildContent()
                }
            })

            llContent.addView(row)
            addDivider()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TRIGGERED TAB
    // ═══════════════════════════════════════════════════════════════════════

    private fun buildTriggeredTab() {
        val triggered = WatchlistEngine.getTriggeredAlerts()
        if (triggered.isEmpty()) {
            addEmpty("No triggered alerts yet\nAlerts will appear here when price targets are hit")
            return
        }

        triggered.forEach { t ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setBackgroundColor(surface)
            }
            card.addView(TextView(this).apply {
                text = t.message
                textSize = 13f
                setTextColor(green)
            })
            card.addView(TextView(this).apply {
                text = sdf.format(Date(t.triggeredAt))
                textSize = 10f
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
            })

            llContent.addView(card)
            addDivider()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DIALOGS
    // ═══════════════════════════════════════════════════════════════════════

    private fun showAddSymbolDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        val input = EditText(this).apply {
            hint = "Symbol (e.g., NVDA, SOL, AAPL)"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        layout.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Add to Watchlist")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val symbol = input.text.toString().trim().uppercase()
                if (symbol.isNotEmpty()) {
                    val exists = PerpsMarket.values().any { it.symbol == symbol }
                    if (exists || symbol.length >= 2) {
                        WatchlistEngine.addToWatchlist(symbol)
                        buildContent()
                        Toast.makeText(this, "$symbol added", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Unknown symbol", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSetAlertDialog(symbol: String, currentPrice: Double) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        val spinnerType = Spinner(this)
        val types = arrayOf("Price Above", "Price Below", "Change Above %", "Change Below %", "Volume Spike")
        spinnerType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, types)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val inputValue = EditText(this).apply {
            hint = if (currentPrice > 0) "Target (current: $${"%.2f".format(currentPrice)})" else "Target value"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        layout.addView(spinnerType)
        layout.addView(inputValue)

        AlertDialog.Builder(this)
            .setTitle("Set Alert: $symbol")
            .setView(layout)
            .setPositiveButton("Set") { _, _ ->
                val value = inputValue.text.toString().toDoubleOrNull()
                if (value != null && value > 0) {
                    val type = when (spinnerType.selectedItemPosition) {
                        0 -> AlertType.PRICE_ABOVE
                        1 -> AlertType.PRICE_BELOW
                        2 -> AlertType.CHANGE_ABOVE
                        3 -> AlertType.CHANGE_BELOW
                        4 -> AlertType.VOLUME_SPIKE
                        else -> AlertType.PRICE_ABOVE
                    }
                    WatchlistEngine.addAlert(symbol, type, value)
                    buildContent()
                    Toast.makeText(this, "Alert set for $symbol", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SCAN ALL
    // ═══════════════════════════════════════════════════════════════════════

    private fun scanAll() {
        progressBar.visibility = View.VISIBLE
        Toast.makeText(this, "Scanning watchlist...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            withContext(Dispatchers.IO) { WatchlistEngine.scan() }
            progressBar.visibility = View.GONE
            buildContent()
            Toast.makeText(this@WatchlistActivity, "Scan complete", Toast.LENGTH_SHORT).show()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private fun addEmpty(text: String) {
        llContent.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(48), 0, 0)
        })
    }

    private fun addDivider() {
        llContent.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(divider)
        })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

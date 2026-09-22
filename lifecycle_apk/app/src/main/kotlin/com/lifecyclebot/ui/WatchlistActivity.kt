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
import com.lifecyclebot.engine.HeldPositionSupervisor7246
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
    private lateinit var tabHeld: TextView
    private lateinit var tabAlerts: TextView
    private lateinit var tabTriggered: TextView

    private var currentTab = 0 // 0=watchlist, 1=held, 2=alerts, 3=triggered

    private val white   = AateUi.TEXT
    private val muted   = AateUi.TEXT_MUTED
    private val green   = AateUi.GREEN
    private val red     = AateUi.RED
    private val amber   = AateUi.AMBER
    private val purple  = AateUi.PURPLE
    private val surface = 0xFF111118.toInt()
    private val divider = AateUi.SURFACE_3
    private val sdf     = SimpleDateFormat("MMM dd HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watchlist)
        supportActionBar?.hide()

        llContent = findViewById(R.id.llWatchlistContent)
        tvStats = findViewById(R.id.tvWatchlistStats)
        progressBar = findViewById(R.id.progressWatchlist)
        tabWatchlist = findViewById(R.id.tabWatchlist)
        tabHeld = findViewById(R.id.tabHeld)
        tabAlerts = findViewById(R.id.tabActiveAlerts)
        tabTriggered = findViewById(R.id.tabTriggered)

        WatchlistEngine.init(applicationContext)

        findViewById<View>(R.id.btnWatchlistBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAddSymbol).setOnClickListener { showAddSymbolDialog() }
        findViewById<View>(R.id.btnRefreshWatchlist).setOnClickListener { scanAll() }

        tabWatchlist.setOnClickListener { selectTab(0) }
        tabHeld.setOnClickListener { selectTab(1) }
        tabAlerts.setOnClickListener { selectTab(2) }
        tabTriggered.setOnClickListener { selectTab(3) }

        buildContent()
    }

    private fun selectTab(tab: Int) {
        currentTab = tab
        val tabs = listOf(tabWatchlist, tabHeld, tabAlerts, tabTriggered)
        val colors = listOf(green, purple, amber, red)
        val bgs = listOf(
            0xFF1A2E1A.toInt(), 0xFF221A2E.toInt(),
            0xFF2E2A1A.toInt(), 0xFF2E1A1A.toInt(),
        )
        tabs.forEachIndexed { i, tv ->
            if (i == tab) { tv.setTextColor(colors[i]); tv.setBackgroundColor(bgs[i]) }
            else { tv.setTextColor(muted); tv.setBackgroundColor(0x00000000) }
        }
        buildContent()
    }

    private fun buildContent() {
        llContent.removeAllViews()
        val stats = WatchlistEngine.getStats()
        val heldCount = try { HeldPositionSupervisor7246.snapshot().size } catch (_: Throwable) { 0 }
        tvStats.text = "${stats["watchlist_size"]} discovery | $heldCount held | ${stats["active_alerts"]} alerts"

        when (currentTab) {
            0 -> buildWatchlistTab()
            1 -> buildHeldTab()
            2 -> buildAlertsTab()
            3 -> buildTriggeredTab()
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
                setBackgroundColor(AateUi.SURFACE)
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
            // V5.0.7028 — project/Watchlist.dc.html's row.
            //
            // The render's row is HORIZONTAL and reads left to right as
            // identity -> context -> shape -> verdict:
            //
            //   [CW]  catwifout  ALERT        ╱╲╱   74
            //         DEX_BOOSTED · QUALITY        score
            //
            // The shipped row was a vertical stack of symbol / price / change
            // with two text links under it, so every row was four lines tall,
            // eight rows filled the screen, and none of them showed whether
            // the price was going anywhere. Same data, a quarter of the
            // height, plus the one thing that was missing: the trail.
            val changeColor = if (item.change24hPct >= 0) green else red
            val alertCount = WatchlistEngine.getAlertsForSymbol(item.symbol).count { it.isActive }

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), dp(10), dp(11), dp(10))
                setBackgroundResource(
                    if (alertCount > 0) R.drawable.aate_row_card_hot else R.drawable.aate_row_card
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(7) }
            }

            val row1 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            // Identity tile. The render gives each row a 30px rounded square
            // carrying the first two letters, tinted by whether the row is
            // live — it is what makes a list of tickers scannable by shape
            // instead of by reading every name.
            row1.addView(GradientTileView7028(this).apply {
                cornerDp = 10f
                labelSizeSp = 11f
                val hot = alertCount > 0
                setTile(
                    item.symbol.take(2).uppercase(),
                    if (hot) 0xFF34D399.toInt() else 0xFF2B4570.toInt(),
                    if (hot) 0xFF22D3EE.toInt() else 0xFF18244A.toInt(),
                    if (hot) AateUi.BG else AateUi.TEXT,
                )
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                    marginEnd = dp(9)
                }
            })

            val idCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            nameRow.addView(TextView(this).apply {
                text = item.symbol
                textSize = 12f
                setTextColor(white)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            if (alertCount > 0) {
                nameRow.addView(TextView(this).apply {
                    text = if (alertCount > 1) "$alertCount ALERTS" else "ALERT"
                    textSize = 8f
                    setTextColor(0xFFFDE68A.toInt())
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    background = androidx.core.content.ContextCompat
                        .getDrawable(this@WatchlistActivity, R.drawable.aate_chip_tint)
                        ?.mutate()?.also { it.setTint(0x28FBBF24) }
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { marginStart = dp(6) }
                })
            }
            idCol.addView(nameRow)
            idCol.addView(TextView(this).apply {
                // The render's second line is the SOURCE. The app's watchlist
                // items carry a signal and a price instead, so that is what
                // this says — the layout is the render's, the content is what
                // this screen actually knows.
                text = buildString {
                    append(if (item.lastPrice > 0) "$" + "%,.4f".format(item.lastPrice) else "no mark")
                    if (item.signal.isNotBlank() && item.signal != "NEUTRAL") {
                        append(" · ").append(item.signal)
                    }
                }
                textSize = 9f
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row1.addView(idCol)

            // The trail. Drawn only when the scan has actually recorded two or
            // more points; a single observation gets no line, because a flat
            // stroke would assert a steady price nobody watched.
            val trail = try { WatchlistEngine.priceTrail7028(item.symbol) } catch (_: Throwable) { FloatArray(0) }
            if (trail.size >= 2) {
                row1.addView(SparklineView7009(this).apply {
                    lineColor = changeColor
                    strokeWidthDp = 1.5f
                    setSeries(trail, animate = false)
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(20)).apply {
                        marginStart = dp(8)
                        marginEnd = dp(8)
                    }
                })
            }

            val verdict = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
            }
            verdict.addView(TextView(this).apply {
                text = "${if (item.change24hPct >= 0) "+" else ""}${"%.1f".format(item.change24hPct)}%"
                textSize = 12f
                setTextColor(changeColor)
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD,
                )
                gravity = Gravity.END
            })
            verdict.addView(TextView(this).apply {
                text = "24h"
                textSize = 8f
                setTextColor(0xFF566B90.toInt())
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = Gravity.END
            })
            row1.addView(verdict)
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
            // V5.0.7028 — the alert count moved up into the row's ALERT chip,
            // where the render puts it. Repeating it here as a third trailing
            // label is what made the old row four lines tall.
            card.addView(row2)

            llContent.addView(card)
            addDivider()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HELD TAB — canonical owned inventory, completely separate from discovery
    // ═══════════════════════════════════════════════════════════════════════

    private fun buildHeldTab() {
        val rows = HeldPositionSupervisor7246.snapshot()
        if (rows.isEmpty()) {
            addEmpty("No held positions\nOwned assets move here immediately after execution")
            return
        }

        rows.forEach { row ->
            val markColor = when (row.markState) {
                "FRESH" -> green
                "STALE_REFRESH" -> amber
                else -> red
            }
            val ageText = when {
                row.markAgeMs == Long.MAX_VALUE -> "no mark"
                row.markAgeMs < 1_000L -> "<1s"
                row.markAgeMs < 60_000L -> "${row.markAgeMs / 1_000}s"
                else -> "${row.markAgeMs / 60_000}m"
            }
            val pnlPct = if (row.entryPriceUsd > 0.0 && row.currentPriceUsd > 0.0)
                ((row.currentPriceUsd / row.entryPriceUsd) - 1.0) * 100.0 else Double.NaN
            val pnlColor = when {
                !pnlPct.isFinite() -> muted
                pnlPct >= 0.0 -> green
                else -> red
            }

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), dp(10), dp(11), dp(10))
                setBackgroundResource(
                    if (row.markState == "MISSING") R.drawable.aate_row_card_hot else R.drawable.aate_row_card
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(7) }
            }

            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(GradientTileView7028(this).apply {
                cornerDp = 10f
                labelSizeSp = 11f
                setTile(
                    row.symbol.take(2).uppercase(),
                    if (row.markState == "FRESH") 0xFF34D399.toInt() else 0xFFF59E0B.toInt(),
                    0xFF18244A.toInt(),
                    AateUi.TEXT,
                )
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(9) }
            })

            val identity = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            identity.addView(TextView(this).apply {
                text = row.symbol
                textSize = 12f
                setTextColor(white)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            identity.addView(TextView(this).apply {
                text = "${row.mode.uppercase()} · ${row.lane} · ${row.assetClass.tag}"
                textSize = 9f
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            top.addView(identity)

            val stateCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
            }
            stateCol.addView(TextView(this).apply {
                text = row.markState
                textSize = 10f
                setTextColor(markColor)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.END
            })
            stateCol.addView(TextView(this).apply {
                text = ageText
                textSize = 8f
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = Gravity.END
            })
            top.addView(stateCol)
            card.addView(top)

            card.addView(TextView(this).apply {
                text = buildString {
                    append("entry ")
                    append(if (row.entryPriceUsd > 0.0) "$" + "%.8g".format(row.entryPriceUsd) else "—")
                    append("  mark ")
                    append(if (row.currentPriceUsd > 0.0) "$" + "%.8g".format(row.currentPriceUsd) else "—")
                    append("  PnL ")
                    append(if (pnlPct.isFinite()) "${if (pnlPct >= 0) "+" else ""}${"%.1f".format(pnlPct)}%" else "—")
                }
                textSize = 10f
                setTextColor(pnlColor)
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(0, dp(7), 0, 0)
            })
            card.addView(TextView(this).apply {
                text = "qty=${"%.8g".format(row.remainingQty)} · ${row.markSource.ifBlank { "mark pending" }} · ${row.mint.take(18)}…"
                textSize = 8f
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            })

            llContent.addView(card)
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

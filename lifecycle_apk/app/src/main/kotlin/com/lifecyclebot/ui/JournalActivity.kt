package com.lifecyclebot.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lifecyclebot.R
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.BotService
import com.lifecyclebot.engine.CurrencyManager
import com.lifecyclebot.engine.TradeJournal
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JournalActivity : AppCompatActivity() {

    private lateinit var llJournalTrades: LinearLayout
    private lateinit var tvJournalPnl: TextView
    private lateinit var tvJournalWinRate: TextView
    private lateinit var tvJournalCount: TextView
    private lateinit var tvJournalAvgWin: TextView
    private lateinit var journal: TradeJournal
    private lateinit var currency: CurrencyManager

    private var pollJob: Job? = null

    // V5.9.330 OOM FIX: Paginate the trade list. Building thousands of Views
    // at once (every 2s polling loop) causes OOM → process kill → START_STICKY
    // → bot appears to "randomly start". Show only the most recent PAGE_SIZE
    // trades; user can tap "Load More" to see older ones.
    private val PAGE_SIZE = 100
    private var currentPage = 1          // number of pages currently visible (grows on "Load More")
    private var cachedSellEntries = listOf<com.lifecyclebot.engine.TradeJournal.JournalEntry>()

    private val white = 0xFFFFFFFF.toInt()
    private val muted = 0xFF6B7280.toInt()
    private val green = 0xFF10B981.toInt()
    private val red = 0xFFEF4444.toInt()
    private val amber = 0xFFF59E0B.toInt()
    private val surface = 0xFF111118.toInt()
    private val divider = 0xFF1F2937.toInt()

    private val sdf = SimpleDateFormat("MMM dd HH:mm", Locale.US)

    // V5.9.248: mode filter — LIVE / PAPER / ALL
    private var currentModeFilter: String? = null  // null = ALL, "live", "paper"
    private var tvFilterLive: TextView? = null
    private var tvFilterPaper: TextView? = null
    private var tvFilterAll: TextView? = null
    private var tabBarView: android.view.View? = null  // V5.9.264: keep the filter bar across refreshes

    companion object {
        private const val WIN_THRESHOLD_PCT = 0.5
        private const val LOSS_THRESHOLD_PCT = -2.0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_journal)
        supportActionBar?.hide()

        // V5.9.431 — belt-and-braces: ensure SQLite-backed trade store is
        // open even if Journal is the first activity after a cold start and
        // something skipped AATEApp init. The store's own lazy-init also
        // covers this, but calling explicitly here guarantees the persisted
        // trades load synchronously before buildJournal runs.
        try {
            com.lifecyclebot.engine.TradeHistoryStore.init(applicationContext)
        } catch (_: Exception) {}

        journal = try {
            BotService.instance?.tradeJournal ?: TradeJournal(applicationContext)
        } catch (_: Exception) {
            TradeJournal(applicationContext)
        }

        currency = try {
            BotService.instance?.currencyManager ?: CurrencyManager(applicationContext)
        } catch (_: Exception) {
            CurrencyManager(applicationContext)
        }

        llJournalTrades = findViewById(R.id.llJournalTrades)
        tvJournalPnl = findViewById(R.id.tvJournalPnl)
        tvJournalWinRate = findViewById(R.id.tvJournalWinRate)
        tvJournalCount = findViewById(R.id.tvJournalCount)
        tvJournalAvgWin = findViewById(R.id.tvJournalAvgWin)

        findViewById<View>(R.id.btnJournalBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnExportCsv).setOnClickListener { showExportDialog() }
        findViewById<View>(R.id.btnClearJournal).setOnClickListener { showClearConfirmDialog() }
        findViewById<View>(R.id.btnRestoreStats).setOnClickListener { showRestoreStatsDialog() }

        startPolling()
        setupModeFilterTabs()
    }

    private fun setupModeFilterTabs() {
        // V5.9.264 BUILD-FIX: previous version added the tab bar as a sibling
        // of llJournalTrades, but llJournalTrades' parent is a ScrollView
        // which throws "ScrollView can host only one direct child" on addView.
        // Now we insert the tab bar as the first child of llJournalTrades so
        // it lives inside the scroll content.
        val tabBar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun makeTab(label: String, filter: String?): TextView {
            return TextView(this).apply {
                text = label
                textSize = 12f
                setPadding(dp(14), dp(6), dp(14), dp(6))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).also { it.marginEnd = dp(4) }
                gravity = android.view.Gravity.CENTER
                setBackgroundColor(0xFF1F2937.toInt())
                setTextColor(if (filter == currentModeFilter) 0xFF10B981.toInt() else 0xFF9CA3AF.toInt())
                setOnClickListener {
                    currentModeFilter = filter
                    currentPage = 1  // V5.9.330: reset pagination on filter change
                    updateTabColors()
                    refreshTrades()
                }
            }
        }

        tvFilterAll   = makeTab("ALL",   null)
        tvFilterLive  = makeTab("💰 LIVE",  "live")
        tvFilterPaper = makeTab("📝 PAPER", "paper")

        tabBar.addView(tvFilterAll)
        tabBar.addView(tvFilterLive)
        tabBar.addView(tvFilterPaper)

        // Inject as first child of llJournalTrades (which IS the ScrollView's
        // single child). refreshTrades() calls removeAllViews() on
        // llJournalTrades, so re-inject after each refresh as well.
        tabBarView = tabBar
        try { llJournalTrades.addView(tabBar, 0) } catch (_: Exception) {}
        updateTabColors()
    }
    
    /** V5.9.264: re-attach the filter bar after llJournalTrades.removeAllViews(). */
    private fun reattachTabBar() {
        val tb = tabBarView ?: return
        try {
            (tb.parent as? android.view.ViewGroup)?.removeView(tb)
            llJournalTrades.addView(tb, 0)
        } catch (_: Exception) {}
    }

    private fun updateTabColors() {
        val active = 0xFF10B981.toInt()
        val inactive = 0xFF9CA3AF.toInt()
        tvFilterAll?.setTextColor(if (currentModeFilter == null) active else inactive)
        tvFilterLive?.setTextColor(if (currentModeFilter == "live") active else inactive)
        tvFilterPaper?.setTextColor(if (currentModeFilter == "paper") active else inactive)
    }

    override fun onDestroy() {
        pollJob?.cancel()
        super.onDestroy()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    // V5.9.330: Fetch data on IO thread, render on main.
                    // journal.buildJournal() hits SharedPreferences/TradeHistoryStore —
                    // doing this on the main thread causes ANR when trade count is large.
                    val tokens = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        getTokensSnapshot()
                    }
                    // buildJournal renders on the main thread (required for View ops)
                    refreshTradesWithTokens(tokens)
                } catch (_: Exception) {
                }
                // V5.9.330: Slowed from 2s → 10s. Building 100-View lists on
                // the main thread 30x/min was burning CPU and causing frame drops.
                delay(10_000)
            }
        }
    }

    /**
     * Show export options dialog
     */
    private fun showExportDialog() {
        val options = arrayOf(
            "📝 PAPER Trades Only (CSV)",
            "💰 LIVE Trades Only (CSV)",
            "📊 ALL Trades (CSV)",
            "📄 PDF Tax Report (All)",
            "🏛️ IRS Form 8949 (All)",
            "📦 Export All Formats"
        )

        android.app.AlertDialog.Builder(this)
            .setTitle("Export Trade Journal")
            .setItems(options) { _, which ->
                lifecycleScope.launch {
                    val tokens = getTokensSnapshot()
                    when (which) {
                        0 -> exportPaperCsv(tokens)
                        1 -> exportLiveCsv(tokens)
                        2 -> exportCsv(tokens)
                        3 -> exportPdf(tokens)
                        4 -> exportIrs8949(tokens)
                        5 -> exportAll(tokens)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportPaperCsv(tokens: Map<String, TokenState>) {
        val intent = journal.exportPaperCsv(tokens)
        if (intent != null) {
            startActivity(Intent.createChooser(intent, "Export Paper Trades CSV"))
        } else {
            Toast.makeText(this, "No paper trades to export", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportLiveCsv(tokens: Map<String, TokenState>) {
        val intent = journal.exportLiveCsv(tokens)
        if (intent != null) {
            startActivity(Intent.createChooser(intent, "Export Live Trades CSV"))
        } else {
            Toast.makeText(this, "No live trades to export", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportCsv(tokens: Map<String, TokenState>) {
        val intent = journal.exportCsv(tokens)
        if (intent != null) {
            startActivity(Intent.createChooser(intent, "Export CSV"))
        } else {
            Toast.makeText(this, "No trades to export yet", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportPdf(tokens: Map<String, TokenState>) {
        val intent = journal.exportPdf(tokens)
        if (intent != null) {
            startActivity(Intent.createChooser(intent, "Export PDF Tax Report"))
        } else {
            Toast.makeText(this, "No trades to export yet", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportIrs8949(tokens: Map<String, TokenState>) {
        val intent = journal.exportIrs8949(tokens)
        if (intent != null) {
            startActivity(Intent.createChooser(intent, "Export IRS Form 8949"))
        } else {
            Toast.makeText(this, "No trades to export yet", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportAll(tokens: Map<String, TokenState>) {
        val exports = journal.exportAll(tokens)
        if (exports.isEmpty()) {
            Toast.makeText(this, "No trades to export yet", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Exported ${exports.size} formats!", Toast.LENGTH_SHORT).show()

        exports.forEach { (name, intent) ->
            startActivity(Intent.createChooser(intent, "Share $name"))
        }
    }

    private fun getTokensSnapshot(): Map<String, TokenState> {
        return synchronized(BotService.status.tokens) {
            BotService.status.tokens.toMap()
        }
    }

    private fun refreshTrades() {
        try {
            buildJournal(getTokensSnapshot())
        } catch (_: Exception) {
            showEmptyJournal()
        }
    }

    /** V5.9.330: Called from IO-prefetched polling path with pre-fetched tokens. */
    private fun refreshTradesWithTokens(tokens: Map<String, TokenState>) {
        try {
            buildJournal(tokens)
        } catch (_: Exception) {
            showEmptyJournal()
        }
    }

    private fun buildJournal(tokens: Map<String, TokenState>) {
        // V5.9.248: apply mode filter — live / paper / all
        val allEntries = journal.buildJournal(tokens)
        val filtered = when (currentModeFilter) {
            "live"  -> allEntries.filter { it.mode.equals("live",  ignoreCase = true) }
            "paper" -> allEntries.filter { it.mode.equals("paper", ignoreCase = true) }
            else    -> allEntries
        }
        val stats = journal.getStatsFiltered(filtered)
        val entries = filtered
        val sellEntries = entries.filter { it.side == "SELL" }

        tvJournalPnl.text = currency.format(stats.totalPnlSol, showPlus = true)
        tvJournalPnl.setTextColor(if (stats.totalPnlSol >= 0.0) green else red)

        tvJournalWinRate.text = "${stats.winRate.toInt()}%  (${stats.totalWins}W/${stats.totalLosses}L)"
        tvJournalCount.text = sellEntries.size.toString()
        tvJournalAvgWin.text = if (stats.totalWins > 0) {
            "%+.1f%%".format(stats.avgWinPct)
        } else {
            "0.0%"
        }

        llJournalTrades.removeAllViews()
        reattachTabBar()  // V5.9.264: keep the filter tabs visible across refreshes

        if (sellEntries.isEmpty()) {
            showEmptyTradeList()
            return
        }

        // V5.9.330 OOM FIX: Only render up to (PAGE_SIZE * currentPage) most-recent
        // trades. "Load More" button below list bumps currentPage by 1.
        // Building 5000+ Views on the main thread every 2s caused OOM crashes.
        cachedSellEntries = sellEntries
        val pagedEntries = sellEntries.takeLast(PAGE_SIZE * currentPage)

        pagedEntries.forEach { entry ->
            val outcome = classifyOutcome(entry.pnlPct)
            val pnlColor = when (outcome) {
                TradeOutcome.WIN -> green
                TradeOutcome.LOSS -> red
                TradeOutcome.SCRATCH -> amber
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setBackgroundColor(surface)
            }

            val accent = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    dp(3),
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).also { it.marginEnd = dp(12) }
                setBackgroundColor(pnlColor)
            }

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            info.addView(TextView(this).apply {
                val reasonText = entry.reason.take(22)
                text = "${entry.symbol}  ·  $reasonText"
                textSize = 13f
                setTextColor(white)
                typeface = Typeface.DEFAULT_BOLD
            })

            info.addView(TextView(this).apply {
                val modeEmoji = if (entry.mode.equals("paper", ignoreCase = true)) "📝" else "💰"
                val tradingEmoji = entry.tradingModeEmoji.ifEmpty { "📈" }
                val tradingModeText = entry.tradingMode.ifEmpty { "STANDARD" }
                text = "${sdf.format(Date(entry.ts))}  ·  $modeEmoji ${entry.mode.uppercase()}  ·  $tradingEmoji $tradingModeText"
                textSize = 10f
                setTextColor(muted)
                typeface = Typeface.MONOSPACE
            })

            if (outcome == TradeOutcome.SCRATCH) {
                info.addView(TextView(this).apply {
                    text = "SCRATCH"
                    textSize = 10f
                    setTextColor(amber)
                    typeface = Typeface.DEFAULT_BOLD
                })
            }

            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
            }

            right.addView(TextView(this).apply {
                text = "%+.1f%%".format(entry.pnlPct)
                textSize = 15f
                setTextColor(pnlColor)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END
            })

            right.addView(TextView(this).apply {
                text = currency.format(entry.pnlSol, showPlus = true)
                textSize = 11f
                setTextColor(pnlColor)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.END
            })

            row.addView(accent)
            row.addView(info)
            row.addView(right)

            llJournalTrades.addView(row)
            llJournalTrades.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1
                )
                setBackgroundColor(divider)
            })
        }

        // V5.9.330: "Load More" button — shows if there are older trades beyond current page
        val totalShowing = PAGE_SIZE * currentPage
        if (cachedSellEntries.size > totalShowing) {
            val loadMoreBtn = TextView(this).apply {
                text = "⬆️  Load ${minOf(PAGE_SIZE, cachedSellEntries.size - totalShowing)} older trades  (${cachedSellEntries.size - totalShowing} remaining)"
                textSize = 13f
                setPadding(dp(16), dp(14), dp(16), dp(14))
                setTextColor(0xFF60A5FA.toInt())
                gravity = android.view.Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setBackgroundColor(0xFF1F2937.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(8) }
                setOnClickListener {
                    currentPage++
                    refreshTrades()
                }
            }
            llJournalTrades.addView(loadMoreBtn)
        } else if (cachedSellEntries.size > PAGE_SIZE) {
            // All trades loaded — show count label
            llJournalTrades.addView(TextView(this).apply {
                text = "— all ${cachedSellEntries.size} trades loaded —"
                textSize = 11f
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setTextColor(muted)
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
        }
    }

    private fun showClearConfirmDialog() {
        val tradeCount = com.lifecyclebot.engine.TradeHistoryStore.getTotalTradeCount()

        android.app.AlertDialog.Builder(this)
            .setTitle("Clear All Trade Stats?")
            .setMessage(
                "This will reset $tradeCount journal rows AND every visible trade counter on the main screen:\n\n" +
                    "• Trade Journal rows + lifetime W/L/PnL\n" +
                    "• 24h Trades pill + Live Readiness gauges\n" +
                    "• All Traders summary + lane breakdown (M/A/P/S/FX/MT/CD)\n" +
                    "• Per-lane sub-trader counters (Crypto / Perps / Stocks / Forex / Metals / Commodities)\n\n" +
                    "✅ PRESERVED:\n" +
                    "• BehaviorAI (streak, tilt, discipline)\n" +
                    "• FluidLearningAI (learning progress %)\n" +
                    "• ML Engine weights\n" +
                    "• Pattern classifier / SmartSizer memory\n" +
                    "• Open positions + paper / live wallet balance\n" +
                    "• Active 30-Day Proof Run (timeline keeps running on the same Day-X)\n\n" +
                    "(To wipe learned intelligence, use Behavior → Reset All Learning.)\n\n" +
                    "This action cannot be undone."
            )
            .setPositiveButton("Clear Journal") { _, _ ->
                com.lifecyclebot.engine.TradeHistoryStore.clearAllTrades()
                Toast.makeText(this, "Journal cleared — learned AI preserved", Toast.LENGTH_SHORT).show()
                refreshTrades()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * V5.6.28c: Manual restoration dialog for lost paper treasury balance.
     * Shows input dialog to restore paper treasury value that was wiped.
     * V5.9.317: FIXED — AlertDialog with both setMessage() + setItems() was
     * suppressing the items list (Android only renders one). Switched to a
     * custom view with explicit option buttons so user can actually pick.
     */
    private fun showRestoreStatsDialog() {
        val currentPaper = com.lifecyclebot.v3.scoring.CashGenerationAI.getTreasuryBalance(true)
        val currentLive = com.lifecyclebot.v3.scoring.CashGenerationAI.getTreasuryBalance(false)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        container.addView(TextView(this).apply {
            text = "Current values:\n• Paper Treasury: ${String.format("%.2f", currentPaper)} SOL\n• Live Treasury: ${String.format("%.2f", currentLive)} SOL\n\nWhat would you like to restore?"
            setPadding(0, 0, 0, dp(16))
            textSize = 14f
        })

        lateinit var dlg: android.app.AlertDialog

        fun mkBtn(label: String, color: Int, onClick: () -> Unit): android.widget.Button {
            return android.widget.Button(this).apply {
                text = label
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(color)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 0, dp(8)) }
                setOnClickListener {
                    dlg.dismiss()
                    onClick()
                }
            }
        }

        container.addView(mkBtn("Restore Paper Treasury", 0xFF10B981.toInt()) {
            showTreasuryInputDialog("Paper", true)
        })
        container.addView(mkBtn("Restore Live Treasury", 0xFF9945FF.toInt()) {
            showTreasuryInputDialog("Live", false)
        })
        container.addView(mkBtn("Restore Both", 0xFFF59E0B.toInt()) {
            showBothTreasuryInputDialog()
        })

        dlg = android.app.AlertDialog.Builder(this)
            .setTitle("Restore Stats")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.show()
    }
    
    private fun showTreasuryInputDialog(type: String, isPaper: Boolean) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Enter $type treasury balance (SOL)"
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        
        android.app.AlertDialog.Builder(this)
            .setTitle("Restore $type Treasury")
            .setMessage("Enter your $type treasury balance in SOL:")
            .setView(input)
            .setPositiveButton("Restore") { _, _ ->
                val value = input.text.toString().toDoubleOrNull()
                if (value != null && value > 0) {
                    if (isPaper) {
                        com.lifecyclebot.v3.scoring.CashGenerationAI.manualRestorePaperTreasury(value)
                    } else {
                        com.lifecyclebot.v3.scoring.CashGenerationAI.manualRestoreLiveTreasury(value)
                    }
                    Toast.makeText(this, "$type treasury restored to $value SOL", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Invalid value", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showBothTreasuryInputDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        
        val paperInput = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Paper treasury (SOL)"
        }
        val liveInput = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Live treasury (SOL)"
        }
        
        layout.addView(TextView(this).apply { 
            text = "Paper Treasury:"
            setTextColor(0xFF10B981.toInt())
        })
        layout.addView(paperInput)
        layout.addView(TextView(this).apply { 
            text = "\nLive Treasury:"
            setTextColor(0xFF9945FF.toInt())
        })
        layout.addView(liveInput)
        
        android.app.AlertDialog.Builder(this)
            .setTitle("Restore Both Treasuries")
            .setView(layout)
            .setPositiveButton("Restore") { _, _ ->
                val paperValue = paperInput.text.toString().toDoubleOrNull()
                val liveValue = liveInput.text.toString().toDoubleOrNull()
                
                var restored = 0
                if (paperValue != null && paperValue > 0) {
                    com.lifecyclebot.v3.scoring.CashGenerationAI.manualRestorePaperTreasury(paperValue)
                    restored++
                }
                if (liveValue != null && liveValue > 0) {
                    com.lifecyclebot.v3.scoring.CashGenerationAI.manualRestoreLiveTreasury(liveValue)
                    restored++
                }
                
                if (restored > 0) {
                    Toast.makeText(this, "Treasury values restored!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "No valid values entered", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEmptyJournal() {
        llJournalTrades.removeAllViews()
        showEmptyTradeList()
        tvJournalPnl.text = currency.format(0.0, showPlus = false)
        tvJournalPnl.setTextColor(muted)
        tvJournalWinRate.text = "0%  (0W/0L)"
        tvJournalCount.text = "0"
        tvJournalAvgWin.text = "0.0%"
    }

    private fun showEmptyTradeList() {
        llJournalTrades.removeAllViews()
        reattachTabBar()  // V5.9.264: filter tabs stay visible
        llJournalTrades.addView(TextView(this).apply {
            text = "No trades yet"
            textSize = 14f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(48), 0, 0)
        })
    }

    private fun classifyOutcome(pnlPct: Double): TradeOutcome {
        return when {
            pnlPct >= WIN_THRESHOLD_PCT -> TradeOutcome.WIN
            pnlPct <= LOSS_THRESHOLD_PCT -> TradeOutcome.LOSS
            else -> TradeOutcome.SCRATCH
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private enum class TradeOutcome {
        WIN,
        LOSS,
        SCRATCH
    }
}
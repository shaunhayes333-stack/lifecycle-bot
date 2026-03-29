package com.lifecyclebot.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lifecyclebot.R
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.BotService
import com.lifecyclebot.engine.CurrencyManager
import com.lifecyclebot.engine.TradeJournal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class JournalActivity : AppCompatActivity() {

    // NO ViewModel - directly read from BotService.status to avoid restarts
    private lateinit var llJournalTrades: LinearLayout
    private lateinit var tvJournalPnl: TextView
    private lateinit var tvJournalWinRate: TextView
    private lateinit var tvJournalCount: TextView
    private lateinit var tvJournalAvgWin: TextView
    private lateinit var journal: TradeJournal
    private lateinit var currency: CurrencyManager

    private val white   = 0xFFFFFFFF.toInt()
    private val muted   = 0xFF6B7280.toInt()
    private val green   = 0xFF10B981.toInt()
    private val red     = 0xFFEF4444.toInt()
    private val amber   = 0xFFF59E0B.toInt()
    private val purple  = 0xFF9945FF.toInt()
    private val surface = 0xFF111118.toInt()
    private val divider = 0xFF1F2937.toInt()
    private val sdf     = SimpleDateFormat("MMM dd HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_journal)
        supportActionBar?.hide()

        // Don't use ViewModel - read directly from service
        journal  = try { BotService.instance?.tradeJournal ?: TradeJournal(applicationContext) }
                   catch (_: Exception) { TradeJournal(applicationContext) }
        currency = try { BotService.instance?.currencyManager ?: CurrencyManager(applicationContext) }
                   catch (_: Exception) { CurrencyManager(applicationContext) }

        llJournalTrades = findViewById(R.id.llJournalTrades)
        tvJournalPnl    = findViewById(R.id.tvJournalPnl)
        tvJournalWinRate= findViewById(R.id.tvJournalWinRate)
        tvJournalCount  = findViewById(R.id.tvJournalCount)
        tvJournalAvgWin = findViewById(R.id.tvJournalAvgWin)

        findViewById<View>(R.id.btnJournalBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnExportCsv).setOnClickListener { showExportDialog() }
        findViewById<View>(R.id.btnClearJournal).setOnClickListener { showClearConfirmDialog() }

        // Simple polling loop - no ViewModel, just read from BotService
        lifecycleScope.launch {
            while (true) {
                try {
                    val tokens = synchronized(BotService.status.tokens) {
                        BotService.status.tokens.toMap()
                    }
                    buildJournal(tokens)
                } catch (_: Exception) {}
                delay(2000)
            }
        }
    }
    
    /**
     * Show export options dialog - Paper/Live + CSV, PDF, or IRS 8949
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
                    val tokens = synchronized(BotService.status.tokens) {
                        BotService.status.tokens.toMap()
                    }
                    
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
        
        // Show success and share the first one
        Toast.makeText(this, "Exported ${exports.size} formats!", Toast.LENGTH_SHORT).show()
        
        // Share all files via multiple intents
        exports.forEach { (name, intent) ->
            startActivity(Intent.createChooser(intent, "Share $name"))
        }
    }

    private fun buildJournal(tokens: Map<String, TokenState>) {
        val stats = journal.getStats(tokens)
        val entries = journal.buildJournal(tokens)

        // Stats header
        tvJournalPnl.text = currency.format(stats.totalPnlSol, showPlus = true)
        tvJournalPnl.setTextColor(if (stats.totalPnlSol >= 0) green else red)
        tvJournalWinRate.text = "${stats.winRate.toInt()}%  (${stats.totalWins}W/${stats.totalLosses}L)"
        tvJournalCount.text   = "${stats.totalTrades}"
        tvJournalAvgWin.text  = "%+.1f%%".format(stats.avgWinPct)

        // Trade list
        llJournalTrades.removeAllViews()

        if (entries.isEmpty()) {
            llJournalTrades.addView(TextView(this).apply {
                text = "No trades yet"
                textSize = 14f
                setTextColor(muted)
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(48), 0, 0)
            })
            return
        }

        entries.filter { it.side == "SELL" }.forEach { entry ->
            val isWin   = entry.pnlSol > 0
            val pnlColor = if (isWin) green else red

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setBackgroundColor(surface)
            }

            // Left colour accent
            val accent = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(3), LinearLayout.LayoutParams.MATCH_PARENT).also {
                    it.marginEnd = dp(12)
                }
                setBackgroundColor(pnlColor)
            }

            // Info column
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = "${entry.symbol}  ·  ${entry.reason.take(22)}"
                textSize = 13f
                setTextColor(white)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            info.addView(TextView(this).apply {
                val modeEmoji = if (entry.mode == "paper") "📝" else "💰"
                val tradingEmoji = entry.tradingModeEmoji.ifEmpty { "📈" }
                text = "${sdf.format(Date(entry.ts))}  ·  $modeEmoji ${entry.mode.uppercase()}  ·  $tradingEmoji ${entry.tradingMode.ifEmpty { "STANDARD" }}"
                textSize = 10f
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
            })

            // P&L column
            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.END
            }
            right.addView(TextView(this).apply {
                text = "%+.1f%%".format(entry.pnlPct)
                textSize = 15f
                setTextColor(pnlColor)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.END
            })
            right.addView(TextView(this).apply {
                text = currency.format(entry.pnlSol, showPlus = true)
                textSize = 11f
                setTextColor(pnlColor)
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = android.view.Gravity.END
            })

            row.addView(accent)
            row.addView(info)
            row.addView(right)

            llJournalTrades.addView(row)
            llJournalTrades.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(divider)
            })
        }
    }
    
    private fun showClearConfirmDialog() {
        val tradeCount = com.lifecyclebot.engine.TradeHistoryStore.getTotalTradeCount()
        
        android.app.AlertDialog.Builder(this)
            .setTitle("Clear All Trade History?")
            .setMessage("This will permanently delete $tradeCount trades from the journal.\n\n" +
                "Your win rate and all statistics will be reset to 0.\n\n" +
                "This action cannot be undone.")
            .setPositiveButton("Clear All") { _, _ ->
                com.lifecyclebot.engine.TradeHistoryStore.clearAllTrades()
                Toast.makeText(this, "Trade history cleared", Toast.LENGTH_SHORT).show()
                refreshTrades()  // Refresh the UI
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

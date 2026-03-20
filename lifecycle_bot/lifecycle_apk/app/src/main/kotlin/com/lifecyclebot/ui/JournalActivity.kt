package com.lifecyclebot.ui

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.lifecyclebot.R
import com.lifecyclebot.engine.BotService
import com.lifecyclebot.engine.CurrencyManager
import com.lifecyclebot.engine.TradeJournal
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class JournalActivity : AppCompatActivity() {

    private lateinit var vm: BotViewModel
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

        vm = ViewModelProvider(this)[BotViewModel::class.java]

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
        findViewById<View>(R.id.btnExportCsv).setOnClickListener { exportCsv() }

        lifecycleScope.launch {
            vm.ui.collect { state -> buildJournal(state) }
        }
    }

    private fun buildJournal(state: UiState) {
        val stats = journal.getStats(state.tokens)
        val entries = journal.buildJournal(state.tokens)

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
                text = "${sdf.format(Date(entry.ts))}  ·  ${entry.mode}"
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

    private fun exportCsv() {
        lifecycleScope.launch {
            val state  = vm.ui.value
            val intent = journal.exportCsv(state.tokens)
            if (intent != null) {
                startActivity(android.content.Intent.createChooser(intent, "Export Trade Journal"))
            } else {
                Toast.makeText(this@JournalActivity, "No trades to export yet", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

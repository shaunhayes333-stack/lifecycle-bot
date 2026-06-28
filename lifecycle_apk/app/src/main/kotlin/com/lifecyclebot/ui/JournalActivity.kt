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
import com.lifecyclebot.data.Trade
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
    private val PAGE_SIZE = 30  // V5.9.1057: was 100 — 300 addView calls/poll caused 15s UI freeze
    private var currentPage = 1          // number of pages currently visible (grows on "Load More")
    private var lastRenderedTradeCount: Int = -1  // V5.9.1057: skip rebuild if count unchanged
    private var cachedSellEntries = listOf<com.lifecyclebot.engine.TradeJournal.JournalEntry>()
    @Volatile private var journalRefreshInFlight: Boolean = false
    private var lastRenderedStoreCount: Int = -1
    private var lastRenderedFilter: String? = "__INIT__"

    private val white = 0xFFFFFFFF.toInt()
    private val muted = 0xFF6B7280.toInt()
    private val green = 0xFF10B981.toInt()
    private val red = 0xFFEF4444.toInt()
    private val amber = 0xFFF59E0B.toInt()
    private val buyBlue = 0xFF60A5FA.toInt()
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

    // V5.0.3921 — DELETED the synthetic-Trade revalidator. The block below
    // built a synthetic Trade missing entryCostSol + entryPriceSnapshot,
    // which made TradeHistoryStore.isValidAccountingTrade() reject EVERY
    // sell at the "entryCostSol <= 0.0 → return false" gate — the literal
    // root cause of the blank-journal UI bug the operator kept reporting.
    // allEntries is ALREADY validated upstream at line 461
    // (.filter { TradeHistoryStore.isValidAccountingTrade(it) }) AND the
    // memory snapshot comes from getRecentValidTrades() which only returns
    // already-valid rows. A second client-side revalidation was always
    // dead weight; now it's gone.
    private fun isValidJournalAccounting(e: com.lifecyclebot.engine.TradeJournal.JournalEntry): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_journal)
        supportActionBar?.hide()

        // V5.9.1059 — TradeHistoryStore.init on IO thread (was sync on main in ≤1056,
        // removed entirely in 1057 causing blank journal). AATEApp normally calls it,
        // but if this Journal opens before BotService starts (bot stopped, app freshly
        // reopened), the in-memory list may be empty even if SQLite has 1000 trades.
        // Fire it asynchronously so main thread never blocks but the first poll picks
        // up populated data. fail-open: any exception is swallowed.
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try { com.lifecyclebot.engine.TradeHistoryStore.init(applicationContext) } catch (_: Throwable) {}
        }

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
                    lastRenderedTradeCount = -1  // V5.9.1057: force re-render on filter change
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
            var pollCount = 0
            while (isActive) {
                try {
                    // V5.9.330: Fetch data on IO thread, render on main.
                    // journal.buildJournal() hits SharedPreferences/TradeHistoryStore —
                    // doing this on the main thread causes ANR when trade count is large.
                    val storeSize = try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.lifecyclebot.engine.TradeHistoryStore.getTotalTradeCount()
                        }
                    } catch (_: Throwable) { -1 }
                    if (storeSize == lastRenderedStoreCount && currentModeFilter == lastRenderedFilter && lastRenderedTradeCount >= 0) {
                        delay(10_000)
                        continue
                    }
                    val tokens = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        getTokensSnapshot()
                    }
                    // buildJournal renders on the main thread (required for View ops)
                    refreshTradesWithTokens(tokens, storeSize)
                    // V5.9.658 — operator triage: user reports Journal screen
                    // shows 0 trades but bot is clearly trading (875 24h
                    // trades, 15 open positions). The previous catch
                    // swallowed every exception silently — Journal could be
                    // crashing on every poll and the operator would just see
                    // the pristine XML layout state forever. Emit a
                    // structured trace per poll so the operator can grep
                    // "JOURNAL_POLL" and confirm whether the activity is
                    // even refreshing, plus the size of the trade store at
                    // the time of the poll.
                    com.lifecyclebot.engine.ErrorLogger.info(
                        "JournalActivity",
                        "📓 JOURNAL_POLL #${++pollCount} ok | tokens=${tokens.size} | storeTrades=$storeSize | filter=${currentModeFilter ?: "ALL"}",
                    )
                } catch (e: Exception) {
                    // V5.9.658 — was: catch (_: Exception) {} (silent).
                    // If buildJournal/Render throws, log it with the class
                    // and message so the bug is diagnosable instead of
                    // hiding behind the empty XML layout state forever.
                    com.lifecyclebot.engine.ErrorLogger.error(
                        "JournalActivity",
                        "📓 JOURNAL_POLL EXCEPTION cls=${e.javaClass.simpleName} msg=${e.message}",
                        e,
                    )
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
                // V5.9.1049 ANR FIX: lifecycleScope.launch defaults to Main —
                // export* methods walk SQLite, format thousands of CSV rows
                // and write to disk. Run that on IO; only the resulting
                // share Intent (cheap) is handed back to Main.
                Toast.makeText(this, "Preparing export…", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
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
        runOnUiThread {
            if (intent != null) {
                startActivity(Intent.createChooser(intent, "Export Paper Trades CSV"))
            } else {
                Toast.makeText(this, "No paper trades to export", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun exportLiveCsv(tokens: Map<String, TokenState>) {
        val intent = journal.exportLiveCsv(tokens)
        runOnUiThread {
            if (intent != null) {
                startActivity(Intent.createChooser(intent, "Export Live Trades CSV"))
            } else {
                Toast.makeText(this, "No live trades to export", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun exportCsv(tokens: Map<String, TokenState>) {
        val intent = journal.exportCsv(tokens)
        runOnUiThread {
            if (intent != null) {
                startActivity(Intent.createChooser(intent, "Export CSV"))
            } else {
                Toast.makeText(this, "No trades to export yet", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun exportPdf(tokens: Map<String, TokenState>) {
        val intent = journal.exportPdf(tokens)
        runOnUiThread {
            if (intent != null) {
                startActivity(Intent.createChooser(intent, "Export PDF Tax Report"))
            } else {
                Toast.makeText(this, "No trades to export yet", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun exportIrs8949(tokens: Map<String, TokenState>) {
        val intent = journal.exportIrs8949(tokens)
        runOnUiThread {
            if (intent != null) {
                startActivity(Intent.createChooser(intent, "Export IRS Form 8949"))
            } else {
                Toast.makeText(this, "No trades to export yet", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun exportAll(tokens: Map<String, TokenState>) {
        val exports = journal.exportAll(tokens)
        runOnUiThread {
            if (exports.isEmpty()) {
                Toast.makeText(this, "No trades to export yet", Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }

            Toast.makeText(this, "Exported ${exports.size} formats!", Toast.LENGTH_SHORT).show()

            exports.forEach { (name, intent) ->
                startActivity(Intent.createChooser(intent, "Share $name"))
            }
        }
    }

    private fun getTokensSnapshot(): Map<String, TokenState> {
        // V5.9.1062 — safe null-check; status.tokens may be null if bot never started
        return try {
            synchronized(BotService.status.tokens) {
                BotService.status.tokens.toMap()
            }
        } catch (_: Throwable) { emptyMap() }
    }

    private fun refreshTrades() {
        try {
            buildJournal(getTokensSnapshot())
        } catch (e: Exception) {
            // V5.9.658 — was silent. Log loudly so we know whether the
            // pristine-XML-layout state is from buildJournal crashing or
            // from the polling loop never firing.
            com.lifecyclebot.engine.ErrorLogger.error(
                "JournalActivity",
                "📓 refreshTrades EXCEPTION cls=${e.javaClass.simpleName} msg=${e.message}",
                e,
            )
            showEmptyJournal()
        }
    }

    /** V5.9.330: Called from IO-prefetched polling path with pre-fetched tokens. */
    private fun refreshTradesWithTokens(tokens: Map<String, TokenState>, knownStoreCount: Int = -1) {
        try {
            buildJournal(tokens, knownStoreCount)
        } catch (e: Exception) {
            // V5.9.658 — see refreshTrades comment.
            com.lifecyclebot.engine.ErrorLogger.error(
                "JournalActivity",
                "📓 refreshTradesWithTokens EXCEPTION cls=${e.javaClass.simpleName} msg=${e.message}",
                e,
            )
            showEmptyJournal()
        }
    }

    private fun buildJournal(tokens: Map<String, TokenState>, knownStoreCount: Int = -1) {
        // V5.0.3869 — UI must not copy the full in-memory journal on refresh.
        // instead of going through TradeJournal.buildJournal(tokens).
        //
        // Root cause of blank journal: TradeJournal.buildJournal(tokens) had two
        // data sources: (1) in-memory token.trades (empty when bot is stopped because
        // status.tokens is cleared on stopBot) and (2) TradeHistoryStore bounded snapshots.
        // When the bot is stopped and the process was freshly started, the in-memory
        // token map is empty AND TradeHistoryStore may not have loaded from SQLite yet
        // (init called async in fix 1). The TradeJournal path also depended on the
        // tokens snapshot for symbol lookup, adding another failure point.
        //
        // New path: read getAllTrades() directly on IO thread, map Trade→JournalEntry
        // inline (same field mapping TradeJournal.buildJournal uses), sort descending.
        // This works whether bot is running, stopped, or never started.
        if (journalRefreshInFlight) return
        journalRefreshInFlight = true
        lifecycleScope.launch {
            try {
                val allEntries = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    // V5.9.1079 — In-memory list is the source of truth while the
                    // bot is running. recordTrade() populates it synchronously,
                    // but insertTradeAsync() flushes to SQLite via the IO
                    // HandlerThread — there is a wall-clock window where 198
                    // TRADEJRNL_REC counter increments have happened but the
                    // SQLite rows have not yet been committed. Reading from
                    // disk first in that window returns 0 even though the
                    // trades are live in memory (the V5.9.1078 snapshot symptom).
                    // Read in-memory first; fall back to SQLite only when memory
                    // is empty (bot stopped / fresh open before init() finished).
                    val rawTrades = run {
                        // V5.0.4497 — lifecycle journal visibility. Trade Journal must
                        // show BUY + SELL + PARTIAL rows. getRecentValidTrades() is a
                        // close-row helper, so using it here hid all BUY entries while
                        // the bot was running. Read the full valid in-memory ledger and
                        // fall back to SQLite only when memory is empty.
                        val mem = com.lifecyclebot.engine.TradeHistoryStore.getAllValidTradesSnapshot(5_000)
                        if (mem.isNotEmpty()) mem
                        else com.lifecyclebot.engine.TradeHistoryStore.getAllTradesFromDb()
                    }
                    // Also merge any in-memory token trades not yet persisted (running bot)
                    val tokenTrades = mutableListOf<com.lifecyclebot.data.Trade>()
                    try {
                        val seenKeys = rawTrades.map { "${it.ts}_${it.mint}_${it.side}" }.toHashSet()
                        tokens.values.forEach { ts ->
                            ts.trades.forEach { t ->
                                val k = "${t.ts}_${t.mint}_${t.side}"
                                if (k !in seenKeys) tokenTrades.add(t)
                            }
                        }
                    } catch (_: Throwable) {}
                    (rawTrades + tokenTrades)
                        .filter { com.lifecyclebot.engine.TradeHistoryStore.isValidAccountingTrade(it) }
                        .sortedByDescending { it.ts }
                        .map { t ->
                            com.lifecyclebot.engine.TradeJournal.JournalEntry(
                                ts              = t.ts,
                                symbol          = tokens.values.find { it.mint == t.mint }?.symbol
                                                    ?: t.mint.take(8),
                                mint            = t.mint,
                                side            = t.side,
                                entryPrice      = t.entryPriceSnapshot.takeIf { it > 0.0 } ?: if (!isSellLike(t.side)) t.price else 0.0,
                                exitPrice       = if (isSellLike(t.side)) t.price else 0.0,
                                solAmount       = t.sol,
                                pnlSol          = t.pnlSol,
                                pnlPct          = t.pnlPct,
                                reason          = t.reason,
                                mode            = t.mode,
                                score           = t.score,
                                durationMins    = 0.0,
                                phase           = "",
                                tradingMode     = t.tradingMode,
                                tradingModeEmoji = t.tradingModeEmoji,
                                feeSol          = t.feeSol,
                                netPnlSol       = t.netPnlSol,
                                proofState      = t.proofState,
                                positionId      = t.positionId,
                                entryTsMs       = t.entryTsMs,
                                entryMcapUsd    = t.entryMcapUsd,
                                entryQtyToken   = t.entryQtyToken,
                                entryCostSol    = t.entryCostSol,
                                entryDecimals   = t.entryDecimals,
                                soldQtyToken    = t.soldQtyToken,
                                remainingQtyToken = t.remainingQtyToken,
                                entryPriceSource = t.entryPriceSource,
                                entryPoolAddress = t.entryPoolAddress,
                            )
                        }
                }
                val validEntries = allEntries.filter { isValidJournalAccounting(it) }
                val filtered = when (currentModeFilter) {
                    "live"  -> validEntries.filter { it.mode.equals("live",  ignoreCase = true) }
                    "paper" -> validEntries.filter { it.mode.equals("paper", ignoreCase = true) }
                    else    -> validEntries
                }
                // V5.0.4497 — re-render on lifecycle row count, not sell-only count.
                // BUY rows arriving between sells must show up immediately.
                val lifecycleRowCount = filtered.size
                if (lifecycleRowCount == lastRenderedTradeCount && lastRenderedTradeCount >= 0) return@launch
                val stats = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    journal.getStatsFiltered(filtered)
                }
                if (isActive) {
                    lastRenderedTradeCount = lifecycleRowCount
                    lastRenderedStoreCount = if (knownStoreCount >= 0) knownStoreCount else try { com.lifecyclebot.engine.TradeHistoryStore.getTotalTradeCount() } catch (_: Throwable) { lifecycleRowCount }
                    lastRenderedFilter = currentModeFilter
                    renderJournalBody(filtered, stats)
                }
            } catch (e: Exception) {
                com.lifecyclebot.engine.ErrorLogger.error(
                    "JournalActivity",
                    "📓 buildJournal EXCEPTION cls=${e.javaClass.simpleName} msg=${e.message}", e
                )
                if (isActive) showEmptyJournal()
            } finally {
                journalRefreshInFlight = false
            }
        }
    }

    private fun isPartialSell(side: String): Boolean = side.equals("PARTIAL_SELL", ignoreCase = true)
    private fun isSellLike(side: String): Boolean =
        side.equals("SELL", ignoreCase = true) || isPartialSell(side)

    private fun renderJournalBody(filtered: List<com.lifecyclebot.engine.TradeJournal.JournalEntry>, stats: com.lifecyclebot.engine.TradeJournal.JournalStats) {
        val entries = filtered
        val sellEntries = entries.filter { isSellLike(it.side) } // stats only; visible list is full lifecycle

        tvJournalPnl.text = currency.format(stats.totalPnlSol, showPlus = true)
        tvJournalPnl.setTextColor(if (stats.totalPnlSol >= 0.0) green else red)

        tvJournalWinRate.text = "${stats.winRate.toInt()}%  (${stats.totalWins}W/${stats.totalLosses}L)"
        tvJournalCount.text = entries.size.toString()
        tvJournalAvgWin.text = if (stats.totalWins > 0) {
            "%+.1f%%".format(stats.avgWinPct)
        } else {
            "0.0%"
        }

        llJournalTrades.removeAllViews()
        reattachTabBar()  // V5.9.264: keep the filter tabs visible across refreshes

        if (entries.isEmpty()) {
            showEmptyTradeList()
            return
        }

        // V5.9.330 OOM FIX: Only render up to (PAGE_SIZE * currentPage) most-recent
        // trades. "Load More" button below list bumps currentPage by 1.
        // Building 5000+ Views on the main thread every 2s caused OOM crashes.
        cachedSellEntries = entries
        // V5.9.701 — takeLast was showing OLDEST trades (tail of descending list).
        // V5.0.4497 — page lifecycle rows, not sell-only rows.
        val pagedEntries = entries.take(PAGE_SIZE * currentPage)

        pagedEntries.forEach { entry ->
            val isBuyRow = entry.side.equals("BUY", ignoreCase = true)
            val outcome = if (isBuyRow) TradeOutcome.SCRATCH else classifyOutcome(entry.pnlPct)
            val pnlColor = when {
                isBuyRow -> buyBlue
                outcome == TradeOutcome.WIN -> green
                outcome == TradeOutcome.LOSS -> red
                else -> amber
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
                val reasonText = if (isBuyRow) "BUY_ENTRY" else entry.reason.take(22)
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

            // V5.0.3806 — compact linked-lifecycle diagnostics. Keep this to
            // two tiny TextViews so the Journal does not recreate the old ANR-heavy
            // wall of text while still showing entry/exit basis and partial qty.
            info.addView(TextView(this).apply {
                val posTail = entry.positionId.takeLast(10).ifBlank { "unlinked" }
                val proof = entry.proofState.ifBlank { if (entry.mode.equals("paper", true)) "PAPER_SIM" else "LIVE_PROOF?" }
                val rowKind = when {
                    isBuyRow -> "buy"
                    isPartialSell(entry.side) -> "partial"
                    else -> "terminal"
                }
                text = "id=$posTail · $rowKind · proof=$proof · e=${"%.8f".format(entry.entryPrice)} x=${"%.8f".format(entry.exitPrice)}"
                textSize = 9f
                setTextColor(muted)
                typeface = Typeface.MONOSPACE
            })
            if (entry.soldQtyToken > 0.0 || entry.remainingQtyToken > 0.0 || entry.entryMcapUsd > 0.0) {
                info.addView(TextView(this).apply {
                    val mcapText = if (entry.entryMcapUsd > 0.0) "mcap=${'$'}${"%.0f".format(entry.entryMcapUsd)}" else "mcap=n/a"
                    text = "qty sold=${"%.3f".format(entry.soldQtyToken)} rem=${"%.3f".format(entry.remainingQtyToken)} · $mcapText"
                    textSize = 9f
                    setTextColor(muted)
                    typeface = Typeface.MONOSPACE
                })
            }

            if (!isBuyRow && outcome == TradeOutcome.SCRATCH) {
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
                text = if (isBuyRow) "BUY" else "%+.1f%%".format(entry.pnlPct)
                textSize = 15f
                setTextColor(pnlColor)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END
            })

            right.addView(TextView(this).apply {
                text = if (isBuyRow) "size ${"%.4f".format(entry.solAmount)} SOL" else currency.format(entry.pnlSol, showPlus = true)
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
                text = "⬆️  Load ${minOf(PAGE_SIZE, cachedSellEntries.size - totalShowing)} older journal rows  (${cachedSellEntries.size - totalShowing} remaining)"
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
                    lastRenderedTradeCount = -1
                    refreshTrades()
                }
            }
            llJournalTrades.addView(loadMoreBtn)
        } else if (cachedSellEntries.size > PAGE_SIZE) {
            // All trades loaded — show count label
            llJournalTrades.addView(TextView(this).apply {
                text = "— all ${cachedSellEntries.size} journal rows loaded —"
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
                lastRenderedTradeCount = -1
                lastRenderedStoreCount = -1
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
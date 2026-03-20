package com.lifecyclebot.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.lifecyclebot.R
import com.lifecyclebot.data.*
import com.lifecyclebot.engine.SafetyTier
import com.lifecyclebot.engine.WalletConnectionState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var vm: BotViewModel
    private lateinit var currency: com.lifecyclebot.engine.CurrencyManager

    // top bar
    private lateinit var tvNetworkLabel: TextView
    private lateinit var btnWalletTop: View
    private lateinit var tvWalletDot: View
    private lateinit var tvWalletShort: TextView

    // hero balance
    private lateinit var tvBalanceLarge: TextView
    private lateinit var tvBalanceUsd: TextView
    private lateinit var tvPnlChange: TextView
    private lateinit var tvPnlChangePct: TextView

    // bot status card
    private lateinit var tvTokenName: TextView
    private lateinit var tvTokenPhase: TextView
    private lateinit var tvSignalChip: TextView
    private lateinit var tvPrice: TextView
    private lateinit var tvMcap: TextView
    private lateinit var tvPosition: TextView
    private lateinit var pbEntry: ProgressBar
    private lateinit var pbExit: ProgressBar
    private lateinit var pbVol: ProgressBar
    private lateinit var pbPress: ProgressBar
    private lateinit var tvEntryVal: TextView
    private lateinit var tvExitVal: TextView
    private lateinit var tvVolVal: TextView
    private lateinit var tvPressVal: TextView

    // chart
    private lateinit var priceChart: LineChart
    private lateinit var tvSafetyChip: TextView

    // safety
    private lateinit var tvSafety: TextView
    private lateinit var tvRugcheck: TextView

    // trades
    private lateinit var llTradeList: LinearLayout
    private lateinit var tvTradeCount: TextView
    private lateinit var tvNoTrades: TextView
    // bonding curve + whale
    private lateinit var pbBondingCurve: android.widget.ProgressBar
    private lateinit var tvCurveStage: android.widget.TextView
    private lateinit var pbWhale: android.widget.ProgressBar
    private lateinit var tvWhaleSummary: android.widget.TextView
    // nav buttons
    private lateinit var btnOpenJournal: android.widget.TextView
    private lateinit var btnOpenAlerts: android.widget.TextView
    private lateinit var cardOpenPositions: android.view.View
    private lateinit var llOpenPositions: LinearLayout
    private lateinit var tvTotalExposure: TextView
    private lateinit var tvTotalUnrealisedPnl: TextView

    // decision log
    private lateinit var cardLogScores: android.view.View
    private lateinit var tvLogToken: TextView
    private lateinit var tvLogPhase: TextView
    private lateinit var tvLogSignal: TextView
    private lateinit var tvLogEntry: TextView
    private lateinit var tvLogExit: TextView
    private lateinit var tvLogVol: TextView
    private lateinit var tvLogPress: TextView
    private lateinit var tvLogMom: TextView
    private lateinit var tvLogEmaFan: TextView
    private lateinit var tvLogFlags: TextView
    private lateinit var tvLogReason: TextView
    private lateinit var tvDecisionLog: TextView
    private lateinit var scrollLog: android.widget.ScrollView
    private lateinit var btnClearLog: TextView
    private val logLines = ArrayDeque<String>(200)

    // top-up settings
    private lateinit var switchTopUp: android.widget.Switch
    private lateinit var etTopUpMinGain: EditText
    private lateinit var etTopUpGainStep: EditText
    private lateinit var etTopUpMaxCount: EditText
    private lateinit var etTopUpMaxSol: EditText

    // watchlist
    private lateinit var llTokenList: LinearLayout
    private lateinit var etAddMint: EditText
    private lateinit var btnAddToken: Button

    // settings
    private lateinit var etActiveToken: EditText
    private lateinit var spMode: Spinner
    private lateinit var spAutoTrade: Spinner
    private lateinit var etStopLoss: EditText
    private lateinit var etExitScore: EditText
    private lateinit var tvAdvancedToggle: TextView
    private lateinit var layoutAdvanced: View
    private lateinit var etSmallBuy: EditText
    private lateinit var etLargeBuy: EditText
    private lateinit var etSlippage: EditText
    private lateinit var etPoll: EditText
    private lateinit var etRpc: EditText
    private lateinit var etTgBotToken: EditText
    private lateinit var etTgChatId: EditText
    private lateinit var etWatchlist: EditText
    private lateinit var etHeliusKey: EditText
    private lateinit var etBirdeyeKey: EditText
    private lateinit var etGroqKey: EditText
    private lateinit var btnSave: Button

    // bottom bar
    private lateinit var statusDot: View
    private lateinit var tvBotStatus: TextView
    private lateinit var tvMode: TextView
    private lateinit var tvAutoMode: android.widget.TextView
    private lateinit var btnToggle: Button

    // chart data
    private val chartEntries = mutableListOf<Entry>()
    private var chartIdx = 0f
    private var advancedExpanded = false
    private var settingsPopulated = false

    // colours
    private val purple  = 0xFF9945FF.toInt()
    private val green   = 0xFF10B981.toInt()
    private val red     = 0xFFEF4444.toInt()
    private val amber   = 0xFFF59E0B.toInt()
    private val muted   = 0xFF6B7280.toInt()
    private val white   = 0xFFFFFFFF.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()

        // Transparent status bar
        window.statusBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        vm       = ViewModelProvider(this)[BotViewModel::class.java]
        currency = try {
            com.lifecyclebot.engine.BotService.instance?.currencyManager
                ?: com.lifecyclebot.engine.CurrencyManager(applicationContext)
        } catch (_: Exception) {
            com.lifecyclebot.engine.CurrencyManager(applicationContext)
        }

        bindViews()
        setupChart()
        setupSettings()
        requestNotifPermission()
        checkBatteryOptimisation()

        lifecycleScope.launch {
            vm.ui.collect { state -> updateUi(state) }
        }
    }

    override fun onPause() {
        super.onPause()
        // Auto-save settings when app goes to background
        saveCurrentSettings()
    }

    override fun onStop() {
        super.onStop()
        // Auto-save settings when app is stopped
        saveCurrentSettings()
    }

    /** Save current settings from UI fields */
    private fun saveCurrentSettings() {
        try {
            val state = vm.ui.value
            val cfg = state.config.copy(
                heliusApiKey          = etHeliusKey.text.toString().trim(),
                birdeyeApiKey         = etBirdeyeKey.text.toString().trim(),
                groqApiKey            = etGroqKey.text.toString().trim(),
                telegramBotToken      = etTgBotToken.text.toString().trim(),
                telegramChatId        = etTgChatId.text.toString().trim(),
                watchlist             = etWatchlist.text.toString()
                                            .split(",")
                                            .map { it.trim() }
                                            .filter { it.isNotBlank() },
            )
            vm.saveConfig(cfg)
        } catch (_: Exception) {}
    }

    private fun checkBatteryOptimisation() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Battery Optimisation")
                .setMessage("LifecycleBot needs to be excluded from battery optimisation " +
                    "so trading continues in the background. Tap OK to open settings.")
                .setPositiveButton("OK") { dialog: android.content.DialogInterface, _: Int ->
                    startActivity(Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    ))
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    // ── bind ──────────────────────────────────────────────────────────

    private fun bindViews() {
        tvNetworkLabel  = findViewById(R.id.tvNetworkLabel)
        btnWalletTop    = findViewById(R.id.btnWalletTop)
        tvWalletDot     = findViewById(R.id.tvWalletDot)
        tvWalletShort   = findViewById(R.id.tvWalletShort)
        tvBalanceLarge  = findViewById(R.id.tvBalanceLarge)
        tvBalanceUsd    = findViewById(R.id.tvBalanceUsd)
        tvPnlChange     = findViewById(R.id.tvPnlChange)
        tvPnlChangePct  = findViewById(R.id.tvPnlChangePct)
        tvTokenName     = findViewById(R.id.tvTokenName)
        tvTokenPhase    = findViewById(R.id.tvTokenPhase)
        tvSignalChip    = findViewById(R.id.tvSignalChip)
        tvPrice         = findViewById(R.id.tvPrice)
        tvMcap          = findViewById(R.id.tvMcap)
        tvPosition      = findViewById(R.id.tvPosition)
        pbEntry         = findViewById(R.id.pbEntry)
        pbExit          = findViewById(R.id.pbExit)
        pbVol           = findViewById(R.id.pbVol)
        pbPress         = findViewById(R.id.pbPress)
        tvEntryVal      = findViewById(R.id.tvEntryVal)
        tvExitVal       = findViewById(R.id.tvExitVal)
        tvVolVal        = findViewById(R.id.tvVolVal)
        tvPressVal      = findViewById(R.id.tvPressVal)
        priceChart      = findViewById(R.id.priceChart)
        tvSafetyChip    = findViewById(R.id.tvSafetyChip)
        tvSafety        = findViewById(R.id.tvSafety)
        tvRugcheck      = findViewById(R.id.tvRugcheck)
        llTradeList     = findViewById(R.id.llTradeList)
        tvTradeCount    = findViewById(R.id.tvTradeCount)
        tvNoTrades         = findViewById(R.id.tvNoTrades)
        pbBondingCurve     = try { findViewById(R.id.pbBondingCurve) } catch (_:Exception) { android.widget.ProgressBar(this) }
        tvCurveStage       = try { findViewById(R.id.tvCurveStage) } catch (_:Exception) { android.widget.TextView(this) }
        pbWhale            = try { findViewById(R.id.pbWhale) } catch (_:Exception) { android.widget.ProgressBar(this) }
        tvWhaleSummary     = try { findViewById(R.id.tvWhaleSummary) } catch (_:Exception) { android.widget.TextView(this) }
        btnOpenJournal     = try { findViewById(R.id.btnOpenJournal) } catch (_:Exception) { android.widget.TextView(this) }
        btnOpenAlerts      = try { findViewById(R.id.btnOpenAlerts) } catch (_:Exception) { android.widget.TextView(this) }
        btnOpenJournal.setOnClickListener { startActivity(android.content.Intent(this, JournalActivity::class.java)) }
        try {
            findViewById<android.widget.TextView>(R.id.btnOpenBacktest)
                ?.setOnClickListener { startActivity(android.content.Intent(this, BacktestActivity::class.java)) }
        } catch (_: Exception) {}

        // Scanner source toggles — save to config on change
        listOf(
            R.id.switchFullScan to "fullMarketScanEnabled",
            R.id.cbScanGraduates to "scanPumpGraduates",
            R.id.cbScanDexTrending to "scanDexTrending",
            R.id.cbScanGainers to "scanDexGainers",
            R.id.cbScanBoosted to "scanDexBoosted",
            R.id.cbScanRaydium to "scanRaydiumNew",
            R.id.cbScanNarrative to "narrativeScanEnabled",
        ).forEach { (viewId, _) ->
            try {
                val v = findViewById<android.widget.CompoundButton>(viewId)
                v?.setOnCheckedChangeListener { _: android.widget.CompoundButton, _: Boolean -> saveScannerSettings() }
            } catch (_: Exception) {}
        }
        btnOpenAlerts.setOnClickListener  { startActivity(android.content.Intent(this, AlertsActivity::class.java)) }
        cardOpenPositions  = findViewById(R.id.cardOpenPositions)
        llOpenPositions    = findViewById(R.id.llOpenPositions)
        tvTotalExposure    = try { findViewById(R.id.tvTotalExposure) } catch (_: Exception) { TextView(this) }
        tvTotalUnrealisedPnl = try { findViewById(R.id.tvTotalUnrealisedPnl) } catch (_: Exception) { TextView(this) }
        llTokenList     = findViewById(R.id.llTokenList)
        etAddMint       = findViewById(R.id.etAddMint)
        btnAddToken     = findViewById(R.id.btnAddToken)
        etActiveToken   = findViewById(R.id.etActiveToken)
        spMode          = findViewById(R.id.spMode)
        spAutoTrade     = findViewById(R.id.spAutoTrade)
        etStopLoss      = findViewById(R.id.etStopLoss)
        etExitScore     = findViewById(R.id.etExitScore)
        tvAdvancedToggle = findViewById(R.id.tvAdvancedToggle)
        layoutAdvanced  = findViewById(R.id.layoutAdvanced)
        etSmallBuy      = findViewById(R.id.etSmallBuy)
        etLargeBuy      = findViewById(R.id.etLargeBuy)
        etSlippage      = findViewById(R.id.etSlippage)
        etPoll          = findViewById(R.id.etPoll)
        etRpc           = findViewById(R.id.etRpc)
        etTgBotToken    = findViewById(R.id.etTgBotToken)
        etTgChatId          = findViewById(R.id.etTgChatId)
        etWatchlist     = findViewById(R.id.etWatchlist)
        etHeliusKey     = try { findViewById(R.id.etHeliusKey) } catch (_: Exception) { EditText(this) }
        etBirdeyeKey    = try { findViewById(R.id.etBirdeyeKey) } catch (_: Exception) { EditText(this) }
        etGroqKey       = try { findViewById(R.id.etGroqKey) } catch (_: Exception) { EditText(this) }
        btnSave         = findViewById(R.id.btnSave)

        // API key help links - open signup pages
        setupApiKeyHelpLinks()

        // Clear settings button
        setupClearSettingsButton()

        // Quick action buttons
        setupQuickActionButtons()

        // decision log
        cardLogScores = try { findViewById(R.id.cardLogScores) } catch (_: Exception) { android.view.View(this) }
        tvLogToken    = try { findViewById(R.id.tvLogToken)  } catch (_: Exception) { TextView(this) }
        tvLogPhase    = try { findViewById(R.id.tvLogPhase)  } catch (_: Exception) { TextView(this) }
        tvLogSignal   = try { findViewById(R.id.tvLogSignal) } catch (_: Exception) { TextView(this) }
        tvLogEntry    = try { findViewById(R.id.tvLogEntry)  } catch (_: Exception) { TextView(this) }
        tvLogExit     = try { findViewById(R.id.tvLogExit)   } catch (_: Exception) { TextView(this) }
        tvLogVol      = try { findViewById(R.id.tvLogVol)    } catch (_: Exception) { TextView(this) }
        tvLogPress    = try { findViewById(R.id.tvLogPress)  } catch (_: Exception) { TextView(this) }
        tvLogMom      = try { findViewById(R.id.tvLogMom)    } catch (_: Exception) { TextView(this) }
        tvLogEmaFan   = try { findViewById(R.id.tvLogEmaFan) } catch (_: Exception) { TextView(this) }
        tvLogFlags    = try { findViewById(R.id.tvLogFlags)  } catch (_: Exception) { TextView(this) }
        tvLogReason   = try { findViewById(R.id.tvLogReason) } catch (_: Exception) { TextView(this) }
        tvDecisionLog = try { findViewById(R.id.tvDecisionLog) } catch (_: Exception) { TextView(this) }
        scrollLog     = try { findViewById(R.id.scrollLog)   } catch (_: Exception) { android.widget.ScrollView(this) }
        btnClearLog   = try { findViewById(R.id.btnClearLog) } catch (_: Exception) { TextView(this) }
        btnClearLog.setOnClickListener { clearDecisionLog() }

        // top-up
        switchTopUp    = try { findViewById(R.id.switchTopUp)    } catch (_: Exception) { android.widget.Switch(this) }
        etTopUpMinGain = try { findViewById(R.id.etTopUpMinGain) } catch (_: Exception) { EditText(this) }
        etTopUpGainStep= try { findViewById(R.id.etTopUpGainStep)} catch (_: Exception) { EditText(this) }
        etTopUpMaxCount= try { findViewById(R.id.etTopUpMaxCount)} catch (_: Exception) { EditText(this) }
        etTopUpMaxSol  = try { findViewById(R.id.etTopUpMaxSol)  } catch (_: Exception) { EditText(this) }

        statusDot       = findViewById(R.id.statusDot)
        tvBotStatus     = findViewById(R.id.tvBotStatus)
        tvMode          = findViewById(R.id.tvMode)
        tvAutoMode      = try { findViewById(R.id.tvAutoMode) } catch (_:Exception) { android.widget.TextView(this) }
        btnToggle       = findViewById(R.id.btnToggle)

        btnToggle.setOnClickListener { vm.toggleBot() }
        btnWalletTop.setOnClickListener {
            startActivity(Intent(this, WalletActivity::class.java))
        }
        btnAddToken.setOnClickListener { addToken() }
        btnSave.setOnClickListener { saveSettings() }
        tvAdvancedToggle.setOnClickListener {
            advancedExpanded = !advancedExpanded
            layoutAdvanced.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
            tvAdvancedToggle.text = if (advancedExpanded) "Advanced settings ›" else "Advanced settings ›"
        }
    }

    // ── chart ─────────────────────────────────────────────────────────

    private fun setupChart() {
        priceChart.apply {
            setBackgroundColor(Color.TRANSPARENT)
            setDrawGridBackground(false)
            description.isEnabled = false
            legend.isEnabled      = false
            setTouchEnabled(false)
            xAxis.apply {
                isEnabled     = false
                position      = XAxis.XAxisPosition.BOTTOM
            }
            axisLeft.apply {
                setDrawGridLines(true)
                gridColor    = 0xFF1F2937.toInt()
                textColor    = muted
                textSize     = 9f
                axisLineColor = Color.TRANSPARENT
                setDrawAxisLine(false)
            }
            axisRight.isEnabled = false
        }
    }

    private fun appendChart(price: Double) {
        chartEntries.add(Entry(chartIdx++, price.toFloat()))
        if (chartEntries.size > 120) chartEntries.removeAt(0)

        val ds = LineDataSet(chartEntries, "").apply {
            color           = purple
            lineWidth       = 1.8f
            setDrawCircles(false)
            setDrawValues(false)
            setDrawFilled(true)
            fillColor       = purple
            fillAlpha       = 30
            mode            = LineDataSet.Mode.CUBIC_BEZIER
        }
        priceChart.data = LineData(ds)
        priceChart.invalidate()
    }

    // ── update UI ─────────────────────────────────────────────────────

    private fun updateUi(state: UiState) {
        val ts  = state.activeToken
        val cfg = state.config
        val ws  = state.walletState

        // ── wallet top bar ────────────────────────────────────────────
        when (ws.connectionState) {
            WalletConnectionState.CONNECTED -> {
                tvWalletDot.background   = ContextCompat.getDrawable(this, R.drawable.dot_green)
                tvWalletShort.text       = ws.shortKey
                tvWalletShort.setTextColor(white)
            }
            WalletConnectionState.ERROR -> {
                tvWalletDot.background   = ContextCompat.getDrawable(this, R.drawable.dot_red)
                tvWalletShort.text       = "Error"
                tvWalletShort.setTextColor(red)
            }
            else -> {
                tvWalletDot.background   = ContextCompat.getDrawable(this, R.drawable.dot_bg)
                tvWalletShort.text       = "Connect wallet"
                tvWalletShort.setTextColor(muted)
            }
        }

        // ── hero balance ──────────────────────────────────────────────
        if (ws.isConnected && ws.solBalance > 0) {
            tvBalanceLarge.text = currency.format(ws.solBalance)
            // Secondary: always show SOL if displaying another currency
            tvBalanceUsd.text = if (currency.selectedCurrency != "SOL")
                "◎ %.4f".format(ws.solBalance) else ""
        } else {
            tvBalanceLarge.text = "—"
            tvBalanceUsd.text   = ""
        }

        val pnl    = ws.totalPnlSol
        val pnlPct = ws.totalPnlPct
        if (ws.totalTrades > 0) {
            tvPnlChange.text = currency.format(pnl, showPlus = true)
            tvPnlChange.setTextColor(if (pnl >= 0) green else red)
            tvPnlChangePct.text = "%+.1f%%  •  ${ws.winRate}% wins".format(pnlPct)
        } else {
            tvPnlChange.text    = ""
            tvPnlChangePct.text = ""
        }

        // ── Treasury + ScalingMode tier ──────────────────────────────
        try {
            val trs     = ws.treasurySol
            val trsUsd  = ws.treasuryUsd
            val tier    = ws.highestMilestoneName
            val nextUsd = ws.nextMilestoneUsd
            findViewById<android.widget.TextView?>(R.id.tvTreasuryTier)?.text =
                if (trs > 0.001) "Tier: $tier" else "Tier: None"
            findViewById<android.widget.TextView?>(R.id.tvTreasuryAmount)?.text =
                if (trs > 0.001) "${"%.3f".format(trs)} SOL  ($${"%.0f".format(trsUsd)})" else "—"
            findViewById<android.widget.TextView?>(R.id.tvTreasuryNext)?.text = when {
                nextUsd > 0 -> "Next: $${"%,.0f".format(nextUsd)}"
                trs > 0     -> "Max tier reached"
                else        -> "First milestone: $500"
            }
        } catch (_: Exception) {}

        // ── bot status card ───────────────────────────────────────────
        tvTokenName.text  = ts?.symbol?.ifBlank { "No token selected" } ?: "No token selected"
        val ageMins = if (ts != null && ts.history.isNotEmpty()) {
            (System.currentTimeMillis() - ts.history.first().ts) / 60_000.0
        } else -1.0
        val modeLabel = when {
            ageMins < 0  -> ""
            ageMins <= 15 -> " · SNIPE"
            else         -> " · RANGE"
        }
        tvTokenPhase.text = "${ts?.phase ?: "—"}$modeLabel"

        val sig = ts?.signal ?: "WAIT"
        tvSignalChip.text = sig
        val (sigBg, sigColor) = when {
            sig == "BUY" ->
                R.drawable.chip_green_bg to green
            sig in listOf("SELL", "EXIT") ->
                R.drawable.chip_red_bg to red
            sig in listOf("WAIT_BUILDING", "WAIT_PULLBACK", "WAIT_CONFIRM", "WAIT_COOLING") ->
                R.drawable.chip_neutral_bg to amber
            else ->
                R.drawable.chip_neutral_bg to muted
        }
        tvSignalChip.background = ContextCompat.getDrawable(this, sigBg)
        tvSignalChip.setTextColor(sigColor)

        tvPrice.text    = if (ts?.lastPrice != null && ts.lastPrice > 0) currency.formatPrice(ts.lastPrice) else "—"
        tvMcap.text     = ts?.lastMcap?.fmtMcap()  ?: "—"
        tvPosition.text = when {
            ts?.position?.isOpen == true -> "● OPEN"
            else                         -> "FLAT"
        }
        tvPosition.setTextColor(if (ts?.position?.isOpen == true) green else muted)

        pbEntry.progress  = ts?.entryScore?.toInt() ?: 0
        pbExit.progress   = ts?.exitScore?.toInt()  ?: 0
        pbVol.progress    = ts?.meta?.volScore?.toInt()   ?: 0
        pbPress.progress  = ts?.meta?.pressScore?.toInt() ?: 0
        tvEntryVal.text   = "${ts?.entryScore?.toInt() ?: 0}"
        tvExitVal.text    = "${ts?.exitScore?.toInt()  ?: 0}"
        tvVolVal.text     = "${ts?.meta?.volScore?.toInt()   ?: 0}"
        tvPressVal.text   = "${ts?.meta?.pressScore?.toInt() ?: 0}"

        // ── chart ─────────────────────────────────────────────────────
        if (ts?.lastPrice != null && ts.lastPrice > 0) appendChart(ts.lastPrice)

        // ── open positions panel ─────────────────────────────────
        val openPos = state.openPositions
        cardOpenPositions.visibility = if (openPos.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        if (openPos.isNotEmpty()) {
            tvTotalExposure.text = "%.3f◎ at risk".format(state.totalExposureSol)
            val upnl = state.totalUnrealisedPnlSol
            tvTotalUnrealisedPnl.text = "%+.4f◎".format(upnl)
            tvTotalUnrealisedPnl.setTextColor(if (upnl >= 0) green else red)
            renderOpenPositions(openPos)
        }

        // ── safety ────────────────────────────────────────────────────
        val safety = ts?.safety
        if (safety != null && safety.checkedAt > 0) {
            tvSafety.text = safety.summary
            tvSafety.setTextColor(when (safety.tier) {
                SafetyTier.HARD_BLOCK -> red
                SafetyTier.CAUTION    -> amber
                else                  -> green
            })
            val rc = safety.rugcheckScore
            tvRugcheck.text = if (rc >= 0) "RC $rc" else "RC —"
            tvRugcheck.setTextColor(when {
                rc < 0   -> muted
                rc < 70  -> red
                rc < 80  -> amber
                else     -> green
            })
            tvSafetyChip.text = safety.summary.take(30)
            tvSafetyChip.setTextColor(when (safety.tier) {
                SafetyTier.HARD_BLOCK -> red
                SafetyTier.CAUTION    -> amber
                else                  -> green
            })
        }

        // ── bonding curve ─────────────────────────────────────
        if (ts != null) {
            val curveState = com.lifecyclebot.engine.BondingCurveTracker.evaluate(ts)
            pbBondingCurve.progress = curveState.progressPct.toInt()
            tvCurveStage.text       = curveState.stageLabel

            // Show graduation mcap dynamically (moves with SOL price)
            val gradMcap = curveState.graduationMcapUsd
            val gradStr  = if (gradMcap > 0) " (grad ≈ \$${"%,.0f".format(gradMcap)})" else ""

            tvCurveStage.text = "${curveState.stageLabel}$gradStr"
            tvCurveStage.setTextColor(when (curveState.stage) {
                com.lifecyclebot.engine.BondingCurveTracker.CurveStage.GRADUATING -> green
                com.lifecyclebot.engine.BondingCurveTracker.CurveStage.PRE_GRAD   -> amber
                com.lifecyclebot.engine.BondingCurveTracker.CurveStage.GRADUATED  -> green
                else                                                               -> muted
            })
        } else {
            pbBondingCurve.progress = 0
            tvCurveStage.text       = "—"
        }

        // ── whale indicator ───────────────────────────────────────
        val whaleMeta = ts?.signal?.let { _ ->
            com.lifecyclebot.engine.WhaleDetector.evaluate(ts.mint, ts)
        }
        pbWhale.progress     = whaleMeta?.velocityScore?.toInt() ?: 0
        tvWhaleSummary.text  = whaleMeta?.summary?.ifBlank { "—" } ?: "—"
        tvWhaleSummary.setTextColor(when {
            whaleMeta?.hasWhaleActivity == true -> amber
            else                                -> muted
        })

        // ── trades ────────────────────────────────────────────────────
        val trades = ts?.trades ?: emptyList()
        tvTradeCount.text = if (trades.isNotEmpty()) "${trades.size} trades" else ""
        tvNoTrades.visibility = if (trades.isEmpty()) View.VISIBLE else View.GONE
        if (trades.isNotEmpty()) {
            renderTrades(trades)
        }

        // ── decision log ──────────────────────────────────────────────
        if (ts != null) updateDecisionLog(ts)

        // ── top-up status in bot status text ─────────────────────────
        // Show top-up count on active position
        if (ts?.position?.isOpen == true && ts.position.topUpCount > 0) {
            val gainPct = if (ts.position.entryPrice > 0)
                (ts.ref - ts.position.entryPrice) / ts.position.entryPrice * 100.0 else 0.0
            val topUpBadge = "🔺×${ts.position.topUpCount}  avg entry ${ts.position.entryPrice.fmtRef()}"
            tvBotStatus.text = "${tvBotStatus.text}  $topUpBadge"
        }

        // ── watchlist ─────────────────────────────────────────────────
        renderWatchlist(state)

        // ── bottom bar ────────────────────────────────────────────────
        val running = state.running
        val cb      = state.circuitBreaker

        // Determine effective bot state
        val isHalted  = cb.isHalted
        val isPaused  = cb.isPaused && running
        val isRunning = running && !isHalted && !isPaused

        btnToggle.text = when {
            isHalted -> "Halted — Tap to Reset"
            running  -> "Stop Bot"
            else     -> "Start Bot"
        }
        btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(when {
            isHalted -> 0xFFEF4444.toInt()
            running  -> 0xFF374151.toInt()
            else     -> purple
        })
        btnToggle.setTextColor(if (running || isHalted) white else white)

        // Override toggle click when halted
        if (isHalted) {
            btnToggle.setOnClickListener {
                // Clear halt and stop the bot
                try {
                    val svc = com.lifecyclebot.engine.BotService.instance
                    val f   = svc?.javaClass?.getDeclaredField("securityGuard")
                    f?.isAccessible = true
                    (f?.get(svc) as? com.lifecyclebot.engine.SecurityGuard)?.clearHalt()
                } catch (_: Exception) {}
                vm.stopBot()
            }
        } else {
            btnToggle.setOnClickListener { vm.toggleBot() }
        }

        statusDot.background = ContextCompat.getDrawable(this, when {
            isHalted -> R.drawable.dot_red
            isPaused -> R.drawable.dot_bg     // amber would be ideal but using muted
            isRunning -> R.drawable.dot_green
            else      -> R.drawable.dot_bg
        })

        tvBotStatus.text = when {
            isHalted  -> "🛑 ${cb.haltReason.take(40)}"
            isPaused  -> "⏸ Paused ${cb.pauseRemainingSecs}s  •  ${cb.consecutiveLosses} losses"
            running && ts?.signal in listOf("BUY","EXIT","SELL") ->
                "Signal: ${ts?.signal}  •  ${ts?.symbol ?: ""}"
            running   -> "Scanning  ${ts?.symbol ?: ""}  •  ${cb.consecutiveLosses} consec losses"
            else      -> "Bot stopped"
        }
        tvBotStatus.setTextColor(when {
            isHalted -> 0xFFEF4444.toInt()
            isPaused -> amber
            else     -> 0xFF9CA3AF.toInt()
        })

        // Daily loss display in mode badge
        val isPaper = cfg.paperMode
        tvMode.text = when {
            isPaper             -> "PAPER"
            cb.dailyLossSol > 0 -> "LIVE -${"%.3f".format(cb.dailyLossSol)}◎"
            else                -> "LIVE"
        }
        tvMode.setTextColor(if (isPaper) amber else red)

        // Auto-mode badge
        val mode = state.currentMode
        tvAutoMode.text = mode.label
        tvAutoMode.setTextColor(mode.colour)

        // Show blacklist count in status
        if (state.blacklistedCount > 0) {
            tvBotStatus.text = tvBotStatus.text.toString() + "  🚫${state.blacklistedCount}"
        }

        // Settings population (once)
        if (!settingsPopulated) {
            val c = vm.ui.value.config
            switchTopUp.isChecked      = c.topUpEnabled
            etTopUpMinGain.setText(c.topUpMinGainPct.toString())
            etTopUpGainStep.setText(c.topUpGainStepPct.toString())
            etTopUpMaxCount.setText(c.topUpMaxCount.toString())
            etTopUpMaxSol.setText(c.topUpMaxTotalSol.toString())
            populateSettings(cfg)
            settingsPopulated = true
        }
    }

    // ── trades ────────────────────────────────────────────────────────

    private fun renderOpenPositions(positions: List<TokenState>) {
        llOpenPositions.removeAllViews()
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        positions.forEach { ts ->
            val pos     = ts.position
            val ref     = ts.ref
            val gainPct = if (pos.entryPrice > 0 && ref > 0)
                (ref - pos.entryPrice) / pos.entryPrice * 100.0 else 0.0
            val gainCol = if (gainPct >= 0) green else red
            val pnlSol  = pos.costSol * gainPct / 100.0

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 10, 0, 10)
            }

            // Colour bar on left
            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(4, LinearLayout.LayoutParams.MATCH_PARENT).also {
                    it.marginEnd = 12
                }
                setBackgroundColor(gainCol)
            }
            row.addView(bar)

            // Token info
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = ts.symbol.ifBlank { ts.mint.take(8) }
                textSize = resources.getDimension(R.dimen.trade_row_text) / resources.displayMetrics.scaledDensity
                setTextColor(white)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            info.addView(TextView(this).apply {
                text = "Entry: ${pos.entryPrice.fmtPrice()}  ·  ${sdf.format(java.util.Date(pos.entryTime))}"
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
            })
            row.addView(info)

            // P&L
            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.END
            }
            right.addView(TextView(this).apply {
                text = "%+.1f%%".format(gainPct)
                textSize = resources.getDimension(R.dimen.token_name_size) / resources.displayMetrics.scaledDensity
                setTextColor(gainCol)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.END
            })
            right.addView(TextView(this).apply {
                text = "%+.4f◎".format(pnlSol)
                textSize = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
                setTextColor(gainCol)
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = android.view.Gravity.END
            })
            row.addView(right)

            val div = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1).also { it.topMargin = 10 }
                setBackgroundColor(0xFF1F2937.toInt())
            }
            llOpenPositions.addView(row)
            llOpenPositions.addView(div)
        }
    }

    private fun renderTrades(trades: List<Trade>) {
        llTradeList.removeAllViews()
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
        val tradeTextSp = resources.getDimension(R.dimen.trade_row_text) / resources.displayMetrics.scaledDensity
        val tradeSubSp  = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity

        trades.reversed().take(8).forEach { t ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 10, 0, 10)
            }

            // Side dot + label
            val sideView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = 12 }
            }
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(8, 8).also {
                    it.topMargin = 4
                    it.bottomMargin = 4
                }
                background = ContextCompat.getDrawable(
                    this@MainActivity,
                    if (t.side == "BUY") R.drawable.dot_green else R.drawable.dot_red
                )
            }
            sideView.addView(dot)
            row.addView(sideView)

            // Info
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val sideLabel = TextView(this).apply {
                text      = "${t.side}  ${t.reason.ifBlank { t.mode }}"
                textSize  = tradeTextSp
                setTextColor(if (t.side == "BUY") green else if (t.pnlSol > 0) green else red)
                typeface  = android.graphics.Typeface.DEFAULT_BOLD
            }
            val timeLabel = TextView(this).apply {
                text      = sdf.format(Date(t.ts))
                textSize  = tradeSubSp
                setTextColor(muted)
                typeface  = android.graphics.Typeface.MONOSPACE
            }
            info.addView(sideLabel)
            info.addView(timeLabel)
            row.addView(info)

            // Amount + P&L
            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity     = android.view.Gravity.END
            }
            val amtLabel = TextView(this).apply {
                text      = "%.4f◎".format(t.sol)
                textSize  = tradeTextSp
                setTextColor(white)
                typeface  = android.graphics.Typeface.MONOSPACE
                gravity   = android.view.Gravity.END
            }
            right.addView(amtLabel)
            if (t.side == "SELL" && t.pnlSol != 0.0) {
                val pnlLabel = TextView(this).apply {
                    text      = "%+.4f◎  %+.1f%%".format(t.pnlSol, t.pnlPct)
                    textSize  = tradeSubSp
                    setTextColor(if (t.pnlSol > 0) green else red)
                    typeface  = android.graphics.Typeface.MONOSPACE
                    gravity   = android.view.Gravity.END
                }
                right.addView(pnlLabel)
            }
            row.addView(right)

            // Divider
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).also { it.topMargin = 10 }
                setBackgroundColor(0xFF1F2937.toInt())
            }

            llTradeList.addView(row)
            llTradeList.addView(divider)
        }
    }

    // ── watchlist ─────────────────────────────────────────────────────

    // ── Decision log ─────────────────────────────────────────────────

    private fun updateDecisionLog(ts: TokenState) {
        val meta   = ts.meta
        val signal = ts.signal
        val phase  = ts.phase

        // Show the score breakdown card
        cardLogScores.visibility = android.view.View.VISIBLE
        tvLogToken.text  = ts.symbol.ifBlank { ts.mint.take(8) + "…" }
        tvLogPhase.text  = phase

        tvLogSignal.text = signal
        val (sigBg, sigCol) = when {
            signal == "BUY"                -> R.drawable.chip_green_bg to green
            signal in listOf("SELL","EXIT") -> R.drawable.chip_red_bg  to red
            signal.startsWith("WAIT_")     -> R.drawable.chip_neutral_bg to amber
            else                           -> R.drawable.chip_neutral_bg to muted
        }
        tvLogSignal.background = ContextCompat.getDrawable(this, sigBg)
        tvLogSignal.setTextColor(sigCol)

        tvLogEntry.text  = "ENTRY  ${ts.entryScore.toInt()}"
        tvLogExit.text   = "EXIT   ${ts.exitScore.toInt()}"
        tvLogVol.text    = "VOL    ${meta.volScore.toInt()}"
        tvLogPress.text  = "BUY%%   ${meta.pressScore.toInt()}"
        tvLogMom.text    = "MOM    ${meta.momScore.toInt()}"
        tvLogEmaFan.text = "EMA FAN  ${meta.emafanAlignment.ifBlank { "—" }}"

        // Active flag pills — shows which v4 signals fired this tick
        val flags = buildList {
            if (meta.exhaustion)           add("EXHAUST")
            if (phase == "breakdown")      add("BREAKDOWN")
            if (phase == "strong_reclaim") add("RECLAIM✓")
            if (phase == "choppy_range")   add("CHOP")
            if (phase == "micro_cap_wait") add("LOW HOLDERS")
            if (ts.exitScore > 70)         add("EXIT HIGH")
            if (ts.entryScore > 70)        add("ENTRY HIGH")
            if (meta.topUpReady)           add("🔺 TOP-UP READY")
            if (meta.spikeDetected)        add("⚡ SPIKE")
            if (meta.protectMode)          add("🔒 PROTECT")
            try {
                val regime = com.lifecyclebot.engine.BotService.instance?.botBrain?.currentRegime ?: ""
                if (regime.isNotBlank() && regime != "NEUTRAL" && regime != "UNKNOWN") add("brain:$regime")
            } catch (_: Exception) {}
        }
        tvLogFlags.text = flags.joinToString("  ·  ")
        tvLogFlags.visibility = if (flags.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE

        // Reason line — why did the bot pick this signal?
        tvLogReason.text = buildReasonLine(ts, phase, signal)
        try {
            val insight = com.lifecyclebot.engine.BotService.instance?.botBrain?.lastLlmInsight ?: ""
            if (insight.isNotBlank()) tvLogReason.text = "${tvLogReason.text}\n💡 $insight"
        } catch (_: Exception) {}

        // Show SmartSizer tier + multipliers
        val walletSol = vm.ui.value.walletSol
        val tier = when {
            walletSol < 0.5  -> "MICRO"
            walletSol < 2.0  -> "SMALL"
            walletSol < 10.0 -> "MEDIUM"
            walletSol < 50.0 -> "LARGE"
            else             -> "WHALE"
        }
        val pct = when {
            walletSol < 0.5  -> "5%"
            walletSol < 2.0  -> "6%"
            walletSol < 10.0 -> "7%"
            walletSol < 50.0 -> "6%"
            else             -> "5%"
        }
        tvLogReason.text = "${tvLogReason.text}\nSizer: $tier ${pct}×wallet  " +
            "wallet=${walletSol.fmtRef()}◎"

        // Append a timestamped line to the scrolling log
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val logLine = "$time  ${ts.symbol.padEnd(8)}  ${phase.padEnd(16)}  " +
            "E:${ts.entryScore.toInt().toString().padStart(3)}  " +
            "X:${ts.exitScore.toInt().toString().padStart(3)}  " +
            signal

        logLines.addFirst(logLine)
        if (logLines.size > 200) logLines.removeLast()

        tvDecisionLog.text = logLines.joinToString("\n")
        // Auto-scroll to top (newest entry)
        if (::scrollLog.isInitialized) {
            scrollLog.post { scrollLog.smoothScrollTo(0, 0) }
        }
    }

    private fun buildReasonLine(ts: TokenState, phase: String, signal: String): String {
        val meta = ts.meta
        return when {
            signal == "BUY" -> when (phase) {
                "pre_pump"       -> "Pre-pump: early accumulation, buyer dominance confirmed"
                "pumping"        -> "Active pump: volume + pressure aligned"
                "pump_pullback"  -> "Pullback on active pump: dip entry"
                "range"          -> "Range bottom (${meta.posInRange.toInt()}% in range): buying support"
                "strong_reclaim" -> "Strong reclaim: double-bottom + vol expanding on recovery"
                "reclaim_attempt"-> "Reclaim attempt: price above EMA, buyers returning"
                "cooling"        -> "Post-pump cooling: EMA fan healthy, dip entry"
                else             -> "Entry score ${ts.entryScore.toInt()} crossed threshold"
            }
            signal in listOf("SELL", "EXIT") -> when {
                meta.exhaustion          -> "Volume exhaustion: 3+ declining candles + buy ratio drop"
                phase == "breakdown"     -> "Breakdown: price below range floor"
                phase == "distribution"  -> "Distribution: lower highs forming, smart money exiting"
                ts.exitScore > 80        -> "Exit score ${ts.exitScore.toInt()}: multiple signals converging"
                else                     -> "Exit score ${ts.exitScore.toInt()} crossed threshold"
            }
            signal == "WAIT_CHOP"      -> "Choppy range: flat EMAs, no volume expansion — skipping"
            meta.topUpReady            -> "🔺 Top-up conditions met — will add to position"
            signal == "WAIT_HOLDERS"   -> "Micro-cap: holder count below 150 — waiting for distribution"
            signal == "WAIT_BUILDING"  -> "Pre-pump building: vol accelerating, not yet confirmed"
            signal == "WAIT_PULLBACK"  -> "Pumping: waiting for pullback entry"
            signal == "WAIT_CONFIRM"   -> "Reclaim: waiting for volume confirmation"
            signal == "WAIT_COOLING"   -> "Cooling: EMA fan not yet aligned for entry"
            else                       -> "Monitoring — E:${ts.entryScore.toInt()} X:${ts.exitScore.toInt()}"
        }
    }

    private fun saveScannerSettings() {
        val cfg = vm.ui.value.config
        try {
            val fullScan = try { findViewById<android.widget.Switch>(R.id.switchFullScan)?.isChecked } catch (_: Exception) { null } ?: cfg.fullMarketScanEnabled
            val graduates = try { findViewById<android.widget.CheckBox>(R.id.cbScanGraduates)?.isChecked } catch (_: Exception) { null } ?: cfg.scanPumpGraduates
            val dexTrend  = try { findViewById<android.widget.CheckBox>(R.id.cbScanDexTrending)?.isChecked } catch (_: Exception) { null } ?: cfg.scanDexTrending
            val gainers   = try { findViewById<android.widget.CheckBox>(R.id.cbScanGainers)?.isChecked } catch (_: Exception) { null } ?: cfg.scanDexGainers
            val boosted   = try { findViewById<android.widget.CheckBox>(R.id.cbScanBoosted)?.isChecked } catch (_: Exception) { null } ?: cfg.scanDexBoosted
            val raydium   = try { findViewById<android.widget.CheckBox>(R.id.cbScanRaydium)?.isChecked } catch (_: Exception) { null } ?: cfg.scanRaydiumNew
            val narrative = try { findViewById<android.widget.CheckBox>(R.id.cbScanNarrative)?.isChecked } catch (_: Exception) { null } ?: cfg.narrativeScanEnabled
            val minMc     = try { findViewById<android.widget.EditText>(R.id.etScanMinMc)?.text?.toString()?.toDoubleOrNull() } catch (_: Exception) { null } ?: cfg.scanMinMcapUsd
            val maxMc     = try { findViewById<android.widget.EditText>(R.id.etScanMaxMc)?.text?.toString()?.toDoubleOrNull() } catch (_: Exception) { null } ?: cfg.scanMaxMcapUsd
            val kwText    = try { findViewById<android.widget.EditText>(R.id.etNarrativeKeywords)?.text?.toString() } catch (_: Exception) { null } ?: ""
            val kws       = if (kwText.isNotBlank()) kwText.split(",").map{it.trim()}.filter{it.isNotBlank()} else cfg.narrativeKeywords
            com.lifecyclebot.data.ConfigStore.save(applicationContext,
                cfg.copy(fullMarketScanEnabled=fullScan, scanPumpGraduates=graduates,
                         scanDexTrending=dexTrend, scanDexGainers=gainers,
                         scanDexBoosted=boosted, scanRaydiumNew=raydium,
                         narrativeScanEnabled=narrative, scanMinMcapUsd=minMc,
                         scanMaxMcapUsd=maxMc, narrativeKeywords=kws))
        } catch (_: Exception) {}
    }

    private fun clearDecisionLog() {
        logLines.clear()
        tvDecisionLog.text = "Log cleared"
        cardLogScores.visibility = android.view.View.GONE
    }

    private fun renderWatchlist(state: UiState) {
        llTokenList.removeAllViews()
        val active = state.config.activeToken
        val tradeSubSp = resources.getDimension(R.dimen.trade_sub_text) / resources.displayMetrics.scaledDensity
        state.tokens.values.forEach { ts ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 10, 0, 10)
                isClickable  = true
                isFocusable  = true
                setOnClickListener {
                    vm.saveConfig(state.config.copy(activeToken = ts.mint))
                    etActiveToken.setText(ts.mint)
                    settingsPopulated = false
                }
            }
            val left = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val watchTextSp = resources.getDimension(R.dimen.token_name_size) / resources.displayMetrics.scaledDensity
            left.addView(TextView(this).apply {
                text      = (if (ts.mint == active) "● " else "  ") + (ts.symbol.ifBlank { ts.mint.take(8) })
                textSize  = watchTextSp
                setTextColor(if (ts.mint == active) white else muted)
                typeface  = android.graphics.Typeface.DEFAULT_BOLD
            })
            left.addView(TextView(this).apply {
                text      = ts.lastPrice.fmtPrice()
                textSize  = tradeSubSp
                setTextColor(muted)
                typeface  = android.graphics.Typeface.MONOSPACE
            })
            row.addView(left)
            row.addView(TextView(this).apply {
                text      = ts.phase
                textSize  = 11f
                setTextColor(muted)
                typeface  = android.graphics.Typeface.MONOSPACE
                gravity   = android.view.Gravity.END
            })
            val div = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).also { it.topMargin = 10 }
                setBackgroundColor(0xFF1F2937.toInt())
            }
            llTokenList.addView(row)
            llTokenList.addView(div)
        }
    }

    private fun addToken() {
        val mint = etAddMint.text.toString().trim()
        if (mint.isBlank()) return
        val cfg = ConfigStore.load(this)
        val wl  = cfg.watchlist.toMutableList()
        if (mint !in wl) wl.add(mint)
        vm.saveConfig(cfg.copy(watchlist = wl))
        etAddMint.setText("")
        Toast.makeText(this, "Added to watchlist", Toast.LENGTH_SHORT).show()
    }

    // ── settings ─────────────────────────────────────────────────────

    private fun setupSettings() {
        ArrayAdapter.createFromResource(this, R.array.mode_options, R.layout.spinner_item)
            .also { spMode.adapter = it }
        ArrayAdapter.createFromResource(this, R.array.auto_options, R.layout.spinner_item)
            .also { spAutoTrade.adapter = it }
    }

    private fun populateSettings(cfg: BotConfig) {
        etActiveToken.setText(cfg.activeToken)
        spMode.setSelection(if (cfg.paperMode) 0 else 1)
        spAutoTrade.setSelection(if (cfg.autoTrade) 1 else 0)
        etStopLoss.setText(cfg.stopLossPct.toString())
        etExitScore.setText(cfg.exitScoreThreshold.toString())
        etSmallBuy.setText(cfg.smallBuySol.toString())
        etLargeBuy.setText(cfg.largeBuySol.toString())
        etSlippage.setText(cfg.slippageBps.toString())
        etPoll.setText(cfg.pollSeconds.toString())
        etRpc.setText(cfg.rpcUrl)
        etTgBotToken.setText(cfg.telegramBotToken)
        etWatchlist.setText(cfg.watchlist.joinToString(", "))
        etHeliusKey.setText(cfg.heliusApiKey)
        etBirdeyeKey.setText(cfg.birdeyeApiKey)
        etGroqKey.setText(cfg.groqApiKey)
    }

    private fun saveSettings() {
        val wl = etWatchlist.text.toString()
            .split(",").map { it.trim() }.filter { it.isNotBlank() }
        val cfg = ConfigStore.load(this).copy(
            activeToken           = etActiveToken.text.toString().trim(),
            paperMode             = spMode.selectedItemPosition == 0,
            autoTrade             = spAutoTrade.selectedItemPosition == 1,
            stopLossPct           = etStopLoss.text.toString().toDoubleOrNull() ?: 10.0,
            exitScoreThreshold    = etExitScore.text.toString().toDoubleOrNull() ?: 58.0,
            smallBuySol           = etSmallBuy.text.toString().toDoubleOrNull() ?: 0.05,
            largeBuySol           = etLargeBuy.text.toString().toDoubleOrNull() ?: 0.10,
            slippageBps           = etSlippage.text.toString().toIntOrNull() ?: 200,
            pollSeconds           = etPoll.text.toString().toIntOrNull() ?: 8,
            rpcUrl                = etRpc.text.toString().trim().ifBlank { "https://api.mainnet-beta.solana.com" },
            telegramBotToken      = etTgBotToken.text.toString().trim(),
            heliusApiKey          = etHeliusKey.text.toString().trim(),
            birdeyeApiKey         = etBirdeyeKey.text.toString().trim(),
            groqApiKey            = etGroqKey.text.toString().trim(),
            watchlist             = wl,
        )
        vm.saveConfig(cfg)
        settingsPopulated = false
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    // ── permissions ───────────────────────────────────────────────────

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    /** Setup clickable help links for API key fields */
    private fun setupApiKeyHelpLinks() {
        val apiLinks = mapOf(
            R.id.tvHeliusHelp to "https://dev.helius.xyz/signup",
            R.id.tvBirdeyeHelp to "https://birdeye.so",
            R.id.tvGroqHelp to "https://console.groq.com",
            R.id.tvTelegramHelp to "https://t.me/BotFather"
        )
        
        apiLinks.forEach { (viewId, url) ->
            try {
                findViewById<TextView>(viewId)?.apply {
                    setOnClickListener {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (_: Exception) {
                            // Copy URL to clipboard as fallback
                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", url))
                            android.widget.Toast.makeText(this@MainActivity, "URL copied: $url", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    // Make it look clickable
                    paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                }
            } catch (_: Exception) {}
        }
    }

    /** Setup quick action icon buttons */
    private fun setupQuickActionButtons() {
        // Wallet button
        findViewById<View>(R.id.btnQuickWallet)?.setOnClickListener {
            startActivity(Intent(this, WalletActivity::class.java))
        }

        // Journal button
        findViewById<View>(R.id.btnQuickJournal)?.setOnClickListener {
            startActivity(Intent(this, JournalActivity::class.java))
        }

        // Alerts button
        findViewById<View>(R.id.btnQuickAlerts)?.setOnClickListener {
            startActivity(Intent(this, AlertsActivity::class.java))
        }

        // Settings button - scroll to settings section
        findViewById<View>(R.id.btnQuickSettings)?.setOnClickListener {
            val settingsSection = findViewById<View>(R.id.cardSettings)
            settingsSection?.let {
                val scrollView = findViewById<androidx.core.widget.NestedScrollView>(R.id.mainScrollView)
                scrollView?.smoothScrollTo(0, it.top)
            }
        }
    }

    /** Setup clear settings button with confirmation */
    private fun setupClearSettingsButton() {
        try {
            findViewById<android.widget.Button>(R.id.btnClearSettings)?.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Clear All API Keys?")
                    .setMessage("This will remove:\n\n" +
                        "• Helius API key\n" +
                        "• Birdeye API key\n" +
                        "• Groq API key\n" +
                        "• Telegram bot token\n" +
                        "• Telegram chat ID\n\n" +
                        "Your wallet and trading settings will be kept.")
                    .setPositiveButton("Clear Keys") { dialog: android.content.DialogInterface, _: Int ->
                        clearApiKeys()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        } catch (_: Exception) {}
    }

    /** Clear all API keys from storage and UI */
    private fun clearApiKeys() {
        try {
            // Clear UI fields
            etHeliusKey.setText("")
            etBirdeyeKey.setText("")
            etGroqKey.setText("")
            etTgBotToken.setText("")
            etTgChatId.setText("")

            // Save empty values
            val state = vm.ui.value
            val cfg = state.config.copy(
                heliusApiKey = "",
                birdeyeApiKey = "",
                groqApiKey = "",
                telegramBotToken = "",
                telegramChatId = "",
            )
            vm.saveConfig(cfg)

            Toast.makeText(this, "API keys cleared", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }
}

// ── extensions ────────────────────────────────────────────────────────────────

private fun Double.fmtRef(): String = "%.4f".format(this)

private fun Double.fmtPrice(): String = when {
    this <= 0       -> "—"
    this >= 1.0     -> "$%.4f".format(this)
    this >= 0.001   -> "$%.6f".format(this)
    else            -> "$%.8f".format(this)
}

private fun Double.fmtMcap(): String = when {
    this <= 0          -> "—"
    this >= 1_000_000  -> "$%.2fM".format(this / 1_000_000)
    this >= 1_000      -> "$%.1fK".format(this / 1_000)
    else               -> "$%.0f".format(this)
}
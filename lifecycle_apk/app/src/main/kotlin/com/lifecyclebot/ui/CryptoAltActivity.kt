package com.lifecyclebot.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lifecyclebot.R
import com.lifecyclebot.engine.RunTracker30D
import com.lifecyclebot.engine.ShadowLearningEngine
import com.lifecyclebot.perps.CryptoAltTrader
import com.lifecyclebot.perps.CryptoAltScannerAI
import com.lifecyclebot.perps.PerpsMarket
import com.lifecyclebot.perps.PerpsMarketDataFetcher
import com.lifecyclebot.perps.PerpsMarketData
import com.lifecyclebot.perps.WatchlistEngine
import com.lifecyclebot.v3.scoring.BlueChipTraderAI
import com.lifecyclebot.v3.scoring.FluidLearningAI
import com.lifecyclebot.v3.scoring.ManipulatedTraderAI
import com.lifecyclebot.v3.scoring.MoonshotTraderAI
import com.lifecyclebot.v3.scoring.QualityTraderAI
import com.lifecyclebot.v3.scoring.ShitCoinExpress
import com.lifecyclebot.v3.scoring.ShitCoinTraderAI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * ═══════════════════════════════════════════════════════════════════════
 * CRYPTO ALTS ACTIVITY — V2.1  (AATE Full Unified UI)
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Full AATE-style tile dashboard for cross-chain alt tokens.
 * ALL trading mode AIs are genuinely wired in:
 *
 *   • ShitCoinTraderAI  — ShitCoin mode (stats, win rate, daily PnL, mode)
 *   • QualityTraderAI   — Quality mode ($100K–$1M mcap)
 *   • BlueChipTraderAI  — Blue Chip mode
 *   • ShitCoinExpress   — Express high-velocity mode
 *   • MoonshotTraderAI  — Moonshot / 10x+ mode (10x/100x counters)
 *   • ManipulatedTraderAI — Manip catch mode
 *   • FluidLearningAI   — Bootstrap arc, learning progress, thresholds
 *   • RunTracker30D     — 30-day proof run (equity, drawdown, integrity)
 *   • ShadowLearningEngine — Shadow / FDG blocked trade analysis
 *   • CryptoAltScannerAI  — Dominance, sector heat, fear/greed, narratives
 *
 * Bottom tabs: Scanner | Watchlist | Positions | Settings
 */
class CryptoAltActivity : AppCompatActivity() {

    // ─── Palette ─────────────────────────────────────────────────────────────
    private val bg      = 0xFF0A0A0F.toInt()
    private val card    = 0xFF1A1A2E.toInt()
    private val card2   = 0xFF12122A.toInt()
    private val divBg   = 0xFF1F2937.toInt()
    private val white   = 0xFFFFFFFF.toInt()
    private val muted   = 0xFF6B7280.toInt()
    private val green   = 0xFF22C55E.toInt()
    private val red     = 0xFFEF4444.toInt()
    private val amber   = 0xFFF59E0B.toInt()
    private val purple  = 0xFFA78BFA.toInt()
    private val blue    = 0xFF60A5FA.toInt()
    private val teal    = 0xFF14B8A6.toInt()
    private val pink    = 0xFFF472B6.toInt()
    private val orange  = 0xFFFB923C.toInt()
    private val indigo  = 0xFF818CF8.toInt()
    private val sdf     = SimpleDateFormat("MMM dd HH:mm", Locale.US)

    // ─── Views ───────────────────────────────────────────────────────────────
    private lateinit var progressBar : ProgressBar
    private lateinit var llContent   : LinearLayout   // R.id.llCryptoAltContent
    private lateinit var tabScanner  : TextView
    private lateinit var tabWatchlist: TextView
    private lateinit var tabPositions: TextView
    private lateinit var tabSettings : TextView

    // Hero
    private lateinit var tvHeroBalance : TextView
    private lateinit var tvHeroPnl     : TextView
    private lateinit var tvHeroWinRate : TextView
    private lateinit var tvHeroTrades  : TextView
    private lateinit var tvHeroPhase   : TextView

    // State
    private var currentTab  = 0
    private var chainFilter : String? = null

    private val altMarkets: List<PerpsMarket> by lazy {
        PerpsMarket.values().filter { it.isCrypto && !it.isSolPerp }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crypto_alt)
        supportActionBar?.hide()

        llContent    = findViewById(R.id.llCryptoAltContent)
        progressBar  = findViewById(R.id.progressCryptoAlt)
        tabScanner   = findViewById(R.id.tabCryptoAltScanner)
        tabWatchlist = findViewById(R.id.tabCryptoAltWatchlist)
        tabPositions = findViewById(R.id.tabCryptoAltPositions)
        tabSettings  = findViewById(R.id.tabCryptoAltSettings)

        WatchlistEngine.init(applicationContext)

        findViewById<View>(R.id.btnCryptoAltBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnCryptoAltScan).setOnClickListener { buildFullDashboard() }
        findViewById<View>(R.id.btnCryptoAltAdd).setOnClickListener { showAddToWatchlistDialog() }

        tabScanner.setOnClickListener   { selectTab(0) }
        tabWatchlist.setOnClickListener { selectTab(1) }
        tabPositions.setOnClickListener { selectTab(2) }
        tabSettings.setOnClickListener  { selectTab(3) }

        buildFullDashboard()
        startAutoRefresh()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AUTO REFRESH (15s hero stats)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun startAutoRefresh() {
        lifecycleScope.launch {
            while (isActive) {
                delay(15_000L)
                withContext(Dispatchers.Main) {
                    if (::tvHeroBalance.isInitialized) refreshHeroStats()
                }
            }
        }
    }

    private fun refreshHeroStats() {
        val bal     = CryptoAltTrader.getBalance()
        val pnl     = CryptoAltTrader.getTotalPnlSol()
        val wr      = CryptoAltTrader.getWinRate()
        val trades  = CryptoAltTrader.getTotalTrades()
        val phase   = getPhaseLabel()
        tvHeroBalance.text = "◎ ${"%.4f".format(bal)}"
        tvHeroPnl.text     = "${if (pnl >= 0) "+" else ""}${"%.4f".format(pnl)} SOL"
        tvHeroPnl.setTextColor(if (pnl >= 0) green else red)
        tvHeroWinRate.text = "${"%.1f".format(wr)}% WR"
        tvHeroTrades.text  = "$trades trades"
        tvHeroPhase.text   = phase
        tvHeroPhase.setTextColor(phaseColor(phase))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FULL DASHBOARD
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildFullDashboard() {
        llContent.removeAllViews()

        buildHeroSection()
        buildReadinessTile()
        buildProofRunTile()
        buildShitCoinTile()
        buildQualityTile()
        buildBlueChipTile()
        buildExpressTile()
        buildMoonshotTile()
        buildManipTile()
        buildShadowFDGPanel()
        buildHiveMindPanel()
        buildSectorHeatPanel()
        buildSignalCards()
        addDivider()
        buildTabContent()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HERO
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildHeroSection() {
        val bal    = CryptoAltTrader.getBalance()
        val pnl    = CryptoAltTrader.getTotalPnlSol()
        val wr     = CryptoAltTrader.getWinRate()
        val trades = CryptoAltTrader.getTotalTrades()
        val phase  = getPhaseLabel()

        val tile = vBox(card2, 20, 18)

        // Balance + badge row
        val balRow = hBox()
        tvHeroBalance = tv("◎ ${"%.4f".format(bal)}", 28f, white, bold = true).apply {
            layoutParams = llp(0, wrap, 1f)
        }
        val badge = tv(if (CryptoAltTrader.isLiveMode()) "● LIVE" else "● PAPER", 10f,
            if (CryptoAltTrader.isLiveMode()) green else amber, bold = true).apply {
            setBackgroundColor(if (CryptoAltTrader.isLiveMode()) 0xFF052E16.toInt() else 0xFF451A03.toInt())
            setPadding(8, 4, 8, 4)
        }
        balRow.addView(tvHeroBalance); balRow.addView(badge)
        tile.addView(balRow)

        // Stats row
        val statsRow = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 4 } }
        tvHeroPnl = tv("${if (pnl >= 0) "+" else ""}${"%.4f".format(pnl)} SOL", 14f,
            if (pnl >= 0) green else red, mono = true).apply { layoutParams = llp(0, wrap, 1f) }
        tvHeroWinRate = tv("${"%.1f".format(wr)}% WR", 12f, purple, mono = true).apply { layoutParams = llp(0, wrap, 1f) }
        tvHeroTrades  = tv("$trades trades", 12f, muted, mono = true)
        statsRow.addView(tvHeroPnl); statsRow.addView(tvHeroWinRate); statsRow.addView(tvHeroTrades)
        tile.addView(statsRow)

        // Phase
        tvHeroPhase = tv(phase, 10f, phaseColor(phase), bold = true).apply {
            layoutParams = llp(match, wrap).apply { topMargin = 4 }
        }
        tile.addView(tvHeroPhase)

        // Chain chips
        val chainRow = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 10 } }
        listOf("BNB" to amber, "ETH" to blue, "SOL" to purple, "POLY" to indigo).forEach { (label, col) ->
            chainRow.addView(tv(label, 9f, col).apply {
                setBackgroundColor(0xFF0D0D1A.toInt()); setPadding(8, 3, 8, 3)
                layoutParams = llp(0, wrap, 1f).apply { marginEnd = 3 }; gravity = Gravity.CENTER
            })
        }
        tile.addView(chainRow)

        llContent.addView(tile)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LIVE READINESS TILE (FluidLearningAI)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildReadinessTile() {
        val trades   = FluidLearningAI.getTotalTradeCount()
        val mTrades  = FluidLearningAI.getMarketsTradeCount()
        val progress = FluidLearningAI.getLearningProgress()
        val mProgress= FluidLearningAI.getMarketsLearningProgress()
        val phase    = getPhaseLabel()
        val boost    = FluidLearningAI.getBootstrapConfidenceBoost()
        val sizeMult = FluidLearningAI.getBootstrapSizeMultiplier()
        val status   = FluidLearningAI.getBootstrapStatus()

        val tile = buildTile(purple, "📡 Live Readiness", phase, phaseColor(phase))

        // Fluid learning progress bar
        tile.addView(tv("Fluid Learning", 9f, muted).apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } })
        tile.addView(progressBar(purple, (progress * 100).toInt()))

        tile.addView(tv("Markets Learning", 9f, muted).apply { layoutParams = llp(match, wrap).apply { topMargin = 4 } })
        tile.addView(progressBar(teal, (mProgress * 100).toInt()))

        val row = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(row, "V3 Trades",  "$trades",    white,  1f)
        addStatChip(row, "Mkts Trades","$mTrades",   teal,   1f)
        addStatChip(row, "Conf Boost", "+${(boost*100).toInt()}%", purple, 1f)
        addStatChip(row, "Size Mult",  "${"%.2f".format(sizeMult)}x", amber, 1f)
        tile.addView(row)

        tile.addView(tv(status, 9f, muted).apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } })

        llContent.addView(tile)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 30-DAY PROOF RUN TILE (RunTracker30D)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildProofRunTile() {
        val isActive = RunTracker30D.isRunActive()
        val day      = RunTracker30D.getCurrentDay()
        val wins     = RunTracker30D.wins
        val losses   = RunTracker30D.losses
        val total    = RunTracker30D.totalTrades
        val wr       = if (total > 0) wins.toDouble() / total * 100 else 0.0
        val pnl      = RunTracker30D.totalRealizedPnlSol
        val startBal = RunTracker30D.startBalance
        val currBal  = RunTracker30D.currentBalance
        val peak     = RunTracker30D.peakBalance
        val dd       = RunTracker30D.maxDrawdown
        val integrity= if (isActive) RunTracker30D.integrityScore() else 0
        val equity   = RunTracker30D.getEquityLabel()
        val dayLabel = if (isActive) "Day $day / 30" else "NOT STARTED"

        val tile = buildTile(teal, "📊 30-Day Proof Run", dayLabel, if (isActive) teal else muted)

        val row1 = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 8 } }
        addStatChip(row1, "Trades",    "$total",             white, 1f)
        addStatChip(row1, "Win Rate",  "${"%.1f".format(wr)}%", if (wr >= 55) green else amber, 1f)
        addStatChip(row1, "Realized",  "${if (pnl >= 0) "+" else ""}${"%.3f".format(pnl)}◎", if (pnl >= 0) green else red, 1f)
        addStatChip(row1, "Integrity", "$integrity/100",     if (integrity >= 80) green else amber, 1f)
        tile.addView(row1)

        val row2 = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 4 } }
        addStatChip(row2, "Start",     "◎${"%.2f".format(startBal)}", muted,  1f)
        addStatChip(row2, "Current",   "◎${"%.2f".format(currBal)}", white,  1f)
        addStatChip(row2, "Peak",      "◎${"%.2f".format(peak)}",    green,  1f)
        addStatChip(row2, "Max DD",    "${"%.1f".format(dd)}%",       red,    1f)
        tile.addView(row2)

        tile.addView(tv(equity, 9f, muted).apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } })

        if (!isActive) {
            tile.addView(tv("⚠️ Run not started — call RunTracker30D.startRun() to begin", 9f, amber).apply {
                layoutParams = llp(match, wrap).apply { topMargin = 4 }
            })
        }

        llContent.addView(tile)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SHITCOIN TILE (ShitCoinTraderAI)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildShitCoinTile() {
        val stats    = ShitCoinTraderAI.getStats()
        val mode     = stats.mode.name
        val modeEmoji= when (mode) { "HUNTING" -> "🎯"; "POSITIONED" -> "📊"; else -> "💤" }
        val openPos  = stats.activePositions
        val wr       = stats.winRate
        val pnl      = stats.dailyPnlSol
        val bal      = stats.balanceSol
        val paper    = stats.isPaperMode

        val tile = buildTile(orange, "💩 ShitCoin Trader", "$modeEmoji $mode", orange)

        val row = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 8 } }
        addStatChip(row, "Balance",  "◎${"%.3f".format(bal)}", white,  1f)
        addStatChip(row, "Daily PnL","${if (pnl >= 0) "+" else ""}${"%.3f".format(pnl)}◎", if (pnl >= 0) green else red, 1f)
        addStatChip(row, "Win Rate", "${"%.1f".format(wr)}%", if (wr >= 55) green else amber, 1f)
        addStatChip(row, "Open",     "$openPos pos", blue, 1f)
        tile.addView(row)

        val row2 = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 4 } }
        addStatChip(row2, "Score Min",  "${stats.minScoreThreshold}", muted,  1f)
        addStatChip(row2, "Conf Min",   "${stats.minConfThreshold}",  muted,  1f)
        addStatChip(row2, "Rugged Devs","${stats.ruggedDevsCount}",   red,    1f)
        addStatChip(row2, "Mode",       if (paper) "PAPER" else "LIVE", if (paper) amber else green, 1f)
        tile.addView(row2)

        // Daily wins/losses
        tile.addView(tv("Today: ${stats.dailyWins}W / ${stats.dailyLosses}L  •  Max Loss: ${"%.3f".format(stats.dailyMaxLossSol)}◎",
            9f, muted).apply { layoutParams = llp(match, wrap).apply { topMargin = 4 } })

        llContent.addView(tile)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // QUALITY TILE (QualityTraderAI)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildQualityTile() {
        val wr      = QualityTraderAI.getWinRate()
        val dailyPnl= QualityTraderAI.getDailyPnl()
        val openPos = QualityTraderAI.getActivePositions().size
        val tp      = QualityTraderAI.getFluidTakeProfit()
        val sl      = QualityTraderAI.getFluidStopLoss()

        val tile = buildTile(blue, "💎 Quality Trader", "$100K–$1M mcap", blue)

        val row = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 8 } }
        addStatChip(row, "Win Rate",  "${"%.1f".format(wr)}%",               if (wr >= 55) green else amber, 1f)
        addStatChip(row, "Daily PnL", "${if (dailyPnl >= 0) "+" else ""}${"%.3f".format(dailyPnl)}◎", if (dailyPnl >= 0) green else red, 1f)
        addStatChip(row, "Open Pos",  "$openPos",                             blue, 1f)
        tile.addView(row)

        val row2 = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 4 } }
        addStatChip(row2, "TP",  "${"%.1f".format(tp)}%", green, 1f)
        addStatChip(row2, "SL",  "${"%.1f".format(sl)}%", red,   1f)
        tile.addView(row2)

        llContent.addView(tile)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BLUE CHIP TILE (BlueChipTraderAI)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildBlueChipTile() {
        val stats   = BlueChipTraderAI.getStats()
        val wr      = BlueChipTraderAI.getWinRatePct()
        val mode    = stats.mode.name
        val bal     = BlueChipTraderAI.getCurrentBalance()
        val pnl     = BlueChipTraderAI.getDailyPnlSol()
        val openPos = stats.activePositions
        val tp      = BlueChipTraderAI.getFluidTakeProfit()
        val sl      = BlueChipTraderAI.getFluidStopLoss()

        val tile = buildTile(teal, "🔵 Blue Chip Trader", mode, teal)

        val row = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 8 } }
        addStatChip(row, "Balance",  "◎${"%.3f".format(bal)}",               white,                    1f)
        addStatChip(row, "Daily PnL","${if (pnl >= 0) "+" else ""}${"%.3f".format(pnl)}◎", if (pnl >= 0) green else red, 1f)
        addStatChip(row, "Win Rate", "$wr%",                                  if (wr >= 55) green else amber, 1f)
        addStatChip(row, "Open",     "$openPos",                              blue, 1f)
        tile.addView(row)

        val row2 = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 4 } }
        addStatChip(row2, "TP", "${"%.1f".format(tp)}%", green, 1f)
        addStatChip(row2, "SL", "${"%.1f".format(sl)}%", red,   1f)
        addStatChip(row2, "Score Min", "${BlueChipTraderAI.getFluidScoreThreshold()}", muted, 1f)
        tile.addView(row2)

        llContent.addView(tile)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EXPRESS TILE (ShitCoinExpress)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildExpressTile() {
        val stats  = ShitCoinExpress.getStats()
        val wr     = stats.winRate
        val pnl    = stats.dailyPnlSol
        val active = stats.activeRides
        val rides  = stats.dailyRides
        val paper  = stats.isPaperMode

        val tile = buildTile(amber, "⚡ ShitCoin Express", "Fast Rides", amber)

        val row = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 8 } }
        addStatChip(row, "Daily Rides", "$rides",                              white, 1f)
        addStatChip(row, "Win Rate",    "${"%.1f".format(wr)}%",               if (wr >= 55) green else amber, 1f)
        addStatChip(row, "Daily PnL",   "${if (pnl >= 0) "+" else ""}${"%.3f".format(pnl)}◎", if (pnl >= 0) green else red, 1f)
        addStatChip(row, "Active",      "$active rides",                       blue, 1f)
        tile.addView(row)

        tile.addView(tv("${stats.dailyWins}W / ${stats.dailyLosses}L  •  Mode: ${if (paper) "PAPER" else "LIVE"}",
            9f, muted).apply { layoutParams = llp(match, wrap).apply { topMargin = 4 } })

        llContent.addView(tile)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MOONSHOT TILE (MoonshotTraderAI)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildMoonshotTile() {
        val wr       = MoonshotTraderAI.getWinRatePct()
        val pnl      = MoonshotTraderAI.getDailyPnlSol()
        val openPos  = MoonshotTraderAI.getActivePositions().size
        val progress = MoonshotTraderAI.getLearningProgress()
        val tenX     = MoonshotTraderAI.getDailyTenX()
        val hundredX = MoonshotTraderAI.getDailyHundredX()
        val ltTenX   = MoonshotTraderAI.getLifetimeTenX()
        val ltHundredX = MoonshotTraderAI.getLifetimeHundredX()
        val dWins    = MoonshotTraderAI.getDailyWins()
        val dLosses  = MoonshotTraderAI.getDailyLosses()

        val tile = buildTile(pink, "🚀 Moonshot Trader", "10x / 100x", pink)

        tile.addView(tv("Learning", 9f, muted).apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } })
        tile.addView(progressBar(pink, (progress * 100).toInt()))

        val row = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(row, "Win Rate",  "$wr%",                               if (wr >= 55) green else amber, 1f)
        addStatChip(row, "Daily PnL", "${if (pnl >= 0) "+" else ""}${"%.3f".format(pnl)}◎", if (pnl >= 0) green else red, 1f)
        addStatChip(row, "Open",      "$openPos",                           blue, 1f)
        tile.addView(row)

        val row2 = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 4 } }
        addStatChip(row2, "Today 10x",   "$tenX 🚀",   green,  1f)
        addStatChip(row2, "Today 100x",  "$hundredX 🌕", pink,  1f)
        addStatChip(row2, "Life 10x",    "$ltTenX",    green,  1f)
        addStatChip(row2, "Life 100x",   "$ltHundredX",pink,   1f)
        tile.addView(row2)

        tile.addView(tv("Today: ${dWins}W / ${dLosses}L", 9f, muted).apply {
            layoutParams = llp(match, wrap).apply { topMargin = 4 }
        })

        llContent.addView(tile)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MANIP TILE (ManipulatedTraderAI)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildManipTile() {
        val stats  = ManipulatedTraderAI.getStats()
        val wr     = if ((stats.dailyWins + stats.dailyLosses) > 0)
            stats.dailyWins.toDouble() / (stats.dailyWins + stats.dailyLosses) * 100 else 0.0
        val pnl    = stats.dailyPnlSol
        val caught = stats.totalManipCaught
        val active = stats.activeCount

        val tile = buildTile(red, "☠️ Manip Catcher", "Catch & Ride", red)

        val row = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 8 } }
        addStatChip(row, "Win Rate", "${"%.1f".format(wr)}%",               if (wr >= 55) green else amber, 1f)
        addStatChip(row, "Daily PnL","${if (pnl >= 0) "+" else ""}${"%.3f".format(pnl)}◎", if (pnl >= 0) green else red, 1f)
        addStatChip(row, "Caught",   "$caught",                              red,   1f)
        addStatChip(row, "Active",   "$active",                              blue,  1f)
        tile.addView(row)

        tile.addView(tv("Today: ${stats.dailyWins}W / ${stats.dailyLosses}L  •  Score Min: ${ManipulatedTraderAI.getFluidScoreThreshold()}",
            9f, muted).apply { layoutParams = llp(match, wrap).apply { topMargin = 4 } })

        llContent.addView(tile)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SHADOW / FDG PANEL (ShadowLearningEngine)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildShadowFDGPanel() {
        val stats   = ShadowLearningEngine.getBlockedTradeStats()
        val topMode = ShadowLearningEngine.getTopTrackedMode() ?: "—"
        val best    = ShadowLearningEngine.getBestVariant()
        val insights= ShadowLearningEngine.getInsights(3)

        val tile = buildTile(indigo, "👁️ Shadow / FDG Learning", "AI Watcher", indigo)

        val row = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 8 } }
        addStatChip(row, "Tracked",  "${stats.totalTracked}",  white,  1f)
        addStatChip(row, "Would Win","${stats.wouldHaveWon}",  green,  1f)
        addStatChip(row, "Would Lose","${stats.wouldHaveLost}",red,    1f)
        addStatChip(row, "Top Mode", topMode,                  indigo, 1f)
        tile.addView(row)

        if (best != null) {
            tile.addView(tv("Best Variant: ${best.variantId}  •  Score: ${"%.2f".format(best.score)}",
                9f, green).apply { layoutParams = llp(match, wrap).apply { topMargin = 4 } })
        }

        if (insights.isNotEmpty()) {
            tile.addView(divLine())
            tile.addView(tv("🧠 Insights", 9f, indigo, bold = true).apply {
                layoutParams = llp(match, wrap).apply { topMargin = 4; bottomMargin = 2 }
            })
            insights.forEach { insight ->
                tile.addView(tv("• ${insight.description}", 9f, muted).apply {
                    layoutParams = llp(match, wrap).apply { bottomMargin = 1 }
                })
            }
        }

        llContent.addView(tile)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HIVE MIND (CryptoAltScannerAI)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildHiveMindPanel() {
        val dominance  = CryptoAltScannerAI.getDominanceCycleSignal()
        val fg         = CryptoAltScannerAI.getCryptoFearGreed()
        val narratives = CryptoAltScannerAI.getActiveNarratives()
        val domColor   = when (dominance) { "ALT_SEASON" -> green; "BTC_DOMINANCE" -> red; else -> amber }
        val fgLabel    = when { fg >= 75 -> "Extreme Greed 🔥"; fg >= 55 -> "Greed 📈"; fg >= 45 -> "Neutral 😐"; fg >= 25 -> "Fear 📉"; else -> "Extreme Fear 🧊" }
        val fgColor    = when { fg >= 75 -> red; fg >= 55 -> orange; fg >= 45 -> amber; fg >= 25 -> blue; else -> teal }

        val tile = buildTile(orange, "🐝 Hive Mind — Alt Intelligence", "AI", orange)

        val row = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 8 } }
        addStatChip(row, "Dominance", dominance,  domColor, 1f)
        addStatChip(row, "Fear/Greed","$fg — $fgLabel", fgColor, 2f)
        tile.addView(row)

        if (narratives.isNotEmpty()) {
            tile.addView(divLine())
            tile.addView(tv("🔥 Active Narratives", 9f, orange, bold = true).apply {
                layoutParams = llp(match, wrap).apply { topMargin = 4; bottomMargin = 4 }
            })
            val narRow = hBox()
            narratives.take(4).forEach { nar ->
                narRow.addView(tv(nar.replace("_", " "), 9f, amber).apply {
                    setBackgroundColor(0xFF451A03.toInt()); setPadding(6, 3, 6, 3)
                    layoutParams = llp(wrap, wrap).apply { marginEnd = 4 }
                })
            }
            tile.addView(narRow)
        }

        llContent.addView(tile)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SECTOR HEAT (CryptoAltScannerAI)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildSectorHeatPanel() {
        val tile = buildTile(blue, "🧬 Sector Heat Map", "6 sectors", blue)

        val sectors = listOf(
            Triple("MEME",   CryptoAltScannerAI.getSectorHeat("DOGE"),   pink),
            Triple("DeFi",   CryptoAltScannerAI.getSectorHeat("AAVE"),   teal),
            Triple("L1",     CryptoAltScannerAI.getSectorHeat("ADA"),    purple),
            Triple("L2",     CryptoAltScannerAI.getSectorHeat("ARB"),    blue),
            Triple("Gaming", CryptoAltScannerAI.getSectorHeat("AXS"),    green),
            Triple("AI",     CryptoAltScannerAI.getSectorHeat("RENDER"), amber)
        )

        for (i in 0 until 2) {
            val row = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 4 } }
            for (j in 0 until 3) {
                val (name, heat, color) = sectors[i * 3 + j]
                val pct = (heat * 100).toInt()
                row.addView(vBox(card2, 8, 6).apply {
                    layoutParams = llp(0, wrap, 1f).apply { marginEnd = if (j < 2) 3 else 0 }
                    addView(tv(name, 9f, color, bold = true))
                    addView(progressBar(color, pct).apply { layoutParams = llp(match, 4).apply { topMargin = 2; bottomMargin = 1 } })
                    addView(tv("$pct%", 8f, muted, mono = true))
                })
            }
            tile.addView(row)
        }

        // Alt beta row
        tile.addView(divLine())
        val betaRow = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 4 } }
        listOf("BTC", "ETH", "DOGE", "SHIB").forEach { sym ->
            val beta = CryptoAltScannerAI.getAltBeta(sym)
            betaRow.addView(vBox(0, 0, 0).apply {
                layoutParams = llp(0, wrap, 1f)
                addView(tv(sym, 8f, muted).apply { gravity = Gravity.CENTER })
                addView(tv("${"%.1f".format(beta)}β", 10f,
                    if (beta > 1.5) red else if (beta > 1.0) amber else green, mono = true).apply { gravity = Gravity.CENTER })
            })
        }
        tile.addView(betaRow)

        llContent.addView(tile)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SIGNAL CARDS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildSignalCards() {
        addSectionHeader("🔥 Top Alt Signals", purple)
        val container = vBox(0, 0, 0).apply { layoutParams = llp(match, wrap) }
        llContent.addView(container)

        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val results = mutableListOf<Pair<PerpsMarket, PerpsMarketData>>()
            for (m in altMarkets.take(30)) {
                try { results.add(m to PerpsMarketDataFetcher.getMarketData(m)) } catch (_: Exception) {}
            }
            results.sortByDescending { kotlin.math.abs(it.second.priceChange24hPct) }
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                container.removeAllViews()
                results.take(8).forEach { (market, data) -> container.addView(buildSignalCard(market, data)) }
            }
        }
    }

    private fun buildSignalCard(market: PerpsMarket, data: PerpsMarketData): LinearLayout {
        val change  = data.priceChange24hPct
        val heat    = CryptoAltScannerAI.getSectorHeat(market.symbol)
        val beta    = CryptoAltScannerAI.getAltBeta(market.symbol)
        val signal  = when {
            change > 8 && heat > 0.6  -> "🟢 STRONG BUY"
            change > 3                 -> "🟢 BUY"
            change < -8 && heat < 0.4  -> "🔴 STRONG SELL"
            change < -3                -> "🔴 SELL"
            else                       -> "🟡 HOLD"
        }
        val sigColor = when {
            signal.contains("STRONG BUY")  -> green
            signal.contains("BUY")         -> 0xFF4ADE80.toInt()
            signal.contains("STRONG SELL") -> red
            signal.contains("SELL")        -> 0xFFFC8181.toInt()
            else                           -> amber
        }

        return hBox(card, 16, 12).apply {
            layoutParams = llp(match, wrap).apply { bottomMargin = 2 }
            gravity = Gravity.CENTER_VERTICAL
            addView(tv("${market.emoji} ${market.symbol}", 13f, white, bold = true).apply { layoutParams = llp(0, wrap, 2f) })
            addView(tv(if (data.price > 1) "$%.2f".format(data.price) else "$%.5f".format(data.price), 11f, white, mono = true).apply { layoutParams = llp(0, wrap, 2f) })
            addView(tv("${if (change >= 0) "+" else ""}%.1f%%".format(change), 11f, if (change >= 0) green else red, mono = true).apply { layoutParams = llp(0, wrap, 1.5f) })
            addView(tv(signal, 9f, sigColor, bold = true).apply { layoutParams = llp(0, wrap, 2f) })
            addView(tv("${"%.1f".format(beta)}β", 9f, if (beta > 1.5) red else muted, mono = true))
            setOnClickListener { showSignalDetail(market, data, signal, beta) }
        }
    }

    private fun showSignalDetail(market: PerpsMarket, data: PerpsMarketData, signal: String, beta: Double) {
        val change = data.priceChange24hPct
        val heat   = (CryptoAltScannerAI.getSectorHeat(market.symbol) * 100).toInt()
        AlertDialog.Builder(this)
            .setTitle("${market.emoji} ${market.symbol}")
            .setMessage(
                "Price:       ${if (data.price > 1) "$%.2f".format(data.price) else "$%.6f".format(data.price)}\n" +
                "24h Change:  ${if (change >= 0) "+" else ""}%.1f%%\n".format(change) +
                "Volume 24h:  $%.0fM\n".format(data.volume24h / 1_000_000) +
                "Signal:      $signal\n" +
                "Sector Heat: $heat%\n" +
                "BTC Beta:    ${"%.2f".format(beta)}x"
            )
            .setPositiveButton("⭐ Watchlist") { _, _ -> addSymbolToWatchlist(market.symbol) }
            .setNegativeButton("Close", null)
            .show()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TABS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildTabContent() {
        // Marker so we know where to trim on tab switch
        llContent.addView(View(this).apply { tag = "TAB_START"; layoutParams = llp(match, 0) })
        selectTab(currentTab)
    }

    private fun selectTab(tab: Int) {
        currentTab = tab
        val colors = listOf(purple, green, amber, 0xFF9CA3AF.toInt())
        val bgs    = listOf(0xFF1A1528.toInt(), 0xFF1A2E1A.toInt(), 0xFF2E2A1A.toInt(), 0xFF1F2937.toInt())
        listOf(tabScanner, tabWatchlist, tabPositions, tabSettings).forEachIndexed { i, tv ->
            if (i == tab) { tv.setTextColor(colors[i]); tv.setBackgroundColor(bgs[i]) }
            else          { tv.setTextColor(muted);     tv.setBackgroundColor(0) }
        }

        // Remove old tab content
        val marker = llContent.findViewWithTag<View>("TAB_START")
        val idx    = llContent.indexOfChild(marker)
        while (llContent.childCount > idx + 1) llContent.removeViewAt(idx + 1)

        when (tab) {
            0 -> buildScannerTab()
            1 -> buildWatchlistTab()
            2 -> buildPositionsTab()
            3 -> buildSettingsTab()
        }
    }

    // ─── SCANNER ─────────────────────────────────────────────────────────────

    private fun buildScannerTab() {
        addSectionHeader("🔍 Full Alt Scanner", purple)
        addChainFilterRow()
        val markets = getFilteredMarkets()
        if (markets.isEmpty()) { addEmptyState("No alt markets match filter"); return }

        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val results = mutableListOf<Pair<PerpsMarket, PerpsMarketData>>()
            for (m in markets.take(50)) {
                try { results.add(m to PerpsMarketDataFetcher.getMarketData(m)) } catch (_: Exception) {}
            }
            results.sortByDescending { kotlin.math.abs(it.second.priceChange24hPct) }
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                results.forEach { (market, data) ->
                    val change = data.priceChange24hPct
                    val row = hBox(card, 16, 10).apply {
                        layoutParams = llp(match, wrap).apply { bottomMargin = 2 }
                        gravity = Gravity.CENTER_VERTICAL
                        addView(tv("${market.emoji} ${market.symbol}", 13f, white, bold = true).apply { layoutParams = llp(0, wrap, 2f) })
                        addView(tv(if (data.price > 1) "$%.2f".format(data.price) else "$%.6f".format(data.price), 12f, white, mono = true).apply { layoutParams = llp(0, wrap, 2f) })
                        addView(tv("${if (change >= 0) "+" else ""}%.1f%%".format(change), 12f, if (change >= 0) green else red, mono = true).apply { gravity = Gravity.END; layoutParams = llp(0, wrap, 1f) })
                        addView(tv("+", 16f, purple).apply {
                            setPadding(16, 0, 8, 0)
                            setOnClickListener { addSymbolToWatchlist(market.symbol) }
                        })
                        setOnClickListener { showSignalDetail(market, data,
                            if (change > 3) "🟢 BUY" else if (change < -3) "🔴 SELL" else "🟡 HOLD",
                            CryptoAltScannerAI.getAltBeta(market.symbol)) }
                    }
                    llContent.addView(row); llContent.addView(thinDivider())
                }
                findViewById<TextView>(R.id.tvCryptoAltStats)?.text = "${results.size} alts scanned"
            }
        }
    }

    private fun addChainFilterRow() {
        val row = hBox().apply { setPadding(16, 8, 16, 8) }
        listOf<Pair<String?, String>>(null to "All", "BNB" to "BNB", "ETH" to "ETH", "SOL" to "SOL", "MATIC" to "Poly").forEach { (chain, label) ->
            row.addView(tv(label, 11f, if (chainFilter == chain) white else muted).apply {
                gravity = Gravity.CENTER; setPadding(16, 6, 16, 6)
                setBackgroundColor(if (chainFilter == chain) purple else divBg)
                layoutParams = llp(0, wrap, 1f).apply { marginEnd = 4 }
                setOnClickListener { chainFilter = chain; selectTab(0) }
            })
        }
        llContent.addView(row)
    }

    private fun getFilteredMarkets(): List<PerpsMarket> {
        val bnb   = setOf("DOGE","SHIB","FLOKI","BABYDOGE","PEPE","WIF","BONK","CAKE","BNB","ONE","TWT")
        val eth   = setOf("ETH","LINK","UNI","AAVE","MKR","SNX","COMP","ENS","GRT","LDO","IMX","ARB","OP")
        val sol   = setOf("SOL","RAY","ORCA","JUP","PYTH","DRIFT","MNGO","BONK","WIF","BOME","MEW")
        val matic = setOf("MATIC","QUICK","GHST","SAND","MANA","AXS","AAVE")
        return when (chainFilter) {
            "BNB"   -> altMarkets.filter { it.symbol in bnb }
            "ETH"   -> altMarkets.filter { it.symbol in eth }
            "SOL"   -> altMarkets.filter { it.symbol in sol }
            "MATIC" -> altMarkets.filter { it.symbol in matic }
            else    -> altMarkets
        }
    }

    // ─── WATCHLIST ────────────────────────────────────────────────────────────

    private fun buildWatchlistTab() {
        addSectionHeader("⭐ Alt Watchlist", green)
        val items = WatchlistEngine.getWatchlist().filter { item -> altMarkets.any { it.symbol == item.symbol } }
        if (items.isEmpty()) { addEmptyState("No alts in watchlist yet. Tap + to add."); return }

        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val results = items.map { item ->
                val market = altMarkets.find { it.symbol == item.symbol }
                val data   = market?.let { runCatching { PerpsMarketDataFetcher.getMarketData(it) }.getOrNull() }
                Triple(item, market, data)
            }
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                results.forEach { (item, market, data) ->
                    val change = data?.priceChange24hPct ?: 0.0
                    val row = hBox(card, 16, 10).apply {
                        layoutParams = llp(match, wrap).apply { bottomMargin = 2 }
                        gravity = Gravity.CENTER_VERTICAL
                        addView(tv("${market?.emoji ?: "🪙"} ${item.symbol}", 13f, white, bold = true).apply { layoutParams = llp(0, wrap, 2f) })
                        addView(tv(data?.let { if (it.price > 1) "$%.2f".format(it.price) else "$%.6f".format(it.price) } ?: "—", 12f, white, mono = true).apply { layoutParams = llp(0, wrap, 2f) })
                        addView(tv("${if (change >= 0) "+" else ""}%.1f%%".format(change), 12f, if (change >= 0) green else red, mono = true).apply { gravity = Gravity.END; layoutParams = llp(0, wrap, 1f) })
                        addView(tv("✕", 14f, red).apply { setPadding(16, 0, 8, 0); setOnClickListener { WatchlistEngine.removeFromWatchlist(item.symbol); selectTab(1) } })
                        if (market != null && data != null) setOnClickListener { showSignalDetail(market, data, if (change > 3) "🟢 BUY" else if (change < -3) "🔴 SELL" else "🟡 HOLD", CryptoAltScannerAI.getAltBeta(market.symbol)) }
                    }
                    llContent.addView(row); llContent.addView(thinDivider())
                }
            }
        }
    }

    // ─── POSITIONS ───────────────────────────────────────────────────────────

    private fun buildPositionsTab() {
        val allPos    = CryptoAltTrader.getAllPositions()
        val openPos   = allPos.filter { it.closeTime == null }
        val closedPos = allPos.filter { it.closeTime != null }.take(20)
        val totalPnl  = allPos.sumOf { it.getPnlSol() }

        addSectionHeader("📂 Open Positions (${openPos.size})", amber)
        if (openPos.isEmpty()) addEmptyState("No open positions")
        else openPos.forEach { addPositionRow(it, true) }

        addSectionHeader("📜 Recent Closed (${closedPos.size})", muted)
        if (closedPos.isEmpty()) addEmptyState("No closed positions yet")
        else closedPos.forEach { addPositionRow(it, false) }

        findViewById<TextView>(R.id.tvCryptoAltStats)?.text =
            "${openPos.size} open | PnL: ${if (totalPnl >= 0) "+" else ""}%.4f◎".format(totalPnl)
    }

    private fun addPositionRow(pos: CryptoAltTrader.AltPosition, isOpen: Boolean) {
        val pnlPct = pos.getPnlPct()
        val tile = vBox(card, 16, 12).apply {
            layoutParams = llp(match, wrap).apply { bottomMargin = 2 }
        }
        val header = hBox().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(tv("${pos.market.emoji} ${pos.market.symbol}  ${pos.direction.emoji} ${pos.leverageLabel}", 13f, white, bold = true).apply { layoutParams = llp(0, wrap, 1f) })
        header.addView(tv("${if (pnlPct >= 0) "+" else ""}%.2f%%".format(pnlPct), 13f, if (pnlPct >= 0) green else red, mono = true))
        tile.addView(header)
        tile.addView(tv("Entry: %.6f  Now: %.6f  Size: %.4f◎".format(pos.entryPrice, pos.currentPrice, pos.sizeSol), 10f, muted, mono = true))
        if (isOpen) tile.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("${pos.market.emoji} ${pos.market.symbol} — ${pos.direction.emoji} ${pos.leverageLabel}")
                .setMessage("Size: %.4f◎\nEntry: %.6f\nCurrent: %.6f\nP&L: ${if (pnlPct >= 0) "+" else ""}%.2f%%\nOpened: %s".format(
                    pos.sizeSol, pos.entryPrice, pos.currentPrice, pnlPct, sdf.format(Date(pos.openTime))))
                .setPositiveButton("Close Position") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        CryptoAltTrader.requestClose(pos.id)
                        withContext(Dispatchers.Main) { selectTab(2) }
                    }
                }
                .setNegativeButton("Cancel", null).show()
        }
        llContent.addView(tile); llContent.addView(thinDivider())
    }

    // ─── SETTINGS ─────────────────────────────────────────────────────────────

    private fun buildSettingsTab() {
        addSectionHeader("⚙️ Crypto Alts Settings", 0xFF9CA3AF.toInt())

        addToggleRow("🤖 Alt Trader Running", CryptoAltTrader.isRunning()) { on ->
            if (on) CryptoAltTrader.start() else CryptoAltTrader.stop(); selectTab(3) }
        addToggleRow("💰 Live Mode (real money)", CryptoAltTrader.isLiveMode()) { on ->
            if (on) AlertDialog.Builder(this)
                .setTitle("⚠️ Enable Live Trading?")
                .setMessage("This will use REAL SOL. Only enable when win rate > 55%.")
                .setPositiveButton("Enable Live") { _, _ -> CryptoAltTrader.setLiveMode(true); selectTab(3) }
                .setNegativeButton("Cancel") { _, _ -> selectTab(3) }.show()
            else { CryptoAltTrader.setLiveMode(false); selectTab(3) }
        }

        addSectionHeader("📊 Performance", purple)
        addInfoRow("Balance",     "◎${"%.4f".format(CryptoAltTrader.getBalance())}")
        addInfoRow("Total PnL",   "${if (CryptoAltTrader.getTotalPnlSol() >= 0) "+" else ""}${"%.4f".format(CryptoAltTrader.getTotalPnlSol())}◎")
        addInfoRow("Win Rate",    "${CryptoAltTrader.getWinRate().toInt()}%")
        addInfoRow("Total Trades","${CryptoAltTrader.getTotalTrades()}")
        addInfoRow("Open Pos",    "${CryptoAltTrader.getAllPositions().count { it.closeTime == null }}")
        addInfoRow("Phase",       getPhaseLabel())

        addSectionHeader("🧠 Fluid Learning", blue)
        addInfoRow("V3 Trades",        "${FluidLearningAI.getTotalTradeCount()}")
        addInfoRow("Markets Trades",   "${FluidLearningAI.getMarketsTradeCount()}")
        addInfoRow("V3 Progress",      "${(FluidLearningAI.getLearningProgress() * 100).toInt()}%")
        addInfoRow("Mkts Progress",    "${(FluidLearningAI.getMarketsLearningProgress() * 100).toInt()}%")
        addInfoRow("Conf Boost",       "+${(FluidLearningAI.getBootstrapConfidenceBoost() * 100).toInt()}%")

        addSectionHeader("📈 30-Day Run", teal)
        addInfoRow("Day",        if (RunTracker30D.isRunActive()) "Day ${RunTracker30D.getCurrentDay()} / 30" else "Not started")
        addInfoRow("Trades",     "${RunTracker30D.totalTrades}")
        addInfoRow("Win Rate",   if (RunTracker30D.totalTrades > 0) "${(RunTracker30D.wins.toDouble() / RunTracker30D.totalTrades * 100).toInt()}%" else "—")
        addInfoRow("Realized PnL","${if (RunTracker30D.totalRealizedPnlSol >= 0) "+" else ""}${"%.4f".format(RunTracker30D.totalRealizedPnlSol)}◎")
        addInfoRow("Max Drawdown","${"%.1f".format(RunTracker30D.maxDrawdown)}%")
        addInfoRow("Integrity",   if (RunTracker30D.isRunActive()) "${RunTracker30D.integrityScore()}/100" else "—")

        addSectionHeader("👁️ Shadow / FDG", indigo)
        val shadow = ShadowLearningEngine.getBlockedTradeStats()
        addInfoRow("Tracked",     "${shadow.totalTracked}")
        addInfoRow("Would Win",   "${shadow.wouldHaveWon}")
        addInfoRow("Would Lose",  "${shadow.wouldHaveLost}")
        addInfoRow("Top Mode",    ShadowLearningEngine.getTopTrackedMode() ?: "—")

        addSectionHeader("🌐 Alt Intel", orange)
        addInfoRow("Dominance",   CryptoAltScannerAI.getDominanceCycleSignal())
        addInfoRow("Fear & Greed","${CryptoAltScannerAI.getCryptoFearGreed()}")
        addInfoRow("Total Alts",  "${altMarkets.size} tokens")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DIALOGS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun showAddToWatchlistDialog() {
        val labels = altMarkets.map { "${it.emoji} ${it.symbol}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Add Alt to Watchlist")
            .setItems(labels) { _, which -> addSymbolToWatchlist(altMarkets[which].symbol) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun addSymbolToWatchlist(symbol: String) {
        val added = WatchlistEngine.addToWatchlist(symbol)
        Toast.makeText(this, if (added) "✅ $symbol added" else "$symbol already in watchlist", Toast.LENGTH_SHORT).show()
        if (currentTab == 1) selectTab(1)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UI FACTORY HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildTile(accentColor: Int, title: String, badge: String, badgeColor: Int): LinearLayout {
        return vBox(card, 16, 14).apply {
            // Accent line
            addView(View(this@CryptoAltActivity).apply {
                setBackgroundColor(accentColor)
                layoutParams = llp(match, 2).apply { bottomMargin = 8 }
            })
            // Header row
            val header = hBox().apply { gravity = Gravity.CENTER_VERTICAL }
            header.addView(tv(title, 13f, white, bold = true).apply { layoutParams = llp(0, wrap, 1f) })
            header.addView(tv(badge, 9f, badgeColor, bold = true).apply {
                setBackgroundColor(0xFF0D0D1A.toInt()); setPadding(6, 3, 6, 3)
            })
            addView(header)
        }
    }

    private fun addStatChip(parent: LinearLayout, label: String, value: String, valueColor: Int, weight: Float) {
        parent.addView(vBox(0xFF111128.toInt(), 8, 6).apply {
            layoutParams = llp(0, wrap, weight).apply { marginEnd = 3 }
            gravity = Gravity.CENTER
            addView(tv(value, 12f, valueColor, mono = true).apply { gravity = Gravity.CENTER })
            addView(tv(label, 8f, muted).apply { gravity = Gravity.CENTER })
        })
    }

    private fun addSectionHeader(text: String, color: Int) {
        llContent.addView(tv(text, 12f, color, bold = true).apply { setPadding(16, 14, 16, 6) })
    }

    private fun addEmptyState(msg: String) {
        llContent.addView(tv(msg, 13f, muted).apply {
            gravity = Gravity.CENTER; setPadding(16, 32, 16, 32)
            layoutParams = llp(match, wrap)
        })
    }

    private fun addDivider() {
        llContent.addView(View(this).apply {
            setBackgroundColor(divBg)
            layoutParams = llp(match, 1).apply { topMargin = 8; bottomMargin = 8 }
        })
    }

    private fun thinDivider() = View(this).apply {
        setBackgroundColor(divBg); layoutParams = llp(match, 1)
    }

    private fun divLine() = View(this).apply {
        setBackgroundColor(divBg)
        layoutParams = llp(match, 1).apply { topMargin = 6; bottomMargin = 4 }
    }

    private fun addToggleRow(label: String, current: Boolean, onChange: (Boolean) -> Unit) {
        llContent.addView(hBox(card, 16, 12).apply {
            layoutParams = llp(match, wrap).apply { bottomMargin = 2 }
            gravity = Gravity.CENTER_VERTICAL
            addView(tv(label, 13f, white).apply { layoutParams = llp(0, wrap, 1f) })
            addView(Switch(this@CryptoAltActivity).apply { isChecked = current; setOnCheckedChangeListener { _, v -> onChange(v) } })
        })
        llContent.addView(thinDivider())
    }

    private fun addInfoRow(label: String, value: String) {
        llContent.addView(hBox(card, 16, 10).apply {
            layoutParams = llp(match, wrap).apply { bottomMargin = 2 }
            gravity = Gravity.CENTER_VERTICAL
            addView(tv(label, 13f, muted).apply { layoutParams = llp(0, wrap, 1f) })
            addView(tv(value, 13f, white, mono = true))
        })
        llContent.addView(thinDivider())
    }

    private fun progressBar(color: Int, pct: Int) = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100; progress = pct
        progressTintList = android.content.res.ColorStateList.valueOf(color)
        layoutParams = llp(match, 6).apply { topMargin = 2; bottomMargin = 2 }
    }

    // ─── View factories ──────────────────────────────────────────────────────

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        if (mono) typeface = android.graphics.Typeface.MONOSPACE
    }

    private fun hBox(bg: Int = 0, padH: Int = 0, padV: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        if (bg != 0) setBackgroundColor(bg)
        if (padH > 0 || padV > 0) setPadding(padH, padV, padH, padV)
        layoutParams = llp(match, wrap)
    }

    private fun vBox(bg: Int = 0, padH: Int = 0, padV: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (bg != 0) setBackgroundColor(bg)
        if (padH > 0 || padV > 0) setPadding(padH, padV, padH, padV)
        layoutParams = llp(match, wrap).apply { bottomMargin = 3 }
    }

    // ─── Layout params ────────────────────────────────────────────────────────

    private val match = LinearLayout.LayoutParams.MATCH_PARENT
    private val wrap  = LinearLayout.LayoutParams.WRAP_CONTENT

    private fun llp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    // ─── Phase helpers ────────────────────────────────────────────────────────

    private fun getPhaseLabel(): String {
        val trades = FluidLearningAI.getTotalTradeCount()
        val wr     = CryptoAltTrader.getWinRate()
        return when {
            trades < 500  -> "BOOTSTRAP"
            trades < 1500 -> "LEARNING"
            trades < 3000 -> "VALIDATING"
            trades < 5000 -> "MATURING"
            wr >= 55.0    -> "READY"
            else          -> "MATURING"
        }
    }

    private fun phaseColor(phase: String) = when (phase) {
        "BOOTSTRAP"  -> muted
        "LEARNING"   -> blue
        "VALIDATING" -> amber
        "MATURING"   -> orange
        "READY"      -> green
        else         -> muted
    }
}

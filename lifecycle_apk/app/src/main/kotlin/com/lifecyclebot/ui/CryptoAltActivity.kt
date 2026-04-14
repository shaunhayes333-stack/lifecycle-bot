package com.lifecyclebot.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.CircleCropTransformation
import com.lifecyclebot.R
import com.lifecyclebot.engine.RunTracker30D
import com.lifecyclebot.engine.ShadowLearningEngine
import com.lifecyclebot.engine.WalletManager
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
 * CRYPTO ALTS ACTIVITY — V3.0  (Mem Trader UI Parity)
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Full feature parity with MainActivity mem trader UI:
 *   • Token logos via CoinGecko CDN (circle-cropped, placeholder fallback)
 *   • Rich position cards: colour bar, USD value, TP/SL, entry time
 *   • Live price refresh from PerpsMarketDataFetcher every 15s
 *   • "X at risk" header showing total open exposure
 *   • Closed positions with realised PnL in SOL + USD
 *
 * All trading mode AIs are genuinely wired in:
 *   ShitCoinTraderAI · QualityTraderAI · BlueChipTraderAI · ShitCoinExpress
 *   MoonshotTraderAI · ManipulatedTraderAI · FluidLearningAI
 *   RunTracker30D · ShadowLearningEngine · CryptoAltScannerAI
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
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)

    // ─── Views ───────────────────────────────────────────────────────────────
    private lateinit var progressBar : ProgressBar
    private lateinit var llContent   : LinearLayout
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
    // AUTO REFRESH — 15s: hero stats + live prices in positions
    // ═══════════════════════════════════════════════════════════════════════════

    private fun startAutoRefresh() {
        lifecycleScope.launch {
            while (isActive) {
                delay(15_000L)
                withContext(Dispatchers.Main) {
                    if (::tvHeroBalance.isInitialized) refreshHeroStats()
                    // Refresh positions tab in place when it is showing
                    if (currentTab == 2) selectTab(2)
                }
            }
        }
    }

    private fun refreshHeroStats() {
        val bal    = CryptoAltTrader.getBalance()
        val pnl    = CryptoAltTrader.getTotalPnlSol()
        val wr     = CryptoAltTrader.getWinRate()
        val trades = CryptoAltTrader.getTotalTrades()
        val phase  = getPhaseLabel()
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

        val statsRow = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 4 } }
        tvHeroPnl = tv("${if (pnl >= 0) "+" else ""}${"%.4f".format(pnl)} SOL", 14f,
            if (pnl >= 0) green else red, mono = true).apply { layoutParams = llp(0, wrap, 1f) }
        tvHeroWinRate = tv("${"%.1f".format(wr)}% WR", 12f, purple, mono = true).apply { layoutParams = llp(0, wrap, 1f) }
        tvHeroTrades  = tv("$trades trades", 12f, muted, mono = true)
        statsRow.addView(tvHeroPnl); statsRow.addView(tvHeroWinRate); statsRow.addView(tvHeroTrades)
        tile.addView(statsRow)

        tvHeroPhase = tv(phase, 10f, phaseColor(phase), bold = true).apply {
            layoutParams = llp(match, wrap).apply { topMargin = 4 }
        }
        tile.addView(tvHeroPhase)

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
    // LIVE READINESS TILE
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildReadinessTile() {
        val trades   = FluidLearningAI.getTotalTradeCount()
        val mTrades  = FluidLearningAI.getMarketsTradeCount()
        val boost    = FluidLearningAI.getBootstrapConfidenceBoost()
        val sizeMult = FluidLearningAI.getAdaptiveSizeMultiplier()
        val phase    = getPhaseLabel()

        val tile = buildTile(phaseColor(phase), "🚦 Live Readiness", phase, phaseColor(phase))
        tile.addView(progressBar(phaseColor(phase), (FluidLearningAI.getLearningProgress() * 100).toInt()))
        val row = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(row, "V3 Trades",  "$trades",    white,  1f)
        addStatChip(row, "Mkts Trades","$mTrades",   teal,   1f)
        addStatChip(row, "Conf Boost", "+${(boost*100).toInt()}%", purple, 1f)
        addStatChip(row, "Size Mult",  "${"%.2f".format(sizeMult)}x", amber, 1f)
        tile.addView(row)
        llContent.addView(tile)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PROOF RUN TILE
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildProofRunTile() {
        val isActive  = RunTracker30D.isRunActive()
        val day       = if (isActive) RunTracker30D.getCurrentDay() else 0
        val total     = RunTracker30D.totalTrades
        val wr        = if (total > 0) RunTracker30D.wins.toDouble() / total * 100 else 0.0
        val pnl       = RunTracker30D.totalRealizedPnlSol
        val integrity = if (isActive) RunTracker30D.integrityScore() else 0
        val startBal  = RunTracker30D.startBalance
        val currBal   = CryptoAltTrader.getBalance()
        val peak      = RunTracker30D.peakBalance
        val dd        = RunTracker30D.maxDrawdown

        val badge = if (isActive) "Day $day / 30" else "NOT STARTED"
        val tile  = buildTile(teal, "📈 30-Day Proof Run", badge, if (isActive) teal else muted)

        val row1 = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(row1, "Trades",    "$total",             white, 1f)
        addStatChip(row1, "Win Rate",  "${"%.1f".format(wr)}%", if (wr >= 55) green else amber, 1f)
        addStatChip(row1, "Realized",  "${if (pnl >= 0) "+" else ""}${"%.3f".format(pnl)}◎", if (pnl >= 0) green else red, 1f)
        addStatChip(row1, "Integrity", "$integrity/100",     if (integrity >= 80) green else amber, 1f)
        tile.addView(row1)

        val row2 = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 3 } }
        addStatChip(row2, "Start",     "◎${"%.2f".format(startBal)}", muted,  1f)
        addStatChip(row2, "Current",   "◎${"%.2f".format(currBal)}", white,  1f)
        addStatChip(row2, "Peak",      "◎${"%.2f".format(peak)}",    green,  1f)
        addStatChip(row2, "Max DD",    "${"%.1f".format(dd)}%",       red,    1f)
        tile.addView(row2)
        llContent.addView(tile)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TRADER MODE TILES
    // ═══════════════════════════════════════════════════════════════════════════

    // SHITCOIN TILE
    private fun buildShitCoinTile() {
        val stats    = ShitCoinTraderAI.getStats()
        val mode     = ShitCoinTraderAI.getMode()
        val paper    = ShitCoinTraderAI.isPaperMode()
        val bal      = stats.balance
        val pnl      = stats.dailyPnlSol
        val wr       = stats.winRatePct
        val openPos  = stats.activePositions
        val modeEmoji= when (mode) { "HUNTING" -> "🎯"; "POSITIONED" -> "📊"; else -> "💤" }

        val tile = buildTile(red, "💩 ShitCoin Degen", "$modeEmoji $mode", if (mode == "HUNTING") orange else muted)
        val row = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(row, "Balance",  "◎${"%.3f".format(bal)}", white,  1f)
        addStatChip(row, "Daily PnL","${if (pnl >= 0) "+" else ""}${"%.3f".format(pnl)}◎", if (pnl >= 0) green else red, 1f)
        addStatChip(row, "Win Rate", "${"%.1f".format(wr)}%", if (wr >= 55) green else amber, 1f)
        addStatChip(row, "Open",     "$openPos pos", blue, 1f)
        tile.addView(row)

        val row2 = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 3 } }
        addStatChip(row2, "Score Min",  "${stats.minScoreThreshold}", muted,  1f)
        addStatChip(row2, "Conf Min",   "${stats.minConfThreshold}",  muted,  1f)
        addStatChip(row2, "Rugged Devs","${stats.ruggedDevsCount}",   red,    1f)
        addStatChip(row2, "Mode",       if (paper) "PAPER" else "LIVE", if (paper) amber else green, 1f)
        tile.addView(row2)
        llContent.addView(tile)
    }

    // QUALITY TILE
    private fun buildQualityTile() {
        val wr      = QualityTraderAI.getWinRate()
        val dailyPnl= QualityTraderAI.getDailyPnl()
        val openPos = QualityTraderAI.getActivePositions().size
        val tp      = QualityTraderAI.getFluidTakeProfit()
        val sl      = QualityTraderAI.getFluidStopLoss()

        val tile = buildTile(teal, "💎 Quality Mode", "MCap \$100K–\$1M", teal)
        val row = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(row, "Win Rate",  "${"%.1f".format(wr)}%",               if (wr >= 55) green else amber, 1f)
        addStatChip(row, "Daily PnL", "${if (dailyPnl >= 0) "+" else ""}${"%.3f".format(dailyPnl)}◎", if (dailyPnl >= 0) green else red, 1f)
        addStatChip(row, "Open Pos",  "$openPos", blue, 1f)
        addStatChip(row, "TP / SL",   "+${tp.toInt()}% / -${sl.toInt()}%", muted, 1f)
        tile.addView(row)
        llContent.addView(tile)
    }

    // BLUE CHIP TILE
    private fun buildBlueChipTile() {
        val stats   = BlueChipTraderAI.getStats()
        val wr      = BlueChipTraderAI.getWinRatePct()
        val openPos = stats.activePositions
        val bal     = BlueChipTraderAI.getCurrentBalance()
        val pnl     = BlueChipTraderAI.getDailyPnlSol()
        val tp      = BlueChipTraderAI.getFluidTakeProfit()
        val sl      = BlueChipTraderAI.getFluidStopLoss()

        val tile = buildTile(blue, "🔵 Blue Chip", "MCap \$1M+", blue)
        val row = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(row, "Balance",  "◎${"%.3f".format(bal)}", white,  1f)
        addStatChip(row, "Daily PnL","${if (pnl >= 0) "+" else ""}${"%.3f".format(pnl)}◎", if (pnl >= 0) green else red, 1f)
        addStatChip(row, "Win Rate", "${"%.1f".format(wr)}%", if (wr >= 55) green else amber, 1f)
        addStatChip(row, "Open",     "$openPos pos", blue, 1f)
        tile.addView(row)

        val row2 = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 3 } }
        addStatChip(row2, "Score Min", "${BlueChipTraderAI.getFluidScoreThreshold()}", muted, 1f)
        addStatChip(row2, "TP / SL",   "+${tp.toInt()}% / -${sl.toInt()}%", muted, 1f)
        addStatChip(row2, "Positions", "${openPos}", blue, 1f)
        addStatChip(row2, "Fluid",     "ON", teal, 1f)
        tile.addView(row2)
        llContent.addView(tile)
    }

    // EXPRESS TILE
    private fun buildExpressTile() {
        val stats  = ShitCoinExpress.getStats()
        val wr     = stats.winRatePct
        val pnl    = stats.dailyPnlSol
        val openPos= stats.activePositions

        val tile = buildTile(orange, "⚡ Express Mode", "High Velocity", orange)
        val row = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(row, "Win Rate", "${"%.1f".format(wr)}%", if (wr >= 55) green else amber, 1f)
        addStatChip(row, "Daily PnL","${if (pnl >= 0) "+" else ""}${"%.3f".format(pnl)}◎", if (pnl >= 0) green else red, 1f)
        addStatChip(row, "Open Pos", "$openPos", blue, 1f)
        addStatChip(row, "Speed",    "FAST", orange, 1f)
        tile.addView(row)
        llContent.addView(tile)
    }

    // MOONSHOT TILE
    private fun buildMoonshotTile() {
        val wr       = MoonshotTraderAI.getWinRatePct()
        val pnl      = MoonshotTraderAI.getDailyPnlSol()
        val openPos  = MoonshotTraderAI.getActivePositions().size
        val tenX     = MoonshotTraderAI.getTenXCount()
        val hundredX = MoonshotTraderAI.getHundredXCount()

        val tile = buildTile(purple, "🌙 Moonshot", "10x / 100x Hunters", purple)
        val row = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(row, "Win Rate", "${"%.1f".format(wr)}%", if (wr >= 55) green else amber, 1f)
        addStatChip(row, "Daily PnL","${if (pnl >= 0) "+" else ""}${"%.3f".format(pnl)}◎", if (pnl >= 0) green else red, 1f)
        addStatChip(row, "Open Pos", "$openPos", blue, 1f)
        addStatChip(row, "10x Hits", "$tenX", purple, 1f)
        tile.addView(row)

        val row2 = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 3 } }
        addStatChip(row2, "100x Hits", "$hundredX", pink, 1f)
        addStatChip(row2, "Mode", "RIDING", purple, 1f)
        addStatChip(row2, "Target",  "10x+", amber, 1f)
        addStatChip(row2, "Filter",  "Moonshots", muted, 1f)
        tile.addView(row2)
        llContent.addView(tile)
    }

    // MANIP TILE
    private fun buildManipTile() {
        val stats   = ManipulatedTraderAI.getStats()
        val wr      = stats.winRatePct
        val pnl     = stats.dailyPnlSol
        val openPos = stats.activePositions
        val caught  = stats.totalManipCaught

        val tile = buildTile(pink, "🎭 Manip Catch", "Manipulation Detector", pink)
        val row = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(row, "Win Rate", "${"%.1f".format(wr)}%", if (wr >= 55) green else amber, 1f)
        addStatChip(row, "Daily PnL","${if (pnl >= 0) "+" else ""}${"%.3f".format(pnl)}◎", if (pnl >= 0) green else red, 1f)
        addStatChip(row, "Open Pos", "$openPos", blue, 1f)
        addStatChip(row, "Caught",   "$caught", pink, 1f)
        tile.addView(row)
        llContent.addView(tile)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SHADOW / FDG PANEL
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildShadowFDGPanel() {
        val shadow  = ShadowLearningEngine.getBlockedTradeStats()
        val topMode = ShadowLearningEngine.getTopTrackedMode() ?: "—"

        val tile = buildTile(indigo, "👁️ Shadow FDG", "Blocked Trade Analysis", indigo)
        val row = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(row, "Tracked",   "${shadow.totalTracked}", white, 1f)
        addStatChip(row, "Would Win", "${shadow.wouldHaveWon}", green, 1f)
        addStatChip(row, "Would Lose","${shadow.wouldHaveLost}", red,  1f)
        addStatChip(row, "Top Mode",  topMode, indigo, 1f)
        tile.addView(row)
        llContent.addView(tile)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HIVE MIND PANEL
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildHiveMindPanel() {
        val tile = buildTile(amber, "🐝 Hive Mind", "Cross-Wallet Intelligence", amber)
        tile.addView(tv("Consensus signals from aggregated wallet behaviour", 11f, muted))
        llContent.addView(tile)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SECTOR HEAT PANEL
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildSectorHeatPanel() {
        val sectors  = CryptoAltScannerAI.getSectorHeatmap()
        val dominant = CryptoAltScannerAI.getDominanceCycleSignal()
        val fg       = CryptoAltScannerAI.getCryptoFearGreed()

        val tile = buildTile(teal, "🌡️ Sector Heat", dominant, teal)

        val fgRow = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(fgRow, "Fear & Greed", "$fg", if (fg > 60) green else if (fg < 40) red else amber, 1f)
        addStatChip(fgRow, "Dominance", dominant, teal, 3f)
        tile.addView(fgRow)

        if (sectors.isNotEmpty()) {
            val sectorRow = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 4 } }
            sectors.entries.take(4).forEach { (name, heat) ->
                val col = when {
                    heat > 0.7 -> green
                    heat > 0.4 -> amber
                    else       -> red
                }
                addStatChip(sectorRow, name.take(6), "${"%.0f".format(heat * 100)}%", col, 1f)
            }
            tile.addView(sectorRow)
        }
        llContent.addView(tile)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LIVE SIGNAL CARDS (top movers)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildSignalCards() {
        addSectionHeader("⚡ Live Alt Signals", amber)
        lifecycleScope.launch(Dispatchers.IO) {
            val results = altMarkets.mapNotNull { market ->
                val data = PerpsMarketDataFetcher.getCachedPrice(market) ?: return@mapNotNull null
                Pair(market, data)
            }.toMutableList()
            results.sortByDescending { kotlin.math.abs(it.second.priceChange24hPct) }

            withContext(Dispatchers.Main) {
                results.take(8).forEach { (market, data) ->
                    llContent.addView(buildSignalCard(market, data))
                    llContent.addView(thinDivider())
                }
            }
        }
    }

    private fun buildSignalCard(market: PerpsMarket, data: PerpsMarketData): LinearLayout {
        val change  = data.priceChange24hPct
        val col     = if (change >= 0) green else red
        val arrow   = if (change >= 0) "▲" else "▼"

        val row = hBox(card, 16, 10).apply { gravity = Gravity.CENTER_VERTICAL }

        // Token logo
        val logoImg = android.widget.ImageView(this).apply {
            val size = (36 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).also { it.marginEnd = 10 }
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            try { background = androidx.core.content.ContextCompat.getDrawable(this@CryptoAltActivity, R.drawable.token_logo_bg) } catch (_: Exception) {}
            val logoUrl = getCoinGeckoLogoUrl(market.symbol)
            load(logoUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_token_placeholder)
                error(R.drawable.ic_token_placeholder)
                allowHardware(false)
                transformations(CircleCropTransformation())
            }
        }
        row.addView(logoImg)

        row.addView(tv("${market.emoji} ${market.symbol}", 13f, white, bold = true).apply { layoutParams = llp(0, wrap, 1f) })
        row.addView(tv("$arrow ${"%.1f".format(kotlin.math.abs(change))}%", 12f, col, mono = true).apply { layoutParams = llp(0, wrap, 2f) })
        row.addView(tv(if (data.price > 1) "$%.2f".format(data.price) else "$%.5f".format(data.price), 11f, white, mono = true).apply { layoutParams = llp(0, wrap, 2f) })

        row.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val fresh = PerpsMarketDataFetcher.getMarketData(market)
                val change2 = fresh.priceChange24hPct
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@CryptoAltActivity)
                        .setTitle("${market.emoji} ${market.symbol} — ${market.displayName}")
                        .setMessage(
                            "Price:       ${if (fresh.price > 1) "$%.2f".format(fresh.price) else "$%.6f".format(fresh.price)}\n" +
                            "24h Change:  ${if (change2 >= 0) "+" else ""}${"%.2f".format(change2)}%\n" +
                            "Volume:      ${"%.2f".format(fresh.volume24h / 1_000_000)}M\n" +
                            "Market:      ${market.displayName}\n" +
                            "Max Lev:     ${market.maxLeverage.toInt()}x\n" +
                            "Hours:       ${market.tradingHours}"
                        )
                        .setNeutralButton("Close", null).show()
                }
            }
        }
        return row
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TAB ROUTER
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildTabContent() {
        val tabBar = hBox(0xFF0D0D1A.toInt(), 0, 0)
        tabBar.layoutParams = llp(match, wrap).apply { topMargin = 4; bottomMargin = 4 }

        listOf(tabScanner, tabWatchlist, tabPositions, tabSettings).forEachIndexed { i, tv ->
            tv.setBackgroundColor(if (i == currentTab) card else 0)
            tv.setTextColor(if (i == currentTab) white else muted)
        }

        llContent.addView(tabBar)

        when (currentTab) {
            0    -> buildScannerTab()
            1    -> buildWatchlistTab()
            2    -> buildPositionsTab()
            else -> buildSettingsTab()
        }
    }

    private fun selectTab(index: Int) {
        currentTab = index
        buildFullDashboard()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SCANNER TAB
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildScannerTab() {
        addSectionHeader("🔍 Full Alt Scanner (${altMarkets.size} markets)", white)
        lifecycleScope.launch(Dispatchers.IO) {
            val results = altMarkets.mapNotNull { market ->
                val data = PerpsMarketDataFetcher.getCachedPrice(market) ?: return@mapNotNull null
                Pair(market, data)
            }.toMutableList()
            results.sortByDescending { kotlin.math.abs(it.second.priceChange24hPct) }

            withContext(Dispatchers.Main) {
                results.forEach { (market, data) ->
                    val change = data.priceChange24hPct
                    val col    = if (change >= 0) green else red
                    val row    = hBox(card, 16, 10).apply { gravity = Gravity.CENTER_VERTICAL }

                    // Token logo
                    val logoImg = android.widget.ImageView(this@CryptoAltActivity).apply {
                        val sz = (32 * resources.displayMetrics.density).toInt()
                        layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = 8 }
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        try { background = androidx.core.content.ContextCompat.getDrawable(this@CryptoAltActivity, R.drawable.token_logo_bg) } catch (_: Exception) {}
                        load(getCoinGeckoLogoUrl(market.symbol)) {
                            crossfade(true); placeholder(R.drawable.ic_token_placeholder)
                            error(R.drawable.ic_token_placeholder); allowHardware(false)
                            transformations(CircleCropTransformation())
                        }
                    }
                    row.addView(logoImg)

                    row.addView(tv("${market.emoji} ${market.symbol}", 12f, white, bold = true).apply { layoutParams = llp(0, wrap, 2f) })
                    row.addView(tv("${if (change >= 0) "+" else ""}${"%.1f".format(change)}%", 12f, col, mono = true).apply { layoutParams = llp(0, wrap, 2f) })
                    row.addView(tv(if (data.price > 1) "$%.2f".format(data.price) else "$%.6f".format(data.price), 12f, white, mono = true).apply { layoutParams = llp(0, wrap, 2f) })

                    llContent.addView(row)
                    llContent.addView(thinDivider())
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WATCHLIST TAB
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildWatchlistTab() {
        val symbols = WatchlistEngine.getWatchlist()
        addSectionHeader("👀 Watchlist (${symbols.size})", blue)

        if (symbols.isEmpty()) {
            addEmptyState("No alts in watchlist. Tap ＋ to add.")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            symbols.forEach { symbol ->
                val market = altMarkets.firstOrNull { it.symbol == symbol } ?: return@forEach
                val data   = PerpsMarketDataFetcher.getCachedPrice(market)

                withContext(Dispatchers.Main) {
                    val change = data?.priceChange24hPct ?: 0.0
                    val col    = if (change >= 0) green else red
                    val row    = hBox(card, 16, 10).apply { gravity = Gravity.CENTER_VERTICAL }

                    val logoImg = android.widget.ImageView(this@CryptoAltActivity).apply {
                        val sz = (32 * resources.displayMetrics.density).toInt()
                        layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = 8 }
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        try { background = androidx.core.content.ContextCompat.getDrawable(this@CryptoAltActivity, R.drawable.token_logo_bg) } catch (_: Exception) {}
                        load(getCoinGeckoLogoUrl(symbol)) {
                            crossfade(true); placeholder(R.drawable.ic_token_placeholder)
                            error(R.drawable.ic_token_placeholder); allowHardware(false)
                            transformations(CircleCropTransformation())
                        }
                    }
                    row.addView(logoImg)

                    row.addView(tv("${market.emoji} $symbol", 13f, white, bold = true).apply { layoutParams = llp(0, wrap, 2f) })
                    row.addView(tv("${if (change >= 0) "+" else ""}${"%.1f".format(change)}%", 12f, col, mono = true).apply { layoutParams = llp(0, wrap, 2f) })
                    row.addView(tv(data?.let { if (it.price > 1) "$%.2f".format(it.price) else "$%.6f".format(it.price) } ?: "—", 12f, white, mono = true).apply { layoutParams = llp(0, wrap, 2f) })

                    val removeBtn = tv("✕", 12f, red).apply {
                        setPadding(8, 4, 8, 4)
                        setOnClickListener { WatchlistEngine.removeFromWatchlist(symbol); selectTab(1) }
                    }
                    row.addView(removeBtn)

                    llContent.addView(row)
                    llContent.addView(thinDivider())
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POSITIONS TAB — Full mem-trader parity
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildPositionsTab() {
        val allPos    = CryptoAltTrader.getAllPositions()
        val openPos   = allPos.filter { it.closeTime == null }
        val closedPos = allPos.filter { it.closeTime != null }.take(20)
        val totalRisk = openPos.sumOf { it.sizeSol }
        val totalPnl  = allPos.sumOf { it.getPnlSol() }
        val solPrice  = WalletManager.lastKnownSolPrice

        // Summary header
        val summaryRow = hBox(card2, 16, 12).apply { gravity = Gravity.CENTER_VERTICAL }
        summaryRow.addView(tv("Open Positions", 14f, white, bold = true).apply { layoutParams = llp(0, wrap, 1f) })
        summaryRow.addView(tv("${"%.3f".format(totalRisk)}◎ at risk", 11f, muted, mono = true).apply { layoutParams = llp(0, wrap, 0f).apply { marginEnd = 8 } })
        val pnlColor = if (totalPnl >= 0) green else red
        summaryRow.addView(tv("${if (totalPnl >= 0) "+" else ""}${"%.4f".format(totalPnl)}◎", 12f, pnlColor, mono = true))
        llContent.addView(summaryRow)
        llContent.addView(thinDivider())

        if (openPos.isEmpty()) {
            addEmptyState("No open positions")
        } else {
            openPos.forEach { pos -> addRichPositionRow(pos, isOpen = true, solPrice = solPrice) }
        }

        // Closed positions
        llContent.addView(tv("📜 Recent Closed (${closedPos.size})", 12f, muted, bold = true).apply { setPadding(16, 14, 16, 6) })
        if (closedPos.isEmpty()) {
            addEmptyState("No closed positions yet")
        } else {
            closedPos.forEach { pos -> addRichPositionRow(pos, isOpen = false, solPrice = solPrice) }
        }

        // Update stats bar
        findViewById<TextView>(R.id.tvCryptoAltStats)?.text =
            "${openPos.size} open | PnL: ${if (totalPnl >= 0) "+" else ""}%.4f◎".format(totalPnl)
    }

    /**
     * Rich position card — matches mem trader UI exactly:
     *  [Logo] [Colour bar] [Symbol + direction + leverage]  [PnL %]
     *                      [Entry price · Time]             [PnL SOL]
     *                      [Size + TP/SL]                   [≈$USD]
     */
    private fun addRichPositionRow(pos: CryptoAltTrader.AltPosition, isOpen: Boolean, solPrice: Double) {
        val pnlPct   = pos.getPnlPct()
        val pnlSol   = pos.getPnlSol()
        val pnlColor = if (pnlPct >= 0) green else red
        val posValue = pos.sizeSol + pnlSol
        val valueUsd = posValue * solPrice

        // Outer card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(this@CryptoAltActivity.card)
            setPadding(0, 12, 16, 12)
            layoutParams = llp(match, wrap).apply { bottomMargin = 2 }
        }

        val row = hBox().apply { gravity = Gravity.CENTER_VERTICAL }

        // Token logo (CoinGecko CDN)
        val logoImg = android.widget.ImageView(this).apply {
            val sz = (40 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).also { lp -> lp.marginStart = 12; lp.marginEnd = 10 }
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            try { background = androidx.core.content.ContextCompat.getDrawable(this@CryptoAltActivity, R.drawable.token_logo_bg) } catch (_: Exception) {}
            load(getCoinGeckoLogoUrl(pos.market.symbol)) {
                crossfade(true)
                placeholder(R.drawable.ic_token_placeholder)
                error(R.drawable.ic_token_placeholder)
                allowHardware(false)
                transformations(CircleCropTransformation())
            }
        }
        row.addView(logoImg)

        // Colour bar
        val bar = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(4, LinearLayout.LayoutParams.MATCH_PARENT).also { it.marginEnd = 10 }
            setBackgroundColor(pnlColor)
        }
        row.addView(bar)

        // Left column — info
        val info = vBox().apply { layoutParams = llp(0, wrap, 1f) }

        // Line 1: Symbol + direction + leverage
        info.addView(tv(
            "${pos.market.emoji} ${pos.market.symbol}  ${pos.direction.emoji} ${pos.leverageLabel}",
            13f, white, bold = true
        ))
        // Line 2: Entry price · Time
        info.addView(tv(
            "Entry: ${fmtPrice(pos.entryPrice)}  ·  ${timeFmt.format(Date(pos.openTime))}",
            10f, muted, mono = true
        ))
        // Line 3: Size + TP/SL
        val tpPct = pos.takeProfitPrice.let { tp ->
            if (tp > 0 && pos.entryPrice > 0) ((tp - pos.entryPrice) / pos.entryPrice * 100.0) else 0.0
        }
        val slPct = pos.stopLossPrice.let { sl ->
            if (sl > 0 && pos.entryPrice > 0) ((pos.entryPrice - sl) / pos.entryPrice * 100.0) else 0.0
        }
        val tpSlStr = if (tpPct > 0) "TP +${tpPct.toInt()}%  SL -${slPct.toInt()}%" else ""
        info.addView(tv(
            "Size: ${"%.4f".format(pos.sizeSol)}◎${if (tpSlStr.isNotEmpty()) "  ·  $tpSlStr" else ""}",
            10f, muted, mono = true
        ))
        row.addView(info)

        // Right column — P&L
        val right = vBox().apply { gravity = Gravity.END }

        right.addView(tv(
            "${if (pnlPct >= 0) "+" else ""}${"%.2f".format(pnlPct)}%",
            14f, pnlColor, bold = true
        ).apply { gravity = Gravity.END })

        right.addView(tv(
            "${if (pnlSol >= 0) "+" else ""}${"%.4f".format(pnlSol)}◎",
            11f, pnlColor, mono = true
        ).apply { gravity = Gravity.END })

        if (solPrice > 0) {
            right.addView(tv(
                "≈\$${"%.2f".format(valueUsd)}",
                10f, muted, mono = true
            ).apply { gravity = Gravity.END })
        }

        // "Live" label for open positions
        if (isOpen) {
            right.addView(tv("● OPEN", 9f, green, bold = true).apply {
                gravity = Gravity.END
                layoutParams = llp(wrap, wrap).apply { topMargin = 2 }
            })
        } else {
            val closedPnl = pos.realizedPnl ?: pnlSol
            right.addView(tv("${if (closedPnl >= 0) "+" else ""}${"%.4f".format(closedPnl)}◎ realized", 9f, if (closedPnl >= 0) green else red, mono = true).apply {
                gravity = Gravity.END
            })
        }

        row.addView(right)
        card.addView(row)

        // Live price row (async fetch)
        if (isOpen) {
            val priceRow = hBox(0xFF111128.toInt(), 16, 6).apply { gravity = Gravity.CENTER_VERTICAL }
            val tvNow    = tv("Now: ${fmtPrice(pos.currentPrice)}", 10f, white, mono = true).apply { layoutParams = llp(0, wrap, 1f) }
            val tvChange = tv("", 10f, pnlColor, mono = true)
            priceRow.addView(tvNow)
            priceRow.addView(tvChange)
            card.addView(priceRow)

            // Async live price refresh
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val fresh = PerpsMarketDataFetcher.getCachedPrice(pos.market) ?: return@launch
                    val livePrice  = fresh.price
                    val changePct  = fresh.priceChange24hPct
                    val changeArrow = when {
                        changePct > 0.5  -> "▲"
                        changePct < -0.5 -> "▼"
                        else             -> "•"
                    }
                    val changeCol = when {
                        changePct > 0.1  -> green
                        changePct < -0.1 -> red
                        else             -> white
                    }
                    withContext(Dispatchers.Main) {
                        tvNow.text    = "Now: ${fmtPrice(livePrice)}"
                        tvChange.text = "$changeArrow ${if (changePct >= 0) "+" else ""}${"%.2f".format(changePct)}% 24h"
                        tvChange.setTextColor(changeCol)
                    }
                } catch (_: Exception) {}
            }
        }

        llContent.addView(card)
        llContent.addView(thinDivider())

        // Click → close dialog
        if (isOpen) {
            card.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("${pos.market.emoji} ${pos.market.symbol} — ${pos.direction.emoji} ${pos.leverageLabel}")
                    .setMessage(
                        "Size:     ${"%.4f".format(pos.sizeSol)}◎\n" +
                        "Entry:    ${fmtPrice(pos.entryPrice)}\n" +
                        "Current:  ${fmtPrice(pos.currentPrice)}\n" +
                        "P&L:      ${if (pnlPct >= 0) "+" else ""}${"%.2f".format(pnlPct)}%\n" +
                        "P&L SOL:  ${if (pnlSol >= 0) "+" else ""}${"%.4f".format(pnlSol)}◎\n" +
                        (if (solPrice > 0) "≈ USD:    \$${"%.2f".format(valueUsd)}\n" else "") +
                        "Opened:   ${sdf.format(Date(pos.openTime))}"
                    )
                    .setPositiveButton("🔴 Close Position") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            CryptoAltTrader.requestClose(pos.id)
                            withContext(Dispatchers.Main) { selectTab(2) }
                        }
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SETTINGS TAB
    // ═══════════════════════════════════════════════════════════════════════════

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
        addInfoRow("Day",         if (RunTracker30D.isRunActive()) "Day ${RunTracker30D.getCurrentDay()} / 30" else "Not started")
        addInfoRow("Trades",      "${RunTracker30D.totalTrades}")
        addInfoRow("Win Rate",    if (RunTracker30D.totalTrades > 0) "${(RunTracker30D.wins.toDouble() / RunTracker30D.totalTrades * 100).toInt()}%" else "—")
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
    // TOKEN LOGO URL HELPER
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns the best available logo URL for a PerpsMarket symbol.
     * Primary:   CoinGecko CDN  (https://assets.coingecko.com/coins/images/{id}/small/{slug}.png)
     * Fallback:  CoinGecko API thumbnail via known slug
     */
    private fun getCoinGeckoLogoUrl(symbol: String): String {
        // CoinGecko asset CDN — these IDs are stable
        val cdnMap = mapOf(
            "BTC"    to "https://assets.coingecko.com/coins/images/1/small/bitcoin.png",
            "ETH"    to "https://assets.coingecko.com/coins/images/279/small/ethereum.png",
            "SOL"    to "https://assets.coingecko.com/coins/images/4128/small/solana.png",
            "BNB"    to "https://assets.coingecko.com/coins/images/825/small/bnb-icon2_2x.png",
            "XRP"    to "https://assets.coingecko.com/coins/images/44/small/xrp-symbol-white-128.png",
            "ADA"    to "https://assets.coingecko.com/coins/images/975/small/cardano.png",
            "DOGE"   to "https://assets.coingecko.com/coins/images/5/small/dogecoin.png",
            "AVAX"   to "https://assets.coingecko.com/coins/images/12559/small/Avalanche_Circle_RedWhite_Trans.png",
            "DOT"    to "https://assets.coingecko.com/coins/images/12171/small/polkadot.png",
            "LINK"   to "https://assets.coingecko.com/coins/images/877/small/chainlink-new-logo.png",
            "MATIC"  to "https://assets.coingecko.com/coins/images/4713/small/matic-token-icon.png",
            "SHIB"   to "https://assets.coingecko.com/coins/images/11939/small/shiba.png",
            "LTC"    to "https://assets.coingecko.com/coins/images/2/small/litecoin.png",
            "ATOM"   to "https://assets.coingecko.com/coins/images/1481/small/cosmos_hub.png",
            "UNI"    to "https://assets.coingecko.com/coins/images/12504/small/uniswap-uni.png",
            "ARB"    to "https://assets.coingecko.com/coins/images/16547/small/photo_2023-03-29_21.47.00.jpeg",
            "OP"     to "https://assets.coingecko.com/coins/images/25244/small/Optimism.png",
            "APT"    to "https://assets.coingecko.com/coins/images/26455/small/aptos_round.png",
            "SUI"    to "https://assets.coingecko.com/coins/images/26375/small/sui_asset.jpeg",
            "SEI"    to "https://assets.coingecko.com/coins/images/28205/small/Sei_Logo_-_Transparent.png",
            "INJ"    to "https://assets.coingecko.com/coins/images/12882/small/Secondary_Symbol.png",
            "TIA"    to "https://assets.coingecko.com/coins/images/31967/small/tia.jpg",
            "JUP"    to "https://assets.coingecko.com/coins/images/34188/small/jup.png",
            "PEPE"   to "https://assets.coingecko.com/coins/images/29850/small/pepe-token.jpeg",
            "WIF"    to "https://assets.coingecko.com/coins/images/33566/small/dogwifhat.jpg",
            "BONK"   to "https://assets.coingecko.com/coins/images/28600/small/bonk.jpg",
            "NEAR"   to "https://assets.coingecko.com/coins/images/10365/small/near.jpg",
            "FTM"    to "https://assets.coingecko.com/coins/images/4001/small/Fantom_round.png",
            "ALGO"   to "https://assets.coingecko.com/coins/images/4380/small/download.png",
            "ICP"    to "https://assets.coingecko.com/coins/images/14495/small/Internet_Computer_logo.png",
            "VET"    to "https://assets.coingecko.com/coins/images/1167/small/VET_Token_Icon.png",
            "FIL"    to "https://assets.coingecko.com/coins/images/12817/small/filecoin.png",
            "TRX"    to "https://assets.coingecko.com/coins/images/1094/small/tron-logo.png",
            "TON"    to "https://assets.coingecko.com/coins/images/17980/small/ton_symbol.png",
            "XLM"    to "https://assets.coingecko.com/coins/images/100/small/Stellar_symbol_outline_svg.png",
            "XMR"    to "https://assets.coingecko.com/coins/images/69/small/monero_logo.png",
            "ETC"    to "https://assets.coingecko.com/coins/images/453/small/ethereum-classic-logo.png",
            "BCH"    to "https://assets.coingecko.com/coins/images/780/small/bitcoin-cash-circle.png",
            "GMX"    to "https://assets.coingecko.com/coins/images/18323/small/arbit.png",
            "DYDX"   to "https://assets.coingecko.com/coins/images/17500/small/hjnIm9bV.jpg",
            "ENA"    to "https://assets.coingecko.com/coins/images/36530/small/ethena.png",
            "PENDLE" to "https://assets.coingecko.com/coins/images/15069/small/Pendle_Logo_Normal-03.png",
            "WLD"    to "https://assets.coingecko.com/coins/images/31069/small/worldcoin.jpeg",
            "JTO"    to "https://assets.coingecko.com/coins/images/33228/small/jito.png",
            "STRK"   to "https://assets.coingecko.com/coins/images/26433/small/starknet.png",
            "TAO"    to "https://assets.coingecko.com/coins/images/28452/small/ARUsPeNQ_400x400.jpeg",
            "FLOKI"  to "https://assets.coingecko.com/coins/images/16746/small/PNG_image.png",
            "NOT"    to "https://assets.coingecko.com/coins/images/38567/small/notcoin.jpg",
            "POPCAT" to "https://assets.coingecko.com/coins/images/35262/small/popcat.jpeg",
            "TRUMP"  to "https://assets.coingecko.com/coins/images/39490/small/trump.png"
        )
        return cdnMap[symbol] ?: "https://assets.coingecko.com/coins/images/1/small/bitcoin.png"
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UI FACTORY HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildTile(accentColor: Int, title: String, badge: String, badgeColor: Int): LinearLayout {
        return vBox(card, 16, 14).apply {
            addView(View(this@CryptoAltActivity).apply {
                setBackgroundColor(accentColor)
                layoutParams = llp(match, 2).apply { bottomMargin = 8 }
            })
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

    // ─── Price formatter ─────────────────────────────────────────────────────

    private fun fmtPrice(price: Double): String = when {
        price == 0.0  -> "—"
        price > 1000  -> "$%.0f".format(price)
        price > 1     -> "$%.4f".format(price)
        price > 0.001 -> "$%.6f".format(price)
        else          -> "$%.8f".format(price)
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

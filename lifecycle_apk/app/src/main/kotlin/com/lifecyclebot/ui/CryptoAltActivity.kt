package com.lifecyclebot.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import com.lifecyclebot.engine.UniversalBridgeEngine
import com.lifecyclebot.engine.ShadowLearningEngine
import com.lifecyclebot.engine.AICrossTalk
import com.lifecyclebot.engine.AdaptiveLearningEngine
import com.lifecyclebot.engine.BehaviorLearning
import com.lifecyclebot.collective.CollectiveLearning
import com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI
import com.lifecyclebot.v4.meta.CrossTalkFusionEngine
import com.lifecyclebot.perps.PerpsLearningBridge
import com.lifecyclebot.engine.WalletManager
import com.lifecyclebot.perps.CryptoAltScannerAI
import com.lifecyclebot.perps.CryptoAltTrader
import com.lifecyclebot.perps.DynamicAltTokenRegistry
import com.lifecyclebot.perps.DynamicAltTokenRegistry.DynToken
import com.lifecyclebot.perps.DynamicAltTokenRegistry.SortMode
import com.lifecyclebot.perps.PerpsMarket
import com.lifecyclebot.perps.PerpsMarketDataFetcher
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
 * CRYPTO ALTS ACTIVITY — V4.0  (Dynamic Token Universe)
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Displays ALL discovered alt tokens via DynamicAltTokenRegistry:
 *   • DexScreener boosted + latest profiles (live)
 *   • DexScreener keyword search (rotating 33 keywords)
 *   • CoinGecko top 500 by volume + trending 10
 *   • Jupiter verified strict token list (all Solana tokens with metadata)
 *   • Static PerpsMarket enum (56 tokens, always present)
 *
 * Total: potentially thousands of tokens, auto-refreshed every 5 minutes.
 *
 * Scanner tab: search + sort + sector filter, paginated list (50/page)
 * New tab: tokens discovered in last 24h
 * Trending tab: CoinGecko + boosted spotlight
 * Positions + Settings remain as before.
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
    private var currentTab       = 0
    private var scannerSortMode  = SortMode.QUALITY
    private var scannerSector    = "All"
    private var scannerSearch    = ""
    private var scannerPage      = 0
    private val PAGE_SIZE        = 50

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

        // ── Self-init: ensure CryptoAltTrader is ready even if BotService hasn't started it ──
        if (!CryptoAltTrader.isRunning()) {
            try { CryptoAltTrader.init(applicationContext) } catch (_: Exception) {}
        }
        // Restore Markets FluidLearning counters from prefs so UI never shows "INIT"
        try { FluidLearningAI.initMarketsPrefs(applicationContext) } catch (_: Exception) {}

        // Seed static tokens immediately so scanner isn't blank on first open
        DynamicAltTokenRegistry.seedStaticTokens()

        findViewById<View>(R.id.btnCryptoAltBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnCryptoAltScan).setOnClickListener {
            // Force a fresh discovery cycle
            lifecycleScope.launch(Dispatchers.IO) {
                DynamicAltTokenRegistry.runDiscoveryCycle()
                withContext(Dispatchers.Main) { buildFullDashboard() }
            }
        }
        findViewById<View>(R.id.btnCryptoAltAdd).setOnClickListener { showAddToWatchlistDialog() }

        tabScanner.setOnClickListener   { selectTab(0) }
        tabWatchlist.setOnClickListener { selectTab(1) }
        tabPositions.setOnClickListener { selectTab(2) }
        tabSettings.setOnClickListener  { selectTab(3) }

        buildFullDashboard()
        startAutoRefresh()

        // Kick off first discovery cycle in background
        lifecycleScope.launch(Dispatchers.IO) {
            DynamicAltTokenRegistry.runDiscoveryCycle()
            withContext(Dispatchers.Main) {
                if (currentTab == 0) buildFullDashboard()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AUTO REFRESH
    // ═══════════════════════════════════════════════════════════════════════════

    private fun startAutoRefresh() {
        lifecycleScope.launch {
            while (isActive) {
                delay(15_000L)
                withContext(Dispatchers.Main) {
                    if (::tvHeroBalance.isInitialized) refreshHeroStats()
                    if (currentTab == 2) selectTab(2)
                }
            }
        }
        // Discovery cycle every 5 min
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(5 * 60_000L)
                DynamicAltTokenRegistry.runDiscoveryCycle()
                withContext(Dispatchers.Main) {
                    if (currentTab == 0) buildTabContent()
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

    private fun refreshWalletCapacityAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val wallet = com.lifecyclebot.engine.WalletManager.getWallet() ?: return@launch
                val cap    = UniversalBridgeEngine.scanWalletCapacity(wallet)
                withContext(Dispatchers.Main) {
                    val msg = "💼 \$${cap.totalUsdValue.let { "%.0f".format(it) }} across ${cap.allBalances.size} tokens"
                    Toast.makeText(this@CryptoAltActivity, msg, Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FULL DASHBOARD
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildFullDashboard() {
        llContent.removeAllViews()
        buildHeroSection()
        buildReadinessTile()
        buildProofRunTile()
        buildModuleIconGrid()   // ← AATE-style 2-row icon grid
        buildShadowFDGPanel()
        buildHiveMindPanel()
        buildSectorHeatPanel()
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
        val dynCnt = DynamicAltTokenRegistry.getTokenCount()

        val tile = vBox(card2, 20, 18)

        val balRow = hBox()
        tvHeroBalance = tv("◎ ${"%.4f".format(bal)}", 28f, white, bold = true).apply { layoutParams = llp(0, wrap, 1f) }
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

        tvHeroPhase = tv(phase, 10f, phaseColor(phase), bold = true).apply { layoutParams = llp(match, wrap).apply { topMargin = 4 } }
        tile.addView(tvHeroPhase)

        // Token universe counter
        tile.addView(tv("🌐 $dynCnt tokens in universe", 10f, teal, mono = true).apply {
            layoutParams = llp(match, wrap).apply { topMargin = 4 }
        })

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
    // TRADER TILES (same as v3, collapsed for brevity)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildReadinessTile() {
        val trades   = FluidLearningAI.getMarketsTradeCount()
        val mTrades  = FluidLearningAI.getMarketsTradeCount()
        val boost    = FluidLearningAI.getBootstrapConfidenceBoost()
        val sizeMult = FluidLearningAI.getBootstrapSizeMultiplier()
        val phase    = getPhaseLabel()
        val tile     = buildTile(phaseColor(phase), "🚦 Live Readiness", phase, phaseColor(phase))
        tile.addView(progressBar(phaseColor(phase), (FluidLearningAI.getMarketsLearningProgress() * 100).toInt()))
        val row = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(row, "V3 Trades",  "$trades",    white,  1f)
        addStatChip(row, "Mkts Trades","$mTrades",   teal,   1f)
        addStatChip(row, "Conf Boost", "+${(boost*100).toInt()}%", purple, 1f)
        addStatChip(row, "Size Mult",  "${"%.2f".format(sizeMult)}x", amber, 1f)
        tile.addView(row); llContent.addView(tile)
    }

    private fun buildProofRunTile() {
        val isActive  = RunTracker30D.isRunActive()
        val day       = if (isActive) RunTracker30D.getCurrentDay() else 0
        val total     = RunTracker30D.totalTrades
        val wr        = if (total > 0) RunTracker30D.wins.toDouble() / total * 100 else 0.0
        val pnl       = RunTracker30D.totalRealizedPnlSol
        val integrity = if (isActive) RunTracker30D.integrityScore() else 0
        val tile      = buildTile(teal, "📈 30-Day Proof Run", if (isActive) "Day $day / 30" else "NOT STARTED", if (isActive) teal else muted)
        val row = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(row, "Trades",    "$total", white, 1f)
        addStatChip(row, "Win Rate",  "${"%.1f".format(wr)}%", if (wr >= 55) green else amber, 1f)
        addStatChip(row, "Realized",  "${if (pnl >= 0) "+" else ""}${"%.3f".format(pnl)}◎", if (pnl >= 0) green else red, 1f)
        addStatChip(row, "Integrity", "$integrity/100", if (integrity >= 80) green else amber, 1f)
        tile.addView(row); llContent.addView(tile)
    }

    private fun buildShitCoinTile() {
        val stats   = ShitCoinTraderAI.getStats()
        val mode    = ShitCoinTraderAI.getCurrentMode()
        val tile    = buildTile(red, "💩 ShitCoin Degen", mode.name, if (mode == ShitCoinTraderAI.ShitCoinMode.HUNTING) orange else muted)
        val row     = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(row, "Balance",  "◎${"%.3f".format(stats.balanceSol)}", white, 1f)
        addStatChip(row, "Daily PnL","${if (stats.dailyPnlSol >= 0) "+" else ""}${"%.3f".format(stats.dailyPnlSol)}◎", if (stats.dailyPnlSol >= 0) green else red, 1f)
        addStatChip(row, "Win Rate", "${"%.1f".format(stats.winRate)}%", if (stats.winRate >= 55) green else amber, 1f)
        addStatChip(row, "Open",     "${stats.activePositions}", blue, 1f)
        tile.addView(row); llContent.addView(tile)
    }

    private fun buildQualityTile() {
        val wr     = QualityTraderAI.getWinRate()
        val pnl    = QualityTraderAI.getDailyPnl()
        val open   = QualityTraderAI.getActivePositions().size
        val tile   = buildTile(teal, "💎 Quality Mode", "MCap \$100K–\$1M", teal)
        val row    = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(row, "Win Rate",  "${"%.1f".format(wr)}%", if (wr >= 55) green else amber, 1f)
        addStatChip(row, "Daily PnL", "${if (pnl >= 0) "+" else ""}${"%.3f".format(pnl)}◎", if (pnl >= 0) green else red, 1f)
        addStatChip(row, "Open Pos",  "$open", blue, 1f)
        addStatChip(row, "TP / SL",   "+${QualityTraderAI.getFluidTakeProfit().toInt()}% / -${QualityTraderAI.getFluidStopLoss().toInt()}%", muted, 1f)
        tile.addView(row); llContent.addView(tile)
    }

    private fun buildBlueChipTile() {
        val stats  = BlueChipTraderAI.getStats()
        val wr     = BlueChipTraderAI.getWinRatePct().toDouble()
        val bal    = BlueChipTraderAI.getCurrentBalance()
        val pnl    = BlueChipTraderAI.getDailyPnlSol()
        val tile   = buildTile(blue, "🔵 Blue Chip", "MCap \$1M+", blue)
        val row    = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(row, "Balance",  "◎${"%.3f".format(bal)}", white, 1f)
        addStatChip(row, "Daily PnL","${if (pnl >= 0) "+" else ""}${"%.3f".format(pnl)}◎", if (pnl >= 0) green else red, 1f)
        addStatChip(row, "Win Rate", "${"%.1f".format(wr)}%", if (wr >= 55) green else amber, 1f)
        addStatChip(row, "Open",     "${stats.activePositions}", blue, 1f)
        tile.addView(row); llContent.addView(tile)
    }

    private fun buildExpressTile() {
        val stats  = ShitCoinExpress.getStats()
        val tile   = buildTile(orange, "⚡ Express Mode", "High Velocity", orange)
        val row    = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(row, "Win Rate", "${"%.1f".format(stats.winRate)}%", if (stats.winRate >= 55) green else amber, 1f)
        addStatChip(row, "Daily PnL","${if (stats.dailyPnlSol >= 0) "+" else ""}${"%.3f".format(stats.dailyPnlSol)}◎", if (stats.dailyPnlSol >= 0) green else red, 1f)
        addStatChip(row, "Open Pos", "${stats.activeRides}", blue, 1f)
        addStatChip(row, "Speed",    "FAST", orange, 1f)
        tile.addView(row); llContent.addView(tile)
    }

    private fun buildMoonshotTile() {
        val wr       = MoonshotTraderAI.getWinRatePct().toDouble()
        val pnl      = MoonshotTraderAI.getDailyPnlSol()
        val open     = MoonshotTraderAI.getActivePositions().size
        val tile     = buildTile(purple, "🌙 Moonshot", "10x / 100x Hunters", purple)
        val row      = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(row, "Win Rate", "${"%.1f".format(wr)}%", if (wr >= 55) green else amber, 1f)
        addStatChip(row, "Daily PnL","${if (pnl >= 0) "+" else ""}${"%.3f".format(pnl)}◎", if (pnl >= 0) green else red, 1f)
        addStatChip(row, "Open Pos", "$open", blue, 1f)
        addStatChip(row, "10x Hits", "${MoonshotTraderAI.getLifetimeTenX()}", purple, 1f)
        tile.addView(row); llContent.addView(tile)
    }

    private fun buildManipTile() {
        val stats  = ManipulatedTraderAI.getStats()
        val tile   = buildTile(pink, "🎭 Manip Catch", "Manipulation Detector", pink)
        val row    = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        val manipWr = if (stats.dailyWins + stats.dailyLosses > 0) (stats.dailyWins.toDouble() / (stats.dailyWins + stats.dailyLosses) * 100) else 0.0
        addStatChip(row, "Win Rate", "${"%.1f".format(manipWr)}%", if (manipWr >= 55) green else amber, 1f)
        addStatChip(row, "Daily PnL","${if (stats.dailyPnlSol >= 0) "+" else ""}${"%.3f".format(stats.dailyPnlSol)}◎", if (stats.dailyPnlSol >= 0) green else red, 1f)
        addStatChip(row, "Open Pos", "${stats.activeCount}", blue, 1f)
        addStatChip(row, "Caught",   "${stats.totalManipCaught}", pink, 1f)
        tile.addView(row); llContent.addView(tile)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MODULE ICON GRID  —  mirrors AATE main screen 2-row tile grid
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildModuleIconGrid() {
        val grid = vBox(card, 12, 12).apply {
            layoutParams = llp(match, wrap).apply { bottomMargin = 6 }
        }

        // ── Row 1: Active Traders ──────────────────────────────────────────────
        val row1 = hBox().apply { layoutParams = llp(match, wrap) }

        val scStats = ShitCoinTraderAI.getStats()
        row1.addView(buildIconTile("💩", "Shit",
            "${scStats.activePositions} | ${scStats.winRate.toInt()}%",
            if (scStats.winRate >= 55) green else if (scStats.winRate >= 40) amber else red))

        val qWr   = QualityTraderAI.getWinRate()
        val qOpen = QualityTraderAI.getActivePositions().size
        row1.addView(buildIconTile("💎", "Quality",
            "$qOpen | ${qWr.toInt()}%",
            if (qWr >= 55) green else if (qWr >= 40) amber else red))

        val bcStats = BlueChipTraderAI.getStats()
        row1.addView(buildIconTile("🔵", "Blue",
            "${bcStats.activePositions} | ${bcStats.winRate.toInt()}%",
            if (bcStats.winRate >= 55) green else if (bcStats.winRate >= 40) amber else red))

        val exStats = ShitCoinExpress.getStats()
        row1.addView(buildIconTile("⚡", "Express",
            "${exStats.activeRides} | ${exStats.winRate.toInt()}%",
            if (exStats.winRate >= 55) green else if (exStats.winRate >= 40) amber else red))

        val maStats = ManipulatedTraderAI.getStats()
        val maWr    = if (maStats.dailyWins + maStats.dailyLosses > 0)
            (maStats.dailyWins.toDouble() / (maStats.dailyWins + maStats.dailyLosses) * 100).toInt() else 0
        row1.addView(buildIconTile("🎭", "Manip",
            "${maStats.activeCount} | $maWr%",
            if (maWr >= 55) green else if (maWr >= 40) amber else red))

        val moWr   = MoonshotTraderAI.getWinRatePct()
        val moOpen = MoonshotTraderAI.getActivePositions().size
        row1.addView(buildIconTile("🌙", "Moon",
            "$moOpen | $moWr%",
            if (moWr >= 55) green else if (moWr >= 40) amber else purple))

        val flTrades = FluidLearningAI.getMarketsTradeCount()
        val flPct    = (FluidLearningAI.getMarketsLearningProgress() * 100).toInt()
        row1.addView(buildIconTile("🧠", "Learn",
            "$flTrades | $flPct%",
            if (flPct >= 80) green else if (flPct >= 40) blue else muted))

        grid.addView(row1)

        grid.addView(View(this).apply {
            setBackgroundColor(divBg)
            layoutParams = llp(match, 1).apply { topMargin = 8; bottomMargin = 8 }
        })

        // ── Row 2: Intelligence Modules ────────────────────────────────────────
        val row2 = hBox().apply { layoutParams = llp(match, wrap) }

        val ciStats = CollectiveIntelligenceAI.getStats()
        row2.addView(buildIconTile("🐝", "Hive",
            "${ciStats.cachedPatterns}/${ciStats.cachedModes}",
            if (ciStats.isEnabled) amber else muted))

        val shadow = ShadowLearningEngine.getBlockedTradeStats()
        row2.addView(buildIconTile("👁️", "Shadow",
            "${shadow.wouldHaveWon}/${shadow.totalTracked}",
            if (shadow.totalTracked > 0) indigo else muted))

        val dominance   = CryptoAltScannerAI.getDominanceCycleSignal()
        val regimeShort = when {
            dominance.contains("BULL", ignoreCase = true) -> "BULL"
            dominance.contains("BEAR", ignoreCase = true) -> "BEAR"
            dominance.contains("MEME", ignoreCase = true) -> "MEME"
            dominance.contains("MID",  ignoreCase = true) -> "MID"
            else -> dominance.take(4)
        }
        val regimeColor = when {
            dominance.contains("BULL", ignoreCase = true) -> green
            dominance.contains("BEAR", ignoreCase = true) -> red
            else -> amber
        }
        row2.addView(buildIconTile("📊", "Regimes", regimeShort, regimeColor))

        val plbLayers = PerpsLearningBridge.getConnectedLayerCount()
        val plbSyncs  = PerpsLearningBridge.getCrossLayerSyncs()
        row2.addView(buildIconTile("🔗", "Layers",
            "${plbSyncs}s/${plbLayers}L",
            if (plbLayers > 0) blue else muted))

        val alCount = AdaptiveLearningEngine.getTradeCount()
        row2.addView(buildIconTile("📐", "Adapt",
            if (alCount > 0) "$alCount" else "NEW",
            if (alCount > 100) green else if (alCount > 0) amber else muted))

        val fg = CryptoAltScannerAI.getCryptoFearGreed()
        row2.addView(buildIconTile("🌡️", "F&G",
            "$fg/100",
            if (fg > 60) green else if (fg < 30) red else amber))

        val dynCount = DynamicAltTokenRegistry.getTokenCount()
        row2.addView(buildIconTile("🌐", "Tokens",
            "$dynCount",
            if (dynCount > 100) teal else amber))

        grid.addView(row2)
        llContent.addView(grid)
    }

    private fun buildIconTile(emoji: String, label: String, stat: String, color: Int): LinearLayout {
        return vBox(0xFF0D0D1A.toInt(), 4, 8).apply {
            layoutParams = llp(0, wrap, 1f).apply { marginEnd = 3 }
            gravity = android.view.Gravity.CENTER
            addView(tv(emoji, 18f, white).apply { gravity = android.view.Gravity.CENTER })
            addView(tv(label, 8f, muted).apply { gravity = android.view.Gravity.CENTER })
            addView(tv(stat,  9f, color, mono = true).apply { gravity = android.view.Gravity.CENTER })
        }
    }

    private fun buildShadowFDGPanel() {
        val shadow  = ShadowLearningEngine.getBlockedTradeStats()
        val topMode = ShadowLearningEngine.getTopTrackedMode() ?: "—"
        val tile    = buildTile(indigo, "👁️ Shadow FDG", "Blocked Trade Analysis", indigo)
        val row     = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(row, "Tracked",   "${shadow.totalTracked}", white, 1f)
        addStatChip(row, "Would Win", "${shadow.wouldHaveWon}", green, 1f)
        addStatChip(row, "Would Lose","${shadow.wouldHaveLost}", red,  1f)
        addStatChip(row, "Top Mode",  topMode, indigo, 1f)
        tile.addView(row); llContent.addView(tile)
    }

    private fun buildHiveMindPanel() {
        // ── Collective Intelligence AI ─────────────────────────────────────────
        val ci      = CollectiveIntelligenceAI.getStats()
        val tile    = buildTile(amber, "🐝 Hive Mind", "Collective Intelligence", amber)
        val row1    = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(row1, "Patterns",   "${ci.cachedPatterns}",   white,  1f)
        addStatChip(row1, "Modes",      "${ci.cachedModes}",      amber,  1f)
        addStatChip(row1, "Consensus",  "${ci.cachedConsensus}",  green,  1f)
        addStatChip(row1, "Anomalies",  "${ci.anomaliesDetected}",red,    1f)
        tile.addView(row1)

        val row2    = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 4 } }
        addStatChip(row2, "Conf Thresh","${ci.dynamicConfThreshold}%", purple, 1f)
        val enabled = if (ci.isEnabled) "LIVE" else "OFFLINE"
        val enabledCol = if (ci.isEnabled) green else muted
        addStatChip(row2, "Network",    enabled,                  enabledCol, 1f)
        val hotMints = CollectiveIntelligenceAI.getNetworkHotMints().size
        addStatChip(row2, "Hot Mints",  "$hotMints",              orange, 1f)
        val activeSignals = CollectiveIntelligenceAI.getActiveNetworkSignals().size
        addStatChip(row2, "Signals",    "$activeSignals",         blue,   1f)
        tile.addView(row2)
        llContent.addView(tile)

        // ── AI Cross-Talk ──────────────────────────────────────────────────────
        val ctStats  = AICrossTalk.getStats()          // returns a formatted String
        val ctTile   = buildTile(indigo, "🔗 AI Cross-Talk", "Multi-AI Coordination", indigo)
        ctTile.addView(tv(ctStats, 10f, muted, mono = true).apply {
            setPadding(0, 6, 0, 4)
        })

        // Cross-Talk Fusion snapshot
        val snap = CrossTalkFusionEngine.getSnapshot()
        if (snap != null) {
            val snapRow = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 4 } }
            val fusionSources = CrossTalkFusionEngine.getStats()["activeSources"] ?: 0
            val fusionSignals = CrossTalkFusionEngine.getStats()["totalSignals"] ?: 0
            addStatChip(snapRow, "Sources",  "$fusionSources",  blue,  1f)
            addStatChip(snapRow, "Signals",  "$fusionSignals",  amber, 1f)
            ctTile.addView(snapRow)
        }
        llContent.addView(ctTile)

        // ── Adaptive Learning Engine ───────────────────────────────────────────
        val alTile  = buildTile(teal, "📐 Adaptive Learning", "Feature-Weight Optimizer", teal)
        val alRow   = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        val alCount = AdaptiveLearningEngine.getTradeCount()
        val alStatus = AdaptiveLearningEngine.getStatus()
        addStatChip(alRow, "Trades",  "$alCount",   white, 1f)
        addStatChip(alRow, "Status",  alStatus.take(8), teal, 2f)
        alTile.addView(alRow)

        // Top feature weights
        val weights = try { AdaptiveLearningEngine.getDetailedWeights() } catch (_: Exception) { emptyMap<String, Double>() }
        if (weights.isNotEmpty()) {
            val wRow = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 4 } }
            weights.entries.sortedByDescending { it.value }.take(4).forEach { (k, v) ->
                val col = when {
                    v > 1.5  -> green
                    v > 1.0  -> amber
                    v < 0.5  -> red
                    else     -> muted
                }
                addStatChip(wRow, k.take(6), "${"%.2f".format(v)}×", col, 1f)
            }
            alTile.addView(wRow)
        }
        llContent.addView(alTile)

        // ── Behavior Learning ──────────────────────────────────────────────────
        val bl       = BehaviorLearning.getInsights()
        val blTile   = buildTile(pink, "🧬 Behavior Learning", "Pattern Recognition", pink)
        val blRow    = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(blRow, "Trades",    "${BehaviorLearning.getTradeCount()}", white,  1f)
        addStatChip(blRow, "Good Pat",  "${bl.totalGoodPatterns}",             green,  1f)
        addStatChip(blRow, "Bad Pat",   "${bl.totalBadPatterns}",              red,    1f)
        addStatChip(blRow, "Best Mode", bl.bestTradingMode.take(7),            amber,  1f)
        blTile.addView(blRow)
        if (bl.topGoodPatterns.isNotEmpty()) {
            val top = bl.topGoodPatterns.first()
            blTile.addView(tv("Best: ${top.signature.take(28)} → WR ${"%.0f".format(top.winRate)}% avg ${"%.1f".format(top.avgPnl)}%", 9f, muted, mono = true).apply {
                setPadding(0, 4, 0, 2)
            })
        }
        llContent.addView(blTile)

        // ── Perps Learning Bridge ──────────────────────────────────────────────
        val plbTile  = buildTile(orange, "🌉 Learning Bridge", "Cross-Layer Sync", orange)
        val plbRow   = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        val plbEvents= PerpsLearningBridge.getTotalLearningEvents()
        val plbSyncs = PerpsLearningBridge.getCrossLayerSyncs()
        val plbLayers= PerpsLearningBridge.getConnectedLayerCount()
        addStatChip(plbRow, "Events",  "$plbEvents", white,  1f)
        addStatChip(plbRow, "Syncs",   "$plbSyncs",  green,  1f)
        addStatChip(plbRow, "Layers",  "$plbLayers", blue,   1f)
        val plbStats = PerpsLearningBridge.getLayerPerpsStats()
        val bestLayer = plbStats.maxByOrNull { it.value.first }
        if (bestLayer != null) {
            addStatChip(plbRow, bestLayer.key.take(5), "${"%.0f".format(bestLayer.value.first)}%WR", orange, 1f)
        }
        plbTile.addView(plbRow)
        llContent.addView(plbTile)
    }

    private fun buildSectorHeatPanel() {
        val sectors  = CryptoAltScannerAI.getSectorHeatSummary()
        val dominant = CryptoAltScannerAI.getDominanceCycleSignal()
        val fg       = CryptoAltScannerAI.getCryptoFearGreed()
        val tile     = buildTile(teal, "🌡️ Sector Heat", dominant, teal)
        val fgRow    = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(fgRow, "Fear & Greed", "$fg", if (fg > 60) green else if (fg < 40) red else amber, 1f)
        addStatChip(fgRow, "Dominance", dominant, teal, 3f)
        tile.addView(fgRow)
        if (sectors.isNotEmpty()) {
            val sRow = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 4 } }
            sectors.entries.take(4).forEach { (name, heat) ->
                addStatChip(sRow, name.take(6), "${"%.0f".format(heat*100)}%", when { heat > 0.7 -> green; heat > 0.4 -> amber; else -> red }, 1f)
            }
            tile.addView(sRow)
        }
        llContent.addView(tile)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TAB ROUTER
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildTabContent() {
        listOf(tabScanner, tabWatchlist, tabPositions, tabSettings).forEachIndexed { i, tv ->
            tv.setBackgroundColor(if (i == currentTab) card else 0)
            tv.setTextColor(if (i == currentTab) white else muted)
        }
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
    // SCANNER TAB — dynamic token universe with search/sort/sector/pagination
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildScannerTab() {
        val total     = DynamicAltTokenRegistry.getTokenCount()
        val dynCount  = DynamicAltTokenRegistry.getDynamicCount()
        val trending  = DynamicAltTokenRegistry.getTrendingTokens().size
        val boosted   = DynamicAltTokenRegistry.getBoostedTokens().size

        // ── Stats bar ──────────────────────────────────────────────────────
        val statsRow = hBox(card2, 16, 8).apply { gravity = Gravity.CENTER_VERTICAL }
        statsRow.addView(tv("🌐 $total tokens", 11f, teal, bold = true).apply { layoutParams = llp(0, wrap, 1f) })
        statsRow.addView(tv("🔥 $trending trending", 10f, orange).apply { layoutParams = llp(0, wrap, 1f) })
        statsRow.addView(tv("⚡ $boosted boosted", 10f, purple))
        llContent.addView(statsRow)

        // ── Search bar ─────────────────────────────────────────────────────
        val searchBox = EditText(this).apply {
            hint = "Search tokens..."
            setHintTextColor(muted)
            setTextColor(white)
            setBackgroundColor(card)
            setPadding(16, 10, 16, 10)
            textSize = 13f
            setText(scannerSearch)
            layoutParams = llp(match, wrap).apply { topMargin = 4; bottomMargin = 4 }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    scannerSearch = s?.toString() ?: ""
                    scannerPage   = 0
                    renderTokenList()
                }
            })
        }
        llContent.addView(searchBox)

        // ── Sort chips ─────────────────────────────────────────────────────
        val sortRow = hBox().apply {
            layoutParams = llp(match, wrap).apply { bottomMargin = 4 }
        }
        val hScroll = HorizontalScrollView(this).apply { layoutParams = llp(match, wrap) }
        val sortInner = hBox().apply { setPadding(12, 4, 12, 4) }
        listOf(
            "Quality" to SortMode.QUALITY,
            "Trending" to SortMode.TRENDING,
            "Volume" to SortMode.VOLUME,
            "MCap" to SortMode.MCAP,
            "% Change" to SortMode.CHANGE,
            "Newest" to SortMode.NEW,
            "Boosted" to SortMode.BOOSTED,
        ).forEach { (label, mode) ->
            val isActive = scannerSortMode == mode
            sortInner.addView(tv(label, 10f, if (isActive) white else muted, bold = isActive).apply {
                setBackgroundColor(if (isActive) indigo else card)
                setPadding(10, 4, 10, 4)
                layoutParams = llp(wrap, wrap).apply { marginEnd = 4 }
                setOnClickListener {
                    scannerSortMode = mode
                    scannerPage     = 0
                    renderTokenList()
                }
            })
        }
        hScroll.addView(sortInner)
        llContent.addView(hScroll)

        // ── Sector chips ───────────────────────────────────────────────────
        val sectorScroll = HorizontalScrollView(this).apply { layoutParams = llp(match, wrap).apply { bottomMargin = 4 } }
        val sectorInner  = hBox().apply { setPadding(12, 4, 12, 4) }
        val sectors = listOf("All","L1","L2","DEX","Lending","Oracle","DePIN","Gaming","Meme","Political","AI/Agent","LST","NFT","Other")
        sectors.forEach { sector ->
            val isActive = scannerSector == sector
            sectorInner.addView(tv(sector, 9f, if (isActive) white else muted).apply {
                setBackgroundColor(if (isActive) teal else card)
                setPadding(8, 3, 8, 3)
                layoutParams = llp(wrap, wrap).apply { marginEnd = 3 }
                setOnClickListener {
                    scannerSector = sector
                    scannerPage   = 0
                    renderTokenList()
                }
            })
        }
        sectorScroll.addView(sectorInner)
        llContent.addView(sectorScroll)

        // ── Token list (rendered separately so we can refresh without rebuilding controls) ──
        renderTokenList()
    }

    // Token list anchor tag — we re-add from this index
    private var tokenListStartIdx = -1

    private fun renderTokenList() {
        // Remove everything after the controls (search+sort+sector = 4 views added by buildScannerTab)
        // Find the anchor and remove from there
        if (tokenListStartIdx >= 0) {
            while (llContent.childCount > tokenListStartIdx) {
                llContent.removeViewAt(llContent.childCount - 1)
            }
        } else {
            tokenListStartIdx = llContent.childCount
        }

        var tokens = DynamicAltTokenRegistry.getAllTokens(scannerSortMode)

        // Sector filter
        if (scannerSector != "All") {
            tokens = tokens.filter { it.sector.equals(scannerSector, ignoreCase = true) }
        }
        // Search filter
        if (scannerSearch.isNotBlank()) {
            val q = scannerSearch.uppercase()
            tokens = tokens.filter { it.symbol.contains(q) || it.name.uppercase().contains(q) || it.sector.uppercase().contains(q) }
        }

        val total    = tokens.size
        val fromIdx  = scannerPage * PAGE_SIZE
        val toIdx    = minOf(fromIdx + PAGE_SIZE, total)
        val page     = tokens.subList(fromIdx.coerceAtMost(total), toIdx)

        // Result count
        llContent.addView(tv("$total results  (page ${scannerPage + 1} of ${(total + PAGE_SIZE - 1) / PAGE_SIZE})", 10f, muted).apply {
            setPadding(16, 8, 16, 4)
        })

        if (page.isEmpty()) {
            addEmptyState("No tokens found. Tap 🔄 to refresh.")
            return
        }

        page.forEach { tok ->
            llContent.addView(buildDynTokenRow(tok))
            llContent.addView(thinDivider())
        }

        // Pagination buttons
        val pageRow = hBox(0, 16, 8).apply { gravity = Gravity.CENTER_VERTICAL }
        if (scannerPage > 0) {
            pageRow.addView(tv("← Prev", 12f, blue).apply {
                setBackgroundColor(card); setPadding(12, 6, 12, 6)
                layoutParams = llp(0, wrap, 1f)
                setOnClickListener { scannerPage--; renderTokenList() }
            })
        } else {
            pageRow.addView(View(this).apply { layoutParams = llp(0, 1, 1f) })
        }
        pageRow.addView(tv("${scannerPage + 1} / ${(total + PAGE_SIZE - 1).coerceAtLeast(1) / PAGE_SIZE}", 11f, muted).apply {
            gravity = Gravity.CENTER; layoutParams = llp(0, wrap, 1f)
        })
        if (toIdx < total) {
            pageRow.addView(tv("Next →", 12f, blue).apply {
                setBackgroundColor(card); setPadding(12, 6, 12, 6)
                gravity = Gravity.END
                layoutParams = llp(0, wrap, 1f)
                setOnClickListener { scannerPage++; renderTokenList() }
            })
        } else {
            pageRow.addView(View(this).apply { layoutParams = llp(0, 1, 1f) })
        }
        llContent.addView(pageRow)
    }

    private fun buildDynTokenRow(tok: DynToken): LinearLayout {
        val change = tok.priceChange24h
        val col    = if (change >= 0) green else red
        val row    = hBox(card, 14, 10).apply { gravity = Gravity.CENTER_VERTICAL }

        // Token logo
        val logoImg = android.widget.ImageView(this).apply {
            val sz = (34 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = 8 }
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            try { background = androidx.core.content.ContextCompat.getDrawable(this@CryptoAltActivity, R.drawable.token_logo_bg) } catch (_: Exception) {}
            val logoUrl = tok.logoUrl.ifBlank { DynamicAltTokenRegistry.getCoinGeckoLogoUrl(tok.symbol) }
            if (logoUrl.isNotBlank()) {
                load(logoUrl) {
                    crossfade(true)
                    placeholder(R.drawable.ic_token_placeholder)
                    error(R.drawable.ic_token_placeholder)
                    allowHardware(false)
                    transformations(CircleCropTransformation())
                }
            } else {
                setImageResource(R.drawable.ic_token_placeholder)
            }
        }
        row.addView(logoImg)

        // Middle column
        val mid = vBox().apply { layoutParams = llp(0, wrap, 1f) }
        // Symbol + badges
        val headerRow = hBox()
        headerRow.addView(tv("${tok.emoji} ${tok.symbol}", 13f, white, bold = true))
        if (tok.isTrending && tok.trendingRank in 0..2) {
            headerRow.addView(tv(" 🔥#${tok.trendingRank + 1}", 9f, orange).apply { setPadding(4, 0, 0, 0) })
        }
        if (tok.isBoosted) {
            headerRow.addView(tv(" ⚡", 9f, purple).apply { setPadding(2, 0, 0, 0) })
        }
        mid.addView(headerRow)
        // Name + sector
        mid.addView(tv("${tok.name.take(18)}  ${if (tok.sector.isNotBlank()) "· ${tok.sector}" else ""}", 9f, muted))
        // MCap + volume
        if (tok.mcap > 0 || tok.volume24h > 0) {
            mid.addView(tv("MC:${tok.fmtMcap()}  Vol:${tok.volume24h.fmtVol()}", 9f, muted, mono = true))
        }
        row.addView(mid)

        // Right column
        val right = vBox().apply { gravity = Gravity.END }
        if (tok.price > 0) {
            right.addView(tv(tok.fmtPrice(), 12f, white, mono = true).apply { gravity = Gravity.END })
        }
        if (change != 0.0) {
            right.addView(tv("${if (change >= 0) "+" else ""}${"%.2f".format(change)}%", 11f, col, mono = true).apply { gravity = Gravity.END })
        } else {
            right.addView(tv("—", 11f, muted, mono = true).apply { gravity = Gravity.END })
        }
        row.addView(right)

        // Click → add to watchlist or show detail
        row.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("${tok.emoji} ${tok.symbol} — ${tok.name}")
                .setMessage(
                    "Price:    ${tok.fmtPrice()}\n" +
                    "24h:      ${if (change >= 0) "+" else ""}${"%.2f".format(change)}%\n" +
                    "MCap:     ${tok.fmtMcap()}\n" +
                    "Liq:      ${tok.liquidityUsd.fmtVol()}\n" +
                    "Volume:   ${tok.volume24h.fmtVol()}\n" +
                    "Buys/Sells: ${tok.buys24h}/${tok.sells24h}\n" +
                    "Sector:   ${tok.sector}\n" +
                    "Source:   ${tok.source}"
                )
                .setPositiveButton("📌 Watch") { _, _ ->
                    WatchlistEngine.addToWatchlist(tok.symbol)
                    Toast.makeText(this, "📌 ${tok.symbol} added to watchlist", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Close", null).show()
        }

        return row
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WATCHLIST TAB
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildWatchlistTab() {
        val symbols = WatchlistEngine.getWatchlist()
        addSectionHeader("👀 Watchlist (${symbols.size})", blue)

        if (symbols.isEmpty()) {
            addEmptyState("No tokens in watchlist. Tap a token and choose 📌 Watch.")
            return
        }

        symbols.forEach { item ->
            val symbol = item.symbol
            val tok = DynamicAltTokenRegistry.getTokenBySymbol(symbol)
            if (tok != null) {
                val row = buildDynTokenRow(tok)
                // Override click to add remove button
                val removeBtn = tv("✕", 12f, red).apply {
                    setPadding(8, 4, 8, 4)
                    setOnClickListener { WatchlistEngine.removeFromWatchlist(symbol); selectTab(1) }
                }
                row.addView(removeBtn)
                llContent.addView(row)
            } else {
                // Fallback for symbols not in dynamic registry
                val row = hBox(card, 16, 10).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(tv("⚪ $symbol", 13f, white, bold = true).apply { layoutParams = llp(0, wrap, 1f) })
                row.addView(tv("✕", 12f, red).apply {
                    setPadding(8, 4, 8, 4)
                    setOnClickListener { WatchlistEngine.removeFromWatchlist(symbol); selectTab(1) }
                })
                llContent.addView(row)
            }
            llContent.addView(thinDivider())
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POSITIONS TAB
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildPositionsTab() {
        val allPos    = CryptoAltTrader.getAllPositions()
        val openPos   = allPos.filter { it.closeTime == null }
        val closedPos = allPos.filter { it.closeTime != null }.take(20)
        val totalRisk = openPos.sumOf { it.sizeSol }
        val totalPnl  = allPos.sumOf { it.getPnlSol() }
        val solPrice  = WalletManager.lastKnownSolPrice

        val summaryRow = hBox(card2, 16, 12).apply { gravity = Gravity.CENTER_VERTICAL }
        summaryRow.addView(tv("Open Positions", 14f, white, bold = true).apply { layoutParams = llp(0, wrap, 1f) })
        summaryRow.addView(tv("${"%.3f".format(totalRisk)}◎ at risk", 11f, muted, mono = true).apply { layoutParams = llp(0, wrap, 0f).apply { marginEnd = 8 } })
        summaryRow.addView(tv("${if (totalPnl >= 0) "+" else ""}${"%.4f".format(totalPnl)}◎", 12f, if (totalPnl >= 0) green else red, mono = true))
        llContent.addView(summaryRow)
        llContent.addView(thinDivider())

        if (openPos.isEmpty()) addEmptyState("No open positions")
        else openPos.forEach { addRichPositionRow(it, true, solPrice) }

        llContent.addView(tv("📜 Recent Closed (${closedPos.size})", 12f, muted, bold = true).apply { setPadding(16, 14, 16, 6) })
        if (closedPos.isEmpty()) addEmptyState("No closed positions yet")
        else closedPos.forEach { addRichPositionRow(it, false, solPrice) }

        findViewById<TextView>(R.id.tvCryptoAltStats)?.text =
            "${openPos.size} open | PnL: ${if (totalPnl >= 0) "+" else ""}%.4f◎".format(totalPnl)
    }

    private fun addRichPositionRow(pos: CryptoAltTrader.AltPosition, isOpen: Boolean, solPrice: Double) {
        val pnlPct   = pos.getPnlPct()
        val pnlSol   = pos.getPnlSol()
        val pnlColor = if (pnlPct >= 0) green else red
        val valueUsd = (pos.sizeSol + pnlSol) * solPrice

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(this@CryptoAltActivity.card)
            setPadding(0, 12, 16, 12)
            layoutParams = llp(match, wrap).apply { bottomMargin = 2 }
        }
        val row = hBox().apply { gravity = Gravity.CENTER_VERTICAL }

        // Try to get logo from registry first
        val dynTok  = DynamicAltTokenRegistry.getTokenBySymbol(pos.market.symbol)
        val logoUrl = dynTok?.logoUrl?.ifBlank { DynamicAltTokenRegistry.getCoinGeckoLogoUrl(pos.market.symbol) }
            ?: DynamicAltTokenRegistry.getCoinGeckoLogoUrl(pos.market.symbol)

        val logoImg = android.widget.ImageView(this).apply {
            val sz = (40 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).also { lp -> lp.marginStart = 12; lp.marginEnd = 10 }
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            try { background = androidx.core.content.ContextCompat.getDrawable(this@CryptoAltActivity, R.drawable.token_logo_bg) } catch (_: Exception) {}
            if (logoUrl.isNotBlank()) {
                load(logoUrl) {
                    crossfade(true); placeholder(R.drawable.ic_token_placeholder)
                    error(R.drawable.ic_token_placeholder); allowHardware(false)
                    transformations(CircleCropTransformation())
                }
            } else setImageResource(R.drawable.ic_token_placeholder)
        }
        row.addView(logoImg)

        val bar = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(4, LinearLayout.LayoutParams.MATCH_PARENT).also { it.marginEnd = 10 }
            setBackgroundColor(pnlColor)
        }
        row.addView(bar)

        val info = vBox().apply { layoutParams = llp(0, wrap, 1f) }
        info.addView(tv("${pos.market.emoji} ${pos.market.symbol}  ${pos.direction.emoji} ${pos.leverageLabel}", 13f, white, bold = true))
        info.addView(tv("Entry: ${fmtPrice(pos.entryPrice)}  ·  ${timeFmt.format(Date(pos.openTime))}", 10f, muted, mono = true))
        val tpPct = if (pos.takeProfitPrice > 0 && pos.entryPrice > 0) ((pos.takeProfitPrice - pos.entryPrice) / pos.entryPrice * 100) else 0.0
        val slPct = if (pos.stopLossPrice > 0 && pos.entryPrice > 0) ((pos.entryPrice - pos.stopLossPrice) / pos.entryPrice * 100) else 0.0
        info.addView(tv("Size: ${"%.4f".format(pos.sizeSol)}◎${if (tpPct > 0) "  TP +${tpPct.toInt()}%  SL -${slPct.toInt()}%" else ""}", 10f, muted, mono = true))
        row.addView(info)

        val right = vBox().apply { gravity = Gravity.END }
        right.addView(tv("${if (pnlPct >= 0) "+" else ""}${"%.2f".format(pnlPct)}%", 14f, pnlColor, bold = true).apply { gravity = Gravity.END })
        right.addView(tv("${if (pnlSol >= 0) "+" else ""}${"%.4f".format(pnlSol)}◎", 11f, pnlColor, mono = true).apply { gravity = Gravity.END })
        if (solPrice > 0) right.addView(tv("≈\$${"%.2f".format(valueUsd)}", 10f, muted, mono = true).apply { gravity = Gravity.END })
        if (isOpen) right.addView(tv("● OPEN", 9f, green, bold = true).apply { gravity = Gravity.END; layoutParams = llp(wrap, wrap).apply { topMargin = 2 } })
        row.addView(right)
        card.addView(row)

        if (isOpen) {
            card.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("${pos.market.emoji} ${pos.market.symbol} — ${pos.direction.emoji} ${pos.leverageLabel}")
                    .setMessage("Size: ${"%.4f".format(pos.sizeSol)}◎\nEntry: ${fmtPrice(pos.entryPrice)}\nP&L: ${if (pnlPct >= 0) "+" else ""}${"%.2f".format(pnlPct)}%\n${if (solPrice > 0) "≈ USD: \$${"%.2f".format(valueUsd)}\n" else ""}Opened: ${sdf.format(Date(pos.openTime))}")
                    .setPositiveButton("🔴 Close") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            CryptoAltTrader.requestClose(pos.id)
                            withContext(Dispatchers.Main) { selectTab(2) }
                        }
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }

        llContent.addView(card)
        llContent.addView(thinDivider())
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SETTINGS TAB
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildSettingsTab() {
        // ── Engine Controls ────────────────────────────────────────────────────
        addSectionHeader("⚙️ Engine Controls", 0xFF9CA3AF.toInt())
        addToggleRow("🤖 Alt Trader Running", CryptoAltTrader.isRunning()) { on ->
            if (on) CryptoAltTrader.start() else CryptoAltTrader.stop(); selectTab(3)
        }
        addToggleRow("💰 Live Mode (real money)", CryptoAltTrader.isLiveMode()) { on ->
            if (on) AlertDialog.Builder(this)
                .setTitle("⚠️ Enable Live Trading?")
                .setMessage("This will use REAL SOL. Win rate must be > 55% before enabling.\n\nCurrent WR: ${CryptoAltTrader.getWinRate().toInt()}%")
                .setPositiveButton("Enable Live") { _, _ ->
                    CryptoAltTrader.setLiveMode(true)
                    // Propagate live mode to all sub-AIs
                    ShitCoinTraderAI.setTradingMode(false)
                    BlueChipTraderAI.setTradingMode(false)
                    ShitCoinExpress.init(false)
                    MoonshotTraderAI.setTradingMode(false)
                    ManipulatedTraderAI.init(false)
                    selectTab(3)
                }
                .setNegativeButton("Cancel") { _, _ -> selectTab(3) }.show()
            else {
                CryptoAltTrader.setLiveMode(false)
                ShitCoinTraderAI.setTradingMode(true)
                BlueChipTraderAI.setTradingMode(true)
                ShitCoinExpress.init(true)
                MoonshotTraderAI.setTradingMode(true)
                ManipulatedTraderAI.init(true)
                selectTab(3)
            }
        }

        // ── Sub-AI Mode Controls ───────────────────────────────────────────────
        addSectionHeader("🧠 Sub-AI Engine States", purple)
        val scStats  = ShitCoinTraderAI.getStats()
        val bcStats  = BlueChipTraderAI.getStats()
        val exStats  = ShitCoinExpress.getStats()
        val moWr     = MoonshotTraderAI.getWinRatePct()
        val maStats  = ManipulatedTraderAI.getStats()
        val maWr     = if (maStats.dailyWins + maStats.dailyLosses > 0) (maStats.dailyWins.toDouble() / (maStats.dailyWins + maStats.dailyLosses) * 100).toInt() else 0

        llContent.addView(buildSubAiCard("💩 ShitCoin",
            mode   = scStats.mode.name,
            paper  = scStats.isPaperMode,
            balance= scStats.balanceSol,
            wr     = scStats.winRate.toInt(),
            open   = scStats.activePositions,
            pnl    = scStats.dailyPnlSol))
        llContent.addView(thinDivider())

        llContent.addView(buildSubAiCard("🔵 BlueChip",
            mode   = bcStats.mode.name,
            paper  = bcStats.isPaperMode,
            balance= bcStats.balanceSol,
            wr     = bcStats.winRate.toInt(),
            open   = bcStats.activePositions,
            pnl    = bcStats.dailyPnlSol))
        llContent.addView(thinDivider())

        llContent.addView(buildSubAiCard("⚡ Express",
            mode   = if (exStats.isPaperMode) "PAPER" else "LIVE",
            paper  = exStats.isPaperMode,
            balance= 0.0,
            wr     = exStats.winRate.toInt(),
            open   = exStats.activeRides,
            pnl    = exStats.dailyPnlSol))
        llContent.addView(thinDivider())

        llContent.addView(buildSubAiCard("🌙 Moonshot",
            mode   = "ACTIVE",
            paper  = MoonshotTraderAI.isPaperMode,
            balance= MoonshotTraderAI.getBalance(MoonshotTraderAI.isPaperMode),
            wr     = moWr,
            open   = MoonshotTraderAI.getActivePositions().size,
            pnl    = MoonshotTraderAI.getDailyPnlSol()))
        llContent.addView(thinDivider())

        llContent.addView(buildSubAiCard("🎭 Manip",
            mode   = if (maStats.activeCount > 0) "POSITIONED" else "HUNTING",
            paper  = ManipulatedTraderAI.isPaperMode,
            balance= 0.0,
            wr     = maWr,
            open   = maStats.activeCount,
            pnl    = maStats.dailyPnlSol))
        llContent.addView(thinDivider())

        // ── Token Universe Feed Stats ──────────────────────────────────────────
        addSectionHeader("🌐 Token Universe (Scanner Feed)", teal)
        addInfoRow("Total Tokens",    "${DynamicAltTokenRegistry.getTokenCount()}")
        addInfoRow("Static (enum)",   "${DynamicAltTokenRegistry.getStaticCount()}")
        addInfoRow("Dynamic (live)",  "${DynamicAltTokenRegistry.getDynamicCount()}")
        addInfoRow("Trending Now",    "${DynamicAltTokenRegistry.getTrendingTokens().size}")
        addInfoRow("Boosted",         "${DynamicAltTokenRegistry.getBoostedTokens().size}")
        addInfoRow("Sources",         "DexScreener + CoinGecko + Jupiter")
        addInfoRow("Scan Depth",      "200 tokens/batch × rotating universe")
        addInfoRow("Scan Interval",   "30s dynamic + 12s PerpsMarket")

        // ── Overall Performance ────────────────────────────────────────────────
        addSectionHeader("📊 Alt Trader Performance", purple)
        addInfoRow("Balance",      "◎${"%.4f".format(CryptoAltTrader.getBalance())}")
        addInfoRow("Total PnL",    "${if (CryptoAltTrader.getTotalPnlSol() >= 0) "+" else ""}${"%.4f".format(CryptoAltTrader.getTotalPnlSol())}◎")
        addInfoRow("Win Rate",     "${CryptoAltTrader.getWinRate().toInt()}%")
        addInfoRow("Total Trades", "${CryptoAltTrader.getTotalTrades()}")
        addInfoRow("Phase",        getPhaseLabel())

        // ── Fluid Learning (shared across all layers) ──────────────────────────
        addSectionHeader("🧠 Fluid Learning — All Layers", blue)
        val flProgress = (FluidLearningAI.getMarketsLearningProgress() * 100).toInt()
        val mkProgress = try { (FluidLearningAI.getMarketsLearningProgress() * 100).toInt() } catch (_: Exception) { 0 }
        val flThresh   = try { FluidLearningAI.getMarketsSpotScoreThreshold() } catch (_: Exception) { 50 }
        addInfoRow("V3 Trades",      "${FluidLearningAI.getMarketsTradeCount()}")
        addInfoRow("Markets Trades", "${FluidLearningAI.getMarketsTradeCount()}")
        addInfoRow("V3 Progress",    "${flProgress}%")
        addInfoRow("Markets Progress","${mkProgress}%")
        addInfoRow("Conf Boost",     "+${(FluidLearningAI.getBootstrapConfidenceBoost() * 100).toInt()}%")
        addInfoRow("Size Mult",      "${"%.2f".format(FluidLearningAI.getBootstrapSizeMultiplier())}×")
        addInfoRow("Spot Score Thresh","${FluidLearningAI.getMarketsSpotScoreThreshold()}")
        addInfoRow("Cross-Learn",    "ShitCoin + BlueChip + Express + Moonshot + Manip + AltTrader → FluidLearningAI")

        llContent.addView(progressBar(blue, flProgress).apply { layoutParams = llp(match, 8).apply { leftMargin = 16; rightMargin = 16; topMargin = 4; bottomMargin = 8 } })

        // ── 30-Day Run ─────────────────────────────────────────────────────────
        addSectionHeader("📈 30-Day Run Tracker", teal)
        addInfoRow("Day",          if (RunTracker30D.isRunActive()) "Day ${RunTracker30D.getCurrentDay()} / 30" else "Not started")
        addInfoRow("Realized PnL", "${if (RunTracker30D.totalRealizedPnlSol >= 0) "+" else ""}${"%.4f".format(RunTracker30D.totalRealizedPnlSol)}◎")
        addInfoRow("Max Drawdown", "${"%.1f".format(RunTracker30D.maxDrawdown)}%")

        // ── Shadow FDG ─────────────────────────────────────────────────────────
        addSectionHeader("👁️ Shadow FDG Learning", indigo)
        val shadow = ShadowLearningEngine.getBlockedTradeStats()
        addInfoRow("Total Tracked",   "${shadow.totalTracked}")
        addInfoRow("Would Have Won",  "${shadow.wouldHaveWon}")
        addInfoRow("Would Have Lost", "${shadow.wouldHaveLost}")
        addInfoRow("Top Mode",        ShadowLearningEngine.getTopTrackedMode() ?: "—")

        // ── Sector Intelligence ────────────────────────────────────────────────
        addSectionHeader("🌡️ Sector Intelligence", amber)
        addInfoRow("Dominance",    CryptoAltScannerAI.getDominanceCycleSignal())
        addInfoRow("Fear & Greed", "${CryptoAltScannerAI.getCryptoFearGreed()}/100")
        val narratives = CryptoAltScannerAI.getActiveNarratives()
        addInfoRow("Narratives",   if (narratives.isNotEmpty()) narratives.take(3).joinToString(", ") else "—")

        // ── Universal Wallet Bridge ───────────────────────────────────────────────
        addSectionHeader("🌉 Universal Wallet Bridge", orange)
        addInfoRow("Supported Input", "SOL · USDC · USDT · WBTC · WETH · BNB · AVAX")
        addInfoRow("Bridge Route",    "Source → USDC → Target (Jupiter, max 2 hops)")
        addInfoRow("Bridges Done",    "${UniversalBridgeEngine.getBridgeStats()["executed"]}")
        addInfoRow("Bridge Failures", "${UniversalBridgeEngine.getBridgeStats()["failed"]}")
        addInfoRow("Success Rate",    "${UniversalBridgeEngine.getBridgeStats()["successRate"]}")

        llContent.addView(tv("🔍 Scan Wallet Capacity", 12f, teal, bold = true).apply {
            setBackgroundColor(card); setPadding(16, 14, 16, 14)
            gravity = Gravity.CENTER
            layoutParams = llp(match, wrap).apply { topMargin = 4; bottomMargin = 4 }
            setOnClickListener { refreshWalletCapacityAsync() }
        })
        llContent.addView(thinDivider())

        // ── AI Cross-Talk Tuning ──────────────────────────────────────────────────
        addSectionHeader("🔗 AI Cross-Talk Tuning", indigo)
        llContent.addView(tv(AICrossTalk.getStats(), 10f, muted, mono = true).apply {
            setPadding(16, 4, 16, 8)
            layoutParams = llp(match, wrap)
        })

        // ── Collective Learning Network ────────────────────────────────────────
        addSectionHeader("🌐 Collective Learning Network", amber)
        val clStats = CollectiveLearning.getStats()
        addInfoRow("Network",            if (CollectiveLearning.isEnabled()) "CONNECTED" else "OFFLINE (needs TursoDB)")
        addInfoRow("Patterns Cached",    "${clStats["patterns"]}")
        addInfoRow("Mode Stats",         "${clStats["modeStats"]}")
        addInfoRow("Blacklisted Tokens", "${clStats["blacklistedTokens"]}")
        val bestMode = try { CollectiveLearning.getBestMode("NEUTRAL", "MID") } catch (_: Exception) { null }
        if (bestMode != null) addInfoRow("Best Mode", bestMode)

        // ── CollectiveIntelligenceAI Mode Recommendations ────────────────────
        addSectionHeader("🧠 Collective Intelligence Recommendations", purple)
        val ciS = CollectiveIntelligenceAI.getStats()
        addInfoRow("CI Patterns",   "${ciS.cachedPatterns}")
        addInfoRow("CI Modes",      "${ciS.cachedModes}")
        addInfoRow("Conf Threshold","${ciS.dynamicConfThreshold}%")
        addInfoRow("Anomalies",     "${ciS.anomaliesDetected}")
        listOf("ShitCoin", "BlueChip", "Express", "Moonshot", "Manip").forEach { mode ->
            val rec = CollectiveIntelligenceAI.getModeRecommendation(mode)
            val recColor = when (rec.name) { "PREFER" -> green; "AVOID" -> red; else -> muted }
            llContent.addView(hBox(card, 16, 8).apply {
                layoutParams = llp(match, wrap).apply { bottomMargin = 1 }
                addView(tv("  $mode", 12f, muted).apply { layoutParams = llp(0, wrap, 1f) })
                addView(tv(rec.name, 12f, recColor, bold = true))
            })
            llContent.addView(thinDivider())
        }

        // ── Adaptive Learning Engine ─────────────────────────────────────────
        addSectionHeader("📐 Adaptive Learning", teal)
        addInfoRow("Trade Count",  "${AdaptiveLearningEngine.getTradeCount()}")
        addInfoRow("Status",       AdaptiveLearningEngine.getStatus())
        val wts = try { AdaptiveLearningEngine.getDetailedWeights() } catch (_: Exception) { emptyMap<String, Double>() }
        wts.entries.sortedByDescending { it.value }.take(6).forEach { (k, v) ->
            val col = when { v > 1.5 -> green; v < 0.5 -> red; else -> muted }
            llContent.addView(hBox(card, 16, 10).apply {
                layoutParams = llp(match, wrap).apply { bottomMargin = 2 }
                gravity = Gravity.CENTER_VERTICAL
                addView(tv("  $k", 13f, muted).apply { layoutParams = llp(0, wrap, 1f) })
                addView(tv("${"%.3f".format(v)}×", 13f, col, mono = true))
            })
            llContent.addView(thinDivider())
        }

        // ── Behavior Learning Insights ───────────────────────────────────────
        addSectionHeader("🧬 Behavior Learning", pink)
        val bli = BehaviorLearning.getInsights()
        addInfoRow("Total Trades",  "${BehaviorLearning.getTradeCount()}")
        addInfoRow("Good Patterns", "${bli.totalGoodPatterns}")
        addInfoRow("Bad Patterns",  "${bli.totalBadPatterns}")
        addInfoRow("Best Mode",     bli.bestTradingMode)
        addInfoRow("Worst Mode",    bli.worstTradingMode)
        if (bli.topGoodPatterns.isNotEmpty()) {
            llContent.addView(tv("  ✅ Top Winning Patterns", 11f, green, bold = true).apply { setPadding(16, 8, 16, 4) })
            bli.topGoodPatterns.take(3).forEach { p ->
                addInfoRow("  ${p.signature.take(24)}", "WR:${"%.0f".format(p.winRate)}% avg:${"%.1f".format(p.avgPnl)}%")
            }
        }
        if (bli.topBadPatterns.isNotEmpty()) {
            llContent.addView(tv("  ⛔ Top Losing Patterns", 11f, red, bold = true).apply { setPadding(16, 8, 16, 4) })
            bli.topBadPatterns.take(3).forEach { p ->
                addInfoRow("  ${p.signature.take(24)}", "WR:${"%.0f".format(p.winRate)}% avg:${"%.1f".format(p.avgPnl)}%")
            }
        }

        // ── Perps Learning Bridge ─────────────────────────────────────────────
        addSectionHeader("🌉 Cross-Layer Bridge", orange)
        addInfoRow("Learning Events",  "${PerpsLearningBridge.getTotalLearningEvents()}")
        addInfoRow("Cross-Layer Syncs","${PerpsLearningBridge.getCrossLayerSyncs()}")
        addInfoRow("Connected Layers", "${PerpsLearningBridge.getConnectedLayerCount()}")
        llContent.addView(tv(PerpsLearningBridge.getDiagnostics(), 9f, muted, mono = true).apply {
            setPadding(16, 4, 16, 8)
            layoutParams = llp(match, wrap)
        })

        // ── Manual Controls ────────────────────────────────────────────────────
        addSectionHeader("🔧 Manual Actions", 0xFF9CA3AF.toInt())

        llContent.addView(hBox(card, 12, 12).apply {
            layoutParams = llp(match, wrap).apply { topMargin = 4; bottomMargin = 4 }

            addView(tv("🔄 Refresh Universe", 12f, teal, bold = true).apply {
                setBackgroundColor(0xFF0D2020.toInt()); setPadding(12, 10, 12, 10)
                gravity = Gravity.CENTER
                layoutParams = llp(0, wrap, 1f).apply { marginEnd = 4 }
                setOnClickListener {
                    lifecycleScope.launch(Dispatchers.IO) {
                        DynamicAltTokenRegistry.runDiscoveryCycle()
                        withContext(Dispatchers.Main) { selectTab(3) }
                    }
                }
            })

            addView(tv("🔄 Refresh UI", 12f, blue, bold = true).apply {
                setBackgroundColor(0xFF0D1020.toInt()); setPadding(12, 10, 12, 10)
                gravity = Gravity.CENTER
                layoutParams = llp(0, wrap, 1f)
                setOnClickListener { buildFullDashboard() }
            })
        })
    }

    private fun buildSubAiCard(name: String, mode: String, paper: Boolean, balance: Double, wr: Int, open: Int, pnl: Double): LinearLayout {
        val pnlColor = if (pnl >= 0) green else red
        val wrColor  = if (wr >= 55) green else if (wr >= 40) amber else red
        return hBox(card, 14, 10).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(tv(name, 12f, white, bold = true).apply { layoutParams = llp(0, wrap, 2f) })
            addView(tv(if (paper) "PAPER" else "LIVE", 9f, if (paper) amber else green, bold = true).apply {
                setBackgroundColor(if (paper) 0xFF2D1A00.toInt() else 0xFF002D0D.toInt())
                setPadding(5, 2, 5, 2); layoutParams = llp(wrap, wrap).apply { marginEnd = 6 }
            })
            addView(tv("$wr%WR", 11f, wrColor, mono = true).apply { layoutParams = llp(wrap, wrap).apply { marginEnd = 6 } })
            addView(tv("$open open", 10f, blue, mono = true).apply { layoutParams = llp(wrap, wrap).apply { marginEnd = 6 } })
            if (balance > 0) addView(tv("◎${"%.2f".format(balance)}", 10f, white, mono = true))
        }
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // DIALOGS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun showAddToWatchlistDialog() {
        val topTokens = DynamicAltTokenRegistry.getAllTokens(SortMode.QUALITY).take(100)
        val labels    = topTokens.map { "${it.emoji} ${it.symbol} — ${it.name.take(16)}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Add Token to Watchlist")
            .setItems(labels) { _, which ->
                val tok = topTokens[which]
                WatchlistEngine.addToWatchlist(tok.symbol)
                Toast.makeText(this, "📌 ${tok.symbol} added", Toast.LENGTH_SHORT).show()
                if (currentTab == 1) selectTab(1)
            }
            .setNegativeButton("Cancel", null).show()
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

    private fun fmtPrice(price: Double): String = when {
        price == 0.0  -> "—"
        price > 1000  -> "$%.0f".format(price)
        price > 1     -> "$%.4f".format(price)
        price > 0.001 -> "$%.6f".format(price)
        else          -> "$%.8f".format(price)
    }

    private fun Double.fmtVol(): String = when {
        this == 0.0 -> "—"
        this >= 1e9  -> "$%.1fB".format(this / 1e9)
        this >= 1e6  -> "$%.1fM".format(this / 1e6)
        this >= 1e3  -> "$%.0fK".format(this / 1e3)
        else         -> "$%.0f".format(this)
    }

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

    private val match = LinearLayout.LayoutParams.MATCH_PARENT
    private val wrap  = LinearLayout.LayoutParams.WRAP_CONTENT
    private fun llp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private fun getPhaseLabel(): String {
        // Use Markets-specific trade counter so progress is independent of meme bot
        val trades = FluidLearningAI.getMarketsTradeCount()
        val wr     = CryptoAltTrader.getWinRate()
        return when {
            trades == 0   -> "INIT — tap ▶ Run"
            trades < 50   -> "SEEDING ($trades/50)"
            trades < 500  -> "BOOTSTRAP ($trades/500)"
            trades < 1500 -> "LEARNING ($trades/1500)"
            trades < 3000 -> "VALIDATING ($trades/3000)"
            trades < 5000 -> "MATURING ($trades/5000)"
            wr >= 55.0    -> "READY ✅"
            else          -> "MATURING ($trades)"
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




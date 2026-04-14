package com.lifecyclebot.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lifecyclebot.R
import com.lifecyclebot.perps.CryptoAltTrader
import com.lifecyclebot.perps.CryptoAltScannerAI
import com.lifecyclebot.perps.PerpsMarket
import com.lifecyclebot.perps.PerpsMarketDataFetcher
import com.lifecyclebot.perps.PerpsMarketData
import com.lifecyclebot.perps.WatchlistEngine
import com.lifecyclebot.v3.scoring.FluidLearningAI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * ═══════════════════════════════════════════════════════════════════════
 * CRYPTO ALTS ACTIVITY — V2.0  (AATE Unified UI)
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Full AATE-style tile dashboard for cross-chain alt tokens.
 * Mirrors the main MainActivity layout with:
 *   • Hero balance / PnL / phase banner
 *   • Live Readiness tile (readiness arc + progress bar)
 *   • 30-Day Proof Run tile
 *   • Strategy tiles: Alt V3 | Alt Treasury | Alt Blue | Alt Express | Alt Moonshot
 *   • Hive Mind / Dominance cycle / Narrative panel
 *   • Layer Confidence / Sector Heat dashboard
 *   • Top signal cards (live scanner feed)
 *   • Bottom tabs: Scanner | Watchlist | Positions | Settings
 *
 * Chains: BNB ▪ ETH ▪ SOL ▪ Polygon
 */
class CryptoAltActivity : AppCompatActivity() {

    // ─── Colours (match AATE palette) ────────────────────────────────────────
    private val bg      = 0xFF0A0A0F.toInt()
    private val card    = 0xFF1A1A2E.toInt()
    private val card2   = 0xFF12122A.toInt()
    private val divider = 0xFF1F2937.toInt()
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
    private val sdf     = SimpleDateFormat("MMM dd HH:mm", Locale.US)

    // ─── Views ────────────────────────────────────────────────────────────────
    private lateinit var scrollView : ScrollView
    private lateinit var llMain     : LinearLayout   // master vertical container
    private lateinit var progressBar: ProgressBar

    // Hero
    private lateinit var tvHeroBalance  : TextView
    private lateinit var tvHeroPnl      : TextView
    private lateinit var tvHeroWinRate  : TextView
    private lateinit var tvHeroPhase    : TextView
    private lateinit var tvHeroTrades   : TextView

    // Tabs
    private lateinit var tabScanner   : TextView
    private lateinit var tabWatchlist : TextView
    private lateinit var tabPositions : TextView
    private lateinit var tabSettings  : TextView

    // Tab content container
    private lateinit var llTabContent : LinearLayout

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

        // Wire existing XML views
        progressBar  = findViewById(R.id.progressCryptoAlt)
        tabScanner   = findViewById(R.id.tabCryptoAltScanner)
        tabWatchlist = findViewById(R.id.tabCryptoAltWatchlist)
        tabPositions = findViewById(R.id.tabCryptoAltPositions)
        tabSettings  = findViewById(R.id.tabCryptoAltSettings)

        WatchlistEngine.init(applicationContext)

        findViewById<View>(R.id.btnCryptoAltBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnCryptoAltScan).setOnClickListener { refreshDashboard() }
        findViewById<View>(R.id.btnCryptoAltAdd).setOnClickListener { showAddToWatchlistDialog() }

        tabScanner.setOnClickListener   { selectTab(0) }
        tabWatchlist.setOnClickListener { selectTab(1) }
        tabPositions.setOnClickListener { selectTab(2) }
        tabSettings.setOnClickListener  { selectTab(3) }

        // The existing llCryptoAltContent becomes our tile container
        llTabContent = findViewById(R.id.llCryptoAltContent)

        buildFullDashboard()
        startAutoRefresh()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AUTO REFRESH
    // ═══════════════════════════════════════════════════════════════════════════

    private fun startAutoRefresh() {
        lifecycleScope.launch {
            while (isActive) {
                delay(15_000L)
                withContext(Dispatchers.Main) { refreshHeroStats() }
            }
        }
    }

    private fun refreshHeroStats() {
        if (!::tvHeroBalance.isInitialized) return
        val bal      = CryptoAltTrader.getBalance()
        val pnl      = CryptoAltTrader.getTotalPnlSol()
        val winRate  = CryptoAltTrader.getWinRate()
        val trades   = CryptoAltTrader.getTotalTrades()
        val phase    = getPhaseLabel()

        tvHeroBalance.text = "◎ ${"%.4f".format(bal)}"
        tvHeroPnl.text     = "${if (pnl >= 0) "+" else ""}${"%.4f".format(pnl)} SOL"
        tvHeroPnl.setTextColor(if (pnl >= 0) green else red)
        tvHeroWinRate.text = "${"%.1f".format(winRate)}% WR"
        tvHeroTrades.text  = "$trades trades"
        tvHeroPhase.text   = phase
        tvHeroPhase.setTextColor(phaseColor(phase))
    }

    private fun refreshDashboard() {
        buildFullDashboard()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FULL DASHBOARD BUILD
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildFullDashboard() {
        llTabContent.removeAllViews()

        // 1. Hero balance section
        buildHeroSection()

        // 2. Live Readiness tile
        buildReadinessTile()

        // 3. 30-Day Proof Run tile
        buildProofRunTile()

        // 4. Strategy tiles row 1 — Alt V3 | Alt Treasury
        buildStrategyRow(
            tile1Label = "🧠 Alt V3",
            tile1Color = purple,
            tile1Sub   = "Cross-chain AI",
            tile2Label = "🏦 Alt Treasury",
            tile2Color = teal,
            tile2Sub   = "Stable alts"
        )

        // 5. Strategy tiles row 2 — Alt Blue | Alt Express
        buildStrategyRow(
            tile1Label = "💎 Alt Blue",
            tile1Color = blue,
            tile1Sub   = "Blue chip alts",
            tile2Label = "⚡ Alt Express",
            tile2Color = amber,
            tile2Sub   = "Fast movers"
        )

        // 6. Strategy tile row 3 — Alt Moonshot | Alt Meme
        buildStrategyRow(
            tile1Label = "🚀 Alt Moonshot",
            tile1Color = pink,
            tile1Sub   = "High risk / reward",
            tile2Label = "🐸 Alt Meme",
            tile2Color = green,
            tile2Sub   = "Meme season plays"
        )

        // 7. Hive Mind / Dominance / Narratives panel
        buildHiveMindPanel()

        // 8. Layer Confidence / Sector Heat dashboard
        buildLayerConfidencePanel()

        // 9. Signal cards (live scanner)
        buildSignalCards()

        // 10. Tab content area (Scanner / Watchlist / Positions / Settings)
        addSectionDivider()
        buildTabBar()
        selectTab(currentTab)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HERO SECTION
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildHeroSection() {
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(card2)
            setPadding(20, 18, 20, 18)
            layoutParams = llp(matchParent, wrapContent).apply { bottomMargin = 3 }
        }

        // Balance row
        val balRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val bal     = CryptoAltTrader.getBalance()
        val pnl     = CryptoAltTrader.getTotalPnlSol()
        val winRate = CryptoAltTrader.getWinRate()
        val trades  = CryptoAltTrader.getTotalTrades()
        val phase   = getPhaseLabel()

        tvHeroBalance = TextView(this).apply {
            text      = "◎ ${"%.4f".format(bal)}"
            textSize  = 28f
            setTextColor(white)
            typeface  = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = llp(0, wrapContent, 1f)
        }

        // Mode badge
        val modeBadge = TextView(this).apply {
            text = if (CryptoAltTrader.isLiveMode()) "● LIVE" else "● PAPER"
            textSize = 10f
            setTextColor(if (CryptoAltTrader.isLiveMode()) green else amber)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setBackgroundColor(if (CryptoAltTrader.isLiveMode()) 0xFF052E16.toInt() else 0xFF451A03.toInt())
            setPadding(8, 4, 8, 4)
        }

        balRow.addView(tvHeroBalance)
        balRow.addView(modeBadge)
        hero.addView(balRow)

        // PnL + win rate row
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = llp(matchParent, wrapContent).apply { topMargin = 4 }
        }

        tvHeroPnl = TextView(this).apply {
            text     = "${if (pnl >= 0) "+" else ""}${"%.4f".format(pnl)} SOL"
            textSize = 14f
            setTextColor(if (pnl >= 0) green else red)
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = llp(0, wrapContent, 1f)
        }

        tvHeroWinRate = TextView(this).apply {
            text     = "${"%.1f".format(winRate)}% WR"
            textSize = 12f
            setTextColor(purple)
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = llp(0, wrapContent, 1f)
        }

        tvHeroTrades = TextView(this).apply {
            text     = "$trades trades"
            textSize = 12f
            setTextColor(muted)
            typeface = android.graphics.Typeface.MONOSPACE
        }

        statsRow.addView(tvHeroPnl)
        statsRow.addView(tvHeroWinRate)
        statsRow.addView(tvHeroTrades)
        hero.addView(statsRow)

        // Phase label
        tvHeroPhase = TextView(this).apply {
            text     = phase
            textSize = 10f
            setTextColor(phaseColor(phase))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = llp(matchParent, wrapContent).apply { topMargin = 6 }
        }
        hero.addView(tvHeroPhase)

        // Chain chips row
        val chainRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = llp(matchParent, wrapContent).apply { topMargin = 10 }
        }
        for ((chain, label, color) in listOf(
            Triple("BNB",   "BNB",     0xFFF59E0B.toInt()),
            Triple("ETH",   "ETH",     0xFF60A5FA.toInt()),
            Triple("SOL",   "SOL",     0xFF9333EA.toInt()),
            Triple("MATIC", "POLY",    0xFF818CF8.toInt())
        )) {
            chainRow.addView(TextView(this).apply {
                text = label; textSize = 9f; gravity = Gravity.CENTER
                setTextColor(color)
                setBackgroundColor(0xFF0D0D1A.toInt())
                setPadding(8, 3, 8, 3)
                layoutParams = llp(0, wrapContent, 1f).apply { marginEnd = 3 }
            })
        }
        hero.addView(chainRow)

        llTabContent.addView(hero)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LIVE READINESS TILE
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildReadinessTile() {
        val phase     = getPhaseLabel()
        val trades    = FluidLearningAI.getMarketsTradeCount()
        val target    = when {
            trades < 500  -> 500
            trades < 1500 -> 1500
            trades < 3000 -> 3000
            trades < 5000 -> 5000
            else          -> 5000
        }
        val progress  = ((trades.toFloat() / target) * 100).toInt().coerceIn(0, 100)
        val winRate   = CryptoAltTrader.getWinRate()
        val readyText = when (phase) {
            "BOOTSTRAP"  -> "Collecting initial alt data..."
            "LEARNING"   -> "Learning alt market patterns..."
            "VALIDATING" -> "Validating cross-chain signals..."
            "MATURING"   -> "Maturing AI strategy..."
            "READY"      -> "🟢 Ready for live trading!"
            else         -> "Building confidence..."
        }

        val tile = buildTileCard(purple)
        addTileHeader(tile, "📡 Live Readiness", phase, phaseColor(phase))

        // Progress bar
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; this.progress = progress
            progressTintList = android.content.res.ColorStateList.valueOf(purple)
            layoutParams = llp(matchParent, 6).apply { topMargin = 8; bottomMargin = 4 }
        }
        tile.addView(pb)

        // Stats row
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = llp(matchParent, wrapContent).apply { topMargin = 4 }
        }
        addStatChip(row, "Trades", "$trades / $target", muted, 1f)
        addStatChip(row, "Win Rate", "${"%.1f".format(winRate)}%", if (winRate >= 55) green else amber, 1f)
        addStatChip(row, "Progress", "$progress%", purple, 1f)
        tile.addView(row)

        // Description
        tile.addView(TextView(this).apply {
            text = readyText; textSize = 10f; setTextColor(muted)
            layoutParams = llp(matchParent, wrapContent).apply { topMargin = 6 }
        })

        llTabContent.addView(tile)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 30-DAY PROOF RUN TILE
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildProofRunTile() {
        val trades  = CryptoAltTrader.getTotalTrades()
        val winRate = CryptoAltTrader.getWinRate()
        val pnl     = CryptoAltTrader.getTotalPnlSol()
        val openPos = CryptoAltTrader.getAllPositions().count { it.closeTime == null }

        val tile = buildTileCard(teal)
        addTileHeader(tile, "📊 30-Day Proof Run", "ALTS", teal)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = llp(matchParent, wrapContent).apply { topMargin = 8 }
        }
        addStatChip(row, "Total Trades", "$trades", white, 1f)
        addStatChip(row, "Win Rate",     "${"%.1f".format(winRate)}%", if (winRate >= 55) green else amber, 1f)
        addStatChip(row, "Total PnL",    "${if (pnl >= 0) "+" else ""}${"%.3f".format(pnl)}◎", if (pnl >= 0) green else red, 1f)
        addStatChip(row, "Open",         "$openPos pos", blue, 1f)
        tile.addView(row)

        // Target indicator
        val targetWr = 55.0
        val gap = targetWr - winRate
        tile.addView(TextView(this).apply {
            text = if (winRate >= targetWr) "✅ Win rate target met — ready for live!"
                   else "Need ${String.format("%.1f", gap)}% more win rate for live approval"
            textSize = 10f
            setTextColor(if (winRate >= targetWr) green else muted)
            layoutParams = llp(matchParent, wrapContent).apply { topMargin = 6 }
        })

        llTabContent.addView(tile)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STRATEGY TILE ROWS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildStrategyRow(
        tile1Label: String, tile1Color: Int, tile1Sub: String,
        tile2Label: String, tile2Color: Int, tile2Sub: String
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = llp(matchParent, wrapContent).apply { bottomMargin = 3 }
        }

        val trades   = CryptoAltTrader.getTotalTrades()
        val winRate  = CryptoAltTrader.getWinRate()
        val pnl      = CryptoAltTrader.getTotalPnlSol()
        val openPos  = CryptoAltTrader.getAllPositions().count { it.closeTime == null }

        // Tile 1
        val t1 = buildMiniTile(tile1Label, tile1Color, tile1Sub, trades / 2, winRate, pnl / 2, openPos / 2)
        t1.layoutParams = llp(0, wrapContent, 1f).apply { marginEnd = 3 }
        row.addView(t1)

        // Tile 2
        val t2 = buildMiniTile(tile2Label, tile2Color, tile2Sub, trades / 2, winRate, pnl / 2, openPos / 2)
        t2.layoutParams = llp(0, wrapContent, 1f)
        row.addView(t2)

        llTabContent.addView(row)
    }

    private fun buildMiniTile(
        label: String, accentColor: Int, sub: String,
        trades: Int, winRate: Double, pnl: Double, openPos: Int
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(card)
            setPadding(14, 12, 14, 12)

            // Header
            addView(TextView(this@CryptoAltActivity).apply {
                text = label; textSize = 12f; setTextColor(accentColor)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@CryptoAltActivity).apply {
                text = sub; textSize = 9f; setTextColor(muted)
                layoutParams = llp(matchParent, wrapContent).apply { topMargin = 2 }
            })

            // Divider line
            addView(View(this@CryptoAltActivity).apply {
                setBackgroundColor(accentColor)
                layoutParams = llp(matchParent, 1).apply { topMargin = 6; bottomMargin = 6 }
                alpha = 0.3f
            })

            // Stats
            addView(TextView(this@CryptoAltActivity).apply {
                text = "$trades trades  •  ${"%.1f".format(winRate)}% WR"
                textSize = 9f; setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
            })
            addView(TextView(this@CryptoAltActivity).apply {
                text = "${if (pnl >= 0) "+" else ""}${"%.3f".format(pnl)}◎"
                textSize = 11f; setTextColor(if (pnl >= 0) green else red)
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = llp(matchParent, wrapContent).apply { topMargin = 2 }
            })
            addView(TextView(this@CryptoAltActivity).apply {
                text = "$openPos open"
                textSize = 9f; setTextColor(blue)
                layoutParams = llp(matchParent, wrapContent).apply { topMargin = 1 }
            })
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HIVE MIND / DOMINANCE / NARRATIVES PANEL
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildHiveMindPanel() {
        val tile = buildTileCard(orange)
        addTileHeader(tile, "🐝 Hive Mind — Alt Intelligence", "AI", orange)

        // Dominance signal
        val dominance = CryptoAltScannerAI.getDominanceCycleSignal()
        val domColor  = when (dominance) {
            "ALT_SEASON"    -> green
            "BTC_DOMINANCE" -> red
            else            -> amber
        }
        val domRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = llp(matchParent, wrapContent).apply { topMargin = 8 }
        }
        domRow.addView(TextView(this).apply {
            text = "Dominance Cycle:"; textSize = 11f; setTextColor(muted)
            layoutParams = llp(0, wrapContent, 1f)
        })
        domRow.addView(TextView(this).apply {
            text = dominance; textSize = 11f; setTextColor(domColor)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        tile.addView(domRow)

        // Fear & Greed
        val fg     = CryptoAltScannerAI.getCryptoFearGreed()
        val fgLabel = when {
            fg >= 75 -> "Extreme Greed 🔥"
            fg >= 55 -> "Greed 📈"
            fg >= 45 -> "Neutral 😐"
            fg >= 25 -> "Fear 📉"
            else     -> "Extreme Fear 🧊"
        }
        val fgColor = when {
            fg >= 75 -> red
            fg >= 55 -> orange
            fg >= 45 -> amber
            fg >= 25 -> blue
            else     -> teal
        }
        val fgRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = llp(matchParent, wrapContent).apply { topMargin = 4 }
        }
        fgRow.addView(TextView(this).apply {
            text = "Fear & Greed ($fg):"; textSize = 11f; setTextColor(muted)
            layoutParams = llp(0, wrapContent, 1f)
        })
        fgRow.addView(TextView(this).apply {
            text = fgLabel; textSize = 11f; setTextColor(fgColor)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        tile.addView(fgRow)

        // Active Narratives
        val narratives = CryptoAltScannerAI.getActiveNarratives()
        if (narratives.isNotEmpty()) {
            tile.addView(View(this).apply {
                setBackgroundColor(divider)
                layoutParams = llp(matchParent, 1).apply { topMargin = 8; bottomMargin = 4 }
            })
            tile.addView(TextView(this).apply {
                text = "🔥 Active Narratives"; textSize = 10f; setTextColor(orange)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            val narRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = llp(matchParent, wrapContent).apply { topMargin = 4 }
            }
            narratives.take(4).forEach { nar ->
                narRow.addView(TextView(this).apply {
                    text = nar.replace("_", " "); textSize = 9f
                    setTextColor(amber); setBackgroundColor(0xFF451A03.toInt())
                    setPadding(6, 3, 6, 3)
                    layoutParams = llp(wrapContent, wrapContent).apply { marginEnd = 4 }
                })
            }
            tile.addView(narRow)
        }

        llTabContent.addView(tile)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LAYER CONFIDENCE / SECTOR HEAT DASHBOARD
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildLayerConfidencePanel() {
        val tile = buildTileCard(blue)
        addTileHeader(tile, "🧬 Layer Confidence — Alt AI", "LAYERS", blue)

        // Sector heat grid
        tile.addView(TextView(this).apply {
            text = "Sector Heat Map"; textSize = 10f; setTextColor(blue)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = llp(matchParent, wrapContent).apply { topMargin = 8; bottomMargin = 4 }
        })

        val sectors = listOf(
            Triple("MEME",   CryptoAltScannerAI.getSectorHeat("DOGE"),  pink),
            Triple("DeFi",   CryptoAltScannerAI.getSectorHeat("AAVE"),  teal),
            Triple("L1",     CryptoAltScannerAI.getSectorHeat("ADA"),   purple),
            Triple("L2",     CryptoAltScannerAI.getSectorHeat("ARB"),   blue),
            Triple("Gaming", CryptoAltScannerAI.getSectorHeat("AXS"),   green),
            Triple("AI",     CryptoAltScannerAI.getSectorHeat("RENDER"),amber)
        )

        // Two rows of 3
        for (i in 0 until 2) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = llp(matchParent, wrapContent).apply { bottomMargin = 3 }
            }
            for (j in 0 until 3) {
                val idx = i * 3 + j
                if (idx >= sectors.size) break
                val (sectorName, heat, color) = sectors[idx]
                val heatPct = (heat * 100).toInt()
                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(card2)
                    setPadding(8, 6, 8, 6)
                    layoutParams = llp(0, wrapContent, 1f).apply { marginEnd = if (j < 2) 3 else 0 }

                    addView(TextView(this@CryptoAltActivity).apply {
                        text = sectorName; textSize = 9f; setTextColor(color)
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    })
                    addView(ProgressBar(this@CryptoAltActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100; progress = heatPct
                        progressTintList = android.content.res.ColorStateList.valueOf(color)
                        layoutParams = llp(matchParent, 4).apply { topMargin = 3; bottomMargin = 2 }
                    })
                    addView(TextView(this@CryptoAltActivity).apply {
                        text = "$heatPct%"; textSize = 8f; setTextColor(muted)
                        typeface = android.graphics.Typeface.MONOSPACE
                    })
                })
            }
            tile.addView(row)
        }

        // Alt beta summary
        tile.addView(View(this).apply {
            setBackgroundColor(divider)
            layoutParams = llp(matchParent, 1).apply { topMargin = 6; bottomMargin = 4 }
        })

        val betaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = llp(matchParent, wrapContent)
        }
        listOf("BTC" to "BTC", "ETH" to "ETH", "DOGE" to "DOGE", "SHIB" to "SHIB").forEach { (sym, label) ->
            val beta = CryptoAltScannerAI.getAltBeta(sym)
            betaRow.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = llp(0, wrapContent, 1f)

                addView(TextView(this@CryptoAltActivity).apply {
                    text = label; textSize = 8f; setTextColor(muted); gravity = Gravity.CENTER
                })
                addView(TextView(this@CryptoAltActivity).apply {
                    text = "${"%.1f".format(beta)}β"
                    textSize = 10f
                    setTextColor(if (beta > 1.5) red else if (beta > 1.0) amber else green)
                    typeface = android.graphics.Typeface.MONOSPACE
                    gravity = Gravity.CENTER
                })
            })
        }
        tile.addView(betaRow)

        llTabContent.addView(tile)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SIGNAL CARDS (LIVE SCANNER FEED)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildSignalCards() {
        addSectionHeader("🔥 Top Alt Signals", purple)

        val signalContainer = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            layoutParams = llp(matchParent, wrapContent)
        }
        llTabContent.addView(signalContainer)

        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            val results = mutableListOf<Pair<PerpsMarket, PerpsMarketData>>()
            for (market in altMarkets.take(30)) {
                try { results.add(market to PerpsMarketDataFetcher.getMarketData(market)) } catch (_: Exception) {}
            }
            results.sortByDescending { kotlin.math.abs(it.second.priceChange24hPct) }

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                signalContainer.removeAllViews()
                for ((market, data) in results.take(8)) {
                    signalContainer.addView(buildSignalCard(market, data))
                }
            }
        }
    }

    private fun buildSignalCard(market: PerpsMarket, data: PerpsMarketData): LinearLayout {
        val change   = data.priceChange24hPct
        val heat     = CryptoAltScannerAI.getSectorHeat(market.symbol)
        val beta     = CryptoAltScannerAI.getAltBeta(market.symbol)
        val signal   = when {
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

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(card)
            setPadding(16, 12, 16, 12)
            layoutParams = llp(matchParent, wrapContent).apply { bottomMargin = 2 }

            // Symbol
            addView(TextView(this@CryptoAltActivity).apply {
                text = "${market.emoji} ${market.symbol}"
                textSize = 13f; setTextColor(white)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = llp(0, wrapContent, 2f)
            })

            // Price
            addView(TextView(this@CryptoAltActivity).apply {
                text = if (data.price > 1) "$%.2f".format(data.price) else "$%.5f".format(data.price)
                textSize = 11f; setTextColor(white)
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = llp(0, wrapContent, 2f)
            })

            // Change %
            addView(TextView(this@CryptoAltActivity).apply {
                text = "${if (change >= 0) "+" else ""}%.1f%%".format(change)
                textSize = 11f; setTextColor(if (change >= 0) green else red)
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = llp(0, wrapContent, 1.5f)
            })

            // Signal
            addView(TextView(this@CryptoAltActivity).apply {
                text = signal; textSize = 9f; setTextColor(sigColor)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = llp(0, wrapContent, 2f)
            })

            // Beta badge
            addView(TextView(this@CryptoAltActivity).apply {
                text = "${"%.1f".format(beta)}β"
                textSize = 9f; setTextColor(if (beta > 1.5) red else muted)
                typeface = android.graphics.Typeface.MONOSPACE
            })

            setOnClickListener { showSignalDialog(market, data, signal, beta) }
        }
    }

    private fun showSignalDialog(market: PerpsMarket, data: PerpsMarketData, signal: String, beta: Double) {
        val change   = data.priceChange24hPct
        val heat     = (CryptoAltScannerAI.getSectorHeat(market.symbol) * 100).toInt()
        val price    = if (data.price > 1) "$%.2f".format(data.price) else "$%.6f".format(data.price)
        val vol      = "%.0fM".format(data.volume24h / 1_000_000)
        AlertDialog.Builder(this)
            .setTitle("${market.emoji} ${market.symbol} — Alt Signal")
            .setMessage(
                "Price: $price\n" +
                "24h Change: ${if (change >= 0) "+" else ""}%.1f%%\n".format(change) +
                "Volume: $$vol\n" +
                "Signal: $signal\n" +
                "Sector Heat: $heat%\n" +
                "BTC Beta: ${"%.2f".format(beta)}x"
            )
            .setPositiveButton("⭐ Watchlist") { _, _ -> addSymbolToWatchlist(market.symbol) }
            .setNegativeButton("Close", null)
            .show()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BOTTOM TAB BAR
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildTabBar() {
        // Tab bar is already in XML — just update tab content below it
        llTabContent.addView(View(this).apply {
            setBackgroundColor(divider)
            layoutParams = llp(matchParent, 1)
        })
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TABS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun selectTab(tab: Int) {
        currentTab = tab
        val tabs   = listOf(tabScanner, tabWatchlist, tabPositions, tabSettings)
        val colors = listOf(purple, green, amber, 0xFF9CA3AF.toInt())
        val bgs    = listOf(0xFF1A1528.toInt(), 0xFF1A2E1A.toInt(), 0xFF2E2A1A.toInt(), 0xFF1F2937.toInt())
        tabs.forEachIndexed { i, tv ->
            if (i == tab) { tv.setTextColor(colors[i]); tv.setBackgroundColor(bgs[i]) }
            else          { tv.setTextColor(muted);     tv.setBackgroundColor(0) }
        }

        // Remove previous tab content (everything added after the divider)
        val startIdx = llTabContent.indexOfChild(
            llTabContent.findViewWithTag<View>("TAB_CONTENT_START")
        )
        if (startIdx >= 0) {
            while (llTabContent.childCount > startIdx + 1) {
                llTabContent.removeViewAt(startIdx + 1)
            }
        } else {
            // First time — add a tag marker
            val marker = View(this).apply {
                tag = "TAB_CONTENT_START"
                layoutParams = llp(matchParent, 0)
            }
            llTabContent.addView(marker)
        }

        when (tab) {
            0 -> buildScannerTabContent()
            1 -> buildWatchlistTabContent()
            2 -> buildPositionsTabContent()
            3 -> buildSettingsTabContent()
        }
    }

    // ─── SCANNER TAB ──────────────────────────────────────────────────────────

    private fun buildScannerTabContent() {
        addSectionHeader("🔍 Full Alt Scanner", purple)
        addChainFilterRow()

        val markets = getFilteredMarkets()
        if (markets.isEmpty()) { addEmptyState("No alt markets match filter"); return }

        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val results = mutableListOf<Pair<PerpsMarket, PerpsMarketData>>()
            for (market in markets.take(50)) {
                try { results.add(market to PerpsMarketDataFetcher.getMarketData(market)) } catch (_: Exception) {}
            }
            results.sortByDescending { kotlin.math.abs(it.second.priceChange24hPct) }
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                results.forEach { (market, data) -> addMarketRow(market, data) }
                findViewById<TextView>(R.id.tvCryptoAltStats)?.text = "${results.size} alts scanned"
            }
        }
    }

    private fun addChainFilterRow() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 8, 16, 8)
        }
        val chains = listOf<Pair<String?, String>>(
            null to "All", "BNB" to "BNB", "ETH" to "ETH", "SOL" to "SOL", "MATIC" to "Polygon"
        )
        for ((chain, label) in chains) {
            row.addView(TextView(this).apply {
                text = label; textSize = 11f; gravity = Gravity.CENTER; setPadding(16, 6, 16, 6)
                setTextColor(if (chainFilter == chain) white else muted)
                setBackgroundColor(if (chainFilter == chain) purple else divider)
                layoutParams = llp(0, wrapContent, 1f).apply { marginEnd = 4 }
                setOnClickListener { chainFilter = chain; selectTab(0) }
            })
        }
        llTabContent.addView(row)
    }

    private fun getFilteredMarkets(): List<PerpsMarket> {
        val bnbSymbols   = setOf("DOGE","SHIB","FLOKI","BABYDOGE","PEPE","WIF","BONK","CAKE","BNB","ONE","TWT")
        val ethSymbols   = setOf("ETH","LINK","UNI","AAVE","MKR","SNX","COMP","ENS","GRT","LDO","IMX","ARB","OP")
        val solSymbols   = setOf("SOL","RAY","ORCA","JUP","PYTH","DRIFT","MNGO","BONK","WIF","BOME","MEW")
        val maticSymbols = setOf("MATIC","QUICK","GHST","SAND","MANA","AXS","AAVE")
        return when (chainFilter) {
            "BNB"   -> altMarkets.filter { it.symbol in bnbSymbols }
            "ETH"   -> altMarkets.filter { it.symbol in ethSymbols }
            "SOL"   -> altMarkets.filter { it.symbol in solSymbols }
            "MATIC" -> altMarkets.filter { it.symbol in maticSymbols }
            else    -> altMarkets
        }
    }

    private fun addMarketRow(market: PerpsMarket, data: PerpsMarketData) {
        val change = data.priceChange24hPct
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 10, 16, 10); setBackgroundColor(card)
            layoutParams = llp(matchParent, wrapContent).apply { bottomMargin = 2 }
        }
        row.addView(TextView(this).apply {
            text = "${market.emoji} ${market.symbol}"; textSize = 13f; setTextColor(white)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = llp(0, wrapContent, 2f)
        })
        row.addView(TextView(this).apply {
            text = if (data.price > 1) "$%.2f".format(data.price) else "$%.6f".format(data.price)
            textSize = 12f; setTextColor(white); typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = llp(0, wrapContent, 2f)
        })
        row.addView(TextView(this).apply {
            text = "${if (change >= 0) "+" else ""}%.1f%%".format(change)
            textSize = 12f; setTextColor(if (change >= 0) green else red)
            typeface = android.graphics.Typeface.MONOSPACE; gravity = Gravity.END
            layoutParams = llp(0, wrapContent, 1f)
        })
        row.addView(TextView(this).apply {
            text = "+"; textSize = 16f; setTextColor(purple); setPadding(16, 0, 8, 0)
            setOnClickListener { addSymbolToWatchlist(market.symbol) }
        })
        row.setOnClickListener { showSignalDialog(market, data,
            if (change > 3) "🟢 BUY" else if (change < -3) "🔴 SELL" else "🟡 HOLD",
            CryptoAltScannerAI.getAltBeta(market.symbol))
        }
        llTabContent.addView(row)
        addThinDivider()
    }

    // ─── WATCHLIST TAB ────────────────────────────────────────────────────────

    private fun buildWatchlistTabContent() {
        addSectionHeader("⭐ Alt Watchlist", green)
        val items = WatchlistEngine.getWatchlist().filter { item -> altMarkets.any { it.symbol == item.symbol } }
        if (items.isEmpty()) { addEmptyState("No alts in watchlist yet. Tap + to add."); return }

        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val results = mutableListOf<Triple<WatchlistEngine.WatchlistItem, PerpsMarket?, PerpsMarketData?>>()
            for (item in items) {
                val market = altMarkets.find { it.symbol == item.symbol }
                val data   = market?.let { runCatching { PerpsMarketDataFetcher.getMarketData(it) }.getOrNull() }
                results.add(Triple(item, market, data))
            }
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                results.forEach { (item, market, data) -> addWatchlistRow(item, market, data) }
            }
        }
    }

    private fun addWatchlistRow(item: WatchlistEngine.WatchlistItem, market: PerpsMarket?, data: PerpsMarketData?) {
        val change = data?.priceChange24hPct ?: 0.0
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 10, 16, 10); setBackgroundColor(card)
            layoutParams = llp(matchParent, wrapContent).apply { bottomMargin = 2 }
        }
        row.addView(TextView(this).apply {
            text = "${market?.emoji ?: "🪙"} ${item.symbol}"; textSize = 13f; setTextColor(white)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = llp(0, wrapContent, 2f)
        })
        row.addView(TextView(this).apply {
            text = data?.let { if (it.price > 1) "$%.2f".format(it.price) else "$%.6f".format(it.price) } ?: "—"
            textSize = 12f; setTextColor(white); typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = llp(0, wrapContent, 2f)
        })
        row.addView(TextView(this).apply {
            text = "${if (change >= 0) "+" else ""}%.1f%%".format(change)
            textSize = 12f; setTextColor(if (change >= 0) green else red)
            typeface = android.graphics.Typeface.MONOSPACE; gravity = Gravity.END
            layoutParams = llp(0, wrapContent, 1f)
        })
        row.addView(TextView(this).apply {
            text = "✕"; textSize = 14f; setTextColor(red); setPadding(16, 0, 8, 0)
            setOnClickListener { WatchlistEngine.removeFromWatchlist(item.symbol); selectTab(1) }
        })
        if (market != null && data != null) row.setOnClickListener {
            showSignalDialog(market, data,
                if (change > 3) "🟢 BUY" else if (change < -3) "🔴 SELL" else "🟡 HOLD",
                CryptoAltScannerAI.getAltBeta(market.symbol))
        }
        llTabContent.addView(row)
        addThinDivider()
    }

    // ─── POSITIONS TAB ────────────────────────────────────────────────────────

    private fun buildPositionsTabContent() {
        val allPos    = CryptoAltTrader.getAllPositions()
        val openPos   = allPos.filter { it.closeTime == null }
        val closedPos = allPos.filter { it.closeTime != null }.take(20)

        addSectionHeader("📂 Open Positions (${openPos.size})", amber)
        if (openPos.isEmpty()) addEmptyState("No open positions")
        else openPos.forEach { addPositionRow(it, isOpen = true) }

        addSectionHeader("📜 Recent Closed (${closedPos.size})", muted)
        if (closedPos.isEmpty()) addEmptyState("No closed positions yet")
        else closedPos.forEach { addPositionRow(it, isOpen = false) }

        val totalPnl = allPos.sumOf { it.getPnlSol() }
        findViewById<TextView>(R.id.tvCryptoAltStats)?.text =
            "${openPos.size} open | PnL: ${if (totalPnl >= 0) "+" else ""}%.4f◎".format(totalPnl)
    }

    private fun addPositionRow(pos: CryptoAltTrader.AltPosition, isOpen: Boolean) {
        val pnlPct = pos.getPnlPct()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(16, 12, 16, 12); setBackgroundColor(card)
            layoutParams = llp(matchParent, wrapContent).apply { bottomMargin = 2 }
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "${pos.market.emoji} ${pos.market.symbol}  ${pos.direction.emoji} ${pos.leverageLabel}"
            textSize = 13f; setTextColor(white); typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = llp(0, wrapContent, 1f)
        })
        header.addView(TextView(this).apply {
            text = "${if (pnlPct >= 0) "+" else ""}%.2f%%".format(pnlPct)
            textSize = 13f; setTextColor(if (pnlPct >= 0) green else red)
            typeface = android.graphics.Typeface.MONOSPACE
        })
        row.addView(header)
        row.addView(TextView(this).apply {
            text = "Entry: %.6f  Now: %.6f  Size: %.4f◎".format(pos.entryPrice, pos.currentPrice, pos.sizeSol)
            textSize = 10f; setTextColor(muted); typeface = android.graphics.Typeface.MONOSPACE
        })
        if (isOpen) row.setOnClickListener { showPositionDialog(pos) }
        llTabContent.addView(row)
        addThinDivider()
    }

    private fun showPositionDialog(pos: CryptoAltTrader.AltPosition) {
        val pnlPct   = pos.getPnlPct()
        val pnlStr   = "${if (pnlPct >= 0) "+" else ""}%.2f%%".format(pnlPct)
        val openedAt = sdf.format(Date(pos.openTime))
        AlertDialog.Builder(this)
            .setTitle("${pos.market.emoji} ${pos.market.symbol} — ${pos.direction.emoji} ${pos.leverageLabel}")
            .setMessage("Size: %.4f◎\nEntry: %.6f\nCurrent: %.6f\nP&L: $pnlStr\nOpened: $openedAt".format(
                pos.sizeSol, pos.entryPrice, pos.currentPrice))
            .setPositiveButton("Close Position") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    CryptoAltTrader.requestClose(pos.id)
                    withContext(Dispatchers.Main) { selectTab(2) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── SETTINGS TAB ─────────────────────────────────────────────────────────

    private fun buildSettingsTabContent() {
        addSectionHeader("⚙️ Crypto Alts Settings", 0xFF9CA3AF.toInt())

        addToggleRow("🤖 Alt Trader Running", CryptoAltTrader.isRunning()) { on ->
            if (on) CryptoAltTrader.start() else CryptoAltTrader.stop()
            selectTab(3)
        }
        addToggleRow("💰 Live Mode (real money)", CryptoAltTrader.isLiveMode()) { on ->
            if (on) {
                AlertDialog.Builder(this)
                    .setTitle("⚠️ Enable Live Trading?")
                    .setMessage("This will use REAL SOL. Only enable when win rate > 55%.")
                    .setPositiveButton("Enable Live") { _, _ -> CryptoAltTrader.setLiveMode(true); selectTab(3) }
                    .setNegativeButton("Cancel")      { _, _ -> selectTab(3) }
                    .show()
            } else { CryptoAltTrader.setLiveMode(false); selectTab(3) }
        }

        addSectionHeader("📊 Performance", purple)
        addInfoRow("Balance",     "%.4f◎".format(CryptoAltTrader.getBalance()))
        addInfoRow("Total PnL",   "${if (CryptoAltTrader.getTotalPnlSol() >= 0) "+" else ""}%.4f◎".format(CryptoAltTrader.getTotalPnlSol()))
        addInfoRow("Win Rate",    "${CryptoAltTrader.getWinRate().toInt()}%")
        addInfoRow("Trades",      "${CryptoAltTrader.getTotalTrades()}")
        addInfoRow("Open Pos",    "${CryptoAltTrader.getAllPositions().count { it.closeTime == null }}")
        addInfoRow("Phase",       getPhaseLabel())

        addSectionHeader("🧠 Learning System", blue)
        addInfoRow("AI Trade Count", "${FluidLearningAI.getMarketsTradeCount()}")
        addInfoRow("Dominance",      CryptoAltScannerAI.getDominanceCycleSignal())
        addInfoRow("Fear & Greed",   "${CryptoAltScannerAI.getCryptoFearGreed()}")

        addSectionHeader("🌐 Chain Coverage", teal)
        addInfoRow("Total alts", "${altMarkets.size} tokens")
        addInfoRow("BNB alts",   "${altMarkets.count { it.symbol in setOf("DOGE","SHIB","FLOKI","PEPE","WIF","BONK","CAKE") }}")
        addInfoRow("ETH alts",   "${altMarkets.count { it.symbol in setOf("ETH","LINK","UNI","AAVE","MKR","ARB","OP","LDO","GRT") }}")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DIALOGS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun showAddToWatchlistDialog() {
        val labels = altMarkets.map { "${it.emoji} ${it.symbol}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Add Alt to Watchlist")
            .setItems(labels) { _, which -> addSymbolToWatchlist(altMarkets[which].symbol) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addSymbolToWatchlist(symbol: String) {
        val added = WatchlistEngine.addToWatchlist(symbol)
        Toast.makeText(this, if (added) "✅ $symbol added" else "$symbol already in watchlist", Toast.LENGTH_SHORT).show()
        if (currentTab == 1) selectTab(1)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UI HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildTileCard(accentColor: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(card)
            setPadding(16, 14, 16, 14)
            layoutParams = llp(matchParent, wrapContent).apply { bottomMargin = 3 }

            // Top accent line
            val accent = View(this@CryptoAltActivity).apply {
                setBackgroundColor(accentColor)
                layoutParams = llp(matchParent, 2).apply { bottomMargin = 8 }
            }
            addView(accent)
        }
    }

    private fun addTileHeader(tile: LinearLayout, title: String, badge: String, badgeColor: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = title; textSize = 13f; setTextColor(white)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = llp(0, wrapContent, 1f)
        })
        row.addView(TextView(this).apply {
            text = badge; textSize = 9f; setTextColor(badgeColor)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setBackgroundColor(0xFF0D0D1A.toInt())
            setPadding(6, 3, 6, 3)
        })
        tile.addView(row)
    }

    private fun addStatChip(parent: LinearLayout, label: String, value: String, valueColor: Int, weight: Float) {
        parent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF111128.toInt())
            setPadding(8, 6, 8, 6)
            layoutParams = llp(0, wrapContent, weight).apply { marginEnd = 3 }

            addView(TextView(this@CryptoAltActivity).apply {
                text = value; textSize = 12f; setTextColor(valueColor)
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = Gravity.CENTER
            })
            addView(TextView(this@CryptoAltActivity).apply {
                text = label; textSize = 8f; setTextColor(muted)
                gravity = Gravity.CENTER
            })
        })
    }

    private fun addSectionHeader(text: String, color: Int) {
        llTabContent.addView(TextView(this).apply {
            this.text = text; textSize = 12f; setTextColor(color)
            typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(16, 14, 16, 6)
        })
    }

    private fun addSectionDivider() {
        llTabContent.addView(View(this).apply {
            setBackgroundColor(divider)
            layoutParams = llp(matchParent, 1).apply { topMargin = 8; bottomMargin = 8 }
        })
    }

    private fun addEmptyState(msg: String) {
        llTabContent.addView(TextView(this).apply {
            text = msg; textSize = 13f; setTextColor(muted); gravity = Gravity.CENTER
            setPadding(16, 32, 16, 32)
            layoutParams = llp(matchParent, wrapContent)
        })
    }

    private fun addThinDivider() {
        llTabContent.addView(View(this).apply {
            setBackgroundColor(divider)
            layoutParams = llp(matchParent, 1)
        })
    }

    private fun addToggleRow(label: String, current: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 12); setBackgroundColor(card)
            layoutParams = llp(matchParent, wrapContent).apply { bottomMargin = 2 }
        }
        row.addView(TextView(this).apply {
            text = label; textSize = 13f; setTextColor(white)
            layoutParams = llp(0, wrapContent, 1f)
        })
        row.addView(Switch(this).apply { isChecked = current; setOnCheckedChangeListener { _, v -> onChange(v) } })
        llTabContent.addView(row)
        addThinDivider()
    }

    private fun addInfoRow(label: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 10, 16, 10); setBackgroundColor(card)
            layoutParams = llp(matchParent, wrapContent).apply { bottomMargin = 2 }
        }
        row.addView(TextView(this).apply {
            text = label; textSize = 13f; setTextColor(muted)
            layoutParams = llp(0, wrapContent, 1f)
        })
        row.addView(TextView(this).apply {
            text = value; textSize = 13f; setTextColor(white)
            typeface = android.graphics.Typeface.MONOSPACE
        })
        llTabContent.addView(row)
        addThinDivider()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LAYOUT PARAMS SHORTHAND
    // ═══════════════════════════════════════════════════════════════════════════

    private val matchParent = LinearLayout.LayoutParams.MATCH_PARENT
    private val wrapContent = LinearLayout.LayoutParams.WRAP_CONTENT

    private fun llp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    // ═══════════════════════════════════════════════════════════════════════════
    // PHASE / COLOUR HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun getPhaseLabel(): String {
        val trades = FluidLearningAI.getMarketsTradeCount()
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

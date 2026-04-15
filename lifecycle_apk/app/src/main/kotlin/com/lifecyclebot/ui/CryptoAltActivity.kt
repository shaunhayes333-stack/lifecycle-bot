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
import com.lifecyclebot.engine.TreasuryManager
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
import com.lifecyclebot.network.BirdeyeApi
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
        // Fast refresh: hero stats + live PnL on active tab every 10s
        lifecycleScope.launch {
            while (isActive) {
                delay(10_000L)
                withContext(Dispatchers.Main) {
                    if (::tvHeroBalance.isInitialized) refreshHeroStats()
                    val hasOpenPos = CryptoAltTrader.getAllPositions().any { it.closeTime == null }
                    when {
                        currentTab == 2                      -> selectTab(2)      // positions always
                        hasOpenPos && currentTab in 0..1     -> buildTabContent() // scanner/watchlist: live PnL
                    }
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

    private fun formatMcap(usd: Double): String = when {
        usd >= 1_000_000 -> "$%.2fM".format(usd / 1_000_000)
        usd >= 1_000     -> "$%.1fK".format(usd / 1_000)
        else             -> "$%.0f".format(usd)
    }

    private fun buildFullDashboard() {
        llContent.removeAllViews()
        buildHeroSection()          // BALANCE label → big number → stat pills → chain chips
        buildOpenPositionsPanel()   // inline Open Positions list (if any)
        buildReadinessTile()        // 🚦 Live Readiness
        buildProofRunTile()         // 📈 30-Day Proof Run
        buildModuleIconGrid()       // AATE-style 2-row trader/intelligence icon grid
        buildTopStatusBar()         // HIVE MIND · SHADOW · REGIMES · LAYERS pills (below grid)
        buildTreasuryTierPanel()    // locked SOL / next unlock
        buildNetworkSignalsPanel()  // Network Signals
        buildShadowFDGPanel()
        buildHiveMindPanel()
        buildSectorHeatPanel()
        addDivider()
        buildTabContent()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HERO
    // ═══════════════════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════════════════
    // TOP STATUS BAR  —  HIVE MIND · SHADOW · REGIMES · LAYERS pills
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildTopStatusBar() {
        val bar = hBox(0xFF0A0A14.toInt(), 0, 0).apply {
            layoutParams = llp(match, wrap).apply { bottomMargin = 4 }
            gravity = Gravity.CENTER_VERTICAL
        }

        // Hive Mind
        val ciStats = CollectiveIntelligenceAI.getStats()
        bar.addView(buildStatusPill(
            "🐝 HIVE MIND",
            "${ciStats.cachedPatterns}/${ciStats.cachedModes}",
            if (ciStats.isEnabled) amber else muted
        ))

        // Shadow
        val shadow = ShadowLearningEngine.getBlockedTradeStats()
        bar.addView(buildStatusPill(
            "👁 SHADOW",
            "${shadow.wouldHaveWon}/${shadow.totalTracked}",
            if (shadow.totalTracked > 0) indigo else muted
        ))

        // Regimes
        val dominance = CryptoAltScannerAI.getDominanceCycleSignal()
        val regShort = when {
            dominance.contains("BULL", ignoreCase=true) -> "BULL"
            dominance.contains("BEAR", ignoreCase=true) -> "BEAR"
            dominance.contains("MEME", ignoreCase=true) -> "MEME"
            dominance.contains("MID",  ignoreCase=true) -> "MID"
            dominance.contains("NEU",  ignoreCase=true) -> "NEU"
            else -> dominance.take(4).uppercase()
        }
        val regColor = when {
            dominance.contains("BULL", ignoreCase=true) -> green
            dominance.contains("BEAR", ignoreCase=true) -> red
            else -> amber
        }
        bar.addView(buildStatusPill("📊 REGIMES", regShort, regColor))

        // Layers
        val plbLayers = PerpsLearningBridge.getConnectedLayerCount()
        bar.addView(buildStatusPill(
            "🔗 LAYERS",
            "${plbLayers}L",
            if (plbLayers > 0) blue else muted
        ))

        llContent.addView(bar)
    }

    private fun buildStatusPill(label: String, value: String, valueColor: Int): LinearLayout {
        return vBox(card, 6, 0).apply {
            layoutParams = llp(0, wrap, 1f).apply { marginEnd = 2 }
            gravity = Gravity.CENTER
            setPadding(4, 8, 4, 8)
            addView(tv(label, 7f, muted, bold = true).apply { gravity = Gravity.CENTER })
            addView(tv(value, 10f, valueColor, mono = true, bold = true).apply { gravity = Gravity.CENTER })
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // OPEN POSITIONS PANEL  —  inline position cards
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildOpenPositionsPanel() {
        val openPos    = CryptoAltTrader.getAllPositions().filter { it.closeTime == null }
        val totalRisk  = openPos.sumOf { it.sizeSol }
        val totalPnl   = openPos.sumOf { it.getPnlSol() }
        val solPrice   = WalletManager.lastKnownSolPrice

        val tile = buildTile(card2, "Open Positions", "${openPos.size} open", white)

        val headerRow = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 4 } }
        headerRow.addView(tv("${"%.3f".format(totalRisk)}◎ at risk", 10f, muted, mono=true).apply { layoutParams = llp(0, wrap, 1f) })
        headerRow.addView(tv("${if (totalPnl >= 0) "+" else ""}${"%.4f".format(totalPnl)}◎",
            10f, if (totalPnl >= 0) green else red, mono=true))
        tile.addView(headerRow)

        if (openPos.isEmpty()) {
            tile.addView(tv("No open positions", 11f, muted).apply { setPadding(0, 8, 0, 4) })
        } else {
            openPos.forEach { pos ->
                val pnlSol = pos.getPnlSol()
                val pnlPct = pos.getPnlPct()
                val elapsed = (System.currentTimeMillis() - pos.openTime) / 60_000

                val row = hBox().apply {
                    layoutParams = llp(match, wrap).apply { topMargin = 6 }
                    gravity = Gravity.CENTER_VERTICAL
                }

                // Logo
                val dynTok = DynamicAltTokenRegistry.getTokenBySymbol(pos.market.symbol)
                val logoUrl2 = dynTok?.logoUrl?.ifBlank { DynamicAltTokenRegistry.getCoinGeckoLogoUrl(pos.market.symbol) }
                    ?: DynamicAltTokenRegistry.getCoinGeckoLogoUrl(pos.market.symbol)
                val logoImg2 = android.widget.ImageView(this).apply {
                    val sz = (36 * resources.displayMetrics.density).toInt()
                    layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = 8 }
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    try { background = androidx.core.content.ContextCompat.getDrawable(this@CryptoAltActivity, R.drawable.token_logo_bg) } catch (_: Exception) {}
                    if (logoUrl2.isNotBlank()) {
                        load(logoUrl2) {
                            crossfade(true); placeholder(R.drawable.ic_token_placeholder)
                            error(R.drawable.ic_token_placeholder); allowHardware(false)
                            transformations(CircleCropTransformation())
                        }
                    } else setImageResource(R.drawable.ic_token_placeholder)
                }
                row.addView(logoImg2)

                // Left: market + entry info
                val leftCol = vBox(0, 0, 0).apply { layoutParams = llp(0, wrap, 1f) }
                leftCol.addView(hBox().apply {
                    addView(tv(pos.market.displayName, 13f, white, bold = true).apply { layoutParams = llp(0, wrap, 1f) })
                    addView(tv("${elapsed}m", 9f, muted, mono=true))
                })
                val entryStr  = if (pos.entryPrice > 0.01) "${"$%.4f".format(pos.entryPrice)}" else "${"$%.6f".format(pos.entryPrice)}"
                val currentStr = dynTok?.let { t -> if (t.price > 0) " → ${fmtPrice(t.price)}" else "" } ?: ""
                val sizeStr   = "${"%.4f".format(pos.sizeSol)}◎"
                leftCol.addView(tv("Entry: $entryStr$currentStr", 9f, muted, mono = true))
                leftCol.addView(tv("$sizeStr  TP:+${((pos.takeProfitPrice/pos.entryPrice - 1)*100).toInt()}%  SL:-${((1 - pos.stopLossPrice/pos.entryPrice)*100).toInt()}%",
                    8f, muted, mono = true))
                row.addView(leftCol)

                // Right: PnL + current value
                val currentValueSol = pos.sizeSol + pnlSol
                val currentValueUsd = currentValueSol * solPrice
                val rightCol = vBox(0, 0, 0).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    layoutParams = llp(wrap, wrap)
                }
                rightCol.addView(tv("${if (pnlPct >= 0) "+" else ""}${"%.1f".format(pnlPct)}%",
                    13f, if (pnlPct >= 0) green else red, mono=true, bold=true).apply { gravity = Gravity.END })
                rightCol.addView(tv("${if (pnlSol >= 0) "+" else ""}${"%.4f".format(pnlSol)}◎",
                    10f, if (pnlSol >= 0) green else red, mono=true).apply { gravity = Gravity.END })
                if (solPrice > 0) {
                    rightCol.addView(tv("≈\$${"%.2f".format(currentValueUsd)}", 9f, muted, mono=true).apply { gravity = Gravity.END })
                }
                row.addView(rightCol)
                tile.addView(row)
                tile.addView(thinDivider())
            }
        }
        llContent.addView(tile)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TREASURY TIER PANEL
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildTreasuryTierPanel() {
        val treasurySol = TreasuryManager.treasurySol
        if (treasurySol <= 0.0) return   // nothing locked yet

        val solPrice    = WalletManager.lastKnownSolPrice
        val usd         = treasurySol * solPrice
        val mIdx        = TreasuryManager.highestMilestoneHit
        val milestones  = TreasuryManager.MILESTONES
        val currentLabel = if (mIdx >= 0) milestones[mIdx].label else "No milestone"
        val nextLabel    = if (mIdx + 1 < milestones.size) "Next: ${"$"}${milestones[mIdx+1].thresholdUsd.toLong()}" else "MAX TIER"

        val tile = buildTile(amber, "🏦 Treasury Tier", "Tier: $currentLabel", amber)
        val row  = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 8 } }
        val lockedCol = vBox(0, 0, 0).apply { layoutParams = llp(0, wrap, 1f) }
        lockedCol.addView(tv("LOCKED", 9f, muted, bold=true))
        lockedCol.addView(tv("${"%.3f".format(treasurySol)} SOL", 16f, white, bold=true))
        lockedCol.addView(tv("($${"%.0f".format(usd)})", 11f, muted, mono=true))
        row.addView(lockedCol)
        val nextCol = vBox(0, 0, 0).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; layoutParams = llp(wrap, wrap) }
        nextCol.addView(tv("NEXT UNLOCK", 9f, muted, bold=true).apply { gravity = Gravity.END })
        nextCol.addView(tv(nextLabel, 13f, green, bold=true).apply { gravity = Gravity.END })
        row.addView(nextCol)
        tile.addView(row)
        llContent.addView(tile)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NETWORK SIGNALS PANEL
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildNetworkSignalsPanel() {
        val rawSignals = CollectiveIntelligenceAI.getActiveNetworkSignals()
        val signals    = rawSignals.filter {
            it.pnlPct.isFinite() && it.pnlPct > -101.0 && it.pnlPct < 1_000_000.0
        }
        if (signals.isEmpty()) return

        val megaCount  = signals.count { it.signalType == "MEGA_WINNER" }
        val hotCount   = signals.count { it.signalType == "HOT_TOKEN" }
        val avoidCount = signals.count { it.signalType == "AVOID" }

        val lastRefresh  = CollectiveIntelligenceAI.getLastRefreshTime()
        val syncAgoSecs  = (System.currentTimeMillis() - lastRefresh) / 1000
        val syncStr      = if (syncAgoSecs < 60) "Sync: ${syncAgoSecs}s" else "Sync: ${syncAgoSecs/60}m"

        val tile = buildTile(blue, "📡 NETWORK SIGNALS", "${signals.size} active", green)

        val statsRow = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        statsRow.addView(tv("MEGA: $megaCount",  10f, if (megaCount  > 0) amber else muted, mono=true).apply { layoutParams = llp(0,wrap,1f) })
        statsRow.addView(tv("HOT: $hotCount",    10f, if (hotCount   > 0) green else muted, mono=true).apply { layoutParams = llp(0,wrap,1f) })
        statsRow.addView(tv("AVOID: $avoidCount",10f, if (avoidCount > 0) red   else muted, mono=true).apply { layoutParams = llp(0,wrap,1f) })
        statsRow.addView(tv(syncStr,             9f,  muted, mono=true))
        tile.addView(statsRow)
        tile.addView(thinDivider())

        val sorted = signals.sortedWith(compareBy(
            { when (it.signalType) { "MEGA_WINNER"->0; "HOT_TOKEN"->1; "AVOID"->2; else->3 } },
            { -it.pnlPct }
        )).take(10)

        sorted.forEach { sig ->
            val emoji = when (sig.signalType) { "MEGA_WINNER" -> "🔥"; "AVOID" -> "⚠️"; else -> "🌐" }
            val sigColor = when (sig.signalType) {
                "MEGA_WINNER" -> amber; "AVOID" -> red; else -> green
            }
            val pnlSign = if (sig.pnlPct >= 0) "+" else ""
            val pnlCol  = if (sig.pnlPct >= 0) green else red

            val row = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 4 }; gravity = Gravity.CENTER_VERTICAL }
            row.addView(tv(emoji, 13f, white).apply { layoutParams = llp(wrap, wrap).apply { marginEnd = 6 } })
            row.addView(tv(sig.symbol.take(10), 12f, sigColor, bold=true).apply { layoutParams = llp(0, wrap, 0.35f) })
            row.addView(tv("$pnlSign${sig.pnlPct.toInt()}%", 11f, pnlCol, mono=true, bold=true).apply { layoutParams = llp(0, wrap, 0.2f) })
            row.addView(tv("from ${sig.broadcasterId.take(6)}...", 9f, muted, mono=true).apply { layoutParams = llp(0, wrap, 0.35f) })
            if (sig.ackCount > 1) {
                row.addView(tv("×${sig.ackCount}", 9f, blue, mono=true))
            }
            tile.addView(row)
        }
        llContent.addView(tile)
    }

    private fun buildHeroSection() {
        val bal    = CryptoAltTrader.getBalance()
        val pnl    = CryptoAltTrader.getTotalPnlSol()
        val wr     = CryptoAltTrader.getWinRate()
        val trades = CryptoAltTrader.getTotalTrades()
        val phase  = getPhaseLabel()
        val dynCnt = DynamicAltTokenRegistry.getTokenCount()
        val open   = CryptoAltTrader.getAllPositions().count { it.closeTime == null }
        val solUsd = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice

        // ── BALANCE label ──────────────────────────────────────────────────────
        llContent.addView(tv("BALANCE", 10f, muted, mono = true).apply {
            letterSpacing = 0.12f
            setPadding(20, 12, 20, 0)
        })

        // ── Balance row: large number + PAPER badge + SOL price ───────────────
        val balRow = hBox().apply {
            setPadding(20, 4, 20, 0)
            gravity = Gravity.BOTTOM
        }
        tvHeroBalance = tv("◎ ${"%.4f".format(bal)}", 28f, white, bold = true).apply {
            layoutParams = llp(0, wrap, 1f)
        }
        balRow.addView(tvHeroBalance)

        // PAPER / LIVE badge + SOL amount
        val modeLabel = if (CryptoAltTrader.isLiveMode()) "LIVE" else "PAPER"
        val modeBg    = if (CryptoAltTrader.isLiveMode()) 0xFF052E16.toInt() else 0xFF1C1400.toInt()
        val modeCol   = if (CryptoAltTrader.isLiveMode()) green else amber
        val midCol = vBox().apply {
            layoutParams = llp(wrap, wrap).apply { marginEnd = 8; bottomMargin = 4 }
            gravity = Gravity.END
        }
        midCol.addView(tv("📝 $modeLabel ◎ ${"%.4f".format(bal)}", 10f, amber, mono = true).apply {
            setBackgroundColor(modeBg); setPadding(6, 3, 6, 3)
        })
        balRow.addView(midCol)

        // SOL/USD price (top right)
        val priceCol = vBox().apply {
            layoutParams = llp(wrap, wrap).apply { bottomMargin = 4 }
            gravity = Gravity.END
        }
        val solPriceStr = if (solUsd >= 10) "$${"%.0f".format(solUsd)}" else "$—"
        priceCol.addView(tv(solPriceStr, 18f, teal, bold = true, mono = true).apply { gravity = Gravity.END })
        priceCol.addView(tv("SOL/USD", 9f, muted, mono = true).apply { gravity = Gravity.END })
        balRow.addView(priceCol)
        llContent.addView(balRow)

        // ── PnL row ────────────────────────────────────────────────────────────
        val pnlRow = hBox().apply {
            setPadding(20, 2, 20, 8)
            gravity = Gravity.CENTER_VERTICAL
        }
        tvHeroPnl = tv("${if (pnl >= 0) "+" else ""}${"%.4f".format(pnl)} SOL", 14f,
            if (pnl >= 0) green else red, mono = true).apply { layoutParams = llp(0, wrap, 1f) }
        tvHeroWinRate = tv("${"%.1f".format(wr)}% WR", 12f, purple, mono = true).apply { layoutParams = llp(0, wrap, 1f) }
        tvHeroTrades  = tv("$trades trades", 12f, muted, mono = true)
        pnlRow.addView(tvHeroPnl); pnlRow.addView(tvHeroWinRate); pnlRow.addView(tvHeroTrades)
        llContent.addView(pnlRow)

        // ── Phase label ────────────────────────────────────────────────────────
        tvHeroPhase = tv(phase, 10f, phaseColor(phase), bold = true).apply {
            setPadding(20, 0, 20, 4)
        }
        llContent.addView(tvHeroPhase)

        // ── 4 Stat pills (matches AATE style exactly) ─────────────────────────
        val pillRow = hBox().apply {
            setPadding(20, 8, 20, 12)
        }
        fun statPill(value: String, label: String, valColor: Int, weight: Float = 1f, last: Boolean = false): LinearLayout {
            return vBox(0xFF111827.toInt(), 8, 8).apply {
                layoutParams = llp(0, wrap, weight).apply { if (!last) marginEnd = 6 }
                gravity = Gravity.CENTER
                addView(tv(value, 16f, valColor, bold = true).apply { gravity = Gravity.CENTER })
                addView(tv(label,  9f, muted).apply { gravity = Gravity.CENTER })
            }
        }
        pillRow.addView(statPill("$trades", "24h Trades", white))
        pillRow.addView(statPill("${"%.0f".format(wr)}%", "Win Rate",
            if (wr >= 60) green else if (wr >= 40) amber else red))
        pillRow.addView(statPill("$open", "Open", if (open > 0) purple else muted))
        pillRow.addView(statPill(phase, "Phase", phaseColor(phase), last = true))
        llContent.addView(pillRow)

        // ── Token universe + chain chips ───────────────────────────────────────
        val chainRow = hBox().apply {
            setPadding(20, 0, 20, 8)
        }
        chainRow.addView(tv("🌐 $dynCnt tokens", 10f, teal, mono = true).apply {
            layoutParams = llp(0, wrap, 1f)
            gravity = Gravity.CENTER_VERTICAL
        })
        listOf("BNB" to amber, "ETH" to blue, "SOL" to purple, "POLY" to indigo).forEach { (label, col) ->
            chainRow.addView(tv(label, 9f, col).apply {
                setBackgroundColor(0xFF0D0D1A.toInt()); setPadding(8, 3, 8, 3)
                layoutParams = llp(wrap, wrap).apply { marginStart = 4 }; gravity = Gravity.CENTER
            })
        }
        llContent.addView(chainRow)
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
        val tile     = buildTile(phaseColor(phase), "🚦 Live Readiness", phase, phaseColor(phase)) { showReadinessDetailDialog() }
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
        val tile      = buildTile(teal, "📈 30-Day Proof Run", if (isActive) "Day $day / 30" else "NOT STARTED", if (isActive) teal else muted) { showProofRunDetailDialog() }
        val row = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(row, "Trades",    "$total", white, 1f)
        addStatChip(row, "Win Rate",  "${"%.1f".format(wr)}%", if (wr >= 55) green else amber, 1f)
        addStatChip(row, "Realized",  "${if (pnl >= 0) "+" else ""}${"%.3f".format(pnl)}◎", if (pnl >= 0) green else red, 1f)
        addStatChip(row, "Integrity", "$integrity/100", if (integrity >= 80) green else amber, 1f)
        tile.addView(row); llContent.addView(tile)
    }

    private fun buildShitCoinTile() {
        val stats     = ShitCoinTraderAI.getStats()
        val mode      = ShitCoinTraderAI.getCurrentMode()
        val positions = ShitCoinTraderAI.getActivePositions()
        val modeLabel = "${mode.name}  ${if (stats.winRate > 0) "· ${"%.1f".format(stats.winRate)}% WR" else ""}"
        val tile      = buildTile(red, "💩 ShitCoin Degen", modeLabel, if (mode == ShitCoinTraderAI.ShitCoinMode.HUNTING) orange else muted) { showShitCoinDetailDialog() }

        val statsRow  = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(statsRow, "Balance",  "◎${"%.3f".format(stats.balanceSol)}", white, 1f)
        addStatChip(statsRow, "Day PnL",  "${if (stats.dailyPnlSol >= 0) "+" else ""}${"%.3f".format(stats.dailyPnlSol)}◎", if (stats.dailyPnlSol >= 0) green else red, 1f)
        addStatChip(statsRow, "Open",     "${positions.size}", blue, 1f)
        tile.addView(statsRow)

        if (positions.isNotEmpty()) {
            tile.addView(thinDivider())
            positions.forEach { pos ->
                val currentPrice = try {
                    com.lifecyclebot.engine.BotService.status.tokens[pos.mint]?.ref?.takeIf { it > 0 } ?: pos.entryPrice
                } catch (_: Exception) { pos.entryPrice }
                val gainPct = if (pos.entryPrice > 0) (currentPrice - pos.entryPrice) / pos.entryPrice * 100 else 0.0
                val gainCol = if (gainPct >= 0) green else red
                val pnlSol  = pos.entrySol * gainPct / 100.0
                val elapsed = (System.currentTimeMillis() - pos.entryTime) / 60_000
                val mcapLabel = formatMcap(pos.marketCapUsd)

                val row = hBox().apply {
                    layoutParams = llp(match, wrap).apply { topMargin = 8 }
                    gravity = Gravity.CENTER_VERTICAL
                }
                val left = vBox(0, 0, 0).apply { layoutParams = llp(0, wrap, 1f) }
                left.addView(hBox().apply {
                    addView(tv("${pos.launchPlatform.emoji} ${pos.symbol}", 13f, orange, bold = true).apply { layoutParams = llp(0, wrap, 1f) })
                    addView(tv("${elapsed}m", 9f, muted, mono = true))
                })
                left.addView(tv("Entry: ${fmtPrice(pos.entryPrice)}  ·  MCap: $mcapLabel", 9f, muted, mono = true))
                left.addView(tv("TP:+${pos.takeProfitPct.toInt()}%  SL:${pos.stopLossPct.toInt()}%", 8f, muted, mono = true))
                row.addView(left)
                val right = vBox(0, 0, 0).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; layoutParams = llp(wrap, wrap) }
                right.addView(tv("%+.1f%%".format(gainPct), 13f, gainCol, bold = true, mono = true).apply { gravity = Gravity.END })
                right.addView(tv("%+.4f◎".format(pnlSol),  9f,  gainCol, mono = true).apply { gravity = Gravity.END })
                row.addView(right)
                tile.addView(row)
                tile.addView(thinDivider())
            }
        }
        llContent.addView(tile)
    }

    private fun buildQualityTile() {
        val wr        = QualityTraderAI.getWinRate()
        val pnl       = QualityTraderAI.getDailyPnl()
        val positions = QualityTraderAI.getActivePositions()
        val tile      = buildTile(teal, "💎 Quality Mode", "MCap \$100K–\$1M", teal) { showQualityDetailDialog() }

        val statsRow  = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(statsRow, "Win Rate",  "${"%.1f".format(wr)}%", if (wr >= 55) green else amber, 1f)
        addStatChip(statsRow, "Day PnL",   "${if (pnl >= 0) "+" else ""}${"%.3f".format(pnl)}◎", if (pnl >= 0) green else red, 1f)
        addStatChip(statsRow, "Open",      "${positions.size}", blue, 1f)
        addStatChip(statsRow, "TP/SL",     "+${QualityTraderAI.getFluidTakeProfit().toInt()}%/-${QualityTraderAI.getFluidStopLoss().toInt()}%", muted, 1f)
        tile.addView(statsRow)

        if (positions.isNotEmpty()) {
            tile.addView(thinDivider())
            positions.forEach { pos ->
                val currentPrice = try {
                    com.lifecyclebot.engine.BotService.status.tokens[pos.mint]?.ref?.takeIf { it > 0 } ?: pos.entryPrice
                } catch (_: Exception) { pos.entryPrice }
                val gainPct = if (pos.entryPrice > 0) (currentPrice - pos.entryPrice) / pos.entryPrice * 100 else 0.0
                val gainCol = if (gainPct >= 0) green else red
                val pnlSol  = pos.entrySol * gainPct / 100.0
                val elapsed = (System.currentTimeMillis() - pos.entryTime) / 60_000

                val row = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 8 }; gravity = Gravity.CENTER_VERTICAL }
                val left = vBox(0, 0, 0).apply { layoutParams = llp(0, wrap, 1f) }
                left.addView(hBox().apply {
                    addView(tv("💎 ${pos.symbol}", 13f, teal, bold = true).apply { layoutParams = llp(0, wrap, 1f) })
                    addView(tv("${elapsed}m", 9f, muted, mono = true))
                })
                left.addView(tv("Entry: ${fmtPrice(pos.entryPrice)}  ·  MCap: ${formatMcap(pos.entryMcap)}", 9f, muted, mono = true))
                left.addView(tv("${"%.3f".format(pos.entrySol)}◎  TP:+${pos.takeProfitPct.toInt()}%  SL:${pos.stopLossPct.toInt()}%", 8f, muted, mono = true))
                row.addView(left)
                val right = vBox(0, 0, 0).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; layoutParams = llp(wrap, wrap) }
                right.addView(tv("%+.1f%%".format(gainPct), 13f, gainCol, bold = true, mono = true).apply { gravity = Gravity.END })
                right.addView(tv("%+.4f◎".format(pnlSol), 9f, gainCol, mono = true).apply { gravity = Gravity.END })
                row.addView(right)
                tile.addView(row)
                tile.addView(thinDivider())
            }
        }
        llContent.addView(tile)
    }

    private fun buildBlueChipTile() {
        val stats     = BlueChipTraderAI.getStats()
        val wr        = BlueChipTraderAI.getWinRatePct().toDouble()
        val bal       = BlueChipTraderAI.getCurrentBalance()
        val pnl       = BlueChipTraderAI.getDailyPnlSol()
        val positions = BlueChipTraderAI.getActivePositions()
        val totalRisk = positions.sumOf { it.entrySol }
        val bcTotalPnlSol = positions.sumOf { p ->
            val cp = try { com.lifecyclebot.engine.BotService.status.tokens[p.mint]?.ref?.takeIf { it > 0 } ?: p.entryPrice } catch (_: Exception) { p.entryPrice }
            p.entrySol * (cp - p.entryPrice) / p.entryPrice
        }
        val tile      = buildTile(blue, "🔵 Blue Chip Trades", "${"%.3f".format(totalRisk)}◎  ${if (bcTotalPnlSol >= 0) "+" else ""}${"%.4f".format(bcTotalPnlSol)}◎", blue)

        val statsRow  = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(statsRow, "Balance",  "◎${"%.3f".format(bal)}", white, 1f)
        addStatChip(statsRow, "Day PnL",  "${if (pnl >= 0) "+" else ""}${"%.3f".format(pnl)}◎", if (pnl >= 0) green else red, 1f)
        addStatChip(statsRow, "Win Rate", "${"%.1f".format(wr)}%", if (wr >= 55) green else amber, 1f)
        addStatChip(statsRow, "Open",     "${positions.size}", blue, 1f)
        tile.addView(statsRow)

        if (positions.isNotEmpty()) {
            tile.addView(thinDivider())
            positions.forEach { pos ->
                val currentPrice = try {
                    com.lifecyclebot.engine.BotService.status.tokens[pos.mint]?.ref?.takeIf { it > 0 } ?: pos.entryPrice
                } catch (_: Exception) { pos.entryPrice }
                val gainPct = if (pos.entryPrice > 0) (currentPrice - pos.entryPrice) / pos.entryPrice * 100 else 0.0
                val gainCol = if (gainPct >= 0) green else red
                val pnlSol  = pos.entrySol * gainPct / 100.0
                val elapsed = (System.currentTimeMillis() - pos.entryTime) / 60_000
                val mcapLabel = formatMcap(pos.marketCapUsd)

                val row = hBox().apply {
                    layoutParams = llp(match, wrap).apply { topMargin = 8 }
                    gravity = Gravity.CENTER_VERTICAL
                }
                val left = vBox(0, 0, 0).apply { layoutParams = llp(0, wrap, 1f) }
                left.addView(hBox().apply {
                    addView(tv("🔵 ${pos.symbol}", 13f, blue, bold = true).apply { layoutParams = llp(0, wrap, 1f) })
                    addView(tv("${elapsed}m", 9f, muted, mono = true))
                })
                left.addView(tv("Entry: ${fmtPrice(pos.entryPrice)}  ·  MCap: $mcapLabel", 9f, muted, mono = true))
                left.addView(tv("${"%.3f".format(pos.entrySol)}◎  TP:+${pos.takeProfitPct.toInt()}%  SL:${pos.stopLossPct.toInt()}%", 8f, muted, mono = true))
                row.addView(left)
                val right = vBox(0, 0, 0).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; layoutParams = llp(wrap, wrap) }
                right.addView(tv("%+.1f%%".format(gainPct), 13f, gainCol, bold = true, mono = true).apply { gravity = Gravity.END })
                right.addView(tv("%+.4f◎".format(pnlSol),  9f,  gainCol, mono = true).apply { gravity = Gravity.END })
                row.addView(right)
                tile.addView(row)
                tile.addView(thinDivider())
            }
        }
        llContent.addView(tile)
    }

    private fun buildExpressTile() {
        val stats = ShitCoinExpress.getStats()
        val rides = ShitCoinExpress.getActiveRides()
        val tile  = buildTile(orange, "⚡ Express Mode", "High Velocity  ${rides.size} riding", orange) { showExpressDetailDialog() }

        val statsRow = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(statsRow, "Win Rate",  "${"%.1f".format(stats.winRate)}%", if (stats.winRate >= 55) green else amber, 1f)
        addStatChip(statsRow, "Day PnL",   "${if (stats.dailyPnlSol >= 0) "+" else ""}${"%.3f".format(stats.dailyPnlSol)}◎", if (stats.dailyPnlSol >= 0) green else red, 1f)
        addStatChip(statsRow, "Open",      "${rides.size}", blue, 1f)
        addStatChip(statsRow, "Speed",     "FAST", orange, 1f)
        tile.addView(statsRow)

        if (rides.isNotEmpty()) {
            tile.addView(thinDivider())
            rides.forEach { ride ->
                val currentPrice = try {
                    com.lifecyclebot.engine.BotService.status.tokens[ride.mint]?.ref?.takeIf { it > 0 } ?: ride.entryPrice
                } catch (_: Exception) { ride.entryPrice }
                val gainPct = if (ride.entryPrice > 0) (currentPrice - ride.entryPrice) / ride.entryPrice * 100 else 0.0
                val gainCol = if (gainPct >= 0) green else red
                val pnlSol  = ride.entrySol * gainPct / 100.0
                val elapsed = (System.currentTimeMillis() - ride.entryTime) / 60_000

                val row = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 8 }; gravity = Gravity.CENTER_VERTICAL }
                val left = vBox(0, 0, 0).apply { layoutParams = llp(0, wrap, 1f) }
                left.addView(hBox().apply {
                    addView(tv("⚡ ${ride.symbol}", 13f, orange, bold = true).apply { layoutParams = llp(0, wrap, 1f) })
                    addView(tv("${elapsed}m", 9f, muted, mono = true))
                })
                left.addView(tv("Entry: ${fmtPrice(ride.entryPrice)}  ·  Mom: ${"%.0f".format(ride.entryMomentum)}", 9f, muted, mono = true))
                left.addView(tv("${"%.3f".format(ride.entrySol)}◎  BP: ${"%.0f".format(ride.entryBuyPressure)}%", 8f, muted, mono = true))
                row.addView(left)
                val right = vBox(0, 0, 0).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; layoutParams = llp(wrap, wrap) }
                right.addView(tv("%+.1f%%".format(gainPct), 13f, gainCol, bold = true, mono = true).apply { gravity = Gravity.END })
                right.addView(tv("%+.4f◎".format(pnlSol), 9f, gainCol, mono = true).apply { gravity = Gravity.END })
                row.addView(right)
                tile.addView(row)
                tile.addView(thinDivider())
            }
        }
        llContent.addView(tile)
    }

    private fun buildMoonshotTile() {
        val wr        = MoonshotTraderAI.getWinRatePct().toDouble()
        val pnl       = MoonshotTraderAI.getDailyPnlSol()
        val positions = MoonshotTraderAI.getActivePositions()
        val msSubtitle = if (positions.isNotEmpty()) "${positions.size}W/${positions.size}L  ·  ${positions.first().spaceMode.displayName}" else "10x / 100x Hunters"
        val tile      = buildTile(purple, "🌙 Moonshot", msSubtitle, purple) { showMoonshotDetailDialog() }

        val statsRow  = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(statsRow, "Win Rate",  "${"%.1f".format(wr)}%", if (wr >= 55) green else amber, 1f)
        addStatChip(statsRow, "Day PnL",   "${if (pnl >= 0) "+" else ""}${"%.3f".format(pnl)}◎", if (pnl >= 0) green else red, 1f)
        addStatChip(statsRow, "Open",      "${positions.size}", blue, 1f)
        addStatChip(statsRow, "10x Hits",  "${MoonshotTraderAI.getLifetimeTenX()}", purple, 1f)
        tile.addView(statsRow)

        if (positions.isNotEmpty()) {
            tile.addView(thinDivider())
            positions.forEach { pos ->
                val currentPrice = try {
                    com.lifecyclebot.engine.BotService.status.tokens[pos.mint]?.ref?.takeIf { it > 0 } ?: pos.entryPrice
                } catch (_: Exception) { pos.entryPrice }
                val gainPct = if (pos.entryPrice > 0) (currentPrice - pos.entryPrice) / pos.entryPrice * 100 else 0.0
                val gainCol = if (gainPct >= 0) green else red
                val pnlSol  = pos.entrySol * gainPct / 100.0
                val elapsed = (System.currentTimeMillis() - pos.entryTime) / 60_000
                val mcapLabel = formatMcap(pos.marketCapUsd)

                val row = hBox().apply {
                    layoutParams = llp(match, wrap).apply { topMargin = 8 }
                    gravity = Gravity.CENTER_VERTICAL
                }
                val left = vBox(0, 0, 0).apply { layoutParams = llp(0, wrap, 1f) }
                left.addView(hBox().apply {
                    addView(tv("${pos.spaceMode.emoji} ${pos.symbol}", 13f, purple, bold = true).apply { layoutParams = llp(0, wrap, 1f) })
                    addView(tv("${elapsed}m", 9f, muted, mono = true))
                })
                left.addView(tv("${fmtPrice(pos.entryPrice)} → ${fmtPrice(currentPrice)}  ·  MCap: $mcapLabel", 9f, muted, mono = true))
                left.addView(tv("TP:+${pos.takeProfitPct.toInt()}%  SL:${pos.stopLossPct.toInt()}%", 8f, muted, mono = true))
                row.addView(left)
                val right = vBox(0, 0, 0).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; layoutParams = llp(wrap, wrap) }
                right.addView(tv("%+.1f%%".format(gainPct), 13f, gainCol, bold = true, mono = true).apply { gravity = Gravity.END })
                right.addView(tv("%+.4f◎".format(pnlSol),  9f,  gainCol, mono = true).apply { gravity = Gravity.END })
                row.addView(right)
                tile.addView(row)
                tile.addView(thinDivider())
            }
        }
        llContent.addView(tile)
    }

    private fun buildManipTile() {
        val stats     = ManipulatedTraderAI.getStats()
        val positions = ManipulatedTraderAI.getActivePositions()
        val manipWr   = if (stats.dailyWins + stats.dailyLosses > 0) (stats.dailyWins.toDouble() / (stats.dailyWins + stats.dailyLosses) * 100) else 0.0
        val tile      = buildTile(pink, "🎭 Manip Catch", "Caught: ${stats.totalManipCaught}  ${if (positions.isNotEmpty()) "· ${positions.size} open" else ""}", pink)

        val statsRow  = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
        addStatChip(statsRow, "Win Rate",  "${"%.1f".format(manipWr)}%", if (manipWr >= 55) green else amber, 1f)
        addStatChip(statsRow, "Day PnL",   "${if (stats.dailyPnlSol >= 0) "+" else ""}${"%.3f".format(stats.dailyPnlSol)}◎", if (stats.dailyPnlSol >= 0) green else red, 1f)
        addStatChip(statsRow, "Open",      "${positions.size}", blue, 1f)
        addStatChip(statsRow, "Caught",    "${stats.totalManipCaught}", pink, 1f)
        tile.addView(statsRow)

        if (positions.isNotEmpty()) {
            tile.addView(thinDivider())
            positions.forEach { pos ->
                val currentPrice = try {
                    com.lifecyclebot.engine.BotService.status.tokens[pos.mint]?.ref?.takeIf { it > 0 } ?: pos.entryPrice
                } catch (_: Exception) { pos.entryPrice }
                val gainPct = if (pos.entryPrice > 0) (currentPrice - pos.entryPrice) / pos.entryPrice * 100 else 0.0
                val gainCol = if (gainPct >= 0) green else red
                val pnlSol  = pos.entrySol * gainPct / 100.0
                val elapsed = (System.currentTimeMillis() - pos.entryTime) / 60_000

                val row = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 8 }; gravity = Gravity.CENTER_VERTICAL }
                val left = vBox(0, 0, 0).apply { layoutParams = llp(0, wrap, 1f) }
                left.addView(hBox().apply {
                    addView(tv("🎭 ${pos.symbol}", 13f, pink, bold = true).apply { layoutParams = llp(0, wrap, 1f) })
                    addView(tv("${elapsed}m", 9f, muted, mono = true))
                })
                left.addView(tv("Entry: ${fmtPrice(pos.entryPrice)}  ·  Manip: ${pos.manipScore}", 9f, muted, mono = true))
                left.addView(tv("${"%.3f".format(pos.entrySol)}◎  TP:+${pos.takeProfitPct.toInt()}%  SL:${pos.stopLossPct.toInt()}%", 8f, muted, mono = true))
                row.addView(left)
                val right = vBox(0, 0, 0).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; layoutParams = llp(wrap, wrap) }
                right.addView(tv("%+.1f%%".format(gainPct), 13f, gainCol, bold = true, mono = true).apply { gravity = Gravity.END })
                right.addView(tv("%+.4f◎".format(pnlSol), 9f, gainCol, mono = true).apply { gravity = Gravity.END })
                row.addView(right)
                tile.addView(row)
                tile.addView(thinDivider())
            }
        }
        llContent.addView(tile)
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
            if (scStats.winRate >= 55) green else if (scStats.winRate >= 40) amber else red
        ) { showAiDialog("💩 ShitCoin Degen",
            buildString {
                append("Mode: ${scStats.mode.name}\n")
                append("Balance: ${"%.3f".format(scStats.balanceSol)}◎\n")
                append("Daily PnL: ${if (scStats.dailyPnlSol >= 0) "+" else ""}${"%.3f".format(scStats.dailyPnlSol)}◎\n")
                append("Win Rate: ${"%.1f".format(scStats.winRate)}%\n")
                append("Open Positions: ${scStats.activePositions}\n")
                append("Trades Today: ${scStats.dailyTradeCount}\n")
                append("Daily W/L: ${scStats.dailyWins}W / ${scStats.dailyLosses}L")
            }) })

        val qWr   = QualityTraderAI.getWinRate()
        val qOpen = QualityTraderAI.getActivePositions().size
        row1.addView(buildIconTile("💎", "Quality",
            "$qOpen | ${qWr.toInt()}%",
            if (qWr >= 55) green else if (qWr >= 40) amber else red
        ) { showAiDialog("💎 Quality Mode",
            buildString {
                append("MCap Range: \$100K–\$1M\n")
                append("Win Rate: ${"%.1f".format(qWr)}%\n")
                append("Daily PnL: ${if (QualityTraderAI.getDailyPnl() >= 0) "+" else ""}${"%.3f".format(QualityTraderAI.getDailyPnl())}◎\n")
                append("Open Positions: $qOpen\n")
                append("TP: +${QualityTraderAI.getFluidTakeProfit().toInt()}%  SL: -${QualityTraderAI.getFluidStopLoss().toInt()}%")
            }) })

        val bcStats = BlueChipTraderAI.getStats()
        row1.addView(buildIconTile("🔵", "Blue",
            "${bcStats.activePositions} | ${bcStats.winRate.toInt()}%",
            if (bcStats.winRate >= 55) green else if (bcStats.winRate >= 40) amber else red
        ) { showAiDialog("🔵 Blue Chip",
            buildString {
                append("MCap Range: \$1M+\n")
                append("Balance: ${"%.3f".format(BlueChipTraderAI.getCurrentBalance())}◎\n")
                append("Daily PnL: ${if (BlueChipTraderAI.getDailyPnlSol() >= 0) "+" else ""}${"%.3f".format(BlueChipTraderAI.getDailyPnlSol())}◎\n")
                append("Win Rate: ${"%.1f".format(BlueChipTraderAI.getWinRatePct())}%\n")
                append("Open Positions: ${bcStats.activePositions}")
            }) })

        val exStats = ShitCoinExpress.getStats()
        row1.addView(buildIconTile("⚡", "Express",
            "${exStats.activeRides} | ${exStats.winRate.toInt()}%",
            if (exStats.winRate >= 55) green else if (exStats.winRate >= 40) amber else red
        ) { showAiDialog("⚡ Express Mode",
            buildString {
                append("Strategy: High Velocity / Momentum\n")
                append("Win Rate: ${"%.1f".format(exStats.winRate)}%\n")
                append("Daily PnL: ${if (exStats.dailyPnlSol >= 0) "+" else ""}${"%.3f".format(exStats.dailyPnlSol)}◎\n")
                append("Active Rides: ${exStats.activeRides}\n")
                append("Daily Rides: ${exStats.dailyRides}")
            }) })

        val maStats = ManipulatedTraderAI.getStats()
        val maWr    = if (maStats.dailyWins + maStats.dailyLosses > 0)
            (maStats.dailyWins.toDouble() / (maStats.dailyWins + maStats.dailyLosses) * 100).toInt() else 0
        row1.addView(buildIconTile("🎭", "Manip",
            "${maStats.activeCount} | $maWr%",
            if (maWr >= 55) green else if (maWr >= 40) amber else red
        ) { showAiDialog("🎭 Manip Catch",
            buildString {
                append("Strategy: Manipulation Detector\n")
                append("Win Rate: $maWr%\n")
                append("Daily PnL: ${if (maStats.dailyPnlSol >= 0) "+" else ""}${"%.3f".format(maStats.dailyPnlSol)}◎\n")
                append("Active Positions: ${maStats.activeCount}\n")
                append("Total Caught: ${maStats.totalManipCaught}")
            }) })

        val moWr   = MoonshotTraderAI.getWinRatePct()
        val moOpen = MoonshotTraderAI.getActivePositions().size
        row1.addView(buildIconTile("🌙", "Moon",
            "$moOpen | $moWr%",
            if (moWr >= 55) green else if (moWr >= 40) amber else purple
        ) { showAiDialog("🌙 Moonshot",
            buildString {
                append("Strategy: 10x / 100x Hunters\n")
                append("Win Rate: $moWr%\n")
                append("Daily PnL: ${if (MoonshotTraderAI.getDailyPnlSol() >= 0) "+" else ""}${"%.3f".format(MoonshotTraderAI.getDailyPnlSol())}◎\n")
                append("Open Positions: $moOpen\n")
                append("10x Lifetime: ${MoonshotTraderAI.getLifetimeTenX()}")
            }) })

        val flTrades = FluidLearningAI.getMarketsTradeCount()
        val flPct    = (FluidLearningAI.getMarketsLearningProgress() * 100).toInt()
        row1.addView(buildIconTile("🧠", "Learn",
            "$flTrades | $flPct%",
            if (flPct >= 80) green else if (flPct >= 40) blue else muted
        ) { showAiDialog("🧠 Fluid Learning",
            buildString {
                append("Learning Phase: ${getPhaseLabel()}\n")
                append("Progress: $flPct%\n")
                append("Total Trades: $flTrades\n")
                append("Confidence Boost: +${"%.1f".format(FluidLearningAI.getBootstrapConfidenceBoost())}\n")
                append("Size Multiplier: ${"%.2f".format(FluidLearningAI.getBootstrapSizeMultiplier())}x")
            }) })

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
            if (ciStats.isEnabled) amber else muted
        ) { showAiDialog("🐝 Hive Mind",
            buildString {
                append("Collective Intelligence AI\n")
                append("Enabled: ${ciStats.isEnabled}\n")
                append("Cached Patterns: ${ciStats.cachedPatterns}\n")
                append("Cached Modes: ${ciStats.cachedModes}\n")
                append("Consensus: ${ciStats.cachedConsensus}")
            }) })

        val shadow = ShadowLearningEngine.getBlockedTradeStats()
        row2.addView(buildIconTile("👁️", "Shadow",
            "${shadow.wouldHaveWon}/${shadow.totalTracked}",
            if (shadow.totalTracked > 0) indigo else muted
        ) { showAiDialog("👁️ Shadow FDG",
            buildString {
                append("Blocked Trade Analysis\n")
                append("Total Tracked: ${shadow.totalTracked}\n")
                append("Would Have Won: ${shadow.wouldHaveWon}\n")
                append("Would Have Lost: ${shadow.wouldHaveLost}\n")
                append("Top Mode: ${ShadowLearningEngine.getTopTrackedMode() ?: "—"}")
            }) })

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
        row2.addView(buildIconTile("📊", "Regimes", regimeShort, regimeColor
        ) { showAiDialog("📊 Regimes",
            buildString {
                append("Market Dominance Cycle\n")
                append("Signal: $dominance\n")
                append("Fear & Greed: ${CryptoAltScannerAI.getCryptoFearGreed()}/100\n")
                append("Sector Dominance: $dominance")
            }) })

        val plbLayers = PerpsLearningBridge.getConnectedLayerCount()
        val plbSyncs  = PerpsLearningBridge.getCrossLayerSyncs()
        row2.addView(buildIconTile("🔗", "Layers",
            "${plbSyncs}s/${plbLayers}L",
            if (plbLayers > 0) blue else muted
        ) { showAiDialog("🔗 Perps Learning Bridge",
            buildString {
                append("Cross-Layer Sync Engine\n")
                append("Connected Layers: $plbLayers\n")
                append("Cross-Layer Syncs: $plbSyncs\n")
                append("Status: ${if (plbLayers > 0) "Active" else "Idle"}")
            }) })

        val alCount = AdaptiveLearningEngine.getTradeCount()
        row2.addView(buildIconTile("📐", "Adapt",
            if (alCount > 0) "$alCount" else "NEW",
            if (alCount > 100) green else if (alCount > 0) amber else muted
        ) { showAiDialog("📐 Adaptive Learning",
            buildString {
                append("Adaptive Learning Engine\n")
                append("Total Trades: $alCount\n")
                append("Status: ${if (alCount > 100) "Mature" else if (alCount > 0) "Learning" else "New"}")
            }) })

        val fg = CryptoAltScannerAI.getCryptoFearGreed()
        row2.addView(buildIconTile("🌡️", "F&G",
            "$fg/100",
            if (fg > 60) green else if (fg < 30) red else amber
        ) { showAiDialog("🌡️ Fear & Greed",
            buildString {
                append("Crypto Fear & Greed Index\n")
                append("Current: $fg/100\n")
                append("Sentiment: ${when { fg > 75 -> "Extreme Greed"; fg > 60 -> "Greed"; fg < 25 -> "Extreme Fear"; fg < 40 -> "Fear"; else -> "Neutral" }}\n")
                append("Sector Dominance: $dominance")
            }) })

        val dynCount = DynamicAltTokenRegistry.getTokenCount()
        row2.addView(buildIconTile("🌐", "Tokens",
            "$dynCount",
            if (dynCount > 100) teal else amber
        ) { showAiDialog("🌐 Token Registry",
            buildString {
                append("Dynamic Alt Token Registry\n")
                append("Total Tokens: $dynCount\n")
                append("Dynamic: ${DynamicAltTokenRegistry.getDynamicCount()}\n")
                append("Trending: ${DynamicAltTokenRegistry.getTrendingTokens().size}\n")
                append("Boosted: ${DynamicAltTokenRegistry.getBoostedTokens().size}")
            }) })

        grid.addView(row2)
        llContent.addView(grid)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AI DETAIL DIALOG
    // ═══════════════════════════════════════════════════════════════════════════
    private fun showAiDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

        private fun buildIconTile(emoji: String, label: String, stat: String, color: Int, onClick: (() -> Unit)? = null): LinearLayout {
        return vBox(card, 10, 8).apply {
            layoutParams = llp(0, wrap, 1f).apply { marginEnd = 6 }
            gravity = android.view.Gravity.CENTER
            addView(tv(emoji, 20f, white).apply { gravity = android.view.Gravity.CENTER })
            addView(tv(label, 10f, muted).apply {
                gravity = android.view.Gravity.CENTER
                layoutParams = llp(wrap, wrap).apply { topMargin = 2 }
            })
            addView(tv(stat, 9f, color, mono = true).apply {
                gravity = android.view.Gravity.CENTER
                layoutParams = llp(wrap, wrap).apply { topMargin = 1 }
            })
            if (onClick != null) {
                isClickable = true; isFocusable = true
                setOnClickListener { onClick() }
                foreground = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, null)
            }
        }
    }

    private fun buildShadowFDGPanel() {
        val shadow  = ShadowLearningEngine.getBlockedTradeStats()
        val topMode = ShadowLearningEngine.getTopTrackedMode() ?: "—"
        val tile    = buildTile(indigo, "👁️ Shadow FDG", "Blocked Trade Analysis", indigo) { showShadowFDGDetailDialog() }
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
        val tile    = buildTile(amber, "🐝 Hive Mind", "Collective Intelligence", amber) { showHiveMindDetailDialog() }
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
        val change   = tok.priceChange24h
        val col      = if (change >= 0) green else red
        val solPrice = WalletManager.lastKnownSolPrice

        // Check if there's an open position for this token
        val openPos = CryptoAltTrader.getAllPositions().filter {
            it.closeTime == null && (it.market.symbol.equals(tok.symbol, ignoreCase = true))
        }
        val hasPosition = openPos.isNotEmpty()
        val posCardBg   = if (hasPosition) 0xFF0D1F2D.toInt() else card  // blue tint if active

        val row = hBox(posCardBg, 14, 10).apply { gravity = Gravity.CENTER_VERTICAL }

        // Token logo
        val logoImg = android.widget.ImageView(this).apply {
            val sz = (34 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = 8 }
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            try { background = androidx.core.content.ContextCompat.getDrawable(this@CryptoAltActivity, R.drawable.token_logo_bg) } catch (_: Exception) {}
            val logoUrl = tok.logoUrl.ifBlank { DynamicAltTokenRegistry.getCoinGeckoLogoUrl(tok.symbol) }
            if (logoUrl.isNotBlank()) {
                load(logoUrl) {
                    crossfade(true); placeholder(R.drawable.ic_token_placeholder)
                    error(R.drawable.ic_token_placeholder); allowHardware(false)
                    transformations(CircleCropTransformation())
                }
            } else setImageResource(R.drawable.ic_token_placeholder)
        }
        row.addView(logoImg)

        // Middle column
        val mid = vBox().apply { layoutParams = llp(0, wrap, 1f) }
        val headerRow = hBox()
        headerRow.addView(tv("${tok.emoji} ${tok.symbol}", 13f, white, bold = true))
        if (tok.isTrending && tok.trendingRank in 0..2)
            headerRow.addView(tv(" 🔥#${tok.trendingRank + 1}", 9f, orange).apply { setPadding(4, 0, 0, 0) })
        if (tok.isBoosted)
            headerRow.addView(tv(" ⚡", 9f, purple).apply { setPadding(2, 0, 0, 0) })
        if (hasPosition)
            headerRow.addView(tv(" ● OPEN", 8f, green, bold = true).apply { setPadding(4, 0, 0, 0) })
        mid.addView(headerRow)
        mid.addView(tv("${tok.name.take(18)}  ${if (tok.sector.isNotBlank()) "· ${tok.sector}" else ""}", 9f, muted))
        if (tok.mcap > 0 || tok.volume24h > 0)
            mid.addView(tv("MC:${tok.fmtMcap()}  Vol:${tok.volume24h.fmtVol()}", 9f, muted, mono = true))

        // If open position: show live PnL line
        if (hasPosition) {
            val totalPnlPct = openPos.sumOf { it.getPnlPct() } / openPos.size
            val totalPnlSol = openPos.sumOf { it.getPnlSol() }
            val totalPnlUsd = totalPnlSol * solPrice
            val pnlCol      = if (totalPnlPct >= 0) green else red
            val pnlStr      = "${if (totalPnlPct >= 0) "+" else ""}${"%.2f".format(totalPnlPct)}%  " +
                              "${if (totalPnlSol >= 0) "+" else ""}${"%.4f".format(totalPnlSol)}◎" +
                              if (solPrice > 0) "  ≈\$${"%.2f".format(totalPnlUsd)}" else ""
            mid.addView(tv(pnlStr, 9f, pnlCol, mono = true, bold = true))
        }
        row.addView(mid)

        // Right column — current price + 24h change
        val right = vBox().apply { gravity = Gravity.END }
        if (tok.price > 0)
            right.addView(tv(fmtPrice(tok.price), 12f, white, mono = true).apply { gravity = Gravity.END })
        if (change != 0.0)
            right.addView(tv("${if (change >= 0) "+" else ""}${"%.2f".format(change)}%", 11f, col, mono = true).apply { gravity = Gravity.END })
        else
            right.addView(tv("—", 11f, muted, mono = true).apply { gravity = Gravity.END })

        // If open position: current value on right
        if (hasPosition) {
            val totalVal    = openPos.sumOf { it.sizeSol + it.getPnlSol() }
            val totalValUsd = totalVal * solPrice
            right.addView(tv("${"%.4f".format(totalVal)}◎", 10f, muted, mono = true).apply { gravity = Gravity.END })
            if (solPrice > 0)
                right.addView(tv("≈\$${"%.2f".format(totalValUsd)}", 9f, muted, mono = true).apply { gravity = Gravity.END })
        }
        row.addView(right)

        // Click → show full detail dialog
        row.setOnClickListener { showTokenDetailDialog(tok, openPos) }
        return row
    }

    private fun showTokenDetailDialog(tok: DynToken, openPositions: List<CryptoAltTrader.AltPosition>) {
        val solPrice = WalletManager.lastKnownSolPrice
        val change   = tok.priceChange24h
        val changeCol = if (change >= 0) green else red

        // ── Root scroll layout ──────────────────────────────────────────────
        val scroll = android.widget.ScrollView(this).apply {
            setBackgroundColor(bg)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val root = vBox(bg, 0, 0)

        // ── Header: logo + symbol + close button ───────────────────────────
        val header = hBox(0xFF0D0D1A.toInt(), 16, 14).apply { gravity = Gravity.CENTER_VERTICAL }
        val logoSz = (44 * resources.displayMetrics.density).toInt()
        val logoImg = android.widget.ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(logoSz, logoSz).also { it.marginEnd = 12 }
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            try { background = androidx.core.content.ContextCompat.getDrawable(this@CryptoAltActivity, R.drawable.token_logo_bg) } catch (_: Exception) {}
            val logoUrl = tok.logoUrl.ifBlank { DynamicAltTokenRegistry.getCoinGeckoLogoUrl(tok.symbol) }
            if (logoUrl.isNotBlank()) load(logoUrl) {
                crossfade(true); placeholder(R.drawable.ic_token_placeholder)
                error(R.drawable.ic_token_placeholder); allowHardware(false)
                transformations(CircleCropTransformation())
            } else setImageResource(R.drawable.ic_token_placeholder)
        }
        header.addView(logoImg)
        val titleCol = vBox().apply { layoutParams = llp(0, wrap, 1f) }
        titleCol.addView(tv("${tok.emoji} ${tok.symbol}", 20f, white, bold = true))
        titleCol.addView(tv(tok.name.take(24), 11f, muted))
        if (tok.sector.isNotBlank()) titleCol.addView(tv(tok.sector, 10f, teal))
        header.addView(titleCol)
        root.addView(header)

        // ── Live price section ─────────────────────────────────────────────
        val priceSection = vBox(card2, 20, 14)
        val priceRow = hBox().apply { gravity = Gravity.BOTTOM }
        priceRow.addView(tv(fmtPrice(tok.price), 26f, white, bold = true, mono = true).apply { layoutParams = llp(0, wrap, 1f) })
        val changeBg = if (change >= 0) 0xFF052E16.toInt() else 0xFF2E0A0A.toInt()
        priceRow.addView(tv("${if (change >= 0) "▲" else "▼"}${"%.2f".format(kotlin.math.abs(change))}%",
            13f, changeCol, bold = true).apply {
            setBackgroundColor(changeBg); setPadding(8, 4, 8, 4)
        })
        priceSection.addView(priceRow)

        // Stats grid
        val statsGrid = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 12 } }
        fun statBox(label: String, value: String, col: Int = white) = vBox(0xFF0F0F1E.toInt(), 8, 8).apply {
            layoutParams = llp(0, wrap, 1f).apply { marginEnd = 4 }
            gravity = Gravity.CENTER
            addView(tv(label, 8f, muted).apply { gravity = Gravity.CENTER })
            addView(tv(value, 12f, col, mono = true, bold = true).apply { gravity = Gravity.CENTER })
        }
        statsGrid.addView(statBox("MCap", tok.fmtMcap(), blue))
        statsGrid.addView(statBox("Volume", tok.volume24h.fmtVol(), teal))
        statsGrid.addView(statBox("Liquidity", tok.liquidityUsd.fmtVol(), purple))
        statsGrid.addView(statBox("Buys/Sells", "${tok.buys24h}/${tok.sells24h}",
            if (tok.buys24h > tok.sells24h) green else red))
        priceSection.addView(statsGrid)
        root.addView(priceSection)
        root.addView(thinDivider())

        // ── Mini price chart (canvas) ──────────────────────────────────────
        val chartContainer = vBox(0xFF08080F.toInt(), 12, 8)
        chartContainer.addView(tv("📊 Price Chart  (loading…)", 10f, muted, bold = true).apply {
            tag = "chartLabel"
        })
        val chartView = MiniPriceChartView(this).apply {
            val dp = resources.displayMetrics.density
            layoutParams = llp(match, (120 * dp).toInt())
        }
        chartContainer.addView(chartView)

        // Timeframe buttons
        val tfRow = hBox().apply {
            layoutParams = llp(match, wrap).apply { topMargin = 6 }
            gravity = Gravity.CENTER
        }
        val timeframes = listOf("1m", "5m", "15m", "1H")
        var selectedTf = "15m"
        val tfBtns = mutableListOf<TextView>()

        fun loadChart(tf: String) {
            selectedTf = tf
            tfBtns.forEach { btn ->
                val isSelected = btn.text == tf
                btn.setTextColor(if (isSelected) green else muted)
                btn.setBackgroundColor(if (isSelected) 0xFF0D2E1A.toInt() else 0x00000000)
            }
            val labelTv = chartContainer.findViewWithTag<TextView>("chartLabel")
            labelTv?.text = "📊 Price Chart  ($tf)"
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // Use BirdeyeApi for Solana mints, fallback to fabricating from current price for CoinGecko tokens
                    val mint = tok.mint
                    val candles = if (!mint.startsWith("cg:") && mint.length > 20) {
                        try { BirdeyeApi().getCandles(mint, tf, 60) } catch (_: Exception) { emptyList() }
                    } else emptyList()

                    withContext(Dispatchers.Main) {
                        if (candles.isNotEmpty()) {
                            chartView.setCandles(candles)
                            labelTv?.text = "📊 Price Chart  ($tf)  ${candles.size} candles"
                        } else {
                            // Fallback: use current price + 24h change to draw a basic 2-point line
                            val nowPrice  = tok.price
                            val prevPrice = if (tok.priceChange24h != 0.0) nowPrice / (1 + tok.priceChange24h / 100.0) else nowPrice
                            chartView.setSimpleLine(listOf(prevPrice, nowPrice))
                            labelTv?.text = "📊 Price Chart  (24h snapshot)"
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        timeframes.forEach { tf ->
            val btn = tv(tf, 11f, muted).apply {
                setPadding(14, 6, 14, 6)
                setOnClickListener { loadChart(tf) }
            }
            tfBtns.add(btn)
            tfRow.addView(btn)
        }
        chartContainer.addView(tfRow)
        root.addView(chartContainer)
        root.addView(thinDivider())

        // ── Open Positions ─────────────────────────────────────────────────
        if (openPositions.isNotEmpty()) {
            val posSection = vBox(card, 16, 12)
            posSection.addView(tv("📍 Open Positions (${openPositions.size})", 13f, green, bold = true).apply {
                layoutParams = llp(match, wrap).apply { bottomMargin = 8 }
            })
            openPositions.forEach { pos ->
                val pnlPct  = pos.getPnlPct()
                val pnlSol  = pos.getPnlSol()
                val pnlUsd  = pnlSol * solPrice
                val valSol  = pos.sizeSol + pnlSol
                val valUsd  = valSol * solPrice
                val pnlCol  = if (pnlPct >= 0) green else red
                val elapsed = (System.currentTimeMillis() - pos.openTime) / 60_000

                // Direction badge
                val posHeader = hBox().apply { gravity = Gravity.CENTER_VERTICAL }
                posHeader.addView(tv("${pos.direction.emoji} ${pos.leverageLabel}", 12f,
                    if (pos.direction.name == "LONG") green else red, bold = true).apply {
                    layoutParams = llp(0, wrap, 1f)
                })
                posHeader.addView(tv("${elapsed}m ago", 10f, muted, mono = true))
                posSection.addView(posHeader)

                // Entry → current price
                val entryCurrentRow = hBox().apply {
                    layoutParams = llp(match, wrap).apply { topMargin = 4 }
                    gravity = Gravity.CENTER_VERTICAL
                }
                entryCurrentRow.addView(tv("Entry", 9f, muted).apply { layoutParams = llp(0, wrap, 1f) })
                entryCurrentRow.addView(tv("${fmtPrice(pos.entryPrice)}  →  ${fmtPrice(tok.price)}", 11f, white, mono = true))
                posSection.addView(entryCurrentRow)

                // P&L row — big and prominent
                val pnlBlock = vBox(if (pnlPct >= 0) 0xFF052E16.toInt() else 0xFF2E0A0A.toInt(), 8, 10).apply {
                    layoutParams = llp(match, wrap).apply { topMargin = 6 }
                }
                val pnlMainRow = hBox().apply { gravity = Gravity.CENTER_VERTICAL }
                pnlMainRow.addView(tv("P&L", 10f, muted).apply { layoutParams = llp(0, wrap, 1f) })
                pnlMainRow.addView(tv("${if (pnlPct >= 0) "+" else ""}${"%.2f".format(pnlPct)}%",
                    18f, pnlCol, bold = true, mono = true))
                pnlBlock.addView(pnlMainRow)

                val pnlSubRow = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 2 } }
                pnlSubRow.addView(tv("Unrealised", 9f, muted).apply { layoutParams = llp(0, wrap, 1f) })
                val pnlDetail = "${if (pnlSol >= 0) "+" else ""}${"%.4f".format(pnlSol)}◎" +
                    if (solPrice > 0) "  ≈\$${"%.2f".format(pnlUsd)}" else ""
                pnlSubRow.addView(tv(pnlDetail, 10f, pnlCol, mono = true))
                pnlBlock.addView(pnlSubRow)
                posSection.addView(pnlBlock)

                // Current value row
                val valRow = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 6 } }
                valRow.addView(tv("Current Value", 10f, muted).apply { layoutParams = llp(0, wrap, 1f) })
                val valStr = "${"%.4f".format(valSol)}◎" + if (solPrice > 0) "  ≈\$${"%.2f".format(valUsd)}" else ""
                valRow.addView(tv(valStr, 11f, white, mono = true))
                posSection.addView(valRow)

                // TP / SL row
                val tpPct = if (pos.entryPrice > 0) ((pos.takeProfitPrice / pos.entryPrice - 1) * 100).toInt() else 0
                val slPct = if (pos.entryPrice > 0) ((1 - pos.stopLossPrice / pos.entryPrice) * 100).toInt() else 0
                val tpSlRow = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 4 } }
                tpSlRow.addView(tv("TP: +$tpPct%", 10f, green, mono = true).apply { layoutParams = llp(0, wrap, 1f) })
                tpSlRow.addView(tv("SL: -$slPct%", 10f, red, mono = true))
                posSection.addView(tpSlRow)

                // Size row
                val sizeRow = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 2 } }
                sizeRow.addView(tv("Size", 10f, muted).apply { layoutParams = llp(0, wrap, 1f) })
                sizeRow.addView(tv("${"%.4f".format(pos.sizeSol)}◎", 10f, muted, mono = true))
                posSection.addView(sizeRow)

                posSection.addView(thinDivider())
            }

            // Close All button
            val closeBtn = tv("🔴  Close All Positions", 13f, red, bold = true).apply {
                setBackgroundColor(0xFF2E0A0A.toInt())
                setPadding(16, 12, 16, 12)
                gravity = Gravity.CENTER
                layoutParams = llp(match, wrap).apply { topMargin = 8 }
            }
            posSection.addView(closeBtn)
            root.addView(posSection)
            root.addView(thinDivider())
        }

        // ── Market Details section ─────────────────────────────────────────
        val detailSection = vBox(card, 16, 12)
        detailSection.addView(tv("📈 Market Details", 12f, muted, bold = true).apply {
            layoutParams = llp(match, wrap).apply { bottomMargin = 6 }
        })
        fun detailRow(label: String, value: String, col: Int = white) {
            val r = hBox().apply { layoutParams = llp(match, wrap).apply { topMargin = 3 } }
            r.addView(tv(label, 10f, muted).apply { layoutParams = llp(0, wrap, 1f) })
            r.addView(tv(value, 10f, col, mono = true))
            detailSection.addView(r)
        }
        detailRow("Price", fmtPrice(tok.price))
        detailRow("24h Change", "${if (change >= 0) "+" else ""}${"%.2f".format(change)}%", changeCol)
        detailRow("Market Cap", tok.fmtMcap(), blue)
        detailRow("Liquidity", tok.liquidityUsd.fmtVol(), teal)
        detailRow("Volume 24h", tok.volume24h.fmtVol())
        detailRow("Buys 24h", tok.buys24h.toString(), green)
        detailRow("Sells 24h", tok.sells24h.toString(), red)
        detailRow("Buy/Sell Ratio", if ((tok.buys24h + tok.sells24h) > 0)
            "${"%.1f".format(tok.buys24h.toDouble() / (tok.buys24h + tok.sells24h) * 100)}% buys" else "—",
            if (tok.buys24h > tok.sells24h) green else red)
        if (tok.sector.isNotBlank()) detailRow("Sector", tok.sector, teal)
        if (tok.ageHours > 0) detailRow("Age", "${"%.1f".format(tok.ageHours)}h")
        detailRow("Source", tok.source)
        root.addView(detailSection)

        // ── Bottom action buttons ──────────────────────────────────────────
        val actionRow = hBox(0xFF0D0D1A.toInt(), 16, 14)
        val watchBtn = tv("📌 Watch", 13f, blue, bold = true).apply {
            setBackgroundColor(0xFF0A1929.toInt()); setPadding(16, 10, 16, 10)
            layoutParams = llp(0, wrap, 1f).apply { marginEnd = 8 }
            gravity = Gravity.CENTER
        }
        actionRow.addView(watchBtn)
        root.addView(actionRow)

        scroll.addView(root)

        // ── Build & show dialog ────────────────────────────────────────────
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(scroll)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Wire buttons now that dialog exists
        watchBtn.setOnClickListener {
            WatchlistEngine.addToWatchlist(tok.symbol)
            Toast.makeText(this, "📌 ${tok.symbol} added to watchlist", Toast.LENGTH_SHORT).show()
        }
        if (openPositions.isNotEmpty()) {
            val closeBtn = root.findViewWithTag<TextView?>(null) // find by traversal
            // We set the close action directly on the button in posSection
            // The button was not tagged — find it by searching root children
            fun findCloseBtn(vg: android.view.ViewGroup): TextView? {
                for (i in 0 until vg.childCount) {
                    val ch = vg.getChildAt(i)
                    if (ch is TextView && ch.text.toString().contains("Close All")) return ch
                    if (ch is android.view.ViewGroup) findCloseBtn(ch)?.let { return it }
                }
                return null
            }
            findCloseBtn(root)?.setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    openPositions.forEach { CryptoAltTrader.requestClose(it.id) }
                    withContext(Dispatchers.Main) {
                        dialog.dismiss()
                        Toast.makeText(this@CryptoAltActivity, "🔴 Closing ${openPositions.size} position(s)", Toast.LENGTH_SHORT).show()
                        selectTab(2)
                    }
                }
            }
        }

        // Close on back tap anywhere outside content
        scroll.setOnClickListener { /* consume */ }
        dialog.show()

        // Kick off chart load
        loadChart("15m")
    }

    /**
     * Simple canvas line/sparkline chart for token price display.
     * Draws last N close prices as a smooth line with gradient fill.
     */
    inner class MiniPriceChartView @JvmOverloads constructor(
        ctx: android.content.Context, attrs: android.util.AttributeSet? = null
    ) : android.view.View(ctx, attrs) {

        private var prices: List<Double> = emptyList()
        private val linePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 2.5f * resources.displayMetrics.density
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        private val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
        }
        private val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color     = 0xFF4A5E70.toInt()
            textSize  = 9f * resources.displayMetrics.scaledDensity
            typeface  = android.graphics.Typeface.MONOSPACE
        }

        fun setCandles(candles: List<com.lifecyclebot.data.Candle>) {
            prices = candles.map { it.priceUsd }.filter { it > 0 }
            invalidate()
        }

        fun setSimpleLine(pts: List<Double>) {
            prices = pts.filter { it > 0 }
            invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            if (prices.size < 2) {
                val p = android.graphics.Paint().apply { color = 0xFF2A3A4A.toInt(); textSize = 12f * resources.displayMetrics.scaledDensity; isAntiAlias = true }
                canvas.drawText("Waiting for price data…", 16f, height / 2f, p)
                return
            }
            val padL = 48f; val padR = 12f; val padT = 12f; val padB = 24f
            val w = width.toFloat() - padL - padR
            val h = height.toFloat() - padT - padB
            val mn = prices.min()
            val mx = prices.max()
            val range = if (mx > mn) mx - mn else mn * 0.01

            val isPositive = prices.last() >= prices.first()
            val lineColor  = if (isPositive) 0xFF22C55E.toInt() else 0xFFEF4444.toInt()
            val fillColor  = if (isPositive) 0x1422C55E.toInt() else 0x14EF4444.toInt()
            linePaint.color = lineColor
            fillPaint.color = fillColor

            fun px(i: Int) = padL + i.toFloat() / (prices.size - 1) * w
            fun py(v: Double) = padT + h - ((v - mn) / range * h).toFloat()

            // Fill path
            val fillPath = android.graphics.Path()
            fillPath.moveTo(px(0), padT + h)
            prices.forEachIndexed { i, v -> fillPath.lineTo(px(i), py(v)) }
            fillPath.lineTo(px(prices.size - 1), padT + h)
            fillPath.close()
            canvas.drawPath(fillPath, fillPaint)

            // Line path
            val linePath = android.graphics.Path()
            prices.forEachIndexed { i, v ->
                if (i == 0) linePath.moveTo(px(i), py(v)) else linePath.lineTo(px(i), py(v))
            }
            canvas.drawPath(linePath, linePaint)

            // Y axis labels (min/max/last)
            fun fmtLabel(v: Double) = when {
                v >= 1000 -> "$%.0f".format(v)
                v >= 1    -> "$%.4f".format(v)
                v >= 0.001 -> "$%.6f".format(v)
                else       -> "$%.8f".format(v)
            }
            canvas.drawText(fmtLabel(mx), 2f, padT + 4, labelPaint)
            canvas.drawText(fmtLabel(mn), 2f, padT + h, labelPaint)
            val lastY = py(prices.last())
            val dotPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = lineColor; style = android.graphics.Paint.Style.FILL }
            canvas.drawCircle(px(prices.size - 1), lastY, 4f * resources.displayMetrics.density, dotPaint)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WATCHLIST TAB
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildWatchlistTab() {
        val watchlistItems   = WatchlistEngine.getWatchlist()
        val probationEntries = com.lifecyclebot.engine.GlobalTradeRegistry.getProbationEntries()

        // ── 3-column layout: Probation | Watchlist | (empty state) ─────────────
        val hasProb = probationEntries.isNotEmpty()

        // Section headers row
        val headerRow = hBox().apply { layoutParams = llp(match, wrap).apply { bottomMargin = 4 } }
        if (hasProb) {
            headerRow.addView(tv("⏳ Probation (${probationEntries.size})", 11f, amber, bold = true).apply {
                layoutParams = llp(0, wrap, 1f)
            })
            headerRow.addView(View(this).apply {
                layoutParams = llp(1, LinearLayout.LayoutParams.MATCH_PARENT).apply { marginStart = 6; marginEnd = 6 }
                setBackgroundColor(divBg)
            })
        }
        headerRow.addView(tv("👀 Watchlist (${watchlistItems.size})", 11f, blue, bold = true).apply {
            layoutParams = llp(0, wrap, 1f)
        })
        llContent.addView(headerRow)
        llContent.addView(thinDivider())

        if (!hasProb && watchlistItems.isEmpty()) {
            addEmptyState("No tokens on watchlist. Tap a token in Scanner and choose 📌 Watch.")
            return
        }

        // ── Main columns ───────────────────────────────────────────────────────
        val columnsRow = hBox().apply {
            layoutParams = llp(match, wrap)
            gravity = Gravity.TOP
        }

        // ── PROBATION COLUMN ───────────────────────────────────────────────────
        if (hasProb) {
            val probCol = vBox(0, 0, 0).apply { layoutParams = llp(0, wrap, 1f).apply { marginEnd = 4 } }
            probationEntries.forEach { entry ->
                val elapsed = (System.currentTimeMillis() - entry.addedAt) / 1000
                val elapsedStr = if (elapsed < 60) "${elapsed}s" else "${elapsed/60}m"

                val probCard = vBox(card, 8, 8).apply {
                    layoutParams = llp(match, wrap).apply { bottomMargin = 4 }
                }
                // Logo + symbol row
                val r1 = hBox().apply { gravity = Gravity.CENTER_VERTICAL }
                val logoImg = android.widget.ImageView(this).apply {
                    val sz = (28 * resources.displayMetrics.density).toInt()
                    layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = 6 }
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    try { background = androidx.core.content.ContextCompat.getDrawable(this@CryptoAltActivity, R.drawable.token_logo_bg) } catch (_: Exception) {}
                    load("https://cdn.dexscreener.com/tokens/solana/${entry.mint}.png") {
                        crossfade(true); placeholder(R.drawable.ic_token_placeholder)
                        error(R.drawable.ic_token_placeholder); allowHardware(false)
                        transformations(coil.transform.CircleCropTransformation())
                    }
                }
                r1.addView(logoImg)
                r1.addView(tv(entry.symbol.take(8), 11f, amber, bold = true).apply { layoutParams = llp(0, wrap, 1f) })
                r1.addView(tv(elapsedStr, 9f, muted, mono = true))
                probCard.addView(r1)
                // Reason
                probCard.addView(tv(entry.source.take(28), 8f, muted))
                probCol.addView(probCard)
            }
            columnsRow.addView(probCol)

            // Vertical divider
            columnsRow.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT).also {
                    it.marginStart = 2; it.marginEnd = 4
                }
                setBackgroundColor(divBg)
            })
        }

        // ── WATCHLIST COLUMN ───────────────────────────────────────────────────
        val watchCol = vBox(0, 0, 0).apply { layoutParams = llp(0, wrap, if (hasProb) 1.4f else 1f) }
        if (watchlistItems.isEmpty()) {
            watchCol.addView(tv("(empty)", 11f, muted).apply { setPadding(4, 8, 4, 4) })
        } else {
            val solPriceW = WalletManager.lastKnownSolPrice
            watchlistItems.forEach { item ->
                val symbol   = item.symbol
                val tok      = DynamicAltTokenRegistry.getTokenBySymbol(symbol)
                val change   = tok?.priceChange24h ?: 0.0
                val col      = if (change >= 0) green else red
                val openPos  = CryptoAltTrader.getAllPositions().filter {
                    it.closeTime == null && it.market.symbol.equals(symbol, ignoreCase = true)
                }
                val hasPos   = openPos.isNotEmpty()
                val cardBg   = if (hasPos) 0xFF0D1F2D.toInt() else card

                val wCard = vBox(cardBg, 8, 8).apply {
                    layoutParams = llp(match, wrap).apply { bottomMargin = 4 }
                }
                // Row 1: Logo + symbol + remove button
                val r1 = hBox().apply { gravity = Gravity.CENTER_VERTICAL }
                if (tok != null) {
                    val logoImg = android.widget.ImageView(this).apply {
                        val sz = (28 * resources.displayMetrics.density).toInt()
                        layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = 6 }
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        try { background = androidx.core.content.ContextCompat.getDrawable(this@CryptoAltActivity, R.drawable.token_logo_bg) } catch (_: Exception) {}
                        val logoUrl = tok.logoUrl.ifBlank { DynamicAltTokenRegistry.getCoinGeckoLogoUrl(tok.symbol) }
                        if (logoUrl.isNotBlank()) load(logoUrl) {
                            crossfade(true); placeholder(R.drawable.ic_token_placeholder)
                            error(R.drawable.ic_token_placeholder); allowHardware(false)
                            transformations(coil.transform.CircleCropTransformation())
                        } else setImageResource(R.drawable.ic_token_placeholder)
                    }
                    r1.addView(logoImg)
                }
                val symbolLabel = tv("${tok?.emoji ?: "⚪"} $symbol", 11f, white, bold = true).apply { layoutParams = llp(0, wrap, 1f) }
                if (hasPos) symbolLabel.append("  ●")
                r1.addView(symbolLabel)
                r1.addView(tv("✕", 11f, red).apply {
                    setPadding(6, 2, 6, 2)
                    setOnClickListener { WatchlistEngine.removeFromWatchlist(symbol); selectTab(1) }
                })
                wCard.addView(r1)

                // Row 2: live price + 24h change
                if (tok != null) {
                    val r2 = hBox().apply { setPadding(0, 2, 0, 0) }
                    r2.addView(tv(fmtPrice(tok.price), 9f, white, mono = true).apply { layoutParams = llp(0, wrap, 1f) })
                    r2.addView(tv("${if (change >= 0) "+" else ""}${"%.2f".format(change)}%", 9f, col, mono = true))
                    wCard.addView(r2)
                    // Row 3: MCap + volume
                    val r3 = hBox().apply { setPadding(0, 1, 0, 0) }
                    r3.addView(tv("MC:${tok.fmtMcap()}", 8f, muted, mono = true).apply { layoutParams = llp(0, wrap, 1f) })
                    r3.addView(tv("V:${tok.volume24h.fmtVol()}", 8f, muted, mono = true))
                    wCard.addView(r3)
                }

                // Row 4: PnL if open position
                if (hasPos) {
                    val totalPnlPct = openPos.sumOf { it.getPnlPct() } / openPos.size
                    val totalPnlSol = openPos.sumOf { it.getPnlSol() }
                    val totalPnlUsd = totalPnlSol * solPriceW
                    val pnlCol      = if (totalPnlPct >= 0) green else red
                    val pnlRow = hBox().apply { setPadding(0, 3, 0, 0) }
                    pnlRow.addView(tv("P&L", 8f, muted).apply { layoutParams = llp(0, wrap, 1f) })
                    val pnlStr = "${if (totalPnlPct >= 0) "+" else ""}${"%.2f".format(totalPnlPct)}%  ${if (totalPnlSol >= 0) "+" else ""}${"%.4f".format(totalPnlSol)}◎"
                    pnlRow.addView(tv(pnlStr, 9f, pnlCol, mono = true, bold = true))
                    wCard.addView(pnlRow)
                    if (solPriceW > 0) {
                        val valSol    = openPos.sumOf { it.sizeSol + it.getPnlSol() }
                        val valUsd    = valSol * solPriceW
                        val usdRow    = hBox()
                        usdRow.addView(tv("Value", 8f, muted).apply { layoutParams = llp(0, wrap, 1f) })
                        usdRow.addView(tv("${"%.4f".format(valSol)}◎  ≈\$${"%.2f".format(valUsd)}", 9f, muted, mono = true))
                        wCard.addView(usdRow)
                    }
                }

                wCard.setOnClickListener {
                    if (tok != null) showTokenDetailDialog(tok, openPos)
                }
                watchCol.addView(wCard)
            }
        }
        columnsRow.addView(watchCol)
        llContent.addView(columnsRow)
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

    private fun buildTile(accentColor: Int, title: String, badge: String, badgeColor: Int, onClick: (() -> Unit)? = null): LinearLayout {
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
            if (onClick != null) {
                header.addView(tv(" ›", 16f, 0xFF4B5563.toInt()).apply { setPadding(4, 0, 0, 0) })
            }
            addView(header)
            if (onClick != null) {
                isClickable = true; isFocusable = true
                setOnClickListener { onClick() }
                foreground = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x22FFFFFF), null, null)
            }
        }
    }

    private fun addStatChip(parent: LinearLayout, label: String, value: String, valueColor: Int, weight: Float) {
        parent.addView(vBox(0xFF111827.toInt(), 8, 6).apply {
            layoutParams = llp(0, wrap, weight).apply { marginEnd = 3 }
            gravity = Gravity.CENTER
            addView(tv(value, 13f, valueColor, mono = true, bold = true).apply { gravity = Gravity.CENTER })
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

    // ═══════════════════════════════════════════════════════════════════════════
    // MODE DETAIL DIALOGS — tap any tile to see full stats
    // ═══════════════════════════════════════════════════════════════════════════

    private fun showReadinessDetailDialog() {
        try {
            val phase      = getPhaseLabel()
            val wr         = CryptoAltTrader.getWinRate()
            val trades     = CryptoAltTrader.getTotalTrades()
            val flProgress = (FluidLearningAI.getMarketsLearningProgress() * 100).toInt()
            val flTrades   = FluidLearningAI.getMarketsTradeCount()
            val confBoost  = FluidLearningAI.getBootstrapConfidenceBoost()
            val sizeMult   = FluidLearningAI.getBootstrapSizeMultiplier()
            val threshold  = FluidLearningAI.getMarketsSpotScoreThreshold()
            val msg = buildString {
                appendLine("🚦 LIVE READINESS — ALT TRADER")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("Phase: $phase")
                appendLine("Win Rate: ${"%.1f".format(wr)}%")
                appendLine("Trades: $trades")
                appendLine()
                appendLine("📚 FLUID LEARNING")
                appendLine("━━━━━━━━━━━━━━━━━")
                appendLine("Progress: $flProgress%")
                appendLine("Markets Trades: $flTrades")
                appendLine("Confidence Boost: +${"%.1f".format(confBoost * 100)}%")
                appendLine("Size Multiplier: ${"%.2f".format(sizeMult)}x")
                appendLine("Score Threshold: $threshold")
                appendLine()
                appendLine("Requirements to go LIVE:")
                appendLine("• 1000+ trades")
                appendLine("• 55%+ win rate")
                appendLine("• 30-day proof run complete")
            }
            AlertDialog.Builder(this)
                .setTitle("🚦 Live Readiness")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Readiness: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showProofRunDetailDialog() {
        try {
            val isActive  = RunTracker30D.isRunActive()
            val day       = RunTracker30D.getCurrentDay()
            val pnl       = RunTracker30D.totalRealizedPnlSol
            val maxDD     = RunTracker30D.maxDrawdown
            val integrity = RunTracker30D.integrityScore()
            val msg = buildString {
                appendLine("📈 30-DAY PROOF RUN")
                appendLine("━━━━━━━━━━━━━━━━━━")
                appendLine()
                if (isActive) {
                    appendLine("Day: $day / 30")
                    appendLine("Realized PnL: ${if (pnl >= 0) "+" else ""}${"%.4f".format(pnl)}◎")
                    appendLine("Max Drawdown: ${"%.1f".format(maxDD)}%")
                    appendLine("Integrity: $integrity / 100")
                } else {
                    appendLine("Status: NOT STARTED")
                    appendLine()
                    appendLine("Complete 30 consecutive trading")
                    appendLine("days to qualify for live mode.")
                }
                appendLine()
                appendLine("The proof run validates that the")
                appendLine("AI is ready for real money.")
            }
            AlertDialog.Builder(this)
                .setTitle("📈 30-Day Proof Run")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "ProofRun: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showShitCoinDetailDialog() {
        try {
            val stats   = ShitCoinTraderAI.getStats()
            val mode    = ShitCoinTraderAI.getCurrentMode()
            val modeEmoji = when (mode) {
                ShitCoinTraderAI.ShitCoinMode.HUNTING    -> "🎯"
                ShitCoinTraderAI.ShitCoinMode.POSITIONED -> "📍"
                ShitCoinTraderAI.ShitCoinMode.CAUTIOUS   -> "⚠️"
                ShitCoinTraderAI.ShitCoinMode.PAUSED     -> "⏸️"
                ShitCoinTraderAI.ShitCoinMode.GRADUATION -> "🎓"
            }
            val msg = buildString {
                appendLine("💩 SHITCOIN DEGEN")
                appendLine("━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("$modeEmoji Mode: ${mode.name}")
                appendLine("Balance: ${"%.4f".format(stats.balanceSol)}◎")
                appendLine()
                appendLine("📊 DAILY STATS")
                appendLine("W/L: ${stats.dailyWins} / ${stats.dailyLosses}")
                appendLine("Trades: ${stats.dailyTradeCount}")
                appendLine("Win Rate: ${"%.1f".format(stats.winRate)}%")
                appendLine("Day PnL: ${if (stats.dailyPnlSol >= 0) "+" else ""}${"%.4f".format(stats.dailyPnlSol)}◎")
                appendLine("Open: ${stats.activePositions}")
                appendLine()
                appendLine("🎰 TARGETS")
                appendLine("MCap: < \$30K")
                appendLine("Age: < 6 hours")
                appendLine("Max Size: 0.20◎")
                appendLine("Hold: < 15 min")
            }
            AlertDialog.Builder(this)
                .setTitle("💩 ShitCoin Degen")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .setNeutralButton("Reset Daily") { d, _ ->
                    ShitCoinTraderAI.resetDaily()
                    Toast.makeText(this, "ShitCoin daily reset", Toast.LENGTH_SHORT).show()
                    d.dismiss()
                }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "ShitCoin: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showQualityDetailDialog() {
        try {
            val wr        = QualityTraderAI.getWinRate()
            val pnl       = QualityTraderAI.getDailyPnl()
            val positions = QualityTraderAI.getActivePositions()
            val tp        = QualityTraderAI.getFluidTakeProfit()
            val sl        = QualityTraderAI.getFluidStopLoss()
            val posList   = if (positions.isEmpty()) "  (none)" else
                positions.joinToString("\n") { pos ->
                    "  • ${pos.symbol}  \$${(pos.entryMcap / 1_000).toInt()}K  ${((System.currentTimeMillis() - pos.entryTime) / 60_000)}m"
                }
            val msg = buildString {
                appendLine("💎 QUALITY MODE  (\$100K–\$1M)")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("Win Rate: ${"%.1f".format(wr)}%")
                appendLine("Day PnL: ${if (pnl >= 0) "+" else ""}${"%.4f".format(pnl)}◎")
                appendLine("Open: ${positions.size}")
                appendLine("TP: +${tp.toInt()}%  SL: -${sl.toInt()}%")
                appendLine()
                appendLine("📍 POSITIONS")
                appendLine(posList)
                appendLine()
                appendLine("CRITERIA")
                appendLine("• MCap: \$100K – \$1M")
                appendLine("• Age: 30+ min")
                appendLine("• Holders: 50+")
            }
            AlertDialog.Builder(this)
                .setTitle("💎 Quality Mode")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Quality: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBlueChipDetailDialog() {
        try {
            val stats     = BlueChipTraderAI.getStats()
            val wr        = BlueChipTraderAI.getWinRatePct()
            val pnl       = BlueChipTraderAI.getDailyPnlSol()
            val bal       = BlueChipTraderAI.getCurrentBalance()
            val positions = BlueChipTraderAI.getActivePositions()
            val modeEmoji = when (stats.mode) {
                BlueChipTraderAI.BlueChipMode.HUNTING    -> "🎯"
                BlueChipTraderAI.BlueChipMode.POSITIONED -> "📊"
                BlueChipTraderAI.BlueChipMode.CAUTIOUS   -> "⚠️"
                BlueChipTraderAI.BlueChipMode.PAUSED     -> "⏸️"
            }
            val posList = if (positions.isEmpty()) "  (none)" else
                positions.joinToString("\n") { pos ->
                    val mcapM = pos.marketCapUsd / 1_000_000
                    val mcapStr = if (mcapM >= 1) "${"%.1f".format(mcapM)}M" else "${(pos.marketCapUsd / 1_000).toInt()}K"
                    "  • ${pos.symbol}  \$$mcapStr  ${((System.currentTimeMillis() - pos.entryTime) / 60_000)}m"
                }
            val msg = buildString {
                appendLine("🔵 BLUE CHIP TRADES  (\$1M+)")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("$modeEmoji Mode: ${stats.mode.name}")
                appendLine("Balance: ${"%.4f".format(bal)}◎")
                appendLine("Win Rate: $wr%")
                appendLine("Day PnL: ${if (pnl >= 0) "+" else ""}${"%.4f".format(pnl)}◎")
                appendLine("Open: ${positions.size}")
                appendLine()
                appendLine("📍 POSITIONS")
                appendLine(posList)
                appendLine()
                appendLine("CRITERIA")
                appendLine("• MCap: \$1M+")
                appendLine("• Liq: \$200K+")
                appendLine("• TP: 10-20%  SL: -8%")
            }
            AlertDialog.Builder(this)
                .setTitle("🔵 Blue Chip Trades")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .setNeutralButton("Reset Daily") { d, _ ->
                    BlueChipTraderAI.resetDaily()
                    Toast.makeText(this, "BlueChip daily reset", Toast.LENGTH_SHORT).show()
                    d.dismiss()
                }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "BlueChip: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showExpressDetailDialog() {
        try {
            val stats = ShitCoinExpress.getStats()
            val rides = ShitCoinExpress.getActiveRides()
            val msg = buildString {
                appendLine("⚡ EXPRESS MODE")
                appendLine("━━━━━━━━━━━━━━")
                appendLine()
                appendLine("Strategy: High Velocity Momentum")
                appendLine("Win Rate: ${"%.1f".format(stats.winRate)}%")
                appendLine("Day PnL: ${if (stats.dailyPnlSol >= 0) "+" else ""}${"%.4f".format(stats.dailyPnlSol)}◎")
                appendLine("Daily Rides: ${stats.dailyRides}")
                appendLine("Active Rides: ${rides.size}")
                appendLine()
                if (rides.isNotEmpty()) {
                    appendLine("🏇 ACTIVE RIDES")
                    rides.forEach { r ->
                        appendLine("  • ${r.symbol}  Mom:${"%.0f".format(r.entryMomentum)}  BP:${"%.0f".format(r.entryBuyPressure)}%  ${((System.currentTimeMillis()-r.entryTime)/60000)}m")
                    }
                }
                appendLine()
                appendLine("Exits on momentum fade or")
                appendLine("buy pressure drop below 40%")
            }
            AlertDialog.Builder(this)
                .setTitle("⚡ Express Mode")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Express: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMoonshotDetailDialog() {
        try {
            val wr        = MoonshotTraderAI.getWinRatePct()
            val pnl       = MoonshotTraderAI.getDailyPnlSol()
            val positions = MoonshotTraderAI.getActivePositions()
            val tenX      = MoonshotTraderAI.getLifetimeTenX()
            val hundredX  = MoonshotTraderAI.getLifetimeHundredX()
            val thousandX = MoonshotTraderAI.getLifetimeThousandX()
            val learn     = (MoonshotTraderAI.getLearningProgress() * 100).toInt()
            val spaceModeStats = MoonshotTraderAI.getSpaceModeStats()
            val posList = if (positions.isEmpty()) "  (none)" else
                positions.joinToString("\n") { "  ${it.spaceMode.emoji} ${it.symbol}  ${((System.currentTimeMillis() - it.entryTime) / 60_000)}m  TP:+${it.takeProfitPct.toInt()}%" }
            val msg = buildString {
                appendLine("🌙 MOONSHOT MODE")
                appendLine("━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("Learning: $learn%")
                appendLine("Win Rate: $wr%")
                appendLine("Day PnL: ${if (pnl >= 0) "+" else ""}${"%.4f".format(pnl)}◎")
                appendLine("W/L: ${MoonshotTraderAI.getDailyWins()}/${MoonshotTraderAI.getDailyLosses()}")
                appendLine()
                appendLine("🏆 LIFETIME HITS")
                appendLine("🔟 10x: $tenX   💯 100x: $hundredX   🌌 1000x: $thousandX")
                appendLine()
                appendLine("📍 POSITIONS (${positions.size})")
                appendLine(posList)
                appendLine()
                appendLine("SPACE MODES")
                spaceModeStats.forEach { (mode, cnt) ->
                    if (cnt > 0) appendLine("  ${mode.emoji} ${mode.displayName}: $cnt")
                }
            }
            AlertDialog.Builder(this)
                .setTitle("🌙 Moonshot Mode")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .setNeutralButton("Reset Daily") { d, _ ->
                    MoonshotTraderAI.resetDaily()
                    Toast.makeText(this, "Moonshot daily reset", Toast.LENGTH_SHORT).show()
                    d.dismiss()
                }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Moonshot: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showManipDetailDialog() {
        try {
            val stats     = ManipulatedTraderAI.getStats()
            val positions = ManipulatedTraderAI.getActivePositions()
            val manipWr   = if (stats.dailyWins + stats.dailyLosses > 0)
                (stats.dailyWins.toDouble() / (stats.dailyWins + stats.dailyLosses) * 100) else 0.0
            val posList = if (positions.isEmpty()) "  (none)" else
                positions.joinToString("\n") { "  🎭 ${it.symbol}  Score:${it.manipScore}  ${((System.currentTimeMillis() - it.entryTime) / 60_000)}m" }
            val msg = buildString {
                appendLine("🎭 MANIP CATCH")
                appendLine("━━━━━━━━━━━━━")
                appendLine()
                appendLine("Strategy: Manipulation Detector")
                appendLine("Total Caught: ${stats.totalManipCaught}")
                appendLine("Win Rate: ${"%.1f".format(manipWr)}%")
                appendLine("Day PnL: ${if (stats.dailyPnlSol >= 0) "+" else ""}${"%.4f".format(stats.dailyPnlSol)}◎")
                appendLine("W/L: ${stats.dailyWins}/${stats.dailyLosses}")
                appendLine("Open: ${stats.activeCount}")
                appendLine()
                appendLine("📍 POSITIONS")
                appendLine(posList)
                appendLine()
                appendLine("Catches pump & dump patterns,")
                appendLine("wash trading, and fake volume.")
            }
            AlertDialog.Builder(this)
                .setTitle("🎭 Manip Catch")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Manip: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showShadowFDGDetailDialog() {
        try {
            val shadow  = ShadowLearningEngine.getBlockedTradeStats()
            val topMode = ShadowLearningEngine.getTopTrackedMode() ?: "—"
            val msg = buildString {
                appendLine("👁️ SHADOW FDG LEARNING")
                appendLine("━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("Blocked Trade Analysis")
                appendLine("Total Tracked: ${shadow.totalTracked}")
                appendLine("Would Have Won: ${shadow.wouldHaveWon}")
                appendLine("Would Have Lost: ${shadow.wouldHaveLost}")
                appendLine("Top Mode: $topMode")
                appendLine()
                val whr = if (shadow.totalTracked > 0)
                    (shadow.wouldHaveWon.toDouble() / shadow.totalTracked * 100).toInt() else 0
                appendLine("Shadow Win Rate: $whr%")
                appendLine()
                appendLine("Shadow learning tracks trades")
                appendLine("that were blocked by FDG filters")
                appendLine("to improve AI decision making.")
            }
            AlertDialog.Builder(this)
                .setTitle("👁️ Shadow FDG")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Shadow: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showHiveMindDetailDialog() {
        try {
            val ciStats = com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI.getStats()
            val clStats = com.lifecyclebot.collective.CollectiveLearning.getStats()
            val msg = buildString {
                appendLine("🐝 HIVE MIND")
                appendLine("━━━━━━━━━━━")
                appendLine()
                appendLine("Collective Intelligence AI")
                appendLine("Enabled: ${ciStats.isEnabled}")
                appendLine("Cached Patterns: ${ciStats.cachedPatterns}")
                appendLine("Cached Modes: ${ciStats.cachedModes}")
                appendLine("Consensus: ${ciStats.cachedConsensus}")
                appendLine("Conf Threshold: ${ciStats.dynamicConfThreshold}%")
                appendLine("Anomalies: ${ciStats.anomaliesDetected}")
                appendLine()
                appendLine("🌐 COLLECTIVE LEARNING")
                appendLine("Network: ${if (com.lifecyclebot.collective.CollectiveLearning.isEnabled()) "CONNECTED" else "OFFLINE"}")
                appendLine("Patterns: ${clStats["patterns"]}")
                appendLine("Blacklisted: ${clStats["blacklistedTokens"]}")
                appendLine()
                appendLine("MODE RECOMMENDATIONS")
                listOf("ShitCoin","BlueChip","Express","Moonshot","Manip").forEach { mode ->
                    val rec = com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI.getModeRecommendation(mode)
                    appendLine("  $mode → ${rec.name}")
                }
            }
            AlertDialog.Builder(this)
                .setTitle("🐝 Hive Mind")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "HiveMind: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

}

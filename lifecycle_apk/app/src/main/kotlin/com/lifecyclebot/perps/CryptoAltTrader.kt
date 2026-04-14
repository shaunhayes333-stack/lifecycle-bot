package com.lifecyclebot.perps

import android.content.Context
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.WalletManager
import com.lifecyclebot.v3.scoring.FluidLearningAI
import com.lifecyclebot.v4.meta.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

// ═══════════════════════════════════════════════════════════════════════════════
// 🪙 CRYPTO ALT TRADER — V1.0.0
// ═══════════════════════════════════════════════════════════════════════════════
//
// Third dedicated trading engine for the full crypto alt market.
// Mirrors the SOL perps (meme) trader architecture exactly:
//   • Paper-first learning (5000-trade maturity arc, FluidLearningAI)
//   • Identical UI readiness phases: BOOTSTRAP → LEARNING → VALIDATING → MATURING → READY → LIVE
//   • Full V3/V4 AI layer stack (PerpsAdvancedAI, CrossTalkFusionEngine, PortfolioHeatAI,
//     CrossAssetLeadLagAI, CrossMarketRegimeAI, LeverageSurvivalAI)
//   • New CryptoAltScannerAI layer for alt-specific signals (BTC correlation, sector rotation,
//     dominance cycles, narrative momentum, L1/L2 spread, DeFi heat)
//   • Live execution via Jupiter DEX swap (SOL → wrapped alt token) using existing wallet
//   • Same SPOT / LEVERAGE toggle, same position card structure, same Hivemind wiring
//
// Markets:
//   All isCrypto && !isSolPerp markets from PerpsMarket (SOL perps engine covers the isSolPerp set)
//   ~68 alts: BTC, ETH, BNB, XRP, SHIB, FLOKI, TRUMP, POPCAT, ADA, SEI, FTM, ALGO,
//             HBAR, ICP, VET, FIL, RENDER, GRT, AAVE, MKR, STX, IMX, SAND, MANA, AXS,
//             ENS, LDO, RPL, MNGO, TRX, TON, BCH, XLM, XMR, ETC, ZEC, XTZ, EOS,
//             CAKE, NOT, and more.
//
// ═══════════════════════════════════════════════════════════════════════════════

object CryptoAltTrader {

    private const val TAG = "🪙CryptoAltTrader"

    // ─── Constants ────────────────────────────────────────────────────────────
    private const val MAX_POSITIONS         = 30            // Up to 30 concurrent alt positions
    private const val SCAN_INTERVAL_MS      = 12_000L       // 12-second scan cycle
    private const val DEFAULT_SIZE_PCT      = 5.0           // 5% of balance per trade
    private const val DEFAULT_LEVERAGE      = 3.0           // Default leverage (when not SPOT)
    private const val DEFAULT_TP_SPOT       = 6.0           // SPOT take-profit %
    private const val DEFAULT_SL_SPOT       = 3.5           // SPOT stop-loss %
    private const val DEFAULT_TP_LEV        = 10.0          // LEVERAGE take-profit %
    private const val DEFAULT_SL_LEV        = 5.0           // LEVERAGE stop-loss %

    // Alts that the SOL perps engine already handles — excluded here
    private val SOL_PERPS_SYMBOLS = setOf(
        "SOL", "BTC", "ETH", "BNB", "XRP", "ADA", "DOGE", "AVAX", "DOT", "LINK",
        "MATIC", "LTC", "ATOM", "UNI", "ARB", "OP", "APT", "SUI", "INJ", "JUP",
        "PEPE", "WIF", "BONK", "NEAR", "TIA", "PYTH", "RAY", "ORCA", "DRIFT",
        "WLD", "JTO", "W", "STRK", "TAO", "GMX", "DYDX", "ENA", "PENDLE"
    )

    // ─── State ────────────────────────────────────────────────────────────────
    private val positions        = ConcurrentHashMap<String, AltPosition>()
    private val spotPositions    = ConcurrentHashMap<String, AltPosition>()
    private val leveragePositions= ConcurrentHashMap<String, AltPosition>()

    private val isRunning        = AtomicBoolean(false)
    private val isEnabled        = AtomicBoolean(true)
    private val isPaperMode      = AtomicBoolean(true)
    private val scanCount        = AtomicInteger(0)
    private val positionCounter  = AtomicInteger(0)
    private val totalTrades      = AtomicInteger(0)
    private val winningTrades    = AtomicInteger(0)
    private val losingTrades     = AtomicInteger(0)

    @Volatile private var paperBalance    = 100.0   // 100 SOL paper start (mirrors meme trader)
    @Volatile private var liveWalletBalance = 0.0
    @Volatile private var totalPnlSol     = 0.0

    private var engineJob : Job? = null
    private var monitorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ─── Position model ───────────────────────────────────────────────────────
    data class AltPosition(
        val id            : String,
        val market        : PerpsMarket,
        val direction     : PerpsDirection,
        val isSpot        : Boolean,
        val entryPrice    : Double,
        var currentPrice  : Double,
        val sizeSol       : Double,
        val leverage      : Double,
        val takeProfitPrice: Double,
        val stopLossPrice  : Double,
        val aiScore       : Int,
        val aiConfidence  : Int,
        val reasons       : List<String>,
        val openTime      : Long = System.currentTimeMillis(),
        var closeTime     : Long? = null,
        var closePrice    : Double? = null,
        var realizedPnl   : Double? = null
    ) {
        val leverageLabel: String get() = if (isSpot) "SPOT" else "${leverage.toInt()}x"

        fun getPnlPct(): Double {
            val diff = currentPrice - entryPrice
            val dir  = if (direction == PerpsDirection.LONG) 1.0 else -1.0
            return (diff / entryPrice) * dir * leverage * 100.0
        }

        fun getPnlSol(): Double = sizeSol * (getPnlPct() / 100.0)

        fun shouldTakeProfit(tpPct: Double): Boolean {
            return getPnlPct() >= tpPct
        }

        fun shouldStopLoss(slPct: Double): Boolean {
            return getPnlPct() <= -slPct
        }
    }

    // ─── Alt signal model ─────────────────────────────────────────────────────
    data class AltSignal(
        val market      : PerpsMarket,
        val direction   : PerpsDirection,
        val score       : Int,
        val confidence  : Int,
        val price       : Double,
        val priceChange24h: Double,
        val reasons     : List<String>,
        val layerVotes  : Map<String, PerpsDirection>,
        val leverage    : Double = DEFAULT_LEVERAGE
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════

    fun init(context: Context) {
        scope.launch {
            loadPersistedState()
        }
        ErrorLogger.info(TAG, "🪙 CryptoAltTrader INITIALIZED | paper=${isPaperMode.get()} | balance=${"%.2f".format(paperBalance)} SOL")
    }

    fun start() {
        if (isRunning.get()) {
            ErrorLogger.debug(TAG, "Already running")
            return
        }
        isRunning.set(true)

        engineJob = scope.launch {
            ErrorLogger.error(TAG, "🪙🪙🪙 CryptoAltTrader ENGINE STARTED 🪙🪙🪙")
            ErrorLogger.error(TAG, "🪙 Scanning every ${SCAN_INTERVAL_MS / 1000}s | enabled=${isEnabled.get()}")

            try {
                ErrorLogger.error(TAG, "🪙🪙🪙 Running INITIAL alt scan NOW... 🪙🪙🪙")
                runScanCycle()
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { ErrorLogger.error(TAG, "🪙 Initial scan error: ${e.message}", e) }

            while (isRunning.get()) {
                try {
                    delay(SCAN_INTERVAL_MS)
                    if (isEnabled.get()) runScanCycle()
                    else ErrorLogger.debug(TAG, "🪙 Alt trading DISABLED — skipping scan")
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) { ErrorLogger.error(TAG, "Scan cycle error: ${e.message}", e) }
            }
        }

        monitorJob = scope.launch {
            while (isRunning.get()) {
                try { monitorPositions() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { ErrorLogger.error(TAG, "Monitor error: ${e.message}", e) }
                delay(5_000)
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        engineJob?.cancel()
        monitorJob?.cancel()
        ErrorLogger.info(TAG, "🪙 CryptoAltTrader STOPPED")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SCAN CYCLE
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun runScanCycle() {
        scanCount.incrementAndGet()
        val scanNum = scanCount.get()

        ErrorLogger.error(TAG, "🪙 ═══════════════════════════════════════════════════")
        ErrorLogger.error(TAG, "🪙 ALT SCAN #$scanNum STARTING")
        ErrorLogger.error(TAG, "🪙 positions=${positions.size}/$MAX_POSITIONS | balance=${"%.2f".format(getBalance())} SOL")
        ErrorLogger.error(TAG, "🪙 ═══════════════════════════════════════════════════")

        // All crypto markets that are NOT covered by the SOL perps engine
        val altMarkets = PerpsMarket.values().filter { m ->
            m.isCrypto && !SOL_PERPS_SYMBOLS.contains(m.symbol)
        }

        val signals      = mutableListOf<AltSignal>()
        var analyzed     = 0
        var skippedPos   = 0
        var skippedPrice = 0

        for (market in altMarkets) {
            try {
                if (hasPosition(market)) { skippedPos++; continue }

                val data = PerpsMarketDataFetcher.getMarketData(market)

                if (data.price <= 0) {
                    skippedPrice++
                    ErrorLogger.warn(TAG, "🪙 ${market.symbol}: price=0 — skipped")
                    continue
                }

                // Feed V4 meta layers
                try {
                    CrossAssetLeadLagAI.recordReturn(market.symbol, data.priceChange24hPct)
                    CrossMarketRegimeAI.updateMarketState(market.symbol, data.price, data.priceChange24hPct, data.volume24h)
                } catch (_: Exception) {}

                analyzed++

                val signal = analyzeAlt(market, data)
                if (signal != null) {
                    val scoreThresh = FluidLearningAI.getMarketsSpotScoreThreshold()
                    val confThresh  = FluidLearningAI.getMarketsSpotConfThreshold()

                    if (signal.score >= scoreThresh && signal.confidence >= confThresh) {
                        signals.add(signal)
                        ErrorLogger.info(TAG, "🪙 SIGNAL: ${market.symbol} | score=${signal.score} | conf=${signal.confidence} | dir=${signal.direction.symbol}")
                    } else {
                        ErrorLogger.warn(TAG, "🪙 ${market.symbol}: below threshold (${signal.score}<$scoreThresh or ${signal.confidence}<$confThresh)")
                    }
                } else {
                    ErrorLogger.warn(TAG, "🪙 ${market.symbol}: analyzeAlt returned null")
                }

            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { ErrorLogger.error(TAG, "🪙 ${market.symbol} exception: ${e.message}", e) }
        }

        ErrorLogger.info(TAG, "🪙 Scan stats: analyzed=$analyzed | hasPos=$skippedPos | badPrice=$skippedPrice | signals=${signals.size}")

        // V4 meta scan
        try {
            CrossAssetLeadLagAI.scan()
            CrossMarketRegimeAI.assessRegime()
            LeverageSurvivalAI.assess(
                currentVolatility = signals.map { abs(it.priceChange24h) }.average().takeIf { !it.isNaN() } ?: 0.0,
                fragilityScore    = LiquidityFragilityAI.getFragilityScore("ALT_MARKET")
            )
            CrossTalkFusionEngine.fuse()
        } catch (_: Exception) {}

        val topSignals = signals.sortedByDescending { it.score }.take(5)

        if (topSignals.isEmpty()) {
            ErrorLogger.warn(TAG, "🪙 ⚠️ NO SIGNALS — skipping execution")
            return
        }

        ErrorLogger.info(TAG, "🪙 TOP ${topSignals.size} signals: ${topSignals.map { "${it.market.symbol}(${it.score}/${it.confidence})" }}")

        for (signal in topSignals) {
            if (positions.size >= MAX_POSITIONS) {
                ErrorLogger.debug(TAG, "Max positions reached")
                break
            }

            val useSpotDefault = (positions.size % 2 == 0)
            var useSpot  = useSpotDefault
            var leverage = if (useSpot) 1.0 else DEFAULT_LEVERAGE

            try {
                CrossMarketRegimeAI.updateMarketState(signal.market.symbol, signal.price, signal.price * 0.01)
                val gated = CrossTalkFusionEngine.computeGatedScore(
                    baseScore          = signal.score.toDouble(),
                    strategy           = "CryptoAltAI",
                    market             = "CRYPTO",
                    symbol             = signal.market.symbol,
                    leverageRequested  = leverage
                )
                if (gated.vetoes.any { it.startsWith("LEVERAGE") } && !useSpotDefault) {
                    useSpot  = true
                    leverage = 1.0
                    ErrorLogger.info(TAG, "🪙 V4 META: leverage suppressed for ${signal.market.symbol}")
                }
                PortfolioHeatAI.addPosition(
                    id       = "ALT_${signal.market.symbol}",
                    symbol   = signal.market.symbol,
                    market   = "CRYPTO",
                    sector   = signal.market.emoji,
                    direction= signal.direction.name,
                    sizeSol  = getEffectiveBalance() * (DEFAULT_SIZE_PCT / 100),
                    leverage = leverage
                )
            } catch (_: Exception) {}

            ErrorLogger.info(TAG, "🪙 EXECUTING: ${signal.market.symbol} ${signal.direction.symbol} @ ${"%.4f".format(signal.price)} | ${if (useSpot) "SPOT" else "${leverage.toInt()}x"}")
            executeSignal(signal.copy(leverage = leverage), isSpot = useSpot)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ANALYSIS — CryptoAltScannerAI embedded
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun analyzeAlt(market: PerpsMarket, data: PerpsMarketData): AltSignal? {
        val reasons    = mutableListOf<String>()
        val layerVotes = mutableMapOf<String, PerpsDirection>()
        var score      = 50
        var confidence = 50

        val change    = data.priceChange24hPct
        val direction = if (change >= 0) PerpsDirection.LONG else PerpsDirection.SHORT

        // ── Layer 1: Price Momentum ───────────────────────────────────────────
        when {
            abs(change) > 8.0 -> { score += 22; confidence += 18; reasons.add("🚀 Strong surge: ${if (change>0)"+" else ""}${"%.1f".format(change)}%") }
            abs(change) > 5.0 -> { score += 16; confidence += 12; reasons.add("📈 Good move: ${if (change>0)"+" else ""}${"%.1f".format(change)}%") }
            abs(change) > 2.5 -> { score += 10; confidence +=  8; reasons.add("📊 Mild move: ${if (change>0)"+" else ""}${"%.1f".format(change)}%") }
            abs(change) > 1.0 -> { score +=  5;                   reasons.add("📉 Small move: ${if (change>0)"+" else ""}${"%.1f".format(change)}%") }
        }
        layerVotes["Momentum"] = direction

        // ── Layer 2: Alt Category / Sector Bonus ─────────────────────────────
        when {
            // Layer 1 DeFi blue-chips
            market.symbol in listOf("AAVE", "MKR", "CRV", "SNX", "LDO", "RPL") -> {
                score += 8; confidence += 10; reasons.add("🏛️ DeFi blue-chip")
            }
            // Meme / narrative
            market.symbol in listOf("SHIB", "FLOKI", "TRUMP", "POPCAT", "NOT") -> {
                score += 12; confidence += 5; reasons.add("🐸 Meme narrative play")
            }
            // Gaming / metaverse
            market.symbol in listOf("AXS", "SAND", "MANA", "IMX") -> {
                score += 6; reasons.add("🎮 Gaming sector")
            }
            // Solana ecosystem (not covered by main engine)
            market.symbol in listOf("MNGO", "PYTH", "RAY", "ORCA") -> {
                score += 10; confidence += 8; reasons.add("◎ Solana ecosystem")
            }
            // L1 alts
            market.symbol in listOf("FTM", "ALGO", "HBAR", "ICP", "VET", "NEAR", "EOS") -> {
                score += 7; confidence += 6; reasons.add("⛓️ L1 alt")
            }
            // Storage / compute
            market.symbol in listOf("FIL", "RENDER", "GRT") -> {
                score += 8; confidence += 6; reasons.add("💾 Storage/compute narrative")
            }
            // Layer-2 & cross-chain
            market.symbol in listOf("STX", "ENS", "RUNE") -> {
                score += 7; confidence += 5; reasons.add("🔗 L2 / cross-chain")
            }
            // Exchange tokens
            market.symbol in listOf("BNB", "CRO") -> {
                score += 9; confidence += 10; reasons.add("🏦 Exchange token")
            }
            // OG alts
            market.symbol in listOf("BTC", "ETH", "LTC", "XMR", "ZEC", "ETC") -> {
                score += 8; confidence += 12; reasons.add("🪙 OG alt — deep liquidity")
            }
        }

        // ── Layer 3: BTC Correlation Divergence ───────────────────────────────
        // If BTC is falling but this alt is rising — strength signal
        try {
            val btcData = PerpsMarketDataFetcher.getMarketData(PerpsMarket.BTC)
            val btcChange = btcData.priceChange24hPct
            val divergence = change - btcChange
            when {
                divergence > 5.0 && direction == PerpsDirection.LONG -> {
                    score += 15; confidence += 10
                    reasons.add("⚡ Alt leading BTC by +${"%.1f".format(divergence)}%")
                    layerVotes["BtcDivergence"] = PerpsDirection.LONG
                }
                divergence < -5.0 && direction == PerpsDirection.SHORT -> {
                    score += 12; confidence += 8
                    reasons.add("📉 Alt lagging BTC by ${"%.1f".format(divergence)}%")
                    layerVotes["BtcDivergence"] = PerpsDirection.SHORT
                }
                abs(btcChange) < 1.0 && abs(change) > 3.0 -> {
                    // Alt moving on its own — narrative momentum
                    score += 8; confidence += 6
                    reasons.add("🎯 Narrative move (BTC flat)")
                    layerVotes["NarrativeMomentum"] = direction
                }
            }
        } catch (_: Exception) {}

        // ── Layer 4: Technical Analysis (PerpsAdvancedAI) ────────────────────
        try {
            PerpsAdvancedAI.recordPrice(market, data.price, data.volume24h)
            val technicals = PerpsAdvancedAI.analyzeTechnicals(market)

            if (technicals.recommendation == direction) {
                score += 10; confidence += 10
                reasons.add("📊 RSI confirms: ${"%.0f".format(technicals.rsi)}")
                layerVotes["Technical"] = direction
            }
            if (technicals.isOversold  && direction == PerpsDirection.LONG)  { score += 15; reasons.add("📉 Oversold bounce") }
            if (technicals.isOverbought && direction == PerpsDirection.SHORT) { score += 15; reasons.add("📈 Overbought short") }
        } catch (_: Exception) {}

        // ── Layer 5: Volume Spike ─────────────────────────────────────────────
        try {
            val volume = PerpsAdvancedAI.analyzeVolume(market)
            if (volume.isSpike) {
                score += when (volume.spikeStrength) {
                    "EXTREME" -> 20; "STRONG" -> 15; "MILD" -> 8; else -> 0
                }
                reasons.add("📊 Volume ${volume.spikeStrength}")
                layerVotes["Volume"] = direction
            }
        } catch (_: Exception) {}

        // ── Layer 6: Support / Resistance ────────────────────────────────────
        try {
            val sr = PerpsAdvancedAI.analyzeSupportResistance(market, data.price)
            if (sr.nearSupport    && direction == PerpsDirection.LONG)  { score += 10; reasons.add("📍 Near support") }
            if (sr.nearResistance && direction == PerpsDirection.SHORT) { score += 10; reasons.add("📍 Near resistance") }
        } catch (_: Exception) {}

        // ── Layer 7: Fluid Learning ───────────────────────────────────────────
        try {
            val progress = FluidLearningAI.getLearningProgress()
            if (progress > 50) { confidence += 5; reasons.add("📚 Learning: ${progress.toInt()}%") }
            val crossBoost = FluidLearningAI.getCrossLearnedConfidence(confidence.toDouble()) - confidence
            if (crossBoost > 0) { confidence += crossBoost.toInt(); reasons.add("🔗 Cross-boost: +${crossBoost.toInt()}") }
        } catch (_: Exception) {}

        // ── Layer 8: Pattern Memory ───────────────────────────────────────────
        try {
            val technicals   = PerpsAdvancedAI.analyzeTechnicals(market)
            val patternConf  = PerpsAdvancedAI.getPatternConfidence(market, direction, technicals)
            if (patternConf > 60) {
                score += 10; confidence += 10
                reasons.add("🧠 Pattern WR: ${"%.0f".format(patternConf)}%")
            }
        } catch (_: Exception) {}

        // ── Layer 9: CryptoAltScannerAI — Sector Heat ────────────────────────
        try {
            val sectorHeat = CryptoAltScannerAI.getSectorHeat(market.symbol)
            if (sectorHeat > 0.6) {
                score += 12; confidence += 8
                reasons.add("🔥 Sector heat: ${"%.0f".format(sectorHeat * 100)}%")
                layerVotes["SectorHeat"] = direction
            } else if (sectorHeat < 0.3 && direction == PerpsDirection.SHORT) {
                score += 8; reasons.add("❄️ Sector cooling — short bias")
            }
        } catch (_: Exception) {}

        // ── Layer 10: CryptoAltScannerAI — Dominance Cycle ───────────────────
        try {
            val dominanceSignal = CryptoAltScannerAI.getDominanceCycleSignal()
            if (dominanceSignal == "ALT_SEASON" && direction == PerpsDirection.LONG) {
                score += 15; confidence += 10
                reasons.add("🌊 Alt season signal")
                layerVotes["DominanceCycle"] = PerpsDirection.LONG
            } else if (dominanceSignal == "BTC_DOMINANCE" && direction == PerpsDirection.SHORT) {
                score += 10; reasons.add("🏦 BTC dominance rising — alt risk-off")
                layerVotes["DominanceCycle"] = PerpsDirection.SHORT
            }
        } catch (_: Exception) {}

        // ── Always-Trade floor (paper learning mode) ─────────────────────────
        if (score < 35)      score      = 35
        if (confidence < 30) confidence = 30
        reasons.add("📚 CryptoAlt ALWAYS_TRADE learning mode")

        return AltSignal(
            market         = market,
            direction      = direction,
            score          = score.coerceIn(0, 100),
            confidence     = confidence.coerceIn(0, 100),
            price          = data.price,
            priceChange24h = change,
            reasons        = reasons,
            layerVotes     = layerVotes
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EXECUTION
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun executeSignal(signal: AltSignal, isSpot: Boolean) {
        // LIVE mode — try on-chain execution
        if (!isPaperMode.get()) {
            val success = executeLiveTrade(signal, isSpot)
            if (!success) {
                ErrorLogger.warn(TAG, "🔴 LIVE alt trade not executed — falling back to paper tracking")
                // Fall through to paper tracking so position is still recorded/monitored
            }
        }

        // Paper execution (or paper tracking alongside live)
        val balance = getEffectiveBalance()
        val sizeSol = balance * (DEFAULT_SIZE_PCT / 100)

        if (sizeSol < 0.01) {
            ErrorLogger.warn(TAG, "Insufficient balance for ${signal.market.symbol} (${sizeSol} SOL)")
            return
        }

        val tpPct  = if (isSpot) DEFAULT_TP_SPOT else DEFAULT_TP_LEV
        val slPct  = if (isSpot) DEFAULT_SL_SPOT else DEFAULT_SL_LEV
        val lev    = if (isSpot) 1.0 else signal.leverage

        // Hivemind size / TP modifier
        val (_, hiveSizeMult, hiveTpAdj) = hiveEntryModifier(signal.market.symbol)
        val finalSize = (sizeSol * hiveSizeMult).coerceIn(0.01, balance * 0.25)
        val finalTp   = (tpPct + hiveTpAdj).coerceAtLeast(1.5)

        val (tp, sl) = when (signal.direction) {
            PerpsDirection.LONG  -> signal.price * (1 + finalTp / 100) to signal.price * (1 - slPct / 100)
            PerpsDirection.SHORT -> signal.price * (1 - finalTp / 100) to signal.price * (1 + slPct / 100)
        }

        val position = AltPosition(
            id             = "ALT_${positionCounter.incrementAndGet()}",
            market         = signal.market,
            direction      = signal.direction,
            isSpot         = isSpot,
            entryPrice     = signal.price,
            currentPrice   = signal.price,
            sizeSol        = finalSize,
            leverage       = lev,
            takeProfitPrice= tp,
            stopLossPrice  = sl,
            aiScore        = signal.score,
            aiConfidence   = signal.confidence,
            reasons        = signal.reasons
        )

        positions[position.id]         = position
        if (isSpot) spotPositions[position.id]     = position
        else        leveragePositions[position.id]  = position

        totalTrades.incrementAndGet()

        if (isPaperMode.get()) paperBalance -= finalSize

        try { FluidLearningAI.recordMarketsTradeStart() } catch (_: Exception) {}

        ErrorLogger.info(TAG, "🪙 OPENED: ${signal.direction.emoji} ${signal.market.symbol} @ ${"%.4f".format(signal.price)} | " +
            "${position.leverageLabel} | size=${finalSize.fmt(3)}◎ | score=${signal.score} | TP=${"%.4f".format(tp)} SL=${"%.4f".format(sl)}")

        signal.reasons.take(3).forEach { ErrorLogger.debug(TAG, "   → $it") }

        persistPositionToTurso(position)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LIVE EXECUTION — Jupiter DEX Swap
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun executeLiveTrade(signal: AltSignal, isSpot: Boolean): Boolean {
        return try {
            val wallet = WalletManager.getWallet()
                ?: run { ErrorLogger.warn(TAG, "No wallet — cannot execute LIVE alt trade"); return false }

            val balance = liveWalletBalance
            val sizeSol = balance * (DEFAULT_SIZE_PCT / 100)
            if (sizeSol < 0.01) {
                ErrorLogger.warn(TAG, "Live balance too low: ${sizeSol} SOL")
                return false
            }

            // Collect 0.1% trading fee first
            try {
                MarketsLiveExecutor.collectTradingFeePublic(wallet, wallet.getPublicKeyOnly(), sizeSol)
            } catch (_: Exception) {}

            // Execute via MarketsLiveExecutor (handles Jupiter swap + signing)
            val (success, txSig) = MarketsLiveExecutor.executeLiveTrade(
                market      = signal.market,
                direction   = signal.direction,
                sizeSol     = sizeSol,
                leverage    = if (isSpot) 1.0 else signal.leverage,
                priceUsd    = signal.price,
                traderType  = "CryptoAlt"
            )

            if (success) {
                ErrorLogger.info(TAG, "🪙 LIVE TRADE EXECUTED: ${signal.market.symbol} tx=${txSig ?: "ok"}")
                updateLiveBalance(balance - sizeSol)
                true
            } else {
                ErrorLogger.warn(TAG, "🪙 Live execution returned failure for ${signal.market.symbol}")
                false
            }
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "🪙 Live trade exception: ${e.message}", e)
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION MONITORING
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun monitorPositions() {
        for ((id, position) in positions.toMap()) {
            try {
                val data = PerpsMarketDataFetcher.getMarketData(position.market)
                if (data.price <= 0) continue

                val updated = position.copy(currentPrice = data.price)
                positions[id]         = updated
                spotPositions[id]     = updated.takeIf { it.isSpot }     ?: run { spotPositions.remove(id); continue }
                leveragePositions[id] = updated.takeIf { !it.isSpot }    ?: run { leveragePositions.remove(id); continue }

                val tpPct = if (updated.isSpot) DEFAULT_TP_SPOT else DEFAULT_TP_LEV
                val slPct = if (updated.isSpot) DEFAULT_SL_SPOT else DEFAULT_SL_LEV

                when {
                    updated.shouldTakeProfit(tpPct) -> closePosition(id, "TP hit +${"%.2f".format(updated.getPnlPct())}%")
                    updated.shouldStopLoss(slPct)   -> closePosition(id, "SL hit  ${"%.2f".format(updated.getPnlPct())}%")
                }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) {}
        }
    }

    private fun closePosition(positionId: String, reason: String) {
        val pos = positions.remove(positionId) ?: return
        spotPositions.remove(positionId)
        leveragePositions.remove(positionId)

        val pnlSol = pos.getPnlSol()
        totalPnlSol += pnlSol

        if (pnlSol >= 0) {
            winningTrades.incrementAndGet()
            try { if (isPaperMode.get()) FluidLearningAI.recordMarketsPaperTrade(true) else FluidLearningAI.recordMarketsLiveTrade(true) } catch (_: Exception) {}
        } else {
            losingTrades.incrementAndGet()
            try { if (isPaperMode.get()) FluidLearningAI.recordMarketsPaperTrade(false) else FluidLearningAI.recordMarketsLiveTrade(false) } catch (_: Exception) {}
        }

        if (isPaperMode.get()) paperBalance += (pos.sizeSol + pnlSol).coerceAtLeast(0.0)

        ErrorLogger.info(TAG, "🪙 CLOSED: ${pos.market.symbol} | $reason | pnl=${pnlSol.fmt(3)}◎ | total_pnl=${totalPnlSol.fmt(3)}◎")

        try {
            PortfolioHeatAI.removePosition("ALT_${pos.market.symbol}")
        } catch (_: Exception) {}

        savePersistedState()
        persistTradeToTurso(pos, pnlSol, reason)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HIVEMIND ENTRY MODIFIER
    // ═══════════════════════════════════════════════════════════════════════════

    private fun hiveEntryModifier(symbol: String): Triple<Boolean, Double, Double> {
        // Simplified Hivemind stub — mirrors the TokenizedStockTrader approach
        // Returns (shouldEnter, sizeMult, tpAdj)
        return Triple(true, 1.0, 0.0)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun loadPersistedState() {
        try {
            val tursoClient = com.lifecyclebot.collective.CollectiveLearning.getClient()
            if (tursoClient != null) {
                val instanceId = com.lifecyclebot.collective.CollectiveLearning.getInstanceId() ?: ""
                // Reuse MarketsState schema — prefix instanceId so it's separate from stocks
                val state = tursoClient.loadMarketsState("ALT_$instanceId")
                if (state != null) {
                    paperBalance = if (state.paperBalanceSol > 1.0) state.paperBalanceSol else 100.0
                    totalTrades.set(state.totalTrades)
                    winningTrades.set(state.totalWins)
                    losingTrades.set(state.totalLosses)
                    totalPnlSol  = state.totalPnlSol
                    isPaperMode.set(!state.isLiveMode)
                    ErrorLogger.info(TAG, "🪙 Loaded state: balance=${paperBalance.fmt(2)} SOL | trades=${state.totalTrades} | WR=${state.winRate.toInt()}%")
                } else {
                    ErrorLogger.info(TAG, "🪙 No persisted state — using defaults")
                }
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Load state error: ${e.message}")
        }
    }

    private fun savePersistedState() {
        scope.launch {
            try {
                val tursoClient = com.lifecyclebot.collective.CollectiveLearning.getClient()
                if (tursoClient != null) {
                    val instanceId = com.lifecyclebot.collective.CollectiveLearning.getInstanceId() ?: ""
                    val state = com.lifecyclebot.collective.MarketsState(
                        instanceId     = "ALT_$instanceId",
                        paperBalanceSol= paperBalance,
                        totalTrades    = totalTrades.get(),
                        totalWins      = winningTrades.get(),
                        totalLosses    = losingTrades.get(),
                        totalPnlSol    = totalPnlSol,
                        learningPhase  = getPhaseLabel(),
                        isLiveMode     = !isPaperMode.get(),
                        lastUpdated    = System.currentTimeMillis()
                    )
                    tursoClient.saveMarketsState(state)
                }
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "Save state error: ${e.message}")
            }
        }
    }

    private fun persistPositionToTurso(pos: AltPosition) {
        scope.launch {
            try {
                val client = com.lifecyclebot.collective.CollectiveLearning.getClient() ?: return@launch
                val instanceId = com.lifecyclebot.collective.CollectiveLearning.getInstanceId() ?: ""
                // Reuse the Markets position schema
                client.saveMarketsPosition(com.lifecyclebot.collective.MarketsPosition(
                    positionId  = pos.id,
                    instanceId  = instanceId,
                    symbol      = pos.market.symbol,
                    direction   = pos.direction.name,
                    entryPrice  = pos.entryPrice,
                    currentPrice= pos.currentPrice,
                    sizeSol     = pos.sizeSol,
                    leverage    = pos.leverage,
                    isSpot      = pos.isSpot,
                    isPaper     = isPaperMode.get(),
                    openTime    = pos.openTime
                ))
            } catch (_: Exception) {}
        }
    }

    private fun persistTradeToTurso(pos: AltPosition, pnlSol: Double, reason: String) {
        // Trade history logged — full persistence uses MarketsState (balance + counters) saved on each close
        ErrorLogger.debug(TAG, "🪙 Trade closed: ${pos.market.symbol} | pnl=${pnlSol.fmt(3)}◎ | reason=$reason")
        savePersistedState()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    fun isRunning()  : Boolean = isRunning.get()
    fun isEnabled()  : Boolean = isEnabled.get()
    fun isPaperMode(): Boolean = isPaperMode.get()
    fun isLiveMode() : Boolean = !isPaperMode.get()

    fun getBalance()          : Double = getEffectiveBalance()
    fun getEffectiveBalance() : Double = if (isPaperMode.get()) paperBalance else liveWalletBalance

    fun setBalance(bal: Double)        { paperBalance = bal }
    fun setEnabled(enabled: Boolean)   { isEnabled.set(enabled); ErrorLogger.info(TAG, "🪙 Enabled: $enabled") }
    fun setLiveMode(live: Boolean)     {
        isPaperMode.set(!live)
        ErrorLogger.info(TAG, "🪙 Mode switched to ${if (live) "LIVE" else "PAPER"}")
    }
    fun updateLiveBalance(sol: Double) { liveWalletBalance = sol }

    fun getAllPositions()      : List<AltPosition> = positions.values.toList()
    fun getSpotPositions()    : List<AltPosition> = spotPositions.values.toList()
    fun getLeveragePositions(): List<AltPosition> = leveragePositions.values.toList()
    fun hasPosition(market: PerpsMarket): Boolean = positions.values.any { it.market == market }

    fun getTotalTrades(): Int    = totalTrades.get()
    fun getWinRate(): Double {
        val total = totalTrades.get()
        return if (total > 0) winningTrades.get().toDouble() / total * 100 else 0.0
    }
    fun getTotalPnlSol(): Double = totalPnlSol

    fun getUnrealizedPnlSol(): Double = positions.values.sumOf { it.getPnlSol() }
    fun getUnrealizedPnlPct(): Double {
        val bal = getEffectiveBalance()
        return if (bal > 0) getUnrealizedPnlSol() / bal * 100 else 0.0
    }

    fun getStats(): Map<String, Any> = mapOf(
        "totalTrades"    to totalTrades.get(),
        "winningTrades"  to winningTrades.get(),
        "losingTrades"   to losingTrades.get(),
        "winRate"        to getWinRate(),
        "totalPnlSol"    to totalPnlSol,
        "openPositions"  to positions.size,
        "paperBalance"   to paperBalance,
        "isLiveMode"     to !isPaperMode.get(),
        "learningPhase"  to getPhaseLabel()
    )

    private fun getPhaseLabel(): String {
        val trades = FluidLearningAI.getMarketsTradeCount()
        return when {
            trades < 500   -> "BOOTSTRAP"
            trades < 1500  -> "LEARNING"
            trades < 3000  -> "VALIDATING"
            trades < 5000  -> "MATURING"
            getWinRate() >= 55.0 -> "READY"
            else           -> "MATURING"
        }
    }

    private fun Double.fmt(d: Int): String = "%.${d}f".format(this)
}

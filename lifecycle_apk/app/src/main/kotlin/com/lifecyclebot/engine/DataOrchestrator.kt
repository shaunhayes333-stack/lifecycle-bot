package com.lifecyclebot.engine

import com.lifecyclebot.data.*
import com.lifecyclebot.engine.WhaleDetector
import com.lifecyclebot.network.*
import kotlinx.coroutines.*

/**
 * DataOrchestrator — event-driven data layer.
 *
 * Replaces the simple poll-everything loop with a multi-source
 * real-time architecture:
 *
 *   Pump.fun WebSocket  → new token events → auto-add to watchlist + safety check
 *   Helius WebSocket    → real-time swap stream → live candle updates
 *   Birdeye OHLCV       → seed candle history on token add (100 real candles instantly)
 *   Helius Creator DAS  → dev rug history check on every new token
 *   Solscan             → dev wallet sell monitoring while in position
 *   LLM (Groq)          → deep sentiment scoring on batched social text
 *   Dexscreener         → fallback polling + pair metadata
 *
 * The poll loop still runs as a fallback but only updates tokens that
 * haven't had a WebSocket event in the last 15 seconds.
 */
class DataOrchestrator(
    private val cfg: () -> BotConfig,
    private val status: BotStatus,
    private val onLog: (String, String) -> Unit,
    private val onNotify: (String, String, com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType) -> Unit,
    private val onNewTokenDetected: (mint: String, symbol: String, name: String) -> Unit,
    private val onDevSell: (mint: String, pct: Double) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── data sources ──────────────────────────────────────────────────
    private val dex         = DexscreenerApi()
    // BirdeyeApi is lazy so it always uses the latest configured key
    private val birdeye get() = BirdeyeApi(cfg().birdeyeApiKey)
    private val devTracker  = SolscanDevTracker()
    private val llmEngine get() = LlmSentimentEngine(cfg().groqApiKey)

    private var pumpWs: PumpFunWebSocket? = null
    private var heliusWs: HeliusWebSocket? = null
    private var creatorChecker: HeliusCreatorHistory? = null

    // Last WS event per mint — used to decide if polling is needed
    private val lastWsEventMs = mutableMapOf<String, Long>()

    // Dev wallet for each token — set on token add
    private val tokenDevWallets = mutableMapOf<String, String>()

    // ── startup ───────────────────────────────────────────────────────

    fun start() {
        val c = cfg()
        startPumpFunWebSocket()
        startHeliusWebSocket(c.heliusApiKey)
        if (c.heliusApiKey.isNotBlank()) {
            creatorChecker = HeliusCreatorHistory(c.heliusApiKey)
        }
        startDevWalletMonitor()
        onLog("DataOrchestrator started", "")
    }

    fun stop() {
        pumpWs?.disconnect()
        heliusWs?.disconnect()
        scope.cancel()
        onLog("DataOrchestrator stopped", "")
    }

    suspend fun reconnectStreams() {
        try {
            heliusWs?.disconnect(); delay(1_000); heliusWs?.connect()
            pumpWs?.disconnect();  delay(500);   pumpWs?.connect()
        } catch (e: Exception) { onLog("Stream reconnect: ${e.message?.take(40)}") }
    }

    // Refresh 5m/15m candles for all active tokens every 5 minutes
    // These don't need to be real-time — they're for trend confirmation
    fun startMtfRefresh() {
        scope.launch {
            while (isActive) {
                delay(5 * 60_000L)
                status.tokens.values.forEach { ts ->
                    try {
                        val c5m = birdeye.getCandles(ts.mint, "5m", 30)
                        if (c5m.isNotEmpty()) {
                            synchronized(ts.history5m) {
                                c5m.forEach { ts.history5m.addLast(it) }
                                while (ts.history5m.size > 100) ts.history5m.removeFirst()
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    // ── token management ──────────────────────────────────────────────

    /**
     * Called when a token is added to the watchlist.
     * Seeds its candle history from Birdeye immediately so the strategy
     * has real data to work with from the first poll.
     */
    fun onTokenAdded(mint: String, symbol: String) {
        scope.launch {
            // 1. Seed candle history from Birdeye
            seedCandleHistory(mint, symbol)

            // 2. Subscribe to real-time trades
            pumpWs?.subscribeToken(mint)
            heliusWs?.subscribeToken(mint)

            onLog("$symbol: data sources connected", mint)
        }
    }

    fun onTokenRemoved(mint: String) {
        pumpWs?.unsubscribeToken(mint)
        heliusWs?.unsubscribeToken(mint)
        tokenDevWallets.remove(mint)
        lastWsEventMs.remove(mint)
    }

    /**
     * Set the dev wallet for a token (from Pump.fun new token event).
     * Starts monitoring it for sells.
     */
    fun setDevWallet(mint: String, devWallet: String) {
        if (devWallet.isBlank()) return
        tokenDevWallets[mint] = devWallet
        heliusWs?.watchWallet(devWallet)
    }

    // ── candle seeding ────────────────────────────────────────────────

    private suspend fun seedCandleHistory(mint: String, symbol: String) {
        val ts = status.tokens[mint] ?: return
        var seeded = 0

        // Seed 1m candles (primary strategy timeframe)
        // For very new tokens (no 4H history yet) 1m is always correct.
        // For established tokens DataOrchestrator checks if 4H data exists
        // and uses that as the primary feed, setting candleTimeframeMinutes accordingly.
        try {
            val candles1m = birdeye.getCandles(mint, "1m", 120)
            if (candles1m.isNotEmpty()) {
                synchronized(ts.history) {
                    if (ts.history.size < 10) {
                        candles1m.forEach { ts.history.addLast(it) }
                        while (ts.history.size > 300) ts.history.removeFirst()
                        ts.candleTimeframeMinutes = 1   // explicitly mark as 1M
                        seeded += candles1m.size
                    }
                }
            }
        } catch (_: Exception) {}

        // Seed 5m candles (trend direction confirmation)
        try {
            val candles5m = birdeye.getCandles(mint, "5m", 60)
            if (candles5m.isNotEmpty()) {
                synchronized(ts.history5m) {
                    ts.history5m.clear()
                    candles5m.forEach { ts.history5m.addLast(it) }
                    while (ts.history5m.size > 100) ts.history5m.removeFirst()
                    seeded += candles5m.size
                }
            }
        } catch (_: Exception) {}

        // Seed 15m candles (macro trend)
        try {
            val candles15m = birdeye.getCandles(mint, "15m", 48)
            if (candles15m.isNotEmpty()) {
                synchronized(ts.history15m) {
                    ts.history15m.clear()
                    candles15m.forEach { ts.history15m.addLast(it) }
                    while (ts.history15m.size > 60) ts.history15m.removeFirst()
                    seeded += candles15m.size
                }
            }
        } catch (_: Exception) {}

        // ── Timeframe selection ───────────────────────────────────────
        // If we got very few 1M candles the token is old — the 1M history
        // only covers the last 2h. Seed 4H candles as primary feed instead
        // so the strategy has meaningful history to work with.
        val primary1mCandles = ts.history.size
        if (primary1mCandles < 30 && seeded > 0) {
            try {
                val candles4h = birdeye.getCandles(mint, "4H", 120)
                if (candles4h.size >= 20) {
                    synchronized(ts.history) {
                        ts.history.clear()
                        candles4h.forEach { ts.history.addLast(it) }
                        while (ts.history.size > 300) ts.history.removeFirst()
                        ts.candleTimeframeMinutes = 240   // 4H
                        seeded = candles4h.size
                        onLog("$symbol: using 4H candles (token age > 2h, ${candles4h.size} candles)", mint)
                    }
                }
            } catch (_: Exception) {}
        }

        if (seeded > 0)
            onLog("$symbol: seeded $seeded candles (tf=${ts.candleTimeframeMinutes}m/5m/15m)", mint)
        else
            onLog("$symbol: Birdeye seed failed — will use real-time data", mint)
    }

    // ── Pump.fun WebSocket ────────────────────────────────────────────

    private fun startPumpFunWebSocket() {
        pumpWs = PumpFunWebSocket(
            onNewToken  = { mint, symbol, name, devWallet ->
                handleNewPumpToken(mint, symbol, name, devWallet)
            },
            onTrade     = { mint, isBuy, solAmount, wallet ->
                handlePumpTrade(mint, isBuy, solAmount, wallet)
                // Feed into copy trade engine
                try {
                    com.lifecyclebot.engine.BotService.instance
                        ?.copyTradeEngine?.onSwapDetected(mint, wallet, solAmount, isBuy)
                } catch (_: Exception) {}
            },
            onGraduation = { mint ->
                onLog("Token graduated to Raydium: ${mint.take(12)}…", mint)
                val ts = status.tokens[mint]
                if (ts != null) onNotify("🎓 Graduated", "${ts.symbol} moved to Raydium", com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            },
            onLog = { msg -> onLog("PumpFun: $msg", "") },
        )
        pumpWs?.connect()
    }

    private fun handleNewPumpToken(mint: String, symbol: String, name: String, devWallet: String) {
        scope.launch {
            // Store dev wallet
            tokenDevWallets[mint] = devWallet

            // Check creator history in background
            val creatorReport = try {
                creatorChecker?.getCreatorReport(devWallet)
            } catch (_: Exception) { null }

            val creatorRisk = creatorReport?.let {
                when {
                    it.isKnownRugger -> "⚠️ KNOWN RUGGER (${it.ruggedTokens}/${it.tokensCreated} rugged)"
                    it.rugRate > 0.3 -> "⚠️ HIGH RUG RATE (${(it.rugRate * 100).toInt()}%)"
                    else             -> "Creator: ${it.riskLevel}"
                }
            } ?: ""

            onLog("🆕 NEW: $symbol | dev=${devWallet.take(8)}… | $creatorRisk", mint)

            // Alert if known rugger
            if (creatorReport?.isKnownRugger == true) {
                onNotify("🚨 Known Rugger", "$symbol — dev has rugged ${creatorReport.ruggedTokens}x before", com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            }

            // Notify the bot to consider adding this token
            onNewTokenDetected(mint, symbol, name)
        }
    }

    private fun handlePumpTrade(mint: String, isBuy: Boolean, solAmount: Double, wallet: String) {
        lastWsEventMs[mint] = System.currentTimeMillis()
        val ts = status.tokens[mint] ?: return

        // Feed into whale detector
        WhaleDetector.recordTrade(mint, wallet, solAmount, isBuy)

        // Build a synthetic candle from the trade event for real-time updates
        updateRealtimeCandle(ts, isBuy, solAmount)
    }

    // ── Helius WebSocket ──────────────────────────────────────────────

    private fun startHeliusWebSocket(apiKey: String) {
        if (apiKey.isBlank()) {
            onLog("Helius: no API key — add key in settings for real-time stream", "")
            return
        }
        heliusWs = HeliusWebSocket(
            apiKey            = apiKey,
            onSwap            = { mint, isBuy, solAmt, tokenAmt, wallet, sig ->
                lastWsEventMs[mint] = System.currentTimeMillis()
                WhaleDetector.recordTrade(mint, wallet, solAmt, isBuy)
                val ts = status.tokens.values.find { it.mint == mint } ?: return@HeliusWebSocket
                updateRealtimeCandle(ts, isBuy, solAmt)
            },
            onLargeWalletMove = { wallet, mint, solAmt, isBuy ->
                // Check if this is a dev wallet selling
                val tokenMint = tokenDevWallets.entries.find { it.value == wallet }?.key
                if (tokenMint != null && !isBuy) {
                    onLog("🚨 Dev wallet move: ${wallet.take(8)}… sent ${solAmt.fmt(4)} SOL", tokenMint)
                    onNotify("🚨 Dev Activity", "Dev wallet is moving SOL — possible rug prep", com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                }
            },
            onLog = { msg -> onLog("Helius: $msg", "") },
        )
        heliusWs?.connect()
    }

    // ── Real-time candle builder ──────────────────────────────────────
    // Accumulates trade events and periodically flushes to a candle

    private val pendingTrades = mutableMapOf<String, PendingCandle>()

    private data class PendingCandle(
        val startMs: Long = System.currentTimeMillis(),
        var buyVol: Double = 0.0,
        var sellVol: Double = 0.0,
        var buys: Int = 0,
        var sells: Int = 0,
        var high: Double = 0.0,   // real-time high within this candle window
        var low: Double = Double.MAX_VALUE,  // real-time low
        var open: Double = 0.0,   // price at candle open
    )

    private fun updateRealtimeCandle(ts: TokenState, isBuy: Boolean, solAmount: Double) {
        val price   = ts.lastPrice.coerceAtLeast(0.0)
        val pending = pendingTrades.getOrPut(ts.mint) {
            PendingCandle(open = price, high = price, low = price)
        }
        if (isBuy) { pending.buyVol += solAmount; pending.buys++ }
        else       { pending.sellVol += solAmount; pending.sells++ }

        // Track real-time OHLC within the candle window
        if (price > 0) {
            if (pending.open == 0.0) pending.open = price
            if (price > pending.high) pending.high = price
            if (price < pending.low || pending.low == Double.MAX_VALUE) pending.low = price
        }

        // Flush to a candle every 8 seconds (matches pollSeconds default)
        val age = System.currentTimeMillis() - pending.startMs
        if (age >= 8_000L && ts.lastPrice > 0) {
            val close = ts.lastPrice
            val candle = Candle(
                ts          = System.currentTimeMillis(),
                priceUsd    = close,
                marketCap   = ts.lastMcap,
                volumeH1    = pending.buyVol + pending.sellVol,
                volume24h   = ts.history.lastOrNull()?.volume24h ?: 0.0,
                buysH1      = pending.buys,
                sellsH1     = pending.sells,
                // Real OHLC from live trade stream
                highUsd     = pending.high.takeIf { it > 0 } ?: close,
                lowUsd      = pending.low.takeIf { it < Double.MAX_VALUE } ?: close,
                openUsd     = pending.open.takeIf { it > 0 } ?: close,
            )
            synchronized(ts.history) {
                ts.history.addLast(candle)
                if (ts.history.size > 300) ts.history.removeFirst()
            }
            pendingTrades[ts.mint] = PendingCandle(open = close, high = close, low = close)
        }
    }

    // ── Dev wallet sell monitor ───────────────────────────────────────

    private fun startDevWalletMonitor() {
        scope.launch {
            while (isActive) {
                delay(30_000L)  // check every 30 seconds
                val activeToken = cfg().activeToken
                val devWallet   = tokenDevWallets[activeToken] ?: continue
                val ts          = status.tokens[activeToken] ?: continue

                if (!ts.position.isOpen) continue  // only monitor when in position

                try {
                    val activity = devTracker.checkDevWallet(devWallet, activeToken)
                    if (activity != null && activity.isSelling) {
                        val pct = (activity.sellPct * 100).toInt()
                        onLog("⚠️ Dev selling: $pct% of position sold", activeToken)

                        when (activity.alertLevel) {
                            "warning", "critical" -> {
                                onNotify(
                                    "🚨 Dev Selling",
                                    "${ts.symbol}: dev sold ${pct}% of their tokens — consider exiting"
                                , com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                                onDevSell(activeToken, activity.sellPct)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // ── LLM sentiment scoring ─────────────────────────────────────────

    /**
     * Score a set of mention events using the LLM.
     * Falls back to null if Groq not configured.
     */
    fun scoreSentimentWithLlm(
        symbol: String,
        mint: String,
        events: List<MentionEvent>,
    ): LlmSentimentEngine.LlmSentiment? {
        return try {
            llmEngine.score(symbol, mint, events)
        } catch (_: Exception) { null }
    }

    // ── Polling fallback ──────────────────────────────────────────────

    /**
     * Should we poll this token this tick?
     * If we've had a WebSocket event in the last 15 seconds, skip polling —
     * the WS data is more current anyway.
     */
    fun shouldPoll(mint: String): Boolean {
        val lastEvent = lastWsEventMs[mint] ?: return true
        return System.currentTimeMillis() - lastEvent > 15_000L
    }
}

private fun Double.fmt(d: Int = 4) = "%.${d}f".format(this)

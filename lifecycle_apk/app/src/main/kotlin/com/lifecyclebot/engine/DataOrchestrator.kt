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
    private val copyTradeEngine: com.lifecyclebot.engine.CopyTradeEngine? = null,
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
    private var dexWs: DexScreenerWebSocket? = null  // V5.6: Real-time price feed
    private var creatorChecker: HeliusCreatorHistory? = null

    // Last WS event per mint — used to decide if polling is needed
    // V5.0.7807 — concurrent (written from OkHttp reader threads and IO coroutines;
    // a plain HashMap there can corrupt) and pruned by pruneLocalMintCaches7807.
    private val lastWsEventMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // Dev wallet for each token — set on token add
    private val tokenDevWallets = java.util.concurrent.ConcurrentHashMap<String, String>()

    // ── startup ───────────────────────────────────────────────────────

    fun start() {
        val c = cfg()
        // V5.0.4169 — DUPLICATE PUMP WS REMOVED.
        // BotService.kt:7242 already starts the primary PumpFunWS client.
        // DataOrchestrator was starting a SECOND connection to the same
        // wss://pumpportal.fun/api/data endpoint (PumpFunWebSocket),
        // which doubled WS bandwidth for every pump.fun create/buy/sell
        // event the bot received. Operator confirmed 281 GB of mobile
        // data in 29 days — most of which was redundant WS streaming.
        // The primary PumpFunWS path is sufficient for new-token intake;
        // BuyTradeStreamFollower / dev-wallet monitoring previously
        // bridged here re-bind to the primary WS path via BotService.
        // startPumpFunWebSocket()  // disabled in V5.0.4169
        startHeliusWebSocket(c.heliusApiKey)
        // V5.0.4169 — DexScreener WS now starts in DEMAND-ONLY mode:
        // it opens the wss firehose only when at least one token is
        // actively subscribed. Idle connection (which still received
        // the full Solana pair stream every second) is closed.
        startDexScreenerWebSocket()
        if (c.heliusApiKey.isNotBlank()) {
            creatorChecker = HeliusCreatorHistory(c.heliusApiKey)
        }
        startDevWalletMonitor()
        startSubscriptionHousekeeping7807()
        onLog("DataOrchestrator started (Helius+DexScreener WS — PumpFun handled by BotService primary)", "")
    }

    fun stop() {
        pumpWs?.disconnect()
        heliusWs?.disconnect()
        dexWs?.disconnect()
        scope.cancel()
        onLog("DataOrchestrator stopped", "")
    }

    suspend fun reconnectStreams() {
        try {
            // V5.0.7819 — a healthy Helius socket is kept (no 128-sub re-send); see reconnectIfStale7819.
            heliusWs?.reconnectIfStale7819("EXTERNAL_STREAM_RECONNECT")
            pumpWs?.disconnect();  delay(500);   pumpWs?.connect()
            dexWs?.disconnect();   delay(500);   dexWs?.connect()
        } catch (e: Exception) { onLog("Stream reconnect: ${e.message?.take(40)}", "") }
    }

    // Refresh 5m/15m candles for all active tokens every 5 minutes
    // These don't need to be real-time — they're for trend confirmation
    fun startMtfRefresh() {
        scope.launch {
            while (isActive) {
                delay(5 * 60_000L)
                // V5.0.7809 — Birdeye is a history source only while its credential
                // is healthy. A terminal 401/403 key is not polled for every token
                // every 5 minutes (each call still charged the budget gate before
                // the circuit refused it). Field Manual L404.
                val birdeyeDead7809 = try {
                    com.lifecyclebot.engine.truth.ProviderCircuitBreaker6402
                        .isAuthTerminal(com.lifecyclebot.engine.truth.ProviderCircuitBreaker6402.Provider.BIRDEYE)
                } catch (_: Throwable) { false }
                if (birdeyeDead7809) {
                    try { PipelineHealthCollector.labelInc("BIRDEYE_MTF_REFRESH_SKIPPED_AUTH_DEAD_7809") } catch (_: Throwable) {}
                    continue
                }
                // Copy tokens list to avoid concurrent modification
                val tokensCopy = synchronized(status.tokens) {
                    status.tokens.values.toList()
                }
                tokensCopy.forEach { ts ->
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
            // V5.0.7401 — LIVE FIRST, HISTORY SECOND.
            // The old order awaited candle enrichment before Pump/Helius
            // subscriptions. On a new launch those first seconds contain the
            // dev/bundle/buyer-acceleration signal; missing them turns an early
            // detector into a post-pump chart follower.
            pumpWs?.subscribeToken(mint)
            heliusWs?.subscribeToken(mint)

            val ts = status.tokens[mint]
            if (ts != null && ts.pairAddress.isNotBlank()) {
                dexWs?.subscribeToken(mint, ts.pairAddress)
            }
            try { PipelineHealthCollector.labelInc("LAUNCH_LIVE_STREAMS_ARMED_BEFORE_HISTORY_7401") } catch (_: Throwable) {}
            onLog("$symbol: live streams armed before history seed", mint)

            // Historical enrichment is confirmation. It must never delay the
            // first-minute launch tape.
            seedCandleHistory(mint, symbol)
        }
    }

    // V5.0.7807 §ONE_SUBSCRIPTION_AUTHORITY — onTokenRemoved(mint) is deleted. It had
    // no production caller, while status.tokens is pruned from 8+ independent sites
    // (BotService, Executor, AntiChokeManager, DataPipeline, GlobalTradeRegistry), so
    // explicit eviction would be a second, racing, half-wired model. HeliusWebSocket's
    // bounded LRU (MAX_TOKEN_SUBSCRIPTIONS_7794) is the sole token-subscription
    // authority; held positions are pinned through setPinnedMintsProvider7807, and the
    // housekeeping tick below garbage-collects this class's per-mint caches.
    // Field Manual L153: launch data goes stale fastest, so held mints keep the feed.

    /**
     * V5.0.7807 — mints the risk engine must protect (canonical protective inventory:
     * open/partial with qty + funded LIVE quarantines) ∪ the in-memory book.
     */
    private fun heldMintsForSubscriptions7807(): Set<String> {
        val out = HashSet<String>()
        // V5.0.7819 — Helius is a Solana feed. Only SOLANA_TOKEN rows with a real
        // base58 mint are pinned; 5.0.7813 pinned 100 of 128 slots to stock tickers
        // and the in-memory book's cross-asset rows. Field Manual L186.
        val nonSolana7819 = HashSet<String>()
        try {
            com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.protectiveInventory7807(if (RuntimeModeAuthority.isPaper()) "paper" else "live").forEach { p ->
                if (p.mint.isBlank()) return@forEach
                if (p.assetClass == com.lifecyclebot.engine.truth.AssetClass.SOLANA_TOKEN) out.add(p.mint) else nonSolana7819.add(p.mint)
            }
        } catch (_: Throwable) {}
        try {
            status.tokens.values.forEach { ts -> if (ts.position.isOpen && ts.position.isPaperPosition == RuntimeModeAuthority.isPaper()) out.add(ts.mint) }
        } catch (_: Throwable) {}
        val pinned7819 = out.filterTo(HashSet()) { it !in nonSolana7819 && com.lifecyclebot.network.HeliusSolanaScope7819.isSolanaMint7819(it) }
        com.lifecyclebot.network.HeliusSubscriptionTelemetry7807.pinnedExcludedNonSolana7819 = (out + nonSolana7819).size - pinned7819.size
        return pinned7819
    }

    /**
     * V5.0.7819 — eligibility for any Helius subscription: a base58 Solana mint that
     * no canonical row classifies as STOCK/FOREX/METAL/COMMODITY/PERPS/CRYPTO_ALT.
     * (UNKNOWN is not treated as non-Solana here: a mis-tagged meme bag keeps its tape.)
     */
    private fun heliusMintEligible7819(mint: String): Boolean {
        if (!com.lifecyclebot.network.HeliusSolanaScope7819.isSolanaMint7819(mint)) return false
        return try {
            com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.protectiveInventory7807().none { p ->
                p.mint == mint &&
                    p.assetClass != com.lifecyclebot.engine.truth.AssetClass.SOLANA_TOKEN &&
                    p.assetClass != com.lifecyclebot.engine.truth.AssetClass.UNKNOWN
            }
        } catch (_: Throwable) { true }
    }

    private fun startSubscriptionHousekeeping7807() {
        scope.launch {
            while (isActive) {
                delay(10_000L)
                try { heliusWs?.ensurePinnedSubscriptions7807() } catch (_: Throwable) {}
                try { pruneLocalMintCaches7807(System.currentTimeMillis()) } catch (_: Throwable) {}
            }
        }
    }

    /**
     * V5.0.7807 — these maps were keyed by every mint ever seen and never shrank.
     * Time-keyed entries are dropped only once they are past the window their reader
     * uses (shouldPoll 15 s, PumpPortal priority 20 s), so behaviour is unchanged.
     */
    private fun pruneLocalMintCaches7807(now: Long) {
        lastWsEventMs.entries.removeIf { now - it.value > 60_000L }
        lastPumpPortalTradeMs7773.entries.removeIf { now - it.value > 60_000L }
        val live = HashSet<String>(status.tokens.keys)
        live.addAll(heldMintsForSubscriptions7807())
        // V5.0.7819 — the pinned set is now Solana-only; per-mint caches still cover every held row.
        try { live.addAll(com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.protectiveInventoryMints7807()) } catch (_: Throwable) {}
        tokenDevWallets.keys.removeIf { it !in live }
        synchronized(pendingTrades) { pendingTrades.keys.removeIf { it !in live } }
        dexWs?.let { d -> d.subscribedMints7807().filter { it !in live }.forEach { d.unsubscribeToken(it) } }
    }

    /**
     * Set the dev wallet for a token (from Pump.fun new token event).
     * Starts monitoring it for sells.
     */
    fun setDevWallet(mint: String, devWallet: String) {
        if (devWallet.isBlank()) return
        tokenDevWallets[mint] = devWallet
        heliusWs?.watchWallet(devWallet)
        // V5.9.357 — mirror into OperatorRegistry so OperatorFingerprintAI's
        // trade-close hook can resolve the creator without touching the scanner.
        try { OperatorRegistry.set(mint, devWallet) } catch (_: Exception) {}
    }

    // ── candle seeding ────────────────────────────────────────────────

    private val SEED_RETRY_FLOOR_MS_7809 = 2_600L
    private val SEED_JITTER_MS_7809 = 1_500L

    /**
     * V5.0.7809 — one keyless OHLCV read that waits for the provider's request
     * slot instead of being declined by it, retried at most [attempts] times.
     * Retries are spaced past the feed's 2.5 s same-key coalesce window, and a
     * provider in 429/5xx cooldown ends the attempt rather than queueing behind
     * it. A provider-answered empty is negative-cached by the feed, so a retry
     * after one costs no request. Never fabricates: empty means no bars.
     */
    private suspend fun keylessFetch7809(
        mint: String,
        timeframe: String,
        limit: Int,
        poolHint: String,
        attempts: Int,
    ): List<Candle> {
        var tries = 0
        while (tries < attempts) {
            tries++
            val slot: com.lifecyclebot.network.SolanaOhlcvFeed6916.SlotWait7809 =
                com.lifecyclebot.network.SolanaOhlcvFeed6916.providerSlotWait7809()
            if (slot.cooldown) {
                try { PipelineHealthCollector.labelInc("OHLCV_SEED_SKIPPED_PROVIDER_COOLDOWN_7809") } catch (_: Throwable) {}
                return emptyList()
            }
            if (tries > 1 || slot.waitMs > 0L) {
                val floor = if (tries > 1) SEED_RETRY_FLOOR_MS_7809 else 0L
                delay(maxOf(slot.waitMs, floor) + kotlin.random.Random.nextLong(0L, SEED_JITTER_MS_7809))
            }
            val got = com.lifecyclebot.network.SolanaOhlcvFeed6916.fetchCandles6916(mint, timeframe, limit, poolHint)
            if (got.isNotEmpty()) {
                if (tries > 1) try { PipelineHealthCollector.labelInc("OHLCV_SEED_SERVED_ON_RETRY_7809") } catch (_: Throwable) {}
                return got
            }
        }
        if (attempts > 0) try { PipelineHealthCollector.labelInc("OHLCV_SEED_EMPTY_AFTER_SLOTTED_TRIES_7809_${timeframe.uppercase()}") } catch (_: Throwable) {}
        return emptyList()
    }

    private suspend fun seedCandleHistory(mint: String, symbol: String) {
        val ts = status.tokens[mint] ?: return
        var seeded = 0

        // V5.0.4186 — BIRDEYE = BACKUP, not primary. Operator P0 mandate:
        // "we can get the data birdeye provides free. its meant to be
        // deprioritised to basically just be a back up". This seed function
        // hits Birdeye 3-4 times per new watchlist token (1m + 5m + 15m +
        // optional 4H candles). With 500+ pump.fun firehose tokens per
        // session that's ~2,000 Birdeye calls just for seeding — the dominant
        // CU sink that pushes the daily budget past the lockdown threshold
        // and silences the bot's whole data layer. Solution: gate every
        // Birdeye seed call behind canAffordScannerLane(). When the budget
        // is tight, SKIP seeding entirely — real-time DexScreener/PumpFun WS
        // feeds will populate ts.history naturally on their first tick.
        // V5.0.6916 §KEYLESS_SEED_FIRST — the mandate quoted directly above
        // ("we can get the data birdeye provides free. its meant to be
        // deprioritised to basically just be a back up") was only half
        // implemented: Birdeye was deprioritised by BUDGET, but it was still
        // the ONLY source, so throttling it meant no candles at all. The
        // early `return` below used to end this function, so both the budget
        // throttle (BIRDEYE_SEED_SKIPPED_BUDGET=483) and the 401 key death
        // (birdeye sr=0% 4xx=60) left ts.history/history5m/history15m empty —
        // and every pattern engine needs 3-5 bars minimum
        // (MovementPatternSignal <4, HistoricalChartScanner <5,
        // SmartChartScanner <5). The whole chart-shape layer has therefore
        // been receiving zero bars, which is why it looks "untouched".
        //
        // GeckoTerminal pool OHLCV is keyless and on a host this app already
        // uses (geckoterminal sr=71%). Seed from it FIRST, unconditionally, so
        // the pattern stack is fed whether or not Birdeye is alive. Birdeye
        // then tops up only when affordable — genuinely a backup now.
        val poolHint6916 = try {
            ts.pairAddress.ifBlank { ts.lastPricePoolAddr }
        } catch (_: Throwable) { "" }
        var keylessSeeded6916 = 0
        try {
            // V5.0.7809 §THE_SEED_ASKED_THREE_TIMES_INSIDE_ONE_SLOT. The 1m/5m/15m
            // reads went out back to back against a 2.5 s provider gate, so 5m
            // and 15m were always declined locally, and the 1m read was declined
            // whenever any other token had seeded in the last 2.5 s — one shot
            // per token, never retried: thousands of "fetches", almost no bars.
            // The 1m read (the one TradePlan7739 needs) now waits for the slot
            // and retries a bounded number of times; 5m/15m get one slotted read
            // only when the pool answered 1m. A provider in cooldown is never
            // queued behind (Field Manual L404).
            val k1m = keylessFetch7809(mint, "1m", 120, poolHint6916, attempts = 3)
            val k1mOk7809 = k1m.size >= 2
            if (k1m.size >= 2) {
                synchronized(ts.history) {
                    if (ts.history.size < 10) {
                        ts.history.clear()
                        k1m.forEach { ts.history.addLast(it) }
                        while (ts.history.size > 300) ts.history.removeFirst()
                        ts.candleTimeframeMinutes = 1
                        keylessSeeded6916 += k1m.size
                    }
                }
            }
            val k5m = keylessFetch7809(mint, "5m", 60, poolHint6916, attempts = if (k1mOk7809) 1 else 0)
            if (k5m.size >= 2) {
                synchronized(ts.history5m) {
                    if (ts.history5m.size < 5) {
                        ts.history5m.clear()
                        k5m.forEach { ts.history5m.addLast(it) }
                        while (ts.history5m.size > 100) ts.history5m.removeFirst()
                        keylessSeeded6916 += k5m.size
                    }
                }
            }
            val k15m = keylessFetch7809(mint, "15m", 48, poolHint6916, attempts = if (k1mOk7809) 1 else 0)
            if (k15m.size >= 2) {
                synchronized(ts.history15m) {
                    if (ts.history15m.size < 5) {
                        ts.history15m.clear()
                        k15m.forEach { ts.history15m.addLast(it) }
                        while (ts.history15m.size > 60) ts.history15m.removeFirst()
                        keylessSeeded6916 += k15m.size
                    }
                }
            }
        } catch (_: Throwable) {}
        if (keylessSeeded6916 > 0) {
            seeded += keylessSeeded6916
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CANDLE_SEED_KEYLESS_6916")
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "CANDLE_SEED_KEYLESS_6916",
                    "mint=${mint.take(10)} symbol=$symbol bars=$keylessSeeded6916 " +
                        "h1m=${ts.history.size} h5m=${ts.history5m.size} h15m=${ts.history15m.size} " +
                        "source=geckoterminal_pool_ohlcv keyless=true " +
                        "action=pattern_stack_now_has_bars",
                )
            } catch (_: Throwable) {}
            onLog("$symbol: seeded $keylessSeeded6916 keyless candles (GeckoTerminal)", mint)
        }

        // V5.0.6947 §REPORT_THE_REAL_REASON. This path checked the BUDGET gate
        // and never the circuit, so a permanently dead 401 key surfaced as
        // BIRDEYE_SEED_SKIPPED_BUDGET x662 in the operator snapshot — "budget
        // throttled" when the truth is "the key is dead and no budget will ever
        // fix it". A misleading reason is worse than no reason: it sends the
        // operator to raise a quota instead of rotating a credential.
        val authDead6947 = try {
            com.lifecyclebot.engine.truth.ProviderCircuitBreaker6402
                .isAuthTerminal(com.lifecyclebot.engine.truth.ProviderCircuitBreaker6402.Provider.BIRDEYE)
        } catch (_: Throwable) { false }
        if (authDead6947) {
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("BIRDEYE_SEED_SKIPPED_AUTH_DEAD_6947")
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "BIRDEYE_SEED_SKIPPED_AUTH_DEAD_6947",
                    "mint=${mint.take(10)} symbol=$symbol reason=birdeye_401_key_dead " +
                        "keylessBars=$keylessSeeded6916 recovery=rotate_key_in_settings " +
                        "note=not_a_budget_problem",
                )
            } catch (_: Throwable) {}
            return
        }

        val budgetOk = try {
            com.lifecyclebot.engine.BirdeyeBudgetGate.canAffordScannerLane()
        } catch (_: Throwable) { true }
        if (!budgetOk) {
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("BIRDEYE_SEED_SKIPPED_BUDGET")
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "BIRDEYE_SEED_SKIPPED_BUDGET",
                    "mint=${mint.take(10)} symbol=$symbol reason=birdeye_scanner_throttled " +
                        "keylessBars=$keylessSeeded6916 — keyless seed already ran (V5.0.6916)",
                )
            } catch (_: Throwable) {}
            onLog("$symbol: skipping Birdeye top-up (budget throttled; keyless bars=$keylessSeeded6916)", mint)
            return
        }
        // V5.0.6916 — if the keyless seed already filled every timeframe there
        // is nothing for Birdeye to add, so do not spend the CU. This is what
        // finally makes Birdeye a true backup rather than the primary.
        if (keylessSeeded6916 > 0 && ts.history.size >= 30 &&
            ts.history5m.size >= 5 && ts.history15m.size >= 5) {
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("BIRDEYE_SEED_UNNEEDED_KEYLESS_SUFFICIENT_6916")
            } catch (_: Throwable) {}
            onLog("$symbol: keyless candles sufficient (${ts.history.size}/1m) — Birdeye not called", mint)
            return
        }

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
                val safeSol = normalizeTradeSolAmount(solAmount, "pumpfun_ws", mint)
                if (safeSol != null) {
                    handlePumpTrade(mint, isBuy, safeSol, wallet)
                    // Feed into copy trade engine with canonical UI SOL only.
                    try {
                        com.lifecyclebot.engine.BotService.instance
                            ?.copyTradeEngine?.onSwapDetected(mint, wallet, safeSol, isBuy)
                    } catch (_: Exception) {}
                }
            },
            onGraduation = { mint ->
                onLog("Token graduated to Raydium: ${mint.take(12)}…", mint)
                val ts = status.tokens[mint]
                if (ts != null) {
                    // V5.0.6852 §GRADUATION_FLAG_HAD_ZERO_WRITERS — this callback KNEW the
                    // curve had completed and did nothing but log it. The canonical flag
                    // that Executor:138 uses to pick the sell route stayed false, so we
                    // kept aiming exits at the dead bonding curve. Stamp it the moment
                    // the WS says so; RouteTruthHydrator also derives it from the venue,
                    // this is just the earliest and most authoritative signal.
                    ts.tokenMap.migratedOrGraduated = true
                    ts.tokenMap.pumpFunExecutable = false
                    ts.tokenMap.updatedAtMs = System.currentTimeMillis()
                    try { PipelineHealthCollector.labelInc("GRADUATION_DETECTED_PUMP_WS") } catch (_: Throwable) {}
                    onNotify("🎓 Graduated", "${ts.symbol} moved to Raydium", com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                }
            },
            onLog = { msg -> onLog("PumpFun: $msg", "") },
        )
        pumpWs?.connect()
    }

    private fun handleNewPumpToken(mint: String, symbol: String, name: String, devWallet: String) {
        scope.launch {
            // Store dev wallet
            tokenDevWallets[mint] = devWallet
            // V5.9.357 — mirror into OperatorRegistry for trade-close lookup.
            try { OperatorRegistry.set(mint, devWallet) } catch (_: Exception) {}

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

            // Alert if known rugger — AND hard-block the mint so it can never
            // reach a LIVE buy. V5.9.1502: operator breach — "Known Rugger"
            // toasts were INFO-only and the token still flowed to live
            // execution (LarpTube/TEST live buys despite "dev has rugged Nx").
            // The known-rugger / high-rug-rate status is now a persistent veto
            // via TokenBlacklist, enforced at the final liveBuy gate.
            val isRugger = creatorReport?.isKnownRugger == true
            val highRug  = (creatorReport?.rugRate ?: 0.0) > 0.3
            if (isRugger || highRug) {
                val why = if (isRugger)
                    "KNOWN_RUGGER dev=${devWallet.take(8)} rugged=${creatorReport?.ruggedTokens}/${creatorReport?.tokensCreated}"
                else
                    "HIGH_RUG_RATE dev=${devWallet.take(8)} rate=${((creatorReport?.rugRate ?: 0.0) * 100).toInt()}%"
                try { com.lifecyclebot.engine.TokenBlacklist.block(mint, why) } catch (_: Throwable) {}
                try {
                    com.lifecyclebot.engine.ForensicLogger.lifecycle(
                        "RUGGER_HARD_BLOCKED", "mint=${mint.take(10)} symbol=$symbol $why")
                } catch (_: Throwable) {}
                onNotify("🚨 Known Rugger", "$symbol — dev has rugged ${creatorReport?.ruggedTokens}x before · BLACKLISTED", com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.WARNING)
                // Do NOT feed a known rugger into the candidate pipeline at all.
                return@launch
            }

            // Notify the bot to consider adding this token
            onNewTokenDetected(mint, symbol, name)
        }
    }


    /**
     * V5.9.1113 — canonical trade feed amount guard. All downstream components
     * expect UI SOL. Raw lamports/micro-SOL leaks must not reach whale, copy,
     * or real-time candle volume logic.
     */
    private fun normalizeTradeSolAmount(raw: Double, source: String, mint: String): Double? {
        if (!raw.isFinite() || raw <= 0.0) return null
        val scaled = when {
            raw > 100_000.0 -> raw / 1_000_000_000.0
            raw > 10_000.0  -> raw / 1_000_000.0
            else -> raw
        }
        if (!scaled.isFinite() || scaled <= 0.0 || scaled > 1_000.0) {
            try {
                ForensicLogger.lifecycle(
                    "TRADE_SOL_AMOUNT_REJECTED",
                    "src=$source mint=${mint.take(10)} raw=$raw scaled=$scaled"
                )
            } catch (_: Throwable) {}
            return null
        }
        if (scaled != raw) {
            try {
                ForensicLogger.lifecycle(
                    "TRADE_SOL_AMOUNT_NORMALIZED",
                    "src=$source mint=${mint.take(10)} raw=$raw sol=$scaled"
                )
            } catch (_: Throwable) {}
        }
        return scaled
    }

    /**
     * V5.0.7743 — PumpPortal trade frames, routed into the consumers this class
     * already has for them: WhaleDetector's launch tape and the 8-second
     * real-time candle builder (handlePumpTrade, reachable before only from the
     * disabled startPumpFunWebSocket), and the dev-sell exit when the seller is
     * the mint's creator (OperatorRegistry, written by PumpFunWS on create).
     */
    fun onPumpPortalTrade7743(mint: String, wallet: String, solAmount: Double, isBuy: Boolean, soldFractionOfHolding: Double) {
        if (mint.isBlank()) return
        lastPumpPortalTradeMs7773[mint] = System.currentTimeMillis()
        onTapeTrade7773(mint, wallet, solAmount, isBuy, soldFractionOfHolding)
    }

    /**
     * V5.0.7773 §ONE_TAPE_TWO_FEEDS. PumpPortal's per-token trade stream needs an
     * API key funded with 0.02 SOL; the Helius websocket decodes the same pump.fun
     * TradeEvent (mint, side, SOL, wallet) on the operator's existing key. Both now
     * land here, so the launch tape, whale tracker, dev-sell exit and buy pressure
     * keep working when either feed is down. While PumpPortal delivers a mint, the
     * Helius copy of its trades is skipped so no trade is counted twice.
     */
    fun onHeliusTrade7773(mint: String, wallet: String, solAmount: Double, isBuy: Boolean) {
        if (mint.isBlank()) return
        val pp = lastPumpPortalTradeMs7773[mint] ?: 0L
        if (System.currentTimeMillis() - pp < PUMPPORTAL_PRIORITY_MS_7773) return
        try { PipelineHealthCollector.labelInc("TAPE_TRADE_FROM_HELIUS_7773") } catch (_: Throwable) {}
        // Helius carries no holding size, so a dev sell's fraction is unknown (0).
        onTapeTrade7773(mint, wallet, solAmount, isBuy, 0.0)
    }

    /**
     * V5.0.7819 §EVERY_REAL_PRINT_FEEDS_THE_BARS. A real executed trade price
     * (SOL per token) for a watched mint — the Helius-decoded pump.fun
     * TradeEvent here, the PumpPortal curve trade from BotService — binned at
     * its arrival time into LocalCandleSynthesis7055. Before this the tape fed
     * launch flow, whales and buy pressure but never the one-minute bars
     * TradePlan7739 reads. USD at the cached SOL/USD, the mark the rest of the
     * book uses (Field Manual L186: one basis for every source).
     */
    fun onTradePrint7819(mint: String, priceSolPerToken: Double) {
        if (!priceSolPerToken.isFinite() || priceSolPerToken <= 0.0) return
        // V5.0.7921 — the launch tape keeps each launch's price path since birth.
        try { com.lifecyclebot.engine.market.LaunchTape7921.onPrice(mint, priceSolPerToken) } catch (_: Throwable) {}
        val ts = status.tokens[mint] ?: return
        val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        if (!solUsd.isFinite() || solUsd <= 0.0) return
        try {
            com.lifecyclebot.engine.truth.LocalCandleSynthesis7055.noteTrade7819(
                ts, priceSolPerToken * solUsd, ts.lastMcap, System.currentTimeMillis(),
            )
        } catch (_: Throwable) {}
    }

    /** V5.0.7787 — (mint, SOL per token) for a held position's on-chain trade; wired by BotService. */
    @Volatile private var onHeldTradeMark7787: ((String, Double) -> Unit)? = null

    fun setOnHeldTradeMark7787(cb: (String, Double) -> Unit) { onHeldTradeMark7787 = cb }

    private val lastPumpPortalTradeMs7773 = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val PUMPPORTAL_PRIORITY_MS_7773 = 20_000L

    private fun onTapeTrade7773(mint: String, wallet: String, solAmount: Double, isBuy: Boolean, soldFractionOfHolding: Double) {
        // V5.0.7921 — the launch tape since birth (crowd, flow, heat, promotion).
        try { com.lifecyclebot.engine.market.LaunchTape7921.onTrade(mint, wallet, solAmount, isBuy) } catch (_: Throwable) {}
        try { synchronized(pendingTrades) { handlePumpTrade(mint, isBuy, solAmount, wallet) } } catch (_: Throwable) {}
        try { applyTapeBuyPressure7773(mint) } catch (_: Throwable) {}
        // V5.0.7747 — WhaleTrackerAI's only feeder was BirdeyeWhaleFeeder (Birdeye
        // 401), so every scorer reading it saw stale data. A single trade at or above
        // the feeder's own whale bar (MIN_VOLUME_USD_TO_CLASSIFY, $1,000) is recorded.
        // Field Manual §3.4: one large wallet can distort volume, which is why the
        // tracker counts distinct whale wallets, not raw size.
        try {
            val solUsd7747 = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
            if (wallet.isNotBlank() && solUsd7747.isFinite() && solUsd7747 > 0.0 && solAmount * solUsd7747 >= WHALE_TRADE_MIN_USD_7747) {
                val ts7747 = status.tokens[mint]
                WhaleTrackerAI.recordWhaleActivity(
                    whaleAddress = wallet,
                    mint = mint,
                    symbol = ts7747?.symbol ?: mint.take(6),
                    action = if (isBuy) WhaleTrackerAI.WhaleAction.BUY else WhaleTrackerAI.WhaleAction.SELL,
                    amountSol = solAmount,
                    priceAtAction = ts7747?.lastPrice ?: 0.0,
                )
                PipelineHealthCollector.labelInc(if (isBuy) "WHALE_BUY_FROM_TRADE_STREAM_7747" else "WHALE_SELL_FROM_TRADE_STREAM_7747")
            }
        } catch (_: Throwable) {}
        if (!isBuy && wallet.isNotBlank()) {
            val dev = try { OperatorRegistry.getDevWallet(mint) } catch (_: Throwable) { null }
            if (dev != null && dev == wallet) {
                try {
                    PipelineHealthCollector.labelInc("DEV_SELL_FROM_TRADE_STREAM_7743")
                    ForensicLogger.lifecycle("DEV_SELL_FROM_TRADE_STREAM_7743", "mint=${mint.take(10)} soldPct=${(soldFractionOfHolding * 100).toInt()} sol=$solAmount")
                } catch (_: Throwable) {}
                try { onDevSell(mint, soldFractionOfHolding) } catch (_: Throwable) {}
            }
        }
    }

    /**
     * V5.0.7773 — buy pressure from the trade tape. ts.lastBuyPressurePct was
     * written only by DexScreener (5-minute txns), so a bonding-curve launch with
     * no DexScreener pair sat at the 50.0 default and MoonshotFreshLaunchAdmission
     * declined it as NO_DEMAND_SIGNAL (744 on 5.0.7771) however hard it was being
     * bought. With 3+ trades in the last 60 s the tape's buy share is the reading.
     * Field Manual §3.4: read participation from the tape, not a default.
     */
    private fun applyTapeBuyPressure7773(mint: String) {
        val ts = status.tokens[mint] ?: return
        val flow = WhaleDetector.launchFlow7401(mint)
        val bp = WhaleDetector.tapeBuyPressure7773(flow.buyTx60s, flow.sellTx60s, flow.buySharePct) ?: return
        ts.lastBuyPressurePct = bp
        ts.lastSellPressurePct = 100.0 - bp
    }

    /** V5.0.7747 — BirdeyeWhaleFeeder's MIN_VOLUME_USD_TO_CLASSIFY, the same whale bar. */
    private val WHALE_TRADE_MIN_USD_7747 = 1_000.0

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
                val safeSol = normalizeTradeSolAmount(solAmt, "helius_ws", mint) ?: return@HeliusWebSocket
                lastWsEventMs[mint] = System.currentTimeMillis()
                // V5.9: route to CopyTradeEngine for copy-buy detection
                if (isBuy && wallet.isNotBlank()) {
                    copyTradeEngine?.onSwapDetected(mint, wallet, safeSol, isBuy = true)
                }
                // V5.0.7773 — the tape (launch flow, candles, whales, dev sells, buy pressure).
                onHeliusTrade7773(mint, wallet, safeSol, isBuy)
                // V5.0.7787 — a decoded pump.fun TradeEvent (wallet present) carries the
                // exact SOL and token amounts, so its executed price is a live mark for a
                // HELD position. Without the PumpPortal trade stream (no key) held curve
                // tokens were priced by polling only: riskClockNoMark=1043 /
                // riskClockStale=177 on 5.0.7783, and stops landed at -46% against a
                // -15% floor. Field Manual §7: in a fast market stale quotes cost most.
                if (wallet.isNotBlank() && tokenAmt.isFinite() && tokenAmt > 0.0) {
                    val held7787 = try { status.tokens[mint]?.position?.isOpen == true } catch (_: Throwable) { false }
                    if (held7787) try { onHeldTradeMark7787?.invoke(mint, safeSol / tokenAmt) } catch (_: Throwable) {}
                    // V5.0.7819 — the same executed price is a candidate's candle print.
                    if (!held7787) onTradePrint7819(mint, safeSol / tokenAmt)
                }
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
        // V5.0.7807 — held positions are subscription-priority (never the LRU victim).
        heliusWs?.setPinnedMintsProvider7807 { heldMintsForSubscriptions7807() }
        // V5.0.7819 — Helius subscriptions are for Solana mints only (Field Manual L186).
        heliusWs?.setMintEligibility7819 { m -> heliusMintEligible7819(m) }
        heliusWs?.connect()
    }

    // ── DexScreener WebSocket ─────────────────────────────────────────
    // V5.6: Real-time price feed for ALL Solana tokens (graduated + Raydium)

    private fun startDexScreenerWebSocket() {
        dexWs = DexScreenerWebSocket(
            onPriceUpdate = { mint, priceUsd, priceChange5m, priceChange1h, 
                             volume5m, volume1h, liquidity, mcap, buys5m, sells5m, txns5m ->
                lastWsEventMs[mint] = System.currentTimeMillis()
                
                val ts = synchronized(status.tokens) {
                    status.tokens.values.find { it.mint == mint }
                } ?: return@DexScreenerWebSocket

                // V5.0.6701 — PRICE + PROVENANCE ARE ONE ATOMIC FACT.
                // Before this fix the WS callback overwrote lastPrice but left the
                // previous provider's lastPriceSource/lastPricePoolAddr/lastPriceDex
                // in place. OpenPnlSanity could therefore see a new DexScreener
                // number wearing stale same-source/same-pool proof and authorize a
                // 10^6 decimal/basis jump as real PnL. Stamp the actual WS source and
                // concrete subscribed pair at the same time as the price mutation.
                val dexPair6701 = ts.pairAddress.ifBlank {
                    ts.tokenMap.poolAddress.ifBlank { ts.tokenMap.pairAddress }
                }
                ts.lastPriceSource = "DEXSCREENER_WS"
                ts.lastPricePoolAddr = dexPair6701
                ts.lastPriceDex = ts.tokenMap.dexId.ifBlank { "DEXSCREENER" }
                ts.lastPrice = priceUsd
                ts.lastPriceUpdate = System.currentTimeMillis()
                ts.lastMcap = mcap
                // V5.0.7807 — a WS frame without a liquidity figure carries 0.0;
                // that is DATA UNKNOWN, not a drained pool. Overwriting a real
                // reading with it fed HARD_BLOCK_ZERO_LIQUIDITY and the
                // liquidity-distress exit evidence (Field Manual L190).
                if (LiquidityDepthAI.isLiquidityEvidence7807(liquidity)) ts.lastLiquidityUsd = liquidity
                // Keep the token-map observation coherent with the same stamped mark.
                ts.tokenMap.priceUsd = priceUsd.takeIf { it.isFinite() && it > 0.0 }
                ts.tokenMap.priceObservedAtMs7858 = ts.lastPriceUpdate
                ts.tokenMap.poolAddress = dexPair6701
                ts.tokenMap.pairAddress = ts.tokenMap.pairAddress.ifBlank { dexPair6701 }
                ts.tokenMap.dexId = ts.lastPriceDex
                ts.tokenMap.venue = ts.lastPriceDex
                ts.tokenMap.updatedAtMs = ts.lastPriceUpdate
                ts.lastBuyPressurePct = if (txns5m > 0) (buys5m.toDouble() / txns5m) * 100 else 50.0
                // V5.9.827 — wire previously-dropped distribution + hourly signals
                ts.lastSellPressurePct = if (txns5m > 0) (sells5m.toDouble() / txns5m) * 100 else 50.0
                // V5.0.7425 — do not throw away the short-horizon direction.
                // Lifecycle routing needs this exact feed to distinguish ignition
                // from a token already cascading after its pump.
                ts.lastPriceChange5m = priceChange5m
                ts.lastPriceChange1h = priceChange1h
                
                // Update volume scores in meta (copy with new values)
                ts.meta = ts.meta.copy(
                    volScore = when {
                        volume5m > 50_000 -> 90.0
                        volume5m > 20_000 -> 75.0
                        volume5m > 10_000 -> 60.0
                        volume5m > 5_000 -> 45.0
                        else -> 30.0
                    },
                    pressScore = if (txns5m > 0) (buys5m.toDouble() / txns5m) * 100 else 50.0
                )

                // V5.0.7819 — a live websocket pair price is an observed tick,
                // the same kind Executor.getActualPrice bins (WS_LIVE provenance).
                try { com.lifecyclebot.engine.truth.LocalCandleSynthesis7055.note(ts, priceUsd, mcap) } catch (_: Throwable) {}

                // V5.0.7777 — feed the existing market sweep's bounded temporal
                // opportunity tape. This is local/cache-only; no network call
                // and no admission authority on the websocket hot path.
                try {
                    com.lifecyclebot.engine.market.MarketSweep7297.recordRealtime7777(
                        mint = ts.mint,
                        priceUsd = priceUsd,
                        mcapUsd = mcap,
                        liquidityUsd = liquidity,
                        buyPressurePct = ts.lastBuyPressurePct,
                        priceChange5mPct = priceChange5m,
                        priceChange1hPct = priceChange1h,
                        volume5mUsd = volume5m,
                        txCount5m = txns5m,
                    )
                } catch (_: Throwable) {}
                
                // Log significant price moves
                if (kotlin.math.abs(priceChange5m) >= 10.0) {
                    val emoji = if (priceChange5m > 0) "📈" else "📉"
                    onLog("$emoji ${ts.symbol}: ${priceChange5m.toInt()}% (5m) | \$${(mcap/1000).toInt()}K mcap", mint)
                }
            },
            onLog = { msg -> onLog("DexScreener: $msg", "") },
        )
        dexWs?.connect()
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
                // V5.0.7402 — MomentumPredictorAI had many live consumers but
                // zero production writers. Feed it from the same normalized
                // 8-second trade candle so acceleration/coiling/accumulation
                // becomes real evidence instead of permanent NEUTRAL.
                try {
                    val txN7402 = pending.buys + pending.sells
                    val bp7402 = if (txN7402 > 0) pending.buys.toDouble() / txN7402.toDouble() * 100.0 else 50.0
                    MomentumPredictorAI.recordPricePoint(
                        mint = ts.mint,
                        symbol = ts.symbol,
                        price = close,
                        volume = pending.buyVol + pending.sellVol,
                        buyPressure = bp7402,
                        txCount = txN7402,
                    )
                    PipelineHealthCollector.labelInc("MOMENTUM_PREDICTOR_LIVE_POINT_7402")
                } catch (_: Throwable) {}
                // V5.0.6852 §SILENT_DEFAULTS_FED_THE_WHOLE_BOOK — TokenState.volatility and
                // TokenState.momentum had ZERO writers tree-wide while carrying 39 and
                // several readers respectively, every one of them a fallback:
                //   BotService:9558 `ts.volatility ?: 50.0`, MainActivity:5523/5758/5822,
                //   ToolkitSignalSheet:224 `?: 0.0`,
                //   RegimeVolatilityExecutorBridge:28-29 `?: ts.meta.volScore / momScore`.
                // So every token in the book was priced as median volatility and neutral
                // momentum: stops, TP widening and regime routing treated a 5%/hr bluechip
                // and a 400%/hr pump.fun launch identically. This is the candle series that
                // should have been feeding them all along.
                // Both are 0..100 scores, matching their meta siblings (volScore/momScore)
                // and the 50.0 "median" the readers assume.
                val recent6852 = ts.history.takeLast(12)
                if (recent6852.size >= 3) {
                    val ranges6852 = recent6852.mapNotNull { c ->
                        val p = c.priceUsd
                        if (!p.isFinite() || p <= 0.0) null else {
                            val hi = if (c.highUsd > 0.0) c.highUsd else p
                            val lo = if (c.lowUsd > 0.0) c.lowUsd else p
                            (((hi - lo) / p) * 100.0).takeIf { it.isFinite() && it >= 0.0 }
                        }
                    }
                    if (ranges6852.isNotEmpty()) {
                        // mean per-candle true range as % of price; ~5%/candle maps to the
                        // 50 midpoint the readers already treat as median, 10% saturates.
                        ts.volatility = (ranges6852.average() * 10.0).coerceIn(0.0, 100.0)
                    }
                    val first6852 = recent6852.first().priceUsd
                    val last6852 = recent6852.last().priceUsd
                    if (first6852.isFinite() && first6852 > 0.0 && last6852.isFinite() && last6852 > 0.0) {
                        // net % change across the window. V5.0.7758 — the same signed
                        // percent the pair poll writes (BotService) and every reader
                        // expects (Express >= 3, pivot >= 6); the old 50-centred score
                        // read as +50% momentum on a flat token.
                        val chg6852 = ((last6852 - first6852) / first6852) * 100.0
                        ts.momentum = chg6852.coerceIn(-100.0, 1000.0)
                    }
                }
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
        val now = System.currentTimeMillis()
        if (now - lastEvent > 15_000L) return true
        // V5.0.7762 — the callers skip the whole processTokenCycle, not just the REST
        // price fetch, so a launch trading more often than every 15 s never got an
        // entry evaluation or a liquidity/mcap/flow refresh. A socket-active token is
        // now cycled at most once per 15 s instead of never.
        val last = lastWsCycleAllowedMs7762[mint] ?: 0L
        if (now - last < 15_000L) return false
        lastWsCycleAllowedMs7762[mint] = now
        if (lastWsCycleAllowedMs7762.size > 4_000) lastWsCycleAllowedMs7762.entries.removeIf { now - it.value > 600_000L }
        try { PipelineHealthCollector.labelInc("WS_ACTIVE_TOKEN_CYCLED_7762") } catch (_: Throwable) {}
        return true
    }

    private val lastWsCycleAllowedMs7762 = java.util.concurrent.ConcurrentHashMap<String, Long>()
}

private fun Double.fmt(d: Int = 4) = "%.${d}f".format(this)

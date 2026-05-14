package com.lifecyclebot.data

data class Candle(
    val ts: Long,
    val priceUsd: Double,
    val marketCap: Double,
    val volumeH1: Double,
    val volume24h: Double,
    val buysH1: Int = 0,
    val sellsH1: Int = 0,
    val buys24h: Int = 0,
    val sells24h: Int = 0,
    val holderCount: Int = 0,     // token holder count at this snapshot
    // OHLC fields — used for wick detection and spike top analysis
    // If not populated by data source, high/low default to priceUsd (flat candle)
    val highUsd: Double = 0.0,    // candle high price
    val lowUsd: Double = 0.0,     // candle low price
    val openUsd: Double = 0.0,    // candle open price
) {
    val buyRatio: Double get() {
        val t = buysH1 + sellsH1
        if (t == 0) {
            val t2 = buys24h + sells24h
            return if (t2 == 0) 0.5 else buys24h.toDouble() / t2
        }
        return buysH1.toDouble() / t
    }
    val vol: Double get() = if (volumeH1 > 0) volumeH1 else volume24h
    // CRITICAL FIX: ref should return PRICE, not market cap! 
    // Market cap was causing astronomical P&L calculations (+98 billion %)
    val ref: Double get() = priceUsd  // Always return actual price

    // Upper wick ratio: (high - close) / (high - low)
    // > 0.4 = long upper wick = buyers rejected at this level = spike top signal
    val upperWickRatio: Double get() {
        val h = if (highUsd > 0) highUsd else priceUsd
        val l = if (lowUsd  > 0) lowUsd  else priceUsd
        val c = priceUsd
        val range = h - l
        return if (range > 0) (h - c) / range else 0.0
    }

    // True if this candle printed a long upper wick (spike rejection)
    val hasUpperWick: Boolean get() = upperWickRatio > 0.40
}

data class Position(
    val qtyToken: Double = 0.0,
    val entryPrice: Double = 0.0,      // average entry price across all adds
    val entryTime: Long = 0L,
    val costSol: Double = 0.0,         // total SOL invested including top-ups
    var highestPrice: Double = 0.0,
    var lowestPrice: Double = 0.0,     // Track lowest price since entry for Exit AI
    var peakGainPct: Double = 0.0,     // Track highest % gain for trailing stop
    val entryPhase: String = "",
    val entryScore: Double = 0.0,
    val entryLiquidityUsd: Double = 0.0,  // liquidity at entry for collapse detection
    val entryMcap: Double = 0.0,          // V4.20: market cap at entry for graduation detection
    // ═══════════════════════════════════════════════════════════════════
    // V5.6.8 FIX: Track if position was opened in PAPER or LIVE mode
    // This is CRITICAL - if user switches modes, we must sell in the mode
    // the position was OPENED in (can't paper-sell a live position!)
    // ═══════════════════════════════════════════════════════════════════
    val isPaperPosition: Boolean = true,  // True = paper trade, False = real on-chain trade
    // ═══════════════════════════════════════════════════════════════════
    // TRADING MODE TRACKING - Which mode was this position opened in?
    // Now MUTABLE for dynamic mode switching by HoldingLogicLayer
    // ═══════════════════════════════════════════════════════════════════
    var tradingMode: String = "STANDARD",  // ExtendedMode name (e.g., "MOONSHOT", "PUMP_SNIPER")
    var tradingModeEmoji: String = "📈",   // Emoji for display
    var modeHistory: String = "",           // Track mode switches: "PUMP_SNIPER>MOMENTUM_SWING"
    // ═══════════════════════════════════════════════════════════════════
    // TREASURY MODE - Quick scalp exits
    // ═══════════════════════════════════════════════════════════════════
    var treasuryTakeProfit: Double = 0.0,  // Target profit % for treasury scalps
    var treasuryStopLoss: Double = 0.0,    // Stop loss % for treasury scalps
    var treasuryEntryPrice: Double = 0.0,  // V5.9.200: Raw entry price before slippage, for accurate TP calc
    var isTreasuryPosition: Boolean = false, // True if this is a treasury mode position
    // ═══════════════════════════════════════════════════════════════════
    // BLUE CHIP MODE - Quality plays on >$1M mcap tokens
    // ═══════════════════════════════════════════════════════════════════
    var blueChipTakeProfit: Double = 0.0,  // Target profit % for blue chip trades
    var blueChipStopLoss: Double = 0.0,    // Stop loss % for blue chip trades
    var isBlueChipPosition: Boolean = false, // True if this is a blue chip position
    // ═══════════════════════════════════════════════════════════════════
    // SHITCOIN MODE - Degen plays on micro-cap memecoins <$500K mcap
    // ═══════════════════════════════════════════════════════════════════
    var shitCoinTakeProfit: Double = 0.0,  // Target profit % for shitcoin trades
    var shitCoinStopLoss: Double = 0.0,    // Stop loss % for shitcoin trades
    var isShitCoinPosition: Boolean = false, // True if this is a shitcoin position
    // Top-up tracking
    val topUpCount: Int = 0,
    val topUpCostSol: Double = 0.0,
    val lastTopUpTime: Long = 0L,
    val lastTopUpPrice: Double = 0.0,
    val partialSoldPct: Double = 0.0,
    val isLongHold: Boolean = false,   // promoted to conviction long-hold mode
    // Graduated building
    val buildPhase: Int = 0,           // 0=none, 1=initial, 2=confirm, 3=full
    val targetBuildSol: Double = 0.0,
    // ═══════════════════════════════════════════════════════════════════
    // PROFIT LOCK SYSTEM - Secure capital, then let house money ride
    // ═══════════════════════════════════════════════════════════════════
    val capitalRecovered: Boolean = false,      // At 2x: sold enough to recover initial investment
    val capitalRecoveredSol: Double = 0.0,      // How much SOL we recovered
    val profitLocked: Boolean = false,          // At 5x: locked 50% of remaining profits
    val profitLockedSol: Double = 0.0,          // How much profit we locked
    val isHouseMoney: Boolean = false,          // True after capital recovered - remainder is "free"
    val lockedProfitFloor: Double = 0.0,        // Minimum value we've secured (won't trail below this)
    // ═══════════════════════════════════════════════════════════════════
    // V5.9.15: PHANTOM GUARD — true while we're still verifying tokens arrived on-chain
    // UI + persistence + exit loops skip positions in this state.
    // ═══════════════════════════════════════════════════════════════════
    val pendingVerify: Boolean = false,
    // V5.9.118: Regression guard — set to true the first time the
    // profit-floor-regression WARN has been logged for this position so
    // we don't spam the log. Intentionally var/transient — not persisted.
    var profitFloorRegressionLogged: Boolean = false,
) {
    // V5.9.290: isOpen — tokens exist AND not in the short verify window.
    // V5.9.315: REMOVED 120s auto-promote. Previously, if pendingVerify stayed
    // true past 120s (RPC failure or tx-indexing lag during verify coroutine),
    // isOpen would silently return true with the EXPECTED qtyToken from buy
    // submission — even though no tokens may have landed on-chain. This was
    // the GHOST POSITION root cause: bot tried to manage/sell tokens that
    // didn't exist, jamming the live meme trader. Now pendingVerify is the
    // single source of truth: only false when the verify coroutine OR the
    // BotService watchdog has confirmed real on-chain state.
    val isOpen get(): Boolean {
        if (qtyToken <= 0.0) return false
        return !pendingVerify
    }
    // True when tokens exist on-chain regardless of verify state — used for
    // fee accounting and capital exposure calculations.
    val hasTokens get() = qtyToken > 0.0
    val initialCostSol get() = costSol - topUpCostSol  // original entry size
    val avgEntryCost get() = if (qtyToken > 0) costSol else 0.0
    val isFullyBuilt get() = buildPhase >= 3 || targetBuildSol <= 0 || costSol >= targetBuildSol * 0.95
    // After capital is recovered, we're playing with house money
    val effectiveRisk get() = if (isHouseMoney) 0.0 else costSol
}

data class Trade(
    val side: String,          // BUY | SELL
    val mode: String,          // paper | live
    val sol: Double,
    val price: Double,
    val ts: Long,
    val reason: String = "",
    val pnlSol: Double = 0.0,
    val pnlPct: Double = 0.0,
    val score: Double = 0.0,
    val sig: String = "",
    val feeSol: Double = 0.0,         // estimated network + protocol fees
    val netPnlSol: Double = 0.0,      // pnlSol minus fees
    val quoteDivergencePct: Double = 0.0, // how much price moved between quote checks
    val isCopyTrade: Boolean = false,
    val copyWallet: String = "",
    val tradingMode: String = "STANDARD",    // ExtendedMode name
    val tradingModeEmoji: String = "📈",     // Emoji for display
    val mint: String = "",                    // Token mint address
)

data class StrategyMeta(
    val move3Pct: Double = 0.0,
    val move8Pct: Double = 0.0,
    val rangePct: Double = 0.0,
    val posInRange: Double = 50.0,
    val lowerHighs: Boolean = false,
    val breakdown: Boolean = false,
    val ema8: Double = 0.0,
    val volScore: Double = 50.0,
    val pressScore: Double = 50.0,
    val momScore: Double = 50.0,
    val exhaustion: Boolean = false,
    val curveStage: String = "",
    val curveProgress: Double = 0.0,
    val whaleSummary: String = "",
    val velocityScore: Double = 0.0,
    val emafanAlignment: String = "FLAT",  // BULL_FAN | BULL_FLAT | FLAT | BEAR_FLAT | BEAR_FAN
    val spikeDetected: Boolean = false,    // spike top forming this tick
    val protectMode: Boolean = false,      // gain > 500% — tightest trail
    val topUpReady: Boolean = false,       // strategy says conditions met to add
    val chartPattern: String = "",         // Detected chart pattern (BULL_FLAG, etc)
    val chartPatternConf: Double = 0.0,    // Pattern confidence 0-100
    val holderConcentration: Double = 0.0, // Top holder % 
    val setupQuality: String = "C",        // A+ / B / C - for position sizing
    val avgAtr: Double = 5.0,              // Average True Range for volatility
    val rsi: Double = 50.0,                // RSI value for Entry/Exit AI
)

data class StrategyResult(
    val phase: String,
    val signal: String,
    val entryScore: Double,
    val exitScore: Double,
    val meta: StrategyMeta,
)

data class TokenState(
    val mint: String,
    val symbol: String = "",
    val name: String = "",
    val pairAddress: String = "",
    val pairUrl: String = "",
    var logoUrl: String = "",  // Token logo URL (from DexScreener)
    // strategy
    var phase: String = "idle",
    var signal: String = "WAIT",
    var entryScore: Double = 0.0,
    var exitScore: Double = 0.0,
    var meta: StrategyMeta = StrategyMeta(),
    // price
    var source: String = "",              // how this token was discovered (PUMP_FUN_NEW etc)
    var addedToWatchlistAt: Long = System.currentTimeMillis(),  // when token was added to watchlist
    var candleTimeframeMinutes: Int = 1,  // timeframe of history[] candles (1=1M, 60=1H, 240=4H, 1440=1D)
    var lastPrice: Double = 0.0,
    var lastPriceUpdate: Long = 0L,    // V5.9.362 — wallclock of most recent lastPrice mutation; used to detect stale-feed positions stuck at 0% PnL
    var lastMcap: Double = 0.0,
    var lastLiquidityUsd: Double = 0.0,    // USD liquidity from Dexscreener — key for exit risk
    var lastFdv: Double = 0.0,             // fully diluted valuation
    var holderGrowthRate: Double = 0.0,    // % change in holders over last N candles (positive = growing)
    var peakHolderCount: Int = 0,          // highest holder count ever seen for this token
    // history — 1m candles (primary strategy timeframe)
    val history: ArrayDeque<Candle> = ArrayDeque(300),
    // Multi-timeframe candles — seeded from Birdeye, used for trend confirmation
    val history5m: ArrayDeque<Candle> = ArrayDeque(100),   // 5m candles
    val history15m: ArrayDeque<Candle> = ArrayDeque(60),   // 15m candles
    var position: Position = Position(),
    val trades: MutableList<Trade> = mutableListOf(),
    var lastError: String = "",
    var lastExitTs: Long = 0L,
    var lastExitPrice: Double = 0.0,   // price at last exit — used for smart re-entry
    var lastExitPnlPct: Double = 0.0,  // P&L % at last exit — drives post-win re-entry boost
    var lastExitWasWin: Boolean = false,
    var recentEntryTimes: MutableList<Long> = mutableListOf(), // cross-token correlation
    var sentiment: com.lifecyclebot.data.SentimentResult = com.lifecyclebot.data.SentimentResult(),
    var lastSentimentRefresh: Long = 0L,
    var safety: com.lifecyclebot.engine.SafetyReport = com.lifecyclebot.engine.SafetyReport(),
    var lastSafetyCheck: Long = 0L,
    // V3 scoring cache for Treasury Mode
    var lastV3Score: Int? = null,
    var lastV3Confidence: Int? = null,
    var lastBuyPressurePct: Double = 50.0,  // Buy pressure percentage
    var topHolderPct: Double? = null,        // Top holder concentration
    var momentum: Double? = null,            // Price momentum
    var volatility: Double? = null,          // Price volatility
    // V5.9.618 — bridge advisory flag. Set per-pass by BotService when the
    // MemeUnifiedScorerBridge agrees an entry is good. Read by ShitCoin/Moonshot
    // evaluators as a small additive confidence bonus. Pure advisory — never blocks.
    var bridgeAdvisoryAgrees: Boolean = false,
) {
    // CRITICAL FIX: ref should return PRICE, not market cap!
    // Market cap was causing astronomical P&L calculations (+98 billion %)
    val ref get() = lastPrice  // Always return actual price
}

data class BotStatus(
    var running: Boolean = false,
    var walletSol: Double = 0.0,
    var paperWalletSol: Double = 0.0,   // Paper trading balance (initialized to $1000 at bot start)
    var paperWalletInitialized: Boolean = false,  // Track if we've synced with real wallet
    var paperWalletLastRefreshMs: Long = 0L,      // Timestamp of last 12-hour top-up
    val logs: ArrayDeque<String> = ArrayDeque(600),
    // V5.9.715 — Changed from mutableMapOf() (LinkedHashMap) to ConcurrentHashMap.
    // BotViewModel.pollLoop() iterates tokens.values (via openPositions getter)
    // on the UI thread while BotService mutates the map on its coroutine thread.
    // LinkedHashMap iterators throw ConcurrentModificationException on concurrent
    // structural modifications — this was the CRASH at BotStatus.getOpenPositions.
    // ConcurrentHashMap iterators are weakly consistent: they never throw CME and
    // always reflect at least the state at iterator creation time. All existing
    // synchronized(status.tokens) call-sites in BotService remain valid (they now
    // act as optional extra guards; ConcurrentHashMap's own internal locking is
    // sufficient for single-operation atomicity).
    val tokens: MutableMap<String, TokenState> = java.util.concurrent.ConcurrentHashMap(),
) {
    /** All tokens currently holding a position.
     *
     *  V5.9.739 — extended to include `pendingVerify` positions older than
     *  120s whose tokens still need on-chain confirmation. Operator (Bernard
     *  Griffin, messenger 2026-05-14 18:43) screenshot showed BULLISH /
     *  MEMEART / MTFR / NVDAx sitting in the Phantom wallet but completely
     *  invisible in the bot UI. Cause: the per-token verifier failed to
     *  confirm within its 30s window (Jupiter Ultra indexing lag), so the
     *  positions stayed `pendingVerify=true`. `position.isOpen` filters
     *  those out → operator sees nothing.
     *
     *  This list is consumed by UI rendering and PnL display. Exit /
     *  management logic continues to use `position.isOpen` directly
     *  (BotService.kt:8217 etc.), so we never trade tokens that haven't
     *  been confirmed on-chain. The visibility relaxation is purely
     *  cosmetic — but it's the difference between "the bot lost my
     *  positions" and "the bot is showing me the position and waiting
     *  for on-chain confirmation". */
    val openPositions: List<TokenState>
        get() = tokens.values.filter { ts ->
            val pos = ts.position
            if (pos.isOpen) return@filter true
            // V5.9.739 — include stale pendingVerify (>120s) with non-zero
            // qty as "open for viewing". Watchdog will resolve them in
            // ≤60s; until then, operator sees the position so they know
            // capital is deployed.
            if (pos.pendingVerify && pos.qtyToken > 0.0 && pos.entryTime > 0L) {
                val ageMs = System.currentTimeMillis() - pos.entryTime
                return@filter ageMs >= 120_000L
            }
            false
        }

    /** Total SOL currently at risk across all positions */
    val totalExposureSol: Double
        get() = openPositions.sumOf { it.position.costSol }

    /** Number of open positions */
    val openPositionCount: Int
        get() = openPositions.size
    
    /** Get effective wallet balance for trading - uses paper balance in paper mode */
    fun getEffectiveBalance(isPaperMode: Boolean): Double {
        // V5.9.495g — LIVE: deduct the (capped) treasury lock from on-chain
        // balance so the locked SOL is never reinvested until wallet growth
        // pushes the lock cap up (treasury cap floats with walletSol).
        // PAPER: untouched — paper accounting handles its own treasury math.
        return if (isPaperMode) {
            paperWalletSol
        } else {
            val locked = com.lifecyclebot.engine.TreasuryManager
                .effectiveLockedSol(walletSol, isPaperMode = false)
            (walletSol - locked).coerceAtLeast(0.0)
        }
    }
}

/**
 * PRIORITY 2: Unified Candidate Decision
 * 
 * Single source of truth for a trade candidate's evaluation.
 * Aggregates all scoring signals from Strategy, Edge Optimizer, and Quality Gates
 * into one coherent structure that the Executor uses for final decisions.
 * 
 * This eliminates the fragmented decision logic where different modules
 * could have conflicting views on the same trade.
 */
data class CandidateDecision(
    // Core scores from LifecycleStrategy
    val entryScore: Double,
    val exitScore: Double,
    val phase: String,
    val signal: String,              // RAW signal from strategy (BUY/SELL/WAIT)
    
    // Quality assessment
    val setupQuality: String,        // A+ / B / C from strategy
    val edgeQuality: String,         // A / B / C / SKIP from EdgeOptimizer
    val finalQuality: String,        // Combined quality rating
    
    // Edge Optimizer analysis
    val edgePhase: String,           // REACCUMULATION / EXPANSION / DISTRIBUTION / DEAD / UNKNOWN
    val edgeConfidence: Double,      // 0-100 from EdgeOptimizer
    val isOptimalEntry: Boolean,     // Spike → Pullback → Reclaim pattern
    val edgeVeto: Boolean,           // Edge says SKIP → veto BUY
    
    // Final verdict
    val shouldTrade: Boolean,        // After all gates and vetoes
    val finalSignal: String,         // Effective signal (may be WAIT if vetoed)
    val blockReason: String,         // Why blocked (empty if not blocked)
    
    // Sizing hints
    val qualityPenalty: Double,      // 0.0-1.0, multiply size by this
    val aiConfidence: Double,        // For SmartSizer
    
    // Strategy metadata passthrough
    val meta: StrategyMeta,
) {
    /**
     * Quick check if this is a high-quality setup worth trading.
     * Used for logging and notifications.
     */
    val isHighQuality: Boolean
        get() = finalQuality in listOf("A+", "A", "B") && !edgeVeto
    
    /**
     * Number of red flags (low quality, unknown phase, low confidence).
     * Used for size penalty calculation.
     */
    val redFlagCount: Int
        get() {
            var count = 0
            if (setupQuality == "C") count++
            if (phase.contains("unknown", ignoreCase = true)) count++
            if (aiConfidence < 30.0) count++
            return count
        }
    
    companion object {
        /**
         * Factory method to create a BLOCKED decision.
         * Used when gates prevent any trading.
         */
        fun blocked(reason: String, meta: StrategyMeta = StrategyMeta()) = CandidateDecision(
            entryScore = 0.0,
            exitScore = 0.0,
            phase = "blocked",
            signal = "WAIT",
            setupQuality = "C",
            edgeQuality = "SKIP",
            finalQuality = "SKIP",
            edgePhase = "UNKNOWN",
            edgeConfidence = 0.0,
            isOptimalEntry = false,
            edgeVeto = true,
            shouldTrade = false,
            finalSignal = "WAIT",
            blockReason = reason,
            qualityPenalty = 0.0,
            aiConfidence = 0.0,
            meta = meta,
        )
    }
}

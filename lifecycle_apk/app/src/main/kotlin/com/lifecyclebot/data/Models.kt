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
    val ref: Double get() = if (marketCap > 0) marketCap else priceUsd

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
    val entryPhase: String = "",
    val entryScore: Double = 0.0,
    // Top-up tracking
    val topUpCount: Int = 0,
    val topUpCostSol: Double = 0.0,
    val lastTopUpTime: Long = 0L,
    val lastTopUpPrice: Double = 0.0,
    val partialSoldPct: Double = 0.0,
    val isLongHold: Boolean = false,   // promoted to conviction long-hold mode
) {
    val isOpen get() = qtyToken > 0.0
    val initialCostSol get() = costSol - topUpCostSol  // original entry size
    val avgEntryCost get() = if (qtyToken > 0) costSol else 0.0
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
    // strategy
    var phase: String = "idle",
    var signal: String = "WAIT",
    var entryScore: Double = 0.0,
    var exitScore: Double = 0.0,
    var meta: StrategyMeta = StrategyMeta(),
    // price
    var source: String = "",              // how this token was discovered (PUMP_FUN_NEW etc)
    var candleTimeframeMinutes: Int = 1,  // timeframe of history[] candles (1=1M, 60=1H, 240=4H, 1440=1D)
    var lastPrice: Double = 0.0,
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
) {
    val ref get() = if (lastMcap > 0) lastMcap else lastPrice
}

data class BotStatus(
    var running: Boolean = false,
    var walletSol: Double = 0.0,
    val logs: ArrayDeque<String> = ArrayDeque(600),
    val tokens: MutableMap<String, TokenState> = mutableMapOf(),
) {
    /** All tokens currently holding a position */
    val openPositions: List<TokenState>
        get() = tokens.values.filter { it.position.isOpen }

    /** Total SOL currently at risk across all positions */
    val totalExposureSol: Double
        get() = openPositions.sumOf { it.position.costSol }

    /** Number of open positions */
    val openPositionCount: Int
        get() = openPositions.size
}

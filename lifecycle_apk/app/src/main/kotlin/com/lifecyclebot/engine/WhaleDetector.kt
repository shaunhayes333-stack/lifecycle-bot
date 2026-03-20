package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState

/**
 * WhaleDetector
 *
 * Detects large individual wallet buys relative to token supply/market cap.
 * A wallet buying >1% of supply in a single tx = strong accumulation signal.
 *
 * Also computes:
 *   • On-chain velocity: unique wallets buying per minute (from Helius stream)
 *   • Buy concentration: are a few wallets driving all volume? (good = distributed)
 *   • Smart money signal: repeated buys from same wallet (conviction)
 *
 * Data sources:
 *   • Helius WebSocket trade events (wallet address per swap)
 *   • Candle buy/sell counts (from Dexscreener)
 *
 * All in-memory — no API calls here, data fed from the existing streams.
 */
object WhaleDetector {

    // A "whale" buy is > this % of recent volume in a single tx
    private const val WHALE_VOL_THRESHOLD_PCT = 15.0

    // Minimum SOL for a trade to be considered "significant"
    private const val MIN_SIGNIFICANT_SOL = 0.5

    data class WhaleSignal(
        val hasWhaleActivity: Boolean,
        val whaleBuys: Int,              // number of large buys detected recently
        val whaleScore: Double,          // 0-100, how bullish is the whale activity
        val velocityScore: Double,       // 0-100, unique buyer rate
        val concentration: Double,       // 0-100, 100=one wallet, 0=fully distributed
        val smartMoneyPresent: Boolean,  // repeated buys from same wallet(s)
        val summary: String,
    )

    // Per-token whale tracking
    private val recentLargeBuys = mutableMapOf<String, MutableList<LargeBuy>>()
    private val walletBuyCount  = mutableMapOf<String, MutableMap<String, Int>>()

    data class LargeBuy(
        val ts: Long,
        val walletAddress: String,
        val solAmount: Double,
        val pctOfVolume: Double,
    )

    /**
     * Record a trade event from Helius/Pump.fun WebSocket.
     * Called by DataOrchestrator on every swap event.
     */
    fun recordTrade(mint: String, wallet: String, solAmount: Double, isBuy: Boolean) {
        if (!isBuy || solAmount < MIN_SIGNIFICANT_SOL) return
        val now = System.currentTimeMillis()

        // Track wallet buy frequency
        val walletMap = walletBuyCount.getOrPut(mint) { mutableMapOf() }
        walletMap[wallet] = (walletMap[wallet] ?: 0) + 1

        // Record as large buy if significant
        val buys = recentLargeBuys.getOrPut(mint) { mutableListOf() }
        buys.add(LargeBuy(now, wallet, solAmount, 0.0))  // pct calculated in evaluate

        // Trim to last 30 min
        buys.removeIf { now - it.ts > 30 * 60_000L }

        // Trim wallet map to wallets seen in last 30 min
        val cutoff = now - 30 * 60_000L
        walletMap.entries.removeIf { _ -> false }  // keep for session
    }

    /**
     * Evaluate whale activity for a token.
     */
    fun evaluate(mint: String, ts: TokenState): WhaleSignal {
        val now      = System.currentTimeMillis()
        val hist     = ts.history.toList()
        val buys     = recentLargeBuys[mint] ?: emptyList()
        val wallets  = walletBuyCount[mint]  ?: emptyMap()

        if (hist.isEmpty()) {
            return WhaleSignal(false, 0, 0.0, 0.0, 50.0, false, "No data")
        }

        // ── Velocity: unique buyers per minute ───────────────────
        // Approximate from candle buy counts over last 5 candles
        val recentCandles   = hist.takeLast(5)
        val totalBuys       = recentCandles.sumOf { it.buysH1 }
        val timeSpanMins    = if (recentCandles.size >= 2)
            (recentCandles.last().ts - recentCandles.first().ts) / 60_000.0
        else 1.0
        val buysPerMin      = if (timeSpanMins > 0) totalBuys / timeSpanMins else 0.0

        // Velocity score: 5+ unique buys/min = very active
        val velocityScore = (buysPerMin / 5.0 * 100.0).coerceIn(0.0, 100.0)

        // ── Whale activity ────────────────────────────────────────
        val recentLarge = buys.filter { now - it.ts < 10 * 60_000L }  // last 10 min
        val recentVol   = hist.takeLast(8).sumOf { it.vol }.coerceAtLeast(1.0)

        // Recalculate pct of volume for each buy
        val largePct = recentLarge.filter { it.solAmount / recentVol * 100 > WHALE_VOL_THRESHOLD_PCT }

        val whaleScore = when {
            largePct.size >= 3 -> 90.0
            largePct.size == 2 -> 70.0
            largePct.size == 1 -> 50.0
            recentLarge.isNotEmpty() -> 30.0
            else -> 0.0
        }

        // ── Smart money: same wallet buying multiple times ────────
        val repeatedBuyers = wallets.values.count { it >= 2 }
        val smartMoney     = repeatedBuyers >= 1 && recentLarge.isNotEmpty()

        // ── Concentration ─────────────────────────────────────────
        val uniqueBuyers   = wallets.size
        val concentration  = if (uniqueBuyers == 0) 50.0
                            else (1.0 / uniqueBuyers * 100.0).coerceIn(0.0, 100.0)

        val hasWhale = largePct.isNotEmpty() || smartMoney

        val summary = buildString {
            if (hasWhale) append("🐋 ${largePct.size} large buys  ")
            if (smartMoney) append("🧠 Smart money  ")
            append("${buysPerMin.toInt()} buys/min")
        }

        return WhaleSignal(
            hasWhaleActivity = hasWhale,
            whaleBuys        = largePct.size,
            whaleScore       = whaleScore,
            velocityScore    = velocityScore,
            concentration    = concentration,
            smartMoneyPresent = smartMoney,
            summary          = summary,
        )
    }

    fun clearToken(mint: String) {
        recentLargeBuys.remove(mint)
        walletBuyCount.remove(mint)
    }
}

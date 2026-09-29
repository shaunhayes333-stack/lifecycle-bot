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

    // V5.0.7401 — launch ignition tape. The old recordTrade returned before
    // recording sells and buys <0.5 SOL, so the "pre-parabola" system could
    // not see the actual first-minute order flow. Keep a tiny 90-second tape
    // of every normalized PumpPortal/Helius trade.
    data class LaunchTrade(
        val ts: Long,
        val wallet: String,
        val sol: Double,
        val isBuy: Boolean,
    )
    data class LaunchFlow(
        val buyTx60s: Int,
        val sellTx60s: Int,
        val buySol60s: Double,
        val sellSol60s: Double,
        val distinctBuyers60s: Int,
        val devBuyTx60s: Int,
        val devSellTx60s: Int,
        val buyTx15s: Int,
        val buyTxPrev15s: Int,
        val accelerationRising: Boolean,
        val buySharePct: Double,
        // V5.0.7450 — first-minute wallet-flow structure. These are
        // coordination metrics, not same-slot/Jito bundle claims.
        val largestBuyerSharePct60s: Double = 0.0,
        val top3BuyerSharePct60s: Double = 0.0,
        val repeatBuyerWallets60s: Int = 0,
    )
    private val launchTrades7401 = java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<LaunchTrade>>()

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
        val now = System.currentTimeMillis()
        if (mint.isNotBlank() && solAmount.isFinite() && solAmount > 0.0) {
            val q = launchTrades7401.getOrPut(mint) { java.util.ArrayDeque() }
            synchronized(q) {
                q.addLast(LaunchTrade(now, wallet, solAmount, isBuy))
                while (q.isNotEmpty() && now - q.first().ts > 90_000L) q.removeFirst()
                while (q.size > 512) q.removeFirst()
            }
        }
        if (!isBuy || solAmount < MIN_SIGNIFICANT_SOL) return

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
        // IMPORTANT: buysH1 is HOURLY buy count, not per-candle!
        // We need to normalize this properly to get buys/min.
        //
        // BUG FIX: Previously divided total buysH1 by candle timespan, which
        // caused massive overestimation (100 buys/hr ÷ 5 min = 20 buys/min, WRONG!)
        // 
        // CORRECT: buysH1 is already per-hour, so:
        //   buysPerMin = latest buysH1 / 60
        val recentCandles   = hist.takeLast(5)
        val latestBuysH1    = recentCandles.lastOrNull()?.buysH1 ?: 0
        val buysPerMin      = latestBuysH1 / 60.0  // Convert hourly to per-minute

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

    fun launchFlow7401(mint: String, devWallet: String? = null, nowMs: Long = System.currentTimeMillis()): LaunchFlow {
        val q = launchTrades7401[mint]
        val rows = if (q == null) emptyList() else synchronized(q) {
            while (q.isNotEmpty() && nowMs - q.first().ts > 90_000L) q.removeFirst()
            q.toList()
        }
        val r60 = rows.filter { nowMs - it.ts <= 60_000L }
        val r15 = rows.filter { nowMs - it.ts <= 15_000L }
        val prev15 = rows.filter { nowMs - it.ts in 15_001L..30_000L }
        val buys = r60.filter { it.isBuy }
        val sells = r60.filterNot { it.isBuy }
        val buySol = buys.sumOf { it.sol }
        val sellSol = sells.sumOf { it.sol }
        val totalSol = buySol + sellSol
        val dev = devWallet?.takeIf { it.isNotBlank() }
        val walletBuySol7450 = buys.asSequence()
            .filter { it.wallet.isNotBlank() }
            .groupBy { it.wallet }
            .mapValues { (_, rows) -> rows.sumOf { it.sol } }
        val rankedWalletSol7450 = walletBuySol7450.values.sortedDescending()
        val largestBuyerShare7450 = if (buySol > 0.0)
            ((rankedWalletSol7450.firstOrNull() ?: 0.0) / buySol * 100.0).coerceIn(0.0, 100.0)
        else 0.0
        val top3BuyerShare7450 = if (buySol > 0.0)
            (rankedWalletSol7450.take(3).sum() / buySol * 100.0).coerceIn(0.0, 100.0)
        else 0.0
        val repeatBuyerWallets7450 = buys.asSequence()
            .filter { it.wallet.isNotBlank() }
            .groupingBy { it.wallet }.eachCount().values.count { it >= 2 }

        return LaunchFlow(
            buyTx60s = buys.size,
            sellTx60s = sells.size,
            buySol60s = buySol,
            sellSol60s = sellSol,
            distinctBuyers60s = buys.map { it.wallet }.filter { it.isNotBlank() }.toSet().size,
            devBuyTx60s = if (dev == null) 0 else buys.count { it.wallet == dev },
            devSellTx60s = if (dev == null) 0 else sells.count { it.wallet == dev },
            buyTx15s = r15.count { it.isBuy },
            buyTxPrev15s = prev15.count { it.isBuy },
            accelerationRising = r15.count { it.isBuy } >= 3 && r15.count { it.isBuy } > prev15.count { it.isBuy },
            buySharePct = if (totalSol > 0.0) buySol / totalSol * 100.0 else 50.0,
            largestBuyerSharePct60s = largestBuyerShare7450,
            top3BuyerSharePct60s = top3BuyerShare7450,
            repeatBuyerWallets60s = repeatBuyerWallets7450,
        )
    }

    fun clearToken(mint: String) {
        recentLargeBuys.remove(mint)
        walletBuyCount.remove(mint)
        launchTrades7401.remove(mint)
    }
}

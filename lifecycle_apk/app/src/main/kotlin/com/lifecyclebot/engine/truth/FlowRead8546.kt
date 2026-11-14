package com.lifecyclebot.engine.truth

/**
 * V5.0.8546 §WHO_IS_BUYING — order flow from the coin's own executed swaps (TradeTape8100: PumpPortal / Helius / pool
 * prints, one venue, never price-only marks) plus the tracked proven wallets (SmartMoneyFeed6394, written by the copy
 * engine for every tracked-wallet buy). The old lane rules read price / cap / age snapshots; these read the buyers.
 *
 * Nothing is inferred: a coin with too few executed prints has no flow read ([Flow8546.known] false) and no family that
 * needs flow can fire on it.
 */
object FlowRead8546 {
    private const val MIN_PRINTS_8546 = 6

    data class Flow8546(
        val known: Boolean,
        val buys2m: Int, val sells2m: Int, val buySol2m: Double, val sellSol2m: Double,
        val buys5m: Int, val sells5m: Int, val buySol5m: Double, val sellSol5m: Double,
        /** Mean buy size (SOL) in the last 2 minutes over the 3 before them; NaN without both. */
        val buySizeTrend: Double,
        /** Sells were >= 35 % of the 2-minute SOL and the 2-minute low held above the prior 3-minute low (absorbed). */
        val absorbing: Boolean,
        /** Distinct tracked proven wallets that bought in the last 5 minutes. */
        val smartWallets5m: Int,
    ) {
        val netSol2m: Double get() = buySol2m - sellSol2m
        val buyShareSol2m: Double get() = if (buySol2m + sellSol2m > 0.0) buySol2m / (buySol2m + sellSol2m) else Double.NaN
        val netSol5m: Double get() = buySol5m - sellSol5m
    }

    private val UNKNOWN_8546 = Flow8546(false, 0, 0, 0.0, 0.0, 0, 0, 0.0, 0.0, Double.NaN, false, 0)

    /** Pure: the flow read of [prints] (oldest first) at [nowMs], with [smartWallets] already counted. */
    fun of8546(prints: List<TradeTape8100.Print8128>, nowMs: Long, smartWallets: Int = 0): Flow8546 {
        val exec = prints.filter { !it.priceOnly && it.sol.isFinite() && it.sol > 0.0 && nowMs - it.atMs in 0L..300_000L }
        if (exec.size < MIN_PRINTS_8546) return UNKNOWN_8546.copy(smartWallets5m = smartWallets)
        val last2 = exec.filter { nowMs - it.atMs <= 120_000L }
        val prior3 = exec.filter { nowMs - it.atMs > 120_000L }
        val b2 = last2.filter { it.isBuy }; val s2 = last2.filter { !it.isBuy }
        val b5 = exec.filter { it.isBuy }; val s5 = exec.filter { !it.isBuy }
        val bPrior = prior3.filter { it.isBuy }
        val trend = if (b2.isNotEmpty() && bPrior.isNotEmpty()) b2.map { it.sol }.average() / bPrior.map { it.sol }.average() else Double.NaN
        val sold2 = s2.sumOf { it.sol }; val bought2 = b2.sumOf { it.sol }
        val low2 = last2.filter { it.priceSol > 0.0 }.minOfOrNull { it.priceSol }
        val lowPrior = prior3.filter { it.priceSol > 0.0 }.minOfOrNull { it.priceSol }
        val absorbing = bought2 + sold2 > 0.0 && sold2 / (bought2 + sold2) >= 0.35 && low2 != null && lowPrior != null && low2 > lowPrior
        return Flow8546(true, b2.size, s2.size, bought2, sold2, b5.size, s5.size, b5.sumOf { it.sol }, s5.sumOf { it.sol },
            trend, absorbing, smartWallets)
    }

    /** [mint]'s flow now (tape + tracked wallets). */
    fun read8546(mint: String, nowMs: Long = System.currentTimeMillis()): Flow8546 {
        val prints = try { TradeTape8100.prints8128(mint, nowMs, 300_000L) } catch (_: Throwable) { emptyList() }
        // V5.0.8756 — the wallets with a graded follower record (SmartMoney8756), not every whale CopyTradeEngine saw
        val smart = try { SmartMoney8756.provenBuyers8756(mint, 300_000L, nowMs) } catch (_: Throwable) { 0 }
        return of8546(prints, nowMs, smart)
    }
}

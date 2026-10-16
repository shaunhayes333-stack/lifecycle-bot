package com.lifecyclebot.engine.market

/**
 * V5.0.8169 — what the meme specialist hunters must not spend a pick on.
 *
 * 5.0.8168: BLUECHIP / TREASURY / CASHGEN each took NVDAx (a Backed xStocks tokenised Nvidia share, 685,754 minutes old)
 * and BLUECHIP took jlUSDC (a Jupiter Lend USDC receipt: cap == liquidity == $475M, 1h move +0.0007%) among their twelve
 * picks; the same rows were re-picked every sweep and churned through MARKET_HUNT_7301_REQUEUE_COOLDOWN_*. Neither is a
 * memecoin: a tokenised equity trades on the stock's clock (TokenizedStockTrader's desk), and a pegged / receipt token is
 * engineered not to move (PeggedAssetGuard7270: "no edge to find"). These are structural facts about the instrument, not a
 * strategy threshold. Discovery only: the native lane brains, FDG and every gate still judge whatever reaches them.
 */
object HunterAssetClass8169 {
    enum class Kind { MEME_TRADEABLE, TOKENIZED_EQUITY, PEGGED_OR_RECEIPT }

    /** Backed xStocks tickers: upper-case root (AAPL, BRK.B) with the lower-case "x" suffix (NVDAx, SPYx). */
    private val XSTOCK_SYMBOL = Regex("^[A-Z][A-Z.]{0,6}x$")

    /** A cap this large that does not move at all (1h and 24h) is a peg or a yield receipt, not a coin with a chart. */
    private const val FLAT_MIN_MCAP_USD = 10_000_000.0
    private const val FLAT_MAX_ABS_CHG_1H_PCT = 0.1
    private const val FLAT_MAX_ABS_CHG_24H_PCT = 1.0

    /**
     * Pure. [knownEquityMints] = TokenizedAssetRegistry's verified xStocks / metal mints. [isPegged] = PeggedAssetGuard7270.
     */
    fun kind(mint: String, symbol: String, mcapUsd: Double, chg1hPct: Double, chg24hPct: Double,
             knownEquityMints: Set<String>, isPegged: Boolean): Kind {
        val m = mint.trim()
        val sym = symbol.trim().removePrefix("$")
        if (m.isNotBlank() && m in knownEquityMints) return Kind.TOKENIZED_EQUITY
        if (m.startsWith("Xs") && XSTOCK_SYMBOL.matches(sym)) return Kind.TOKENIZED_EQUITY
        if (isPegged) return Kind.PEGGED_OR_RECEIPT
        val flat1h = chg1hPct.isFinite() && kotlin.math.abs(chg1hPct) < FLAT_MAX_ABS_CHG_1H_PCT
        val flat24h = !chg24hPct.isFinite() || kotlin.math.abs(chg24hPct) < FLAT_MAX_ABS_CHG_24H_PCT
        if (mcapUsd.isFinite() && mcapUsd >= FLAT_MIN_MCAP_USD && flat1h && flat24h) return Kind.PEGGED_OR_RECEIPT
        return Kind.MEME_TRADEABLE
    }

    /** Production wiring of [kind] for one sweep row (registry + peg guard read here, fail-open to MEME_TRADEABLE). */
    fun of(r: MarketSweep7297.Row): Kind {
        val equities = try { com.lifecyclebot.perps.TokenizedAssetRegistry.allMints() } catch (_: Throwable) { emptySet() }
        val pegged = try {
            com.lifecyclebot.engine.truth.PeggedAssetGuard7270.isPegged(r.symbol, r.priceUsd, r.mcapUsd, r.mint)
        } catch (_: Throwable) { false }
        return try { kind(r.mint, r.symbol, r.mcapUsd, r.priceChangeH1Pct, r.priceChangeH24Pct, equities, pegged) } catch (_: Throwable) { Kind.MEME_TRADEABLE }
    }
}

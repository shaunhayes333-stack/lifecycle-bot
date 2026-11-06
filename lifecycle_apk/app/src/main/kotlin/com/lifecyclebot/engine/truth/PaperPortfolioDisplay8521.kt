package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.perps.*

/** Immutable display evidence for the whole PAPER portfolio. Never supplies execution or learning authority. */
object PaperPortfolioDisplay8521 {
    data class Row(
        val positionId: String,
        val mint: String,
        val symbol: String,
        val assetClass: AssetClass,
        val lane: String,
        val entryPriceUsd: Double,
        val quantity: Double,
        val costSol: Double,
        val openedAtMs: Long,
        val markPriceUsd: Double?,
        val markAtMs: Long,
        val pnlPct: Double?,
        val pnlSol: Double?,
    ) {
        val family: String get() = when (assetClass) {
            AssetClass.SOLANA_TOKEN -> "Solana"
            AssetClass.CRYPTO_ALT -> "Crypto"
            AssetClass.STOCK -> "Stocks"
            AssetClass.FOREX -> "Forex"
            AssetClass.COMMODITY -> "Commodities"
            AssetClass.METAL -> "Metals"
            AssetClass.PERPS -> "Perps"
            AssetClass.UNKNOWN -> "Unclassified"
        }
    }

    internal data class OwnerMark(
        val positionId: String, val paper: Boolean, val entryPriceUsd: Double,
        val markPriceUsd: Double, val markAtMs: Long, val pnlPct: Double,
    )

    private fun markAt8521(market: PerpsMarket, price: Double): Long = try {
        PerpsMarketDataFetcher.getCachedPrice(market)?.takeIf {
            price.isFinite() && price > 0.0 && it.price.isFinite() && it.price > 0.0 &&
                kotlin.math.abs(it.price / price - 1.0) <= 0.000001
        }?.lastUpdate ?: 0L
    } catch (_: Exception) { 0L }

    /** Read each owning book once, then join by immutable position ID and mode; symbols are never identity. */
    fun snapshot8521(positions: List<TokenState>, nowMs: Long): List<Row> {
        val marks = HashMap<String, OwnerMark>()
        fun put(id: String, paper: Boolean, entry: Double, price: Double, at: Long, pct: Double) {
            if (paper) marks[id] = OwnerMark(id, true, entry, price, at, pct)
        }
        val families = positions.map { assetClass8521(it) }.toSet()
        if (AssetClass.CRYPTO_ALT in families) try {
            CryptoAltTrader.getAllPositions().filter { it.isPaper && it.closeTime == null }.forEach { p ->
                if (p.hasTrustedMark(nowMs)) put(p.id, p.isPaper, p.entryPrice, p.currentPrice,
                    if (p.isDynamic) p.markUpdatedAtMs else markAt8521(p.market, p.currentPrice), p.getPnlPct())
            }
        } catch (_: Exception) { }
        if (AssetClass.STOCK in families || AssetClass.PERPS in families) try {
            TokenizedStockTrader.getAllPositions().forEach { p ->
                put(p.id, p.isPaper, p.entryPrice, p.currentPrice, markAt8521(p.market, p.currentPrice), p.getPnlPercent())
            }
        } catch (_: Exception) { }
        if (AssetClass.FOREX in families) try {
            ForexTrader.getAllPositions().filter { it.closeTime == null }.forEach { p ->
                put(p.id, p.isPaper, p.entryPrice, p.currentPrice, markAt8521(p.market, p.currentPrice), p.getPnlPercent())
            }
        } catch (_: Exception) { }
        if (AssetClass.COMMODITY in families) try {
            CommoditiesTrader.getAllPositions().filter { it.closeTime == null }.forEach { p ->
                put(p.id, p.isPaper, p.entryPrice, p.currentPrice, markAt8521(p.market, p.currentPrice), p.getPnlPercent())
            }
        } catch (_: Exception) { }
        if (AssetClass.METAL in families) try {
            MetalsTrader.getAllPositions().filter { it.closeTime == null }.forEach { p ->
                put(p.id, p.isPaper, p.entryPrice, p.currentPrice, markAt8521(p.market, p.currentPrice), p.getPnlPercent())
            }
        } catch (_: Exception) { }
        if (AssetClass.PERPS in families) try {
            PerpsExecutionEngine.getActivePositions().forEach { p ->
                put(p.id, p.isPaper, p.entryPrice, p.currentPrice, p.lastUpdateTime, p.getPnlPercent())
            }
        } catch (_: Exception) { }
        for (ts in positions) if (ts.position.isPaperPosition && assetClass8521(ts) == AssetClass.SOLANA_TOKEN) try {
            val truth = com.lifecyclebot.engine.OpenPnlSanity.pricingTruth(ts, "PaperPortfolio8521/${ts.position.positionId}", emit = false)
            if (truth.trusted) put(ts.position.positionId, true, ts.position.entryPrice, ts.ref,
                ts.lastPriceUpdate, truth.pnlPct)
        } catch (_: Exception) { }
        return rows8521(positions, marks, nowMs)
    }

    /**
     * Pure (V5.0.8581): the open-position list the main panel renders. A paper list holding at least one meme position
     * shows its meme positions only, on the live cards; other asset classes live on their own desk screens, as in LIVE.
     * A live list, a mixed list or a paper list without memes is returned unchanged.
     */
    fun memeCardsLikeLive8581(positions: List<TokenState>): List<TokenState> {
        if (positions.isEmpty() || positions.any { !it.position.isPaperPosition }) return positions
        val memes = positions.filter { assetClass8521(it) == AssetClass.SOLANA_TOKEN }
        return if (memes.isEmpty()) positions else memes
    }

    internal fun assetClass8521(ts: TokenState): AssetClass =
        AssetClass.values().firstOrNull { it.tag.equals(ts.position.canonicalAssetClassTag, true) }
            ?: AssetClass.fromLane(ts.position.tradingMode)

    internal fun rows8521(positions: List<TokenState>, marks: Map<String, OwnerMark>, nowMs: Long): List<Row> =
        positions.asSequence().filter { it.position.isPaperPosition && it.position.positionId.isNotBlank() }
            .distinctBy { it.position.positionId }.map { ts ->
                val p = ts.position
                val owner = marks[p.positionId]?.takeIf { it.paper && it.positionId == p.positionId }
                val mark = owner?.markPriceUsd?.takeIf { it.isFinite() && it > 0.0 }
                val sameBasis = owner != null && p.entryPrice.isFinite() && p.entryPrice > 0.0 &&
                    owner.entryPriceUsd.isFinite() && kotlin.math.abs(owner.entryPriceUsd / p.entryPrice - 1.0) <= 0.000001
                val fresh = owner != null && owner.markAtMs > 0L && nowMs - owner.markAtMs in 0L..60_000L
                val pct = owner?.pnlPct?.takeIf { sameBasis && fresh && mark != null && it.isFinite() }
                // The owner supplies signed/leverage-adjusted return; canonical remaining capital supplies size.
                // A restored native row may still carry its pre-partial original size.
                val pnlSol = pct?.takeIf { p.costSol.isFinite() && p.costSol > 0.0 }
                    ?.let { p.costSol * it / 100.0 }?.takeIf { it.isFinite() }
                Row(p.positionId, ts.mint, ts.symbol.ifBlank { ts.mint.take(12) }, assetClass8521(ts), p.tradingMode,
                    p.entryPrice, p.qtyToken, p.costSol, p.entryTime, mark, owner?.markAtMs ?: 0L,
                    pct, pnlSol)
            }.sortedWith(compareByDescending<Row> { it.pnlPct ?: Double.NEGATIVE_INFINITY }
                .thenByDescending { it.openedAtMs }).toList()
}

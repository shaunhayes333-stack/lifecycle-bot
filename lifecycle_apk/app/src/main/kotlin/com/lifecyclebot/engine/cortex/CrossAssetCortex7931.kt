package com.lifecyclebot.engine.cortex

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.AssetClass
import com.lifecyclebot.engine.truth.CanonicalAssetEntryCandidate6551
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.7931 — the crypto universe and Markets (stocks, forex, metals, commodities,
 * perps) inside the Cortex.
 *
 * Every cross-asset entry already passes CanonicalEntryAuthority6551.submit, yet none
 * of it was observed for forward labels, none of it met the live edge gate, and its
 * realised closes reached Cortex7885.onCanonicalClose with no entry read to match
 * (paper rows keyed "solana|<mint>" or the ticker, live rows the traded mint). This
 * bridge gives each decision one identity, a TokenState snapshot the voters can read,
 * its own lane (never a meme lane, never the memecoin GLOBAL prior), and a price the
 * forward labeler can mark it with.
 */
object CrossAssetCortex7931 {
    private const val PRICE_MAX_AGE_MS = 120_000L

    /** Off-chain markets have no pool; a deep notional keeps the labeler's impact cost at the venue fee. */
    private const val OFF_CHAIN_LIQUIDITY_USD = 10_000_000.0

    private val marketsBySymbol: Map<String, com.lifecyclebot.perps.PerpsMarket> by lazy {
        com.lifecyclebot.perps.PerpsMarket.values().associateBy { it.symbol.uppercase() }
    }

    private data class Asset(val assetClass: AssetClass, val symbol: String, val dynKey: String, val atMs: Long)
    private val assets = ConcurrentHashMap<String, Asset>()

    /** Pure: the Cortex lane of an asset class (null = not cross-asset). */
    fun laneFor(c: AssetClass): String? = when (c) {
        AssetClass.CRYPTO_ALT -> "CRYPTO_ALT"
        AssetClass.STOCK -> "MKT_STOCK"
        AssetClass.FOREX -> "MKT_FOREX"
        AssetClass.COMMODITY -> "MKT_COMMODITY"
        AssetClass.METAL -> "MKT_METAL"
        AssetClass.PERPS -> "MKT_PERPS"
        else -> null
    }

    fun isCrossAssetLane(lane: String): Boolean {
        val l = lane.substringBefore('@').uppercase()
        return l == "CRYPTO_ALT" || l.startsWith("MKT_")
    }

    /**
     * Pure: one identity for paper and live. Solana assets use the raw mint
     * ("solana|<mint>" is stripped); Markets use the traded SPL mint when the
     * symbol has a route, else the ticker.
     */
    fun keyOf(assetId: String, symbol: String, routedMint: String?): String {
        val raw = if (assetId.startsWith("solana|", true)) assetId.substringAfter('|') else assetId
        return when {
            !routedMint.isNullOrBlank() -> routedMint
            raw.contains('|') -> raw.substringAfterLast('|')
            raw.isNotBlank() -> raw
            else -> symbol.uppercase()
        }
    }

    private fun routedMint(c: AssetClass, symbol: String): String? =
        if (c.isOffChainMarket) try { com.lifecyclebot.perps.TokenizedAssetRegistry.mintFor(symbol) } catch (_: Throwable) { null } else null

    /** The close side: map a canonical row's mint onto the captured identity. */
    fun normalizeCloseKey(mint: String): String {
        if (mint.isBlank()) return mint
        if (mint.startsWith("solana|", true)) return mint.substringAfter('|')
        if (assets.containsKey(mint)) return mint
        val routed = try { com.lifecyclebot.perps.TokenizedAssetRegistry.mintFor(mint) } catch (_: Throwable) { null }
        return if (!routed.isNullOrBlank() && assets.containsKey(routed)) routed else mint
    }

    /** A fresh TokenState snapshot of the candidate for the labeler and the voters. */
    private fun tokenStateFor(c: CanonicalAssetEntryCandidate6551, nowMs: Long = System.currentTimeMillis()): Pair<TokenState, String>? {
        val lane = laneFor(c.assetClass) ?: return null
        // The labeler grades longs; a short would be learned upside down.
        if (!(c.direction.isBlank() || c.direction.equals("LONG", true) || c.direction.equals("BUY", true))) return null
        if (!c.price.isFinite() || c.price <= 0.0) return null
        val key = keyOf(c.assetId, c.symbol, routedMint(c.assetClass, c.symbol))
        if (key.isBlank()) return null
        if (assets.size > 4_000) {
            assets.entries.sortedBy { it.value.atMs }.take(assets.size - 3_000).forEach { assets.remove(it.key, it.value) }
        }
        assets[key] = Asset(c.assetClass, c.symbol, c.assetId, nowMs)
        val ts = TokenState(mint = key, symbol = c.symbol, name = c.symbol).apply {
            lastPrice = c.price
            lastPriceUpdate = nowMs
            source = "XASSET_${c.assetClass.tag}_${c.source}".take(64)
            addedToWatchlistAt = 0L
            entryScore = if (c.score.isFinite()) c.score else 0.0
            lastLiquidityUsd = when {
                c.liquidityUsd.isFinite() && c.liquidityUsd > 0.0 -> c.liquidityUsd
                c.assetClass.isOffChainMarket || c.assetClass == AssetClass.PERPS -> OFF_CHAIN_LIQUIDITY_USD
                else -> 0.0
            }
        }
        return ts to lane
    }

    /** ForwardReturnLabeler7731.tick fallback: a fresh price for a cross-asset identity, else null. */
    fun priceFor(key: String, nowMs: Long = System.currentTimeMillis()): Double? {
        val a = assets[key] ?: return null
        return try {
            if (a.assetClass == AssetClass.CRYPTO_ALT || a.assetClass == AssetClass.SOLANA_TOKEN) {
                val m = com.lifecyclebot.perps.DynamicAltTokenRegistry.heldMarkSnapshot7251(a.dynKey.ifBlank { key })
                    .takeIf { it.freshObservation && it.price > 0.0 }
                    ?: com.lifecyclebot.perps.DynamicAltTokenRegistry.heldMarkSnapshot7251(key).takeIf { it.freshObservation && it.price > 0.0 }
                m?.price ?: perpsPrice(a.symbol, nowMs)
            } else perpsPrice(a.symbol, nowMs)
        } catch (_: Throwable) { null }
    }

    private fun perpsPrice(symbol: String, nowMs: Long): Double? {
        val market = marketsBySymbol[symbol.uppercase()] ?: return null
        val d = com.lifecyclebot.perps.PerpsMarketDataFetcher.getCachedPrice(market) ?: return null
        return d.price.takeIf { it.isFinite() && it > 0.0 && nowMs - d.lastUpdate <= PRICE_MAX_AGE_MS }
    }

    /**
     * CanonicalEntryAuthority6551.submit: observe this decision (admitted or refused)
     * for its forward label and Cortex capture.
     */
    fun observe(c: CanonicalAssetEntryCandidate6551, admitted: Boolean, reason: String?) {
        val (ts, lane) = tokenStateFor(c) ?: return
        try { com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.observe(ts, lane, admitted, reason) } catch (_: Throwable) {}
    }

    /** CanonicalEntryAuthority6551.submit: the live edge gate for a cross-asset entry (null = admit). */
    fun liveRefusal(c: CanonicalAssetEntryCandidate6551): String? {
        val paper = c.mode.equals("PAPER", true)
        val (ts, lane) = tokenStateFor(c) ?: return null
        return try { com.lifecyclebot.engine.truth.LiveEdgeGate7877.liveRefusal(ts, lane, paper) } catch (_: Throwable) { null }
    }

    fun statusLine(): String = "tracked=${assets.size} byClass=" +
        assets.values.groupingBy { it.assetClass.tag }.eachCount().entries.joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "-" }
}

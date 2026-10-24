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

    private data class Asset(val assetClass: AssetClass, val symbol: String, val dynKey: String, val atMs: Long,
                             val nativePriceProvenance8372: Boolean = false)
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

    /**
     * V5.0.8107 §CRYPTO_LANE_PARITY (phase 1). Owner: the crypto trader is meant to run the full meme-trader stack over the
     * rest of the crypto universe. Every CRYPTO_ALT label and grade fell in ONE lane book (8098: CRYPTO_ALT n1059 -7.0%,
     * Cortex STRONG -7.4%), so no crypto lane could ever prove itself apart from the others. A crypto candidate the desk
     * elected to a lane (CryptoLaneDesk7391: MOONSHOT, SHITCOIN, QUALITY, BLUECHIP ...) now learns as CRYPTO_<LANE>.
     * Learning only: crypto entries, sizing and exits are unchanged.
     */
    private val CRYPTO_DESK_LANES_8107 = setOf("CORE", "EXPRESS", "DIP_HUNTER", "TREASURY", "CASHGEN", "QUALITY", "BLUECHIP", "SHITCOIN", "MOONSHOT",
        "PROJECT_SNIPER", "CYCLIC", "MANIPULATED")

    /** Pure: the learning lane of a cross-asset candidate — CRYPTO_<desk lane> when the crypto desk elected one. */
    fun laneFor8107(c: AssetClass, deskLane: String?): String? {
        val d = deskLane?.trim()?.uppercase().orEmpty()
        return if (c == AssetClass.CRYPTO_ALT && d in CRYPTO_DESK_LANES_8107) "CRYPTO_$d" else laneFor(c)
    }

    fun isCrossAssetLane(lane: String): Boolean {
        val l = lane.substringBefore('@').uppercase()
        // V5.0.7962 — the multi-chain meme lanes (EVM_BSC, EVM_BASE, ...) learn in their own lanes too.
        // V5.0.8107 — and every crypto desk lane (CRYPTO_<LANE>).
        return l == "CRYPTO_ALT" || l.startsWith("MKT_") || l.startsWith("EVM_") ||
            (l.startsWith("CRYPTO_") && l.removePrefix("CRYPTO_") in CRYPTO_DESK_LANES_8107)
    }

    /**
     * Pure: one identity for paper and live. Solana assets use the raw mint
     * ("solana|<mint>" is stripped); Markets use the traded SPL mint when the
     * symbol has a route, else the ticker.
     */
    fun keyOf(assetId: String, symbol: String, routedMint: String?): String {
        return com.lifecyclebot.engine.NativeDecisionEnrollment8372.learningIdentity(assetId, symbol, routedMint)
    }

    private fun routedMint(c: AssetClass, symbol: String): String? =
        if (c.isOffChainMarket) try { com.lifecyclebot.perps.TokenizedAssetRegistry.mintFor(symbol) } catch (_: Throwable) { null } else null

    /** Freeze this at entry sealing; later confirmed fills must not re-infer an alias from a ticker. */
    internal fun learningIdentity8372(c: CanonicalAssetEntryCandidate6551): String =
        keyOf(c.assetId, c.symbol, routedMint(c.assetClass, c.symbol))

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
        val lane = laneFor8107(c.assetClass, c.evidence["deskLane7391"]) ?: return null
        // The labeler grades longs; a short would be learned upside down.
        if (!(c.direction.isBlank() || c.direction.equals("LONG", true) || c.direction.equals("BUY", true))) return null
        if (!c.price.isFinite() || c.price <= 0.0) return null
        val key = learningIdentity8372(c)
        if (key.isBlank()) return null
        // V5.0.8063 — owner: "crypto has meme coins, stable coins, utility coins the works". Every CRYPTO_ALT label fell
        // in ONE cell (XASSET_CRYPTO_ALT_CRYPTO|CRYPTO_ALT|MC_UNKNOWN|AGE_UNKNOWN, n343): no type, cap or age. The
        // registry's facts now give each coin its kind, its cap tier and its real age; stablecoins are not graded.
        val dyn8063 = if (c.assetClass == AssetClass.CRYPTO_ALT) try {
            com.lifecyclebot.perps.DynamicAltTokenRegistry.getTokenByMint(key)
                ?: com.lifecyclebot.perps.DynamicAltTokenRegistry.getTokenByCanonicalIdentity6544(c.assetId)
        } catch (_: Throwable) { null } else null
        val mcap8063 = dyn8063?.let { if (it.mcap > 0.0) it.mcap else it.fdv } ?: 0.0
        if (c.assetClass == AssetClass.CRYPTO_ALT && (try {
                com.lifecyclebot.engine.truth.PeggedAssetGuard7270.isPegged(c.symbol, c.price, mcap8063, key)
            } catch (_: Throwable) { false })) { stablesSkipped8063.incrementAndGet(); return null }
        if (assets.size > 4_000) {
            assets.entries.sortedBy { it.value.atMs }.take(assets.size - 3_000).forEach { assets.remove(it.key, it.value) }
        }
        // An actual admission may follow a native observation; it must not weaken that episode's mark provenance.
        assets[key] = Asset(c.assetClass, c.symbol, c.assetId, nowMs,
            nativePriceProvenance8372 = assets[key]?.nativePriceProvenance8372 == true)
        val ts = TokenState(mint = key, symbol = c.symbol, name = c.symbol).apply {
            lastPrice = c.price
            lastPriceUpdate = nowMs
            source = if (c.assetClass == AssetClass.CRYPTO_ALT)
                "XC_${cryptoKind8063(c.symbol, dyn8063?.sector.orEmpty(), key)}_${capTier8063(mcap8063)}"
            else "XASSET_${c.assetClass.tag}_${c.source}".take(64)
            // V5.0.8063 — the coin's real age (pair creation) so the cell's age band means something; unknown stays unknown.
            addedToWatchlistAt = dyn8063?.pairCreatedAtMs?.takeIf { it in 1L..nowMs } ?: 0L
            if (mcap8063 > 0.0) lastMcap = mcap8063
            entryScore = if (c.score.isFinite()) c.score else 0.0
            lastLiquidityUsd = when {
                c.liquidityUsd.isFinite() && c.liquidityUsd > 0.0 -> c.liquidityUsd
                c.assetClass.isOffChainMarket || c.assetClass == AssetClass.PERPS -> OFF_CHAIN_LIQUIDITY_USD
                else -> 0.0
            }
        }
        return ts to lane
    }

    private val stablesSkipped8063 = java.util.concurrent.atomic.AtomicLong(0)

    /** Pure (V5.0.8063): what kind of crypto coin this is — MEME / MAJOR / WRAPPED / DEFI / AI / GAMING / L1 / INFRA / OTHER. */
    fun cryptoKind8063(symbol: String, sector: String, mint: String): String {
        val sym = symbol.trim().uppercase(); val sec = sector.trim().uppercase()
        return when {
            sym in setOf("WBTC", "WETH", "CBBTC", "TBTC") || sym.endsWith("SOL") && sym.length in 4..8 && sym != "SOL" -> "WRAPPED"
            sym in setOf("BTC", "ETH", "SOL", "BNB", "XRP", "ADA", "AVAX", "TRX", "DOT", "LINK", "TON", "LTC", "BCH", "SUI") -> "MAJOR"
            sec.contains("MEME") || mint.endsWith("pump", true) || mint.endsWith("bonk", true) -> "MEME"
            sec.contains("DEFI") || sec.contains("DEX") || sec.contains("LEND") -> "DEFI"
            sec.split(Regex("[^A-Z0-9]+")).any { it == "AI" } || sec.contains("ARTIFICIAL") || sec.contains("AGENT") -> "AI"
            sec.contains("GAM") || sec.contains("NFT") || sec.contains("META") -> "GAMING"
            sec.contains("L1") || sec.contains("L2") || sec.contains("LAYER") || sec.contains("CHAIN") -> "L1"
            sec.contains("INFRA") || sec.contains("ORACLE") || sec.contains("STORAGE") || sec.contains("DEPIN") -> "INFRA"
            else -> "OTHER"
        }
    }

    /** Pure (V5.0.8063): crypto cap tier above the meme bands (the shared bands stop at "over $1M"). */
    fun capTier8063(mcapUsd: Double): String = when {
        !mcapUsd.isFinite() || mcapUsd <= 0.0 -> "CAPU"
        mcapUsd < 10_000_000.0 -> "CAP_LT10M"
        mcapUsd < 100_000_000.0 -> "CAP_10M_100M"
        mcapUsd < 1_000_000_000.0 -> "CAP_100M_1B"
        else -> "CAP_GT1B"
    }

    /** ForwardReturnLabeler7731.tick fallback: a fresh price for a cross-asset identity, else null. */
    fun priceFor(key: String, nowMs: Long = System.currentTimeMillis()): Double? {
        // V5.0.7962 — multi-chain meme labels are marked from that lane's own pair prices.
        val a = assets[key] ?: return com.lifecyclebot.perps.crypto.MultiChainMemeLane7962.freshMark7962(key, nowMs)
        return try {
            if (a.nativePriceProvenance8372) return com.lifecyclebot.perps.DynamicAltTokenRegistry
                .learningMark8372(a.dynKey, nowMs)?.price
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

    /** Native crypto desk YES/NO before owner selection, in its exact crypto lane; observation only, never a fill. */
    internal fun observeNativeLong8372(ts: TokenState, deskLane: String, admitted: Boolean, reason: String,
                                       nowMs: Long, score: Int): Boolean {
        val rules = com.lifecyclebot.engine.NativeDecisionEnrollment8372
        if (!rules.isCryptoDesk(ts.lastPriceSource)) return false
        val lane = rules.cryptoLearningLane(deskLane) ?: return false
        if (rules.observedPriceAt(ts.lastPrice, ts.lastPrice, ts.lastPriceUpdate, nowMs) <= 0L) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_NATIVE_PRICE_PROVENANCE_MISSING_8372") } catch (_: Throwable) {}
            return false
        }
        val key = keyOf(ts.mint, ts.symbol, null)
        if (key.isBlank()) return false
        if (com.lifecyclebot.engine.truth.PeggedAssetGuard7270.isPegged(ts.symbol, ts.lastPrice, ts.lastMcap, key)) return false
        if (assets.size > 4_000) assets.entries.sortedBy { it.value.atMs }.take(assets.size - 3_000)
            .forEach { assets.remove(it.key, it.value) }
        assets[key] = Asset(AssetClass.CRYPTO_ALT, ts.symbol, ts.mint, nowMs, nativePriceProvenance8372 = true)
        com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.observe(
            if (key == ts.mint) ts else ts.copy(mint = key), lane, admitted, reason, nowMs, score)
        return true
    }

    /** CanonicalEntryAuthority6551.submit: the live edge gate for a cross-asset entry (null = admit). */
    fun liveRefusal(c: CanonicalAssetEntryCandidate6551): String? {
        val paper = c.mode.equals("PAPER", true)
        val (ts, lane) = tokenStateFor(c) ?: return null
        return try { com.lifecyclebot.engine.truth.LiveEdgeGate7877.liveRefusal(ts, lane, paper) } catch (_: Throwable) { null }
    }

    fun statusLine(): String = "tracked=${assets.size} stablesSkipped8063=${stablesSkipped8063.get()} byClass=" +
        assets.values.groupingBy { it.assetClass.tag }.eachCount().entries.joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "-" } +
        // V5.0.7962 — everything but SOL: per chain gas, live/shadow, candidates, labels, cell EV (own diag line;
        // carried here because PipelineHealthCollector.dumpText is a pinned method that may only shrink).
        "\n  Multi-chain lane (§7962):    " +
        (try { com.lifecyclebot.perps.crypto.MultiChainMemeLane7962.statusLine() } catch (_: Throwable) { "unavailable" })
}

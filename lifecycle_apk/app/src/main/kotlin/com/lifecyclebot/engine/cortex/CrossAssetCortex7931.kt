package com.lifecyclebot.engine.cortex

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.TokenFacts
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

    /** Only identities belonging to retained observations; marks remain subject to their original freshness checks. */
    internal fun checkpointPriceIdentities8402(keys: Set<String>): String = org.json.JSONArray().also { rows ->
        keys.forEach { key -> assets[key]?.let { a ->
            rows.put(org.json.JSONArray(listOf(key, a.assetClass.name, a.symbol, a.dynKey, a.atMs, a.nativePriceProvenance8372)))
        } }
    }.toString()

    internal fun restorePriceIdentities8402(raw: String?) {
        if (raw.isNullOrBlank()) return
        val rows = org.json.JSONArray(raw)
        require(rows.length() <= 2000) { "oversized price identity checkpoint" }
        for (i in 0 until rows.length()) {
            val r = rows.getJSONArray(i)
            val key = r.getString(0)
            val restored = Asset(AssetClass.valueOf(r.getString(1)), r.getString(2), r.getString(3), r.getLong(4), r.getBoolean(5))
            if (key.isNotBlank()) assets.compute(key) { _, current ->
                if (current == null || current.atMs < restored.atMs) restored else current
            }
        }
    }

    private fun prunePriceIdentities8402() {
        if (assets.size <= 4000) return
        val retained = com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.pendingPriceKeys8402()
        assets.entries.asSequence().filterNot { it.key in retained }.sortedBy { it.value.atMs }
            .take((assets.size - 3000).coerceAtLeast(0)).toList().forEach { assets.remove(it.key, it.value) }
    }

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

    /**
     * V5.0.8849 — the canonical execution owner can remain CRYPTO, but its
     * predictive oracle must use the *elected desk's* learning lane. Before
     * this, all crypto signals were forecast as untrained generic "CRYPTO"
     * even while their outcome labels went to CRYPTO_MOONSHOT, CRYPTO_CORE,
     * etc. Keep the sealed intent/dispatch owner unchanged.
     */
    fun forecastLane8849(c: CanonicalAssetEntryCandidate6551): String =
        if (c.assetClass == AssetClass.CRYPTO_ALT)
            laneFor8107(c.assetClass, c.evidence["deskLane7391"]) ?: c.assetClass.tag
        else c.specialist.ifBlank { c.assetClass.tag }

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

    /**
     * V5.0.8834 — supply the canonical crypto decision with the same, source-
     * dated evidence that CryptoBrain and CryptoLaneDesk used to elect it.
     * The 8833 registry repair preserved this data, but tokenStateFor below
     * rebuilt a price/market-cap-only TokenState at the final live gate. That
     * made observed buyers, 5m movement and true price structure disappear
     * just before LiveEdgeGate7877/CandidateAdmission8136 read them.
     *
     * A registry write time, 24h aggregate or cached scan cannot manufacture
     * a current reading. Unknown/stale remains unobserved; no buy authority,
     * risk threshold or source identity is changed by this bridge.
     * V5.0.8835: TokenFacts owns every price, liquidity and snapshot write;
     * the 8834 direct field mirrors violated its single-writer contract.
     */
    internal fun bridgeCryptoObservedContext8834(
        ts: TokenState,
        dyn: com.lifecyclebot.perps.DynamicAltTokenRegistry.DynToken,
        candidatePrice: Double,
        candidateLiquidity: Double,
        candidateMcap: Double,
        nowMs: Long,
        candidateObservedAtMs8870: Long = 0L,
        candidatePriceSource8870: String = "",
        candidatePriceAssetId8870: String = "",
        maxPriceAgeMs8870: Long = PRICE_MAX_AGE_MS,
    ) {
        val facts = TokenFacts
        val rank = TokenFacts.Rank
        val priceAt = candidateObservationAt8870(ts.mint, candidatePrice,
            candidatePriceAssetId8870, candidateObservedAtMs8870, candidatePriceSource8870,
            dyn.canonicalIdentity6544, dyn.price, dyn.priceObservedAtMs8372, nowMs, maxPriceAgeMs8870)
        if (priceAt > 0L) {
            facts.writePrice(ts, candidatePrice,
                com.lifecyclebot.engine.NativeDecisionEnrollment8372.CRYPTO_DESK_PRICE_SOURCE,
                rank.PROVIDER, priceAt, nowMs)
        } else {
            facts.restorePrice(ts, candidatePrice, "")
        }
        // Canonical candidates provide liquidity and registry cap as values,
        // not independent observation clocks. Their mirrors are RESTORE facts,
        // never freshly stamped provider reads at the gate's wall-clock time.
        facts.restoreLiquidity(ts, candidateLiquidity, "CRYPTO_CANDIDATE_UNCLOCKED_8835")
        facts.restoreMcap(ts, candidateMcap)

        val recent = com.lifecyclebot.perps.CryptoShortWindow8832.from(
            nowMs, dyn.shortWindowObservedAtMs8832, dyn.buys5m8832, dyn.sells5m8832,
            dyn.volume5m8832, dyn.change5mPct8832,
        )
        if (recent.flowObserved) {
            facts.writeSnapshot(ts, TokenFacts.Field.BUY_PRESSURE_PCT, recent.buyPressurePct,
                "DEX_PAIR_M5_CRYPTO_8835", rank.PROVIDER, dyn.shortWindowObservedAtMs8832, nowMs)
            facts.writeSnapshot(ts, TokenFacts.Field.SELL_PRESSURE_PCT, 100.0 - recent.buyPressurePct,
                "DEX_PAIR_M5_CRYPTO_8835", rank.PROVIDER, dyn.shortWindowObservedAtMs8832, nowMs)
        }
        recent.change5mPct?.let { change ->
            facts.writeSnapshot(ts, TokenFacts.Field.CHANGE_5M_PCT, change,
                "DEX_PAIR_M5_CRYPTO_8835", rank.PROVIDER, dyn.shortWindowObservedAtMs8832, nowMs)
        }
        // V5.0.8980 — the pool's own provider-dated hour change (DexScreener / GeckoTerminal h1, CryptoTrend8886's read) is
        // the hour a crypto coin is judged on. Without it a crypto TokenState read its hour as the 0.0 default, counted as
        // observed through the five-minute stamp (LanePlaybook7907.chgObs8084): every chg1h setup was graded on a flat hour,
        // and the expected move had no hour, only the desk's own candles (none for 15 minutes after a start).
        if (priceAt > 0L && cryptoHourReadable8980(dyn.change1hPct8886, dyn.trendObservedAtMs8886, nowMs)) {
            if (facts.writeSnapshot(ts, TokenFacts.Field.CHANGE_1H_PCT, dyn.change1hPct8886,
                    "DEX_PAIR_H1_CRYPTO_8980", rank.PROVIDER, dyn.trendObservedAtMs8886, nowMs)) hourBridged8980.incrementAndGet()
            else hourRefused8980.incrementAndGet()
        } else hourUnread8980.incrementAndGet()
        ts.tokenMap.volume5mUsd = recent.volume5mUsd
        ts.tokenMap.volume24hUsd = dyn.volume24h.takeIf { it.isFinite() && it > 0.0 }

        // Only actual observed source ticks can form history. Keep their
        // original timestamps and never include a tick newer than this
        // candidate's own validated entry price.
        if (priceAt > 0L) {
            ts.history.addAll(
                com.lifecyclebot.perps.CryptoLaneDesk7391.observedCandles8834(dyn.canonicalIdentity6544)
                    .filter { it.ts in 1L..priceAt },
            )
        }
    }

    // ── V5.0.8980 — the crypto hour ──
    private val hourBridged8980 = java.util.concurrent.atomic.AtomicLong(0L)
    private val hourRefused8980 = java.util.concurrent.atomic.AtomicLong(0L)
    private val hourUnread8980 = java.util.concurrent.atomic.AtomicLong(0L)

    /** Pure: is a provider's hour change [chgPct] read at [observedAtMs] a usable hour now (finite, above -100 %, under 15 min old)? */
    internal fun cryptoHourReadable8980(chgPct: Double, observedAtMs: Long, nowMs: Long): Boolean =
        chgPct.isFinite() && chgPct > -100.0 && observedAtMs > 0L &&
            nowMs - observedAtMs in 0L..LanePlaybook7907.OBS_FRESH_MS_8084

    /** Brief: crypto candidates whose hour came from their pool's provider-dated h1, and those with none to read. */
    fun hourLine8980(): String =
        "cryptoHour8980[bridged=${hourBridged8980.get()} refused=${hourRefused8980.get()} unread=${hourUnread8980.get()} " +
            "rule=a crypto candidate's hour is its pool's provider-dated h1 when it is under 15 min old; otherwise its desk candles alone]"

    /** A source-dated nomination stays dated when the live registry advances to its next tick. */
    internal fun candidateObservationAt8870(assetId: String, price: Double,
                                           observedAssetId: String, observedAtMs: Long, observedSource: String,
                                           registryAssetId: String, registryPrice: Double, registryObservedAtMs: Long,
                                           nowMs: Long, maxAgeMs: Long = PRICE_MAX_AGE_MS): Long {
        val identity = com.lifecyclebot.perps.DynamicAltTokenRegistry
        if (observedAtMs != 0L || observedSource.isNotBlank() || observedAssetId.isNotBlank()) {
            if (observedSource.isBlank() || !identity.sameAsset8866(assetId, observedAssetId)) return 0L
            return com.lifecyclebot.engine.NativeDecisionEnrollment8372.observedPriceAt(
                price, price, observedAtMs, nowMs, maxAgeMs)
        }
        // Legacy callers can use only the exact registry price with its original clock and identity.
        if (!identity.sameAsset8866(assetId, registryAssetId)) return 0L
        return com.lifecyclebot.engine.NativeDecisionEnrollment8372.observedPriceAt(
            price, registryPrice, registryObservedAtMs, nowMs, maxAgeMs)
    }

    /** Read the existing same-mint liquidity fact instead of discarding its provenance at the desk boundary. */
    internal fun bridgeSharedLiquidity8870(ts: TokenState, shared: TokenState?, nowMs: Long, ageMin: Double): Boolean {
        if (shared == null) return false
        val mint = com.lifecyclebot.perps.DynamicAltTokenRegistry.solanaMintOf8866(ts.mint) ?: return false
        if (com.lifecyclebot.perps.DynamicAltTokenRegistry.solanaMintOf8866(shared.mint) != mint) return false
        val fact = TokenFacts.fact(shared, TokenFacts.Field.LIQUIDITY_USD) ?: return false
        if (fact.rank <= TokenFacts.Rank.RESTORE || fact.source.isBlank()) return false
        val sourceMaxAge = com.lifecyclebot.engine.truth.SetupFeatureResolver8169.maxEvidenceAge8170(ageMin)
        val maxAge = if (fact.rank >= TokenFacts.Rank.CHAIN) minOf(sourceMaxAge, 90_000L) else sourceMaxAge
        if (com.lifecyclebot.engine.NativeDecisionEnrollment8372.priceAge8870(fact.observedAtMs, nowMs) > maxAge) return false
        return TokenFacts.writeLiquidity(ts, fact.value, fact.source, fact.rank, fact.observedAtMs, nowMs)
    }

    /** The close side: map a canonical row's mint onto the captured identity. */
    fun normalizeCloseKey(mint: String): String {
        if (mint.isBlank()) return mint
        if (mint.startsWith("solana|", true)) return mint.substringAfter('|')
        if (assets.containsKey(mint)) return mint
        val routed = try { com.lifecyclebot.perps.TokenizedAssetRegistry.mintFor(mint) } catch (_: Throwable) { null }
        if (!routed.isNullOrBlank() && assets.containsKey(routed)) return routed
        // V5.0.8418 §EVM_KEY_MIGRATION — 8372 keyed EVM identities "chain|0xabc" (was "0xabc"). A close row that still carries the
        // bare address binds to the one captured chain-qualified identity instead of being orphaned (and vice versa).
        return migratedKey8418(mint, assets.keys) ?: mint
    }

    /**
     * Pure (V5.0.8418): the one captured key [mint] names across the 8372 EVM key change — a bare "0x.." address matches exactly
     * one "<chain>|0x.." key; a "<chain>|0x.." key matches a captured bare "0x..". Null when none or ambiguous (two chains).
     */
    internal fun migratedKey8418(mint: String, keys: Collection<String>): String? {
        if (mint.isBlank()) return null
        if (!mint.contains('|')) {
            if (!mint.startsWith("0x", true)) return null
            val hits = keys.filter { it.contains('|') && it.substringAfterLast('|').equals(mint, true) }
            return hits.singleOrNull()
        }
        val bare = mint.substringAfterLast('|')
        return if (bare.startsWith("0x", true)) keys.firstOrNull { it.equals(bare, true) } else null
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
        prunePriceIdentities8402()
        // An actual admission may follow a native observation; it must not weaken that episode's mark provenance.
        assets[key] = Asset(c.assetClass, c.symbol, c.assetId, nowMs,
            nativePriceProvenance8372 = assets[key]?.nativePriceProvenance8372 == true)
        val ts = TokenState(mint = key, symbol = c.symbol, name = c.symbol).apply {
            // The crypto registry's original observation clock is applied by
            // TokenFacts below; never seed a false now-clock that can reject it.
            if (c.assetClass != AssetClass.CRYPTO_ALT) {
                lastPrice = c.price
                lastPriceUpdate = nowMs
            } else if (dyn8063 == null) {
                val observedAt8870 = candidateObservationAt8870(key, c.price, c.priceAssetId8870,
                    c.priceObservedAtMs8870, c.priceSource8870, "", 0.0, 0L, nowMs)
                if (observedAt8870 > 0L) TokenFacts.writePrice(this, c.price,
                    com.lifecyclebot.engine.NativeDecisionEnrollment8372.CRYPTO_DESK_PRICE_SOURCE,
                    TokenFacts.Rank.PROVIDER, observedAt8870, nowMs)
                else TokenFacts.restorePrice(this, c.price, "CRYPTO_UNDATED_8870")
            }
            source = if (c.assetClass == AssetClass.CRYPTO_ALT)
                "XC_${cryptoKind8063(c.symbol, dyn8063?.sector.orEmpty(), key)}_${capTier8063(mcap8063)}"
            else "XASSET_${c.assetClass.tag}_${c.source}".take(64)
            // V5.0.8063 — the coin's real age (pair creation) so the cell's age band means something; unknown stays unknown.
            addedToWatchlistAt = dyn8063?.pairCreatedAtMs?.takeIf { it in 1L..nowMs } ?: 0L
            if (mcap8063 > 0.0 && (c.assetClass != AssetClass.CRYPTO_ALT || dyn8063 == null))
                lastMcap = mcap8063
            entryScore = if (c.score.isFinite()) c.score else 0.0
            if (c.assetClass != AssetClass.CRYPTO_ALT || dyn8063 == null) {
                lastLiquidityUsd = when {
                    c.liquidityUsd.isFinite() && c.liquidityUsd > 0.0 -> c.liquidityUsd
                    c.assetClass.isOffChainMarket || c.assetClass == AssetClass.PERPS -> OFF_CHAIN_LIQUIDITY_USD
                    else -> 0.0
                }
            }
        }
        if (c.assetClass == AssetClass.CRYPTO_ALT && dyn8063 != null) {
            bridgeCryptoObservedContext8834(ts, dyn8063, c.price, c.liquidityUsd, mcap8063, nowMs,
                c.priceObservedAtMs8870, c.priceSource8870, c.priceAssetId8870)
            bridgeSharedLiquidity8870(ts,
                try { val rows = com.lifecyclebot.engine.BotService.status.tokens
                    synchronized(rows) { rows[key] } } catch (_: Throwable) { null },
                nowMs, dyn8063.discoveryAgeHours6544 * 60.0)
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc(
                    if (ts.lastPriceUpdate > 0L) "CRYPTO_CANONICAL_PRICE_PROVEN_8834"
                    else "CRYPTO_CANONICAL_PRICE_UNOBSERVED_8834",
                )
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc(
                    if (ts.buyPressureObservedAtMs8084 > 0L) "CRYPTO_CANONICAL_FLOW_PROVEN_8834"
                    else "CRYPTO_CANONICAL_FLOW_UNOBSERVED_8834",
                )
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc(
                    if (ts.history.isNotEmpty()) "CRYPTO_CANONICAL_CANDLES_PROVEN_8834"
                    else "CRYPTO_CANONICAL_CANDLES_UNOBSERVED_8834",
                )
            } catch (_: Throwable) {}
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

    /**
     * V5.0.8571 — [priceFor] with the observation's own time (price to observedAtMs), for the forward labeler's typed mark.
     * The labeler took the untimed price only when a TokenState row of the same identity held the same price; a cross-asset
     * identity's TokenState is synthetic, so the feed's price was thrown away (CRYPTO_TREASURY lost=577, CRYPTO_CASHGEN 212).
     * A multi-chain meme pair mark carries its receipt time; a price with no observation time is never answered.
     */
    fun markAt8571(key: String, nowMs: Long = System.currentTimeMillis()): Pair<Double, Long>? {
        val a = assets[key] ?: return com.lifecyclebot.perps.crypto.MultiChainMemeLane7962.freshMarkAt8571(key, nowMs)
        return try {
            fun ok(px: Double, at: Long) = if (px.isFinite() && px > 0.0 && at > 0L && nowMs - at in 0L..PRICE_MAX_AGE_MS) px to at else null
            if (a.nativePriceProvenance8372) return com.lifecyclebot.perps.DynamicAltTokenRegistry.learningMark8372(a.dynKey, nowMs)
                ?.let { ok(it.price, it.observedAtMs) }
            if (a.assetClass == AssetClass.CRYPTO_ALT || a.assetClass == AssetClass.SOLANA_TOKEN) {
                val m = com.lifecyclebot.perps.DynamicAltTokenRegistry.heldMarkSnapshot7251(a.dynKey.ifBlank { key })
                    .takeIf { it.freshObservation && it.price > 0.0 }
                    ?: com.lifecyclebot.perps.DynamicAltTokenRegistry.heldMarkSnapshot7251(key).takeIf { it.freshObservation && it.price > 0.0 }
                if (m != null) return ok(m.price, m.observedAtMs)
            }
            val market = marketsBySymbol[a.symbol.uppercase()] ?: return null
            val d = com.lifecyclebot.perps.PerpsMarketDataFetcher.getCachedPrice(market) ?: return null
            ok(d.price, d.lastUpdate)
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
        val at9058=System.currentTimeMillis()
        val (ts, lane) = tokenStateFor(c,at9058) ?: return
        // Bind the desk's own strategy to this decision, not whichever older observation happens to be pending.
        val setup9058=com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.deskSetup9013(c.evidence["strategy7803"],c.evidence["trend8886"])
        try { com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.observe(ts,lane,admitted,reason,at9058,deskSetup9058=setup9058,observationMode9059=c.mode) } catch (_: Throwable) {}
    }

    /** Native crypto desk YES/NO before owner selection, in its exact crypto lane; observation only, never a fill. */
    internal fun observeNativeLong8372(ts: TokenState, deskLane: String, admitted: Boolean, reason: String,
                                       nowMs: Long, score: Int, nativeSetup9059: String? = null,
                                       nativeSignalAt9059: Long? = null, observationMode9059: String? = null): Boolean {
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
        prunePriceIdentities8402()
        assets[key] = Asset(AssetClass.CRYPTO_ALT, ts.symbol, ts.mint, nowMs, nativePriceProvenance8372 = true)
        com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.observe(
            if (key == ts.mint) ts else ts.copy(mint = key), lane, admitted, reason, nowMs, score,
            nativeSetup9059=nativeSetup9059?.let{"CX_DESK_"+it},nativeSignalAt9059=nativeSignalAt9059,
            observationMode9059=observationMode9059)
        return true
    }

    data class EntryAssessment8871(
        val refusal: String?,
        val admission: com.lifecyclebot.engine.truth.CandidateAdmission8136.Decision8136?,
    )

    /** The native generation/ticket survive policy assessment; integrity classification reads the gate's actual result. */
    fun assessEntry8871(c: CanonicalAssetEntryCandidate6551,
                        nowMs: Long = System.currentTimeMillis()): EntryAssessment8871 {
        val paper = c.mode.equals("PAPER", true)
        val (ts, lane) = tokenStateFor(c, nowMs) ?: return EntryAssessment8871(null, null)
        if (c.assetClass != AssetClass.CRYPTO_ALT) return EntryAssessment8871(
            try { com.lifecyclebot.engine.truth.LiveEdgeGate7877.liveRefusal(ts, lane, paper) } catch (_: Throwable) { null }, null)
        val native = com.lifecyclebot.perps.CryptoNativeSignal8871.validate(c, nowMs)
        val assess8871 = {
            com.lifecyclebot.engine.truth.CandidateAdmission8136.assess8136(ts, lane, nowMs,
                candidateVersion = c.candidateVersion, proposedSizeSol = c.requestedSizeSol,
                cryptoNative8871 = native)
        }
        val admission = try {
            com.lifecyclebot.engine.truth.FdgGate8365.inContext8365(ts, lane, null, paper) {
                assess8871()
            }
        } catch (_: Throwable) { null }
        var evaluated8871 = admission
        val refusal = try {
            com.lifecyclebot.engine.truth.LiveEdgeGate7877.liveRefusal(ts, lane, paper, nowMs,
                candidateVersion = c.candidateVersion, candidateAdmission = admission,
                reassess8871 = assess8871, onAdmission8871 = { evaluated8871 = it })
        } catch (_: Throwable) { "CANONICAL_ENTRY_EVAL_FAILED_8392" }
        return EntryAssessment8871(refusal, evaluated8871)
    }

    /** Compatibility callers retain the ordinary refusal API. */
    fun liveRefusal(c: CanonicalAssetEntryCandidate6551): String? = assessEntry8871(c).refusal

    /**
     * V5.0.8857 — CryptoSignalEngine8857: does the live edge gate's refusal [reason] of [c] bind the crypto engine (an
     * integrity refusal, or a data refusal resting on the executable minimum of the candidate's own admission)? A
     * candidate the labeler cannot read binds.
     */
    fun refusalBinds8857(c: CanonicalAssetEntryCandidate6551, reason: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        com.lifecyclebot.engine.truth.CryptoSignalEngine8857.binds8857(reason) {
            tokenStateFor(c, nowMs)?.let { (ts, lane) -> com.lifecyclebot.engine.truth.CandidateAdmission8136.assess8136(ts, lane, nowMs) }
        }

    /** The canonical caller must classify the same immutable admission, never a second reconstruction of the token. */
    internal fun refusalBinds8871(reason: String, assessment: EntryAssessment8871): Boolean =
        com.lifecyclebot.engine.truth.CryptoSignalEngine8857.binds8857(reason) { assessment.admission }

    /** V5.0.8857 — the playbook setup [c] is showing in its learning lane (LanePlaybook7907, the meme desk's own read), or "". */
    fun firedSetup8857(c: CanonicalAssetEntryCandidate6551, nowMs: Long = System.currentTimeMillis()): String = try {
        tokenStateFor(c, nowMs)?.let { (ts, lane) -> LanePlaybook7907.selectedSetup8094(ts, lane, nowMs) }.orEmpty()
    } catch (_: Throwable) { "" }

    fun statusLine(): String = "tracked=${assets.size} stablesSkipped8063=${stablesSkipped8063.get()} byClass=" +
        assets.values.groupingBy { it.assetClass.tag }.eachCount().entries.joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "-" } +
        // V5.0.7962 — everything but SOL: per chain gas, live/shadow, candidates, labels, cell EV (own diag line;
        // carried here because PipelineHealthCollector.dumpText is a pinned method that may only shrink).
        "\n  Multi-chain lane (§7962):    " +
        (try { com.lifecyclebot.perps.crypto.MultiChainMemeLane7962.statusLine() } catch (_: Throwable) { "unavailable" })
}

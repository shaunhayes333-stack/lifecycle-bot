package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8400 §CAPTURED_WITH_THE_TOKEN_AT_DISCOVERY — the operator's rule: a token's market cap (and the inputs every
 * specialist grades it on) is captured when the token is discovered, not hydrated lazily after a lane has already
 * refused it as "mcap unknown".
 *
 * 5.0.8399 run: MOONSHOT NATIVE_MCAP_UNHYDRATED_DEFER=925 of 2,348 readies and MCAP_HYDRATED_FROM_CURVE_8399=0 — the
 * deferred coins reached the specialists with lastMcap=0 because their intake source carried none (HOT_CONVICTION_WARMUP
 * passes NaN, PROBATION / restore rows carry no cap, a scanner row whose aggregator had no fdv) and nothing at intake
 * resolved it from what the app already knew. This is the one resolver, in evidence order, each step an existing
 * authority (nothing invented, no new provider surface):
 *
 *  1. the row already has a cap                         -> ROW
 *  2. the curve's own reserves (frame / account read)   -> CURVE_RESERVES   (CurveReserves7951.hydrateMcap8399)
 *  3. independent price x resolved supply               -> PRICE_X_SUPPLY   (TrustedMcap8019.mcap8019, writes the row)
 *  4. (V5.0.8418: the curve-from-liquidity derivation is removed — a cap is never derived from a depth)
 *  5. none of the above: queue the batched chain read   -> CHAIN_READ_QUEUED (OnChainDepth8038.needDepth8047; the next
 *     evaluate re-runs this and the fingerprint carries lastMcap)
 *
 * Called at intake (admitProtectedMemeIntake, every source) and as the backstop at the top of
 * SpecialistBrainBridge7542.evaluate. Labels name the resolver and, when nothing resolved, the intake source, so the
 * report shows which discovery path delivers incomplete tokens.
 */
object DiscoveryCapture8400 {
    private const val ROW = "ROW"
    private const val CURVE_RESERVES = "CURVE_RESERVES"
    private const val PRICE_X_SUPPLY = "PRICE_X_SUPPLY"
    private const val CHAIN_READ_QUEUED = "CHAIN_READ_QUEUED"
    private const val UNRESOLVED = "UNRESOLVED"
    private const val PROVIDER_CAP = "PROVIDER_CAP_8533"
    private const val PROVIDER_CAP_MAX_AGE_MS_8533 = 15L * 60_000L
    private val providerCap8533 = AtomicLong(0)

    /**
     * Pure (V5.0.8533): a provider's own cap for a coin that is NOT on a pump curve — the market sweep row's, else the
     * DexScreener pair's fdv — when observed within [maxAgeMs]; null otherwise. The owner: the sources are already in
     * the code. It stands in until price x verified supply lands (TrustedMcap8019.mcap8019 reconciles the row against
     * that on every decision), so a lane can read the coin instead of deferring on an empty field.
     */
    fun providerCap8533(sweepCap: Double?, sweepAtMs: Long?, pairFdv: Double?, pairAtMs: Long?, nowMs: Long,
                        maxAgeMs: Long = PROVIDER_CAP_MAX_AGE_MS_8533): Double? {
        fun ok(v: Double?, at: Long?) = v != null && v.isFinite() && v > 0.0 && at != null && at > 0L && nowMs - at in 0L..maxAgeMs
        if (ok(sweepCap, sweepAtMs)) return sweepCap
        if (ok(pairFdv, pairAtMs)) return pairFdv
        return null
    }

    private val resolved = AtomicLong(0)
    private val queued = AtomicLong(0)
    private val unresolved = AtomicLong(0)
    private val byHow = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
    private val missingBySource = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
    private val liqHydrated = AtomicLong(0)
    private val liqMissingBySource = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
    private fun bump(m: java.util.concurrent.ConcurrentHashMap<String, AtomicLong>, k: String) {
        if (m.size > 64 && !m.containsKey(k)) return
        m.computeIfAbsent(k) { AtomicLong(0) }.incrementAndGet()
    }
    private fun top(m: Map<String, AtomicLong>, n: Int = 6): String =
        m.entries.sortedByDescending { it.value.get() }.take(n).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }

    private fun activeCurve8400(mint: String): Boolean =
        (try { com.lifecyclebot.network.PumpFunDirectApi.isPumpFunMint(mint) } catch (_: Throwable) { false }) &&
            !(try { com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(mint) } catch (_: Throwable) { true })

    /**
     * Resolve [ts]'s market cap from what the app already knows; queue the chain read when nothing does.
     * Returns the resolver name. [where] is the caller ("INTAKE_<source>" / "BRIDGE") for the unresolved label.
     */
    fun captureMcap8400(ts: TokenState, where: String, solUsd: Double = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice): String {
        if (ts.mint.isBlank()) return UNRESOLVED
        if (ts.lastMcap.isFinite() && ts.lastMcap > 0.0) return ROW
        var how = UNRESOLVED
        try {
            val v = CurveReserves7951.hydrateMcap8399(ts, solUsd)
            if (v.isFinite() && v > 0.0) how = CURVE_RESERVES
        } catch (_: Throwable) {}
        if (how == UNRESOLVED) try {
            val v = TrustedMcap8019.mcap8019(ts)   // writes the price-implied cap back to the row when it can
            if (v.isFinite() && v > 0.0) how = PRICE_X_SUPPLY
        } catch (_: Throwable) {}
        // V5.0.8533 §HELIUS_MATE — no verified supply: ask Helius DAS for it in a batch (HeliusSupplyBatch8533), so the next
        // read is price x supply; meanwhile a provider's own cap for a non-curve coin stands in (providerCap8533).
        if (how == UNRESOLVED) {
            try { com.lifecyclebot.network.HeliusSupplyBatch8533.request(ts.mint) } catch (_: Throwable) {}
            if (!activeCurve8400(ts.mint)) {
                val now8533 = System.currentTimeMillis()
                val row8533 = try { com.lifecyclebot.engine.market.MarketSweep7297.rowFor8169(ts.mint, now8533) } catch (_: Throwable) { null }
                val pair8533 = try { PairFlow8169.peek8169(ts.mint) } catch (_: Throwable) { null }
                val v8533 = providerCap8533(row8533?.mcapUsd, row8533?.observedAtMs8169, pair8533?.fdvUsd8533, pair8533?.atMs, now8533)
                if (v8533 != null) { ts.lastMcap = v8533; how = PROVIDER_CAP; providerCap8533.incrementAndGet() }
            }
        }
        // superseded by V5.0.8418 — step 4 (CURVE_FROM_LIQUIDITY: cap = (realSol + 30)^2 / 32.19 from ts.lastLiquidityUsd) is
        // deleted: it derived a cap from a depth that LiquidityTruth8037 may itself have derived from a cap, over an assumed 1B
        // supply. Without observed reserves x verified supply or price x verified supply the cap stays UNKNOWN (chain read queued).
        if (how == UNRESOLVED) {
            try { OnChainDepth8038.needDepth8047(ts) } catch (_: Throwable) {}
            how = if (activeCurve8400(ts.mint) || ts.mint.endsWith("pump", ignoreCase = true)) CHAIN_READ_QUEUED else UNRESOLVED
        }
        when (how) {
            CHAIN_READ_QUEUED -> queued.incrementAndGet()
            UNRESOLVED -> unresolved.incrementAndGet()
            else -> resolved.incrementAndGet()
        }
        bump(byHow, how)
        if (how == UNRESOLVED || how == CHAIN_READ_QUEUED) bump(missingBySource, where.take(40))
        if (how == UNRESOLVED) bump(unresolvedWhy8439, missWhy8439(ts.lastPrice, ts.lastPriceSource,
            try { OnChainSupplyAuthority7075.supplyOf7075(ts.mint) } catch (_: Throwable) { 0.0 }) + "@" + ts.source.ifBlank { "?" }.take(24))
        try {
            PipelineHealthCollector.labelInc("DISCOVERY_MCAP_${how}_8400")
            if (how == UNRESOLVED || how == CHAIN_READ_QUEUED) PipelineHealthCollector.labelInc("DISCOVERY_MCAP_MISSING_8400_${where.take(40)}")
        } catch (_: Throwable) {}
        return how
    }

    /**
     * BotService.admitProtectedMemeIntake (V5.0.8400): the source's own cap first (V5.9.769 max-take), then the cap
     * resolver, then the depth hydrate (LiquidityTruth8037: curve account / observed pool / 8164 cap-derived / chain read
     * queued) — after the cap so the derivation has a cap to read. Lives here so the ART-pinned intake method only shrinks.
     */
    fun captureAtIntake8400(ts: TokenState, source: String, trustedMarketCapUsd: Double) {
        // V5.0.8418 — superseded V5.9.769 max-take: the source's newer cap replaces the older one, up or down.
        if (trustedMarketCapUsd.isFinite() && trustedMarketCapUsd > 0.0) ts.lastMcap = trustedMarketCapUsd
        try { captureMcap8400(ts, "INTAKE_$source") } catch (_: Throwable) {}
        if (!(ts.lastLiquidityUsd.isFinite() && ts.lastLiquidityUsd > 0.0)) {
            val liq = try { LiquidityTruth8037.hydrate8037(ts) } catch (_: Throwable) { 0.0 }
            try { PipelineHealthCollector.labelInc(if (liq > 0.0) "DISCOVERY_LIQ_HYDRATED_8400" else "DISCOVERY_LIQ_MISSING_8400_${source.take(40)}") } catch (_: Throwable) {}
            if (liq > 0.0) liqHydrated.incrementAndGet() else bump(liqMissingBySource, source.take(40))
        }
        try { seedShape8407(ts) } catch (_: Throwable) {}   // V5.0.8407 — and its shape, from the exchange's reported changes
    }

    // ── V5.0.8407 §SHAPE_FROM_THE_EXCHANGE_YOU_ALREADY_ASKED ──────────────────────────────────────────────────────────────
    // 5.0.8404: setup classification needs three time-separated price points ("shape"); a token discovered this cycle has one,
    // so its first evaluations read DATA_INCOMPLETE (MOONSHOT shape missing 1,226; keyless OHLCV served 8 of 716 fetches,
    // 335 shape repairs unresolved). DexScreener's pair row already reports the pair's own price change over the last 5 minutes
    // and hour (PairFlow8169 keeps it at every fetch). price_now / (1 + chg) at (observedAt - lag) is that exchange's observation
    // of the price then: exact arithmetic on reported data, not a guess, written only for a lag the token has lived through.
    // The points carry synthetic=true (no OHLCV volume of their own): shape classifiers read them, the range / volume readers
    // that filter synthetic candles keep ignoring them. Later real polls add to them, never replace them.
    private val shapeSeeded8407 = AtomicLong(0)
    private val shapeNotSeeded8407 = ConcurrentHashMap<String, AtomicLong>()
    private const val LAG_5M_8407 = 5L * 60_000L
    private const val LAG_1H_8407 = 60L * 60_000L
    private const val FLOW_FRESH_MS_8407 = 10L * 60_000L

    /** Pure: the price [chgPct] percent before [priceNow]; NaN when not derivable (a dead pair derives nothing). */
    fun impliedPriceBefore8407(priceNow: Double, chgPct: Double): Double =
        if (priceNow.isFinite() && priceNow > 0.0 && chgPct.isFinite() && chgPct > -99.9) priceNow / (1.0 + chgPct / 100.0) else Double.NaN

    /** Pure: how many time-separated finite points [pts] holds (the shape read needs three). */
    fun distinctPoints8407(pts: List<Candle>): Int =
        pts.filter { it.priceUsd.isFinite() && it.priceUsd > 0.0 && it.ts > 0L }.map { it.ts }.distinct().size

    /** Seed the exchange-reported points a thin history lacks; returns how many were added. */
    fun seedShape8407(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Int {
        val mint = ts.mint
        if (mint.isBlank()) return 0
        if (synchronized(ts.history) { distinctPoints8407(ts.history.toList()) } >= 3) return 0
        fun miss(why: String): Int { shapeNotSeeded8407.computeIfAbsent(why) { AtomicLong() }.incrementAndGet(); return 0 }
        val flow = try { PairFlow8169.peek8169(mint) } catch (_: Throwable) { null } ?: return miss("NO_FLOW")
        if (nowMs - flow.atMs !in 0L..FLOW_FRESH_MS_8407) return miss("FLOW_STALE")
        val px = flow.priceUsd8417
        if (!(px.isFinite() && px > 0.0)) return miss("NO_PRICE")
        // A new pair's "1h change" may cover only its first minutes. Without a pool-age witness, do not invent
        // an hour-old candle. Both the price and the age must refer to the answer's observation time.
        if (flow.pairCreatedAtMs8417 < AdmissionBirth8144.MIN_VALID_MS_8144 || flow.pairCreatedAtMs8417 > flow.atMs)
            return miss("PAIR_AGE_UNKNOWN")
        val ageMs = flow.atMs - flow.pairCreatedAtMs8417
        var added = 0
        synchronized(ts.history) {
            val existing = ts.history.map { it.ts }.toHashSet()
            fun put(lagMs: Long, chg: Double) {
                if (ageMs < lagMs) return
                val p = impliedPriceBefore8407(px, chg); if (!p.isFinite()) return
                val at = flow.atMs - lagMs
                if (!existing.add(at)) return
                ts.history.addFirst(Candle(ts = at, priceUsd = p, marketCap = 0.0, volumeH1 = 0.0, volume24h = 0.0,
                    highUsd = p, lowUsd = p, openUsd = p, synthetic = true))
                added++
            }
            put(LAG_1H_8407, flow.chg1h)
            put(LAG_5M_8407, flow.chg5m)
            if (added > 0) put(0L, 0.0)   // keep the matching endpoint when the token's current mark has moved on
            if (added > 0) { val sorted = ts.history.sortedBy { it.ts }; ts.history.clear(); ts.history.addAll(sorted) }
        }
        if (added > 0) {
            shapeSeeded8407.addAndGet(added.toLong())
            try { PipelineHealthCollector.labelAdd("DISCOVERY_SHAPE_SEEDED_8407", added.toLong()) } catch (_: Throwable) {}
        } else miss("TOO_YOUNG_OR_PRESENT")
        return added
    }

    // V5.0.8439 — 8438: unresolved=455 (BRIDGE 449; MOONSHOT / MANIPULATED NATIVE_MCAP_UNHYDRATED_DEFER=449 each) with no line
    // saying whether the coin had no price, a cap-derived price, or no verified supply. The next report names it, by source.
    private val unresolvedWhy8439 = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()

    /** Pure: why price x verified supply could not cap the coin. */
    fun missWhy8439(priceUsd: Double, priceSource: String, supply: Double): String = when {
        !(priceUsd.isFinite() && priceUsd > 0.0) -> "NO_PRICE"
        !TrustedMcap8019.independentPrice8019(priceSource) -> "PRICE_NOT_INDEPENDENT"
        !(supply.isFinite() && supply > 0.0) -> "NO_VERIFIED_SUPPLY"
        else -> "OTHER"
    }

    fun statusLine(): String =
        "discoveryMcap8400 resolved=${resolved.get()} chainQueued=${queued.get()} unresolved=${unresolved.get()} by{${top(byHow)}} " +
            "unresolvedWhy8439{${top(unresolvedWhy8439)}} " +
            "providerCap8533=${providerCap8533.get()} uncappedBySource{${top(missingBySource)}} liq[hydrated=${liqHydrated.get()} missingBySource{${top(liqMissingBySource)}}] " +
            "shape8407[seeded=${shapeSeeded8407.get()} notSeeded{${shapeNotSeeded8407.entries.sortedByDescending { it.value.get() }.take(4).joinToString(",") { "${it.key}=${it.value.get()}" }}}]"
}

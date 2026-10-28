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
 *  4. an active pump curve's real SOL -> cap invariant  -> CURVE_FROM_LIQUIDITY (inverse of LiquidityTruth8037.curveRealSolFromCap8048)
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
    private const val CURVE_FROM_LIQUIDITY = "CURVE_FROM_LIQUIDITY"
    private const val CHAIN_READ_QUEUED = "CHAIN_READ_QUEUED"
    private const val UNRESOLVED = "UNRESOLVED"

    /** pump.fun curve invariant: 30 + r virtual SOL over a 1B supply gives cap = (30 + r)^2 / 32.19 SOL (inverse of 8048). */
    private const val CURVE_K_8400 = 30.0 * 1.073

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

    /** Pure: an active curve's cap in SOL from the real SOL buyers put in, or NaN. */
    fun curveCapSolFromRealSol8400(realSol: Double): Double =
        if (realSol.isFinite() && realSol >= 0.0) (realSol + 30.0) * (realSol + 30.0) / CURVE_K_8400 else Double.NaN

    /** Pure: the cap in USD an active curve's USD depth implies, or NaN. */
    fun curveCapUsdFromLiquidity8400(liquidityUsd: Double, solUsd: Double): Double {
        if (!(liquidityUsd.isFinite() && liquidityUsd > 0.0 && solUsd.isFinite() && solUsd > 0.0)) return Double.NaN
        val realSol = EconomicUnitInvariant7061.usdToSol(liquidityUsd, solUsd)   // the one USD->SOL authority (ci/economic_units_scan §1)
        if (!(realSol.isFinite() && realSol > 0.0)) return Double.NaN
        val capSol = curveCapSolFromRealSol8400(realSol)
        return if (capSol.isFinite() && capSol > 0.0) capSol * solUsd else Double.NaN
    }

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
        if (how == UNRESOLVED && activeCurve8400(ts.mint)) {
            val cap = curveCapUsdFromLiquidity8400(ts.lastLiquidityUsd, solUsd)
            if (cap.isFinite() && cap > 0.0) {
                synchronized(ts) { if (!(ts.lastMcap.isFinite() && ts.lastMcap > 0.0)) ts.lastMcap = cap }
                how = CURVE_FROM_LIQUIDITY
            }
        }
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
        if (trustedMarketCapUsd.isFinite() && trustedMarketCapUsd > ts.lastMcap) ts.lastMcap = trustedMarketCapUsd
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
        val px = ts.lastPrice
        if (!(px.isFinite() && px > 0.0)) return miss("NO_PRICE")
        val ageMs = try { CanonicalTokenBirthTime7440.resolvedAgeMs(ts, nowMs) } catch (_: Throwable) { null }
        var added = 0
        synchronized(ts.history) {
            val existing = ts.history.map { it.ts }.toHashSet()
            fun put(lagMs: Long, chg: Double) {
                if (ageMs != null && ageMs < lagMs) return
                val p = impliedPriceBefore8407(px, chg); if (!p.isFinite()) return
                val at = flow.atMs - lagMs
                if (at in existing) return
                ts.history.addFirst(Candle(ts = at, priceUsd = p, marketCap = 0.0, volumeH1 = 0.0, volume24h = 0.0,
                    highUsd = p, lowUsd = p, openUsd = p, synthetic = true))
                added++
            }
            put(LAG_1H_8407, flow.chg1h)
            put(LAG_5M_8407, flow.chg5m)
            if (added > 0) { val sorted = ts.history.sortedBy { it.ts }; ts.history.clear(); ts.history.addAll(sorted) }
        }
        if (added > 0) {
            shapeSeeded8407.addAndGet(added.toLong())
            try { PipelineHealthCollector.labelAdd("DISCOVERY_SHAPE_SEEDED_8407", added.toLong()) } catch (_: Throwable) {}
        } else miss("TOO_YOUNG_OR_PRESENT")
        return added
    }

    fun statusLine(): String =
        "discoveryMcap8400 resolved=${resolved.get()} chainQueued=${queued.get()} unresolved=${unresolved.get()} by{${top(byHow)}} " +
            "uncappedBySource{${top(missingBySource)}} liq[hydrated=${liqHydrated.get()} missingBySource{${top(liqMissingBySource)}}] " +
            "shape8407[seeded=${shapeSeeded8407.get()} notSeeded{${shapeNotSeeded8407.entries.sortedByDescending { it.value.get() }.take(4).joinToString(",") { "${it.key}=${it.value.get()}" }}}]"
}

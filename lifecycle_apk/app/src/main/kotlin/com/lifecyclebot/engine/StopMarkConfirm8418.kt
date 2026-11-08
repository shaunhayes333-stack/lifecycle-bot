package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8418 §A_STOP_SELLS_ON_A_PRICE_THE_ROUTE_AGREES_WITH — the 8418 live journal (c92cda8e2), trigger mark vs fill:
 *   Ac6PLX   RAPID_CATASTROPHE_STOP (needs <= -25 %)  realised -3.3 % gross, 48 s after entry
 *   WJJkGmNB SWEEP_FLUID_FLOOR_-15  (needs <= -15 %)  realised -4.2 % gross
 *   9Pq7aLAb RAPID_FLUID_STOP                          realised -2.7 % gross
 * The 7385 guard already confirmed an off-basis mark with an executable quote — but only inside the first 45 s and only
 * for a short list of source names, so a lagging poll, a best-pair switch or a stale row sold positions at any later age.
 * Rule now: a LOSS stop (catastrophe, hard floor, fluid, sweep floor) whose trigger mark is not a chain read of the
 * position's own venue, nor an executable quote, asks the executable price first — at any age. A real dump still exits:
 * the quote agrees within seconds, and a mark at or under [BYPASS_PCT_8418] after [BYPASS_AGE_MS_8418] never waits.
 * Paper is never gated.
 *
 * V5.0.8419 (review of 8418) — two holes closed:
 *  1. The wait had no bound. With the 45 s expiry gone, a quote API that is down or routes nothing (MARK_EXECUTABLE_QUOTE_
 *     NO_ROUTE_7301) held a -15 % / -25 % stop until -60 %. An unconfirmed stop now fires on its mark once the executable
 *     price has been asked for [AWAIT_MAX_MS_8419] without an answer; a refutation counts only from a quote observed AFTER
 *     the ask (a 60 s-cached quote that pre-dates the mark cannot answer for it — 8094 H9NC5D: a lagging cached quote held a
 *     catastrophe stop from -23 % to -43 %), and a refuted stop asks again every [REASK_GAP_MS_8419], not every 30 s.
 *  2. The coin's own chain state was off-basis. CurveTicks7968 (the bonding-curve account) and the decoded pump trades all
 *     carry PUMP_PORTAL_TRADE_WS_7278 (StopFeed8327: "those prints ARE the executable price"; 3,434 of the 8418 session's
 *     held marks), which the 7385 heuristic reads as a trade-stream observation — so every pump-curve stop was quote-gated
 *     at any age. StopFeed8327.chainMark8327 sources are on the position's basis here and in the rapid monitor.
 */
object StopMarkConfirm8418 {
    private const val BYPASS_PCT_8418 = -60.0
    private const val BYPASS_AGE_MS_8418 = 10_000L
    private val CHAIN_8418 = listOf("CURVE_RPC", "CURVE_ACCOUNT", "VAULT", "POOL_STATE", "ONCHAIN", "LOCKED_VENUE", "EXEC_QUOTE", "JUPITER_QUOTE", "EXECUTABLE")
    // V5.0.8419
    private const val AWAIT_MAX_MS_8419 = 5_000L   // V5.0.8487: was 15 s — the venue reserves answer first now
    private const val REASK_GAP_MS_8419 = 5_000L
    private const val MAX_AWAITS_8419 = 2_048
    private const val RESERVE_ASK_MS_8486 = 3_000L   // a pending stop asks the venue for a read no older than this
    private val awaitSince8419 = ConcurrentHashMap<String, Long>()   // mint|entryTime -> when the executable price was first asked

    /** Pure: is a mark from [source] (pool [markPool]) on the basis of a position entered on [entryPool]? */
    fun onBasis8418(source: String, markPool: String, entryPool: String): Boolean {
        val s = source.uppercase()
        // V5.0.8419 — the coin's own chain state (curve account, decoded curve trade, pool vault / state) is the basis.
        if (StopFeed8327.chainMark8327(s)) return true
        if (CHAIN_8418.any { s.contains(it) }) return true
        if (s.contains("SYNTH") || s.contains("CAP_SEED") || s.contains("UNCORROBORATED") || s.contains("OBSERVATION") ||
            s.contains("ORACLE_FALLBACK") || s.contains("CANDIDATE_REFRESH")) return false
        // a poll / stream print is on basis only when it names the position's own venue
        return entryPool.isNotBlank() && markPool.isNotBlank() && entryPool == markPool
    }

    /**
     * V5.0.8419 — pure: the rapid monitor's verdict. The 7385 heuristic ([legacyOffBasis]: SYNTH / PUMP_FUN_BC / TRADE_WS /
     * another pool) still marks a non-chain source off-basis, but it never overrides a chain mark of the coin itself.
     */
    fun offBasis8419(legacyOffBasis: Boolean, source: String, markPool: String, entryPool: String): Boolean =
        if (StopFeed8327.chainMark8327(source)) false else legacyOffBasis || !onBasis8418(source, markPool, entryPool)

    /** Pure: the verdict given the executable price when one is fresh (null = not yet known). */
    fun executableBreaches8418(execPx: Double?, entryPx: Double, thresholdPct: Double): Boolean? {
        if (execPx == null || !execPx.isFinite() || execPx <= 0.0 || !entryPx.isFinite() || entryPx <= 0.0) return null
        return (execPx / entryPx - 1.0) * 100.0 <= thresholdPct
    }

    /**
     * V5.0.8419 — pure: the verdict of an executable quote [execPx] observed at [execAtMs] for an ask first made at
     * [askedAtMs]. A breach confirms at any age; a refutation needs a quote observed at or after the ask (null = not yet).
     */
    fun quoteVerdict8419(execPx: Double?, execAtMs: Long, askedAtMs: Long, entryPx: Double, thresholdPct: Double): Boolean? {
        val breached = executableBreaches8418(execPx, entryPx, thresholdPct) ?: return null
        if (breached) return true
        return if (execAtMs >= askedAtMs) false else null
    }

    /** The exact holding's newer SELL quote can refute an older bad mark, never a subsequent price tick. */
    fun feedbackVerdict8521(q: com.lifecyclebot.engine.truth.HeldExitQuote8189.Quote8189?, positionId: String,
                           raw: java.math.BigInteger, remainingCostSol: Double, markAtMs: Long,
                           nowMs: Long, thresholdPct: Double): Boolean? {
        if (q == null || q.positionId8521 != positionId || q.rawQty != raw ||
            nowMs - q.observedAtMs !in 0L..2_000L || q.observedAtMs < markAtMs ||
            !remainingCostSol.isFinite() || remainingCostSol <= 0.0) return null
        val proceeds = q.proceedsSol?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val sellCost = q.estimatedSellCostSol8521?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        return ((proceeds - sellCost).coerceAtLeast(0.0) / remainingCostSol - 1.0) * 100.0 <= thresholdPct
    }

    fun cachedSellQuoteVerdict8521(ts: TokenState, thresholdPct: Double, nowMs: Long = System.currentTimeMillis()): Boolean? {
        if (ts.position.isPaperPosition) return null
        val p = try { com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.firstOpenForMint(ts.mint, "live") }
            catch (_: Throwable) { null } ?: return null
        return feedbackVerdict8521(com.lifecyclebot.engine.truth.HeldExitQuote8189.last8189(ts.mint), p.positionId,
            p.remainingQtyRaw, p.entryCostSol - p.soldCostBasisSol, ts.lastPriceUpdate, nowMs, thresholdPct)
    }

    /** V5.0.8419 — pure: an unconfirmed loss stop fires on its own mark once the ask is [AWAIT_MAX_MS_8419] old. */
    fun awaitExpired8419(askedAtMs: Long, nowMs: Long): Boolean = askedAtMs > 0L && nowMs - askedAtMs >= AWAIT_MAX_MS_8419

    /**
     * May a loss stop at [thresholdPct] (negative %) fire for [ts] whose mark reads [pnlPct]? True when the mark is on the
     * position's basis, the executable price agrees, the ask went unanswered for [AWAIT_MAX_MS_8419], or the bypass holds;
     * false while an executable confirmation is pending.
     */
    fun confirmed8418(ts: TokenState, pnlPct: Double, thresholdPct: Double, paper: Boolean, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (paper) return true
        cachedSellQuoteVerdict8521(ts, thresholdPct, nowMs)?.let { return it }
        val pos = ts.position
        if (pos.entryPrice <= 0.0) return true
        val key = "${ts.mint}|${pos.entryTime}"
        val ageMs = if (pos.entryTime > 0L) nowMs - pos.entryTime else Long.MAX_VALUE
        if (pnlPct <= BYPASS_PCT_8418 && ageMs >= BYPASS_AGE_MS_8418) { awaitSince8419.remove(key); return true }
        if (onBasis8418(ts.lastPriceSource, ts.lastPricePoolAddr, pos.entryPoolAddress)) { awaitSince8419.remove(key); return true }
        return executableConfirms8419(ts, thresholdPct, nowMs)
    }

    /**
     * V5.0.8419 — the executable-price leg shared with the rapid monitor's catastrophe / hard-floor block (BotService
     * stopConfirmed7385): a fresh quote decides; none within [AWAIT_MAX_MS_8419] of the first ask fires the stop on its mark.
     */
    fun executableConfirms8419(ts: TokenState, thresholdPct: Double, nowMs: Long = System.currentTimeMillis()): Boolean {
        val pos = ts.position
        val key = "${ts.mint}|${pos.entryTime}"
        if (awaitSince8419.size > MAX_AWAITS_8419) awaitSince8419.entries.removeIf { nowMs - it.value > 10L * 60_000L }
        awaitSince8419.putIfAbsent(key, nowMs)
        val asked = awaitSince8419[key] ?: nowMs
        // V5.0.8485/8486 — the coin's own pool or curve reserves (chain-read within the last 20 s) answer first: they price what
        // selling this bag at its venue returns now, and they do not 429. Jupiter's quote is the second answer. Same rule as the
        // quote: a breach confirms at any age; a refutation counts only from a read made at or after the ask.
        reserveVerdict8485(ts, asked, nowMs, thresholdPct)?.let { breached ->
            try { PipelineHealthCollector.labelInc(if (breached) "STOP_MARK_CONFIRMED_VENUE_RESERVE_8485" else "STOP_MARK_REFUTED_VENUE_RESERVE_8485") } catch (_: Throwable) {}
            if (breached) awaitSince8419.remove(key) else awaitSince8419[key] = nowMs
            return breached
        }
        val exec = try { com.lifecyclebot.engine.truth.MarkIdentityRepairAuthority7236.getExecutablePriceIfFresh7301(ts.mint) } catch (_: Throwable) { null }
        val execAt = try { com.lifecyclebot.engine.truth.MarkIdentityRepairAuthority7236.executableObservedAtMs7884(ts.mint) } catch (_: Throwable) { 0L }
        quoteVerdict8419(exec, execAt, asked, pos.entryPrice, thresholdPct)?.let { breached ->
            try { PipelineHealthCollector.labelInc(if (breached) "STOP_MARK_CONFIRMED_EXECUTABLE_8418" else "STOP_MARK_REFUTED_EXECUTABLE_8418") } catch (_: Throwable) {}
            // refuted: the market said "not breached" at execAt; only a NEWER quote may answer the next ask
            if (breached) awaitSince8419.remove(key) else awaitSince8419[key] = nowMs
            return breached
        }
        if (awaitExpired8419(asked, nowMs)) {
            try { PipelineHealthCollector.labelInc("STOP_MARK_UNCONFIRMED_FIRES_ON_MARK_8419") } catch (_: Throwable) {}
            try {
                ForensicLogger.lifecycle("STOP_MARK_UNCONFIRMED_FIRES_ON_MARK_8419",
                    "mint=${ts.mint.take(10)} source=${ts.lastPriceSource} askedMsAgo=${nowMs - asked} action=stop_fires_on_mark_no_executable_answer")
            } catch (_: Throwable) {}
            awaitSince8419.remove(key)
            return true
        }
        try { com.lifecyclebot.engine.truth.OnChainDepth8038.request8038(ts, RESERVE_ASK_MS_8486) } catch (_: Throwable) {}
        try {
            val dec = com.lifecyclebot.engine.truth.MintDecimalsAuthority6392.get(ts.mint) ?: ts.tokenMap.decimals ?: 6
            com.lifecyclebot.engine.truth.MarkIdentityRepairAuthority7236.requestExecutableQuote7301(ts.mint, dec, minGapMs = REASK_GAP_MS_8419)
        } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("STOP_MARK_AWAITING_EXECUTABLE_8418") } catch (_: Throwable) {}
        return false
    }

    /** V5.0.8485 — the venue-reserve leg: null when no fresh pool / curve read exists (or it pre-dates the ask unbreached). */
    private fun reserveVerdict8485(ts: TokenState, askedAtMs: Long, nowMs: Long, thresholdPct: Double): Boolean? {
        val pos = ts.position
        if (pos.qtyToken <= 0.0 || pos.entryPrice <= 0.0) return null
        return try {
            val dec = com.lifecyclebot.engine.truth.MintDecimalsAuthority6392.get(ts.mint) ?: ts.tokenMap.decimals ?: return null
            val raw = java.math.BigDecimal.valueOf(pos.qtyToken).movePointRight(dec).toBigInteger()
            val est = com.lifecyclebot.engine.truth.HeldHotMarkAuthority7419.venueReserveSellEstimate(
                ts.mint, raw, dec, WalletManager.lastKnownSolPrice, nowMs, countMiss = false) ?: return null
            quoteVerdict8419(est.priceUsd, est.observedAtMs, askedAtMs, pos.entryPrice, thresholdPct)
        } catch (_: Throwable) { null }
    }

    // ── V5.0.8489 — every price-based loss stop is checked against the coin's own pool / curve before it sells ──
    // 8488 live: 2gTbp2 STRICT_SL_-4 sold at +2.7 % on chain; 3qjBR3 RUNNER_HARD_STOP_7967_-99PCT fired on a mark of
    // 6.5e-8 USD against a 1.38e-4 entry (the coin was ~-7 %). A stop whose fresh venue-reserve read prices the bag well
    // above its own trigger (better than half the stop's depth, or green when no depth is named) is a bad mark: it holds,
    // asks for a new read, and fires on the next tick once the venue agrees. No fresh read = no refusal (unchanged).
    private const val VENUE_STOP_READ_MAX_AGE_MS_8489 = 8_000L   // held reads are topped up past 8 s (HeldHotMarkAuthority7419)
    private val STOP_PCT_8489 = Regex("-(\\d+(?:\\.\\d+)?)")

    /** Pure: is [reason] a price-driven loss stop (not a rug / drain / freeze / liquidity / manual exit)? */
    fun priceLossStop8489(reason: String): Boolean {
        val r = reason.uppercase()
        if (r.contains("RUG") || r.contains("DRAIN") || r.contains("FREEZE") || r.contains("FROZEN") || r.contains("LIQUIDITY") ||
            r.contains("HONEYPOT") || r.contains("DEV_DUMP") || r.contains("DEV_SELL") || r.contains("MANUAL") || r.contains("KILL") ||
            r.contains("SHUTDOWN") || r.contains("CANNOT_SELL") || r.contains("LP_") || r.contains("MINT_AUTH")) return false
        if (r.contains("PROFIT") || r.contains("TRAIL") || r.contains("TAKE") || r.contains("PEAK")) return false
        return (try { com.lifecyclebot.engine.sell.ProtectiveExitClass7807.of(reason) == com.lifecyclebot.engine.sell.ProtectiveExitClass7807.Priority.HARD_SL } catch (_: Throwable) { false }) || r.contains("CATASTROPH") || r.contains("GAP_GUARD") ||
            r.contains("_STOP") || r.contains("STOP_") || r.contains("FLOOR")
    }

    /** Pure: the stop depth named in [reason] (e.g. STRICT_SL_-4 -> -4.0), or null. */
    fun stopDepthPct8489(reason: String): Double? =
        STOP_PCT_8489.findAll(reason).lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull()?.let { -it }?.takeIf { it < 0.0 && it >= -100.0 }

    /** Pure: does an executable [venuePnlPct] refute a stop of depth [depthPct] (null = unnamed)? */
    fun venueRefutes8489(venuePnlPct: Double, depthPct: Double?): Boolean {
        if (!venuePnlPct.isFinite()) return false
        return if (depthPct != null) venuePnlPct > depthPct * 0.5 else venuePnlPct >= 0.0
    }

    /** The bag's executable PnL % from a venue-reserve read no older than [maxAgeMs]; null when none. */
    private fun venuePnlPct8489(ts: TokenState, nowMs: Long = System.currentTimeMillis(), maxAgeMs: Long = VENUE_STOP_READ_MAX_AGE_MS_8489): Double? {
        val pos = ts.position
        if (pos.qtyToken <= 0.0 || pos.entryPrice <= 0.0) return null
        return try {
            val dec = com.lifecyclebot.engine.truth.MintDecimalsAuthority6392.get(ts.mint) ?: ts.tokenMap.decimals ?: return null
            val raw = java.math.BigDecimal.valueOf(pos.qtyToken).movePointRight(dec).toBigInteger()
            val est = com.lifecyclebot.engine.truth.HeldHotMarkAuthority7419.venueReserveSellEstimate(
                ts.mint, raw, dec, WalletManager.lastKnownSolPrice, nowMs, countMiss = false) ?: return null
            if (nowMs - est.observedAtMs > maxAgeMs) return null
            (est.priceUsd / pos.entryPrice - 1.0) * 100.0
        } catch (_: Throwable) { null }
    }

    /** Executor.requestSellCore7948: true when a fresh venue read refutes this live loss stop (it holds this tick). */
    fun stopRefutedByVenue8489(ts: TokenState, reason: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (ts.position.isPaperPosition || !ts.position.isOpen || !priceLossStop8489(reason)) return false
        val pnl = venuePnlPct8489(ts, nowMs)
        val key8631 = ts.position.positionId.ifBlank { ts.mint }
        if (pnl == null) {
            try { com.lifecyclebot.engine.truth.OnChainDepth8038.request8038(ts, RESERVE_ASK_MS_8486) } catch (_: Throwable) {}
            return holdForVenueRead8631(ts, key8631, nowMs)
        }
        noReadSince8631.remove(key8631)
        val depth = stopDepthPct8489(reason)
        if (!venueRefutes8489(pnl, depth)) {
            try { PipelineHealthCollector.labelInc("STOP_CONFIRMED_BY_VENUE_8489") } catch (_: Throwable) {}
            return false
        }
        try {
            PipelineHealthCollector.labelInc("STOP_REFUTED_BY_VENUE_8489")
            ForensicLogger.lifecycle("STOP_REFUTED_BY_VENUE_8489",
                "mint=${ts.mint.take(10)} reason=${reason.take(60)} venuePnl=${"%.1f".format(pnl)} depth=${depth ?: "-"} markSource=${ts.lastPriceSource} action=hold_bad_mark_reask")
        } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.truth.OnChainDepth8038.request8038(ts, RESERVE_ASK_MS_8486) } catch (_: Throwable) {}
        return true
    }

    /**
     * V5.0.8631 §NO_READ_NO_STOP_FOR_A_MOMENT — audit_exits_now §3: three "-15 %" stops filled at -2.5 %, -3.8 % and +1.3 %:
     * the venue refute (8489) was fail-open, so with no fresh venue read the stop fired on the bad mark. A live price-loss stop
     * now waits up to [NO_READ_HOLD_MS_8631] for the read it asked for; then it fires. A mark at or below [BYPASS_PCT_8418]
     * fires at once. Pure rule in [holdsAwaitingRead8631].
     */
    const val NO_READ_HOLD_MS_8631 = 6_000L
    private val noReadSince8631 = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Pure: does a stop first left unread at [firstAskMs] still wait at [nowMs], with the mark at [markPnlPct]? */
    fun holdsAwaitingRead8631(firstAskMs: Long, nowMs: Long, markPnlPct: Double): Boolean =
        !(markPnlPct.isFinite() && markPnlPct <= BYPASS_PCT_8418) && nowMs - firstAskMs in 0L until NO_READ_HOLD_MS_8631

    private fun holdForVenueRead8631(ts: TokenState, key: String, nowMs: Long): Boolean {
        if (noReadSince8631.size > MAX_AWAITS_8419) noReadSince8631.clear()
        val first = noReadSince8631.putIfAbsent(key, nowMs) ?: nowMs
        val mark = if (ts.position.entryPrice > 0.0 && ts.lastPrice > 0.0) (ts.lastPrice / ts.position.entryPrice - 1.0) * 100.0 else Double.NaN
        val hold = holdsAwaitingRead8631(first, nowMs, mark)
        if (!hold) noReadSince8631.remove(key)
        try { PipelineHealthCollector.labelInc(if (hold) "STOP_HELD_AWAITING_VENUE_READ_8631" else "STOP_FIRED_NO_VENUE_READ_8631") } catch (_: Throwable) {}
        return hold
    }

    internal fun resetForTest8419() { awaitSince8419.clear(); noReadSince8631.clear() }
}

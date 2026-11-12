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
        // V5.0.8681 — identity before name: a mark that names a DIFFERENT pool than the one the position was filled on is
        // not this bag's price, whatever chain feed produced it (a migrated coin's old curve, a second pool, a pair switch).
        if (poolsDisagree8681(markPool, entryPool)) return false
        if (identityUnproven8730(s, markPool, entryPool)) return false
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
        if (poolsDisagree8681(markPool, entryPool)) true
        else if (identityUnproven8730(source.uppercase(), markPool, entryPool)) true
        else if (StopFeed8327.chainMark8327(source)) false else legacyOffBasis || !onBasis8418(source, markPool, entryPool)

    /**
     * Pure (V5.0.8730) — owner's second diagnostic: "a recognised source label can sometimes establish trust even without a
     * definitive match to the actual pool used by the position". [poolsDisagree8681] rejects a mark only when BOTH pools are
     * known; a chain-feed label (curve read, curve trade frame, vault stream) with no pool on it was then this bag's price by
     * its name, for a position filled on a known pool (a migrated coin's stale curve, a second pool's vault). A position
     * whose entry pool is a real address now needs the mark to carry that pool; an executable quote for the held amount is
     * the one exception (it prices this bag wherever it routes). Mint-route (curve) entries and unknown entry pools are
     * unchanged. An unproven mark is off basis: the stop asks the venue and the route ([stopRefutedByVenue8489]).
     */
    private fun identityUnproven8730(sourceUpper: String, markPool: String, entryPool: String): Boolean {
        val e = entryPool.trim()
        if (e.length < 32 || e.startsWith("MINT_ROUTE:")) return false
        if (markPool.isNotBlank()) return false
        return !(sourceUpper.contains("EXEC_QUOTE") || sourceUpper.contains("JUPITER_QUOTE") || sourceUpper.contains("EXECUTABLE"))
    }

    /** Pure (V5.0.8681): both pools are known, real addresses, and differ. Blank or sentinel pools prove nothing. */
    fun poolsDisagree8681(markPool: String, entryPool: String): Boolean {
        val m = markPool.trim(); val e = entryPool.trim()
        if (m.length < 32 || e.length < 32) return false
        if (m.startsWith("MINT_ROUTE:") || e.startsWith("MINT_ROUTE:")) return false
        return m != e
    }

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

    /**
     * Pure (V5.0.8681): the depth a catastrophe-family stop implies when its reason names none. 8680 live: all four
     * RAPID_CATASTROPHE_STOP exits filled at -3.8 % .. -5.9 % (two within 33 s of the buy). With no named depth the venue
     * leg refuted only a GREEN read, so a fresh pool read at -4 % could not stop a "catastrophe" sale. The rapid
     * catastrophe band is never shallower than the 15 % hard floor (ExitDoctrine8169.rapidCatastrophePct8169), and
     * DEEP_CATASTROPHE_NET fires only at -30 % (BotService), so those are the depths they claim.
     */
    fun impliedStopDepthPct8681(reason: String): Double? {
        val r = reason.uppercase()
        return when {
            r.contains("DEEP_CATASTROPH") -> -30.0
            r.contains("CATASTROPH") -> -15.0
            r.contains("HARD_FLOOR") -> -15.0
            else -> null
        }
    }

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

    /** V5.0.8681 — the bag's PnL % from an executable quote observed at or after this stop's first unanswered ask, else null. */
    private fun executablePnlSinceAsk8681(ts: TokenState, key: String): Double? {
        val asked = noReadSince8631[key] ?: return null
        val entry = ts.position.entryPrice
        if (!entry.isFinite() || entry <= 0.0) return null
        return try {
            val px = com.lifecyclebot.engine.truth.MarkIdentityRepairAuthority7236.getExecutablePriceIfFresh7301(ts.mint) ?: return null
            val at = com.lifecyclebot.engine.truth.MarkIdentityRepairAuthority7236.executableObservedAtMs7884(ts.mint)
            if (at < asked || !px.isFinite() || px <= 0.0) null else (px / entry - 1.0) * 100.0
        } catch (_: Throwable) { null }
    }

    /** Executor.requestSellCore7948: true when a fresh venue read refutes this live loss stop (it holds this tick). */
    fun stopRefutedByVenue8489(ts: TokenState, reason: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (ts.position.isPaperPosition || !ts.position.isOpen || !priceLossStop8489(reason)) return false
        val key8631 = ts.position.positionId.ifBlank { ts.mint }
        // V5.0.8681 — a pool the reserve reader cannot price (concentrated / depth-only venues) never answered, so the stop
        // fired on its mark after the wait (8680 DACITYCOIN: CATASTROPHIC_HARD_BACKSTOP_-25 filled at -7.5 %). The executable
        // sell quote asked during the same wait answers too, when it was observed after the first ask.
        val pnl = venuePnlPct8489(ts, nowMs) ?: executablePnlSinceAsk8681(ts, key8631)
        if (pnl == null) {
            try { com.lifecyclebot.engine.truth.OnChainDepth8038.request8038(ts, RESERVE_ASK_MS_8486) } catch (_: Throwable) {}
            try {
                val dec = com.lifecyclebot.engine.truth.MintDecimalsAuthority6392.get(ts.mint) ?: ts.tokenMap.decimals ?: 6
                com.lifecyclebot.engine.truth.MarkIdentityRepairAuthority7236.requestExecutableQuote7301(ts.mint, dec, minGapMs = REASK_GAP_MS_8419)
            } catch (_: Throwable) {}
            return holdForVenueRead8631(ts, key8631, nowMs)
        }
        noReadSince8631.remove(key8631)
        val depth = stopDepthPct8489(reason) ?: impliedStopDepthPct8681(reason)
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
     * V5.0.8706 §ANOTHER_VENUES_MARK_DOES_NOT_SELL_THIS_BAG — the unread stop, rewritten. 8631 waited [NO_READ_HOLD_MS_8631]
     * for the venue read it asked for and then fired on whatever mark had triggered it; Executor's sell-time route quote
     * allowed the sell when no quote came back. 8704 live: 61 stop triggers were refuted by an executable read (quote x38,
     * venue x13, held quote x10), the five that sold filled 22 points ABOVE their trigger marks, and three "-15 % / -25 %"
     * stops realised -0.9 %, -3.3 % and -3.4 % — the fills that got through were the ones no executable read answered for
     * (held quotes asked 217, answered 98). A live price-loss stop with no executable answer now fires only on:
     *   - a mark of the position's own venue ([onBasis8418]: its curve / pool chain state, or a print naming its pool),
     *     after the same short wait for the read; or
     *   - a mark at or below [BYPASS_PCT_8418], at once.
     * A mark from another pool, a seed, an uncorroborated fan-out or a source that names no pool keeps asking the venue and
     * the route every tick and is not a sell. Rug, drain, structural, time and operator exits are not price-loss stops and
     * never pass through here. One rule, [unreadStopFires8706], for the request path and the sell-time check.
     */
    const val NO_READ_HOLD_MS_8631 = 6_000L
    private val noReadSince8631 = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Pure: may a stop left unread since [firstAskMs] fire at [nowMs] on a mark at [markPnlPct] that is / is not [markOnVenue]? */
    fun unreadStopFires8706(firstAskMs: Long, nowMs: Long, markPnlPct: Double, markOnVenue: Boolean): Boolean = when {
        markPnlPct.isFinite() && markPnlPct <= BYPASS_PCT_8418 -> true
        nowMs - firstAskMs in 0L until NO_READ_HOLD_MS_8631 -> false
        else -> markOnVenue
    }

    private fun markPnlPct8706(ts: TokenState): Double =
        if (ts.position.entryPrice > 0.0 && ts.lastPrice > 0.0) (ts.lastPrice / ts.position.entryPrice - 1.0) * 100.0 else Double.NaN

    private fun markOnVenue8706(ts: TokenState): Boolean =
        try { onBasis8418(ts.lastPriceSource, ts.lastPricePoolAddr, ts.position.entryPoolAddress) } catch (_: Throwable) { false }

    /**
     * V5.0.8712 §THE_WAIT_IS_ONE_UNBROKEN_BREACH — 8711 live: SHITCOIN bought 22:41:12 and sold 22:41:29 on
     * RAPID_CATASTROPHE_STOP at -4.0 % realised; EXPRESS bought 22:41:30, sold 22:42:38 on its -15 % stop at -3.3 %. Both
     * were BASE-stage entries, the one stage that pays, closed inside 70 seconds for the round-trip cost. The unread wait was
     * timed from the FIRST time the stop ever asked and never restarted: one wick on the pool started the clock, the price
     * came back, and the next wick - any time later - was already "past the wait" and sold at once. The wait is now one
     * unbroken breach: if the stop stopped asking for longer than [BREACH_GAP_MS_8712] (the price was back above its line),
     * the next ask starts a new wait.
     */
    const val BREACH_GAP_MS_8712 = NO_READ_HOLD_MS_8631 / 2
    private val lastUnreadAsk8712 = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Pure: when the breach now being asked about began, given its recorded [first] ask and the [lastAsk] before this one. */
    fun breachStart8712(first: Long?, lastAsk: Long?, nowMs: Long): Long =
        if (first == null || lastAsk == null || nowMs - lastAsk !in 0L..BREACH_GAP_MS_8712) nowMs else first

    private fun holdForVenueRead8631(ts: TokenState, key: String, nowMs: Long): Boolean {
        if (noReadSince8631.size > MAX_AWAITS_8419) { noReadSince8631.clear(); lastUnreadAsk8712.clear() }
        val first = breachStart8712(noReadSince8631[key], lastUnreadAsk8712.put(key, nowMs), nowMs)
        noReadSince8631[key] = first
        val onVenue = markOnVenue8706(ts)
        val fires = unreadStopFires8706(first, nowMs, markPnlPct8706(ts), onVenue)
        if (fires) noReadSince8631.remove(key)
        try {
            PipelineHealthCollector.labelInc(when {
                fires -> "STOP_FIRED_NO_VENUE_READ_8631"
                nowMs - first < NO_READ_HOLD_MS_8631 -> "STOP_HELD_AWAITING_VENUE_READ_8631"
                else -> "STOP_HELD_OFF_VENUE_MARK_UNREAD_8706"
            })
            if (!fires && nowMs - first >= NO_READ_HOLD_MS_8631) ForensicLogger.lifecycle("STOP_HELD_OFF_VENUE_MARK_UNREAD_8706",
                "mint=${ts.mint.take(10)} markSource=${ts.lastPriceSource} markPool=${ts.lastPricePoolAddr.take(8)} entryPool=${ts.position.entryPoolAddress.take(8)} unreadMs=${nowMs - first} action=hold_and_reask")
        } catch (_: Throwable) {}
        return !fires
    }

    /**
     * Executor.stopCorroboratedByQuote7928: the sell-time route quote did not come back — may this stop still sell? Yes when
     * it is not a price-loss stop, when the venue itself has a fresh read (it was asked on the request path and did not
     * refute), or when its mark may fire unread ([unreadStopFires8706], the wait counted from the request path's first ask).
     */
    fun sellsWithoutRouteQuote8706(ts: TokenState, reason: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (ts.position.isPaperPosition || !priceLossStop8489(reason)) return true
        // V5.0.8728 §AN_EXECUTABLE_READ_IS_READ_NOT_COUNTED — 8727 live, jjaemu (SHITCOIN): LANE_HARD_15PCT_SL fired on a mark
        // 15 % under the price, the executable quote refuted it nine times (SIGNAL_INVALIDATED_QUOTE_8521 x9), the tenth quote
        // took 3.9 s, past the emergency check budget, and the stop sold at -0.46 %. Here a fresh venue read meant "sell"
        // whatever it said. A fresh venue read, else the executable quote asked since this breach began, now decides by its
        // value: one that refutes the stop holds it ([venueRefutes8489], the same rule the request path applies).
        val read8728 = venuePnlPct8489(ts, nowMs) ?: executablePnlSinceAsk8681(ts, ts.position.positionId.ifBlank { ts.mint })
        if (read8728 != null) {
            val refutes = venueRefutes8489(read8728, stopDepthPct8489(reason) ?: impliedStopDepthPct8681(reason))
            if (refutes) try { PipelineHealthCollector.labelInc("STOP_HELD_NO_QUOTE_READ_REFUTES_8728") } catch (_: Throwable) {}
            return !refutes
        }
        val first = noReadSince8631[ts.position.positionId.ifBlank { ts.mint }] ?: nowMs
        val fires = unreadStopFires8706(first, nowMs, markPnlPct8706(ts), markOnVenue8706(ts))
        if (!fires) try { PipelineHealthCollector.labelInc("STOP_HELD_NO_ROUTE_QUOTE_OFF_VENUE_MARK_8706") } catch (_: Throwable) {}
        return fires
    }

    internal fun resetForTest8419() { awaitSince8419.clear(); noReadSince8631.clear() }
}

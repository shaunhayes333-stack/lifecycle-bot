package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8100 §THE_TAPE_FROM_THE_TRADES_THEMSELVES.
 *
 * Owner: "gecko terminal dex paprika never work. can we replace them or use an existing provider to cover that data?"
 * 5.0.8095: GeckoTerminal sr=0-55% (rate-limited / 5xx), DexPaprika 403 terminal, keyless OHLCV served 0 bars; the setup
 * classifier read DATA_INCOMPLETE on most fresh pump coins (SHITCOIN missing tape=1355 opp=1097 shape=1012 chg=933
 * bp=898) because buy pressure, price change and chart shape come from DexScreener rows / provider candles that a
 * fresh bonding-curve coin does not have yet.
 *
 * The PumpPortal trade stream (live since the key was funded: 1,071 trades in 4 minutes) carries every buy and sell on
 * the curve with the curve's reserves after it. This keeps, per mint, the EXECUTED trades of that one source — never a
 * mix of providers (the withdrawn 8094 tick-shape fallback mixed sources and misclassified) — and derives:
 *  - buy pressure: SOL-volume buy share over the last 5 minutes (>= [MIN_TRADES_BP] trades);
 *  - price change: 5 m from the trade at/before now-5m; 1 h from the trade at/before now-60m, or from the launch price
 *    when the coin is younger than an hour (it did not exist before its launch);
 *  - shape: run-up / vs-peak / drawdown over the last 24 one-minute closes (>= 3 minutes with trades).
 * LanePlaybook7907 reads it ONLY for an input its usual source left unobserved; it never overrides a provider reading.
 */
object TradeTape8100 {
    private const val KEEP_MS = 65L * 60_000L
    private const val MAX_TRADES = 800
    private const val MAX_MINTS = 400
    private const val MIN_TRADES_BP = 6
    // V5.0.8317 — 120 s → 300 s, matching [BP_WINDOW_MS]: a read two minutes after the last print returned null (STALE)
    // while its own buy-pressure window still held five minutes of prints (8314: swaps missing tape8100:STALE 2,491).
    private const val FRESH_MS = 300_000L
    private const val BP_WINDOW_MS = 5L * 60_000L

    // V5.0.8169 — [priceOnly]: a pool-state price (bonding-curve account / PumpSwap vault pair) with no side or size.
    // It states price (change, shape) but never buy pressure and never an executed print for the local bars.
    // V5.0.8285 — per-trade provenance: [atMs] is the EVENT time (the chain / provider timestamp when the feed states one,
    // else the receive time), [recvMs] when this process received it, [source] the feed, [pool] the venue it executed on
    // (a pool address, or [VENUE_CURVE_8285] / [VENUE_AMM_8285]; "" = the feed did not say), [signature] the transaction,
    // [tokenQty] the token side. [inferred]: a trade stated by two bonding-curve snapshots (CurveTicks7968.tradeBetween8252),
    // kept price-only — a snapshot difference is not an executed print and never counts as a buy, a sell or volume.
    internal class Trade(
        val atMs: Long, val priceSol: Double, val sol: Double, val isBuy: Boolean, val priceOnly: Boolean = false,
        val source: String = "", val pool: String = "", val signature: String = "", val recvMs: Long = atMs,
        val tokenQty: Double = 0.0, val inferred: Boolean = false,
    )
    private val tapes = ConcurrentHashMap<String, ArrayDeque<Trade>>()
    private val notes = AtomicLong(0)
    private val reads = AtomicLong(0)
    private val filled = AtomicLong(0)
    // V5.0.8169 — prints received per feed (pumpportal / helius / pumpswap / curve), for the tape8169 report line.
    private val bySource8169 = ConcurrentHashMap<String, AtomicLong>()
    const val SRC_HELIUS_8169 = "helius"
    const val SRC_PUMPSWAP_8169 = "pumpswap"
    const val SRC_CURVE_8169 = "curve"

    /** PumpFunWS: one executed curve trade (price = curve reserves after the trade, SOL per token). */
    fun note8100(mint: String, priceSol: Double, sol: Double, isBuy: Boolean, atMs: Long = System.currentTimeMillis(), source: String = "pumpportal") {
        if (mint.isBlank() || !priceSol.isFinite() || priceSol <= 0.0) return
        // V5.0.8285 — a curve-snapshot trade (DataOrchestrator.onCurveTrade8252) is price-only: never a buy, a sell or volume.
        val curve8285 = source == SRC_CURVE_8169
        add8169(mint, Trade(atMs, priceSol, if (sol.isFinite() && sol > 0.0) sol else 0.0, isBuy, priceOnly = curve8285,
            source = source, pool = poolKey8285(source, ""), recvMs = atMs, inferred = curve8285), atMs, source)
    }

    /**
     * V5.0.8285 — one executed trade with its provenance (PumpFunWS, ProofDataSwapTape8144, the GeckoTerminal pool-trades
     * fallback). [eventMs] is the feed's own timestamp (0 = the feed states none: the receive time stands in and no
     * latency is recorded). The same transaction delivered by two feeds is ONE trade: a signature already taped for
     * [mint] and side is refused here, whichever feed delivered it first; a trade without a signature cannot be matched
     * and is taped as delivered. Returns true when the print was taped.
     */
    fun note8285(
        mint: String, priceSol: Double, sol: Double, tokenQty: Double, isBuy: Boolean, source: String,
        pool: String = "", signature: String = "", eventMs: Long = 0L, recvMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (mint.isBlank() || !priceSol.isFinite() || priceSol <= 0.0) return false
        val src = source.ifBlank { "other" }
        val sig = signature.trim()
        if (sig.isNotBlank()) {
            val prior = seenSig8285.put("$mint|$sig|$isBuy", src)
            if (prior != null) {
                (if (prior == src) sameFeedDup8285 else crossFeedDup8285).incrementAndGet()
                return false
            }
            lastSigned8285["$src|$mint"] = recvMs
            // V5.0.8289 — hard cap: the 10 min TTL alone never held while every key was fresh.
            if (lastSigned8285.size > 8_000) MemoryBounds8289.capByAge8289(lastSigned8285, 8_000, 10L * 60_000L, recvMs) { it }
        } else unsigned8285.incrementAndGet()
        val eventKnown = eventMs > 0L && eventMs <= recvMs + 5_000L
        val at = if (eventKnown) eventMs else recvMs
        if (eventKnown) noteLatency8285(src, (recvMs - eventMs).coerceAtLeast(0L))
        else noEventTs8285.computeIfAbsent(src) { AtomicLong(0) }.incrementAndGet()
        // V5.0.8289 — the signature's work is done by [seenSig8285] (nothing reads Trade.signature): the tape keeps no
        // per-trade copy of it, and the venue / feed strings are shared instances instead of one parsed copy per print.
        val t = Trade(at, priceSol, if (sol.isFinite() && sol > 0.0) sol else 0.0, isBuy, source = MemoryBounds8289.intern8289(src),
            pool = MemoryBounds8289.intern8289(poolKey8285(src, pool)), signature = "", recvMs = recvMs,
            tokenQty = if (tokenQty.isFinite() && tokenQty > 0.0) tokenQty else 0.0)
        add8169(mint, t, at, src)
        return true
    }

    /** V5.0.8285 — did [source] deliver a SIGNED print of [mint] inside [windowMs]? (signature dedup is then authoritative) */
    fun signedFrom8285(mint: String, source: String, nowMs: Long, windowMs: Long): Boolean =
        lastSigned8285["$source|$mint"]?.let { nowMs - it in 0L..windowMs } == true

    const val VENUE_CURVE_8285 = "pump.curve"
    const val VENUE_AMM_8285 = "pump.amm"
    const val SRC_PUMPPORTAL_8285 = "pumpportal"
    const val SRC_GECKO_8285 = "gecko"

    /** Pure: the venue key of a print — its pool address when known, else the venue its feed implies ("" = unknown). */
    internal fun poolKey8285(source: String, pool: String): String = when {
        pool.isNotBlank() -> pool.trim()
        source == SRC_CURVE_8169 -> VENUE_CURVE_8285
        source == SRC_PUMPSWAP_8169 -> VENUE_AMM_8285
        else -> ""
    }

    /**
     * Pure: the one venue a mint's tape is read from — the pool of the newest executed print that names one, else of the
     * newest print that names one ("" = no print names a venue). Two pools of one token never blend into one series.
     */
    internal fun venueOf8285(trades: List<Trade>): String =
        trades.lastOrNull { !it.priceOnly && it.pool.isNotBlank() }?.pool
            ?: trades.lastOrNull { it.pool.isNotBlank() }?.pool ?: ""

    /** Pure: the prints compatible with [venue]: the venue's own, plus prints whose feed named no venue. */
    internal fun sameVenue8285(trades: List<Trade>, venue: String): List<Trade> =
        if (venue.isBlank()) trades else trades.filter { it.pool.isBlank() || it.pool == venue }

    /** Pure: transaction-count buy share (0..100) of executed prints' sides, or null below [MIN_TRADES_BP] trades. */
    fun buyPressureTx8285(sides: List<Boolean>): Double? {
        if (sides.size < MIN_TRADES_BP) return null
        return sides.count { it } * 100.0 / sides.size
    }

    private fun noteLatency8285(source: String, ms: Long) {
        val d = latency8285.computeIfAbsent(source) { java.util.ArrayDeque() }
        synchronized(d) { d.addLast(ms); while (d.size > 200) d.pollFirst() }
    }

    private fun latencyLine8285(): String = latency8285.entries.sortedBy { it.key }.joinToString(",") { e ->
        val xs = synchronized(e.value) { e.value.toList() }.sorted()
        if (xs.isEmpty()) "${e.key}=-" else "${e.key}:n=${xs.size},p50=${xs[xs.size / 2]}ms,max=${xs.last()}ms"
    }.ifBlank { "-" }

    /**
     * V5.0.8169 — a pool-state price for [mint] (SOL per token) from the pool the trades execute on: the bonding-curve
     * account (CurveTicks7968) or the PumpSwap vault pair (GradTicks8018). Price only: it feeds the tape's change and
     * shape, never its buy pressure (no side, no size) and never [prints8128].
     */
    fun notePrice8169(mint: String, priceSol: Double, source: String, atMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank() || !priceSol.isFinite() || priceSol <= 0.0) return
        add8169(mint, Trade(atMs, priceSol, 0.0, false, priceOnly = true), atMs, source)
    }

    private fun add8169(mint: String, t: Trade, atMs: Long, source: String) {
        val q = tapes.computeIfAbsent(mint) { ArrayDeque() }
        synchronized(q) {
            // V5.0.8285 — event-time order: a print whose event precedes the newest one (a fallback feed's older trade)
            // is inserted in place, so "newest" and "at/before" read the chain's order, not the arrival order.
            if (q.isEmpty() || q.last().atMs <= t.atMs) q.addLast(t) else {
                var i = q.size
                while (i > 0 && q[i - 1].atMs > t.atMs) i--
                q.add(i, t)
            }
            val newest = maxOf(atMs, q.last().atMs)
            while (q.size > MAX_TRADES || (q.isNotEmpty() && newest - q.first().atMs > KEEP_MS)) q.removeFirst()
        }
        if (t.priceOnly) {
            // V5.0.8285 FEATURE_ARRIVED_PRICE(mint)
            try { FeatureRevision8283.bumpThrottled8286(mint, FeatureKind8283.PRICE) } catch (_: Throwable) {}  // V5.0.8286
            (if (t.inferred) inferred8285 else priceOnly8285).incrementAndGet()
        } else {
            // V5.0.8285 FEATURE_ARRIVED_TAPE(mint)
            try { FeatureRevision8283.bumpThrottled8286(mint, FeatureKind8283.TAPE) } catch (_: Throwable) {}  // V5.0.8286
            executed8285.incrementAndGet()
        }
        notes.incrementAndGet()
        bySource8169.computeIfAbsent(source.ifBlank { "other" }) { AtomicLong(0) }.incrementAndGet()
        if (tapes.size > MAX_MINTS) {
            val drop = tapes.entries.sortedBy { e -> synchronized(e.value) { e.value.lastOrNull()?.atMs ?: 0L } }.take(MAX_MINTS / 10)
            for (e in drop) tapes.remove(e.key)
        }
    }

    data class Shape(val runupPct: Double, val vsPeak: Double, val ddPct: Double)
    // V5.0.8285 — [bp] stays the SOL-volume buy share; [bpTx8285] the transaction-count buy share, stated separately (a
    // few large buys and many small sells read differently on each); [buys8285]/[sells8285] the executed prints counted;
    // [venue8285] the one venue the readings came from ("" = no print named one).
    data class Read(
        val bp: Double?, val chg: Pair<Double, Double>?, val shape: Shape?,
        val bpTx8285: Double? = null, val buys8285: Int = 0, val sells8285: Int = 0, val venue8285: String = "",
    )

    /** Pure: SOL-volume buy share (0..100) of [trades], or null below [MIN_TRADES_BP] trades / no volume. */
    fun buyPressure8100(buys: List<Pair<Boolean, Double>>): Double? {
        if (buys.size < MIN_TRADES_BP) return null
        val tot = buys.sumOf { it.second }
        if (!(tot > 0.0)) return null
        return buys.filter { it.first }.sumOf { it.second } / tot * 100.0
    }

    /** Pure: run-up from the local low, current vs peak, drawdown from peak over [closes] (>= 3), as the stage router does. */
    fun shape8100(closes: List<Double>): Shape? {
        if (closes.size < 3 || closes.any { !it.isFinite() || it <= 0.0 }) return null
        val local = closes.takeLast(24)
        val cur = local.last(); val peak = local.maxOrNull() ?: return null; val low = local.minOrNull() ?: return null
        return Shape(((cur - low) / low * 100.0).coerceAtLeast(0.0), (cur / peak).coerceIn(0.0, 2.0), ((peak - cur) / peak * 100.0).coerceAtLeast(0.0))
    }

    internal const val YOUNG_BAR_MS_8220 = 15_000L
    internal const val YOUNG_MIN_SPAN_MS_8220 = 30_000L
    private val youngShapes8220 = java.util.concurrent.atomic.AtomicLong(0)

    /** Pure-ish: 15-second-bar shape from [trades] within the last 3 minutes, or null when it cannot be stated. */
    private fun youngShape8220(trades: List<Trade>, nowMs: Long): Shape? {
        val recent = trades.filter { !it.priceOnly && nowMs - it.atMs in 0L..3L * 60_000L && it.priceSol.isFinite() && it.priceSol > 0.0 }
        // V5.0.8236 — no executed prints on the paid stream (AUTH_DENIED) or none yet: the curve account's own price-only
        // reads (CurveTicks7968 / the fan-out's curve RPC, one pricing function of one account) state the same young shape.
        // Never mixed with executed prints: either set alone.
        val src = if (recent.size >= 3) recent else trades.filter { it.priceOnly && nowMs - it.atMs in 0L..3L * 60_000L && it.priceSol.isFinite() && it.priceSol > 0.0 }
        val curve8236 = src !== recent
        return youngShapeOf8236(src, curve8236)
    }

    /** Pure: the 15-second-bar shape of one source's prints spanning >= 30 s, or null. */
    internal fun youngShapeOf8236(src: List<Trade>, curve: Boolean): Shape? {
        if (src.size < 3 || src.last().atMs - src.first().atMs < YOUNG_MIN_SPAN_MS_8220) return null
        val bars = src.groupBy { it.atMs / YOUNG_BAR_MS_8220 }.toSortedMap().values.map { it.last().priceSol }
        if (bars.size < 3) return null
        return shape8100(bars)?.also {
            youngShapes8220.incrementAndGet()
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc(if (curve) "TAPE_YOUNG_SHAPE_CURVE_8236" else "TAPE_YOUNG_SHAPE_15S_8220") } catch (_: Throwable) {}
        }
    }

    /** LanePlaybook7907: the tape's readings for [mint] now, each null when the tape cannot state it. */
    fun read8100(mint: String, nowMs: Long = System.currentTimeMillis()): Read? {
        val q = tapes[mint] ?: return null
        val all8285 = synchronized(q) { q.toList() }
        // V5.0.8285 — one venue: prints from another pool of the same token never distort this venue's change, shape or flow.
        val venue8285 = venueOf8285(all8285)
        val trades = sameVenue8285(all8285, venue8285)
        if (trades.size < all8285.size) venueSplitReads8285.incrementAndGet()
        if (trades.isEmpty() || nowMs - trades.last().atMs > FRESH_MS) return null
        reads.incrementAndGet()
        val last = trades.last().priceSol
        val flow8285 = trades.filter { !it.priceOnly && nowMs - it.atMs in 0L..BP_WINDOW_MS }
        val bp = buyPressure8100(flow8285.map { it.isBuy to it.sol })
        val bpTx8285 = buyPressureTx8285(flow8285.map { it.isBuy })
        val chg = run {
            val launchAt = try { com.lifecyclebot.network.PumpCurveKeys7269.createdAtMs7280(mint) } catch (_: Throwable) { null }
            val launchPx = try { com.lifecyclebot.network.PumpCurveKeys7269.createPriceSol7280(mint) } catch (_: Throwable) { null }
            // V5.0.8173 — a launch younger than 5 minutes has no trade 5 minutes ago: its launch price is the base (as for 1 h).
            val base5m = LaunchCohort8173.windowBase8173(trades.lastOrNull { it.atMs <= nowMs - 5L * 60_000L }?.priceSol,
                launchPx, launchAt, nowMs, 5L * 60_000L) ?: return@run null
            val base1h = LaunchCohort8173.windowBase8173(trades.lastOrNull { it.atMs <= nowMs - 60L * 60_000L }?.priceSol,
                launchPx, launchAt, nowMs, 60L * 60_000L) ?: return@run null
            (last / base5m - 1.0) * 100.0 to (last / base1h - 1.0) * 100.0
        }
        val closes = trades.filter { nowMs - it.atMs in 0L..24L * 60_000L }
            .groupBy { it.atMs / 60_000L }.toSortedMap().values.map { it.last().priceSol }
        // V5.0.8220 — a young coin has fewer than three one-minute bars for its first minutes, so its shape stayed
        // UNKNOWN, no setup fired and every qualified specialist deferred (8218: "no setup fired; unobserved core inputs:
        // shape:UNKNOWN", MOONSHOT FDG_DATA_INCOMPLETE_DEFER_8136=760). Its own executed trades, ONE source, in 15-second
        // bars state the same run-up / peak / drawdown sooner. Requires >= 3 bars of real priced trades spanning >= 30 s;
        // never mixes sources, never fills a coin that already has minute bars.
        val shape = shape8100(closes) ?: youngShape8220(trades, nowMs)
        if (bp != null || chg != null || shape != null) filled.incrementAndGet()
        return Read(bp, chg, shape, bpTx8285, flow8285.count { it.isBuy }, flow8285.count { !it.isBuy }, venue8285)
    }

    /** LanePlaybook7907: an input the usual source left unobserved was filled from the trade tape. */
    fun noteFilled8100(feature: String) {
        try { PipelineHealthCollector.labelInc("TRADE_TAPE_FILLED_8100_$feature") } catch (_: Throwable) {}
    }

    /** V5.0.8128 — one executed curve print (SOL per token, SOL size), read by the chart backfill's keyless local rung. */
    data class Print8128(val atMs: Long, val priceSol: Double, val sol: Double, val isBuy: Boolean,
                         val source: String = "", val pool: String = "",   // V5.0.8285 — provenance
                         val priceOnly: Boolean = false)   // V5.0.8287 — a price observation, never a trade (no side, no size)

    /** V5.0.8128 — [mint]'s executed prints inside [windowMs] before [nowMs], oldest first (a copy; the tape is not changed). */
    fun prints8128(mint: String, nowMs: Long, windowMs: Long): List<Print8128> {
        val q = tapes[mint] ?: return emptyList()
        val all8285 = synchronized(q) { q.toList() }
        // V5.0.8285 — the bars are built from ONE venue's executed prints (a second pool never blends into its OHLCV).
        val exec = all8285.filter { !it.priceOnly }
        return sameVenue8285(exec, venueOf8285(exec)).filter { nowMs - it.atMs in 0L..windowMs }
            .map { Print8128(it.atMs, it.priceSol, it.sol, it.isBuy, it.source, it.pool) }
    }

    /**
     * V5.0.8287 — [mint]'s observed PRICES inside [windowMs] before [nowMs], oldest first, from ONE venue: executed prints
     * and price-only prints (curve snapshots, pool-state prices) alike, each flagged. They state price and shape only: a
     * price-only print carries no size and no side (sol = 0), so no reader can count it as volume or buy pressure.
     * Null when the tape has never seen the mint.
     */
    fun observedPrints8287(mint: String, nowMs: Long, windowMs: Long): List<Print8128>? {
        val q = tapes[mint] ?: return null
        val all = synchronized(q) { q.toList() }
        return sameVenue8285(all, venueOf8285(all)).filter { nowMs - it.atMs in 0L..windowMs && it.priceSol.isFinite() && it.priceSol > 0.0 }
            .map { Print8128(it.atMs, it.priceSol, if (it.priceOnly) 0.0 else it.sol, it.isBuy && !it.priceOnly, it.source, it.pool, it.priceOnly) }
    }

    /** V5.0.8287 — executed prints of [mint] inside [windowMs] (one venue), or null when the tape has never seen the mint. */
    fun executedCount8287(mint: String, nowMs: Long, windowMs: Long): Int? {
        val q = tapes[mint] ?: return null
        val all = synchronized(q) { q.toList() }
        val exec = all.filter { !it.priceOnly }
        return sameVenue8285(exec, venueOf8285(exec)).count { nowMs - it.atMs in 0L..windowMs }
    }

    /**
     * V5.0.8293 — RunTape8293's scan list: mints whose newest EXECUTED print is within [windowMs] of [nowMs] (bounded by
     * the tape's own [MAX_MINTS]). A copy; the tape is not changed.
     */
    fun activeMints8293(nowMs: Long, windowMs: Long): List<String> =
        tapes.entries.filter { (_, q) -> synchronized(q) { q.lastOrNull { !it.priceOnly }?.let { nowMs - it.atMs in 0L..windowMs } == true } }
            .map { it.key }

    fun statusLine8100(): String = "mints=${tapes.size} trades=${notes.get()} reads=${reads.get()} filledReads=${filled.get()} youngShape15s8220=${youngShapes8220.get()}"

    /** V5.0.8169 — why the tape could state nothing for [mint] now: NO_PRINTS (never received) or STALE (newest > [FRESH_MS]: 5 min since 8317). */
    fun absentWhy8169(mint: String, nowMs: Long = System.currentTimeMillis()): String {
        val q = tapes[mint] ?: return "NO_PRINTS"
        val last = synchronized(q) { q.lastOrNull()?.atMs } ?: return "NO_PRINTS"
        return if (nowMs - last > FRESH_MS) "STALE" else "TOO_FEW"
    }

    /** V5.0.8169 — mints whose newest print is fresh enough for [read8100] to state anything. */
    private fun freshMints8169(nowMs: Long): Int =
        tapes.values.count { q -> synchronized(q) { q.lastOrNull()?.let { nowMs - it.atMs in 0L..FRESH_MS } == true } }

    /** V5.0.8169 — tape8169[...]: which received prints reached the tape the setup features read. */
    fun statusLine8169(nowMs: Long = System.currentTimeMillis()): String =
        "tape8169[mintsWithTape=${tapes.size} fresh=${freshMints8169(nowMs)} prints=${notes.get()} printsBySource{" +
            bySource8169.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" } +
            "} reads=${reads.get()} filledReads=${filled.get()}] " + statusLine8285()

    // ── V5.0.8285 provenance bookkeeping ──
    private const val SEEN_SIG_CAP_8285 = 30_000
    private val seenSig8285: MutableMap<String, String> = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, String>(1024, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > SEEN_SIG_CAP_8285
        })
    private val lastSigned8285 = ConcurrentHashMap<String, Long>()
    private val latency8285 = ConcurrentHashMap<String, java.util.ArrayDeque<Long>>()
    private val noEventTs8285 = ConcurrentHashMap<String, AtomicLong>()
    private val crossFeedDup8285 = AtomicLong(0)
    private val sameFeedDup8285 = AtomicLong(0)
    private val unsigned8285 = AtomicLong(0)
    private val executed8285 = AtomicLong(0)
    private val priceOnly8285 = AtomicLong(0)
    private val inferred8285 = AtomicLong(0)
    private val venueSplitReads8285 = AtomicLong(0)

    /** V5.0.8285 — tape8285[...]: executed vs price-only prints, signature dedup across feeds, venue splits, feed latency. */
    fun statusLine8285(): String =
        "tape8285[executed=${executed8285.get()} priceOnly=${priceOnly8285.get()} curveInferredPriceOnly=${inferred8285.get()} " +
            "dupCrossFeed=${crossFeedDup8285.get()} dupSameFeed=${sameFeedDup8285.get()} unsigned=${unsigned8285.get()} " +
            "venueSplitReads=${venueSplitReads8285.get()} latency{${latencyLine8285()}} noEventTs{" +
            noEventTs8285.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" } + "}]"

    // ── V5.0.8289 — memory census and pressure trim (MemoryGuard7977 registry) ──
    /** ART bytes of one retained [Trade] (header, 5 longs/doubles, 3 flags, 3 shared refs, the deque slot). */
    const val TRADE_BYTES_8289 = 72L
    /** One [seenSig8285] entry: the "mint|signature|side" key (~140 chars) and its LinkedHashMap node. */
    private const val SEEN_SIG_BYTES_8289 = 230L

    fun tapeMints8289(): Int = tapes.size
    fun tradeCount8289(): Int {
        var n = 0
        for (q in tapes.values) n += synchronized(q) { q.size }
        return n
    }
    fun seenSigCount8289(): Int = seenSig8285.size
    internal fun lastSignedCount8289(): Int = lastSigned8285.size

    /** Census estimate: retained trades, the signature dedup window, the per-feed signed stamps and the tape headers. */
    fun estBytes8289(): Long = tradeCount8289() * TRADE_BYTES_8289 + seenSigCount8289() * SEEN_SIG_BYTES_8289 +
        lastSigned8285.size * 140L + tapes.size * 120L

    /**
     * Pressure trim. Always: a tape whose newest print is older than [KEEP_MS] (nothing reads it). HARD: unprotected
     * tapes quiet for 10 min go, every other unprotected tape keeps its newest 400 prints, the signature window keeps
     * its newest 10,000. CRITICAL: at most 150 unprotected tapes (most recent first) of 200 prints, 5,000 signatures.
     * [protect] (an open position, an active admission, a wallet-tracked mint) keeps its tape whole. Returns prints freed.
     */
    fun trim8289(hard: Boolean, critical: Boolean, nowMs: Long, protect: (String) -> Boolean): Int {
        var freed = 0
        val keepPrints = if (critical) 200 else 400
        val quietMs = 10L * 60_000L
        val rows = tapes.entries.map { e -> Triple(e.key, e.value, synchronized(e.value) { e.value.lastOrNull()?.atMs ?: 0L }) }
        var unprotectedKept = 0
        for ((mint, q, newest) in rows.sortedByDescending { it.third }) {
            val stale = nowMs - newest > KEEP_MS
            val isProtected = !stale && (try { protect(mint) } catch (_: Throwable) { true })
            val drop = stale || (!isProtected && hard && (nowMs - newest > quietMs || (critical && unprotectedKept >= 150)))
            if (drop) { if (tapes.remove(mint, q)) freed += synchronized(q) { q.size }; continue }
            if (isProtected || !hard) continue
            unprotectedKept++
            synchronized(q) { while (q.size > keepPrints) { q.removeFirst(); freed++ } }
        }
        if (hard) MemoryBounds8289.trimEldest8289(seenSig8285, if (critical) 5_000 else 10_000)
        MemoryBounds8289.capByAge8289(lastSigned8285, if (hard) 2_000 else 8_000, 10L * 60_000L, nowMs) { it }
        return freed
    }

    /** V5.0.8360 — the newest observed price on [mint]'s tape (SOL per token, its event time): an executed print or a
     *  pool-state price, never synthesised; null when the tape holds none. ShadowMark8360 ages it. */
    fun lastPrint8360(mint: String): Pair<Double, Long>? {
        val q = tapes[mint] ?: return null
        val t = synchronized(q) { q.lastOrNull() } ?: return null
        return if (t.priceSol.isFinite() && t.priceSol > 0.0 && t.atMs > 0L) t.priceSol to t.atMs else null
    }

    internal fun resetForTest8100() {
        tapes.clear(); notes.set(0); reads.set(0); filled.set(0); bySource8169.clear()
        seenSig8285.clear(); lastSigned8285.clear(); latency8285.clear(); noEventTs8285.clear()
        crossFeedDup8285.set(0); sameFeedDup8285.set(0); unsigned8285.set(0); executed8285.set(0)
        priceOnly8285.set(0); inferred8285.set(0); venueSplitReads8285.set(0)
    }
}

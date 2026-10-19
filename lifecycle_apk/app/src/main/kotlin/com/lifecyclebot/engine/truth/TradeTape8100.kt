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
    private const val FRESH_MS = 120_000L
    private const val BP_WINDOW_MS = 5L * 60_000L

    // V5.0.8169 — [priceOnly]: a pool-state price (bonding-curve account / PumpSwap vault pair) with no side or size.
    // It states price (change, shape) but never buy pressure and never an executed print for the local bars.
    internal class Trade(val atMs: Long, val priceSol: Double, val sol: Double, val isBuy: Boolean, val priceOnly: Boolean = false)
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
        add8169(mint, Trade(atMs, priceSol, if (sol.isFinite() && sol > 0.0) sol else 0.0, isBuy), atMs, source)
    }

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
            q.addLast(t)
            while (q.size > MAX_TRADES || (q.isNotEmpty() && atMs - q.first().atMs > KEEP_MS)) q.removeFirst()
        }
        notes.incrementAndGet()
        bySource8169.computeIfAbsent(source.ifBlank { "other" }) { AtomicLong(0) }.incrementAndGet()
        if (tapes.size > MAX_MINTS) {
            val drop = tapes.entries.sortedBy { e -> synchronized(e.value) { e.value.lastOrNull()?.atMs ?: 0L } }.take(MAX_MINTS / 10)
            for (e in drop) tapes.remove(e.key)
        }
    }

    data class Shape(val runupPct: Double, val vsPeak: Double, val ddPct: Double)
    data class Read(val bp: Double?, val chg: Pair<Double, Double>?, val shape: Shape?)

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
        val trades = synchronized(q) { q.toList() }
        if (trades.isEmpty() || nowMs - trades.last().atMs > FRESH_MS) return null
        reads.incrementAndGet()
        val last = trades.last().priceSol
        val bp = buyPressure8100(trades.filter { !it.priceOnly && nowMs - it.atMs in 0L..BP_WINDOW_MS }.map { it.isBuy to it.sol })
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
        return Read(bp, chg, shape)
    }

    /** LanePlaybook7907: an input the usual source left unobserved was filled from the trade tape. */
    fun noteFilled8100(feature: String) {
        try { PipelineHealthCollector.labelInc("TRADE_TAPE_FILLED_8100_$feature") } catch (_: Throwable) {}
    }

    /** V5.0.8128 — one executed curve print (SOL per token, SOL size), read by the chart backfill's keyless local rung. */
    data class Print8128(val atMs: Long, val priceSol: Double, val sol: Double, val isBuy: Boolean)

    /** V5.0.8128 — [mint]'s executed prints inside [windowMs] before [nowMs], oldest first (a copy; the tape is not changed). */
    fun prints8128(mint: String, nowMs: Long, windowMs: Long): List<Print8128> {
        val q = tapes[mint] ?: return emptyList()
        return synchronized(q) { q.filter { !it.priceOnly && nowMs - it.atMs in 0L..windowMs }.map { Print8128(it.atMs, it.priceSol, it.sol, it.isBuy) } }
    }

    fun statusLine8100(): String = "mints=${tapes.size} trades=${notes.get()} reads=${reads.get()} filledReads=${filled.get()} youngShape15s8220=${youngShapes8220.get()}"

    /** V5.0.8169 — why the tape could state nothing for [mint] now: NO_PRINTS (never received) or STALE (newest > 2 min). */
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
            "} reads=${reads.get()} filledReads=${filled.get()}]"

    internal fun resetForTest8100() { tapes.clear(); notes.set(0); reads.set(0); filled.set(0); bySource8169.clear() }
}

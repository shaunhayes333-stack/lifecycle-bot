package com.lifecyclebot.perps

import com.lifecyclebot.engine.StrategyTelemetry
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8883 §THE_CRYPTO_TWIN (plan W1) — owner, 20 Nov: "paper is meant to turn off when live is running close out all
 * trades and the shadow trader runs while live does!" / "the shadow trading engine is meant to basically be exactly the
 * same as the live trader in all forms and functions for the meme and crypto traders just building authority driving
 * learning etc to unlock the lanes".
 *
 * Since 8872 paper is off while LIVE runs, so the crypto lanes' own paper record — the record the owner chose to let them
 * trade live on (CryptoSpotPaperBacking8788, 8806) — stopped growing: it could no longer stop backing a lane that stops
 * paying, nor start backing one that starts. The meme lanes had their shadows; crypto had none.
 *
 * This is the crypto twin. Beside LIVE only, every crypto candidate the live decision built — the position it opened, or the
 * one a learned door (not an integrity refusal: those bind both engines) stopped it from opening — is held here as a
 * virtual position: the same entry price, size, take-profit, stop, leverage, reasons and learned hold the live book gives
 * it. Each monitor pass it is managed by the live book's own exit functions (CryptoAltTrader.evict8883 on the previous mark,
 * then CryptoAltTrader.exitTick8883 on a fresh one), on a mark read the way the monitor reads one. A partial is deferred, as
 * LIVE defers every partial. A close is booked at the mark that fired it, gross, as the paper book booked its closes. A twin
 * whose mark stays unread for 10 minutes is dropped, never booked (nothing is invented).
 *
 * Its closes form one record per spot lane (CRYPTO_SPOT / CRYPTO_ALT), graded by the leaderboard's own computation
 * (StrategyTelemetry.metricOf8883). In LIVE with paper off, CryptoSpotPaperBacking8788 reads it in place of the stopped paper
 * record once it holds 20 decisive closes; until then the paper record stands. The record survives restarts; open twins do
 * not (a restart drops them, counted).
 */
object CryptoTwin8883 {
    private const val MAX_OPEN_8883 = 60
    private const val KEEP_CLOSES_8883 = 400
    private const val LOST_MARK_MS_8883 = 10L * 60_000L
    private const val PREFS_8883 = "aate_crypto_twin_8883"

    // V5.0.8886 — [cell] is the trend cell the position's reasons carry (CryptoTrend8886); each close is filed under lane|cell too
    private class Twin(@Volatile var pos: CryptoAltTrader.AltPosition, val lane: String, val cell: String? = null) {
        @Volatile var rungIdx = 0
        @Volatile var trailPeak: Double? = null
        @Volatile var strike = false
        @Volatile var lastFreshAtMs = System.currentTimeMillis()
    }

    private val open = ConcurrentHashMap<String, Twin>()                        // asset key -> twin
    private val closes = ConcurrentHashMap<String, ArrayDeque<DoubleArray>>()   // lane (or lane|cell, 8886) -> (atMs, pnl %, size SOL)
    private val byKind = ConcurrentHashMap<String, AtomicLong>()
    private val opened = AtomicLong(0L)
    private val duplicate = AtomicLong(0L)
    private val full = AtomicLong(0L)
    private val lost = AtomicLong(0L)
    private val ticking = AtomicBoolean(false)
    @Volatile private var loaded = false

    private val NO_SINK_8883 = object : CryptoAltTrader.ExitSink8883 {
        override fun warn(msg: String) {}
        override fun label(name: String) {}
        override fun forensic(event: String, detail: String) {}
        override fun published(p: CryptoAltTrader.AltPosition) {}
    }

    /**
     * CryptoAltTrader.executeSignal (LIVE only): [p] is the position the live decision opened, or the one a learned door
     * refused. One twin per asset at a time, at most [MAX_OPEN_8883].
     */
    fun open8883(p: CryptoAltTrader.AltPosition, lane: String) {
        val l = lane.trim().uppercase()
        if (l.isBlank() || !p.entryPrice.isFinite() || p.entryPrice <= 0.0 || !p.sizeSol.isFinite() || p.sizeSol <= 0.0) return
        val key = p.canonicalAssetKey.ifBlank { p.market.name }
        if (open.containsKey(key)) { duplicate.incrementAndGet(); return }
        if (open.size >= MAX_OPEN_8883) { full.incrementAndGet(); return }
        val twin = p.copy(id = "TWIN8883:" + p.id, isPaper = false, currentPrice = p.entryPrice, highestPnlPct = 0.0)
        if (open.putIfAbsent(key, Twin(twin, l, CryptoTrend8886.cellOfReasons8886(p.reasons))) == null) opened.incrementAndGet()
    }

    fun hasOpen8883(): Boolean = open.isNotEmpty()

    /** CryptoAltTrader.monitorPositions (LIVE), on its own coroutine: one pass over every twin, one pass at a time. */
    suspend fun tick8883() {
        if (!ticking.compareAndSet(false, true)) return
        try {
            for ((key, t) in open.entries.toList()) {
                try { step8883(key, t, System.currentTimeMillis()) }
                catch (e: CancellationException) { throw e }
                catch (_: Throwable) {}
            }
        } finally { ticking.set(false) }
    }

    private suspend fun step8883(key: String, t: Twin, nowMs: Long) {
        val pos = t.pos
        // the eviction pass on the previous mark, as the live monitor runs it before it refreshes the mark
        if (!pos.isDynamic || pos.hasTrustedMark(nowMs)) {
            val ev = CryptoAltTrader.evict8883(pos.getPnlPct(), nowMs - pos.openTime, t.rungIdx, t.trailPeak)
            t.trailPeak = ev.trailPeak
            if (ev.rungFired >= 0) t.rungIdx = ev.rungIdx
            if (ev.reason != null) { close8883(key, t, pos, ev.kind, nowMs); return }
        }
        // a fresh mark, read as the live monitor reads one (a dynamic asset by exact identity; a static market from its feed)
        val mark: Double
        val markKey: String
        val markAtMs: Long
        if (pos.isDynamic) {
            val m = CryptoAltTrader.twinMark8883(pos.canonicalAssetKey)
            if (m == null) { lostIfStale8883(key, t, nowMs); return }
            mark = m.price; markKey = pos.canonicalAssetKey; markAtMs = m.observedAtMs
        } else {
            val d = PerpsMarketDataFetcher.getMarketData(pos.market)
            if (!d.price.isFinite() || d.price <= 0.0) { lostIfStale8883(key, t, nowMs); return }
            val ratio = d.price / pos.entryPrice
            if (ratio > 10.0 || ratio < 0.1) return   // the static feeds' spike guard
            mark = d.price; markKey = pos.market.name; markAtMs = nowMs
        }
        t.lastFreshAtMs = nowMs
        val updated = pos.copy(currentPrice = mark, markAssetKey = markKey, markUpdatedAtMs = markAtMs)
        t.pos = updated
        val v = CryptoAltTrader.exitTick8883(updated.id, updated, mark, markKey, t.strike, NO_SINK_8883)
        t.strike = v.strikeAfter
        // PARTIAL: the live book defers every partial in LIVE (LIVE_PARTIAL_DEFERRED_NO_CONFIRMED_ADAPTER_6566_CRYPTO); so does the twin
        if (v.act == CryptoAltTrader.ExitAct8883.CLOSE) close8883(key, t, updated, v.kind, nowMs)
    }

    private fun lostIfStale8883(key: String, t: Twin, nowMs: Long) {
        if (nowMs - t.lastFreshAtMs <= LOST_MARK_MS_8883) return
        if (open.remove(key, t)) lost.incrementAndGet()
    }

    private fun close8883(key: String, t: Twin, at: CryptoAltTrader.AltPosition, kind: String, nowMs: Long) {
        if (!open.remove(key, t)) return
        val pnl = at.getPnlPct()
        if (!pnl.isFinite()) { lost.incrementAndGet(); return }
        ensureLoaded8883()
        for (k in listOfNotNull(t.lane, t.cell?.let { "${t.lane}$CELL_SEP_8886$it" })) {
            val q = closes.computeIfAbsent(k) { ArrayDeque() }
            synchronized(q) {
                q.addLast(doubleArrayOf(nowMs.toDouble(), pnl, at.sizeSol))
                while (q.size > KEEP_CLOSES_8883) q.removeFirst()
            }
        }
        byKind.computeIfAbsent(kind.ifBlank { "OTHER" }) { AtomicLong(0L) }.incrementAndGet()
        persist8883()
    }

    /** Pure: the strategy rows of closes [(atMs, pnl %, size SOL)], as the paper book booked them (pnl SOL = size x pnl %). */
    internal fun rows8883(closes: List<DoubleArray>): List<StrategyTelemetry.Row8883> =
        closes.map { StrategyTelemetry.Row8883(it[1], it[2] * it[1] / 100.0, it[2], it[2]) }

    /** The twin's record of [lane], graded by the leaderboard's own computation; null with no close. */
    fun metric8883(lane: String): StrategyTelemetry.StrategyMetric? {
        ensureLoaded8883()
        val q = closes[lane.trim().uppercase()] ?: return null
        val rows = synchronized(q) { rows8883(q.toList()) }
        return if (rows.isEmpty()) null else StrategyTelemetry.metricOf8883(lane.trim().uppercase(), rows)
    }

    internal const val CELL_SEP_8886 = "|"

    /** V5.0.8886 — the twin's record of [lane]'s trend [cell]: the leaderboard metric and its (n, mean, se, pf) proof; null with no close. */
    fun cellRecord8886(lane: String, cell: String): Pair<StrategyTelemetry.StrategyMetric, DoubleArray>? {
        ensureLoaded8883()
        val k = lane.trim().uppercase() + CELL_SEP_8886 + cell.trim().uppercase()
        val q = closes[k] ?: return null
        val list = synchronized(q) { q.toList() }
        if (list.isEmpty()) return null
        return StrategyTelemetry.metricOf8883(k, rows8883(list)) to CryptoTrend8886.proof8886(list.map { it[1] })
    }

    /** V5.0.8886 — how many of [lane]'s cells are graded (hold the lane rule's decisive count): the family the cell margin holds for. */
    fun gradedCells8886(lane: String): Int {
        ensureLoaded8883()
        val prefix = lane.trim().uppercase() + CELL_SEP_8886
        return closes.keys.count { k -> k.startsWith(prefix) && cellRecord8886(lane, k.removePrefix(prefix))
            ?.first?.let { it.wins + it.losses >= com.lifecyclebot.engine.truth.CryptoSpotPaperBacking8788.MIN_DECISIVE_8788 } == true }
    }

    private fun prefs8883(): android.content.SharedPreferences? = try {
        com.lifecyclebot.AATEApp.appContextOrNull()?.getSharedPreferences(PREFS_8883, android.content.Context.MODE_PRIVATE)
    } catch (_: Throwable) { null }

    /** Pure: one lane's closes as stored ("at,pnl,size;..."). */
    internal fun encode8883(q: List<DoubleArray>): String = q.joinToString(";") { "${it[0].toLong()},${it[1]},${it[2]}" }

    /** Pure: a lane's stored closes; a malformed entry is skipped. */
    internal fun decode8883(s: String?): List<DoubleArray> = s.orEmpty().split(';').mapNotNull { e ->
        val f = e.split(',')
        if (f.size != 3) null else {
            val at = f[0].toLongOrNull(); val pnl = f[1].toDoubleOrNull(); val size = f[2].toDoubleOrNull()
            if (at == null || pnl == null || size == null || !pnl.isFinite() || !size.isFinite()) null
            else doubleArrayOf(at.toDouble(), pnl, size)
        }
    }

    @Synchronized private fun ensureLoaded8883() {
        if (loaded) return
        loaded = true
        val p = prefs8883() ?: return
        for (k in p.all.keys) {
            val q = ArrayDeque(decode8883(p.getString(k, "")).takeLast(KEEP_CLOSES_8883))
            closes.merge(k, q) { old, new -> ArrayDeque((new + old).takeLast(KEEP_CLOSES_8883)) }
        }
    }

    private fun persist8883() {
        val p = prefs8883() ?: return
        try {
            val e = p.edit()
            for ((lane, q) in closes) e.putString(lane, synchronized(q) { encode8883(q.toList()) })
            e.apply()
        } catch (_: Throwable) {}
    }

    /** Brief: open twins, the record per lane (n / decisive / mean / SOL) and how each twin left. */
    fun statusLine8883(): String {
        val recs = closes.keys.filter { !it.contains(CELL_SEP_8886) }.sorted().joinToString(" ") { lane ->
            val m = metric8883(lane)
            if (m == null) "$lane=-" else "$lane{n=${m.trades} W${m.wins}/L${m.losses} mean=${"%+.1f".format(m.meanPnlPct)}% sol=${"%+.3f".format(m.totalSolPnl)} pfEdge=${"%+.1f".format(m.pfExpectancyPp)}}"
        }.ifBlank { "-" }
        val kinds = byKind.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        val cells = closes.keys.count { it.contains(CELL_SEP_8886) }
        return "cryptoTwin8883[open=${open.size}/$MAX_OPEN_8883 opened=${opened.get()} dup=${duplicate.get()} full=${full.get()} lost=${lost.get()} " +
            "records{$recs} cells8886=$cells exits{$kinds} rule=beside LIVE: every crypto candidate the live decision opened or a learned door refused, on the live exit functions, virtual fill, gross as the paper book]"
    }

    internal fun resetForTest8883() { open.clear(); closes.clear(); byKind.clear(); opened.set(0); duplicate.set(0); full.set(0); lost.set(0); loaded = true }
    internal fun addCloseForTest8883(lane: String, atMs: Long, pnl: Double, size: Double) {
        closes.computeIfAbsent(lane) { ArrayDeque() }.addLast(doubleArrayOf(atMs.toDouble(), pnl, size))
    }
}

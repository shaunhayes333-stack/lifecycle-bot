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
 *
 * V5.0.8910 §EACH_STRATEGY_ITS_OWN_RECORD — owner, 21 Nov: "all 5 now please one build". Each close is also filed under the
 * strategy the position was opened as (its STRATEGY7803 reason: a desk, the classic analyser, or a MajorSetups8910 setup) as
 * lane|S=<strategy> ([strategyKey8910]), and, for the brief only, under that strategy by the UTC session it opened in and by
 * BTC's regime at the open (lane|S=<strategy>@<session>, @BTC_<regime>). The strategy record is read by
 * CryptoSpotPaperBacking8788 exactly as a trend cell's is (backs on its own proof, withdraws when proven losing); the session
 * and regime records are graded and printed, they decide nothing yet. A strategy record keeps [KEEP_STRATEGY_8910] closes, a
 * session / regime record [KEEP_SLICE_8910], and at most [MAX_STRATEGY_KEYS_8910] such keys are held.
 */
object CryptoTwin8883 {
    private const val MAX_OPEN_8883 = 60
    private const val KEEP_CLOSES_8883 = 400
    private const val LOST_MARK_MS_8883 = 10L * 60_000L
    private const val PREFS_8883 = "aate_crypto_twin_8883"

    // V5.0.8886 — [cell] is the trend cell the position's reasons carry (CryptoTrend8886); each close is filed under lane|cell too
    private class Twin(@Volatile var pos: CryptoAltTrader.AltPosition, val lane: String, val cell: String? = null,
                       val slices8910: List<String> = emptyList()) {
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
        if (open.putIfAbsent(key, Twin(twin, l, CryptoTrend8886.cellOfReasons8886(p.reasons), slices8910(l, p.reasons, p.openTime))) == null) opened.incrementAndGet()
    }

    fun hasOpen8883(): Boolean = open.isNotEmpty() || ghosts8924.isNotEmpty()   // V5.0.8924 — a hold-max ghost is marked on the same pass

    /** CryptoAltTrader.monitorPositions (LIVE), on its own coroutine: one pass over every twin, one pass at a time. */
    suspend fun tick8883() {
        if (!ticking.compareAndSet(false, true)) return
        try {
            for ((key, t) in open.entries.toList()) {
                try { step8883(key, t, System.currentTimeMillis()) }
                catch (e: CancellationException) { throw e }
                catch (_: Throwable) {}
            }
            for ((key, g) in ghosts8924.entries.toList()) {   // V5.0.8924
                try { stepGhost8924(key, g, System.currentTimeMillis()) }
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
        val filed8915 = ArrayList<String>(8)
        for (k in listOfNotNull(t.lane, t.cell?.let { "${t.lane}$CELL_SEP_8886$it" }) + t.slices8910) {
            if (k.contains(STRATEGY_MARK_8910) && !closes.containsKey(k) && closes.keys.count { it.contains(STRATEGY_MARK_8910) } >= MAX_STRATEGY_KEYS_8910) continue
            val keep = when { !k.contains(STRATEGY_MARK_8910) -> KEEP_CLOSES_8883; k.contains('@') -> KEEP_SLICE_8910; else -> KEEP_STRATEGY_8910 }
            val q = closes.computeIfAbsent(k) { ArrayDeque() }
            synchronized(q) {
                q.addLast(doubleArrayOf(nowMs.toDouble(), pnl, at.sizeSol))
                while (q.size > keep) q.removeFirst()
            }
            filed8915 += k
        }
        byKind.computeIfAbsent(kind.ifBlank { "OTHER" }) { AtomicLong(0L) }.incrementAndGet()
        persist8883(filed8915)
        // V5.0.8924 — a close on the one-hour hold cap is followed one more hour as a ghost: what holding on would have done
        if (kind.contains("HOLD_MAX") && ghosts8924.size < MAX_GHOSTS_8924) ghosts8924.putIfAbsent(key, Ghost8924(t.lane, at, pnl, nowMs))
    }

    // ── V5.0.8924 §WHAT_THE_HOUR_CAP_COSTS — owner, 22 Nov 00:39: "do all 5 now". The desks' hold is capped at one hour
    //    (adaptiveMaxHoldSeconds coerceIn(60, 3600)) and ADAPTIVE_HOLD_MAX is the twin's most common exit (40 of ~90 closes on
    //    21 Nov 23:11). Whether the cap cuts trades that would have paid is now measured, not assumed: each hold-max close is
    //    marked again [HOLD_EXT_MS_8924] later (the same mark the twin reads), and the pair (pnl at the cap, pnl an hour on)
    //    is filed per lane. [holdExtends8924] is true once a lane's extra hour is PROVEN better (30+ pairs, mean gain minus
    //    two standard errors above zero); the desk's cap then gains that hour for that lane (at most two hours, CryptoAltTrader). Unproven or
    //    worse, the cap stays one hour. ──
    internal const val HOLD_EXT_SEC_8924 = 3_600
    internal const val HOLD_EXT_MS_8924 = HOLD_EXT_SEC_8924 * 1_000L
    private const val MAX_GHOSTS_8924 = 60
    private const val GHOST_GRACE_MS_8924 = 15L * 60_000L
    private const val KEEP_PAIRS_8924 = 200
    private class Ghost8924(val lane: String, val pos: CryptoAltTrader.AltPosition, val capPnl: Double, val atMs: Long)
    private val ghosts8924 = java.util.concurrent.ConcurrentHashMap<String, Ghost8924>()
    private val holdPairs8924 = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<DoubleArray>>()

    private suspend fun stepGhost8924(key: String, g: Ghost8924, nowMs: Long) {
        if (nowMs - g.atMs < HOLD_EXT_MS_8924) return
        val mark = if (g.pos.isDynamic) CryptoAltTrader.twinMark8883(g.pos.canonicalAssetKey)?.price
            else PerpsMarketDataFetcher.getMarketData(g.pos.market).price.takeIf { it.isFinite() && it > 0.0 && it / g.pos.entryPrice in 0.1..10.0 }
        if (mark == null || !mark.isFinite() || mark <= 0.0) {
            if (nowMs - g.atMs > HOLD_EXT_MS_8924 + GHOST_GRACE_MS_8924) ghosts8924.remove(key)   // no mark within the grace: no pair, never a guess
            return
        }
        ghosts8924.remove(key)
        val ext = g.pos.copy(currentPrice = mark).getPnlPct()
        if (!ext.isFinite()) return
        loadPairs8924()
        val q = holdPairs8924.computeIfAbsent(g.lane) { ArrayDeque() }
        val enc = synchronized(q) { q.addLast(doubleArrayOf(g.capPnl, ext)); while (q.size > KEEP_PAIRS_8924) q.removeFirst(); encodePairs8924(q.toList()) }
        try { pairPrefs8924()?.edit()?.putString(g.lane, enc)?.apply() } catch (_: Throwable) {}   // its own file: the twin's records load every key of theirs
    }

    @Volatile private var pairsLoaded8924 = false
    private fun pairPrefs8924(): android.content.SharedPreferences? = try {
        com.lifecyclebot.AATEApp.appContextOrNull()?.getSharedPreferences(PAIR_PREFS_8924, android.content.Context.MODE_PRIVATE)
    } catch (_: Throwable) { null }
    private const val PAIR_PREFS_8924 = "crypto_twin_hold_ext_8924"

    @Synchronized private fun loadPairs8924() {
        if (pairsLoaded8924) return
        pairsLoaded8924 = true
        val p = pairPrefs8924() ?: return
        for (k in p.all.keys) holdPairs8924.merge(k, ArrayDeque(decodePairs8924(p.getString(k, "")).takeLast(KEEP_PAIRS_8924))) { old, new -> ArrayDeque((new + old).takeLast(KEEP_PAIRS_8924)) }
    }

    /** Pure (V5.0.8924): a lane's hold pairs as stored ("cap,ext;..."). */
    internal fun encodePairs8924(q: List<DoubleArray>): String = q.joinToString(";") { "${it[0]},${it[1]}" }

    /** Pure (V5.0.8924): stored hold pairs; a malformed or non-finite entry is skipped. */
    internal fun decodePairs8924(s: String?): List<DoubleArray> = s.orEmpty().split(';').mapNotNull { e ->
        val f = e.split(',')
        val a = f.getOrNull(0)?.toDoubleOrNull(); val b = f.getOrNull(1)?.toDoubleOrNull()
        if (f.size != 2 || a == null || b == null || !a.isFinite() || !b.isFinite()) null else doubleArrayOf(a, b)
    }

    /** Pure (V5.0.8924): (n, mean gain of the extra hour, its standard error) over [pairs] of (pnl at the cap, pnl an hour on). */
    internal fun holdGain8924(pairs: List<DoubleArray>): DoubleArray {
        val d = pairs.map { it[1] - it[0] }
        if (d.isEmpty()) return doubleArrayOf(0.0, Double.NaN, Double.NaN)
        val m = d.average()
        val se = if (d.size < 2) Double.NaN else kotlin.math.sqrt(d.sumOf { (it - m) * (it - m) } / (d.size - 1) / d.size)
        return doubleArrayOf(d.size.toDouble(), m, se)
    }

    /** Pure (V5.0.8924): does a gain record (n, mean, se) prove the extra hour pays? */
    internal fun extends8924(g: DoubleArray): Boolean = g[0] >= 30.0 && g[1].isFinite() && g[2].isFinite() && g[1] - 2.0 * g[2] > 0.0

    /** V5.0.8924 — CryptoAltTrader: may [lane]'s desk hold two hours (its extra hour proven to pay on the twin)? */
    fun holdExtends8924(lane: String?): Boolean = try {
        loadPairs8924()
        val q = lane?.let { holdPairs8924[it.trim().uppercase()] }
        q != null && extends8924(holdGain8924(synchronized(q) { q.toList() }))
    } catch (_: Throwable) { false }

    fun holdLine8924(): String {
        loadPairs8924()
        val body = holdPairs8924.entries.sortedBy { it.key }.joinToString(" ") { (l, q) ->
            val g = holdGain8924(synchronized(q) { q.toList() })
            "$l{n${g[0].toInt()} extraHour${"%+.1f".format(g[1])}%±${"%.1f".format(g[2])}${if (extends8924(g)) " EXTENDS" else ""}}"
        }.ifBlank { "-" }
        return "holdCap8924[ghosts=${ghosts8924.size} $body rule=each hold-max twin close marked again 1 h on; a lane's cap becomes 2 h once the extra hour is proven (n30, mean-2SE>0)]"
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

    // ── V5.0.8910: the strategy records ──
    internal const val STRATEGY_MARK_8910 = "|S="
    internal const val KEEP_STRATEGY_8910 = 200
    internal const val KEEP_SLICE_8910 = 80
    internal const val MAX_STRATEGY_KEYS_8910 = 240

    /** Pure: the record key of [lane]'s [strategy] (upper-cased; ':' '|' '@' and spaces become '_'); null for a blank strategy. */
    fun strategyKey8910(lane: String, strategy: String): String? {
        val st = strategy.trim().uppercase().replace(Regex("[:|@ ]"), "_").take(48)
        return if (st.isBlank() || lane.isBlank()) null else lane.trim().uppercase() + STRATEGY_MARK_8910 + st
    }

    /** Pure: the keys one close of [lane] is filed under for its strategy: the strategy, @session (of [openMs]) and @BTC_<regime>. */
    internal fun slices8910(lane: String, reasons: List<String>, openMs: Long, regime: String? = null): List<String> {
        val strategy = reasons.firstOrNull { it.startsWith("STRATEGY7803=") }?.removePrefix("STRATEGY7803=").orEmpty()
        val base = strategyKey8910(lane, strategy) ?: return emptyList()
        val session = reasons.firstOrNull { it.startsWith(MajorSetups8910.SESSION_TAG_8910) }?.removePrefix(MajorSetups8910.SESSION_TAG_8910)
            ?: MajorSetups8910.session8910(if (openMs > 0L) openMs else System.currentTimeMillis())
        val reg = reasons.firstOrNull { it.startsWith(MajorSetups8910.REGIME_TAG_8910) }?.removePrefix(MajorSetups8910.REGIME_TAG_8910)
            ?: regime ?: MajorSetups8910.regimeNow8910()
        return listOf(base, "$base@$session", "$base@BTC_$reg")
    }

    /** V5.0.8910 — the twin's record of [lane]'s [strategy]: the leaderboard metric and its (n, mean, se, pf) proof; null with no close. */
    fun strategyRecord8910(lane: String, strategy: String): Pair<StrategyTelemetry.StrategyMetric, DoubleArray>? {
        ensureLoaded8883()
        val k = strategyKey8910(lane, strategy) ?: return null
        val q = closes[k] ?: return null
        val list = synchronized(q) { q.toList() }
        if (list.isEmpty()) return null
        return StrategyTelemetry.metricOf8883(k, rows8883(list)) to CryptoTrend8886.proof8886(list.map { it[1] })
    }

    /** V5.0.8910 — how many of [lane]'s strategies are graded (hold the lane rule's decisive count): the family their margin holds for. */
    fun gradedStrategies8910(lane: String): Int {
        ensureLoaded8883()
        val prefix = lane.trim().uppercase() + STRATEGY_MARK_8910
        return closes.keys.count { k -> k.startsWith(prefix) && !k.contains('@') && strategyRecord8910(lane, k.removePrefix(prefix))
            ?.first?.let { it.wins + it.losses >= com.lifecyclebot.engine.truth.CryptoSpotPaperBacking8788.MIN_DECISIVE_8788 } == true }
    }

    /** V5.0.8910 — brief: each strategy record (n / W-L / mean / pf), session and regime slices, best first. */
    fun strategyLine8910(): String {
        val keys = closes.keys.filter { it.contains(STRATEGY_MARK_8910) }.sorted()
        if (keys.isEmpty()) return "twinStrategies8910[-]"
        val body = keys.mapNotNull { k ->
            val q = closes[k] ?: return@mapNotNull null
            val p = CryptoTrend8886.proof8886(synchronized(q) { q.toList() }.map { it[1] })
            Triple(k, p, if (p[0] >= 1.0) p[1] else Double.NEGATIVE_INFINITY)
        }.sortedByDescending { it.third }.take(24).joinToString(" ") { (k, p, _) ->
            k.replace(STRATEGY_MARK_8910, ":") + "{n${p[0].toInt()} ${"%+.1f".format(p[1])}%±${"%.1f".format(p[2])} pf${"%.2f".format(p[3])}" +
                (if (CryptoTrend8886.cellLosing8886(p)) " LOSING" else "") + "}"
        }
        return "twinStrategies8910[$body rule=each close by its STRATEGY7803, and by session / BTC regime at the open; a strategy backs live on its own record like a trend cell]"
    }

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
        return closes.keys.count { k -> k.startsWith(prefix) && !k.contains(STRATEGY_MARK_8910) && cellRecord8886(lane, k.removePrefix(prefix))
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

    /**
     * V5.0.8915 — writes only the records [keys] this close changed. It rewrote every record on every close; with 8910's
     * strategy / session / regime records that is up to ~250 strings of up to 200 closes each per close, queued on Android's
     * SharedPreferences write-back (QueuedWork) — the queue the 3.5 h OutOfMemoryError died in.
     */
    private fun persist8883(keys: Collection<String>) {
        val p = prefs8883() ?: return
        try {
            val e = p.edit()
            for (k in keys) { val q = closes[k] ?: continue; e.putString(k, synchronized(q) { encode8883(q.toList()) }) }
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
        val cells = closes.keys.count { it.contains(CELL_SEP_8886) && !it.contains(STRATEGY_MARK_8910) }
        return "cryptoTwin8883[open=${open.size}/$MAX_OPEN_8883 opened=${opened.get()} dup=${duplicate.get()} full=${full.get()} lost=${lost.get()} " +
            "records{$recs} cells8886=$cells exits{$kinds} rule=beside LIVE: every crypto candidate the live decision opened or a learned door refused, on the live exit functions, virtual fill, gross as the paper book]"
    }

    internal fun addHoldPairForTest8924(lane: String, capPnl: Double, extPnl: Double) {
        val q = holdPairs8924.computeIfAbsent(lane) { ArrayDeque() }; synchronized(q) { q.addLast(doubleArrayOf(capPnl, extPnl)) }
    }

    internal fun resetForTest8883() {
        holdPairs8924.clear(); ghosts8924.clear(); pairsLoaded8924 = true; open.clear(); closes.clear(); byKind.clear(); opened.set(0); duplicate.set(0); full.set(0); lost.set(0); loaded = true }
    internal fun addCloseForTest8883(lane: String, atMs: Long, pnl: Double, size: Double) {
        closes.computeIfAbsent(lane) { ArrayDeque() }.addLast(doubleArrayOf(atMs.toDouble(), pnl, size))
    }
}

package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8964 §A_CLOCK_IS_A_PROMPT_TO_REASSESS_NOT_A_SELL
 *
 * Owner, 23 Nov 23:38: "nothing should be force closed if the token metrics, volume etc support holding". The Field Manual
 * says the same (§10): "Time exit: if the expected move does not occur within the strategy's horizon, REASSESS or close."
 * The stack closed on the clock alone in many places: the crypto desks' one-hour ADAPTIVE_HOLD_MAX (the twin's most common
 * exit, 13 of 18 on 23 Nov 22:58), the xStocks 2 h cap / 45 min loss cut / 30 min flat flush, and every meme time exit that
 * reaches the sell door (FieldManual7715.classifyExit TIME: STALE / TIME / TTL / AGE / DEAD_MONEY / FLAT / HORIZON, plus
 * the hold-max, timeout and underwater-time stops).
 *
 * Now a time exit first reassesses the position on its own live metrics ([supports8964], pure). It holds only when they
 * support holding, two signs agreeing, never on missing data:
 *  - the price is fresh (a minute at most);
 *  - in profit: it has not rolled over (keeps at least half its peak gain), the last 15 minutes are flat or up, and a
 *    second sign agrees — the last hour up, buyers at least half the flow, or participation not dying (rolling one-hour
 *    volume at least half what it was 30 minutes ago); none of those read against it;
 *  - under water: it is recovering on two signs — the last 15 minutes up AND (the last hour up, or buyers 55 %+).
 * Otherwise the time exit proceeds exactly as before. Stops, rug / liquidity / integrity, emergency, target and trail exits
 * are not time exits and are never touched here. [statusLine8964] counts held and released by reason.
 */
object HoldSupport8964 {
    private const val FRESH_MS_8964 = 60_000L
    private const val W15_MS_8964 = 15L * 60_000L
    private const val W60_MS_8964 = 60L * 60_000L
    private const val PARTICIPATION_LAG_MS_8964 = 30L * 60_000L
    private const val FLOW_FRESH_MS_8964 = 120_000L
    private const val RING_STEP_MS_8964 = 30_000L
    private const val RING_KEEP_MS_8964 = 3L * 60L * 60_000L
    private const val MAX_RINGS_8964 = 512

    /** One reassessment. NaN = not read (never counted as support). */
    data class Read8964(
        val priceFresh: Boolean,
        val pnlPct: Double,
        val peakPct: Double,
        val move15Pct: Double,
        val move60Pct: Double,
        val buyPct: Double = Double.NaN,
        val participation: Double = Double.NaN,
    )

    private fun known(v: Double) = v.isFinite()

    /** Pure: why the metrics support holding past a time exit, or null when they do not (or cannot be read). */
    fun supports8964(r: Read8964): String? {
        if (!r.priceFresh || !known(r.pnlPct) || !known(r.move15Pct)) return null
        if (r.pnlPct >= 0.0) {
            if (known(r.peakPct) && r.peakPct > 0.0 && r.pnlPct < r.peakPct * 0.5) return null       // rolled over
            if (r.move15Pct < 0.0) return null
            val against = (known(r.move60Pct) && r.move60Pct < 0.0) || (known(r.buyPct) && r.buyPct < 50.0) ||
                (known(r.participation) && r.participation < 0.5)
            if (against) return null
            return when {
                known(r.buyPct) -> "UP_BUYERS"
                known(r.move60Pct) -> "UP_15M_AND_1H"
                known(r.participation) -> "UP_VOLUME_HELD"
                else -> null                                                                           // one sign is not two
            }
        }
        if (r.move15Pct <= 0.0) return null
        if (known(r.participation) && r.participation < 0.5) return null
        return when {
            known(r.buyPct) && r.buyPct >= 55.0 -> "RECOVERING_BUYERS"
            known(r.move60Pct) && r.move60Pct > 0.0 && !(known(r.buyPct) && r.buyPct < 50.0) -> "RECOVERING_15M_AND_1H"
            else -> null
        }
    }

    /** Pure: is [reason] a time exit (the clock, not the price, the safety or the plan)? */
    fun isTimeExit8964(reason: String): Boolean {
        val r = reason.uppercase()
        // an operational, safety or price-stop exit that happens to carry a clock word is never a time exit here
        if (NOT_TIME_8964.any { r.contains(it) }) return false
        if (FieldManual7715.classifyExit(r) == FieldManual7715.ExitClass.TIME) return true
        return r.contains("TIME_STOP") || r.contains("HOLD_MAX") || r.contains("MAX_HOLD") || r.contains("TIMEOUT") ||
            r.contains("TIME_CAP") || r.contains("TIME_CUT") || r.contains("TIME_FLUSH") || r.contains("TIME_EXIT")
    }

    private val NOT_TIME_8964 = listOf("RECONCIL", "ORPHAN", "MANUAL", "SWEEP", "DISPOS", "LIQUIDAT", "RECOVER", "DUST", "RETRY",
        "RUG", "EMERGENCY", "CATASTROPH", "INTEGRITY", "QUARANTINE", "HONEYPOT", "FREEZE", "LIQ_", "LIQUIDITY", "MARK_INVALID",
        "SELL_DOMINANT", "DUMP", "HARD_STOP", "STRICT_SL", "STOP_LOSS", "TRAIL", "TAKE_PROFIT", "LADDER", "RUNG")

    /** Pure: the move (percent) from the latest price at or before [atMs] in [points] (time, price) to [priceNow]. */
    internal fun moveSince8964(points: List<Pair<Long, Double>>, atMs: Long, priceNow: Double): Double {
        if (!(priceNow > 0.0)) return Double.NaN
        var ref = Double.NaN
        for ((t, p) in points) { if (t <= atMs && p > 0.0) ref = p else if (t > atMs) break }
        return if (ref > 0.0) (priceNow / ref - 1.0) * 100.0 else Double.NaN
    }

    // ── positions without candles (crypto desks, xStocks): a mark ring, one point per 30 s, three hours ──
    private val rings = ConcurrentHashMap<String, ArrayDeque<Pair<Long, Double>>>()

    /** Every exit tick: [key]'s mark [price] at [nowMs]. */
    fun noteMark8964(key: String, price: Double, nowMs: Long = System.currentTimeMillis()) {
        if (!(price > 0.0) || key.isBlank()) return
        if (rings.size > MAX_RINGS_8964 && !rings.containsKey(key)) rings.keys.take(MAX_RINGS_8964 / 4).forEach { rings.remove(it) }
        val q = rings.computeIfAbsent(key) { ArrayDeque() }
        synchronized(q) {
            if (q.isNotEmpty() && nowMs - q.last().first < RING_STEP_MS_8964) return
            q.addLast(nowMs to price)
            while (q.isNotEmpty() && nowMs - q.first().first > RING_KEEP_MS_8964) q.removeFirst()
        }
    }

    /** A closed position's ring is dropped. */
    fun forget8964(key: String) { rings.remove(key) }

    private val held = ConcurrentHashMap<String, AtomicLong>()
    private val released = ConcurrentHashMap<String, AtomicLong>()
    private fun count(m: ConcurrentHashMap<String, AtomicLong>, k: String) {
        if (m.size < 48 || m.containsKey(k)) m.computeIfAbsent(k) { AtomicLong(0L) }.incrementAndGet()
    }

    /**
     * Crypto desk / xStocks time exit: should [key] hold past it? [direction] -1 for a short (its gain is the price falling).
     * [pnlPct] / [peakPct] the position's own; [priceAtMs] when its mark was observed.
     */
    fun holdKeyed8964(key: String, book: String, price: Double, priceAtMs: Long, pnlPct: Double, peakPct: Double,
                      direction: Int = 1, nowMs: Long = System.currentTimeMillis()): Boolean {
        val pts = rings[key]?.let { q -> synchronized(q) { q.toList() } }.orEmpty()
        val sign = if (direction < 0) -1.0 else 1.0
        val read = Read8964(
            priceFresh = priceAtMs > 0L && nowMs - priceAtMs in 0L..FRESH_MS_8964,
            pnlPct = pnlPct, peakPct = peakPct,
            move15Pct = moveSince8964(pts, nowMs - W15_MS_8964, price) * sign,
            move60Pct = moveSince8964(pts, nowMs - W60_MS_8964, price) * sign,
        )
        val why = supports8964(read)
        if (why != null) count(held, "$book:$why") else count(released, book)
        return why != null
    }

    /** The meme sell door: should [ts]'s time exit [reason] hold on the coin's own live metrics? */
    fun holdMeme8964(ts: TokenState, reason: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!isTimeExit8964(reason)) return false
        val pos = ts.position
        val entry = pos.entryPrice
        val px = ts.lastPrice
        if (!(entry > 0.0 && px > 0.0)) { count(released, "MEME_UNPRICED"); return false }
        val candles = try { synchronized(ts.history) { ts.history.map { it.ts to it.priceUsd } } } catch (_: Throwable) { emptyList() }
        val vols = try { synchronized(ts.history) { ts.history.map { it.ts to it.volumeH1 } } } catch (_: Throwable) { emptyList() }
        val volNow = vols.lastOrNull()?.second ?: Double.NaN
        val volThen = vols.lastOrNull { it.first <= nowMs - PARTICIPATION_LAG_MS_8964 }?.second ?: Double.NaN
        val pnl = (px / entry - 1.0) * 100.0
        val read = Read8964(
            priceFresh = ts.lastPriceUpdate > 0L && nowMs - ts.lastPriceUpdate in 0L..FRESH_MS_8964,
            pnlPct = pnl,
            peakPct = maxOf(pos.peakGainPct, if (pos.highestPrice > 0.0) (pos.highestPrice / entry - 1.0) * 100.0 else 0.0),
            move15Pct = moveSince8964(candles, nowMs - W15_MS_8964, px),
            move60Pct = moveSince8964(candles, nowMs - W60_MS_8964, px),
            buyPct = if (nowMs - ts.buyPressureObservedAtMs8084 in 0L..FLOW_FRESH_MS_8964) ts.lastBuyPressurePct else Double.NaN,
            participation = if (volNow > 0.0 && volThen > 0.0) volNow / volThen else Double.NaN,
        )
        val why = supports8964(read)
        if (why != null) count(held, "MEME:$why") else count(released, "MEME")
        return why != null
    }

    fun statusLine8964(): String =
        "holdSupport8964[held{" + held.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" } +
            "} released{" + released.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" } +
            "} rings=${rings.size} rule=a time exit reassesses first: held only while the position's own fresh metrics support it " +
            "(in profit: not rolled over, 15 min flat or up and a second sign agreeing — 1 h up, buyers >= 50 %, or volume held; " +
            "under water: recovering on two signs); stops, safety, target and trail exits never pass through here]"
}

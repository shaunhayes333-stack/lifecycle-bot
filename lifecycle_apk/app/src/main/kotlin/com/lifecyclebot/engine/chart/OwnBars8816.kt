package com.lifecyclebot.engine.chart

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8816 §THE_SETUPS_LEARN_FROM_EVERY_CHART_THE_BOT_SEES — owner: "if no set up makes [money] we need to re engineer the
 * set ups". Offline on every dataset (175,766 decisions, 19,109 coins, 9-16 Nov) no setup built on the decision snapshot
 * (liquidity, cap, age, buy pressure, 5 m / 1 h move) held on later days once each coin counts once. The setups have to be
 * rebuilt from different information: the candles themselves, read the way the owner trades them (ColourShape8807: colour
 * runs against the chart's shape and the textbook patterns, scored by the trade ladder, proven on coins it was not fitted
 * on). That learner saw 44 charts in a day, because downloaded meme charts come from GeckoTerminal at about one a minute
 * (5815972e: geckoterminal ok=40 skips=309 queue=566).
 *
 * The bot already decodes every witnessed trade of every coin it hears (the Helius program tape decodes every pump.fun
 * bonding-curve trade, the PumpSwap tape every trade of its pools): 2,424 decoded prints were dropped in one 47-minute
 * report because no desk was watching those coins. This records them as one-minute candles (price in USD at the print's
 * SOL price, volume in SOL), every coin, bounded:
 *  - at most [MAX_MINTS] coins at a time, [MAX_BARS] candles each, primitive arrays (~11 KB a coin);
 *  - a coin is handed to the learner once (ColourShape8807.learnTape8810) when it goes quiet for [IDLE_MS] or fills its
 *    [MAX_BARS] candles, and never recorded again (one coin, one chart);
 *  - a coin that is still printing when the store is full waits; a new coin is refused and counted.
 * A minute without a print is a flat candle at the last close (zero volume). Nothing here trades or admits.
 */
object OwnBars8816 {
    internal const val MAX_MINTS = 400
    internal const val MAX_BARS = 240
    internal const val IDLE_MS = 15L * 60_000L
    private const val SWEEP_MS = 30_000L
    private const val DONE_MAX = 50_000
    private const val MIN = 60_000L

    internal class Tape(val startMin: Long) {
        val o = DoubleArray(MAX_BARS); val h = DoubleArray(MAX_BARS); val l = DoubleArray(MAX_BARS)
        val c = DoubleArray(MAX_BARS); val v = DoubleArray(MAX_BARS)
        var n = 0
        var lastPrintMs = 0L

        /** One print at minute [m] (epoch minutes); false when the tape is full. Flat candles fill a quiet gap. */
        fun add(m: Long, px: Double, sol: Double): Boolean {
            val idx = (m - startMin).toInt()
            if (idx < 0) return true                       // a late print for a closed candle is ignored
            if (idx >= MAX_BARS) return false
            if (idx >= n) {
                val prev = if (n > 0) c[n - 1] else px
                while (n < idx) { o[n] = prev; h[n] = prev; l[n] = prev; c[n] = prev; v[n] = 0.0; n++ }
                o[n] = if (n > 0) c[n - 1] else px; h[n] = maxOf(o[n], px); l[n] = minOf(o[n], px); c[n] = px; v[n] = sol; n++
            } else {
                if (px > h[idx]) h[idx] = px
                if (px < l[idx]) l[idx] = px
                if (idx == n - 1) c[idx] = px
                v[idx] += sol
            }
            return true
        }

        fun bars(): List<Bar7950> = List(n) { Bar7950((startMin + it) * MIN, o[it], h[it], l[it], c[it], v[it]) }
    }

    private val tapes = ConcurrentHashMap<String, Tape>()
    // coins already handed over (or refused as too short), as 64-bit hashes: 50k mints in ~3 MB instead of ~7 MB of strings
    private val done = object : LinkedHashMap<Long, Boolean>(4_096, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>?): Boolean = size > DONE_MAX
    }

    /** Pure: FNV-1a 64-bit hash of [s] (a mint's identity in the bounded done-sets). */
    internal fun hash8816(s: String): Long {
        var h = -0x340d631b7bdddcdbL
        for (ch in s) { h = h xor ch.code.toLong(); h *= 0x100000001b3L }
        return h
    }
    private val lastSweep = AtomicLong(0L)
    private val prints = AtomicLong(0L)
    private val refusedFull = AtomicLong(0L)
    private val handed = AtomicLong(0L)
    private val tooShort = AtomicLong(0L)
    private val liveReads = AtomicLong(0L)
    private val noSolUsd = AtomicLong(0L)

    private fun isDone(mint: String): Boolean = synchronized(done) { done.containsKey(hash8816(mint)) }

    /** TradeTape8100.launchEvidence8750: one witnessed print of [mint] at [eventMs] ([priceSol] SOL per token, [sol] SOL). */
    fun onPrint8816(mint: String, eventMs: Long, priceSol: Double, sol: Double, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank() || eventMs <= 0L || !(priceSol > 0.0) || !priceSol.isFinite()) return
        try {
            // USD, the unit ChartReader7950's candles and the learner's open paper ladders use for the same coin.
            val solUsd = solUsdForTest8816.takeIf { it > 0.0 } ?: com.lifecyclebot.engine.truth.ShadowMark8360.solUsd8756(eventMs)
            if (!(solUsd > 0.0) || !solUsd.isFinite()) { noSolUsd.incrementAndGet(); return }
            val px = priceSol * solUsd
            prints.incrementAndGet()
            ColourShape8807.onPrice8807(mint, px, eventMs)   // steps this coin's open colour-shape paper ladder (no-op without one)
            val swept = lastSweep.get()
            if (nowMs - swept >= SWEEP_MS && lastSweep.compareAndSet(swept, nowMs)) sweep8816(nowMs)
            val t: Tape = tapes[mint] ?: run {
                if (isDone(mint)) return
                if (tapes.size >= MAX_MINTS) { refusedFull.incrementAndGet(); return }
                tapes.computeIfAbsent(mint) { Tape(eventMs / MIN) }
            }
            var newCandle = false
            val full = synchronized(t) {
                t.lastPrintMs = maxOf(t.lastPrintMs, nowMs)
                val before = t.n
                val ok = t.add(eventMs / MIN, px, if (sol.isFinite() && sol > 0.0) sol else 0.0)
                newCandle = t.n > before
                !ok
            }
            if (full) { retire(mint, t); return }
            // A candle just closed: once the learner holds a proven setup, every recorded coin is read live (paper fires,
            // then admission evidence when ARMED) — not only the coins a desk already watches.
            if (newCandle && ColourShape8807.hasProven8816(nowMs)) {
                val bars = synchronized(t) { if (t.n > ColourShape8807.LOOK) t.bars() else null }
                if (bars != null) { liveReads.incrementAndGet(); ColourShape8807.onBars8807(mint, bars, nowMs) }
            }
        } catch (_: Throwable) {}
    }

    /** Hands every quiet tape to the learner (and frees its slot). */
    internal fun sweep8816(nowMs: Long) {
        for ((m, t) in tapes) {
            val idle = synchronized(t) { nowMs - t.lastPrintMs >= IDLE_MS }
            if (idle) retire(m, t)
        }
    }

    private fun retire(mint: String, t: Tape) {
        if (!tapes.remove(mint, t)) return
        synchronized(done) { done[hash8816(mint)] = true }
        val bars = synchronized(t) { t.bars() }
        if (bars.size < ColourShape8807.LOOK + 11) { tooShort.incrementAndGet(); return }
        handed.incrementAndGet()
        ColourShape8807.learnTape8810(mint, bars)
    }

    fun statusLine8816(): String =
        "ownBars8816[recording=${tapes.size}/$MAX_MINTS prints=${prints.get()} handedToLearner=${handed.get()} tooShort=${tooShort.get()} " +
            "refusedFull=${refusedFull.get()} liveReads=${liveReads.get()} noSolUsd=${noSolUsd.get()} rule=every witnessed print as 1-minute candles, one chart per coin when quiet ${IDLE_MS / MIN} min or ${MAX_BARS} candles]"

    @Volatile internal var solUsdForTest8816 = 0.0

    internal fun resetForTest8816() { tapes.clear(); synchronized(done) { done.clear() }; prints.set(0); refusedFull.set(0); handed.set(0); tooShort.set(0); liveReads.set(0); noSolUsd.set(0); lastSweep.set(0) }
    internal fun recordingForTest8816(): Int = tapes.size
}

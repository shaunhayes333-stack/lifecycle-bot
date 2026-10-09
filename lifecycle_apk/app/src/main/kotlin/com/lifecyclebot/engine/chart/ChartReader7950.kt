package com.lifecyclebot.engine.chart

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7950 — the live chart reader.
 *
 * Every price print and every trade the bot already receives (PumpPortal /
 * Helius trade tape, held-curve marks, DexScreener / registry marks through
 * MarkBars7948) is folded into one-minute candles per mint with the buy and sell
 * volume split, plus a dev-sold flag. The last 20 candles are fingerprinted
 * scale-free (ChartMotif7950) and read against the chart library
 * (ChartLibrary7950): what did charts that looked exactly like this do next?
 *
 *  - BUY: the nearest motifs ran +2 ATR before -1 ATR clearly more often than
 *    the library's base rate, their typical run beats their typical drawdown,
 *    and buyers dominate the tape now (when the tape shows a split).
 *  - EXIT (held): the nearest motifs are tops (fell -1 ATR first well above base
 *    rate, ended lower), or the dev sold.
 *
 * Closed live candles are fingerprinted back into the library once their own
 * future is known, so the library keeps learning the market it trades.
 */
object ChartReader7950 {
    private const val BUCKET_MS = 60_000L
    private const val KEEP_MS = 120L * 60_000L
    private const val MAX_MINTS = 1_500
    private const val READ_TTL_MS = 15_000L
    private const val DEV_SELL_WINDOW_MS = 10L * 60_000L

    /** Minimum neighbours, lift and payoff for a BUY. */
    private const val MIN_N = 30
    private const val BUY_LIFT = 0.10
    private const val EXIT_LIFT = -0.12
    private const val MIN_BUY_SHARE = 0.55

    private class Tape {
        // minute -> [o, h, l, c, buySol, sellSol, lastPriceTs]
        val bars = TreeMap<Long, DoubleArray>()
        @Volatile var lastMs = 0L
        @Volatile var devSoldAtMs = 0L
        @Volatile var ingestedThrough = -1L
    }

    private val tapes = ConcurrentHashMap<String, Tape>()

    data class Read(val motif: MotifRead7950?, val bars: Int, val buyShare: Double, val devSold: Boolean, val atMs: Long, val devSoldAtMs: Long = 0L)

    private val reads = ConcurrentHashMap<String, Read>()
    private val buys = AtomicLong(0)
    private val exits = AtomicLong(0)
    private val readsDone = AtomicLong(0)
    private val liveMotifs = AtomicLong(0)
    private val admitted = ConcurrentHashMap<String, AtomicLong>()

    // ── feed ──

    /** Every observed price (MarkBars7948.note7948 calls this). */
    fun onPrice(mint: String, priceUsd: Double, atMs: Long) {
        if (mint.isBlank() || !(priceUsd > 0.0) || !priceUsd.isFinite() || atMs <= 0L) return
        val tape = tapes.computeIfAbsent(mint) { Tape() }
        synchronized(tape) {
            val m = atMs / BUCKET_MS
            val b = tape.bars[m]
            if (b == null) {
                val prevClose = tape.bars.lowerEntry(m)?.value?.get(3) ?: priceUsd
                tape.bars[m] = doubleArrayOf(prevClose, maxOf(prevClose, priceUsd), minOf(prevClose, priceUsd), priceUsd, 0.0, 0.0, atMs.toDouble())
            } else {
                if (priceUsd > b[1]) b[1] = priceUsd
                if (priceUsd < b[2]) b[2] = priceUsd
                if (atMs.toDouble() >= b[6]) { b[3] = priceUsd; b[6] = atMs.toDouble() }
            }
            if (atMs > tape.lastMs) tape.lastMs = atMs
            val oldest = (tape.lastMs - KEEP_MS) / BUCKET_MS
            while (tape.bars.isNotEmpty() && tape.bars.firstKey() < oldest) tape.bars.pollFirstEntry()
        }
        if (tapes.size > MAX_MINTS) trim(atMs)
    }

    /** Every trade on the tape (DataOrchestrator.onTapeTrade7773). */
    fun onTrade(mint: String, solAmount: Double, isBuy: Boolean, isDev: Boolean, atMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        val tape = tapes[mint] ?: return
        synchronized(tape) {
            if (isDev && !isBuy) tape.devSoldAtMs = atMs
            if (!(solAmount > 0.0) || !solAmount.isFinite()) return
            val b = tape.bars[atMs / BUCKET_MS] ?: tape.bars.lastEntry()?.value ?: return
            if (isBuy) b[4] += solAmount else b[5] += solAmount
        }
    }

    private fun trim(nowMs: Long) {
        tapes.entries.filter { nowMs - it.value.lastMs > KEEP_MS }.forEach { tapes.remove(it.key, it.value) }
        if (tapes.size > MAX_MINTS) {
            tapes.entries.sortedBy { it.value.lastMs }.take(tapes.size - MAX_MINTS).forEach { tapes.remove(it.key, it.value) }
        }
    }

    /** One-minute candles for [mint], oldest first; gaps carry the last close forward. */
    private fun bars(mint: String): List<Bar7950> {
        val tape = tapes[mint] ?: return emptyList()
        return synchronized(tape) { toBars(tape.bars) }
    }

    /** Pure: tape minutes -> contiguous candles (a quiet minute is a flat, zero-volume candle). */
    fun toBars(tape: TreeMap<Long, DoubleArray>): List<Bar7950> {
        if (tape.isEmpty()) return emptyList()
        val out = ArrayList<Bar7950>(tape.size + 8)
        var prevMin = -1L
        var prevClose = Double.NaN
        for ((m, b) in tape) {
            if (prevMin >= 0 && prevClose.isFinite()) {
                var g = prevMin + 1
                while (g < m && out.size < 600) { out += Bar7950(g * BUCKET_MS, prevClose, prevClose, prevClose, prevClose, 0.0, 0.0); g++ }
            }
            val vol = b[4] + b[5]
            out += Bar7950(m * BUCKET_MS, b[0], b[1], b[2], b[3], vol, if (vol > 0.0) b[4] else Double.NaN)
            prevMin = m
            prevClose = b[3]
        }
        return out
    }

    // ── read ──

    /** The chart read for [mint] now (cached [READ_TTL_MS]); null when there are too few candles. */
    fun read(mint: String, nowMs: Long = System.currentTimeMillis()): Read? {
        reads[mint]?.let { if (nowMs - it.atMs < READ_TTL_MS) return it }
        val tape = tapes[mint] ?: return null
        val bars = bars(mint)
        if (bars.size < ChartMotif7950.WINDOW + 1) return null
        val end = bars.size - 1
        val f = ChartMotif7950.encode(bars, end) ?: return null
        val motif = ChartLibrary7950.query(f)
        val r = Read(motif, bars.size, ChartMotif7950.buyShare(bars, end), nowMs - tape.devSoldAtMs < DEV_SELL_WINDOW_MS, nowMs, tape.devSoldAtMs)
        if (reads.size > 3_000) reads.clear()
        reads[mint] = r
        readsDone.incrementAndGet()
        learnLive(mint, tape, bars)
        return r
    }

    /** V5.0.7953 — fingerprint every live tape's closed candles into the library. Returns motifs added. */
    fun learnAll7953(): Int {
        val before = liveMotifs.get()
        for ((mint, tape) in tapes.entries.toList()) {
            val bars = synchronized(tape) { toBars(tape.bars) }
            if (bars.size > ChartMotif7950.WINDOW + ChartMotif7950.HORIZON) learnLive(mint, tape, bars)
        }
        return (liveMotifs.get() - before).toInt()
    }

    /** Fingerprint this mint's closed candles whose future is now known back into the library. */
    private fun learnLive(mint: String, tape: Tape, bars: List<Bar7950>) {
        val lastEnd = ChartLibrary7950.lastLabelledEnd(bars.size)
        if (lastEnd < ChartMotif7950.WINDOW) return
        val lastEndMs = bars[lastEnd].t
        if (lastEndMs <= tape.ingestedThrough) return
        val fromEnd = bars.indexOfFirst { it.t > tape.ingestedThrough }.coerceAtLeast(ChartMotif7950.WINDOW)
        val n = ChartLibrary7950.ingestSeries(bars, ChartLibrary7950.SRC_LIVE, maxWindows = 4, fromEnd = fromEnd)
        tape.ingestedThrough = lastEndMs
        if (n > 0) liveMotifs.addAndGet(n.toLong())
    }

    /**
     * Pure: does a read say BUY? V5.0.7951 review: the lift must clear the bar by one
     * standard error of the neighbours' win rate, and the neighbours must be close
     * (mean distance within [ChartLibrary7950.typicalDist] x 1.25) — a window that
     * resembles nothing gets no verdict.
     */
    fun buySignal(r: Read?, typicalDist: Double = ChartLibrary7950.typicalDist()): Boolean {
        val m = r?.motif ?: return false
        if (r.devSold) return false
        val se = kotlin.math.sqrt((m.pUp * (1.0 - m.pUp)).coerceAtLeast(0.0) / m.n.coerceAtLeast(1))
        if (m.n < MIN_N || m.lift - se < BUY_LIFT) return false
        if (typicalDist.isFinite() && typicalDist > 0.0 && m.meanDist > typicalDist * 1.25) return false
        if (!(m.meanUpPct > 1.5 * -m.meanDnPct) || !(m.meanEndPct > 0.0)) return false
        return !r.buyShare.isFinite() || r.buyShare >= MIN_BUY_SHARE
    }

    /** Pure: does a read say get out of a held position? Returns the reason or null. */
    fun exitSignal(r: Read?): String? {
        if (r == null) return null
        if (r.devSold) return "DEV_SOLD"
        val m = r.motif ?: return null
        if (m.n < MIN_N) return null
        val se = kotlin.math.sqrt((m.pUp * (1.0 - m.pUp)).coerceAtLeast(0.0) / m.n.coerceAtLeast(1))
        return if (m.lift + se <= EXIT_LIFT && m.meanEndPct < 0.0 && -m.meanDnPct > m.meanUpPct) "TOP_MOTIF" else null
    }

    /** Gate check (not counted): does the chart say BUY for [mint] now? */
    fun saysBuy(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        try { buySignal(read(mint, nowMs)) } catch (_: Throwable) { false }

    /**
     * Live entry: the chart says BUY for [mint]. Counted per lane; the caller lets
     * the candidate past the soft (learned / plan / budget) refusals. Hard safety
     * is the caller's and runs regardless.
     */
    fun admitsLive(mint: String, lane: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val ok = try { buySignal(read(mint, nowMs)) } catch (_: Throwable) { false }
        if (ok) {
            buys.incrementAndGet()
            admitted.computeIfAbsent(lane.uppercase()) { AtomicLong(0) }.incrementAndGet()
            try { PipelineHealthCollector.labelInc("CHART_READER_BUY_7950_${lane.uppercase()}") } catch (_: Throwable) {}
        }
        return ok
    }

    /**
     * Held position: the chart's exit reason, or null to keep holding. A dev sale
     * counts only when it happened after [entryMs] (V5.0.7951 review: a sale minutes
     * before the buy forced a full exit at 90 s).
     */
    fun exitFor(mint: String, nowMs: Long = System.currentTimeMillis(), entryMs: Long = 0L): String? {
        val r = try { read(mint, nowMs) } catch (_: Throwable) { null }
        val r2 = if (r != null && r.devSold && r.devSoldAtMs <= entryMs) r.copy(devSold = false) else r
        val why = try { exitSignal(r2) } catch (_: Throwable) { null } ?: return null
        exits.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("CHART_READER_EXIT_7950_$why")
            ForensicLogger.lifecycle("CHART_READER_EXIT_7950", "mint=${mint.take(10)} why=$why read=${reads[mint]?.motif}")
        } catch (_: Throwable) {}
        return why
    }

    fun statusLine(): String =
        "tapes=${tapes.size} reads=${readsDone.get()} buys=${buys.get()} exits=${exits.get()} liveMotifs=${liveMotifs.get()} " +
            "admitted=${admitted.entries.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }} lib[${ChartLibrary7950.statusLine()}] " +
            "build[${ChartLibraryBuilder7950.statusLine()}]"

}

package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7948 §THE_BOT_ALREADY_HAS_THE_TAPE.
 *
 * 5.0.7947: GeckoTerminal sr=35%, DexPaprika 403-disabled, keyless OHLCV one
 * serve in 79 fetches, Helius swaps 10 requests -> 51 bars; TradePlan7739
 * waited TOO_FEW_BARS 182 times and CHOKEPOINT_7742_PLAN_TOO_FEW_BARS was five
 * of nine refused live buys. Meanwhile every token the bot watches is marked
 * many times a minute: PumpPortal/Helius trade prints, the WS pair price, the
 * executor's polled quote, the held-curve trade marks.
 *
 * LocalCandleSynthesis7055 bins some of those into ts.history, but only when a
 * minute ROLLS (the forming minute is never visible), only while the token is
 * in status.tokens (a launch's pre-admission prints are lost), never for a held
 * position, and its bars are wiped when a provider seed clears the history.
 *
 * This keeps a per-mint one-minute OHLC tape of every mark the bot receives,
 * keyed by mint (not by TokenState), the forming minute included. TradePlan7739
 * reads it as a first-class bar source: provider/history bars win every minute
 * they cover, mark bars fill the minutes they do not. Prices only; no volume is
 * invented (Field Manual L325).
 */
object MarkBars7948 {
    private const val BUCKET_MS = 60_000L
    private const val KEEP_MS = 35L * 60_000L
    private const val MAX_MINTS = 2_000

    private class Tape {
        // minute index -> [open, high, low, close, closeTs, openTs]
        val bars = TreeMap<Long, DoubleArray>()
        @Volatile var lastMs = 0L
    }

    private val tapes = ConcurrentHashMap<String, Tape>()
    private val marksNoted = AtomicLong(0)
    private val barsLent = AtomicLong(0)

    /** Record one observed price for [mint] at [atMs]. Cheap; safe on socket threads. */
    fun note7948(mint: String, priceUsd: Double, atMs: Long) {
        if (mint.isBlank() || !priceUsd.isFinite() || priceUsd <= 0.0 || atMs <= 0L) return
        // V5.0.7950 — every price the bot sees also feeds the chart reader's candles.
        try { com.lifecyclebot.engine.chart.ChartReader7950.onPrice(mint, priceUsd, atMs) } catch (_: Throwable) {}
        val tape = tapes.computeIfAbsent(mint) { Tape() }
        synchronized(tape) {
            fold7948(tape.bars, priceUsd, atMs)
            if (atMs > tape.lastMs) tape.lastMs = atMs
            val oldest = (tape.lastMs - KEEP_MS) / BUCKET_MS
            while (tape.bars.isNotEmpty() && tape.bars.firstKey() < oldest) tape.bars.pollFirstEntry()
        }
        marksNoted.incrementAndGet()
        if (tapes.size > MAX_MINTS) trimMints7948(atMs)
    }

    /**
     * Pure: fold one mark into minute bars. Order-independent: a late print
     * widens high/low, replaces the close only when it is the latest seen in
     * its minute and the open only when it is the earliest.
     */
    fun fold7948(bars: TreeMap<Long, DoubleArray>, priceUsd: Double, atMs: Long) {
        val m = atMs / BUCKET_MS
        val b = bars[m]
        if (b == null) {
            bars[m] = doubleArrayOf(priceUsd, priceUsd, priceUsd, priceUsd, atMs.toDouble(), atMs.toDouble())
            return
        }
        if (priceUsd > b[1]) b[1] = priceUsd
        if (priceUsd < b[2]) b[2] = priceUsd
        if (atMs.toDouble() >= b[4]) { b[3] = priceUsd; b[4] = atMs.toDouble() }
        if (atMs.toDouble() < b[5]) { b[0] = priceUsd; b[5] = atMs.toDouble() }
    }

    /** One-minute mark bars for [mint] inside [windowMs] before [nowMs] (forming minute included). */
    fun bars7948(mint: String, nowMs: Long, windowMs: Long): List<TradePlan7739.Bar> {
        val tape = tapes[mint] ?: return emptyList()
        val from = (nowMs - windowMs) / BUCKET_MS
        val to = (nowMs + BUCKET_MS) / BUCKET_MS
        return synchronized(tape) {
            tape.bars.subMap(from, true, to, true).map { (m, b) -> TradePlan7739.Bar(m * BUCKET_MS, b[0], b[1], b[2], b[3]) }
        }
    }

    /**
     * Pure: provider/history bars win every minute they cover; mark bars fill the
     * rest. History that already has [enoughBars] is returned unchanged.
     */
    fun merge7948(history: List<TradePlan7739.Bar>, marks: List<TradePlan7739.Bar>, enoughBars: Int): List<TradePlan7739.Bar> {
        if (history.size >= enoughBars || marks.isEmpty()) return history
        val byMinute = TreeMap<Long, TradePlan7739.Bar>()
        for (b in marks) byMinute[b.startMs / BUCKET_MS] = b
        for (b in history) byMinute[b.startMs / BUCKET_MS] = b
        return byMinute.values.toList()
    }

    /** TradePlan7739: count a read that the mark tape lengthened. */
    fun noteLent7948(added: Int) {
        if (added <= 0) return
        barsLent.addAndGet(added.toLong())
        try { PipelineHealthCollector.labelInc("PLAN_BARS_FROM_MARKS_7948") } catch (_: Throwable) {}
    }

    // ── V5.0.7951 §THE_TAPE_SURVIVES_A_RESTART ─────────────────────────────
    //
    // 5.0.7949 at 146 s: Trade plans waited TOO_FEW_BARS=95 even with this tape,
    // because a restart empties it and the plan needs six one-minute bars. Known
    // bars (a restored tape, the token's own one-minute history at first sight)
    // are folded in for the minutes the tape lacks; they are real prices, the
    // live prints keep winning the minutes they cover.

    private val barsSeeded7951 = AtomicLong(0)

    /**
     * Pure: the COMPLETED bars of [bars] whose minute [present] lacks, inside
     * [keepMs] before [nowMs]. The forming minute is left to the live prints.
     */
    fun missingBars7951(bars: List<TradePlan7739.Bar>, present: Set<Long>, nowMs: Long, keepMs: Long = KEEP_MS): List<TradePlan7739.Bar> =
        bars.filter { b ->
            b.startMs > 0L && b.startMs + BUCKET_MS <= nowMs && nowMs - b.startMs <= keepMs && (b.startMs / BUCKET_MS) !in present &&
                listOf(b.open, b.high, b.low, b.close).all { it.isFinite() && it > 0.0 }
        }

    /** Fold known one-minute bars into the minutes [mint]'s tape lacks. Returns the minutes added. */
    fun seedBars7951(mint: String, bars: List<TradePlan7739.Bar>, nowMs: Long): Int {
        if (mint.isBlank() || bars.isEmpty()) return 0
        val present = tapes[mint]?.let { t -> synchronized(t) { HashSet(t.bars.keys) } } ?: emptySet<Long>()
        val missing = missingBars7951(bars, present, nowMs)
        if (missing.isEmpty()) return 0
        for (b in missing) {
            note7948(mint, b.open, b.startMs)
            note7948(mint, b.high, b.startMs + 1L)
            note7948(mint, b.low, b.startMs + 2L)
            note7948(mint, b.close, b.startMs + BUCKET_MS - 1_000L)
        }
        barsSeeded7951.addAndGet(missing.size.toLong())
        try { PipelineHealthCollector.labelInc("MARK_BARS_SEEDED_7951") } catch (_: Throwable) {}
        return missing.size
    }

    /** The tapes of the [maxMints] most recently marked mints still inside the keep window (for persistence). */
    fun export7951(nowMs: Long, maxMints: Int): List<Pair<String, List<TradePlan7739.Bar>>> =
        tapes.entries.filter { nowMs - it.value.lastMs in 0L..KEEP_MS }
            .sortedByDescending { it.value.lastMs }
            .take(maxMints)
            .map { (mint, t) -> mint to synchronized(t) { t.bars.map { (m, b) -> TradePlan7739.Bar(m * BUCKET_MS, b[0], b[1], b[2], b[3]) } } }

    fun statusLine7948(): String = "markBars7948 mints=${tapes.size} marks=${marksNoted.get()} barsLent=${barsLent.get()} seeded7951=${barsSeeded7951.get()}"

    private fun trimMints7948(nowMs: Long) {
        tapes.entries.removeIf { nowMs - it.value.lastMs > KEEP_MS }
        if (tapes.size <= MAX_MINTS) return
        val drop = tapes.entries.sortedBy { it.value.lastMs }.take(tapes.size - MAX_MINTS + MAX_MINTS / 10)
        for (e in drop) tapes.remove(e.key)
    }

    internal fun resetForTest7948() { tapes.clear(); marksNoted.set(0); barsLent.set(0); barsSeeded7951.set(0) }
}

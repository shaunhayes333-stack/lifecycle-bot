package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.Candle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8430 §A_WINDOW_THAT_CLOSED_WHILE_THE_PROCESS_WAS_DOWN.
 *
 * 5.0.8422 (8.5 min after a restart): forward labels pending=2163 restored=1817, due=1881, priced 373, lost 968
 * (noSource=651, late=317). The lost ones were the restart cohort: observations whose 5-minute (or hour) window elapsed
 * while the app was down, so no source in THIS process could ever have priced them, and ForwardReturnLabeler7731.expired8395
 * closed them UNKNOWN. Every build restart threw away half the day's labels.
 *
 * The price at that horizon is not lost: the keyless 1-minute candles (SolanaOhlcvFeed6916, GeckoTerminal) still hold it
 * for hours. For an observation whose window closed BEFORE this process started, the candle whose minute covers the horizon
 * is offered to the labeler's existing recovery filter (eligibility, curve witness, basis, time order all unchanged) as a
 * mark of source [SOURCE_8430] — a provider candle, never an independent basis, so a >= 10x move stays unwitnessed and is
 * not booked. Bounded hard: one fetch per [FETCH_GAP_MS_8430] and only when the provider slot is idle (the live chart reads
 * come first), at most [MAX_MINTS_PER_BOOT_8430] mints, inside [RECOVERY_WINDOW_MS_8430] of boot; an observation waits for
 * its fetch only inside that window and then closes exactly as before. Nothing is fabricated: no candle, no label.
 */
object DowntimeRecovery8430 {
    const val SOURCE_8430 = "OHLCV_RECOVERED_8430"
    const val MAX_MINTS_PER_BOOT_8430 = 150
    const val RECOVERY_WINDOW_MS_8430 = 15L * 60_000L
    const val FETCH_GAP_MS_8430 = 10_000L
    private const val CANDLE_MS_8430 = 60_000L
    private const val CANDLES_8430 = 180
    /** V5.0.8435 — a provider interval gap up to this long is slept on the fetch thread rather than skipped. */
    private const val MAX_SLOT_SLEEP_MS_8435 = 5_000L

    /** [waiting]: the fetch for this mint is queued inside the boot window — keep the observation pending; else [mark] or nothing. */
    class Read8430(val waiting: Boolean, val mark: LabelMark8386?)
    private val NONE_8430 = Read8430(false, null)
    private val WAIT_8430 = Read8430(true, null)

    private val candles = ConcurrentHashMap<String, List<Candle>>()
    private val attempted: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val queue: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())
    private val fetching = AtomicBoolean(false)
    @Volatile private var lastFetchMs = 0L
    private val fetched = AtomicLong(0)
    private val empty = AtomicLong(0)
    private val recovered = AtomicLong(0)
    private val gaveUp = AtomicLong(0)

    /** Pure: did a window ending at [deadlineMs] close before this process started ([bootMs])? */
    fun downtime8430(deadlineMs: Long, bootMs: Long): Boolean = deadlineMs < bootMs

    /** Pure: a candle timestamp in milliseconds (a provider's seconds are normalised). */
    private fun ms8430(ts: Long): Long = if (ts in 1L..99_999_999_999L) ts * 1000L else ts

    /** Pure: the candle whose minute covers the window ([fromMs], [toMs]] nearest the horizon [targetMs]; null when none. */
    fun pick8430(cs: List<Candle>, targetMs: Long, fromMs: Long, toMs: Long): Candle? =
        cs.asSequence()
            .filter { c -> val t = ms8430(c.ts); t > fromMs - CANDLE_MS_8430 && t <= toMs && c.priceUsd.isFinite() && c.priceUsd > 0.0 }
            .minByOrNull { kotlin.math.abs(ms8430(it.ts) - targetMs) }

    /**
     * ForwardReturnLabeler7731.expired8395: the recovered candle mark of [mint] at horizon [targetMs] for a window
     * ([fromMs], [deadlineMs]] that closed before [bootMs]; WAIT while its fetch is queued inside the boot window.
     */
    fun candleMark8430(mint: String, targetMs: Long, fromMs: Long, deadlineMs: Long, bootMs: Long, nowMs: Long): Read8430 {
        if (mint.isBlank() || !downtime8430(deadlineMs, bootMs)) return NONE_8430
        candles[mint]?.let { cs ->
            val c = pick8430(cs, targetMs, fromMs, deadlineMs) ?: return NONE_8430
            recovered.incrementAndGet()
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("FORWARD_LABEL_DOWNTIME_CANDLE_8430") } catch (_: Throwable) {}
            val at = ms8430(c.ts).coerceIn(fromMs + 1L, minOf(deadlineMs, nowMs))
            return Read8430(false, LabelMark8386(mint, "ohlcv", c.priceUsd, at, nowMs, SOURCE_8430))
        }
        if (mint == inFlight8430) return WAIT_8430   // its fetch is on the wire
        // V5.0.8435 — 8434 (5.4 min): queued=150 fetched=0 gaveUp=197. The full-queue branch below ran BEFORE the queued-
        // membership read, so once the queue held 150 mints every re-ask for a mint already in it was counted as given up and
        // its observation closed UNKNOWN while its fetch still sat in the queue. Queued is waiting.
        if (queue.contains(mint)) return WAIT_8430
        if (attempted.contains(mint)) return NONE_8430
        if (nowMs - bootMs > RECOVERY_WINDOW_MS_8430 || attempted.size + queue.size >= MAX_MINTS_PER_BOOT_8430) {
            attempted.add(mint); gaveUp.incrementAndGet(); return NONE_8430
        }
        queue.add(mint)
        return WAIT_8430
    }

    /** ForwardReturnLabeler7731.tick: one bounded fetch when the provider slot is idle and the gap has passed. */
    fun drain8430(nowMs: Long) {
        if (queue.isEmpty() || nowMs - lastFetchMs < FETCH_GAP_MS_8430) return
        val wait = try { com.lifecyclebot.network.SolanaOhlcvFeed6916.providerSlotWait7809(nowMs) } catch (_: Throwable) { return }
        // V5.0.8435 — 8434 (5.4 min): queued=150 fetched=0. The provider's own call interval rarely reads exactly zero at the
        // labeler's tick, so "waitMs > 0 -> return" never fetched. A cooldown (429 / dead source) still defers; a short interval
        // gap is slept on the fetch thread instead.
        if (wait.cooldown || wait.waitMs > MAX_SLOT_SLEEP_MS_8435) return
        val sleepMs8435 = wait.waitMs.coerceAtLeast(0L)
        if (!fetching.compareAndSet(false, true)) return
        val mint = synchronized(queue) { queue.firstOrNull()?.also { queue.remove(it) } }
        if (mint == null) { fetching.set(false); return }
        lastFetchMs = nowMs
        attempted.add(mint)
        inFlight8430 = mint
        try {
            Thread({
                try {
                    if (sleepMs8435 > 0L) try { Thread.sleep(sleepMs8435) } catch (_: InterruptedException) {}
                    val cs = try { com.lifecyclebot.network.SolanaOhlcvFeed6916.fetchCandles6916(mint, "1m", CANDLES_8430) } catch (_: Throwable) { emptyList() }
                    if (cs.isEmpty()) empty.incrementAndGet() else fetched.incrementAndGet()
                    candles[mint] = cs
                    if (candles.size > 400) candles.keys.take(100).forEach { candles.remove(it) }
                } finally { inFlight8430 = ""; fetching.set(false) }
            }, "label-downtime-8430").apply { isDaemon = true }.start()
        } catch (_: Throwable) { inFlight8430 = ""; fetching.set(false) }
    }

    @Volatile private var inFlight8430 = ""

    fun statusLine8430(): String =
        "downtimeRecovery8430[queued=${queue.size} fetched=${fetched.get()} empty=${empty.get()} recovered=${recovered.get()} " +
            "gaveUp=${gaveUp.get()} cap=$MAX_MINTS_PER_BOOT_8430 mints/${RECOVERY_WINDOW_MS_8430 / 60_000L}m]"

    internal fun seedForTest8430(mint: String, cs: List<Candle>) { candles[mint] = cs }
    internal fun resetForTest8430() { candles.clear(); attempted.clear(); queue.clear(); fetching.set(false); lastFetchMs = 0L; fetched.set(0); empty.set(0); recovered.set(0); gaveUp.set(0) }
}

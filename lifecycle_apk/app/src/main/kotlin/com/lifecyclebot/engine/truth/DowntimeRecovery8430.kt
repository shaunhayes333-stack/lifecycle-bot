package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.Candle
import com.lifecyclebot.network.OhlcvRequestQueue8401
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
    // V5.0.8651 — 150 mints in a 15-minute recovery window cannot be drained
    // with the old one-per-10s cadence: at most 90 requests could even start,
    // before provider cooldown or live chart traffic. A 4s dispatch gap allows
    // 225 theoretical starts while the Gecko slot's adaptive 2.5-6s rate gate,
    // its cooldown and the existing single in-flight request still bind.
    // The hard 150-unique-mint cap and original candle/basis checks are unchanged.
    const val FETCH_GAP_MS_8430 = 4_000L
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
    // A local refusal is not a completed attempt. These deadlines bound retries even while the provider stays unavailable.
    private val retryUntil8527 = ConcurrentHashMap<String, Long>()
    private val providerFailures8527 = ConcurrentHashMap<String, Int>()
    private const val MAX_PROVIDER_FAILURES_8527 = 3
    private val requeued8527 = AtomicLong(0)
    private val localWaits8527 = AtomicLong(0)
    private val failed8527 = AtomicLong(0)
    private val fetching = AtomicBoolean(false)
    @Volatile private var lastFetchMs = 0L
    private val fetched = AtomicLong(0)
    private val empty = AtomicLong(0)
    private val recovered = AtomicLong(0)
    private val gaveUp = AtomicLong(0)
    private val yielded8439 = AtomicLong(0)
    // V5.0.8535 §P0-02 — every queued backfill ends with one named receipt (the labeler writes it on the unavailable terminal):
    // RECOVERED / FETCHED / PROVIDER_EMPTY / NO_CANDLE_IN_WINDOW / BOOT_WINDOW_CLOSED / BOOT_CAPACITY / RETRY_WINDOW_EXPIRED /
    // PROVIDER_FAILURES_n. A queued request is not a priced label; an unavailable one is not a negative return.
    private val receipts8535 = ConcurrentHashMap<String, String>()
    private val gaveUpBy8535 = ConcurrentHashMap<String, AtomicLong>()
    private val slotReadFailed8535 = AtomicLong(0)
    private const val MAX_RECEIPTS_8535 = 2_000

    private fun receipt8535(mint: String, why: String) {
        if (receipts8535.size >= MAX_RECEIPTS_8535 && !receipts8535.containsKey(mint)) return
        receipts8535[mint] = why
    }

    /** The receipt of [mint]'s backfill request, or null when it was never queued here. */
    fun receipt8535(mint: String): String? = receipts8535[mint]

    private fun gaveUp8535(mint: String, why: String) {
        if (attempted.add(mint)) { gaveUp.incrementAndGet(); gaveUpBy8535.computeIfAbsent(why) { AtomicLong() }.incrementAndGet() }
        receipt8535(mint, why)
    }

    /** Pure: did a window ending at [deadlineMs] close before this process started ([bootMs])? */
    fun downtime8430(deadlineMs: Long, bootMs: Long): Boolean = deadlineMs < bootMs

    /** Pure: a candle timestamp in milliseconds (a provider's seconds are normalised). */
    private fun ms8430(ts: Long): Long = if (ts in 1L..99_999_999_999L) ts * 1000L else ts

    /** The feed's priceUsd is the bar CLOSE, while ts is its OPEN. A close is knowable at the end of the minute. */
    private fun closeAt8521(c: Candle): Long = ms8430(c.ts) + CANDLE_MS_8430

    /** Pure: a completed candle close inside [fromMs]..[toMs], nearest the target; no timestamp is moved into a window. */
    fun pick8430(cs: List<Candle>, targetMs: Long, fromMs: Long, toMs: Long): Candle? =
        cs.asSequence()
            .filter { c -> val t = closeAt8521(c); !c.synthetic && t in fromMs..toMs && c.priceUsd.isFinite() && c.priceUsd > 0.0 }
            .minByOrNull { kotlin.math.abs(closeAt8521(it) - targetMs) }

    /**
     * ForwardReturnLabeler7731.expired8395: the recovered candle mark of [mint] at horizon [targetMs] for a window
     * ([fromMs], [deadlineMs]] that closed before [bootMs]; WAIT while its fetch is queued inside the boot window.
     */
    fun candleMark8430(mint: String, targetMs: Long, fromMs: Long, deadlineMs: Long, bootMs: Long, nowMs: Long): Read8430 {
        if (mint.isBlank() || !downtime8430(deadlineMs, bootMs)) return NONE_8430
        return synchronized(queue) {
            // Completion publishes candles and terminal membership under this same lock. A result landing between
            // a cache miss and the attempted read must not make a genuinely recovered label close unavailable.
            candles[mint]?.let { cs ->
                val c = pick8430(cs, targetMs, fromMs, minOf(deadlineMs, nowMs)) ?: run { receipt8535(mint, "NO_CANDLE_IN_WINDOW"); return@synchronized NONE_8430 }
                recovered.incrementAndGet()
                receipt8535(mint, "RECOVERED")
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("FORWARD_LABEL_DOWNTIME_CANDLE_8430") } catch (_: Throwable) {}
                val at = closeAt8521(c)
                return@synchronized Read8430(false, LabelMark8386(mint, "ohlcv", c.priceUsd, at, nowMs, SOURCE_8430))
            }
            // Expiry precedes queue/in-flight membership. A dead provider must not hold a restored label forever.
            if (nowMs - bootMs > RECOVERY_WINDOW_MS_8430) {
                queue.remove(mint)
                retryUntil8527.remove(mint)
                providerFailures8527.remove(mint)
                gaveUp8535(mint, "BOOT_WINDOW_CLOSED")
                return@synchronized NONE_8430
            }
            if (mint == inFlight8430 || queue.contains(mint)) return@synchronized WAIT_8430
            if (attempted.contains(mint)) return@synchronized NONE_8430
            val inFlight = if (inFlight8430.isNotBlank() && !attempted.contains(inFlight8430)) 1 else 0
            if (attempted.size + queue.size + inFlight >= MAX_MINTS_PER_BOOT_8430) {
                gaveUp8535(mint, "BOOT_CAPACITY")
                return@synchronized NONE_8430
            }
            retryUntil8527[mint] = bootMs + RECOVERY_WINDOW_MS_8430
            queue.add(mint)
            WAIT_8430
        }
    }

    /** ForwardReturnLabeler7731.tick: one bounded fetch when the provider slot is idle and the gap has passed. */
    fun drain8430(nowMs: Long) {
        expireQueued8527(nowMs)
        if (queue.isEmpty() || nowMs - lastFetchMs < FETCH_GAP_MS_8430) return
        // V5.0.8535 §P0-02 — a silent return here left the 8529 queue at fetched=1 with no reason on record.
        val wait = try { com.lifecyclebot.network.SolanaOhlcvFeed6916.providerSlotWait7809(nowMs) } catch (_: Throwable) { slotReadFailed8535.incrementAndGet(); return }
        // V5.0.8435 — 8434 (5.4 min): queued=150 fetched=0. The provider's own call interval rarely reads exactly zero at the
        // labeler's tick, so "waitMs > 0 -> return" never fetched. A cooldown (429 / dead source) still defers; a short interval
        // gap is slept on the fetch thread instead.
        // 8480 — the yield-to-live door (8439 spare bucket / 8457 one-a-minute trickle) is removed: it held recovery to ~1 fetch a
        // minute against a 150-mint boot queue and a 15-minute window (pipeline report: downtime recovered 0/145). The provider's
        // own slot (cooldown on 429 / dead source, interval wait) still paces every call. Free sources only (Birdeye is dead).
        val slot8457 = !wait.cooldown && wait.waitMs <= MAX_SLOT_SLEEP_MS_8435
        if (!slot8457) { yielded8439.incrementAndGet(); return }
        val sleepMs8435 = wait.waitMs.coerceAtLeast(0L)
        if (!fetching.compareAndSet(false, true)) return
        val mint = takeQueued8527(nowMs)
        if (mint == null) { fetching.set(false); return }
        lastFetchMs = nowMs
        try {
            Thread({
                try {
                    if (sleepMs8435 > 0L) try { Thread.sleep(sleepMs8435) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    val answer = if (Thread.currentThread().isInterrupted || System.currentTimeMillis() > (retryUntil8527[mint] ?: 0L))
                        OhlcvRequestQueue8401.Answer<Candle>(OhlcvRequestQueue8401.State.DEADLINE)
                    else fetchOwned8527 { com.lifecyclebot.network.SolanaOhlcvFeed6916.fetchAnswer8527(mint, "1m", CANDLES_8430) }
                    completeFetch8527(mint, answer, System.currentTimeMillis())
                } finally { synchronized(queue) { if (inFlight8430 == mint) inFlight8430 = "" }; fetching.set(false) }
            }, "label-downtime-8430").apply { isDaemon = true }.start()
        } catch (_: Throwable) {
            completeFetch8527(mint, OhlcvRequestQueue8401.Answer(OhlcvRequestQueue8401.State.PROVIDER_FAILURE), nowMs)
            synchronized(queue) { if (inFlight8430 == mint) inFlight8430 = "" }
            fetching.set(false)
        }
    }

    @Volatile private var inFlight8430 = ""

    /** Raw daemon threads own cancellation locally; it must neither crash Android nor count as a provider failure. */
    internal fun fetchOwned8527(fetch: () -> OhlcvRequestQueue8401.Answer<Candle>): OhlcvRequestQueue8401.Answer<Candle> =
        try { fetch() }
        catch (_: kotlinx.coroutines.CancellationException) { OhlcvRequestQueue8401.Answer(OhlcvRequestQueue8401.State.DEADLINE) }
        catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            OhlcvRequestQueue8401.Answer(OhlcvRequestQueue8401.State.DEADLINE)
        }
        catch (_: Exception) { OhlcvRequestQueue8401.Answer(if (Thread.currentThread().isInterrupted)
            OhlcvRequestQueue8401.State.DEADLINE else OhlcvRequestQueue8401.State.PROVIDER_FAILURE) }

    /** One queue owner per mint. In-flight work counts against the same unique-mint boot budget. */
    internal fun takeQueued8527(nowMs: Long): String? = synchronized(queue) {
        expireQueued8527(nowMs)
        if (inFlight8430.isNotBlank()) return@synchronized null
        queue.firstOrNull()?.also { queue.remove(it); inFlight8430 = it }
    }

    private fun retire8527(mint: String, why: String) {
        queue.remove(mint)
        retryUntil8527.remove(mint)
        providerFailures8527.remove(mint)
        gaveUp8535(mint, why)
    }

    /** Expiry runs even when a provider cooldown prevents any fetch from starting. */
    internal fun expireQueued8527(nowMs: Long) = synchronized(queue) {
        queue.toList().filter { nowMs > (retryUntil8527[it] ?: 0L) }.forEach { retire8527(it, "RETRY_WINDOW_EXPIRED") }
    }

    /**
     * Retain temporary waits and transport failures as work, never as a fabricated empty source answer.
     * Actual completed candles may still arrive from an already-started call after the boot window; their original
     * source close times are retained, and only the labeler's existing exact pending episode can consume them.
     */
    internal fun completeFetch8527(mint: String, answer: OhlcvRequestQueue8401.Answer<Candle>, nowMs: Long) = synchronized(queue) {
        if (inFlight8430 != mint) return@synchronized
        queue.remove(mint)
        val terminal = (answer.state == OhlcvRequestQueue8401.State.ANSWERED_BARS && answer.rows.isNotEmpty()) ||
            (answer.state == OhlcvRequestQueue8401.State.ANSWERED_EMPTY && answer.rows.isEmpty())
        if (terminal) {
            if (answer.rows.isEmpty()) { empty.incrementAndGet(); receipt8535(mint, "PROVIDER_EMPTY") } else { fetched.incrementAndGet(); receipt8535(mint, "FETCHED") }
            candles[mint] = answer.rows
            attempted.add(mint)
            retryUntil8527.remove(mint)
            providerFailures8527.remove(mint)
            if (candles.size > 400) candles.keys.take(100).forEach { candles.remove(it) }
        } else {
            val failure = answer.state == OhlcvRequestQueue8401.State.PROVIDER_FAILURE ||
                answer.state == OhlcvRequestQueue8401.State.ANSWERED_BARS || answer.state == OhlcvRequestQueue8401.State.ANSWERED_EMPTY
            val failures = if (failure) providerFailures8527.merge(mint, 1) { a, b -> a + b } ?: 1 else providerFailures8527[mint] ?: 0
            if (failure) failed8527.incrementAndGet() else localWaits8527.incrementAndGet()
            if (nowMs > (retryUntil8527[mint] ?: 0L) || failures >= MAX_PROVIDER_FAILURES_8527 || attempted.contains(mint))
                retire8527(mint, when {
                    failures >= MAX_PROVIDER_FAILURES_8527 -> "PROVIDER_FAILURES_$failures"
                    nowMs > (retryUntil8527[mint] ?: 0L) -> "RETRY_WINDOW_EXPIRED"
                    else -> "ALREADY_ATTEMPTED"
                })
            else { queue.add(mint); requeued8527.incrementAndGet() }
        }
        inFlight8430 = ""
    }


    /** V5.0.8438 — DiagnosisBrief8438: (queued, fetched, recovered, gaveUp). */
    fun counts8438(): LongArray = longArrayOf(queue.size.toLong(), fetched.get(), recovered.get(), gaveUp.get(), empty.get(), yielded8439.get())

    fun statusLine8430(): String =
        "downtimeRecovery8430[queued=${queue.size} fetched=${fetched.get()} empty=${empty.get()} recovered=${recovered.get()} " +
            "gaveUp=${gaveUp.get()} slotBusy=${yielded8439.get()} retry8527=${requeued8527.get()} " +
            "localWait8527=${localWaits8527.get()} providerFail8527=${failed8527.get()} cap=$MAX_MINTS_PER_BOOT_8430 mints/${RECOVERY_WINDOW_MS_8430 / 60_000L}m " +
            "gaveUpBy8535{" + gaveUpBy8535.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" } + "} slotReadFailed8535=${slotReadFailed8535.get()}] ${ChainHistoryMark8541.statusLine8541()}"

    internal fun seedForTest8430(mint: String, cs: List<Candle>) { candles[mint] = cs }
    internal fun resetForTest8430() {
        candles.clear(); attempted.clear(); queue.clear(); retryUntil8527.clear(); providerFailures8527.clear()
        fetching.set(false); inFlight8430 = ""; lastFetchMs = 0L
        fetched.set(0); empty.set(0); recovered.set(0); gaveUp.set(0); yielded8439.set(0)
        requeued8527.set(0); localWaits8527.set(0); failed8527.set(0)
        receipts8535.clear(); gaveUpBy8535.clear(); slotReadFailed8535.set(0)
    }
}

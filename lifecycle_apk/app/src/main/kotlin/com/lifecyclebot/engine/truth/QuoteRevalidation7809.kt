package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7809 §A_STALE_QUOTE_IS_A_QUESTION_NOT_AN_ANSWER.
 *
 * 5.0.7808 live: FIELD_MANUAL_WAIT_7715:quote. FieldManual7715 correctly
 * refuses to price an entry on a quote older than QUOTE_MAX_AGE_MS_7715, but
 * nothing ever asked for a new quote, so an otherwise valid setup (every other
 * card field answered) waited until the scanner happened to touch the mint
 * again — usually after the move. Field Manual L33 / L187 / L240: verify data
 * freshness, get an executable quote, measure quote age.
 *
 * What this does, and only this:
 *  - FieldManual7715.decide() asks for a revalidation when a LIVE card's only
 *    open questions are quote age / quote freshness.
 *  - One bounded, background re-quote per mint through the existing
 *    independent-feed resolver (ParallelMarkFanout7088.resolve7088, which
 *    includes the executable Jupiter quote). Single-flight per mint, at most
 *    MAX_ATTEMPTS_7809 per WINDOW_MS_7809 with backoff between attempts, so a
 *    mint that cannot be quoted cannot loop.
 *  - A fresh quote that AGREES with the price the card is built on (within
 *    CONFIRM_TOLERANCE_7809) confirms that price as current; the next card for
 *    the mint reads the confirmed age, re-runs cost / impact / R:R and decides
 *    again. The FDG verdict caches for the mint are dropped so that next look
 *    is a real one.
 *  - A contested fan-out, no answer, or a quote that DISAGREES (the card's
 *    price is stale) confirms nothing: the card keeps waiting until the price
 *    itself is refreshed. Nothing here writes a price into TokenState, opens a
 *    ticket or executes; execution still goes through every existing gate,
 *    lease and finality check, so a revalidation cannot cause a duplicate buy
 *    and an entry is never taken on stale price evidence.
 */
object QuoteRevalidation7809 {

    private data class Confirmed(val priceUsd: Double, val confirmedAtMs: Long)

    private data class Attempts(val count: Int, val windowStartMs: Long, val lastMs: Long)

    private const val MAX_ATTEMPTS_7809 = 3
    private const val WINDOW_MS_7809 = 10L * 60_000L
    private val BACKOFF_MS_7809 = longArrayOf(0L, 15_000L, 45_000L)
    private const val CONFIRM_TOLERANCE_7809 = 0.03
    private const val MAP_CAP_7809 = 2_048

    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val attempts = ConcurrentHashMap<String, Attempts>()
    private val confirmed = ConcurrentHashMap<String, Confirmed>()

    private val requested = AtomicLong(0L)
    private val confirmedCount = AtomicLong(0L)
    private val moved = AtomicLong(0L)
    private val unanswered = AtomicLong(0L)
    private val throttled = AtomicLong(0L)

    private val worker by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "quote-revalidation-7809").apply { isDaemon = true } }
    }

    /** Price source; uncorroborated-but-contested fan-outs answer null. Replaceable in tests. */
    @Volatile
    internal var fetcher7809: (String) -> Double? = { mint ->
        val mark = com.lifecyclebot.network.ParallelMarkFanout7088.resolve7088(listOf(mint))[mint]
        if (mark == null || (mark.sourceCount >= 2 && !mark.corroborated)) null
        else mark.priceUsd.takeIf { it.isFinite() && it > 0.0 }
    }

    /** Drops the mint's FDG verdict caches so the next look is a real evaluation. Replaceable in tests. */
    @Volatile
    internal var onConfirmed7809: (String) -> Unit = { mint ->
        com.lifecyclebot.engine.FinalDecisionGate.invalidateCandidate6734(mint)
    }

    /** False runs the re-quote on the caller's thread (tests only). */
    @Volatile
    internal var async7809: Boolean = true

    /**
     * Ask for a re-quote of [mint] to confirm [cardPriceUsd]. Returns true when
     * a re-quote was started; false when one is already in flight, the mint is
     * in backoff, or its attempt budget for the window is spent.
     */
    fun request(mint: String, cardPriceUsd: Double, nowMs: Long = System.currentTimeMillis()): Boolean {
        val m = mint.trim()
        if (m.isBlank() || !cardPriceUsd.isFinite() || cardPriceUsd <= 0.0) return false
        val prior = attempts[m]?.let { if (nowMs - it.windowStartMs > WINDOW_MS_7809) null else it }
        if (prior != null) {
            val wait = BACKOFF_MS_7809[prior.count.coerceIn(0, BACKOFF_MS_7809.size - 1)]
            if (prior.count >= MAX_ATTEMPTS_7809 || nowMs - prior.lastMs < wait) {
                throttled.incrementAndGet()
                return false
            }
        }
        if (!inFlight.add(m)) return false
        attempts[m] = Attempts((prior?.count ?: 0) + 1, prior?.windowStartMs ?: nowMs, nowMs)
        if (attempts.size > MAP_CAP_7809) {
            attempts.entries.removeIf { nowMs - it.value.windowStartMs > WINDOW_MS_7809 }
        }
        requested.incrementAndGet()
        try { PipelineHealthCollector.labelInc("QUOTE_REVALIDATION_REQUESTED_7809") } catch (_: Throwable) {}
        val job = Runnable {
            try { revalidate(m, cardPriceUsd) } catch (_: Throwable) {} finally { inFlight.remove(m) }
        }
        if (!async7809) {
            job.run()
            return true
        }
        try {
            worker.execute(job)
        } catch (_: Throwable) {
            inFlight.remove(m)
            return false
        }
        return true
    }

    private fun revalidate(mint: String, cardPriceUsd: Double) {
        val fresh = try { fetcher7809(mint) } catch (_: Throwable) { null }
        val now = System.currentTimeMillis()
        if (fresh == null || !fresh.isFinite() || fresh <= 0.0) {
            unanswered.incrementAndGet()
            try { PipelineHealthCollector.labelInc("QUOTE_REVALIDATION_UNANSWERED_7809") } catch (_: Throwable) {}
            return
        }
        val drift = kotlin.math.abs(fresh - cardPriceUsd) / cardPriceUsd
        if (drift > CONFIRM_TOLERANCE_7809) {
            moved.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("QUOTE_REVALIDATION_PRICE_MOVED_7809")
                ForensicLogger.lifecycle(
                    "QUOTE_REVALIDATION_PRICE_MOVED_7809",
                    "mint=${mint.take(10)} cardPrice=$cardPriceUsd freshQuote=$fresh drift=${"%.1f".format(drift * 100.0)}% " +
                        "action=keep_wait_card_price_is_stale",
                )
            } catch (_: Throwable) {}
            return
        }
        confirmed[mint] = Confirmed(fresh, now)
        if (confirmed.size > MAP_CAP_7809) {
            confirmed.entries.removeIf { now - it.value.confirmedAtMs > FieldManual7715.QUOTE_MAX_AGE_MS_7715 }
        }
        confirmedCount.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("QUOTE_REVALIDATION_CONFIRMED_7809")
            ForensicLogger.lifecycle(
                "QUOTE_REVALIDATION_CONFIRMED_7809",
                "mint=${mint.take(10)} cardPrice=$cardPriceUsd freshQuote=$fresh drift=${"%.2f".format(drift * 100.0)}% " +
                    "action=reconsider_with_fresh_quote",
            )
        } catch (_: Throwable) {}
        try { onConfirmed7809(mint) } catch (_: Throwable) {}
    }

    /**
     * Age of a fresh quote that confirmed [cardPriceUsd] for [mint], or null.
     * The confirmation only counts while the card is still built on that
     * price: if the price has since moved beyond the tolerance it no longer
     * vouches for it.
     */
    fun confirmedAgeMs(mint: String, cardPriceUsd: Double, nowMs: Long = System.currentTimeMillis()): Long? {
        val c = confirmed[mint.trim()] ?: return null
        if (!cardPriceUsd.isFinite() || cardPriceUsd <= 0.0) return null
        val age = nowMs - c.confirmedAtMs
        if (age < 0L || age > FieldManual7715.QUOTE_MAX_AGE_MS_7715) return null
        if (kotlin.math.abs(c.priceUsd - cardPriceUsd) / cardPriceUsd > CONFIRM_TOLERANCE_7809) return null
        return age
    }

    fun statusLine(): String =
        "QuoteRevalidation7809 requested=${requested.get()} confirmed=${confirmedCount.get()} moved=${moved.get()} " +
            "unanswered=${unanswered.get()} throttled=${throttled.get()} inFlight=${inFlight.size}"

    internal fun resetForTest() {
        inFlight.clear(); attempts.clear(); confirmed.clear()
        requested.set(0L); confirmedCount.set(0L); moved.set(0L); unanswered.set(0L); throttled.set(0L)
    }
}

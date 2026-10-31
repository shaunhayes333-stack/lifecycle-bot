package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8189 — THE BEST EXIT MARK IS A SELL QUOTE FOR WHAT IS HELD.
 *
 * Owner diagnosis of 5.0.8186: 14 held marks rejected / suppressed, 12 of 36 mark repairs succeeded,
 * PROTECTIVE_EXIT_ARMED_STALL_7213=1 (positions open, no price compared to a threshold for 15 s) and no profit rung
 * ever requested. Trace of the held-position mark chain (HeldHotMarkAuthority7419.refreshPass):
 *
 *   locked venue (curve / sealed pool) and the corroborated fan-out share ONE 1.2 s whole-book deadline; the
 *   executable sell-quote fallback (7876) then gets only what is LEFT of that deadline
 *   (HeldQuoteBatch8077.resolve(..., BATCH_FANOUT_DEADLINE_MS_7510)) on a six-thread zero-queue pool the two book
 *   futures already occupy. A Jupiter quote (Ultra, then the v6 fallback) routinely takes longer than that, so the
 *   future is cancelled, the answer is thrown away, the mint is debounced 4 s and the next pass starts over: the
 *   last-resort mark almost never lands. With no fresh EXIT_ECONOMIC mark the risk clock pings with markPx = 0
 *   (no threshold comparison: the armed stall), the spike / lifecycle ladders wait for a fresh mark, and the
 *   mark-repair cascade (MarkIdentityRepairAuthority7236) fails with NO_FEED_ANSWERED.
 *
 * This object keeps the quote off that deadline: a Jupiter SELL quote for the position's actual held quantity
 * (min(canonical remaining, last owner+mint wallet read) at the canonical scale) runs on its own two daemon threads and
 * its answer is cached with the time the request was made (never the completion time). The next refresh pass, the
 * repair cascade and the proof table read it while it is at most [QUOTE_FRESH_MS_8189] old. It is the price a sale of
 * THIS holding would get, so it can only be lower than a feed print, never a phantom peak; the peak ratchet keeps its
 * own two-feed corroboration rules (PositionDisplayTruth8078) and nothing here writes a peak.
 */
object HeldExitQuote8189 {
    const val SOURCE_8189 = "JUPITER_HELD_SELL_QUOTE_8189"
    /**
     * A held sell quote is an exit mark for this long after it was REQUESTED. V5.0.8262 — 4 s was shorter than a
     * Jupiter round trip stamped at request time, so the answer was stale on arrival (8261: Fartcoin quote 16 s old at
     * +370 %, mark=NONE, no rung requested; 125/232 mark repairs failed). A sale re-quotes at send; this only decides
     * whether the ladders may compare a price to their thresholds.
     */
    internal const val QUOTE_FRESH_MS_8189 = 20_000L
    /** Re-quote a mint at most this often. */
    private const val QUOTE_DEBOUNCE_MS_8189 = 2_500L
    /** A wallet read older than this does not cap the quoted quantity. */
    private const val WALLET_READ_MAX_AGE_MS_8189 = 10L * 60_000L

    data class Quote8189(val priceUsd: Double, val observedAtMs: Long, val rawQty: BigInteger,
                         val proceedsSol: Double? = null, val source: String = SOURCE_8189)

    // ── pure ──

    /**
     * Pure: the raw quantity a held exit quote is sized on — min(canonical remaining, wallet) when the wallet read is
     * positive at the canonical scale, else the canonical remaining. Null when nothing is canonically held or the
     * quantity does not fit a quote request (63 bits).
     */
    fun heldQty8189(canonicalRaw: BigInteger, walletRaw: BigInteger?, walletScaleMatches: Boolean): BigInteger? {
        if (canonicalRaw.signum() <= 0) return null
        val q = if (walletRaw != null && walletScaleMatches && walletRaw.signum() > 0) canonicalRaw.min(walletRaw) else canonicalRaw
        return q.takeIf { it.signum() > 0 && it.bitLength() <= 62 }
    }

    /** Pure: is [q] usable as an exit mark at [nowMs]? */
    fun usable8189(q: Quote8189?, nowMs: Long): Boolean =
        q != null && q.priceUsd.isFinite() && q.priceUsd > 0.0 && nowMs - q.observedAtMs in 0L..QUOTE_FRESH_MS_8189

    /** An average exit price belongs to one exact remaining quantity. */
    internal fun matchesQuantity(q: Quote8189?, raw: BigInteger, nowMs: Long): Boolean =
        usable8189(q, nowMs) && q?.rawQty == raw

    /** Pure: is a new quote due ([lastAttemptMs] 0 = never asked)? */
    fun due8189(lastAttemptMs: Long, nowMs: Long, inFlight: Boolean): Boolean =
        !inFlight && (lastAttemptMs <= 0L || nowMs - lastAttemptMs >= QUOTE_DEBOUNCE_MS_8189)

    // ── cache ──

    private val quotes = ConcurrentHashMap<String, Quote8189>()
    private val attempts = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val asked = AtomicLong(0)
    private val answered = AtomicLong(0)
    private val empty = AtomicLong(0)
    private val served = AtomicLong(0)

    private val lane8189: ThreadPoolExecutor = ThreadPoolExecutor(
        2, 2, 30L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(32),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "held-exit-quote-8189").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private fun bare8189(mint: String): String = mint.removePrefix("solana|").trim()

    /** The last owner+mint wallet read of [mint] (StopLatencyCore8153 proof), if recent: (raw, decimals). */
    fun walletRead8189(mint: String, nowMs: Long): Pair<BigInteger, Int>? {
        val p = com.lifecyclebot.engine.sell.StopLatencyWiring8153.provenHolding8153(bare8189(mint), nowMs)
            ?: return null
        return p.raw to p.decimals
    }

    /** The cached quote of [mint] while it is fresh. Never blocks, never asks. */
    fun fresh8189(mint: String, nowMs: Long = System.currentTimeMillis()): Quote8189? =
        quotes[mint]?.takeIf { usable8189(it, nowMs) }

    /** Any cached quote of [mint] (for the proof table: its age is printed). */
    fun last8189(mint: String): Quote8189? = quotes[mint]

    /**
     * HeldHotMarkAuthority7419.refreshPass: the fresh held sell quote of [mint] for [canonicalRaw] at [scale], or null.
     * Asks for a new quote in the background when one is due (the answer serves the next pass). Never blocks.
     */
    fun take8189(mint: String, canonicalRaw: BigInteger, scale: Int, nowMs: Long = System.currentTimeMillis()): Quote8189? {
        if (mint.isBlank() || scale !in 0..18) return null
        val cached = fresh8189(mint, nowMs)?.takeIf { matchesQuantity(it, canonicalRaw, nowMs) }
        if (due8189(attempts[mint] ?: 0L, nowMs, mint in inFlight)) ask8189(mint, canonicalRaw, scale, nowMs)
        if (cached != null) served.incrementAndGet()
        return cached
    }

    private fun ask8189(mint: String, canonicalRaw: BigInteger, scale: Int, nowMs: Long) {
        val w = walletRead8189(mint, nowMs)
        val qty = heldQty8189(canonicalRaw, w?.first, w?.second == scale) ?: return
        if (!inFlight.add(mint)) return
        attempts[mint] = nowMs
        if (attempts.size > 2_000) attempts.clear()
        try {
            lane8189.execute {
                try { quoteInto8189(mint, qty, scale, System.currentTimeMillis()) } finally { inFlight.remove(mint) }
            }
        } catch (_: Throwable) { inFlight.remove(mint) }
    }

    /** Blocking: quote [qty] of [mint] now and cache it (stamped at [askedAtMs]). Off the main thread only. */
    private fun quoteInto8189(mint: String, qty: BigInteger, scale: Int, askedAtMs: Long): Quote8189? {
        asked.incrementAndGet()
        val estimate = try { HeldHotMarkAuthority7419.quoteSellEstimate(mint, qty, scale) } catch (_: Throwable) { null }
        if (estimate == null || !estimate.priceUsd.isFinite() || estimate.priceUsd <= 0.0) {
            empty.incrementAndGet()
            try { PipelineHealthCollector.labelInc("HELD_EXIT_QUOTE_NO_ROUTE_8189") } catch (_: Throwable) {}
            // V5.0.8435 — the miss by cause (a thrown call / Jupiter null / qty mismatch / no fresh reserve / bad price).
            val cause8435 = (try { HeldHotMarkAuthority7419.takeMiss8435(mint) } catch (_: Throwable) { null }) ?: if (estimate == null) "UNKNOWN" else "PRICE_INVALID"
            if (missCauses8435.size < 32 || missCauses8435.containsKey(cause8435)) missCauses8435.computeIfAbsent(cause8435) { AtomicLong() }.incrementAndGet()
            return null
        }
        val q = Quote8189(estimate.priceUsd, minOf(askedAtMs, estimate.observedAtMs), qty, estimate.proceedsSol, estimate.source)
        quotes[mint] = q
        if (quotes.size > 2_000) quotes.clear()
        answered.incrementAndGet()
        try { PipelineHealthCollector.labelInc("HELD_EXIT_QUOTE_OK_8189") } catch (_: Throwable) {}
        return q
    }

    /**
     * MarkIdentityRepairAuthority7236 (the repair's last resort, already off the main thread): a fresh held sell quote
     * for the open LIVE canonical position of [mint], asked now when none is cached. Null when the bot holds none.
     */
    fun quoteNow8189(mint: String, nowMs: Long = System.currentTimeMillis()): Quote8189? {
        val pos = try {
            (CanonicalPositionAuthority6441.firstOpenForMint(mint) ?: CanonicalPositionAuthority6441.firstOpenForMint(bare8189(mint)))
                ?.takeIf { it.mode.equals("live", true) }
        } catch (_: Throwable) { null } ?: return null
        fresh8189(mint, nowMs)?.takeIf { it.rawQty == pos.remainingQtyRaw }?.let { return it }
        val w = walletRead8189(pos.mint, nowMs)
        val qty = heldQty8189(pos.remainingQtyRaw, w?.first, w?.second == pos.quantityScale) ?: return null
        if (!inFlight.add(pos.mint)) return null
        attempts[pos.mint] = nowMs
        return try { quoteInto8189(pos.mint, qty, pos.quantityScale, nowMs) } finally { inFlight.remove(pos.mint) }
    }

    private val missCauses8435 = ConcurrentHashMap<String, AtomicLong>()

    /** V5.0.8438 — DiagnosisBrief8438: (asked, answered) held sell quotes this process. */
    fun askedOk8438(): Pair<Long, Long> = asked.get() to answered.get()

    fun statusLine8189(): String =
        HeldHotMarkAuthority7419.dustRouteLine8475() + " " +   // V5.0.8475
        "heldQuote8189[asked=${asked.get()} ok=${answered.get()} noRoute=${empty.get()} served=${served.get()} cached=${quotes.size} inFlight=${inFlight.size}" +
            " missBy8435{${missCauses8435.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}}]"

    internal fun putForTest8189(mint: String, q: Quote8189) { quotes[mint] = q }
    internal fun resetForTest8189() { quotes.clear(); attempts.clear(); inFlight.clear() }
}

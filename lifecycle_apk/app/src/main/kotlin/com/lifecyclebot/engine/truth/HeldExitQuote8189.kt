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
 * its answer retains the original route request or reserve observation time. The next refresh pass, the
 * repair cascade and proof table apply the evidence type’s freshness window. Reserve payout models are explicitly
 * distinguished from routed quotes and cannot authorize a transaction. The peak ratchet keeps its
 * own two-feed corroboration rules (PositionDisplayTruth8078) and nothing here writes a peak.
 */
object HeldExitQuote8189 {
    const val SOURCE_8189 = "JUPITER_HELD_SELL_QUOTE_8189"
    /** A held sell quote is an exit mark for this long after it was requested. */
    private const val QUOTE_FRESH_MS_8189 = 4_000L
    /** Re-quote a mint at most this often. */
    private const val QUOTE_DEBOUNCE_MS_8189 = 2_500L
    /** A wallet read older than this does not cap the quoted quantity. */
    private const val WALLET_READ_MAX_AGE_MS_8189 = 10L * 60_000L

    data class Quote8189(
        val priceUsd: Double,
        val observedAtMs: Long,
        val rawQty: BigInteger,
        val holding: HeldQuoteEvidence8239.Holding? = null,
        val observation: HeldQuoteEvidence8239.Observation? = null,
    ) {
        val source: String get() = observation?.source ?: "HELD_QUOTE_UNBOUND_8239"
    }

    // ── pure ──

    /**
     * Pure: the raw quantity a held exit quote is sized on — min(canonical remaining, wallet) when the wallet read is
     * observed at the canonical scale, else the canonical remaining. An observed zero does not become unknown.
     * Null when nothing is canonically held or the
     * quantity does not fit a quote request (63 bits).
     */
    fun heldQty8189(canonicalRaw: BigInteger, walletRaw: BigInteger?, walletScaleMatches: Boolean): BigInteger? {
        if (canonicalRaw.signum() <= 0) return null
        val q = if (walletRaw != null && walletScaleMatches && walletRaw.signum() >= 0) canonicalRaw.min(walletRaw) else canonicalRaw
        return q.takeIf { it.signum() > 0 && it.bitLength() <= 62 }
    }

    /** Pure: is [q] usable as an exit mark at [nowMs]? */
    fun usable8189(q: Quote8189?, nowMs: Long): Boolean =
        q != null && q.priceUsd.isFinite() && q.priceUsd > 0.0 &&
            (q.observation?.let { HeldQuoteEvidence8239.fresh(it, nowMs) }
                ?: (nowMs - q.observedAtMs in 0L..QUOTE_FRESH_MS_8189))

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
    private val rejected = AtomicLong(0)
    private val identityChanged = AtomicLong(0)

    private val lane8189: ThreadPoolExecutor = ThreadPoolExecutor(
        2, 2, 30L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(32),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "held-exit-quote-8189").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private fun bare8189(mint: String): String = mint.removePrefix("solana|").trim()

    /** The last owner+mint wallet read of [mint] (StopLatencyCore8153 proof), if recent: (raw, decimals). */
    fun walletRead8189(mint: String, nowMs: Long): Pair<BigInteger, Int>? {
        val p = try { com.lifecyclebot.engine.sell.StopLatencyCore8153.proof8153(mint) ?: com.lifecyclebot.engine.sell.StopLatencyCore8153.proof8153(bare8189(mint)) } catch (_: Throwable) { null }
            ?: return null
        if (nowMs - p.atMs !in 0L..WALLET_READ_MAX_AGE_MS_8189) return null
        return p.raw to p.decimals
    }

    private fun holding8239(mint: String, nowMs: Long): HeldQuoteEvidence8239.Holding? {
        val p = try { CanonicalPositionAuthority6441.openPositions().firstOrNull {
            it.mode.equals("live", true) && bare8189(it.mint) == bare8189(mint)
        } } catch (_: Throwable) { null } ?: return null
        if (!p.mode.equals("live", true) || p.positionId.isBlank()) return null
        val wallet = walletRead8189(p.mint, nowMs)
        val qty = heldQty8189(p.remainingQtyRaw, wallet?.first, wallet?.second == p.quantityScale) ?: return null
        val migrated = try { com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(bare8189(p.mint)) } catch (_: Throwable) { false }
        val currentVenue = try { com.lifecyclebot.engine.BotService.status.tokens[p.mint]?.let {
            "${it.tokenMap.venue}:${it.tokenMap.poolAddress}:${it.lastPricePoolAddr}"
        }.orEmpty() } catch (_: Throwable) { "" }
        return HeldQuoteEvidence8239.Holding(bare8189(p.mint), p.positionId, "live", p.remainingQtyRaw,
            qty, p.quantityScale, "${p.entryPoolAddress}|$currentVenue|migrated=$migrated")
    }

    /** A partial fill, mode/position change or decimal correction invalidates the cached valuation immediately. */
    private fun freshFor8239(h: HeldQuoteEvidence8239.Holding, nowMs: Long): Quote8189? {
        val q = quotes[h.mint] ?: return null
        if (q.holding == null || !HeldQuoteEvidence8239.matches(h, q.holding)) {
            if (quotes.remove(h.mint, q)) identityChanged.incrementAndGet()
            return null
        }
        return q.takeIf { usable8189(it, nowMs) }
    }

    /** The cached quote for the CURRENT live holding, never an earlier position in the same mint. */
    fun fresh8189(mint: String, nowMs: Long = System.currentTimeMillis()): Quote8189? =
        holding8239(mint, nowMs)?.let { freshFor8239(it, nowMs) }

    /** Any cached quote of [mint] (for the proof table: its age is printed). */
    fun last8189(mint: String): Quote8189? = quotes[bare8189(mint)]

    fun invalidate8189(mint: String) {
        quotes.remove(bare8189(mint))
        attempts.remove(bare8189(mint))
        CanonicalPriceMarkRegistry6522.invalidateHeldQuote8239(mint)
        if (bare8189(mint) != mint) CanonicalPriceMarkRegistry6522.invalidateHeldQuote8239(bare8189(mint))
    }

    /**
     * HeldHotMarkAuthority7419.refreshPass: the fresh held sell quote of [mint] for [canonicalRaw] at [scale], or null.
     * Asks for a new quote in the background when one is due (the answer serves the next pass). Never blocks.
     */
    fun take8189(mint: String, canonicalRaw: BigInteger, scale: Int, nowMs: Long = System.currentTimeMillis(), positionId: String = ""): Quote8189? {
        if (mint.isBlank() || scale !in 0..18) return null
        val h = holding8239(mint, nowMs) ?: return null
        if (h.canonicalRaw != canonicalRaw || h.decimals != scale || (positionId.isNotBlank() && h.positionId != positionId)) return null
        val cached = freshFor8239(h, nowMs)
        if (due8189(attempts[h.mint] ?: 0L, nowMs, h.mint in inFlight)) ask8189(h, nowMs)
        if (cached != null) served.incrementAndGet()
        return cached
    }

    private fun ask8189(h: HeldQuoteEvidence8239.Holding, nowMs: Long) {
        if (h.mint in inFlight) return
        attempts[h.mint] = nowMs
        if (attempts.size > 2_000) attempts.clear()
        val enqueued = try { HeldQuoteEvidence8239.enqueue(lane8189, inFlight, h.mint) { quoteInto8189(h) } } catch (_: Throwable) { false }
        if (!enqueued && h.mint !in inFlight) {
            attempts.remove(h.mint, nowMs)
            rejected.incrementAndGet()
            try { PipelineHealthCollector.labelInc("HELD_EXIT_QUOTE_QUEUE_REJECTED_8239") } catch (_: Throwable) {}
        }
    }

    /** Blocking: quote this exact holding and retain the provider observation clock. Off the main thread only. */
    private fun quoteInto8189(h: HeldQuoteEvidence8239.Holding): Quote8189? {
        asked.incrementAndGet()
        val obs = try { HeldHotMarkAuthority7419.quoteSellObservation8239(h.mint, h.rawQty, h.decimals) } catch (_: Throwable) { null }
        val now = System.currentTimeMillis()
        if (obs == null || !HeldQuoteEvidence8239.fresh(obs, now)) {
            empty.incrementAndGet()
            try { PipelineHealthCollector.labelInc("HELD_EXIT_QUOTE_NO_ROUTE_8189") } catch (_: Throwable) {}
            return null
        }
        // A close/partial may complete while the provider is answering. Never cache that answer for the replacement holding.
        val current = holding8239(h.mint, now) ?: return null
        if (!HeldQuoteEvidence8239.matches(h, current)) { identityChanged.incrementAndGet(); return null }
        val q = Quote8189(obs.priceUsd, obs.observedAtMs, h.rawQty, h, obs)
        quotes[h.mint] = q
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
        val h = holding8239(mint, nowMs) ?: return null
        freshFor8239(h, nowMs)?.let { return it }
        if (!inFlight.add(h.mint)) return null
        attempts[h.mint] = nowMs
        return try { quoteInto8189(h) } finally { inFlight.remove(h.mint) }
    }

    fun statusLine8189(): String =
        "heldQuote8189[asked=${asked.get()} ok=${answered.get()} noRoute=${empty.get()} served=${served.get()} cached=${quotes.size} inFlight=${inFlight.size} rejected8239=${rejected.get()} identityChanged8239=${identityChanged.get()}]"

    internal fun putForTest8189(mint: String, q: Quote8189) { quotes[mint] = q }
    internal fun resetForTest8189() { quotes.clear(); attempts.clear(); inFlight.clear() }
}

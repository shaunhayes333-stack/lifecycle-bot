package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.sell.StopLatencyCore8153.HeldProof8153
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.8288 — STOP LATENCY: an emergency / hard stop starts its sell within about a second of the trigger.
 *
 * 5.0.8286 live: EMRG queue avg 9,449 ms / max 18,893 ms; PHP (Cbkg5w) HARD_STOP trigger -> broadcast 19,714 ms with
 * failStage TOKEN_BALANCE(3):FAILED_RETRYABLE; CATASTROPHIC_EXIT queue 23,476 ms (later 161 s avg, 540 s max). The
 * queue stage of the sell spine (SellRouteSpine8144: CLOSE_LEASE) is charged with every wait until the NEXT lease, and
 * the waits were:
 *
 *  1. Attempts that ended before any balance / quote stage ran — the sell lock held by a running partial / profit-lock
 *     slice (blockIfSellInFlight, SellExecutionLocks), a mark veto — counted as a failed ROUTE attempt: CloseLease's
 *     emergency cadence (2 / 3 / 5 / 8 s, ProtectiveExitClass7807) ran between them (2+3+5+8 = 18 s = the 18,893 ms
 *     max) and each one escalated the slippage ladder without ever quoting. Now such a PRE-ROUTE wait
 *     ([preRoute8288]) re-dispatches on a short clock ([preRouteDelayMs8288]) and is not a route attempt. A mark veto
 *     is not a pre-route wait (re-asking every 250 ms changes nothing).
 *  2. The balance stage: the 8153 parallel read was bounded at 2.5 s and, unanswered, fell back to the serial single-
 *     mint (2.5 s) + whole-wallet (5 s) ladder — then the planner's own reads. Now the parallel read is bounded at
 *     [STOP_READ_BUDGET_MS_8288]; unanswered, the sale is sized from the CANONICAL held quantity (chain-confirmed fills),
 *     min'd with the last confirmed owner+mint wallet read ([fallbackProof8288]). The reads keep running and a late
 *     positive answer is recorded for the next sizing. Two providers proving the token account absent is ABSENT: no
 *     sale, the balance-proof poller closes the row on its own zero proof.
 *
 * Never more than held: the fallback is the smaller of the canonical remainder and the last wallet read, refused while
 * a signed sell is unresolved or a landed sell is not yet absorbed by the canonical row, and never with a scale
 * disagreement. Never double: CloseLease / SignedTxLedger8085 single-flight is untouched.
 */
object StopFast8288 {

    /** The emergency balance read waits at most this long before sizing from the canonical held quantity. */
    const val STOP_READ_BUDGET_MS_8288 = 900L
    /** Target: trigger -> first broadcast for a stop. */
    const val STOP_T2B_TARGET_MS_8288 = 5_000L
    /** A canonical-sized answer is reused for this long by the same exit (the plan re-asks after liveSell). */
    const val FALLBACK_TTL_MS_8288 = 15_000L
    /** Pre-route waits re-dispatch on this clock for the first [FAST_PRE_ROUTE_WAITS_8288] waits, then every second. */
    const val PRE_ROUTE_FAST_MS_8288 = 250L
    const val PRE_ROUTE_SLOW_MS_8288 = 1_000L
    const val FAST_PRE_ROUTE_WAITS_8288 = 20
    /** The deep-stop quote corroboration (ExitQuoteCorroboration7928) may hold an emergency this long, no longer. */
    const val STOP_QUOTE_CHECK_BUDGET_MS_8288 = 1_200L

    private val DUST_RAW_8288: BigInteger = BigInteger.ONE

    enum class Route8288 { PROVEN_HOLDING, PARALLEL_READ, CANONICAL_FALLBACK, ABSENT, NONE }

    data class Answer8288(val proof: HeldProof8153?, val route: Route8288, val elapsedMs: Long, val readsIssued: Int)

    // ── pure ────────────────────────────────────────────────────────────────

    /**
     * Pure: the canonical-sized holding for a stop whose wallet read did not answer in time. [canonicalRaw] /
     * [canonicalScale] / [canonicalMutatedAtMs] are the open canonical row; [observed] is the newest owner+mint wallet
     * read (any age); [lastSellTouchMs] the last time a SELL for the mint was signed or changed state;
     * [lastConfirmedSellAtMs] the last time one CONFIRMED. Null: no safe size.
     */
    fun fallbackProof8288(
        canonicalRaw: BigInteger?, canonicalScale: Int?, canonicalMutatedAtMs: Long,
        observed: HeldProof8153?, lastSellTouchMs: Long, lastConfirmedSellAtMs: Long,
        signedSellUnresolved: Boolean, frozen: Boolean, nowMs: Long,
    ): HeldProof8153? {
        if (signedSellUnresolved || frozen) return null
        if (canonicalRaw == null || canonicalScale == null || canonicalScale !in 0..18 || canonicalRaw <= DUST_RAW_8288) return null
        // A landed sell the canonical row has not absorbed yet: its remainder is not what the wallet holds.
        if (lastConfirmedSellAtMs > canonicalMutatedAtMs) return null
        val obs = observed?.takeIf { StopLatencyCore8153.positive8153(it) && it.atMs > lastSellTouchMs }
        if (obs != null && obs.decimals != canonicalScale) return null
        val raw = if (obs != null) canonicalRaw.min(obs.raw) else canonicalRaw
        if (raw <= DUST_RAW_8288) return null
        return HeldProof8153(raw, canonicalScale, if (obs != null) "CANONICAL_MIN_OBSERVED_8288" else "CANONICAL_HELD_8288", nowMs)
    }

    /** Pure: an attempt that ended before any balance / quote stage, signed nothing and was not a veto. */
    fun preRoute8288(emergency: Boolean, result: String, lastStageIsLease: Boolean, failedSinceLease: Boolean,
                     sellSignedSinceLease: Boolean, vetoed: Boolean): Boolean =
        emergency && result == "FAILED_RETRYABLE" && lastStageIsLease && !failedSinceLease && !sellSignedSinceLease && !vetoed

    /** Pure: the re-dispatch delay of pre-route wait number [waits] (1-based). */
    fun preRouteDelayMs8288(waits: Int): Long = if (waits <= FAST_PRE_ROUTE_WAITS_8288) PRE_ROUTE_FAST_MS_8288 else PRE_ROUTE_SLOW_MS_8288

    /** Pure: a pre-route wait scheduled at [eligibleAtMs] (0 = none) lets the emergency re-enter at [nowMs]. */
    fun preRouteEligible8288(eligibleAtMs: Long, nowMs: Long): Boolean = eligibleAtMs in 1L..nowMs

    // ── bounded race ────────────────────────────────────────────────────────

    private class Box8288(val v: HeldProof8153?)

    /**
     * The balance stage of a stop. A usable proven holding answers at once. Otherwise every reader runs at once on
     * [lane]; the smallest positive answer within [budgetMs] (plus [graceMs] after the first) wins. None: [absentNow]
     * (two providers proved the account absent) is ABSENT; else [fallback] (the canonical-sized holding). Readers still
     * running at the deadline are NOT cancelled: a later positive answer goes to [onLate].
     */
    fun holdingForStop8288(
        proven: () -> HeldProof8153?,
        readers: List<() -> HeldProof8153?>,
        absentNow: () -> Boolean,
        fallback: () -> HeldProof8153?,
        onLate: (HeldProof8153) -> Unit,
        budgetMs: Long = STOP_READ_BUDGET_MS_8288,
        graceMs: Long = StopLatencyCore8153.PARALLEL_GRACE_MS_8153,
        lane: ExecutorService = StopLatencyCore8153.emergencyLane8153,
    ): Answer8288 {
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000L
        val known = try { proven() } catch (_: Throwable) { null }
        if (StopLatencyCore8153.positive8153(known)) return Answer8288(known, Route8288.PROVEN_HOLDING, ms(), 0)
        val done = AtomicBoolean(false)
        val q = LinkedBlockingQueue<Box8288>()
        var submitted = 0
        for (r in readers) {
            try {
                lane.execute(Runnable {
                    val v = try { r() } catch (_: Throwable) { null }
                    // Offered under the lock before done flips, or delivered late after it: never lost.
                    val queued = synchronized(done) { if (!done.get()) { q.offer(Box8288(v)); true } else false }
                    if (!queued && v != null && StopLatencyCore8153.positive8153(v)) {
                        try { onLate(v) } catch (_: Throwable) {}
                    }
                })
                submitted++
            } catch (_: Throwable) {}
        }
        val budgetDeadline = t0 + budgetMs.coerceAtLeast(1L) * 1_000_000L
        var deadline = budgetDeadline
        val answers = ArrayList<HeldProof8153?>()
        var pending = submitted
        while (pending > 0) {
            val left = deadline - System.nanoTime()
            if (left <= 0L) break
            val b = try { q.poll(left, TimeUnit.NANOSECONDS) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); null
            } ?: break
            pending--
            answers.add(b.v)
            if (StopLatencyCore8153.positive8153(b.v)) {
                deadline = minOf(deadline, System.nanoTime() + graceMs.coerceAtLeast(0L) * 1_000_000L)
            }
        }
        synchronized(done) { done.set(true) }
        // Answers that arrived between the deadline and done=true are late answers, not lost ones.
        while (true) {
            val b = q.poll() ?: break
            val v = b.v
            if (v != null && StopLatencyCore8153.positive8153(v)) try { onLate(v) } catch (_: Throwable) {}
        }
        StopLatencyCore8153.minHeld8153(answers)?.let { return Answer8288(it, Route8288.PARALLEL_READ, ms(), submitted) }
        if (try { absentNow() } catch (_: Throwable) { false }) return Answer8288(null, Route8288.ABSENT, ms(), submitted)
        val fb = try { fallback() } catch (_: Throwable) { null }
        if (StopLatencyCore8153.positive8153(fb)) return Answer8288(fb, Route8288.CANONICAL_FALLBACK, ms(), submitted)
        return Answer8288(null, Route8288.NONE, ms(), submitted)
    }

    // ── canonical-sized answers (never a wallet proof: PositionProof8189 / reconcile never read these) ──

    private val fallbacks8288 = ConcurrentHashMap<String, HeldProof8153>()

    fun rememberFallback8288(mint: String, p: HeldProof8153) {
        if (mint.isBlank() || !StopLatencyCore8153.positive8153(p)) return
        fallbacks8288[mint] = p
        if (fallbacks8288.size > 500) fallbacks8288.entries.removeIf { p.atMs - it.value.atMs > FALLBACK_TTL_MS_8288 }
    }

    /** The canonical-sized answer of this exit, while young and not spent by an order signed after it. */
    fun cachedFallback8288(mint: String, nowMs: Long, lastTouchMs: Long): HeldProof8153? {
        val p = fallbacks8288[mint] ?: return null
        if (nowMs - p.atMs !in 0L..FALLBACK_TTL_MS_8288 || !StopLatencyCore8153.usable8153(p, nowMs, lastTouchMs, false)) {
            fallbacks8288.remove(mint, p)
            return null
        }
        return p
    }

    // ── pre-route waits ─────────────────────────────────────────────────────

    private val vetoed8288 = ConcurrentHashMap<String, Long>()

    /** Executor.freshExitReason7835: this attempt was vetoed (cortex hold / mark veto / quote contradiction). */
    fun noteVeto8288(mint: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        vetoed8288[mint] = nowMs
        if (vetoed8288.size > 500) vetoed8288.entries.removeIf { nowMs - it.value > 60_000L }
    }

    /**
     * Executor.requestSellCore7948, after doSell returned [result] for an exit under [reason]: true when the attempt was
     * a pre-route wait (CloseLease.recordPreRouteWait8288 then re-dispatches it on the short clock, no route attempt).
     */
    fun preRouteWait8288(mint: String, reason: String, result: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val vetoAt = vetoed8288.remove(mint)
        val t = try { SellRouteSpine8144.timer8144(mint) } catch (_: Throwable) { null } ?: return false
        val leaseAt = t.leaseMarkAtMs8288
        if (leaseAt <= 0L) return false
        val signed = try {
            com.lifecyclebot.network.SignedTxLedger8085.historyFor8090(mint).any { it.side == "SELL" && it.createdAtMs >= leaseAt }
        } catch (_: Throwable) { true }
        val pre = preRoute8288(
            emergency = try { ProtectiveExitClass7807.isEmergency(reason) } catch (_: Throwable) { false },
            result = result,
            lastStageIsLease = t.lastStage == SellRouteSpine8144.Stage8144.CLOSE_LEASE,
            failedSinceLease = t.failedSinceLease8288,
            sellSignedSinceLease = signed,
            vetoed = vetoAt != null && vetoAt >= leaseAt,
        )
        if (pre) try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("STOP_PRE_ROUTE_WAIT_FAST_REDISPATCH_8288") } catch (_: Throwable) {}
        return pre
    }

    // ── bounded quote corroboration ────────────────────────────────────────

    private val quotePool8288: ExecutorService by lazy {
        java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "AATE-StopQuote-8288").apply { isDaemon = true } }
    }

    /** [fn] bounded at [budgetMs] (null on timeout / failure; the slow call finishes off-thread). */
    fun <T> bounded8288(budgetMs: Long, fn: () -> T?): T? {
        val f = try { quotePool8288.submit(java.util.concurrent.Callable { fn() }) } catch (_: Throwable) { return null }
        return try { f.get(budgetMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS) } catch (_: java.util.concurrent.TimeoutException) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("STOP_QUOTE_CHECK_TIMED_OUT_ALLOWED_8288") } catch (_: Throwable) {}
            null
        } catch (_: Throwable) { null }
    }

    internal fun resetForTest8288() { fallbacks8288.clear(); vetoed8288.clear() }
}

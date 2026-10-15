package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8136 — ask the chain for a mint's authority proof when the candidate becomes a credible admission prospect,
 * not first at the pre-broadcast gate. By the time the live gate runs, the proof is cached (or in flight, and the gate
 * joins it instead of asking again).
 *
 * [prefetch8136] is cheap and idempotent: a cached fresh proof, an ask already in flight, or a mint inside its retry
 * back-off returns at once. Unanswered mints retry on [AuthorityProofState8136.retryDue8136]'s bounded ladder. Work
 * runs on two daemon threads with a bounded queue; overflow is dropped (the live gate still asks for itself).
 */
object AuthorityProofPrefetch8136 {

    private data class Attempts(val count: Int, val lastAtMs: Long, val windowStartMs: Long)

    private const val ATTEMPTS_CAP_8136 = 4_000
    private val attempts = ConcurrentHashMap<String, Attempts>()
    private val submitted = AtomicLong(0L)
    private val skippedFresh = AtomicLong(0L)
    private val skippedInFlight = AtomicLong(0L)
    private val skippedBackoff = AtomicLong(0L)
    private val answered = AtomicLong(0L)
    private val unanswered = AtomicLong(0L)

    private val pool: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            2, 2, 30L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(64),
            java.util.concurrent.ThreadFactory { r -> Thread(r, "authority-prefetch-8136").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } },
            ThreadPoolExecutor.DiscardPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }
    }

    /** LIVE only: paper never spends RPC credits on a proof it does not need. */
    fun prefetchIfLive8136(mint: String): Boolean {
        val paper = try { com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { true }
        return if (paper) false else prefetch8136(mint)
    }

    /** Returns true when a background ask was queued. */
    fun prefetch8136(mint: String): Boolean {
        if (mint.isBlank()) return false
        val now = System.currentTimeMillis()
        AdmissionProspect8136.note8136(mint, now)
        if (!AuthorityProofState8136.mustAsk8136(AuthorityProofState8136.stateFor8136(mint, now))) {
            skippedFresh.incrementAndGet(); return false
        }
        if (OnChainMintAuthorityProof7248.inFlight8136(mint)) { skippedInFlight.incrementAndGet(); return false }
        val a = attempts[mint]
        if (a != null && !AuthorityProofState8136.retryDue8136(a.count, a.lastAtMs, a.windowStartMs, now)) {
            skippedBackoff.incrementAndGet(); return false
        }
        val windowOpen = a != null && now - a.windowStartMs < AuthorityProofState8136.RETRY_WINDOW_MS_8136
        attempts[mint] = if (a != null && windowOpen) a.copy(count = a.count + 1, lastAtMs = now) else Attempts(1, now, now)
        if (attempts.size > ATTEMPTS_CAP_8136) {
            attempts.entries.removeIf { now - it.value.windowStartMs >= AuthorityProofState8136.RETRY_WINDOW_MS_8136 }
        }
        return try {
            pool.execute { runOne8136(mint) }
            submitted.incrementAndGet()
            true
        } catch (_: Throwable) { false }
    }

    /**
     * The live gate asked and nobody answered: count that ask as this window's attempt so the next [prefetch8136]
     * (the candidate's next evaluation) retries on the ladder instead of immediately repeating the same ask.
     */
    fun noteGateUnanswered8136(mint: String) {
        if (mint.isBlank()) return
        val now = System.currentTimeMillis()
        val a = attempts[mint]
        val windowOpen = a != null && now - a.windowStartMs < AuthorityProofState8136.RETRY_WINDOW_MS_8136
        attempts[mint] = if (a != null && windowOpen) a.copy(count = a.count + 1, lastAtMs = now) else Attempts(1, now, now)
        try { PipelineHealthCollector.labelInc("MINT_AUTHORITY_GATE_UNANSWERED_RETRY_SCHEDULED_8136") } catch (_: Throwable) {}
    }

    private fun runOne8136(mint: String) {
        val proof = try { OnChainMintAuthorityProof7248.resolve(mint, liveGate8094 = true) } catch (_: Throwable) { null }
        if (proof != null) {
            answered.incrementAndGet()
            attempts.remove(mint)
            try { PipelineHealthCollector.labelInc("MINT_AUTHORITY_PREFETCH_ANSWERED_8136") } catch (_: Throwable) {}
        } else {
            unanswered.incrementAndGet()
            try { PipelineHealthCollector.labelInc("MINT_AUTHORITY_PREFETCH_UNANSWERED_8136") } catch (_: Throwable) {}
        }
    }

    fun statusLine8136(): String =
        "authorityPrefetch8136 submitted=${submitted.get()} answered=${answered.get()} unanswered=${unanswered.get()} " +
            "skippedFresh=${skippedFresh.get()} skippedInFlight=${skippedInFlight.get()} skippedBackoff=${skippedBackoff.get()} " +
            "records=${AuthorityProofState8136.size8136()} contradicted=${AuthorityProofState8136.contradictions8136()}"

    /** Memory pressure: retry bookkeeping is rebuilt on demand. */
    fun trim8136() { attempts.clear() }
}

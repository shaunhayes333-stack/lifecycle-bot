package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8136 — ask the chain for a mint's authority proof when the candidate becomes a credible admission prospect,
 * not first at the pre-broadcast gate. By the time the live gate runs, the proof is cached (or in flight, and the gate
 * joins it instead of asking again).
 *
 * [prefetch8136] is cheap and idempotent: a cached fresh proof, an ask already in flight, or a mint inside its retry
 * back-off returns at once. Unanswered mints retry on [AuthorityProofState8136.retryDue8136]'s bounded ladder.
 *
 * V5.0.8144 — the 8136 pool (64-slot queue, DiscardPolicy) dropped jobs silently on saturation while `submitted`
 * still counted them, so the ladder believed an ask was on the wire and the gate later refused cold
 * (PRETRADE_HARD_BLOCK_FREEZE_AUTHORITY_UNVERIFIED_7238, 3 live buys). Work now goes through
 * [ProofDataAuthorityQueue8144]: an explicit per-mint single-flight state machine with a LIVE lane (gate / FDG
 * callers) served before a BACKGROUND lane (speculative enrichment). A full lane is QUEUE_REJECTED — never counted as
 * submitted — and the mint keeps a bounded retry; an unanswered ask gets RETRY_SCHEDULED on the ladder or the named
 * UNANSWERED terminal. Two daemon workers drain the lanes and fire due retries themselves, so a deferred candidate is
 * re-asked even if nobody evaluates it again. Nothing here approves a buy: only a fresh PROVEN_CLEAN record does.
 */
object AuthorityProofPrefetch8136 {

    private val book8144 = ProofDataAuthorityQueue8144()
    private val submitted = AtomicLong(0L)
    private val skippedFresh = AtomicLong(0L)
    private val skippedInFlight = AtomicLong(0L)
    private val skippedBackoff = AtomicLong(0L)
    private val answered = AtomicLong(0L)
    private val unanswered = AtomicLong(0L)
    private val queueRejected8144 = AtomicLong(0L)
    private val promoted8144 = AtomicLong(0L)
    private val exhausted8144 = AtomicLong(0L)
    private val retriesFired8144 = AtomicLong(0L)
    private val unansweredTerminal8144 = AtomicLong(0L)

    private const val WORKERS_8144 = 2
    private const val IDLE_PUMP_MS_8144 = 1_000L
    private const val RETRIES_PER_PUMP_8144 = 16
    private val signal8144 = Semaphore(0)
    @Volatile private var started8144 = false

    private fun label8144(name: String) {
        try { PipelineHealthCollector.labelInc(name) } catch (_: Throwable) {}
    }

    private fun paper8144(): Boolean = try { com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { true }

    /** LIVE only: paper never spends RPC credits on a proof it does not need. Gate / FDG callers: the LIVE lane. */
    fun prefetchIfLive8136(mint: String): Boolean {
        val paper = try { com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { true }
        return if (paper) false else prefetch8136(mint)
    }

    /**
     * V5.0.8144 — a scan-time read (speculative enrichment, not yet a qualified candidate) went unanswered: a BACKGROUND
     * retry is scheduled on the ladder (LIVE mode only), behind every qualified live candidate. It is a retry, not an
     * immediate re-ask: the scan just asked.
     */
    fun noteScanUnanswered8144(mint: String) {
        if (mint.isBlank() || paper8144()) return
        val f = book8144.noteUnanswered8144(mint, ProofDataAuthorityQueue8144.Priority8144.BACKGROUND, System.currentTimeMillis())
        if (f == ProofDataAuthorityQueue8144.Flight8144.RETRY_SCHEDULED) { label8144("MINT_AUTHORITY_SCAN_UNANSWERED_RETRY_SCHEDULED_8144"); ensureWorkers8144() }
    }

    /** Returns true only when a new ask was accepted into a lane (a rejected or joined ask is not "submitted"). */
    fun prefetch8136(mint: String): Boolean =
        submit8144(mint, ProofDataAuthorityQueue8144.Priority8144.LIVE, System.currentTimeMillis(), retry = false)

    private fun submit8144(mint: String, priority: ProofDataAuthorityQueue8144.Priority8144, now: Long, retry: Boolean): Boolean {
        if (mint.isBlank()) return false
        if (!retry && priority == ProofDataAuthorityQueue8144.Priority8144.LIVE) AdmissionProspect8136.note8136(mint, now)
        val proven = AuthorityProofState8136.stateFor8136(mint, now)
        if (!AuthorityProofState8136.mustAsk8136(proven)) {
            skippedFresh.incrementAndGet()
            book8144.settle8144(mint, proven, now)
            return false
        }
        if (OnChainMintAuthorityProof7248.inFlight8136(mint)) { skippedInFlight.incrementAndGet(); return false }
        return when (book8144.request8144(mint, priority, now)) {
            ProofDataAuthorityQueue8144.Submit8144.ACCEPTED -> {
                submitted.incrementAndGet()
                ensureWorkers8144()
                signal8144.release()
                true
            }
            ProofDataAuthorityQueue8144.Submit8144.PROMOTED -> {
                promoted8144.incrementAndGet()
                label8144("MINT_AUTHORITY_PREFETCH_PROMOTED_LIVE_8144")
                false
            }
            ProofDataAuthorityQueue8144.Submit8144.ALREADY_QUEUED,
            ProofDataAuthorityQueue8144.Submit8144.ALREADY_RUNNING -> { skippedInFlight.incrementAndGet(); false }
            ProofDataAuthorityQueue8144.Submit8144.BACKOFF -> { skippedBackoff.incrementAndGet(); false }
            ProofDataAuthorityQueue8144.Submit8144.EXHAUSTED -> {
                exhausted8144.incrementAndGet()
                label8144("MINT_AUTHORITY_PREFETCH_WINDOW_EXHAUSTED_8144")
                false
            }
            ProofDataAuthorityQueue8144.Submit8144.QUEUE_REJECTED -> {
                queueRejected8144.incrementAndGet()
                label8144("MINT_AUTHORITY_PREFETCH_QUEUE_REJECTED_8144_${priority.name}")
                // The retry pump runs on the workers: make sure they exist even if nothing was ever accepted.
                ensureWorkers8144()
                false
            }
        }
    }

    /**
     * The live gate asked and nobody answered: count that ask as this window's attempt and schedule a LIVE retry on the
     * ladder (or the named UNANSWERED terminal), so the candidate is re-asked asynchronously instead of being forgotten.
     */
    fun noteGateUnanswered8136(mint: String) {
        if (mint.isBlank()) return
        val f = book8144.noteGateUnanswered8144(mint, System.currentTimeMillis())
        if (f == ProofDataAuthorityQueue8144.Flight8144.UNANSWERED) {
            unansweredTerminal8144.incrementAndGet()
            label8144("MINT_AUTHORITY_GATE_UNANSWERED_TERMINAL_8144")
        } else {
            label8144("MINT_AUTHORITY_GATE_UNANSWERED_RETRY_SCHEDULED_8136")
        }
        if (!paper8144()) ensureWorkers8144()
    }

    /** V5.0.8144 — the mint's queue state, for the gate's refusal label (diagnostic only — never an approval). */
    fun flightState8144(mint: String): ProofDataAuthorityQueue8144.Flight8144 = book8144.state8144(mint)

    private fun ensureWorkers8144() {
        if (started8144) return
        synchronized(this) {
            if (started8144) return
            started8144 = true
            repeat(WORKERS_8144) { i ->
                try {
                    Thread({ workLoop8144() }, "authority-prefetch-8144-$i").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }.start()
                } catch (_: Throwable) {}
            }
        }
    }

    private fun workLoop8144() {
        while (true) {
            try {
                signal8144.tryAcquire(IDLE_PUMP_MS_8144, TimeUnit.MILLISECONDS)
                if (!paper8144()) pumpRetries8144(System.currentTimeMillis())
                while (true) {
                    val t = book8144.take8144(System.currentTimeMillis()) ?: break
                    runOne8136(t)
                }
            } catch (_: InterruptedException) {
                return
            } catch (_: Throwable) {}
        }
    }

    /** Due bounded retries (unanswered on the ladder, or a lane that was full) are offered again at their own priority. */
    private fun pumpRetries8144(now: Long) {
        for (t in book8144.due8144(now, RETRIES_PER_PUMP_8144)) {
            retriesFired8144.incrementAndGet()
            submit8144(t.mint, t.priority, now, retry = true)
        }
    }

    private fun runOne8136(t: ProofDataAuthorityQueue8144.Taken8144) {
        val mint = t.mint
        // Helius first with every configured RPC behind it for a qualified live candidate; background enrichment keeps
        // the scan order (public first, Helius last) so speculation does not spend the live gate's credits.
        val live = t.priority == ProofDataAuthorityQueue8144.Priority8144.LIVE
        val proof = try { OnChainMintAuthorityProof7248.resolve(mint, liveGate8094 = live) } catch (_: Throwable) { null }
        val now = System.currentTimeMillis()
        if (proof != null) {
            val clean = AuthorityProofState8136.classify8136(proof.freezeAuthorityDisabled, proof.hostileExtension8123) ==
                AuthorityProofState8136.State.PROVEN_CLEAN
            book8144.complete8144(mint, if (clean) ProofDataAuthorityQueue8144.Answer8144.CLEAN else ProofDataAuthorityQueue8144.Answer8144.HOSTILE, now)
            answered.incrementAndGet()
            label8144("MINT_AUTHORITY_PREFETCH_ANSWERED_8136")
        } else {
            val f = book8144.complete8144(mint, ProofDataAuthorityQueue8144.Answer8144.UNANSWERED, now)
            unanswered.incrementAndGet()
            label8144("MINT_AUTHORITY_PREFETCH_UNANSWERED_8136")
            if (f == ProofDataAuthorityQueue8144.Flight8144.UNANSWERED) {
                unansweredTerminal8144.incrementAndGet()
                label8144("MINT_AUTHORITY_PREFETCH_UNANSWERED_TERMINAL_8144")
            } else {
                label8144("MINT_AUTHORITY_PREFETCH_RETRY_SCHEDULED_8144")
            }
        }
    }

    fun statusLine8136(): String =
        "authorityPrefetch8136 submitted=${submitted.get()} answered=${answered.get()} unanswered=${unanswered.get()} " +
            "skippedFresh=${skippedFresh.get()} skippedInFlight=${skippedInFlight.get()} skippedBackoff=${skippedBackoff.get()} " +
            "queueRejected8144=${queueRejected8144.get()} promoted8144=${promoted8144.get()} exhausted8144=${exhausted8144.get()} " +
            "retriesFired8144=${retriesFired8144.get()} unansweredTerminal8144=${unansweredTerminal8144.get()} " +
            "lanes8144[live=${book8144.liveDepth8144()} background=${book8144.backgroundDepth8144()}] " +
            "flights8144=${book8144.census8144().entries.joinToString(",") { "${it.key.name}:${it.value}" }.ifBlank { "-" }} " +
            "records=${AuthorityProofState8136.size8136()} contradicted=${AuthorityProofState8136.contradictions8136()}"

    /** Memory pressure: settled queue slots (proven / named terminal) are rebuilt on demand. */
    fun trim8136() { book8144.trim8144(1_000, System.currentTimeMillis()) }
}

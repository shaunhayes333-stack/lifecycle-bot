package com.lifecyclebot.engine.sell

import java.math.BigInteger
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * V5.0.8153 — STOP LATENCY (P5: catastrophic stop-to-broadcast delays).
 *
 * 5.0.8151 device: a HARD_STOP took 30,185 ms to broadcast; a QUALITY exit realised about -35.3% after 20,144 ms in
 * the balance stage and 9,754 ms in quote / venue. The trigger -> broadcast path re-proved the SAME holding up to
 * four times, serially, each behind its own timeout ladder:
 *
 *   liveSell            single-mint read (2.5 s) -> whole-wallet scan (5 s) -> known-mints fallback (6 s)
 *                       -> 8144 multi-provider corroboration (2.5 s) when the mint was not listed
 *   every processor     ProcessorAmountPlanner: whole-wallet scan (5 s + 6 s fallback), then a re-read (3 s + 6 s)
 *   plan (race, Jupiter, PumpPortal, Raydium) — 20 s on a provider that hangs the whole-wallet read
 *   tryPumpPortalSell   another whole-wallet scan (5 s + 6 s) for the 95% check, plus a 1.5 s venue read per attempt
 *
 * Corroboration (several providers agreeing) exists to prove ABSENCE before a zero close; it was never needed to sell
 * a holding the wallet already shows. This object holds the pure decisions and the bounded orchestration that replace
 * those serial reads on an EMERGENCY / protective exit (ProtectiveExitClass7807.isEmergency):
 *
 *  - [HeldProof8153]: a positive owner+mint quantity with its read time. [usable8153] admits it only while young and
 *    only when no order for the mint was signed after the read (and no signed sell is unresolved), so a retry after
 *    any broadcast always re-reads: the sale is re-sized from the wallet, never from a pre-sell quantity.
 *  - [holdingForExit8153]: proven holding first (no read); otherwise every provider read runs IN PARALLEL on the
 *    dedicated emergency lane, bounded at [EMERGENCY_READ_BUDGET_MS_8153]; the SMALLEST positive answer wins (a lagging
 *    provider can never size above the wallet). No answer returns null and the caller keeps its legacy read and its
 *    indeterminate semantics (a failed read is never absence).
 *  - [fullExitRaw8153]: min(canonical remaining, wallet) — the planner's own full-exit rule, never more than either.
 *  - lanes: emergency reads run on [emergencyLane8153] (max-priority daemon threads, never shared); background
 *    prewarm runs on [backgroundLane8153] (two min-priority threads, bounded queue, overflow dropped) and throttles to
 *    one read per tick while an emergency is working toward its broadcast ([prewarmBudget8153]).
 *
 * Nothing here widens a stop, raises slippage or invents a balance: every quantity is an owner+mint wallet read.
 * Pure parts take time as a parameter; nothing touches Android, the network or a logger.
 */
object StopLatencyCore8153 {

    /** A proven holding may size an emergency exit for this long after its read (7730 accepts a 10 min snapshot). */
    const val HOT_PROOF_TTL_MS_8153 = 45_000L
    /** Background prewarm refreshes a proof once it is this old (well inside [HOT_PROOF_TTL_MS_8153]). */
    const val REFRESH_AHEAD_MS_8153 = 20_000L
    /** Background prewarm refreshes an active-curve venue probe this long before it goes stale (TTL 30 s). */
    const val VENUE_REFRESH_AHEAD_MS_8153 = 12_000L
    /** Every parallel provider read of an emergency is bounded by this, in total. */
    const val EMERGENCY_READ_BUDGET_MS_8153 = 2_500L
    /** After the first positive answer, the others get at most this long to answer (min-held rule, bounded). */
    const val PARALLEL_GRACE_MS_8153 = 300L
    const val PREWARM_TICK_MS_8153 = 2_500L
    const val PREWARM_READS_PER_TICK_8153 = 5

    /** Same dust floor as SellAmountAuthority.SELL_DUST_RAW (1 raw unit): at or below it nothing is sellable. */
    private val DUST_RAW_8153: BigInteger = BigInteger.ONE

    data class HeldProof8153(val raw: BigInteger, val decimals: Int, val source: String, val atMs: Long)

    enum class BalanceRoute8153 { PROVEN_HOLDING, PARALLEL_READ, LEGACY_READ }

    // ── pure decisions ──────────────────────────────────────────────────────

    /** Pure: a positive, well-formed quantity. */
    fun positive8153(p: HeldProof8153?): Boolean = p != null && p.raw > DUST_RAW_8153 && p.decimals in 0..18

    /**
     * Pure: may [p] size an exit at [nowMs]. Spent once anything was signed for the mint after the read
     * ([lastSignedAtMs]) or while a signed sell is unresolved: the quantity may have moved, so the attempt re-reads.
     */
    fun usable8153(p: HeldProof8153?, nowMs: Long, lastSignedAtMs: Long, signedSellUnresolved: Boolean): Boolean =
        p != null && positive8153(p) && !signedSellUnresolved && nowMs >= p.atMs &&
            nowMs - p.atMs <= HOT_PROOF_TTL_MS_8153 && p.atMs > lastSignedAtMs

    /** Pure: how the balance stage of an exit is answered. Only emergencies take the hot path. */
    fun balanceRoute8153(emergency: Boolean, proofUsable: Boolean): BalanceRoute8153 = when {
        !emergency -> BalanceRoute8153.LEGACY_READ
        proofUsable -> BalanceRoute8153.PROVEN_HOLDING
        else -> BalanceRoute8153.PARALLEL_READ
    }

    /** Pure: multi-provider corroboration proves ABSENCE; a known positive holding never waits for it. */
    fun corroborationNeeded8153(knownHolding: HeldProof8153?): Boolean = !positive8153(knownHolding)

    /** Pure: the smallest positive answer (a lagging provider never sizes above the wallet); null on none or a decimals split. */
    fun minHeld8153(reads: List<HeldProof8153?>): HeldProof8153? {
        val held = reads.filterNotNull().filter { positive8153(it) }
        if (held.isEmpty() || held.map { it.decimals }.distinct().size != 1) return null
        return held.minByOrNull { it.raw }
    }

    /** Pure: the newest usable proof among [candidates] (the most recent owner+mint read is the authority). */
    fun newestUsable8153(candidates: List<HeldProof8153?>, nowMs: Long, lastSignedAtMs: Long, signedSellUnresolved: Boolean): HeldProof8153? =
        candidates.filterNotNull().filter { usable8153(it, nowMs, lastSignedAtMs, signedSellUnresolved) }.maxByOrNull { it.atMs }

    /**
     * Pure: a full exit's raw size — min(canonical remaining, wallet), the planner's own rule. Null when the canonical
     * row is missing, its scale disagrees with the wallet decimals, or either side is not positive.
     */
    fun fullExitRaw8153(walletRaw: BigInteger, decimals: Int, canonicalRemainingRaw: BigInteger?, canonicalScale: Int?): BigInteger? {
        if (canonicalRemainingRaw == null || canonicalScale == null) return null
        if (walletRaw.signum() <= 0 || canonicalRemainingRaw.signum() <= 0 || decimals !in 0..18 || canonicalScale != decimals) return null
        return canonicalRemainingRaw.min(walletRaw)
    }

    /** Pure: the hot plan only serves an emergency FULL exit (a partial keeps the planner's fraction contract). */
    fun fastPlanEligible8153(emergency: Boolean, fraction: Double?, processor: String): Boolean =
        emergency && fraction == null && !processor.contains("PARTIAL", ignoreCase = true)

    /**
     * Pure: background prewarm reads per tick. It yields to any emergency still working toward its broadcast (one
     * read per tick, latched exits first, so the other open positions do not go cold behind a stuck exit).
     */
    fun prewarmBudget8153(emergenciesPending: Int): Int = if (emergenciesPending <= 0) PREWARM_READS_PER_TICK_8153 else 1

    data class PrewarmTarget8153(val mint: String, val latched: Boolean)

    /**
     * Pure: the mints refreshed this tick — due ones only (never refreshed, or last refreshed at least [refreshAheadMs]
     * ago), latched exits first, then the stalest; at most [budget].
     */
    fun prewarmPick8153(targets: List<PrewarmTarget8153>, lastRefreshAtMs: Map<String, Long>, nowMs: Long, budget: Int,
                        refreshAheadMs: Long = REFRESH_AHEAD_MS_8153): List<String> =
        targets.distinctBy { it.mint }
            .filter { t -> lastRefreshAtMs[t.mint]?.let { nowMs - it >= refreshAheadMs } ?: true }
            .sortedWith(compareBy<PrewarmTarget8153>({ if (it.latched) 0 else 1 }, { lastRefreshAtMs[it.mint] ?: Long.MIN_VALUE }))
            .take(budget.coerceAtLeast(0))
            .map { it.mint }

    // ── proof cache ─────────────────────────────────────────────────────────

    private val proofs8153 = ConcurrentHashMap<String, HeldProof8153>()
    private val refreshedAt8153 = ConcurrentHashMap<String, Long>()

    /** Keep the newest positive proof for [mint]. */
    fun record8153(mint: String, p: HeldProof8153) {
        if (mint.isBlank() || !positive8153(p)) return
        proofs8153.merge(mint, p) { old, new -> if (new.atMs >= old.atMs) new else old }
        refreshedAt8153.merge(mint, p.atMs) { a, b -> maxOf(a, b) }
        if (proofs8153.size > 2_000) {
            val cutoff = p.atMs - HOT_PROOF_TTL_MS_8153
            proofs8153.entries.removeIf { it.value.atMs < cutoff }
        }
    }

    fun proof8153(mint: String): HeldProof8153? = proofs8153[mint]

    /** A prewarm read was started for [mint] (a failing read is not re-asked every tick). */
    fun noteRefresh8153(mint: String, nowMs: Long) { refreshedAt8153.merge(mint, nowMs) { a, b -> maxOf(a, b) } }

    fun refreshedAt8153(): Map<String, Long> = HashMap(refreshedAt8153)

    /** Drop bookkeeping for mints that are no longer open live positions. */
    fun retain8153(openMints: Set<String>) {
        proofs8153.keys.retainAll(openMints)
        refreshedAt8153.keys.retainAll(openMints)
    }

    // ── lanes ───────────────────────────────────────────────────────────────

    /** Emergency provider reads: never shared with prewarm, entry, enrichment or harvesting work. */
    val emergencyLane8153: ExecutorService = Executors.newCachedThreadPool(java.util.concurrent.ThreadFactory { r ->
        Thread(r, "AATE-StopLatency-EMRG-8153").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1 }
    })

    /** Background prewarm: two min-priority threads, a bounded queue, overflow dropped (the next tick re-picks). */
    val backgroundLane8153: ThreadPoolExecutor = ThreadPoolExecutor(
        2, 2, 30L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(64),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "AATE-StopLatency-prewarm-8153").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } },
        ThreadPoolExecutor.DiscardPolicy(),
    )

    // ── bounded parallel reads ──────────────────────────────────────────────

    data class ParallelRead8153(val held: HeldProof8153?, val answered: Int, val unanswered: Int, val elapsedMs: Long)

    /**
     * Run every reader at once on [lane]; return when all answered, [graceMs] after the first positive answer, or at
     * [budgetMs] — whichever is first. Readers return a positive proof or null (absent / failed: never zero here).
     * The smallest positive answer wins. Unfinished reads are cancelled.
     */
    fun parallelHeld8153(readers: List<() -> HeldProof8153?>, budgetMs: Long = EMERGENCY_READ_BUDGET_MS_8153,
                         graceMs: Long = PARALLEL_GRACE_MS_8153, lane: ExecutorService = emergencyLane8153): ParallelRead8153 {
        val t0 = System.nanoTime()
        if (readers.isEmpty()) return ParallelRead8153(null, 0, 0, 0L)
        val cs = ExecutorCompletionService<HeldProof8153?>(lane)
        val futures = ArrayList<Future<HeldProof8153?>>(readers.size)
        for (r in readers) {
            try { futures.add(cs.submit(Callable { r() })) } catch (_: Throwable) {}
        }
        val budgetDeadline = t0 + budgetMs.coerceAtLeast(1L) * 1_000_000L
        var deadline = budgetDeadline
        val answers = ArrayList<HeldProof8153?>()
        var pending = futures.size
        while (pending > 0) {
            val left = deadline - System.nanoTime()
            if (left <= 0L) break
            val f = try { cs.poll(left, TimeUnit.NANOSECONDS) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); null
            } ?: break
            pending--
            val v = try { f.get() } catch (_: Throwable) { null }
            answers.add(v)
            if (positive8153(v)) deadline = minOf(budgetDeadline, minOf(deadline, System.nanoTime() + graceMs.coerceAtLeast(0L) * 1_000_000L))
        }
        for (f in futures) if (!f.isDone) try { f.cancel(true) } catch (_: Throwable) {}
        val held = minHeld8153(answers)
        return ParallelRead8153(held, answers.count { positive8153(it) }, futures.size - answers.size, (System.nanoTime() - t0) / 1_000_000L)
    }

    data class HoldingAnswer8153(val proof: HeldProof8153?, val route: BalanceRoute8153, val elapsedMs: Long, val readsIssued: Int)

    /**
     * The balance stage of an exit. Non-emergency: LEGACY_READ (the caller keeps its own read). Emergency with a
     * usable proven holding ([proven] returns only usable proofs): answered at once, no read, no corroboration.
     * Otherwise [readers] run in parallel, bounded; a null proof means "keep the legacy read".
     */
    fun holdingForExit8153(emergency: Boolean, proven: () -> HeldProof8153?, readers: List<() -> HeldProof8153?>,
                           budgetMs: Long = EMERGENCY_READ_BUDGET_MS_8153, graceMs: Long = PARALLEL_GRACE_MS_8153): HoldingAnswer8153 {
        val t0 = System.nanoTime()
        if (!emergency) return HoldingAnswer8153(null, BalanceRoute8153.LEGACY_READ, 0L, 0)
        val known = try { proven() } catch (_: Throwable) { null }
        if (positive8153(known)) return HoldingAnswer8153(known, BalanceRoute8153.PROVEN_HOLDING, (System.nanoTime() - t0) / 1_000_000L, 0)
        val r = parallelHeld8153(readers, budgetMs, graceMs)
        return HoldingAnswer8153(r.held, BalanceRoute8153.PARALLEL_READ, (System.nanoTime() - t0) / 1_000_000L, readers.size)
    }

    // ── stage model (a MODEL, not a measurement) ────────────────────────────

    /**
     * Provider / stage latencies for one simulated exit. Used by the stage-timer replay and the healthy-provider model
     * in the tests: it encodes the timeouts the code paths use, not measured device numbers.
     */
    data class StageSample8153(
        val leaseMs: Long, val singleMintMs: Long, val multiProviderMs: Long, val wholeWalletMs: Long,
        val venueRpcMs: Long, val quoteMs: Long, val buildMs: Long, val signMs: Long, val sendMs: Long,
    )

    private const val LEGACY_SINGLE_MINT_TIMEOUT_MS = 2_500L
    private const val LEGACY_WHOLE_WALLET_TIMEOUT_MS = 5_000L
    private const val LEGACY_KNOWN_MINTS_FALLBACK_MS = 6_000L
    private const val LEGACY_PLAN_REREAD_TIMEOUT_MS = 3_000L
    private const val LEGACY_VENUE_BUDGET_MS = 1_500L
    const val EXIT_VENUE_BUDGET_MS_8153 = 700L

    private fun bounded(ms: Long, cap: Long) = ms.coerceIn(0L, cap)

    /** Model: one whole-wallet read with its known-mints fallback (a read past its timeout pays the fallback too). */
    private fun legacyWholeWallet(s: StageSample8153, timeout: Long): Long =
        if (s.wholeWalletMs <= timeout) s.wholeWalletMs else timeout + bounded(s.singleMintMs, LEGACY_KNOWN_MINTS_FALLBACK_MS)

    /** Model: the pre-8153 balance time — liveSell single-mint then whole-wallet, then the planner's read and re-read. */
    fun legacyBalanceMs8153(s: StageSample8153): Long {
        val single = bounded(s.singleMintMs, LEGACY_SINGLE_MINT_TIMEOUT_MS)
        val liveSell = if (s.singleMintMs <= LEGACY_SINGLE_MINT_TIMEOUT_MS) single else single + legacyWholeWallet(s, LEGACY_WHOLE_WALLET_TIMEOUT_MS)
        val planFirst = legacyWholeWallet(s, LEGACY_WHOLE_WALLET_TIMEOUT_MS)
        val planReread = if (s.wholeWalletMs <= LEGACY_WHOLE_WALLET_TIMEOUT_MS) 0L else legacyWholeWallet(s, LEGACY_PLAN_REREAD_TIMEOUT_MS)
        return liveSell + planFirst + planReread
    }

    /** Model: the 8153 balance time — a proven holding costs nothing; otherwise one bounded parallel read, then proven. */
    fun hotBalanceMs8153(s: StageSample8153, proofPrewarmed: Boolean): Long {
        if (balanceRoute8153(true, proofPrewarmed) == BalanceRoute8153.PROVEN_HOLDING) return 0L
        val fast = minOf(s.singleMintMs, s.multiProviderMs)
        val slow = maxOf(s.singleMintMs, s.multiProviderMs)
        if (fast > EMERGENCY_READ_BUDGET_MS_8153) return EMERGENCY_READ_BUDGET_MS_8153 + legacyWholeWallet(s, LEGACY_WHOLE_WALLET_TIMEOUT_MS)
        return minOf(EMERGENCY_READ_BUDGET_MS_8153, minOf(slow, fast + PARALLEL_GRACE_MS_8153))
    }

    fun legacyVenueMs8153(s: StageSample8153, venueFresh: Boolean): Long = if (venueFresh) 0L else bounded(s.venueRpcMs, LEGACY_VENUE_BUDGET_MS)

    /** Model: the 8153 exit venue — a fresh or last-confirmed probe answers at once; only an unknown mint reads (bounded). */
    fun hotVenueMs8153(s: StageSample8153, venueKnown: Boolean): Long = if (venueKnown) 0L else bounded(s.venueRpcMs, EXIT_VENUE_BUDGET_MS_8153)

    /** Model: trigger -> first broadcast. */
    fun triggerToBroadcastMs8153(s: StageSample8153, hot: Boolean, proofPrewarmed: Boolean, venueKnown: Boolean): Long =
        s.leaseMs + (if (hot) hotBalanceMs8153(s, proofPrewarmed) else legacyBalanceMs8153(s)) +
            (if (hot) hotVenueMs8153(s, venueKnown) else legacyVenueMs8153(s, venueKnown)) +
            s.quoteMs + s.buildMs + s.signMs + s.sendMs

    /** Pure: the [q] quantile (0..1) of [values] (nearest rank). */
    fun quantile8153(values: List<Long>, q: Double): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val rank = Math.ceil(q.coerceIn(0.0, 1.0) * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    internal fun resetForTest8153() { proofs8153.clear(); refreshedAt8153.clear() }
}

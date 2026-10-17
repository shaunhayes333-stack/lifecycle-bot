package com.lifecyclebot.engine

import com.lifecyclebot.perps.crypto.CryptoFinalBuyCandidate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8185 §HOLDERS_HAVE_A_FETCHER — a missing-holders deferral fetches holders.
 *
 * LanePlaybook7907 names "holders" UNKNOWN when the coin's last candle carries no holder count and no holder growth, and
 * the data deferral (DataDeferFetch8180 / requestEvidence8153) hands "holders:UNKNOWN" to
 * DataOrchestrator.refreshMissingInputs8087 — which knew no "holders" input and only forced the next price poll. That
 * poll stamps a candle's holder count only from JupiterHolders8128's cache, and nothing had asked Jupiter for the mint,
 * so the next candle carried 0 again. The deferral now asks the existing holder client directly: JupiterHolders8128 (the
 * market sweep's row first, else its paced single-worker /tokens/v2/search ask — one ask per mint per 10 min, one
 * request per 3 s overall, never blocking), and the forced poll that follows stamps the answer on the next candle. No
 * answer stays unknown; nothing is guessed.
 */
object HolderFetch8185 {
    /** The refresher input names that mean "the holder count / holder growth is missing". */
    val HOLDER_INPUTS_8185: Set<String> = setOf("holders", "holder", "holders_count", "holders_growth", "holdergrowth")

    private val requested8185 = AtomicLong(0)
    private val served8185 = AtomicLong(0)
    private val skippedDesk8185 = AtomicLong(0)

    /** Pure: a refresher key set names a holder input. */
    fun holderInput8185(keys: Collection<String>): Boolean = keys.any { it.trim().lowercase() in HOLDER_INPUTS_8185 }

    /**
     * DataOrchestrator.refreshMissingInputs8087: ask the holder client for [mint] (never blocks). Returns the count
     * already known (sweep row / cache), or null while the paced background ask is outstanding or unanswered.
     */
    fun request8185(mint: String, nowMs: Long = System.currentTimeMillis()): Int? {
        if (mint.isBlank() || HandoffRoute8180.deskIdentity8180(mint)) { skippedDesk8185.incrementAndGet(); return null }
        requested8185.incrementAndGet()
        val h = try { com.lifecyclebot.network.JupiterHolders8128.holders8128(mint, nowMs) } catch (_: Throwable) { null }
        if (h != null) served8185.incrementAndGet()
        try { PipelineHealthCollector.labelInc(if (h != null) "HOLDER_FETCH_SERVED_8185" else "HOLDER_FETCH_ASKED_8185") } catch (_: Throwable) {}
        return h
    }

    fun statusLine8185(): String = "holderFetch8185[requested=${requested8185.get()} served=${served8185.get()} deskSkipped=${skippedDesk8185.get()}]"
}

/**
 * V5.0.8185 §HANDOFF_TARGETS_STAY_IN_THE_MAP — a hand-off can reach its receiver's FDG only while the coin's TokenState
 * exists. 5.0.8175: expiredBy8169 TASK_DEFERRED_DATA:DEFERRED_NO_TOKEN_STATE=211 — the memory guard
 * (MemoryGuard7977.pruneTokens8025 / prunePressure8136) protected open positions, admissions and runner tickets, not an
 * open hand-off or receiver task, so the target row could be pruned mid-task and never came back (a pruned row returns
 * only through intake). Two parts:
 *  - protection: a mint with a pending receiver task or an open hand-off is never pruned ([protects8185]); both are
 *    bounded by their own TTLs (45 s / 180 s task, 120 s hand-off), so protection lapses with them;
 *  - re-hydration: a hand-off target already missing from the map is put back on the watchlist through the normal intake
 *    (GlobalTradeRegistry.addToWatchlist — hard rejects and held positions still refuse), so the next watchlist poll
 *    rebuilds its TokenState from real pair data. Bounded and single-flight: once per mint per minute, at most
 *    [REHYDRATE_PER_MIN_8185] a minute, only for an open hand-off target, never a desk identity.
 */
object HandoffRetention8185 {
    const val REHYDRATE_GAP_MS_8185 = 60_000L
    const val REHYDRATE_PER_MIN_8185 = 20
    private const val REHYDRATE_SOURCE_8185 = "HANDOFF_REHYDRATE_8185"

    private val lastAttempt8185 = ConcurrentHashMap<String, Long>()
    private val recent8185 = ArrayDeque<Long>()
    private val protectedHits8185 = AtomicLong(0)
    private val rehydrated8185 = AtomicLong(0)
    private val rehydrateRefused8185 = AtomicLong(0)
    private val rehydrateThrottled8185 = AtomicLong(0)

    /** Pure: an open hand-off or a pending receiver task protects the mint's row from pruning. */
    fun protectedByHandoff8185(taskPending: Boolean, handoffOpen: Boolean): Boolean = taskPending || handoffOpen

    /** Pure: may [mint] be re-hydrated now (per-mint gap, global per-minute budget)? */
    fun rehydrateDue8185(lastAttemptMs: Long?, nowMs: Long, attemptsLastMinute: Int): Boolean =
        (lastAttemptMs == null || nowMs - lastAttemptMs >= REHYDRATE_GAP_MS_8185) && attemptsLastMinute < REHYDRATE_PER_MIN_8185

    /** Is [mint] the target of a pending receiver task or an open hand-off now? */
    private fun openHandoff8185(mint: String, nowMs: Long): Boolean {
        val task = try { HandoffReceiverTasks8153.taskOf8153(mint, nowMs)?.pending == true } catch (_: Throwable) { false }
        val open = try { com.lifecyclebot.engine.truth.AdmissionHandoff8144.handoffOf8144(mint, nowMs)?.open == true } catch (_: Throwable) { false }
        return protectedByHandoff8185(task, open)
    }

    /** MemoryGuard7977: true = keep this row (an open hand-off target). */
    fun protects8185(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val p = openHandoff8185(mint, nowMs)
        if (p) protectedHits8185.incrementAndGet()
        return p
    }

    /** SpecialistOwnership7951.rescueIneligible8136: the receiver's row is missing — re-hydrate it, and name the deferral. */
    fun noTokenState8185(mint: String, nowMs: Long = System.currentTimeMillis()): String {
        rehydrate8185(mint, nowMs)
        return "DEFERRED_NO_TOKEN_STATE"
    }

    /** AdmissionHandoff8144.emitHandoff8144: a hand-off emitted for a mint the map no longer holds. */
    fun afterEmit8185(mint: String, nowMs: Long = System.currentTimeMillis()) {
        if (HandoffRoute8180.tokenFor8180(mint) == null) rehydrate8185(mint, nowMs)
    }

    /** Re-add an open hand-off target to the watchlist through the normal intake. True when the registry took it. */
    fun rehydrate8185(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val key = HandoffRoute8180.memeKey8180(mint.trim())
        if (key.length < 32 || HandoffRoute8180.deskIdentity8180(key)) return false
        if (HandoffRoute8180.tokenFor8180(key) != null) return false
        if (!openHandoff8185(mint, nowMs) && !openHandoff8185(key, nowMs)) return false
        synchronized(this) {
            while (recent8185.isNotEmpty() && nowMs - recent8185.first() > 60_000L) recent8185.removeFirst()
            if (!rehydrateDue8185(lastAttempt8185[key], nowMs, recent8185.size)) { rehydrateThrottled8185.incrementAndGet(); return false }
            lastAttempt8185[key] = nowMs
            recent8185.addLast(nowMs)
            if (lastAttempt8185.size > 2_000) lastAttempt8185.entries.removeIf { nowMs - it.value > REHYDRATE_GAP_MS_8185 }
        }
        val res = try { GlobalTradeRegistry.addToWatchlist(key, key.take(6), REHYDRATE_SOURCE_8185) } catch (_: Throwable) { null }
        val ok = res != null && (res.added || res.reason.startsWith("DUPLICATE"))
        if (ok) rehydrated8185.incrementAndGet() else rehydrateRefused8185.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc(if (ok) "HANDOFF_TARGET_REHYDRATED_8185" else "HANDOFF_TARGET_REHYDRATE_REFUSED_8185")
            ForensicLogger.lifecycle("HANDOFF_TARGET_REHYDRATE_8185", "mint=${key.take(10)} ok=$ok reason=${res?.reason?.take(60) ?: "ERROR"}")
        } catch (_: Throwable) {}
        return ok
    }

    fun counts8185(): Map<String, Long> = mapOf(
        "protected" to protectedHits8185.get(), "rehydrated" to rehydrated8185.get(),
        "refused" to rehydrateRefused8185.get(), "throttled" to rehydrateThrottled8185.get(),
    )

    internal fun resetForTests8185() {
        synchronized(this) { recent8185.clear(); lastAttempt8185.clear() }
        protectedHits8185.set(0); rehydrated8185.set(0); rehydrateRefused8185.set(0); rehydrateThrottled8185.set(0)
    }
}

/**
 * V5.0.8185 — the handoff8144 status line shows what 8180 changed. 8180 counted DESK_IDENTITY_SKIPPED_8180 and
 * WITHHELD_NO_LANE_FITS_8180 but the line printed neither, and nothing counted a receiver chosen by floors (the named lane
 * did not fit; the other runner lane did), so a device log could not show whether the 8180 routing ran.
 */
object HandoffStatus8185 {
    const val DESK_SKIPPED = "DESK_IDENTITY_SKIPPED_8180"
    const val WITHHELD = "WITHHELD_NO_LANE_FITS_8180"
    const val BY_FLOORS = "RECEIVER_BY_FLOORS_8185"
    const val AS_NAMED = "RECEIVER_AS_NAMED_8185"

    /** Pure: the counter a route outcome adds ([named0] the sender's name, [routed] the route's name). */
    fun routeCounter8185(named0: String?, routed: String?): String =
        if (named0.orEmpty().trim().uppercase() != routed.orEmpty().trim().uppercase()) BY_FLOORS else AS_NAMED

    /** Pure: "route8180[...] rehydrate8185[...]" from the hand-off ledger's counters and the retention counters. */
    fun line8185(counts: Map<String, Long>, retention: Map<String, Long>): String {
        val c = { k: String -> counts[k] ?: 0L }
        val r = { k: String -> retention[k] ?: 0L }
        return "route8180[deskSkipped=${c(DESK_SKIPPED)} withheldNoLaneFits=${c(WITHHELD)} receiverByFloors=${c(BY_FLOORS)} " +
            "receiverAsNamed=${c(AS_NAMED)}] rehydrate8185[protected=${r("protected")} rehydrated=${r("rehydrated")} " +
            "refused=${r("refused")} throttled=${r("throttled")}] "
    }
}

/**
 * V5.0.8185 — the crypto-alt pre-FDG refusal names its true cause. passesCryptoDiscipline6647 returns
 * candidate.canEnterFdg after its discipline checks (only the rug blacklist refuses there; learned discipline only shapes),
 * so a WATCH-score candidate (score below the entry bar) was refused as CRYPTO_DISCIPLINE_REFUSED_7835
 * (5.0.8175: CANONICAL_BLOCKED_..._CRYPTO_DISCIPLINE_REFUSED_7835 x34). Same refusal, same trades; the true name.
 */
object CryptoPreFdgReason8185 {
    const val WATCH_8185 = "CRYPTO_WATCH_SCORE_BELOW_ENTRY_8185"
    const val NO_BUY_8185 = "CRYPTO_PREFDG_NO_BUY_8185"
    const val HARD_NO_8185 = "CRYPTO_PREFDG_HARD_NO_BUY_8185"
    const val DISCIPLINE_7835 = "CRYPTO_DISCIPLINE_REFUSED_7835"

    /** Pure: the refusal label (null = passed). Refuses exactly when [passed] is false. */
    fun reason8185(passed: Boolean, verdict: CryptoFinalBuyCandidate.PreFdgVerdict, hardNoReasons: List<String>): String? {
        if (passed) return null
        return when {
            verdict == CryptoFinalBuyCandidate.PreFdgVerdict.HARD_NO_BUY || hardNoReasons.isNotEmpty() -> HARD_NO_8185
            verdict == CryptoFinalBuyCandidate.PreFdgVerdict.WATCH -> WATCH_8185
            verdict == CryptoFinalBuyCandidate.PreFdgVerdict.NO_BUY -> NO_BUY_8185
            else -> DISCIPLINE_7835
        }
    }

    fun reasonFor8185(passed: Boolean, candidate: CryptoFinalBuyCandidate): String? =
        reason8185(passed, candidate.preFdgVerdict, candidate.hardNoReasons)
}

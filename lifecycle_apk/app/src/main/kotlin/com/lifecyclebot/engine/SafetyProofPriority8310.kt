package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8310 §THE_PROOF_THAT_NEVER_REFRESHED.
 *
 * Live 5.0.8306: SHITCOIN ticket -> exec 9 %, and 9 of 10 dropped tickets were safety-proof deferrals. The proof that
 * stayed pending was the safety REPORT's freshness, not a missing authority read:
 *
 *  - PreTradeHardGate (8269) needs `ts.safety.checkedAt` within SAFETY_FRESH_MS (120 s) at the buy door, and on a stale
 *    report defers (`LIVE_SAFETY_REPORT_NOT_FRESH_8269`) and queues SafetyRefreshQueue.
 *  - The cycle's "refresh" (BotService, explicit queue -> synchronous) calls TokenSafetyChecker.check, which returns its
 *    CACHED report for 10 minutes — with the cached report's OLD checkedAt — while BotService stamps
 *    `ts.lastSafetyCheck = now`. So the FDG (reads lastSafetyCheck) saw a fresh report and sealed the ticket, and the buy
 *    door (reads checkedAt) saw the same report 2..10 minutes old and deferred it, every time, until the cache aged out.
 *    A deferral returns false from the lane buy, which ends the ticket (LIVE_BUY_NOT_OPENED_7948:SAFETY_PROOF_*).
 *
 * Fix (no proof skipped, no threshold moved): a caller that needs a current proof asks the checker for a report no older
 * than [LIVE_PROOF_MAX_AGE_MS_8310] (`check(maxCacheAgeMs = ...)`), which re-runs RugCheck + the authority reads instead of
 * replaying the cache. That is done (a) on the explicit refresh a deferred live buy queued, and (b) ahead of the buy for a
 * live prospect (a candidate at the FDG door, AdmissionProspect8136, or a confirmed-run ticket) whose report is already
 * older than the window — in the safety step that runs BEFORE the FDG, so the sealed ticket meets a fresh proof. A mint
 * whose buy deferred on proof also rides the next cycle's priority window ([targets8310]). The buy door's checks are
 * unchanged: a coin without the proof is still never bought.
 */
object SafetyProofPriority8310 {
    /** Under PreTradeHardGate's 120 s freshness, with room for the check's own latency and the next tick. */
    const val LIVE_PROOF_MAX_AGE_MS_8310 = 90_000L
    private const val FORCE_MEMO_MS_8310 = 30_000L
    private const val DEFER_PRIORITY_MS_8310 = 120_000L
    private const val CAP_8310 = 2_000

    private val forcedAt8310 = ConcurrentHashMap<String, Long>()
    private val deferredAt8310 = ConcurrentHashMap<String, Long>()
    private val forcedExplicit8310 = AtomicLong(0)
    private val forcedProspect8310 = AtomicLong(0)
    private val deferrals8310 = AtomicLong(0)

    /** Pure: may a cached report checked at [checkedAtMs] serve a caller that accepts at most [maxAgeMs]? */
    fun cacheUsable8310(checkedAtMs: Long, maxAgeMs: Long, nowMs: Long): Boolean =
        maxAgeMs == Long.MAX_VALUE || (checkedAtMs > 0L && nowMs - checkedAtMs in 0L..maxAgeMs)

    /**
     * Pure: must the cycle's safety step run a current (uncached) proof? An explicit refresh always does; a live prospect
     * does once its report is older than [LIVE_PROOF_MAX_AGE_MS_8310] (at most once per [FORCE_MEMO_MS_8310]).
     */
    fun needsFresh8310(live: Boolean, explicitRefresh: Boolean, prospect: Boolean, reportAgeMs: Long, sinceForcedMs: Long): Boolean {
        if (!live) return false
        if (explicitRefresh) return true
        return prospect && reportAgeMs > LIVE_PROOF_MAX_AGE_MS_8310 && sinceForcedMs >= FORCE_MEMO_MS_8310
    }

    private fun live8310(): Boolean = try { !RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false }

    private fun prospect8310(mint: String, nowMs: Long): Boolean =
        (try { com.lifecyclebot.engine.truth.AdmissionProspect8136.isActive8136(mint, nowMs) } catch (_: Throwable) { false }) ||
            (try { RunnerPlay8018.ticketActive8018(mint, nowMs) } catch (_: Throwable) { false }) ||
            deferredAt8310[mint]?.let { nowMs - it in 0L..DEFER_PRIORITY_MS_8310 } == true

    /** BotService safety step: run a current proof for [mint] now (true), with the checker's cache capped at the window. */
    fun forceFresh8310(mint: String, explicitRefresh: Boolean, reportCheckedAtMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (mint.isBlank()) return false
        val age = if (reportCheckedAtMs > 0L) nowMs - reportCheckedAtMs else Long.MAX_VALUE
        val since = forcedAt8310[mint]?.let { nowMs - it } ?: Long.MAX_VALUE
        val live = live8310()
        val prospect = !explicitRefresh && live && prospect8310(mint, nowMs)
        if (!needsFresh8310(live, explicitRefresh, prospect, age, since)) return false
        if (forcedAt8310.size > CAP_8310) forcedAt8310.entries.removeIf { nowMs - it.value > FORCE_MEMO_MS_8310 }
        forcedAt8310[mint] = nowMs
        if (explicitRefresh) forcedExplicit8310.incrementAndGet() else forcedProspect8310.incrementAndGet()
        try { PipelineHealthCollector.labelInc(if (explicitRefresh) "SAFETY_PROOF_FRESH_EXPLICIT_8310" else "SAFETY_PROOF_FRESH_PROSPECT_8310") } catch (_: Throwable) {}
        return true
    }

    /**
     * TokenSafetyChecker's mint read: Helius-first (the paid plan's single getAccountInfo jsonParsed on the mint) for a live
     * mint whose current proof was just forced or whose buy deferred on proof; the public-first scan ladder otherwise.
     */
    fun heliusFirst8310(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (mint.isBlank() || !live8310()) return false
        return forcedAt8310[mint]?.let { nowMs - it in 0L..FORCE_MEMO_MS_8310 } == true ||
            deferredAt8310[mint]?.let { nowMs - it in 0L..DEFER_PRIORITY_MS_8310 } == true
    }

    /** The checker's cache cap for this cycle's run. */
    fun maxCacheAge8310(forced: Boolean): Long = if (forced) LIVE_PROOF_MAX_AGE_MS_8310 else Long.MAX_VALUE

    /** PreTradeHardGate: a live buy deferred on proof — its mint gets a current proof and a cycle slot next. */
    fun noteDeferred8310(mint: String, reason: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        deferrals8310.incrementAndGet()
        if (deferredAt8310.size > CAP_8310) deferredAt8310.entries.removeIf { nowMs - it.value > DEFER_PRIORITY_MS_8310 }
        deferredAt8310[mint] = nowMs
        try { PipelineHealthCollector.labelInc("SAFETY_PROOF_DEFER_8310_${reason.take(48)}") } catch (_: Throwable) {}
    }

    /** HandoffThroughput8190: mints whose buy deferred on proof in the last [DEFER_PRIORITY_MS_8310] (mint -> deadline). */
    fun targets8310(nowMs: Long = System.currentTimeMillis()): List<Pair<String, Long>> =
        deferredAt8310.entries.filter { nowMs - it.value in 0L..DEFER_PRIORITY_MS_8310 }
            .sortedBy { it.value }.take(8).map { it.key to it.value + DEFER_PRIORITY_MS_8310 }

    fun statusLine8310(): String =
        "safetyProof8310[forcedExplicit=${forcedExplicit8310.get()} forcedProspect=${forcedProspect8310.get()} deferrals=${deferrals8310.get()} " +
            "window=${LIVE_PROOF_MAX_AGE_MS_8310 / 1000}s]"
}

package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8948 §A_PROVEN_COIN_WAITS_WITH_ALL_ITS_PROOFS_ASKED
 *
 * Owner, 22 Nov 23:21: "still badly choked ... what happened to supplying the lanes with the right tokens and right trades".
 * The 23:03 capture (8947, LIVE, 31 min): coins that carried proven market-cell evidence reached the admission and stopped
 * there on data. LANE_POCKET 49 candidate episodes, all 49 DEFER_DATA (waits: PRICE_STALE 26, buy-door critical proof 19,
 * safety report not fresh 4), 0 admitted; paying-cell coins DEFER_DATA 142 against ADMIT 7. The last such trace: an EXPRESS
 * coin in the lane pocket "origin=OTHER&stage=BASE_START n188 +26.5 %", net +26.7 % after cost, deferred on a price older
 * than the 30 s live bar with a safety report 167 s old (the door's bar is 120 s). Each proof is re-asked on its own
 * schedule: the safety refresh runs only when the cycle reaches the coin again, the price on the mark lane's, so a coin
 * meets one proof fresh and the other stale, defers again, and the lane's qualification lapses before both line up. On the
 * owner's research rows the same pocket (off pump.fun, BASE_START, pool $5k-$100k) was 222 coins in the 24 h to 22:48 at
 * +32.0 % mean / +19.8 % median an hour on (74 coins in the last 6 h at +24.3 % / +15.8 %); the bot bought none.
 *
 * Owner, 23:31: "we are deferring way too many trades when we could be just confirming the required information on chain
 * with a direct request". Now, when a LIVE coin carrying market-cell evidence defers on data, its proofs are requested
 * directly, at once, instead of waiting for the cycle to come round: an explicit safety refresh (SafetyRefreshQueue: the
 * next pass runs TokenSafetyChecker with no cache — RugCheck plus the chain's own mint / freeze authority, holder and LP
 * reads) and the coin's own full pipeline pass run now ([direct8948] -> BotService.fastLaneEvaluate7277: two passes 1.5 s
 * apart, the second meeting the fresh price), a fresh price (CandidateMarkFresh8094) and the mint authority read
 * (AuthorityProofPrefetch8136). A direct request goes at most once per [DIRECT_GAP_MS_8948] per coin. Between them the
 * coin keeps a cycle slot (SafetyProofPriority8310.noteDeferred8310 and [targets8948] through HandoffPriority8190) for up
 * to [WINDOW_MS_8948] until the admission decides it. Nothing is admitted here: no gate, bar, threshold or proof changes; the same admission, door and FDG decide.
 * Each wait is filed by how it ended (admitted, refused and why, still missing when the window closed) on [statusLine8948].
 */
object EvidenceWait8948 {
    const val WINDOW_MS_8948 = 120_000L
    const val DIRECT_GAP_MS_8948 = 20_000L

    /** BotService: run [mint]'s full pipeline pass now (the fast lane), keyed by [slot] so one request runs once. */
    @Volatile var direct8948: ((mint: String, slot: Long) -> Unit)? = null
    private const val CAP_8948 = 256
    private const val MAX_REASONS_8948 = 24

    private class Wait8948(val firstMs: Long) {
        @Volatile var causes: String = ""
        @Volatile var directMs: Long = 0L
    }

    private val waits = ConcurrentHashMap<String, Wait8948>()
    private val noted = AtomicLong(0L)
    private val asked = AtomicLong(0L)
    private val directs = AtomicLong(0L)
    private val admitted = AtomicLong(0L)
    private val waitMsAdmitted = AtomicLong(0L)
    private val refused = ConcurrentHashMap<String, AtomicLong>()
    private val expired = ConcurrentHashMap<String, AtomicLong>()
    private val kinds = ConcurrentHashMap<String, AtomicLong>()

    /** Pure: does a wait first seen at [firstMs] still ride at [nowMs]? */
    internal fun riding8948(firstMs: Long, nowMs: Long): Boolean = nowMs - firstMs in 0L..WINDOW_MS_8948

    /**
     * CandidateAdmission8136 (LIVE, a candidate carrying market-cell evidence of [kind]): its admission ended [terminal]
     * ([reason]); a DEFER_DATA files the wait and asks every live proof; any other terminal closes the coin's wait.
     */
    fun note8948(ts: TokenState, kind: String, terminal: String, reason: String, causes: List<String>, nowMs: Long = System.currentTimeMillis()) {
        val mint = ts.mint
        if (mint.isBlank() || kind.isBlank()) return
        sweep8948(nowMs)
        if (terminal != "DEFER_DATA") { close8948(mint, terminal, reason, nowMs); return }
        val w = waits[mint] ?: Wait8948(nowMs).also {
            if (waits.size >= CAP_8948) return
            waits[mint] = it
            noted.incrementAndGet()
            count8948(kinds, kind.take(24))
        }
        if (!riding8948(w.firstMs, nowMs)) return
        w.causes = causes.joinToString("+").take(48).ifBlank { "UNNAMED" }
        ask8948(ts, nowMs)
        try { ChainConfirm8950.confirm8950(ts, causes, "EVIDENCE", nowMs) } catch (_: Throwable) {}   // V5.0.8950 — each named input on chain
        if (direct8948(w.directMs, nowMs)) {
            w.directMs = nowMs
            directs.incrementAndGet()
            try { com.lifecyclebot.engine.SafetyRefreshQueue.request(mint) } catch (_: Throwable) {}
            try { direct8948?.invoke(mint, nowMs / DIRECT_GAP_MS_8948) } catch (_: Throwable) {}
        }
    }

    /** Pure: may a direct request go now, the last one at [lastMs] (0 = none yet)? */
    internal fun direct8948(lastMs: Long, nowMs: Long): Boolean = lastMs <= 0L || nowMs - lastMs >= DIRECT_GAP_MS_8948

    /**
     * Every live proof of [ts] at once: a cycle slot and a current safety report once the held one is past the 90 s window
     * (SafetyProofPriority8310's prospect rule, at most one forced report per 30 s, so RugCheck is not asked every pass),
     * a fresh price, the authority read.
     */
    private fun ask8948(ts: TokenState, nowMs: Long) {
        asked.incrementAndGet()
        try { com.lifecyclebot.engine.SafetyProofPriority8310.noteDeferred8310(ts.mint, "EVIDENCE_WAIT_8948", nowMs) } catch (_: Throwable) {}
        try { CandidateMarkFresh8094.ensureFresh8094(ts, nowMs) } catch (_: Throwable) {}
        try { AuthorityProofPrefetch8136.prefetchIfLive8136(ts.mint) } catch (_: Throwable) {}
    }

    private fun close8948(mint: String, terminal: String, reason: String, nowMs: Long) {
        val w = waits.remove(mint) ?: return
        if (terminal == "ADMIT") {
            admitted.incrementAndGet()
            waitMsAdmitted.addAndGet((nowMs - w.firstMs).coerceAtLeast(0L))
            try { PipelineHealthCollector.labelInc("EVIDENCE_WAIT_8948_ADMITTED") } catch (_: Throwable) {}
        } else count8948(refused, "$terminal:${reason.take(40)}")
    }

    private fun sweep8948(nowMs: Long) {
        val it = waits.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (riding8948(e.value.firstMs, nowMs)) continue
            it.remove()
            count8948(expired, e.value.causes.ifBlank { "UNNAMED" })
        }
    }

    private fun count8948(m: ConcurrentHashMap<String, AtomicLong>, k: String) {
        if (m.size < MAX_REASONS_8948 || m.containsKey(k)) m.computeIfAbsent(k) { AtomicLong(0L) }.incrementAndGet()
    }

    /** HandoffPriority8190: the coins waiting now (mint -> the end of their window), so each rides the next cycles. */
    fun targets8948(nowMs: Long = System.currentTimeMillis()): List<Pair<String, Long>> =
        waits.entries.filter { riding8948(it.value.firstMs, nowMs) }.map { it.key to it.value.firstMs + WINDOW_MS_8948 }

    /** Brief: waits filed, proofs asked, how the waits ended. */
    fun statusLine8948(nowMs: Long = System.currentTimeMillis()): String {
        sweep8948(nowMs)
        fun top(m: ConcurrentHashMap<String, AtomicLong>) =
            m.entries.sortedByDescending { it.value.get() }.take(6).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        val a = admitted.get()
        return "evidenceWait8948[waiting=${waits.size} noted=${noted.get()} asked=${asked.get()} direct=${directs.get()} kinds{${top(kinds)}} admitted=$a" +
            "${if (a > 0L) " meanWaitS=${waitMsAdmitted.get() / a / 1000L}" else ""} refused{${top(refused)}} expired{${top(expired)}} " +
            "rule=a LIVE coin with market-cell evidence that defers on data has its proofs requested directly (fresh safety report with the " +
            "chain reads, its own pipeline pass now and 1.5 s on, price, authority; at most every ${DIRECT_GAP_MS_8948 / 1000}s) and keeps a cycle " +
            "slot up to ${WINDOW_MS_8948 / 1000}s until the admission decides it; nothing admitted here, every gate unchanged]"
    }

    internal fun resetForTest8948() {
        waits.clear(); noted.set(0L); asked.set(0L); directs.set(0L); admitted.set(0L); waitMsAdmitted.set(0L)
        refused.clear(); expired.clear(); kinds.clear()
    }
}

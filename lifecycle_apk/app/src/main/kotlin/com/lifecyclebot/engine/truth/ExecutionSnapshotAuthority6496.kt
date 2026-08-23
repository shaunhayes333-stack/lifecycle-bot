package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6496 §4 — EXECUTION SNAPSHOT AUTHORITY.
 *
 * OPERATOR MANDATE (verbatim, 6495 evidence):
 *
 *   "Look at the failed-open path:
 *      PAPER_BUY_NOT_OPENED: 1827
 *      ROUTE_FAILED: 1539
 *      BUY_NON_TERMINAL_RELEASE: 1537
 *      EXEC_OPEN_PRECHECK_SIZE_PENDING: 399
 *      EXEC_OPEN_DROPPED_TOKEN_STATE_CHANGED: 293
 *      EXEC_OPEN_DROPPED_PRE_FDG_NOT_BUY: 465
 *
 *    That is a lot of lifecycle work for very few committed entries.
 *    Some of this is deliberate suppression, but there's still a
 *    routing inconsistency visible in your Orangie example:
 *      primary=PROJECT_SNIPER
 *    then execution ticket:
 *      lane=STANDARD preFdg=WATCH
 *    then EXEC_OPEN_DROPPED_PRE_FDG_NOT_BUY.
 *
 *    The candidate changed ownership/semantic lane between evaluation
 *    and execution. It correctly refused the entry, but it shouldn't
 *    be producing the ticket in the first place.
 *
 *    I'd enforce: candidateVersion + primaryLane + preFdgVerdict +
 *    authorityVersion as one immutable execution snapshot."
 *
 * DESIGN
 * ──────
 * `record(mint, snap)` is called at the FDG-allow site — the moment
 * a candidate is deemed executable. The 4-tuple is sealed for the
 * mint until (a) it is consumed via `consumeIfMatches` or (b) it
 * expires after `SNAPSHOT_TTL_MS`.
 *
 * At ticket-creation site the caller invokes `matches(mint, snap)`
 * with the tuple it CURRENTLY sees. If they diverge:
 *   • label `EXEC_SNAPSHOT_DRIFT_6496` fires (visible in root cause)
 *   • ticket creation MUST be refused UPSTREAM (no
 *     EXEC_OPEN_DROPPED_PRE_FDG_NOT_BUY row minted)
 *
 * The stored snapshot never mutates. If FDG re-evaluates and issues
 * a new allow, the new snapshot fully replaces the prior one.
 *
 * TTL exists so a legitimately re-evaluated candidate on a later
 * tick is not blocked by a stale seal from 30 s ago.
 */
object ExecutionSnapshotAuthority6496 {

    data class Snapshot(
        val candidateVersion: Long,
        val primaryLane: String,
        val preFdgVerdict: String,
        val authorityVersion: Long,
        val recordedAtMs: Long,
    )

    private val sealed = ConcurrentHashMap<String, Snapshot>()
    private val records = AtomicLong(0L)
    private val matches = AtomicLong(0L)
    private val drifts = AtomicLong(0L)

    private const val SNAPSHOT_TTL_MS = 15_000L

    /** Called by FDG-allow. Seals the executable tuple for the mint. */
    fun record(
        mint: String,
        candidateVersion: Long,
        primaryLane: String,
        preFdgVerdict: String,
        authorityVersion: Long,
    ) {
        if (mint.isBlank()) return
        val snap = Snapshot(
            candidateVersion = candidateVersion,
            primaryLane = primaryLane.uppercase(),
            preFdgVerdict = preFdgVerdict.uppercase(),
            authorityVersion = authorityVersion,
            recordedAtMs = System.currentTimeMillis(),
        )
        sealed[mint] = snap
        records.incrementAndGet()
        try { PipelineHealthCollector.labelInc("EXEC_SNAPSHOT_RECORDED_6496") } catch (_: Throwable) {}
    }

    /**
     * Ticket-creation callers pass their currently-observed tuple.
     * Returns null on match (proceed with ticket). Returns a
     * human-readable drift reason otherwise; caller MUST refuse
     * ticket creation upstream and log `EXEC_SNAPSHOT_DRIFT_6496`.
     *
     * A stale (>TTL) or missing snapshot is treated as "no seal ⇒
     * allow" so we do not accidentally block cold-start candidates.
     * The whole point is to catch drift *during a live seal*, not
     * to enforce a seal exists everywhere.
     */
    fun matchOrDriftReason(
        mint: String,
        candidateVersion: Long,
        primaryLane: String,
        preFdgVerdict: String,
        authorityVersion: Long,
    ): String? {
        val snap = sealed[mint] ?: return null
        if (System.currentTimeMillis() - snap.recordedAtMs > SNAPSHOT_TTL_MS) {
            sealed.remove(mint, snap)
            return null
        }
        val pLane = primaryLane.uppercase()
        val pVerdict = preFdgVerdict.uppercase()
        val driftBits = buildList {
            if (snap.candidateVersion != candidateVersion)
                add("candidateVersion(${snap.candidateVersion}->$candidateVersion)")
            if (snap.primaryLane != pLane)
                add("primaryLane(${snap.primaryLane}->$pLane)")
            if (snap.preFdgVerdict != pVerdict)
                add("preFdgVerdict(${snap.preFdgVerdict}->$pVerdict)")
            if (snap.authorityVersion != authorityVersion)
                add("authorityVersion(${snap.authorityVersion}->$authorityVersion)")
        }
        return if (driftBits.isEmpty()) {
            matches.incrementAndGet()
            null
        } else {
            drifts.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "EXEC_SNAPSHOT_DRIFT_6496",
                    "mint=${mint.take(10)} drift=${driftBits.joinToString("+")}",
                )
                PipelineHealthCollector.labelInc("EXEC_SNAPSHOT_DRIFT_6496")
            } catch (_: Throwable) {}
            driftBits.joinToString("+")
        }
    }

    /**
     * Consume the seal on ticket commit so a later tick re-evaluates
     * fresh. Idempotent.
     */
    fun consume(mint: String) {
        if (mint.isBlank()) return
        sealed.remove(mint)
    }

    fun statusLine(): String =
        "sealed=${sealed.size} records=${records.get()} matches=${matches.get()} drifts=${drifts.get()}"

    internal fun resetForTest() {
        sealed.clear()
        records.set(0L); matches.set(0L); drifts.set(0L)
    }
}
